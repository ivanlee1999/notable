package com.ethran.notable.sync.couch

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The client and engine against a **real** CouchDB, rather than [FakeCouchTransport].
 *
 * The fake encodes what we believe CouchDB does; these tests check that belief — revision
 * handling, the exact 409 conditions, the shape of `_changes` and `last_seq`, and whether a
 * tombstone really comes back on the feed with its body intact. That last pair is where the fake
 * was wrong once already: CouchDB answers **200** (not 201) to a `_deleted` write, so a client
 * accepting only 201/202 fails every deletion against a real server while passing against a mock.
 *
 * Skipped unless `NOTABLE_COUCH_URL` is set, so the normal unit-test run stays hermetic:
 *
 *     NOTABLE_COUCH_URL=http://127.0.0.1:5984 NOTABLE_COUCH_USER=sync \
 *       NOTABLE_COUCH_PASSWORD=… ./gradlew :app:testDebugUnitTest \
 *       --tests 'com.ethran.notable.sync.couch.CouchIntegrationTest'
 *
 * `app/build.gradle` forwards those variables into the test JVM.
 */
class CouchIntegrationTest {

    private lateinit var client: CouchDbClient
    private var namespace = ""

    @Before
    fun setUp() {
        val url = System.getenv("NOTABLE_COUCH_URL")
        assumeTrue("set NOTABLE_COUCH_URL to run the integration tests", !url.isNullOrBlank())
        client = CouchDbClient(
            OkHttpCouchTransport(
                baseUrl = url!!,
                username = System.getenv("NOTABLE_COUCH_USER") ?: "sync",
                password = System.getenv("NOTABLE_COUCH_PASSWORD") ?: "",
                client = httpClient(),
            ),
            database = System.getenv("NOTABLE_COUCH_DATABASE") ?: "notes",
        )
        // Fresh ids per run so repeated runs against the same database cannot collide.
        namespace = UUID.randomUUID().toString().lowercase().take(8)
    }

    private fun httpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    // region Fixtures

    private fun pageId(name: String) = CouchDocId.page("$namespace-$name")
    private fun notebookId(name: String) = CouchDocId.notebook("$namespace-$name")

    private fun stamp(second: Int): String =
        Instant.ofEpochSecond(1_770_000_000L + second).toString()

    private fun stroke(id: String, at: Int, device: String) = CouchStroke(
        id = id, createdAt = stamp(at), updatedAt = stamp(at), deviceId = device,
        pen = "BALLPEN", color = -16_777_216, size = 3.5f,
        top = 0f, bottom = 1f, left = 0f, right = 1f, pointsData = "U0ICD10AAAAA",
    )

    private fun page(strokes: List<CouchStroke>, updatedAt: Int, by: String) = CouchPage(
        notebookId = "$namespace-nb", strokes = strokes,
        createdAt = stamp(0), updatedAt = stamp(updatedAt), updatedBy = by,
    )

    // endregion

    // region Client

    @Test
    fun put_then_get_round_trips_a_page_through_couchdb() = runBlocking {
        val id = pageId("roundtrip")
        val original = page(listOf(stroke("s1", 1, "ipad")), updatedAt = 5, by = "ipad")

        val rev = client.put(id, null, original, CouchPage.serializer())
        assertTrue("first write should be revision 1, got $rev", rev.startsWith("1-"))

        val stored = client.get(id, CouchPage.serializer())
        assertEquals(rev, stored?.rev)
        assertEquals("the page must survive CouchDB byte-for-byte", original, stored?.body)
        assertEquals("U0ICD10AAAAA", stored?.body?.strokes?.first()?.pointsData)
    }

    @Test
    fun get_of_an_absent_document_is_null_rather_than_an_error() = runBlocking {
        assertNull(client.get(pageId("never-written"), CouchPage.serializer()))
    }

