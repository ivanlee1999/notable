package com.ethran.notable.data.db

import androidx.lifecycle.LiveData
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import androidx.room.withTransaction
import com.ethran.notable.data.model.BackgroundType
import com.ethran.notable.data.model.PageSize
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
data class Notebook(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val title: String = "New notebook",
    val openPageId: String? = null,
    val pageIds: List<String> = listOf(),

    @ColumnInfo(index = true)
    val parentFolderId: String? = null,

    @ColumnInfo(defaultValue = "blank")
    val defaultBackground: String = "blank",
    @ColumnInfo(defaultValue = "native")
    val defaultBackgroundType: String = "native",

    // The sheet new pages here are created with, in page units (see [PageSize]). Chosen once,
    // when the notebook is created: a page's own pageWidth/pageHeight is what lays it out, the
    // same division of labour as defaultBackground and background. Null for a notebook created
    // before page sizes existed.
    val defaultPageWidth: Int? = null,
    val defaultPageHeight: Int? = null,

    // File that its linked to:
    val linkedExternalUri: String? = null,
    val createdAt: Date = Date(),
    val updatedAt: Date = Date(),

    /**
     * When this notebook was moved to the Trash, or null while it is a normal notebook. The twin
     * of [Folder.deletedAt], and hidden from the library by the same rule: listing queries filter
     * on it, [getAll] does not, because a notebook waiting in the Trash is not deleted anywhere
     * yet and must keep syncing until it really is.
     */
    @ColumnInfo(index = true)
    val deletedAt: Date? = null
)

// DAO
@Dao
interface NotebookDao {
    // Trashed notebooks are hidden from the library but left in the table: see [Notebook.deletedAt].
    @Query("SELECT * FROM notebook WHERE parentFolderId is :folderId AND deletedAt IS NULL")
    fun getAllInFolder(folderId: String? = null): LiveData<List<Notebook>>

    /** Every notebook, trashed ones included — what sync reads. */
    @Query("SELECT * FROM notebook")
    fun getAll(): List<Notebook>

    @Query("SELECT * FROM notebook WHERE deletedAt IS NULL")
    fun getAllFlow(): Flow<List<Notebook>>

    /** Notebooks anywhere inside [folderId], trashed ones included — the purge subtree walk. */
    @Query("SELECT * FROM notebook WHERE parentFolderId IS :folderId")
    suspend fun getInFolderIncludingTrashed(folderId: String?): List<Notebook>

    @Query("SELECT * FROM notebook WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC")
    fun getTrashed(): LiveData<List<Notebook>>

    @Query("SELECT * FROM notebook WHERE deletedAt IS NOT NULL")
    suspend fun getTrashedNow(): List<Notebook>

    @Query("UPDATE notebook SET deletedAt=:deletedAt WHERE id=:id")
    suspend fun setDeletedAt(id: String, deletedAt: Date?)

    // Nullable: Room emits null when the row is absent (e.g. the notebook was deleted while a
    // screen still observes it) and re-emits on every write to the table. Typing it non-null let
    // collectors dereference a null and NPE.
    @Query("SELECT * FROM notebook WHERE id = (:notebookId)")
    fun getByIdLive(notebookId: String): LiveData<Notebook?>

    @Query("SELECT * FROM notebook WHERE id = (:notebookId)")
    suspend fun getById(notebookId: String): Notebook?

    @Query("UPDATE notebook SET openPageId=:pageId WHERE id=:notebookId")
    suspend fun setOpenPageId(notebookId: String, pageId: String)

    // Advances updatedAt alongside pageIds so a structural change (add/remove/reorder) marks the
    // notebook dirty for sync — otherwise the change would not be detected and could be lost.
    @Query("UPDATE notebook SET pageIds=:pageIds, updatedAt=:updatedAt WHERE id=:id")
    suspend fun setPageIds(id: String, pageIds: List<String>, updatedAt: Date)

    @Insert
    suspend fun create(notebook: Notebook): Long

    @Update
    suspend fun update(notebook: Notebook)

    @Query("DELETE FROM notebook WHERE id=:id")
    suspend fun delete(id: String)
}

