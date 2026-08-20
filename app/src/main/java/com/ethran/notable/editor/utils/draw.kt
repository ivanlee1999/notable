package com.ethran.notable.editor.utils

import androidx.core.graphics.toRect
import com.ethran.notable.data.db.MAX_PRESSURE_NORMALIZED
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.db.StrokePoint
import com.ethran.notable.editor.PageView
import com.ethran.notable.ui.SnackConf
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


private val log = ShipBook.getLogger("draw")

// touchpoints are in page coordinates
fun handleDraw(
    page: PageView,
    historyBucket: MutableList<String>,
    strokeSize: Float,
    color: Int,
    pen: Pen,
    touchPoints: List<StrokePoint>
) {
    try {
        if (touchPoints.isEmpty()) return
        val startY = touchPoints.first().y

        // A stroke that *starts* past this page's end is not on this page. Under continuous
        // scrolling what lies there is the next page, drawn under the seam — so the ink is the
        // next page's, shifted into its own coordinates and filed there directly. The whole
        // stroke travels: this is the same rule PageSplit applies (a stroke belongs to the sheet
        // its top edge falls in, and ink is never cut), so writing across the seam from above
        // stays on this page, while writing below it lands where the eye says it landed.
        if (startY >= page.height && page.continuousScrollEnabled) {
            val nextPageId = page.nextPageId
            if (nextPageId != null) {
                handleDrawOntoNextPage(page, nextPageId, strokeSize, color, pen, touchPoints)
                return
            }
            // The last page. Writing past the end means "keep writing", and the way to keep
            // writing is the next page — so it is created here, exactly the way turning past
            // the end creates it, and the ink files onto it. Asynchronous because the creation
            // is a database transaction; the stroke appears with the redraw that follows.
            if (page.hasHardBounds) {
                page.coroutineScope.launch(Dispatchers.IO) {
                    val created = page.pageDataManager.ensureNextPage(page.currentPageId)
                    withContext(Dispatchers.Main.immediate) {
                        if (created != null) {
                            handleDrawOntoNextPage(
                                page, created, strokeSize, color, pen, touchPoints
                            )
                        } else {
                            // The open page moved on under us, so there is nothing to extend.
                            page.snackManager.showOrUpdateSnack(
                                SnackConf(text = "The page ends here", duration = 2000)
                            )
                        }
                    }
                }
                return
            }
        }

        // Past the end with Pagination selected: the gray dead space of a bounded page. Nothing
        // there can be scrolled back to (the page never grows past its sheet), so ink accepted
        // there would be stored and unreachable. Refusing loudly beats losing it.
        if (startY >= page.height && page.hasHardBounds) {
            page.snackManager.showOrUpdateSnack(
                SnackConf(text = "The page ends here", duration = 2000)
            )
            return
        }

        // Beside the sheet: the same dead space, on the axis the whole-page fit opens up. A sheet
        // centred in a wider view has a gray margin either side of it, and ink aimed there would
        // be stored at an x no scroll can reach — the very complaint ("the margin is uneditable")
        // that the gray exists to answer, which is answered by refusing rather than by pretending.
        val startX = touchPoints.first().x
        if (page.hasHardBounds && (startX < 0f || startX >= page.sheet.width)) {
            page.snackManager.showOrUpdateSnack(
                SnackConf(text = "The page ends here", duration = 2000)
            )
            return
        }

        val stroke = strokeFromPoints(
            page.currentPageId, strokeSize, color, pen, touchPoints
        )
        page.addStrokes(listOf(stroke))
        // this is causing lagging and crushing, neo pens are not good
        page.drawAreaPageCoordinates(strokeBounds(stroke).toRect())
        historyBucket.add(stroke.id)
    } catch (e: Exception) {
        log.e("Handle Draw: An error occurred while handling the drawing: ${e.message}")
    }
}

/**
 * Files a stroke drawn below the seam onto the next page, in that page's own coordinates.
 *
 * Deliberately not added to the undo history: the history belongs to the page being edited, and
 * an entry pointing into another page would delete rows out from under whatever state that page
 * is in when it is finally undone.
 */
private fun handleDrawOntoNextPage(
    page: PageView,
    nextPageId: String,
    strokeSize: Float,
    color: Int,
    pen: Pen,
    touchPoints: List<StrokePoint>,
) {
    val pageEnd = page.height.toFloat()
    val shifted = touchPoints.map { it.copy(y = it.y - pageEnd) }
    val stroke = strokeFromPoints(nextPageId, strokeSize, color, pen, shifted)
    page.pageDataManager.addStrokesToPage(nextPageId, listOf(stroke))
    // Redraw where the stroke sits *on this screen*: its own bounds, shifted back below the seam.
    page.drawAreaPageCoordinates(
        strokeBounds(stroke).toRect().apply { offset(0, pageEnd.toInt()) }
    )
}

private fun strokeFromPoints(
    pageId: String,
    strokeSize: Float,
    color: Int,
    pen: Pen,
    touchPoints: List<StrokePoint>,
): Stroke {
    val boundingBox = calculateBoundingBox(touchPoints) { Pair(it.x, it.y) }

    //move rectangle
    boundingBox.inset(-strokeSize, -strokeSize)

    return Stroke(
        size = strokeSize,
        pen = pen,
        pageId = pageId,
        top = boundingBox.top,
        bottom = boundingBox.bottom,
        left = boundingBox.left,
        right = boundingBox.right,
        points = touchPoints,
        color = color,
        // Pressure is normalized to [0,1] at capture (TouchPoint.toStrokePoint).
        maxPressure = MAX_PRESSURE_NORMALIZED
    )
}
