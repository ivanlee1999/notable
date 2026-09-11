package com.ethran.notable.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

/** The same identity vectors live in Bopa's CoverIdentityTests. */
class CoverIdentityTest {
    @Test
    fun spineIdentityMatchesBopa() {
        val vectors = listOf(
            "" to 0, "notebook-1" to 3, "notebook-2" to 0,
            "00000000-0000-0000-0000-000000000001" to 1,
            "ffffffff-ffff-ffff-ffff-ffffffffffff" to 0,
            "日本語" to 3, "Notes 🖊️" to 3, "polygenelubricants" to 0,
        )
        val palette = listOf(
            Color(0xFFAE1800), Color(0xFF201E1D), Color(0xFFDD2B0F), Color(0xFF888888),
        )
        for ((id, index) in vectors) {
            assertEquals(id, palette[index], Kaleido.spineFor(id))
        }
    }
}
