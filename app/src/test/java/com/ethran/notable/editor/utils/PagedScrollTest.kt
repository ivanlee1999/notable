package com.ethran.notable.editor.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PagedScrollTest {

    private val viewport = 1000

    @Test
    fun `advancing scrolls the canvas down, so the delta is negative`() {
        // requestScroll's sign convention is the finger's: reaching the next screen means
        // dragging up, and the consumer negates it into a downward scroll.
        assertTrue(pagedScrollDelta(viewport, direction = 1) < 0f)
    }

    @Test
    fun `going back scrolls the canvas up`() {
        assertTrue(pagedScrollDelta(viewport, direction = -1) > 0f)
    }

    @Test
    fun `a step is one viewport less the overlap`() {
        assertEquals(-940f, pagedScrollDelta(viewport, direction = 1), 0.001f)
    }

    @Test
    fun `the two directions are exact opposites, so a turn back lands where it started`() {
        assertEquals(
            -pagedScrollDelta(viewport, direction = 1),
            pagedScrollDelta(viewport, direction = -1),
            0.001f
        )
    }

    @Test
    fun `the overlap keeps some of the outgoing screen, but most of it turns`() {
        val travelled = -pagedScrollDelta(viewport, direction = 1)
        assertTrue("a step should move most of a screen", travelled > viewport * 0.8f)
        assertTrue("a step should never exceed a screen", travelled < viewport)
    }

    @Test
    fun `no direction means no movement`() {
        assertEquals(0f, pagedScrollDelta(viewport, direction = 0), 0f)
    }

    @Test
    fun `an unmeasured viewport cannot produce a step`() {
        // PageView reports a zero height until the canvas has been laid out; stepping by a
        // fraction of nothing would silently do nothing anyway, but returning 0 keeps the
        // caller from queueing an empty scroll pass.
        assertEquals(0f, pagedScrollDelta(0, direction = 1), 0f)
        assertEquals(0f, pagedScrollDelta(-5, direction = 1), 0f)
    }
}
