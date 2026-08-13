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
import kotlin.random.Random

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

    /** Queues one document by id — a notebook or folder changed without any page being drawn in. */
    suspend fun markDocumentDirty(documentId: String)

    /** Queues every document this device holds — the first push against a fresh server. */
    suspend fun markEverythingDirty()

    /**
     * Queues everything this device holds that the server has never seen, and says how many that
     * was. The catch-up for documents no edit ever queued — see [CouchSyncEngine.neverSent].
     */
    suspend fun markUnsentDirty(): Int

    /** Records a locally-initiated deletion so a tombstone is pushed, including offline. */
    suspend fun recordDeletion(documentId: String)

    /**
     * "Yes, delete them on the server too": lets exactly [ids] past the mass-deletion guard on the
     * next flush. Scoped to those ids and spent once they are sent — never a standing exemption.
     */
    suspend fun approveHeldDeletions(ids: List<String>)

    /**
     * "No, keep them on the server": drops [ids]' tombstones, so nothing is published. The
     * notebooks come back to this device on the next pull, which is the point — the caller must say
     * so before offering the choice.
     */
    suspend fun discardHeldDeletions(ids: List<String>)

    /**
     * How many documents are still waiting to be sent — the durable outbox, not the last flush's
     * report.
     *
     * The controller talks to the backend rather than to the engine, so this is how the reconnect
     * path can ask "is there anything left over from when we were offline?".
     */
    suspend fun pendingCount(): Int

    /**
     * Which documents the server has seen and which are still queued, or null when CouchDB is not
     * the live backend. The library badge reads this: without it a notebook synced perfectly over
     * CouchDB still showed "local only", because the only other record of sync state is a table
     * that only the WebDAV engine writes.
     */
    val documentState: StateFlow<CouchDocumentState?>
}

/**
 * The per-document facts the UI needs, lifted out of [CouchSyncState] so the badge does not depend
 * on the engine's checkpoint format.
 *
 * @property known ids the server has a revision for — it has this document.
 * @property queued ids changed here and not yet accepted by the server.
 */
