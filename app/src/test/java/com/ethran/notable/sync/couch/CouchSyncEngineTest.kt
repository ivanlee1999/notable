package com.ethran.notable.sync.couch

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * Two engines against one in-memory CouchDB — the iPad and the BOOX, as far as these tests are
 * concerned. The scenarios are the ones that actually went wrong over WebDAV, and they mirror
 * bopa's `CouchSyncEngineTests.swift` case for case so a divergence in either implementation
 * shows up as the same named failure on both sides.
 */
class CouchSyncEngineTest {

    private lateinit var server: FakeCouchTransport
    private lateinit var ipadStore: FakeLocalStore
    private lateinit var booxStore: FakeLocalStore
    private lateinit var ipad: CouchSyncEngine
    private lateinit var boox: CouchSyncEngine

    private val pageId = CouchDocId.page("p1")
    private val notebookId = CouchDocId.notebook("nb1")

    @Before
    fun setUp() {
        server = FakeCouchTransport()
        ipadStore = FakeLocalStore()
        booxStore = FakeLocalStore()
        val client = CouchDbClient(server, database = "notes")
        ipad = CouchSyncEngine(client, ipadStore, deviceId = "ipad")
        boox = CouchSyncEngine(client, booxStore, deviceId = "boox")
    }

    // region Fixtures

    private fun stamp(second: Int): String =
        Instant.ofEpochSecond(1_770_000_000L + second).toString()

    private fun stroke(id: String, at: Int, device: String) = CouchStroke(
        id = id, createdAt = stamp(at), updatedAt = stamp(at), deviceId = device,
        pen = "BALLPEN", color = -16_777_216, size = 3f,
        top = 0f, bottom = 1f, left = 0f, right = 1f, pointsData = "AAA=",
    )

    private fun page(
        strokes: List<CouchStroke> = emptyList(),
        deleted: List<CouchTombstone> = emptyList(),
        updatedAt: Int,
        by: String,
    ) = CouchPage(
        notebookId = "nb1", strokes = strokes, deletedStrokes = deleted,
        createdAt = stamp(0), updatedAt = stamp(updatedAt), updatedBy = by,
    )

    private fun notebook(title: String, pageIds: List<String>, updatedAt: Int, by: String) =
        CouchNotebook(
            title = title, pageIds = pageIds,
            createdAt = stamp(0), updatedAt = stamp(updatedAt), updatedBy = by,
        )

    private fun putPaths(): List<String> =
        server.requestLog.filter { it.first == "PUT" }.map { it.second }

    // endregion

    @Test
    fun push_creates_document_then_pull_delivers_it_to_the_other_device() = runBlocking {
        ipadStore.set(pageId, CouchDocBody.Page(page(listOf(stroke("s1", 1, "ipad")), updatedAt = 5, by = "ipad")))
        ipad.markDirty(listOf(pageId))

        val flush = ipad.flush()
        assertEquals(listOf(pageId), flush.pushed)
        assertTrue(flush.failures.isEmpty())
        assertEquals(listOf(pageId), server.documentIds())

        val pull = boox.pull()
        assertEquals(listOf(pageId), pull.applied)
        assertEquals(listOf("s1"), booxStore.page(pageId)?.strokes?.map { it.id })
    }

    @Test
    fun own_write_coming_back_on_the_feed_is_not_reapplied() = runBlocking {
        ipadStore.set(pageId, CouchDocBody.Page(page(listOf(stroke("s1", 1, "ipad")), updatedAt = 5, by = "ipad")))
        ipad.markDirty(listOf(pageId))
        ipad.flush()

        val pull = ipad.pull()
        assertEquals(listOf(pageId), pull.skippedEchoes)
        assertTrue(pull.applied.isEmpty())
        // An echo that was applied would mark the document dirty and start a push ping-pong.
        assertEquals(0, ipad.pendingCount)
    }

