package com.ethran.notable.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import javax.inject.Inject

/**
 * A notebook or folder this device knows to be deleted.
 *
 * The row exists because deleting the Room row is not by itself a fact that can be synced: an
 * absent notebook is also what a device that has never seen it looks like. It is also why deleting
 * while offline works — the intent survives a restart, so the tombstone is still pushed days later.
 *
 * [docId] is the full CouchDB document id (`notebook:<uuid>`, `folder:<uuid>`), and [deletedAt] the
 * ISO-8601 instant the protocol compares in delete-vs-edit. Dropped when the document is written
 * again, which is how an edit newer than the deletion resurrects it.
 */
@Entity(tableName = "couch_deletion")
data class CouchDeletion(
    @PrimaryKey val docId: String,
    val deletedAt: String,
    /**
     * Whether the server still has to be told. A deletion made here starts pending and settles once
     * the tombstone has been taken; one that *arrived* from the server is settled from the start.
     *
     * The row outlives the push either way, and that is the point. A deleted notebook's pages stay
     * live on the server for good — protocol §6.4, pages keep no tombstone of their own — so every
     * replay of the feed meets orphan pages naming a notebook that is gone, and
     * `RoomCouchStore.applyPage` reads this row to know not to rebuild it around them. Forgetting
     * the deletion the moment it was published made that guard depend on the tombstone arriving
     * *after* the pages it orphaned, which the change feed does not promise: CouchDB spreads
     * documents across shards and merges the per-shard streams in whatever order they answer.
     *
     * Kept for the same reason `deleted_page` and `deleted_stroke` rows are kept forever — a
     * removal this device forgets is a removal the next merge undoes.
     */
    @ColumnInfo(defaultValue = "1") val pending: Boolean = true,
)

@Dao
interface CouchDeletionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: CouchDeletion)

    @Query("SELECT * FROM couch_deletion WHERE docId = :docId")
    suspend fun get(docId: String): CouchDeletion?

    @Query("SELECT docId FROM couch_deletion WHERE pending != 0 ORDER BY docId")
    suspend fun pendingIds(): List<String>

    @Query("DELETE FROM couch_deletion WHERE docId = :docId")
    suspend fun delete(docId: String)
}

class CouchDeletionRepository @Inject constructor(
    private val db: CouchDeletionDao
) {
    /** A deletion made here: the server has still to be told. */
    suspend fun record(docId: String, deletedAt: String) =
        db.upsert(CouchDeletion(docId, deletedAt, pending = true))

    /**
     * A deletion the server already holds — one it just sent us, or one of ours it has taken.
     *
     * Recorded rather than forgotten: see [CouchDeletion.pending]. Nothing will be pushed for it,
     * but this device goes on knowing the document is deleted, which is what stops a leftover page
     * rebuilding the notebook around itself on the next replay.
     */
    suspend fun recordPublished(docId: String, deletedAt: String) =
        db.upsert(CouchDeletion(docId, deletedAt, pending = false))

    suspend fun get(docId: String): CouchDeletion? = db.get(docId)

    /** Only the deletions still owed to the server; see [CouchDeletion.pending]. */
    suspend fun pendingIds(): List<String> = db.pendingIds()

    /** Forgets the deletion entirely — the document is not deleted after all. */
    suspend fun clear(docId: String) = db.delete(docId)
}
