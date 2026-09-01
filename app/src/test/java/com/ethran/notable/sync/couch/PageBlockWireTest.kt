package com.ethran.notable.sync.couch

import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Blocks survive a round trip through the page document, and a document written before they existed
 * still decodes.
 *
 * This is the whole of the first stage: nothing creates a block yet, and the only thing that matters
 * is that a build which meets one does not quietly destroy it. Neither app keeps unknown fields —
 * this side decodes with `ignoreUnknownKeys`, the iPad uses plain `Codable` — so a field a peer does
 * not model is a field it erases the next time it writes the document back. That has shipped as data
 * loss once already, with page titles.
 *
 * The twin of bopa's `PageBlockWireTests`. The two models must stay field-for-field identical, so
 * these two files should fail together or not at all.
 */
class PageBlockWireTest {

    private val paragraph = CouchBlock(
        id = "b1",
        kind = "md",
        orderKey = "a0",
        text = "## Groceries\n\nmilk **and** eggs",
        createdAt = "2026-09-01T10:00:00Z",
        updatedAt = "2026-09-01T10:00:00Z",
        deviceId = "ipad",
    )

    private val recording = CouchBlock(
        id = "b2",
        kind = "audio",
        orderKey = "a1",
        segments = listOf(
            CouchAudioSegment(assetId = "asset:aa", startMs = 0, durationMs = 120_000),
            CouchAudioSegment(assetId = "asset:bb", startMs = 120_000, durationMs = 90_000),
        ),
        startedAt = "2026-09-01T10:05:00Z",
        createdAt = "2026-09-01T10:05:00Z",
        updatedAt = "2026-09-01T10:08:30Z",
        deviceId = "boox",
    )

    private fun page(blocks: List<CouchBlock>) = CouchPage(
        notebookId = "nb1",
        blocks = blocks,
        deletedBlocks = listOf(CouchTombstone("b0", "2026-09-01T09:00:00Z")),
        createdAt = "2026-09-01T09:00:00Z",
        updatedAt = "2026-09-01T10:08:30Z",
        updatedBy = "ipad",
    )

    @Test
    fun `a page document round-trips its blocks`() {
        val original = page(listOf(paragraph, recording))
        val decoded = couchJson.decodeFromString<CouchPage>(couchJson.encodeToString(original))
        assertEquals(original, decoded)
    }

    /**
     * Every page in both libraries today. Absent must read as empty, not as a decode failure — §6.5
     * would otherwise conflict-copy the entire library into "Unreadable sync copy" notebooks.
     */
    @Test
    fun `a page written before blocks existed decodes with none`() {
        val json = """
            {"type":"page","schema":1,"notebookId":"nb1","background":"blank",
            "backgroundType":"native","strokes":[],"deletedStrokes":[],"images":[],
            "deletedImages":[],"createdAt":"2026-09-01T09:00:00Z",
            "updatedAt":"2026-09-01T09:00:00Z","updatedBy":"boox"}
        """.trimIndent().replace("\n", "")

        val decoded = couchJson.decodeFromString<CouchPage>(json)

        assertTrue(decoded.blocks.isEmpty())
        assertTrue(decoded.deletedBlocks.isEmpty())
    }

    /**
     * A block whose `kind` this build has never heard of is carried through untouched, so a fifth
     * kind can ship on one app before the other without the pages that use it being quarantined.
     */
    @Test
    fun `an unrecognized kind is carried verbatim`() {
        val exotic = CouchBlock(
            id = "b9",
            kind = "video",
            orderKey = "z",
            createdAt = "2026-09-01T09:00:00Z",
            updatedAt = "2026-09-01T09:00:00Z",
        )
        val decoded = couchJson.decodeFromString<CouchPage>(
            couchJson.encodeToString(page(listOf(exotic)))
        )
        assertEquals("video", decoded.blocks.single().kind)
    }

    @Test
    fun `coordinates decide flowing versus positioned, and a half-declared block flows`() {
        assertTrue(paragraph.isFlowing)
        assertFalse(paragraph.copy(x = 100, y = 200).isFlowing)
        assertTrue(paragraph.copy(x = 100).isFlowing)
    }

    /** What the push ordering and the asset collector both read. */
    @Test
    fun `a block reports the assets its bytes live in`() {
        assertEquals(listOf("asset:aa", "asset:bb"), recording.referencedAssetIds)
        assertEquals(emptyList<String>(), paragraph.referencedAssetIds)
        assertEquals(
            listOf("asset:cc"),
            paragraph.copy(kind = "image", imageAssetId = "asset:cc").referencedAssetIds,
        )
    }

    /**
     * An empty key sorts first, and ties break on id.
     *
     * Sorted here with Kotlin's own string comparison, which is UTF-16; the merge will use the
     * protocol's UTF-8 byte order (§4). The two agree for every key over the fractional index's
     * ASCII alphabet, which is what this pins — the alphabet is chosen so that they cannot diverge.
     */
    @Test
    fun `flow order is the order key then the id`() {
        val unkeyed = paragraph.copy(id = "b0", orderKey = "")
        val a = paragraph.copy(id = "zz", orderKey = "a0")
        val b = paragraph.copy(id = "aa", orderKey = "a0")
        val late = paragraph.copy(id = "b3", orderKey = "b")

        val sorted = listOf(late, a, b, unkeyed).sortedWith(
            compareBy({ it.orderKey }, { it.id })
        )

        assertEquals(listOf("b0", "aa", "zz", "b3"), sorted.map { it.id })
    }
}
