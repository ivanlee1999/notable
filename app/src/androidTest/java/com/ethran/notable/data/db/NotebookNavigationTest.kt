package com.ethran.notable.data.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ethran.notable.sync.couch.CouchOutlineEntry
import com.ethran.notable.testing.TestDatabaseFactory
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Bookmarks and the outline, against a real (in-memory) Room database.
 *
 * The rules worth pinning here are the ones a peer can see, because both fields travel: an
 * un-starring has to be written down rather than dropped, and a deleted page has to take its
 * outline entries with it — the merge deliberately will not do that second one, so if this layer
 * forgets, the entry comes back from the iPad.
 *
 * Instrumentation rather than a JVM test for the same reason as [CouchOutboxTest]: Room's
 * in-memory builder needs an Android `Context` and a real SQLite.
 */
@RunWith(AndroidJUnit4::class)
class NotebookNavigationTest {
    private lateinit var db: AppDatabase
    private lateinit var books: BookRepository
    private lateinit var pages: PageRepository
    private lateinit var outbox: CouchOutboxRepository
    private lateinit var notebookId: String

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = TestDatabaseFactory.createInMemory(context)
        outbox = CouchOutboxRepository(db.couchOutboxDao())
        books = BookRepository(db.notebookDao(), db.pageDao(), outbox, db)
        pages = PageRepository(db.pageDao(), outbox, db)
        runBlocking {
            val notebook = Notebook(title = "Notes")
            books.create(notebook)
            notebookId = notebook.id
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun notebook(): Notebook = runBlocking { books.getById(notebookId)!! }

    private fun addPages(count: Int): List<String> = runBlocking {
        (0 until count).map {
            val page = Page(notebookId = notebookId)
            pages.create(page)
            books.addPage(notebookId, page.id)
            page.id
        }
    }

    private fun pageIds(): List<String> = notebook().pageIds

    // region Bookmarks

    @Test
    fun starring_a_page_shows_it_in_the_bookmark_list() = runBlocking {
        addPages(2)
        val target = pageIds()[1]

        books.setBookmark(notebookId, target, true)

        val starred = notebook().bookmarks.filterNot { it.removed }.map { it.pageId }
        assertEquals(listOf(target), starred)
    }

    /**
     * Un-starring writes a `removed` entry rather than dropping the bookmark. Dropping it would
     * leave the peer's copy as the only word on the subject, and the star would come back.
     */
    @Test
    fun unstarring_is_recorded_rather_than_dropped() = runBlocking {
        val target = pageIds()[0]
        books.setBookmark(notebookId, target, true)
        books.setBookmark(notebookId, target, false)

        val entry = notebook().bookmarks.first { it.pageId == target }
        assertTrue("the un-starring has to travel, so it must be stored", entry.removed)
    }

    @Test
    fun a_page_can_be_starred_again_after_being_unstarred() = runBlocking {
        val target = pageIds()[0]
        books.setBookmark(notebookId, target, true)
        books.setBookmark(notebookId, target, false)
        books.setBookmark(notebookId, target, true)

        assertFalse(notebook().bookmarks.first { it.pageId == target }.removed)
    }

    /** Sorted by page id, matching what the merge produces, so both writers agree byte for byte. */
    @Test
    fun bookmarks_are_stored_sorted_by_page_id() = runBlocking {
        addPages(3)
        pageIds().forEach { books.setBookmark(notebookId, it, true) }

        val stored = notebook().bookmarks.map { it.pageId }
        assertEquals(stored.sorted(), stored)
    }

    // endregion

    // region Outline

    @Test
    fun adding_entries_builds_the_outline() = runBlocking {
        addPages(1)
        books.addOutlineEntry(notebookId, pageIds()[0], "Intro")
        books.addOutlineEntry(notebookId, pageIds()[1], "Body")

        assertEquals(listOf("Intro", "Body"), notebook().outline.map { it.title })
    }

    /**
     * A page may carry more than one entry — it is how a page that closes one section and opens
     * another gets to say so, and it is why entries are keyed by their own id.
     */
    @Test
    fun a_page_may_appear_in_the_outline_twice() = runBlocking {
        val target = pageIds()[0]
        books.addOutlineEntry(notebookId, target, "End of I")
        books.addOutlineEntry(notebookId, target, "Start of II")

        val outline = notebook().outline
        assertEquals(2, outline.size)
        assertEquals(2, outline.map { it.id }.toSet().size)
    }

    /**
     * A new entry lands beside the pages it sits between, not at the end: an outline is read
     * against the notebook, so an entry for page 2 added last still belongs before page 4's.
     */
    @Test
    fun a_new_entry_is_placed_by_its_page_position() = runBlocking {
        addPages(3)
        books.addOutlineEntry(notebookId, pageIds()[3], "Later")
        books.addOutlineEntry(notebookId, pageIds()[1], "Earlier")

        assertEquals(listOf("Earlier", "Later"), notebook().outline.map { it.title })
    }

    @Test
    fun depth_is_clamped_to_what_the_format_allows() = runBlocking {
        books.addOutlineEntry(notebookId, pageIds()[0], "Deep")
        val entryId = notebook().outline.first().id

        books.updateOutlineEntry(notebookId, entryId, depth = 9)
        assertEquals(CouchOutlineEntry.MAX_DEPTH, notebook().outline.first().depth)

        books.updateOutlineEntry(notebookId, entryId, depth = -4)
        assertEquals(0, notebook().outline.first().depth)
    }

    /** Removal is a mark, not a delete, for the same reason un-starring is. */
    @Test
    fun removing_an_entry_leaves_a_tombstone() = runBlocking {
        books.addOutlineEntry(notebookId, pageIds()[0], "Intro")
        val entryId = notebook().outline.first().id

        books.removeOutlineEntry(notebookId, entryId)

        assertTrue(notebook().outline.first { it.id == entryId }.removed)
    }

    /**
     * Reordering moves the entry the reader named, one step against the entries the reader can
     * see. The stored list also holds removed entries; they keep their positions and are not
     * counted.
     */
    @Test
    fun reordering_counts_only_the_entries_the_reader_can_see() = runBlocking {
        addPages(2)
        listOf("A", "B", "C").forEachIndexed { index, title ->
            books.addOutlineEntry(notebookId, pageIds()[index], title)
        }
        books.removeOutlineEntry(notebookId, notebook().outline.first().id) // "A"
        val entryB = notebook().outline.first { it.title == "B" }.id

        books.moveOutlineEntry(notebookId, entryB, direction = 1)

        val visible = notebook().outline.filterNot { it.removed }.map { it.title }
        assertEquals(listOf("C", "B"), visible)
        // The removed entry stays exactly where it was: a move swaps the visible pair around it.
        assertEquals(listOf("A", "C", "B"), notebook().outline.map { it.title })
    }

    /**
     * The bug this API replaced: the tab hides a dangling entry (its page is gone from
     * `pageIds`), the repository's index space used to count it, and every index below it named
     * the wrong row — "move up" swapped with the invisible neighbor instead of the visible one.
     * Dangling entries are deliberately kept in the data (the merge cannot drop them and stay
     * idempotent), so the move has to step over them, not the caller.
     */
    @Test
    fun a_move_steps_over_a_dangling_entry_instead_of_swapping_with_it() = runBlocking {
        addPages(1)
        val (first, second) = pageIds()
        books.updateVerbatim(
            notebook().copy(
                outline = listOf(
                    CouchOutlineEntry(id = "a", pageId = first, title = "A"),
                    CouchOutlineEntry(id = "dangling", pageId = "no-such-page", title = "D"),
                    CouchOutlineEntry(id = "b", pageId = second, title = "B"),
                )
            )
        )

        books.moveOutlineEntry(notebookId, "b", direction = -1)

        assertEquals(
            "the lower visible entry must swap with the visible one above, not the dangling one",
            listOf("b", "dangling", "a"),
            notebook().outline.map { it.id },
        )
    }

    // endregion

    // region Deleting a page

    /**
     * The merge will not drop an outline entry whose page is gone — doing so is not idempotent —
     * so deleting the page has to mark its entries here. If it does not, the peer that still holds
     * the entry adds it straight back on the next sync.
     */
    @Test
    fun deleting_a_page_marks_its_outline_entries_removed() = runBlocking {
        addPages(1)
        val doomed = pageIds()[1]
        books.addOutlineEntry(notebookId, doomed, "Gone")
        books.addOutlineEntry(notebookId, pageIds()[0], "Stays")
        val doomedEntryId = notebook().outline.first { it.pageId == doomed }.id

        books.removePage(notebookId, doomed)

        val entry = notebook().outline.first { it.id == doomedEntryId }
        assertTrue(
            "a dropped entry would be re-added by the peer; a marked one will not be",
            entry.removed
        )
        assertFalse(notebook().outline.first { it.title == "Stays" }.removed)
    }

    @Test
    fun deleting_a_page_takes_its_bookmark_with_it() = runBlocking {
        addPages(1)
        val doomed = pageIds()[1]
        books.setBookmark(notebookId, doomed, true)

        books.removePage(notebookId, doomed)

        assertTrue(notebook().bookmarks.none { it.pageId == doomed })
    }

    // endregion

    // region Sync plumbing

    /**
     * Every one of these is a change a peer has to hear about. The repository queues inside the
     * same transaction as the write, so a starred page that never reaches the outbox is a starred
     * page that never reaches the iPad.
     */
    @Test
    fun every_navigation_edit_queues_the_notebook() = runBlocking {
        val docId = com.ethran.notable.sync.couch.CouchDocId.notebook(notebookId)
        books.addOutlineEntry(notebookId, pageIds()[0], "Intro")
        books.addOutlineEntry(notebookId, pageIds()[0], "Body")
        val entryId = notebook().outline.first().id

        for (edit in listOf<suspend () -> Unit>(
            { books.setBookmark(notebookId, pageIds()[0], true) },
            { books.updateOutlineEntry(notebookId, entryId, title = "Renamed") },
            { books.updateOutlineEntry(notebookId, entryId, depth = 1) },
            { books.moveOutlineEntry(notebookId, entryId, direction = 1) },
            { books.removeOutlineEntry(notebookId, entryId) },
        )) {
            outbox.clear(docId)
            edit()
            assertTrue(
                "the edit has to be offered to the peer, not just written locally",
                docId in outbox.pendingIds()
            )
        }
    }

    // endregion
}
