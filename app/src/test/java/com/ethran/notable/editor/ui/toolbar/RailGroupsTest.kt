package com.ethran.notable.editor.ui.toolbar

import com.ethran.notable.data.datastore.AppSettings
import com.ethran.notable.editor.ui.toolbar.model.ERASER_BASE_WIDTH
import com.ethran.notable.editor.ui.toolbar.model.NibWidth
import com.ethran.notable.editor.ui.toolbar.model.PenElement
import com.ethran.notable.editor.ui.toolbar.model.ToolbarElementId
import com.ethran.notable.editor.ui.toolbar.model.ToolbarPen
import com.ethran.notable.editor.ui.toolbar.model.baseWidth
import com.ethran.notable.editor.utils.ERASER_SWATH_WIDTH
import com.ethran.notable.editor.utils.Pen
import com.ethran.notable.ui.theme.Kaleido
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rail's arrangement is fixed, so it is worth asserting on directly: these are the
 * guarantees a user relies on when they stop looking at the rail before tapping it.
 */
class RailGroupsTest {

    private val pens = ToolbarPen.DEFAULT_PENS

    @Test
    fun `the tool group is the four implements then the two tools that take no ink`() {
        val tools = RailGroups.of(pens).tools
        assertEquals(
            ToolbarPen.RAIL_TYPES,
            tools.filterIsInstance<PenElement>().map { it.pen },
        )
        assertEquals(
            listOf(ToolbarElementId.ERASER, ToolbarElementId.SELECT),
            tools.drop(ToolbarPen.RAIL_TYPES.size).map { it.id },
        )
    }

    @Test
    fun `undo comes before redo, and the way out is last`() {
        val groups = RailGroups.of(pens)
        assertEquals(
            listOf(ToolbarElementId.UNDO, ToolbarElementId.REDO),
            groups.history.map { it.id },
        )
        assertEquals(ToolbarElementId.MENU, groups.pinned.last().id)
    }

    /**
     * The panel of previews, outline and bookmarks was reachable only by a three-finger swipe up —
     * a gesture nothing on screen mentions — so to a reader who did not know it, the feature was
     * not there at all. Its button is what makes it exist; losing it from the rail would be the
     * same regression, silently.
     */
    @Test
    fun `the navigation panel has a button, beside the page counter`() {
        val pinned = RailGroups.of(pens).pinned.map { it.id }

        assertTrue(ToolbarElementId.QUICK_NAV in pinned)
        assertEquals(
            pinned.indexOf(ToolbarElementId.PAGE_NAV) + 1,
            pinned.indexOf(ToolbarElementId.QUICK_NAV),
        )
    }

    @Test
    fun `the arrangement does not depend on which presets the user has`() {
        val mine = listOf(ToolbarPen("mine", Pen.CALLIGRAPHY, android.graphics.Color.BLACK, 5f))
        val ids = { p: List<ToolbarPen> ->
            RailGroups.of(p).let { it.tools.map(::idOf) + it.history.map(::idOf) + it.pinned.map(::idOf) }
        }
        assertEquals(ids(pens), ids(mine))
    }

    @Test
    fun `a preset the four implements do not stand for keeps a button in the overflow`() {
        val calligraphy = ToolbarPen("cal", Pen.CALLIGRAPHY, android.graphics.Color.BLACK, 5f)
        val overflow = RailGroups.of(pens + calligraphy).overflow
        assertTrue(
            "A user's own pen must not become unreachable",
            overflow.filterIsInstance<PenElement>().any { it.presetId == "cal" },
        )
    }

    @Test
    fun `every preset reaches the rail exactly once, on it or behind it`() {
        val groups = RailGroups.of(pens)
        val shown = (groups.tools + groups.overflow).filterIsInstance<PenElement>().map { it.presetId }
        assertEquals("A preset must not appear twice", shown.size, shown.distinct().size)
        assertEquals(pens.map { it.id }.toSet(), shown.toSet())
    }

    @Test
    fun `the shape tool stays reachable, in the overflow`() {
        val overflow = RailGroups.of(pens).overflow.map { it.id }
        assertTrue(ToolbarElementId.SHAPE in overflow)
        assertFalse("Shape is not one of bopa's implements", ToolbarElementId.SHAPE in
            RailGroups.of(pens).tools.map { it.id })
    }

    // --- the nib ramp ---

