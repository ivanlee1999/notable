package com.ethran.notable.sync.couch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The rule that lets a PDF travel: a file-backed background is published as the `asset:` id of its
 * bytes, and each device turns that id back into a path of its own.
 *
 * bopa's `BackgroundKind.isFileBacked` is the twin of the classification here. The two have to
 * agree about which backgrounds are files, because a background one app calls a file and the other
 * calls a template is one that arrives as a path nobody can open.
 */
class CouchBackgroundAssetsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val pdf = "%PDF-1.7\nlecture notes\n".toByteArray()
    private val pdfSha = CouchAssetId.sha256Hex(pdf)
    private val pdfAssetId = CouchDocId.asset(pdfSha)

    // region What counts as a file

    @Test
    fun `native templates are not files and are never turned into assets`() {
        assertFalse(CouchBackgroundFiles.isFileBacked("native"))
        // The value travels as itself: "lined" means the same thing on both devices.
        assertNull(CouchBackgroundFiles.assetIdFor("lined", "native"))
        assertNull(CouchBackgroundFiles.assetIdFor("blank", "native"))
    }

    @Test
    fun `every kind of PDF and picture page is file-backed`() {
        // A book imported page by page, and one that follows the page number.
        assertTrue(CouchBackgroundFiles.isFileBacked("pdf0"))
        assertTrue(CouchBackgroundFiles.isFileBacked("pdf137"))
        assertTrue(CouchBackgroundFiles.isFileBacked("autoPdf"))
        assertTrue(CouchBackgroundFiles.isFileBacked("image"))
        assertTrue(CouchBackgroundFiles.isFileBacked("imagerepeating"))
        assertTrue(CouchBackgroundFiles.isFileBacked("coverImage"))
    }

    @Test
    fun `only PDFs keep an extension, because only they are paged through`() {
        val folder = temp.newFolder("backgrounds")
        assertEquals(
            File(folder, "$pdfSha.pdf").absolutePath,
            CouchBackgroundFiles.localPathFor(pdfAssetId, "pdf3", folder),
        )
        assertEquals(
            File(folder, "$pdfSha.pdf").absolutePath,
            CouchBackgroundFiles.localPathFor(pdfAssetId, "autoPdf", folder),
        )
        assertEquals(
            File(folder, pdfSha).absolutePath,
            CouchBackgroundFiles.localPathFor(pdfAssetId, "image", folder),
        )
    }

    // endregion

    // region Naming the bytes

    @Test
    fun `an imported PDF is published as the id of its bytes, not as its path`() {
        val file = temp.newFile("Lecture 3.pdf")
        file.writeBytes(pdf)

        assertEquals(pdfAssetId, CouchBackgroundFiles.assetIdFor(file.absolutePath, "pdf0"))
    }

    /**
     * The window this exists for: a page arrives naming bytes that have not been downloaded yet, so
     * its row points at a file that does not exist. The page still has to be pushable — and to go on
     * naming the same asset — or the reference would be dropped on the way back to the server.
     */
    @Test
    fun `a background still downloading is recognized by its name alone`() {
        val notHereYet = File(temp.newFolder("backgrounds"), "$pdfSha.pdf")
        assertFalse(notHereYet.exists())

        assertEquals(pdfAssetId, CouchBackgroundFiles.assetIdFor(notHereYet.absolutePath, "pdf0"))
    }

    /**
     * The name is consulted before the bytes. A background is routinely tens of megabytes and shared
     * by every page of a notebook, so reading one per page is the difference between a sync and an
     * ordeal — and a file this device named after a hash was written by the downloader, which had
     * the bytes in hand.
     */
    @Test
    fun `a hash-named background is taken at its name without being read`() {
        val downloaded = File(temp.newFolder("backgrounds"), "$pdfSha.pdf")
        downloaded.writeBytes(pdf)
        var reads = 0

        val id = CouchBackgroundFiles.assetIdFor(downloaded.absolutePath, "pdf0") { file ->
            reads++
            CouchAssetId.sha256Hex(file)
        }

        assertEquals(pdfAssetId, id)
        assertEquals("a hash-named file must not be read", 0, reads)
    }

    @Test
    fun `a reference this device could not resolve is not guessed at`() {
        val gone = File(temp.newFolder("external"), "somebody-elses.pdf")

        assertNull(CouchBackgroundFiles.assetIdFor(gone.absolutePath, "pdf0"))
        assertNull(CouchBackgroundFiles.assetIdFor("", "pdf0"))
    }

    /**
     * A page received while storage was unreachable keeps the reference it arrived with. Pushing it
     * back must name the same bytes rather than treat the id as a path and lose it.
     */
    @Test
    fun `an id that is already an id survives being published again`() {
        assertEquals(pdfAssetId, CouchBackgroundFiles.assetIdFor(pdfAssetId, "autoPdf"))
    }

    // endregion

    @Test
    fun `a downloaded background is found under either name it can have`() {
        assertEquals(listOf(pdfSha, "$pdfSha.pdf"), CouchBackgroundFiles.fileNamesFor(pdfSha))
    }

    @Test
    fun `a name that is not a hash says nothing about content`() {
        assertNull(CouchBackgroundFiles.sha256HexOfFileName("Lecture 3.pdf"))
        assertNull(CouchBackgroundFiles.sha256HexOfFileName("$pdfSha.png"))
        assertEquals(pdfSha, CouchBackgroundFiles.sha256HexOfFileName(pdfSha))
        assertEquals(pdfSha, CouchBackgroundFiles.sha256HexOfFileName("$pdfSha.pdf"))
    }

    /** A PDF is sniffed as one, so the server stores it under a type a browser can open. */
    @Test
    fun `a PDF announces its own content type`() {
        assertEquals("application/pdf", CouchAssetId.contentTypeOf(pdf))
    }

    /**
     * The round trip in one test: what this device publishes for an imported book is what the peer
     * resolves to a file of its own, and pushing that back names the same bytes again.
     */
    @Test
    fun `a path here becomes an id on the wire and a path there`() {
        val here = temp.newFile("Lecture 3.pdf")
        here.writeBytes(pdf)
        val theirBackgrounds = temp.newFolder("their-backgrounds")

        val onTheWire = CouchBackgroundFiles.assetIdFor(here.absolutePath, "pdf0")!!
        val there = CouchBackgroundFiles.localPathFor(onTheWire, "pdf0", theirBackgrounds)!!
        File(there).writeBytes(pdf)

        assertEquals(onTheWire, CouchBackgroundFiles.assetIdFor(there, "pdf0"))
    }
}
