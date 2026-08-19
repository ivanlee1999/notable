package com.ethran.notable.editor.utils

import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.db.MAX_PRESSURE_NORMALIZED
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.db.StrokePoint
import com.ethran.notable.data.model.SimplePointF
import com.ethran.notable.editor.PageView
import com.ethran.notable.editor.state.History
import com.ethran.notable.editor.state.Operation
import io.shipbook.shipbooksdk.Log

enum class Eraser(val _name: String) {
    PEN("PEN"), SELECT("SELECT"),
}

const val SCRIBBLE_TO_ERASE_GRACE_PERIOD_MS = 150L
const val SCRIBBLE_INTERSECTION_THRESHOLD = 0.20f

/**
 * Whether a stroke starting at [firstPointTime] is still inside the grace period after the
 * previous stroke ended at [lastStrokeEndTime] — too soon after real writing to be read as a
 * scribble-erase gesture.
 *
 * Both stamps must come from the same clock, which is why the caller takes them from the input
 * path's own point timestamps ([com.ethran.notable.editor.canvas.OnyxInputHandler
 * .onStrokeCollected]): the check itself is base-agnostic, comparing only their difference.
 * Mixing bases is the bug this signature pins — an epoch stroke-end stamp against uptime point
 * timestamps made every stroke of a session look like it started decades "before" the last one
 * ended, and permanently suppressed scribble-erase on the fallback input path.
 */
fun isWithinScribbleGrace(lastStrokeEndTime: Long, firstPointTime: Long): Boolean =
    firstPointTime < lastStrokeEndTime + SCRIBBLE_TO_ERASE_GRACE_PERIOD_MS

/**
 * Width of the pen-eraser swath in PAGE units: the diameter of the region [handleErase] actually
 * deletes. Shared so the native side-button eraser indicator (see einkHelper.enableNativeEraser)
 * is drawn at exactly the size it erases — through [eraserIndicatorWidth], because the indicator
 * lives on the screen and page units only match screen pixels at zoom 1.
 */
const val ERASER_SWATH_WIDTH = 30f

/**
 * The on-screen width of the eraser indicator for a given [zoom] — the swath's page-unit diameter
 * scaled exactly the way the Draw path scales a pen's live width (`strokeSize * zoomLevel`).
 * Unscaled, the firmware track showed a 30px circle while the erase removed 30·zoom px of ink:
 * too small zoomed in, too wide zoomed out.
 */
fun eraserIndicatorWidth(zoom: Float): Float = ERASER_SWATH_WIDTH * zoom

const val MINIMUM_SCRIBBLE_POINTS = 15


// Calculates total stroke length using Manhattan distance
private fun calculateStrokeLength(points: List<StrokePoint>): Float {
    var totalDistance = 0.0f
    for (i in 1 until points.size) {
        val dx = points[i].x - points[i - 1].x
        val dy = points[i].y - points[i - 1].y
        totalDistance += kotlin.math.abs(dx) + kotlin.math.abs(dy)
    }
    return totalDistance
}

// Counts the number of direction changes (sharp reversals) in a stroke
private fun calculateNumReversals(
    points: List<StrokePoint>, stepSize: Int = 10
): Int {
    var numReversals = 0
    for (i in 0 until points.size - 2 * stepSize step stepSize) {
        val p1 = points[i]
        val p2 = points[i + stepSize]
        val p3 = points[i + 2 * stepSize]
        val segment1 = SimplePointF(p2.x - p1.x, p2.y - p1.y)
        val segment2 = SimplePointF(p3.x - p2.x, p3.y - p2.y)
        val dotProduct = segment1.x * segment2.x + segment1.y * segment2.y
        // Reversal is detected when angle between segments > 90 degrees
        if (dotProduct < 0) {
            numReversals++
        }
    }
    return numReversals
}


// Filters strokes that significantly intersect with a given bounding box
private fun filterStrokesByIntersection(
    candidateStrokes: List<Stroke>,
    boundingBox: RectF,
    threshold: Float = SCRIBBLE_INTERSECTION_THRESHOLD
): List<Stroke> {
    return candidateStrokes.filter { stroke ->
        val strokeRect = strokeBounds(stroke)
        val intersection = RectF()

        if (intersection.setIntersect(strokeRect, boundingBox)) {
            val strokeArea = strokeRect.width() * strokeRect.height()
            val intersectionArea = intersection.width() * intersection.height()
            val intersectionRatio = if (strokeArea > 0) intersectionArea / strokeArea else 0f

            intersectionRatio >= threshold
        } else {
            false
        }
    }
}

