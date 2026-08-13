package com.ethran.notable.sync.couch

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.UnknownServiceException
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
     * The loop stops at the first transport failure, because one dead connection applies to every
     * remaining document too. What it reports has to be the whole queue regardless: the controller
     * uses `stillDirty.size` as the pending count, and the reconnect drain decides whether to push
     * at all by asking how much is waiting. Reporting only the document that happened to be first
     * made both of those answers too small.
     */
    @Test
    fun a_flush_that_stops_early_reports_everything_still_waiting() = runBlocking {
        val secondPageId = CouchDocId.page("p2")
        ipadStore.set(pageId, CouchDocBody.Page(page(updatedAt = 5, by = "ipad")))
        ipadStore.set(secondPageId, CouchDocBody.Page(page(updatedAt = 6, by = "ipad")))
        ipadStore.set(
            notebookId,
            CouchDocBody.Notebook(notebook("Notes", listOf("p1", "p2"), updatedAt = 6, by = "ipad"))
        )
        ipad.markDirty(listOf(pageId, secondPageId, notebookId))

        server.isOffline = true
        val report = ipad.flush()

        assertEquals("the loop should stop after the first failure", 1, report.failures.size)
        assertEquals(
            "everything still in the outbox has to be reported, not only the attempt that failed",
            setOf(pageId, secondPageId, notebookId),
            report.stillDirty.toSet(),
        )
        assertEquals(
            "and the count has to agree with the outbox itself",
            report.stillDirty.size,
            ipad.pendingCount,
        )
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
        assertEquals(12, report.deletionsHeldBack)
        assertTrue(report.pushed.isEmpty())
        assertTrue("nothing should have reached the server", server.documentIds().isEmpty())
    }

    /**
     * The guard asks whether these deletions are most of the library. It used to ask that of
     * `revs`, which is never pruned — so a device that had seen a hundred notebooks come and go
     * counted all hundred, and deleting the ten it actually had left no longer looked like most of
     * anything. A guard that grows unable to trip is worse than none, since it still reads as one.
     */
    @Test
    fun the_deletion_guard_measures_against_what_the_device_holds() = runBlocking {
        // Two hundred notebooks this device pushed and has since stopped holding. They are gone
        // from the library but still named in `revs`, which nothing prunes.
        val old = (0 until 200).map { index ->
            val id = CouchDocId.notebook("old$index")
            ipadStore.set(id, CouchDocBody.Notebook(notebook("old", emptyList(), 1, "ipad")))
            id
        }
        ipad.markDirty(old)
        ipad.flush()
        old.forEach { ipadStore.remove(it) }

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
        assertTrue("wiping the whole library should still ask", report.blockedByDeletionGuard)
    }

    /**
     * The guard exists to question a suspicious *deletion*. Stopping the rest of the queue with it
     * meant a drawing could not sync either — and since the confirmation the warning asks for does
     * not exist yet, that was a permanent stall rather than a prompt.
     */
    @Test
    fun the_deletion_guard_does_not_hold_back_ordinary_edits() = runBlocking {
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
        ipadStore.set(
            pageId,
            CouchDocBody.Page(page(listOf(stroke("s1", 1, "ipad")), updatedAt = 5, by = "ipad"))
        )
        ipad.markDirty(ids + pageId)

        val report = ipad.flush()
        assertTrue(report.blockedByDeletionGuard)
        assertEquals("the drawing should still have gone out", listOf(pageId), report.pushed)
        assertEquals(listOf(pageId), server.documentIds())
        assertFalse(report.stillDirty.contains(pageId))
    }

    /**
     * Twelve notebook tombstones, which is what the guard is built to notice. Returns the ids in
     * the order they were created; the report's held set is in push order, so compare as sets.
     */
    private fun stageMassDeletion(count: Int = 12, from: Int = 0): List<String> =
        (from until from + count).map { index ->
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

    /**
     * A count is something to warn with; the ids are what a decision needs. Without them the UI can
     * say "twelve deletions are held" and then has nothing to hand back when the user answers.
     */
    @Test
    fun the_guard_reports_which_deletions_it_held() = runBlocking {
        val ids = stageMassDeletion()
        ipad.markDirty(ids)

        val report = ipad.flush()

        assertEquals(ids.toSet(), report.heldDeletions.toSet())
        assertEquals(ids.size, report.deletionsHeldBack)
        assertTrue(report.blockedByDeletionGuard)
    }

    /** "Delete them on the server too": the named ids go out, and nothing else changes. */
    @Test
    fun approved_deletions_are_sent_on_the_next_flush() = runBlocking {
        val ids = stageMassDeletion()
        ipad.markDirty(ids)
        val held = ipad.flush().heldDeletions
        assertTrue("nothing should have reached the server yet", server.documentIds().isEmpty())

        ipad.approveHeldDeletions(held)
        val report = ipad.flush()

        assertFalse("the guard must let an answered batch through", report.blockedByDeletionGuard)
        assertEquals(ids.toSet(), report.pushed.toSet())
        assertEquals(ids.toSet(), server.documentIds().toSet())
        ids.forEach { assertTrue("$it should be a tombstone on the server", server.isDeleted(it)) }
        assertEquals("the outbox must drain", 0, ipad.pendingCount)
    }

    /** ...and *only* the named ids: approval is set-scoped, not a blanket exemption. */
    @Test
    fun approving_some_deletions_leaves_the_rest_held() = runBlocking {
        val ids = stageMassDeletion()
        ipad.markDirty(ids)
        ipad.flush()

        val approved = ids.take(3)
        ipad.approveHeldDeletions(approved)
        val report = ipad.flush()

        assertEquals(approved.toSet(), report.pushed.toSet())
        assertEquals(approved.toSet(), server.documentIds().toSet())
        assertEquals(
            "the deletions nobody approved must still be waiting",
            (ids - approved.toSet()).toSet(),
            report.heldDeletions.toSet(),
        )
    }

    /**
     * The one property that makes this a confirmation rather than a setting. A blanket "stop
     * guarding" flag would be the obvious implementation and would disarm the guard for the *next*
     * device-wipe too, which is the accident it exists to catch.
     */
    @Test
    fun approval_does_not_carry_over_to_a_later_batch() = runBlocking {
        val first = stageMassDeletion()
        ipad.markDirty(first)
        ipad.approveHeldDeletions(ipad.flush().heldDeletions)
        ipad.flush()
        assertEquals("the approved batch should have gone", 0, ipad.pendingCount)

        // Later, a different set of notebooks is deleted — a restore gone wrong, a wiped database.
        // Larger than the first batch on purpose: the tombstones already pushed are still part of
        // what this device holds, so they sit in the guard's denominator too.
        val second = stageMassDeletion(count = 20, from = 100)
        ipad.markDirty(second)
        val report = ipad.flush()

        assertTrue("a fresh batch has to ask again", report.blockedByDeletionGuard)
        assertEquals(second.toSet(), report.heldDeletions.toSet())
        assertEquals(
            "and none of it may reach the server",
            first.toSet(),
            server.documentIds().toSet(),
        )
    }

    /**
     * "Keep them on the server": both records go — the tombstone and the outbox row — and nothing
     * is published. The notebooks are still on the server, which is what brings them back on the
     * next pull; that is the undo, not a side effect.
     */
    @Test
    fun discarded_deletions_leave_nothing_to_send() = runBlocking {
        val ids = stageMassDeletion()
        ipad.markDirty(ids)
        val held = ipad.flush().heldDeletions

        ipad.discardHeldDeletions(held)

        assertEquals("the outbox must be empty", 0, ipad.pendingCount)
        ids.forEach { assertNull("$it's tombstone should be gone", ipadStore.body(it)) }

        val report = ipad.flush()
        assertTrue(report.pushed.isEmpty())
        assertFalse(report.blockedByDeletionGuard)
        assertTrue("nothing may reach the server", server.documentIds().isEmpty())
    }

    /** And the server's copy comes back, because the server was never told anything. */
    @Test
    fun a_discarded_deletion_returns_from_the_server_on_the_next_pull() = runBlocking {
        // The notebook exists on the server, pushed by the peer.
        booxStore.set(notebookId, CouchDocBody.Notebook(notebook("notes", listOf("p1"), 1, "boox")))
        boox.markDirty(listOf(notebookId))
        boox.flush()
        ipad.pull()

        // The iPad deletes it as part of a batch big enough to trip the guard, then changes its mind.
        val ids = stageMassDeletion(count = 11, from = 200) + notebookId
        ipadStore.set(notebookId, tombstone(at = 10, by = "ipad"))
        ipad.markDirty(ids)
        val held = ipad.flush().heldDeletions
        assertTrue(held.contains(notebookId))
        ipad.discardHeldDeletions(held)

        ipad.pull()

        assertFalse("the server never heard about it", server.isDeleted(notebookId))
        assertEquals(
            "so its copy comes back — that is the undo",
            "notes",
            ipadStore.notebook(notebookId)?.title,
        )
    }

    /**
     * The notebook-level twin of [erasure_on_one_device_sticks_on_the_other]. `mergeNotebook` is an
     * add-wins union, so a manifest that merely stopped naming a page has the peer's copy appended
     * straight back onto it — deleting a page on the BOOX was guaranteed to come back from the iPad
     * until the removal was recorded as a tombstone (protocol §6.6).
     */
    @Test
    fun page_deleted_on_one_device_stays_deleted_on_the_other() = runBlocking {
        ipadStore.set(
            notebookId,
            CouchDocBody.Notebook(notebook("notes", listOf("p1", "p2"), 3, "ipad"))
        )
        ipad.markDirty(listOf(notebookId))
        ipad.flush()
        boox.pull()
        assertEquals(listOf("p1", "p2"), booxStore.notebook(notebookId)?.pageIds)

        booxStore.deletePage(notebookId, "p2", deletedAt = stamp(20))
        boox.markDirty(listOf(notebookId))
        boox.flush()

        ipad.pull()
        assertEquals(listOf("p1"), ipadStore.notebook(notebookId)?.pageIds)
        assertEquals(
            "the deletion has to be remembered, not only applied",
            listOf("p2"),
            ipadStore.deletedPages(notebookId).map { it.id },
        )

        // The iPad listed p2 a moment ago; re-pushing must not put it back.
        ipad.markDirty(listOf(notebookId))
        ipad.flush()
        boox.pull()
        assertEquals(listOf("p1"), booxStore.notebook(notebookId)?.pageIds)
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

    // All five of the cases below used to be one bare `continue`, so the pull reported success and
    // the page simply had a hole in it. They stay non-fatal — the ink is applied, and an image on
    // its way is a picture that has not appeared yet — but the pull now says which one happened.

    /**
     * The commonest case, and the one that resolves itself: a peer wrote the page before its bytes
     * finished uploading.
     */
    @Test
    fun `a picture the server does not hold yet is reported as missing`() = runBlocking {
        // The page names a picture; the bytes were never uploaded.
        ipadStore.set(pageId, CouchDocBody.Page(pageWithPicture(updatedAt = 5, by = "ipad")))
        ipad.markDirty(listOf(pageId))
        ipad.flush()

        val pulled = boox.pull()

        assertEquals(listOf(pictureId), pulled.missingAssets)
        assertTrue(pulled.corruptAssets.isEmpty())
        assertTrue(pulled.hasAssetProblems)
        assertTrue("the page and its ink still arrived", pulled.applied.contains(pageId))
    }

    @Test
    fun `a download that failed is reported with its reason and stays retriable`() = runBlocking {
        ipadStore.placePicture(by = "ipad")
        ipad.markDirty(listOf(pageId))
        ipad.flush()

        server.failingDocumentIds["$pictureId/${CouchAssetId.BLOB_NAME}"] = 503
        val pulled = boox.pull()

        assertTrue(pulled.fetchedAssets.isEmpty())
        assertNotNull(
            "the reason should be reported, not lost", pulled.assetFailures[pictureId])
        assertTrue(pulled.hasAssetProblems)
        assertEquals(
            "and it is still owed, so still retried",
            listOf(pictureId),
            booxStore.missingAssetIds(),
        )
    }

    /**
     * The id is a promise about the bytes. Bytes that break it must never be stored — writing them
     * would file content under a name that does not describe it, and the next push would upload it
     * under that name.
     */
    @Test
    fun `bytes that do not match their digest are reported as corrupt and not stored`() =
        runBlocking {
            ipadStore.placePicture(by = "ipad")
            ipad.markDirty(listOf(pageId))
            ipad.flush()

            // The server now serves different bytes under the same content-addressed id.
            server.replaceAttachment(pictureId, "not the picture at all".toByteArray())
            val pulled = boox.pull()

            assertEquals(listOf(pictureId), pulled.corruptAssets)
            assertTrue(pulled.fetchedAssets.isEmpty())
            assertNull("corrupt bytes must not be written", booxStore.body(pictureId))
            assertNotNull(pulled.assetFailures[pictureId])
        }

    @Test
    fun `a store that will not write the blob is reported rather than swallowed`() = runBlocking {
        ipadStore.placePicture(by = "ipad")
        ipad.markDirty(listOf(pageId))
        ipad.flush()

        booxStore.failWrites(pictureId)
        val pulled = boox.pull()

        assertTrue(pulled.fetchedAssets.isEmpty())
        assertNotNull(pulled.assetFailures[pictureId])
        assertNull(booxStore.body(pictureId))
    }

    /**
     * The warning has to be able to go away on its own, or it would outlive the problem: the set is
     * derived from the store on every pull rather than accumulated.
     */
    @Test
    fun `a later pull that succeeds reports no asset problems`() = runBlocking {
        ipadStore.set(pageId, CouchDocBody.Page(pageWithPicture(updatedAt = 5, by = "ipad")))
        ipad.markDirty(listOf(pageId))
        ipad.flush()

        assertEquals(listOf(pictureId), boox.pull().missingAssets)

        // The picture finishes uploading from the iPad.
        ipadStore.set(
            pictureId,
            CouchDocBody.Asset(CouchAsset.of(picture, at = stamp(1), updatedBy = "ipad")),
        )
        ipad.markDirty(listOf(pageId))
        ipad.flush()

        val second = boox.pull()
        assertEquals(listOf(pictureId), second.fetchedAssets)
        assertFalse("the warning must clear itself", second.hasAssetProblems)
    }

    // endregion
    /**
     * Computing a merge takes a network round trip, and the pen does not stop for it. A stroke
     * committed while a push was in flight used to be read as "present locally but absent from the
     * merge result" and hard-deleted — no tombstone written, nothing left dirty, so the ink was
     * gone from the device *and* never pushed. The merge may only drop what the merge itself saw.
     */
    @Test
    fun ink_drawn_while_a_push_is_in_flight_survives_the_merge() = runBlocking {
        ipadStore.set(pageId, CouchDocBody.Page(page(listOf(stroke("s0", 0, "ipad")), updatedAt = 1, by = "ipad")))
        ipad.markDirty(listOf(pageId))
        ipad.flush()
        boox.pull()

        // The BOOX writes first, so the iPad's next push takes a 409 and has to merge.
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
        boox.flush()

        // The user draws on the iPad, and goes on drawing *during* the merge round trip: the store
        // hands the engine its snapshot, then a further stroke lands before `apply` is called.
        val drawing = ipadStore.page(pageId)!!
        ipadStore.set(
            pageId,
            CouchDocBody.Page(
                drawing.copy(
                    strokes = drawing.strokes + stroke("s-ipad", 10, "ipad"),
                    updatedAt = stamp(10), updatedBy = "ipad",
                )
            )
        )
        ipad.markDirty(listOf(pageId))
        // A flush reads the page more than once — once to scan it for assets while ordering the
        // queue, then again in `push` itself. It is that second read the merge is built from, so
        // that is the one the stroke has to land just after; interrupting the first would simply
        // put it in the snapshot and prove nothing.
        var loads = 0
        ipadStore.onLoad = {
            loads += 1
            if (loads == 2) {
                val mid = ipadStore.page(pageId)!!
                ipadStore.set(
                    pageId,
                    CouchDocBody.Page(
                        mid.copy(
                            strokes = mid.strokes + stroke("s-midflight", 12, "ipad"),
                            updatedAt = stamp(12), updatedBy = "ipad",
                        )
                    )
                )
                ipadStore.onLoad = null
            }
        }

        ipad.flush()

        assertTrue(
            "the stroke drawn during the merge must not be deleted",
            ipadStore.page(pageId)!!.strokes.map { it.id }.contains("s-midflight"),
        )
    }

    /**
     * A longpoll parks for the best part of a minute by design. While the engine held its lock
     * across that wait, every local edit queued behind it: `markDirty` and the flush after it only
     * ran once the peer happened to write something or the window expired, so sync-on-save was
     * paced by the other device rather than by the user's pen.
     */
    @Test(timeout = 10_000)
    fun a_local_edit_pushes_while_the_change_feed_is_parked() = runBlocking {
        val parked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val gated = object : CouchTransport {
            override fun send(request: CouchRequest): CouchResponse {
                if (request.query.any { it.name == "feed" && it.value == "longpoll" }) {
                    parked.countDown()
                    release.await()
                }
                return server.send(request)
            }
        }
        val engine = CouchSyncEngine(
            CouchDbClient(gated, database = "notes"), ipadStore, deviceId = "ipad"
        )

        val feed = launch(Dispatchers.IO) { engine.pull(longpoll = true) }
        assertTrue("the feed should have reached the server", parked.await(5, TimeUnit.SECONDS))

        // The feed is now parked mid-request. A push must not have to wait for it.
        ipadStore.set(pageId, CouchDocBody.Page(page(listOf(stroke("s1", 1, "ipad")), updatedAt = 5, by = "ipad")))
        engine.markDirty(listOf(pageId))
        val report = withTimeout(5_000) { engine.flush() }

        assertEquals(listOf(pageId), report.pushed)
        assertTrue(server.documentIds().contains(pageId))

        release.countDown()
        feed.join()
    }




    private fun tombstone(at: Int, by: String) = CouchDocBody.Deleted(
        CouchDeletedDoc(type = CouchDocType.NOTEBOOK, deletedAt = stamp(at), updatedBy = by)
    )


    /**
     * A catch-up from `0` — a fresh install, or any device whose checkpoint was lost — used to ask
     * for the whole library in one response and hold it all in memory before applying any of it.
     * It arrives in batches now, each one checkpointed as it lands.
     */
    @Test
    fun a_catch_up_longer_than_one_batch_pages_through_the_whole_feed() = runBlocking {
        val total = 125
        val ids = (0 until total).map { index ->
            val id = CouchDocId.page("p$index")
            booxStore.set(
                id,
                CouchDocBody.Page(
                    page(listOf(stroke("s$index", index, "boox")), updatedAt = 5, by = "boox")
                )
            )
            id
        }
        boox.markDirty(ids)
        boox.flush()

        val report = ipad.pull()

        assertEquals("every page should have arrived", total, report.applied.size)
        // The point is the size of each response, not the number of them: an unpaged read is one
        // big response followed by an empty one, which is also two requests.
        assertTrue(
            "no single response should have carried the whole library",
            server.largestChangeBatch <= 100,
        )
        // A second pull has nothing left to do: the checkpoint moved past everything applied.
        assertTrue(ipad.pull().applied.isEmpty())
    }

    /**
     * The same bound applies to a longpoll, which used to be sent with no limit at all.
     *
     * The reasoning was that a longpoll is one wait for one notification — but what it returns is
     * everything that changed *while* it waited, which after a reconnection is the whole backlog,
     * inlined base64 ink and all. And once a full batch comes back there is nothing left to wait
     * for, so the loop drops to normal requests and drains at full speed: parking a fresh longpoll
     * would sit waiting for a *new* change while the ones already queued went uncollected.
     */
    @Test
    fun a_longpoll_backlog_arrives_in_bounded_batches_and_is_drained() = runBlocking {
        val total = 225
        val ids = (0 until total).map { index ->
            val id = CouchDocId.page("p$index")
            booxStore.set(
                id,
                CouchDocBody.Page(
                    page(listOf(stroke("s$index", index, "boox")), updatedAt = 5, by = "boox")
                )
            )
            id
        }
        boox.markDirty(ids)
        boox.flush()

        val report = ipad.pull(longpoll = true)

        assertEquals("the backlog should be drained, not sampled", total, report.applied.size)
        assertTrue(
            "no single longpoll response should have carried the whole backlog",
            server.largestChangeBatch <= 100,
        )
        assertTrue(
            "and the checkpoint moved past all of it",
            ipad.pull(longpoll = true).applied.isEmpty(),
        )
    }

    /**
     * Losing the checkpoint replays the whole feed, which this design calls safe — so the same
     * unreadable document arrives again. Minting fresh ids each time turned every replay into
     * another set of duplicate notebooks in the library.
     */
    @Test
    fun the_same_unreadable_document_does_not_pile_up_copies() = runBlocking {
        val id = CouchDocId.page("p-broken")
        val body = buildJsonObject {
            put("type", JsonPrimitive("page"))
            put("schema", JsonPrimitive(99))
        }
        server.seedRaw(id, body)

        ipad.pull()
        // The checkpoint is lost — a reinstall, or a cleared state file. The design calls that safe
        // and replays the feed from the start, so the same row arrives a second time.
        val afterStateLoss =
            CouchSyncEngine(CouchDbClient(server, database = "notes"), ipadStore, deviceId = "ipad")
        afterStateLoss.pull()

        assertEquals(listOf(id, id), ipadStore.conflictCopies)
        assertEquals(
            "the same unreadable document should not breed copies",
            1,
            ipadStore.conflictCopyIdentities.size,
        )
    }
    /**
     * Protocol §7. A plain GET of a tombstone is a 404, indistinguishable from a document that
     * never existed — so a pusher that reads it as "absent" retries as a create, and a PUT with no
     * `_rev` over a tombstone succeeds. That silently undoes whatever the peer deleted.
     */
    @Test
    fun a_queued_edit_does_not_resurrect_a_deletion_the_peer_already_made() = runBlocking {
        ipadStore.set(notebookId, CouchDocBody.Notebook(notebook("notes", listOf("p1"), 1, "ipad")))
        ipad.markDirty(listOf(notebookId))
        ipad.flush()
        boox.pull()

        // The BOOX edits it offline, at an instant *before* the deletion happens.
        val known = booxStore.notebook(notebookId)!!
        booxStore.set(
            notebookId,
            CouchDocBody.Notebook(known.copy(title = "renamed offline", updatedAt = stamp(5)))
        )
        boox.markDirty(listOf(notebookId))

        // Meanwhile the iPad deletes it, and that lands first.
        ipadStore.set(notebookId, tombstone(at = 10, by = "ipad"))
        ipad.markDirty(listOf(notebookId))
        ipad.flush()
        assertTrue(server.isDeleted(notebookId))

        // The BOOX now flushes its older edit. The deletion is newer, so it stands.
        boox.flush()

        assertTrue("the deletion must survive the peer's queued edit", server.isDeleted(notebookId))
        assertTrue(booxStore.body(notebookId)?.isDeleted ?: false)
        assertEquals("nothing should be left waiting", 0, boox.pendingCount)
    }

    /**
     * Protocol §7. CouchDB answers 409 to a `_deleted` write over an already-deleted document even
     * when the revision is current, so a pusher that keeps retrying burns its attempts and leaves
     * the id dirty forever — every later flush replaying the same doomed requests.
     */
    @Test
    fun both_devices_deleting_the_same_document_settles_instead_of_wedging_the_outbox() =
        runBlocking {
            ipadStore.set(
                notebookId,
                CouchDocBody.Notebook(notebook("notes", listOf("p1"), 1, "ipad"))
            )
            ipad.markDirty(listOf(notebookId))
            ipad.flush()
            boox.pull()

            // Both devices delete it independently, at different instants.
            ipadStore.set(notebookId, tombstone(at = 10, by = "ipad"))
            ipad.markDirty(listOf(notebookId))
            booxStore.set(notebookId, tombstone(at = 12, by = "boox"))
            boox.markDirty(listOf(notebookId))

            ipad.flush()
            val report = boox.flush()

            assertTrue("the second deletion should not fail", report.failures.isEmpty())
            assertTrue(report.stillDirty.isEmpty())
            assertEquals("the outbox must drain", 0, boox.pendingCount)
            assertTrue(server.isDeleted(notebookId))
        }

    /**
     * A cleartext URL under Android's network security policy fails here — thrown by the platform
     * before a socket is opened, as an IOException like any other. Reported as [CouchError.Transport]
     * it becomes "you are offline", which is both wrong and unactionable: the retry loop then hides
     * the misconfiguration behind a message promising it will heal itself.
     */
    @Test
    fun a_policy_rejection_is_blocked_rather_than_offline() = runBlocking {
        val refused = object : CouchTransport {
            override fun send(request: CouchRequest): CouchResponse =
                throw UnknownServiceException(
                    "CLEARTEXT communication to 192.168.0.100 not permitted by network security policy"
                )
        }
        val engine = CouchSyncEngine(
            CouchDbClient(refused, database = "notes"), ipadStore, deviceId = "ipad",
        )
        ipadStore.set(pageId, CouchDocBody.Page(page(updatedAt = 5, by = "ipad")))
        engine.markDirty(listOf(pageId))

        val failure = engine.flush().failures[pageId]
        assertTrue("expected a blocked() failure, got $failure", failure!!.startsWith("blocked("))
        assertTrue("the reason should name the policy", failure.contains("CLEARTEXT"))
        assertFalse(
            "retrying cannot lift a policy rejection",
            CouchError.Blocked("cleartext").isRetriable,
        )
        // The work must survive: the user fixes the URL and the page still pushes.
        assertEquals(1, engine.pendingCount)
    }
}
