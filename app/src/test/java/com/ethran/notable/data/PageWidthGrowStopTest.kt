package com.ethran.notable.data

import com.ethran.notable.data.model.PageSizePreset
import com.ethran.notable.editor.PageViewportBounds
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The horizontal twin of [PageHeightGrowStopTest]: the pannable width goes through the same
 * grow-stop as the height ([PageHeightGrowStop.clamp] is axis-agnostic).
 *
 * The bug these pin: a declared page's width used to be the sheet, full stop. PageSplit stamps a
 * sheet onto every page it touches without moving or clipping x, so a formerly-endless page whose
 * ink ran past the new sheet's right edge became a declared page with *unreachable* ink — the pan
 * clamped at the sheet and the zoom floor forbade zooming out to it. The vertical axis had its
 * legacy exemption; the horizontal one was missing.
 */
class PageWidthGrowStopTest {

    private val sheet = PageSizePreset.A4.size.width

    @Test
    fun `a legacy page wider than its sheet reaches all of its ink on first measurement`() {
        // First measurement of the session: the measured extent, straight from the content, is the
        // only record of what the page already is, and it comes through whole.
        val measured = PageViewportBounds.contentExtent(sheet, listOf(sheet * 2f))
        assertEquals(measured, PageHeightGrowStop.clamp(measured, sheet, sessionHeight = null))
    }

    @Test
    fun `a page whose ink is inside the sheet clamps to the sheet`() {
        // Ink near (even touching) the right edge measures sheet + slack, but the grow-stop holds
        // the line: within the paper, the paper is the page.
        val measured = PageViewportBounds.contentExtent(sheet, listOf(sheet - 10f))
        assertEquals(sheet, PageHeightGrowStop.clamp(measured, sheet, sessionHeight = sheet))
    }

    @Test
    fun `writing at the right edge repeatedly cannot ratchet the page wider`() {
        // The live-write path: each stroke at the reachable right edge re-measures extent + slack,
        // and without the clamp every one of them would unlock another ~50px of pan past the sheet.
        var width: Int? = sheet // a declared page, loaded and already measured at its sheet
        repeat(5) {
            val strokeRight = width!!.toFloat() // ink touching the reachable right edge
            val measured = PageViewportBounds.contentExtent(sheet, listOf(strokeRight))
            width = PageHeightGrowStop.clamp(measured, sheet, width)
        }
        assertEquals(sheet, width)
    }

    @Test
    fun `writing at the edge of a legacy wide page keeps its extent, not more`() {
        val wide = sheet * 3
        val measured = PageViewportBounds.contentExtent(sheet, listOf(wide.toFloat()))
        assertEquals(wide, PageHeightGrowStop.clamp(measured, sheet, sessionHeight = wide))
    }

    @Test
    fun `deleting the widest content shrinks the page back`() {
        // The ceiling is only a ceiling: a smaller measurement passes straight through, so a wide
        // legacy page whose stray ink was cleaned up becomes an ordinary one-sheet page.
        assertEquals(sheet, PageHeightGrowStop.clamp(sheet, sheet, sessionHeight = sheet * 3))
    }
}
