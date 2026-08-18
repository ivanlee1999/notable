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
            failureCauses = mapOf("page:a" to CouchError.Transport("offline")),
        )
        val controller = controller(backend, FakeSleeper(allowedTicks = 10))

        runBlocking { controller.pushNow() }

        assertEquals(2, controller.pendingCount)
        assertEquals(
            "Offline — changes are saved and will sync when you reconnect. 2 waiting to sync.",
            controller.state.value.detail,
        )
    }

    /**
     * Flush failures store the engine's stable detail strings, and those used to be put straight
     * into the status caption — the user read "unauthorized" while describe()'s friendly wording
     * sat as dead code on this path. The typed cause travels with the report now, and the caption
     * maps it; the raw detail still goes to the log.
     */
    @Test
    fun `a flush failure reaches the user in words, not engine strings`() {
        val backend = BackendSpy()
        backend.flushReport = CouchSyncEngine.FlushReport(
            stillDirty = listOf("page:a"),
            failures = mapOf("page:a" to "unauthorized"),
            failureCauses = mapOf("page:a" to CouchError.Unauthorized),
        )
        val controller = controller(backend, FakeSleeper(allowedTicks = 10))

        runBlocking { controller.pushNow() }

        val status = controller.status
        assertTrue("expected a failure, got $status", status is CouchSyncController.Status.Failed)
        assertEquals(
            "Sync rejected the username or password.",
            (status as CouchSyncController.Status.Failed).message,
        )
    }

    /**
     * FlushReport and PullReport carry the §1.2 identity observation "which is what makes the
     * staged rollout observable" — and nothing consumed it: a wiped-and-recreated database was
     * detected and nobody was told. The three states a user can act on ride the footer as a
     * non-blocking warning, the same way clock skew does.
     */
    @Test
    fun `a generation change reaches the footer as a warning, not a failure`() {
        val backend = BackendSpy()
        backend.flushReport = CouchSyncEngine.FlushReport(
            pushed = listOf("page:a"),
            databaseIdentity =
                CouchSyncEngine.DatabaseIdentity.GenerationChanged("gen-old", "gen-new"),
        )
        val controller = controller(backend, FakeSleeper(allowedTicks = 10))

        runBlocking { controller.pushNow() }

        val state = controller.state.value
        assertTrue(
            "identity is observed, not enforced: the sync itself succeeded",
            state.status is CouchSyncController.Status.Idle,
        )
        val warning = state.identityWarning
        assertTrue("expected a warning, got null", warning != null)
        assertTrue(
            "the footer line has to carry it: ${state.detail}",
            state.detail.orEmpty().contains(warning!!),
        )
    }

    /** Unknown is the normal state of an older server; warning about it would train blindness. */
    @Test
    fun `an unknown identity is recorded but not surfaced`() {
        val backend = BackendSpy()
        backend.flushReport = CouchSyncEngine.FlushReport(
            pushed = listOf("page:a"),
            databaseIdentity = CouchSyncEngine.DatabaseIdentity.Unknown,
        )
        val controller = controller(backend, FakeSleeper(allowedTicks = 10))

        runBlocking { controller.pushNow() }

        val state = controller.state.value
        assertEquals(CouchSyncEngine.DatabaseIdentity.Unknown, state.databaseIdentity)
        assertEquals("no warning line for the normal case", null, state.identityWarning)
    }

    /** The pull path reports the same observation; it must land the same way. */
    @Test
    fun `a locked database observed on pull reaches the footer`() {
        val backend = BackendSpy()
        backend.pullReport = CouchSyncEngine.PullReport(
            databaseIdentity = CouchSyncEngine.DatabaseIdentity.Locked("rebuilding from the iPad"),
        )
        val controller = controller(backend, FakeSleeper(allowedTicks = 10))

        runBlocking { controller.syncNow() }

        val warning = controller.state.value.identityWarning
        assertTrue("expected the lock to surface, got null", warning != null)
        assertTrue(
            "the reason should be shown: $warning",
            warning!!.contains("rebuilding from the iPad"),
        )
    }

    /** A report without typed causes (an older engine, a fake) still surfaces its raw detail. */
    @Test
    fun `a failure without a typed cause falls back to the raw detail`() {
        val backend = BackendSpy()
        backend.flushReport = CouchSyncEngine.FlushReport(
            stillDirty = listOf("page:a"),
            failures = mapOf("page:a" to "something bespoke"),
        )
        val controller = controller(backend, FakeSleeper(allowedTicks = 10))

        runBlocking { controller.pushNow() }

        assertEquals(
            "something bespoke",
            (controller.status as CouchSyncController.Status.Failed).message,
        )
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

    /**
     * The feed loop drains the outbox after every successful pull, and a longpoll comes round
     * forever — so with a retry already waiting out its backoff, an unconditional drain re-sent
     * the same failing documents at feed pace and made the push backoff a fiction. The drain now
     * yields to a scheduled retry; fresh push-back content still sends immediately.
     */
    @Test
    fun `the feed drain does not preempt a scheduled push retry`() {
        val backend = BackendSpy()
        backend.pending = 1
        backend.flushReport = CouchSyncEngine.FlushReport(
            stillDirty = listOf("page:a"),
            failures = mapOf("page:a" to "conflict(page:a)"),
            hasRetriableFailure = true,
        )
        val controller = CouchSyncController(
            scope = scope,
            backend = backend,
            clock = CouchSyncClock(
                editQuietPeriodMs = 3_000,
                retryFloorMs = 1_000,
                retryCeilingMs = 60_000,
                idleFloorMs = 500,
                // The retry's wait (>= 850ms with jitter) parks forever, so the retry stays
                // genuinely pending while the feed keeps coming round at test speed.
                sleep = { ms ->
                    if (ms >= 800) kotlinx.coroutines.awaitCancellation() else delay(10)
                },
            ),
        )

        controller.start()
        settle(400)
        controller.stop()

        assertTrue(
            "the feed should have come round more than once, got ${backend.pullCalls.size}",
            backend.pullCalls.size >= 2,
        )
        assertEquals(
            "the drain must wait out the scheduled retry instead of re-flushing at feed pace",
            1,
            backend.flushCount,
        )
    }

    // region Backgrounding

    /**
     * `onStop` asks this to decide whether the last push needs a durable continuation, and the
     * commonest case is not a full outbox: someone draws a stroke and immediately leaves the app,
     * so the edit is queued in the engine but the debounce timer that would have sent it never
     * fires. That is exactly the work most at risk of being lost, because it is the most recent —
     * and the pending count alone does not see it.
     */
    @Test
    fun `an edit still waiting on the debounce counts as unfinished work`() {
        val backend = BackendSpy()
        // Paced, so the timer is genuinely still pending when the question is asked.
        val controller = controller(backend, FakeSleeper(allowedTicks = 10, pacingMs = 200))

        assertFalse("nothing has happened yet", controller.hasUnfinishedWork)
        controller.noteEdited()

        assertTrue(
            "an edit whose push has not fired is still owed to the server",
            controller.hasUnfinishedWork,
        )
        controller.stop()
    }

    @Test
    fun `a full outbox counts as unfinished work even with no timer pending`() {
        val backend = BackendSpy()
        backend.flushReport = CouchSyncEngine.FlushReport(stillDirty = listOf("page:p1"))
        val controller = controller(backend, FakeSleeper(allowedTicks = 4), quietMs = 10)

        runBlocking { controller.pushNow() }

        assertTrue(controller.hasUnfinishedWork)
        controller.stop()
    }

    @Test
    fun `a drained outbox with nothing pending needs no durable follow-up`() {
        val backend = BackendSpy()
        val controller = controller(backend, FakeSleeper(allowedTicks = 4), quietMs = 10)

        runBlocking { controller.pushNow() }
        settle(50)

        assertFalse(
            "scheduling a worker with nothing to do is a wakeup the device did not need",
            controller.hasUnfinishedWork,
        )
        controller.stop()
    }

    // endregion

    // region Push backoff

    private fun failingFlush(retriable: Boolean, retryAfterMs: Long? = null) =
        CouchSyncEngine.FlushReport(
            stillDirty = listOf("page:p1"),
            failures = mapOf("page:p1" to "server(503)"),
            hasRetriableFailure = retriable,
            retryAfterMs = retryAfterMs,
        )

    /**
     * A failing outbox has to retry itself. Before this it flushed once, reported the failure, and
     * then waited for the user to edit something else or tap Sync now — on a server that was
     * briefly down, the queued pages simply sat there.
     */
    @Test
    fun `a failed push retries itself with growing delays`() {
        val backend = BackendSpy()
        backend.flushReport = failingFlush(retriable = true)
        val sleeper = FakeSleeper(allowedTicks = 4)
        val controller = controller(backend, sleeper, quietMs = 10)

        runBlocking { controller.pushNow() }
        settle(400)
        controller.stop()

        assertTrue("the outbox should have been tried again", backend.flushCount > 1)
        val waits = sleeper.recorded
        assertTrue("expected several retries: $waits", waits.size >= 2)
        // Jittered, so compare across a doubling rather than between neighbours.
        assertTrue("backoff should grow: $waits", waits[1] > waits[0] * 1.2)
    }

    /**
     * The trap the hardening plan calls out: pull and push fail independently, and the pull
     * recovers first. One shared counter meant every successful pull reset the push delay to a
     * second, so an outbox that kept failing retried at full speed forever.
     */
    @Test
    fun `a successful pull does not reset push backoff`() {
        val backend = BackendSpy()
        backend.flushReport = failingFlush(retriable = true)
        val sleeper = FakeSleeper(allowedTicks = 8)
        val controller = controller(backend, sleeper, quietMs = 10)

        // Climb the backoff a few steps.
        runBlocking { controller.pushNow() }
        settle(400)
        val climbed = sleeper.recorded.last()
        controller.stop()

        // A pull succeeds — the network is back for reads — and the outbox keeps failing.
        backend.pullReport = CouchSyncEngine.PullReport()
        backend.pending = 1
        runBlocking { controller.pushNow() }
        settle(150)
        controller.stop()

        val resumed = sleeper.recorded.last()
        assertTrue(
            "the push delay should have carried on from where it was, not restarted: " +
                "${sleeper.recorded}",
            resumed > climbed * 0.5,
        )
    }

    @Test
    fun `a successful push resets the backoff`() {
        val backend = BackendSpy()
        backend.flushReport = failingFlush(retriable = true)
        val sleeper = FakeSleeper(allowedTicks = 8)
        val controller = controller(backend, sleeper, quietMs = 10)

        runBlocking { controller.pushNow() }
        settle(400)
        controller.stop()
        val climbed = sleeper.recorded.last()
        assertTrue("the backoff should have grown past the floor first", climbed > 1_000)

        // The outbox drains, and then something fails again from scratch.
        backend.flushReport = CouchSyncEngine.FlushReport()
        runBlocking { controller.pushNow() }
        backend.flushReport = failingFlush(retriable = true)
        val before = sleeper.recorded.size
        runBlocking { controller.pushNow() }
        settle(100)
        controller.stop()

        val afterReset = sleeper.recorded.drop(before)
        assertTrue("the new failure should have scheduled a retry", afterReset.isNotEmpty())
        assertTrue(
            "a push that succeeded should have put the delay back at the floor: $afterReset",
            afterReset[0] < climbed,
        )
    }

    /**
     * A terminal failure is left queued and reported, but must not put the device into a retry loop
     * that cannot end. Nothing about waiting makes a 413 acceptable to the server.
     */
    @Test
    fun `a terminal push failure is not retried on a timer`() {
        val backend = BackendSpy()
        backend.flushReport = failingFlush(retriable = false)
        val sleeper = FakeSleeper(allowedTicks = 8)
        val controller = controller(backend, sleeper, quietMs = 10)

        runBlocking { controller.pushNow() }
        settle(400)
        controller.stop()

        assertEquals(
            "a refusal on the merits should not be re-sent on a timer", 1, backend.flushCount)
        assertEquals("and the document stays queued", 1, controller.state.value.pendingCount)
        assertTrue(
            "expected the failure to be reported",
            controller.status is CouchSyncController.Status.Failed,
        )
    }

    /** RFC 9110 §10.2.3: when the server says when to come back, that beats the client's guess. */
    @Test
    fun `a retry-after overrides the clients own delay`() {
        val backend = BackendSpy()
        backend.flushReport = failingFlush(retriable = true, retryAfterMs = 30_000)
        val sleeper = FakeSleeper(allowedTicks = 2)
        val controller = controller(backend, sleeper, quietMs = 10)

        runBlocking { controller.pushNow() }
        settle(150)
        controller.stop()

        val first = sleeper.recorded.first()
        // Jittered ±15%, so assert the band rather than the number.
        assertTrue("should have waited the 30s the server asked for: $first", first > 24_000)
        assertTrue("$first", first < 36_000)
    }

    // endregion

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
