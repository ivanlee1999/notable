package com.ethran.notable.sync

import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.db.SyncStateValue
import com.ethran.notable.sync.couch.CouchDocId
import com.ethran.notable.sync.couch.CouchDocumentState
import com.ethran.notable.sync.couch.CouchSyncBackend
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

/** Per-notebook sync badge shown in the library. */
enum class SyncBadge {
    /** Local changes not yet synced (no sync running). */
    NOT_SYNCED,

    /** A sync is running and this notebook is queued but not yet its turn. */
    SCHEDULED,

    /** This notebook is being uploaded/downloaded right now. */
    SYNCING,

    /** Local matches the last committed sync. */
    SYNCED,

    /** Server has a newer version that was not pulled (upload-only mode). */
    REMOTE_AHEAD,

    /** A page was edited both locally and on the server; the user must resolve it. */
    CONFLICT,

    /** The last sync of this notebook failed. */
    ERROR,
}

/**
 * Derives a per-notebook [SyncBadge] map from the notebook list, the `notebook_sync_state` table,
 * and the live sync state. Nothing is stored: the badge is a pure function of those three sources,
 * so it stays correct as notebooks are edited, synced, or fail.
 */
@Singleton
class NotebookSyncStatusStore @Inject constructor(
    appRepository: AppRepository,
    reporter: SyncProgressReporter,
    couchBackend: CouchSyncBackend,
) {
    val badges: Flow<Map<String, SyncBadge>> = combine(
        appRepository.bookRepository.getAllFlow(),
        appRepository.notebookSyncStateRepository.getAllFlow(),
        reporter.state,
        couchBackend.documentState,
    ) { notebooks, states, syncState, couch ->
        // CouchDB keeps no `notebook_sync_state` rows — that table is the WebDAV engine's record of
        // its own commits. Reading it while CouchDB is driving reported every notebook as never
        // synced, however faithfully it was syncing, so when Couch is live its own outbox answers
        // instead. Nothing else about the badge changes: it is still derived, never stored.
        if (couch != null) return@combine notebooks.associate { nb ->
            nb.id to couchBadge(CouchDocId.notebook(nb.id), couch)
        }

        val syncing = syncState is SyncState.Syncing
        // The one notebook the engine is processing right now (null between items).
        val currentId = (syncState as? SyncState.Syncing)?.item?.id
        val byId = states.associateBy { it.notebookId }
        notebooks.associate { nb ->
            val row = byId[nb.id]
            val base = when {
                row == null -> SyncBadge.NOT_SYNCED
                row.state == SyncStateValue.ERROR -> SyncBadge.ERROR
                // A conflict outranks the plain "edited locally" badge: local IS edited (that's half
                // the conflict), but the actionable fact is that it collides with the server.
                row.state == SyncStateValue.CONFLICT -> SyncBadge.CONFLICT
                // Local edited since its last committed sync -> pending upload (supersedes a stale
                // REMOTE_AHEAD: once you edit locally, the badge is about your unsynced change).
                nb.updatedAt.time - row.syncedLocalUpdatedAt.time > TOLERANCE_MS -> SyncBadge.NOT_SYNCED
                row.state == SyncStateValue.REMOTE_AHEAD -> SyncBadge.REMOTE_AHEAD
                else -> SyncBadge.SYNCED
            }
            val badge = when {
                base == SyncBadge.SYNCED -> SyncBadge.SYNCED
                // Exactly the notebook being transferred right now spins.
                syncing && currentId == nb.id -> SyncBadge.SYNCING
                // Other pending notebooks during a run are queued, not "syncing".
                syncing -> SyncBadge.SCHEDULED
                else -> base
            }
            nb.id to badge
        }
    }

    /**
     * The badge for one notebook under CouchDB.
     *
     * Queued outranks known: a document the server has *and* that has been changed here again is
     * unsynced, which is the same precedence the WebDAV branch applies to a local edit. A document
     * in neither set has never been sent, which reads as unsynced too — correctly, since that was
     * the state the "never sent" scan exists to find.
     *
     * There is no CONFLICT badge here because the CouchDB engine does not produce one: a merge it
     * cannot resolve is written as a separate conflict copy rather than held for the user.
     */
    private fun couchBadge(documentId: String, couch: CouchDocumentState): SyncBadge =
        couchBadgeFor(documentId, couch)

    companion object {
        private const val TOLERANCE_MS = 1000L

        /** See [couchBadge]; on the companion so it can be exercised without building the store. */
        fun couchBadgeFor(documentId: String, couch: CouchDocumentState): SyncBadge = when {
            documentId in couch.queued -> SyncBadge.NOT_SYNCED
            documentId in couch.known -> SyncBadge.SYNCED
            else -> SyncBadge.NOT_SYNCED
        }
    }
}
