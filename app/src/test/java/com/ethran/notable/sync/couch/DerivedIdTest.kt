package com.ethran.notable.sync.couch

import com.ethran.notable.data.model.PageLayout
import com.ethran.notable.data.model.PageSize
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The identifiers two devices compute rather than mint, and the one page the split refuses to
 * divide.
 *
 * The twin of bopa's `PageSplitTests` journal cases. The pinned values are the contract: a device
 * offline today has to compute what a device offline last year computed, or a journal entry simply
 * stops being where anything looks for it.
 */
class DerivedIdTest {

    private val sheet = PageSize(width = 1400, height = 1000)
    private val now = "2026-09-19T12:00:00.000Z"

    /**
     * Reproduce without either app:
     * `printf 'notable-journal-day:2026-09-19' | shasum -a 256 | cut -c1-32`
     */
    @Test
    fun `journal ids are these exact values`() {
        assertEquals(
            "57008734-1905-d544-39b5-4e1806e663df", DerivedId.journalNotebookId(2026))
        assertEquals(
            "546732d7-b5b7-3e3a-36c2-5289f669308c", DerivedId.journalDayPageId("2026-09-19"))
        assertEquals("21de662f-e961-4ece-7cf5-f3c8b6f937ad", DerivedId.journalFolderId())
    }

    @Test
    fun `the split's own id still comes from the shared derivation`() {
        assertEquals(
            DerivedId.derive(DerivedId.pageSplitSeed("page-a", 1)),
            PageSplit.childId("page-a", 1),
        )
        assertEquals("82b2c548-bbc8-c002-5ea9-3e81ce812b9e", PageSplit.childId("page-a", 1))
        assertEquals("page-a", PageSplit.childId("page-a", 0))
    }

    @Test
    fun `a year is zero-padded`() {
        assertEquals("notable-journal:0999", DerivedId.journalNotebookSeed(999))
        assertEquals("notable-journal:2026", DerivedId.journalNotebookSeed(2026))
    }

    @Test
    fun `a derived id looks like every other page id`() {
        val id = DerivedId.journalDayPageId("2026-01-01")
        assertEquals(36, id.length)
        assertEquals(4, id.count { it == '-' })
        assertEquals(id, id.lowercase())
    }

    /**
     * Rule 0. Everything about this page says "divide me" — ink starting two sheets down, a
     * declared sheet it has outgrown — and the declaration overrides all of it.
     */
    @Test
    fun `a scroll page is never divided`() {
        val journal = CouchPage(
            notebookId = "book",
            pageWidth = sheet.width,
            pageHeight = 3000,
            layout = PageLayout.SCROLL,
            blocks = listOf(
                CouchBlock(
                    id = "l1",
                    kind = "link",
                    strokeIds = listOf("s1"),
                    x = 10,
                    y = 2400,
                    width = 100,
                    height = 40,
                    targetNotebookId = "nb-other",
                    createdAt = now,
                    updatedAt = now,
                )
            ),
            createdAt = now,
            updatedAt = now,
            updatedBy = "boox",
        )

        val pieces = PageSplit.split(journal, "page-j", sheet, now, "boox")

        assertEquals(1, pieces.size)
        assertEquals("page-j", pieces[0].id)
        // Untouched, not merely undivided: stamping the sheet's height onto it would contradict
        // the declaration it just made.
        assertEquals(journal, pieces[0].page)
    }
}
