package com.ethran.notable.editor.utils

/**
 * The page-cut edge decision, on its own and free of android imports so the rule is testable on
 * the JVM.
 *
 * A Select-mode stroke is converted to PAGE coordinates before `handleSelect` sees it, but the
 * question "did this stroke start at the edge of the screen?" is about the VIEW: the margin is 50
 * screen pixels whatever the zoom. The old test compared the page-space x against `50` and
 * `viewWidth - 50` directly — right only at zoom 1 with no horizontal scroll. At fit-to-width zoom
 * (the resting zoom of any declared page, generally != 1) the right edge sat at a page x the pen
 * could not even reach, so the page cut triggered at the wrong places or not at all.
 */
object PageCutGeometry {

    /** Margin, in screen pixels, within which a stroke endpoint counts as touching a view edge. */
    const val EDGE_MARGIN_PX = 50f

    /**
     * Which vertical screen edge a page-space [pageX] sits within [marginPx] of, if either.
     *
     * One coordinate space: the page-space point is mapped back to screen space
     * (`(pageX - scrollX) * zoom`) and compared against the view's own pixels.
     */
    fun edgePosition(
        pageX: Float,
        scrollX: Float,
        zoom: Float,
        viewWidthPx: Int,
        marginPx: Float = EDGE_MARGIN_PX,
    ): SelectPointPosition {
        val screenX = (pageX - scrollX) * zoom
        return when {
            screenX < marginPx -> SelectPointPosition.LEFT
            screenX > viewWidthPx - marginPx -> SelectPointPosition.RIGHT
            else -> SelectPointPosition.CENTER
        }
    }
}
