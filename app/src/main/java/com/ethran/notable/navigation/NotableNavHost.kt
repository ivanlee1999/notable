package com.ethran.notable.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.ethran.notable.data.datastore.RecentNotebooks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.JournalRepository
import com.ethran.notable.ui.LocalSnackContext
import com.ethran.notable.ui.SnackConf
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.ethran.notable.editor.EditorDestination
import com.ethran.notable.editor.EditorView
import com.ethran.notable.io.ExportEngine
import com.ethran.notable.ui.views.BugReportDestination
import com.ethran.notable.ui.views.BugReportScreen
import com.ethran.notable.ui.views.Library
import com.ethran.notable.ui.views.LibraryDestination
import com.ethran.notable.ui.views.TodayDestination
import com.ethran.notable.ui.views.TodayView
import com.ethran.notable.ui.components.TextAction
import com.ethran.notable.ui.views.PagesDestination
import com.ethran.notable.ui.views.PagesView
import com.ethran.notable.ui.views.SettingsDestination
import com.ethran.notable.ui.views.SettingsView
import com.ethran.notable.ui.views.SystemInformationDestination
import com.ethran.notable.ui.views.SystemInformationView
import com.ethran.notable.ui.views.TrashDestination
import com.ethran.notable.ui.views.TrashView
import com.ethran.notable.ui.views.WelcomeDestination
import com.ethran.notable.ui.views.WelcomeView
import io.shipbook.shipbooksdk.ShipBook

private val log = ShipBook.getLogger("NotableNavHost")

@Composable
fun NotableNavHost(
    exportEngine: ExportEngine,
    appRepository: AppRepository,
    journalRepository: JournalRepository,
    modifier: Modifier = Modifier,
    appNavigator: NotableNavigator
) {
    val snackState = LocalSnackContext.current
    val scope = rememberCoroutineScope()

    Box(modifier = modifier.fillMaxSize()) {
        NavHost(
            navController = appNavigator.navController,
            startDestination = appNavigator.startDestination,
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None },
        ) {
            composable(TodayDestination.route) {
                TodayView(
                    onOpen = { page, book -> appNavigator.goToJournalEntry(page, book) },
                    onLibrary = { appNavigator.goToLibrary(null) },
                    onPages = { book -> appNavigator.goToPages(book) },
                )
                appNavigator.cleanCurrentPageId()
            }
            composable(
                route = LibraryDestination.routeWithArgs,
                arguments = listOf(navArgument(LibraryDestination.FOLDER_ID_ARG) {
                    nullable = true
                }),
            ) {
                Column {
                    TextAction("Journal", { appNavigator.goToToday() }, Modifier.padding(8.dp))
                    Box(Modifier.weight(1f)) {
                        Library(
                            navController = appNavigator.navController,
                            folderId = it.arguments?.getString(LibraryDestination.FOLDER_ID_ARG),
                            goToPage = { pageId -> appNavigator.goToPage(appRepository, pageId) },
                            onCreateNewNote = { folderId ->
                                appNavigator.onCreateNewNote(journalRepository, folderId) { message ->
                                    scope.launch { snackState.showOrUpdateSnack(SnackConf(text = message, duration = 3000)) }
                                }
                            }
                        )
                    }
                }
                appNavigator.cleanCurrentPageId()
            }
            composable(
                route = WelcomeDestination.route,
            ) {
                WelcomeView(
                    goToLibrary = { appNavigator.goToToday() },
                )
                appNavigator.cleanCurrentPageId()
            }
            composable(
                route = SystemInformationDestination.route,
            ) {
                SystemInformationView(
                    onBack = { appNavigator.goBack() },
                )
                appNavigator.cleanCurrentPageId()
            }

            composable(
                route = EditorDestination.routeWithArgs,
                arguments = listOf(
                    navArgument(EditorDestination.PAGE_ID_ARG) { type = NavType.StringType },
                    navArgument(EditorDestination.BOOK_ID_ARG) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { backStackEntry ->
                val bookId = backStackEntry.arguments?.getString(EditorDestination.BOOK_ID_ARG)

                val currentPageId = appNavigator.resolveAndSyncPageId(backStackEntry)
                val context = LocalContext.current
                LaunchedEffect(bookId) {
                    if (bookId != null) withContext(Dispatchers.IO) {
                        try {
                            val book = appRepository.bookRepository.getById(bookId)
                            if (book != null && book.deletedAt == null) {
                                RecentNotebooks.get(context).record(bookId)
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            // Reading history is optional; failure must never prevent editing.
                            log.w("Could not remember recently opened notebook", error)
                        }
                    }
                }

                EditorView(
                    goToLibrary = { appNavigator.exitEditor(it) },
                    onExitRequested = { appNavigator.cancelPendingNavigation() },
                    goToPages = { bookId -> appNavigator.goToPages(bookId) },
                    openQuickNav = { appNavigator.openQuickNav() },
                    openNoteSwitcher = { appNavigator.openQuickNav(com.ethran.notable.ui.viewmodels.QuickNavTab.NOTES) },
                    goToBugReport = { appNavigator.goToBugReport() },
                    bookId = bookId,
                    initialPageId = currentPageId,
                    isQuickNavOpen = appNavigator.isQuickNavOpen,
                    onNewEntry = { page, book -> appNavigator.goToJournalEntry(page, book) },
                    onPageChange = { newPageId ->
                        log.d("onPageChange: $newPageId")
                        appNavigator.onPageChange(
                            backStackEntry,
                            newPageId
                        )
                    }
                )
            }
            composable(
                route = PagesDestination.routeWithArgs,
                arguments = listOf(navArgument(PagesDestination.BOOK_ID_ARG) {
                    /* configuring arguments for navigation */
                    type = NavType.StringType
                }),
            ) { pagesEntry ->
                val fromJournal = pagesEntry.savedStateHandle.get<Boolean>("fromJournal") ?: run {
                    val value = appNavigator.navController.previousBackStackEntry
                        ?.let { it.destination.route == TodayDestination.route || it.savedStateHandle.get<Boolean>("fromJournal") == true } == true
                    pagesEntry.savedStateHandle["fromJournal"] = value
                    value
                }
                PagesView(
                    goToLibrary = { folderId -> if (fromJournal) appNavigator.goToToday() else appNavigator.goToLibrary(folderId) },
                    goToEditor = { pageId, bId ->
                        if (fromJournal) appNavigator.goToJournalEntry(pageId, bId)
                        else appNavigator.goToEditor(pageId, bId)
                    },
                    bookId = pagesEntry.arguments?.getString(PagesDestination.BOOK_ID_ARG)!!,
                )
            }
            composable(
                route = SettingsDestination.route,
            ) {
                SettingsView(
                    onBack = { appNavigator.goBack() },
                    goToWelcome = { appNavigator.goToWelcome() },
                    goToSystemInfo = { appNavigator.goToSystemInfo() }
                )
                appNavigator.cleanCurrentPageId()
            }
            composable(
                route = TrashDestination.route,
            ) {
                TrashView(onBack = { appNavigator.goBack() })
                appNavigator.cleanCurrentPageId()
            }
            composable(
                route = BugReportDestination.route,
            ) {
                BugReportScreen(goBack = { appNavigator.goBack() })
                appNavigator.cleanCurrentPageId()
            }
        }
    }
}