    /** The condition the whole push loop is built on: a stale revision must be a 409. */
    @Test
    fun writing_with_a_stale_revision_conflicts() = runBlocking {
        val id = pageId("stale")
        val first = client.put(id, null, page(emptyList(), 1, "ipad"), CouchPage.serializer())
        client.put(id, first, page(emptyList(), 2, "ipad"), CouchPage.serializer())

        try {
            client.put(id, first, page(emptyList(), 3, "boox"), CouchPage.serializer())
            fail("expected a conflict when writing against a superseded revision")
        } catch (_: CouchError.Conflict) {
            // expected
        }
        Unit
    }

    @Test
    fun creating_without_a_revision_over_an_existing_document_conflicts() = runBlocking {
        val id = pageId("exists")
        client.put(id, null, page(emptyList(), 1, "ipad"), CouchPage.serializer())
        try {
            client.put(id, null, page(emptyList(), 2, "boox"), CouchPage.serializer())
            fail("expected a conflict creating over an existing document")
        } catch (_: CouchError.Conflict) {
            // expected
        }
        Unit
    }

    @Test
    fun changes_feed_reports_writes_and_carries_the_document() = runBlocking {
        val before = client.changes(since = "0", longpoll = false)
        val id = pageId("feed")
        val rev = client.put(
            id, null, page(listOf(stroke("s9", 2, "boox")), updatedAt = 7, by = "boox"),
            CouchPage.serializer(),
        )

        val after = client.changes(since = before.lastSeq, longpoll = false)
        val row = after.rows.firstOrNull { it.id == id }
        assertNotNull("the write should appear on the feed", row)
        assertEquals(rev, row?.rev)
        assertNotNull("include_docs should have carried the body", row?.json)
        assertTrue("last_seq should have moved", after.lastSeq != "0")

        val decoded = couchJson.decodeFromJsonElement(CouchPage.serializer(), row!!.json!!)
        assertEquals(listOf("s9"), decoded.strokes.map { it.id })
    }

    /**
     * Deletions must arrive as tombstones that still carry a body, or the peer cannot tell a
     * delete from a document it simply has not seen. This is also the test that fails outright if
     * the client ever goes back to rejecting a 200 from a `_deleted` write.
     */
    @Test
    fun deleted_documents_come_back_as_tombstones_with_their_body() = runBlocking {
        val id = notebookId("deleted")
        val rev = client.put(
            id, null,
            CouchNotebook(
                title = "doomed", pageIds = emptyList(),
                createdAt = stamp(0), updatedAt = stamp(1), updatedBy = "ipad",
            ),
            CouchNotebook.serializer(),
        )
        val before = client.changes(since = "0", longpoll = false)

        client.put(
            id, rev,
            CouchDeletedDoc(type = CouchDocType.NOTEBOOK, deletedAt = stamp(10), updatedBy = "ipad"),
            CouchDeletedDoc.serializer(),
            deleted = true,
        )

        val after = client.changes(since = before.lastSeq, longpoll = false)
        val row = after.rows.firstOrNull { it.id == id }
        assertEquals(true, row?.deleted)
        val body = couchJson.decodeFromJsonElement(CouchDeletedDoc.serializer(), row!!.json!!)
        assertEquals("the tombstone must keep the time of death", stamp(10), body.deletedAt)
    }

    /** The near-real-time path: a longpoll held open should return as soon as a write lands. */
    @Test
    fun longpoll_returns_as_soon_as_a_write_arrives() = runBlocking {
        val start = client.changes(since = "0", longpoll = false)
        val id = pageId("longpoll")

        val waiting = async(Dispatchers.IO) {
            client.changes(since = start.lastSeq, longpoll = true, timeoutMs = 20_000)
        }
        delay(300)
        client.put(id, null, page(emptyList(), 3, "boox"), CouchPage.serializer())

        val began = System.currentTimeMillis()
        val result = waiting.await()
        assertTrue(
            "longpoll should return on the write, not sit until the timeout",
            System.currentTimeMillis() - began < 15_000,
        )
        assertTrue(result.rows.any { it.id == id })
    }

