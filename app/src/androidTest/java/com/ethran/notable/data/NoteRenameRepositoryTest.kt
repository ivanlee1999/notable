package com.ethran.notable.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ethran.notable.data.db.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Date

@RunWith(AndroidJUnit4::class)
class NoteRenameRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var books: BookRepository
    private lateinit var pages: PageRepository
    private val clock = Date(System.currentTimeMillis() + 60_000)
    private val book = Notebook(id = "rename-book", title = "Before", pageIds = listOf("rename-page"),
        openPageId = "rename-page", createdAt = Date(1), updatedAt = clock, updatedBy = "peer")
    private val page = Page(id = "rename-page", notebookId = "rename-book", title = "Before page",
        createdAt = Date(2), updatedAt = clock, updatedBy = "peer")

    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
        val outbox = CouchOutboxRepository(db.couchOutboxDao())
        books = BookRepository(db.notebookDao(), db.pageDao(), outbox, db)
        pages = PageRepository(db.pageDao(), outbox, db)
        db.notebookDao().create(book); db.pageDao().create(page)
        db.kvDao().set(Kv("journal.entry.rename-book", "organization unchanged"))
    }
    @After fun cleanup() { db.close() }

    @Test fun notebookRenameKeepsLatestManifestAndOrganizationAndAdvancesClock() = runBlocking {
        books.rename(book.id, "After")
        val renamed = books.getById(book.id)!!
        assertTrue(renamed.updatedAt.after(clock))
        assertNull(renamed.updatedBy)
        assertEquals(book.copy(title = "After", updatedAt = renamed.updatedAt, updatedBy = null), renamed)
        assertEquals("organization unchanged", db.kvDao().get("journal.entry.rename-book")!!.value)
        assertTrue(db.couchOutboxDao().allIds().contains("notebook:rename-book"))
    }

    @Test fun pageRenameKeepsCanvasFieldsAndAdvancesPastTheCurrentPeerClock() = runBlocking {
        pages.rename(page.id, "After page", expectedNotebookId = book.id)
        val renamed = pages.getById(page.id)!!
        assertTrue(renamed.updatedAt.after(clock))
        assertEquals(page.copy(title = "After page", updatedAt = renamed.updatedAt, updatedBy = null), renamed)
        assertTrue(db.couchOutboxDao().allIds().contains("page:rename-page"))
    }

    @Test fun movedOrMissingPagesCannotBeRenamedThroughAnOlderNotebookContext() = runBlocking {
        db.notebookDao().create(Notebook(id = "other", pageIds = listOf(page.id)))
        val moved = page.copy(notebookId = "other")
        db.pageDao().update(moved)
        assertTrue(runCatching { pages.rename(page.id, "Wrong context", expectedNotebookId = book.id) }.isFailure)
        assertTrue(runCatching { pages.rename("missing", "Missing", expectedNotebookId = book.id) }.isFailure)
        assertEquals(moved, pages.getById(page.id))
        assertTrue(db.couchOutboxDao().allIds().isEmpty())
    }

    @Test fun aTrashedNotebookCannotAcceptAPageRename() = runBlocking {
        db.notebookDao().update(book.copy(deletedAt = Date(10)))
        assertTrue(runCatching { pages.rename(page.id, "Trashed context", expectedNotebookId = book.id) }.isFailure)
        assertEquals(page, pages.getById(page.id))
        assertTrue(db.couchOutboxDao().allIds().isEmpty())
    }
}
