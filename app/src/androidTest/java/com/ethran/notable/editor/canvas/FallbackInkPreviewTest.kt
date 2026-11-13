package com.ethran.notable.editor.canvas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.data.TouchPointList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FallbackInkPreviewTest {
    private val dirty = mutableListOf<Rect>()
    private val preview = FallbackInkPreview { dirty += Rect(it) }
    private val pen = Paint().apply {
        color = Color.BLUE; strokeWidth = 8f; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }

    private fun stroke(start: Float, end: Float) = TouchPointList().apply {
        add(TouchPoint(start, 20f, 1f, 0f, 0, 0, 1L))
        add(TouchPoint(end, 20f, 1f, 0f, 0, 0, 2L))
    }

    private fun frame(): Bitmap = Bitmap.createBitmap(200, 80, Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.WHITE)
        preview.draw(Canvas(this))
    }

    @Test
    fun motion_paints_visible_ink_before_any_finished_stroke() {
        preview.begin(pen)
        preview.update(stroke(10f, 50f))
        assertEquals(Color.BLUE, frame().getPixel(30, 20))
        assertTrue(dirty.last().contains(10, 20))
        assertTrue(dirty.last().contains(50, 20))
    }

    @Test
    fun cancelled_preview_leaves_the_authoritative_bitmap_unchanged() {
        preview.begin(pen)
        preview.update(stroke(10f, 50f))
        preview.cancelActive()
        assertEquals(Color.WHITE, frame().getPixel(30, 20))
        assertTrue("old preview bounds must be repainted", dirty.size >= 2)
    }

    @Test
    fun a_finished_preview_stays_until_its_own_bitmap_commit() {
        val first = stroke(10f, 50f)
        preview.begin(pen)
        preview.update(first)
        preview.finish(first)
        assertEquals(Color.BLUE, frame().getPixel(30, 20))

        val second = stroke(100f, 150f)
        preview.begin(pen.apply { color = Color.RED })
        preview.update(second)
        preview.committed(first)
        assertEquals(Color.WHITE, frame().getPixel(30, 20))
        assertEquals("committing older ink must keep the new live stroke", Color.RED, frame().getPixel(120, 20))
    }

    @Test
    fun cancelling_the_next_stroke_keeps_the_previous_uncommitted_preview() {
        val first = stroke(10f, 50f)
        preview.begin(pen)
        preview.update(first)
        preview.finish(first)
        preview.begin(pen)
        preview.update(stroke(100f, 150f))
        preview.cancelActive()
        assertEquals(Color.BLUE, frame().getPixel(30, 20))
        assertEquals(Color.WHITE, frame().getPixel(120, 20))
        preview.committed(first)
        assertEquals(Color.WHITE, frame().getPixel(30, 20))
    }

    @Test
    fun clearing_for_a_surface_change_removes_all_transient_ink() {
        val first = stroke(10f, 50f)
        preview.begin(pen); preview.update(first); preview.finish(first)
        preview.begin(pen); preview.update(stroke(100f, 150f))
        preview.clear()
        assertEquals(Color.WHITE, frame().getPixel(30, 20))
        assertEquals(Color.WHITE, frame().getPixel(120, 20))
        // A late handoff from the old page is harmless after cleanup.
        preview.committed(first)
        assertEquals(Color.WHITE, frame().getPixel(30, 20))
    }
}
