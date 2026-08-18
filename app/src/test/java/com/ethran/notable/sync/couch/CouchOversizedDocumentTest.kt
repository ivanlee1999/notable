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

    /** CouchDB refusing a document on its merits: the ordinary fields are too big. */
    private fun refuseAsCouchDb(documentId: String) {
        server.failingDocumentIds[documentId] = 413
        server.failingDocumentBodies[documentId] = FakeCouchTransport.COUCHDB_TOO_LARGE
    }

    /** A proxy refusing the request before CouchDB ever sees it. */
    private fun refuseAsProxy(documentId: String) {
        server.failingDocumentIds[documentId] = 413
        server.failingDocumentBodies[documentId] = FakeCouchTransport.PROXY_TOO_LARGE
    }

    private fun clearRefusals() {
        server.failingDocumentIds.clear()
        server.failingDocumentBodies.clear()
    }

    @Test
    fun `a 413'd page holds its notebook back that pass`() = runBlocking {
        stagePageAndNotebook()
        engine.markDirty(listOf(pageId, notebookId))
        refuseAsCouchDb(pageId)

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
        refuseAsCouchDb(pageId)
        engine.flush()

        // The server is healthy again as far as the transport is concerned; the denylist is what
        // has to stop the re-send now.
        clearRefusals()
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
        refuseAsCouchDb(pageId)
        engine.flush()

        // The user erases half the page; its updatedAt moves.
        store.set(pageId, CouchDocBody.Page(page(updatedAt = 20)))
        engine.markDirty(listOf(pageId))
        clearRefusals()
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
        refuseAsProxy(assetId)

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

    // region Which hop refused it — protocol §7.2

    private suspend fun refuserFor(documentId: String): CouchError.Refuser {
        val cause = engine.flush().failureCauses[documentId]
        return (cause as CouchError.Server).refusedBy
    }

    @Test
    fun `CouchDB's own error body names CouchDB as the refuser`() = runBlocking {
        stagePageAndNotebook()
        engine.markDirty(listOf(pageId))
        refuseAsCouchDb(pageId)

        assertEquals(CouchError.Refuser.COUCHDB, refuserFor(pageId))
    }

    @Test
    fun `an HTML error page names an intermediary`() = runBlocking {
        stagePageAndNotebook()
        engine.markDirty(listOf(pageId))
        refuseAsProxy(pageId)

        assertEquals(CouchError.Refuser.INTERMEDIARY, refuserFor(pageId))
    }

    @Test
    fun `a 413 with no body at all is read as an intermediary`() = runBlocking {
        // The safe default: assuming the proxy means the upload is retried once the server is
        // fixed. Assuming CouchDB would strand it until an edit that is never coming.
        stagePageAndNotebook()
        engine.markDirty(listOf(pageId))
        server.failingDocumentIds[pageId] = 413

        assertEquals(CouchError.Refuser.INTERMEDIARY, refuserFor(pageId))
    }

    @Test
    fun `editing a document the proxy refused does not earn a retry`() = runBlocking {
        stagePageAndNotebook()
        engine.markDirty(listOf(pageId, notebookId))
        refuseAsProxy(pageId)
        engine.flush()

        // The user, told the note is "too large", edits it down. That changes nothing: the cap is
        // on the request, and a smaller page is still refused by a 1 MB proxy if it exceeds it.
        // Re-sending on every edit is exactly the megabytes-a-pass waste the denylist prevents.
        store.set(pageId, CouchDocBody.Page(page(updatedAt = 20)))
        engine.markDirty(listOf(pageId))
        clearRefusals()
        server.forgetRequests()

        val report = engine.flush()

        assertFalse(
            "an edit is not news about the server's configuration",
            server.requestLog.any { it.first == "PUT" && it.second.contains("page:p1") },
        )
        assertNotNull("and the refusal is still reported", report.failures[pageId])
    }

    @Test
    fun `re-arming releases what the proxy refused`() = runBlocking {
        stagePageAndNotebook()
        engine.markDirty(listOf(pageId, notebookId))
        refuseAsProxy(pageId)
        engine.flush()

        // The user raises client_max_body_size and presses Sync now.
        clearRefusals()
        server.forgetRequests()
        engine.rearmRefusedRequests()

        val report = engine.flush()

        assertTrue(
            "the upload must be tried for real once the server may have changed",
            server.requestLog.any { it.first == "PUT" && it.second.contains("page:p1") },
        )
        assertTrue(report.pushed.contains(pageId))
        assertTrue("and the held notebook goes out with it", report.pushed.contains(notebookId))
        assertEquals("nothing left waiting", 0, engine.pendingCount)
    }

    @Test
    fun `re-arming does not release what CouchDB refused`() = runBlocking {
        stagePageAndNotebook()
        engine.markDirty(listOf(pageId, notebookId))
        refuseAsCouchDb(pageId)
        engine.flush()

        clearRefusals()
        server.forgetRequests()
        engine.rearmRefusedRequests()

        val report = engine.flush()

        assertFalse(
            "nothing on the server changes what a page with too much ink in it weighs",
            server.requestLog.any { it.first == "PUT" && it.second.contains("page:p1") },
        )
        assertNotNull(report.failures[pageId])
    }

    // endregion
}
