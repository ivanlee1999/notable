package com.ethran.notable.data.db

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.distinctUntilChanged
import androidx.lifecycle.map
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import androidx.room.withTransaction
import com.ethran.notable.sync.couch.CouchDocId
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.flow.Flow
import java.util.Date
import java.util.UUID
import javax.inject.Inject

@Entity(
    foreignKeys = [ForeignKey(
        entity = Folder::class,
        parentColumns = arrayOf("id"),
        childColumns = arrayOf("parentFolderId"),
        onDelete = ForeignKey.CASCADE
    )]
)
data class Folder(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val title: String = "New Folder",

    @ColumnInfo(index = true)
    val parentFolderId: String? = null,

    val createdAt: Date = Date(),
    val updatedAt: Date = Date(),

    /**
     * When this folder was moved to the Trash, or null while it is a normal folder.
     *
     * The row stays exactly where it was — nothing is re-parented and no content moves — so a
     * restore is one column write and cannot half-fail. Only the library's listing queries filter
     * on it; [getAll] deliberately does not, because a trashed folder is still this device's
     * current answer for that id and must keep syncing until it is really deleted. See
     * [com.ethran.notable.data.TrashRepository] for why deletion is staged this way.
     */
    @ColumnInfo(index = true)
    val deletedAt: Date? = null,

    /** Which device last wrote this folder, or null when that device is this one. See [Page.updatedBy]. */
    val updatedBy: String? = null
)

// DAO
@Dao
interface FolderDao {
    // Trashed folders are hidden from the library but left in the table: see [Folder.deletedAt].
    @Query("SELECT * FROM folder WHERE parentFolderId IS :folderId AND deletedAt IS NULL")
    fun getChildrenFolders(folderId: String?): LiveData<List<Folder>>

    @Query("SELECT * FROM folder WHERE id IS :folderId")
    suspend fun get(folderId: String): Folder?

    @Query("SELECT * FROM folder WHERE id IS :folderId")
    fun getLive(folderId: String): LiveData<Folder?>

    /**
     * Every folder, trashed ones included. Sync reads through here, and a folder waiting in the
     * Trash has not been deleted anywhere yet — hiding it would make this device look like it had
     * dropped the folder and let a peer's merge fight the local copy.
     */
    @Query("SELECT * FROM folder")
    fun getAll(): List<Folder>

    /** Every folder the library shows, as a flow — what a search over the whole library reads. */
    @Query("SELECT * FROM folder WHERE deletedAt IS NULL")
    fun getAllVisibleFlow(): Flow<List<Folder>>

    /** Direct children of [folderId], trashed ones included — the subtree walk purge does. */
    @Query("SELECT * FROM folder WHERE parentFolderId IS :folderId")
    suspend fun getChildrenIncludingTrashed(folderId: String?): List<Folder>

    @Query("SELECT * FROM folder WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC")
    fun getTrashed(): LiveData<List<Folder>>

    @Query("SELECT * FROM folder WHERE deletedAt IS NOT NULL")
    suspend fun getTrashedNow(): List<Folder>

    @Query("UPDATE folder SET deletedAt=:deletedAt WHERE id=:id")
    suspend fun setDeletedAt(id: String, deletedAt: Date?)

    @Insert
    suspend fun create(folder: Folder): Long

    @Update
    suspend fun update(folder: Folder)

    @Query("DELETE FROM folder WHERE id=:id")
    suspend fun delete(id: String)
}

/**
 * Like [BookRepository], every mutating method writes its row and its [CouchOutbox] entry in one
 * transaction, so a folder cannot exist on disk without the record that it has to be sent.
 */
class FolderRepository @Inject constructor(
    private val db: FolderDao,
    private val outbox: CouchOutboxRepository,
    private val database: AppDatabase,
) {
    private val log = ShipBook.getLogger("FolderRepository")
    private val nullLiveData = MutableLiveData<String?>(null)

    suspend fun create(folder: Folder) {
        database.withTransaction {
            db.create(folder)
            outbox.queue(CouchDocId.folder(folder.id))
        }
    }

    /**
     * Stamps `updatedAt = now()`, because folder sync resolves a conflict by comparing it
     * (`FolderSyncService`, `Merge.mergeFolder`). A rename written with the old timestamp loses
     * that comparison against any peer copy and silently reverts.
     */
    suspend fun update(folder: Folder) {
        database.withTransaction {
            // `updatedBy = null` travels with the stamp: this write happened here.
            db.update(folder.copy(updatedAt = Date(), updatedBy = null))
            outbox.queue(CouchDocId.folder(folder.id))
        }
    }

    /**
     * Write the folder exactly as given, unlike [update], which stamps `updatedAt = now()`.
     * Used during sync when downloading from the server, to keep the remote timestamp — stamping
     * it locally would make every received folder look newer than its source and push straight
     * back out again.
     *
     * It queues anyway: [RemoteApply] suppresses that on the download path, so the call is inert
     * where it is actually used and safe wherever it is used next.
     */
    suspend fun updateVerbatim(folder: Folder) {
        database.withTransaction {
            db.update(folder)
            outbox.queue(CouchDocId.folder(folder.id))
        }
    }

    fun getAll(): List<Folder> {
        return db.getAll()
    }

    fun getAllVisibleFlow(): Flow<List<Folder>> = db.getAllVisibleFlow()

    fun getAllInFolder(folderId: String? = null): LiveData<List<Folder>> {
        return db.getChildrenFolders(folderId)
    }

    suspend fun getParent(folderId: String? = null): String? {
        if (folderId == null)
            return null
        val folder = db.get(folderId)
        return folder?.parentFolderId
    }

    fun getParentLive(folderId: String? = null): LiveData<String?> {
        if (folderId == null) {
            log.w("getParentLive called with null folderId")
            return nullLiveData
        }
        return db.getLive(folderId)
            .map { it?.parentFolderId }
            .distinctUntilChanged()
    }

    suspend fun get(folderId: String): Folder? {
        val folder = db.get(folderId)
        if (folder == null) log.e("Folder not found: $folderId")
        return folder
    }

    suspend fun getWithChildren(folderId: String): Folder? {
        return db.get(folderId)
    }

    /** Direct child folders of [folderId], trashed ones included. */
    suspend fun getChildrenIncludingTrashed(folderId: String?): List<Folder> =
        db.getChildrenIncludingTrashed(folderId)

    /** Folders currently in the Trash, most recently thrown away first. */
    fun getTrashed(): LiveData<List<Folder>> = db.getTrashed()

    suspend fun getTrashedNow(): List<Folder> = db.getTrashedNow()

    /**
     * Move to Trash, or restore with null. Touches one column, so it cannot race a concurrent
     * write of the folder's other fields into overwriting them.
     *
     * Queues nothing: the Trash never leaves this device, so there is no change for a peer to
     * hear about until the item is really deleted.
     */
    suspend fun setDeletedAt(id: String, deletedAt: Date?) = db.setDeletedAt(id, deletedAt)

    /**
     * Removes the row only — see [BookRepository.delete] for why a deletion queues nothing on its
     * own. `ON DELETE CASCADE` takes every descendant folder, notebook and page with it, which is
     * why a folder the user deleted goes through
     * [com.ethran.notable.data.AppRepository.deleteFolderSubtreeLocally], where the tombstones for
     * the whole subtree are written in the same transaction.
     */
    suspend fun delete(id: String) {
        db.delete(id)
    }

}