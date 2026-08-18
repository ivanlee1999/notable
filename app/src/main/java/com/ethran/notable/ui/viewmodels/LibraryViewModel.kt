package com.ethran.notable.ui.viewmodels

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asFlow
import androidx.lifecycle.viewModelScope
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.PageDataManager
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.db.Folder
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.model.BackgroundType
import com.ethran.notable.data.model.PageSize
import com.ethran.notable.io.ExportEngine
import com.ethran.notable.io.ImportEngine
import com.ethran.notable.io.ImportOptions
import com.ethran.notable.io.ThumbnailBackfillQueue
import com.ethran.notable.editor.utils.PreviewSaveMode
import com.ethran.notable.ui.SnackConf
import com.ethran.notable.ui.SnackDispatcher
import com.ethran.notable.utils.fold
import com.ethran.notable.utils.isLatestVersion
import com.ethran.notable.data.events.AppEventBus
import com.ethran.notable.sync.NotebookSyncStatusStore
import com.ethran.notable.sync.SyncBadge
import com.ethran.notable.sync.SyncProgressReporter
import com.ethran.notable.sync.SyncScheduler
import com.ethran.notable.sync.SyncState
import com.ethran.notable.sync.couch.CouchDocId
import com.ethran.notable.sync.couch.CouchSyncController
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Date
import javax.inject.Inject

data class LibraryUiState(
    val folderId: String? = null,
    val isLatestVersion: Boolean = true,
    val isImporting: Boolean = false,
    val breadcrumbFolders: List<Folder> = emptyList(),
    val folders: List<Folder> = emptyList(),
    val books: List<Notebook> = emptyList(),
    val syncBadges: Map<String, SyncBadge> = emptyMap(),
    /** A sync is running right now, whichever backend is driving it. */
    val isSyncing: Boolean = false,
    /** Notebooks directly inside each folder, keyed by folder id — the count on its row. */
    val folderBookCounts: Map<String, Int> = emptyMap(),
    /** Each notebook's newest page clock, for the "Last edited" order. */
    val lastEdited: Map<String, Date> = emptyMap(),
    /** How much is waiting in the Trash; 0 hides the row entirely. */
    val trashedCount: Int = 0,
    /** What the user is looking for. Empty means "show me where I am" rather than "show nothing". */
    val query: String = "",
) {
    val isSearching: Boolean get() = query.isNotBlank()
}

/** What a search turned up. Null rather than an instance of this means no search is running. */
private data class SearchResults(
    val folders: List<Folder>,
    val books: List<Notebook>,
)

/** The screen's own state, grouped so `combine` stays inside its typed overloads. */
private data class LibraryScreenState(
    val folderId: String? = null,
    val isLatestVersion: Boolean = true,
    val isImporting: Boolean = false,
    val breadcrumbFolders: List<Folder> = emptyList(),
    val query: String = "",
)

