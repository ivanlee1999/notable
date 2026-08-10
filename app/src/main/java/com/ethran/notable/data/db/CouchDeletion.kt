package com.ethran.notable.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import javax.inject.Inject

/**
 * A notebook or folder deleted **on this device**, awaiting a tombstone push.
 *
 * The row exists because deleting the Room row is not by itself a fact that can be synced: an
 * absent notebook is also what a device that has never seen it looks like. It is also why deleting
 * while offline works — the intent survives a restart, so the tombstone is still pushed days later.
 *
 * [docId] is the full CouchDB document id (`notebook:<uuid>`, `folder:<uuid>`), and [deletedAt] the
 * ISO-8601 instant the protocol compares in delete-vs-edit. Cleared when the document is written
 * again, which is how an edit newer than the deletion resurrects it.
 */
@Entity(tableName = "couch_deletion")
data class CouchDeletion(
    @PrimaryKey val docId: String,
    val deletedAt: String,
)

@Dao
interface CouchDeletionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: CouchDeletion)

    @Query("SELECT * FROM couch_deletion WHERE docId = :docId")
    suspend fun get(docId: String): CouchDeletion?

    @Query("SELECT docId FROM couch_deletion ORDER BY docId")
    suspend fun allIds(): List<String>

    @Query("DELETE FROM couch_deletion WHERE docId = :docId")
    suspend fun delete(docId: String)
}

class CouchDeletionRepository @Inject constructor(
    private val db: CouchDeletionDao
) {
    suspend fun record(docId: String, deletedAt: String) = db.upsert(CouchDeletion(docId, deletedAt))

    suspend fun get(docId: String): CouchDeletion? = db.get(docId)

    suspend fun pendingIds(): List<String> = db.allIds()

    suspend fun clear(docId: String) = db.delete(docId)
}
