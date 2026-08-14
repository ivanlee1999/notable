package com.ethran.notable.sync.couch

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Drives `couch-sync-vectors/vectors.json` — the same file bopa's `CouchMergeVectorTests` runs.
 * The resource under `app/src/test/resources` is a byte-identical copy of the canonical file in
 * bopa's `docs/`; a merge-rule change that lands in only one app fails here.
 */
class MergeTest {

    @Serializable
    private data class VectorFile(
        val version: Int,
        val vectors: List<Vector>,
    )

    /** `a`/`b`/`expected` stay raw until `kind` says which document type to decode them as. */
    @Serializable
    private data class Vector(
        val name: String,
        val kind: String,
        val why: String? = null,
        val a: JsonObject,
        val b: JsonObject,
        val expected: JsonObject,
    )

    private fun loadVectors(): List<Vector> {
        val stream = requireNotNull(
            javaClass.getResourceAsStream("/couch-sync-vectors/vectors.json")
        ) { "couch-sync-vectors/vectors.json missing from test resources" }
        val text = stream.bufferedReader().use { it.readText() }
        return couchJson.decodeFromString(VectorFile.serializer(), text).vectors
    }

    @Test
    fun vector_file_is_present_and_covers_every_document_kind() {
        val vectors = loadVectors()
        assertTrue("vector file is empty", vectors.isNotEmpty())
        // Every merge rule with a branch of its own should have at least one vector.
        assertEquals(setOf("page", "notebook", "folder"), vectors.map { it.kind }.toSet())
    }

    @Test
    fun vectors_merge_commutatively_and_idempotently_to_expected() {
        for (vector in loadVectors()) {
            when (vector.kind) {
                "page" -> check(vector, CouchPage.serializer(), CouchMerge::mergePage)
                "notebook" -> check(vector, CouchNotebook.serializer(), CouchMerge::mergeNotebook)
                "folder" -> check(vector, CouchFolder.serializer(), CouchMerge::mergeFolder)
                else -> throw AssertionError("vector ${vector.name}: unknown kind ${vector.kind}")
            }
        }
    }

    /**
     * Asserts the four properties every vector must satisfy: the stated result, commutativity, and
     * idempotence against both inputs.
     */
    private fun <T> check(
        vector: Vector,
        serializer: kotlinx.serialization.KSerializer<T>,
        merge: (T, T) -> T,
    ) {
        val a = couchJson.decodeFromJsonElement(serializer, vector.a)
        val b = couchJson.decodeFromJsonElement(serializer, vector.b)
        val expected = couchJson.decodeFromJsonElement(serializer, vector.expected)

        assertEquals("${vector.name}: merge(a,b)", expected, merge(a, b))
        assertEquals("${vector.name}: merge(b,a) — not commutative", expected, merge(b, a))
        assertEquals(
            "${vector.name}: merge(expected,a) — not idempotent", expected, merge(expected, a)
        )
        assertEquals(
            "${vector.name}: merge(expected,b) — not idempotent", expected, merge(expected, b)
        )
    }

    /**
     * The Swift decoder falls `updatedAt` back to `createdAt`; kotlinx cannot express that as a
     * constructor default, so the models normalize it in an `init` block. Guard that the generated
     * deserializer really runs those blocks — if it ever stopped, lenient documents would decode
     * with an empty `updatedAt`, which compares as [Long.MIN_VALUE] and silently loses every merge.
     */
    @Test
    fun lenient_decoding_falls_updated_at_back_to_created_at() {
        val page = couchJson.decodeFromString(
            CouchPage.serializer(),
            """{"type":"page","createdAt":"2026-08-10T06:00:00Z",""" +
                """"strokes":[{"id":"s1","createdAt":"2026-08-10T06:01:00Z"}]}""",
        )
        assertEquals("2026-08-10T06:00:00Z", page.updatedAt)
        assertEquals("2026-08-10T06:01:00Z", page.strokes[0].updatedAt)
        // The remaining Swift `decodeIfPresent` fallbacks come through as plain defaults.
        assertEquals("BALLPEN", page.strokes[0].pen)
        assertEquals(-16_777_216, page.strokes[0].color)
        assertEquals("blank", page.background)
    }

