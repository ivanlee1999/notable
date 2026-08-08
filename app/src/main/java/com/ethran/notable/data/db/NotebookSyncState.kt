package com.ethran.notable.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import java.util.Date
import javax.inject.Inject

/**
 * Per-notebook sync bookkeeping, written only at commit points (a successful upload or download).
 *
 * Deliberately has **no** foreign key to [Notebook]: the row must outlive local deletion of the
 * notebook so the next sync can still detect "was synced, now gone" and propagate a tombstone. It
 * is the single source of truth for badges, deletion detection, and change detection —
 * the richer replacement for the old `SyncSettings.syncedNotebookIds` set.
 */
@Entity(tableName = "notebook_sync_state")
data class NotebookSyncState(
    @PrimaryKey val notebookId: String,
    /** [SyncStateValue.SYNCED] or [SyncStateValue.ERROR]. */
    val state: String,
    /** Wall-clock time of the last committed sync. Informational (log/UI); never compared. */
    val lastSyncedAt: Date,
    /**
     * The **change anchor**: the value `Notebook.updatedAt` had at that commit. Compared against the
     * notebook's current `updatedAt` to answer "edited since we last synced?" — both sides are the
     * same local edit clock, so equality means unchanged.
     */
    val syncedLocalUpdatedAt: Date,
    /** The manifest's server ETag, verbatim — see [PageSyncState.remoteEtag] for how to read it. */
    val remoteEtag: String? = null,
    val remoteUpdatedAt: Date? = null,
    val lastError: String? = null,
    /**
     * The notebook **directory** ETag observed at the last converged sync — the value the bulk
     * change-detection fast path compares a fresh listing against to skip the per-notebook manifest
     * GET. Owned exclusively by [NotebookSyncStateRepository.updateRemoteDirBaseline]; every other
     * writer builds a fresh row and so resets this to null, which is correct because a non-`SYNCED`
     * state must never carry a consumable baseline.
     */
    val remoteDirEtag: String? = null,
    /**
     * The server identity ([ServerCapabilities.serverKey]) the [remoteDirEtag] was observed on. A
     * baseline is consumed only when this matches the current server, so an opaque ETag left over
     * from a previously configured server can never suppress a read on a different one.
     */
    val remoteDirServerKey: String? = null,
)

object SyncStateValue {
    const val SYNCED = "SYNCED"
    const val ERROR = "ERROR"

    /** Remote is newer than local, but it was not pulled (upload-only mode). */
    const val REMOTE_AHEAD = "REMOTE_AHEAD"

    /**
     * At least one page was edited both locally and on the server since the last common sync, and the
     * conflict strategy is ASK. Nothing was overwritten; the user must resolve it. Re-detected each
     * sync until resolved, so this is safe to lose (recomputed from ETags + local dirtiness).
     */
    const val CONFLICT = "CONFLICT"
}

@Dao
interface NotebookSyncStateDao {
    @Query("SELECT * FROM notebook_sync_state WHERE notebookId = :id")
    suspend fun get(id: String): NotebookSyncState?

    @Query("SELECT notebookId FROM notebook_sync_state")
    suspend fun getAllIds(): List<String>

    @Query("SELECT * FROM notebook_sync_state")
    fun getAllFlow(): Flow<List<NotebookSyncState>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: NotebookSyncState)

    @Query("DELETE FROM notebook_sync_state WHERE notebookId = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM notebook_sync_state")
    suspend fun deleteAll()

    /**
     * Update only the directory-baseline columns for one row. A targeted `UPDATE` (not a full-row
     * upsert) so a post-commit network read can never clobber other fields of a fresher sync-state
     * row. A no-op when no row exists for [id] or the row is no longer `SYNCED`, preserving the
     * invariant even if a future caller attempts to store a stale post-transfer observation.
     */
    @Query(
        "UPDATE notebook_sync_state SET remoteDirEtag = :etag, remoteDirServerKey = :serverKey " +
            "WHERE notebookId = :id AND state = 'SYNCED'"
    )
    suspend fun updateRemoteDirBaseline(id: String, etag: String?, serverKey: String)
}

