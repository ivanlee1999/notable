package com.ethran.notable.sync.couch

import org.junit.Assert.*
import org.junit.Test

class TextVersionsTest {
    private fun block(text: String?, clock: Int, device: String = "boox", positioned: Boolean = false) = CouchBlock(
        id = "paragraph", kind = "md", orderKey = "a", text = text,
        x = if (positioned) 10 else null, y = if (positioned) 20 else null,
        createdAt = "2026-11-12T10:00:00Z", updatedAt = "2026-11-12T10:00:0${clock}Z", deviceId = device,
    )
    private fun page(blocks: List<CouchBlock>, removed: List<String> = emptyList()) = CouchPage(
        notebookId = "book", blocks = blocks,
        deletedBlocks = removed.map { CouchTombstone(it, "2026-11-12T10:00:09Z") },
        createdAt = "2026-11-12T10:00:00Z", updatedAt = "2026-11-12T10:00:09Z", updatedBy = "boox",
    )

    @Test fun exactSourceAndGuard() {
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", TextVersions.fingerprint("hello"))
        assertFalse(TextVersions.sameSource("caf\u00e9", "cafe\u0301"))
        assertTrue(TextVersions.conflicts("base", "remote", "mine"))
        assertFalse(TextVersions.conflicts("base", "base", "mine"))
        assertFalse(TextVersions.conflicts("base", "mine", "mine"))
        assertEquals("source:with:colons", TextVersions.sourceId(TextVersions.versionId("source:with:colons", "hello")))
        assertNull(TextVersions.sourceId("text-version:missing:hash"))
    }

    @Test fun unchangedMergesDoNotCreateHistory() {
        val one = block("source", 1); val two = block("source", 2)
        assertEquals(listOf(two), CouchMerge.mergePage(page(listOf(one)), page(listOf(two))).blocks)
    }

    @Test fun absentAndEmptySourcesHaveADeterministicWinner() {
        val absent = page(listOf(block(null, 1)))
        val empty = page(listOf(block("", 1)))
        val merged = CouchMerge.mergePage(absent, empty)
        assertEquals(merged, CouchMerge.mergePage(empty, absent))
        assertEquals("", merged.blocks.single { it.kind == "md" }.text)
        assertEquals(merged, CouchMerge.mergePage(merged, absent))
    }

    @Test fun divergentSourcesRemainExactAndHidden() {
        val a = block("caf\u00e9\n\nA", 1); val b = block("cafe\u0301\n\nB", 2, "ipad")
        val result = CouchMerge.mergePage(page(listOf(a)), page(listOf(b)))
        assertEquals(listOf(b), result.blocks.filter { it.kind == "md" })
        val versions = result.blocks.filter { it.kind == TextVersions.KIND }
        assertEquals(setOf(a.text, b.text), versions.map { it.text }.toSet())
        assertTrue(versions.all { TextVersions.sourceId(it.id) == a.id })
    }

    @Test fun deletionAndVersionTombstoneAreGroupingIndependent() {
        val a = page(listOf(block("one", 1))); val b = page(listOf(block("two", 2)))
        val version = TextVersions.versionId("paragraph", "one")
        val deleted = page(emptyList(), listOf("paragraph", version))
        val left = CouchMerge.mergePage(CouchMerge.mergePage(a, b), deleted)
        val right = CouchMerge.mergePage(a, CouchMerge.mergePage(b, deleted))
        assertEquals(left, right)
        assertTrue(left.blocks.none { it.id == "paragraph" || it.id == version })
        assertTrue(left.blocks.any { it.text == "two" && it.kind == TextVersions.KIND })
    }

    @Test fun mergeAlgebraAcrossRandomTriplesAndQuadruples() {
        var value = 0x5eedUL
        fun next(upper: Int): Int {
            value = value * 6364136223846793005UL + 1442695040888963407UL
            return ((value shr 32) % upper.toULong()).toInt()
        }
        val sources = listOf(null, "", "one", "two", "caf\u00e9", "cafe\u0301", "\nA|B\n")
        repeat(1_000) { iteration ->
            val inputs = (0..<4).map {
                var candidate = block(sources[next(sources.size)], next(5), if (next(2) == 0) "boox" else "ipad", next(4) == 0)
                candidate = candidate.copy(orderKey = listOf("a", "b", "\u00e9")[next(3)])
                if (next(8) == 0) candidate = candidate.copy(kind = "future-kind")
                val removed = mutableListOf<String>()
                if (next(5) == 0) removed += candidate.id
                if (next(5) == 0) removed += TextVersions.versionId(candidate.id, sources[next(sources.size)] ?: "")
                page(if (next(5) == 0) emptyList() else listOf(candidate), removed)
            }
            val (a,b,c,d) = inputs
            val ab = CouchMerge.mergePage(a,b)
            assertEquals("commutative $iteration", ab, CouchMerge.mergePage(b,a))
            assertEquals("replay $iteration", ab, CouchMerge.mergePage(ab,a))
            assertEquals("triple $iteration", CouchMerge.mergePage(ab,c), CouchMerge.mergePage(a,CouchMerge.mergePage(b,c)))
            val canonical = CouchMerge.mergePage(CouchMerge.mergePage(ab,c),d)
            assertEquals("quad split $iteration", canonical, CouchMerge.mergePage(ab,CouchMerge.mergePage(c,d)))
            assertEquals("quad right $iteration", canonical, CouchMerge.mergePage(a,CouchMerge.mergePage(b,CouchMerge.mergePage(c,d))))
            assertEquals("quad middle $iteration", canonical, CouchMerge.mergePage(a,CouchMerge.mergePage(CouchMerge.mergePage(b,c),d)))
            assertEquals("quad mixed $iteration", canonical, CouchMerge.mergePage(CouchMerge.mergePage(a,CouchMerge.mergePage(b,c)),d))
            val removed = inputs.flatMap { it.deletedBlocks }.map { it.id }.toSet()
            val expectedSources = inputs.flatMap { it.blocks }.filter {
                val text = it.text
                it.kind == "md" && text != null && TextVersions.versionId(it.id, text) !in removed
            }.mapNotNull { it.text }.toSet()
            val preserved = canonical.blocks.filter { it.kind == "md" || it.kind == TextVersions.KIND }.mapNotNull { it.text }.toSet()
            assertTrue("source conservation $iteration", preserved.containsAll(expectedSources))
        }
    }
}
