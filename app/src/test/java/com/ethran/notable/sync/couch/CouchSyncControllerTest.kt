package com.ethran.notable.sync.couch

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * The two pumps — the edit debounce and the change-feed loop — without a server.
 *
 * bopa's `CouchSyncControllerTests.swift` is mirrored case for case, because these behaviours are
 * the ones that decide whether a stroke ever leaves the device, and a divergence between the two
 * apps should surface as the same named failure on both sides.
 *
 * Timing is faked, not slowed down: [FakeSleeper] grants a fixed budget of waits and then throws,
 * so "this loop spins" fails deterministically instead of depending on how fast the machine is.
 */
class CouchSyncControllerTest {

    /** Stands in for the engine. Records calls, and can be told to fail. */
    private class BackendSpy : CouchSyncBackend {
        private val lock = Any()
        private var flushes = 0
        private val pulls = mutableListOf<Boolean>()

        @Volatile
        var flushReport = CouchSyncEngine.FlushReport()

        @Volatile
        var pullReport = CouchSyncEngine.PullReport()

        @Volatile
        var pullError: Throwable? = null

        @Volatile
        var enabled = true

        val markedPages = mutableListOf<String>()

        /** Notebook/folder documents queued without any page being drawn in. */
        val markedDocuments = mutableListOf<String>()
        val deletions = mutableListOf<String>()
        val everythingMarked = AtomicInteger(0)

        /** How many times a sync scanned for documents the server has never seen. */
        val unsentScans = AtomicInteger(0)

        @Volatile
        var unsentCount = 0

        @Volatile
        var unsentError: Throwable? = null

        val flushCount: Int get() = synchronized(lock) { flushes }

        /** The `longpoll` flag of each pull, newest last. */
        val pullCalls: List<Boolean> get() = synchronized(lock) { pulls.toList() }

        override suspend fun isEnabled(): Boolean = enabled

        override suspend fun flush(): CouchSyncEngine.FlushReport {
            synchronized(lock) { flushes += 1 }
            return flushReport
        }

        override suspend fun pull(longpoll: Boolean): CouchSyncEngine.PullReport {
            synchronized(lock) { pulls += longpoll }
            pullError?.let { throw it }
            return pullReport
        }

        override suspend fun markPageDirty(pageId: String) {
            synchronized(lock) { markedPages += pageId }
        }



        override suspend fun markEverythingDirty() {
            everythingMarked.incrementAndGet()
        }

        /** Nothing here reads the badge; the flow just has to exist and stay quiet. */
        override val documentState =
            kotlinx.coroutines.flow.MutableStateFlow<CouchDocumentState?>(null)

        override suspend fun markDocumentDirty(documentId: String) {
            synchronized(lock) { markedDocuments += documentId }
        }

        override suspend fun markUnsentDirty(): Int {
            unsentScans.incrementAndGet()
            unsentError?.let { throw it }
            return unsentCount
        }

        override suspend fun recordDeletion(documentId: String) {
            synchronized(lock) { deletions += documentId }
        }

        /** Ids waved past the mass-deletion guard, newest last. */
        val approvedDeletions = mutableListOf<String>()

        /** Ids whose tombstones were dropped instead of published. */
        val discardedDeletions = mutableListOf<String>()

        override suspend fun approveHeldDeletions(ids: List<String>) {
            synchronized(lock) { approvedDeletions += ids }
        }

        override suspend fun discardHeldDeletions(ids: List<String>) {
            synchronized(lock) { discardedDeletions += ids }
        }

        /** What the durable outbox still holds; the reconnect drain reads it. */
        @Volatile
        var pending = 0

        /** How many times the controller asked, so a test can tell "never asked" from "asked, 0". */
        val pendingReads = AtomicInteger(0)

        override suspend fun pendingCount(): Int {
            pendingReads.incrementAndGet()
            return pending
        }
    }

