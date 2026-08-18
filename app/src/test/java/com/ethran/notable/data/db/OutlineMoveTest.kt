package com.ethran.notable.data.db

import com.ethran.notable.sync.couch.CouchOutlineEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The outline move, as pure list arithmetic ([movedOutline]) so the index-space rule is testable
 * without a database.
 *
 * The rule: a move addresses the entries the reader can see. The stored outline also holds
 * removed entries and *dangling* ones — entries whose page id is no longer in `pageIds`,
 * deliberately kept for merge idempotence (protocol §5.2.2) and hidden by the outline tab. The
 * old API took indices, the UI counted its filtered list, the repository counted a differently
 * filtered one, and one hidden entry shifted every index after it: "move up" reordered the wrong
 * row. Identity plus direction cannot drift that way.
 */
class OutlineMoveTest {

    private val pageIds = listOf("p1", "p2", "p3")

    private fun entry(id: String, pageId: String, removed: Boolean = false) =
        CouchOutlineEntry(id = id, pageId = pageId, title = id, removed = removed)

    /** A dangling entry hidden between two visible ones — the shape that broke index moves. */
    private val withDangling = listOf(
        entry("a", "p1"),
        entry("dangling", "no-such-page"),
        entry("b", "p2"),
    )

    @Test
    fun `moving the lower visible entry up swaps with the visible one above, not the dangling one`() {
        val moved = movedOutline(withDangling, pageIds, entryId = "b", direction = -1)

        assertEquals(listOf("b", "dangling", "a"), moved?.map { it.id })
    }

    @Test
    fun `the dangling entry keeps its data untouched`() {
        val moved = movedOutline(withDangling, pageIds, entryId = "b", direction = -1)!!

        assertEquals(withDangling[1], moved[1])
    }

    @Test
    fun `a removed entry between two visible ones is stepped over the same way`() {
        val outline = listOf(
            entry("a", "p1"),
            entry("tombstone", "p2", removed = true),
            entry("b", "p3"),
        )

        val moved = movedOutline(outline, pageIds, entryId = "a", direction = 1)

        assertEquals(listOf("b", "tombstone", "a"), moved?.map { it.id })
    }

    @Test
    fun `moving off either end does nothing`() {
        assertNull(movedOutline(withDangling, pageIds, entryId = "a", direction = -1))
        assertNull(movedOutline(withDangling, pageIds, entryId = "b", direction = 1))
    }

    @Test
    fun `an entry the reader cannot see cannot be moved`() {
        assertNull(movedOutline(withDangling, pageIds, entryId = "dangling", direction = 1))
        assertNull(movedOutline(withDangling, pageIds, entryId = "unknown", direction = 1))
    }

    @Test
    fun `adjacent visible entries simply swap`() {
        val outline = listOf(entry("a", "p1"), entry("b", "p2"), entry("c", "p3"))

        val moved = movedOutline(outline, pageIds, entryId = "a", direction = 1)

        assertEquals(listOf("b", "a", "c"), moved?.map { it.id })
    }
}
