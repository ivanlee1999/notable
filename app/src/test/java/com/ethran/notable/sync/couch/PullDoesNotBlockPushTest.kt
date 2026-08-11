package com.ethran.notable.sync.couch

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The engine must stay usable while the change feed is waiting.
 *
 * A long poll is a request designed to sit there for up to a minute. The engine used to hold its
 * lock across it, so a quiet server meant no push, no queued edit and no manual sync could get
 * through for that whole window — the device looked frozen mid-sync, and the log stopped after the
 * run marker. These tests hold a feed request open and require the rest of the engine to keep
 * working, which is the property that was missing rather than any particular timeout value.
 */
class PullDoesNotBlockPushTest {

    /**
     * Wraps the fake server and blocks `_changes` until released, standing in for a long poll
     * against a server with nothing to report.
     */
    private class FeedHoldingTransport(private val inner: CouchTransport) : CouchTransport {
        val feedStarted = CountDownLatch(1)
        private val release = CountDownLatch(1)

        fun releaseFeed() = release.countDown()

        override fun send(request: CouchRequest): CouchResponse {
            if (request.path.endsWith("_changes")) {
                feedStarted.countDown()
                // Mirrors a server holding the connection open with nothing to say.
                release.await(10, TimeUnit.SECONDS)
            }
            return inner.send(request)
        }
    }

    private fun engineWith(transport: CouchTransport, store: CouchLocalStore) =
        CouchSyncEngine(CouchDbClient(transport, database = "notes"), store, deviceId = "boox")

    @Test
    fun `a document can be queued while the change feed is waiting`(): Unit = runBlocking {
        val server = FakeCouchTransport()
        val holding = FeedHoldingTransport(server)
        val engine = engineWith(holding, FakeLocalStore())

        val feed = async(Dispatchers.IO) { engine.pull(longpoll = true) }
        assertTrue(
            "the feed request should have started",
            withContext(Dispatchers.IO) { holding.feedStarted.await(5, TimeUnit.SECONDS) }
        )

        // The real symptom: this call used to sit behind the feed for the whole poll window.
        withTimeout(5_000) { engine.markDirty(listOf(CouchDocId.notebook("nb1"))) }

        holding.releaseFeed()
        feed.await()
        assertEquals(1, engine.pendingCount)
    }

    /**
     * The scan that finds documents the server has never seen runs at the start of every sync, so
     * it is the first thing a manual "Sync now" blocked on.
     */
    @Test
    fun `the unsent scan answers while the change feed is waiting`(): Unit = runBlocking {
        val server = FakeCouchTransport()
        val holding = FeedHoldingTransport(server)
        val engine = engineWith(holding, FakeLocalStore())

        val feed = async(Dispatchers.IO) { engine.pull(longpoll = true) }
        assertTrue(withContext(Dispatchers.IO) { holding.feedStarted.await(5, TimeUnit.SECONDS) })

        val unsent = withTimeout(5_000) {
            engine.neverSent(listOf(CouchDocId.notebook("nb1"), CouchDocId.folder("f1")))
        }

        assertEquals(listOf(CouchDocId.notebook("nb1"), CouchDocId.folder("f1")), unsent)
        holding.releaseFeed()
        feed.await()
    }

    /**
     * The checkpoint still has to move, or every pull would replay the whole feed. Splitting the
     * lock made advancing it conditional, so this pins that the ordinary case still advances.
     */
    @Test
    fun `an ordinary pull advances the checkpoint so the next one starts after it`(): Unit = runBlocking {
        val server = FakeCouchTransport()
        val store = FakeLocalStore()
        val engine = engineWith(server, store)

        val first = engine.pull(longpoll = false)
        val second = engine.pull(longpoll = false)

        assertEquals(
            "a second pull must not start from the beginning again",
            first.lastSeq,
            second.lastSeq
        )
        assertTrue("and has nothing left to apply", second.applied.isEmpty())
    }
}