    /**
     * Runs the loops at test speed. Returns after [pacingMs] for [allowedTicks] calls, then throws
     * [CancellationException] so a runaway loop dies instead of spinning for the whole test window.
     *
     * [pacingMs] is zero wherever the test only cares about *how many* waits happened. Tests that
     * need the loop to still be running when they poke it use a small real pause instead, because a
     * loop whose waits cost nothing burns its whole budget before the test's first assertion.
     */
    private class FakeSleeper(allowedTicks: Int, private val pacingMs: Long = 0) {
        private val lock = Any()
        private var remaining = allowedTicks
        private val waits = mutableListOf<Long>()

        suspend fun sleep(durationMs: Long) {
            synchronized(lock) {
                waits += durationMs
                if (remaining <= 0) throw CancellationException("sleeper budget exhausted")
                remaining -= 1
            }
            if (pacingMs > 0) delay(pacingMs)
        }

        val recorded: List<Long> get() = synchronized(lock) { waits.toList() }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun controller(
        backend: BackendSpy,
        sleeper: FakeSleeper,
        quietMs: Long = 3_000,
    ) = CouchSyncController(
        scope = scope,
        backend = backend,
        clock = CouchSyncClock(
            editQuietPeriodMs = quietMs,
            retryFloorMs = 1_000,
            retryCeilingMs = 60_000,
            idleFloorMs = 500,
            sleep = { sleeper.sleep(it) },
        ),
    )

    /** Real elapsed time, since the controller's own waits are faked out. */
    private fun settle(ms: Long) = runBlocking { delay(ms) }

    // region Push

    @Test
    fun `edits coalesce into a single push`() {
        val backend = BackendSpy()
        val controller = controller(backend, FakeSleeper(allowedTicks = 10))

        controller.noteEdited()
        controller.noteEdited()
        controller.noteEdited()
        // Each call cancels the previous timer, so only the last one survives.
        settle(200)

        assertEquals("a burst of edits should cost one push", 1, backend.flushCount)
    }

    @Test
    fun `pushNow sends immediately and clears a pending timer`() {
        val backend = BackendSpy()
        // Paced, so the timer is genuinely still pending when pushNow lands — which is the whole
        // situation being tested. With instant waits there is no window for a push to pre-empt.
        val controller = controller(backend, FakeSleeper(allowedTicks = 10, pacingMs = 50))

        controller.noteEdited()
        runBlocking { controller.pushNow() }
        settle(200)

        assertEquals("the debounced push should not also fire", 1, backend.flushCount)
    }

    /**
     * Offline is the normal case on a BOOX, not an error state: work stays queued and the message
     * says so rather than implying loss.
     */
    @Test
    fun `offline leaves work queued and says so`() {
        val backend = BackendSpy()
        backend.flushReport = CouchSyncEngine.FlushReport(
            stillDirty = listOf("page:a", "page:b"),
            failures = mapOf("page:a" to "transport(offline)"),
        )
        val controller = controller(backend, FakeSleeper(allowedTicks = 10))

        runBlocking { controller.pushNow() }

        assertEquals(2, controller.pendingCount)
        assertEquals("transport(offline) 2 waiting to sync.", controller.state.value.detail)
    }

    @Test
    fun `a successful push clears pending and stamps`() {
        val backend = BackendSpy()
        backend.flushReport = CouchSyncEngine.FlushReport(pushed = listOf("page:a"))
        val controller = controller(backend, FakeSleeper(allowedTicks = 10))

        runBlocking { controller.pushNow() }

        assertEquals(0, controller.pendingCount)
        assertEquals(CouchSyncController.Status.Idle, controller.status)
        assertTrue(controller.lastSyncedAt != null)
    }

    private val heldIds = (0 until 12).map { "notebook:n$it" }

    private fun guardedReport() = CouchSyncEngine.FlushReport(
        stillDirty = heldIds + listOf("page:p1"),
        heldDeletions = heldIds,
    )

