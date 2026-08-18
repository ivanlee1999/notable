package com.ethran.notable.editor

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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ethran.notable.editor.canvas.CanvasEventBus
import com.ethran.notable.editor.state.ClipboardStore
import com.ethran.notable.editor.ui.EditorSurface
import com.ethran.notable.editor.ui.EditorTitleBar
import com.ethran.notable.editor.ui.HorizontalScrollIndicator
import com.ethran.notable.editor.ui.SaveStateBadge
import com.ethran.notable.editor.ui.ScrollIndicator
import com.ethran.notable.editor.ui.SelectedBitmap
import com.ethran.notable.editor.ui.toolbar.PositionedToolbar
import com.ethran.notable.editor.ui.toolbar.editorChromeInset
import com.ethran.notable.editor.ui.toolbar.toolbarInset
import com.ethran.notable.gestures.EditorGestureReceiver
import com.ethran.notable.navigation.NavigationDestination
import com.ethran.notable.ui.LocalSnackContext
import com.ethran.notable.ui.SnackConf
import com.ethran.notable.ui.convertDpToPixel
import com.ethran.notable.ui.theme.InkaTheme
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
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
    goToBugReport: () -> Unit,

    viewModel: EditorViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val snackManager = LocalSnackContext.current
    val scope = rememberCoroutineScope()

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
        val height = convertDpToPixel(this.maxHeight, context).toInt()
        val width = convertDpToPixel(this.maxWidth, context).toInt()

        // Here we load initial page into the memory
        val page = remember {
            PageView(
                context = context,
                coroutineScope = scope,
                pageDataManager = viewModel.pageDataManager,
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
            viewModel.setShowResetView(abs(zoomLevel - page.fitToWidthZoom) > ZOOM_FIT_EPSILON)
            viewModel.setSelectionActive(selectionActive)
        }

        DisposableEffect(Unit) {
            onDispose {
                viewModel.onDispose(page)
            }
        }



        InkaTheme {
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
                    context = context, controlTower = editorControlTower
                )
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
                        onAction = viewModel::onToolbarAction,
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
    }
}
