package com.ethran.notable.editor.state

import android.graphics.Rect
import androidx.compose.ui.unit.IntOffset
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.db.StrokePoint
import com.ethran.notable.editor.PageView
import com.ethran.notable.editor.utils.Pen
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * The selection's stored rect is a baseline, not a cursor.
 *
 * `applySelectionDisplace` used to offset the stored [SelectionState.selectionRect] in place —
 * android.graphics.Rect is mutable, so every commit shifted the baseline itself while
 * `selectedStrokes` and `selectionDisplaceOffset` kept the baseline-plus-cumulative-offset model.
 * Duplicating a moved selection then committed its redraw zone one displacement further than the
 * pasted strokes actually sat, and the copy stayed invisible until an unrelated full redraw.
 */
@RunWith(AndroidJUnit4::class)
class SelectionDisplaceTest {

    private fun stroke(left: Float, top: Float, right: Float, bottom: Float) = Stroke(
        id = UUID.randomUUID().toString(),
        size = 2f,
        pen = Pen.BALLPEN,
        top = top,
        bottom = bottom,
        left = left,
        right = right,
        points = listOf(
            StrokePoint(x = left, y = top, pressure = 1f),
            StrokePoint(x = right, y = bottom, pressure = 1f),
        ),
        pageId = "page",
    )

    private fun selectionOf(stroke: Stroke, rect: Rect) = SelectionState().apply {
        selectedStrokes = listOf(stroke)
        initialSelectedStrokes = listOf(stroke)
        selectionRect = rect
        selectionStartOffset = IntOffset(rect.left, rect.top)
        selectionDisplaceOffset = IntOffset(0, 0)
        placementMode = PlacementMode.Move
    }

    @Test
    fun committing_a_move_redraws_the_displaced_zone_but_keeps_the_stored_baseline() {
        val state = selectionOf(stroke(100f, 100f, 200f, 200f), Rect(100, 100, 200, 200))
        state.selectionDisplaceOffset = IntOffset(30, 40)
        val page = mockk<PageView>(relaxed = true)

        state.applySelectionDisplace(page)

        verify { page.drawAreaPageCoordinates(Rect(130, 140, 230, 240)) }
        assertEquals(
            "the stored rect is the selection's baseline and must not move with a commit",
            Rect(100, 100, 200, 200),
            state.selectionRect,
        )
    }

    @Test
    fun duplicating_a_moved_selection_redraws_exactly_where_the_copy_lands() {
        val original = stroke(100f, 100f, 200f, 200f)
        val state = selectionOf(original, Rect(100, 100, 200, 200))
        state.selectionDisplaceOffset = IntOffset(30, 40)
        val page = mockk<PageView>(relaxed = true)

        // The duplicate flow as EditorControlTower runs it: commit the move, fork the copy
        // (+50,+50 on the cumulative offset), then commit the paste.
        state.applySelectionDisplace(page)
        state.duplicateSelection()
        assertEquals(IntOffset(80, 90), state.selectionDisplaceOffset)
        state.applySelectionDisplace(page)

        // The pasted strokes sit at baseline + cumulative offset...
        val added = slot<List<Stroke>>()
        verify { page.addStrokes(capture(added)) }
        val pasted = added.captured.single()
        assertEquals(180f, pasted.left, 0.001f)
        assertEquals(190f, pasted.top, 0.001f)

        // ...and the committed redraw zone must be the same place, not one displacement further.
        verify { page.drawAreaPageCoordinates(Rect(180, 190, 280, 290)) }
    }
}
