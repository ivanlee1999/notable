package com.ethran.notable.editor

import androidx.compose.ui.geometry.Offset
import com.ethran.notable.gestures.MAX_ZOOM
import com.ethran.notable.gestures.MIN_ZOOM
import kotlin.math.ceil

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
 * The vertical direction is bounded by the same rule, against the page's content extent rather
 * than the sheet. It used not to be: the canvas scrolled past the bottom of the sheet onto blank
 * space, and writing there made the *page* taller. That is where "subpages" came from — screenfuls
 * of notes that were not pages, invisible to the overview, to bookmarks and to reordering, because
 * as far as the file was concerned they were all one page. A page ends at its sheet now, and the
 * way to keep writing is the next page.
 *
 * Bounding against the *content* extent rather than the sheet is what keeps that safe: a page
 * written before this, holding ink below its sheet, still scrolls far enough to reach all of it
 * until the split moves that ink onto pages of its own.
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
     * How far the view may pan along one axis: enough to bring [pageExtent]'s far edge to the far
     * edge of the view, and never negative — a page smaller than the view does not pan at all.
     */
    fun maxScroll(pageExtent: Float, viewExtent: Int, zoom: Float): Float {
        val visible = viewExtent / zoom.coerceAtLeast(SMALLEST_USABLE_ZOOM)
        return (pageExtent - visible).coerceAtLeast(0f)
    }

    fun maxHorizontalScroll(pageWidth: Float, viewWidth: Int, zoom: Float): Float =
        maxScroll(pageWidth, viewWidth, zoom)

    /** [scroll] pulled inside the page: never before an edge, never past the opposite one. */
    fun boundScroll(
        scroll: Offset,
        pageWidth: Float,
        viewWidth: Int,
        zoom: Float,
        pageHeight: Float,
        viewHeight: Int,
    ): Offset = Offset(
        scroll.x.coerceIn(0f, maxScroll(pageWidth, viewWidth, zoom)),
        scroll.y.coerceIn(0f, maxScroll(pageHeight, viewHeight, zoom))
    )

    /** Kept past the last thing on the page, so its far edge is not flush with the scroll limit. */
    const val CONTENT_SLACK = 50

    /**
     * How far the canvas scrolls along one axis: the sheet, or as far as the page's content runs
     * past it.
     *
     * [contentEdges] are the far edges of everything the page holds on that axis — the bottom of
     * every stroke and image for the vertical extent, their right edges for the horizontal one.
     * *Everything*, not only the ink: sizing this from strokes alone is what made an image placed
     * below the sheet or past its right edge unreachable. It was still stored and still drawn, but
     * there was no scroll position that brought it into view, so it was gone as far as the user
     * was concerned.
     *
     * Null and non-finite edges are dropped rather than propagated: a half-loaded image or an
     * empty bounds must not be able to stretch the canvas to infinity.
     */
    fun contentExtent(sheet: Int, contentEdges: List<Float>): Int {
        val furthest = contentEdges.filter { it.isFinite() }.maxOrNull() ?: return sheet
        // Rounded up, not truncated: a content edge at 100.1 truncates to 100, which puts the last
        // fraction of a pixel back outside the canvas — the very thing this function exists to
        // prevent, in miniature.
        return maxOf(sheet, ceil(furthest + CONTENT_SLACK).toInt())
    }
}
