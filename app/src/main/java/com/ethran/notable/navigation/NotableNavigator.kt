package com.ethran.notable.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.JournalRepository
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.editor.EditorDestination
import com.ethran.notable.editor.canvas.CanvasEventBus
import com.ethran.notable.editor.utils.refreshScreen
import com.ethran.notable.ui.viewmodels.QuickNavTab
import com.ethran.notable.ui.views.BugReportDestination
import com.ethran.notable.ui.views.LibraryDestination
import com.ethran.notable.ui.views.TodayDestination
import com.ethran.notable.ui.views.PagesDestination
import com.ethran.notable.ui.views.SystemInformationDestination
import com.ethran.notable.ui.views.TrashDestination
import com.ethran.notable.ui.views.WelcomeDestination
import com.ethran.notable.utils.hasUsableStorage
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val log = ShipBook.getLogger("NotableAppState")

@Composable
fun rememberNotableAppState(
    navController: NavHostController = rememberNavController(),
    coroutineScope: CoroutineScope = rememberCoroutineScope()
): NotableNavigator {
    val context = LocalContext.current
    return remember(navController, context, coroutineScope) {
        NotableNavigator(navController, hasUsableStorage(context), coroutineScope)
    }
}

@Stable
class NotableNavigator(
    val navController: NavHostController,
    private val hasUsableStorage: Boolean,
    private val coroutineScope: CoroutineScope
) {
    private val navigationRequests = NavigationRequestGuard()

    var isQuickNavOpen by mutableStateOf(false)
    var quickNavInitialTab by mutableStateOf(QuickNavTab.PAGES)
        private set
    var currentPageId by mutableStateOf<String?>(null)


    val startDestination: String
        get() = if (GlobalAppSettings.current.showWelcome || !hasUsableStorage) WelcomeDestination.route
        else TodayDestination.route

    val quickNavSourcePageId: String?
        get() = navController.currentBackStackEntry?.savedStateHandle?.get<String>("quickNavSourcePageId")

    fun openQuickNav(tab: QuickNavTab = QuickNavTab.PAGES) {
        quickNavInitialTab = tab
        navController.currentBackStackEntry?.savedStateHandle?.set(
            "quickNavSourcePageId", currentPageId
        )
        isQuickNavOpen = true
        updateDrawingState()
    }

    fun closeQuickNav() {
        isQuickNavOpen = false
        updateDrawingState()
        if (quickNavSourcePageId == currentPageId) {
            // User didn't use the QuickNav, so remove the savedStateHandle
            navController.currentBackStackEntry?.savedStateHandle?.remove<String>("quickNavSourcePageId")
        } else {
            // user did change page with QuickNav, start counting page changes
            navController.currentBackStackEntry?.savedStateHandle?.set("pageChangesSinceJump", 2)
        }
        refreshScreen()
    }

    fun goToAnchor(appRepository: AppRepository){
        val targetPageId = quickNavSourcePageId
        if (targetPageId == null) {
            log.e("QuickNav source pageId is null")
            return
        }
        goToPage(appRepository, targetPageId)
    }

    fun shouldAnchorBeVisible(): Boolean {
        return isQuickNavOpen && quickNavSourcePageId != currentPageId
    }

    // Updates the drawing state based on whether QuickNav is open
    fun updateDrawingState() {
        coroutineScope.launch {
            log.d("Changing drawing state, isQuickNavOpen: $isQuickNavOpen")
            CanvasEventBus.isDrawing.emit(!isQuickNavOpen)
        }
    }

    /** Invalidate lookup results immediately, before resolving the exit folder asynchronously. */
    fun cancelPendingNavigation() {
        navigationRequests.begin()
    }

    fun goToLibrary(folderId: String?) {
        navigationRequests.begin()
        navController.navigate(LibraryDestination.createRoute(folderId))
    }

    fun goToToday() {
        navigationRequests.begin()
        if (navController.popBackStack(TodayDestination.route, false)) return
        navController.navigate(TodayDestination.route) {
            popUpTo(navController.graph.id) { inclusive = false }
            launchSingleTop = true
        }
    }

    fun goToJournalEntry(pageId: String, bookId: String) {
        goToEditor(pageId, bookId)
        navController.currentBackStackEntry?.savedStateHandle?.set("fromJournal", true)
    }

    /** Leave the note directly, removing editor/page-manager entries above the library. */
    fun exitEditor(folderId: String?) {
        if (navController.currentBackStackEntry?.savedStateHandle?.get<Boolean>("fromJournal") == true) {
            goToToday()
            return
        }
        navigationRequests.begin()
        navController.navigate(LibraryDestination.createRoute(folderId)) {
            popUpTo(navController.graph.id) { inclusive = false }
            launchSingleTop = true
        }
    }

    fun goToEditor(pageId: String, bookId: String?) {
        navigationRequests.begin()
        navController.navigate(EditorDestination.createRoute(pageId, bookId))
    }

    fun goBack() {
        navigationRequests.begin()
        navController.popBackStack()
    }

    fun goToWelcome() {
        navigationRequests.begin()
        navController.navigate(WelcomeDestination.route)
    }

    fun goToSystemInfo() {
        navigationRequests.begin()
        navController.navigate(SystemInformationDestination.route)
    }

    fun goToTrash() {
        navigationRequests.begin()
        navController.navigate(TrashDestination.route)
    }

    fun goToBugReport(){
        navigationRequests.begin()
        navController.navigate(BugReportDestination.route)
    }

    fun goToPages(bookId: String) {
        navigationRequests.begin()
        navController.navigate(PagesDestination.createRoute(bookId))
    }

    fun goToPage(appRepository: AppRepository, pageId: String) {
        val request = navigationRequests.begin()
        if (pageId == currentPageId) return
        val fromJournal = navController.currentBackStackEntry?.let {
            it.destination.route == TodayDestination.route || it.savedStateHandle.get<Boolean>("fromJournal") == true
        } == true
        coroutineScope.launch {
            val page = try {
                withContext(Dispatchers.IO) {
                    val target = appRepository.pageRepository.getById(pageId) ?: return@withContext null
                    val book = appRepository.bookRepository.getById(target.notebookId)
                    if (book == null || book.deletedAt != null || pageId !in book.pageIds) {
                        return@withContext null
                    }
                    target
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                log.w("Failed to resolve navigation target $pageId", error)
                null
            } ?: return@launch
            if (!navigationRequests.isCurrent(request)) return@launch
            val url = EditorDestination.createRoute(pageId, page.notebookId)
            log.d("navigate -> $url")
            navController.navigate(url) { launchSingleTop = true }
            if (fromJournal) navController.currentBackStackEntry?.savedStateHandle?.set("fromJournal", true)
        }
    }

    /**
     * One tap to a blank page: make a notebook in the folder the user is standing in, and open it.
     *
     * This is what the old quick-page button became. The speed is the same — no dialog, straight
     * into the editor — but what it makes is an ordinary notebook, so the note syncs, is searchable
     * and can be recovered from the Trash. None of which a loose page could do.
     */
    fun onCreateNewNote(journal: JournalRepository, folderId: String?, onFailure: (String) -> Unit = {}) {
        val request = navigationRequests.begin()
        coroutineScope.launch {
            val ids = try {
                withContext(Dispatchers.IO) { journal.createEntry(parentFolderId = folderId) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                log.e("Could not create journal entry", error)
                if (navigationRequests.isCurrent(request)) onFailure("Could not create note. Try again.")
                return@launch
            }
            if (!navigationRequests.isCurrent(request)) return@launch
            navController.navigate(EditorDestination.createRoute(ids.second, ids.first))
        }
    }


    fun onPageChange(backStackEntry: NavBackStackEntry, newPageId: String) {
        // SAVE new pageId in savedStateHandle - do not call navigate
        backStackEntry.savedStateHandle["pageId"] = newPageId
        if (backStackEntry.savedStateHandle.get<Int>("pageChangesSinceJump") == 2) {
            backStackEntry.savedStateHandle["pageChangesSinceJump"] = 1
        } else if (backStackEntry.savedStateHandle.get<Int>("pageChangesSinceJump") == 1) {
            backStackEntry.savedStateHandle.remove<Int>("pageChangesSinceJump")
            backStackEntry.savedStateHandle.remove<String>("quickNavSourcePageId")
        }
        currentPageId = newPageId
        log.d("Editor changed page -> saved pageId=$newPageId (no navigate, no recreate)")
    }

    /**
     * Resolves the pageId from the backStackEntry (prioritizing saved state),
     * synchronizes the internal state, and ensures the SavedStateHandle is updated.
     */
    fun resolveAndSyncPageId(backStackEntry: NavBackStackEntry): String {

        // Priority: SavedStateHandle (for process death/recomposition) > Nav Argument
        val newCurrentPageId =
            backStackEntry.savedStateHandle.get<String>(EditorDestination.PAGE_ID_ARG)
                ?: backStackEntry.arguments?.getString(EditorDestination.PAGE_ID_ARG)!!

        // Sync state
        currentPageId = newCurrentPageId
        backStackEntry.savedStateHandle[EditorDestination.PAGE_ID_ARG] = currentPageId
        return newCurrentPageId
    }

    fun cleanCurrentPageId() {
        currentPageId = null
    }
}
