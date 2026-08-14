package com.ethran.notable.editor.utils

import com.ethran.notable.data.datastore.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the pen may write once the docked chrome has taken its share.
 *
 * Two ways to get this wrong, both invisible until a device is in hand: leave the title bar
 * out of the exclusion and the firmware draws ink underneath it, or take its height off the
 * limit rect twice and a strip of paper below the bar goes dead. The property tests at the
 * end state the invariant rather than either half of the arithmetic.
 */
class DrawingBandsTest {

    private val width = 1404
    private val height = 1872
    private val rail = 40
    private val titleBar = 48

    private fun surface(
        position: AppSettings.Position,
        toolbar: Int = rail,
        title: Int = titleBar,
    ) = drawingSurface(position, width, height, toolbar, title)

    @Test
    fun `a left-docked rail leaves the title bar beside it, not above it`() {
        val s = surface(AppSettings.Position.Left)

        assertEquals(listOf(
            Band(0, 0, rail, height),                 // the rail, full height
            Band(rail, 0, width, titleBar),           // the title bar, starting where it ends
        ), s.exclude)
        assertEquals(Band(rail, titleBar, width, height), s.limit)
    }

    @Test
    fun `a right-docked rail mirrors it`() {
        val s = surface(AppSettings.Position.Right)

        assertEquals(listOf(
            Band(width - rail, 0, width, height),
            Band(0, 0, width - rail, titleBar),
        ), s.exclude)
        assertEquals(Band(0, titleBar, width - rail, height), s.limit)
    }

    @Test
    fun `a top-docked rail stacks the title bar under it`() {
        val s = surface(AppSettings.Position.Top)

        assertEquals(listOf(
            Band(0, 0, width, rail),
            Band(0, rail, width, rail + titleBar),
        ), s.exclude)
        assertEquals(Band(0, rail + titleBar, width, height), s.limit)
    }

    @Test
    fun `a bottom-docked rail still puts the title bar at the top`() {
        val s = surface(AppSettings.Position.Bottom)

        assertEquals(listOf(
            Band(0, height - rail, width, height),
            Band(0, 0, width, titleBar),
        ), s.exclude)
        assertEquals(Band(0, titleBar, width, height - rail), s.limit)
    }

    // Collapsing the toolbar hides the rail and the title bar together, so both bands go to
    // zero — and a zero-thickness rect is never handed to the firmware.
    @Test
    fun `a collapsed toolbar gives the pen the whole screen`() {
        for (position in AppSettings.Position.entries) {
            val s = surface(position, toolbar = 0, title = 0)

            assertEquals("$position", emptyList<Band>(), s.exclude)
            assertEquals("$position", Band(0, 0, width, height), s.limit)
        }
    }

    @Test
    fun `no excluded band overlaps the paper`() {
        for (position in AppSettings.Position.entries) {
            val s = surface(position)
            for (band in s.exclude) {
                assertTrue(
                    "$position: $band overlaps ${s.limit}",
                    band.right <= s.limit.left || band.left >= s.limit.right ||
                            band.bottom <= s.limit.top || band.top >= s.limit.bottom
                )
            }
        }
    }

    @Test
    fun `the chrome and the paper account for the whole screen`() {
        for (position in AppSettings.Position.entries) {
            val s = surface(position)
            val covered = s.exclude.sumOf { it.area } + s.limit.area

            assertEquals("$position", width * height, covered)
        }
    }

    private val Band.area: Int get() = (right - left) * (bottom - top)
}