/**
 * Every mutating method here writes its rows **and** its [CouchOutbox] entries inside one
 * transaction. Queueing used to be the caller's job, done at the UI call site next to the write,
 * which meant two things could go wrong and both did: a new call site could simply forget, and even
 * one that remembered could be killed between persisting the notebook and persisting the intent to
 * send it. Both failures look identical from the outside — a change that is on the device and never
 * on the peer — and neither is recoverable, because a document the server has already seen once is
 * invisible to the "never sent" scan.
 */
class BookRepository @Inject constructor(
    private val notebookDao: NotebookDao,
    private val pageDao: PageDao,
    private val outbox: CouchOutboxRepository,
    private val database: AppDatabase,
) {
    private val log = ShipBook.getLogger("BookRepository")

    fun getAll(): List<Notebook> {
        return notebookDao.getAll()
    }

    fun getAllFlow(): Flow<List<Notebook>> {
        return notebookDao.getAllFlow()
    }

    suspend fun create(notebook: Notebook) {
        val page = Page(
            notebookId = notebook.id,
            background = notebook.defaultBackground,
            backgroundType = notebook.defaultBackgroundType,
            pageWidth = notebook.defaultPageWidth,
            pageHeight = notebook.defaultPageHeight
        )
        database.withTransaction {
            notebookDao.create(notebook)
            pageDao.create(page)

            notebookDao.setPageIds(notebook.id, listOf(page.id), Date())
            notebookDao.setOpenPageId(notebook.id, page.id)
            // Both documents, not just the notebook. The initial page is created here and nothing
            // else is ever going to touch it, so queueing the manifest alone shipped a notebook
            // naming a page the server had never been offered.
            outbox.queue(listOf(CouchDocId.notebook(notebook.id), CouchDocId.page(page.id)))
        }
    }

    suspend fun createEmpty(notebook: Notebook) {
        database.withTransaction {
            notebookDao.create(notebook)
            outbox.queue(CouchDocId.notebook(notebook.id))
        }
    }

    suspend fun update(notebook: Notebook) {
        log.i("updating DB")
        val updatedNotebook = notebook.copy(updatedAt = Date())
        database.withTransaction {
            notebookDao.update(updatedNotebook)
            outbox.queue(CouchDocId.notebook(updatedNotebook.id))
        }
    }

    /**
     * Write the notebook exactly as given, unlike [update], which stamps `updatedAt = now()`.
     * Used during sync when downloading from server, to keep the remote timestamp.
     *
     * It queues anyway: on the download path [RemoteApply] suppresses that, so the call is inert
     * where it is actually used, and any future caller gets the safe behaviour rather than silence.
     */
    suspend fun updateVerbatim(notebook: Notebook) {
        database.withTransaction {
            notebookDao.update(notebook)
            outbox.queue(CouchDocId.notebook(notebook.id))
        }
    }

    fun getAllInFolder(folderId: String? = null): LiveData<List<Notebook>> {
        return notebookDao.getAllInFolder(folderId)
    }

    suspend fun getById(notebookId: String): Notebook? {
        return notebookDao.getById(notebookId)
    }

    fun getByIdLive(notebookId: String): LiveData<Notebook?> {
        return notebookDao.getByIdLive(notebookId)
    }

    /**
     * Which page you had open is a fact about this device, not about the notebook: it is not part
     * of the synced document — `RoomCouchStore.applyNotebook` carries the local value across every
     * incoming change — so this queues nothing.
     */
    suspend fun setOpenPageId(id: String, pageId: String) {
        notebookDao.setOpenPageId(id, pageId)
    }

    suspend fun addPage(bookId: String, pageId: String, index: Int? = null) {
        database.withTransaction {
            val notebook = notebookDao.getById(bookId) ?: return@withTransaction
            val pageIds = notebook.pageIds.toMutableList()
            if (index != null) pageIds.add(index, pageId)
            else pageIds.add(pageId)
            notebookDao.setPageIds(bookId, pageIds, Date())
            outbox.queue(listOf(CouchDocId.notebook(bookId), CouchDocId.page(pageId)))
        }
    }

    suspend fun removePage(id: String, pageId: String) {
        database.withTransaction {
            val notebook = notebookDao.getById(id) ?: return@withTransaction
            val updatedNotebook = notebook.copy(
                // remove the page
                pageIds = notebook.pageIds.filterNot { it == pageId },
                // remove the "open page" if it's the one
                openPageId = if (notebook.openPageId == pageId) null else notebook.openPageId,
                // a structural change marks the notebook dirty for sync
                updatedAt = Date()
            )
            notebookDao.update(updatedNotebook)
            // Only the notebook. The removal travels inside its manifest and `deletedPageIds`
            // (protocol §6.6); the page document itself is on its way out, so offering it would
            // push a document the manifest no longer names.
            outbox.queue(CouchDocId.notebook(id))
        }
        log.i("Cleaned $id $pageId")
    }

    suspend fun changePageIndex(id: String, pageId: String, index: Int) {
        database.withTransaction {
            val notebook = notebookDao.getById(id) ?: return@withTransaction
            val pageIds = notebook.pageIds.toMutableList()
            var correctedIndex = index
            if (correctedIndex < 0) correctedIndex = 0
            if (correctedIndex > pageIds.size - 1) correctedIndex = pageIds.size - 1

            pageIds.remove(pageId)
            pageIds.add(correctedIndex, pageId)
            notebookDao.setPageIds(id, pageIds, Date())
            // A reorder is the case the "never sent" scan can never rescue: the notebook is one the
            // server already holds, so nothing about it looks unsent, and only the order changed.
            outbox.queue(CouchDocId.notebook(id))
        }
    }

    suspend fun getPageIndex(id: String, pageId: String): Int? {
        val notebook = notebookDao.getById(id) ?: return null
        val pageIds = notebook.pageIds
        val index = pageIds.indexOf(pageId)
        return if (index != -1) index else null
    }

    suspend fun getPageAtIndex(id: String, index: Int): String? {
        val notebook = notebookDao.getById(id) ?: return null
        val pageIds = notebook.pageIds
        if (index < 0 || index > pageIds.size - 1) return null
        return pageIds[index]
    }

    /**
     * Removes the rows only. Deleting queues nothing, because an outbox entry for a notebook with
     * no tombstone beside it resolves to "nothing to send" — absence is not a fact the peer can
     * read. A deletion the user made goes through [com.ethran.notable.data.AppRepository
     * .deleteNotebookLocally], which writes the tombstone and the outbox entry in one transaction
     * with this delete.
    /** Notebooks directly inside [folderId], trashed ones included. */
    suspend fun getInFolderIncludingTrashed(folderId: String?): List<Notebook> =
        notebookDao.getInFolderIncludingTrashed(folderId)

    /** Notebooks currently in the Trash, most recently thrown away first. */
    fun getTrashed(): LiveData<List<Notebook>> = notebookDao.getTrashed()

    suspend fun getTrashedNow(): List<Notebook> = notebookDao.getTrashedNow()

    /**
     * Move to Trash, or restore with null. A single-column write, so it cannot race a concurrent
     * page-list update into losing it.
     */
    suspend fun setDeletedAt(id: String, deletedAt: Date?) = notebookDao.setDeletedAt(id, deletedAt)

    /**
     * Permanently remove the row; `ON DELETE CASCADE` takes its pages with it. Called from
     * [com.ethran.notable.data.TrashRepository], which writes the tombstone in the same
     * transaction — a deleted row on its own is indistinguishable from a notebook this device
     * never had, so without one a peer simply sends it back.
     */
    suspend fun delete(id: String) {
        notebookDao.delete(id)
    }

}


fun Notebook.getBackgroundType(): BackgroundType {
    return BackgroundType.fromKey(defaultBackgroundType)
}

fun Notebook.newPage(): Page {
    return Page(
        notebookId = id,
        background = defaultBackground,
        backgroundType = defaultBackgroundType,
        // A notebook that declares no sheet keeps declaring none, so it never ends up part
        // page-sized and part legacy.
        pageWidth = defaultPageWidth,
        pageHeight = defaultPageHeight
    )
}

/** The sheet new pages here get, or null if this notebook declares none. */
fun Notebook.declaredDefaultPageSize(): PageSize? =
    PageSize.of(defaultPageWidth, defaultPageHeight)

fun Notebook.getPageIndex(pageId: String): Int {
    return pageIds.indexOf(pageId)
}