    /** The headline case: both devices draw on the same page with no network, then both sync. */
    @Test
    fun concurrent_offline_edits_to_one_page_union_rather_than_overwrite() = runBlocking {
        ipadStore.set(pageId, CouchDocBody.Page(page(listOf(stroke("s0", 0, "ipad")), updatedAt = 1, by = "ipad")))
        ipad.markDirty(listOf(pageId))
        ipad.flush()
        boox.pull()

        // Both go offline and draw.
        val ipadPage = ipadStore.page(pageId)!!
        ipadStore.set(
            pageId,
            CouchDocBody.Page(
                ipadPage.copy(
                    strokes = ipadPage.strokes + stroke("s-ipad", 10, "ipad"),
                    updatedAt = stamp(10), updatedBy = "ipad",
                )
            )
        )
        ipad.markDirty(listOf(pageId))

        val booxPage = booxStore.page(pageId)!!
        booxStore.set(
            pageId,
            CouchDocBody.Page(
                booxPage.copy(
                    strokes = booxPage.strokes + stroke("s-boox", 11, "boox"),
                    updatedAt = stamp(11), updatedBy = "boox",
                )
            )
        )
        boox.markDirty(listOf(pageId))

        // iPad reaches the server first; the BOOX hits a 409 and merges.
        ipad.flush()
        val booxFlush = boox.flush()
        assertEquals("the BOOX should have merged, not overwritten", listOf(pageId), booxFlush.merged)

        assertEquals(
            listOf("s-boox", "s-ipad", "s0"),
            booxStore.page(pageId)?.strokes?.map { it.id }?.sorted(),
        )

        // And the iPad converges on the same content when it next pulls.
        ipad.pull()
        assertEquals(
            listOf("s-boox", "s-ipad", "s0"),
            ipadStore.page(pageId)?.strokes?.map { it.id }?.sorted(),
        )
    }

    /**
     * Drawing on one device while erasing the same stroke on the other: the erase must win, and
     * must stay won after further syncs.
     */
    @Test
    fun erasure_on_one_device_sticks_on_the_other() = runBlocking {
        ipadStore.set(
            pageId,
            CouchDocBody.Page(
                page(listOf(stroke("s1", 1, "ipad"), stroke("s2", 2, "ipad")), updatedAt = 3, by = "ipad")
            )
        )
        ipad.markDirty(listOf(pageId))
        ipad.flush()
        boox.pull()

        val erased = booxStore.page(pageId)!!
        booxStore.set(
            pageId,
            CouchDocBody.Page(
                erased.copy(
                    strokes = erased.strokes.filterNot { it.id == "s2" },
                    deletedStrokes = listOf(CouchTombstone(id = "s2", deletedAt = stamp(20))),
                    updatedAt = stamp(20), updatedBy = "boox",
                )
            )
        )
        boox.markDirty(listOf(pageId))
        boox.flush()

        ipad.pull()
        assertEquals(listOf("s1"), ipadStore.page(pageId)?.strokes?.map { it.id })

        // The iPad still had s2 locally a moment ago; re-pushing must not resurrect it.
        ipad.markDirty(listOf(pageId))
        ipad.flush()
        boox.pull()
        assertEquals(listOf("s1"), booxStore.page(pageId)?.strokes?.map { it.id })
    }

    @Test
    fun deletion_propagates_but_an_edit_after_it_resurrects() = runBlocking {
        ipadStore.set(notebookId, CouchDocBody.Notebook(notebook("notes", listOf("p1"), 1, "ipad")))
        ipad.markDirty(listOf(notebookId))
        ipad.flush()
        boox.pull()

        // BOOX deletes it.
        booxStore.set(
            notebookId,
            CouchDocBody.Deleted(
                CouchDeletedDoc(type = CouchDocType.NOTEBOOK, deletedAt = stamp(10), updatedBy = "boox")
            )
        )
        boox.markDirty(listOf(notebookId))
        boox.flush()
        assertTrue(server.isDeleted(notebookId))

        ipad.pull()
        assertTrue(ipadStore.body(notebookId)?.isDeleted ?: false)

        // A different notebook, edited *after* a delete, comes back instead.
        val otherId = CouchDocId.notebook("nb2")
        ipadStore.set(otherId, CouchDocBody.Notebook(notebook("kept", emptyList(), 1, "ipad")))
        ipad.markDirty(listOf(otherId))
        ipad.flush()
        boox.pull()

        booxStore.set(
            otherId,
            CouchDocBody.Deleted(
                CouchDeletedDoc(type = CouchDocType.NOTEBOOK, deletedAt = stamp(20), updatedBy = "boox")
            )
        )
        boox.markDirty(listOf(otherId))
        boox.flush()

        val edited = ipadStore.notebook(otherId)!!
        ipadStore.set(
            otherId,
            CouchDocBody.Notebook(edited.copy(title = "edited after the delete", updatedAt = stamp(30)))
        )
        ipad.markDirty(listOf(otherId))
        val flush = ipad.flush()

        assertEquals(listOf(otherId), flush.merged)
        assertEquals("edited after the delete", ipadStore.notebook(otherId)?.title)
        assertFalse("the newer edit should have resurrected it", server.isDeleted(otherId))
    }

