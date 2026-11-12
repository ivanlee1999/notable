package com.ethran.notable.navigation

import androidx.compose.material.Text
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.db.Page
import com.ethran.notable.editor.EditorDestination
import com.ethran.notable.testing.ComposeUiSupportRule
import com.ethran.notable.ui.views.LibraryDestination
import com.ethran.notable.ui.views.TodayDestination
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class JournalNavigationTest {
    private val compose = createComposeRule()
    @get:Rule val composeGate = ComposeUiSupportRule(compose)
    private lateinit var navigator: NotableNavigator

    private fun graph() {
        compose.setContent {
            val controller = rememberNavController()
            val scope = rememberCoroutineScope()
            navigator = remember(controller, scope) { NotableNavigator(controller, true, scope) }
            NavHost(controller, startDestination = TodayDestination.route) {
                composable(TodayDestination.route) { Text("Today") }
                composable(LibraryDestination.routeWithArgs,
                    arguments = listOf(navArgument(LibraryDestination.FOLDER_ID_ARG) { nullable = true })) {
                    Text("Library")
                }
                composable(EditorDestination.routeWithArgs, arguments = listOf(
                    navArgument(EditorDestination.PAGE_ID_ARG) { type = NavType.StringType },
                    navArgument(EditorDestination.BOOK_ID_ARG) { nullable = true; defaultValue = null },
                )) { Text("Editor") }
            }
        }
    }

    private fun followQuickNavAndExit(fromJournal: Boolean) {
        val app = mockk<AppRepository>(relaxed = true)
        coEvery { app.pageRepository.getById("target-page") } returns
            Page(id = "target-page", notebookId = "target-book")
        coEvery { app.bookRepository.getById("target-book") } returns
            Notebook(id = "target-book", pageIds = listOf("target-page"))
        graph()
        compose.runOnIdle {
            if (fromJournal) navigator.goToJournalEntry("first-page", "first-book")
            else {
                navigator.goToLibrary(null)
                navigator.goToEditor("first-page", "first-book")
            }
        }
        compose.runOnIdle { navigator.goToPage(app, "target-page") }
        compose.waitUntil {
            navigator.navController.currentBackStackEntry?.arguments
                ?.getString(EditorDestination.PAGE_ID_ARG) == "target-page"
        }
        compose.runOnIdle { navigator.exitEditor(null) }
        compose.runOnIdle {
            assertEquals(if (fromJournal) TodayDestination.route else LibraryDestination.routeWithArgs,
                navigator.navController.currentDestination?.route)
        }
    }

    @Test
    fun quickNavFromJournalReturnsToToday() = followQuickNavAndExit(fromJournal = true)

    @Test
    fun quickNavFromLibraryStillReturnsToLibrary() = followQuickNavAndExit(fromJournal = false)
}
