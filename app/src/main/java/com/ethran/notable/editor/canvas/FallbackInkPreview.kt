package com.ethran.notable.editor.canvas

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import com.onyx.android.sdk.pen.data.TouchPointList
import kotlin.math.ceil
import kotlin.math.floor

/** Transient surface ink. Only the normal finished-stroke handler ever changes the page. */
internal class FallbackInkPreview(private val redraw: (Rect) -> Unit) {
    private class Track(val paint: Paint) {
        val path = Path()
        val bounds = RectF()
        var count = 0
        var consumed = 0
        var lastX = 0f
        var lastY = 0f
    }

    private var active: Track? = null
    // Keep a finished preview visible until its authoritative page bitmap has been painted.
    // A later pen-down may already have opened the next track when that handoff completes.
    private val awaitingCommit = mutableListOf<Pair<TouchPointList, Track>>()

    fun begin(paint: Paint) {
        cancelActive()
        active = Track(Paint(paint))
    }

    fun update(stroke: TouchPointList) {
        val track = active ?: return
        for (index in track.consumed until stroke.points.size) {
            val point = stroke.points[index]
            if (!point.x.isFinite() || !point.y.isFinite()) continue
            if (track.count == 0) {
                track.path.moveTo(point.x, point.y)
                track.bounds.set(point.x, point.y, point.x, point.y)
            } else {
                track.path.lineTo(point.x, point.y)
                track.bounds.union(point.x, point.y)
            }
            track.lastX = point.x
            track.lastY = point.y
            track.count++
        }
        track.consumed = stroke.points.size
        if (track.count > 0) redraw(track.dirty())
    }

    fun finish(stroke: TouchPointList) {
        active?.let { awaitingCommit += stroke to it }
        active = null
    }

    fun committed(stroke: TouchPointList) {
        val index = awaitingCommit.indexOfFirst { it.first === stroke }
        if (index >= 0) awaitingCommit.removeAt(index).second.let { if (it.count > 0) redraw(it.dirty()) }
    }

    fun cancelActive() {
        active?.let { if (it.count > 0) redraw(it.dirty()) }
        active = null
    }

    fun clear() {
        cancelActive()
        awaitingCommit.forEach { (_, track) -> if (track.count > 0) redraw(track.dirty()) }
        awaitingCommit.clear()
    }

    fun draw(canvas: Canvas) {
        awaitingCommit.forEach { (_, track) -> track.draw(canvas) }
        active?.draw(canvas)
    }

    private fun Track.draw(canvas: Canvas) {
        if (count == 1) canvas.drawPoint(lastX, lastY, paint)
        else if (count > 1) canvas.drawPath(path, paint)
    }

    private fun Track.dirty(): Rect {
        val margin = ceil(paint.strokeWidth / 2f).toInt() + 2
        return Rect(floor(bounds.left).toInt() - margin, floor(bounds.top).toInt() - margin,
            ceil(bounds.right).toInt() + margin, ceil(bounds.bottom).toInt() + margin)
    }
}
