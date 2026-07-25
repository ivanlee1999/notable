package com.ethran.notable.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugTest {

    private fun frame(cls: String, method: String, file: String, line: Int) =
        StackTraceElement(cls, method, file, line)

    @Test
    fun keeps_only_app_frames_and_drops_plumbing() {
        val stack = arrayOf(
            frame("java.lang.Thread", "getStackTrace", "Thread.java", 1),
            frame("com.ethran.notable.utils.DebugKt", "logCallStack", "debug.kt", 9),
            frame("kotlinx.coroutines.DispatchedTask", "run", "DispatchedTask.kt", 101),
            frame("kotlin.coroutines.jvm.internal.BaseContinuationImpl", "resumeWith", "ContinuationImpl.kt", 34),
            frame("com.ethran.notable.editor.PageView", "loadPage", "PageView.kt", 300),
            frame("com.ethran.notable.data.PageDataManager", "setPage", "PageDataManager.kt", 460),
        )

        val out = formatAppCallStack(stack)

        // App frames survive, plumbing and this file's own frame are gone.
        assertTrue(out.contains("editor.PageView.loadPage (PageView.kt:300)"))
        assertTrue(out.contains("data.PageDataManager.setPage (PageDataManager.kt:460)"))
        assertFalse(out.contains("DispatchedTask"))
        assertFalse(out.contains("BaseContinuationImpl"))
        assertFalse(out.contains("logCallStack"))
        assertEquals(2, out.lines().size)
    }

    @Test
    fun falls_back_to_raw_frames_when_no_app_frame_present() {
        val stack = arrayOf(
            frame("java.lang.Thread", "getStackTrace", "Thread.java", 1),
            frame("kotlinx.coroutines.DispatchedTask", "run", "DispatchedTask.kt", 101),
            frame("kotlin.coroutines.jvm.internal.BaseContinuationImpl", "resumeWith", "ContinuationImpl.kt", 34),
            frame("kotlinx.coroutines.scheduling.CoroutineScheduler", "runSafely", "CoroutineScheduler.kt", 589),
        )

        val out = formatAppCallStack(stack)

        // Never empty: with no app frame, show something rather than a blank line.
        assertTrue(out.isNotBlank())
        assertTrue(out.contains("CoroutineScheduler.runSafely"))
    }

    @Test
    fun respects_depth_limit() {
        val stack = (1..20).map {
            frame("com.ethran.notable.pkg.Class$it", "m$it", "F$it.kt", it)
        }.toTypedArray()

        val out = formatAppCallStack(stack, n = 5)

        assertEquals(5, out.lines().size)
    }
}
