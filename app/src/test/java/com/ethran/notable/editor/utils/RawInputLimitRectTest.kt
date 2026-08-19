package com.ethran.notable.editor.utils

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Test

class RawInputLimitRectTest {
    @Test
    fun `uses the surface's screen bounds rather than treating screen origin as surface origin`() {
        // The left rail (80 px) and title bar (98 px) inset the 2480×1702 paper on a 2560×1800
        // panel. A view-local 0,0 rectangle recreates exactly the dead left/top strip reported
        // on hardware.
        assertEquals(
            Rect(80, 98, 2560, 1800),
            rawInputLimitRect(left = 80, top = 98, width = 2480, height = 1702)
        )
    }

    @Test
    fun `full screen surface remains unchanged`() {
        assertEquals(
            Rect(0, 0, 2560, 1800),
            rawInputLimitRect(left = 0, top = 0, width = 2560, height = 1800)
        )
    }
}
