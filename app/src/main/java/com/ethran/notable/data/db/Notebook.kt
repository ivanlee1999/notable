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
import com.ethran.notable.sync.SyncClock
import com.ethran.notable.sync.couch.CouchBookmark
import com.ethran.notable.sync.couch.CouchDocId
import com.ethran.notable.sync.couch.CouchMerge
import com.ethran.notable.sync.couch.CouchOutlineEntry
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

    /**
     * Starred pages, and the record of pages that were un-starred — see [CouchBookmark].
     *
     * Stored as a JSON column rather than as its own table, and holding the sync layer's own type
     * rather than a domain twin, because these lists *are* what the notebook document carries: the
     * merge in [CouchMerge.mergeNotebook] is defined over them, and a second representation would
     * be a second place for the two to drift apart. The outline additionally needs its order kept,
     * which a JSON list gives for free and a table would need a position column to fake.
     */
    @ColumnInfo(defaultValue = "[]")
    val bookmarks: List<CouchBookmark> = emptyList(),

    /** The notebook's table of contents, in reading order. See [bookmarks] for why it lives here. */
    @ColumnInfo(defaultValue = "[]")
    val outline: List<CouchOutlineEntry> = emptyList(),

    // File that its linked to:
    val linkedExternalUri: String? = null,
    val createdAt: Date = SyncClock.nowDate(),
    val updatedAt: Date = SyncClock.nowDate(),

    /**
     * When this notebook was moved to the Trash, or null while it is a normal notebook. The twin
     * of [Folder.deletedAt], and hidden from the library by the same rule: listing queries filter
     * on it, [getAll] does not, because a notebook waiting in the Trash is not deleted anywhere
     * yet and must keep syncing until it really is.
     */
    @ColumnInfo(index = true)
    val deletedAt: Date? = null,

    /** Which device last wrote this notebook, or null when that device is this one. See [Page.updatedBy]. */
    val updatedBy: String? = null
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
    // `updatedBy` is cleared alongside `updatedAt`: adding, removing or reordering pages here is
    // authored here, whoever wrote the manifest last. See [Notebook.updatedBy]. Only the local
    // repository paths reach this — `RoomCouchStore.applyNotebook` writes the whole row through
    // [BookRepository.updateVerbatim] instead, and so keeps the peer's author.
    @Query("UPDATE notebook SET pageIds=:pageIds, updatedAt=:updatedAt, updatedBy=NULL WHERE id=:id")
    suspend fun setPageIds(id: String, pageIds: List<String>, updatedAt: Date)

    /**
     * The default backgrounds that are files rather than native templates — the notebook half of
     * what CouchDB sync checks for bytes that have not arrived.
     *
     * A notebook usually shares its PDF with the pages already drawn on it, so this rarely finds
     * anything [PageDao.getFileBackgrounds] has not. It matters for the one that does: a notebook
     * whose pages have all been deleted still hands its default to the next page created in it, and
     * a default nobody ever downloaded would hand out a path to nothing.
     */
    @Query(
        "SELECT DISTINCT defaultBackground AS background, " +
            "defaultBackgroundType AS backgroundType FROM notebook " +
            "WHERE defaultBackgroundType != 'native'"
    )
    suspend fun getFileDefaultBackgrounds(): List<FileBackground>

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

    /** @return the id of the notebook's first page, so a caller can open straight onto it. */
    suspend fun create(notebook: Notebook): String {
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

            notebookDao.setPageIds(notebook.id, listOf(page.id), SyncClock.nowDate())
            notebookDao.setOpenPageId(notebook.id, page.id)
            // Both documents, not just the notebook. The initial page is created here and nothing
            // else is ever going to touch it, so queueing the manifest alone shipped a notebook
            // naming a page the server had never been offered.
            outbox.queue(listOf(CouchDocId.notebook(notebook.id), CouchDocId.page(page.id)))
        }
        return page.id
    }

    suspend fun createEmpty(notebook: Notebook) {
        database.withTransaction {
            notebookDao.create(notebook)
            outbox.queue(CouchDocId.notebook(notebook.id))
        }
    }

    suspend fun update(notebook: Notebook) {
        log.i("updating DB")
        // `updatedBy = null` for the same reason `updatedAt` is stamped: this write happened here.
        val updatedNotebook = notebook.copy(updatedAt = SyncClock.nowDate(), updatedBy = null)
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

    suspend fun getFileDefaultBackgrounds(): List<FileBackground> {
        return notebookDao.getFileDefaultBackgrounds()
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

    // There is deliberately no ink-save bump of the notebook here any more. The notebook's
    // updatedAt is the envelope clock the merge decides renames, moves and page order by; ink
    // advanced it on every stroke and silently undid whichever of those arrived from the peer.
    // Recency for the library is read from the page clocks (PageDao.lastEditedByNotebookFlow).

    suspend fun addPage(bookId: String, pageId: String, index: Int? = null) {
        database.withTransaction {
            val notebook = notebookDao.getById(bookId) ?: return@withTransaction
            val pageIds = notebook.pageIds.toMutableList()
            if (index != null) pageIds.add(index, pageId)
            else pageIds.add(pageId)
            notebookDao.setPageIds(bookId, pageIds, SyncClock.nowDate())
            outbox.queue(listOf(CouchDocId.notebook(bookId), CouchDocId.page(pageId)))
        }
    }

    suspend fun removePage(id: String, pageId: String) {
        database.withTransaction {
            val notebook = notebookDao.getById(id) ?: return@withTransaction
            val now = SyncClock.nowDate()
            val updatedNotebook = notebook.copy(
                // remove the page
                pageIds = notebook.pageIds.filterNot { it == pageId },
                // remove the "open page" if it's the one
                openPageId = if (notebook.openPageId == pageId) null else notebook.openPageId,
                // The bookmark can simply go — the merge filters starred pages by the page
                // tombstone too. The outline entries have to be *marked* removed instead, because
                // the merge deliberately does not filter those (protocol §5.2.2) and a peer still
                // holding them would otherwise add them straight back. Doing it here, as an
                // ordinary stamped edit, is what makes the removal travel.
                bookmarks = notebook.bookmarks.filterNot { it.pageId == pageId },
                outline = notebook.outline.map {
                    if (it.pageId == pageId) {
                        it.copy(removed = true, updatedAt = now.toInstant().toString())
                    } else it
                },
                // a structural change marks the notebook dirty for sync
                updatedAt = now,
                // and it was made here, whoever wrote the manifest last
                updatedBy = null
            )
            notebookDao.update(updatedNotebook)
            // Only the notebook. The removal travels inside its manifest and `deletedPageIds`
            // (protocol §6.6); the page document itself is on its way out, so offering it would
            // push a document the manifest no longer names.
            outbox.queue(CouchDocId.notebook(id))
        }
        log.i("Cleaned $id $pageId")
    }

    // region Bookmarks and outline

    /**
     * Star or un-star a page — protocol §3.2.1.
     *
     * Un-starring writes a `removed` entry rather than dropping the bookmark, so the un-starring
     * reaches a peer that still holds the star instead of being undone by it. The list is kept
     * sorted by page id, matching what the merge produces, so a document written here and one
     * written by a merge are byte-identical.
     */
    suspend fun setBookmark(id: String, pageId: String, bookmarked: Boolean) {
        database.withTransaction {
            val notebook = notebookDao.getById(id) ?: return@withTransaction
            val now = SyncClock.nowDate()
            val entry = CouchBookmark(
                pageId = pageId,
                updatedAt = now.toInstant().toString(),
                removed = !bookmarked,
            )
            val bookmarks = (notebook.bookmarks.filterNot { it.pageId == pageId } + entry)
                .sortedBy { it.pageId }
            notebookDao.update(
                notebook.copy(bookmarks = bookmarks, updatedAt = now, updatedBy = null)
            )
            outbox.queue(CouchDocId.notebook(id))
        }
    }

    /**
     * Add an entry to the table of contents — protocol §3.2.2.
     *
     * Placed at the page's own position rather than at the end: an outline is read against the
     * notebook, so a new entry belongs beside the pages it sits between.
     */
    suspend fun addOutlineEntry(id: String, pageId: String, title: String, depth: Int = 0) {
        database.withTransaction {
            val notebook = notebookDao.getById(id) ?: return@withTransaction
            val now = SyncClock.nowDate()
            val entry = CouchOutlineEntry(
                id = UUID.randomUUID().toString(),
                pageId = pageId,
                title = title,
                depth = depth,
                updatedAt = now.toInstant().toString(),
            )
            val order = notebook.pageIds.withIndex().associate { (index, it) -> it to index }
            val position = order[pageId] ?: Int.MAX_VALUE
            val insertion = notebook.outline
                .indexOfFirst { (order[it.pageId] ?: Int.MAX_VALUE) > position }
                .let { if (it < 0) notebook.outline.size else it }
            val outline = notebook.outline.toMutableList().apply { add(insertion, entry) }
            notebookDao.update(notebook.copy(outline = outline, updatedAt = now, updatedBy = null))
            outbox.queue(CouchDocId.notebook(id))
        }
    }

    /** Rename an entry, change its nesting depth, or both. Depth is clamped, matching the decoder. */
    suspend fun updateOutlineEntry(
        id: String, entryId: String, title: String? = null, depth: Int? = null,
    ) = editOutline(id, entryId) { entry, now ->
        entry.copy(
            title = title ?: entry.title,
            depth = (depth ?: entry.depth).coerceIn(0, CouchOutlineEntry.MAX_DEPTH),
            updatedAt = now,
        )
    }

    /**
     * Delete an entry by marking it removed — an ordinary edit the merge carries, rather than a
     * drop the peer would undo.
     */
    suspend fun removeOutlineEntry(id: String, entryId: String) =
        editOutline(id, entryId) { entry, now -> entry.copy(removed = true, updatedAt = now) }

    /**
     * Move an outline entry one step against its *visible* siblings — the entries the reader can
     * actually see, which is what the up/down buttons mean.
     *
     * Identity-based on purpose. This used to take indices into "the live list" defined as
     * `filterNot { removed }`, while the UI numbered rows in *its* list, which additionally hides
     * dangling entries (page id no longer in `pageIds` — deliberately kept in the data for merge
     * idempotence, see [movedOutline]). One hidden dangling entry shifted every index after it,
     * and the buttons reordered the wrong row. An entry id cannot go stale that way.
     */
    suspend fun moveOutlineEntry(id: String, entryId: String, direction: Int) {
        database.withTransaction {
            val notebook = notebookDao.getById(id) ?: return@withTransaction
            val outline = movedOutline(notebook.outline, notebook.pageIds, entryId, direction)
                ?: return@withTransaction
            notebookDao.update(
                notebook.copy(outline = outline, updatedAt = SyncClock.nowDate(), updatedBy = null)
            )
            outbox.queue(CouchDocId.notebook(id))
        }
    }

    private suspend fun editOutline(
        id: String, entryId: String, edit: (CouchOutlineEntry, String) -> CouchOutlineEntry,
    ) {
        database.withTransaction {
            val notebook = notebookDao.getById(id) ?: return@withTransaction
            if (notebook.outline.none { it.id == entryId }) return@withTransaction
            val now = SyncClock.nowDate()
            val outline = notebook.outline.map {
                if (it.id == entryId) edit(it, now.toInstant().toString()) else it
            }
            notebookDao.update(notebook.copy(outline = outline, updatedAt = now, updatedBy = null))
            outbox.queue(CouchDocId.notebook(id))
        }
    }

    // endregion

    suspend fun changePageIndex(id: String, pageId: String, index: Int) {
        database.withTransaction {
            val notebook = notebookDao.getById(id) ?: return@withTransaction
            val pageIds = notebook.pageIds.toMutableList()
            var correctedIndex = index
            if (correctedIndex < 0) correctedIndex = 0
            if (correctedIndex > pageIds.size - 1) correctedIndex = pageIds.size - 1

            pageIds.remove(pageId)
            pageIds.add(correctedIndex, pageId)
            notebookDao.setPageIds(id, pageIds, SyncClock.nowDate())
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

    /** Notebooks directly inside [folderId], trashed ones included. */
    suspend fun getInFolderIncludingTrashed(folderId: String?): List<Notebook> =
        notebookDao.getInFolderIncludingTrashed(folderId)

    /** Notebooks currently in the Trash, most recently thrown away first. */
    fun getTrashed(): LiveData<List<Notebook>> = notebookDao.getTrashed()

    suspend fun getTrashedNow(): List<Notebook> = notebookDao.getTrashedNow()

    /**
     * Move to Trash, or restore with null. A single-column write, so it cannot race a concurrent
     * page-list update into losing it.
     *
     * Queues nothing: the Trash never leaves this device, so there is no change for a peer to hear
     * about until the item is really deleted.
     */
    suspend fun setDeletedAt(id: String, deletedAt: Date?) = notebookDao.setDeletedAt(id, deletedAt)

    /**
     * Removes the rows only. Deleting queues nothing, because an outbox entry for a notebook with
     * no tombstone beside it resolves to "nothing to send" — absence is not a fact the peer can
     * read. A deletion the user made goes through [com.ethran.notable.data.AppRepository
     * .deleteNotebookLocally], which writes the tombstone and the outbox entry in one transaction
     * with this delete.
     */
    suspend fun delete(id: String) {
        notebookDao.delete(id)
    }

}


/**
 * The outline with [entryId] swapped one step ([direction] -1 up, +1 down) against its *visible*
 * neighbor, or null when there is nothing to do — the entry is not visible, or the move falls off
 * the end.
 *
 * "Visible" is exactly what the outline tab shows: entries neither removed nor dangling. A
 * dangling entry — one whose page id is no longer in [pageIds] — is deliberately kept in the data
 * (the merge cannot drop it and stay idempotent, protocol §5.2.2) but hidden from the reader, so a
 * move must count positions the way the reader does or it reorders the wrong row.
 *
 * A swap rather than a remove-and-insert, so the removed and dangling entries between the two
 * visible neighbors keep their absolute positions: they are not this move's to disturb.
 */
internal fun movedOutline(
    outline: List<CouchOutlineEntry>,
    pageIds: List<String>,
    entryId: String,
    direction: Int,
): List<CouchOutlineEntry>? {
    val pages = pageIds.toSet()
    val visible = outline.withIndex().filter { !it.value.removed && it.value.pageId in pages }
    val position = visible.indexOfFirst { it.value.id == entryId }
    if (position < 0) return null
    val source = visible[position]
    val target = visible.getOrNull(position + direction) ?: return null
    val moved = outline.toMutableList()
    moved[source.index] = target.value
    moved[target.index] = source.value
    return moved
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