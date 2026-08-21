package com.ethran.notable.editor

import androidx.compose.ui.geometry.Offset
import com.ethran.notable.data.model.PageSizePreset
import com.ethran.notable.gestures.MIN_ZOOM
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The page-edge rule: on a page that declares a size, the sheet is the paper and nothing outside it
 * is ever on screen. The two halves (zoom floor, pan limit) are only correct together, so the last
 * test states the property they exist for rather than either half of the arithmetic.
 */
class PageViewportBoundsTest {

    // A device wider than an A4 sheet is where the bug showed: the page fell short of the screen,
    // leaving a strip of non-page beside it that could be looked at and written on.
    private val a4 = PageSizePreset.A4.size
    private val wideView = 1650
    private val narrowView = 1404

    @Test
    fun `fit to width scales the sheet across the view`() {
        assertEquals(wideView.toFloat() / a4.width, fit(wideView), 1e-6f)
        assertEquals(narrowView.toFloat() / a4.width, fit(narrowView), 1e-6f)
    }

    @Test
    fun `fit to width falls back to 1 when either dimension is unknown`() {
        assertEquals(1f, PageViewportBounds.fitToWidthZoom(sheetWidth = 0, viewWidth = 100), 1e-6f)
        assertEquals(1f, PageViewportBounds.fitToWidthZoom(sheetWidth = 100, viewWidth = 0), 1e-6f)
    }

    @Test
    fun `a page cannot zoom out past the fit`() {
        assertEquals(fit(wideView), PageViewportBounds.minZoom(fit(wideView)), 1e-6f)
    }

    /** Every page is bounded now, so the floor never drops below the global minimum either. */
    @Test
    fun `the zoom floor stays inside the global zoom range`() {
        assertEquals(MIN_ZOOM, PageViewportBounds.minZoom(MIN_ZOOM / 10f), 1e-6f)
    }

    @Test
    fun `panning stops with the sheet's right edge at the view's`() {
        // Zoomed to 2x, half the sheet's width is on screen at a time.
        val zoom = 2f * fit(wideView)
        val max = PageViewportBounds.maxHorizontalScroll(a4.width.toFloat(), wideView, zoom)
        assertEquals(a4.width / 2f, max, 1e-3f)
        assertEquals(a4.width.toFloat(), max + wideView / zoom, 1e-3f)
    }

    @Test
    fun `a page narrower than the view does not pan`() {
        val max = PageViewportBounds.maxHorizontalScroll(a4.width.toFloat(), wideView, zoom = 0.5f)
        assertEquals(0f, max, 1e-6f)
    }

    /**
     * Bounds a scroll against a page tall enough that only the horizontal rule can bite — the
     * tests below this are about the sides.
     */
    private fun boundHorizontally(
        scroll: Offset, width: Float, view: Int, zoom: Float
    ): Offset = PageViewportBounds.boundScroll(
        scroll, width, view, zoom, pageHeight = 1e6f, viewHeight = 100
    )

    @Test
    fun `scroll is pulled back inside the page from either side`() {
        val zoom = 2f * fit(wideView)
        val width = a4.width.toFloat()

        val pastRight = boundHorizontally(Offset(9999f, 40f), width, wideView, zoom)
        assertEquals(a4.width / 2f, pastRight.x, 1e-3f)
        assertEquals(40f, pastRight.y, 1e-6f)

        val pastLeft = boundHorizontally(Offset(-50f, -50f), width, wideView, zoom)
        assertEquals(Offset.Zero, pastLeft)
    }

    /**
     * The canvas used to scroll downward for ever, and writing there made the page taller — that is
     * where "subpages" came from. A page ends at its content now, the same as at its sides.
     */
    @Test
    fun `scrolling down stops at the end of the page`() {
        val zoom = fit(wideView)
        val viewHeight = 800
        val far = Offset(0f, a4.height * 10f)

        val bounded = PageViewportBounds.boundScroll(
            far, a4.width.toFloat(), wideView, zoom,
            pageHeight = a4.height.toFloat(), viewHeight = viewHeight
        )

        assertEquals(
            PageViewportBounds.maxScroll(a4.height.toFloat(), viewHeight, zoom),
            bounded.y, 1e-3f
        )
        assertTrue("must not scroll past the page", bounded.y < a4.height)
    }

    /**
     * A page written before pages were sheets holds ink below its own sheet. Bounding against the
     * content extent rather than the sheet is what keeps that ink reachable until the split moves
     * it onto pages of its own — bounding at the sheet would strand it.
     */
    @Test
    fun `a page whose ink runs past its sheet still scrolls to reach it`() {
        val zoom = fit(wideView)
        val viewHeight = 800
        val contentExtent = a4.height * 3f

        val bounded = PageViewportBounds.boundScroll(
            Offset(0f, contentExtent), a4.width.toFloat(), wideView, zoom,
            pageHeight = contentExtent, viewHeight = viewHeight
        )

        assertTrue("ink below the sheet must stay reachable", bounded.y > a4.height)
    }