    /**
     * The message has to describe what the guard does, count the deletions it held back rather than
     * the whole queue — those are different numbers, because the queue also carries the ordinary
     * edits the guard deliberately lets through — and point at the two answers that now exist.
     */
    @Test
    fun `the mass deletion guard surfaces as an actionable message`() {
        val backend = BackendSpy()
        backend.flushReport = guardedReport()
        val controller = controller(backend, FakeSleeper(allowedTicks = 10))

        runBlocking { controller.pushNow() }

        val status = controller.status
        assertTrue("the guard should surface as a failure, got $status",
            status is CouchSyncController.Status.Failed)
        val message = (status as CouchSyncController.Status.Failed).message
        assertTrue("the message should name the deletions held back: $message",
            message.contains("12"))
        // It used to say "delete them on your other device to confirm", because that was the only
        // way through. Now there are two buttons, and the message has to name both of them and say
        // where they are — a warning that describes a dead end is worse than one that offers a way
        // out, and the words here have to be the words on the buttons.
        assertTrue("the message should say where to answer: $message",
            message.contains("Settings"))
        assertTrue("the message should offer the destructive answer: $message",
            message.contains("Delete them on the server too"))
        assertTrue("the message should offer the reversible answer: $message",
            message.contains("Keep them on the server"))
    }

    /** The set has to reach the UI, not just its size: both answers act on exactly these ids. */
    @Test
    fun `the held deletions themselves are exposed to the UI`() {
        val backend = BackendSpy()
        backend.flushReport = guardedReport()
        val controller = controller(backend, FakeSleeper(allowedTicks = 10))

        runBlocking { controller.pushNow() }

        assertEquals(heldIds, controller.state.value.heldDeletions)
    }

    @Test
    fun `confirming the held deletions approves exactly them and pushes`() {
        val backend = BackendSpy()
        backend.flushReport = guardedReport()
        val controller = controller(backend, FakeSleeper(allowedTicks = 10))
        runBlocking { controller.pushNow() }
        val flushesBefore = backend.flushCount
        // The engine lets an approved batch through, so the flush this triggers reports them sent
        // rather than held. Without saying so the spy would keep answering "still holding", which
        // is the one thing the real engine cannot do once it has been told.
        backend.flushReport = CouchSyncEngine.FlushReport(pushed = heldIds)

        runBlocking { controller.approveHeldDeletions() }

        assertEquals("only the held ids may be approved", heldIds, backend.approvedDeletions)
        assertTrue("confirming has to actually send them", backend.flushCount > flushesBefore)
        assertTrue(
            "the prompt must not linger once it has been answered",
            controller.state.value.heldDeletions.isEmpty()
        )
    }

    /**
     * Discarding publishes nothing, so there is no flush to report from — and the pending count has
     * to be re-read rather than carried over, because the rows the last report counted are gone.
     */
    @Test
    fun `discarding the held deletions drops them and sends nothing`() {
        val backend = BackendSpy()
        backend.flushReport = guardedReport()
        val controller = controller(backend, FakeSleeper(allowedTicks = 10))
        runBlocking { controller.pushNow() }
        val flushesBefore = backend.flushCount
        backend.pending = 1

        runBlocking { controller.discardHeldDeletions() }

        assertEquals(heldIds, backend.discardedDeletions)
        assertTrue("nothing is published, so nothing is pushed", backend.approvedDeletions.isEmpty())
        assertEquals("discarding must not push", flushesBefore, backend.flushCount)
        assertTrue(controller.state.value.heldDeletions.isEmpty())
        assertEquals("the outbox is the count, not the stale report", 1, controller.pendingCount)
        assertEquals(CouchSyncController.Status.Idle, controller.status)
    }

    /** Nothing held means nothing to answer: neither button may reach the backend on an empty set. */
    @Test
    fun `answering with nothing held does nothing at all`() {
        val backend = BackendSpy()
        val controller = controller(backend, FakeSleeper(allowedTicks = 10))

        runBlocking {
            controller.approveHeldDeletions()
            controller.discardHeldDeletions()
        }

        assertTrue(backend.approvedDeletions.isEmpty())
        assertTrue(backend.discardedDeletions.isEmpty())
        assertEquals(0, backend.flushCount)
    }

