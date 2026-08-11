package com.ethran.notable.sync.couch

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant

// region Local state

/**
 * What this device believes about the server. Losing it is safe: every document re-pushes
 * (409 → merge → usually identical) and the feed replays from `"0"`, which is slow but correct
 * because every merge is idempotent. That is the difference from the WebDAV engine, where a lost
 * state file produced a wall of unresolvable conflicts.
 */
@Serializable
data class CouchSyncState(
    /** Change-feed checkpoint. `"0"` means "replay everything". */
    val lastSeq: String = "0",
    /** Last revision this device wrote or applied, per document id. Doubles as echo suppression. */
    val revs: Map<String, String> = emptyMap(),
    /** The outbox: documents changed locally and not yet accepted by the server. */
    val dirty: Set<String> = emptySet(),
)

// endregion

// region Document bodies

/**
 * A locally deleted notebook or folder. Written to CouchDB as a `_deleted` document that keeps its
 * body, so the peer can apply delete-vs-edit (protocol §6.4) rather than just seeing a document
 * vanish.
 */
@Serializable
data class CouchDeletedDoc(
    val type: String,
    val schema: Int = COUCH_SCHEMA_VERSION,
    /**
     * When the deletion happened, or empty when this device cannot know — a tombstone written
     * without a body (a plain HTTP `DELETE`, or a client that did not keep one) carries no
     * instant. Empty means *unknown*, not "the epoch" and emphatically not "now": it loses every
     * comparison in [CouchMerge.resolveDeletion], so an unknown deletion yields to a live document
     * rather than destroying it (protocol §6.4).
     */
    val deletedAt: String = "",
    /** Empty only transiently: normalized to [deletedAt] below, matching Swift's initializer. */
    var updatedAt: String = "",
    val updatedBy: String = "",
) {
    init {
        if (updatedAt.isEmpty()) updatedAt = deletedAt
    }
}

/** One document's content, in whichever shape its id prefix implies. */
sealed class CouchDocBody {
    data class Page(val page: CouchPage) : CouchDocBody()
    data class Notebook(val notebook: CouchNotebook) : CouchDocBody()
    data class Folder(val folder: CouchFolder) : CouchDocBody()
    data class Asset(val asset: CouchAsset) : CouchDocBody()
    data class Deleted(val tombstone: CouchDeletedDoc) : CouchDocBody()

    val isDeleted: Boolean get() = this is Deleted

    /** `updatedAt` regardless of shape — the input to delete-vs-edit. */
    val updatedAt: String
        get() = when (this) {
            is Page -> page.updatedAt
            is Notebook -> notebook.updatedAt
            is Folder -> folder.updatedAt
            is Asset -> asset.updatedAt
            is Deleted -> tombstone.updatedAt
        }

    /**
     * The `asset:` documents this body names. Only a page names any; the engine uses this to send
     * an image's bytes before the page that places it, and to fetch them when one arrives.
     */
    val referencedAssetIds: List<String>
        get() = if (this is Page) {
            page.images.mapNotNull { it.assetId }
                .filter { CouchAssetId.sha256HexOfAssetId(it) != null }
        } else {
            emptyList()
        }
}

/**
 * Merges two versions of the same document, including the delete-vs-edit case.
 *
 * Mismatched shapes (a page against a notebook) cannot arise from a well-formed database — the id
 * prefix fixes the type — so they are reported as null rather than guessed at.
 */
@Suppress("UnusedReceiverParameter")
fun CouchMerge.mergeBodies(a: CouchDocBody, b: CouchDocBody): CouchDocBody? = when {
    a is CouchDocBody.Page && b is CouchDocBody.Page ->
        CouchDocBody.Page(CouchMerge.mergePage(a.page, b.page))

    a is CouchDocBody.Notebook && b is CouchDocBody.Notebook ->
        CouchDocBody.Notebook(CouchMerge.mergeNotebook(a.notebook, b.notebook))

    a is CouchDocBody.Folder && b is CouchDocBody.Folder ->
        CouchDocBody.Folder(CouchMerge.mergeFolder(a.folder, b.folder))

    // Protocol §5.4: an asset's id is the hash of its bytes, so two copies of one id are the same
    // bytes. There is nothing to reconcile.
    a is CouchDocBody.Asset && b is CouchDocBody.Asset -> a

    a is CouchDocBody.Deleted && b is CouchDocBody.Deleted ->
        CouchDocBody.Deleted(mergeTombstones(a.tombstone, b.tombstone))

    // An edit made after the deletion resurrects the document; otherwise the delete stands.
    a is CouchDocBody.Deleted -> resolveTombstoneAgainstLive(a.tombstone, b)
    b is CouchDocBody.Deleted -> resolveTombstoneAgainstLive(b.tombstone, a)

    else -> null
}

