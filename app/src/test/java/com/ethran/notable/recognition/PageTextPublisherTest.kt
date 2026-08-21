package com.ethran.notable.recognition

import com.ethran.notable.data.db.PageText
import com.ethran.notable.sync.couch.CouchQueryItem
import com.ethran.notable.sync.couch.CouchRequest
import com.ethran.notable.sync.couch.CouchResponse
import com.ethran.notable.sync.couch.CouchTransport
import com.ethran.notable.sync.couch.FakeCouchTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

/**
 * Publishing recognized text, and the guard around the write.
 *
 * Two devices recognize the same ink with two different engines, and they will not agree on the
 * wording. Without a guard, each would see text it did not write, replace it, and be replaced in
 * turn — forever, over the network, on battery. Everything here is about that not happening.
 */
class PageTextPublisherTest {

    private val database = "notes_text"

    private fun publisher(
        transport: CouchTransport,
        deviceId: String = "boox",
    ) = PageTextPublisher(transport, database, deviceId)

    private fun text(
        pageId: String = "page-1",
        body: String = "milk, eggs",
        engine: String = ENGINE_MYSCRIPT,
        language: String? = "en_US",
        recognizedClock: Long = 1_000L,
        updatedAt: Long = 2_000L,
    ) = PageText(
        pageId = pageId,
        text = body,
        engine = engine,
        language = language,
        recognizedClock = recognizedClock,
        updatedAt = Date(updatedAt),
    )

    private fun PageTextPublisher.publish(
        text: PageText,
        notebookId: String? = "book-1",
        pageTitle: String? = null,
    ) = publish(text, notebookId, pageTitle, Date(text.recognizedClock))

    /** The stored document, as the server holds it. */
    private fun stored(transport: FakeCouchTransport, pageId: String = "page-1"): String {
        val response = transport.send(
            CouchRequest(method = "GET", path = "/$database/${PageTextPublisher.documentId(pageId)}")
        )
        assertEquals(200, response.status)
        return response.body.toString(Charsets.UTF_8)
    }

    // MARK: The happy path

    @Test
    fun `text reaches an empty server`() {
        val server = FakeCouchTransport()

        val outcome = publisher(server).publish(text())

        assertEquals(PublishOutcome.PUBLISHED, outcome)
        assertTrue(stored(server).contains("milk, eggs"))
    }

    @Test
    fun `the document carries what a reader needs to file it`() {
        val server = FakeCouchTransport()

        publisher(server).publish(text(), notebookId = "book-7", pageTitle = "Groceries")

        val document = stored(server)
        // The plugin files text by notebook and titles the section from the page, and asking the
        // library for either would mean reading a page document full of stroke data to get it.
        assertTrue(document.contains("\"notebookId\":\"book-7\""))
        assertTrue(document.contains("\"pageTitle\":\"Groceries\""))
        assertTrue(document.contains("\"pageId\":\"page-1\""))
        assertTrue(document.contains("\"updatedBy\":\"boox\""))
    }

    @Test
    fun `newer text replaces older text from this device`() {
        val server = FakeCouchTransport()
        val publisher = publisher(server)
        publisher.publish(text(body = "milk", recognizedClock = 1_000L))

        val outcome = publisher.publish(text(body = "milk, eggs", recognizedClock = 5_000L))

        assertEquals(PublishOutcome.PUBLISHED, outcome)
        assertTrue(stored(server).contains("milk, eggs"))
    }

    // MARK: The guard

    @Test
    fun `text describing older ink does not replace text describing newer ink`() {
        // The iPad recognized ink this device has not seen yet. Publishing over it would lose the
        // better answer and, worse, invite the iPad to publish over this one straight back.
        val server = FakeCouchTransport()
        publisher(server, deviceId = "ipad").publish(text(body = "from the iPad", recognizedClock = 9_000L))

        val outcome = publisher(server).publish(text(body = "stale", recognizedClock = 1_000L))

        assertEquals(PublishOutcome.ALREADY_CURRENT, outcome)
        assertTrue(stored(server).contains("from the iPad"))
    }

    @Test
    fun `the other engine's reading of the same ink is left alone`() {
        // This is the loop the whole guard exists to prevent: same ink, different wording, and
        // both devices convinced the other's copy is wrong.
        val server = FakeCouchTransport()
        publisher(server, deviceId = "ipad").publish(
            text(body = "Apple's wording", engine = "vision", recognizedClock = 4_000L, updatedAt = 4_100L)
        )

        val outcome = publisher(server).publish(
            text(body = "MyScript's wording", recognizedClock = 4_000L, updatedAt = 4_050L)
        )

        assertEquals(PublishOutcome.ALREADY_CURRENT, outcome)
        assertTrue(stored(server).contains("Apple's wording"))
    }

