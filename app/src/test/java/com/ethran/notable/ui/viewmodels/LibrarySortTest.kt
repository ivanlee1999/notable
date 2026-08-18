package com.ethran.notable.ui.viewmodels

import com.ethran.notable.data.db.Folder
import com.ethran.notable.data.db.Notebook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

/**
 * Finding things in the library: matching a name, and the order the shelf is arranged in.
 *
 * The order used to be `books.reversed()` — database insertion order backwards, which is neither
 * "recent" nor "alphabetical" but an artefact — and there was no matching at all.
 */
class LibrarySortTest {

    private fun book(id: String, title: String, created: Long = 0, updated: Long = 0) =
        Notebook(id = id, title = title, createdAt = Date(created), updatedAt = Date(updated))

    private fun folder(id: String, title: String, updated: Long = 0) =
        Folder(id = id, title = title, updatedAt = Date(updated))

    // MARK: Matching

    @Test
    fun `matching ignores case and accents and is not anchored`() {
        assertTrue(LibrarySort.matches("Field Notes", "notes"))
        assertTrue(LibrarySort.matches("Résumé drafts", "resume"))
        assertTrue(LibrarySort.matches("Term 2 Physics", "physics"))
        assertFalse(LibrarySort.matches("Field Notes", "chemistry"))
    }

    /** An empty query is not a filter — it is the absence of one. */
    @Test
    fun `an empty query matches everything`() {
        assertTrue(LibrarySort.matches("Anything", ""))
        assertTrue(LibrarySort.matches("Anything", "   "))
    }

    // MARK: Sorting

    /**
     * "Last edited" reads the later of the notebook's envelope clock and its newest page clock:
     * ink lands on pages and deliberately no longer bumps the envelope (the merge decides
     * renames and moves by it), so a notebook someone just drew in must float on its pages.
     */
    @Test
    fun `last edited reads the page clocks, not only the envelope`() {
        val stale = book("a", "Older", updated = 1_000)
        val fresh = book("b", "Newer", updated = 2_000)

        val sorted = LibrarySort.notebooks(
            listOf(stale, fresh), LibrarySortOrder.UPDATED, descending = true,
            lastEdited = mapOf("a" to Date(3_000)),
        )

        assertEquals(listOf("a", "b"), sorted.map { it.id })
    }

    /** A page clock older than the envelope must not drag a freshly renamed notebook down. */
    @Test
    fun `the envelope still counts when it is the later clock`() {
        val renamed = book("a", "Renamed just now", updated = 5_000)
        val other = book("b", "Untouched", updated = 2_000)

        val sorted = LibrarySort.notebooks(
            listOf(renamed, other), LibrarySortOrder.UPDATED, descending = true,
            lastEdited = mapOf("a" to Date(1_000), "b" to Date(1_000)),
        )

        assertEquals(listOf("a", "b"), sorted.map { it.id })
    }

    /**
     * The bug this guards is code-point ordering, which files "Éclair" *after* "zebra" because
     * U+00E9 is greater than 'z'. Asserted as "the accented title does not fall off the end"
     * rather than as one exact sequence: the collator follows the platform's default locale, and
     * a few locales legitimately file accented letters somewhere other than beside their base.
     */
    @Test
    fun `an accented title does not sort past the end of the alphabet`() {
        val books = listOf(
            book("1", "zebra"), book("2", "Éclair"), book("3", "apple")
        )

        val ascending =
            LibrarySort.notebooks(books, LibrarySortOrder.TITLE, descending = false).map { it.title }

        assertEquals("apple", ascending.first())
        assertEquals("zebra", ascending.last())
        assertEquals(listOf("Éclair"), ascending.drop(1).dropLast(1))
    }

    @Test
    fun `title order reverses`() {
        val books = listOf(book("1", "zebra"), book("3", "apple"))

        assertEquals(
            listOf("apple", "zebra"),
            LibrarySort.notebooks(books, LibrarySortOrder.TITLE, descending = false).map { it.title }
        )
        assertEquals(
            listOf("zebra", "apple"),
            LibrarySort.notebooks(books, LibrarySortOrder.TITLE, descending = true).map { it.title }
        )
    }

    @Test
    fun `each date order reads its own field`() {
        val books = listOf(
            book("1", "old edit new", created = 1_000, updated = 9_000),
            book("2", "new edit old", created = 5_000, updated = 2_000),
        )

        assertEquals(
            listOf("1", "2"),
            LibrarySort.notebooks(books, LibrarySortOrder.UPDATED, descending = true).map { it.id }
        )
        assertEquals(
            listOf("2", "1"),
            LibrarySort.notebooks(books, LibrarySortOrder.CREATED, descending = true).map { it.id }
        )
    }

    /** Two notebooks edited in the same second must not swap places between launches. */
    @Test
    fun `equal timestamps still order stably`() {
        val books = listOf(book("b", "Beta", updated = 5_000), book("a", "Alpha", updated = 5_000))

        assertEquals(
            listOf("a", "b"),
            LibrarySort.notebooks(books, LibrarySortOrder.UPDATED, descending = false).map { it.id }
        )
    }

    @Test
    fun `folders sort by the same rules`() {
        val folders = listOf(folder("1", "zebra"), folder("2", "apple"))

        assertEquals(
            listOf("apple", "zebra"),
            LibrarySort.folders(folders, LibrarySortOrder.TITLE, descending = false).map { it.title }
        )
    }

    /** A value from a newer build must fall back rather than throw. */
    @Test
    fun `an unknown stored order falls back to last edited`() {
        assertEquals(LibrarySortOrder.UPDATED, LibrarySortOrder.fromKeyOrDefault("FAVOURITES"))
        assertEquals(LibrarySortOrder.UPDATED, LibrarySortOrder.fromKeyOrDefault(null))
        assertEquals(LibrarySortOrder.TITLE, LibrarySortOrder.fromKeyOrDefault("TITLE"))
    }
}
