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
 * An image erased from a page here — the picture's counterpart to [DeletedStroke] (couch protocol
 * §6.6).
 *
 * A page's images merge as a union keyed by id, so an image that is merely absent here is
 * indistinguishable from one that has not arrived yet, and the peer's copy comes back on the next
 * merge. The row says this device removed it, and when.
 *
 * Deliberately has **no** foreign key to [Page]: the image row is gone by definition, and the
 * tombstone is queried by [pageId] rather than cascaded. Rows are pruned by age (see
 * [DeletedImageDao.deleteOlderThan]) once every device has certainly synced past them.
 */
@Entity
data class DeletedImage(
    @PrimaryKey val imageId: String,
    @ColumnInfo(index = true) val pageId: String,
    val deletedAt: Date,
)

@Dao
interface DeletedImageDao {
    /**
     * Records erasures. **Ignores** ids already tombstoned: protocol §6.6 requires a tombstone to
     * keep its original `deletedAt`, because re-stamping it on every save would let an arbitrarily
     * later timestamp win a delete-vs-edit comparison it should lose.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rows: List<DeletedImage>)

    /**
     * Writes tombstones that arrived from a merge, whose `deletedAt` is already the earliest of the
     * two sides — so here the incoming value must *replace* whatever this device recorded.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<DeletedImage>)

    @Query("SELECT * FROM DeletedImage WHERE pageId = :pageId ORDER BY imageId")
    suspend fun getByPage(pageId: String): List<DeletedImage>

    @Query("DELETE FROM DeletedImage WHERE imageId IN (:imageIds)")
    suspend fun deleteByIds(imageIds: List<String>)

    /** Prunes tombstones older than [cutoff] (epoch millis). */
    @Query("DELETE FROM DeletedImage WHERE deletedAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)
}

class DeletedImageRepository @Inject constructor(
    private val db: DeletedImageDao
) {
    suspend fun record(pageId: String, imageIds: List<String>, deletedAt: Date = Date()) {
        if (pageId.isEmpty() || imageIds.isEmpty()) return
        imageIds.chunked(900).forEach { batch ->
            db.insertAll(batch.map {
                DeletedImage(imageId = it, pageId = pageId, deletedAt = deletedAt)
            })
        }
    }

    suspend fun upsertAll(rows: List<DeletedImage>) {
        if (rows.isEmpty()) return
        rows.chunked(900).forEach { db.upsertAll(it) }
    }

    suspend fun getByPage(pageId: String): List<DeletedImage> = db.getByPage(pageId)

    suspend fun deleteByIds(imageIds: List<String>) {
        if (imageIds.isEmpty()) return
        imageIds.chunked(900).forEach { db.deleteByIds(it) }
    }

    suspend fun deleteOlderThan(cutoffMillis: Long) = db.deleteOlderThan(cutoffMillis)
}
