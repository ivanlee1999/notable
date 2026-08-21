package com.ethran.notable.data.db

import com.ethran.notable.data.model.PageSize
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class NotebookTest {

    @Test
    fun getPageIndex_returns_index_of_existing_page() {
        val page1 = UUID.randomUUID().toString()
        val page2 = UUID.randomUUID().toString()
        
        val notebook = Notebook(pageIds = listOf(page1, page2))

        assertEquals(0, notebook.getPageIndex(page1))
        assertEquals(1, notebook.getPageIndex(page2))
    }

    @Test
    fun getPageIndex_returns_minus_one_for_missing_page() {
        val notebook = Notebook(pageIds = listOf("page-1", "page-2"))

        assertEquals(-1, notebook.getPageIndex("page-3"))
    }

    @Test
    fun getPageIndex_returns_index_for_empty_string_pageId() {
        // Technically an edge case: empty string ID 
        val notebook = Notebook(pageIds = listOf("page-1", "", "page-2"))

        assertEquals(1, notebook.getPageIndex(""))
    }

    @Test
    fun getPageIndex_returns_minus_one_if_empty_string_not_in_list() {
        val notebook = Notebook(pageIds = listOf("page-1", "page-2"))

        assertEquals(-1, notebook.getPageIndex(""))
    }

    @Test
    fun a_new_page_declares_the_notebook_default_sheet() {
        val notebook = Notebook(defaultPageWidth = 1400, defaultPageHeight = 1980)

        val page = notebook.newPage()

        assertEquals(1400, page.pageWidth)
        assertEquals(1980, page.pageHeight)
    }

    /**
     * A notebook from before page sizes existed declares no default, but its new pages still
     * declare a sheet — the canonical legacy one its old pages already resolve to. This is what
     * stops old notebooks minting fresh undeclared pages forever, and the iPad app applies the
     * same rule, so the page is the same page on both devices.
     */
    @Test
    fun a_new_page_in_a_legacy_notebook_declares_the_canonical_legacy_sheet() {
        val notebook = Notebook()

        val page = notebook.newPage()

        assertEquals(PageSize.LEGACY_UNDECLARED.width, page.pageWidth)
        assertEquals(PageSize.LEGACY_UNDECLARED.height, page.pageHeight)
    }
}

