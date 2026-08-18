package com.ethran.notable.editor

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Test

class InkViewportTest {

    @Test
    fun `identity leaves screen pixels alone`() {
        val point = InkViewport.Identity.pagePoint(120f, 340f)
        assertEquals(120f, point.x, 0.001f)
        assertEquals(340f, point.y, 0.001f)
    }

    @Test
    fun `a scrolled view files ink further down the page`() {
        val viewport = InkViewport(Offset(0f, 196f), zoom = 1f)
        assertEquals(716f, viewport.pageY(520f), 0.001f)
    }

    @Test
    fun `zoom divides before the scroll is added`() {
        val viewport = InkViewport(Offset(40f, 100f), zoom = 2f)
        // Half a page unit per pixel, then the page's own offset.
        assertEquals(140f, viewport.pageX(200f), 0.001f)
        assertEquals(400f, viewport.pageY(600f), 0.001f)
    }

    /**
     * The bug this type exists for: the view moved while the pen was down, and the stroke has to
     * be filed where it was drawn rather than where the editor has since scrolled to.
     *
     * The numbers are the ones from the note that prompted it — a page whose whole scroll range
     * is one screenful, written on at the bottom of it. Filed through the view at pen-up the
     * line lands 196 units up the page, on top of the line above it; filed through the view at
     * pen-down it lands where the nib was.
     */
    @Test
    fun `a stroke is filed through the view it was drawn on, not the one it lifted on`() {
        val atPenDown = InkViewport(Offset(0f, 196f), zoom = 1f)
        val atPenUp = InkViewport(Offset.Zero, zoom = 1f)

        val drawnAtScreenY = 520f
        assertEquals(716f, atPenDown.pageY(drawnAtScreenY), 0.001f)
        assertEquals(520f, atPenUp.pageY(drawnAtScreenY), 0.001f)
    }
}