    @Test
    fun `re-recognizing the same ink to the same text writes nothing`() {
        val server = FakeCouchTransport()
        val publisher = publisher(server)
        publisher.publish(text(updatedAt = 2_000L))
        val before = server.requestLog.count { it.first == "PUT" }

        val outcome = publisher.publish(text(updatedAt = 9_000L))

        assertEquals(PublishOutcome.ALREADY_CURRENT, outcome)
        assertEquals("an unchanged reading was republished", before, server.requestLog.count { it.first == "PUT" })
    }

    @Test
    fun `a later reading of the same ink wins when the text actually changed`() {
        val server = FakeCouchTransport()
        publisher(server, deviceId = "ipad").publish(
            text(body = "rnilk", engine = "vision", recognizedClock = 4_000L, updatedAt = 4_000L)
        )

        val outcome = publisher(server).publish(
            text(body = "milk", recognizedClock = 4_000L, updatedAt = 8_000L)
        )

        assertEquals(PublishOutcome.PUBLISHED, outcome)
        assertTrue(stored(server).contains("\"milk\""))
    }

    @Test
    fun `a document with an unreadable clock loses to one that can be read`() {
        // A corrupt or hand-edited document must not become an immovable winner.
        val remote = PageTextDocument(
            id = "pagetext:page-1", pageId = "page-1", text = "corrupt",
            recognizedClock = "not a date", updatedAt = "not a date",
        )
        val local = PageTextDocument(
            id = "pagetext:page-1", pageId = "page-1", text = "good",
            recognizedClock = "2026-08-18T04:11:02.113Z", updatedAt = "2026-08-18T04:11:02.113Z",
        )

        assertTrue(PageTextPublisher.supersedes(local, remote))
        assertTrue(!PageTextPublisher.supersedes(remote, local))
    }

    // MARK: Races and failures

    @Test
    fun `a write that loses a race is retried against what actually landed`() {
        // Someone writes between this publisher's read and its write. CouchDB answers 409, and the
        // retry has to re-read rather than force the stale revision through.
        val server = FakeCouchTransport()
        var interceptedOnce = false
        val racing = object : CouchTransport {
            override fun send(request: CouchRequest): CouchResponse {
                if (request.method == "PUT" && !interceptedOnce) {
                    interceptedOnce = true
                    // A different device gets there first, with text describing the same ink.
                    publisher(server, deviceId = "ipad").publish(
                        text(body = "theirs", engine = "vision", recognizedClock = 1_000L, updatedAt = 1_500L)
                    )
                }
                return server.send(request)
            }
        }

        val outcome = publisher(racing).publish(text(body = "ours", recognizedClock = 1_000L, updatedAt = 9_000L))

        assertEquals(PublishOutcome.PUBLISHED, outcome)
        assertTrue(stored(server).contains("ours"))
    }

    @Test
    fun `losing a race to newer ink stands down instead of fighting`() {
        val server = FakeCouchTransport()
        var interceptedOnce = false
        val racing = object : CouchTransport {
            override fun send(request: CouchRequest): CouchResponse {
                if (request.method == "PUT" && !interceptedOnce) {
                    interceptedOnce = true
                    publisher(server, deviceId = "ipad").publish(
                        text(body = "newer ink", engine = "vision", recognizedClock = 50_000L)
                    )
                }
                return server.send(request)
            }
        }

        val outcome = publisher(racing).publish(text(body = "ours", recognizedClock = 1_000L))

        assertEquals(PublishOutcome.ALREADY_CURRENT, outcome)
        assertTrue(stored(server).contains("newer ink"))
    }

    @Test
    fun `an unreachable server leaves the text unpublished rather than lost`() {
        val server = FakeCouchTransport().apply { isOffline = true }

        val outcome = publisher(server).publish(text())

        // FAILED is what keeps the row pending, so the next foreground retries it.
        assertEquals(PublishOutcome.FAILED, outcome)
    }

    // MARK: Wire format

    @Test
    fun `the document id is derived from the page id`() {
        assertEquals("pagetext:abc-123", PageTextPublisher.documentId("abc-123"))
    }

    @Test
    fun `clocks are written as UTC ISO-8601`() {
        val stamp = PageTextPublisher.iso(Date(1_755_500_000_000L))

        assertTrue("unexpected stamp: $stamp", stamp.matches(Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z""")))
        assertEquals(1_755_500_000_000L, PageTextPublisher.millis(stamp))
    }

    @Test
    fun `an unreadable clock sorts below every readable one`() {
        assertEquals(Long.MIN_VALUE, PageTextPublisher.millis(""))
        assertEquals(Long.MIN_VALUE, PageTextPublisher.millis("yesterday"))
        assertNotEquals(Long.MIN_VALUE, PageTextPublisher.millis(PageTextPublisher.iso(Date(0))))
    }
}
