package com.ethran.notable.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.ethran.notable.editor.canvas.CanvasEventBus
import com.ethran.notable.editor.state.ClipboardStore
import com.ethran.notable.editor.ui.EditorSurface
import com.ethran.notable.editor.ui.EditorTitleBar
import com.ethran.notable.editor.ui.HorizontalScrollIndicator
import com.ethran.notable.editor.ui.SaveStateBadge
import com.ethran.notable.editor.ui.ScrollIndicator
import com.ethran.notable.editor.ui.SelectedBitmap
import com.ethran.notable.editor.ui.TextBoxEditor
import com.ethran.notable.editor.text.FlowTextViewModel
import com.ethran.notable.editor.text.FlowEditorSurface
import com.ethran.notable.ui.dialogs.NoteRenameDialog
import com.ethran.notable.editor.topics.EditorTopicButton
import com.ethran.notable.editor.topics.EditorTopicsPopup
import com.ethran.notable.editor.topics.EditorTopicsViewModel
import com.ethran.notable.editor.ui.toolbar.PositionedToolbar
import com.ethran.notable.editor.ui.toolbar.editorChromeInset
import com.ethran.notable.editor.ui.toolbar.toolbarInset
import com.ethran.notable.gestures.EditorGestureReceiver
import com.ethran.notable.navigation.NavigationDestination
import com.ethran.notable.ui.LocalSnackContext
import com.ethran.notable.ui.SnackConf
import com.ethran.notable.ui.theme.InkaTheme
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlin.math.abs

private val log = ShipBook.getLogger("EditorView")

/** Zoom difference below which the view counts as "fitted" — a rounded float is still the fit. */
private const val ZOOM_FIT_EPSILON = 0.001f

object EditorDestination : NavigationDestination {
    override val route = "editor"

    const val PAGE_ID_ARG = "pageId"
    const val BOOK_ID_ARG = "bookId"

    // Unified route: editor/{pageId}?bookId={bookId}
    val routeWithArgs = "$route/{$PAGE_ID_ARG}?$BOOK_ID_ARG={$BOOK_ID_ARG}"

    /**
     * Helper to create the path. If bookId is null, it just won't be appended.
     */
    fun createRoute(pageId: String, bookId: String? = null): String {
        return "$route/$pageId" + if (bookId != null) "?$BOOK_ID_ARG=$bookId" else ""
    }
}


