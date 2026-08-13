package com.ethran.notable.sync.couch

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A feed batch may only be applied if the checkpoint it was fetched from is still current.
 *
 * The engine releases its lock across the feed request so a long poll cannot stall a push, which
 * means two pulls can overlap: a foreground catch-up runs to completion while the long poll waits,
 * and the long poll's answer then describes an earlier moment than the engine does.
 *
 * The checkpoint was already protected against that. [CouchSyncEngine.revs] was not — and it is the
 * reason the *whole batch* has to be dropped rather than only its `last_seq`. Mirrors bopa's
 * `CouchCheckpointTests.swift`.
 */
class CouchCheckpointTest {

    /**
     * Answers `_changes` from the state at the moment the request was *made*, then holds the
     * response until released — which is what makes a long poll's answer stale by the time it
     * lands. Everything else passes straight through.
     *
     * Note the order: the inner call happens first, unlike `PullDoesNotBlockPushTest`'s holder,
     * which blocks before asking and so answers with whatever the server holds at release time.
     */
    private class StaleFeedTransport(private val inner: CouchTransport) : CouchTransport {
        val arrived = CountDownLatch(1)
        private val release = CountDownLatch(1)

        fun releaseFeed() = release.countDown()

        override fun send(request: CouchRequest): CouchResponse {
            val isLongpoll = request.path.endsWith("_changes") &&
                request.query.any { it.name == "feed" && it.value == "longpoll" }
            if (!isLongpoll) return inner.send(request)

            // Captured now, delivered later.
            val response = inner.send(request)
            arrived.countDown()
            release.await(10, TimeUnit.SECONDS)
            return response
        }
    }

    private fun engineWith(transport: CouchTransport, store: CouchLocalStore) =
        CouchSyncEngine(CouchDbClient(transport, database = "notes"), store, deviceId = "boox")

    /** `createdAt` is not optional on [CouchNotebook]; without it the row decodes to nothing and
     *  lands as a conflict copy rather than as an applied document. */
    private fun notebookJson(title: String) = buildJsonObject {
        put("type", JsonPrimitive(CouchDocType.NOTEBOOK))
        put("schema", JsonPrimitive(COUCH_SCHEMA_VERSION))
        put("title", JsonPrimitive(title))
        put("createdAt", JsonPrimitive("2026-02-01T00:00:00Z"))
        put("updatedAt", JsonPrimitive("2026-02-01T00:00:0${title.length % 10}Z"))
        put("updatedBy", JsonPrimitive("ipad"))
    }

    /**
     * `revs` caches "the revision the next push must build on". Writing an older revision into it
     * moves it backwards, and the next push then quotes a stale `_rev`, takes a 409 it did not
     * need, and stops recognising this device's own writes as echoes. Merges being idempotent says
     * nothing about that: the argument for re-applying rows was about *content*.
     */
    @Test
    fun `a longpoll that lost the race does not regress a revision`(): Unit = runBlocking {
        val server = FakeCouchTransport()
        val holding = StaleFeedTransport(server)
        val engine = engineWith(holding, FakeLocalStore())

        val notebookId = CouchDocId.notebook("nb1")
        server.seedRaw(notebookId, notebookJson("first"))
        val staleRev = server.revision(notebookId)

        // The longpoll reads the server as it is now — nb1 at its first revision — and is held.
        val longpoll = async(Dispatchers.IO) { engine.pull(longpoll = true) }
        assertTrue(withContext(Dispatchers.IO) { holding.arrived.await(5, TimeUnit.SECONDS) })

        // While it waits, nb1 is edited and a catch-up pull takes the newer revision.
        server.seedRaw(notebookId, notebookJson("second"))
        val freshRev = server.revision(notebookId)
        assertNotEquals(staleRev, freshRev)
        val catchUp = engine.pull(longpoll = false)
        assertEquals(freshRev, engine.currentState.revs[notebookId])

        // Now the held longpoll lands, carrying the first revision.
        holding.releaseFeed()
        val report = longpoll.await()

        assertEquals(
            "the losing batch must not write its older revision over the winner's",
            freshRev,
            engine.currentState.revs[notebookId],
        )
        assertEquals(catchUp.lastSeq, engine.currentState.lastSeq)
        assertEquals(1, report.discardedStaleBatches)
        assertTrue("a discarded batch applied nothing", report.applied.isEmpty())
    }

    /**
     * The reverse order — no race at all — must still apply normally, or the guard above would be
     * indistinguishable from a client that never pulls.
     */
    @Test
    fun `a pull that won the race applies its rows`(): Unit = runBlocking {
        val server = FakeCouchTransport()
        val engine = engineWith(server, FakeLocalStore())

        val notebookId = CouchDocId.notebook("nb1")
        server.seedRaw(notebookId, notebookJson("only"))
        val report = engine.pull(longpoll = false)

        assertEquals(0, report.discardedStaleBatches)
        assertEquals(server.revision(notebookId), engine.currentState.revs[notebookId])
        assertTrue(report.applied.contains(notebookId))
    }
}
