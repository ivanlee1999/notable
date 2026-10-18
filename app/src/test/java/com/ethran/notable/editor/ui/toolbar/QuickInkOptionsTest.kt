package com.ethran.notable.editor.ui.toolbar

import org.junit.Assert.assertEquals
import org.junit.Test

class QuickInkOptionsTest {
    @Test fun common_inks_are_one_tap_away_in_a_stable_order() {
        val black = 0xFF201E1D.toInt()
        val red = 0xFFAE1800.toInt()
        val blue = 0xFF1B4FA0.toInt()
        assertEquals(listOf(black, blue, red), quickInkOptions(listOf(red, 0xFF888888.toInt(), blue, black)))
    }

    @Test fun respects_a_custom_palette_without_duplicates() {
        assertEquals(listOf(12, 14), quickInkOptions(listOf(12, 14, 12)))
        assertEquals(emptyList<Int>(), quickInkOptions(emptyList()))
    }
}
