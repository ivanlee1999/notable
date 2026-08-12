package com.ethran.notable.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import javax.inject.Inject

/**
 * A document changed **on this device** and not yet accepted by CouchDB — the durable outbox.
 *
 * The twin of [CouchDeletion], and it exists for the mirror-image reason. That table records a
 * deletion because *absence* is not a syncable fact; this one records an edit because "the row
 * changed" is not one either. Room does not remember that a notebook's title moved, or that a page
 * was inserted, once the write has landed — so unless the intent is written down beside the data,
 * the only thing that can rescue the change is a scan for documents the server has *never* seen,
 * and that scan is blind to a new edit of a document already sent once.
 *
 * The row is written inside the same transaction as the data it describes. That is the whole point:
 * "write the data" and "queue the document" used to be two separate acts performed by UI call
 * sites, so every new mutation site was one forgotten call away from a change that stays local
 * forever — and even the sites that remembered could be killed between the two writes.
 *
 * [docId] is the full CouchDB document id (`notebook:<uuid>`, `page:<uuid>`, `folder:<uuid>`), and
 * [queuedAt] the epoch-millis instant it was first queued. Cleared by the engine once the server
 * has accepted the document.
 */
@Entity(tableName = "couch_outbox")
data class CouchOutbox(
    @PrimaryKey val docId: String,
    val queuedAt: Long,
)

@Dao
interface CouchOutboxDao {
    /**
     * IGNORE rather than REPLACE: re-queueing a document that is already waiting must not push
     * [CouchOutbox.queuedAt] forward. The interesting number is how long the *oldest* unsent change
     * has been waiting, and a page being drawn on rewrites its row several times a minute.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun add(row: CouchOutbox)

    @Query("SELECT docId FROM couch_outbox ORDER BY queuedAt, docId")
    suspend fun allIds(): List<String>

    @Query("DELETE FROM couch_outbox WHERE docId = :docId")
    suspend fun delete(docId: String)
}

/**
 * Marks the coroutine that is landing a change *received from the server*, so the repositories
 * beneath it do not queue it straight back.
 *
 * `RoomCouchStore.apply` writes remote content by calling the very same repository methods a user
 * edit does. Without this marker every pull would queue a push, the peer would see the echo, and
 * two devices would ping-pong forever.
 *
 * Suppression is expressed as the exception rather than enqueueing as the exception, because the
 * entire class of bug this outbox exists to remove is "someone forgot to queue". Forgetting to
 * suppress costs one redundant round trip that the engine's echo detection absorbs; forgetting to
 * enqueue costs the user their work.
 */
class RemoteApply : AbstractCoroutineContextElement(RemoteApply) {
    companion object Key : CoroutineContext.Key<RemoteApply>
}

class CouchOutboxRepository @Inject constructor(
    private val db: CouchOutboxDao
) {
    suspend fun queue(documentId: String) = queue(listOf(documentId))

    /**
     * Records that [documentIds] have to be sent, unless this is the sync engine landing a remote
     * change — see [RemoteApply].
     */
    suspend fun queue(documentIds: List<String>) {
        if (currentCoroutineContext()[RemoteApply] != null) return
        val now = System.currentTimeMillis()
        for (documentId in documentIds) db.add(CouchOutbox(docId = documentId, queuedAt = now))
    }

    suspend fun pendingIds(): List<String> = db.allIds()

    suspend fun clear(documentId: String) = db.delete(documentId)
}
