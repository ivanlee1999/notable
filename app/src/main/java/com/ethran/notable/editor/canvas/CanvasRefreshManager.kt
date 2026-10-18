package com.ethran.notable.editor.canvas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import com.ethran.notable.data.model.SimplePointF
import com.ethran.notable.editor.EditorViewModel
import com.ethran.notable.editor.PageView
import com.ethran.notable.editor.drawing.selectPaint
import com.ethran.notable.editor.state.Mode
import com.ethran.notable.editor.utils.DeviceCompat
import com.ethran.notable.editor.utils.pointsToPath
import com.ethran.notable.editor.utils.refreshScreenRegion
import com.ethran.notable.editor.utils.resetScreenFreeze
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.pen.TouchHelper
import io.shipbook.shipbooksdk.Log
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch

class CanvasRefreshManager(
    private val drawCanvas: DrawCanvas,
    private val page: PageView,
    private val viewModel: EditorViewModel,
    private val touchHelper: TouchHelper?
) {
    private val log = ShipBook.getLogger("DrawCanvas")
    private val frameHandler = Handler(Looper.getMainLooper())

    fun refreshUi(dirtyRect: Rect?) {
        log.d("refreshUi: scroll: ${page.scroll}, zoom: ${page.zoomLevel.value}")

        postRefresh(dirtyRect)
    }

    suspend fun refreshUiSuspend() {
        // Wait for both the stroke work and the actual SurfaceHolder post. Merely scheduling
        // View.post leaves the old bitmap on screen while the firmware ink is cleared.
        CanvasEventBus.waitForDrawing()
        val completed = CompletableDeferred<Unit>()
        postRefresh(null) { completed.complete(Unit) }
        completed.await()
    }

    private fun postRefresh(dirtyRect: Rect?, onComplete: () -> Unit = {}) {
        val pageId = page.currentPageId
        val inkGeneration = page.inkHandoffGuard.generation
        drawCanvasToView(dirtyRect) { posted ->
            try {
                // Recheck mode here: selection/page transitions can happen while this frame
                // is queued. Never clear the raw layer if no replacement frame was posted.
                if (posted && page.currentPageId == pageId &&
                    viewModel.toolbarState.value.isDrawing &&
                    !CanvasEventBus.drawingInProgress.isLocked
                ) {
                    page.inkHandoffGuard.runIfIdle(inkGeneration) {
                        resetScreenFreeze(touchHelper)
                    }
                }
            } finally {
                onComplete()
            }
        }
    }

    /**
     * Atomic erase commit for both the pen-button eraser and scribble-to-erase. Pushes the
     * already-repainted page bitmap to the panel while the screen is still frozen, then fully
     * toggles setRawDrawingEnabled(false→true) to drop the firmware layer atomically — eraser
     * indicator and erased strokes disappear in one transition with no gap to draw into.
     * ORDER IS CRITICAL: push first, then drop. See docs/onyx-sdk/onyx-pen-up-refresh-and-screen-freeze.md.
     * Must be called after the page bitmap has already been repainted (after handleErase /
     * handleScribbleToErase).
     */
    fun commitErase(dirtyRect: Rect?, areaErase: Boolean = false) {
        val dirty = dirtyRect ?: Rect(0, 0, page.viewWidth, page.viewHeight)
        // 1. Block input immediately so no stroke can start during the swap/settle.
        touchHelper?.setRawInputReaderEnable(false)
        drawCanvas.coroutineScope.launch {
            // 2. Push the erased page bitmap to the EPD *while still frozen* (drawBitmapToSurfaceSync
            //    forces it through with enablePost(0)+enablePost(1), like kreader's renderToScreen).
            drawBitmapToSurfaceSync(dirty)
            // 3. Now drop the firmware raw layer (eraser indicator / scribble ink). The panel
            //    already shows the clean page, so this is a no-flash transition.
            touchHelper?.setRawDrawingEnabled(false)
            // 4. Settle before re-arming (150ms stroke / 500ms area), mirroring the official app.
            DeviceCompat.delayBeforeResumingDrawing(isErasing = true, areaErase = areaErase)
            // 5. Re-arm raw drawing (also re-enables input). The heavy toggle resets the eraser
            //    channel and stroke style; enableRawDrawing re-asserts both (matches the
            //    official C(true) path).
            if (viewModel.toolbarState.value.isDrawing) {
                drawCanvas.inputHandler.enableRawDrawing()
            } else {
                log.w("commitErase: not in drawing mode, leaving raw drawing disabled")
            }
        }
    }

    /** Synchronous variant of [drawCanvasToView] (no `post{}` hop). Locks, draws the page
     *  bitmap for [dirtyRect], and posts — on the calling thread. */
    private fun drawBitmapToSurfaceSync(dirtyRect: Rect?) {
        val zoneToRedraw = dirtyRect ?: Rect(0, 0, page.viewWidth, page.viewHeight)
        var canvas: Canvas? = null
        try {
            canvas = drawCanvas.holder.lockCanvas(zoneToRedraw)
            if (canvas == null) {
                log.e("commitErase: failed to lock canvas (surface invalid/destroyed)")
                return
            }
            // enablePost(0)+enablePost(1) forces the bitmap through to the EPD even while the
            // screen is frozen (a bare unlockCanvasAndPost is swallowed). Mirrors kreader's
            // RxBaseReaderRequest.unlockCanvas.
            EpdController.enablePost(0)
            EpdController.enablePost(1)
            canvas.drawBitmap(page.windowedBitmap, zoneToRedraw, zoneToRedraw, Paint())
        } catch (e: IllegalStateException) {
            log.w("Surface released during erase draw", e)
        } finally {
            try {
                if (canvas != null) drawCanvas.holder.unlockCanvasAndPost(canvas)
                // Let the EPD apply the pushed region (kreader's afterUnlockCanvas equivalent).
                EpdController.resetViewUpdateMode(drawCanvas)
            } catch (e: IllegalStateException) {
                log.w("Surface released during unlock", e)
            }
        }
    }

    /** [onPosted] reports whether drawing AND posting succeeded, including queue rejection. */
    fun drawCanvasToView(dirtyRect: Rect?, onPosted: ((Boolean) -> Unit)? = null) {
        val dirty = dirtyRect?.let(::Rect)
        enqueueSurfaceFrame(
            // View.post retains work until reattachment. A destroyed editor may never
            // reattach, so use the main looper and explicitly reject detached surfaces.
            enqueue = { frame -> frameHandler.post { frame() } },
            render = render@{
                if (!drawCanvas.isAttachedToWindow) return@render false
                val zoneToRedraw = dirty ?: Rect(0, 0, page.viewWidth, page.viewHeight)
                postSurfaceFrame(
                    lock = { drawCanvas.holder.lockCanvas(zoneToRedraw) },
                    draw = { canvas ->
                        canvas.drawBitmap(page.windowedBitmap, zoneToRedraw, zoneToRedraw, Paint())
                        if (viewModel.toolbarState.value.mode == Mode.Select) {
                            viewModel.selectionState.firstPageCut?.let { cutPoints ->
                                val path = pointsToPath(cutPoints.map {
                                    SimplePointF(it.x - page.scroll.x, it.y - page.scroll.y)
                                })
                                canvas.drawPath(path, selectPaint)
                            }
                        }
                    },
                    unlockAndPost = { drawCanvas.holder.unlockCanvasAndPost(it) },
                    onFailure = { log.w("Surface released during bitmap post", it) },
                )
            },
            onPosted = { onPosted?.invoke(it) },
        )
    }


    fun restoreCanvas(dirtyRect: Rect, bitmap: Bitmap = page.windowedBitmap) {
        drawCanvas.post {
            val holder = drawCanvas.holder
            var surfaceCanvas: Canvas? = null
            try {
                surfaceCanvas = holder.lockCanvas(dirtyRect)
                // Draw the preview bitmap scaled to fit the dirty rect
                surfaceCanvas.drawBitmap(bitmap, dirtyRect, dirtyRect, null)
            } catch (e: Exception) {
                Log.e("DrawCanvas", "Canvas lock failed: ${e.message}")
            } finally {
                if (surfaceCanvas != null) {
                    log.d("restoreCanvas: page=${page.currentPageId} bitmap=${bitmap.hashCode()}")
                    holder.unlockCanvasAndPost(surfaceCanvas)
                }
                // Trigger partial refresh
                refreshScreenRegion(drawCanvas, dirtyRect)
            }
        }
    }
}