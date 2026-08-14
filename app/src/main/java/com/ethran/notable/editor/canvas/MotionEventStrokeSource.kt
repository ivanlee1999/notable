package com.ethran.notable.editor.canvas

import android.graphics.Rect
import android.view.MotionEvent
import com.ethran.notable.editor.utils.rawInputMaxPressure
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.data.TouchPointList
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Collects a stroke out of plain [MotionEvent]s, for a device where the Onyx raw-pen path
 * never came up.
 *
 * The firmware hands [OnyxInputHandler] a finished [TouchPointList] on pen-up, and the whole
 * editor — ink, shapes, selection, erase — hangs off that single call. So this reads the same
 * shape out of the framework's own events and dispatches to the same place rather than
 * duplicating any of it; every tool keeps working without knowing which path fed it.
 *
 * What the framework cannot replace is the *live* track. The raw path paints ink into the
 * panel from firmware as the pen moves, which is the whole reason this app is built on it;
 * here a stroke appears when the pen lifts. That is a real downgrade, and it is the reason
 * this is a fallback rather than a second supported input mode.
 */
class MotionEventStrokeSource(
    private val onStrokeFinished: (TouchPointList) -> Unit,
) {
    private var pending: TouchPointList? = null

    /**
     * Feeds one event in. [limitRect] is the region the pen may draw on — the firmware is
     * handed the same rectangle as its limit rect and enforces it there, so on this path it
     * has to be tested here or a stylus press on the docked rail would open a stroke under it.
     *
     * A stroke already under way is not cut off when it leaves the region: the firmware
     * clips at the boundary rather than discarding, and the alternative is a stroke that
     * vanishes because it clipped the rail on the way past.
     */
    fun onTouchEvent(event: MotionEvent, limitRect: Rect): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!limitRect.contains(event.x.roundToInt(), event.y.roundToInt())) return false
                pending = TouchPointList().apply { add(event.toTouchPoint()) }
            }

            MotionEvent.ACTION_MOVE -> {
                val stroke = pending ?: return false
                // A batched move carries its intermediate samples as history. Reading only the
                // current position would sample the stroke at the frame rate rather than the
                // digitizer's, which shows up as visible faceting on a fast stroke.
                for (i in 0 until event.historySize) stroke.add(event.toTouchPoint(i))
                stroke.add(event.toTouchPoint())
            }

            MotionEvent.ACTION_UP -> {
                val stroke = pending ?: return false
                pending = null
                stroke.add(event.toTouchPoint())
                // A tap is not a stroke: two points are the minimum the handlers can take a
                // bounding box from.
                if (stroke.size() >= 2) onStrokeFinished(stroke)
            }

            MotionEvent.ACTION_CANCEL -> pending = null

            else -> return false
        }
        return true
    }

    /** Drops a stroke in progress — the surface went away, or drawing was switched off under it. */
    fun cancel() {
        pending = null
    }
}

/**
 * One framework sample as the SDK's own point type, so both input paths hand the editor the
 * same thing. [historyPos] reads a batched historical sample; -1 reads the current position.
 */
private fun MotionEvent.toTouchPoint(historyPos: Int = -1): TouchPoint {
    val historical = historyPos >= 0
    val tilt =
        if (historical) getHistoricalAxisValue(MotionEvent.AXIS_TILT, historyPos)
        else getAxisValue(MotionEvent.AXIS_TILT)
    val barrel = if (historical) getHistoricalOrientation(historyPos) else orientation

    return TouchPoint(
        if (historical) getHistoricalX(historyPos) else x,
        if (historical) getHistoricalY(historyPos) else y,
        // toStrokePoint divides by the digitizer's own full-scale value; the framework
        // already reports 0..1, so put it back on that scale to come out unchanged rather
        // than normalized twice.
        (if (historical) getHistoricalPressure(historyPos) else pressure) * rawInputMaxPressure,
        // Onyx fills this with the firmware's own contact size. Nothing downstream reads it —
        // a stroke's width comes from the pen preset — so it is left at zero rather than
        // guessed at from getSize(), which is a different quantity on a different scale.
        0f,
        // The framework reports tilt in polar form (how far the pen leans, plus which way the
        // barrel points); an Onyx point carries it already split across the two axes. A stylus
        // that reports no tilt — most capacitive pens — leaves both at zero.
        Math.toDegrees((tilt * sin(barrel)).toDouble()).roundToInt(),
        Math.toDegrees((tilt * cos(barrel)).toDouble()).roundToInt(),
        if (historical) getHistoricalEventTime(historyPos) else eventTime,
    )
}