    // endregion

    // region Engine

    /** Two engines, one real server: the scenario that fails over WebDAV today. */
    @Test
    fun two_devices_editing_offline_converge_through_real_couchdb() = runBlocking {
        val id = pageId("converge")
        val ipadStore = FakeLocalStore()
        val booxStore = FakeLocalStore()
        val ipad = CouchSyncEngine(client, ipadStore, deviceId = "ipad")
        val boox = CouchSyncEngine(client, booxStore, deviceId = "boox")

        ipadStore.set(id, CouchDocBody.Page(page(listOf(stroke("s0", 0, "ipad")), 1, "ipad")))
        ipad.markDirty(listOf(id))
        assertEquals(listOf(id), ipad.flush().pushed)
        boox.pull()
        assertEquals(listOf("s0"), booxStore.page(id)?.strokes?.map { it.id })

        // Both devices go offline and draw.
        val ipadPage = ipadStore.page(id)!!
        ipadStore.set(
            id,
            CouchDocBody.Page(
                ipadPage.copy(
                    strokes = ipadPage.strokes + stroke("s-ipad", 10, "ipad"),
                    updatedAt = stamp(10),
                )
            )
        )
        ipad.markDirty(listOf(id))

        val booxPage = booxStore.page(id)!!
        booxStore.set(
            id,
            CouchDocBody.Page(
                booxPage.copy(
                    strokes = booxPage.strokes + stroke("s-boox", 11, "boox"),
                    updatedAt = stamp(11), updatedBy = "boox",
                )
            )
        )
        boox.markDirty(listOf(id))

        // iPad wins the race; the BOOX must merge rather than clobber.
        ipad.flush()
        assertEquals(listOf(id), boox.flush().merged)
        ipad.pull()

        val expected = listOf("s-boox", "s-ipad", "s0")
        assertEquals(expected, ipadStore.page(id)?.strokes?.map { it.id }?.sorted())
        assertEquals(expected, booxStore.page(id)?.strokes?.map { it.id }?.sorted())

        // And the server holds the union, not one device's view of it.
        val onServer = client.get(id, CouchPage.serializer())
        assertEquals(expected, onServer?.body?.strokes?.map { it.id }?.sorted())
    }

    @Test
    fun erasure_survives_a_round_trip_through_real_couchdb() = runBlocking {
        val id = pageId("erase")
        val ipadStore = FakeLocalStore()
        val booxStore = FakeLocalStore()
        val ipad = CouchSyncEngine(client, ipadStore, deviceId = "ipad")
        val boox = CouchSyncEngine(client, booxStore, deviceId = "boox")

        ipadStore.set(
            id,
            CouchDocBody.Page(
                page(listOf(stroke("keep", 1, "ipad"), stroke("rub-out", 2, "ipad")), 3, "ipad")
            )
        )
        ipad.markDirty(listOf(id))
        ipad.flush()
        boox.pull()

        val erased = booxStore.page(id)!!
        booxStore.set(
            id,
            CouchDocBody.Page(
                erased.copy(
                    strokes = erased.strokes.filterNot { it.id == "rub-out" },
                    deletedStrokes = listOf(CouchTombstone(id = "rub-out", deletedAt = stamp(20))),
                    updatedAt = stamp(20), updatedBy = "boox",
                )
            )
        )
        boox.markDirty(listOf(id))
        boox.flush()

        ipad.pull()
        assertEquals(listOf("keep"), ipadStore.page(id)?.strokes?.map { it.id })

        // The iPad still had the stroke moments ago; re-pushing must not bring it back.
        ipad.markDirty(listOf(id))
        ipad.flush()
        val onServer = client.get(id, CouchPage.serializer())
        assertEquals(listOf("keep"), onServer?.body?.strokes?.map { it.id })
    }

    // endregion
}
