package com.ethran.notable.editor


import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.core.graphics.createBitmap
import androidx.core.graphics.toRect
import com.ethran.notable.R
import com.ethran.notable.SCREEN_HEIGHT
import com.ethran.notable.SCREEN_WIDTH
import com.ethran.notable.data.CachedBackground
import com.ethran.notable.data.PageDataManager
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.db.Image
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.model.BackgroundType
import com.ethran.notable.data.model.PageSize
import com.ethran.notable.data.model.SimplePointF
import com.ethran.notable.editor.canvas.CanvasEventBus
import com.ethran.notable.editor.canvas.CanvasEventBus.drawingInProgress
import com.ethran.notable.editor.canvas.CanvasEventBus.waitForDrawing
import com.ethran.notable.editor.drawing.drawBg
import com.ethran.notable.editor.drawing.drawOnCanvasFromPage
import com.ethran.notable.editor.utils.div
import com.ethran.notable.editor.utils.divideStrokesFromCut
import com.ethran.notable.editor.utils.loadHQPagePreview
import com.ethran.notable.editor.utils.minus
import com.ethran.notable.editor.utils.plus
import com.ethran.notable.editor.utils.strokeBounds
import com.ethran.notable.editor.utils.times
import com.ethran.notable.editor.utils.toIntOffset
import com.ethran.notable.gestures.MAX_ZOOM
import com.ethran.notable.gestures.ZOOM_SENSITIVITY
import com.ethran.notable.gestures.ZOOM_SNAP_THRESHOLD
import com.ethran.notable.ui.SnackConf
import com.ethran.notable.ui.SnackState
import com.ethran.notable.utils.onError
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.system.measureTimeMillis

const val OVERLAP = 2

// Backgrounds are rendered this much larger than the current zoom so a small zoom-in reuses the
// cached bitmap instead of re-rendering (trades a little memory/render cost for fewer reloads).
private const val BACKGROUND_ZOOM_HEADROOM = 1.2f

data class PageCutMoveResult(
    val previousStrokes: List<Stroke>,
    val movedStrokes: List<Stroke>,
)

/**
 * Manages the state and rendering of a single page within the editor.
 * It delegates task to PageDataManager, which is responsible for loading data from db,
 * and caching it.
 */