// Private data class for clean Flow combining
private data class LibraryDatabaseState(
    val folders: List<Folder> = emptyList(),
    val books: List<Notebook> = emptyList(),
    val syncBadges: Map<String, SyncBadge> = emptyMap(),
    val isSyncing: Boolean = false,
    val folderBookCounts: Map<String, Int> = emptyMap(),
    val trashedCount: Int = 0,
    val lastEdited: Map<String, Date> = emptyMap(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(
    val appRepository: AppRepository,
    private val appEventBus: AppEventBus,
    val importEngine: ImportEngine,
    val exportEngine: ExportEngine,
    private val thumbnailBackfillQueue: ThumbnailBackfillQueue,
    val pageDataManager: PageDataManager,
    private val snackDispatcher: SnackDispatcher,
    val syncScheduler: SyncScheduler,
    private val syncStatusStore: NotebookSyncStatusStore,
    private val syncProgressReporter: SyncProgressReporter,
    private val couchSync: CouchSyncController,
    @param:ApplicationContext private val context: Context // Kept strictly for ImportEngine
) : ViewModel() {

    private val bookRepository = appRepository.bookRepository
    private val folderRepository = appRepository.folderRepository
    private val pageRepository = appRepository.pageRepository

    private val _folderId = MutableStateFlow<String?>(null)
    private val _isImporting = MutableStateFlow(false)
    private val _isLatestVersion = MutableStateFlow(true)
    private val _breadcrumbFolders = MutableStateFlow<List<Folder>>(emptyList())

    // 1. Convert LiveData to Flow and switch automatically when folderId changes
    private val _foldersFlow =
        _folderId.flatMapLatest { id -> folderRepository.getAllInFolder(id).asFlow() }
    private val _booksFlow =
        _folderId.flatMapLatest { id -> bookRepository.getAllInFolder(id).asFlow() }

    // Counted across the whole table rather than per visible folder: one flow covers every
    // folder row on screen, instead of one query each.
    private val _folderBookCountsFlow = bookRepository.getAllFlow().map { books ->
        books.mapNotNull { it.parentFolderId }.groupingBy { it }.eachCount()
    }

    private val _query = MutableStateFlow("")

    /**
     * What a search finds, anywhere in the library.
     *
     * Deliberately not scoped to the folder the user is standing in: the reason to search is not
     * knowing where the thing is, so a search that only looked here would answer a question nobody
     * asked. Empty while the query is blank, so the ordinary listing costs nothing.
     */
    private val _searchResultsFlow = combine(
        _query, folderRepository.getAllVisibleFlow(), bookRepository.getAllFlow()
    ) { query, folders, books ->
        if (query.isBlank()) null
        else SearchResults(
            folders = folders.filter { LibrarySort.matches(it.title, query) },
            books = books.filter { LibrarySort.matches(it.title, query) },
        )
    }

    private val _trashedCountFlow = combine(
        folderRepository.getTrashed().asFlow(), bookRepository.getTrashed().asFlow()
    ) { folders, books -> folders.size + books.size }.distinctUntilChanged()

    /** Each notebook's newest page clock — what "Last edited" means now that ink lands on pages
     *  without bumping the notebook's envelope (the merge's clock for renames and moves). */
    private val _lastEditedFlow = pageRepository.lastEditedByNotebookFlow()
        .map { rows -> rows.associate { it.notebookId to it.lastEdited } }

    // Grouped rather than combined as sixth/seventh flows: `combine` has typed overloads up to
    // five, and one more argument would drop the whole group into the untyped array form.
    private val _countsFlow = combine(
        _folderBookCountsFlow, _trashedCountFlow, _lastEditedFlow
    ) { folderCounts, trashed, lastEdited -> Triple(folderCounts, trashed, lastEdited) }

    // Whether *anything* is syncing, as opposed to the per-notebook badges: the two backends report
    // through different channels, and the header's Sync now button is about the run as a whole.
    //
    // Flattened to the boolean the header actually reads before it joins the screen state. A run
    // reports every page of every notebook through [SyncProgressReporter], and all of that says the
    // same one thing up here; stopping it at this line keeps a long sync from rebuilding the whole
    // library state a few hundred times to arrive at "still true".
    private val _isSyncingFlow = combine(
        syncProgressReporter.state, couchSync.state
    ) { webdav, couch ->
        webdav is SyncState.Syncing || couch.status is CouchSyncController.Status.Syncing
    }.distinctUntilChanged()

    private val _syncStatusFlow = combine(
        syncStatusStore.badges, _isSyncingFlow
    ) { badges, syncing -> badges to syncing }

    // 2. Group the database flows (plus per-notebook sync badges) semantically
    /**
     * The folder the user is standing in, or — while a search is running — what the search found
     * anywhere in the library.
     */
    private val _listingFlow = combine(
        _foldersFlow, _booksFlow, _searchResultsFlow
    ) { folders, books, found ->
        // Null, not empty: a search that found nothing has to show nothing, and falling back to
        // the folder's contents would look like the search had simply been ignored.
        if (found == null) folders to books
        else found.folders to found.books
    }

    private val _dbDataFlow = combine(
        _listingFlow, _syncStatusFlow, _countsFlow
    ) { (folders, books), (badges, syncing), (folderCounts, trashed, lastEdited) ->
        LibraryDatabaseState(
            folders, books, badges, syncing, folderCounts, trashed, lastEdited
        )
    }

    // 3. Expose the final UI State
    private val _screenFlow = combine(
        _folderId, _isLatestVersion, _isImporting, _breadcrumbFolders, _query
    ) { folderId, isLatest, isImporting, breadcrumbs, query ->
        LibraryScreenState(folderId, isLatest, isImporting, breadcrumbs, query)
    }

    val uiState: StateFlow<LibraryUiState> = combine(
        _screenFlow, _dbDataFlow
    ) { screen, dbData ->
        LibraryUiState(
            folderId = screen.folderId,
            isLatestVersion = screen.isLatestVersion,
            isImporting = screen.isImporting,
            breadcrumbFolders = screen.breadcrumbFolders,
            folders = dbData.folders,
            books = dbData.books,
            syncBadges = dbData.syncBadges,
            isSyncing = dbData.isSyncing,
            folderBookCounts = dbData.folderBookCounts,
            trashedCount = dbData.trashedCount,
            lastEdited = dbData.lastEdited,
            query = screen.query
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = LibraryUiState()
    )


    init {
        // Run network/heavy checks in the background
        viewModelScope.launch(Dispatchers.IO) {
            _isLatestVersion.value = isLatestVersion(context, appEventBus, true)
        }
    }

    fun onPreviewRequested(pageId: String) {
        thumbnailBackfillQueue.enqueue(listOf(pageId))
    }

    fun loadFolder(folderId: String?) {
        pageDataManager.cancelLoadingPages()
        _folderId.value = folderId

        // Resolve breadcrumbs in background thread
        viewModelScope.launch(Dispatchers.IO) {
            _breadcrumbFolders.value = resolveBreadcrumbs(folderId)
        }
    }

    private suspend fun resolveBreadcrumbs(folderId: String?): List<Folder> {
        if (folderId == null) return emptyList()

        val list = mutableListOf<Folder>()
        var currentId: String? = folderId

        while (currentId != null) {
            val folder = folderRepository.get(currentId)
            if (folder != null) {
                list.add(folder)
                currentId = folder.parentFolderId
            } else {
                currentId = null
            }
        }
        return list.reversed()
    }

    fun onQueryChanged(query: String) {
        _query.value = query
    }

    /**
     * Rearranges the library and remembers the choice.
     *
     * Through `setAppSettings` rather than a write of its own: that is the one call that both
     * persists and republishes the snapshot state the whole UI reads, and doing half of it here
     * would leave the shelf sorted until the next launch.
     */
    fun onSortChanged(order: LibrarySortOrder, descending: Boolean) {
        val updated = GlobalAppSettings.current.copy(
            librarySortOrder = order.name,
            librarySortDescending = descending,
        )
        viewModelScope.launch(Dispatchers.IO) {
            appRepository.kvProxy.setAppSettings(updated)
        }
    }

    fun createNewFolder(title: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val folder = Folder(title = title, parentFolderId = _folderId.value)
            folderRepository.create(folder)
            // The outbox entry went in with the row; this only starts the debounce. See
            // [onCreateNewNotebook].
            couchSync.noteDocumentChanged(CouchDocId.folder(folder.id))
        }
    }

    /**
     * The user accepted the prompt offering to remove a notebook with no pages.
     *
     * It goes through [AppRepository.deleteNotebookLocally] like every other deletion the user
     * makes, and for the reason that keeps coming back: absence is not a syncable fact. A bare
     * `bookRepository.delete` leaves this device holding nothing and telling the server nothing, so
     * the peer's copy is still there and comes straight back on the next merge — the notebook
     * resurrects itself, and the prompt appears again.
     *
     * A notebook with no pages is a notebook the peer may well have pages for, which is the case
     * that makes this more than tidiness: the empty copy here is often the *incomplete* one.
     */
    fun deleteEmptyBook(bookId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            appRepository.deleteNotebookLocally(bookId)
            // Only for promptness — the tombstone and the outbox row went in with the delete.
            couchSync.noteDeleted(CouchDocId.notebook(bookId))
        }
    }

    /**
     * The sheet and template are the notebook's, not the app's: they are asked for when it is
     * created (see `NewNotebookDialog`) and only fall back to the global defaults for the paths
     * that create a notebook without asking. The first page inherits both from the notebook, so
     * what is chosen here is what the user writes on.
     */
    fun onCreateNewNotebook(
        title: String,
        pageSize: PageSize = GlobalAppSettings.current.defaultPageSize,
        template: String = GlobalAppSettings.current.defaultNativeTemplate
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val notebook = Notebook(
                title = title,
                parentFolderId = _folderId.value,
                defaultBackground = template,
                defaultBackgroundType = BackgroundType.Native.key,
                defaultPageWidth = pageSize.width,
                defaultPageHeight = pageSize.height
            )
            // `create` writes the notebook, its first page and both outbox entries in one
            // transaction, so the change is already durable and complete by the time this returns.
            bookRepository.create(notebook)
            // Kept for promptness only: this starts the debounce and updates the badge, rather than
            // waiting for the next flush to notice the rows. Forgetting it would cost a delay, not
            // the notebook — which is the whole point of moving the queueing into the repository.
            couchSync.noteDocumentChanged(CouchDocId.notebook(notebook.id))
        }
    }

    fun onPdfFile(uri: Uri, copy: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val snackText =
                if (copy) "Importing PDF background (copy)" else "Setting up observer for PDF"

            _isImporting.value = true
            snackDispatcher.showOrUpdateSnack(SnackConf(text = snackText, duration = 2000))

            try {
                // Ideally, ImportEngine should be injected via Hilt rather than instantiated here
                val result = importEngine.import(
                    uri, ImportOptions(folderId = _folderId.value, linkToExternalFile = !copy)
                )
                
                result.fold(
                    onSuccess = { importedPageIds ->
                        if (importedPageIds.isNotEmpty()) {
                            thumbnailBackfillQueue.enqueue(importedPageIds, PreviewSaveMode.STRICT_BW)
                        }
                        snackDispatcher.showOrUpdateSnack(SnackConf(text = "PDF Import Successful"))
                    },
                    onError = { error ->
                        snackDispatcher.showOrUpdateSnack(SnackConf(text = "Import failed: ${error.userMessage}"))
                    }
                )
            } catch (e: Exception) {
                snackDispatcher.showOrUpdateSnack(SnackConf(text = "Import failed: ${e.message}"))
            } finally {
                _isImporting.value = false
            }
        }
    }

    fun onXoppFile(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _isImporting.value = true
            snackDispatcher.showOrUpdateSnack(
                SnackConf(
                    text = "Importing from xopp file...",
                    duration = 2000
                )
            )

            try {
                val result = importEngine.import(uri, ImportOptions(folderId = _folderId.value))
                result.fold(
                    onSuccess = { _ -> 
                        snackDispatcher.showOrUpdateSnack(SnackConf(text = "XOPP Import Successful", duration = 3000))
                    },
                    onError = { error ->
                        snackDispatcher.showOrUpdateSnack(SnackConf(text = "Import failed: ${error.userMessage}"))
                    }
                )
            } catch (e: Exception) {
                snackDispatcher.showOrUpdateSnack(SnackConf(text = "Import failed: ${e.message}"))
            } finally {
                _isImporting.value = false
            }
        }
    }

}
