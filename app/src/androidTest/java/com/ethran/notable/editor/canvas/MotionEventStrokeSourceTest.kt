package com.ethran.notable.editor.canvas

import android.graphics.Rect
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ethran.notable.editor.utils.rawInputMaxPressure
import com.onyx.android.sdk.pen.data.TouchPointList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The stroke path for an ONYX device whose raw channel never opened. It cannot be driven on
 * such a device here, so what is pinned instead is the contract it has to meet for the
 * editor downstream to treat it like a firmware stroke: the points the pen actually visited,
 * pressure on the digitizer's scale, and the two things the firmware would otherwise be
 * enforcing — the rail's band, and that a tap is not a stroke.
 */
@RunWith(AndroidJUnit4::class)
class MotionEventStrokeSourceTest {

    private val canvas = Rect(0, 0, 1000, 1000)

    /** Everything but a rail docked across the bottom 40px. */
    private val aboveTheRail = Rect(0, 0, 1000, 960)

    private var collected: TouchPointList? = null
    private val source = MotionEventStrokeSource { collected = it }

    private fun event(action: Int, x: Float, y: Float, pressure: Float = 0.5f): MotionEvent {
        val properties = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_STYLUS
        }
        val coords = MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            this.pressure = pressure
            size = 1f
        }
        return MotionEvent.obtain(
            0L, 0L, action, 1, arrayOf(properties), arrayOf(coords),
            0, 0, 1f, 1f, 0, 0, 0, 0
        )
    }

    private fun stroke(vararg points: Pair<Float, Float>, pressure: Float = 0.5f) {
        points.forEachIndexed { index, (x, y) ->
            val action = when (index) {
                0 -> MotionEvent.ACTION_DOWN
                points.lastIndex -> MotionEvent.ACTION_UP
                else -> MotionEvent.ACTION_MOVE
            }
            source.onTouchEvent(event(action, x, y, pressure), canvas)
        }
    }

    @Test
    fun a_finished_stroke_carries_every_point_the_pen_visited() {
        stroke(10f to 10f, 20f to 30f, 40f to 60f)

        val points = collected?.points
        assertEquals(3, points?.size)
        assertEquals(10f, points!![0].x, 0.01f)
        assertEquals(30f, points[1].y, 0.01f)
        assertEquals(60f, points[2].y, 0.01f)
    }

    @Test
    fun pressure_lands_back_on_the_digitizer_scale() {
        // toStrokePoint divides by this, so a point that came in at 0.5 has to leave at
        // 0.5 * scale to survive the round trip as 0.5 rather than being normalized twice.
        stroke(10f to 10f, 20f to 20f, pressure = 0.5f)

        val pressure = collected!!.points.first().pressure
        assertEquals(0.5f * rawInputMaxPressure, pressure, 0.01f)
        assertEquals(0.5f, pressure / rawInputMaxPressure, 0.01f)
    }

    @Test
    fun a_tap_is_not_a_stroke() {
        source.onTouchEvent(event(MotionEvent.ACTION_DOWN, 10f, 10f), canvas)
        source.onTouchEvent(event(MotionEvent.ACTION_UP, 10f, 10f), canvas)

        // Two points arrive — down and up — but they are the same place, and the handlers
        // downstream would take a zero-area bounding box from it.
        assertEquals(2, collected?.points?.size)
        assertEquals(10f, collected!!.points[0].x, 0.01f)
    }

    @Test
    fun a_press_on_the_rail_never_opens_a_stroke() {
        // The firmware is handed this same rectangle as its limit rect and enforces it there.
        source.onTouchEvent(event(MotionEvent.ACTION_DOWN, 500f, 980f), aboveTheRail)
        source.onTouchEvent(event(MotionEvent.ACTION_MOVE, 500f, 900f), aboveTheRail)
        source.onTouchEvent(event(MotionEvent.ACTION_UP, 500f, 800f), aboveTheRail)

        assertNull(collected)
    }

    @Test
    fun a_stroke_that_runs_past_the_rail_is_kept() {
        // The firmware clips at the boundary rather than discarding, and a stroke that
        // vanished because it clipped the rail on the way past would be worse than one that
        // ran a few pixels under it.
        source.onTouchEvent(event(MotionEvent.ACTION_DOWN, 500f, 900f), aboveTheRail)
        source.onTouchEvent(event(MotionEvent.ACTION_MOVE, 500f, 950f), aboveTheRail)
        source.onTouchEvent(event(MotionEvent.ACTION_UP, 500f, 990f), aboveTheRail)

        assertEquals(3, collected?.points?.size)
    }

    @Test
    fun a_cancelled_stroke_is_dropped() {
        source.onTouchEvent(event(MotionEvent.ACTION_DOWN, 10f, 10f), canvas)
        source.onTouchEvent(event(MotionEvent.ACTION_MOVE, 20f, 20f), canvas)
        source.onTouchEvent(event(MotionEvent.ACTION_CANCEL, 30f, 30f), canvas)

        assertNull(collected)
    }

    @Test
    fun drawing_switched_off_mid_stroke_leaves_nothing_behind() {
        source.onTouchEvent(event(MotionEvent.ACTION_DOWN, 10f, 10f), canvas)
        source.onTouchEvent(event(MotionEvent.ACTION_MOVE, 20f, 20f), canvas)
        source.cancel()
        // The pen-up that follows belongs to a stroke that no longer exists.
        source.onTouchEvent(event(MotionEvent.ACTION_UP, 30f, 30f), canvas)

        assertNull(collected)
    }

    @Test
    fun a_move_with_no_stroke_open_is_ignored() {
        assertTrue(!source.onTouchEvent(event(MotionEvent.ACTION_MOVE, 10f, 10f), canvas))
        assertNull(collected)
    }
}