    // endregion

    // region Pull loop

    /**
     * The first pull must not be a long poll: a long poll only reports what happens after it
     * starts, so opening with one would miss everything that changed while notable was closed.
     */
    @Test
    fun `the loop catches up before it waits`() {
        val backend = BackendSpy()
        val controller = controller(backend, FakeSleeper(allowedTicks = 10))

        controller.start()
        settle(300)
        controller.stop()

        val calls = backend.pullCalls
        assertTrue("the loop should keep pulling, got $calls", calls.size >= 2)
        assertEquals("the first pull should be a catch-up, not a long poll", false, calls[0])
        assertEquals("subsequent pulls should hold the connection open", true, calls[1])
    }

    @Test
    fun `content the server lacks is pushed back without waiting for the edit timer`() {
        val backend = BackendSpy()
        backend.pullReport = CouchSyncEngine.PullReport(
            applied = listOf("page:a"),
            pushBack = listOf("page:a"),
        )
        val controller = controller(backend, FakeSleeper(allowedTicks = 10))

        controller.start()
        settle(300)
        controller.stop()

        assertTrue(
            "a pull that found local-only content should push it without waiting",
            backend.flushCount >= 1
        )
    }

    /**
     * Reconnecting has to drain the outbox, not merely deliver whatever the feed happened to carry.
     * After a flush that failed offline the documents are still queued; a pull that succeeds is the
     * first proof the network came back, and pushing only on `pushBack` left them waiting for the
     * user to write something else or tap Sync now.
     */
    @Test
    fun `a successful empty pull still drains an outbox left over from being offline`() {
        val backend = BackendSpy()
        // Nothing came down — the peer has not written anything since we lost the connection.
        backend.pullReport = CouchSyncEngine.PullReport()
        backend.pending = 3
        val controller = controller(backend, FakeSleeper(allowedTicks = 10))

        controller.start()
        settle(300)
        controller.stop()

        assertTrue(
            "an empty pull with work still queued should push it, got ${backend.flushCount} pushes",
            backend.flushCount >= 1
        )
    }

    /** ...and stays quiet when there is genuinely nothing to send, rather than pushing on a timer. */
    @Test
    fun `a successful empty pull with an empty outbox pushes nothing`() {
        val backend = BackendSpy()
        backend.pending = 0
        val controller = controller(backend, FakeSleeper(allowedTicks = 10))

        controller.start()
        settle(300)
        controller.stop()

        assertTrue("the outbox should have been consulted", backend.pendingReads.get() >= 1)
        assertEquals("nothing is waiting, so nothing should be sent", 0, backend.flushCount)
    }

    @Test
    fun `a failing pull backs off instead of spinning`() {
        val backend = BackendSpy()
        backend.pullError = CouchError.Transport("offline")
        val sleeper = FakeSleeper(allowedTicks = 4)
        val controller = controller(backend, sleeper)

        controller.start()
        settle(400)
        controller.stop()

        val waits = sleeper.recorded
        assertTrue("a failing pull should wait before retrying", waits.isNotEmpty())
        // Doubling, so a server that is down does not become a request flood.
        if (waits.size >= 2) {
            assertTrue("backoff should grow: $waits", waits[1] > waits[0])
        }
        val status = controller.status
        assertTrue("expected a failure status, got $status",
            status is CouchSyncController.Status.Failed)
        assertTrue(
            (status as CouchSyncController.Status.Failed).message.lowercase().contains("offline"),
            )
    }

    @Test
    fun `a recovered pull clears the previous failure`() {
        val backend = BackendSpy()
        backend.pullError = CouchError.Transport("offline")
        // Paced, so the loop is still alive to notice the server coming back.
        val controller = controller(backend, FakeSleeper(Int.MAX_VALUE, pacingMs = 5))

        controller.start()
        settle(200)
        assertTrue(
            "expected the loop to report a failure first, got ${controller.status}",
            controller.status is CouchSyncController.Status.Failed
        )

        backend.pullError = null
        settle(300)
        controller.stop()

        assertEquals(
            "reaching the server again should clear the error",
            CouchSyncController.Status.Idle, controller.status
        )
    }