    // region Properties the vector file does not enumerate

    /** Deterministic pseudo-random source: a failure here has to be reproducible. */
    private class Rng(private var state: Long) {
        fun next(bound: Int): Int {
            state = state * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L
            return ((state ushr 33) % bound).toInt()
        }
    }

    /** Fixed epoch so runs are reproducible. */
    private fun stamp(offsetSeconds: Int): String =
        Instant.ofEpochMilli(1_770_000_000_000L + offsetSeconds * 1000L).toString()

    private fun randomPage(rng: Rng, deviceId: String): CouchPage {
        val strokes = ArrayList<CouchStroke>()
        repeat(rng.next(6)) {
            val n = rng.next(8)
            strokes.add(
                CouchStroke(
                    id = "s$n", createdAt = stamp(n), updatedAt = stamp(n), deviceId = deviceId,
                    pen = "BALLPEN", color = -16_777_216, size = 3f,
                    top = 0f, bottom = 1f, left = 0f, right = 1f, pointsData = "AAA=",
                )
            )
        }
        val tombs = ArrayList<CouchTombstone>()
        repeat(rng.next(4)) {
            tombs.add(CouchTombstone(id = "s${rng.next(8)}", deletedAt = stamp(rng.next(30))))
        }
        return CouchPage(
            notebookId = "nb", background = if (rng.next(2) == 0) "blank" else "grid",
            // Sometimes null: an unnamed page is the common case, and it has to survive a merge
            // against a named one without either side depending on argument order.
            title = if (rng.next(3) == 0) null else "page ${rng.next(4)}",
            strokes = strokes, deletedStrokes = tombs,
            createdAt = stamp(0), updatedAt = stamp(rng.next(60)), updatedBy = deviceId,
        )
    }

    /**
     * A notebook carrying bookmarks and an outline, both of which the merge has to reconcile
     * without a common ancestor. Page ids are drawn from a small pool so the two sides collide
     * often — a generator whose documents never overlap would exercise none of the interesting
     * cases.
     */
    private fun randomNotebook(rng: Rng, deviceId: String): CouchNotebook {
        val pageIds = LinkedHashSet<String>()
        repeat(rng.next(6)) { pageIds.add("p${rng.next(8)}") }
        val bookmarks = ArrayList<CouchBookmark>()
        repeat(rng.next(5)) {
            bookmarks.add(
                CouchBookmark(
                    pageId = "p${rng.next(8)}", updatedAt = stamp(rng.next(60)),
                    removed = rng.next(3) == 0,
                )
            )
        }
        val outline = ArrayList<CouchOutlineEntry>()
        repeat(rng.next(5)) {
            outline.add(
                CouchOutlineEntry(
                    id = "e${rng.next(8)}", pageId = "p${rng.next(8)}",
                    title = "section ${rng.next(4)}", depth = rng.next(3),
                    updatedAt = stamp(rng.next(60)), removed = rng.next(4) == 0,
                )
            )
        }
        val tombs = ArrayList<CouchTombstone>()
        repeat(rng.next(3)) {
            tombs.add(CouchTombstone(id = "p${rng.next(8)}", deletedAt = stamp(rng.next(30))))
        }
        return CouchNotebook(
            title = "book ${rng.next(3)}", pageIds = pageIds.toList(), deletedPageIds = tombs,
            bookmarks = bookmarks, outline = outline,
            createdAt = stamp(0), updatedAt = stamp(rng.next(60)), updatedBy = deviceId,
        )
    }