    /**
     * The other half of delete-vs-edit, and the one that used to lose the deletion.
     *
     * A plain `GET` of a deleted document is a 404 — CouchDB does not hand the tombstone back there
     * — so a pusher that read 404 as "never existed" retried as a create, and a PUT with no revision
     * over a tombstone *succeeds*: the peer's deletion silently came back as a live notebook. Then,
     * once the tombstone is read correctly, the merge resolves to it, and writing that tombstone
     * back is itself a 409 — so the id has to leave the outbox without a write.
     */
    @Test
    fun peers_deletion_survives_an_older_local_edit_waiting_to_be_pushed() = runBlocking {
        ipadStore.set(notebookId, CouchDocBody.Notebook(notebook("notes", emptyList(), 1, "ipad")))
        ipad.markDirty(listOf(notebookId))
        ipad.flush()
        boox.pull()

        // The iPad renames it offline...
        val renamed = ipadStore.notebook(notebookId)!!
        ipadStore.set(
            notebookId,
            CouchDocBody.Notebook(renamed.copy(title = "renamed on the iPad", updatedAt = stamp(10))),
        )
        ipad.markDirty(listOf(notebookId))

        // ...while the BOOX deletes it, later, and gets there first.
        booxStore.set(
            notebookId,
            CouchDocBody.Deleted(
                CouchDeletedDoc(
                    type = CouchDocType.NOTEBOOK, deletedAt = stamp(20), updatedBy = "boox",
                )
            ),
        )
        boox.markDirty(listOf(notebookId))
        boox.flush()

        val flush = ipad.flush()

        assertTrue("the push should settle, not exhaust its retries", flush.failures.isEmpty())
        assertTrue("the document must leave the outbox", flush.stillDirty.isEmpty())
        assertEquals(0, ipad.pendingCount)
        assertTrue(
            "the iPad should have accepted the deletion rather than re-created the notebook",
            ipadStore.body(notebookId)?.isDeleted ?: false,
        )
        assertTrue("the deletion must still stand on the server", server.isDeleted(notebookId))
    }

    @Test
    fun offline_edits_queue_and_drain_on_reconnect() = runBlocking {
        server.isOffline = true
        ipadStore.set(pageId, CouchDocBody.Page(page(listOf(stroke("s1", 1, "ipad")), updatedAt = 5, by = "ipad")))
        ipad.markDirty(listOf(pageId))

        val offlineFlush = ipad.flush()
        assertEquals(listOf(pageId), offlineFlush.stillDirty)
        assertTrue(offlineFlush.pushed.isEmpty())
        assertEquals("work must survive being offline", 1, ipad.pendingCount)

        server.isOffline = false
        val onlineFlush = ipad.flush()
        assertEquals(listOf(pageId), onlineFlush.pushed)
        assertEquals(0, ipad.pendingCount)
    }

    /**
     * A lost checkpoint replays the whole feed. Because merges are idempotent that has to be a
     * slow no-op, not a source of duplicates or spurious conflicts.
     */
    @Test
    fun replaying_the_feed_from_zero_is_a_no_op() = runBlocking {
        ipadStore.set(pageId, CouchDocBody.Page(page(listOf(stroke("s1", 1, "ipad")), updatedAt = 5, by = "ipad")))
        ipad.markDirty(listOf(pageId))
        ipad.flush()
        boox.pull()
        val before = booxStore.page(pageId)

        val replayed = CouchSyncEngine(
            CouchDbClient(server, database = "notes"), booxStore, deviceId = "boox",
            state = CouchSyncState(),
        )
        val report = replayed.pull()

        assertEquals(listOf(pageId), report.applied)
        assertEquals("replay changed content", before, booxStore.page(pageId))
        assertTrue("replay should not think we are ahead of the server", report.pushBack.isEmpty())
    }

