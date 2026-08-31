package com.ethran.notable.data

import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.db.newPage
import com.ethran.notable.data.model.BackgroundType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Paper chosen once is the paper every page made after it starts on.
 *
 * A template used to be printed on the one page it was chosen for, so the next page came back
 * blank and the choice had to be made again — every page, forever. What the editor does about that
 * is write the choice onto the notebook itself; these are the two halves of that promise which can
 * be stated without a database: which choices are worth remembering, and that a remembered one is
 * what the next page is actually printed with.
 */
class StickyTemplateTest {

    private val notebook = Notebook(
        title = "Daily",
        defaultBackground = "blank",
        defaultBackgroundType = BackgroundType.Native.key,
    )

    @Test
    fun `a page made after the choice starts on the chosen paper`() {
        val squared = notebook.copy(
            defaultBackground = "squared",
            defaultBackgroundType = BackgroundType.Native.key,
        )

        val page = squared.newPage()

        assertEquals("squared", page.background)
        assertEquals(BackgroundType.Native.key, page.backgroundType)
    }

    @Test
    fun `a document chosen for a page is what the next page is printed on too`() {
        val planner = notebook.copy(
            defaultBackground = "/storage/emulated/0/Download/Weekly.pdf",
            defaultBackgroundType = BackgroundType.Pdf(2).key,
        )

        val page = planner.newPage()

        assertEquals("/storage/emulated/0/Download/Weekly.pdf", page.background)
        assertEquals("pdf2", page.backgroundType)
    }

    /** Every kind of paper is worth remembering... */
    @Test
    fun `paper chosen for a page can be the notebook's default`() {
        assertTrue(BackgroundType.Native.canBeNotebookDefault)
        assertTrue(BackgroundType.Image.canBeNotebookDefault)
        assertTrue(BackgroundType.ImageRepeating.canBeNotebookDefault)
        assertTrue(BackgroundType.AutoPdf.canBeNotebookDefault)
        assertTrue(BackgroundType.Pdf(2).canBeNotebookDefault)
    }

    /** ...except cover art, which fronts one page. Every page after it is not a cover. */
    @Test
    fun `cover art is not a paper for new pages`() {
        assertFalse(BackgroundType.CoverImage.canBeNotebookDefault)
    }
}
