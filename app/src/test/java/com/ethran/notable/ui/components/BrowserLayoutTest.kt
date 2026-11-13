package com.ethran.notable.ui.components

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserLayoutTest {
    @Test fun defaultsAndUnknownSettingsUseReadableDeviceLayouts() {
        assertEquals(BrowserLayout.FEED, BrowserLayout.fromKey(null, compact = true))
        assertEquals(BrowserLayout.LARGE, BrowserLayout.fromKey("future-mode", compact = false))
        assertEquals(BrowserLayout.MEDIUM, BrowserLayout.fromKey("OVERVIEW", compact = true))
        assertEquals(3, BrowserLayout.OVERVIEW.columnsFor(900.dp))
        assertEquals(2, BrowserLayout.OVERVIEW.columnsFor(360.dp))
        assertEquals(BrowserLayout.COMPACT, BrowserLayout.fromKey("COMPACT", compact = true))
    }

    @Test fun expandingANoteKeepsEveryNeighborInOrderAndGivesItAFullRow() {
        val notes = (1..8).map(Int::toString)
        val rows = previewRows(notes, 3, "3") { it }
        assertEquals(notes, rows.flatten())
        assertEquals(listOf(listOf("1", "2"), listOf("3"), listOf("4", "5", "6"), listOf("7", "8")), rows)
        assertTrue(rows.all { it.size <= 3 })
    }

    @Test fun missingExpandedNotesAndEmptyLibrariesKeepNormalRows() {
        assertEquals(listOf(listOf("1", "2"), listOf("3")), previewRows(listOf("1", "2", "3"), 2, "missing") { it })
        assertEquals(emptyList<List<String>>(), previewRows(emptyList<String>(), 2, null) { it })
    }

    @Test fun densityChangesPreviewHeightWithoutShrinkingBelowAUsableCrop() {
        assertTrue(BrowserLayout.LARGE.previewHeight(800.dp) > BrowserLayout.MEDIUM.previewHeight(800.dp))
        assertTrue(BrowserLayout.MEDIUM.previewHeight(800.dp) > BrowserLayout.OVERVIEW.previewHeight(800.dp))
        assertTrue(BrowserLayout.OVERVIEW.previewHeight(200.dp) >= 64.dp)
        assertTrue(BrowserLayout.COMPACT.previewHeight(800.dp) < BrowserLayout.OVERVIEW.previewHeight(800.dp))
    }
}