    /** Local content the server has not seen must be pushed back rather than quietly dropped. */
    @Test
    fun pull_queues_a_push_back_when_the_local_copy_has_more() = runBlocking {
        server.seed(
            pageId,
            page(listOf(stroke("s-remote", 2, "boox")), updatedAt = 6, by = "boox"),
            CouchPage.serializer(),
        )
        ipadStore.set(
            pageId,
            CouchDocBody.Page(page(listOf(stroke("s-local", 1, "ipad")), updatedAt = 5, by = "ipad"))
        )

        val report = ipad.pull()
        assertEquals(listOf(pageId), report.pushBack)
        assertEquals(
            listOf("s-local", "s-remote"),
            ipadStore.page(pageId)?.strokes?.map { it.id }?.sorted(),
        )

        val flush = ipad.flush()
        assertEquals(listOf(pageId), flush.pushed)
    }

    @Test
    fun document_from_a_newer_schema_becomes_a_conflict_copy() = runBlocking {
        server.seedRaw(
            pageId,
            buildJsonObject {
                put("type", JsonPrimitive("page"))
                put("schema", JsonPrimitive(99))
                put("notebookId", JsonPrimitive("nb1"))
                put("createdAt", JsonPrimitive(stamp(0)))
                put("updatedAt", JsonPrimitive(stamp(5)))
                put("updatedBy", JsonPrimitive("boox"))
                put("strokes", buildJsonArray { })
                put("somethingNew", buildJsonObject { put("shape", JsonPrimitive("unknown")) })
            },
        )

        val report = ipad.pull()
        assertEquals(listOf(pageId), report.conflictCopies)
        assertEquals(listOf(pageId), ipadStore.conflictCopies)
        assertNull(
            "a future document must not be decoded as if understood",
            ipadStore.page(pageId),
        )
    }

    @Test
    fun notebooks_are_pushed_after_their_pages() = runBlocking {
        val folderId = CouchDocId.folder("f1")
        ipadStore.set(pageId, CouchDocBody.Page(page(updatedAt = 5, by = "ipad")))
        ipadStore.set(notebookId, CouchDocBody.Notebook(notebook("notes", listOf("p1"), 5, "ipad")))
        ipadStore.set(
            folderId,
            CouchDocBody.Folder(
                CouchFolder(
                    title = "school", createdAt = stamp(0), updatedAt = stamp(5), updatedBy = "ipad"
                )
            )
        )
        ipad.markDirty(listOf(notebookId, pageId, folderId))

        ipad.flush()

        val puts = putPaths()
        val pageIndex = puts.indexOfFirst { it.contains("page:") }
        val notebookIndex = puts.indexOfFirst { it.contains("notebook:") }
        val folderIndex = puts.indexOfFirst { it.contains("folder:") }
        assertTrue(pageIndex >= 0)
        assertTrue(notebookIndex >= 0)
        assertTrue(folderIndex < notebookIndex)
        assertTrue(
            "a notebook must never land before the pages it names",
            pageIndex < notebookIndex,
        )
    }

    /**
     * A wiped local database looks exactly like "the user deleted everything"; the guard makes the
     * difference a human decision rather than a silent mass delete.
     */
    @Test
    fun mass_deletion_is_refused_rather_than_pushed() = runBlocking {
        val ids = (0 until 12).map { index ->
            val id = CouchDocId.notebook("nb$index")
            ipadStore.set(
                id,
                CouchDocBody.Deleted(
                    CouchDeletedDoc(
                        type = CouchDocType.NOTEBOOK, deletedAt = stamp(10), updatedBy = "ipad"
                    )
                )
            )
            id
        }
        ipad.markDirty(ids)

        val report = ipad.flush()
        assertTrue(report.blockedByDeletionGuard)
        assertTrue(report.pushed.isEmpty())
        assertTrue("nothing should have reached the server", server.documentIds().isEmpty())
    }

