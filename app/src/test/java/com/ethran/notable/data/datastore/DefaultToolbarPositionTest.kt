package com.ethran.notable.data.datastore

import androidx.compose.ui.unit.dp
import com.ethran.notable.ui.theme.KALEIDO_WIDE_BREAKPOINT
import org.junit.Assert.assertEquals
import org.junit.Test

class DefaultToolbarPositionTest {

    @Test
    fun `a one-handed panel starts with the rail docked at the bottom`() {
        // Palma 2 Pro: 824px across a 2x display.
        assertEquals(AppSettings.Position.Bottom, defaultToolbarPosition(412.dp))
    }

    @Test
    fun `a tablet keeps the shipped default`() {
        // Tab Air 10C, about 940dp across.
        assertEquals(AppSettings.Position.Top, defaultToolbarPosition(940.dp))
    }

    @Test
    fun `the breakpoint itself is already wide`() {
        // Same edge kaleidoMetrics uses, so the rail and the Library never disagree about
        // which kind of device this is.
        assertEquals(AppSettings.Position.Top, defaultToolbarPosition(KALEIDO_WIDE_BREAKPOINT))
        assertEquals(
            AppSettings.Position.Bottom,
            defaultToolbarPosition(KALEIDO_WIDE_BREAKPOINT - 1.dp)
        )
    }
}
