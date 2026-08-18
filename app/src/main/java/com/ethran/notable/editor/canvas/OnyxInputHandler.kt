package com.ethran.notable.editor.canvas

import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toRect
import com.ethran.notable.data.datastore.TOOLBAR_THICKNESS
import com.ethran.notable.editor.EditorViewModel
import com.ethran.notable.editor.state.Mode
import com.ethran.notable.editor.PageView
import com.ethran.notable.editor.state.History
import com.ethran.notable.editor.ui.editorTitleBarHeight
import com.ethran.notable.editor.utils.DeviceCompat
import com.ethran.notable.editor.utils.Eraser
import com.ethran.notable.editor.utils.Pen
import com.ethran.notable.editor.utils.calculateBoundingBox
import com.ethran.notable.editor.utils.cancelPendingScreenFreezeReset
import com.ethran.notable.editor.utils.copyInput
import com.ethran.notable.editor.utils.configureCalligraphyLiveAngle
import com.ethran.notable.editor.utils.copyInputToSimplePointF
import com.ethran.notable.editor.utils.ERASER_INDICATOR_COLOR
import com.ethran.notable.editor.utils.enableNativeEraser
import com.ethran.notable.editor.utils.eraserIndicatorWidth
import com.ethran.notable.editor.utils.getModifiedStrokeEndpoints
import com.ethran.notable.editor.utils.handleDraw
import com.ethran.notable.editor.utils.handleErase
import com.ethran.notable.editor.utils.handleScribbleToErase
import com.ethran.notable.editor.utils.handleSelect
import com.ethran.notable.editor.utils.onSurfaceInit
import com.ethran.notable.editor.utils.penToStroke
import com.ethran.notable.editor.utils.setupSurface
import com.ethran.notable.editor.utils.ShapeGeometry
import com.ethran.notable.ui.convertDpToPixel
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.device.Device
import com.onyx.android.sdk.extension.isNullOrEmpty
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

