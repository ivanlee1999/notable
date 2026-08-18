package com.ethran.notable.data

import androidx.room.withTransaction
import com.ethran.notable.data.db.AppDatabase
import com.ethran.notable.data.db.BookRepository
import com.ethran.notable.data.db.CouchDeletionRepository
import com.ethran.notable.data.db.CouchOutboxRepository
import com.ethran.notable.data.db.Folder
import com.ethran.notable.data.db.FolderRepository
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.db.PageRepository
import com.ethran.notable.sync.SyncClock
import com.ethran.notable.sync.couch.CouchDocId
import io.shipbook.shipbooksdk.ShipBook
import java.time.Instant
import java.util.Date
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Deleting a folder or a notebook, in two steps that can each be survived.
 *
 * Deleting a folder used to be one unconfirmed tap that ran straight into `ON DELETE CASCADE`:
 * every descendant folder, notebook and page went with it, only the folder itself was tombstoned,
 * and there was no way back. The subtree was gone locally while the server still held the
 * notebooks, so the next merge either resurrected them under a folder that no longer existed or
 * left the two devices permanently disagreeing.
 *
 * So deletion is staged:
 *
 *  1. **Trash.** [trashFolder]/[trashNotebook] set `deletedAt` and nothing else. The rows stay
 *     where they are, so [restoreFolder]/[restoreNotebook] are one column write and cannot
 *     half-fail. Nothing is *deleted*: a trashed document keeps syncing, ink and all, because it
 *     is not deleted anywhere yet and a peer that is still editing it loses nothing.
 *
 *     `deletedAt` is published, though — it is a field of the `notebook`/`folder` document
 *     (protocol §3.2), not a note this device keeps to itself. Throwing something away takes it
 *     out of the library on every device, and restoring it on any of them brings it back on all,
 *     which is the only way "delete" can mean one thing across two apps. That is why these go
 *     through `update` rather than `setDeletedAt`: `update` stamps `updatedAt` and queues the
 *     outbox, and the stamp is what §5.5 compares — an unstamped trashing ties with the peer's
 *     live copy and can lose.
 *  2. **Purge.** [purgeFolder]/[purgeNotebook] walk the whole subtree first, write a tombstone for
 *     every folder and notebook in it, and only then delete the root row — all inside one
 *     transaction. Either the tombstones and the deletion both happen or neither does, which is
 *     what stops a descendant from coming back on the next merge.
 *
 * Pages carry no tombstone of their own: a page has no lifecycle apart from its notebook's
 * `pageIds` (protocol §6.4), so tombstoning the notebook is what removes them everywhere. They are
 * still counted in [DeletionScope] because the number the user needs before confirming is "how
 * much writing is this", not "how many documents".
 */