    @Test
    fun `no zoom and scroll a bounded page allows puts non-page space on screen`() {
        val width = a4.width.toFloat()
        for (view in listOf(narrowView, wideView, 2200)) {
            val floor = PageViewportBounds.minZoom(fit(view))
            for (zoom in listOf(floor, floor * 1.3f, 1f.coerceAtLeast(floor), floor * 4f)) {
                val scroll = boundHorizontally(Offset(1e6f, 0f), width, view, zoom).x
                val rightEdgeOfView = scroll + view / zoom
                assertTrue(
                    "view=$view zoom=$zoom reaches $rightEdgeOfView, past the sheet's $width",
                    rightEdgeOfView <= width + 1e-2f
                )
            }
        }
    }

    // The other half of "nothing on the page is out of reach": how far the canvas scrolls.

    @Test
    fun `a page with nothing on it scrolls exactly one sheet`() {
        assertEquals(a4.height, PageViewportBounds.contentExtent(a4.height, emptyList()))
    }

    @Test
    fun `content inside the sheet does not stretch the canvas`() {
        assertEquals(
            a4.height,
            PageViewportBounds.contentExtent(
                a4.height, listOf(10f, a4.height / 2f, a4.height - 0.1f)
            )
        )
    }

    // An image dropped three sheets down has to be scrollable to; sizing this from the strokes
    // alone is what left it stored, drawn, and impossible to reach.
    @Test
    fun `the canvas reaches the furthest thing on the page`() {
        val imageBottom = a4.height * 3f
        assertEquals(
            (imageBottom + PageViewportBounds.CONTENT_SLACK).toInt(),
            PageViewportBounds.contentExtent(a4.height, listOf(50f, imageBottom, 120f))
        )
    }

    @Test
    fun `a non-finite edge cannot stretch the canvas to infinity`() {
        assertEquals(
            a4.height,
            PageViewportBounds.contentExtent(
                a4.height, listOf(Float.NaN, Float.POSITIVE_INFINITY)
            )
        )
    }

    private fun fit(viewWidth: Int) = PageViewportBounds.fitToWidthZoom(a4.width, viewWidth)

    // MARK: Turning the page at the edge

    /**
     * "Past the end of the page" is a scroll the bound swallows whole. This is the arithmetic
     * `PageView.isAtVerticalEdge` asks, and the whole vertical page turn rests on it: get it wrong
     * in one direction and the notebook cannot be left, in the other and every drag turns a page.
     */
    @Test
    fun `a drag the bound swallows whole is a page turn, one that moves is not`() {
        val sheet = PageSizePreset.A4.size
        val viewHeight = 1000
        val zoom = 1f
        val maxScroll = PageViewportBounds.maxScroll(sheet.height.toFloat(), viewHeight, zoom)

        // At the top, dragging down (positive delta scrolls toward the start) has nowhere to go.
        val atTop = Offset(0f, 0f)
        assertEquals(
            atTop,
            PageViewportBounds.boundScroll(
                atTop + Offset(0f, -200f), sheet.width.toFloat(), 800, zoom,
                sheet.height.toFloat(), viewHeight))

        // At the bottom, dragging further down is swallowed too.
        val atBottom = Offset(0f, maxScroll)
        assertEquals(
            atBottom,
            PageViewportBounds.boundScroll(
                atBottom + Offset(0f, 200f), sheet.width.toFloat(), 800, zoom,
                sheet.height.toFloat(), viewHeight))

        // In the middle it moves, so it is a scroll and not a turn.
        val middle = Offset(0f, maxScroll / 2)
        assertTrue(
            PageViewportBounds.boundScroll(
                middle + Offset(0f, 100f), sheet.width.toFloat(), 800, zoom,
                sheet.height.toFloat(), viewHeight).y > middle.y)
    }

    /** Continuous navigation uses the full width; pagination presents one complete sheet. */
    @Test
    fun `vertical navigation selects the matching automatic fit`() {
        val sheet = PageSizePreset.A4.size
        val viewWidth = 1400
        val viewHeight = 1000

        val widthFit = PageViewportBounds.fitToWidthZoom(sheet.width, viewWidth)
        val wholeFit = PageViewportBounds.fitWholePageZoom(
            sheet.width, sheet.height, viewWidth, viewHeight)

        assertEquals(
            widthFit,
            PageViewportBounds.fitForVerticalNavigation(
                sheet.width, sheet.height, viewWidth, viewHeight, paged = false),
            0.0001f)
        assertEquals(
            wholeFit,
            PageViewportBounds.fitForVerticalNavigation(
                sheet.width, sheet.height, viewWidth, viewHeight, paged = true),
            0.0001f)
        assertTrue("the whole page must fit within the view", sheet.height * wholeFit <= viewHeight + 1)
        assertTrue("the whole-page fit is never larger than the width fit", wholeFit <= widthFit)
        assertTrue("the width fit overflows a view shorter than the sheet", sheet.height * widthFit > viewHeight)
    }