    @Test
    fun unauthorized_stops_immediately_and_keeps_work() = runBlocking {
        server.failingDocumentIds[pageId] = 401
        ipadStore.set(pageId, CouchDocBody.Page(page(updatedAt = 5, by = "ipad")))
        ipad.markDirty(listOf(pageId))

        val report = ipad.flush()
        assertEquals(listOf(pageId), report.stillDirty)
        assertEquals(CouchError.Unauthorized.detail, report.failures[pageId])
        assertEquals(1, ipad.pendingCount)
    }

    // region Images

    private val picture = "the bytes of a picture".toByteArray()
    private val pictureId get() = CouchAssetId.forBytes(picture)

    private fun pageWithPicture(updatedAt: Int, by: String) = page(updatedAt = updatedAt, by = by)
        .copy(
            images = listOf(
                CouchImage(
                    id = "i1", assetId = pictureId, x = 0, y = 0, width = 4, height = 4,
                    createdAt = stamp(1), updatedAt = stamp(1),
                )
            )
        )

    private fun FakeLocalStore.placePicture(by: String) {
        set(pageId, CouchDocBody.Page(pageWithPicture(updatedAt = 5, by = by)))
        set(pictureId, CouchDocBody.Asset(CouchAsset.of(picture, at = stamp(1), updatedBy = by)))
    }

    /**
     * Nobody queues an asset — the app only ever edits a page. The bytes are derived from the page
     * being sent, and they go first, so the peer never reads a reference to nothing.
     */
    @Test
    fun `an image's bytes are sent before the page that places them`() = runBlocking {
        ipadStore.placePicture(by = "ipad")
        ipad.markDirty(listOf(pageId))

        val flush = ipad.flush()
        assertEquals(listOf(pictureId, pageId), flush.pushed)

        val puts = putPaths()
        assertTrue(
            "the bytes must land before the page",
            puts.indexOfFirst { it.contains("asset:") } < puts.indexOfFirst { it.contains("page:") },
        )
    }

    /**
     * Content-addressed means an id can only ever hold one thing. A second device offering the
     * same picture is told 409, and that is the upload succeeding.
     */
    @Test
    fun `the same picture from two devices is not a conflict`() = runBlocking {
        ipadStore.placePicture(by = "ipad")
        booxStore.placePicture(by = "boox")
        ipad.markDirty(listOf(pageId))
        boox.markDirty(listOf(pageId))

        ipad.flush()
        val second = boox.flush()
        assertTrue("a duplicate upload is not a failure: ${second.failures}", second.failures.isEmpty())
        assertTrue(second.pushed.contains(pictureId))

        // And a third attempt does not even reach the wire: the revision says it is already there.
        server.forgetRequests()
        boox.markDirty(listOf(pageId))
        boox.flush()
        assertFalse(server.requestLog.any { it.second.contains("asset:") })
    }

    /** The other half: a page arrives naming a picture, and the bytes have to follow. */
    @Test
    fun `pull fetches the bytes of a picture the page names`() = runBlocking {
        ipadStore.placePicture(by = "ipad")
        ipad.markDirty(listOf(pageId))
        ipad.flush()

        val pulled = boox.pull()
        assertEquals(listOf(pictureId), pulled.fetchedAssets)
        val held = (booxStore.body(pictureId) as? CouchDocBody.Asset)?.asset
        assertArrayEquals(picture, held?.bytes)
        assertEquals("application/octet-stream", held?.contentType)

        // Nothing left owed, so the next pull does not go asking again.
        server.forgetRequests()
        boox.pull()
        assertFalse(server.requestLog.any { it.second.endsWith("/blob") })
    }

    /** A download that fails is retried, not forgotten: the reference stays owed. */
    @Test
    fun `a picture that could not be fetched is tried again on the next pull`() = runBlocking {
        ipadStore.placePicture(by = "ipad")
        ipad.markDirty(listOf(pageId))
        ipad.flush()

        server.failingDocumentIds["$pictureId/${CouchAssetId.BLOB_NAME}"] = 503
        assertEquals(emptyList<String>(), boox.pull().fetchedAssets)
        assertNull(booxStore.body(pictureId))
        assertEquals(listOf(pictureId), booxStore.missingAssetIds())

        server.failingDocumentIds.clear()
        assertEquals(listOf(pictureId), boox.pull().fetchedAssets)
    }

    // endregion
}
