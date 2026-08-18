package com.ethran.notable.editor.utils

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The eraser indicator's on-screen width tracks the erased swath through the zoom.
 *
 * [handleErase] strokes the erase path in PAGE units with [ERASER_SWATH_WIDTH], so the screen
 * diameter of what gets deleted is `30 · zoom` px — while the firmware indicator used a flat 30px
 * whatever the zoom, diverging from the erased track everywhere but zoom 1.
 */
class EraserIndicatorWidthTest {

    @Test
    fun `the indicator is the swath scaled by zoom, like a pen's live width`() {
        assertEquals(ERASER_SWATH_WIDTH * 0.5f, eraserIndicatorWidth(0.5f), 0.0001f)
        assertEquals(ERASER_SWATH_WIDTH, eraserIndicatorWidth(1f), 0.0001f)
        assertEquals(ERASER_SWATH_WIDTH * 2f, eraserIndicatorWidth(2f), 0.0001f)
    }
}
