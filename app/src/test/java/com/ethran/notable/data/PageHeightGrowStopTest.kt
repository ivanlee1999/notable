package com.ethran.notable.data

import com.ethran.notable.data.model.PageSizePreset
import com.ethran.notable.editor.PageViewportBounds
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The grow-stop on a page's scrollable height: a page never gets taller than its sheet, or than it
 * already was this session — but the *first* measurement of a session must come through unclamped,
 * because the session height it would clamp against does not exist yet. Seeding that ceiling with
 * the sheet instead is the bug these tests pin: it cut a legacy tall page down to one sheet on its
 * very first load, stranding every stroke below it with no scroll that could reach them.
 */
class PageHeightGrowStopTest {

    private val sheet = PageSizePreset.A4.size.height

    @Test
    fun `the first measurement of a session is never clamped`() {
        // A legacy page three sheets of ink tall, opened fresh: the session map is empty, and the
        // measured extent — straight from the database — is the only record of what the page
        // already is. It must be taken whole.
        val measured = sheet * 3
        assertEquals(measured, PageHeightGrowStop.clamp(measured, sheet, sessionHeight = null))
    }

    @Test
    fun `after eviction the height is re-derived from content, not remembered`() {
        // Eviction wipes the session height, so the next load takes the first-measurement path
        // again. That is safe for the same reason the first load is: the measurement covers
        // everything the database holds, so nothing reachable is lost by forgetting.
        val measured = sheet * 2
        assertEquals(measured, PageHeightGrowStop.clamp(measured, sheet, sessionHeight = null))
    }

    @Test
    fun `writing below the sheet does not grow the page`() {
        // The stop itself. Ink landing past the bottom used to stretch the page, which is where
        // "subpages" came from — screenfuls of notes that were not pages.
        assertEquals(sheet, PageHeightGrowStop.clamp(sheet + 500, sheet, sessionHeight = sheet))
    }

    @Test
    fun `a legacy tall page keeps its extent across recomputes`() {
        val tall = sheet * 3
        assertEquals(tall, PageHeightGrowStop.clamp(tall, sheet, sessionHeight = tall))
        // And ink drawn below even that does not grow it further: the below-sheet allowance is
        // for what the page already held, not an invitation to keep extending it.
        assertEquals(tall, PageHeightGrowStop.clamp(tall + 500, sheet, sessionHeight = tall))
    }

    @Test
    fun `deleting the lowest content shrinks the page back`() {
        // The ceiling is only a ceiling: a smaller measurement passes straight through, so a
        // legacy page whose stray ink was cleaned up becomes an ordinary one-sheet page.
        assertEquals(sheet, PageHeightGrowStop.clamp(sheet, sheet, sessionHeight = sheet * 3))
    }

    @Test
    fun `a stale session height below the sheet cannot pull the page under it`() {
        // Whatever the session claims, the page is at least its sheet.
        assertEquals(sheet, PageHeightGrowStop.clamp(sheet, sheet, sessionHeight = sheet / 2))
    }

    @Test
    fun `writing at the bottom edge repeatedly cannot ratchet the page taller`() {
        // The live-write path. PageView used to grow the height directly on each stroke —
        // `bottom + 50`, no ceiling — so a stroke touching the visible bottom of a declared page
        // always exceeded the height and unlocked another ~50px of scroll, stroke after stroke.
        // Writing now re-measures through contentExtent and this clamp, exactly like the load
        // path, so a session of writing at the edge holds the line the sheet draws.
        var height: Int? = sheet // a declared page, loaded and already measured at its sheet
        repeat(5) {
            val strokeBottom = height!!.toFloat() // ink touching the visible bottom
            val measured = PageViewportBounds.contentExtent(sheet, listOf(strokeBottom))
            height = PageHeightGrowStop.clamp(measured, sheet, height)
        }
        assertEquals(sheet, height)
    }

    @Test
    fun `writing at the bottom of a legacy tall page keeps its extent, not more`() {
        // The same live write on a page that already holds ink below its sheet: the measured
        // extent may exceed the sheet, but never what the page already was.
        val tall = sheet * 3
        val measured = PageViewportBounds.contentExtent(sheet, listOf(tall.toFloat()))
        assertEquals(tall, PageHeightGrowStop.clamp(measured, sheet, sessionHeight = tall))
    }
}