data class CouchDocumentState(
    val known: Set<String> = emptySet(),
    val queued: Set<String> = emptySet(),
)

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
        /**
         * The notebook tombstones the mass-deletion guard is holding, from the last flush.
         *
         * Carried as ids rather than as a count because the settings screen has to act on exactly
         * this set: confirming or discarding "the deletions" means these ones, not whatever the
         * outbox happens to hold by the time the user taps.
         */
        val heldDeletions: List<String> = emptyList(),
        /**
         * How far this device's clock is from the server's, signed, in seconds — or null when the
         * difference is too small to matter or the server did not say. See
         * [CouchDbClient.clockSkewSeconds].
         */
        val clockSkewSeconds: Long? = null,
        /** Referenced images the last pull could not bring down; see [assetWarning]. */
        val waitingImages: Int = 0,
        /** Of those, the ones whose bytes did not match the id that named them. */
        val unverifiedImages: Int = 0,
    ) {
        val lastError: String? get() = (status as? Status.Failed)?.message

        /**
         * Images that have not arrived, or null. Non-fatal by construction, like
         * [clockSkewWarning]: it is never a [Status], so nothing can mistake it for a reason a sync
         * did not happen.
         *
         * Saying "notes and ink are synced" is the point of the sentence. Without it, a gap where a
         * picture belongs reads as a broken sync — and the two things people then try, waiting and
         * re-downloading the library, are the two things that will not help.
         */
        val assetWarning: String?
            get() = when {
                unverifiedImages > 0 -> {
                    val plural = if (unverifiedImages == 1) "" else "s"
                    "$unverifiedImages image$plural could not be verified and will be retried. " +
                        "Notes and ink are synced."
                }

                waitingImages > 0 -> {
                    val plural = if (waitingImages == 1) "" else "s"
                    "$waitingImages image$plural still downloading. Notes and ink are synced."
                }

                else -> null
            }

        /**
         * The clock warning, or null. Non-fatal by construction: it is never a [Status], so nothing
         * can mistake it for a reason a sync did not happen.
         *
         * Says which direction and roughly how much, because that is what makes it actionable —
         * "your clock is wrong" sends someone looking at the wrong device half the time, while
         * "eight minutes ahead of the server" is something they can go and check.
         */
        val clockSkewWarning: String?
            get() = clockSkewSeconds?.let { skew ->
                val minutes = (kotlin.math.abs(skew) + 30) / 60
                val direction = if (skew > 0) "ahead of" else "behind"
                "This device's clock is about $minutes minute${if (minutes == 1L) "" else "s"} " +
                    "$direction the sync server's. Edits and deletions are compared by time, so " +
                    "the wrong one can win until the clocks agree."
            }

        /** One-line status for the settings footer. */
        val detail: String?
            get() {
                val line = when (status) {
                    is Status.Syncing -> "Syncing…"
                    is Status.Failed -> {
                        val message = status.message
                        if (pendingCount > 0) "$message $pendingCount waiting to sync." else message
                    }

                    is Status.Idle ->
                        if (pendingCount > 0) "$pendingCount waiting to sync" else null
                }
                // Appended rather than substituted: sync is working, and the caption still has to
                // say so. The warning is extra information about *how well*, not a replacement for
                // what happened.
                return listOfNotNull(line, assetWarning, clockSkewWarning)
                    .joinToString(" ").ifEmpty { null }
            }
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    val status: Status get() = _state.value.status
    val pendingCount: Int get() = _state.value.pendingCount

    /**
     * Whether anything is still owed to the server — documents in the outbox, or an edit whose
     * debounce timer has not fired yet.
     *
     * Read at `onStop` to decide whether the last push needs a durable continuation. The pending
     * count alone would miss the commonest case by far: someone draws a stroke and immediately
     * leaves the app, so the edit is queued in the engine but the three-second timer that would
     * have sent it never fires. That is exactly the work most at risk of being lost, because it is
     * the most recent.
     *
     * Cheap and synchronous, because the caller is a lifecycle callback that cannot suspend. A
     * pending push also implies the CouchDB backend is the selected one: every path that starts one
     * checks [CouchSyncBackend.isEnabled] first.
     */
    val hasUnfinishedWork: Boolean
        get() = pendingCount > 0 || synchronized(jobs) { pushJob != null }
    val lastSyncedAt: Long? get() = _state.value.lastSyncedAt
    val lastError: String? get() = _state.value.lastError

    /** Guards [pullJob]/[pushJob]; taken only around field reads and writes, never around work. */
    private val jobs = Any()
    private var pullJob: Job? = null
    private var pushJob: Job? = null

    /**
     * The push loop's own backoff, deliberately separate from [runFeed]'s.
     *
     * The two fail independently, and the feed is the one that recovers first: a pull succeeds as
     * soon as the network is back, while an outbox can keep failing for its own reasons — a
     * document the server keeps rejecting, a rate limit that applies to writes. Sharing one counter
     * meant every successful pull reset the push delay to a second, so a persistently failing
     * outbox retried at full speed forever, which is precisely the request flood the backoff exists
     * to prevent. Guarded by [jobs].
     */
    private var pushBackoffMs = clock.retryFloorMs

    /**
     * Whether the pending [pushJob] is a scheduled retry rather than an edit timer. Only a retry
     * may schedule another, so an edit arriving mid-backoff replaces it with a normal push instead
     * of adding a second timer. Guarded by [jobs].
     */
    private var isRetryingPush = false

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
            isRetryingPush = false
            // [pushBackoffMs] is deliberately *not* reset here. Stopping is what happens when sync
            // is switched off or the app stops, and a device that comes straight back to a server
            // that is still rate-limiting it should not arrive at full speed.
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
                //
                // The outbox is asked too, not just what this pull brought back. A pull that
                // succeeded is the first proof the network returned, and edits made while it was
                // gone are still sitting dirty from a flush that failed offline. Pushing only on
                // `pushBack` left them there until the user wrote something else or tapped Sync
                // now — reconnecting has to drain the outbox, not merely deliver what the feed
                // happened to carry. bopa's controller does the same, for the same reason.
                if (report.pushBack.isNotEmpty() || backend.pendingCount() > 0) pushNow()

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
            isRetryingPush = false
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
        val wasRetry = synchronized(jobs) {
            pushJob?.cancel()
            pushJob = null
            val was = isRetryingPush
            isRetryingPush = false
            was
        }
        flushNow(wasRetry = wasRetry)
    }

    /**
     * Waits out the push backoff and tries the outbox again.
     *
     * Scheduled only after a flush that failed in a way waiting could fix. A terminal failure — a
     * page the server will not accept at any size, a malformed request — leaves the documents
     * queued and the message standing, but does not put the device in a retry loop that cannot end:
     * the next edit, foreground, or manual "Sync now" tries again, by which point something may
     * have changed.
     */
    private fun schedulePushRetry(delayMs: Long) {
        synchronized(jobs) {
            pushJob?.cancel()
            isRetryingPush = true
            // Jitter so a household of devices that all lost the same Wi-Fi does not come back in
            // lockstep and rebuild the thundering herd the backoff exists to break up.
            val wait = (delayMs * (0.85 + Random.nextDouble() * 0.3)).toLong()
            pushJob = scope.launch {
                try {
                    clock.sleep(wait)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    return@launch
                }
                val self = currentCoroutineContext()[Job]
                synchronized(jobs) { if (pushJob === self) pushJob = null }
                flushNow(wasRetry = true)
            }
        }
    }

    /** Fire-and-forget [pushNow], for callers that cannot suspend (`onStop`, a click handler). */
    fun requestPushNow() {
        scope.launch { detached("pushNow") { pushNow() } }
    }

    /**
     * @param coveringGeneration when set, skip if an earlier flush already covered that edit. Only
     *   the debounce timer passes it; an explicit push always sends.
     */
    private suspend fun flushNow(
        coveringGeneration: Long? = null,
        wasRetry: Boolean = false,
    ) = flushMutex.withLock {
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
        logFlush(report)
        noteClockSkew(report.clockSkewSeconds)
        synchronized(jobs) {
            when {
                // Reset on a push that actually succeeded, and on nothing else. A successful *pull*
                // used to do it, which is how an outbox that had been failing for ten minutes went
                // back to retrying every second.
                report.failures.isEmpty() -> pushBackoffMs = clock.retryFloorMs

                report.hasRetriableFailure -> {
                    // The server's own answer wins when it gave one (RFC 9110 §10.2.3);
                    // `CouchDbClient` has already clamped it to something sane.
                    schedulePushRetry(report.retryAfterMs ?: pushBackoffMs)
                    pushBackoffMs = (pushBackoffMs * 2).coerceAtMost(clock.retryCeilingMs)
                }

                // A retry that came back terminal: stop climbing, and let the next edit or
                // foreground start from the floor rather than from wherever this sequence reached.
                wasRetry -> pushBackoffMs = clock.retryFloorMs
            }
        }
        _state.update { current ->
            val pending = report.stillDirty.size
            val held = report.heldDeletions
            when {
                // What the guard actually does — it holds the tombstones back and lets everything
                // else through — and, now that the two answers exist, where to give one. Naming the
                // buttons rather than the screen alone matters: "confirm in settings" sends someone
                // hunting, while the words on the buttons are the words they will read there.
                report.blockedByDeletionGuard -> current.copy(
                    pendingCount = pending,
                    heldDeletions = held,
                    status = Status.Failed(
                        "Holding back ${report.deletionsHeldBack} notebook deletions — that is " +
                            "most of this library, and a wiped device looks the same. Everything " +
                            "else still syncs. In Settings → Sync, choose “Delete them on the " +
                            "server too” or “Keep them on the server”."
                    ),
                )

                report.failures.isNotEmpty() -> current.copy(
                    pendingCount = pending,
                    heldDeletions = held,
                    status = Status.Failed(report.failures.values.sorted().first()),
                )

                else -> current.copy(
                    pendingCount = pending,
                    heldDeletions = held,
                    status = Status.Idle,
                    lastSyncedAt = clock.nowMs(),
                )
            }
        }
    }

    /**
     * "Delete them on the server too." Sends exactly the batch the guard is holding, then clears it
     * from the UI state — a flush that fails puts it back, and one that succeeds must not leave a
     * stale prompt on screen offering to confirm deletions that have already gone.
     *
     * The approval is scoped to these ids and spent when they are sent, so this is an answer to one
     * question rather than a switch that turns the guard off.
     */
    suspend fun approveHeldDeletions() {
        val held = _state.value.heldDeletions
        if (held.isEmpty()) return
        SyncLogger.beginRun("CouchDB confirm deletions")
        _state.update { it.copy(heldDeletions = emptyList()) }
        backend.approveHeldDeletions(held)
        pushNow()
    }

    /**
     * "Keep them on the server." Drops the tombstones, so nothing is published and the notebooks
     * come back here on the next pull.
     *
     * Nothing is pushed, so there is nothing to report from a flush — the state is settled here
     * instead, reading the pending count back from the outbox rather than guessing at it, since
     * discarding removes rows the last flush report still counted.
     */
    suspend fun discardHeldDeletions() {
        val held = _state.value.heldDeletions
        if (held.isEmpty()) return
        SyncLogger.beginRun("CouchDB discard deletions")
        backend.discardHeldDeletions(held)
        val pending = runCatching { backend.pendingCount() }.getOrNull()
        _state.update {
            it.copy(
                heldDeletions = emptyList(),
                pendingCount = pending ?: it.pendingCount,
                status = Status.Idle,
            )
        }
    }

    /**
     * What the push actually did. Only the first failure reaches the status caption, so the rest
     * are written down here — otherwise a run where one document of forty failed is indistinguishable
     * from one where thirty-nine did.
     */
    private fun logFlush(report: CouchSyncEngine.FlushReport) {
        if (report.blockedByDeletionGuard) {
            // `deletionsHeldBack`, not `stillDirty.size`: the queue also holds whatever else was
            // waiting, and only the tombstones were refused.
            SyncLogger.w(
                TAG,
                "Push held back by the mass-deletion guard: " +
                    "${report.deletionsHeldBack} notebook deletions at once. Confirm or discard " +
                    "them in Settings → Sync."
            )
            // Which ones, so a bug report can say what the guard was actually looking at rather
            // than only how alarming it found it.
            SyncLogger.d(TAG, "Held back: ${report.heldDeletions.joinToString(", ")}")
            return
        }
        val summary = listOfNotNull(
            report.pushed.size.takeIf { it > 0 }?.let { "$it sent" },
            report.merged.size.takeIf { it > 0 }?.let { "$it merged with the server's copy" },
            report.stillDirty.size.takeIf { it > 0 }?.let { "$it still waiting" },
            report.failures.size.takeIf { it > 0 }?.let { "$it failed" },
        )
        SyncLogger.i(TAG, "Push: " + if (summary.isEmpty()) "nothing to send" else summary.joinToString(", "))
        report.failures.forEach { (id, message) -> SyncLogger.w(TAG, "✗ $id: $message") }
        if (report.pushed.isNotEmpty()) SyncLogger.d(TAG, "Sent: ${report.pushed.joinToString(", ")}")
        if (report.stillDirty.isNotEmpty()) {
            SyncLogger.d(TAG, "Still waiting: ${report.stillDirty.joinToString(", ")}")
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
     * A notebook or folder document changed here — created, renamed, moved. Queues it and starts
     * the debounce, the metadata twin of [notePageEdited].
     *
     * This exists because the outbox was fed by ink alone: [notePageEdited] queued the page and,
     * as a side effect, its notebook. Anything that changed a notebook or folder *without* drawing
     * in it — creating one, renaming one — enqueued nothing and was never sent. The scan in
     * [markUnsentDirty] catches a document that has never been sent at all; this is what makes a
     * change to one already on the server travel.
     *
     * Fire-and-forget, like its twin: no caller of a rename can wait on the network.
     */
    fun noteDocumentChanged(documentId: String) {
        scope.launch {
            detached("noteDocumentChanged") {
                if (!backend.isEnabled()) return@detached
                backend.markDocumentDirty(documentId)
                noteEdited()
            }
        }
    }

    /**
     * A page was deleted from [notebookId]. Only the notebook travels: a page has no lifecycle of
     * its own, it lives and dies with its notebook's `pageIds` / `deletedPageIds` (protocol §6.4).
     * Without this the removal waits for some unrelated edit to queue the notebook — which on a
     * device where nothing else is touched is never.
     */
    fun notePageDeleted(notebookId: String) = noteDocumentChanged(CouchDocId.notebook(notebookId))

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
        SyncLogger.beginRun("CouchDB sync")
        if (!backend.isEnabled()) {
            // The button that gets here stays enabled on a misconfigured or unparseable URL, so
            // this used to be a tap that produced nothing at all: no state change, no message, no
            // log line. The host has already said which setting is at fault.
            SyncLogger.w(TAG, "Nothing to sync: CouchDB is not usable with the current settings")
            _state.update { it.copy(status = Status.Failed("Check your CouchDB settings")) }
            return
        }
        _state.update { it.copy(status = Status.Syncing) }
        // Before pulling: queue anything created here that no edit ever queued. Without this a
        // notebook made and left alone is never sent, however many times sync runs.
        runCatching { backend.markUnsentDirty() }
            .onFailure { SyncLogger.w(TAG, "Could not scan for unsent documents: ${it.message}") }
        try {
            SyncLogger.i(TAG, "Pulling changes from the server…")
            apply(backend.pull(false))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A read failure also skips the push below, so say so — otherwise the outbox appears
            // to be stuck for a reason the log never mentions.
            SyncLogger.e(TAG, "Pull failed, so nothing was sent either: ${describe(e)}")
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
        SyncLogger.beginRun("CouchDB upload everything")
        if (!backend.isEnabled()) {
            // The caller shows "Queueing everything on this device…" before calling, so returning
            // quietly here left a message that was simply untrue.
            SyncLogger.w(TAG, "Nothing queued: CouchDB is not usable with the current settings")
            _state.update { it.copy(status = Status.Failed("Check your CouchDB settings")) }
            return
        }
        backend.markEverythingDirty()
        pushNow()
    }

    // endregion

    // region Plumbing

    private fun apply(report: CouchSyncEngine.PullReport) {
        logPull(report)
        noteClockSkew(report.clockSkewSeconds)
        for ((assetId, reason) in report.assetFailures) {
            SyncLogger.w("CouchSync", "asset $assetId unavailable: $reason")
        }
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
                // Assigned from each report, never accumulated: the sets are derived from the
                // store's own list of what is still owed, so a blob that lands on a later pull
                // clears the warning without anything having to remember it was set.
                // A corrupt asset is recorded in both `corruptAssets` and `assetFailures`, so it is
                // subtracted back out here rather than counted twice. A 404 is only in
                // `missingAssets` — it is not a failure, it is a peer that has not finished
                // uploading.
                waitingImages = report.missingAssets.size +
                    (report.assetFailures.size - report.corruptAssets.size).coerceAtLeast(0),
                unverifiedImages = report.corruptAssets.size,
            )
        }
    }

    /**
     * What the pull brought down. A conflict copy is called out at warning level because it is the
     * one outcome that needs the user to do something — the caption only ever counts them.
     */
    private fun logPull(report: CouchSyncEngine.PullReport) {
        val summary = listOfNotNull(
            report.applied.size.takeIf { it > 0 }?.let { "$it received" },
            report.fetchedAssets.size.takeIf { it > 0 }?.let { "$it image(s) downloaded" },
            report.pushBack.size.takeIf { it > 0 }?.let { "$it queued to send back" },
            report.skippedEchoes.size.takeIf { it > 0 }?.let { "$it of our own changes ignored" },
        )
        SyncLogger.i(
            TAG,
            "Pull: " + if (summary.isEmpty()) "nothing new" else summary.joinToString(", ")
        )
        report.conflictCopies.forEach {
            SyncLogger.w(TAG, "⚠ Kept a conflict copy of $it; both versions are on the device")
        }
        if (report.applied.isNotEmpty()) {
            SyncLogger.d(TAG, "Received: ${report.applied.joinToString(", ")}")
        }
    }

    /**
     * Records what the server's `Date` header said about this device's clock, and writes it down
     * once per change rather than once per request.
     *
     * The change-feed loop makes a request roughly every minute, forever, so logging every reading
     * would bury the sync log under the same sentence — and the log is read precisely to find the
     * *other* lines. A skew that clears itself is worth a line too, since "your clock was wrong"
     * with no follow-up leaves the reader unsure whether it still is.
     *
     * Never a [Status]: significant skew is a warning that rides alongside a sync that worked.
     */
    private fun noteClockSkew(skewSeconds: Long?) {
        val previous = _state.value.clockSkewSeconds
        _state.update { it.copy(clockSkewSeconds = skewSeconds) }
        val warning = _state.value.clockSkewWarning
        when {
            warning != null && warning != lastLoggedClockSkew -> SyncLogger.w(TAG, warning)
            warning == null && previous != null ->
                SyncLogger.i(TAG, "This device's clock now agrees with the sync server's.")
        }
        lastLoggedClockSkew = warning
    }

    /** The last skew sentence written to the log, so an unchanged one is not written again. */
    @Volatile
    private var lastLoggedClockSkew: String? = null

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
        is CouchError.Blocked ->
            "Android refused this address: sync needs an https:// server. A plain http:// one " +
                "would send your password in the clear, so it is not allowed."
        // 413 is the one terminal status with an answer the user can act on. Everything else in
        // that class is a server or configuration fault they can only report.
        is CouchError.Server -> when {
            error.status == 413 -> "A page is too large for the sync server to accept."
            error.status < 500 ->
                "The sync server refused the request (${error.status}). Check the sync settings."
            else -> "The sync server returned an error (${error.status})."
        }
        else -> error.toString()
    }

    // endregion

    private companion object {
        const val TAG = "CouchSync"
    }
}