class NotebookSyncStateRepository @Inject constructor(
    private val dao: NotebookSyncStateDao
) {
    suspend fun get(id: String): NotebookSyncState? = dao.get(id)
    suspend fun getAllIds(): Set<String> = dao.getAllIds().toSet()
    fun getAllFlow(): Flow<List<NotebookSyncState>> = dao.getAllFlow()
    suspend fun upsert(state: NotebookSyncState) = dao.upsert(state)
    suspend fun delete(id: String) = dao.delete(id)
    suspend fun deleteAll() = dao.deleteAll()

    /**
     * Record the directory ETag observed for [notebookId] at a converged sync, tagged with the
     * [serverKey] it was seen on. The single owner of the two baseline columns; see the field docs on
     * [NotebookSyncState.remoteDirEtag]. Called after the commit that established convergence, so the
     * preceding `markSynced` (which resets these columns) has already run.
     */
    suspend fun updateRemoteDirBaseline(notebookId: String, etag: String?, serverKey: String) =
        dao.updateRemoteDirBaseline(notebookId, etag, serverKey)

    /** Record a notebook as successfully synced at [localUpdatedAt] / [remoteUpdatedAt]. */
    suspend fun markSynced(
        notebookId: String,
        localUpdatedAt: Date,
        remoteUpdatedAt: Date?,
        remoteEtag: String?,
    ) = dao.upsert(
        NotebookSyncState(
            notebookId = notebookId,
            state = SyncStateValue.SYNCED,
            lastSyncedAt = Date(),
            syncedLocalUpdatedAt = localUpdatedAt,
            remoteUpdatedAt = remoteUpdatedAt,
            remoteEtag = remoteEtag,
        )
    )

    /**
     * Record that the server copy is newer than local but was intentionally not pulled (upload-only
     * mode). Anchored at the current [localUpdatedAt] so a later local edit flips the badge to
     * "pending upload" rather than staying "remote ahead".
     */
    suspend fun markRemoteAhead(
        notebookId: String,
        localUpdatedAt: Date,
        remoteUpdatedAt: Date?,
        remoteEtag: String?,
    ) = dao.upsert(
        NotebookSyncState(
            notebookId = notebookId,
            state = SyncStateValue.REMOTE_AHEAD,
            lastSyncedAt = Date(),
            syncedLocalUpdatedAt = localUpdatedAt,
            remoteUpdatedAt = remoteUpdatedAt,
            remoteEtag = remoteEtag,
        )
    )

    /**
     * Record that a notebook's last sync attempt failed, so the library shows the ERROR badge.
     * Preserves the previous sync anchor ([syncedLocalUpdatedAt], ETag, remote timestamp) if a row
     * already existed, so a later successful sync still knows what was last committed. If no row
     * existed, an epoch-0 anchor is used — the ERROR state is checked before the anchor, so the
     * badge is correct either way.
     */
    suspend fun markError(notebookId: String, message: String?) {
        val existing = dao.get(notebookId)
        dao.upsert(
            NotebookSyncState(
                notebookId = notebookId,
                state = SyncStateValue.ERROR,
                lastSyncedAt = Date(),
                syncedLocalUpdatedAt = existing?.syncedLocalUpdatedAt ?: Date(0),
                remoteUpdatedAt = existing?.remoteUpdatedAt,
                remoteEtag = existing?.remoteEtag,
                lastError = message,
            )
        )
    }

    /**
     * Record that a notebook has an unresolved page conflict (ASK strategy). Non-destructive:
     * preserves the previous anchor and — crucially — the previous manifest [remoteEtag], so the next
     * sync's conditional GET still sees the remote as changed and re-enters reconciliation to
     * re-detect (or clear) the conflict, rather than reading it as "in sync". Nothing here overwrites
     * page content; the divergence stays on disk until the user resolves it.
     */
    suspend fun markConflict(notebookId: String) {
        val existing = dao.get(notebookId)
        dao.upsert(
            NotebookSyncState(
                notebookId = notebookId,
                state = SyncStateValue.CONFLICT,
                lastSyncedAt = Date(),
                syncedLocalUpdatedAt = existing?.syncedLocalUpdatedAt ?: Date(0),
                remoteUpdatedAt = existing?.remoteUpdatedAt,
                remoteEtag = existing?.remoteEtag,
            )
        )
    }

    /**
     * Adopt [remoteEtag] as the manifest base while KEEPING the previous change anchor, clearing the
     * CONFLICT state. Because the anchor is not advanced, a still-newer local copy stays dirty and is
     * uploaded next sync (winning the now-current guard). Used to force the local version after a
     * user resolves a structural conflict in favor of local. A no-op if no row exists.
     */
    suspend fun rebaselineToRemoteEtag(notebookId: String, remoteEtag: String?) {
        val existing = dao.get(notebookId) ?: return
        dao.upsert(
            existing.copy(
                state = SyncStateValue.SYNCED,
                lastSyncedAt = Date(),
                remoteEtag = remoteEtag,
            )
        )
    }
}