@Singleton
class TrashRepository @Inject constructor(
    private val folderRepository: FolderRepository,
    private val bookRepository: BookRepository,
    private val pageRepository: PageRepository,
    private val couchDeletionRepository: CouchDeletionRepository,
    private val couchOutboxRepository: CouchOutboxRepository,
    /**
     * Lazy because the cycle is real and shallow: `AppRepository` holds this so every caller can
     * reach the Trash, and this reaches back for the one-document delete transaction rather than
     * writing a second copy of it.
     */
    private val appRepositoryProvider: Provider<AppRepository>,
    private val db: AppDatabase,
) {
    private val appRepository: AppRepository get() = appRepositoryProvider.get()
    private val log = ShipBook.getLogger("TrashRepository")

    /**
     * Everything a deletion would take, gathered before anything is destroyed.
     *
     * [folders] and [notebooks] include the target itself, so a plain notebook scope is one
     * notebook and no folders. [pageCount] is every page under it.
     */
    data class DeletionScope(
        val folders: List<Folder> = emptyList(),
        val notebooks: List<Notebook> = emptyList(),
        val pageCount: Int = 0,
    ) {
        /** Descendant folders, i.e. not counting the folder being deleted. */
        val childFolderCount: Int get() = (folders.size - 1).coerceAtLeast(0)
        val notebookCount: Int get() = notebooks.size

        /** True when deleting this takes nothing but the empty container itself. */
        val isEmpty: Boolean get() = childFolderCount == 0 && notebookCount == 0 && pageCount == 0
    }

    // region Measuring

    /**
     * Walk [folderId] and everything under it.
     *
     * Iterative rather than recursive, and with a visited set: a folder tree is user-built data,
     * and a cycle in it (a bad merge, a hand-edited database) would otherwise spin here forever
     * while holding up a dialog.
     */
    suspend fun scopeOfFolder(folderId: String): DeletionScope {
        val root = folderRepository.get(folderId) ?: return DeletionScope()

        val folders = mutableListOf(root)
        val notebooks = mutableListOf<Notebook>()
        var pages = 0

        val seen = mutableSetOf(root.id)
        val queue = ArrayDeque(listOf(root.id))
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()

            for (child in folderRepository.getChildrenIncludingTrashed(current)) {
                if (!seen.add(child.id)) continue
                folders += child
                queue += child.id
            }
            for (notebook in bookRepository.getInFolderIncludingTrashed(current)) {
                notebooks += notebook
                pages += pageRepository.getPageIdsForNotebook(notebook.id).size
            }
        }

        return DeletionScope(folders = folders, notebooks = notebooks, pageCount = pages)
    }

    suspend fun scopeOfNotebook(notebookId: String): DeletionScope {
        val notebook = bookRepository.getById(notebookId) ?: return DeletionScope()
        return DeletionScope(
            notebooks = listOf(notebook),
            pageCount = pageRepository.getPageIdsForNotebook(notebookId).size,
        )
    }

    // endregion

    // region Trash and restore

    /**
     * Move a folder to the Trash. Only the folder's own row is marked: its descendants stay
     * untouched and become unreachable simply because their ancestor is no longer listed, which is
     * what makes restoring the whole subtree a single write rather than a second cascade to get
     * wrong. The peer hides the same subtree from the same one field.
     *
     * @return the CouchDB document ids peers need to hear about, for the caller to nudge sync
     *   with. The write is already durable in the outbox either way.
     */
    suspend fun trashFolder(folderId: String): List<String> {
        val folder = folderRepository.get(folderId) ?: return emptyList()
        folderRepository.update(folder.copy(deletedAt = SyncClock.nowDate()))
        log.i("Moved folder $folderId to the Trash")
        return listOf(CouchDocId.folder(folderId))
    }

    suspend fun trashNotebook(notebookId: String): List<String> {
        val notebook = bookRepository.getById(notebookId) ?: return emptyList()
        bookRepository.update(notebook.copy(deletedAt = SyncClock.nowDate()))
        log.i("Moved notebook $notebookId to the Trash")
        return listOf(CouchDocId.notebook(notebookId))
    }

    /**
     * Bring a folder back. If the folder it used to live in has since been purged — or is itself
     * still in the Trash — it comes back at the top level instead, because restoring it into an
     * invisible parent would put it somewhere the user cannot reach and look exactly like the
     * restore having silently failed.
     *
     * @return the CouchDB document ids peers need to hear about — always the item itself, because
     *   clearing `deletedAt` is what takes it out of the Trash on the *other* device too. Only a
     *   newer write does that, so a restore that published nothing would be undone by the peer's
     *   trashed copy on the next merge.
     */
    suspend fun restoreFolder(folderId: String): List<String> {
        val folder = folderRepository.get(folderId) ?: return emptyList()
        // update(), not setDeletedAt(): it stamps updatedAt and queues the outbox, and without the
        // stamp the restore loses the timestamp comparison against the peer's trashed copy.
        if (!isReachable(folder.parentFolderId)) {
            folderRepository.update(folder.copy(parentFolderId = null, deletedAt = null))
            log.i("Restored folder $folderId to the top level; its parent is gone")
        } else {
            folderRepository.update(folder.copy(deletedAt = null))
        }
        return listOf(CouchDocId.folder(folderId))
    }

    /** The notebook twin of [restoreFolder], with the same re-homing rule and return contract. */
    suspend fun restoreNotebook(notebookId: String): List<String> {
        val notebook = bookRepository.getById(notebookId) ?: return emptyList()
        if (!isReachable(notebook.parentFolderId)) {
            bookRepository.update(notebook.copy(parentFolderId = null, deletedAt = null))
            log.i("Restored notebook $notebookId to the top level; its folder is gone")
        } else {
            bookRepository.update(notebook.copy(deletedAt = null))
        }
        return listOf(CouchDocId.notebook(notebookId))
    }

    /**
     * Whether something filed under [parentFolderId] would actually be visible.
     *
     * Walks the whole chain to the root, not just the immediate parent: a parent can be perfectly
     * untrashed and still sit inside a trashed grandparent, and restoring into that puts the item
     * somewhere the user cannot reach — which looks exactly like the restore having silently
     * failed. A null parent is the top level, which is always there.
     *
     * The visited set is the same guard the subtree walk uses: the folder tree is user-built and
     * merged data, and a cycle in it reaches no root.
     */
    private suspend fun isReachable(parentFolderId: String?): Boolean {
        val seen = mutableSetOf<String>()
        var cursor = parentFolderId
        while (cursor != null) {
            if (!seen.add(cursor)) return false
            val folder = folderRepository.get(cursor) ?: return false
            if (folder.deletedAt != null) return false
            cursor = folder.parentFolderId
        }
        return true
    }

    // endregion

    // region Purge

    /**
     * Delete a folder and its subtree for good.
     *
     * The scope is measured before the transaction opens — it is many reads, and holding a write
     * transaction across them would block every other writer for as long as the walk takes — and
     * the tombstones plus the delete then go in together. The single `delete` is enough because
     * `ON DELETE CASCADE` removes the descendants; what it cannot do is record that they were
     * deleted, which is exactly what the tombstones above it are for.
     *
     * @return the scope that was removed, so the caller can say what happened.
     */
    suspend fun purgeFolder(folderId: String): DeletionScope {
        val scope = scopeOfFolder(folderId)
        if (scope.folders.isEmpty()) return scope

        val deletedAt = SyncClock.nowIso()
        val documentIds = scope.folders.map { CouchDocId.folder(it.id) } +
            scope.notebooks.map { CouchDocId.notebook(it.id) }
        db.withTransaction {
            documentIds.forEach { couchDeletionRepository.record(it, deletedAt) }
            // The outbox entry belongs in the transaction too: a tombstone nothing queued is a
            // deletion the push never looks at, so the subtree would be gone here and live on the
            // peer. Same rule the single-document `AppRepository.deleteFolderLocally` follows —
            // this is that, for a whole subtree at once.
            couchOutboxRepository.queue(documentIds)
            folderRepository.delete(folderId)
        }
        log.i(
            "Purged folder $folderId: ${scope.childFolderCount} subfolder(s), " +
                "${scope.notebookCount} notebook(s), ${scope.pageCount} page(s)"
        )
        return scope
    }

    suspend fun purgeNotebook(notebookId: String): DeletionScope {
        val scope = scopeOfNotebook(notebookId)
        if (scope.notebooks.isEmpty()) return scope

        // One document, so main's own transaction says it: row, tombstone and outbox entry together.
        appRepository.deleteNotebookLocally(notebookId)
        log.i("Purged notebook $notebookId with ${scope.pageCount} page(s)")
        return scope
    }

    /**
     * Purge everything in the Trash.
     *
     * Folders go first: purging one may cascade away a notebook that is also in the Trash in its
     * own right, and a purge of an already-deleted notebook would then find nothing to scope. The
     * notebook pass re-reads the table afterwards for that reason rather than working from a list
     * taken up front.
     */
    suspend fun emptyTrash(): DeletionScope {
        var folders = emptyList<Folder>()
        var notebooks = emptyList<Notebook>()
        var pages = 0

        folderRepository.getTrashedNow().forEach { folder ->
            val removed = purgeFolder(folder.id)
            folders = folders + removed.folders
            notebooks = notebooks + removed.notebooks
            pages += removed.pageCount
        }
        bookRepository.getTrashedNow().forEach { notebook ->
            val removed = purgeNotebook(notebook.id)
            notebooks = notebooks + removed.notebooks
            pages += removed.pageCount
        }

        return DeletionScope(folders.distinctBy { it.id }, notebooks.distinctBy { it.id }, pages)
    }

    // endregion
}