    @Test
    fun `start is idempotent and stop ends the loop`() {
        val backend = BackendSpy()
        // Paced, so the loop is genuinely running when `stop()` lands rather than already finished.
        val controller = controller(backend, FakeSleeper(Int.MAX_VALUE, pacingMs = 5))

        controller.start()
        controller.start()
        assertTrue(controller.isRunning)
        settle(200)
        controller.stop()
        assertFalse(controller.isRunning)

        // A request already in flight when stop lands still completes, so allow for one more —
        // what must not happen is the count continuing to climb.
        val afterStop = backend.pullCalls.size
        settle(300)
        assertTrue(
            "stop should really stop it: $afterStop then ${backend.pullCalls.size}",
            backend.pullCalls.size <= afterStop + 1
        )
    }

    /**
     * A long poll is meant to block until something happens. When it does not — a proxy that
     * answers immediately, a server ignoring the timeout — re-issuing at once turns the loop into a
     * hot spin against the server. This was a real defect: 2463 requests in 50ms.
     */
    @Test
    fun `an immediately returning feed does not become a hot loop`() {
        val backend = BackendSpy() // returns an empty report instantly
        val sleeper = FakeSleeper(allowedTicks = 3)
        val controller = controller(backend, sleeper)

        controller.start()
        settle(400)
        controller.stop()

        // Bounded by the sleeper's ticks rather than by how fast the machine is.
        assertTrue(
            "an idle feed should pace itself, got ${backend.pullCalls.size} requests",
            backend.pullCalls.size <= 5
        )
        assertTrue("it should have waited between empty results", sleeper.recorded.isNotEmpty())
    }

    @Test
    fun `a blocked url is reported as configuration rather than as being offline`() {
        val backend = BackendSpy()
        backend.pullError = CouchError.Blocked("CLEARTEXT communication to 192.168.0.100 …")
        val controller = controller(backend, FakeSleeper(allowedTicks = 3))

        controller.start()
        settle(200)
        controller.stop()

        val status = controller.status
        assertTrue("expected a failure, got $status", status is CouchSyncController.Status.Failed)
        val message = (status as CouchSyncController.Status.Failed).message.lowercase()
        assertFalse(
            "the server is reachable; calling this offline sends the user to their router: $message",
            message.contains("offline"),
        )
        assertTrue(
            "say what to change: $message",
            message.contains("http") || message.contains("blocked"),
        )
    }

    @Test
    fun `unauthorized is reported as credentials rather than as being offline`() {
        val backend = BackendSpy()
        backend.pullError = CouchError.Unauthorized
        val controller = controller(backend, FakeSleeper(allowedTicks = 3))

        controller.start()
        settle(200)
        controller.stop()

        val status = controller.status
        assertTrue("expected a failure, got $status", status is CouchSyncController.Status.Failed)
        val message = (status as CouchSyncController.Status.Failed).message.lowercase()
        assertTrue(
            "retrying cannot fix bad credentials, so say that: $message",
            message.contains("username") || message.contains("password")
        )
    }

    @Test
    fun `syncNow catches up and pushes without holding a connection open`() {
        val backend = BackendSpy()
        val controller = controller(backend, FakeSleeper(allowedTicks = 10))

        runBlocking { controller.syncNow() }

        assertEquals("Sync now should not wait on a long poll", listOf(false), backend.pullCalls)
        assertEquals(1, backend.flushCount)
    }

    /**
     * The outbox is fed by edits, and a notebook created and left alone is never edited — so it was
     * never sent, however many times sync ran. Every sync now looks for documents the server has
     * never seen, which is the only thing that catches a document no edit ever queued.
     */
    @Test
    fun `syncNow queues documents the server has never seen`() {
        val backend = BackendSpy()
        backend.unsentCount = 1
        val controller = controller(backend, FakeSleeper(allowedTicks = 10))

        runBlocking { controller.syncNow() }

        assertEquals("every sync should scan for unsent documents", 1, backend.unsentScans.get())
        assertEquals("and then push what it found", 1, backend.flushCount)
    }

