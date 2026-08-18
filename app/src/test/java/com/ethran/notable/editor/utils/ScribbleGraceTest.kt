package com.ethran.notable.editor.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scribble-to-erase grace check, with both stamps on the clock the fallback input path
 * actually uses: MotionEvent.eventTime, whose base is device uptime — a few hours since boot,
 * not the epoch.
 *
 * The bug pinned here: the stroke-end stamp used to be System.currentTimeMillis() (epoch,
 * ~1.7e12) while the point timestamps it was compared against were uptime (~1e7). After the
 * first stroke of a session, every later stroke's first point read as astronomically earlier
 * than "last stroke end + 150ms", so the guard returned "too soon" forever and scribble-erase
 * was dead on every non-Onyx device. Both stamps now come from the path's own point timestamps,
 * so the comparison is a difference on one clock — these cases must hold on an uptime base
 * exactly as they do on an epoch one.
 */
class ScribbleGraceTest {

    /** Three hours since boot, in ms — an ordinary MotionEvent.eventTime. */
    private val uptime = 3L * 60 * 60 * 1000

    @Test
    fun `a stroke starting inside the grace period is not a scribble`() {
        assertTrue(isWithinScribbleGrace(lastStrokeEndTime = uptime, firstPointTime = uptime + 100))
    }

    @Test
    fun `a stroke starting after the grace period may erase`() {
        assertFalse(isWithinScribbleGrace(lastStrokeEndTime = uptime, firstPointTime = uptime + 200))
    }

    @Test
    fun `the boundary itself is outside the grace`() {
        assertFalse(isWithinScribbleGrace(lastStrokeEndTime = uptime, firstPointTime = uptime + 150))
    }

    @Test
    fun `the first stroke of a session is never suppressed`() {
        // lastStrokeEndTime starts at 0; any real timestamp, uptime or epoch, is far past it.
        assertFalse(isWithinScribbleGrace(lastStrokeEndTime = 0, firstPointTime = uptime))
    }

    @Test
    fun `an epoch stamp against uptime points is the dead-eraser shape`() {
        // What the old code produced: stroke end stamped on the epoch clock, points on uptime.
        // The guard then suppressed everything — pinned here as documentation of why the two
        // stamps must share a base, not as behavior to keep.
        val epochNow = 1_700_000_000_000L
        assertTrue(
            "mixing bases starves the eraser: this is the state the fix removes",
            isWithinScribbleGrace(lastStrokeEndTime = epochNow, firstPointTime = uptime + 200),
        )
    }
}
