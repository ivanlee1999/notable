package com.ethran.notable.editor.ui

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionToolbarPlacementTest {
    @Test
    fun toolbarCentersAboveSelectionInPhysicalPixels() {
        assertEquals(IntOffset(140, 180), selectionToolbarOffset(
            IntOffset(200, 300), IntSize(400, 200), IntSize(1080, 1920),
            IntSize(520, 104), 16,
        ))
    }

    @Test
    fun selectionAtTopPlacesToolbarBelowIt() {
        assertEquals(IntOffset(0, 78), selectionToolbarOffset(
            IntOffset(0, 10), IntSize(100, 60), IntSize(320, 560),
            IntSize(320, 56), 8,
        ))
    }

    @Test
    fun negativeAndOffscreenSelectionsNeverLoseTheControls() {
        val viewport = IntSize(320, 560)
        val toolbar = IntSize(300, 56)
        for (x in listOf(-1000, -1, 0, 100, 1000)) {
            for (y in listOf(-1000, -1, 0, 100, 1000)) {
                val offset = selectionToolbarOffset(IntOffset(x, y), IntSize(80, 80),
                    viewport, toolbar, 8)
                assertTrue(offset.x in 0..20)
                assertTrue(offset.y in 0..504)
            }
        }
    }

    @Test
    fun undersizedViewportDoesNotCreateAnInvalidClampRange() {
        assertEquals(IntOffset.Zero, selectionToolbarOffset(
            IntOffset(-100, 500), IntSize(50, 50), IntSize(10, 10),
            IntSize(48, 56), 8,
        ))
    }
}
