package com.ethran.notable.ui

import com.ethran.notable.editor.ToolbarAction
import com.ethran.notable.editor.ToolbarUiState
import com.ethran.notable.editor.ui.EditorTitleBar
import com.ethran.notable.ui.components.NotebookSwitcherList
import com.ethran.notable.ui.components.QuickNavContent
import com.ethran.notable.ui.viewmodels.QuickNavUiState
import com.ethran.notable.ui.viewmodels.QuickNavTab
import com.ethran.notable.data.db.Notebook
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.editor.ui.toolbar.ToolbarContent
import com.ethran.notable.editor.ui.toolbar.toolbarInset
import com.ethran.notable.editor.ui.toolbar.model.ToolbarPen
import com.ethran.notable.editor.state.Mode
import com.ethran.notable.editor.utils.Pen
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
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

    private fun content(width: Int = 320, fontScale: Float = 1f, height: Int = 900, body: @Composable () -> Unit) {
        compose.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(DpSize(width.dp, height.dp)) then
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
    fun compactEditorKeepsDirectExitAndNoteSwitcherReachableAtLargeText() {
        val actions = mutableListOf<ToolbarAction>()
        var switches = 0
        content(width = 320, fontScale = 1.5f) {
            EditorTitleBar(
                uiState = ToolbarUiState(notebookTitle = "Planning and handwritten meeting notes", pageNumberInfo = "2/12"),
                onAction = { actions.add(it) },
                onSwitchNotebook = { switches++ },
            )
        }
        compose.onNodeWithContentDescription("Switch notebook or page")
            .assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, switches)
        compose.onNodeWithContentDescription("Pages").assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(ToolbarAction.OpenQuickNav, actions.removeAt(0))
        // A useful title remains, rather than one letter between five separate buttons.
        compose.onNodeWithText("Planning and handwritten meeting notes", useUnmergedTree = true)
            .assertWidthIsAtLeast(96.dp)
        val context = ApplicationProvider.getApplicationContext<Context>()
        compose.onNodeWithContentDescription(context.getString(com.ethran.notable.R.string.editor_title_bar_back))
            .assertIsDisplayed().performClick()
        assertEquals(listOf(ToolbarAction.NavigateToLibrary), actions)
        screenshot("editor-title-320-large-text")
    }

    private fun compactEditorChrome(position: AppSettings.Position, name: String) {
        GlobalAppSettings.update(AppSettings(version = 1, toolbarPosition = position))
        val state = ToolbarUiState(
            isToolbarOpen = true, notebookTitle = "Field notes", notebookId = "book",
            pageNumberInfo = "2/12", mode = Mode.Draw, pen = Pen.BALLPEN,
            penPresetId = "ball", penSettings = ToolbarPen.defaultPenSettings,
        )
        content(width = 320, fontScale = 1.5f) {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.align(if (position == AppSettings.Position.Right) Alignment.TopEnd else Alignment.TopStart)) {
                    ToolbarContent(state, {}, {})
                }
                Box(Modifier.padding(toolbarInset(true))) { EditorTitleBar(state, {}) }
            }
        }
        compose.onNodeWithContentDescription("Switch notebook or page")
            .assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithContentDescription("Pages").assertIsDisplayed().assertWidthIsAtLeast(48.dp)
        compose.onNodeWithContentDescription("undo").assertIsDisplayed()
        screenshot(name)
    }

    @Test
    fun leftRailAndTitleKeepIndependentTargetsAtLargeText() =
        compactEditorChrome(AppSettings.Position.Left, "editor-left-320-large-text")

    @Test
    fun rightRailAndTitleKeepIndependentTargetsAtLargeText() =
        compactEditorChrome(AppSettings.Position.Right, "editor-right-320-large-text")

    @Test
    fun notebookSwitcherShowsRecentAndCurrentWithoutLosingResumePage() {
        val opened = mutableListOf<String>()
        val books = listOf(
            Notebook(id = "planning", title = "Planning and handwritten meeting notes",
                pageIds = listOf("p1", "p2", "p3"), openPageId = "p2"),
            Notebook(id = "ideas", title = "Ideas", pageIds = listOf("idea")),
            Notebook(id = "reading", title = "Reading journal", pageIds = listOf("r1", "r2")),
        )
        content(width = 320, fontScale = 1.5f) {
            Column(Modifier.heightIn(max = 700.dp)) {
                NotebookSwitcherList(books, listOf("planning", "ideas"), "planning", opened::add)
            }
        }
        compose.onNodeWithText("Recent").assertIsDisplayed()
        compose.onNodeWithText("All notebooks").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Planning and handwritten meeting notes")
            .performScrollTo().assertIsSelected().performClick()
        assertEquals(listOf("p2"), opened)
        compose.onNodeWithText("Ideas").performScrollTo().performClick()
        assertEquals(listOf("p2", "idea"), opened)
        screenshot("notebook-switcher-320-large-text")
    }

    @Test
    fun navigationSheetClosesWithoutChangingThePageAndExposesSelectedTabs() {
        var closes = 0
        var navigations = 0
        content(width = 320, fontScale = 1.5f) {
            var tab by remember { mutableStateOf(QuickNavTab.PAGES) }
            QuickNavContent(
                appRepository = null, uiState = QuickNavUiState(isLoading = false, tab = tab),
                onClose = { closes++ }, onNavigateBreadcrumb = { navigations++ },
                onSelectTab = { tab = it }, onToggleBookmark = {}, onSetBookmark = { _, _ -> },
                onTogglePinned = {}, onAddOutlineEntry = { _, _ -> }, onRenameOutlineEntry = { _, _ -> },
                onChangeOutlineDepth = { _, _ -> }, onRemoveOutlineEntry = {}, onMoveOutlineEntry = { _, _ -> },
                onGenerateBookPreviews = {}, onScrubStart = {}, onScrubPreview = {}, onScrubEnd = {},
                onReturnClick = {}, goToPage = { navigations++ },
            )
        }
        compose.onNodeWithText("Pages").assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
        compose.onNodeWithText("Notebooks").performClick().assertIsSelected()
        compose.onNodeWithText("Pages").assertIsNotSelected().performClick()
        compose.onNodeWithContentDescription("Close navigation")
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, closes)
        assertEquals(0, navigations)
        screenshot("navigation-sheet-320-large-text")
    }

    @Test
    fun shortNavigationKeepsCloseTabsScrubberAndEveryPageReachable() {
        val opened = mutableListOf<String>()
        var closed = false
        content(width = 600, height = 320, fontScale = 1.5f) {
            QuickNavContent(
                appRepository = null,
                uiState = QuickNavUiState(isLoading = false, tab = QuickNavTab.PAGES,
                    bookId = "book", currentPageId = "p1", bookPageCount = 12,
                    bookPageIds = (1..12).map { "p$it" }),
                onClose = { closed = true }, onNavigateBreadcrumb = {}, onSelectTab = {},
                onToggleBookmark = {}, onSetBookmark = { _, _ -> }, onTogglePinned = {},
                onAddOutlineEntry = { _, _ -> }, onRenameOutlineEntry = { _, _ -> },
                onChangeOutlineDepth = { _, _ -> }, onRemoveOutlineEntry = {}, onMoveOutlineEntry = { _, _ -> },
                onGenerateBookPreviews = {}, onScrubStart = {}, onScrubPreview = {}, onScrubEnd = {},
                onReturnClick = {}, goToPage = opened::add,
            )
        }
        compose.onNodeWithContentDescription("Close navigation").assertIsDisplayed()
        compose.onNodeWithText("Pages").assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
        compose.onNodeWithTag("navigation-page-scrubber").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)).performScrollToIndex(11)
        compose.onNodeWithContentDescription("Open page 12").assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf("p12"), opened)
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)).performScrollToIndex(0)
        compose.onNodeWithContentDescription("Open page 1").assertIsDisplayed().performClick()
        assertEquals(listOf("p12", "p1"), opened)
        screenshot("navigation-sheet-short-large-text")
        compose.onNodeWithContentDescription("Close navigation").performClick()
        assertTrue(closed)
    }

    private fun navigationThumbnailsKeepTheirLabels(height: Int, name: String) {
        val opened = mutableListOf<String>()
        content(width = 600, height = height, fontScale = 1.5f) {
            QuickNavContent(
                appRepository = null,
                uiState = QuickNavUiState(isLoading = false, tab = QuickNavTab.PAGES,
                    bookId = "book", currentPageId = "p1", bookPageCount = 12,
                    bookPageIds = (1..12).map { "p$it" }),
                onClose = {}, onNavigateBreadcrumb = {}, onSelectTab = {},
                onToggleBookmark = {}, onSetBookmark = { _, _ -> }, onTogglePinned = {},
                onAddOutlineEntry = { _, _ -> }, onRenameOutlineEntry = { _, _ -> },
                onChangeOutlineDepth = { _, _ -> }, onRemoveOutlineEntry = {}, onMoveOutlineEntry = { _, _ -> },
                onGenerateBookPreviews = {}, onScrubStart = {}, onScrubPreview = {}, onScrubEnd = {},
                onReturnClick = {}, goToPage = opened::add,
            )
        }
        // Capture before assertions so a baseline run records the clipped labels too.
        screenshot(name)
        compose.onNodeWithContentDescription("Close navigation").assertIsDisplayed()
        compose.onNodeWithTag("navigation-page-scrubber").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        // These assertions also run against the old implementation: the text nodes existed,
        // but the fixed two-row grid allocated no usable height to their labels.
        for (label in listOf("1", "2")) {
            compose.onNodeWithText(label, useUnmergedTree = true)
                .assertIsDisplayed().assertHeightIsAtLeast(20.dp)
        }
        compose.onNodeWithContentDescription("Open page 2").assertIsDisplayed()
            .assertHeightIsAtLeast(130.dp).performClick()
        assertEquals(listOf("p2"), opened)
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)).performScrollToIndex(11)
        compose.onNodeWithText("12", useUnmergedTree = true)
            .assertIsDisplayed().assertHeightIsAtLeast(20.dp)
        compose.onNodeWithContentDescription("Open page 12").performClick()
        assertEquals(listOf("p2", "p12"), opened)
    }

    @Test
    fun intermediateHeightNavigationPreservesFullThumbnailsAndLabels() =
        navigationThumbnailsKeepTheirLabels(500, "navigation-thumbnails-intermediate-large-text")

    @Test
    fun fullHeightNavigationPreservesFullThumbnailsAndLabels() =
        navigationThumbnailsKeepTheirLabels(900, "navigation-thumbnails-full-large-text")

    @Test
    fun titleMenuKeepsPaperSettingsReachableWithoutCrowdingTheTitle() {
        val actions = mutableListOf<ToolbarAction>()
        content(width = 320, fontScale = 1.5f) {
            var menuOpen by remember { mutableStateOf(false) }
            EditorTitleBar(
                ToolbarUiState(notebookTitle = "Field notes", notebookId = "book", pageNumberInfo = "3/24", isMenuOpen = menuOpen),
                onAction = { action ->
                    actions.add(action)
                    if (action == ToolbarAction.ToggleMenu) menuOpen = !menuOpen
                },
            )
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        compose.onNodeWithContentDescription(context.getString(com.ethran.notable.R.string.toolbar_element_menu)).performClick()
        compose.onNodeWithText(context.getString(com.ethran.notable.R.string.change_background))
            .assertIsDisplayed().performClick()
        assertTrue(actions.contains(ToolbarAction.ToggleBackgroundSelector(true)))
        compose.onNodeWithText(context.getString(com.ethran.notable.R.string.change_background)).assertDoesNotExist()
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
