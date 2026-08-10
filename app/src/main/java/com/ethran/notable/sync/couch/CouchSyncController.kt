package com.ethran.notable.sync.couch

import com.ethran.notable.di.ApplicationScope
import com.ethran.notable.sync.SyncLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything the pumps need from the CouchDB stack, behind an interface.
 *
 * The indirection is what lets [CouchSyncController]'s timing be tested without a server — and it
 * is also what keeps the controller from having to know that the backend might not be configured
 * at all. [CouchSyncHost] is the real implementation; when WebDAV is the selected backend every
 * method here is an inert no-op, so nothing above this line has to branch on the backend.
 */
interface CouchSyncBackend {
    /** True when CouchDB is the selected backend *and* configured well enough to reach. */
    suspend fun isEnabled(): Boolean

    suspend fun flush(): CouchSyncEngine.FlushReport

    suspend fun pull(longpoll: Boolean): CouchSyncEngine.PullReport

    /** Queues a page document and the notebook that owns it. */
    suspend fun markPageDirty(pageId: String)

    /** Queues every document this device holds — the first push against a fresh server. */
    suspend fun markEverythingDirty()

    /** Records a locally-initiated deletion so a tombstone is pushed, including offline. */
    suspend fun recordDeletion(documentId: String)
}

/**
 * The controller's tunables, injected as one value so production defaults live in a Hilt module and
 * tests can run the same loops at test speed.
 *
 * [sleep] is the seam that makes the pacing testable: a fake can count the waits and refuse to
 * grant more than a budget of them, which turns "this loop spins" from a flaky timing observation
 * into a deterministic failure.
 */
class CouchSyncClock(
    /**
     * How long after the last edit to push. Short because a flush sends only the documents that
     * changed — unlike the WebDAV engine, which re-sends a whole notebook and so needs 20s.
     */
    val editQuietPeriodMs: Long = 3_000,
    val retryFloorMs: Long = 1_000,
    val retryCeilingMs: Long = 60_000,
    /** Shortest gap between two change-feed requests that both came back with nothing. */
    val idleFloorMs: Long = 500,
    val nowMs: () -> Long = System::currentTimeMillis,
    val sleep: suspend (Long) -> Unit = { delay(it) },
)

/**
 * Drives CouchDB sync for the app: a debounced push after writing stops, and a change-feed loop
 * that keeps the library current while notable is in the foreground.
 *
 * Separate from [com.ethran.notable.sync.SyncOrchestrator] rather than folded into it. That one is
 * built around a WebDAV run — a whole-tree reconcile with a report and a conflict list — while this
 * is two independent pumps over a document outbox. Keeping them apart also means WebDAV keeps
 * working untouched while the backend switch is still live.
 *
 * bopa's `CouchSyncController.swift` is the twin of this class, and `CouchSyncControllerTest`
 * mirrors its test suite case for case. The Swift original is `@MainActor`, which serializes every
 * entry point for free; here the job bookkeeping is guarded by a monitor and the flush itself by a
 * [Mutex], so a background flush and a foreground pull cannot interleave over the reported state.
 */
