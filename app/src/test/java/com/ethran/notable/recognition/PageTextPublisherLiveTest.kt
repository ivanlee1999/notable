package com.ethran.notable.recognition

import com.ethran.notable.data.db.PageText
import com.ethran.notable.sync.couch.OkHttpCouchTransport
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.util.Date
import java.util.UUID

/**
 * The publisher against a real CouchDB.
 *
 * The in-memory fake models revision checking faithfully, but it is still a model: the one thing
 * it cannot prove is that CouchDB agrees with it. This suite covers the parts where a difference
 * would be silent — a body CouchDB reads differently from the fake, a 409 raised where none was
 * expected — and self-skips when no server is reachable, the way the sync suites here do.
 *
 * Point it at a server with COUCH_TEST_URL (plus COUCH_TEST_USER / COUCH_TEST_PASSWORD if it
 * needs them). The database is created on demand and its documents are left behind, which is
 * fine for a scratch server and is why it defaults to skipping.
 */
class PageTextPublisherLiveTest {

    private val url: String? = System.getenv("COUCH_TEST_URL")
    private val user: String? = System.getenv("COUCH_TEST_USER")
    private val password: String? = System.getenv("COUCH_TEST_PASSWORD")
    private val database = System.getenv("COUCH_TEST_TEXT_DB") ?: "notable_text_livetest"

    private lateinit var transport: OkHttpCouchTransport

    @Before
    fun requireServer() {
        assumeTrue("COUCH_TEST_URL is not set", !url.isNullOrBlank())
        transport = OkHttpCouchTransport(url!!, user, password, OkHttpClient())
        // PUT on an existing database answers 412, which is as good as having created it.
        transport.send(
            com.ethran.notable.sync.couch.CouchRequest(method = "PUT", path = "/$database")
        )
    }

    private fun publisher(deviceId: String = "boox") =
        PageTextPublisher(transport, database, deviceId)

    private fun text(
        pageId: String,
        body: String,
        recognizedClock: Long = 1_000L,
        updatedAt: Long = 2_000L,
        engine: String = ENGINE_MYSCRIPT,
    ) = PageText(
        pageId = pageId,
        text = body,
        engine = engine,
        language = "en_US",
        recognizedClock = recognizedClock,
        updatedAt = Date(updatedAt),
    )

    private fun publish(
        publisher: PageTextPublisher,
        text: PageText,
    ) = publisher.publish(text, "book-1", "A page", Date(text.recognizedClock))

    @Test
    fun `a create with no revision is accepted`() {
        // The failure this catches: encoding `"_rev": null` reads as a revision claim, and
        // CouchDB answers 409 to every create. The fake caught it once; this proves the fix
        // against the real thing.
        val pageId = UUID.randomUUID().toString()

        val outcome = publish(publisher(), text(pageId, "milk, eggs"))

        assertEquals(PublishOutcome.PUBLISHED, outcome)
    }

    @Test
    fun `an update carries the revision the server gave out`() {
        val pageId = UUID.randomUUID().toString()
        val publisher = publisher()
        publish(publisher, text(pageId, "milk"))

        val outcome = publish(publisher, text(pageId, "milk, eggs", recognizedClock = 5_000L))

        assertEquals(PublishOutcome.PUBLISHED, outcome)
    }

    @Test
    fun `text describing older ink stands down against a real server`() {
        val pageId = UUID.randomUUID().toString()
        publish(publisher("ipad"), text(pageId, "from the iPad", recognizedClock = 9_000L, engine = "vision"))

        val outcome = publish(publisher(), text(pageId, "stale", recognizedClock = 1_000L))

        assertEquals(PublishOutcome.ALREADY_CURRENT, outcome)
    }

    @Test
    fun `the document CouchDB stores is the one the plugin expects to read`() {
        val pageId = UUID.randomUUID().toString()
        publish(publisher(), text(pageId, "milk, eggs"))

        val response = transport.send(
            com.ethran.notable.sync.couch.CouchRequest(
                method = "GET",
                path = "/$database/${PageTextPublisher.documentId(pageId)}",
            )
        )
        val body = response.body.toString(Charsets.UTF_8)

        assertEquals(200, response.status)
        for (field in listOf("pageId", "notebookId", "pageTitle", "text", "engine", "recognizedClock", "updatedBy")) {
            assertTrue("$field is missing from $body", body.contains("\"$field\""))
        }
        // Absent rather than null: the plugin and bopa both treat the two the same, and CouchDB
        // has no reason to carry a key that says nothing.
        assertTrue("null crept into the body: $body", !body.contains("null"))
    }
}
