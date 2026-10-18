package com.ethran.notable.editor.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SurfaceFramePostTest {
    @Test
    fun `handoff follows bitmap draw and successful surface post`() {
        val events = mutableListOf<String>()
        val posted = postSurfaceFrame(
            lock = { events.add("lock"); Any() },
            draw = { events.add("draw") },
            unlockAndPost = { events.add("post") },
        )
        if (posted) events.add("reset raw ink")
        assertTrue(posted)
        assertEquals(listOf("lock", "draw", "post", "reset raw ink"), events)
    }

    @Test
    fun `missing surface does not hand off raw ink`() {
        assertFalse(postSurfaceFrame<Any>(
            lock = { null },
            draw = { error("Must not draw without a surface") },
            unlockAndPost = { error("Must not unlock without a surface") },
        ))
    }

    @Test
    fun `failed lock does not hand off raw ink`() {
        assertFalse(postSurfaceFrame<Any>(
            lock = { throw IllegalStateException("destroyed") },
            draw = { error("Must not draw") },
            unlockAndPost = { error("Must not unlock") },
        ))
    }

    @Test
    fun `failed draw releases canvas but does not hand off raw ink`() {
        var unlocked = false
        assertFalse(postSurfaceFrame(
            lock = { Any() },
            draw = { throw IllegalStateException("released during draw") },
            unlockAndPost = { unlocked = true },
        ))
        assertTrue(unlocked)
    }

    @Test
    fun `failed post does not hand off raw ink`() {
        assertFalse(postSurfaceFrame(
            lock = { Any() },
            draw = {},
            unlockAndPost = { throw IllegalStateException("released during post") },
        ))
    }
    @Test
    fun `queued frame does not reset raw ink until bitmap has posted`() {
        val events = mutableListOf<String>()
        var pendingFrame: (() -> Unit)? = null
        enqueueSurfaceFrame(
            enqueue = { pendingFrame = it; true },
            render = { events.add("bitmap posted"); true },
            onPosted = { if (it) events.add("reset raw ink") },
        )
        assertTrue(events.isEmpty())
        pendingFrame!!.invoke()
        assertEquals(listOf("bitmap posted", "reset raw ink"), events)
    }

    @Test
    fun `rejected frame completes without resetting raw ink`() {
        val results = mutableListOf<Boolean>()
        enqueueSurfaceFrame(
            enqueue = { false },
            render = { error("Rejected frame must not render") },
            onPosted = { results.add(it) },
        )
        assertEquals(listOf(false), results)
    }

    @Test
    fun `failed queued frame completes without resetting raw ink`() {
        val results = mutableListOf<Boolean>()
        var pendingFrame: (() -> Unit)? = null
        enqueueSurfaceFrame(
            enqueue = { pendingFrame = it; true },
            render = { false },
            onPosted = { results.add(it) },
        )
        pendingFrame!!.invoke()
        assertEquals(listOf(false), results)
    }
    @Test
    fun `frame queued before a new stroke cannot clear its raw ink`() {
        val guard = InkHandoffGuard()
        val queued = guard.generation
        guard.begin()
        guard.end()
        var cleared = false
        guard.runIfIdle(queued) { cleared = true }
        assertFalse(cleared)
    }

    @Test
    fun `active stroke cannot lose its raw ink even with current generation`() {
        val guard = InkHandoffGuard()
        guard.begin()
        var cleared = false
        guard.runIfIdle(guard.generation) { cleared = true }
        assertFalse(cleared)
    }

    @Test
    fun `completed stroke permits current frame handoff`() {
        val guard = InkHandoffGuard()
        guard.begin()
        guard.end()
        var cleared = false
        guard.runIfIdle(guard.generation) { cleared = true }
        assertTrue(cleared)
    }
    @Test
    fun `leaving drawing mode waits for queued bitmap before disabling raw drawing`() {
        val events = mutableListOf<String>()
        var pendingFrame: (() -> Unit)? = null
        enqueueSurfaceFrame(
            enqueue = { pendingFrame = it; true },
            render = { events.add("post"); true },
            onPosted = { posted ->
                handoffPostedFrame(posted, isCurrent = { true }) { events.add("disable") }
            },
        )
        assertTrue(events.isEmpty())
        pendingFrame!!.invoke()
        assertEquals(listOf("post", "disable"), events)
    }

    @Test
    fun `returning to drawing mode invalidates queued disable`() {
        var isDrawing = false
        var disabled = false
        var pendingFrame: (() -> Unit)? = null
        enqueueSurfaceFrame(
            enqueue = { pendingFrame = it; true },
            render = { true },
            onPosted = { posted ->
                handoffPostedFrame(posted, isCurrent = { !isDrawing }) { disabled = true }
            },
        )
        isDrawing = true
        pendingFrame!!.invoke()
        assertFalse(disabled)
    }

    @Test
    fun `failed bitmap post cannot disable raw drawing on mode exit`() {
        var disabled = false
        handoffPostedFrame(false, isCurrent = { true }) { disabled = true }
        assertFalse(disabled)
    }
}
