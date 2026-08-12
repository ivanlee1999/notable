package com.ethran.notable.editor

import com.ethran.notable.data.db.StrokePoint
import com.ethran.notable.editor.state.Shape
import com.ethran.notable.editor.utils.ShapeGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The shape tool's arithmetic.
 *
 * The editor had one shape, a straight line, where every competitor has at least rectangle,
 * ellipse and arrow. All of the difference is geometry, so all of it is testable here — and the
 * properties worth pinning are the ones a user would notice: a rectangle whose corners are the
 * corners they dragged, an ellipse that closes, an arrow whose head is at the end they finished on.
 */
class ShapeGeometryTest {

    private fun point(x: Float, y: Float, pressure: Float? = null) =
        StrokePoint(x = x, y = y, pressure = pressure)

    private fun bounds(points: List<StrokePoint>) = listOf(
        points.minOf { it.x }, points.minOf { it.y },
        points.maxOf { it.x }, points.maxOf { it.y }
    )

    // MARK: Rectangle

    @Test
    fun `a rectangle spans exactly the dragged diagonal`() {
        val points = ShapeGeometry.points(Shape.RECT, point(10f, 20f), point(110f, 220f))

        val (left, top, right, bottom) = bounds(points)
        assertEquals(10f, left, 0.5f)
        assertEquals(20f, top, 0.5f)
        assertEquals(110f, right, 0.5f)
        assertEquals(220f, bottom, 0.5f)
    }

    /** Dragging up-left has to draw the same rectangle as dragging down-right. */
    @Test
    fun `a rectangle does not care which corner the drag started from`() {
        val forwards = bounds(ShapeGeometry.points(Shape.RECT, point(10f, 20f), point(110f, 220f)))
        val backwards = bounds(ShapeGeometry.points(Shape.RECT, point(110f, 220f), point(10f, 20f)))

        forwards.zip(backwards).forEach { (a, b) -> assertEquals(a, b, 0.5f) }
    }

    /** One stroke, closed — so undo takes the rectangle back rather than one of its sides. */
    @Test
    fun `a rectangle closes back onto its first corner`() {
        val points = ShapeGeometry.points(Shape.RECT, point(0f, 0f), point(100f, 50f))

        assertEquals(points.first().x, points.last().x, 0.5f)
        assertEquals(points.first().y, points.last().y, 0.5f)
    }

    @Test
    fun `a rectangle has no duplicate points at its corners`() {
        val points = ShapeGeometry.points(Shape.RECT, point(0f, 0f), point(100f, 50f))

        // The closing point repeats the first on purpose; nothing else may repeat its neighbour.
        val duplicates = points.dropLast(1).zipWithNext().count { (a, b) ->
            abs(a.x - b.x) < 1e-4 && abs(a.y - b.y) < 1e-4
        }
        assertEquals(0, duplicates)
    }

    // MARK: Ellipse

    @Test
    fun `an ellipse is inscribed in the dragged rectangle`() {
        val points = ShapeGeometry.points(Shape.ELLIPSE, point(0f, 0f), point(200f, 100f))

        val (left, top, right, bottom) = bounds(points)
        assertEquals(0f, left, 1f)
        assertEquals(0f, top, 1f)
        assertEquals(200f, right, 1f)
        assertEquals(100f, bottom, 1f)
    }

    @Test
    fun `an ellipse closes`() {
        val points = ShapeGeometry.points(Shape.ELLIPSE, point(0f, 0f), point(200f, 100f))

        assertEquals(points.first().x, points.last().x, 0.5f)
        assertEquals(points.first().y, points.last().y, 0.5f)
    }

    @Test
    fun `every point of a circle is on it`() {
        val radius = 50f
        val points = ShapeGeometry.points(Shape.ELLIPSE, point(0f, 0f), point(2 * radius, 2 * radius))

        points.forEach {
            assertEquals(radius, hypot(it.x - radius, it.y - radius), 0.5f)
        }
    }

    // MARK: Arrow

    @Test
    fun `an arrow starts where the drag did and reaches where it ended`() {
        val points = ShapeGeometry.points(Shape.ARROW, point(0f, 0f), point(300f, 0f))

        assertEquals(0f, points.first().x, 0.5f)
        assertEquals(0f, points.first().y, 0.5f)
        assertTrue(points.any { abs(it.x - 300f) < 1f && abs(it.y) < 1f })
    }

    /** The head belongs at the end you finished on, not the one you began at. */
    @Test
    fun `an arrow head sits at the far end`() {
        val points = ShapeGeometry.points(Shape.ARROW, point(0f, 0f), point(300f, 0f))

        // The barbs are the only points off the shaft; they must all be near the tip.
        val offAxis = points.filter { abs(it.y) > 1f }
        assertTrue("no head was drawn", offAxis.isNotEmpty())
        assertTrue(offAxis.all { it.x > 150f })
    }

    /** A short arrow still has a point; a long one does not grow a head the size of a page. */
    @Test
    fun `the arrow head is scaled and capped`() {
        val short = ShapeGeometry.points(Shape.ARROW, point(0f, 0f), point(40f, 0f))
        val long = ShapeGeometry.points(Shape.ARROW, point(0f, 0f), point(4000f, 0f))

        val shortSpread = short.maxOf { abs(it.y) }
        val longSpread = long.maxOf { abs(it.y) }
        assertTrue("a short arrow lost its head", shortSpread > 0.5f)
        assertTrue("a long arrow grew an oversized head", longSpread < 100f)
    }

    // MARK: The nib

    /** A shape is drawn with the same nib as freehand ink, not a flat synthetic one. */
    @Test
    fun `pressure is carried from the drag onto every shape`() {
        for (shape in Shape.entries) {
            val points = ShapeGeometry.points(
                shape, point(0f, 0f, pressure = 0.2f), point(100f, 100f, pressure = 0.8f)
            )
            assertTrue(shape.name, points.all { it.pressure != null })
            assertTrue(shape.name, points.all { it.pressure!! in 0.19f..0.81f })
        }
    }

    @Test
    fun `a drag with no pressure produces points with none`() {
        for (shape in Shape.entries) {
            val points = ShapeGeometry.points(shape, point(0f, 0f), point(100f, 100f))
            assertTrue(shape.name, points.all { it.pressure == null })
        }
    }

    @Test
    fun `every shape produces something to draw`() {
        for (shape in Shape.entries) {
            val points = ShapeGeometry.points(shape, point(0f, 0f), point(100f, 100f))
            assertTrue(shape.name, points.size > 10)
        }
    }

    /** A tap rather than a drag must not divide by a zero-length shaft. */
    @Test
    fun `a zero length drag is harmless`() {
        for (shape in Shape.entries) {
            val points = ShapeGeometry.points(shape, point(50f, 50f), point(50f, 50f))
            assertTrue(shape.name, points.all { it.x.isFinite() && it.y.isFinite() })
        }
    }
}