private fun mergeTombstones(x: CouchDeletedDoc, y: CouchDeletedDoc): CouchDeletedDoc {
    // The same total order `CouchMerge.wins` applies to live documents, with an empty scalar key:
    // greater updatedAt, then greater updatedBy, then (unreachable) a tie either way.
    val mx = CouchMerge.millis(x.updatedAt)
    val my = CouchMerge.millis(y.updatedAt)
    val xWins = when {
        mx != my -> mx > my
        x.updatedBy != y.updatedBy -> x.updatedBy > y.updatedBy
        else -> true
    }
    return CouchDeletedDoc(
        type = x.type,
        schema = maxOf(x.schema, y.schema),
        // A deletion cannot un-happen: the earliest observation of it is the true one.
        // A deletion cannot un-happen: the earliest observation of it is the true one. An unknown
        // instant is not an early one — taking it would erase a real timestamp the peer recorded,
        // so a known value always survives beside an empty one.
        deletedAt = when {
            x.deletedAt.isEmpty() -> y.deletedAt
            y.deletedAt.isEmpty() -> x.deletedAt
            else -> CouchMerge.earlier(x.deletedAt, y.deletedAt)
        },
        updatedAt = CouchMerge.later(x.updatedAt, y.updatedAt),
        updatedBy = if (xWins) x.updatedBy else y.updatedBy,
    )
}

private fun resolveTombstoneAgainstLive(
    tombstone: CouchDeletedDoc,
    live: CouchDocBody,
): CouchDocBody = when (
    CouchMerge.resolveDeletion(live.updatedAt, tombstone.deletedAt.ifEmpty { null })
) {
    CouchMerge.DeletionOutcome.RESURRECT -> live
    CouchMerge.DeletionOutcome.APPLY_DELETION -> CouchDocBody.Deleted(tombstone)
}

// endregion

// region Local store

/**
 * How the engine reaches this device's own copy. notable implements it over Room; bopa implements
 * the same protocol over its notebook directory.
 */
interface CouchLocalStore {
    /** Current local content, or null when this device has never held the document. */
    fun load(documentId: String): CouchDocBody?

    /**
     * Every document this device holds, including tombstones it has yet to push. The denominator
     * for §6.7's mass-deletion guard: "most of what this device knows" is a question about the
     * library, and only the store can answer it.
     *
     * A store that cannot enumerate itself reports nothing, which makes the guard *more* cautious
     * rather than less — with no library to compare against, any large batch of deletions looks
     * like most of it. Erring towards asking is the right direction for a guard, and since it holds
     * back only the deletions, a false positive costs nothing else.
     */
    fun allDocumentIds(): List<String> = emptyList()

    /**
     * Replaces local content with the merged result.
     *
     * [basedOn] is the local copy the merge actually consumed — null when this device held none.
     * It is not the same thing as what is on disk now: computing a merge takes a network round
     * trip, and the editor goes on committing strokes throughout. Only content the merge *saw* and
     * chose to drop may be removed here; anything that arrived since is work this merge knows
     * nothing about, and deleting it would destroy ink that was never given the chance to sync.
     */
    fun apply(documentId: String, body: CouchDocBody, basedOn: CouchDocBody?)

    /**
     * A document that could not be understood (undecodable, or a newer `schema`). The
     * implementation keeps the local copy untouched and materializes the remote one alongside it
     * under a new identity — protocol §6.5. Never overwrite on this path.
     */
    fun applyConflictCopy(documentId: String, json: JsonObject)

    /**
     * `asset:<sha256>` ids a local page places but whose bytes this device does not hold — an
     * image the peer drew in, whose blob has still to be fetched.
     *
     * The store answers rather than the engine because only the store knows where the bytes will
     * go, and the answer has to survive a restart: a page can arrive in one session and its image
     * only be fetchable in the next.
     *
     * A store that holds no images has none to fetch, which is why this has a default.
     */
    fun missingAssetIds(): List<String> = emptyList()
}