class PageView(
    val context: Context,
    val coroutineScope: CoroutineScope,
    val pageDataManager: PageDataManager,
    val initialPageId: String,
    var viewWidth: Int,
    var viewHeight: Int,
    val snackManager: SnackState,
) {
    // TODO: unify width height variable

    val log = ShipBook.getLogger("PageView")
    private val logCache = ShipBook.getLogger("PageViewCache")

    private var loadingJob: Job? = null

    /**
     * Serializes page switching. Every step of a switch reads and writes state the page owns
     * ([currentPageId], [windowedBitmap], [windowedCanvas]), and each switch used to run in its own
     * coroutine over that state with nothing ordering them — so twenty fast taps on Next, or a drag
     * of the page scrubber, could let an older switch finish after a newer one.
     */
    private val pageSwitchMutex = Mutex()

    /**
     * Which page switch is the current one, claimed at tap time.
     *
     * The mutex alone only makes the switches take turns; it cannot say that four of the five
     * queued behind it are already pointless. Comparing generations answers the question that
     * matters — "has the user asked for a different page since?" — and lets a superseded switch
     * cost nothing.
     */
    private val pageSwitchGeneration = AtomicLong(0)

    @Volatile
    var windowedBitmap = createBitmap(viewWidth, viewHeight)
        private set

    @Volatile
    var windowedCanvas = Canvas(windowedBitmap)
        private set

    // Spare screen-sized buffer reused by updateScroll to avoid allocating a new
    // bitmap on every scroll event. Ping-ponged with windowedBitmap; recreated only
    // when the canvas size/config changes (zoom, dimension change, page switch).
    private var scrollBackBuffer: Bitmap? = null

    /**
     * The fraction of a pixel the last scroll could not move the picture by, held for the next
     * one. Screen pixels, like the drag deltas it is added to.
     */
    private var scrollRemainderPx = Offset.Zero

    //    var strokes = listOf<Stroke>()
    var strokes: List<Stroke>
        get() = pageDataManager.getStrokes(currentPageId)
        set(value) = pageDataManager.setStrokes(currentPageId, value)

    var images: List<Image>
        get() = pageDataManager.getImages(currentPageId)
        set(value) = pageDataManager.setImages(currentPageId, value)

    // warning: The setter is delayed!
    private var currentBackground: CachedBackground
        get() = pageDataManager.getCurrentBackground()
        set(value) = pageDataManager.setCurrentBackground(value)

    val currentPageId: String
        get() = pageDataManager.getCurrentPageId()


    // scroll is observed by ui, represents top left corner
    var scroll: Offset
        get() = pageDataManager.getPageScroll(currentPageId)
        set(value) = pageDataManager.setPageScroll(currentPageId, value)


    val isTransformationAllowed: Boolean
        get() = pageDataManager.isTransformationAllowedForCurrentPage()

    /**
     * The view the pen is drawing on right now, taken at pen-down; null when no stroke is open.
     *
     * Written from the input callbacks and read from whatever thread finishes the stroke, hence
     * volatile. See [InkViewport] for why a stroke may not be filed through the *live* view.
     */
    @Volatile
    private var strokeViewport: InkViewport? = null

    /** Where the page sits under the screen at this instant. */
    val currentViewport: InkViewport
        get() = InkViewport(scroll, zoomLevel.value)

    /** Pen down: hold the view the firmware is about to paint this stroke onto. */
    fun beginInkCapture() {
        strokeViewport = currentViewport
    }

    /** Pen up, cancelled, or the page changed under the stroke: stop holding a view. */
    fun endInkCapture() {
        strokeViewport = null
    }

    /**
     * The view a just-finished stroke must be filed through: the one it was drawn on.
     *
     * Falls back to the live view when no pen-down was seen — a shape or selection replayed from
     * elsewhere, or an input path that does not bracket its strokes — which is the behaviour this
     * replaced, and is right whenever nothing has moved.
     *
     * A difference between the two is the bug this exists to absorb, so it is logged rather than
     * passed over: something moved the view under a stroke in flight, and but for the snapshot the
     * ink would have been stored that far from where it was written.
     */
    fun inkViewport(): InkViewport {
        val atPenDown = strokeViewport ?: return currentViewport
        val now = currentViewport
        if (atPenDown != now) {
            log.w(
                "View moved under a stroke in flight; filing it where it was drawn. " +
                    "penDown=$atPenDown now=$now"
            )
        }
        return atPenDown
    }


    // we need to observe zoom level, to adjust strokes size.
    val zoomLevel: MutableStateFlow<Float> =
        MutableStateFlow(pageDataManager.getPageZoom(currentPageId, ifUnzoomed = 1f))

    var height: Int
        get() = pageDataManager.getPageHeight(currentPageId) ?: viewHeight
        set(value) {
            pageDataManager.setPageHeight(currentPageId, value)
        }

    /** The sheet the current page is laid out on, in page units. */
    val sheet: PageSize
        get() = pageDataManager.getSheet(currentPageId)

    /**
     * The zoom a page is shown at when it is fitted: the sheet exactly filling the view's width,
     * with the rest of the page reached by scrolling down.
     *
     * This is what makes a declared page size mean the same thing on both devices: the page is the
     * page, and the device scales to it. Before page sizes, zoom 1.0 *was* the fit because the page
     * width was the screen width — which is precisely why the same page came out different sizes on
     * the two apps.
     *
     * The width fit regardless of which way pages turn. Sideways turning briefly used the
     * whole-page fit instead, which letterboxed the sheet between dead margins on any screen
     * whose aspect is not the paper's — margins that looked like page but took no ink. The page
     * opens at full width now, and the part that does not fit is in the direction scrolling
     * already goes.
     */
    val fitToWidthZoom: Float
        get() = PageViewportBounds.fitToWidthZoom(sheet.width, viewWidth)

    /**
     * Whether the sheet is a hard edge — true for any page that declares its own size.
     *
     * A declared page *is* the paper: there is nothing to the right of it to look at or write on,
     * so the view never goes there. A page that declares nothing has no edge to enforce (its
     * "sheet" is only this device's screen), and bounding it would hide ink written past that
     * screen on a wider device, so it keeps the old free-roaming behaviour.
     */
    val hasHardBounds: Boolean
        get() = pageDataManager.hasDeclaredSheet(currentPageId)

    /**
     * The next / previous page of the notebook, as last resolved by the neighbor prefetch.
     * Null for a quick page, at either end of the book, or before the prefetch has run.
     */
    val nextPageId: String? get() = pageDataManager.nextPageIdOf(currentPageId)
    val previousPageId: String? get() = pageDataManager.previousPageIdOf(currentPageId)

    /**
     * Whether the "Scrolling" choice under Vertical Navigation is in force: the notebook is one
     * long surface, and vertical scrolling is allowed to flow across the seam between pages.
     * "Pagination" keeps the discrete turn, which on e-ink is one clean refresh.
     *
     * Deliberately *not* gated on the page-turn direction. It was at first — "a sideways turn
     * has no seam to scroll over" — which quietly turned the whole feature off for anyone who
     * turns pages sideways: their vertical drags stopped dead at the sheet's end and the space
     * below it stayed gray, which read as "scrolling is still paginated" and "the edge cannot
     * be written on". The two settings answer different questions: the page turn is what a
     * *sideways swipe* does; this is what *scrolling down* does when the page runs out.
     */
    val continuousScrollEnabled: Boolean
        get() = !GlobalAppSettings.current.verticalNavigation.isPaged

    /**
     * [continuousScrollEnabled], and the next page is actually known — the condition for the
     * seam to be drawn and scrolled past. On the last page there is nothing to draw under the
     * seam; writing past the end creates the next page instead (see handleDraw).
     */
    val crossPageScrollActive: Boolean
        get() = continuousScrollEnabled && nextPageId != null

    /**
     * The lowest zoom this page may be viewed at: the fit on a bounded page, so blank non-page
     * space beyond the sheet's right edge never comes on screen. See [PageViewportBounds].
     */
    val minZoom: Float
        get() = PageViewportBounds.minZoom(fitToWidthZoom, hasHardBounds)

    /**
     * The zoom to open a page at: the one it was left at this session, or the fit. Held inside the
     * same bounds every other zoom goes through, so a page cannot be entered at a zoom a pinch
     * could never have reached.
     */
    private fun initialZoom(): Float =
        pageDataManager.getPageZoom(currentPageId, fitToWidthZoom).coerceIn(minZoom, MAX_ZOOM)


//    private var dbStrokes = appRepository.strokeRepository
//    private var dbImages = appRepository.imageRepository

    val currentPageNumber: Int
        get() = pageDataManager.getCurrentPageNumber()

    /*
        If pageNumber is -1, its assumed that the background is image type.
     */
    fun getOrLoadBackground(filePath: String, pageNumber: Int, scale: Float): Bitmap? {
        log.i("getOrLoadBackground")
        val cached = currentBackground
        if (cached.matches(filePath, pageNumber, scale)) {
            log.i("Background bitmap (cached): ${cached.bitmap}")
            return cached.bitmap
        }
        // Render a little larger than requested so small zoom-in steps reuse this bitmap instead of
        // forcing a re-render (matches() accepts any cached scale >= requested). Multiplicative so
        // the headroom stays proportional as you zoom; floored to the old additive margin near 1x.
        val renderScale = (scale * BACKGROUND_ZOOM_HEADROOM).coerceAtLeast(scale + 0.1f)
        val newBackground = CachedBackground(filePath, pageNumber, renderScale)
        currentBackground = newBackground
        log.i("Background bitmap: ${newBackground.bitmap}")
        return newBackground.bitmap
    }

    fun getBackgroundPageNumber(): Int {
        // There might be a bug here -- check it again.
        return currentBackground.pageNumber
    }


    init {
        coroutineScope.launch(Dispatchers.IO) {
            // set page, and retrieve page data from db
            pageDataManager.setPage(initialPageId)
            log.i("PageView init with initial pageId: $initialPageId" )
            if(currentPageId.isEmpty())
                log.e("Current page id is empty")

            // The sheet is only known once the page row is loaded, so the fit is computed here
            // rather than at construction: a page that has not been zoomed yet opens fitted.
            zoomLevel.value = initialZoom()
            clampScrollToBounds()
            pageDataManager.getCachedBitmap(currentPageId)?.let { cached ->
                log.i("PageView: using cached bitmap")
                windowedBitmap = cached
                windowedCanvas = Canvas(windowedBitmap)
            } ?: run {
                log.i("PageView.init: creating new bitmap")
                recreateCanvas()
                pageDataManager.cacheBitmap(currentPageId, windowedBitmap)
            }

            coroutineScope.launch(Dispatchers.Main) {
                // If we do it with main.immediate then it wont work.
                CanvasEventBus.refreshUiImmediately.emit(Unit)
            }
            loadPage()
            log.d("Page loaded (Init with id: $currentPageId)")
            pageDataManager.startPersistingBitmaps(context)
        }
    }

    /**
     * Switches the `PageView` to display a different page.
     * **It doesn't notify the UI about the change.**
     *
     * This function handles the entire process of transitioning from the current page to a new one specified by `newPageId`.
     * It performs the following steps:
     * 1.  Saves the state of the old page, including persisting its bitmap representation to disk.
     * 2.  Updates the internal `currentPageId` to `newPageId`.
     * 3.  Fetches the new page's data from the repository.
     * 4.  Updates the `pageDataManager` to the new page context.
     * 5.  Restores the zoom level for the new page.
     * 6.  Attempts to load a cached bitmap for the new page. If a cached bitmap exists and its dimensions
     *     match the current view, it's used directly. If dimensions differ, the canvas is resized.
     * 7.  If no cached bitmap is available, it creates a new bitmap and canvas from scratch.
     * 8.  Launches a coroutine to load the page's content (strokes, images) asynchronously and refreshes the UI.
     *
     * @param newPageId The unique identifier of the page to switch to.
     */
    fun changePage(newPageId: String) {
        // Claimed before the coroutine starts, so "which page was asked for last" is settled at
        // tap time. A switch that a later tap has already overtaken then costs nothing at all
        // rather than racing the one that replaced it.
        val generation = pageSwitchGeneration.incrementAndGet()
        log.d("changePage Entry: -> $newPageId (request $generation)")

        coroutineScope.launch(Dispatchers.IO) {
            // Serialized, not merely cancellable. Every step below reads and writes state the page
            // owns — currentPageId, windowedBitmap, windowedCanvas — and two switches interleaving
            // over it could finish in the wrong order: the toolbar would name page C while the
            // bitmap and the loaded strokes belonged to page B, which is a blank or stale canvas
            // that the next stroke is then written onto.
            pageSwitchMutex.withLock {
                if (generation != pageSwitchGeneration.get()) {
                    log.d("changePage $newPageId superseded before it ran")
                    return@withLock
                }
                // Read here rather than at the call site: a superseded request returns above
                // without touching anything, so the page actually being left is whichever one the
                // last completed switch arrived at — not whatever was current when this was tapped.
                val oldId = currentPageId

                // The previous page's loader owns the same page state; let it finish unwinding
                // before that state is replaced.
                loadingJob?.cancelAndJoin()

                // A stroke finished just before the switch is still being processed on Main under
                // drawingInProgress, and it reads page identity — currentPageId, scroll, zoom — at
                // processing time (OnyxInputHandler, Mode.Draw/Line). Swapping the page out from
                // under it filed that stroke onto the NEW page, at the new page's scroll and zoom.
                // Wait it out the same way updateScroll/updateZoom do before touching page state.
                waitForDrawingWithSnack()
                // A pen still down when the page went out from under it has no view left to be
                // filed through: the one it was drawn on belongs to a page that is no longer here.
                endInkCapture()

                pageDataManager.onExit(oldId, windowedBitmap)
                pageDataManager.setPage(newPageId)
                zoomLevel.value = initialZoom()
                clampScrollToBounds()
                pageDataManager.getCachedBitmap(newPageId)?.let { cached ->
                    log.i("PageView: using cached bitmap")
                    windowedBitmap = cached
                    windowedCanvas = Canvas(windowedBitmap)
                    applyCanvasZoom()
                    // Check if we have correct size of canvas
                    if (windowedCanvas.width != viewWidth || windowedCanvas.height != viewHeight)
                        updateCanvasDimensions()
                } ?: run {
                    log.i("PageView.changePage: creating new bitmap")
                    recreateCanvas()
                    pageDataManager.cacheBitmap(newPageId, windowedBitmap)
                }

                log.d("New bitmap hash: ${windowedBitmap.hashCode()}, ID: $currentPageId")

                // Deliberately no second generation check here. A tap that lands mid-switch costs
                // one redraw of a page the user has already left — but bailing out at this point
                // would leave the page half-entered: current, with a bitmap, and with no strokes
                // ever loaded into it. One wasted refresh is the cheaper of the two.

                // Refresh UI without waiting for drawing.
                // TODO: Problem: Sometimes refreshUi had a problem with proper refreshing screen,
                //  using function that does not wait for drawing mostly solved the problem.
                //  but there might be still bugs with it.
                CanvasEventBus.refreshUiImmediately.emit(Unit)
                loadPage()
                log.d("Page loaded (updatePageID($currentPageId))")
            }
        }
    }

    /**
     * Sets the canvas transform to exactly the current zoom — *sets*, never multiplies.
     *
     * Every place that used to call `windowedCanvas.scale(...)` was implicitly relying on the
     * canvas being freshly created or just reset by `setBitmap` (which clears the matrix), and
     * one path relied on nothing at all: [updateCanvasDimensions] recreated the canvas and never
     * scaled it, so until the next zoom or scroll event everything drew in screen pixels while
     * the rest of the pipeline spoke page units. On a blank page that was invisible — a white
     * fill has no scale — which is how it survived; anything drawn at a real position (strokes,
     * the off-page shading) landed in the wrong place or off the bitmap entirely. Idempotent, so
     * it is safe wherever the canvas might or might not already carry the zoom.
     */
    @Suppress("DEPRECATION")
    private fun applyCanvasZoom() {
        windowedCanvas.setMatrix(Matrix().apply {
            setScale(zoomLevel.value, zoomLevel.value)
        })
    }

    private fun recreateCanvas() {
        windowedBitmap = createBitmap(viewWidth, viewHeight)
        windowedCanvas = Canvas(windowedBitmap)
        // The initial bitmap (a disk preview or a bare background) is drawn in screen pixels on
        // the identity matrix; the zoom comes on for everything after it.
        loadInitialBitmap()
        applyCanvasZoom()
    }

    /*
        Cancel loading strokes, and save bitmap to disk
    */
    fun disposeOldPage() {
        log.d("Dispose old page")
        pageDataManager.onExit(currentPageId, windowedBitmap)
        cleanJob()
    }


    // To be removed.
    private fun redrawAll(scope: CoroutineScope) {
        scope.launch(Dispatchers.Main.immediate) {
            val viewRectangle = Rect(0, 0, windowedCanvas.width, windowedCanvas.height)
            drawAreaScreenCoordinates(viewRectangle)
        }
    }

    /**
     * Starts loading the current page's content.
     *
     * Does not cancel a previous load itself: the only way to get a second one is to switch pages,
     * and [changePage] already cancels *and joins* the outgoing loader before it replaces any of
     * the state the loader is reading. Cancelling here without joining — which is what used to be
     * written here, commented out — would let the old loader keep running against the new page.
     */
    private fun loadPage() {
        logCache.i("Init from persist layer, pageId: $currentPageId")
        applyCanvasZoom()
        loadingJob = coroutineScope.launch(Dispatchers.IO) {
            try {
                snackManager.showSnackDuring(text = "Loading strokes...") {
                    val timeToLoad = measureTimeMillis {
                        logCache.d("Start page loading, id $currentPageId")
                        pageDataManager.requestCurrentPageLoadJoin()
                        logCache.d("Got page data (PageView.loadPage). id $currentPageId")
                    }
                    logCache.d("All strokes loaded in $timeToLoad ms")
                }
                // The load measured the page, so the bounds exist now: this is the clamp that
                // init/changePage deferred while the height was still unknown.
                clampScrollToBounds()
                // TODO: If we put it in loadPage(…) sometimes it will try to refresh
                //  without seeing strokes, I have no idea why.
                coroutineScope.launch(Dispatchers.Main) {
//                    delay(100)
                    CanvasEventBus.forceUpdate.emit(null)
                }
//                sleep(5000)

                logCache.d("Loaded page from persistent layer $currentPageId")
                if (!pageDataManager.validatePageDataLoaded(currentPageId))
                    logCache.e("Page should be loaded, but it is not. $currentPageId")
                // Admission control already evicts to fit before this load, so this trim is only a
                // safety net for an under-estimate (actual > estimate); run it at load completion,
                // not after a magic delay. Then prefetch neighbors into whatever budget is left.
                coroutineScope.launch(Dispatchers.Default) {
                    pageDataManager.trimToBudget()
                    pageDataManager.cacheNeighbors()
                }
//                sleep(10000)

            } catch (_: CancellationException) {
                val dataStatus = pageDataManager.validatePageDataLoaded(currentPageId)
                logCache.d("Page loading cancelled, data was loaded correctly: $dataStatus")
            } catch (e: Exception) {
                val dataStatus = pageDataManager.validatePageDataLoaded(currentPageId)
                logCache.e("Page loading cancelled, data was loaded correctly: $dataStatus", e)
            }
        }
    }


    fun addStrokes(strokesToAdd: List<Stroke>) {
        strokes += strokesToAdd
        updateHeightForChange(strokesToAdd)

        saveStrokesToPersistLayer(strokesToAdd)

//        persistBitmapDebounced()
    }

    fun applyPageCutOffset(cutLine: List<SimplePointF>, offset: Offset): PageCutMoveResult? {
        if (offset.x < 0 || offset.y < 0) return null

        val (_, previousStrokes) = divideStrokesFromCut(strokes, cutLine)
        if (previousStrokes.isEmpty()) return null

        val movedStrokes = previousStrokes.map { stroke ->
            stroke.copy(
                points = stroke.points.map { point ->
                    point.copy(x = point.x + offset.x, y = point.y + offset.y)
                },
                top = stroke.top + offset.y,
                bottom = stroke.bottom + offset.y,
                left = stroke.left + offset.x,
                right = stroke.right + offset.x
            )
        }

        removeStrokes(strokeIds = previousStrokes.map { it.id })
        addStrokes(movedStrokes)
        drawAreaScreenCoordinates(strokeBounds(previousStrokes + movedStrokes))

        return PageCutMoveResult(
            previousStrokes = previousStrokes,
            movedStrokes = movedStrokes,
        )
    }

    // Completely updates strokes
    fun updateStrokes(strokesToUpdate: List<Stroke>) {
        // TODO: Clean it up, move some logic to pageDataManager
        val strokeUpdateById = strokesToUpdate.associateBy { it.id }
        strokes = strokes.map { stroke ->
            strokeUpdateById[stroke.id] ?: stroke
        }
        updateHeightForChange(strokesToUpdate)
        pageDataManager.updateStrokesInDb(strokesToUpdate)
//        persistBitmapDebounced()
    }

    fun removeStrokes(strokeIds: List<String>) {
        strokes = strokes.filter { s -> !strokeIds.contains(s.id) }
        removeStrokesFromPersistLayer(strokeIds)
        pageDataManager.recomputeHeight(currentPageId)

//        persistBitmapDebounced()
    }

    fun getStrokes(strokeIds: List<String>): List<Stroke?> {
        return pageDataManager.getStrokes(strokeIds, currentPageId)
    }

    /**
     * Re-measures the page after strokes landed, through the same grow-stop the load path uses.
     *
     * This used to nudge [height] directly — `stroke.bottom + 50`, no ceiling — so a stroke
     * touching the visible bottom of a declared page always grew it, and each such stroke
     * unlocked another ~50px of scroll past the sheet. The overflow only lived until the next
     * open: the load-time measurement clamps to the sheet, which turned that ink from off-page
     * into unreachable. [PageDataManager.recomputeHeight] applies the identical rule live —
     * never taller than the sheet, or than the page already was this session, which is also the
     * exemption that keeps a legacy tall page's below-sheet ink reachable.
     */
    fun updateHeightForChange(strokesChanged: List<Stroke>) {
        if (strokesChanged.isEmpty()) return
        pageDataManager.recomputeHeight(currentPageId)
    }

    private fun saveStrokesToPersistLayer(strokes: List<Stroke>) =
        pageDataManager.saveStrokesToDb(strokes)


    private fun saveImagesToPersistLayer(image: List<Image>) = pageDataManager.saveImagesToDb(image)


    fun addImage(imageToAdd: Image) = addImage(listOf(imageToAdd))

    /**
     * The canvas has to reach the image, so the extent is recomputed rather than nudged.
     *
     * It used to be nudged, and with the wrong coordinate: `image.x + image.height`. On a page
     * where the image sat further down than across — the ordinary case for anything pasted below
     * what is already written — that computed a bottom edge above the image's own, so the canvas
     * stopped short of it and there was no scroll position that brought it into view. Nothing grew
     * horizontally at all, so an image past the right edge was unreachable outright.
     */
    fun addImage(imageToAdd: List<Image>) {
        images += imageToAdd
        saveImagesToPersistLayer(imageToAdd)
        pageDataManager.recomputeHeight(currentPageId)

//        persistBitmapDebounced()
    }

    fun removeImages(imageIds: List<String>) {
        images = images.filter { s -> !imageIds.contains(s.id) }
        removeImagesFromPersistLayer(imageIds)
        pageDataManager.recomputeHeight(currentPageId)
//        persistBitmapDebounced()
    }

    // Moves/updates existing images in place (same ids). Mirrors updateStrokes — used for a Move
    // displacement so we never delete-then-reinsert the same id (which raced to a UNIQUE crash).
    fun updateImages(imagesToUpdate: List<Image>) {
        val imageUpdateById = imagesToUpdate.associateBy { it.id }
        images = images.map { image -> imageUpdateById[image.id] ?: image }
        // Recomputed, not nudged, and for the same reason as in [addImage]: a move is exactly the
        // action that puts an image below the sheet or past its right edge, and the nudge here
        // added `x` to `height` — so dragging an image down left the canvas unable to reach it.
        pageDataManager.updateImagesInDb(imagesToUpdate)
        pageDataManager.recomputeHeight(currentPageId)
    }

    fun getImages(imageIds: List<String>): List<Image?> =
        pageDataManager.getImages(imageIds, currentPageId)


    private fun removeStrokesFromPersistLayer(strokeIds: List<String>) =
        pageDataManager.removeStrokesFromDb(strokeIds, currentPageId)

    private fun removeImagesFromPersistLayer(imageIds: List<String>) =
        pageDataManager.removeImagesFromDb(imageIds, currentPageId)

    // load background, fast, if it is accurate enough.
    private fun loadInitialBitmap(): Boolean {
        val bitmapFromDisc = loadHQPagePreview(
            context = context,
            pageID = currentPageId,
            scroll = scroll,
            zoom = zoomLevel.value,
            pageUpdatedAtMs = pageDataManager.pageFromDb?.updatedAt?.time,
            requireExactMatch = true,
        )
        if (bitmapFromDisc != null) {
            // let's control that the last preview fits the present orientation. Otherwise we'll ask for a redraw.
            if (bitmapFromDisc.height == windowedCanvas.height && bitmapFromDisc.width == windowedCanvas.width) {
                windowedCanvas.drawBitmap(bitmapFromDisc, 0f, 0f, Paint())
                log.d("loaded initial bitmap, drawing to canvas: ${windowedCanvas.hashCode()}, bitmap: ${windowedBitmap.hashCode()}, page: $currentPageId")
                return true
            } else
                log.i("Image preview does not fit canvas area - redrawing")
        }

        log.d("Drawing initial background.")
        // draw just background.
        val backgroundType = pageDataManager.getBackgroundType()
        if (backgroundType == BackgroundType.Native) {
            drawBgToCanvas(null)
        } else
            windowedCanvas.drawColor(Color.WHITE)
        log.d("loaded initial bitmap, drawing to canvas: ${windowedCanvas.hashCode()}, bitmap: ${windowedBitmap.hashCode()}, page: $currentPageId")
        return false
    }


    private fun cleanJob() {
        //ensure that snack is canceled, even on dispose of the page.
        coroutineScope.launch(Dispatchers.IO) {
            pageDataManager.cancelLoadingPage(pageId = currentPageId)
        }
        loadingJob?.cancel()
        if (loadingJob?.isActive == true) {
            log.e("Strokes are still loading, trying to cancel and resume")
        }
    }


    fun drawAreaPageCoordinates(
        pageArea: Rect, // in page coordinates
        ignoredStrokeIds: List<String> = listOf(),
        ignoredImageIds: List<String> = listOf(),
        canvas: Canvas? = null
    ) {
        val areaInScreen = toScreenCoordinates(pageArea)
        drawAreaScreenCoordinates(areaInScreen, ignoredStrokeIds, ignoredImageIds, canvas)
    }

    /*
        provided a rectangle, in screen coordinates, its check
        for all images intersecting it, excluding ones set to be ignored,
        and redraws them. Does not refresh screen/SurfaceView.
     */
    fun drawAreaScreenCoordinates(
        screenArea: Rect,
        ignoredStrokeIds: List<String> = listOf(),
        ignoredImageIds: List<String> = listOf(),
        canvas: Canvas? = null
    ) {
        val activeCanvas = canvas ?: windowedCanvas
        val pageArea = toPageCoordinates(screenArea)
        val pageAreaWithoutScroll = removeScroll(pageArea)
        drawOnCanvasFromPage(
            page = this,
            canvas = activeCanvas,
            canvasClipBounds = pageAreaWithoutScroll,
            pageArea = pageArea,
            ignoredStrokeIds = ignoredStrokeIds,
            ignoredImageIds = ignoredImageIds,
        ).onError {
            snackManager.showOrUpdateSnack(
                SnackConf(
                    text = "Error during drawing page area: ${it.userMessage}",
                    duration = 3000
                )
            )
        }
    }

    suspend fun simpleUpdateScroll(dragDelta: Offset) {
        // Just update scroll, for debugging.
        // It will redraw whole screen, instead of trying to redraw only needed area.
        log.d("Simple update scroll")
        val delta = (dragDelta / zoomLevel.value)

        waitForDrawingWithSnack()

        scroll = boundScroll(scroll + delta)

        CanvasEventBus.forceUpdate.emit(null)
    }


    fun alreadyDrawnRectAfterShift(
        movement: IntOffset,
        screenW: Int,
        screenH: Int
    ): Rect {
        val dx = -movement.x
        val dy = -movement.y
        val left = max(0, dx)
        val top = max(0, dy)
        val right = min(screenW, dx + screenW)
        val bottom = min(screenH, dy + screenH)
        return Rect(left, top, right, bottom)
    }

    suspend fun updateScroll(dragDelta: Offset) {
//        log.d("Update scroll, dragDelta: $dragDelta, scroll: $scroll, zoomLevel.value: $zoomLevel.value")
        val zoom = zoomLevel.value
        // drag delta is in screen coordinates, so we have to scale it back to page coordinates.
        // Carrying the last step's sub-pixel tail in with it, so slow travel accumulates into
        // whole pixels instead of being dropped a fraction at a time.
        val requestedPx = dragDelta + scrollRemainderPx
        val deltaInPage = boundScroll(
            scroll + Offset(requestedPx.x / zoom, requestedPx.y / zoom)
        ) - scroll

        // There is nothing to do, return.
        if (deltaInPage == Offset.Zero) {
            scrollRemainderPx = Offset.Zero
            return
        }

        // The scroll moves exactly as far as the picture is about to, and not one fraction of a
        // pixel further: a scroll the panel has not followed is a scroll the pen is filed through
        // but nobody can see. See PageViewportBounds.scrollStep.
        val step = PageViewportBounds.scrollStep(deltaInPage, zoom)
        scrollRemainderPx = step.remainderPx
        if (step.isStandingStill) return

        // before scrolling, make sure that strokes are drawn.
        waitForDrawingWithSnack()

        scroll += step.scrollDelta
        val movement = step.movementPx

        val width = windowedBitmap.width
        val height = windowedBitmap.height
        // Shift the existing bitmap content into the spare buffer, reusing it across
        // scroll events. Recreate the spare only if it doesn't match current geometry.
        val shiftedBitmap = scrollBackBuffer?.takeIf {
            it.width == width && it.height == height && it.config == windowedBitmap.config
        } ?: createBitmap(width, height, windowedBitmap.config!!)
        val shiftedCanvas = Canvas(shiftedBitmap)
        shiftedCanvas.drawColor(Color.RED) //for debugging.
        shiftedCanvas.drawBitmap(windowedBitmap, -movement.x.toFloat(), -movement.y.toFloat(), null)

        // Swap in the shifted bitmap; the old live buffer becomes the next spare.
        scrollBackBuffer = windowedBitmap
        windowedBitmap = shiftedBitmap
        windowedCanvas.setBitmap(windowedBitmap)
        applyCanvasZoom()

        redrawOutsideRect(
            alreadyDrawnRectAfterShift(movement, width, height),
            width,
            height
        )

//        persistBitmapDebounced()
        saveToPersistLayer()
    }


    /**
     * How far right the view may reach, in page units: the sheet, or the ink, whichever reaches
     * further — held by the same grow-stop the vertical extent uses.
     *
     * This used to be the sheet's width, full stop, for any page that declared a size. But
     * PageSplit stamps a sheet onto every page it touches without moving or clipping x, so a
     * formerly-endless page holding ink past the new sheet's right edge became a declared page
     * whose ink was *unreachable*: the pan clamped at the sheet and the zoom floor forbade zooming
     * out to it. The vertical axis got its legacy exemption for exactly this (see
     * [PageDataManager.recomputeHeight]); this is the missing horizontal twin. A declared page
     * whose content lives within its sheet still clamps at the sheet — the grow-stop means new
     * writes at the edge cannot ratchet it wider — while one holding wider legacy ink extends far
     * enough to reach it.
     *
     * Before the session's first measurement lands, falls back to the raw measured extent, which
     * is the sheet for any page whose ink is inside it.
     *
     * Not private: the page-cut selection spans exactly this width (see `handleSelect`), and the
     * definition of "how wide the page is" must live in one place.
     */
    fun pannableWidth(): Float =
        (pageDataManager.getPageWidth(currentPageId)
            ?: pageDataManager.computeWidth(currentPageId)).toFloat()

    private fun maxHorizontalScroll(zoom: Float = zoomLevel.value): Float =
        PageViewportBounds.maxHorizontalScroll(pannableWidth(), viewWidth, zoom)

    /** [scroll] clamped into the page: never before an edge, never past the opposite one. */
    private fun boundScroll(scroll: Offset, zoom: Float = zoomLevel.value): Offset =
        PageViewportBounds.boundScroll(
            scroll, pannableWidth(), viewWidth, zoom, height.toFloat(), viewHeight,
            overshootIntoNextPage = crossPageScrollActive)

    /**
     * Whether a scroll of [pageDelta] has nowhere left to go on this page — the page is already
     * against that edge and the bound would swallow the whole movement.
     *
     * [pageDelta] is the movement **as this page will apply it**, not as the finger made it. The
     * two have opposite signs: `EditorControlTower`'s scroll consumer negates before calling
     * `onPageScroll`, so a drag up (a negative gesture delta) arrives here positive, because
     * dragging up asks to move *down* the page. Callers hold a gesture delta and must negate it —
     * getting this backwards tests the far edge, which turns the page at the top and never turns it
     * at the bottom.
     *
     * This is what "past the end of the page" means now that a page stops at its sheet. Asking here
     * keeps the one definition of the page's edge in the place that already owns it.
     */
    fun isAtVerticalEdge(pageDelta: Float): Boolean {
        if (pageDelta == 0f) return false
        val moved = boundScroll(scroll + Offset(0f, pageDelta / zoomLevel.value)) - scroll
        return moved.y == 0f
    }

    /**
     * Pulls the current scroll back inside the page. Needed wherever the bounds move rather than
     * the scroll — a page switch, a rotation, a zoom out.
     *
     * Waits for the page to have been measured. Before the load has run, [height] falls back to
     * the view's own height, and clamping a scroll saved deep in the page against one screenful
     * folds it to the top — overwriting the saved position moments before the load would have
     * proven it valid. [loadPage] clamps again once the measurement exists, and every gesture
     * passes through [boundScroll] regardless, so nothing stays out of bounds for long.
     */
    private fun clampScrollToBounds() {
        if (pageDataManager.getPageHeight(currentPageId) == null) return
        val bounded = boundScroll(scroll)
        if (bounded != scroll) scroll = bounded
    }

    private fun calculateZoomLevel(
        scaleDelta: Float,
        currentZoom: Float,
    ): Float {
        // TODO: Better snapping logic
        // The two zooms worth snapping to: the whole sheet across the view, and 1:1 (one page unit
        // per pixel). Before page sizes these were the same thing in portrait, so the snap target
        // was derived from the screen; now the page decides, and the fit is what has to be easy to
        // land on, since it is the zoom at which the page looks like the page.
        val fitZoom = fitToWidthZoom
        // Never below the fit on a bounded page: zooming out past it would put non-page space on
        // screen, and the page edge is an edge.
        val floor = minZoom

        return if (!GlobalAppSettings.current.continuousZoom) {
            // Discrete zoom mode - snap to either 1.0 or the fit.
            // scaleDelta is a growth ratio minus 1 (see PointerTracker.pinchRatio),
            // so it is negative when pinching in (zoom out) and positive when
            // spreading (zoom in); split on 0, not 1.
            if (scaleDelta <= 0f) {
                max(min(fitZoom, 1.0f), floor)
            } else {
                max(fitZoom, 1.0f)
            }
        } else {
            // Continuous zoom: scaleDelta is the per-frame growth ratio minus 1,
            // so the zoom scales multiplicatively. ZOOM_SENSITIVITY damps how
            // hard the pinch drives the zoom (< 1 zooms more gently than the
            // fingers spread).
            val newZoom =
                (currentZoom * (1f + scaleDelta * ZOOM_SENSITIVITY)).coerceIn(floor, MAX_ZOOM)

            // Snap to either 1.0 or the fit, depending on which is closer
            val snapTarget = if (abs(newZoom - 1.0f) < abs(newZoom - fitZoom)) {
                1.0f
            } else {
                fitZoom
            }

            if (abs(newZoom - snapTarget) < ZOOM_SNAP_THRESHOLD) {
                log.d("Zoom snap to $snapTarget")
                snapTarget.coerceAtLeast(floor)
            } else {
                log.d("Left zoom as is. $newZoom")
                newZoom
            }
        }
    }

    suspend fun simpleUpdateZoom(scaleDelta: Float) {
        log.d("Simple Zoom updated, $scaleDelta")
        // Update the zoom factor
        val newZoomLevel = calculateZoomLevel(scaleDelta, zoomLevel.value)

        // If there's no actual zoom change, skip
        if (newZoomLevel == zoomLevel.value) {
            log.d("Zoom unchanged. Current level: ${zoomLevel.value}")
            return
        }
        log.d("New zoom level: $newZoomLevel")
        applyZoomAndRedraw(newZoomLevel)
    }

    suspend fun applyZoomAndRedraw(newZoom: Float) {
        zoomLevel.value = newZoom.coerceIn(minZoom, MAX_ZOOM)
        // Zooming out shows more of the page at once, so the scroll that was at its right edge no
        // longer is. Everything below redraws from scratch, so simply pulling it back is enough.
        clampScrollToBounds()
        waitForDrawingWithSnack()
        // Create a scaled bitmap to represent zoomed view
        val scaledWidth = windowedCanvas.width
        val scaledHeight = windowedCanvas.height
        log.d("Canvas dimensions: width=$scaledWidth, height=$scaledHeight")
        log.d("Bitmap dimensions: width=${windowedBitmap.width}, height=${windowedBitmap.height}")
        log.d("Screen dimensions: width=$SCREEN_WIDTH, height=$SCREEN_HEIGHT")
        log.d("Page View dimension: width=${viewWidth}, height=${viewHeight}")


        val zoomedBitmap = createBitmap(scaledWidth, scaledHeight, windowedBitmap.config!!)

        // Swap in the new zoomed bitmap
//        windowedBitmap.recycle() -- It causes race condition with init from persistent layer
        windowedBitmap = zoomedBitmap
        windowedCanvas.setBitmap(windowedBitmap)
        applyCanvasZoom()


        // Redraw everything at new zoom level
        val redrawRect = Rect(0, 0, windowedBitmap.width, windowedBitmap.height)

        log.d("Redrawing full logical rect: $redrawRect")
        windowedCanvas.drawColor(Color.GREEN)
        drawBgToCanvas(redrawRect)
        pageDataManager.cacheBitmap(currentPageId, windowedBitmap)

        drawAreaScreenCoordinates(redrawRect)

        saveToPersistLayer()
        log.i("Zoom and redraw completed")
    }


    /**
     * Update zoom by reusing the existing screen bitmap.
     * - Scales the snapshot around the given center (screen coords).
     * - Redraws only the uncovered bands when zooming out.
     * - When zooming in, keeps the upscaled snapshot (even if low-res) for now.
     * - Updates scroll (IntOffset) so that the top-left of the view is correct after zoom,
     *   keeping the content under the pinch center stationary on screen.
     */
    suspend fun updateZoom(scaleDelta: Float, center: Offset?) {
        log.d("Zoom(delta): $scaleDelta. Center: $center")

        val oldZoom = zoomLevel.value
        val newZoom = calculateZoomLevel(scaleDelta, oldZoom)
        if (newZoom == oldZoom) {
            log.d("Zoom unchanged. Current level: $oldZoom")
            return
        }

        // Flush pending strokes/background before snapshot-based operations
        waitForDrawingWithSnack()

        val scaleFactor = newZoom / oldZoom
        val screenW = windowedCanvas.width
        val screenH = windowedCanvas.height

        // Default pivot to screen center if none passed
        val pivotX = center?.x ?: (screenW / 2f)
        val pivotY = center?.y ?: (screenH / 2f)

        // Draw scaled snapshot into a fresh screen-sized bitmap
        val scaledBitmap = createBitmap(screenW, screenH, windowedBitmap.config!!)
        val scaledCanvas = Canvas(scaledBitmap)
        scaledCanvas.drawColor(Color.RED) // clear

        val matrix = Matrix().apply {
            postScale(scaleFactor, scaleFactor, pivotX, pivotY)
        }

        // Calculate where the scaled snapshot ended up on screen.
        // Map the original screen rect through the same matrix to get content bounds.
        val srcRect = RectF(0f, 0f, screenW.toFloat(), screenH.toFloat())
        val dstRect = RectF()
        matrix.mapRect(dstRect, srcRect)


        //make sure that we won't go outside canvas.
        val dx = (scroll.x - dstRect.left).coerceAtMost(0f)
        val dy = (scroll.y - dstRect.top).coerceAtMost(0f)
        if (dx != 0f || dy != 0f) {
            matrix.postTranslate(dx, dy)
            matrix.mapRect(dstRect, srcRect)
        }
        scaledCanvas.drawBitmap(windowedBitmap, matrix, null)


        val deltaScrollPage = Offset(-dstRect.left / newZoom, -dstRect.top / newZoom)


        val unbounded = scroll + deltaScrollPage
        // The zoom moves the bounds as well as the view: zooming out at the page's right edge
        // leaves the old scroll past the new maximum. Bound against the *new* zoom.
        val bounded = boundScroll(unbounded, newZoom)
        scroll = bounded

        // Swap in the new bitmap and update zoom on the windowed canvas
        windowedBitmap = scaledBitmap
        windowedCanvas.setBitmap(windowedBitmap)

        zoomLevel.value = newZoom
        applyCanvasZoom()

        if (bounded != unbounded) {
            // The snapshot was scaled about the pinch centre, so it sits where the *unbounded*
            // scroll would have put it. Once the bound moves us elsewhere it no longer lines up
            // with anything — redraw the screen rather than shifting a picture of the wrong place.
            val fullScreen = Rect(0, 0, screenW, screenH)
            drawBgToCanvas(fullScreen)
            drawAreaScreenCoordinates(fullScreen)
        } else if (scaleFactor < 1f) redrawOutsideRect(dstRect.toRect(), screenW, screenH)

//        persistBitmapDebounced()
        saveToPersistLayer()
        log.i(
            "Zoom updated using snapshot scaling. " +
                    "oldZoom=$oldZoom newZoom=$newZoom " +
                    "scaleFactor=$scaleFactor pivot=($pivotX,$pivotY) " +
                    "bounds=$dstRect" +
                    "scrollDelta=$deltaScrollPage newScroll=$scroll"
        )
    }

    fun redrawOutsideRect(dstRect: Rect, screenW: Int, screenH: Int) {
        val scaledOverlap = ceil(OVERLAP * zoomLevel.value.coerceAtLeast(1f)).toInt()

        // Uncovered top band
        if (dstRect.top > 0) {
            val r = Rect(
                0,
                0,
                screenW,
                (dstRect.top + scaledOverlap).coerceAtMost(screenH)
            )
            if (!r.isEmpty) drawAreaScreenCoordinates(r)
        }
        // Uncovered bottom band
        if (dstRect.bottom < screenH) {
            val r = Rect(
                0, (dstRect.bottom - scaledOverlap).coerceAtLeast(0), screenW, screenH
            )
            if (!r.isEmpty) drawAreaScreenCoordinates(r)
        }
        // Uncovered left band
        if (dstRect.left > 0) {
            val r = Rect(
                0,
                (dstRect.top - scaledOverlap).coerceAtLeast(0),
                (dstRect.left + scaledOverlap).coerceAtMost(screenW),
                (dstRect.bottom + scaledOverlap).coerceAtMost(screenH)
            )
            if (!r.isEmpty) drawAreaScreenCoordinates(r)
        }
        // Uncovered right band
        if (dstRect.right < screenW) {
            val r = Rect(
                (dstRect.right - scaledOverlap).coerceAtLeast(0),
                (dstRect.top - scaledOverlap).coerceAtLeast(0),
                screenW,
                (dstRect.bottom + scaledOverlap).coerceAtMost(screenH)
            )
            if (!r.isEmpty) drawAreaScreenCoordinates(r)
        }
    }


    fun drawBgToCanvas(clipRect: Rect?) {
        val backgroundType = pageDataManager.getBackgroundType() ?: BackgroundType.Native
        val bg = pageDataManager.getBackgroundName()
        val pageNumber = currentPageNumber
        val scale = zoomLevel.value
        val bgImage: Bitmap? =
            when (backgroundType) {
                BackgroundType.Image, BackgroundType.CoverImage, BackgroundType.AutoPdf,
                is BackgroundType.Pdf, BackgroundType.ImageRepeating -> {
                    if (backgroundType is BackgroundType.Image && bg == "iris") {
                        val resId = R.drawable.iris
                        ImageBitmap.imageResource(context.resources, resId).asAndroidBitmap()
                    } else {
                        getOrLoadBackground(bg, pageNumber, scale)
                    }
                }

                BackgroundType.Native -> {
                    null
                }
            }
        drawBg(
            canvas = windowedCanvas,
            backgroundType = backgroundType,
            background = bg,
            sheet = sheet,
            scroll = scroll,
            resourceBitmap = bgImage,
            scale = scale,
            // The real flag, matching what export passes (PageContentRenderer): a repeating
            // background tiles on screen the same way it does in the PDF. This was hardcoded
            // false, so ImageRepeating pages never tiled on screen at all.
            repeat = backgroundType is BackgroundType.ImageRepeating,
            clipRect = clipRect
        )
    }


    fun updateDimensions(newWidth: Int, newHeight: Int) {
        if (newWidth != viewWidth || newHeight != viewHeight) {
            log.d("Updating dimensions: $newWidth x $newHeight")
            viewWidth = newWidth
            viewHeight = newHeight
            updateCanvasDimensions()
        }
    }

    private fun updateCanvasDimensions() {
        // Re-fit to the new width. Resetting to 1.0 was the old "fit", back when the page was the
        // screen; on a declared sheet it would leave the page adrift after every rotation.
        // Before the canvas is recreated, so the new canvas carries the new zoom.
        zoomLevel.value = fitToWidthZoom
        // Recreate bitmap and canvas with new dimensions
        recreateCanvas()
        pageDataManager.setPageZoom(currentPageId, zoomLevel.value)
        // The new width moves the right-hand bound with it.
        clampScrollToBounds()
        // TODO: it might be worth to do it
        //  by redrawing only part of the screen, like in scroll and zoom.
        coroutineScope.launch {
            CanvasEventBus.forceUpdate.emit(null)
        }
//        persistBitmapDebounced()
    }


    private fun saveToPersistLayer() = pageDataManager.setScrollInDb()

    fun applyZoom(point: IntOffset): IntOffset {
        return point * zoomLevel.value
    }

    fun removeZoom(point: IntOffset): IntOffset {
        return point / zoomLevel.value
    }

    private fun removeScroll(rect: Rect): Rect {
        return rect - scroll
    }

    fun toScreenCoordinates(rect: Rect): Rect {
        return (rect - scroll) * zoomLevel.value
    }

    private fun toPageCoordinates(rect: Rect): Rect {
        return rect / zoomLevel.value + scroll
    }

    private suspend fun waitForDrawingWithSnack() {
        if (drawingInProgress.isLocked) {
            snackManager.runWithSnack("Waiting for drawing to finish…", resultDurationMs = 0) {
                waitForDrawing()
                "Drawing finished"
            }
        }
    }
}