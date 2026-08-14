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

    private data class Pointer(
        val id: Int,
        val x: Float,
        val y: Float,
        val toolType: Int = MotionEvent.TOOL_TYPE_STYLUS,
        val pressure: Float = 0.5f,
    )

    /** [actionIndex] names which pointer the action is about, for the POINTER_ variants. */
    private fun event(action: Int, pointers: List<Pointer>, actionIndex: Int = 0): MotionEvent {
        val properties = pointers.map {
            MotionEvent.PointerProperties().apply {
                id = it.id
                toolType = it.toolType
            }
        }
        val coords = pointers.map {
            MotionEvent.PointerCoords().apply {
                x = it.x
                y = it.y
                pressure = it.pressure
                size = 1f
            }
        }
        return MotionEvent.obtain(
            0L, 0L,
            action or (actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            pointers.size, properties.toTypedArray(), coords.toTypedArray(),
            0, 0, 1f, 1f, 0, 0, 0, 0
        )
    }

    private fun event(action: Int, x: Float, y: Float, pressure: Float = 0.5f): MotionEvent =
        event(action, listOf(Pointer(id = 0, x = x, y = y, pressure = pressure)))

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

    // A hand resting on a screen this size is the ordinary case, and the framework gives no
    // promise about which index a pointer keeps. These are the cases that separate following
    // the pen by id from reading whatever happens to be first.

    private val palm = Pointer(id = 7, x = 800f, y = 800f, toolType = MotionEvent.TOOL_TYPE_FINGER)
    private fun pen(x: Float, y: Float) = Pointer(id = 3, x = x, y = y)

    @Test
    fun a_palm_already_down_does_not_open_a_stroke() {
        source.onTouchEvent(event(MotionEvent.ACTION_DOWN, listOf(palm)), canvas)
        assertNull(collected)

        // The pen lands beside it, so it arrives as the second pointer.
        source.onTouchEvent(
            event(MotionEvent.ACTION_POINTER_DOWN, listOf(palm, pen(10f, 10f)), actionIndex = 1),
            canvas
        )
        source.onTouchEvent(event(MotionEvent.ACTION_MOVE, listOf(palm, pen(20f, 20f))), canvas)
        source.onTouchEvent(
            event(MotionEvent.ACTION_POINTER_UP, listOf(palm, pen(30f, 30f)), actionIndex = 1),
            canvas
        )

        // Every point is the pen's, never the palm's — which never moved from 800,800.
        val points = collected!!.points
        assertEquals(3, points.size)
        points.forEach { assertTrue("palm leaked into the stroke", it.x <= 30f) }
    }

    @Test
    fun the_palm_lifting_first_does_not_cut_the_stroke_short() {
        source.onTouchEvent(
            event(MotionEvent.ACTION_DOWN, listOf(pen(10f, 10f), palm)), canvas
        )
        source.onTouchEvent(event(MotionEvent.ACTION_MOVE, listOf(pen(20f, 20f), palm)), canvas)
        // The palm leaves; the pen is still writing.
        source.onTouchEvent(
            event(MotionEvent.ACTION_POINTER_UP, listOf(pen(30f, 30f), palm), actionIndex = 1),
            canvas
        )
        assertNull("the palm's lift ended the stroke", collected)

        // The pen's index shifted to 0 when the palm left, so following it by index would
        // have lost it here.
        source.onTouchEvent(event(MotionEvent.ACTION_MOVE, listOf(pen(40f, 40f))), canvas)
        source.onTouchEvent(event(MotionEvent.ACTION_UP, listOf(pen(50f, 50f))), canvas)

        val points = collected!!.points
        assertEquals(4, points.size)
        assertEquals(50f, points.last().x, 0.01f)
    }

    @Test
    fun a_finger_alone_never_draws() {
        // One finger is a gesture — scroll, tap — and belongs to the gesture receiver.
        source.onTouchEvent(event(MotionEvent.ACTION_DOWN, listOf(palm)), canvas)
        source.onTouchEvent(
            event(MotionEvent.ACTION_MOVE, listOf(palm.copy(x = 400f, y = 400f))), canvas
        )
        source.onTouchEvent(
            event(MotionEvent.ACTION_UP, listOf(palm.copy(x = 200f, y = 200f))), canvas
        )

        assertNull(collected)
    }
}
