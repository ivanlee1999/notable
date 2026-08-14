package com.ethran.notable.editor.utils

import android.graphics.Rect
import com.ethran.notable.data.datastore.AppSettings

/**
 * A rectangle in view pixels. Deliberately not [android.graphics.Rect]: the arithmetic below
 * decides where the pen may and may not write, which is worth testing on the JVM without a
 * device or a Robolectric shadow in the way.
 */
data class Band(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val isEmpty: Boolean get() = left >= right || top >= bottom

    fun toRect(): Rect = Rect(left, top, right, bottom)
}

/**
 * What the firmware is told about the screen: the one rectangle raw drawing is confined to,
 * and the bands of chrome it must keep off.
 */
data class DrawingSurface(val limit: Band, val exclude: List<Band>)

/**
 * Splits the screen into chrome and paper.
 *
 * The rail takes a full edge — its side depends on where it is docked — and the editor's
 * title bar takes the top of whatever rectangle that leaves, so the two never overlap and
 * the title bar sits beside a left- or right-docked rail rather than above it. Everything
 * still uncovered is the paper.
 *
 * [toolbarThickness] and [titleBarHeight] are both 0 while the toolbar is collapsed, which
 * yields the whole screen as paper and nothing to exclude.
 *
 * Shared so the two input paths cannot drift apart: the firmware is handed these as its
 * exclude/limit rects, and the [com.ethran.notable.editor.canvas.MotionEventStrokeSource]
 * fallback — which the firmware is not there to gate — tests the same limit rect itself.
 */
fun drawingSurface(
    position: AppSettings.Position,
    viewWidth: Int,
    viewHeight: Int,
    toolbarThickness: Int,
    titleBarHeight: Int,
): DrawingSurface {
    val (rail, free) = when (position) {
        AppSettings.Position.Top ->
            Band(0, 0, viewWidth, toolbarThickness) to
                    Band(0, toolbarThickness, viewWidth, viewHeight)

        AppSettings.Position.Bottom ->
            Band(0, viewHeight - toolbarThickness, viewWidth, viewHeight) to
                    Band(0, 0, viewWidth, viewHeight - toolbarThickness)

        AppSettings.Position.Left ->
            Band(0, 0, toolbarThickness, viewHeight) to
                    Band(toolbarThickness, 0, viewWidth, viewHeight)

        AppSettings.Position.Right ->
            Band(viewWidth - toolbarThickness, 0, viewWidth, viewHeight) to
                    Band(0, 0, viewWidth - toolbarThickness, viewHeight)
    }

    val titleBar = free.copy(bottom = free.top + titleBarHeight)
    val paper = free.copy(top = free.top + titleBarHeight)

    // A collapsed rail and a hidden title bar are zero-thickness bands. Sending those to the
    // firmware is at best a no-op and at worst an empty-rect edge case, so only the bands
    // that actually cover something are sent.
    return DrawingSurface(limit = paper, exclude = listOf(rail, titleBar).filter { !it.isEmpty })
}