// endregion

// region Engine

/**
 * The push/pull loop over [CouchDbClient]. bopa's `CouchSyncEngine.swift` is the twin of this
 * file; the two report the same fields so a cross-app failure reads the same on both sides.
 *
 * Swift expresses single-threaded access with `actor`; here a [Mutex] serializes the three entry
 * points, so a background flush and a foreground pull cannot interleave over the state.
 */
class CouchSyncEngine(
    private val client: CouchDbClient,
    private val store: CouchLocalStore,
    private val deviceId: String,
    state: CouchSyncState = CouchSyncState(),
    private val maxPushAttempts: Int = DEFAULT_MAX_PUSH_ATTEMPTS,
    private val onStateChange: ((CouchSyncState) -> Unit)? = null,
) {

    data class FlushReport(
        val pushed: List<String> = emptyList(),
        val merged: List<String> = emptyList(),
        val stillDirty: List<String> = emptyList(),
        val failures: Map<String, String> = emptyMap(),
        /** Set when the mass-deletion guard refused the run (protocol §6.6). */
        val blockedByDeletionGuard: Boolean = false,
        /**
         * How many notebook tombstones the guard held back — not the size of the whole queue,
         * which is what the warning used to report.
         */
        val deletionsHeldBack: Int = 0,
    )

    data class PullReport(
        val applied: List<String> = emptyList(),
        /** Documents where the local copy held content the server lacked; queued to push back. */
        val pushBack: List<String> = emptyList(),
        val skippedEchoes: List<String> = emptyList(),
        val conflictCopies: List<String> = emptyList(),
        /** Image blobs downloaded for pages that reference them (protocol §3.4). */
        val fetchedAssets: List<String> = emptyList(),
        val lastSeq: String = "0",
    ) {
        /** Folds a later batch of the same pull into this one. */
        fun merge(next: PullReport) = PullReport(
            applied = applied + next.applied,
            pushBack = pushBack + next.pushBack,
            skippedEchoes = skippedEchoes + next.skippedEchoes,
            conflictCopies = conflictCopies + next.conflictCopies,
            fetchedAssets = fetchedAssets + next.fetchedAssets,
            lastSeq = next.lastSeq,
        )
    }

    private val mutex = Mutex()
    private var lastSeq: String = state.lastSeq
    private val revs: MutableMap<String, String> = LinkedHashMap(state.revs)
    private val dirty: MutableSet<String> = LinkedHashSet(state.dirty)

    val currentState: CouchSyncState
        get() = CouchSyncState(lastSeq = lastSeq, revs = revs.toMap(), dirty = dirty.toSet())

    val pendingCount: Int get() = dirty.size

    /**
     * Queues documents for the next flush. Called from every local mutation; safe to call
     * repeatedly and while offline, which is what makes the outbox the offline story.
     */
    suspend fun markDirty(documentIds: List<String>) = mutex.withLock {
        dirty.addAll(documentIds)
        persist()
    }

    /**
     * Which of [candidates] this device has neither sent nor received, and is not already holding
     * in the outbox.
     *
     * [revs] records a revision for every document that has been through the server in either
     * direction, so its absence is a reliable "the server has never seen this from us". That makes
     * a document created locally and never edited afterwards — a notebook made and left alone,
     * a folder, an import — detectable without a per-row dirty flag.
     *
     * This is a safety net under [markDirty], not a replacement for it. Enqueueing on the edit is
     * what makes a change push *promptly*; this is what stops one being lost for good when a
     * mutation site forgets to. It cannot see a change to a document already sent once, which still
     * has to be queued at the point of the edit.
     */
    suspend fun neverSent(candidates: List<String>): List<String> = mutex.withLock {
        candidates.filter { it !in revs && it !in dirty }
    }

    // region Push

    suspend fun flush(): FlushReport = mutex.withLock {
        val pushed = mutableListOf<String>()
        val merged = mutableListOf<String>()
        val stillDirty = mutableListOf<String>()
        val failures = LinkedHashMap<String, String>()
        var queue = orderedDirty()

        // Only the deletions are held back. Blocking the whole queue meant a guard meant to
        // question a suspicious *deletion* also stopped ordinary edits syncing — and since the
        // confirmation it asks for does not exist yet, that was a permanent stall rather than a
        // prompt. Drawings keep flowing; the tombstones wait.
        var deletionsHeldBack = 0
        if (exceedsDeletionGuard(queue)) {
            val held = queue.filter { isNotebookTombstone(it) }
            deletionsHeldBack = held.size
            stillDirty += held
            queue = queue - held.toSet()
        }

        for (documentId in queue) {
            try {
                when (push(documentId)) {
                    PushOutcome.PUSHED -> pushed += documentId
                    PushOutcome.MERGED_THEN_PUSHED -> merged += documentId
                    PushOutcome.NOTHING_TO_PUSH -> Unit
                }
            } catch (error: CouchError) {
                failures[documentId] = error.detail
                stillDirty += documentId
                // Offline or a server fault applies to every remaining document too; stopping
                // keeps one dead connection from turning into a burst of doomed requests.
                // ...and so do rejected credentials, which no amount of retrying will fix.
                if (error.isRetriable || error is CouchError.Unauthorized) break
            } catch (error: Exception) {
                failures[documentId] = error.toString()
                stillDirty += documentId
            }
        }
        persist()
        FlushReport(
            pushed = pushed,
            merged = merged,
            stillDirty = stillDirty,
            failures = failures,
            blockedByDeletionGuard = deletionsHeldBack > 0,
            deletionsHeldBack = deletionsHeldBack,
        )
    }

    private enum class PushOutcome { PUSHED, MERGED_THEN_PUSHED, NOTHING_TO_PUSH }

    private suspend fun push(documentId: String): PushOutcome {
        var didMerge = false
        repeat(maxPushAttempts) {
            val local = store.load(documentId)
            if (local == null) {
                // Nothing locally: the document was never created, or was cleaned up after being
                // queued. Dropping it from the outbox is right — there is nothing to send.
                dirty.remove(documentId)
                return PushOutcome.NOTHING_TO_PUSH
            }

            try {
                val rev = putBody(documentId, local)
                revs[documentId] = rev
                dirty.remove(documentId)
                return if (didMerge) PushOutcome.MERGED_THEN_PUSHED else PushOutcome.PUSHED
            } catch (_: CouchError.Conflict) {
                if (CouchDocId.split(documentId)?.first == CouchDocType.ASSET) {
                    // Protocol §3.4: an asset id is the hash of its bytes, so a document already
                    // at that id *is* this upload. Merging or retrying would only re-send bytes
                    // the server demonstrably has.
                    //
                    // Its revision is read anyway, because a known revision is how the next flush
                    // tells "already uploaded" from "never sent" — without it every flush would
                    // re-offer the whole image just to be told again that it is there.
                    runCatching { client.getRaw(documentId)?.rev }.getOrNull()
                        ?.let { revs[documentId] = it }
                    dirty.remove(documentId)
                    return PushOutcome.PUSHED
                }
                didMerge = true
                val remote = fetchBody(documentId)
                if (remote == null) {
                    // Vanished between the write and the re-read: retry as a create.
                    revs.remove(documentId)
                    return@repeat
                }
                revs[documentId] = remote.first
                val merged = CouchMerge.mergeBodies(local, remote.second)
                if (merged == null) {
                    // Shapes disagree — do not overwrite either side.
                    client.getRaw(documentId)?.let { store.applyConflictCopy(documentId, it.json) }
                    dirty.remove(documentId)
                    return PushOutcome.NOTHING_TO_PUSH
                }
                if (merged != local) store.apply(documentId, merged, basedOn = local)
                // The server already holds this, so there is nothing left to send. Returning here
                // is not just an optimization: when the merge resolves to the peer's tombstone,
                // CouchDB answers 409 to a PUT that re-deletes an already deleted document *even
                // with its current revision* — so writing it back would spin until the retries ran
                // out and leave the id stuck in the outbox forever.
                //
                // The deleted case needs its own test rather than plain equality: two devices that
                // deleted the same document independently merge to a tombstone whose `updatedAt`
                // and `updatedBy` differ from the stored one — equal deletions, unequal documents.
                // There is nothing to send either way; the deletion is already recorded.
                if (merged == remote.second || (merged.isDeleted && remote.second.isDeleted)) {
                    dirty.remove(documentId)
                    return PushOutcome.NOTHING_TO_PUSH
                }
            }
        }
        throw CouchError.Conflict(documentId)
    }

    private suspend fun putBody(documentId: String, body: CouchDocBody): String {
        val rev = revs[documentId]
        return when (body) {
            is CouchDocBody.Page ->
                client.put(documentId, rev, body.page, CouchPage.serializer())

            is CouchDocBody.Notebook ->
                client.put(documentId, rev, body.notebook, CouchNotebook.serializer())

            is CouchDocBody.Folder ->
                client.put(documentId, rev, body.folder, CouchFolder.serializer())

            is CouchDocBody.Asset ->
                client.put(documentId, rev, body.asset, CouchAsset.serializer())

            is CouchDocBody.Deleted ->
                client.put(
                    documentId, rev, body.tombstone, CouchDeletedDoc.serializer(), deleted = true
                )
        }
    }

    private suspend fun fetchBody(documentId: String): Pair<String, CouchDocBody>? {
        val raw = client.getRaw(documentId) ?: return null
        val body = decode(documentId, raw.json, raw.deleted) ?: return null
        return raw.rev to body
    }

    /**
     * Push order: assets, then folders and pages, then notebooks. A notebook names its folder and
     * its pages, so sending it last means a reader never sees a manifest pointing at documents
     * that have not landed yet — and an image's bytes go before the page that places it, so the
     * peer never has a reference it cannot resolve.
     *
     * Assets are not queued by the app: nothing "edits" one, and an image placed twice is the same
     * document. They are derived here from the pages being sent, and skipped once the server is
     * known to hold them — immutability means a revision we have seen can never go stale.
     */
    private fun orderedDirty(): List<String> {
        fun rank(documentId: String): Int = when (CouchDocId.split(documentId)?.first) {
            CouchDocType.ASSET -> 0
            CouchDocType.FOLDER -> 1
            CouchDocType.PAGE -> 2
            else -> 3
        }
        val queue = dirty.toMutableSet()
        for (documentId in dirty) {
            if (CouchDocId.split(documentId)?.first != CouchDocType.PAGE) continue
            val body = runCatching { store.load(documentId) }.getOrNull() ?: continue
            queue += body.referencedAssetIds.filter { it !in revs }
        }
        return queue.sortedWith(compareBy({ rank(it) }, { it }))
    }

    /**
     * Protocol §6.6: a device whose local database was wiped looks exactly like a user who deleted
     * everything. Ten-plus notebook tombstones that are also most of what this device knows is
     * treated as the former until a human says otherwise.
     */
    private fun isNotebookTombstone(documentId: String): Boolean =
        CouchDocId.split(documentId)?.first == CouchDocType.NOTEBOOK &&
            (runCatching { store.load(documentId) }.getOrNull()?.isDeleted ?: false)

    private fun exceedsDeletionGuard(queue: List<String>): Boolean {
        val tombstones = queue.filter { isNotebookTombstone(it) }
        if (tombstones.size < MASS_DELETION_FLOOR) return false
        // What this device actually holds, not every id it has ever synced. `revs` is never pruned,
        // so a library that has seen a hundred notebooks come and go kept all hundred in the
        // denominator — and the guard quietly stopped being able to trip at all, which is the one
        // thing it must not do. Deleted notebooks count as known: they are what is being asked
        // about.
        val known = (runCatching { store.allDocumentIds() }.getOrNull().orEmpty() + tombstones)
            .filter { CouchDocId.split(it)?.first == CouchDocType.NOTEBOOK }
            .toSet()
        return tombstones.size * 2 > known.size
    }

    // endregion

    // region Pull

    /**
     * Applies everything the server has seen since the last checkpoint.
     *
     * [longpoll] holds the request open until a change arrives — the near-real-time path. A
     * non-longpoll call returns immediately and is used to catch up on foreground/reconnect.
     */
    /**
     * Catch up with the server, optionally waiting for it to report something.
     *
     * The wait happens **outside** [mutex] on purpose. A long poll is a request designed to sit
     * there until something changes — up to a minute of doing nothing — and holding the engine lock
     * across it froze every push, every queued edit and every manual sync behind it for that whole
     * window. The lock is taken twice instead: once to read the checkpoint, and once to apply what
     * came back. Between those two, anything else that needs the engine can have it.
     *
     * Two pulls can therefore overlap, which is safe because applying a change is idempotent — a row
     * already applied merges to the identical document, or is skipped as our own echo. The one thing
     * that must not happen is the checkpoint going backwards, so [applyChanges] advances it only if
     * nobody moved it while this pull was waiting.
     */
    suspend fun pull(
        longpoll: Boolean = false,
        timeoutMs: Long = CouchDbClient.DEFAULT_LONGPOLL_MS,
    ): PullReport {
        // Read the feed in batches rather than in one response. A catch-up from `0` — a fresh
        // install, or any device whose checkpoint was lost — otherwise asks for the entire library
        // at once, every page with its base64 ink inlined, and holds the lot in memory before
        // applying any of it. On a device with this one's memory that is the difference between
        // slow and dead. Checkpointing each batch also means an interrupted catch-up resumes where
        // it stopped instead of starting over.
        //
        // A longpoll is never paged: it is one wait for one notification, and the batch that
        // follows is whatever changed while it waited.
        var report = PullReport()
        while (true) {
            val since = mutex.withLock { lastSeq }
            val changes = client.changes(
                since = since,
                longpoll = longpoll,
                timeoutMs = timeoutMs,
                limit = if (longpoll) null else CATCH_UP_BATCH_SIZE,
            )
            val batch = mutex.withLock { applyChanges(changes, since) }
            report = report.merge(batch)
            // The server is caught up when it returns a short batch; a full one may have more
            // behind it. A full batch that did not move the checkpoint would ask the same question
            // forever — no CouchDB does that, which is why it is worth refusing to loop on it here
            // rather than finding out on a device with the battery draining.
            if (longpoll || changes.rows.size < CATCH_UP_BATCH_SIZE) break
            if (mutex.withLock { lastSeq } == since) break
        }
        // Also outside the lock: this is a download queue, and the blobs belong to the store rather
        // than to any state this engine guards. Fetching them under the lock would reintroduce
        // exactly the stall the split above exists to remove, just with images instead of waiting.
        return report.copy(fetchedAssets = fetchMissingAssets())
    }

    /**
     * Apply one feed batch. Callers hold [mutex].
     *
     * [since] is the checkpoint this batch was fetched from; see [pull] for why it is needed.
     */
    private suspend fun applyChanges(changes: CouchDbClient.Changes, since: String): PullReport {
        val applied = mutableListOf<String>()
        val pushBack = mutableListOf<String>()
        val skippedEchoes = mutableListOf<String>()
        val conflictCopies = mutableListOf<String>()

        for (row in changes.rows) {
            // Our own write coming back. Applying it would be harmless (merges are idempotent) but
            // it would also mark the document dirty and start a push ping-pong.
            if (revs[row.id] == row.rev) {
                skippedEchoes += row.id
                continue
            }
            // An asset announces itself here without its bytes — the feed carries the document,
            // and CouchDB renders an attachment as a stub. Downloading every image the moment it
            // appears would also mean downloading images for notebooks this device may never open,
            // so the bytes are fetched below, for the pages that turn out to place them.
            if (CouchDocId.split(row.id)?.first == CouchDocType.ASSET) {
                revs[row.id] = row.rev
                continue
            }

            val json = row.json
            if (json == null) {
                // The server elided the body; there is nothing to apply and nothing to copy.
                revs[row.id] = row.rev
                continue
            }
            val incoming = decode(row.id, json, row.deleted)
            if (incoming == null) {
                store.applyConflictCopy(row.id, json)
                conflictCopies += row.id
                revs[row.id] = row.rev
                continue
            }

            val local = store.load(row.id)
            val merged: CouchDocBody
            if (local != null) {
                val result = CouchMerge.mergeBodies(local, incoming)
                if (result == null) {
                    store.applyConflictCopy(row.id, json)
                    conflictCopies += row.id
                    revs[row.id] = row.rev
                    continue
                }
                merged = result
            } else {
                merged = incoming
            }

            store.apply(row.id, merged, basedOn = local)
            applied += row.id
            // Record the server's revision either way: it is the base the next push must use.
            revs[row.id] = row.rev
            if (merged != incoming) {
                // The local copy carried content the server has not seen — push it back.
                dirty.add(row.id)
                pushBack += row.id
            }
        }

        // Only if nothing moved it while this pull was waiting. An overlapping pull that already
        // advanced the checkpoint is at least as far along as this batch, and overwriting it with
        // an older sequence would replay changes we have already applied — harmless, since applying
        // is idempotent, but it would do so on every pull from then on.
        if (lastSeq == since) lastSeq = changes.lastSeq
        persist()
        return PullReport(
            applied = applied,
            pushBack = pushBack,
            skippedEchoes = skippedEchoes,
            conflictCopies = conflictCopies,
            // Filled in by `pull`, which fetches blobs after releasing the lock.
            fetchedAssets = emptyList(),
            lastSeq = changes.lastSeq,
        )
    }

    /**
     * Downloads the blobs local pages reference and this device does not hold yet.
     *
     * Driven by the store's own list rather than by what this pull happened to apply, so a fetch
     * that failed — offline halfway through, a peer that had not uploaded the bytes yet — is
     * simply retried on the next pull instead of needing the page to change again.
     *
     * A failure here never fails the pull: the page and its ink are already applied, and an image
     * that is still on its way is a picture that has not appeared yet, not lost work.
     */
    private suspend fun fetchMissingAssets(): List<String> {
        val wanted = runCatching { store.missingAssetIds() }.getOrNull().orEmpty()
        val fetched = mutableListOf<String>()
        for (assetId in wanted) {
            val sha = CouchAssetId.sha256HexOfAssetId(assetId) ?: continue
            val blob = runCatching { client.getAttachment(assetId) }.getOrNull() ?: continue
            // The id is a promise about the bytes. Checking it costs one hash and turns a
            // truncated or mis-served download into a retry rather than into a corrupt image that
            // would then be re-uploaded under a name that does not describe it.
            if (CouchAssetId.sha256Hex(blob.bytes) != sha) continue
            val now = Instant.now().toString()
            val asset = CouchAsset.of(
                blob.bytes, at = now, updatedBy = deviceId, contentType = blob.contentType
            )
            runCatching { store.apply(assetId, CouchDocBody.Asset(asset), basedOn = null) }
                .onSuccess { fetched += assetId }
        }
        return fetched
    }

    // endregion

    // region Plumbing

    private fun decode(documentId: String, json: JsonObject, deleted: Boolean): CouchDocBody? {
        val type = CouchDocId.split(documentId)?.first ?: return null

        if (deleted) {
            // A tombstone whose body was stripped (or written by a client that did not keep one)
            // still has to be applied; synthesize the minimum the merge needs.
            val decoded = runCatching {
                couchJson.decodeFromJsonElement(CouchDeletedDoc.serializer(), json)
            }.getOrNull()
            // `deletedAt` is left *empty* rather than stamped with the current time. Stamping "now"
            // reads as a deletion newer than any edit this device has ever made, so §6.4's
            // resurrect branch became unreachable from here and a stripped tombstone destroyed
            // work the user did after the deletion. Empty means unknown and loses the comparison.
            return CouchDocBody.Deleted(
                decoded ?: CouchDeletedDoc(type = type, updatedBy = deviceId)
            )
        }

        // A document from a future schema is not something this build can merge safely.
        val schema = json["schema"]?.jsonPrimitive?.intOrNull
        if (schema != null && schema > COUCH_SCHEMA_VERSION) return null

        return when (type) {
            CouchDocType.PAGE ->
                decodeOrNull(json, CouchPage.serializer())?.let { CouchDocBody.Page(it) }

            CouchDocType.NOTEBOOK ->
                decodeOrNull(json, CouchNotebook.serializer())?.let { CouchDocBody.Notebook(it) }

            CouchDocType.FOLDER ->
                decodeOrNull(json, CouchFolder.serializer())?.let { CouchDocBody.Folder(it) }

            else -> null
        }
    }

    private fun <T> decodeOrNull(json: JsonObject, serializer: KSerializer<T>): T? =
        runCatching { couchJson.decodeFromJsonElement(serializer, json) }.getOrNull()

    private fun persist() {
        onStateChange?.invoke(currentState)
    }

    // endregion

    companion object {
        private const val DEFAULT_MAX_PUSH_ATTEMPTS = 5

        /** Protocol §6.6: below this many notebook tombstones, a flush is never refused. */
        private const val MASS_DELETION_FLOOR = 10

        /**
         * Rows per catch-up request. Small enough that a library of any size arrives in pieces this
         * device can hold and apply, large enough that a routine catch-up is still one round trip.
         */
        private const val CATCH_UP_BATCH_SIZE = 100
    }
}

// endregion
