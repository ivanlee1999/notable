package com.ethran.notable.sync

import com.ethran.notable.data.db.Page
import com.ethran.notable.data.db.PageSyncState
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Date

/**
 * Table-driven tests for the pure page-level upload decision (Phase 10c). A page is dirty iff it has
 * no committed sync row or its `updatedAt` is strictly newer than the row's anchor.
 */
class PageSyncSelectorTest {

    private fun page(id: String, updatedAt: Long) =
        Page(id = id, notebookId = "nb", updatedAt = Date(updatedAt))

    private fun row(pageId: String, anchor: Long, etag: String? = "e") = PageSyncState(
        pageId = pageId,
        notebookId = "nb",
        remoteEtag = etag,
        localUpdatedAtAtSync = Date(anchor),
        lastSyncedAt = Date(anchor),
    )

    private fun dirtyIds(pages: List<Page>, rows: List<PageSyncState>): List<String> =
        PageSyncSelector.selectDirtyPages(pages, rows.associateBy { it.pageId }).map { it.id }

    @Test
    fun noRow_isDirty() {
        val pages = listOf(page("p1", 5_000))
        assertEquals(listOf("p1"), dirtyIds(pages, emptyList()))
    }

    @Test
    fun unchangedSinceAnchor_isSkipped() {
        // A committed upload stores anchor == page.updatedAt, so an untouched page is exactly equal.
        val pages = listOf(page("p1", 5_000))
        assertEquals(emptyList<String>(), dirtyIds(pages, listOf(row("p1", 5_000))))
    }

    @Test
    fun editedAfterAnchor_isDirty() {
        val pages = listOf(page("p1", 6_000))
        assertEquals(listOf("p1"), dirtyIds(pages, listOf(row("p1", 5_000))))
    }

    @Test
    fun anchorNewerThanPage_isSkipped() {
        // Defensive: a stale-looking page (anchor ahead) is not re-uploaded.
        val pages = listOf(page("p1", 4_000))
        assertEquals(emptyList<String>(), dirtyIds(pages, listOf(row("p1", 5_000))))
    }

    @Test
    fun mixedNotebook_selectsOnlyDirty() {
        val pages = listOf(
            page("clean", 5_000),
            page("edited", 9_000),
            page("new", 1_000),
        )
        val rows = listOf(row("clean", 5_000), row("edited", 5_000))
        assertEquals(listOf("edited", "new"), dirtyIds(pages, rows))
    }

    @Test
    fun rowForDepartedPage_doesNotResurrectIt() {
        // A row exists for a page no longer in the notebook -> it simply isn't in `pages`, so it is
        // never selected (departed rows are pruned by the commit transaction, not here).
        val pages = listOf(page("p1", 5_000))
        val rows = listOf(row("p1", 5_000), row("gone", 5_000))
        assertEquals(emptyList<String>(), dirtyIds(pages, rows))
    }
}