class OnyxInputHandler(
    private val drawCanvas: DrawCanvas,
    private val page: PageView,
    private val viewModel: EditorViewModel,
    private val history: History,
    private val coroutineScope: CoroutineScope,
    private val strokeHistoryBatch: MutableList<String>,
) {
    var isErasing: Boolean = false

    /**
     * When the previous stroke ended, on the clock of the input path's own point timestamps —
     * see [onStrokeCollected] for why it is stamped from the points rather than the wall clock.
     */
    var lastStrokeEndTime: Long = 0
    private val log = ShipBook.getLogger("DrawCanvas")
    private val toolbarState get() = viewModel.toolbarState.value

    /**
     * Whether the firmware raw-pen path actually came up on this device.
     *
     * Being an ONYX device is not enough: [TouchHelper.create] can fail, and a panel that
     * carries no digitizer of its own (the Palma class, which takes a capacitive stylus
     * rather than the Tab series' EMR one) may not serve the raw channel at all. Everything
     * Onyx-side already null-guards the helper, so on those devices the editor would come up
     * looking fine and simply never record a stroke — [MotionEventStrokeSource] reads the
     * framework's events instead when this is false.
     */
    val usesRawInput: Boolean get() = touchHelper != null

    // TODO: As OnyxInput is not done by lazy, which forces evaluation of the touchHelper
    //       lazy during DrawCanvas construction.
    val touchHelper by lazy {
        val helper = if (DeviceCompat.isOnyxDevice) {
            try {
                referencedSurfaceView = this.hashCode().toString()
                TouchHelper.create(drawCanvas, inputCallback)
            } catch (t: Throwable) {
                Log.w("OnyxInputHandler", "TouchHelper.create failed: ${t.message}")
                null
            }
        } else null
        helper
    }

    @Suppress("RedundantOverride")
    private val inputCallback: RawInputCallback = object : RawInputCallback() {
        // Documentation: https://github.com/onyx-intl/OnyxAndroidDemo/blob/d3a1ffd3af231fe4de60a2a0da692c17cb35ce31/doc/Onyx-Pen-SDK.md#L40-L62
        // - pen : `onBeginRawDrawing()` -> `onRawDrawingTouchPointMoveReceived()` -> `onRawDrawingTouchPointListReceived()` -> `onEndRawDrawing()`
        // - erase :  `onBeginRawErasing()` -> `onRawErasingTouchPointMoveReceived()` -> `onRawErasingTouchPointListReceived()` -> `onEndRawErasing()`

        override fun onBeginRawDrawing(p0: Boolean, p1: TouchPoint?) {
            // The firmware is about to paint on the picture that is on the panel now, so that is
            // the view this stroke will be filed through however far the editor scrolls before the
            // pen lifts. See InkViewport.
            page.beginInkCapture()
        }

        override fun onEndRawDrawing(p0: Boolean, p1: TouchPoint?) {
            // Fires after the point list, which is where the held view is read.
            page.endInkCapture()
        }

        override fun onRawDrawingTouchPointMoveReceived(p0: TouchPoint?) {
        }

        override fun onRawDrawingTouchPointListReceived(plist: TouchPointList) =
            onStrokeCollected(plist)


        // Handle button/eraser tip of the pen:
        override fun onBeginRawErasing(p0: Boolean, p1: TouchPoint?) {
            if (touchHelper == null) return
            // An erase track is aimed at the ink on the panel exactly as a stroke is, and is
            // matched against stored ink the same way. Same view, same reason. See InkViewport.
            page.beginInkCapture()
            // Re-assert the native eraser indicator because setRawDrawingEnabled(true) (called
            // on every resume) resets it to disabled internally. The track style follows the active
            // eraser type: the wide marker (style 8) for the pen/drag eraser, a dotted outline
            // (DASH style 5) for the lasso/select eraser. See docs/onyx-sdk/onyx-native-eraser-indicator.md.
            // The current zoom rides along so the track matches the swath actually erased.
            enableNativeEraser(touchHelper, toolbarState.eraser, page.zoomLevel.value)
            // The eraser channel carries no colour of its own — the firmware paints the track with
            // the global setStrokeColor. Set it here (width comes from the style's params, so this
            // touches colour only, not thickness). This is the one thing we still set on begin.
            touchHelper?.setStrokeColor(ERASER_INDICATOR_COLOR)
            isErasing = true
        }

        override fun onEndRawErasing(p0: Boolean, p1: TouchPoint?) {
            // Fires after the point list, which is where the held view is read.
            page.endInkCapture()
            updatePenAndStroke()
        }

        override fun onRawErasingTouchPointListReceived(plist: TouchPointList?) =
            onRawErasingList(plist)

        override fun onRawErasingTouchPointMoveReceived(p0: TouchPoint?) {
        }

        override fun onPenUpRefresh(refreshRect: RectF?) {
            super.onPenUpRefresh(refreshRect)
        }

        override fun onPenActive(point: TouchPoint?) {
            super.onPenActive(point)
        }
    }

    fun updatePenAndStroke() {
        if(touchHelper == null) return
        // it takes around 11 ms to run on Note 4c.
        log.i("Update pen and stroke")
        when (toolbarState.mode) {
            // we need to change size according to zoom level before drawing on screen
            Mode.Draw, Mode.Line -> {
                val scaledWidth = toolbarState.activePenSetting.strokeSize * page.zoomLevel.value
                touchHelper!!.setStrokeStyle(penToStroke(toolbarState.pen))
                    ?.setStrokeWidth(scaledWidth)
                    ?.setStrokeColor(toolbarState.activePenSetting.color)
                // Match the live square-pen nib angle to the dry render (+45°) so the calligraphy
                // stroke doesn't rotate on pen-up. See docs/onyx-sdk/onyx-pen-styles-catalog.md.
                if (toolbarState.pen == Pen.CALLIGRAPHY) {
                    configureCalligraphyLiveAngle(angleDegrees = 45f, strokeWidth = scaledWidth)
                }
            }

            Mode.Erase -> applyEraserIndicatorStyle(penEraserColor = Color.GRAY)

            Mode.Select -> touchHelper?.setStrokeStyle(penToStroke(Pen.BALLPEN))?.setStrokeWidth(3f)
                ?.setStrokeColor(Color.GRAY)
        }
    }

    /**
     * Configures the helper's stroke so the eraser feedback matches the active eraser type:
     * a marker for the pen eraser, and a dashed line for the lasso / select eraser. Shared
     * by the hand eraser (Mode.Erase in [updatePenAndStroke]) and the pen side-button
     * eraser ([onBeginRawErasing], native indicator).
     *
     * @param penEraserColor colour for the [Eraser.PEN] marker. Hand-erase uses grey; the
     * native button-erase indicator uses black (matches the user's preference and is more
     * visible against ink).
     */
    private fun applyEraserIndicatorStyle(penEraserColor: Int = Color.BLACK) {
        if (touchHelper == null) return
        when (toolbarState.eraser) {
            // Scaled by zoom exactly like the Draw path above: the swath handleErase deletes is
            // 30 page units, so the on-screen track is 30·zoom px. updatePenAndStroke runs on
            // every zoom change, which keeps this current.
            Eraser.PEN -> touchHelper!!.setStrokeStyle(penToStroke(Pen.MARKER))
                ?.setStrokeWidth(eraserIndicatorWidth(page.zoomLevel.value))
                ?.setStrokeColor(penEraserColor)

            Eraser.SELECT -> {
                val dashStyleID = penToStroke(Pen.DASHED)
                touchHelper!!.setStrokeStyle(dashStyleID)
                    ?.setStrokeWidth(3f)
                    ?.setStrokeColor(Color.BLACK)
                val params = FloatArray(4)
                params[0] = 5f // thickness
                params[1] = 9f // no idea
                params[2] = 9f // no idea
                params[3] = 0f // no idea
                Device.currentDevice().setStrokeParameters(dashStyleID, params)
            }
        }
    }

    suspend fun updateIsDrawing() {
        if(touchHelper == null) return
        log.i("Update is drawing: $toolbarState.isDrawing")
        if (toolbarState.isDrawing) {
            touchHelper!!.setRawDrawingEnabled(true)
            // setRawDrawingEnabled(true) resets the framework stroke config to firmware defaults
            // (brush channel on, eraser channel off). Re-assert the eraser channel (styled for the
            // active eraser type) and re-send the active pen style so the next stroke uses the tool.
            enableNativeEraser(touchHelper, toolbarState.eraser, page.zoomLevel.value)
            updatePenAndStroke()
        } else {
            // A pending resetScreenFreeze resume would re-freeze the screen after we disable
            // raw drawing (e.g. lasso select: the select-stroke refreshUi armed it) — kill it.
            cancelPendingScreenFreezeReset()
            // Check if drawing is completed
            CanvasEventBus.waitForDrawing()
            // draw to view, before showing drawing, avoid stutter
            drawCanvas.refreshManager.drawCanvasToView(null)
            touchHelper!!.setRawDrawingEnabled(false)
        }
    }

    fun updateActiveSurface() {
        // Takes at least 50ms on Note 4c,
        // and I don't think that we need it immediately
        log.i("Update editable surface")
        coroutineScope.launch {
            onSurfaceInit(drawCanvas)
            // Across the rail's short edge: its height when docked top/bottom, its width
            // when docked left/right. setupSurface reads the position itself.
            val open = toolbarState.isToolbarOpen
            val toolbarThickness =
                if (open) convertDpToPixel(TOOLBAR_THICKNESS.dp, drawCanvas.context).toInt()
                else 0
            // The title bar is shown and hidden with the rail, and sizes itself off the same
            // screen width the composition reads — so both arrive at the same band.
            val titleBarHeight =
                if (open) convertDpToPixel(
                    editorTitleBarHeight(
                        drawCanvas.context.resources.configuration.screenWidthDp
                    ),
                    drawCanvas.context
                ).toInt()
                else 0
            setupSurface(
                drawCanvas,
                touchHelper,
                toolbarThickness,
                titleBarHeight,
                zoom = page.zoomLevel.value
            )
            // setupSurface resets the framework stroke style to firmware defaults. Re-send the
            // pen style here, inside the same coroutine and after the surface is armed: a caller
            // that invokes updatePenAndStroke() right after updateActiveSurface() would otherwise
            // race this launch and have its style overwritten.
            updatePenAndStroke()
        }
    }
    /**
     * Turns one finished stroke into whatever the active tool means by it — ink, a shape, a
     * selection, an erase.
     *
     * The firmware calls this on pen-up with the points it collected; on a device without
     * the raw channel [MotionEventStrokeSource] calls it with the points it collected from
     * [android.view.MotionEvent]s instead. Deliberately does not test [touchHelper]: that is
     * exactly the case the fallback exists for, and the raw callback cannot fire without one.
     */
    internal fun onStrokeCollected(plist: TouchPointList) {
        val currentLastStrokeEndTime = lastStrokeEndTime
        // Stamped from the stroke's own last point, not from System.currentTimeMillis(). The
        // scribble-to-erase grace check compares this against the *next* stroke's first point
        // timestamp, so the two must share a clock base — and the base differs by path: the
        // fallback path stamps points with MotionEvent.eventTime (uptime, ~hours since boot),
        // while a wall-clock stamp here is epoch (~50 years). Every fallback stroke then read as
        // astronomically older than the last stroke's "end", and the guard killed scribble-erase
        // for the rest of the session. Taking the previous stroke's own pen-up timestamp keeps
        // both sides of the comparison on one clock, whichever clock the path uses.
        plist.points.lastOrNull()?.let { lastStrokeEndTime = it.timestamp }
        val startTime = System.currentTimeMillis()
        // Read here, on the callback, not inside the handlers below: they run on a coroutine or a
        // thread that reaches the page later still, and every one of them has to agree with the
        // others about where this stroke landed.
        val viewport = page.inkViewport()

        when (toolbarState.mode) {
            Mode.Erase -> onRawErasingList(plist)
            Mode.Select -> {
                thread {
                    val points =
                        copyInputToSimplePointF(plist.points, viewport)
                    handleSelect(
                        scope = coroutineScope,
                        page = drawCanvas.page,
                        viewModel = viewModel,
                        points = points
                    )
                    val boundingBox = calculateBoundingBox(points) { Pair(it.x, it.y) }.toRect()
                    val padding = 10
                    val dirtyRect = Rect(
                        boundingBox.left - padding,
                        boundingBox.top - padding,
                        boundingBox.right + padding,
                        boundingBox.bottom + padding
                    )
                    drawCanvas.refreshManager.refreshUi(dirtyRect)
                }
            }

            Mode.Line -> {
                coroutineScope.launch(Dispatchers.Main.immediate) {
                    CanvasEventBus.drawingInProgress.withLock {
                        val lock = System.currentTimeMillis()
                        log.d("lock obtained in ${lock - startTime} ms")


                        val (startPoint, endPoint) = getModifiedStrokeEndpoints(
                            plist.points,
                            viewport,
                        )
                        val linePoints =
                            ShapeGeometry.points(toolbarState.shape, startPoint, endPoint)

                        handleDraw(
                            drawCanvas.page,
                            strokeHistoryBatch,
                            toolbarState.activePenSetting.strokeSize,
                            toolbarState.activePenSetting.color,
                            toolbarState.pen,
                            linePoints
                        )

                        coroutineScope.launch(Dispatchers.Default) {
                            // Measured from the drawn points, not from the two endpoints: an
                            // arrow's barbs and an ellipse's waist reach outside the drag's
                            // rectangle, and refreshing only that rectangle would leave parts of
                            // the shape unpainted until something else redrew them.
                            val padding = 10
                            val dirtyRect = Rect(
                                linePoints.minOf { it.x }.toInt() - padding,
                                linePoints.minOf { it.y }.toInt() - padding,
                                linePoints.maxOf { it.x }.toInt() + padding,
                                linePoints.maxOf { it.y }.toInt() + padding
                            )
                            drawCanvas.refreshManager.refreshUi(dirtyRect)
                            CanvasEventBus.commitHistorySignal.emit(Unit)
                        }
                    }

                }
            }

            Mode.Draw -> {
                coroutineScope.launch(Dispatchers.Main.immediate) {
                    CanvasEventBus.drawingInProgress.withLock {
                        val lock = System.currentTimeMillis()
                        log.d("lock obtained in ${lock - startTime} ms")

                        val scaledPoints =
                            copyInput(plist.points, viewport)
                        val firstPointTime = plist.points.first().timestamp
                        val erasedByScribbleDirtyRect = handleScribbleToErase(
                            page,
                            scaledPoints,
                            history,
                            toolbarState.pen,
                            toolbarState.activePenSetting.strokeSize,
                            toolbarState.activePenSetting.color,
                            currentLastStrokeEndTime,
                            firstPointTime
                        )
                        if (erasedByScribbleDirtyRect.isNullOrEmpty()) {
                            log.d("Drawing...")
                            // draw the stroke
                            handleDraw(
                                drawCanvas.page,
                                strokeHistoryBatch,
                                toolbarState.activePenSetting.strokeSize,
                                toolbarState.activePenSetting.color,
                                toolbarState.pen,
                                scaledPoints
                            )
                            // The firmware's live track is already on the panel, so the raw path
                            // needs no push here — the pen-up refresh swaps it for the real
                            // stroke. Without it the stroke exists only in the page bitmap, and
                            // this is what puts it on screen. Measured from the untransformed
                            // points because a dirty rect is in view coordinates, and padded by
                            // the pen's width so the stroke's edges are not left clipped.
                            if (!usesRawInput) {
                                val padding =
                                    (toolbarState.activePenSetting.strokeSize * page.zoomLevel.value)
                                        .toInt() + 10
                                val box =
                                    calculateBoundingBox(plist.points) { Pair(it.x, it.y) }.toRect()
                                drawCanvas.refreshManager.refreshUi(
                                    Rect(
                                        box.left - padding,
                                        box.top - padding,
                                        box.right + padding,
                                        box.bottom + padding
                                    )
                                )
                            }
                        } else {
                            log.d("Erased by scribble, $erasedByScribbleDirtyRect")
                            // Union the scribble track (firmware screen coords) with the erased
                            // strokes' bounds so commitErase overwrites both in one pass while
                            // still frozen. Scribble is not drawn into the page bitmap — we only
                            // need the region to cover the firmware's live track.
                            // See docs/onyx-sdk/onyx-scribble-to-erase.md.
                            val padding = 10
                            val trackBox =
                                calculateBoundingBox(plist.points) { Pair(it.x, it.y) }.toRect()
                            val dirty = Rect(
                                trackBox.left - padding,
                                trackBox.top - padding,
                                trackBox.right + padding,
                                trackBox.bottom + padding
                            )
                            erasedByScribbleDirtyRect.let { dirty.union(it) }
                            // Use areaErase=true for the longer 500ms settle (scribble is a large gesture).
                            drawCanvas.refreshManager.commitErase(dirty, areaErase = true)
                        }

                    }
                    coroutineScope.launch(Dispatchers.Default) {
                        CanvasEventBus.commitHistorySignal.emit(Unit)
                    }
                }
            }
        }
    }

    private fun onRawErasingList(plist: TouchPointList?) {
        isErasing = false

        if (plist == null) return
        val points = copyInputToSimplePointF(plist.points, page.inkViewport())

        val padding = 10
        val boundingBox = (calculateBoundingBox(plist.points) { Pair(it.x, it.y) }).toRect()
        val strokeArea = Rect(
            boundingBox.left - padding,
            boundingBox.top - padding,
            boundingBox.right + padding,
            boundingBox.bottom + padding
        )
        val zoneEffected = handleErase(
            drawCanvas.page,
            history,
            points,
            eraser = toolbarState.eraser
        )

        // Single atomic commit of the whole touched region: the native eraser indicator
        // track spans strokeArea, the erased strokes' bounds are zoneEffected, so repainting
        // their union both wipes the indicator and shows the erased result in one pass.
        // commitErase blocks input, draws synchronously, then drops the firmware overlay so
        // indicator + strokes disappear together (no double refresh, no gap to draw into).
        // See docs/onyx-sdk/onyx-pen-up-refresh-and-screen-freeze.md.
        val dirty = Rect(strokeArea)
        if (zoneEffected != null) dirty.union(zoneEffected)
        // Area (lasso/select) erase needs the longer 500ms settle the official app uses; the
        // pen/marker erase uses the 150ms stroke settle.
        drawCanvas.refreshManager.commitErase(dirty, areaErase = toolbarState.eraser == Eraser.SELECT)
    }

}