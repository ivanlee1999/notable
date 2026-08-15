package com.ethran.notable.editor.ui.toolbar.model

import com.ethran.notable.editor.EditorViewModel
import com.ethran.notable.editor.ToolbarUiState
import com.ethran.notable.editor.state.Mode
import com.ethran.notable.editor.utils.Pen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolbarElementsTest {

    private val pens = ToolbarPen.DEFAULT_PENS

    @Test
    fun `every static id resolves to an element`() {
        ToolbarElementId.entries.forEach { id ->
            if (id == ToolbarElementId.PEN) return@forEach // sentinel for pen instances
            val element = ToolbarElements.all[id]
            assertNotNull("No ToolbarElement registered for $id", element)
            assertEquals("Element registered under wrong id", id, element!!.id)
        }
    }

    @Test
    fun `registry contains exactly the static ids`() {
        assertEquals(ToolbarElementId.entries.size - 1, ToolbarElements.all.size)
        assertFalse(
            "Pen instances must not live in the static registry",
            ToolbarElements.all.values.any { it is PenElement },
        )
    }

    @Test
    fun `only the page number lacks an icon, because it draws text instead`() {
        ToolbarElements.all.values.forEach { element ->
            if (element.id != ToolbarElementId.PAGE_NAV) {
                assertNotNull("${element.id} has no icon", element.icon)
            }
        }
    }

    @Test
    fun `pen element selected in draw and line mode with matching preset only`() {
        val ball = ToolbarElements.penElement(pens.first { it.id == "ball" })
        assertTrue(ball.isSelected(ToolbarUiState(mode = Mode.Draw, penPresetId = "ball")))
        assertTrue(ball.isSelected(ToolbarUiState(mode = Mode.Line, penPresetId = "ball")))
        // Same base pen type, different preset: not selected.
        assertFalse(ball.isSelected(ToolbarUiState(mode = Mode.Draw, penPresetId = "red")))
        assertFalse(ball.isSelected(ToolbarUiState(mode = Mode.Erase, penPresetId = "ball")))
    }

    @Test
    fun `mode elements selected by mode`() {
        val eraser = ToolbarElements.of(ToolbarElementId.ERASER)
        val select = ToolbarElements.of(ToolbarElementId.SELECT)
        assertTrue(eraser.isSelected(ToolbarUiState(mode = Mode.Erase)))
        assertFalse(eraser.isSelected(ToolbarUiState(mode = Mode.Select)))
        assertTrue(select.isSelected(ToolbarUiState(mode = Mode.Select)))
        assertFalse(select.isSelected(ToolbarUiState(mode = Mode.Draw)))
    }

    @Test
    fun `default presets only use placeable base pen types`() {
        pens.forEach { preset ->
            assertTrue(
                "Preset ${preset.id} uses non-placeable base type ${preset.pen}",
                preset.pen in ToolbarPen.BASE_TYPES,
            )
        }
        assertFalse(Pen.DASHED in ToolbarPen.BASE_TYPES)
        assertFalse(Pen.REDBALLPEN in ToolbarPen.BASE_TYPES)
    }

    @Test
    fun `default preset ids are unique`() {
        assertEquals(pens.size, pens.map { it.id }.distinct().size)
    }

    @Test
    fun `the rail resolves one preset per implement, and reaches the rest through overflow`() {
        val rail = ToolbarPen.railPresets(pens)
        assertEquals(ToolbarPen.RAIL_TYPES, rail.map { it.pen })
        // Seeded from the user's own presets, not from a private copy.
        assertTrue(rail.all { it in pens })

        // Every preset has exactly one home: on the rail, or in the overflow behind it.
        val overflow = ToolbarPen.extraPresets(pens)
        assertEquals(pens.size, rail.size + overflow.size)
        assertTrue((rail + overflow).map { it.id }.containsAll(pens.map { it.id }))
        assertTrue("A rail preset must not repeat in the overflow", rail.none { it in overflow })
    }

    @Test
    fun `a user's own preset wins the rail slot over the seed`() {
        val mine = ToolbarPen("mine", Pen.FOUNTAIN, android.graphics.Color.MAGENTA, 8f)
        val rail = ToolbarPen.railPresets(listOf(mine))
        assertEquals("mine", rail.first { it.pen == Pen.FOUNTAIN }.id)
        // The types the user has no preset for still resolve, so the rail is never short.
        assertEquals(ToolbarPen.RAIL_TYPES.size, rail.size)
        assertEquals(ToolbarPen.RAIL_TYPES, rail.map { it.pen })
    }

    @Test
    fun `every rail implement has a seed preset to fall back on`() {
        val fromNothing = ToolbarPen.railPresets(emptyList())
        assertEquals(ToolbarPen.RAIL_TYPES, fromNothing.map { it.pen })
        assertTrue(fromNothing.all { it in ToolbarPen.DEFAULT_PENS })
    }

    @Test
    fun `default pen settings are the single source of truth for the viewmodel`() {
        // Same object: EditorViewModel must alias the presets, not re-declare values.
        assertTrue(EditorViewModel.DEFAULT_PEN_SETTINGS === ToolbarPen.defaultPenSettings)
        pens.forEach { preset ->
            assertEquals(
                "Default setting drifted for preset ${preset.id}",
                preset.setting(),
                ToolbarPen.defaultPenSettings[preset.id],
            )
        }
        assertEquals(pens.size, ToolbarPen.defaultPenSettings.size)
    }

    @Test
    fun `collapsed icon falls back to dashed-line when no preset matches`() {
        val state = ToolbarUiState(mode = Mode.Draw, penPresetId = "deleted-preset")
        assertEquals(
            IconRef.Drawable(com.ethran.notable.R.drawable.line_dashed),
            ToolbarElements.presentlyUsedToolIcon(state, pens),
        )
    }

    @Test
    fun `presently used tool icon prefers the mode tool over the pen in line mode`() {
        val lineState = ToolbarUiState(mode = Mode.Line, penPresetId = "ball")
        assertEquals(
            ToolbarElements.of(ToolbarElementId.SHAPE).icon,
            ToolbarElements.presentlyUsedToolIcon(lineState, pens),
        )
        val drawState = ToolbarUiState(mode = Mode.Draw, penPresetId = "marker")
        assertEquals(
            ToolbarElements.penIcon(Pen.MARKER),
            ToolbarElements.presentlyUsedToolIcon(drawState, pens),
        )
    }
}