    /**
     * The whole-page fit is what makes a sheet narrower than the view reachable at all, and a
     * sheet parked against the left edge with all its slack on the right reads as a mistake. The
     * scroll goes negative by half the slack instead, which is what centres it.
     */
    @Test
    fun `a sheet narrower than the view is centred in it`() {
        val sheet = PageSizePreset.A4.size
        val viewWidth = 2000
        // The whole-page fit on a view wider than it is tall: height decides, so the sheet ends
        // up narrower than the view.
        val zoom = PageViewportBounds.fitWholePageZoom(
            sheet.width, sheet.height, viewWidth, 1000)
        val visibleWidth = viewWidth / zoom
        val expected = -((visibleWidth - sheet.width) / 2f)

        assertEquals(
            expected,
            PageViewportBounds.minHorizontalScroll(sheet.width.toFloat(), viewWidth, zoom),
            0.001f)
        // Left margin and right margin are the same width — which is what "centred" means.
        val leftMargin = -expected
        val rightMargin = visibleWidth - sheet.width - leftMargin
        assertEquals(leftMargin, rightMargin, 0.001f)
    }

    /** At the width fit — every page under downward turning — there is no slack to centre. */
    @Test
    fun `a sheet that fills the view is not centred`() {
        val sheet = PageSizePreset.A4.size
        val zoom = PageViewportBounds.fitToWidthZoom(sheet.width, 1400)
        assertEquals(
            0f,
            PageViewportBounds.minHorizontalScroll(sheet.width.toFloat(), 1400, zoom),
            0.001f)
    }

    /**
     * The bound has to *allow* the centred position, or the clamp would immediately undo the
     * centring — and it must still refuse to pan past either edge of the sheet.
     */
    @Test
    fun `the centred scroll survives the bound, and nothing beyond it does`() {
        val sheet = PageSizePreset.A4.size
        val viewWidth = 2000
        val viewHeight = 1000
        val zoom = PageViewportBounds.fitWholePageZoom(
            sheet.width, sheet.height, viewWidth, viewHeight)
        val centred = PageViewportBounds.minHorizontalScroll(
            sheet.width.toFloat(), viewWidth, zoom)

        fun boundedX(x: Float) = PageViewportBounds.boundScroll(
            scroll = Offset(x, 0f),
            pageWidth = sheet.width.toFloat(), viewWidth = viewWidth, zoom = zoom,
            pageHeight = sheet.height.toFloat(), viewHeight = viewHeight,
        ).x

        assertEquals("the centred position is allowed", centred, boundedX(centred), 0.001f)
        assertEquals("further left is not", centred, boundedX(centred - 500f), 0.001f)
        // With the whole sheet on screen there is nothing to pan *to*, so the centred position
        // is the *only* position — a page cannot be dragged out of its own margin and left
        // flush against one edge, which an ordinary 0..0 bound would have permitted.
        assertEquals("further right is not", centred, boundedX(centred + 500f), 0.001f)
        assertEquals("nor is the uncentred origin", centred, boundedX(0f), 0.001f)
    }

    /** A view taller than the sheet needs no shrinking: the width fit already shows all of it. */
    @Test
    fun `a view taller than the sheet fits the same either way`() {
        val sheet = PageSizePreset.A4.size
        assertEquals(
            PageViewportBounds.fitToWidthZoom(sheet.width, 700),
            PageViewportBounds.fitWholePageZoom(sheet.width, sheet.height, 700, 4000),
            0.0001f)
    }

    /**
     * The sign convention the vertical page turn rests on, pinned because it is invisible and was
     * wrong once: the scroll consumer negates a gesture delta before the page applies it
     * (`EditorControlTower.onPageScroll(-delta)`), so a drag up — a negative gesture delta — is a
     * positive page delta and asks for what is *below*. Testing the raw gesture delta instead
     * checks the opposite edge, which turns the page at the top and never turns it at the bottom.
     */
    @Test
    fun `a drag up is a positive page delta and is blocked only at the bottom`() {
        val sheet = PageSizePreset.A4.size
        val viewHeight = 1000
        val zoom = 1f
        val maxScroll = PageViewportBounds.maxScroll(sheet.height.toFloat(), viewHeight, zoom)
        val gestureDeltaDraggingUp = -200f
        val pageDelta = -gestureDeltaDraggingUp

        fun blocked(from: Offset) = PageViewportBounds.boundScroll(
            from + Offset(0f, pageDelta), sheet.width.toFloat(), 800, zoom,
            sheet.height.toFloat(), viewHeight) == from

        assertTrue("at the bottom there is nothing below, so this turns the page",
            blocked(Offset(0f, maxScroll)))
        assertTrue("at the top there is plenty below, so this must only scroll",
            !blocked(Offset(0f, 0f)))
    }

