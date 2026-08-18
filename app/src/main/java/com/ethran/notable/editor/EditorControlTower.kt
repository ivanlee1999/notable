package com.ethran.notable.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import androidx.compose.ui.geometry.Offset
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.editor.canvas.CanvasEventBus
import com.ethran.notable.editor.state.ClipboardStore
import com.ethran.notable.editor.state.History
import com.ethran.notable.editor.state.Mode
import com.ethran.notable.editor.state.Operation
import com.ethran.notable.editor.state.PlacementMode
import com.ethran.notable.editor.state.SelectionState
import com.ethran.notable.editor.utils.offsetStroke
import com.ethran.notable.editor.utils.pagedScrollDelta
import com.ethran.notable.editor.utils.refreshScreen
import com.ethran.notable.editor.utils.selectImagesAndStrokes
import com.ethran.notable.gestures.GestureActions
import com.ethran.notable.sync.SyncClock
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.UUID

class EditorControlTower(
    private val scope: CoroutineScope,
    val page: PageView,
    private var history: History,
    private val viewModel: EditorViewModel,
    private val clipboardStore: ClipboardStore,
) : GestureActions {
    private var scrollInProgress = Mutex()
    private val logEditorControlTower = ShipBook.getLogger("EditorControlTower")
    private var changePageObserverJob: Job? = null

    // Accumulated, not-yet-rendered scroll delta in screen coordinates. Input events add
    // into this; a single consumer coroutine drains and renders it. StateFlow conflation
    // means a burst of input collapses to one render pass per frame the renderer can keep
    // up with.
    private val pendingScroll = MutableStateFlow(Offset.Zero)
    private var scrollConsumerJob: Job? = null

    // One page turn per gesture. The drag that turned the page keeps streaming scroll events
    // while the switch is still landing, and every one of them re-reads the same edge — without
    // a latch a sustained drag skips pages, and on the last page each event minted a fresh blank
    // page. Released by [onGestureEnd], which every gesture exit path reaches.
    @Volatile
    private var edgeTurnTaken = false

    fun registerObservers() {
        startScrollConsumer()
        if (changePageObserverJob?.isActive == true) return

        changePageObserverJob = scope.launch {
            CanvasEventBus.changePage.collect { pageId ->
                logEditorControlTower.d("Change to page $pageId")

                // Switch to Main thread for Compose state mutations
                withContext(Dispatchers.Main) {
                    viewModel.changePage(pageId)
                    history.cleanHistory()
                }
                // no need for this, we are listening for change of current page,
                // in EditorView
//                page.changePage(pageId)
                refreshScreen()
            }
        }
    }

    // TODO: remove it, change to proper solution
    fun unregisterObservers() {
        changePageObserverJob?.cancel()
        changePageObserverJob = null
        scrollConsumerJob?.cancel()
        scrollConsumerJob = null
    }

    /**
     * Submit a scroll/drag delta (screen coordinates) for rendering. Non-blocking: the
     * delta is accumulated and consumed by [startScrollConsumer]; bursts coalesce automatically.
     */
    override fun requestScroll(delta: Offset) {
        if (delta == Offset.Zero) return
        if (!page.isTransformationAllowed) return
        // Continuous scrolling: dragging up past the top of the page slides the previous page in
        // above it rather than turning by a whole screen. The switch itself lands the previous
        // page at *exactly* the state that draws the same pixels — its scroll past its own end,
        // with this page under the seam — so nothing on screen moves except by the finger.
        if (page.crossPageScrollActive && delta.x == 0f && delta.y > 0 &&
            page.scroll.y <= 0f && page.previousPageId != null
        ) {
            if (crossTurnAllowed()) {
                crossTurnAnnouncedFrom = page.currentPageId
                enterPreviousPageAtItsEnd()
            }
            return
        }
        // Scrolling past the end of the last page: the next page is created in place, with no
        // whole-screen turn — the seam then slides in under the same drag, and the commit below
        // takes over once it leaves the screen. This is the continuous counterpart of the jump
        // branch underneath, which used to serve the last page even in Scrolling mode: creating
        // a page is right, arriving on it by a full-screen flip mid-drag is not.
        if (page.continuousScrollEnabled && delta.x == 0f && delta.y < 0 &&
            page.nextPageId == null && page.isAtVerticalEdge(-delta.y)
        ) {
            if (!edgeTurnTaken) {
                edgeTurnTaken = true
                scope.launch(Dispatchers.IO) {
                    // Null for a quick page (no notebook to add to) — then there is simply
                    // nothing past the end, as before.
                    if (page.pageDataManager.ensureNextPage(page.currentPageId) != null) {
                        // Redraw so the seam appears under the still-moving finger.
                        CanvasEventBus.forceUpdate.emit(null)
                    }
                }
            }
            return
        }
        // Dragging on when the page has no more to give is how you turn it, when that is the
        // direction the reader chose. Asked before the scroll is queued: once it is in the
        // accumulator it has been coalesced with other movement and the edge is no longer legible.
        // Under continuous scrolling both directions are handled above, so this discrete turn
        // now serves Pagination mode (and the upward turn at a page's top).
        if (GlobalAppSettings.current.pageTurn.isVertical &&
            delta.x == 0f && page.isAtVerticalEdge(-delta.y) &&
            !(page.crossPageScrollActive && delta.y < 0)
        ) {
            if (!edgeTurnTaken) {
                edgeTurnTaken = true
                // A drag up asks for what is below, which is the next page. Through this
                // class's own methods, not the view model's, so the turn drops the undo
                // history the way every other navigation route does.
                if (delta.y < 0) goToNextPage() else goToPreviousPage()
            }
            return
        }
        pendingScroll.update { it + delta }
    }

    /**
     * Whether a page switch has been announced but the [page] has not landed on it yet. Between
     * the two, the scroll consumer must not announce another switch: the edge condition still
     * reads true against the page being left, and a second announcement would skip a page.
     */
    private fun pageSwitchInFlight(): Boolean =
        viewModel.toolbarState.value.pageId?.let { it != page.currentPageId } ?: false

    /**
     * The page a continuous-scrolling turn was last announced from, or null. Its own latch rather
     * than [edgeTurnTaken] because a fling keeps scrolling after [onGestureEnd]: a turn committed
     * mid-fling under the gesture latch stayed taken until the *next* gesture ended, deadening one
     * whole drag. This one releases itself the moment scrolling is observed on any other page, and
     * [onGestureEnd] clears it too as a backstop.
     */
    @Volatile
    private var crossTurnAnnouncedFrom: String? = null

    private fun crossTurnAllowed(): Boolean {
        if (crossTurnAnnouncedFrom != null && crossTurnAnnouncedFrom != page.currentPageId) {
            // The announced switch has landed; the latch has done its job.
            crossTurnAnnouncedFrom = null
        }
        return crossTurnAnnouncedFrom == null && !pageSwitchInFlight()
    }

    /**
     * The continuous-scrolling page turn, committed only when the seam has left the screen — the
     * moment the next page's pixels and the current page's pixels are the same picture, so the
     * switch costs no visible movement. The next page inherits this zoom and starts at its top,
     * which is where the seam handed over.
     */
    private fun maybeCommitCrossPageTurn() {
        if (!page.crossPageScrollActive || !crossTurnAllowed()) return
        val nextId = page.nextPageId ?: return
        if (page.scroll.y < page.height.toFloat()) return
        crossTurnAnnouncedFrom = page.currentPageId
        page.pageDataManager.setPageZoom(nextId, page.zoomLevel.value)
        page.pageDataManager.setPageScroll(
            nextId, Offset(page.scroll.x, page.scroll.y - page.height)
        )
        goToNextPage()
    }

    /**
     * Enters the previous page positioned past its own end, so this page shows under the seam and
     * the screen does not change — the upward mirror of [maybeCommitCrossPageTurn]. The scroll is
     * preset far past any real extent and clamped on entry by the same bound every scroll takes,
     * which lands it exactly at the previous page's end.
     */
    private fun enterPreviousPageAtItsEnd() {
        val prevId = page.previousPageId ?: return
        page.pageDataManager.setPageZoom(prevId, page.zoomLevel.value)
        // Far past any real page extent, small enough to stay safe in float arithmetic until
        // the entry clamp lands it on the page's true end.
        page.pageDataManager.setPageScroll(prevId, Offset(page.scroll.x, 1e7f))
        goToPreviousPage()
    }

    /**
     * One screen of travel, routed through the same accumulator as a drag so the bitmap
     * shift and refresh behave identically — only the distance is decided here rather than
     * by the finger.
     */
    override fun requestPageStep(direction: Int) {
        if (!page.isTransformationAllowed) return
        val delta = pagedScrollDelta(page.viewHeight, direction)
        if (delta == 0f) return
        // Paged vertical navigation steps a screen at a time and comes through here rather than
        // requestScroll, so the page turn has to be offered on this path too — otherwise the
        // setting would work in continuous scrolling and silently do nothing in paged.
        if (GlobalAppSettings.current.pageTurn.isVertical && page.isAtVerticalEdge(-delta)) {
            if (!edgeTurnTaken) {
                edgeTurnTaken = true
                if (direction > 0) goToNextPage() else goToPreviousPage()
            }
            return
        }
        logEditorControlTower.i("Paged step, direction: $direction")
        pendingScroll.update { it + Offset(0f, delta) }
    }

    /**
     * Single consumer that drains [pendingScroll] and performs the actual bitmap shift.
     * Draining atomically resets the accumulator to zero, so any input that arrives while
     * a render is in flight piles onto a fresh zero and is picked up on the next pass —
     * coalescing a flood of touch samples into one shift per render.
     */
    private fun startScrollConsumer() {
        if (scrollConsumerJob?.isActive == true) return
        scrollConsumerJob = scope.launch(Dispatchers.Main.immediate) {
            pendingScroll.collect {
                val delta = pendingScroll.getAndUpdate { Offset.Zero }
                if (delta == Offset.Zero) return@collect
                scrollInProgress.withLock {
                    if (viewModel.toolbarState.value.mode == Mode.Select &&
                        viewModel.selectionState.firstPageCut != null
                    ) {
                        onOpenPageCut(delta / page.zoomLevel.value)
                    } else {
                        onPageScroll(-delta)
                        // Only after the scroll has actually landed: the commit reads the bounded
                        // scroll, and firing it from the input side would read a value the bound
                        // had not seen yet.
                        maybeCommitCrossPageTurn()
                    }
                }
                CanvasEventBus.refreshUiImmediately.emit(Unit)
            }
        }
    }


    override fun setIsDrawing(value: Boolean) {
        if (viewModel.toolbarState.value.isDrawing == value) {
            logEditorControlTower.w("IsDrawing already set to $value")
            return
        }
        scope.launch { CanvasEventBus.isDrawing.emit(value) }
    }

    override fun toggleTool() {
        val mode = viewModel.toolbarState.value.mode
        viewModel.onToolbarAction(ToolbarAction.ChangeMode(if (mode == Mode.Draw) Mode.Erase else Mode.Draw))
    }

    override fun toggleZen() {
        viewModel.onToolbarAction(ToolbarAction.ToggleToolbar)
    }

    fun getSnapshotOfSelectionState(): SelectionState {
        return viewModel.selectionState
    }

    fun getSelectedBitmap(): Bitmap {
        return requireNotNull(viewModel.selectionState.selectedBitmap)
    }

    override fun goToNextPage() {
        logEditorControlTower.i("Going to next page")
        viewModel.goToNextPage()
        history.cleanHistory()
    }

    override fun goToPreviousPage() {
        logEditorControlTower.i("Going to previous page")
        viewModel.goToPreviousPage()
        history.cleanHistory()
    }

    override fun onGestureEnd() {
        edgeTurnTaken = false
        crossTurnAnnouncedFrom = null
    }

    override fun undo() {
        scope.launch {
            logEditorControlTower.i("Undo called")
            history.undo()
//            CanvasEventBus.refreshUi.emit(Unit)
        }
    }

    override fun redo() {
        scope.launch {
            logEditorControlTower.i("Redo called")
            history.redo()
//            CanvasEventBus.refreshUi.emit(Unit)
        }
    }

    override fun onPinchToZoom(delta: Float, center: Offset?) {
        if (!page.isTransformationAllowed) return
        // Zooming under an active selection (floating bitmap, in-progress page
        // cut) would desync the screen-space overlay from the strokes below it;
        // with the select tool merely armed, zooming is fine.
        if (viewModel.selectionState.isNonEmpty() || viewModel.selectionState.firstPageCut != null)
            return
        scope.launch {
            scrollInProgress.withLock {
                if (GlobalAppSettings.current.simpleRendering || !GlobalAppSettings.current.continuousZoom)
                    page.simpleUpdateZoom(delta)
                else
                    page.updateZoom(delta, center)
            }
            CanvasEventBus.refreshUiImmediately.emit(Unit)
        }
    }

    fun resetZoomAndScroll() {
        scope.launch {
            page.scroll = Offset(0f, page.scroll.y)
            // Back to the page fitted across the view, not to 1:1 — the fit is the resting view of
            // a page now, and on a declared sheet 1.0 is an arbitrary zoom like any other.
            page.applyZoomAndRedraw(page.fitToWidthZoom)
            // Request UI update
            CanvasEventBus.refreshUiImmediately.emit(Unit)
        }
    }

    private fun onOpenPageCut(offset: Offset) {
        val cutLine = viewModel.selectionState.firstPageCut ?: return
        val result = page.applyPageCutOffset(cutLine, offset) ?: return

        // commit to history
        history.addOperationsToHistory(
            listOf(
                Operation.DeleteStroke(result.movedStrokes.map { it.id }),
                Operation.AddStroke(result.previousStrokes)
            )
        )

        viewModel.selectionState.reset()
    }

    private suspend fun onPageScroll(dragDelta: Offset) {
        // scroll is in Page coordinates
        if (GlobalAppSettings.current.simpleRendering)
            page.simpleUpdateScroll(dragDelta)
        else
            page.updateScroll(dragDelta)
    }


    // when selection is moved, we need to redraw canvas
    fun applySelectionDisplace() {
        viewModel.selectionState.applySelectionDisplaceAndCommit(page, history)
        scope.launch {
            CanvasEventBus.refreshUi.emit(Unit)
        }
    }

    fun deleteSelection() {
        viewModel.selectionState.deleteSelectionAndCommit(page, history)
        setIsDrawing(true)
        scope.launch {
            CanvasEventBus.refreshUi.emit(Unit)
        }
    }

    fun changeSizeOfSelection(scale: Int) {
        if (!viewModel.selectionState.selectedImages.isNullOrEmpty())
            viewModel.selectionState.resizeImages(scale, page)
        if (!viewModel.selectionState.selectedStrokes.isNullOrEmpty())
            viewModel.selectionState.resizeStrokes(scale, scope, page)
        // Emit a refresh signal to update UI
        scope.launch {
            CanvasEventBus.refreshUi.emit(Unit)
        }
    }

    fun duplicateSelection() {
        // finish ongoing movement
        applySelectionDisplace()
        viewModel.selectionState.duplicateSelection()

    }

    fun cutSelectionToClipboard(context: Context) {
        clipboardStore.set(viewModel.selectionState.selectionToClipboard(page.scroll, context))
        deleteSelection()
        showHint("Content cut to clipboard")
    }

    fun copySelectionToClipboard(context: Context) {
        clipboardStore.set(viewModel.selectionState.selectionToClipboard(page.scroll, context))
    }


    fun pasteFromClipboard() {
        // finish ongoing movement
        applySelectionDisplace()

        val (strokes, images) = clipboardStore.get() ?: return

        val now = SyncClock.nowDate()
        val scrollPos = page.scroll

        val pastedStrokes = strokes.map {
            offsetStroke(it, offset = scrollPos).copy(
                // change the pasted strokes' ids - it's a copy
                id = UUID
                    .randomUUID()
                    .toString(),
                createdAt = now,
                // set the pageId to the current page
                pageId = this.page.currentPageId
            )
        }

        val pastedImages = images.map {
            it.copy(
                // change the pasted images' ids - it's a copy
                id = UUID
                    .randomUUID()
                    .toString(),
                x = it.x + scrollPos.x.toInt(),
                y = it.y + scrollPos.y.toInt(),
                createdAt = now,
                // set the pageId to the current page
                pageId = this.page.currentPageId
            )
        }

        selectImagesAndStrokes(
            scope = scope,
            page = page,
            viewModel = viewModel,
            imagesToSelect = pastedImages,
            strokesToSelect = pastedStrokes
        )
        viewModel.selectionState.placementMode = PlacementMode.Paste

        showHint("Pasted content from clipboard")
    }

    override fun showHint(text: String) = viewModel.showHint(text)

    override fun selectRectangle(rect: Rect) {
        // Take shared ownership of the EPD animation mode now, before the
        // gesture receiver releases its handle: the selection flow runs
        // asynchronously, and the overlap keeps fast refresh on across the
        // hand-over. Released in SelectionState.reset() or by Select.kt if
        // the rectangle selects nothing.
        viewModel.selectionState.holdRefresh()
        scope.launch {
            CanvasEventBus.rectangleToSelectByGesture.emit(rect)
        }
    }

    override fun redrawCanvas() {
        scope.launch {
            CanvasEventBus.forceUpdate.emit(null)
        }
    }
}