    @Test
    fun `every implement offers the same five steps, ascending`() {
        assertEquals(5, NibWidth.entries.size)
        for (pen in ToolbarPen.BASE_TYPES) {
            val ramp = NibWidth.entries.map { it.sizeFor(pen.baseWidth) }
            assertEquals("${pen.penName} ramp must ascend", ramp.sorted(), ramp)
            assertEquals(
                "${pen.penName} must not collapse two steps onto one width",
                ramp.size, ramp.distinct().size
            )
        }
        assertEquals(
            "the dots have to read as one ramp too",
            NibWidth.entries.map { it.dot }.sortedBy { it.value },
            NibWidth.entries.map { it.dot },
        )
    }

    @Test
    fun `a step scales off the implement, so a highlighter's medium is still a highlighter`() {
        val marker = NibWidth.MEDIUM.sizeFor(Pen.MARKER.baseWidth)
        val ballpen = NibWidth.MEDIUM.sizeFor(Pen.BALLPEN.baseWidth)
        assertTrue("a marker at medium must be broader than a pen at medium", marker > ballpen)
        // The old rail read these from each preset's own list, so the same dot meant 5 under one
        // pen and 40 under the next. What is fixed now is the step, not the number.
        assertEquals(24f, marker, 0.001f)
        assertEquals(5f, ballpen, 0.001f)
    }

    @Test
    fun `a size that is not on the ramp still lights exactly one dot`() {
        // 40 is what the marker preset shipped with, and it sits between the ramp's 24 and 48.
        assertEquals(NibWidth.BROAD, NibWidth.nearest(Pen.MARKER.baseWidth, 40f))
        // Anything at all, from a stroke menu or an older build.
        for (size in listOf(0.5f, 1f, 7f, 13f, 500f)) {
            assertTrue(NibWidth.nearest(Pen.BALLPEN.baseWidth, size) in NibWidth.entries)
        }
    }

    @Test
    fun `an untouched eraser still deletes the swath it always did`() {
        // The default is MEDIUM, and MEDIUM of the eraser's base has to be the constant the
        // erase path used when the eraser had no width at all — or every existing install would
        // silently start erasing a different amount.
        assertEquals(
            ERASER_SWATH_WIDTH,
            NibWidth.fromKeyOrDefault(AppSettings(version = 1).eraserWidth)
                .sizeFor(ERASER_BASE_WIDTH),
            0.001f
        )
        assertEquals(ERASER_SWATH_WIDTH, AppSettings(version = 1).eraserSwathWidth, 0.001f)
    }

    @Test
    fun `an unknown persisted step falls back rather than throwing`() {
        assertEquals(NibWidth.MEDIUM, NibWidth.fromKeyOrDefault("from-a-newer-build"))
        assertEquals(NibWidth.MEDIUM, NibWidth.fromKeyOrDefault(null))
    }

    // --- the palette ---

    @Test
    fun `the rail offers the twelve inks the iPad does, in the same order`() {
        // Positions are what the two apps agree on; changing one renames an ink on the other
        // device. Append-only.
        assertEquals(12, Kaleido.Inks.size)
        assertEquals(0xFF201E1D.toInt(), Kaleido.Inks[0])
        assertEquals(0xFFAE1800.toInt(), Kaleido.Inks[1])
        assertEquals(0xFF1B4FA0.toInt(), Kaleido.Inks[8])
        assertEquals(0xFF6B4423.toInt(), Kaleido.Inks[11])
        assertEquals(Kaleido.Inks.size, Kaleido.Inks.distinct().size)
    }

    @Test
    fun `a pen with no configured colours offers the whole palette`() {
        val ballpen = pens.first { it.id == "ball" }
        assertEquals(Kaleido.Inks, ballpen.effectiveColorOptions())
    }

    @Test
    fun `every seed preset writes with an ink that is in the palette`() {
        // Otherwise the strip would show twelve swatches and prepend a thirteenth to mark the
        // selection, which is a hole in the grid on a fresh install.
        for (preset in ToolbarPen.DEFAULT_PENS) {
            assertTrue(
                "${preset.id} writes ${Integer.toHexString(preset.color)}, not a palette ink",
                preset.color in Kaleido.Inks
            )
        }
    }

    @Test
    fun `a narrowed pen keeps only what the user picked`() {
        val narrowed = ToolbarPen(
            "narrow", Pen.BALLPEN, Kaleido.Inks[0], 5f,
            colorOptions = listOf(Kaleido.Inks[0], Kaleido.Inks[1]),
        )
        assertEquals(listOf(Kaleido.Inks[0], Kaleido.Inks[1]), narrowed.effectiveColorOptions())
    }

    private fun idOf(element: com.ethran.notable.editor.ui.toolbar.model.ToolbarElement) = element.id
}
