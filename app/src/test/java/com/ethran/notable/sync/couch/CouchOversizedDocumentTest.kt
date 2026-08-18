package com.ethran.notable.sync.couch

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * What happens when the server refuses a document as too large (413).
 *
 * Two defects lived here. The flush loop broke only on retriable/unauthorized/blocked failures, so
 * a 413'd page fell through and the loop carried on — straight into pushing the notebook whose
 * manifest names that page, permanently breaking the ordering invariant (assets → folders → pages
 * → notebooks) that exists so a reader never sees a manifest pointing at documents the server does
 * not hold. And the refused document was re-derived and re-sent in full on every flush, megabytes
 * a pass for an oversized asset, only to hear the same answer.
 */
class CouchOversizedDocumentTest {

    private lateinit var server: FakeCouchTransport
    private lateinit var store: FakeLocalStore
    private lateinit var engine: CouchSyncEngine

    private val pageId = CouchDocId.page("p1")
    private val notebookId = CouchDocId.notebook("nb1")

    @Before
    fun setUp() {
        server = FakeCouchTransport()
        store = FakeLocalStore()
        engine = CouchSyncEngine(CouchDbClient(server, database = "notes"), store, deviceId = "boox")
    }

    private fun stamp(second: Int): String =
        Instant.ofEpochSecond(1_770_000_000L + second).toString()

    private fun page(updatedAt: Int, images: List<CouchImage> = emptyList()) = CouchPage(
        notebookId = "nb1", images = images,
        createdAt = stamp(0), updatedAt = stamp(updatedAt), updatedBy = "boox",
    )

    private fun notebook(updatedAt: Int) = CouchNotebook(
        title = "notes", pageIds = listOf("p1"),
        createdAt = stamp(0), updatedAt = stamp(updatedAt), updatedBy = "boox",
    )

    private fun stagePageAndNotebook() {
        store.set(pageId, CouchDocBody.Page(page(updatedAt = 5)))
        store.set(notebookId, CouchDocBody.Notebook(notebook(updatedAt = 5)))
    }

    @Test
    fun `a 413'd page holds its notebook back that pass`() = runBlocking {
        stagePageAndNotebook()
        engine.markDirty(listOf(pageId, notebookId))
        server.failingDocumentIds[pageId] = 413

        val report = engine.flush()

        assertNotNull("the refusal must be surfaced", report.failures[pageId])
        assertTrue(report.failures[pageId]!!.startsWith("server(413"))
        assertFalse(
            "the manifest must not outrun the page it names",
            report.pushed.contains(notebookId),
        )
        assertFalse(server.documentIds().contains(notebookId))
        assertTrue(
            "both stay queued for a later pass",
            report.stillDirty.containsAll(listOf(pageId, notebookId)),
        )
        assertFalse("waiting alone will not fix a 413", report.hasRetriableFailure)
    }

    @Test
    fun `the next flush does not re-send an unchanged oversized document`() = runBlocking {
        stagePageAndNotebook()
        engine.markDirty(listOf(pageId, notebookId))
        server.failingDocumentIds[pageId] = 413
        engine.flush()

        // The server is healthy again as far as the transport is concerned; the denylist is what
        // has to stop the re-send now.
        server.failingDocumentIds.clear()
        server.forgetRequests()
        val second = engine.flush()

        assertFalse(
            "an unchanged oversized document must not go back on the wire",
            server.requestLog.any { it.first == "PUT" && it.second.contains("page:p1") },
        )
        assertNotNull("and the refusal is still reported", second.failures[pageId])
        assertTrue(second.failures[pageId]!!.startsWith("server(413"))
        assertFalse("its notebook keeps waiting with it", second.pushed.contains(notebookId))
    }

    @Test
    fun `an edited oversized document earns one real retry`() = runBlocking {
        stagePageAndNotebook()
        engine.markDirty(listOf(pageId, notebookId))
        server.failingDocumentIds[pageId] = 413
        engine.flush()

        // The user erases half the page; its updatedAt moves.
        store.set(pageId, CouchDocBody.Page(page(updatedAt = 20)))
        engine.markDirty(listOf(pageId))
        server.failingDocumentIds.clear()
        server.forgetRequests()

        val report = engine.flush()

        assertTrue(
            "the changed document must be attempted again",
            server.requestLog.any { it.first == "PUT" && it.second.contains("page:p1") },
        )
        assertTrue(report.pushed.contains(pageId))
        assertTrue("and the held notebook goes out with it", report.pushed.contains(notebookId))
        assertEquals("nothing left waiting", 0, engine.pendingCount)
    }

    @Test
    fun `a 413'd asset holds back the page and notebook that reference it`() = runBlocking {
        val picture = ByteArray(64) { it.toByte() }
        val assetId = CouchAssetId.forBytes(picture)
        store.set(
            pageId,
            CouchDocBody.Page(
                page(
                    updatedAt = 5,
                    images = listOf(
                        CouchImage(
                            id = "i1", assetId = assetId, x = 0, y = 0, width = 4, height = 4,
                            createdAt = stamp(1), updatedAt = stamp(1),
                        )
                    ),
                )
            ),
        )
        store.set(notebookId, CouchDocBody.Notebook(notebook(updatedAt = 5)))
        store.set(assetId, CouchDocBody.Asset(CouchAsset.of(picture, at = stamp(1), updatedBy = "boox")))
        engine.markDirty(listOf(pageId, notebookId))
        server.failingDocumentIds[assetId] = 413

        val report = engine.flush()

        assertNotNull(report.failures[assetId])
        assertTrue(report.failures[assetId]!!.startsWith("server(413"))
        assertFalse(
            "a page must not land naming bytes the server refused",
            report.pushed.contains(pageId),
        )
        assertFalse(report.pushed.contains(notebookId))
        assertTrue("nothing reached the server", server.documentIds().isEmpty())
        assertTrue(
            "everything held stays queued",
            report.stillDirty.containsAll(listOf(pageId, notebookId, assetId)),
        )
    }
}
