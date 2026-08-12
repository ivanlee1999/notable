package com.ethran.notable.editor.utils

import com.ethran.notable.data.db.StrokePoint
import com.ethran.notable.editor.state.Shape
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Turns the two ends of a drag into the points of a shape.
 *
 * Pure, and separate from the input handler, so the geometry can be tested on the JVM: shapes are
 * arithmetic, and arithmetic is the part worth pinning. The editor had one shape — a straight line
 * — where every competitor has at least rectangle, ellipse and arrow, and the difference is
 * entirely in this file.
 *
 * Every shape comes out as a single stroke of interpolated points, which is what the rest of the
 * editor already stores, renders, erases and syncs. That is the reason a shape is not a new kind
 * of object: a rectangle drawn here is ink on the BOOX and ink on the iPad, with nothing on either
 * side needing to know it was drawn with a tool.
 */
object ShapeGeometry {

    /** Points per straight run. Enough that a diagonal reads as a line rather than as a chain. */
    private const val SEGMENT_POINTS = 100

    /** Points around a full ellipse — closer spacing than a line, since curvature shows. */
    private const val ELLIPSE_POINTS = 180

    /** The arrow head's length, as a fraction of the shaft, and how far it opens. */
    private const val ARROW_HEAD_FRACTION = 0.18f
    private const val ARROW_HEAD_MAX = 90f
    private const val ARROW_HEAD_ANGLE = (PI / 7).toFloat()

    /**
     * The stroke [shape] makes between [start] and [end].
     *
     * Pressure and tilt are interpolated from the two ends the way [transformToLine] does, so a
     * shape is drawn with the same nib as freehand ink rather than a flat synthetic one.
     */
    fun points(shape: Shape, start: StrokePoint, end: StrokePoint): List<StrokePoint> =
        when (shape) {
            Shape.LINE -> transformToLine(start, end)
            Shape.RECT -> rectangle(start, end)
            Shape.ELLIPSE -> ellipse(start, end)
            Shape.ARROW -> arrow(start, end)
        }

    /**
     * The rectangle the drag's diagonal spans, as one closed outline.
     *
     * One stroke rather than four, so undo takes the rectangle back and not one of its sides.
     */
    private fun rectangle(start: StrokePoint, end: StrokePoint): List<StrokePoint> {
        val left = minOf(start.x, end.x)
        val right = maxOf(start.x, end.x)
        val top = minOf(start.y, end.y)
        val bottom = maxOf(start.y, end.y)

        val corners = listOf(
            left to top, right to top, right to bottom, left to bottom, left to top
        )
        return polyline(corners, start, end)
    }

    /** The ellipse inscribed in the drag's rectangle, closed back onto its own first point. */
    private fun ellipse(start: StrokePoint, end: StrokePoint): List<StrokePoint> {
        val centreX = (start.x + end.x) / 2f
        val centreY = (start.y + end.y) / 2f
        val radiusX = abs(end.x - start.x) / 2f
        val radiusY = abs(end.y - start.y) / 2f

        return List(ELLIPSE_POINTS + 1) { i ->
            val fraction = i.toFloat() / ELLIPSE_POINTS
            val angle = fraction * 2f * PI.toFloat()
            interpolate(
                start, end, fraction,
                x = centreX + radiusX * cos(angle),
                y = centreY + radiusY * sin(angle),
            )
        }
    }

    /**
     * A shaft from [start] to [end] with a head at [end].
     *
     * The head is drawn by going out to one barb, back to the tip, and out to the other — one
     * continuous path, because the stroke is one stroke. It is scaled to the shaft and capped, so
     * a long arrow does not grow a head the size of a page and a short one still has a point.
     */
    private fun arrow(start: StrokePoint, end: StrokePoint): List<StrokePoint> {
        val length = hypot(end.x - start.x, end.y - start.y)
        if (length < 1f) return transformToLine(start, end)

        val head = minOf(length * ARROW_HEAD_FRACTION, ARROW_HEAD_MAX)
        val direction = atan2(end.y - start.y, end.x - start.x)

        fun barb(offset: Float): Pair<Float, Float> {
            val angle = direction + PI.toFloat() + offset
            return (end.x + head * cos(angle)) to (end.y + head * sin(angle))
        }

        val path = listOf(
            start.x to start.y,
            end.x to end.y,
            barb(-ARROW_HEAD_ANGLE),
            end.x to end.y,
            barb(ARROW_HEAD_ANGLE),
        )
        return polyline(path, start, end)
    }

    /**
     * Interpolates along a sequence of corners, spending [SEGMENT_POINTS] on each run.
     *
     * The pressure/tilt fraction advances over the *whole* path rather than restarting at each
     * corner, so a shape drawn with a pressure-sensitive nib shades evenly instead of pulsing at
     * every turn.
     */
    private fun polyline(
        path: List<Pair<Float, Float>>, start: StrokePoint, end: StrokePoint
    ): List<StrokePoint> {
        if (path.size < 2) return emptyList()
        val runs = path.size - 1
        val points = mutableListOf<StrokePoint>()

        for (run in 0 until runs) {
            val (fromX, fromY) = path[run]
            val (toX, toY) = path[run + 1]
            // The first point of every run but the first repeats the previous run's last, so it
            // is skipped: a duplicate point is a zero-length segment for the renderer to chew on.
            val first = if (run == 0) 0 else 1
            for (i in first until SEGMENT_POINTS) {
                val alongRun = i.toFloat() / (SEGMENT_POINTS - 1)
                val alongPath = (run + alongRun) / runs
                points += interpolate(
                    start, end, alongPath,
                    x = fromX + (toX - fromX) * alongRun,
                    y = fromY + (toY - fromY) * alongRun,
                )
            }
        }
        return points
    }

    /**
     * A point at [x], [y] carrying the nib [start] and [end] agree on at [fraction].
     *
     * Null and non-null pressure are not mixed: the two endpoints of one drag always report the
     * same shape of data, and treating a null as a zero would draw a shape that fades to nothing.
     */
    private fun interpolate(
        start: StrokePoint, end: StrokePoint, fraction: Float, x: Float, y: Float
    ): StrokePoint {
        fun lerp(from: Float, to: Float) = from + (to - from) * fraction

        return StrokePoint(
            x = x,
            y = y,
            pressure = if (start.pressure != null && end.pressure != null) {
                lerp(start.pressure, end.pressure)
            } else null,
            tiltX = if (start.tiltX != null && end.tiltX != null) {
                lerp(start.tiltX.toFloat(), end.tiltX.toFloat()).toInt()
            } else null,
            tiltY = if (start.tiltY != null && end.tiltY != null) {
                lerp(start.tiltY.toFloat(), end.tiltY.toFloat()).toInt()
            } else null,
        )
    }
}