@Composable
fun EditorView(
    initialPageId: String,
    bookId: String?,
    isQuickNavOpen: Boolean,

    // navigation callbacks
    onPageChange: (String) -> Unit,
    goToLibrary: (folderId: String?) -> Unit,
    goToPages: (bookId: String) -> Unit,
    openQuickNav: () -> Unit,
    openNoteSwitcher: () -> Unit = openQuickNav,
    onExitRequested: () -> Unit = {},
    onNewEntry: (pageId: String, bookId: String) -> Unit = { _, _ -> },
    goToBugReport: () -> Unit,

    viewModel: EditorViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val snackManager = LocalSnackContext.current
    val scope = rememberCoroutineScope()
    val flowModel: FlowTextViewModel = hiltViewModel()
    var flowOpen by rememberSaveable(initialPageId) { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current

    // A page that outgrew its sheet is divided before anything loads it — the reconciliation
    // the iPad has run on notebook open since pages became sheets (§6.6), now run here too.
    // The editor waits because PageView reads the page the moment it is constructed; on every
    // open after the first this is a handful of MAX() queries and composes on the next frame.
    var pagesDivided by remember(bookId) { mutableStateOf(bookId == null) }
    if (!pagesDivided) {
        LaunchedEffect(bookId) {
            viewModel.divideOversizedPages(bookId!!)
            pagesDivided = true
        }
        return
    }

    BackHandler(enabled = !isQuickNavOpen) {
        scope.launch {
            if (!flowOpen || flowModel.flush()) {
                onExitRequested()
                viewModel.onToolbarAction(ToolbarAction.NavigateToLibrary)
            }
        }
    }

    // Single point of entry for loading book data based on the pageId from Navigation
    // Should not be used for regular page switching
    LaunchedEffect(initialPageId) {
        log.v("EditorView: pageId changed to $initialPageId, loading data")
        viewModel.loadToolbarState(bookId, initialPageId)
    }

    // Sync isQuickNavOpen to ViewModel
    LaunchedEffect(isQuickNavOpen) {
        viewModel.onToolbarAction(ToolbarAction.UpdateQuickNavOpen(isQuickNavOpen))
    }


    BoxWithConstraints {
        val height = constraints.maxHeight
        val width = constraints.maxWidth

        // Here we load initial page into the memory
        val page = remember {
            PageView(
                context = context,
                coroutineScope = scope,
                pageDataManager = viewModel.pageDataManager,
                appRepository = viewModel.appRepository,
                initialPageId = initialPageId,
                viewWidth = width,
                viewHeight = height,
                snackManager = snackManager,
            )
        }

        val history = remember(page) { viewModel.createHistory(page) }

        val editorControlTower = remember {
            EditorControlTower(
                scope = scope,
                page = page,
                history = history,
                viewModel = viewModel,
                clipboardStore = ClipboardStore,
            )
        }


        // Initialize ViewModel with persisted settings on first composition
        LaunchedEffect(Unit) {
            viewModel.initFromPersistedSettings()
            viewModel.updateDrawingState()
        }

//         val editorControlTower = remember {
//             EditorControlTower(scope, page, history, editorState, context, appRepository).apply { registerObservers() }
        DisposableEffect(editorControlTower) {
            editorControlTower.registerObservers()
            onDispose {
                editorControlTower.unregisterObservers()
            }
        }

        // Collect UI Events from ViewModel (navigation )
        LaunchedEffect(Unit) {
            viewModel.uiEvents.collect { event ->
                when (event) {
                    is EditorUiEvent.NavigateToLibrary -> {
                        goToLibrary(event.folderId)
                    }

                    is EditorUiEvent.NavigateToPages -> {
                        goToPages(event.bookId)
                    }

                    EditorUiEvent.OpenQuickNav -> {
                        openQuickNav()
                    }

                    EditorUiEvent.NavigateToBugReport -> {
                        goToBugReport()
                    }
                }
            }
        }

        // Collect Canvas Commands from ViewModel
        LaunchedEffect(Unit) {
            viewModel.canvasCommands.collect { command ->
                when (command) {
                    CanvasCommand.Undo -> editorControlTower.undo()
                    CanvasCommand.Redo -> editorControlTower.redo()
                    CanvasCommand.Paste -> editorControlTower.pasteFromClipboard()
                    CanvasCommand.ResetView -> editorControlTower.resetZoomAndScroll()
                    CanvasCommand.ClearAllStrokes -> {
                        CanvasEventBus.clearPageSignal.emit(Unit)
                        snackManager.displaySnack(
                            SnackConf(
                                text = "Cleared all strokes",
                                duration = 2000
                            )
                        )
                    }

                    CanvasCommand.RefreshCanvas -> {
                        CanvasEventBus.pagesChangedInDb.emit(
                            setOfNotNull(viewModel.toolbarState.value.pageId)
                        )
                    }

                    is CanvasCommand.CopyImageToCanvas -> {
                        CanvasEventBus.addImageByUri.value = command.uri
                    }
                }
            }
        }

        // Handle Canvas signals in UI
        LaunchedEffect(Unit) {
            CanvasEventBus.closeMenusSignal.collect {
                log.d("Closing all menus")
                viewModel.onToolbarAction(ToolbarAction.CloseAllMenus)
            }
        }

        // Handle focus changes from Canvas
//        LaunchedEffect(Unit) {
//            CanvasEventBus.onFocusChange.collect { hasFocus ->
//                log.d("Canvas has focus: $hasFocus")
//                if (hasFocus)
//                    viewModel.updateDrawingState()
//
//            }
//        }

        val toolbarState by viewModel.toolbarState.collectAsStateWithLifecycle()
        val flowState by flowModel.state.collectAsStateWithLifecycle()
        var flowDecisionPage by remember { mutableStateOf<String?>(null) }
        var renameTarget by remember { mutableStateOf<Pair<String, String?>?>(null) }
        fun rename(pageTitle: Boolean = false) {
            val book = toolbarState.notebookId ?: return
            val targetPage = toolbarState.pageId
            editorControlTower.commitTextBox()
            scope.launch {
                if (flowModel.flush() && viewModel.toolbarState.value.notebookId == book &&
                    viewModel.toolbarState.value.pageId == targetPage)
                    renameTarget = book to targetPage?.takeIf { pageTitle }
            }
        }
        DisposableEffect(renameTarget) {
            val active = renameTarget != null
            if (active) viewModel.setTextEditing(true)
            onDispose { if (active) viewModel.setTextEditing(false) }
        }
        LaunchedEffect(toolbarState.pageId) { renameTarget = null }
        LaunchedEffect(toolbarState.pageId) {
            toolbarState.pageId?.let { pageId ->
                flowOpen = false
                flowModel.open(pageId)
            }
        }
        LaunchedEffect(flowState.document?.pageId) {
            flowState.document?.let { document ->
                if (flowDecisionPage != document.pageId) {
                    flowDecisionPage = document.pageId
                    flowOpen = document.runs.values.any { it.original.isNotEmpty() || it.source.isNotBlank() }
                }
            }
        }
        LaunchedEffect(flowState.error, flowState.document?.pageId, toolbarState.pageId) {
            if (flowState.error != null && flowState.document != null && flowState.document?.pageId != toolbarState.pageId)
                flowOpen = true
        }
        DisposableEffect(flowOpen) {
            viewModel.setFlowTextOpen(flowOpen)
            onDispose { viewModel.setFlowTextOpen(false) }
        }
        DisposableEffect(lifecycleOwner, editorControlTower) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) {
                    editorControlTower.commitTextBox()
                    flowModel.flushInBackground()
                } else if (event == Lifecycle.Event.ON_RESUME) flowModel.refreshIfClean()
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
        val topicsModel: EditorTopicsViewModel = hiltViewModel()
        DisposableEffect(topicsModel) { onDispose { topicsModel.close() } }
        val topicEntries by topicsModel.entries.collectAsStateWithLifecycle()
        val topicRequest by topicsModel.request.collectAsStateWithLifecycle()
        val selectedTopics = topicEntries.firstOrNull { it.notebookId == toolbarState.notebookId }?.topics.orEmpty()
        fun openTopics(fromSelection: Boolean = false) {
            val notebook = toolbarState.notebookId ?: return
            val pageId = toolbarState.pageId ?: return
            viewModel.setTopicPickerOpen(true)
            topicsModel.open(notebook, pageId, if (fromSelection) viewModel.selectionState.selectedStrokes.orEmpty() else emptyList())
        }
        LaunchedEffect(toolbarState.notebookId, toolbarState.pageId) {
            topicsModel.retainContext(toolbarState.notebookId, toolbarState.pageId)
        }
        DisposableEffect(topicRequest) {
            viewModel.setTopicPickerOpen(topicRequest != null)
            onDispose { viewModel.setTopicPickerOpen(false) }
        }

        // Observe pageId changes from ViewModel state for navigation
        LaunchedEffect(viewModel) {
            snapshotFlow { toolbarState.pageId }.filterNotNull().distinctUntilChanged()
                .drop(1) // Skip initial emission from loadBookData
                .collect { newPageId ->
                    log.v("EditorView: snapshotFlow detected pageId change to $newPageId, triggering onPageChange")
                    // Undo history names stroke ids, and replaying it against a page that does
                    // not hold them deletes those rows from the DB anyway — the ids are global.
                    // Cleaning here, at the one point every navigation route passes through,
                    // covers the paths that used to skip it (the drag-past-the-edge turn, the
                    // scrubber) instead of trusting each call site to remember.
                    history.cleanHistory()
                    // update the PageView
                    page.changePage(newPageId)

                    // update the navigation state
                    onPageChange(newPageId)
                }
        }

        // Sync PageView state to ViewModel for Toolbar rendering
        val zoomLevel by page.zoomLevel.collectAsStateWithLifecycle()
        val selectionActive = viewModel.selectionState.isNonEmpty()
        LaunchedEffect(
            zoomLevel, selectionActive
        ) {
            log.v("EditorView: zoomLevel=$zoomLevel, selectionActive=$selectionActive")
            // "Reset view" offers a way back to the fitted page, so it appears when the view has
            // left the fit — not when it has left 1:1, which is just some zoom among others once
            // the page has a size of its own.
            viewModel.setShowResetView(abs(zoomLevel - page.fitZoom) > ZOOM_FIT_EPSILON)
            viewModel.setSelectionActive(selectionActive)
        }

        DisposableEffect(Unit) {
            onDispose {
                // The draft is not yet a stored block. Commit before the outgoing page is saved.
                editorControlTower.commitTextBox()
                // Close-sync starts after typed source has reached its canonical rows.
                flowModel.finishOnDispose { viewModel.onDispose(page) }
            }
        }



        InkaTheme {
            if (flowOpen) {
                FlowEditorSurface(flowModel, if (flowState.document?.pageId == toolbarState.pageId)
                    toolbarState.notebookTitle.orEmpty() else "Unsaved text",
                    onBack = { onExitRequested(); viewModel.onToolbarAction(ToolbarAction.NavigateToLibrary) },
                    onInk = { flowDecisionPage = toolbarState.pageId; flowOpen = false; toolbarState.pageId?.let(flowModel::open) },
                    onTags = if (flowState.document?.pageId == toolbarState.pageId) ({ openTopics() }) else null,
                    onSwitchNotebook = openNoteSwitcher,
                    onNewEntry = onNewEntry,
                    onRename = { rename() })
            } else {
            // Everything the docked chrome covers: the rail's band, plus the title bar along
            // the top of what it leaves.
            val chromeInset = editorChromeInset(toolbarState.isToolbarOpen)

            // The page occupies what the chrome leaves, rather than running underneath it.
            //
            // The writing surface used to fill the screen with the rail drawn on top, so a page
            // fitted to the full width had its outer edge permanently hidden — a left-docked
            // rail ate the left margin, and the title bar the first line. The pen was already
            // held off those bands, which only made the covered strip unreachable rather than
            // merely hidden.
            //
            // Insetting the surface itself, rather than teaching the page a second origin, is
            // what keeps the drawing pipeline honest: view coordinates *are* page coordinates
            // here, so the surface being the paper means every existing conversion, dirty rect
            // and scroll bound stays true. Resizing it re-fits the page (surfaceChanged →
            // PageView.updateDimensions), which is why hiding the rail gives the page the room
            // back.
            Box(Modifier.padding(chromeInset)) {
                EditorGestureReceiver(actions = editorControlTower)
                EditorSurface(
                    viewModel = viewModel,
                    page = page,
                    history = history
                )
                SelectedBitmap(
                    context = context, controlTower = editorControlTower,
                    onAddTopic = { openTopics(fromSelection = true) },
                )
                // Only while a box is open. The rest of the time a text box is drawn into the
                // page bitmap with everything else, so the page carries no views and scrolling
                // stays a bitmap blit.
                TextBoxEditor(controlTower = editorControlTower, page = page)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .padding(chromeInset)
            ) {
                Spacer(modifier = Modifier.weight(1f))
                ScrollIndicator(viewModel = viewModel, page = page)
            }
            PositionedToolbar(
                viewModel = viewModel, onDrawingStateCheck = { viewModel.updateDrawingState() })
            // Inside the rail's band only: the title bar runs along the top of what the rail
            // leaves, the way it does beside a left-docked rail in the design.
            if (toolbarState.isToolbarOpen) {
                Box(Modifier.padding(toolbarInset(true))) {
                    EditorTitleBar(
                        uiState = toolbarState,
                        onAction = { action ->
                            if (action == ToolbarAction.NavigateToLibrary) onExitRequested()
                            viewModel.onToolbarAction(action)
                        },
                        onSwitchNotebook = openNoteSwitcher,
                        onRename = { rename() },
                        onRenamePage = { rename(pageTitle = true) },
                        topics = { EditorTopicButton(selectedTopics,
                            enabled = toolbarState.notebookId != null) { openTopics() } },
                        onType = {
                            editorControlTower.commitTextBox()
                            toolbarState.pageId?.let { pageId -> scope.launch {
                                viewModel.pageDataManager.awaitContentWrites(pageId)
                                if (viewModel.toolbarState.value.pageId == pageId && page.currentPageId == pageId) {
                                    flowModel.prepareForTyping(page.strokes.isNotEmpty() || page.images.isNotEmpty() ||
                                        page.blocks.any { it.x != null && it.y != null })
                                    flowDecisionPage = pageId
                                    flowOpen = true
                                }
                            } }
                        },
                    )
                }
            }
            Box(Modifier.padding(chromeInset)) {
                HorizontalScrollIndicator(viewModel = viewModel, page = page)
            }
            // Last, so it is over the toolbar rather than under it: the one thing on this screen
            // the user cannot find out any other way.
            SaveStateBadge(saveState = page.pageDataManager.saveState)
            }
            if (topicRequest?.let { it.notebookId == toolbarState.notebookId && it.pageId == toolbarState.pageId } == true) {
                EditorTopicsPopup(topicsModel)
            }
            renameTarget?.let { (book, targetPage) ->
                NoteRenameDialog(book, targetPage, onDismiss = { renameTarget = null }, onSaved = {
                    scope.launch {
                        val current = viewModel.toolbarState.value
                        if (current.notebookId == book) current.pageId?.let { viewModel.loadToolbarState(book, it) }
                    }
                })
            }
        }
    }
}
