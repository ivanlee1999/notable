package com.ethran.notable.editor.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The scroll thumb at zooms other than 1.
 *
 * The composable used to mix units — `max(page.height, scroll.y + viewportHeightPx)` puts page
 * units and screen pixels in one expression — so the thumb was only right at zoom 1. The visible
 * extent in page units is `viewportPx / zoom`, and everything follows from that.
 */
class ScrollIndicatorGeometryTest {

    private val pageExtent = 2000f
    private val viewportPx = 500

    @Test
    fun `at zoom 1 the thumb covers the visible fraction of the page`() {
        val thumb = ScrollIndicatorGeometry.thumb(pageExtent, scroll = 0f, viewportPx, zoom = 1f)!!
        assertEquals(0.25f, thumb.sizeFraction, 0.0001f)
        assertEquals(0f, thumb.offsetFraction, 0.0001f)
    }

    @Test
    fun `zoomed in, the same viewport shows less of the page and the thumb shrinks`() {
        // 500px at zoom 2 shows 250 page units of a 2000-unit page: an eighth, not a quarter.
        val thumb = ScrollIndicatorGeometry.thumb(pageExtent, scroll = 500f, viewportPx, zoom = 2f)!!
        assertEquals(0.125f, thumb.sizeFraction, 0.0001f)
        assertEquals(0.25f, thumb.offsetFraction, 0.0001f)
    }

    @Test
    fun `zoomed out, the same viewport shows more of the page and the thumb grows`() {
        // 500px at zoom 0.5 shows 1000 page units: half the page.
        val thumb =
            ScrollIndicatorGeometry.thumb(pageExtent, scroll = 250f, viewportPx, zoom = 0.5f)!!
        assertEquals(0.5f, thumb.sizeFraction, 0.0001f)
        assertEquals(0.125f, thumb.offsetFraction, 0.0001f)
    }

    @Test
    fun `a page that fits in the view shows no indicator`() {
        // 800 page units all visible at zoom 0.5 (1000 visible): nothing to indicate. At zoom 2
        // (250 visible) the same page needs one.
        assertNull(ScrollIndicatorGeometry.thumb(800f, scroll = 0f, viewportPx, zoom = 0.5f))
        assertEquals(
            0.3125f,
            ScrollIndicatorGeometry.thumb(800f, scroll = 0f, viewportPx, zoom = 2f)!!.sizeFraction,
            0.0001f,
        )
    }

    @Test
    fun `panned past the extent, the track grows so the thumb stays on it`() {
        // scroll 1900 + 250 visible = 2150 > 2000: the virtual extent follows the view, exactly
        // as the old scroll+viewport term intended — now in one unit.
        val thumb =
            ScrollIndicatorGeometry.thumb(pageExtent, scroll = 1900f, viewportPx, zoom = 2f)!!
        assertEquals(250f / 2150f, thumb.sizeFraction, 0.0001f)
        assertEquals(1900f / 2150f, thumb.offsetFraction, 0.0001f)
    }

    @Test
    fun `degenerate viewport or zoom shows no indicator instead of dividing by zero`() {
        assertNull(ScrollIndicatorGeometry.thumb(pageExtent, 0f, viewportPx = 0, zoom = 1f))
        assertNull(ScrollIndicatorGeometry.thumb(pageExtent, 0f, viewportPx, zoom = 0f))
    }
}