    /**
     * The property the whole design rests on: two devices that were both offline reconcile to the
     * same notebook whichever way round they merge, and re-merging changes nothing.
     */
    @Test
    fun notebook_merge_is_commutative_and_idempotent_over_random_notebooks() {
        val rng = Rng(0xB00C)
        for (iteration in 0 until 300) {
            val a = randomNotebook(rng, "ipad")
            val b = randomNotebook(rng, "boox")
            val ab = CouchMerge.mergeNotebook(a, b)
            assertEquals("iteration $iteration: not commutative", ab, CouchMerge.mergeNotebook(b, a))
            assertEquals(
                "iteration $iteration: not idempotent in a", ab, CouchMerge.mergeNotebook(ab, a)
            )
            assertEquals(
                "iteration $iteration: not idempotent in b", ab, CouchMerge.mergeNotebook(ab, b)
            )
        }
    }

    /**
     * A bookmark must never point at a page the notebook no longer has, however many times the two
     * sides re-merge.
     *
     * Asserted for bookmarks only. The outline cannot be filtered this way and stay idempotent —
     * see the note in [CouchMerge.mergeNotebook] — so a dangling outline entry is the reader's to
     * skip, not the merge's to remove.
     */
    @Test
    fun bookmarks_never_outlive_their_page() {
        val rng = Rng(0x0B17)
        repeat(200) {
            val a = randomNotebook(rng, "ipad")
            val b = randomNotebook(rng, "boox")
            val merged = CouchMerge.mergeNotebook(a, b)
            val removed = merged.deletedPageIds.map { it.id }.toSet()
            assertTrue(merged.bookmarks.none { it.pageId in removed })
            assertTrue(CouchMerge.mergeNotebook(merged, a).bookmarks.none { it.pageId in removed })
        }
    }

    /**
     * Outline entries carry no position field, so the only thing keeping two devices from rendering
     * the table of contents in different orders is that the merge is deterministic.
     */
    @Test
    fun outline_order_is_identical_in_both_argument_orders() {
        val rng = Rng(0x0F17)
        for (iteration in 0 until 200) {
            val a = randomNotebook(rng, "ipad")
            val b = randomNotebook(rng, "boox")
            assertEquals(
                "iteration $iteration: outline order depends on argument order",
                CouchMerge.mergeNotebook(a, b).outline.map { it.id },
                CouchMerge.mergeNotebook(b, a).outline.map { it.id },
            )
        }
    }

    @Test
    fun merge_is_commutative_and_idempotent_over_random_pages() {
        val rng = Rng(0x5EED)
        for (iteration in 0 until 300) {
            val a = randomPage(rng, "ipad")
            val b = randomPage(rng, "boox")
            val ab = CouchMerge.mergePage(a, b)
            assertEquals("iteration $iteration: not commutative", ab, CouchMerge.mergePage(b, a))
            assertEquals("iteration $iteration: not idempotent in a", ab, CouchMerge.mergePage(ab, a))
            assertEquals("iteration $iteration: not idempotent in b", ab, CouchMerge.mergePage(ab, b))
        }
    }

    /**
     * A tombstoned stroke must never reappear, however many times the two sides re-merge — this is
     * what makes "erase on the BOOX" stick on the iPad.
     */
    @Test
    fun erasure_is_absorbing() {
        val rng = Rng(0xC0FFEE)
        repeat(200) {
            val a = randomPage(rng, "ipad")
            val b = randomPage(rng, "boox")
            val merged = CouchMerge.mergePage(a, b)
            val removed = merged.deletedStrokes.map { it.id }.toSet()
            assertTrue(merged.strokes.none { it.id in removed })
            // Re-merging with the pre-erase side must not resurrect anything.
            val again = CouchMerge.mergePage(merged, a)
            assertTrue(again.strokes.none { it.id in removed })
        }
    }

