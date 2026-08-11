package com.ethran.notable.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import java.util.Date
import javax.inject.Inject

/**
 * A page removed from its notebook here — the tombstone `deletedPageIds` is built from (couch
 * protocol §6.6). [DeletedStroke] is the same idea one level down.
 *
 * A notebook's page list merges as an add-wins union, so a page the peer still lists is appended
 * straight back onto a manifest that merely stopped naming it: deleting a page would be undone by
 * the next sync. One row per removed page is what tells the merge that this device *decided*
 * against the page rather than never having heard of it.
 *
 * Deliberately has **no** foreign key to [Notebook]: the row has to outlive the page it names, and
 * that page is gone by definition. Rows are pruned by age (see [DeletedPageDao.deleteOlderThan])
 * once every device has certainly synced past them.
 */
@Entity
data class DeletedPage(
    @PrimaryKey val pageId: String,
    @ColumnInfo(index = true) val notebookId: String,
    val deletedAt: Date,
)

@Dao
interface DeletedPageDao {
    /**
     * Records removals. **Ignores** ids already tombstoned: protocol §6.6 requires a tombstone to
     * keep its original `deletedAt`, because re-stamping it would let an arbitrarily later
     * timestamp win a delete-vs-edit comparison it should lose.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rows: List<DeletedPage>)

    /**
     * Writes tombstones that arrived from a merge, whose `deletedAt` is already the earliest of the
     * two sides — so here the incoming value must *replace* whatever this device recorded.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<DeletedPage>)

    @Query("SELECT * FROM DeletedPage WHERE notebookId = :notebookId ORDER BY pageId")
    suspend fun getByNotebook(notebookId: String): List<DeletedPage>

    @Query("DELETE FROM DeletedPage WHERE pageId IN (:pageIds)")
    suspend fun deleteByIds(pageIds: List<String>)

    /** Prunes tombstones older than [cutoff] (epoch millis). */
    @Query("DELETE FROM DeletedPage WHERE deletedAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)
}

class DeletedPageRepository @Inject constructor(
    private val db: DeletedPageDao
) {
    suspend fun record(notebookId: String, pageIds: List<String>, deletedAt: Date = Date()) {
        if (notebookId.isEmpty() || pageIds.isEmpty()) return
        pageIds.chunked(900).forEach { batch ->
            db.insertAll(batch.map {
                DeletedPage(pageId = it, notebookId = notebookId, deletedAt = deletedAt)
            })
        }
    }

    suspend fun upsertAll(rows: List<DeletedPage>) {
        if (rows.isEmpty()) return
        rows.chunked(900).forEach { db.upsertAll(it) }
    }

    suspend fun getByNotebook(notebookId: String): List<DeletedPage> = db.getByNotebook(notebookId)

    suspend fun deleteByIds(pageIds: List<String>) {
        if (pageIds.isEmpty()) return
        pageIds.chunked(900).forEach { db.deleteByIds(it) }
    }

    suspend fun deleteOlderThan(cutoffMillis: Long) = db.deleteOlderThan(cutoffMillis)
}