// Erases strokes if touchPoints are "scribble", returns true if erased.
// returns null if not erased, dirty rectangle otherwise
fun handleScribbleToErase(
    page: PageView,
    touchPoints: List<StrokePoint>,
    history: History,
    pen: Pen,
    strokeSize: Float,
    color: Int,
    currentLastStrokeEndTime: Long,
    firstPointTime: Long
): Rect? {
    if (pen == Pen.MARKER) return null // do not erase with highlighter
    if (!GlobalAppSettings.current.scribbleToEraseEnabled) return null // scribble to erase is disabled
    if (touchPoints.size < MINIMUM_SCRIBBLE_POINTS) return null
    if (isWithinScribbleGrace(currentLastStrokeEndTime, firstPointTime)) return null // not enough time has passed since last stroke
    if (calculateNumReversals(touchPoints) < 2) return null

    val strokeLength = calculateStrokeLength(touchPoints)
    val boundingBox = calculateBoundingBox(touchPoints) { Pair(it.x, it.y) }
    val width = boundingBox.width()
    val height = boundingBox.height()
    if (width == 0f || height == 0f) return null

    // Require scribble to be long enough relative to bounding box
    val minLengthForScribble = (width + height) * 3
    if (strokeLength < minLengthForScribble) {
        Log.d("ScribbleToErase", "Stroke is too short, $strokeLength < $minLengthForScribble")
        return null
    }

    // calculate stroke width based on bounding box
    // bigger swinging in scribble = bigger bounding box => larger stroke size
    val minDim = kotlin.math.min(boundingBox.width(), boundingBox.height())
    val maxDim = kotlin.math.max(boundingBox.width(), boundingBox.height())
    val aspectRatio = if (minDim > 0) maxDim / minDim else 1f
    val scaleFactor = kotlin.math.min(1f + (aspectRatio - 1f) / 2f, 2f)
    val strokeSizeForDetection = minDim * 0.15f * scaleFactor


    // Get strokes that might intersect with the scribble path
    val path = pointsToPath(touchPoints.map { SimplePointF(it.x, it.y) })
    val outPath = Path()
    Paint().apply { this.strokeWidth = strokeSizeForDetection }.getFillPath(path, outPath)
    val candidateStrokes = selectStrokesFromPath(page.strokes, outPath)


    // Filter intersecting strokes based on intersection ratio
    val expandedBoundingBox = boundingBox.expandBy(strokeSizeForDetection / 2)
    val deletedStrokes = filterStrokesByIntersection(candidateStrokes, expandedBoundingBox)

    // A scribble reaching below the seam means the next page's ink, on the same terms: its own
    // coordinates, and the same intersection test with the box shifted with it.
    val seamArea = eraseUnderSeam(page, outPath) { candidates ->
        filterStrokesByIntersection(
            candidates,
            RectF(expandedBoundingBox).apply { offset(0f, -page.height.toFloat()) },
        )
    }

    // If strokes were found, remove them and update history
    if (deletedStrokes.isNotEmpty()) {
        val deletedStrokeIds = deletedStrokes.map { it.id }
        page.removeStrokes(deletedStrokeIds)

        // Build the scribble as a real stroke (not added to the page — the net visible result is
        // still "everything gone") so undo can bring it back. Two history blocks make undo staged:
        //   undo #1 (top block)    → re-add the erased strokes AND the scribble
        //   undo #2 (older block)  → remove the scribble again (leaving just the erased strokes)
        // Pushed older-first because undo pops the most-recent block first.
        val scribbleBox = calculateBoundingBox(touchPoints) { Pair(it.x, it.y) }
        scribbleBox.inset(-strokeSize, -strokeSize)
        val scribbleStroke = Stroke(
            size = strokeSize,
            pen = pen,
            pageId = page.currentPageId,
            top = scribbleBox.top,
            bottom = scribbleBox.bottom,
            left = scribbleBox.left,
            right = scribbleBox.right,
            points = touchPoints,
            color = color,
            // Pressure is normalized to [0,1] at capture (TouchPoint.toStrokePoint).
            maxPressure = MAX_PRESSURE_NORMALIZED
        )
        history.addOperationsToHistory(listOf(Operation.DeleteStroke(listOf(scribbleStroke.id))))
        history.addOperationsToHistory(listOf(Operation.AddStroke(deletedStrokes + scribbleStroke)))
        // Return the erased region in SCREEN coordinates (mirrors handleErase). The caller
        // pushes this rect to the SurfaceView/EPD via commitErase, and the surface bitmap
        // (windowedBitmap) is in screen space — returning page coords here pushed the wrong
        // region whenever scrolled/zoomed. The caller unions this with the scribble track so
        // the firmware ink is cleared in the same post. See docs/onyx-sdk/onyx-scribble-to-erase.md.
        val effectedArea = page.toScreenCoordinates(strokeBounds(deletedStrokes))
        page.drawAreaScreenCoordinates(screenArea = effectedArea)
        if (seamArea != null) effectedArea.union(seamArea)
        return effectedArea
    }
    // Nothing on this page, but the scribble may still have rubbed out what it crossed under the
    // seam — a non-null area here is also what stops the caller filing the scribble itself as ink.
    return seamArea
}


