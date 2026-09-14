package com.ethran.notable.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ethran.notable.testing.ComposeUiSupportRule
import com.ethran.notable.data.datastore.AppSettings
import com.ethran.notable.sync.SyncBackend
import com.ethran.notable.sync.SyncSettings
import com.ethran.notable.ui.theme.InkaTheme
import com.ethran.notable.ui.theme.Kaleido
import com.ethran.notable.ui.theme.kaleidoMetrics
import com.ethran.notable.ui.viewmodels.LibraryUiState
import com.ethran.notable.ui.viewmodels.PagesUiState
import com.ethran.notable.ui.viewmodels.SyncSettingsUiState
import com.ethran.notable.ui.views.LibraryHeader
import com.ethran.notable.ui.views.PagesContent
import com.ethran.notable.ui.views.SettingsContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Exercise the actual controls at constrained sizes; PNGs accompany CI for visual review.
 * Inspection mode replaces only asynchronous page images, avoiding a database in layout tests.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class UiConsistencyComposeTests {
    private val compose = createComposeRule()
    @get:Rule val composeGate = ComposeUiSupportRule(compose)

    private fun content(width: Int = 320, fontScale: Float = 1f, body: @Composable () -> Unit) {
        compose.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(DpSize(width.dp, 900.dp)) then
                    DeviceConfigurationOverride.FontScale(fontScale)
            ) {
                CompositionLocalProvider(LocalInspectionMode provides true) {
                    InkaTheme {
                        Surface(Modifier.fillMaxSize(), color = Kaleido.Paper, content = body)
                    }
                }
            }
        }
    }

    private fun screenshot(name: String, dialog: Boolean = false) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.getExternalFilesDir(null), "ui-review").apply { mkdirs() }
        compose.waitForIdle()
        // Capture the rendered Compose window, including a dialog's separate root when present.
        val root = if (dialog) compose.onNode(isDialog()) else compose.onRoot()
        val bitmap = root.captureToImage().asAndroidBitmap()
        File(directory, "$name.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    @Composable
    private fun TestPages(
        state: PagesUiState,
        onBack: (String?) -> Unit = {},
        onOpen: (String) -> Unit = {},
        onDelete: (String) -> Unit = {},
        onDuplicate: (String) -> Unit = {},
        onAdd: (Int) -> Unit = {},
    ) = PagesContent(state, onBack, onOpen, {}, { _, _ -> }, onDelete, onDuplicate, onAdd)

    @Test
    fun compactLibraryKeepsCreationViewAndSettingsReachableAtLargeText() {
        var created = 0
        var settingsOpened = 0
        content(width = 320, fontScale = 1.5f) {
            var grid by remember { mutableStateOf(true) }
            BoxWithConstraints {
                LibraryHeader(
                    metrics = kaleidoMetrics(maxWidth), uiState = LibraryUiState(),
                    onCreateNewNotebook = { created++ },
                    onNavigateToSettings = { settingsOpened++ },
                    gridView = grid, onGridChanged = { grid = it },
                )
            }
        }
        compose.onNodeWithText("Library").assertIsDisplayed()
        // The two visible actions are icon squares now, so they are addressed the way a screen
        // reader addresses them — by description, not by a label the header no longer spends a
        // row on.
        compose.onNodeWithContentDescription("New notebook").assertIsDisplayed().performClick()
        assertEquals(1, created)
        screenshot("library-header-320-large-text")
        // Layout, settings and the rest all live behind the one gear.
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("List").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("More").performClick()
        // Substring, because MenuAction ticks the chosen entry by rewriting its label to
        // "✓ List" — an exact match would look for a string the menu no longer draws.
        compose.onNodeWithText("List", substring = true).assertIsSelected()
        compose.onNodeWithText("Grid", substring = true).assertIsNotSelected()
        compose.onNodeWithText("Settings").assertIsDisplayed().performClick()
        assertEquals(1, settingsOpened)
    }

    @Test
    fun intermediateLibraryWidthKeepsTitleAndSearchVisible() {
        content(width = 600) {
            BoxWithConstraints {
                LibraryHeader(metrics = kaleidoMetrics(maxWidth), uiState = LibraryUiState())
            }
        }
        compose.onNodeWithText("Library").assertIsDisplayed()
        compose.onNodeWithContentDescription("Search notebooks and folders").assertIsDisplayed()
        compose.onNodeWithText("Sort").assertIsDisplayed()
        compose.onNodeWithContentDescription("New notebook").assertIsDisplayed()
        compose.onNodeWithContentDescription("More").assertIsDisplayed()
        screenshot("library-header-600")
    }

    private fun checkSyncSettings(backend: SyncBackend, width: Int) {
        var returned = false
        content(width = width, fontScale = 1.5f) {
            SettingsContent(
                versionString = "0.50.0", settings = AppSettings(version = 1), isLatestVersion = true,
                onBack = { returned = true }, goToWelcome = {}, goToSystemInfo = {},
                onCheckUpdate = {}, onUpdateSettings = {}, selectedTabInitial = 3,
                syncUiState = SyncSettingsUiState(syncSettings = SyncSettings(backend = backend)),
            )
        }
        compose.onNodeWithText("Experimental Feature").assertDoesNotExist()
        compose.onNodeWithText("Sync").assertIsSelected().assertIsDisplayed()
        screenshot("settings-${backend.name.lowercase()}-$width-large-text")
        listOf("Off" to SyncBackend.OFF, "WebDAV" to SyncBackend.WEBDAV,
            "CouchDB" to SyncBackend.COUCHDB).forEach { (label, choice) ->
            val option = compose.onNodeWithText(label)
            option.assertIsDisplayed().assertHasClickAction().assertHeightIsAtLeast(48.dp)
            if (choice == backend) option.assertIsSelected() else option.assertIsNotSelected()
            val layouts = mutableListOf<TextLayoutResult>()
            option.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals("$label must stay on one readable line", 1, layouts.single().lineCount)
            val layout = layouts.single()
            val details = "$label must fit its control: size=${layout.size}, " +
                "paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}, " +
                "constraints=${layout.layoutInput.constraints}, " +
                "line=${layout.getLineLeft(0)}..${layout.getLineRight(0)}, " +
                "bottom=${layout.getLineBottom(0)}"
            // GetTextLayoutResult can reconstruct a paragraph at the available maximum width
            // while retaining the Text node's tight size. Check the actual line, not that blank
            // paragraph area (hasVisualOverflow would report it as clipped text).
            assertFalse(details, layout.isLineEllipsized(0))
            assertTrue(details, layout.getLineLeft(0) >= 0f &&
                layout.getLineRight(0) <= layout.size.width &&
                layout.getLineBottom(0) <= layout.size.height)
        }
        val back = compose.onNodeWithContentDescription("Back to library")
        back.assertWidthIsAtLeast(44.dp).assertHeightIsAtLeast(44.dp).assertIsDisplayed()
        back.performClick()
        assertEquals(true, returned)
    }

    @Test
    fun disabledSyncHasNoWebDavWarningAndCanReturnToLibrary() = checkSyncSettings(SyncBackend.OFF, 320)

    @Test
    fun couchSyncHasNoWebDavWarningAndCanReturnToLibrary() = checkSyncSettings(SyncBackend.COUCHDB, 600)

    @Test
    fun loadingKeepsAnAccessibleWayBack() {
        var returned = false
        content { TestPages(PagesUiState(), onBack = { returned = true }) }
        compose.onNodeWithText("Loading pages…").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back to library").assertIsDisplayed().performClick()
        assertEquals(true, returned)
        screenshot("pages-loading-320")
    }

    @Test
    fun compactPageMenusKeepActionsReachableAndRequireDeletionConfirmation() {
        val deleted = mutableListOf<String>()
        val duplicated = mutableListOf<String>()
        val opened = mutableListOf<String>()
        content {
            TestPages(
                PagesUiState(bookId = "ui-review", bookTitle = "Design notes", pageIds = listOf("p1"),
                    openPageId = "p1", isLoading = false),
                onOpen = { opened += it }, onDelete = { deleted += it },
                onDuplicate = { duplicated += it },
            )
        }
        compose.onNodeWithContentDescription("Open page 1").assertIsSelected().performClick()
        assertEquals(listOf("p1"), opened)
        screenshot("pages-320")
        compose.onNodeWithContentDescription("Page 1 options")
            .assertWidthIsAtLeast(44.dp).assertHeightIsAtLeast(44.dp).performClick()
        compose.onNodeWithText("Duplicate page").assertIsDisplayed().performClick()
        assertEquals(listOf("p1"), duplicated)
        compose.onNodeWithContentDescription("Page 1 options").performClick()
        compose.onNodeWithText("Delete page").assertIsDisplayed().performClick()
        compose.onNodeWithText("Delete this page?").assertIsDisplayed()
        assertEquals(emptyList<String>(), deleted)
        screenshot("page-delete-confirmation-320", dialog = true)
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(emptyList<String>(), deleted)
        compose.onNodeWithContentDescription("Page 1 options").performClick()
        compose.onNodeWithText("Delete page").performClick()
        compose.onNodeWithText("Delete page").performClick()
        assertEquals(listOf("p1"), deleted)
    }

    @Test
    fun largeTextKeepsCreationAndOrganizationReachable() {
        val added = mutableListOf<Int>()
        content(width = 600, fontScale = 1.5f) {
            TestPages(PagesUiState(bookId = "ui-review", bookTitle = "Planning and handwritten meeting notes",
                pageIds = listOf("p1", "p2", "p3"), openPageId = "p1", isLoading = false),
                onAdd = { added += it })
        }
        compose.onNodeWithText("Add page").assertIsDisplayed().performClick()
        assertEquals(listOf(3), added)
        compose.onNodeWithText("Organize pages").assertIsDisplayed().performClick()
        compose.onNodeWithText("Done organizing").assertIsSelected()
        compose.onNodeWithContentDescription("Open page 1").assertIsNotEnabled()
        screenshot("pages-600-large-text-organize")
        compose.onNodeWithText("Done organizing").performClick()
        compose.onNodeWithContentDescription("Open page 1").assertIsEnabled()
    }

    @Test
    fun tabletPageOverviewKeepsCurrentPageAndOptionsVisible() {
        content(width = 1200) {
            TestPages(PagesUiState(bookId = "ui-review", bookTitle = "Field notebook",
                pageIds = (1..8).map { "p$it" }, openPageId = "p1", isLoading = false))
        }
        compose.onNodeWithContentDescription("Open page 1").assertIsDisplayed().assertIsSelected()
        compose.onNodeWithContentDescription("Page 1 options").assertIsDisplayed()
        compose.onNodeWithText("Current page").assertIsDisplayed().performClick()
        screenshot("pages-1200")
    }
}
