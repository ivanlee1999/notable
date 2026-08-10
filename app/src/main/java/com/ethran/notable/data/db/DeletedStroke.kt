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
 * A stroke that was erased here — the tombstone the sync merge needs (couch protocol §6.6).
 *
 * The app hard-deletes stroke rows, so without this table an erasure is indistinguishable from
 * "that stroke has not reached this device yet", and the peer's copy simply comes back on the next
 * merge. One row per erased stroke says *this* device removed it, and when.
 *
 * Deliberately has **no** foreign key to [Page]: the row has to outlive whatever it points at in
 * the strict sense (the stroke row is gone by definition), and a page removed locally takes its
 * tombstones with it only because [pageId] is what they are queried by, not because SQLite
 * cascades. Rows are pruned by age (see [DeletedStrokeDao.deleteOlderThan]) once every device has
 * certainly synced past them.
 */
@Entity
data class DeletedStroke(
    @PrimaryKey val strokeId: String,
    @ColumnInfo(index = true) val pageId: String,
    val deletedAt: Date,
)

@Dao
interface DeletedStrokeDao {
    /**
     * Records erasures. **Ignores** ids already tombstoned: protocol §6.6 requires a tombstone to
     * keep its original `deletedAt`, because re-stamping it on every save would let an arbitrarily
     * later timestamp win a delete-vs-edit comparison it should lose.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rows: List<DeletedStroke>)

    /**
     * Writes tombstones that arrived from a merge, whose `deletedAt` is already the earliest of the
     * two sides — so here the incoming value must *replace* whatever this device recorded.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<DeletedStroke>)

    @Query("SELECT * FROM DeletedStroke WHERE pageId = :pageId ORDER BY strokeId")
    suspend fun getByPage(pageId: String): List<DeletedStroke>

    @Query("DELETE FROM DeletedStroke WHERE strokeId IN (:strokeIds)")
    suspend fun deleteByIds(strokeIds: List<String>)

    /** Prunes tombstones older than [cutoff] (epoch millis). */
    @Query("DELETE FROM DeletedStroke WHERE deletedAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)
}

class DeletedStrokeRepository @Inject constructor(
    private val db: DeletedStrokeDao
) {
    suspend fun record(pageId: String, strokeIds: List<String>, deletedAt: Date = Date()) {
        if (pageId.isEmpty() || strokeIds.isEmpty()) return
        strokeIds.chunked(900).forEach { batch ->
            db.insertAll(batch.map { DeletedStroke(strokeId = it, pageId = pageId, deletedAt = deletedAt) })
        }
    }

    suspend fun upsertAll(rows: List<DeletedStroke>) {
        if (rows.isEmpty()) return
        rows.chunked(900).forEach { db.upsertAll(it) }
    }

    suspend fun getByPage(pageId: String): List<DeletedStroke> = db.getByPage(pageId)

    suspend fun deleteByIds(strokeIds: List<String>) {
        if (strokeIds.isEmpty()) return
        strokeIds.chunked(900).forEach { db.deleteByIds(it) }
    }

    suspend fun deleteOlderThan(cutoffMillis: Long) = db.deleteOlderThan(cutoffMillis)
}
