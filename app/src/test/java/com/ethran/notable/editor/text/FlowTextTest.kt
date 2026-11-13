package com.ethran.notable.editor.text

import com.ethran.notable.data.db.Block
import org.junit.Assert.*
import org.junit.Test
import java.util.Date

class FlowTextTest {
    private val now = Date(100)
    private fun block(id: String, text: String, key: String = id) = Block(id = id, pageId = "page", text = text,
        orderKey = key, createdAt = Date(1), updatedAt = Date(1))

    @Test fun unchangedSourceDoesNotResplitExistingMultilineBlock() {
        val old = listOf(block("a", "first\n\nsecond"))
        assertEquals(FlowText.Plan(emptyList(), emptyList()), FlowText.reconcile("page", old, old,
            "first\n\nsecond", now, "device"))
    }

    @Test fun paragraphEditsRetainIdsAndKeysWhileUntouchedRemoteTextSurvives() {
        val old = listOf(block("a", "first"), block("b", "second"))
        val latest = listOf(old[0], old[1].copy(text = "new remote second"))
        val plan = FlowText.reconcile("page", latest, old, "edited first\n\nsecond", now, "device")
        assertEquals(listOf("a"), plan.blocks.map { it.id })
        assertEquals("a", plan.blocks.single().orderKey)
        assertEquals("edited first", plan.blocks.single().text)
        assertTrue(plan.removedIds.isEmpty())
    }

    @Test fun duplicateAndMovedParagraphsRetainDistinctOriginalIds() {
        val old = listOf(block("a", "same"), block("b", "same"), block("c", "last"))
        val plan = FlowText.reconcile("page", old, old, "last\n\nsame\n\nsame", now, "device")
        assertEquals(listOf("c", "a", "b"), plan.blocks.map { it.id })
        assertEquals(3, plan.blocks.map { it.id }.distinct().size)
        assertTrue(plan.removedIds.isEmpty())
    }

    @Test fun topologyChangePreservesNonTextBoundaryAndPositionedAnnotation() {
        val a = block("a", "first", "")
        val image = block("image", "", "").copy(kind = "image")
        val b = block("z", "after", "")
        val positioned = block("box", "annotation").copy(x = 10, y = 20)
        val plan = FlowText.reconcile("page", listOf(a, image, b, positioned), listOf(a),
            "first\n\nnew paragraph", now, "device", newId = { "fresh" })
        assertEquals(listOf("a", "fresh", "image", "z"), plan.blocks.map { it.id })
        assertFalse(plan.blocks.any { it.id == "box" })
        assertEquals("annotation", positioned.text)
        assertEquals(10, positioned.x)
        assertTrue(plan.blocks.zipWithNext().all { (x, y) -> x.orderKey < y.orderKey })
    }

    @Test fun deletedIdsAreNeverReusedAndFencedBlankLinesStayOneBlock() {
        val old = listOf(block("retired", "old"))
        val plan = FlowText.reconcile("page", emptyList(), old, "```\na\n\nb\n```", now, "device",
            retiredIds = setOf("retired"), newId = { "fresh" })
        assertEquals(listOf("fresh"), plan.blocks.map { it.id })
        assertEquals("```\na\n\nb\n```", plan.blocks.single().text)
    }

    @Test fun allNonTextKindsInterruptEditableRunsAndHalfGeometryIsFlowing() {
        val rows = listOf(block("a", "one"), block("b", "two").copy(kind = "future"),
            block("c", "three").copy(x = 10), block("d", "positioned").copy(x = 0, y = 0))
        val sections = FlowText.sections(rows)
        assertEquals(3, sections.size)
        assertEquals(listOf("a"), (sections[0] as FlowText.Section.Text).blocks.map { it.id })
        assertEquals("b", (sections[1] as FlowText.Section.Content).block.id)
        assertEquals(listOf("c"), (sections[2] as FlowText.Section.Text).blocks.map { it.id })
    }
}
