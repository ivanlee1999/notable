package com.ethran.notable.recognition

import org.junit.Assert.*
import org.junit.Test

class TranscriptMarkdownTest {
    @Test fun mixedLayoutKeepsHeadingLevelsTaskStatesNestedIndentAndCellIdentity() {
        val source = "# Project\n\n## Tasks\n- [x] Done\n- [ ] Next\n  - detail\n1. First\n2. Second\n\n" +
            "| Item | Qty | Note |\n| :--- | ---: | :---: |\n| Milk | 2 | |\n| A \\| B | 1 | yes |"
        val blocks = TranscriptMarkdown.parse(source)
        val lines = blocks.filterIsInstance<TranscriptMarkdown.Block.Line>()
        assertEquals(listOf(1, 2), lines.mapNotNull { it.heading })
        assertEquals(true, lines.single { it.source == "Done" }.checked)
        assertEquals(false, lines.single { it.source == "Next" }.checked)
        assertEquals(2, lines.single { it.source == "detail" }.indent)
        assertEquals(listOf("1.", "2."), lines.mapNotNull { it.marker }.filter { it.first().isDigit() })
        val table = blocks.filterIsInstance<TranscriptMarkdown.Block.Table>().single()
        assertEquals(listOf("Item", "Qty", "Note"), table.header)
        assertEquals(listOf("Milk", "2", ""), table.rows[0])
        assertEquals(listOf("A \\| B", "1", "yes"), table.rows[1])
        assertTrue(source.contains("- [x] Done"))
    }
    @Test fun fencedContentStaysLiteralAndCannotBecomeTasksTablesOrHtml() {
        val code = "# literal\n- [x] literal\n| A | B |\n| --- | --- |\n<img src=https://invalid.test/image>"
        val result = TranscriptMarkdown.parse("````\n$code\n```\n````\n# After")
        assertEquals(TranscriptMarkdown.Block.Code("$code\n```"), result.first())
        assertEquals(TranscriptMarkdown.Block.Line("After", heading = 1), result.last())
        assertEquals(TranscriptMarkdown.Block.Code(code), TranscriptMarkdown.parse("~~~\n$code").single())
    }
    @Test fun codeAndEscapedPipesDoNotSplitCellsAndMalformedTablesStayReadable() {
        val table = TranscriptMarkdown.parse("A | B\n--- | ---\n`one|two` | three\nleft \\| right | end")
            .single() as TranscriptMarkdown.Block.Table
        assertEquals(listOf("`one|two`", "three"), table.rows[0])
        assertEquals(listOf("left \\| right", "end"), table.rows[1])
        val malformed = "A | B\n---\nvalue | data"
        assertEquals(malformed.split('\n'), TranscriptMarkdown.parse(malformed)
            .filterIsInstance<TranscriptMarkdown.Block.Line>().map { it.source })
    }
    @Test fun raggedRowsAndDeepListSourceArePreservedWithoutDroppingContent() {
        val table = TranscriptMarkdown.parse("| A | B |\n| --- | --- |\n| one | two | extra |\n| only |").single() as TranscriptMarkdown.Block.Table
        assertEquals(listOf("one", "two", "extra"), table.rows[0])
        assertEquals(listOf("only"), table.rows[1])
        val list = TranscriptMarkdown.parse("\t  - [X] deep\n  3) numbered").filterIsInstance<TranscriptMarkdown.Block.Line>()
        assertEquals(6, list[0].indent); assertEquals(true, list[0].checked)
        assertEquals("3)", list[1].marker)
    }
}
