package com.ethran.notable.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
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

    /**
     * The zoom at which the *whole* sheet is on screen, both dimensions.
     *
     * What "the page fits" means depends on which way you turn it, and this is the answer
     * reMarkable, the Kindle Scribe and GoodNotes all land on for sideways turning: one whole page
     * at a time, because a page you cannot see all of is not one you can turn past. Scrolling down
     * keeps [fitToWidthZoom] instead and lets the page run off the bottom — the direction you are
     * about to travel in.
     */
    fun fitWholePageZoom(sheetWidth: Int, sheetHeight: Int, viewWidth: Int, viewHeight: Int): Float {
        val widthFit = fitToWidthZoom(sheetWidth, viewWidth)
        if (sheetHeight <= 0 || viewHeight <= 0) return widthFit
        // The smaller of the two, so neither edge is cut off.
        return minOf(widthFit, viewHeight.toFloat() / sheetHeight)
    }

    /** The lowest zoom allowed: the fit on a bounded page, the global floor on any other. */
    fun minZoom(fitZoom: Float, bounded: Boolean): Float =
        if (bounded) fitZoom.coerceIn(MIN_ZOOM, MAX_ZOOM) else MIN_ZOOM

    /**
     * How far *before* the page's left edge the view may sit: half the slack, so a sheet
     * narrower than the view is centred in it rather than shoved against the left.
     *
     * Negative by construction — the scroll is where the view's left edge sits in page units, so
     * putting page x=0 further right means scrolling to a negative x. Everything else follows
     * from that one number without knowing about it: strokes draw at `-scroll`, and
     * [com.ethran.notable.editor.InkViewport] maps a pen at screen x to `x / zoom + scroll.x`,
     * so ink lands under the nib on a centred sheet exactly as it does on a full-width one.
     *
     * Zero whenever the page is at least as wide as the view, which is every page at the width
     * fit — the whole-page fit is what makes this reachable at all.
     */
    fun minHorizontalScroll(pageWidth: Float, viewWidth: Int, zoom: Float): Float {
        val visible = viewWidth / zoom.coerceAtLeast(SMALLEST_USABLE_ZOOM)
        val slack = visible - pageWidth
        // Exactly zero, not negative zero: this is compared and stored as a scroll offset, and
        // `Offset(-0f, 0f) != Offset(0f, 0f)`.
        return if (slack <= 0f) 0f else -slack / 2f
    }

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

    /**
     * How far down the view may scroll when the next page is drawn below this one
     * ([overshootIntoNextPage]): all the way to the page's own end, so the whole view can fill
     * with the next page before the switch commits. Without the overshoot the ordinary
     * [maxScroll] holds — the far edge of the page stops at the far edge of the view.
     *
     * The overshoot maximum is the page extent itself: at `scroll.y == pageExtent` the last of
     * this page has just left the top of the view, which is the moment the next page can take
     * over with the exact same pixels on screen. Never less than the ordinary maximum, so a page
     * shorter than the view still cannot be dragged upward off the screen twice over.
     */
    fun maxVerticalScroll(
        pageExtent: Float,
        viewExtent: Int,
        zoom: Float,
        overshootIntoNextPage: Boolean,
    ): Float =
        if (overshootIntoNextPage) maxOf(pageExtent, 0f)
        else maxScroll(pageExtent, viewExtent, zoom)

    /**
     * One axis of [boundScroll]: where the view's left edge may sit.
     *
     * The two cases are exclusive, which is what keeps this simple. A sheet **wider** than the
     * view pans from its left edge to its right one and is never centred. A sheet **narrower**
     * than the view has nowhere to pan to, so its one legal position is the centred one — the
     * range collapses to a point. Allowing the ordinary `0..0` there would have let a centred
     * page be dragged out of its own margin and left flush against the left edge.
     */
    fun boundHorizontalScroll(x: Float, pageWidth: Float, viewWidth: Int, zoom: Float): Float {
        val centred = minHorizontalScroll(pageWidth, viewWidth, zoom)
        val panTo = maxScroll(pageWidth, viewWidth, zoom)
        return if (panTo > 0f) x.coerceIn(0f, panTo) else centred
    }

    /** [scroll] pulled inside the page: never before an edge, never past the opposite one. */
    fun boundScroll(
        scroll: Offset,
        pageWidth: Float,
        viewWidth: Int,
        zoom: Float,
        pageHeight: Float,
        viewHeight: Int,
        overshootIntoNextPage: Boolean = false,
    ): Offset = Offset(
        boundHorizontalScroll(scroll.x, pageWidth, viewWidth, zoom),
        scroll.y.coerceIn(
            0f, maxVerticalScroll(pageHeight, viewHeight, zoom, overshootIntoNextPage)
        )
    )

    /**
     * One scroll of the view: how far the picture moves, how far the scroll moves with it, and
     * what is left over.
     *
     * The panel can only be shifted by whole pixels — the shift is a bitmap blit — so the scroll
     * has to move by whole pixels too. It used not to: the scroll was advanced by the full
     * requested delta and the blit was then skipped when that delta came to less than a pixel, so
     * the model crept away from the picture on screen and stayed there. Every pen stroke after
     * that is filed through the model, which is to say somewhere the writer was not looking.
     *
     * [remainderPx] is the sub-pixel tail, to be added to the next request rather than dropped:
     * that is what keeps a slow drag moving at all, since each of its samples on its own may be
     * worth less than a pixel. It comes back as zero once the bound refuses the travel, so it
     * cannot pile up against the edge of the page.
     *
     * [boundedDeltaInPage] is the page-unit travel that [boundScroll] has already allowed.
     * Movement truncates toward zero, never away from it, so the scroll cannot be rounded past
     * the bound that was just applied to it.
     */
    data class ScrollStep(
        val movementPx: IntOffset,
        val scrollDelta: Offset,
        val remainderPx: Offset,
    ) {
        val isStandingStill: Boolean get() = movementPx == IntOffset.Zero
    }

    fun scrollStep(boundedDeltaInPage: Offset, zoom: Float): ScrollStep {
        val scale = zoom.coerceAtLeast(SMALLEST_USABLE_ZOOM)
        val wantedPx = Offset(boundedDeltaInPage.x * scale, boundedDeltaInPage.y * scale)
        val movement = IntOffset(wantedPx.x.toInt(), wantedPx.y.toInt())
        return ScrollStep(
            movementPx = movement,
            // Exactly what the picture moves, converted back — not what was asked for.
            scrollDelta = Offset(movement.x / scale, movement.y / scale),
            remainderPx = Offset(wantedPx.x - movement.x, wantedPx.y - movement.y),
        )
    }

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
