package com.ethran.notable.data.datastore

import com.ethran.notable.data.db.Notebook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Date

class RecentNotebooksTest {
    @Test fun `reopening moves one notebook to the front without duplicates`() {
        assertEquals(listOf("b", "a", "c"), recordRecentNotebook(listOf("a", "b", "c", "b"), "b"))
    }

    @Test fun `history is bounded and ignores blank targets`() {
        val ids = (1..30).map(Int::toString)
        assertEquals(listOf("new") + ids.take(19), recordRecentNotebook(ids, "new"))
        assertEquals(ids, recordRecentNotebook(ids, " "))
    }

    @Test fun `deleted missing and empty notebooks are not navigation targets`() {
        val live = Notebook(id = "live", pageIds = listOf("p"))
        val deleted = Notebook(id = "deleted", pageIds = listOf("q"), deletedAt = Date(1))
        val empty = Notebook(id = "empty")
        assertEquals(listOf(live), recentNotebooks(
            listOf("missing", "deleted", "empty", "live", "live"), listOf(live, deleted, empty),
        ))
    }

    @Test fun `resume prefers valid reading position and falls back after page removal`() {
        val book = Notebook(pageIds = listOf("first", "second"), openPageId = "second")
        assertEquals("second", book.resumePageId())
        assertEquals("first", book.copy(openPageId = "removed").resumePageId())
        assertNull(book.copy(pageIds = emptyList()).resumePageId())
    }

    @Test fun `history follows opening order independently of edit clocks`() {
        val a = Notebook(id = "a", pageIds = listOf("a1"), updatedAt = Date(100))
        val b = Notebook(id = "b", pageIds = listOf("b1"), updatedAt = Date(1))
        assertEquals(listOf(b, a), recentNotebooks(listOf("b", "a"), listOf(a, b)))
    }
}