/**
 * Rubs out the next page's ink where it shows under the seam, and only there.
 *
 * Under continuous scrolling the space below this page's end is not empty space: it is the top of
 * the next page, drawn there by [com.ethran.notable.editor.drawing.drawBeyondPageEnd]. Writing
 * already knows that — a stroke starting below the seam is filed onto the next page in its own
 * coordinates (handleDraw) — but erasing did not, and searched only [PageView.strokes]. Ink the
 * writer had just put there could be seen and could not be taken back: the eraser passed over it
 * and nothing happened, which reads as "the second page cannot be erased".
 *
 * [erasePath] is the eraser's swath in *this* page's coordinates; it is shifted up by the page's
 * end to ask the same question in the next page's own coordinates — the same offset the stroke
 * took on the way down. [filterCandidates] is the extra test the caller applies to what the path
 * catches (scribble-erase wants its intersection ratio), applied to next-page coordinates too.
 *
 * Deliberately not added to the undo history, for the reason handleDrawOntoNextPage gives: the
 * history belongs to the page being edited, and an entry pointing into another page would restore
 * rows into whatever state that page is in when it is finally undone.
 *
 * Returns the erased region in SCREEN coordinates, like its callers, or null if nothing was there.
 */
private fun eraseUnderSeam(
    page: PageView,
    erasePath: Path,
    filterCandidates: (List<Stroke>) -> List<Stroke> = { it },
): Rect? {
    if (!page.continuousScrollEnabled) return null
    val nextPageId = page.nextPageId ?: return null
    val pageEnd = page.height.toFloat()

    val trackBounds = RectF()
    erasePath.computeBounds(trackBounds, true)
    // Nothing of the swath reached past the seam, so nothing of the next page was touched.
    if (trackBounds.bottom <= pageEnd) return null

    val shifted = Path(erasePath).apply { offset(0f, -pageEnd) }
    val candidates =
        selectStrokesFromPath(page.pageDataManager.getStrokes(nextPageId), shifted)
    val deletedStrokes = filterCandidates(candidates)
    if (deletedStrokes.isEmpty()) return null

    page.pageDataManager.removeStrokesFromPage(nextPageId, deletedStrokes.map { it.id })

    // Back into this page's coordinates — where those strokes were on this screen — so the
    // repaint covers the hole the erase left under the seam.
    val effectedArea = page.toScreenCoordinates(
        strokeBounds(deletedStrokes).apply { offset(0, pageEnd.toInt()) }
    )
    page.drawAreaScreenCoordinates(screenArea = effectedArea)
    return effectedArea
}

// points is in page coordinates, returns effected area.
fun handleErase(
    page: PageView, history: History, points: List<SimplePointF>, eraser: Eraser
): Rect? {
    val paint = Paint().apply {
        this.strokeWidth = ERASER_SWATH_WIDTH
        this.style = Paint.Style.STROKE
        this.strokeCap = Paint.Cap.ROUND
        this.strokeJoin = Paint.Join.ROUND
        this.isAntiAlias = true
    }
    val path = pointsToPath(points)
    var outPath = Path()

    if (eraser == Eraser.SELECT) {
        path.close()
        outPath = path
    }


    if (eraser == Eraser.PEN) {
        paint.getFillPath(path, outPath)
    }

    val deletedStrokes = selectStrokesFromPath(page.strokes, outPath)

    val deletedStrokeIds = deletedStrokes.map { it.id }

    // The same swath, asked of the next page where it shows under the seam.
    val seamArea = eraseUnderSeam(page, outPath)

    if (deletedStrokes.isEmpty()) return seamArea
    page.removeStrokes(deletedStrokeIds)

    history.addOperationsToHistory(listOf(Operation.AddStroke(deletedStrokes)))

    val effectedArea = page.toScreenCoordinates(strokeBounds(deletedStrokes))
    page.drawAreaScreenCoordinates(screenArea = effectedArea)
    // Both regions were repainted above; the union is what the caller has to push to the panel.
    if (seamArea != null) effectedArea.union(seamArea)
    return effectedArea
}


// points is in page coordinates, returns effected area.
fun cleanAllStrokes(
    page: PageView, history: History
): Rect? {
    val deletedStrokes = page.strokes
    val deletedStrokeIds = deletedStrokes.map { it.id }
    if (deletedStrokes.isEmpty()) return null

    page.removeStrokes(deletedStrokeIds)
    history.addOperationsToHistory(listOf(Operation.AddStroke(deletedStrokes)))

    val effectedArea = page.toScreenCoordinates(strokeBounds(deletedStrokes))
    page.drawAreaScreenCoordinates(screenArea = effectedArea)
    return effectedArea
}