@Singleton
class CouchSyncController @Inject constructor(
    @param:ApplicationScope private val scope: CoroutineScope,
    private val backend: CouchSyncBackend,
    private val clock: CouchSyncClock,
) {

    sealed class Status {
        object Idle : Status()
        object Syncing : Status()

        /**
         * Offline or rejected. The pending count alongside it says how much is waiting, which is
         * the number that actually answers "have I lost anything?".
         */
        data class Failed(val message: String) : Status()
    }

    data class UiState(
        val status: Status = Status.Idle,
        val pendingCount: Int = 0,
        val lastSyncedAt: Long? = null,
        /** Documents this build could not understand, materialized alongside the local copy. */
        val conflictCopies: List<String> = emptyList(),
    ) {
        val lastError: String? get() = (status as? Status.Failed)?.message

        /** One-line status for the settings footer. */
        val detail: String?
            get() = when (status) {
                is Status.Syncing -> "Syncing…"
                is Status.Failed -> {
                    val message = status.message
                    if (pendingCount > 0) "$message $pendingCount waiting to sync." else message
                }

                is Status.Idle ->
                    if (pendingCount > 0) "$pendingCount waiting to sync" else null
            }
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    val status: Status get() = _state.value.status
    val pendingCount: Int get() = _state.value.pendingCount
    val lastSyncedAt: Long? get() = _state.value.lastSyncedAt
    val lastError: String? get() = _state.value.lastError

    /** Guards [pullJob]/[pushJob]; taken only around field reads and writes, never around work. */
    private val jobs = Any()
    private var pullJob: Job? = null
    private var pushJob: Job? = null

    /** Serializes flushes so two pushes cannot interleave over the reported state. */
    private val flushMutex = Mutex()

    /**
     * Bumped by every [noteEdited]; the value a flush last covered is recorded alongside it.
     *
     * Cancelling the timer is not on its own enough to stop a duplicate push. By the time
     * `pushNow()` runs, the timer's coroutine may already be past the point where cancelling it
     * would help, and then the same edit is sent twice. Comparing generations under [flushMutex]
     * asks the question that actually matters — "has anything happened since the edit that
     * scheduled me?" — and it also absorbs the case where the change-feed loop's push-back flush
     * beat the timer to it.
     */
    private val editGeneration = AtomicLong(0)
    private val flushedGeneration = AtomicLong(0)

    val isRunning: Boolean get() = synchronized(jobs) { pullJob != null }

    // region Lifecycle

    /**
     * Starts the change-feed loop. Idempotent.
     *
     * Catches up with a plain request first, then holds a long poll open. The catch-up matters: the
     * long poll only reports what happens *after* it starts, so entering it directly would miss
     * everything that changed while notable was closed.
     */
    fun start() {
        synchronized(jobs) {
            if (pullJob != null) return
            pullJob = scope.launch { detached("start") { if (backend.isEnabled()) runFeed() } }
        }
    }

    fun stop() {
        synchronized(jobs) {
            pullJob?.cancel()
            pullJob = null
            pushJob?.cancel()
            pushJob = null
        }
        _state.update { if (it.status is Status.Syncing) it.copy(status = Status.Idle) else it }
    }

    private suspend fun runFeed() {
        var backoff = clock.retryFloorMs
        var caughtUp = false
        while (currentCoroutineContext().isActive) {
            try {
                val report = backend.pull(caughtUp)
                caughtUp = true
                backoff = clock.retryFloorMs
                apply(report)
                // Anything the server lacked is now queued; send it without waiting for the edit
                // timer, which will not fire because the user is not writing.
                if (report.pushBack.isNotEmpty()) pushNow()

                // A long poll is supposed to block until something happens, so an empty result
                // should be rare and slow. When it is neither — a proxy that answers immediately, a
                // server that ignores the timeout — re-issuing at once turns this loop into a hot
                // spin against the server. Pausing only in that case costs nothing when the feed
                // behaves.
                if (report.applied.isEmpty() && report.conflictCopies.isEmpty()) {
                    clock.sleep(clock.idleFloorMs)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(status = Status.Failed(describe(e))) }
                // A dead connection should not become a request flood. Cancellation while waiting
                // propagates, which is how `stop()` ends a loop that is backing off.
                clock.sleep(backoff)
                backoff = (backoff * 2).coerceAtMost(clock.retryCeilingMs)
            }
        }
    }

    // endregion

    // region Push

    /**
     * Signals a local edit. Coalesces: only the last call's timer survives, so a burst of autosaves
     * while someone is writing costs one push once they stop.
     */
    fun noteEdited() {
        val generation = editGeneration.incrementAndGet()
        synchronized(jobs) {
            pushJob?.cancel()
            pushJob = scope.launch {
                try {
                    clock.sleep(clock.editQuietPeriodMs)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    return@launch // the sleeper gave up; a later edit will queue another push
                }
                // Detach before flushing, so a `pushNow()` racing us cancels nothing that is
                // already committed to sending. Cancelling a coroutine mid-flush would abort the
                // very push this timer exists to make.
                val self = currentCoroutineContext()[Job]
                synchronized(jobs) { if (pushJob === self) pushJob = null }
                flushNow(coveringGeneration = generation)
            }
        }
    }

    /** Pushes immediately — leaving the editor, backgrounding, reconnecting, or "Sync now". */
    suspend fun pushNow() {
        synchronized(jobs) {
            pushJob?.cancel()
            pushJob = null
        }
        flushNow()
    }

    /** Fire-and-forget [pushNow], for callers that cannot suspend (`onStop`, a click handler). */
    fun requestPushNow() {
        scope.launch { detached("pushNow") { pushNow() } }
    }

    /**
     * @param coveringGeneration when set, skip if an earlier flush already covered that edit. Only
     *   the debounce timer passes it; an explicit push always sends.
     */
    private suspend fun flushNow(coveringGeneration: Long? = null) = flushMutex.withLock {
        if (coveringGeneration != null && flushedGeneration.get() >= coveringGeneration) {
            return@withLock
        }
        flushedGeneration.set(editGeneration.get())
        _state.update { it.copy(status = Status.Syncing) }
        // A flush reports per-document failures rather than throwing, so anything that *does*
        // escape is the stack itself failing to assemble (unreadable settings, say). That is
        // reported state, not an exception to unwind through a drawing callback.
        val report = try {
            backend.flush()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SyncLogger.w("CouchSync", "flush failed: $e")
            _state.update { it.copy(status = Status.Failed(describe(e))) }
            return@withLock
        }
        _state.update { current ->
            val pending = report.stillDirty.size
            when {
                report.blockedByDeletionGuard -> current.copy(
                    pendingCount = pending,
                    status = Status.Failed(
                        "Refusing to delete $pending notebooks at once. " +
                            "If that is really what you want, confirm in Sync settings."
                    ),
                )

                report.failures.isNotEmpty() -> current.copy(
                    pendingCount = pending,
                    status = Status.Failed(report.failures.values.sorted().first()),
                )

                else -> current.copy(
                    pendingCount = pending,
                    status = Status.Idle,
                    lastSyncedAt = clock.nowMs(),
                )
            }
        }
    }

    // endregion

    // region What the app calls

    /**
     * A local edit landed on [pageId]. Queues exactly the documents it touched, then starts the
     * debounce — the twin of bopa's `didChangeDocuments`.
     *
     * Fire-and-forget by design: every content write funnels through here, and none of those
     * callers can afford to wait on the database, let alone on the network.
     */
    fun notePageEdited(pageId: String) {
        scope.launch {
            detached("notePageEdited") {
                if (!backend.isEnabled()) return@detached
                backend.markPageDirty(pageId)
                noteEdited()
            }
        }
    }

    /**
     * A notebook or folder was deleted here. Recorded rather than pushed straight away, so the
     * intent survives being offline and a restart.
     */
    fun noteDeleted(documentId: String) {
        scope.launch {
            detached("noteDeleted") {
                if (!backend.isEnabled()) return@detached
                backend.recordDeletion(documentId)
                noteEdited()
            }
        }
    }

    /**
     * One catch-up pass plus a push, for foregrounding, the periodic worker and "Sync now" — no
     * long poll, so it returns rather than waiting for someone else to write something.
     */
    suspend fun syncNow() {
        if (!backend.isEnabled()) return
        _state.update { it.copy(status = Status.Syncing) }
        try {
            apply(backend.pull(false))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(status = Status.Failed(describe(e))) }
            return
        }
        pushNow()
    }

    /**
     * Queues everything this device holds and sends it — the first sync after pointing at a new
     * server, when nothing is dirty yet but nothing has been sent either.
     */
    suspend fun pushEverything() {
        if (!backend.isEnabled()) return
        backend.markEverythingDirty()
        pushNow()
    }

    // endregion

    // region Plumbing

    private fun apply(report: CouchSyncEngine.PullReport) {
        _state.update { current ->
            current.copy(
                // A pull that returned at all clears any previous failure: the server is
                // demonstrably reachable again, whether or not it had anything to say.
                status = Status.Idle,
                lastSyncedAt =
                    if (report.applied.isNotEmpty()) clock.nowMs() else current.lastSyncedAt,
                conflictCopies =
                    if (report.conflictCopies.isEmpty()) current.conflictCopies
                    else current.conflictCopies + report.conflictCopies,
            )
        }
    }

    /**
     * Runs a fire-and-forget pump body, turning any failure into reported state.
     *
     * These coroutines are launched on the application scope, whose `SupervisorJob` isolates
     * siblings but does *not* stop an uncaught exception reaching the default handler — and a
     * settings read that throws must not be able to take the app down from a drawing callback.
     */
    private suspend inline fun detached(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(status = Status.Failed(describe(e))) }
            SyncLogger.w("CouchSync", "$what failed: $e")
        }
    }

    private fun describe(error: Throwable): String = when (error) {
        is CouchError.Unauthorized -> "Sync rejected the username or password."
        is CouchError.Transport -> "Offline — changes are saved and will sync when you reconnect."
        is CouchError.Server -> "The sync server returned an error (${error.status})."
        else -> error.toString()
    }

    // endregion
}
