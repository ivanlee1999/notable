package com.ethran.notable.ui.viewmodels


import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.db.Folder
import com.ethran.notable.data.db.Page
import com.ethran.notable.data.db.getParentFolder
import com.ethran.notable.editor.canvas.CanvasEventBus
import com.ethran.notable.io.ThumbnailBackfillQueue
import com.ethran.notable.sync.couch.CouchOutlineEntry
import com.ethran.notable.ui.SnackConf
import com.ethran.notable.ui.SnackDispatcher
import com.ethran.notable.ui.components.getFolderList
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch


/** Which way into the notebook the panel is showing. */
enum class QuickNavTab { PAGES, OUTLINE, BOOKMARKS, PINNED }

data class QuickNavUiState(
    val isLoading: Boolean = true,
    val currentPageId: String? = null,
    val folderId: String? = null,
    val breadcrumbFolders: List<Folder> = emptyList(),
    val bookId: String? = null,
    /**
     * Whether the current page is on the *global* pinned list — the cross-notebook jump list this
     * panel has always had, stored in settings and local to this device. Distinct from
     * [isCurrentPageBookmarked], which is a fact about the notebook and travels.
     */
    val isCurrentPagePinned: Boolean = false,
    val pinnedPages: List<Page> = emptyList(),

    val tab: QuickNavTab = QuickNavTab.PAGES,

    /** Starred pages in this notebook, in page order. */
    val bookmarkedPageIds: List<String> = emptyList(),
    val isCurrentPageBookmarked: Boolean = false,
    /** The table of contents: live entries whose page still exists, in stored order. */
    val outline: List<CouchOutlineEntry> = emptyList(),

    // Scrubber specific state
    val bookPageCount: Int = 0,
    val currentBookIndex: Int = 0,
    val favoriteIndexesInBook: List<Int> = emptyList(),
    val bookPageIds: List<String> = emptyList()
)


