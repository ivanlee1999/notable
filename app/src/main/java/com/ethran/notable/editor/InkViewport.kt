package com.ethran.notable.editor

import androidx.compose.ui.geometry.Offset

/**
 * The view a stroke was drawn on: where the page sat under the screen, and at what scale.
 *
 * Ink arrives from the digitizer in screen pixels and is stored in page units, so every stroke,
 * every lasso and every erase track is filed through one of these. *Which* one is the whole point
 * of the type. The pen paints on the panel as it moves — that is what the raw input path is for —
 * so the picture the writer is aiming at is whatever the last repaint left there. The transform
 * that turns the finished stroke into page coordinates therefore has to be the one that painted
 * *that* picture, not whatever the editor has scrolled to by the time the pen lifts.
 *
 * Reading the live scroll at pen-up is what this replaces. Anything that moved the view between
 * pen-down and pen-up — the other hand on the panel, a queued scroll draining, a page load
 * clamping the scroll back inside new bounds, a rotation refitting the page — filed the stroke
 * against a view the writer had never seen, and the ink landed as far from the nib as the view
 * had travelled. On a page whose whole scroll range is one screenful, that is a line written on
 * top of the line above it, and it is *stored* ink: it syncs, and it is still wrong tomorrow.
 *
 * [zoom] is never zero — the view cannot zoom below [PageViewportBounds.minZoom] — so the
 * division needs no guard.
 */
data class InkViewport(val scroll: Offset, val zoom: Float) {

    /** The page point under screen pixel ([x], [y]). */
    fun pagePoint(x: Float, y: Float): Offset = Offset(pageX(x), pageY(y))

    fun pageX(x: Float): Float = x / zoom + scroll.x

    fun pageY(y: Float): Float = y / zoom + scroll.y

    companion object {
        /** Page units and screen pixels one to one, page origin at the top left of the screen. */
        val Identity = InkViewport(Offset.Zero, 1f)
    }
}
