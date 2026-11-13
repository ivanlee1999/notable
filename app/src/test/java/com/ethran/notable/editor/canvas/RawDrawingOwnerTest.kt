package com.ethran.notable.editor.canvas

import org.junit.Assert.*
import org.junit.Test

class RawDrawingOwnerTest {
    @Test fun retiringASurfaceClosesItsChannelOnlyOnce() {
        val channel = RawDrawingOwner(); val surface = Any(); var closes = 0
        channel.claim(surface)
        channel.release(surface) { closes++ }
        channel.release(surface) { closes++ }
        assertEquals(1, closes)
        assertFalse(channel.isOwner(surface))
    }

    @Test fun retiringAnOldSurfaceCannotCloseItsSuccessor() {
        val channel = RawDrawingOwner(); val old = Any(); val current = Any()
        channel.claim(old); channel.claim(current)
        channel.release(old) { fail("Old surface closed the new native channel") }
        assertTrue(channel.isOwner(current))
    }

    @Test fun theSameViewCanReclaimARecreatedSurface() {
        val channel = RawDrawingOwner(); val view = Any(); var closes = 0
        repeat(2) {
            channel.claim(view)
            assertTrue(channel.isOwner(view))
            channel.release(view) { closes++ }
        }
        assertEquals(2, closes)
    }
}
