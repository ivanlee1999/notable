package com.ethran.notable.data

import com.ethran.notable.data.datastore.GlobalAppSettings
import androidx.room.withTransaction
import com.ethran.notable.data.db.AppDatabase
import com.ethran.notable.data.db.BookRepository
import com.ethran.notable.data.db.CouchDeletionRepository
import com.ethran.notable.data.db.CouchOutboxRepository
import com.ethran.notable.data.db.DeletedImageRepository
import com.ethran.notable.data.db.DeletedPageRepository
import com.ethran.notable.data.db.DeletedStrokeRepository
import com.ethran.notable.data.db.FolderRepository
import com.ethran.notable.data.db.Image
import com.ethran.notable.data.db.ImageRepository
import com.ethran.notable.data.db.KvProxy
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.db.NotebookSyncStateRepository
import com.ethran.notable.data.db.Page
import com.ethran.notable.data.db.PageRepository
import com.ethran.notable.data.db.PageSyncState
import com.ethran.notable.data.db.PageSyncStateRepository
import com.ethran.notable.data.db.PageTextRepository
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.db.StrokeRepository
import com.ethran.notable.data.db.getPageIndex
import com.ethran.notable.data.db.newPage
import com.ethran.notable.data.events.AppEvent
import com.ethran.notable.data.model.BackgroundType
import com.ethran.notable.sync.SyncClock
import com.ethran.notable.sync.couch.CouchDocId
import io.shipbook.shipbooksdk.ShipBook
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val log = ShipBook.getLogger("appRepository")

// The caption the library used to print under an unnamed quick page, now the name a one-tap note
// is born with. Locale-default so the date reads the way the rest of the device writes dates.
private val noteDateFormat get() = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())

