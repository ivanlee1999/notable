package com.ethran.notable.sync.couch

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Pacing of the 409 path. A conflict means another device is writing the same document right now;
 * retrying in a tight loop mostly re-loses the same race — the engine used to burn all five
 * attempts in single-digit milliseconds — and exhaustion was then reported terminal, so the
 * controller scheduled nothing while the feed loop re-triggered a push for the still-dirty
 * document on every pull. Zero backoff anywhere on the one path that is *defined* by contention.
 */
class CouchConflictBackoffTest {

    private val pageId = CouchDocId.page("p1")

    private fun stamp(second: Int): String =
        Instant.ofEpochSecond(1_770_000_000L + second).toString()

    private fun stroke(id: String, at: Int, device: String) = CouchStroke(
        id = id, createdAt = stamp(at), updatedAt = stamp(at), deviceId = device,
        pen = "BALLPEN", color = -16_777_216, size = 3f,
        top = 0f, bottom = 1f, left = 0f, right = 1f, pointsData = "AAA=",
    )

    private fun page(strokes: List<CouchStroke>, updatedAt: Int, by: String) = CouchPage(
        notebookId = "nb1", strokes = strokes,
        createdAt = stamp(0), updatedAt = stamp(updatedAt), updatedBy = by,
    )

    @Test
    fun `a conflicted push waits a growing beat between attempts and exhaustion stays retriable`():
        Unit = runBlocking {
        val server = FakeCouchTransport()
        // Every PUT of the page answers 409 while reads pass through, so each round genuinely
        // merges against the server's copy and the loop runs to exhaustion — the peer that never
        // stops writing.
        val contended = object : CouchTransport {
            override fun send(request: CouchRequest): CouchResponse {
                if (request.method == "PUT" && request.path.contains("page:p1")) {
                    return CouchResponse(
                        status = 409,
                        body = """{"error":"conflict","reason":"Document update conflict."}"""
                            .toByteArray(),
                    )
                }
                return server.send(request)
            }
        }
        server.seed(
            pageId,
            page(listOf(stroke("s-remote", 2, "ipad")), updatedAt = 6, by = "ipad"),
            CouchPage.serializer(),
        )

        val store = FakeLocalStore()
        store.set(
            pageId,
            CouchDocBody.Page(page(listOf(stroke("s-local", 1, "boox")), updatedAt = 5, by = "boox")),
        )
        val waits = mutableListOf<Long>()
        val engine = CouchSyncEngine(
            CouchDbClient(contended, database = "notes"), store, deviceId = "boox",
            sleep = { waits += it },
        )
        engine.markDirty(listOf(pageId))

        val report = engine.flush()

        assertTrue(
            "exhaustion should be reported for the document: ${report.failures}",
            report.failures[pageId].orEmpty().startsWith("conflict("),
        )
        assertTrue(
            "contention resolves itself when the peer's burst ends — retriable with backoff, " +
                "not terminal",
            report.hasRetriableFailure,
        )
        assertEquals(
            "each round should wait longer than the last",
            listOf(250L, 500L, 750L, 1000L),
            waits,
        )
        assertEquals("the work must survive", 1, engine.pendingCount)
    }
}
