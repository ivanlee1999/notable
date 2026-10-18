package com.ethran.notable.ui.viewmodels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickNavTabStateTest {
    @Test fun `page jumps preserve every tab but discard previous notebook data`() {
        QuickNavTab.entries.forEach { tab ->
            val state = QuickNavUiState(
                tab = tab, bookId = "old", currentPageId = "old-page",
                bookPageIds = listOf("old-page"), isCurrentPageBookmarked = true,
            ).forPage("new-page")
            assertEquals(tab, state.tab)
            assertEquals("new-page", state.currentPageId)
            assertNull(state.bookId)
            assertTrue(state.bookPageIds.isEmpty())
            assertFalse(state.isCurrentPageBookmarked)
            assertTrue(state.isLoading)
        }
    }

    @Test fun `library keeps selected tab without waiting for nonexistent page`() {
        val state = QuickNavUiState(tab = QuickNavTab.NOTES).forPage(null)
        assertEquals(QuickNavTab.NOTES, state.tab)
        assertNull(state.currentPageId)
        assertFalse(state.isLoading)
    }
}
