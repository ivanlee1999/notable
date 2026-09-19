package com.ethran.notable.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that decide whether a page ends at its sheet, and what height a page that does not
 * still writes down.
 *
 * The twin of bopa's `PageLayoutTests`.
 */
class PageLayoutTest {

    @Test
    fun `absent and sheet are bounded`() {
        assertFalse(PageLayout.isScroll(null))
        assertFalse(PageLayout.isScroll(""))
        assertFalse(PageLayout.isScroll(PageLayout.SHEET))
    }

    /**
     * The asymmetry is the point: declining to divide a page is recoverable, dividing one that
     * should not have been divided is not.
     */
    @Test
    fun `an unrecognized layout is read as scrolling, not as a sheet`() {
        assertTrue(PageLayout.isScroll(PageLayout.SCROLL))
        assertTrue(PageLayout.isScroll("infinite-2d"))
    }

    @Test
    fun `the materialized height covers the content plus a screenful, in whole sheets`() {
        // Empty page: one sheet, even though slack alone would fit in less.
        assertEquals(2000, PageLayout.materializedHeight(0f, 2000))
        // 900 + 1000 slack = 1900, still inside the first sheet.
        assertEquals(2000, PageLayout.materializedHeight(900f, 2000))
        // 1100 + 1000 = 2100, so two.
        assertEquals(4000, PageLayout.materializedHeight(1100f, 2000))
        assertEquals(6000, PageLayout.materializedHeight(5000f, 2000))
    }

    /**
     * Why it rounds: a value that moved with every stroke would push the document on every save,
     * and two devices that padded differently would overwrite each other's height for ever.
     */
    @Test
    fun `writing more ink inside one sheet does not change the declared height`() {
        assertEquals(
            PageLayout.materializedHeight(2100f, 2000),
            PageLayout.materializedHeight(2400f, 2000),
        )
    }

    @Test
    fun `a page with no usable sheet declares nothing rather than dividing by zero`() {
        assertEquals(0, PageLayout.materializedHeight(500f, 0))
    }
}
