package com.ethran.notable.editor.ui.toolbar

import com.ethran.notable.editor.ui.toolbar.model.PenElement
import com.ethran.notable.editor.ui.toolbar.model.ToolbarElementId
import com.ethran.notable.editor.ui.toolbar.model.ToolbarPen
import com.ethran.notable.editor.utils.Pen
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

    // --- nib choices ---

    @Test
    fun `nib dots are the pen's own sizes, in ascending order`() {
        val ballpen = pens.first { it.id == "ball" }
        assertEquals(ToolbarPen.DEFAULT_STROKE_SIZES.sorted(), ballpen.nibChoices(5f))

        val marker = pens.first { it.id == "marker" }
        assertEquals(ToolbarPen.DEFAULT_MARKER_SIZES.sorted(), marker.nibChoices(40f))
    }

    @Test
    fun `the size in hand survives the cut to four`() {
        val many = ToolbarPen(
            "many", Pen.BALLPEN, android.graphics.Color.BLACK, size = 30f,
            sizeOptions = listOf(1f, 2f, 3f, 5f, 8f, 30f),
        )
        val choices = many.nibChoices(30f)
        assertEquals(4, choices.size)
        assertTrue("The nib being written with must be one of the dots", 30f in choices)
        assertEquals(choices.sorted(), choices)
    }

    @Test
    fun `a size not among the options still shows as the selected dot`() {
        val pen = ToolbarPen(
            "odd", Pen.BALLPEN, android.graphics.Color.BLACK, size = 7f,
            sizeOptions = listOf(3f, 5f, 10f),
        )
        assertEquals(listOf(3f, 5f, 7f, 10f), pen.nibChoices(7f))
    }

    @Test
    fun `a single-size pen draws no dots, because there is nothing to choose`() {
        val fixed = ToolbarPen(
            "fixed", Pen.BALLPEN, android.graphics.Color.BLACK, size = 5f,
            sizeOptions = listOf(5f),
        )
        assertTrue(fixed.nibChoices(5f).isEmpty())
    }

    private fun idOf(element: com.ethran.notable.editor.ui.toolbar.model.ToolbarElement) = element.id
}
