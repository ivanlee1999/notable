package com.ethran.notable.editor.ui

import kotlin.math.max

/** Where the scroll thumb sits and how big it is, as fractions of the track. */
data class ScrollThumb(val offsetFraction: Float, val sizeFraction: Float)

/**
 * The scroll-indicator arithmetic, pure and in ONE coordinate space so it is testable on the JVM.
 *
 * Everything here is page units. The composable holds the viewport in screen pixels, and the two
 * only agree at zoom 1 — the old code compared them directly (`max(page.height, scroll.y +
 * viewportHeightPx)`), so the thumb's size and position were wrong at any other zoom: zoomed in it
 * claimed more of the page was visible than the screen actually showed, zoomed out less.
 */
object ScrollIndicatorGeometry {

    /**
     * The thumb for one axis, or null when the whole extent fits in the view and no indicator
     * should be shown.
     *
     * @param contentExtent how far the page reaches on this axis, in page units.
     * @param scroll the view's top/left edge on this axis, in page units.
     * @param viewportPx the view's extent on this axis, in screen pixels.
     * @param zoom what relates the two: `viewportPx / zoom` page units are on screen.
     */
    fun thumb(contentExtent: Float, scroll: Float, viewportPx: Int, zoom: Float): ScrollThumb? {
        if (viewportPx <= 0 || zoom <= 0f) return null
        val visible = viewportPx / zoom
        // The scroll-past-the-extent case keeps the indicator alive while panned beyond the
        // content (where out-of-bounds legacy ink lives), exactly as before — just in one unit.
        val virtualExtent = max(contentExtent, scroll + visible)
        if (virtualExtent <= visible) return null
        return ScrollThumb(
            offsetFraction = scroll / virtualExtent,
            sizeFraction = visible / virtualExtent,
        )
    }
}
