package com.ethran.notable.editor.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The markdown subset a text box renders.
 *
 * Nothing here is pinned by a conformance vector — rendering is deliberately each app's own
 * business (protocol §3.3.1). What these defend is that the *same grammar* is implemented as the
 * iPad's `TextBoxLayoutTests`, because a note that reads differently on the two devices is a note
 * the user cannot trust either copy of. Pure JVM: no `android.*` in sight.
 */
class MarkdownTextTest {

    private fun spans(source: String) = MarkdownText.parse(source)[0].spans
    private fun kind(source: String) = MarkdownText.parse(source)[0].kind
    private fun plain(source: String) = MarkdownText.parse(source)[0].plainText

    // ---- Block level ----

    @Test
    fun `heading levels are recognized`() {
        assertEquals(MarkdownText.LineKind.Heading(1), kind("# Title"))
        assertEquals(MarkdownText.LineKind.Heading(2), kind("## Title"))
        assertEquals(MarkdownText.LineKind.Heading(3), kind("### Title"))
        assertEquals("Title", plain("# Title"))
    }

    @Test
    fun `four hashes is not a heading`() {
        assertEquals(MarkdownText.LineKind.Body, kind("#### Title"))
        assertEquals("#### Title", plain("#### Title"))
    }

    @Test
    fun `a hash without a space is not a heading`() {
        assertEquals(MarkdownText.LineKind.Body, kind("#hashtag"))
    }

    @Test
    fun `bullets and numbers`() {
        assertEquals(MarkdownText.LineKind.Bullet, kind("- milk"))
        assertEquals(MarkdownText.LineKind.Bullet, kind("* milk"))
        assertEquals(MarkdownText.LineKind.Bullet, kind("+ milk"))
        assertEquals("milk", plain("- milk"))
        assertEquals(MarkdownText.LineKind.Numbered(1), kind("1. first"))
        // The source's own number is kept: renumbering a list that starts at three would edit
        // what the user typed.
        assertEquals(MarkdownText.LineKind.Numbered(3), kind("3. third"))
        assertEquals("third", plain("3. third"))
    }

    @Test
    fun `blank lines survive as empty body lines`() {
        val lines = MarkdownText.parse("one\n\ntwo")
        assertEquals(3, lines.size)
        assertEquals(MarkdownText.LineKind.Body, lines[1].kind)
        assertEquals("", lines[1].plainText)
    }

    @Test
    fun `carriage returns are normalized`() {
        assertEquals(2, MarkdownText.parse("a\r\nb").size)
        assertEquals(2, MarkdownText.parse("a\rb").size)
    }

    // ---- Inline level ----

    @Test
    fun `bold and italic`() {
        val bold = spans("say **loudly** now")
        assertEquals(listOf("say ", "loudly", " now"), bold.map { it.text })
        assertTrue(MarkdownText.SpanTraits.BOLD in bold[1].traits)

        val italic = spans("say *softly* now")
        assertEquals(listOf("say ", "softly", " now"), italic.map { it.text })
        assertTrue(MarkdownText.SpanTraits.ITALIC in italic[1].traits)

        assertTrue(MarkdownText.SpanTraits.BOLD in spans("__b__")[0].traits)
        assertTrue(MarkdownText.SpanTraits.ITALIC in spans("_i_")[0].traits)
    }

    @Test
    fun `code is literal inside`() {
        val runs = spans("run `a **b** c` now")
        assertEquals(listOf("run ", "a **b** c", " now"), runs.map { it.text })
        assertTrue(MarkdownText.SpanTraits.CODE in runs[1].traits)
    }

    @Test
    fun `a link keeps its text and drops its destination`() {
        val runs = spans("see [the docs](https://example.com) please")
        assertEquals(listOf("see ", "the docs", " please"), runs.map { it.text })
        assertTrue(MarkdownText.SpanTraits.LINK in runs[1].traits)
    }

    @Test
    fun `an unmatched marker is literal`() {
        // A lone asterisk mid-sentence is a character somebody typed, not emphasis running to
        // the end of the line.
        assertEquals(listOf("2 * 3 = 6"), spans("2 * 3 = 6").map { it.text })
        assertFalse(MarkdownText.SpanTraits.ITALIC in spans("2 * 3 = 6")[0].traits)
    }

    @Test
    fun `a backslash escapes a marker`() {
        val runs = spans("""literal \*stars\* here""")
        assertEquals(listOf("literal *stars* here"), runs.map { it.text })
        assertFalse(MarkdownText.SpanTraits.ITALIC in runs[0].traits)
    }

    @Test
    fun `nested emphasis combines traits`() {
        val runs = spans("**bold *and italic* here**")
        assertTrue(runs.all { MarkdownText.SpanTraits.BOLD in it.traits })
        assertTrue(
            runs.any {
                MarkdownText.SpanTraits.ITALIC in it.traits && it.text == "and italic"
            }
        )
    }
}