    /**
     * A notebook created or renamed without anyone drawing in it still has to travel. Only ink
     * edits used to queue anything, so this was the difference between a name that syncs and one
     * that stays on the device it was typed on.
     */
    @Test
    fun `a notebook change queues that document and starts the debounce`() {
        val backend = BackendSpy()
        val controller = controller(backend, FakeSleeper(allowedTicks = 10))

        controller.noteDocumentChanged(CouchDocId.notebook("nb1"))
        settle(200)

        assertEquals(listOf("notebook:nb1"), backend.markedDocuments)
        assertEquals("the change should have been pushed once the timer elapsed", 1, backend.flushCount)
    }

    /** A scan that throws must not cost the run its pull and push. */
    @Test
    fun `syncNow still runs when the unsent scan fails`() {
        val backend = BackendSpy()
        backend.unsentError = IllegalStateException("database unavailable")
        val controller = controller(backend, FakeSleeper(allowedTicks = 10))

        runBlocking { controller.syncNow() }

        assertEquals(listOf(false), backend.pullCalls)
        assertEquals(1, backend.flushCount)
    }

    // endregion

    // region The app's entry points

    @Test
    fun `a page edit queues the page and starts the debounce`() {
        val backend = BackendSpy()
        val controller = controller(backend, FakeSleeper(allowedTicks = 10))

        controller.notePageEdited("p1")
        settle(200)

        assertEquals(listOf("p1"), backend.markedPages)
        assertEquals("the edit should have been pushed once the timer elapsed", 1, backend.flushCount)
    }

    /**
     * A page deletion moves no page document — the page dies with its notebook's manifest — so
     * nothing else on this path will ever queue the notebook. Without this the tombstone waits for
     * some unrelated edit, which on a device the user has finished with never comes.
     */
    @Test
    fun `deleting a page queues the notebook it left`() {
        val backend = BackendSpy()
        val controller = controller(backend, FakeSleeper(allowedTicks = 10))

        controller.notePageDeleted("nb1")
        settle(200)

        assertEquals(listOf(CouchDocId.notebook("nb1")), backend.markedDocuments)
        assertEquals("the removal should have been pushed once the timer elapsed", 1, backend.flushCount)
    }

    /** WebDAV is still the default: with CouchDB unselected nothing here may touch the network. */
    @Test
    fun `nothing happens while the backend is disabled`() {
        val backend = BackendSpy()
        backend.enabled = false
        val controller = controller(backend, FakeSleeper(allowedTicks = 10))

        controller.notePageEdited("p1")
        controller.noteDeleted(CouchDocId.notebook("nb1"))
        controller.start()
        runBlocking { controller.syncNow() }
        runBlocking { controller.pushEverything() }
        settle(200)

        assertTrue(backend.markedPages.isEmpty())
        assertTrue(backend.deletions.isEmpty())
        assertTrue(backend.pullCalls.isEmpty())
        assertEquals(0, backend.flushCount)
        assertEquals(0, backend.everythingMarked.get())
    }

    @Test
    fun `a deletion is recorded and then pushed`() {
        val backend = BackendSpy()
        val controller = controller(backend, FakeSleeper(allowedTicks = 10))

        controller.noteDeleted(CouchDocId.notebook("nb1"))
        settle(200)

        assertEquals(listOf("notebook:nb1"), backend.deletions)
        assertEquals(1, backend.flushCount)
    }

    @Test
    fun `upload everything marks the whole device dirty and flushes`() {
        val backend = BackendSpy()
        val controller = controller(backend, FakeSleeper(allowedTicks = 10))

        runBlocking { controller.pushEverything() }

        assertEquals(1, backend.everythingMarked.get())
        assertEquals(1, backend.flushCount)
    }

    // endregion
}