@Singleton
class AppRepository @Inject constructor(
    val bookRepository: BookRepository,
    val pageRepository: PageRepository,
    val strokeRepository: StrokeRepository,
    val imageRepository: ImageRepository,
    val folderRepository: FolderRepository,
    val notebookSyncStateRepository: NotebookSyncStateRepository,
    val pageSyncStateRepository: PageSyncStateRepository,
    val deletedStrokeRepository: DeletedStrokeRepository,
    val deletedPageRepository: DeletedPageRepository,
    val deletedImageRepository: DeletedImageRepository,
    val couchDeletionRepository: CouchDeletionRepository,
    val couchOutboxRepository: CouchOutboxRepository,
    // Staging a deletion, so a folder and its subtree can be thrown away and brought back. The
    // publishing half lives here, in [deleteFolderSubtreeLocally]. See [TrashRepository].
    val trashRepository: TrashRepository,
    // Recognized handwriting. Not part of the library's sync — see docs/recognized-text.md.
    val pageTextRepository: PageTextRepository,
    val kvProxy: KvProxy,
    private val db: AppDatabase
) {
    /**
     * One Room transaction around [block], for callers outside this class that have to make
     * several repository writes land or fail together — the sync store dividing a page, most
     * of all. Room joins nested transactions, so repositories that transact internally compose.
     */
    suspend fun <T> inTransaction(block: suspend () -> T): T = db.withTransaction { block() }

    /**
     * The atomic sync commit point, at page granularity. In one Room
     * transaction: mark the notebook synced, upsert the freshly transferred pages' [PageSyncState]
     * rows, and delete rows for pages that left the notebook. Pages skipped this sync keep their
     * existing rows untouched. A crash before this commits leaves *no* per-page rows for the
     * transfer, so the next sync re-transfers exactly those pages.
     *
     * Network-only follow-ups (remote GC, tombstone cleanup) stay OUTSIDE this transaction — the
     * caller runs them after it returns.
     */
    suspend fun commitNotebookSync(
        notebookId: String,
        localUpdatedAt: Date,
        remoteUpdatedAt: Date?,
        manifestEtag: String?,
        committedPageRows: List<PageSyncState>,
        departedPageIds: List<String>,
    ) {
        db.withTransaction {
            notebookSyncStateRepository.markSynced(
                notebookId = notebookId,
                localUpdatedAt = localUpdatedAt,
                remoteUpdatedAt = remoteUpdatedAt,
                remoteEtag = manifestEtag,
            )
            pageSyncStateRepository.upsertAll(committedPageRows)
            pageSyncStateRepository.deleteByIds(departedPageIds)
        }
    }
    /**
     * Replace a downloaded page's content in a single transaction: an interrupted download can no
     * longer leave a page with its old strokes deleted but new ones not yet inserted (sync P5).
     * A null [pageRepository.getWithDataById] result means the page row is absent -> create it.
     */
    suspend fun replaceDownloadedPage(page: Page, strokes: List<Stroke>, images: List<Image>) {
        db.withTransaction {
            val existing = pageRepository.getWithDataById(page.id)
            if (existing != null) {
                strokeRepository.deleteAll(existing.strokes.map { it.id })
                imageRepository.deleteAll(existing.images.map { it.id })
                pageRepository.update(page)
            } else {
                pageRepository.create(page)
            }
            strokeRepository.create(strokes)
            imageRepository.create(images)
        }
    }

    suspend fun getNextPageIdFromBookAndPageOrCreate(
        notebookId: String,
        pageId: String
    ): String {
        // One transaction: turning the last page creates a page *and* moves the notebook's
        // manifest, and a crash between the two leaves a page nothing points at.
        //
        // The existence check lives INSIDE the transaction. It used to run before it, and two
        // callers racing past that check — a drag off the page's end and a pen stroke past it
        // can fire within the same frame — each saw "no next page" and each created one: the
        // duplicate blank pages that used to accumulate at the end of a notebook. Room
        // serializes the transactions, so the second caller now finds the page the first made.
        return db.withTransaction {
            getNextPageIdFromBookAndPage(notebookId, pageId)?.let { return@withTransaction it }
            val book = bookRepository.getById(notebookId = notebookId)
            val page = book!!.newPage()
            pageRepository.create(page)
            bookRepository.addPage(notebookId, page.id)
            page.id
        }
    }

    suspend fun getNextPageIdFromBookAndPage(
        notebookId: String,
        pageId: String
    ): String? {
        val book = bookRepository.getById(notebookId = notebookId) ?: return null
        val index = book.getPageIndex(pageId)
        if (index == -1 || index == book.pageIds.size - 1)
            return null
        return book.pageIds[index + 1]
    }

    suspend fun getPreviousPageIdFromBookAndPage(
        notebookId: String,
        pageId: String
    ): String? {
        val book = bookRepository.getById(notebookId = notebookId) ?: return null
        val index = book.getPageIndex(pageId)
        if (index <= 0) { // handles -1 and 0
            return null
        }
        return book.pageIds[index - 1]
    }

    suspend fun duplicatePage(pageId: String) {
        val pageWithData = pageRepository.getWithDataById(pageId)
        if (pageWithData == null) {
            log.w("duplicatePage: Missing page Data.")
            return
        }
        val duplicatedPage = pageWithData.page.copy(
            id = UUID.randomUUID().toString(),
            scroll = 0,
            createdAt = SyncClock.nowDate(),
            updatedAt = SyncClock.nowDate()
        )
        // One transaction, so the duplicate and the manifest that names it are either both here or
        // neither is — and so their outbox entries land with them rather than after them.
        db.withTransaction {
            pageRepository.create(duplicatedPage)
            strokeRepository.create(pageWithData.strokes.map {
                it.copy(
                    id = UUID.randomUUID().toString(),
                    pageId = duplicatedPage.id,
                    updatedAt = SyncClock.nowDate(),
                    createdAt = SyncClock.nowDate()
                )
            })
            imageRepository.create(pageWithData.images.map {
                it.copy(
                    id = UUID.randomUUID().toString(),
                    pageId = duplicatedPage.id,
                    updatedAt = SyncClock.nowDate(),
                    createdAt = SyncClock.nowDate()
                )
            })
            val book = bookRepository.getById(pageWithData.page.notebookId)
                ?: return@withTransaction
            val pageIndex = book.getPageIndex(pageWithData.page.id)
            if (pageIndex == -1) return@withTransaction
            val pageIds = book.pageIds.toMutableList()
            pageIds.add(pageIndex + 1, duplicatedPage.id)
            bookRepository.update(book.copy(pageIds = pageIds))
        }
    }

    /**
     * Copies a notebook, pages and all.
     *
     * The button for this existed and said `Not implemented!`, which is a worse answer than no
     * button. Everything gets a fresh id — the notebook, every page, every stroke and image on
     * them — because the copy is a new document: reusing an id would have sync treat the copy and
     * the original as the same thing and merge them back together on the other device.
     *
     * @return the new notebook's id, or null if the source is gone.
     */
    suspend fun duplicateNotebook(notebookId: String): String? {
        val source = bookRepository.getById(notebookId) ?: return null

        val copy = source.copy(
            id = UUID.randomUUID().toString(),
            title = "${source.title} copy",
            pageIds = listOf(),
            openPageId = null,
            // Not linked: a linked notebook exports itself over a file on disk, and two notebooks
            // pointed at the same file would overwrite each other.
            linkedExternalUri = null,
            createdAt = SyncClock.nowDate(),
            updatedAt = SyncClock.nowDate(),
        )
        bookRepository.createEmpty(copy)

        val newPageIds = mutableListOf<String>()
        for (pageId in source.pageIds) {
            val data = pageRepository.getWithDataById(pageId) ?: continue
            val newPage = data.page.copy(
                id = UUID.randomUUID().toString(),
                notebookId = copy.id,
                createdAt = SyncClock.nowDate(),
                updatedAt = SyncClock.nowDate(),
            )
            pageRepository.create(newPage)
            strokeRepository.create(data.strokes.map {
                it.copy(
                    id = UUID.randomUUID().toString(), pageId = newPage.id,
                    createdAt = SyncClock.nowDate(), updatedAt = SyncClock.nowDate()
                )
            })
            imageRepository.create(data.images.map {
                it.copy(
                    id = UUID.randomUUID().toString(), pageId = newPage.id,
                    createdAt = SyncClock.nowDate(), updatedAt = SyncClock.nowDate()
                )
            })
            newPageIds += newPage.id
        }

        bookRepository.update(copy.copy(pageIds = newPageIds, openPageId = newPageIds.firstOrNull()))
        log.i("Duplicated notebook $notebookId as ${copy.id} with ${newPageIds.size} page(s)")
        return copy.id
    }

    /**
     * Moves a folder into another one, refusing a move that would make it its own ancestor.
     *
     * Folders could not be moved at all, so a tree built in the wrong shape had to be rebuilt by
     * hand. The cycle check is not defensive programming: dropping a folder into its own subtree
     * is an ordinary slip with a drag, and the result would be a subtree that reaches no root and
     * disappears from the library entirely.
     *
     * @return false if the move would create a cycle, or the folder is gone.
     */
    suspend fun moveFolder(folderId: String, newParentId: String?): Boolean {
        val folder = folderRepository.get(folderId) ?: return false
        if (folder.parentFolderId == newParentId) return true

        var cursor = newParentId
        val seen = mutableSetOf<String>()
        while (cursor != null) {
            if (cursor == folderId) return false
            if (!seen.add(cursor)) return false  // already a loop; do not add to it
            cursor = folderRepository.get(cursor)?.parentFolderId
        }

        folderRepository.update(folder.copy(parentFolderId = newParentId))
        return true
    }

    suspend fun isObservable(notebookId: String?): Boolean {
        if (notebookId == null) return false
        val book = bookRepository.getById(notebookId = notebookId) ?: return false
        return BackgroundType.fromKey(book.defaultBackgroundType) == BackgroundType.AutoPdf
    }

    /**
     * Retrieves the 0-based index of a page within a notebook.
     *
     * @param notebookId The ID of the notebook containing the page (must not be null).
     * @param pageId The ID of the page to find.
     * @return The 0-based index of the page within the notebook's page list. Returns -1 if the page is not found.
     * @throws NoSuchElementException if the notebook with the given notebookId is not found.
     */
    suspend fun getPageNumber(notebookId: String, pageId: String): Int {
        // Fetch the book or throw an exception if it doesn't exist.
        val book = bookRepository.getById(notebookId)
            ?: throw NoSuchElementException("Notebook with ID '$notebookId' not found.")

        return book.getPageIndex(pageId)
    }

    /**
     * A notebook for capture: one tap, no dialog, straight onto a blank page.
     *
     * This replaced the old `createNewQuickPage`, which made a page belonging to no notebook. The
     * speed was worth keeping and the second kind of thing was not — a loose page was never
     * offered for sync, never matched a search, and could not go to the Trash. What the button
     * makes now is an ordinary notebook, so a note jotted here is a note the iPad also has.
     *
     * Titled with the date, which is what the library already captioned an unnamed quick page
     * with, so a shelf of these reads the way the old strip did. The name is a placeholder for the
     * user to replace, not an identifier — nothing looks a notebook up by title.
     *
     * @return the new notebook's id and the page to open in it, or null if the write failed.
     */
    suspend fun createNewNote(parentFolderId: String? = null): Pair<String, String>? {
        val sheet = GlobalAppSettings.current.defaultPageSize
        val notebook = Notebook(
            title = noteDateFormat.format(Date()),
            parentFolderId = parentFolderId,
            defaultBackground = GlobalAppSettings.current.defaultNativeTemplate,
            defaultBackgroundType = BackgroundType.Native.key,
            defaultPageWidth = sheet.width,
            defaultPageHeight = sheet.height
        )
        return try {
            // `create` writes the notebook, its first page and both outbox entries in one
            // transaction, so what it hands back is already durable.
            notebook.id to bookRepository.create(notebook)
        } catch (e: android.database.sqlite.SQLiteConstraintException) {
            log.e("Failed to create note: ${e.message}")
            null
        }
    }

    /**
     * A new page in [notebookId] at [index], printed with [backgroundType] where one is named and
     * with the notebook's own default where it is not.
     *
     * The template travels with the creation rather than being written over the page afterwards:
     * an insert-with-a-template that created a blank page and then edited it would publish the
     * blank one first, so a peer syncing in that instant would receive a page the user never saw.
     */
    suspend fun newPageInBook(
        notebookId: String,
        index: Int = 0,
        background: String? = null,
        backgroundType: String? = null,
    ): String? {
        try {
            // Both writes and both outbox entries together: adding a blank page changes the page
            // *and* the notebook's page list, and until now it queued neither.
            return db.withTransaction {
                val book = bookRepository.getById(notebookId)
                    ?: return@withTransaction null
                val page = book.newPage().let {
                    if (backgroundType == null) it
                    else it.copy(
                        background = background ?: it.background,
                        backgroundType = backgroundType,
                    )
                }
                pageRepository.create(page)
                bookRepository.addPage(notebookId, page.id, index)
                page.id
            }
        } catch (e: Exception) {
            log.e("Failed to create page in book: ${e.message}")
            return null
        }
    }

    /**
     * Delete a page here, whole: the manifest stops naming it, the tombstone that publishes the
     * removal is recorded, the page row goes, and the outbox entries land — one transaction, all
     * or nothing.
     *
     * This used to live in dbUtils.deletePage (under a `TODO move this to repository`) as three
     * separate transactions: removePage committed, then the tombstone, then the row delete. A
     * crash between them silently un-happened the deletion — the page gone from the list with no
     * tombstone recorded, so the peer's copy appended it straight back on the next merge, or a
     * tombstone with the page still listed. The repository's own standard is one transaction
     * ([deleteNotebookLocally]: "One transaction makes both happen or neither").
     *
     * Room joins nested transactions, so the repositories' own transactional methods compose here
     * rather than committing on their own.
     *
     * @return the page that was deleted, or null if there was no such page. The caller uses its
     * notebookId to nudge the sync scheduler — network work that stays outside the transaction.
     */
    suspend fun deletePageLocally(pageId: String): Page? = db.withTransaction {
        val page = pageRepository.getById(pageId) ?: return@withTransaction null
        bookRepository.removePage(page.notebookId, pageId)
        // A manifest that merely stopped naming the page is not a deletion anyone else can
        // read: `mergeNotebook` is an add-wins union, so the peer's copy appends it straight
        // back. The tombstone is the fact that travels (protocol §6.6).
        deletedPageRepository.record(page.notebookId, listOf(pageId))
        pageRepository.delete(pageId)
        page
    }

    /**
     * Delete a notebook here and record the tombstone that will publish the deletion, in one
     * transaction.
     *
     * The order used to be the other way round and unguarded: the tombstone was written first and
     * the Room delete launched afterwards. `RoomCouchStore.load` consults the tombstone table
     * *before* the live rows — deliberately, since the cascade may not have run — so a delete that
     * failed left the device holding a notebook it still shows the user while telling the server it
     * is gone. One transaction makes both happen or neither.
     *
     * The tombstone stays durable, which is the property that matters most here: a deletion made
     * offline is still pushed days later, after a restart.
     */
    suspend fun deleteNotebookLocally(
        notebookId: String,
        deletedAt: String = SyncClock.nowIso(),
    ) {
        val documentId = CouchDocId.notebook(notebookId)
        db.withTransaction {
            bookRepository.delete(notebookId)
            couchDeletionRepository.record(documentId, deletedAt)
            couchOutboxRepository.queue(documentId)
        }
    }

    /** The folder twin of [deleteNotebookLocally], for the same reason. */
    suspend fun deleteFolderLocally(
        folderId: String,
        deletedAt: String = SyncClock.nowIso(),
    ) {
        val documentId = CouchDocId.folder(folderId)
        db.withTransaction {
            folderRepository.delete(folderId)
            couchDeletionRepository.record(documentId, deletedAt)
            couchOutboxRepository.queue(documentId)
        }
    }

    /**
     * Prunes stroke and image tombstones recorded before [cutoffMillis] — protocol §6.6,
     * "Pruning". Deliberately not an edit: nothing is queued and no clock is bumped, so the
     * shorter lists travel with each page's next ordinary push. Page tombstones
     * ([deletedPageRepository]) and removed outline entries are never pruned — they carry
     * structural identity the merge needs indefinitely.
     */
    suspend fun pruneExpiredTombstones(cutoffMillis: Long) {
        db.withTransaction {
            deletedStrokeRepository.deleteOlderThan(cutoffMillis)
            deletedImageRepository.deleteOlderThan(cutoffMillis)
        }
    }

}