    // MARK: Continuous scrolling across the seam

    /**
     * With the next page drawn under the seam, the scroll may run all the way to the page's own
     * end — the moment the last of it leaves the top of the view is the moment the switch to the
     * next page shows the exact same pixels. Without the overshoot the ordinary bound holds.
     */
    @Test
    fun `the overshoot bound runs to the page's end and no further`() {
        val sheet = PageSizePreset.A4.size
        val viewHeight = 1000
        val zoom = 1f
        val height = sheet.height.toFloat()

        val overshot = PageViewportBounds.boundScroll(
            Offset(0f, height * 5f), sheet.width.toFloat(), 800, zoom, height, viewHeight,
            overshootIntoNextPage = true
        )
        assertEquals(height, overshot.y, 1e-3f)

        val ordinary = PageViewportBounds.boundScroll(
            Offset(0f, height * 5f), sheet.width.toFloat(), 800, zoom, height, viewHeight,
            overshootIntoNextPage = false
        )
        assertEquals(
            PageViewportBounds.maxScroll(height, viewHeight, zoom), ordinary.y, 1e-3f
        )
        assertTrue("the overshoot must reach past the ordinary bound", overshot.y > ordinary.y)
    }

    /**
     * A page shorter than the view — the phone-shaped case where the whole sheet fits with room
     * to spare — can still be overshot exactly to its end: the seam travels from mid-screen to
     * the top, and never past it.
     */
    @Test
    fun `a page shorter than the view overshoots to its end, not off the screen`() {
        val sheet = PageSizePreset.A4.size
        val height = sheet.height.toFloat()
        val tallView = (height * 2).toInt()

        assertEquals(
            height,
            PageViewportBounds.maxVerticalScroll(
                height, tallView, zoom = 1f, overshootIntoNextPage = true
            ),
            1e-3f
        )
        // And without the overshoot such a page does not scroll at all.
        assertEquals(
            0f,
            PageViewportBounds.maxVerticalScroll(
                height, tallView, zoom = 1f, overshootIntoNextPage = false
            ),
            1e-3f
        )
    }

    /**
     * The scroll and the picture move together or the pen goes astray: whatever the scroll
     * advances by has to be exactly what the panel was blitted by, and the panel moves in whole
     * pixels.
     */
    @Test
    fun `the scroll advances by exactly what the picture moves`() {
        val step = PageViewportBounds.scrollStep(Offset(0f, 12.7f), zoom = 1f)
        assertEquals(12, step.movementPx.y)
        assertEquals(12f, step.scrollDelta.y, 1e-3f)
        assertEquals(0.7f, step.remainderPx.y, 1e-3f)
    }

    @Test
    fun `travel worth less than a pixel moves nothing at all`() {
        val step = PageViewportBounds.scrollStep(Offset(0f, 0.4f), zoom = 1f)
        assertTrue(step.isStandingStill)
        assertEquals(0f, step.scrollDelta.y, 1e-3f)
        assertEquals(0.4f, step.remainderPx.y, 1e-3f)
    }

    /**
     * Sub-pixel travel is carried, not dropped: a slow drag whose every sample is worth less than
     * a pixel still scrolls, and the scroll it produces is still exactly what the picture moved.
     *
     * Losing the second half of that was the bug — the scroll took the full delta while the blit
     * was skipped as too small, so the model crept away from the picture a fraction of a pixel at
     * a time and every stroke after it was filed through a view nobody could see.
     */
    @Test
    fun `sub-pixel steps accumulate into travel without drifting from the picture`() {
        var remainder = Offset.Zero
        var scrolled = 0f
        var pictureMoved = 0
        repeat(100) {
            val requested = Offset(0f, 0.3f) + remainder
            val step = PageViewportBounds.scrollStep(requested, zoom = 1f)
            remainder = step.remainderPx
            scrolled += step.scrollDelta.y
            pictureMoved += step.movementPx.y
        }
        assertEquals(30, pictureMoved)
        assertEquals(pictureMoved.toFloat(), scrolled, 1e-3f)
    }

    @Test
    fun `zoom converts the picture's whole pixels back into page units`() {
        val step = PageViewportBounds.scrollStep(Offset(0f, 10f), zoom = 2f)
        // Ten page units is twenty pixels at this zoom, and twenty pixels is ten page units back.
        assertEquals(20, step.movementPx.y)
        assertEquals(10f, step.scrollDelta.y, 1e-3f)
    }
}
