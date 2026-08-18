package com.ethran.notable.editor.utils

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The page-cut edge decision across zooms and scroll offsets.
 *
 * The stroke arrives in page coordinates; the margin is 50 *screen* pixels. The old comparison
 * (`pageX < 50`, `pageX > viewWidth - 50`) was only right at zoom 1 with no horizontal scroll —
 * at fit-to-width zoom on a declared page the right edge lay at a page x the pen could not reach.
 */
class PageCutGeometryTest {

    private val viewWidth = 1200

    /** Page-space x of a point the pen put [screenX] pixels from the view's left edge. */
    private fun pageXAtScreen(screenX: Float, scrollX: Float, zoom: Float): Float =
        screenX / zoom + scrollX

    private fun position(screenX: Float, scrollX: Float, zoom: Float): SelectPointPosition =
        PageCutGeometry.edgePosition(
            pageXAtScreen(screenX, scrollX, zoom), scrollX, zoom, viewWidth
        )

    @Test
    fun `a point at the screen's left edge is LEFT at every zoom and scroll`() {
        for (zoom in listOf(0.5f, 1f, 2f)) {
            for (scrollX in listOf(0f, 37f, 400f)) {
                assertEquals(
                    "zoom=$zoom scroll=$scrollX",
                    SelectPointPosition.LEFT,
                    position(screenX = 10f, scrollX = scrollX, zoom = zoom),
                )
            }
        }
    }

    @Test
    fun `a point at the screen's right edge is RIGHT at every zoom and scroll`() {
        for (zoom in listOf(0.5f, 1f, 2f)) {
            for (scrollX in listOf(0f, 37f, 400f)) {
                assertEquals(
                    "zoom=$zoom scroll=$scrollX",
                    SelectPointPosition.RIGHT,
                    position(screenX = viewWidth - 10f, scrollX = scrollX, zoom = zoom),
                )
            }
        }
    }

    @Test
    fun `a point in the middle of the screen is CENTER at every zoom and scroll`() {
        for (zoom in listOf(0.5f, 1f, 2f)) {
            for (scrollX in listOf(0f, 37f, 400f)) {
                assertEquals(
                    "zoom=$zoom scroll=$scrollX",
                    SelectPointPosition.CENTER,
                    position(screenX = viewWidth / 2f, scrollX = scrollX, zoom = zoom),
                )
            }
        }
    }

    @Test
    fun `a point just past the margin is CENTER, not an edge`() {
        for (zoom in listOf(0.5f, 1f, 2f)) {
            assertEquals(
                SelectPointPosition.CENTER,
                position(screenX = PageCutGeometry.EDGE_MARGIN_PX + 1f, scrollX = 120f, zoom = zoom),
            )
            assertEquals(
                SelectPointPosition.CENTER,
                position(
                    screenX = viewWidth - PageCutGeometry.EDGE_MARGIN_PX - 1f,
                    scrollX = 120f,
                    zoom = zoom,
                ),
            )
        }
    }

    /**
     * The regression itself: at fit-to-width zoom 2 on a declared sheet 600 page-units wide, the
     * old page-space test demanded `pageX > 1150` for the right edge — past the end of the paper,
     * so a cut ending at the screen's right edge was read as CENTER and no cut ever happened.
     */
    @Test
    fun `at fit zoom on a declared page the right edge is reachable again`() {
        val zoom = 2f // 600-unit sheet filling a 1200px view
        val pageXAtRightEdge = pageXAtScreen(viewWidth - 10f, scrollX = 0f, zoom = zoom) // 595
        assertEquals(
            SelectPointPosition.RIGHT,
            PageCutGeometry.edgePosition(pageXAtRightEdge, 0f, zoom, viewWidth),
        )
    }
}
