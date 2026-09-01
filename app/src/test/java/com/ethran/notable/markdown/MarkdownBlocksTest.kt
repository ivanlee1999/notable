package com.ethran.notable.markdown

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives `couch-sync-vectors/markdown-blocks.json` — the same file bopa's `MarkdownBlocksTests`
 * runs.
 *
 * Block boundaries decide block ids, and ids decide what the merge treats as the same paragraph. A
 * splitting rule that lands in only one app therefore does not merely render differently: it
 * duplicates the user's paragraphs on the next sync. This fails when the two drift.
 */
class MarkdownBlocksTest {

    @Serializable
    private data class CaseFile(val version: Int, val cases: List<Case>)

    @Serializable
    private data class Case(
        val name: String,
        val why: String = "",
        val source: String,
        val blocks: List<String>,
    )

    private val cases: List<Case> by lazy {
        val stream = checkNotNull(
            javaClass.classLoader?.getResourceAsStream("couch-sync-vectors/markdown-blocks.json")
        ) { "markdown-blocks.json is missing from the test resources" }
        Json { ignoreUnknownKeys = true }
            .decodeFromString<CaseFile>(stream.bufferedReader().readText())
            .cases
    }

    @Test
    fun `the case file is present and not empty`() {
        assertTrue(cases.isNotEmpty())
    }

    @Test
    fun `every case splits as agreed`() {
        for (case in cases) {
            assertEquals("${case.name}: ${case.why}", case.blocks, MarkdownBlocks.split(case.source))
        }
    }

    /**
     * The property that makes a page exportable as a `.md` file and readable back unchanged. It
     * holds because no block contains a blank line outside a fence, none begins or ends with one,
     * and an unclosed fence can only occur in the final block.
     */
    @Test
    fun `joining and re-splitting is identity`() {
        for (case in cases) {
            assertEquals(
                "${case.name}: joining then splitting changed the blocks",
                case.blocks,
                MarkdownBlocks.split(MarkdownBlocks.join(case.blocks)),
            )
        }
    }

    /**
     * The same property over documents the case file does not name — every pair of cases, glued
     * together — so the rule is exercised past the examples someone thought to write down.
     *
     * Skipping a left-hand document whose last block leaves a fence open is not the test dodging an
     * awkward case; it is the precondition [MarkdownBlocks.join] documents.
     */
    @Test
    fun `joining and re-splitting is identity for assembled documents`() {
        val safe = cases.filter { it.blocks.isEmpty() || !MarkdownBlocks.leavesFenceOpen(it.blocks.last()) }
        for (left in safe) {
            for (right in cases) {
                val blocks = left.blocks + right.blocks
                assertEquals(
                    "${left.name} + ${right.name} did not survive a join and re-split",
                    blocks,
                    MarkdownBlocks.split(MarkdownBlocks.join(blocks)),
                )
            }
        }
    }

    /**
     * The one documented limit of the round trip, asserted rather than described: a block that
     * leaves a fence open is safe last and nowhere else. An editor that reorders blocks has to
     * respect this, which is why the predicate is public.
     */
    @Test
    fun `an unclosed fence is only safe last`() {
        val open = "```\nnot closed"
        val after = "an ordinary paragraph"
        assertTrue(MarkdownBlocks.leavesFenceOpen(open))
        assertFalse(MarkdownBlocks.leavesFenceOpen(after))

        // Last: the document splits back into what it was built from.
        assertEquals(
            listOf(after, open),
            MarkdownBlocks.split(MarkdownBlocks.join(listOf(after, open))),
        )
        // Not last: the fence swallows the paragraph, and one block comes back instead of two.
        assertEquals(1, MarkdownBlocks.split(MarkdownBlocks.join(listOf(open, after))).size)
    }
}
