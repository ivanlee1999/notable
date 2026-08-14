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
     * The pointer the stroke belongs to, followed by id rather than index.
     *
     * A hand resting on a 6" screen is the ordinary case, not the exceptional one, and a
     * pointer's index shifts as others land and lift around it. Reading position from index 0
     * would draw whatever touched first — usually the palm.
     */
    private var penPointerId = MotionEvent.INVALID_POINTER_ID

    /**
     * Feeds one event in. [limitRect] is the region the pen may draw on — the firmware is
     * handed the same rectangle as its limit rect and enforces it there, so on this path it
     * has to be tested here or a stylus press on the docked rail would open a stroke under it.
     *
     * A stroke already under way is not cut off when it leaves the region: the firmware clips
     * at the boundary rather than discarding, and the alternative is a stroke that vanishes
     * because it clipped the rail on the way past.
     */
    fun onTouchEvent(event: MotionEvent, limitRect: Rect): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                // A pen landing beside a hand already on the glass arrives as POINTER_DOWN, so
                // both openings are the same event as far as a stroke is concerned.
                if (pending != null) return false
                val index = event.actionIndex
                if (!event.isPen(index)) return false
                if (!limitRect.contains(
                        event.getX(index).roundToInt(), event.getY(index).roundToInt()
                    )
                ) return false
                penPointerId = event.getPointerId(index)
                pending = TouchPointList().apply { add(event.toTouchPoint(index)) }
            }

            MotionEvent.ACTION_MOVE -> {
                val stroke = pending ?: return false
                val index = event.findPointerIndex(penPointerId).takeIf { it >= 0 } ?: return false
                // A batched move carries its intermediate samples as history. Reading only the
                // current position would sample the stroke at the frame rate rather than the
                // digitizer's, which shows up as visible faceting on a fast stroke.
                for (pos in 0 until event.historySize) stroke.add(event.toTouchPoint(index, pos))
                stroke.add(event.toTouchPoint(index))
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val stroke = pending ?: return false
                // Only the pen's own lift ends the stroke. A palm leaving the glass first must
                // not cut the line short, and its position must never be appended to it.
                val index = event.actionIndex
                if (event.getPointerId(index) != penPointerId) return false
                pending = null
                penPointerId = MotionEvent.INVALID_POINTER_ID
                stroke.add(event.toTouchPoint(index))
                // A tap is not a stroke: two points are the minimum the handlers can take a
                // bounding box from.
                if (stroke.size() >= 2) onStrokeFinished(stroke)
            }

            MotionEvent.ACTION_CANCEL -> cancel()

            else -> return false
        }
        return true
    }

    /** Drops a stroke in progress — the surface went away, or drawing was switched off under it. */
    fun cancel() {
        pending = null
        penPointerId = MotionEvent.INVALID_POINTER_ID
    }
}

private fun MotionEvent.isPen(pointerIndex: Int): Boolean =
    getToolType(pointerIndex) == MotionEvent.TOOL_TYPE_STYLUS ||
            getToolType(pointerIndex) == MotionEvent.TOOL_TYPE_ERASER

/**
 * One framework sample as the SDK's own point type, so both input paths hand the editor the
 * same thing. [historyPos] reads a batched historical sample; -1 reads the current position.
 */
private fun MotionEvent.toTouchPoint(pointerIndex: Int, historyPos: Int = -1): TouchPoint {
    val old = historyPos >= 0
    val tilt =
        if (old) getHistoricalAxisValue(MotionEvent.AXIS_TILT, pointerIndex, historyPos)
        else getAxisValue(MotionEvent.AXIS_TILT, pointerIndex)
    val barrel =
        if (old) getHistoricalOrientation(pointerIndex, historyPos)
        else getOrientation(pointerIndex)

    return TouchPoint(
        if (old) getHistoricalX(pointerIndex, historyPos) else getX(pointerIndex),
        if (old) getHistoricalY(pointerIndex, historyPos) else getY(pointerIndex),
        // toStrokePoint divides by the digitizer's own full-scale value; the framework
        // already reports 0..1, so put it back on that scale to come out unchanged rather
        // than normalized twice.
        (if (old) getHistoricalPressure(pointerIndex, historyPos)
        else getPressure(pointerIndex)) * rawInputMaxPressure,
        // Onyx fills this with the firmware's own contact size. Nothing downstream reads it —
        // a stroke's width comes from the pen preset — so it is left at zero rather than
        // guessed at from getSize(), which is a different quantity on a different scale.
        0f,
        // The framework reports tilt in polar form (how far the pen leans, plus which way the
        // barrel points); an Onyx point carries it already split across the two axes. A stylus
        // that reports no tilt — most capacitive pens — leaves both at zero.
        Math.toDegrees((tilt * sin(barrel)).toDouble()).roundToInt(),
        Math.toDegrees((tilt * cos(barrel)).toDouble()).roundToInt(),
        if (old) getHistoricalEventTime(historyPos) else eventTime,
    )
}