class QuickNavViewModel(
    private val appRepository: AppRepository,
    private val thumbnailBackfillQueue: ThumbnailBackfillQueue,
    private val snackDispatcher: SnackDispatcher,
) : ViewModel() {
    private val pageRepository = appRepository.pageRepository
    private val bookRepository = appRepository.bookRepository
    private val kv = appRepository.kvProxy
    private val log = ShipBook.getLogger("QuickNavViewModel")

    private val _uiState = MutableStateFlow(QuickNavUiState())
    val uiState: StateFlow<QuickNavUiState> = _uiState.asStateFlow()
    private var lastScrubEndTargetPageId: String? = null

    // Initialize data when the ViewModel is created or when a new page is opened
    fun loadPageData(currentPageId: String?) {
        if (currentPageId == null) return

        _uiState.update { it.copy(isLoading = true, currentPageId = currentPageId) }

        viewModelScope.launch(Dispatchers.IO) {
            val page = runCatching { pageRepository.getById(currentPageId) }.getOrNull()
            // A page is filed wherever its notebook is filed; it has no folder of its own.
            val folderId = page?.getParentFolder(appRepository.bookRepository)
            val folderList = getFolderList(appRepository, folderId)

            // Read favorites from your database/preferences
            val currentSettings = GlobalAppSettings.current
            val favorites = currentSettings.quickNavPages
            val isFavorite = favorites.contains(currentPageId)

            val favoritePagesDb = appRepository.pageRepository.getByIds(favorites)

            _uiState.update { state ->
                state.copy(
                    folderId = folderId,
                    breadcrumbFolders = folderList,
                    bookId = page?.notebookId,
                    isCurrentPagePinned = isFavorite,
                    pinnedPages = favoritePagesDb,
                    isLoading = false
                )
            }

            // Load Scrubber data if it belongs to a book
            page?.notebookId?.let { loadBookData(it, currentPageId, favorites) }
        }
    }

    /**
     * Re-read the notebook's own bookmarks and outline.
     *
     * Read back from the row after every edit rather than mutated in place: the merge sorts and
     * reorders these lists, so the state after a write is the repository's to say, not something
     * the view model can predict.
     */
    private suspend fun loadNotebookNavigation(bookId: String, currentPageId: String?) {
        val book = bookRepository.getById(bookId) ?: return
        val starred = book.bookmarks.filterNot { it.removed }.map { it.pageId }.toSet()
        val pages = book.pageIds.toSet()
        _uiState.update { state ->
            state.copy(
                bookmarkedPageIds = book.pageIds.filter { it in starred },
                isCurrentPageBookmarked = currentPageId in starred,
                // Dangling entries are skipped rather than repaired. The merge cannot drop them and
                // stay idempotent (protocol §5.2.2), so hiding them here is what keeps a deleted
                // page from leaving a line that does nothing when tapped.
                outline = book.outline.filter { !it.removed && it.pageId in pages },
            )
        }
    }

    fun selectTab(tab: QuickNavTab) {
        _uiState.update { it.copy(tab = tab) }
    }

    fun toggleBookmark() {
        val state = _uiState.value
        val bookId = state.bookId ?: return
        val pageId = state.currentPageId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            bookRepository.setBookmark(bookId, pageId, !state.isCurrentPageBookmarked)
            loadNotebookNavigation(bookId, pageId)
        }
    }

    fun setBookmark(pageId: String, bookmarked: Boolean) {
        val state = _uiState.value
        val bookId = state.bookId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            bookRepository.setBookmark(bookId, pageId, bookmarked)
            loadNotebookNavigation(bookId, state.currentPageId)
        }
    }

    fun addOutlineEntry(pageId: String, title: String) {
        val state = _uiState.value
        val bookId = state.bookId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            bookRepository.addOutlineEntry(bookId, pageId, title)
            loadNotebookNavigation(bookId, state.currentPageId)
        }
    }

    fun renameOutlineEntry(entryId: String, title: String) = editOutline { bookId ->
        bookRepository.updateOutlineEntry(bookId, entryId, title = title)
    }

    fun changeOutlineDepth(entryId: String, depth: Int) = editOutline { bookId ->
        bookRepository.updateOutlineEntry(bookId, entryId, depth = depth)
    }

    fun removeOutlineEntry(entryId: String) = editOutline { bookId ->
        bookRepository.removeOutlineEntry(bookId, entryId)
    }

    /**
     * By entry id and direction, not by index: the tab's list hides removed *and* dangling
     * entries, so an index into it named a different row in the stored outline whenever a hidden
     * entry sat above the tapped one.
     */
    fun moveOutlineEntry(entryId: String, direction: Int) = editOutline { bookId ->
        bookRepository.moveOutlineEntry(bookId, entryId, direction)
    }

    private fun editOutline(edit: suspend (String) -> Unit) {
        val state = _uiState.value
        val bookId = state.bookId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            edit(bookId)
            loadNotebookNavigation(bookId, state.currentPageId)
        }
    }

    private suspend fun loadBookData(
        bookId: String, currentPageId: String, favorites: List<String>
    ) {
        val book = bookRepository.getById(bookId) ?: return
        val currentIdx = appRepository.getPageNumber(bookId, currentPageId)
        val favIndexes = book.pageIds.mapIndexedNotNull { idx, id ->
            if (favorites.contains(id)) idx else null
        }

        // Set unconditionally, where this used to bail out below two pages. The scrubber is still
        // gated on the count by the view that draws it, but the Pages tab needs the page list for a
        // one-page notebook too — and a notebook grows past one page without this being re-read.
        _uiState.update { state ->
            state.copy(
                bookPageCount = book.pageIds.size,
                currentBookIndex = currentIdx,
                favoriteIndexesInBook = favIndexes,
                bookPageIds = book.pageIds
            )
        }
        loadNotebookNavigation(bookId, currentPageId)
    }

    /** Pin or unpin the current page on the global, device-local jump list. */
    fun togglePinned() {
        val currentState = _uiState.value
        val pageId = currentState.currentPageId ?: return

        viewModelScope.launch(Dispatchers.IO) {
            val settings = GlobalAppSettings.current
            val currentFavorites = settings.quickNavPages
            val isFav = currentFavorites.contains(pageId)

            val newFavorites = if (isFav) {
                currentFavorites.filterNot { it == pageId }
            } else {
                currentFavorites + pageId
            }

            // Save to DB
            kv.setAppSettings(settings.copy(quickNavPages = newFavorites))

            // Update UI State locally immediately
            _uiState.update { it.copy(isCurrentPagePinned = !isFav) }

            // Re-fetch the rich page objects for the ShowPagesRow
            val updatedPinnedPages = appRepository.pageRepository.getByIds(newFavorites)
            _uiState.update { it.copy(pinnedPages = updatedPinnedPages) }
        }
    }

    // --- Scrubber Actions ---

    fun onScrubStart() {
        viewModelScope.launch {
            lastScrubEndTargetPageId = null
            CanvasEventBus.saveCurrent.emit(Unit)
            CanvasEventBus.isScrubbing.emit(true)
        }
    }

    fun onScrubPreview(index: Int) {
        val pageIds = _uiState.value.bookPageIds
        viewModelScope.launch {
            if (index in pageIds.indices) {
                CanvasEventBus.previewPage.tryEmit(pageIds[index])
            }
        }
    }

    fun onScrubEnd(index: Int) {
        val pageIds = _uiState.value.bookPageIds
        val targetPageId = pageIds.getOrNull(index) ?: return

        viewModelScope.launch {
            log.v("onScrubEnd: $index")

            // moved, to be only run if we are changing page to the current page
//            CanvasEventBus.restoreCanvas.emit(Unit)

            CanvasEventBus.isScrubbing.emit(false)

            // Gesture end callbacks can fire more than once; ignore repeated commit for same target.
            if (targetPageId == lastScrubEndTargetPageId)
            {
                log.e("Events can be send multiple times, really")
                return@launch
            }
            lastScrubEndTargetPageId = targetPageId


            CanvasEventBus.changePage.emit(targetPageId)
        }
    }

    fun onReturnClick(quickNavSourcePageId: String?) {
        if (quickNavSourcePageId == null) {
            snackDispatcher.showOrUpdateSnack(
                SnackConf(text = "Can't go back, no QuickNav source page", duration = 4000)
            )
        } else {
            CanvasEventBus.changePage.tryEmit(quickNavSourcePageId)
        }
    }

    fun generateThumbnailsForCurrentBook() {
        val pageIds = _uiState.value.bookPageIds
        if (pageIds.isEmpty()) return
        thumbnailBackfillQueue.enqueue(pageIds)
    }
}