    /**
     * A page's name follows the same last-writer-wins rule as its other scalars, in both argument
     * orders. Renaming on one device and leaving the page untouched on the other must land on the
     * new name — including when the rename is what *clears* the name.
     */
    @Test
    fun later_page_rename_wins_in_either_order() {
        val unnamed = CouchPage(
            notebookId = "nb", title = null,
            createdAt = "2026-08-10T06:00:00Z", updatedAt = "2026-08-10T06:00:00Z",
            updatedBy = "boox",
        )
        val renamed = unnamed.copy(
            title = "Shopping list", updatedAt = "2026-08-10T06:05:00Z", updatedBy = "ipad",
        )

        assertEquals("Shopping list", CouchMerge.mergePage(unnamed, renamed).title)
        assertEquals("Shopping list", CouchMerge.mergePage(renamed, unnamed).title)

        val cleared = renamed.copy(
            title = null, updatedAt = "2026-08-10T06:10:00Z", updatedBy = "boox",
        )
        assertNull(CouchMerge.mergePage(renamed, cleared).title)
        assertNull(CouchMerge.mergePage(cleared, renamed).title)
    }

    /**
     * The tiebreak ends in `aScalarKey >= bScalarKey`, so any scalar the merge picks but the key
     * omits makes both argument orders "win" and the result depend on which document was passed
     * first. Two pages identical except for their name, written in the same millisecond by the same
     * device, are the case that catches it.
     */
    @Test
    fun pages_differing_only_by_title_still_merge_commutatively() {
        val one = CouchPage(
            notebookId = "nb", title = "Groceries",
            createdAt = "2026-08-10T06:00:00Z", updatedAt = "2026-08-10T06:00:00Z",
            updatedBy = "boox",
        )
        val other = one.copy(title = "Shopping list")

        assertEquals(CouchMerge.mergePage(one, other), CouchMerge.mergePage(other, one))
    }

    @Test
    fun timestamps_are_compared_chronologically_not_lexicographically() {
        // "…33.871Z" sorts before "…33Z" as a string while being later in time.
        val fractional = "2026-08-10T06:12:33.871Z"
        val whole = "2026-08-10T06:12:33Z"
        assertTrue("precondition: the strings really do sort this way", fractional < whole)
        assertTrue(CouchMerge.millis(fractional) > CouchMerge.millis(whole))
    }

    @Test
    fun unparseable_timestamp_loses_rather_than_crashing() {
        assertEquals(Long.MIN_VALUE, CouchMerge.millis("not a date"))
        val good = CouchFolder(
            title = "good", createdAt = "2026-08-10T06:00:00Z",
            updatedAt = "2026-08-10T06:05:00Z", updatedBy = "ipad",
        )
        val bad = CouchFolder(
            title = "bad", createdAt = "2026-08-10T06:00:00Z",
            updatedAt = "garbage", updatedBy = "boox",
        )
        assertEquals("good", CouchMerge.mergeFolder(good, bad).title)
        assertEquals("good", CouchMerge.mergeFolder(bad, good).title)
    }

    @Test
    fun deletion_resolution_resurrects_only_a_strictly_later_edit() {
        // An edit after the delete resurrects; an edit before it does not.
        assertEquals(
            CouchMerge.DeletionOutcome.RESURRECT,
            CouchMerge.resolveDeletion("2026-08-10T06:10:00Z", "2026-08-10T06:05:00Z"),
        )
        assertEquals(
            CouchMerge.DeletionOutcome.APPLY_DELETION,
            CouchMerge.resolveDeletion("2026-08-10T06:01:00Z", "2026-08-10T06:05:00Z"),
        )
        // Equal instants keep the deletion: a delete that observed the edit is the later intent.
        assertEquals(
            CouchMerge.DeletionOutcome.APPLY_DELETION,
            CouchMerge.resolveDeletion("2026-08-10T06:05:00Z", "2026-08-10T06:05:00Z"),
        )
        // No tombstone at all: nothing to apply.
        assertEquals(
            CouchMerge.DeletionOutcome.RESURRECT,
            CouchMerge.resolveDeletion("2026-08-10T06:05:00Z", null),
        )
    }

    // endregion
}
