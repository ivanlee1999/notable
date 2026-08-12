package com.ethran.notable.editor

import androidx.compose.ui.geometry.Offset
import com.ethran.notable.gestures.MAX_ZOOM
import com.ethran.notable.gestures.MIN_ZOOM

/**
 * Where the view is allowed to sit over a page: how far out it may zoom, and how far it may pan.
 *
 * A page that declares a size is *bounded*: the sheet is the paper, and there is nothing to the
 * right of the paper. Two rules together make that true at every zoom, and neither works alone:
 *
 * - the view never zooms out past [fitToWidthZoom], or blank non-page space would appear beside
 *   the sheet;
 * - the view never pans past the sheet's right edge ([maxHorizontalScroll]).
 *
 * Because they hold together, the visible page x-range is always inside `0..sheetWidth` — which is
 * also what keeps the pen on the page, since the pen can only mark what is under it.
 *
 * A page that declares nothing is not bounded. Its "sheet" is only whatever screen it happened to
 * be written on, so enforcing it on a wider device would hide ink rather than keep ink on the page.
 *
 * The vertical direction is deliberately not bounded here: the canvas scrolls past the bottom of
 * the sheet onto the next subpage, which is how the page grows as you write.
 */
object PageViewportBounds {

    /** Guards the division below; a zoom at or below this is not a view anyone can see through. */
    private const val SMALLEST_USABLE_ZOOM = 0.01f

    /**
     * The zoom at which [sheetWidth] page units exactly fill a [viewWidth]-pixel view.
     *
     * This is what makes a declared page size mean the same thing on every device: the page is the
     * page, and the device scales to it.
     */
    fun fitToWidthZoom(sheetWidth: Int, viewWidth: Int): Float =
        if (sheetWidth <= 0 || viewWidth <= 0) 1f else viewWidth.toFloat() / sheetWidth

    /** The lowest zoom allowed: the fit on a bounded page, the global floor on any other. */
    fun minZoom(fitZoom: Float, bounded: Boolean): Float =
        if (bounded) fitZoom.coerceIn(MIN_ZOOM, MAX_ZOOM) else MIN_ZOOM

    /**
     * How far right the view may pan: enough to bring [pageWidth]'s far edge to the right edge of
     * the view, and never negative — a page narrower than the view does not pan at all.
     */
    fun maxHorizontalScroll(pageWidth: Float, viewWidth: Int, zoom: Float): Float {
        val visibleWidth = viewWidth / zoom.coerceAtLeast(SMALLEST_USABLE_ZOOM)
        return (pageWidth - visibleWidth).coerceAtLeast(0f)
    }

    /** [scroll] pulled inside the page: never before its left edge, never past its right one. */
    fun boundScroll(scroll: Offset, pageWidth: Float, viewWidth: Int, zoom: Float): Offset = Offset(
        scroll.x.coerceIn(0f, maxHorizontalScroll(pageWidth, viewWidth, zoom)),
        scroll.y.coerceAtLeast(0f)
    )
}
