package com.ethran.notable.sync.couch

import com.ethran.notable.sync.SyncLogger
import kotlinx.coroutines.CancellationException
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
    /**
     * Which database the state above describes (protocol §1.2), or null for state written before
     * this field existed — or by a device that has not yet met a server carrying the metadata.
     *
     * The checkpoint and revision cache are only meaningful against the database they were built
     * from, and "same address, same name" does not establish that: a database dropped and recreated
     * answers to both. Null is treated as "not yet known", never as "any database will do".
     */
    val databaseGeneration: String? = null,
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
     * The `asset:` documents this body names: a page's placed images and its background, and a
     * notebook's default background. The engine uses this to send those bytes before the document
     * that references them, so the peer never has a reference it cannot resolve.
     *
     * The filter is the whole test. A `background` that is not a content-addressed id is a native
     * template's name (`"lined"`) or a path from a build that predates backgrounds travelling —
     * neither names bytes anyone can fetch, and both fall out here without a special case.
     */
    val referencedAssetIds: List<String>
        get() = when (this) {
            is Page -> (page.images.mapNotNull { it.assetId } + page.background)
                .filter { CouchAssetId.sha256HexOfAssetId(it) != null }

            is Notebook -> listOf(notebook.defaultBackground)
                .filter { CouchAssetId.sha256HexOfAssetId(it) != null }

            else -> emptyList()
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

    /**
     * The durable outbox: documents the app changed and the server has not accepted yet.
     *
     * The engine keeps the same set in memory, but the table is what survives being killed. The app
     * writes its rows inside the same transaction as the data they describe, which is the whole
     * point — "the notebook moved" is not something Room remembers once the write has landed, and
     * the outbox is the only place that fact exists. The engine therefore treats the table as the
     * source and its own set as a cache of it.
     *
     * A store with nowhere to persist an outbox answers nothing and keeps the in-memory behaviour,
     * which is what the JVM engine tests and the scenario fixtures rely on.
     */
    fun pendingOutboxIds(): List<String> = emptyList()

    /** Records that these documents have to be sent. Idempotent. */
    fun enqueueOutbox(documentIds: List<String>) {}

    /** Forgets a document the server has now accepted. */
    fun dequeueOutbox(documentId: String) {}

    /**
     * Forgets a locally recorded deletion **without** deleting anything in its place, and without
     * bringing the document back.
     *
     * This is the half of "keep them on the server" that only the store can do: the row is already
     * gone from the library — the delete and the tombstone were written in one transaction — so
     * dropping the tombstone leaves this device holding nothing and telling the server nothing. The
     * server still has the document, and the next pull brings it back. That *is* the undo, and it
     * is the outcome the user has to be told about before choosing (protocol §6.7).
     *
     * A store with nowhere to record deletions has none to discard, which is why this has a default.
     */
    fun discardDeletion(documentId: String) {}
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
    /**
     * The pause before conflict retry *n* is `n × this`. A 409 means another device is writing
     * the same document right now, and retrying in a tight loop mostly re-loses the same race —
     * five attempts in single-digit milliseconds — while a beat's pause lets the peer's burst of
     * writes finish. Injectable so tests can observe the pacing instead of paying it.
     */
    private val conflictBackoffMs: Long = CONFLICT_BACKOFF_MS,
    private val sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    /**
     * Whether a database-identity problem stops sync (§1.2 stage 3) or is merely reported.
     *
     * Off by default, and that is the whole point of the staged rollout: a client that *required*
     * the metadata would refuse to sync with a peer of the previous release, which has never
     * written it. Reading, creating and recording run unconditionally; only blocking waits.
     */
    private val enforceDatabaseIdentity: Boolean = false,
    private val nowIso: () -> String = { Instant.now().toString() },
    private val newGeneration: () -> String = { java.util.UUID.randomUUID().toString() },
    private val onStateChange: ((CouchSyncState) -> Unit)? = null,
    /**
     * Persists the state and does not return until the write has durably landed — or throws when
     * it could not. [onStateChange] is fire-and-forget by design (the host conflates the writes
     * onto one consumer), which is right for every routine save and wrong for exactly one thing:
     * a state that *must* be on disk before the next destructive step is allowed to happen. See
     * [discardHeldDeletions]. Null falls back to [onStateChange], which is what the JVM engine
     * tests and any host without a synchronous path get.
     */
    private val onStateChangeSync: (suspend (CouchSyncState) -> Unit)? = null,
) {

    data class FlushReport(
        val pushed: List<String> = emptyList(),
        val merged: List<String> = emptyList(),
        val stillDirty: List<String> = emptyList(),
        val failures: Map<String, String> = emptyMap(),
        /**
         * The typed error behind each entry in [failures], same keys. [failures] keeps the stable
         * raw detail (`unauthorized`, `server(413, …)`) for the log; anything user-facing maps
         * these through the controller's friendly wording instead, so an engine string never
         * reaches the screen verbatim.
         */
        val failureCauses: Map<String, Throwable> = emptyMap(),
        /**
         * The tombstones the mass-deletion guard held back (protocol §6.7) — notebooks and the
         * folders deleted with them — **which** ones, not merely how many.
         *
         * A count is enough to warn with and useless to act on: the whole point of the guard is that
         * a human decides, and the two things they can decide — send these deletions after all, or
         * drop them — both need the exact set. So the set is what travels up to the UI, and the two
         * summaries below are read off it rather than reported alongside it, because two fields that
         * can disagree eventually will.
         */
        val heldDeletions: List<String> = emptyList(),
        /** See [CouchDbClient.clockSkewSeconds]. Null means "nothing worth saying". */
        val clockSkewSeconds: Long? = null,
        /**
         * Whether any failure above could plausibly succeed on a later attempt — offline, a 5xx, a
         * 429. False when the only failures were ones the server will keep refusing, which is what
         * tells the controller to stop rescheduling and leave the message standing.
         */
        val hasRetriableFailure: Boolean = false,
        /**
         * The longest `Retry-After` any server sent during this flush. The controller waits at
         * least this long instead of its own backoff.
         */
        val retryAfterMs: Long? = null,
        /**
         * What the database said about itself this pass (§1.2). Reported even when it is not
         * enforced, which is what makes the staged rollout observable before it is enabled.
         */
        val databaseIdentity: DatabaseIdentity? = null,
    ) {
        /** Set when the mass-deletion guard held something back. */
        val blockedByDeletionGuard: Boolean get() = heldDeletions.isNotEmpty()

        /**
         * How many tombstones the guard held back — not the size of the whole queue,
         * which is what the warning used to report.
         */
        val deletionsHeldBack: Int get() = heldDeletions.size
    }

    data class PullReport(
        val applied: List<String> = emptyList(),
        /** Documents where the local copy held content the server lacked; queued to push back. */
        val pushBack: List<String> = emptyList(),
        val skippedEchoes: List<String> = emptyList(),
        val conflictCopies: List<String> = emptyList(),
        /** Image blobs downloaded for pages that reference them (protocol §3.4). */
        val fetchedAssets: List<String> = emptyList(),
        /**
         * Referenced images the server does not hold yet — a peer wrote the page before its bytes
         * finished uploading. Retried on every pull, and the commonest reason a page shows a gap
         * where a picture belongs.
         */
        val missingAssets: List<String> = emptyList(),
        /**
         * Blobs whose bytes did not hash to the id that named them. Never stored, never
         * re-uploaded: the id is a promise about the content, and writing these would break it.
         */
        val corruptAssets: List<String> = emptyList(),
        /** Why each asset above did not arrive, by asset id. Safe to show: no paths, no secrets. */
        val assetFailures: Map<String, String> = emptyMap(),
        val lastSeq: String = "0",
        /** See [CouchDbClient.clockSkewSeconds]. Null means "nothing worth saying". */
        val clockSkewSeconds: Long? = null,
        /**
         * Feed batches dropped because an overlapping pull had already moved the checkpoint past
         * them. Diagnostic only — never surfaced as a failure. A foreground catch-up racing the
         * longpoll is normal, and the batch is refetched from the winner's checkpoint.
         */
        val discardedStaleBatches: Int = 0,
        /**
         * What the database said about itself this pass (§1.2). Reported even when it is not
         * enforced, which is what makes the staged rollout observable before it is enabled.
         */
        val databaseIdentity: DatabaseIdentity? = null,
    ) {
        /**
         * Whether anything about the images is worth telling the user. The page and its ink are
         * applied either way — this never means the sync failed.
         */
        val hasAssetProblems: Boolean
            get() = missingAssets.isNotEmpty() ||
                corruptAssets.isNotEmpty() ||
                assetFailures.isNotEmpty()

        /** Folds a later batch of the same pull into this one. */
        fun merge(next: PullReport) = PullReport(
            applied = applied + next.applied,
            pushBack = pushBack + next.pushBack,
            skippedEchoes = skippedEchoes + next.skippedEchoes,
            conflictCopies = conflictCopies + next.conflictCopies,
            fetchedAssets = fetchedAssets + next.fetchedAssets,
            missingAssets = missingAssets + next.missingAssets,
            corruptAssets = corruptAssets + next.corruptAssets,
            assetFailures = assetFailures + next.assetFailures,
            lastSeq = next.lastSeq,
            clockSkewSeconds = next.clockSkewSeconds,
            discardedStaleBatches = discardedStaleBatches + next.discardedStaleBatches,
            databaseIdentity = next.databaseIdentity ?: databaseIdentity,
        )
    }

    private val mutex = Mutex()

    /**
     * Set once this engine's configuration has been replaced — see [invalidate]. Checked wherever
     * state is handed out for persistence, never around the work itself: a PUT already in flight is
     * a real write and may finish, but nothing this engine does from here on may reach the state
     * store, because the state store now belongs to the successor.
     */
    private val invalidated = java.util.concurrent.atomic.AtomicBoolean(false)

    private var lastSeq: String = state.lastSeq
    private val revs: MutableMap<String, String> = LinkedHashMap(state.revs)
    private val dirty: MutableSet<String> = LinkedHashSet(state.dirty)
    private var databaseGeneration: String? = state.databaseGeneration

    /**
     * What the last identity check saw, or null before the first one. Cached so pull and flush do
     * not each re-read the document on every pass.
     */
    private var identity: DatabaseIdentity? = null

    /**
     * Guards the identity check alone, deliberately not [mutex].
     *
     * The check makes a round trip, and holding the state lock across it would stall every push for
     * its duration — the same stall the split lock in [pull] exists to remove. Two callers arriving
     * together serialize here instead, and the second finds the answer cached.
     */
    private val identityMutex = Mutex()

    /**
     * Document ids the user has explicitly waved past the mass-deletion guard, and nothing else.
     *
     * Deliberately *not* persisted and deliberately *not* a boolean. A "stop guarding" flag is the
     * obvious implementation and the wrong one: one tap on one alarming batch would disarm the guard
     * for every batch afterwards, including the one that follows a wiped database — which is the
     * exact accident §6.7 exists to catch. Naming the ids keeps the approval as narrow as the
     * question that was asked.
     *
     * An entry lives until its document leaves the outbox, not until the next flush returns. The
     * flush that *acts* on the approval is the one that gets the deletion accepted; a flush that
     * failed offline acted on nothing, and making the user confirm a second time because the network
     * dropped would be punishing them for the wrong thing. Ids that are no longer queued at all are
     * pruned below, so this cannot accumulate.
     */
    private val approvedDeletions: MutableSet<String> = LinkedHashSet()

    /**
     * Documents the server refused as too large (413), by id, with the `updatedAt` of the exact
     * copy it refused. A 413 is terminal for that content — the server will keep saying it — but
     * the document sat in the outbox being re-derived and re-sent in full on every flush,
     * megabytes a pass for an oversized asset, only to hear the same refusal. Later flushes skip
     * an entry here until the document's `updatedAt` moves, which is the one thing that makes the
     * answer worth asking for again; the entry then leaves and the document gets one real attempt.
     *
     * In-memory on purpose: an engine lives as long as one configuration, and after a restart the
     * single re-send re-learns the entry. Guarded by [mutex] like everything else here.
     *
     * [refusedBy] decides what lifts the entry, and the two answers are opposites (§7.2). CouchDB
     * refusing a document is about its content, so a changed `updatedAt` is exactly the right key.
     * An intermediary refusing the request is about the *server's* configuration: nothing about the
     * document will ever change, so keying on `updatedAt` would hold it forever after the user
     * raised `client_max_body_size` — the one outcome worse than re-sending it. Those entries are
     * cleared by [rearmRefusedRequests] instead, on the occasions that plausibly follow a fix.
     */
    private class Oversized(
        val updatedAt: String,
        val requestBytes: Long?,
        val refusedBy: CouchError.Refuser,
    )
    private val oversized = LinkedHashMap<String, Oversized>()

    /**
     * Forgets every request an intermediary refused, so the next flush tries them for real.
     *
     * Called when something has plausibly changed on the far side — a reconnect, a settings change,
     * or the user explicitly asking to sync. The last is the one that matters: after editing the
     * proxy's body limit, "Sync now" is what a user actually does, and it must be the thing that
     * works rather than a button that reports the same stale refusal.
     *
     * Documents CouchDB itself refused are left alone: nothing on the server changes what a page
     * with too much ink in it weighs.
     */
    suspend fun rearmRefusedRequests() = mutex.withLock {
        oversized.entries.removeAll { it.value.refusedBy == CouchError.Refuser.INTERMEDIARY }
    }

    init {
        // The checkpoint blob's copy of the outbox is written asynchronously (see
        // `CouchSyncHost.stateWrites`), so it can be a moment behind at the point the process died.
        // The table is written inside the transaction that made the change, so it cannot be.
        adoptOutbox()
    }

    val currentState: CouchSyncState
        get() = CouchSyncState(
            lastSeq = lastSeq,
            revs = revs.toMap(),
            dirty = dirty.toSet(),
            databaseGeneration = databaseGeneration,
        )

    /** The last identity observation, for a UI that wants to explain a refusal. */
    val databaseIdentity: DatabaseIdentity? get() = identity

    // region Database identity (§1.2)

    /** What the last check made of the database this device is pointed at. */
    sealed class DatabaseIdentity {
        /**
         * The database this state was built against, or one that has just adopted this device's
         * first sight of it.
         */
        data class Matched(val generation: String) : DatabaseIdentity()

        /**
         * A database with the same address and name, but not the same database. Nothing is applied
         * or uploaded until a human chooses what should happen to the two libraries.
         */
        data class GenerationChanged(val stored: String, val found: String) : DatabaseIdentity()

        /** The server requires a newer client than this one. */
        data class ClientTooOld(val minimum: Int) : DatabaseIdentity()

        /** A rebuild is in progress on another device. */
        data class Locked(val reason: String?) : DatabaseIdentity()

        /**
         * The server holds no metadata: a database created before this document existed, or a peer
         * that has not shipped it yet. Explicitly *not* a mismatch.
         */
        object Unknown : DatabaseIdentity()

        val isUsable: Boolean get() = this is Matched || this is Unknown
    }

    /**
     * Reads — and on an empty database, creates — the identity document, and reconciles it with
     * what this device believes.
     *
     * Called before the feed is read and before the outbox is sent. Cheap after the first pass: the
     * result is cached for the life of the engine, which is the life of one configuration.
     */
    suspend fun verifyDatabaseIdentity(): DatabaseIdentity = identityMutex.withLock {
        identity?.let { return@withLock it }

        val stored = databaseGeneration
        val metadata = try {
            readDatabaseMetadata()
        } catch (e: CouchError.NotFound) {
            null
        }

        if (metadata == null) {
            // No metadata. On a database that already holds documents this means "created before
            // this document existed", and the only safe reading is to leave it alone: treating a
            // missing identity as permission to mint one — and therefore as permission to decide
            // this is a *new* database — is how a client would come to rebuild a library it should
            // have been syncing with.
            //
            // On an empty database there is nothing to be wrong about, so this device names it.
            val resolved = claimEmptyDatabase() ?: DatabaseIdentity.Unknown
            identity = resolved
            return@withLock resolved
        }

        val resolved = when {
            metadata.minimumClientProtocol > COUCH_PROTOCOL_VERSION ->
                DatabaseIdentity.ClientTooOld(metadata.minimumClientProtocol)

            metadata.locked -> DatabaseIdentity.Locked(metadata.lockReason)

            stored == null -> {
                // First sight of a database that already has an identity. Adopt it — this device
                // has no prior claim to contradict it with.
                mutex.withLock {
                    databaseGeneration = metadata.generation
                    persist()
                }
                DatabaseIdentity.Matched(metadata.generation)
            }

            stored == metadata.generation -> DatabaseIdentity.Matched(metadata.generation)

            // The name and address are the same; the database is not. The checkpoint describes
            // history this server never had, and the revision cache would suppress genuine changes
            // as though they were this device's own echoes.
            else -> DatabaseIdentity.GenerationChanged(stored, metadata.generation)
        }
        identity = resolved
        return@withLock resolved
    }

    /**
     * The identity document as live metadata, or null when the server effectively holds none.
     *
     * Read raw rather than through the typed `get`, because two shapes of tombstone must read as
     * *absent* and used not to:
     *
     * - A `_deleted` document written with its body intact (a `PUT` tombstone) decoded cleanly and
     *   was treated as live metadata — a deleted identity resurrected as an authoritative one.
     * - A bare tombstone from a plain HTTP `DELETE` carries no `generation`, so the typed decode
     *   threw [CouchError.MalformedResponse] straight through [verifyDatabaseIdentity]'s
     *   NotFound-only catch — and since [pull] calls the check unconditionally, one deleted
     *   identity document permanently failed every pull.
     *
     * A deleted identity document is somebody having reset the database's bookkeeping, not a
     * statement about whose database it is: the null routes into the existing no-metadata branch —
     * claim it when empty, [DatabaseIdentity.Unknown] otherwise. An identity document that exists
     * but will not decode is treated the same way, for the same reason: refusing to sync forever
     * over unreadable bookkeeping is strictly worse than treating it as not-yet-known.
     */
    private suspend fun readDatabaseMetadata(): CouchDatabaseMetadata? {
        val raw = client.getRaw(CouchMetaDocId.DATABASE) ?: return null
        if (raw.deleted) return null
        return runCatching {
            couchJson.decodeFromJsonElement(CouchDatabaseMetadata.serializer(), raw.json)
        }.getOrNull()
    }

    /**
     * Names an empty database, or returns null when it turns out not to be empty.
     *
     * `PUT` with no `_rev` is the whole concurrency story: two devices reaching a fresh database
     * together both try to create the document, and CouchDB gives exactly one of them the write.
     * The loser re-reads and adopts the winner's generation rather than retrying, because the
     * question "which of us names it" has already been answered — but only a loser with no prior
     * identity of its own may adopt (§1.2); one that was already syncing a different generation
     * reports the mismatch instead of quietly becoming a client of a database it never knew.
     */
    private suspend fun claimEmptyDatabase(): DatabaseIdentity? {
        // Pre-existing library, no metadata: record nothing and block nothing.
        if (!isDatabaseEmpty()) return null

        val stored = databaseGeneration
        // Always minted fresh, never `stored`: §1.2 says a generation is minted *with the
        // database*, and this database is demonstrably not the one `stored` named — it is empty
        // where that one held a library. Re-writing the old generation onto the new database made
        // the wipe invisible: the stored checkpoint stayed "matched" and never replayed, the local
        // library was never re-offered, and every device reported healthy while the server copy
        // was silently gone.
        val metadata = CouchDatabaseMetadata(
            generation = newGeneration(),
            updatedAt = nowIso(),
        )
        return try {
            client.put(
                CouchMetaDocId.DATABASE,
                rev = null,
                body = metadata,
                serializer = CouchDatabaseMetadata.serializer(),
            )
            mutex.withLock {
                databaseGeneration = metadata.generation
                if (stored != null) restartAgainstRecreatedDatabase(stored, metadata.generation)
                persist()
            }
            DatabaseIdentity.Matched(metadata.generation)
        } catch (e: CouchError.Conflict) {
            // Another device got there first in the moment between the read and the write.
            val winner = client.get(CouchMetaDocId.DATABASE, CouchDatabaseMetadata.serializer())
                ?.body ?: return null
            if (stored != null && stored != winner.generation) {
                // The racing peer named the recreated database before this device could. That
                // makes it the §1.2 generation change it always was — a human decides what
                // happens to the two libraries, exactly as if the metadata had been found.
                return DatabaseIdentity.GenerationChanged(stored, winner.generation)
            }
            mutex.withLock {
                databaseGeneration = winner.generation
                persist()
            }
            DatabaseIdentity.Matched(winner.generation)
        }
    }

    /**
     * The stored state described a database that no longer exists — same address, same name, but
     * this device just watched an *empty* database answer where [stored] named a populated one.
     * That is §1.2's generation change with this device holding the only surviving copy, so the
     * recovery is the "rebuild the server from this device" arm, applied automatically because
     * claiming an empty database destroys nothing:
     *
     * - the checkpoint rewinds to `"0"` — `lastSeq` was a position in the dead database's feed;
     * - the revision cache clears — its entries would suppress every re-upload's echo wrongly;
     * - everything this device holds queues for push, because the server holds nothing.
     *
     * Callers hold [mutex] and persist afterwards.
     */
    private fun restartAgainstRecreatedDatabase(stored: String, minted: String) {
        SyncLogger.w(
            "CouchSync",
            "The sync database at this address is empty but was generation $stored when last " +
                "seen — it was wiped or recreated. Named it $minted and queueing this device's " +
                "whole library for re-upload."
        )
        lastSeq = "0"
        revs.clear()
        val everything = runCatching { store.allDocumentIds() }.getOrNull().orEmpty()
        dirty += everything
        runCatching { store.enqueueOutbox(everything) }
    }

    /**
     * Whether the server holds any document this protocol cares about. Reserved ids do not count: a
     * database holding nothing but protocol bookkeeping is still an empty library.
     *
     * A page of rows rather than one, because a single row could be a reserved id and say nothing
     * about what follows it. One page is enough: the answer only has to be right about a database
     * small enough to be plausibly new, and anything with a hundred documents is not.
     */
    private suspend fun isDatabaseEmpty(): Boolean {
        val probe = client.changes(since = "0", longpoll = false, limit = CATCH_UP_BATCH_SIZE)
        return probe.rows.all { CouchMetaDocId.isReserved(it.id) }
    }

    // endregion

    val pendingCount: Int get() = dirty.size

    /**
     * Queues documents for the next flush. Called from every local mutation; safe to call
     * repeatedly and while offline, which is what makes the outbox the offline story.
     */
    suspend fun markDirty(documentIds: List<String>) = mutex.withLock {
        dirty.addAll(documentIds)
        // Written through rather than left for the next checkpoint save: this is the one moment the
        // intent exists and has not been recorded anywhere that survives a restart.
        runCatching { store.enqueueOutbox(documentIds) }
        persist()
    }

    /** Folds the durable outbox into the in-memory set. Callers hold [mutex], or hold nothing yet. */
    private fun adoptOutbox() {
        dirty += runCatching { store.pendingOutboxIds() }.getOrNull().orEmpty()
    }

    /**
     * Drops a document the server has taken, from the in-memory set and the table together.
     *
     * A failure to delete the row is swallowed: the document has genuinely been sent, and the worst
     * a stale row can do is offer it again on the next flush, where it merges to something
     * identical and is dropped. Failing the push over it would be the worse trade.
     */
    private fun clearDirty(documentId: String) {
        dirty.remove(documentId)
        // An approval is spent the moment the document it named is off the queue — see
        // [approvedDeletions]. Anything else would leave a standing exemption behind.
        approvedDeletions.remove(documentId)
        runCatching { store.dequeueOutbox(documentId) }
    }

    /**
     * Lets exactly [ids] past the mass-deletion guard on the next flush — the "yes, delete them on
     * the server too" answer to the question the guard asked.
     *
     * Set-scoped and one-shot: see [approvedDeletions] for why it is neither a setting nor a flag.
     * Ids that are not actually held are harmless — they are pruned at the next flush.
     */
    suspend fun approveHeldDeletions(ids: List<String>) = mutex.withLock {
        approvedDeletions += ids
    }

    /**
     * Drops [ids]' tombstones entirely — the "keep them on the server" answer.
     *
     * Both records go: the deletion the store recorded, and the outbox row that would have sent it.
     * Nothing is pushed, and nothing is restored *here* — the notebooks are already gone from this
     * device's library, since the delete and the tombstone were written together. What brings them
     * back is the server, on the next pull, because it never heard about the deletion at all. That
     * is the undo, and the caller must have said so before getting here (protocol §6.7).
     */
    suspend fun discardHeldDeletions(ids: List<String>) = mutex.withLock {
        if (ids.isEmpty()) return@withLock

        // The checkpoint is rewound, or the promise made to the user is not kept.
        //
        // "They come back on the next pull" is only true if the server mentions them again, and the
        // change feed does not repeat itself: these documents were announced long ago, the
        // checkpoint is far past them, and nothing will re-announce a notebook nobody is editing.
        // Discarding without this would leave the user with the notebooks gone from this device,
        // present on the server, and no path between the two.
        //
        // Replaying the whole feed is the sledgehammer, and this design already calls it safe — it
        // is exactly what a lost checkpoint costs, slow but correct, because every merge is
        // idempotent. Paying it here is fine: this is a deliberate, rare answer to a prompt, not
        // something a drawing can trigger. It also survives being offline, which a targeted re-fetch
        // would not — the replay simply happens whenever the network comes back.
        //
        // The rev map goes with it — the *whole* map, not the discarded ids' entries. A recorded
        // revision is what makes a replayed row look like this device's own echo, and the replay
        // has to re-deliver more than the notebook documents themselves: deleting the notebooks
        // locally cascaded their PAGE rows away, and nothing tombstoned those pages, so their
        // server revisions are unchanged and still in the map. Clearing only the notebooks' revs
        // let the manifests re-apply while every page under them was skipped as an echo — the
        // notebooks came back as empty shells. With the checkpoint at "0" the map rebuilds itself
        // over the replay; the cost is the 409-then-merge round trip on the next push of each
        // still-held document, which resolves to "nothing to send".
        revs.clear()
        lastSeq = "0"

        // ...and the rewind lands durably BEFORE a single tombstone is dropped. The two halves of
        // this method compensate each other — dropping the tombstones is what forgets the deletion,
        // replaying the feed is what brings the documents back — and the routine persist path is an
        // asynchronous, conflated channel. A crash after the drops but before that write left the
        // tombstones forgotten AND the checkpoint still past the documents: the promised restore
        // simply never happened. Written the other way round, a crash in the window costs a feed
        // replay while the tombstones are still held — safe, and the user's answer still stands to
        // be given again. A rewind that cannot be written durably aborts the discard entirely, for
        // the same reason.
        persistDurably()

        for (documentId in ids) {
            runCatching { store.discardDeletion(documentId) }
            clearDirty(documentId)
        }
        persist()
    }

    /**
     * Persists through the synchronous path when the host provides one, suspending until the write
     * has landed; falls back to the ordinary fire-and-forget persist otherwise. Throws when the
     * durable write failed — callers use this precisely because proceeding without the write on
     * disk would be unsafe. An invalidated engine refuses outright, for [persist]'s reason.
     */
    private suspend fun persistDurably() {
        val write = onStateChangeSync ?: run {
            persist()
            return
        }
        check(!invalidated.get()) { "this engine's configuration has been replaced" }
        write(currentState)
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

    suspend fun flush(): FlushReport {
        // Checked before a single document goes out, and before the flush lock is taken. Uploading
        // a library into a database that is not the one this device has been syncing with is the
        // one mistake here that cannot be undone from the other side.
        //
        // Outside the lock for two reasons: it makes a round trip, and [verifyDatabaseIdentity]
        // takes [mutex] itself to record what it learns — Kotlin's `Mutex` is not reentrant, so
        // asking from inside would deadlock rather than fail.
        //
        // A check that could not complete is *not* evidence about identity — offline is the usual
        // reason — so it is swallowed and the flush proceeds exactly as it did before this existed.
        // The pushes will discover the same network for themselves, and report it per document the
        // way every other caller expects.
        val identity = runCatching { verifyDatabaseIdentity() }.getOrNull()
        if (enforceDatabaseIdentity && identity != null && !identity.isUsable) {
            return mutex.withLock {
                val refusal = CouchError.DatabaseIdentity(identity)
                FlushReport(
                    stillDirty = dirty.sorted(),
                    failures = mapOf(CouchMetaDocId.DATABASE to refusal.detail),
                    failureCauses = mapOf(CouchMetaDocId.DATABASE to refusal),
                    // Terminal on purpose: no amount of retrying resolves whose library this is.
                    // The outbox keeps everything, and the user's answer is what releases it.
                    hasRetriableFailure = false,
                    databaseIdentity = identity,
                )
            }
        }
        return flushDocuments(identity)
    }

    /** The flush proper. Callers have already settled [identity]. */
    private suspend fun flushDocuments(identity: DatabaseIdentity?): FlushReport = mutex.withLock {
        val pushed = mutableListOf<String>()
        val merged = mutableListOf<String>()
        val stillDirty = mutableListOf<String>()
        val failures = LinkedHashMap<String, String>()
        val failureCauses = LinkedHashMap<String, Throwable>()
        var hasRetriableFailure = false
        var retryAfterMs: Long? = null
        // The table, not only what `markDirty` was told. A repository queues a document by writing
        // its outbox row inside the transaction that changed the data, without going anywhere near
        // this engine — which is precisely what stops a new mutation site from being one forgotten
        // call away from a change that never leaves the device.
        adoptOutbox()
        var queue = orderedDirty()

        // An approval names ids, so it goes stale the moment they leave the queue. Pruning here
        // rather than at the end of the flush that used it keeps a set that can only ever shrink
        // towards what is actually waiting.
        approvedDeletions.retainAll(dirty)

        // Only the deletions are held back. Blocking the whole queue meant a guard meant to
        // question a suspicious *deletion* also stopped ordinary edits syncing — and while the
        // confirmation it asks for did not exist, that was a permanent stall rather than a prompt.
        // Drawings keep flowing; the tombstones wait for an answer.
        var heldDeletions = emptyList<String>()
        if (exceedsDeletionGuard(queue)) {
            // Approved ids are not held — but they are still counted by the guard above, so a batch
            // that is approved in part still asks about the rest rather than being waved through
            // wholesale. Folder tombstones wait alongside the notebooks' (they do not trip the
            // guard, but a tripped guard means the whole deletion is in question).
            val held = queue.filter { isHeldTombstone(it) && it !in approvedDeletions }
            heldDeletions = held
            stillDirty += held
            queue = queue - held.toSet()
        }

        // Documents this pass may not push because something they name was just refused — see
        // [suppressDependents]. Only ever ids later in [queue] than the failure that put them
        // here, because the push order sorts dependencies first.
        val suppressed = mutableSetOf<String>()

        for ((index, documentId) in queue.withIndex()) {
            if (documentId in suppressed) {
                stillDirty += documentId
                continue
            }
            if (skipAsOversized(documentId, queue, index, suppressed, failures, failureCauses)) {
                stillDirty += documentId
                continue
            }
            try {
                when (push(documentId)) {
                    PushOutcome.PUSHED -> pushed += documentId
                    PushOutcome.MERGED_THEN_PUSHED -> merged += documentId
                    PushOutcome.NOTHING_TO_PUSH -> Unit
                }
            } catch (error: CouchError) {
                failures[documentId] = error.detail
                failureCauses[documentId] = error
                stillDirty += documentId
                if (error is CouchError.Server && error.status == 413) {
                    // Remember exactly what was refused, so later flushes stop re-deriving and
                    // re-sending it — see [oversized] for what lifts the entry, which differs by
                    // which hop refused it (§7.2).
                    runCatching { store.load(documentId) }.getOrNull()?.let { body ->
                        oversized[documentId] =
                            Oversized(body.updatedAt, error.requestBytes, error.refusedBy)
                    }
                }
                if (error.isRetriable) {
                    hasRetriableFailure = true
                    (error as? CouchError.Server)?.retryAfterMs?.let {
                        retryAfterMs = maxOf(retryAfterMs ?: 0L, it)
                    }
                } else if (error is CouchError.Conflict) {
                    // Exhausted its conflict retries: the peer is writing this document faster
                    // than this device can merge. That resolves itself the moment the peer's
                    // burst ends, so it is retriable-with-backoff — reporting it terminal left
                    // the controller scheduling nothing while the feed loop re-triggered a push
                    // for the still-dirty document on every pull, an immediate-retry spin with
                    // no backoff anywhere.
                    hasRetriableFailure = true
                } else if (error !is CouchError.Unauthorized && error !is CouchError.Blocked) {
                    // The server refused this document on its merits and will keep refusing it.
                    // Whatever is queued behind it that *names* it must not push this pass: the
                    // push order exists so a manifest never precedes its documents, and a 413'd
                    // page with its notebook still in the queue used to break that invariant
                    // permanently. (A conflict is contention, not a refusal — the document is
                    // demonstrably on the server — and unauthorized/blocked stop the whole loop
                    // below anyway.)
                    suppressDependents(documentId, queue, index, suppressed)
                }
                // Offline or a server fault applies to every remaining document too; stopping
                // keeps one dead connection from turning into a burst of doomed requests.
                // ...and so do rejected credentials, which no amount of retrying will fix.
                // A blocked scheme is the same shape of failure: the platform refuses the address
                // itself, before a socket is opened, so every document behind this one would fail
                // identically. Carrying on would fill `failures` with the same sentence once per
                // queued document and bury the single message that tells the user to change the
                // URL — the very outcome CouchError.Blocked was introduced to avoid.
                if (error.isRetriable ||
                    error is CouchError.Unauthorized ||
                    error is CouchError.Blocked
                ) {
                    // Everything behind this one is still in the outbox and was never attempted.
                    // Reporting only the document that happened to fail made `stillDirty` mean
                    // "the last thing we tried" rather than "what is still waiting" — which
                    // understates the badge, misleads the log, and would leave a reconnect
                    // believing there was nothing to drain.
                    stillDirty += queue.drop(index + 1)
                    break
                }
            } catch (error: Exception) {
                failures[documentId] = error.toString()
                failureCauses[documentId] = error
                stillDirty += documentId
                // Not a `CouchError` at all — a store read that failed, most likely. Local faults
                // are usually transient (a row locked, a device briefly out of space), and the
                // document stays in the durable outbox either way.
                hasRetriableFailure = true
            }
        }
        persist()
        FlushReport(
            pushed = pushed,
            merged = merged,
            stillDirty = stillDirty,
            failures = failures,
            failureCauses = failureCauses,
            hasRetriableFailure = hasRetriableFailure,
            retryAfterMs = retryAfterMs,
            databaseIdentity = identity,
            heldDeletions = heldDeletions,
            // Whatever the last response said about the server's clock. Reported rather than acted
            // on: it is a warning, and a warning must never be able to fail a push.
            clockSkewSeconds = client.clockSkewSeconds,
        )
    }

    /**
     * Whether [documentId] is denylisted as oversized and unchanged — in which case the refusal is
     * reported exactly as the wire would have, minus the round trip that re-sends megabytes to be
     * told the same thing, and its dependents are held back the same way. A changed document
     * leaves the denylist and earns one real attempt.
     */
    private fun skipAsOversized(
        documentId: String,
        queue: List<String>,
        index: Int,
        suppressed: MutableSet<String>,
        failures: MutableMap<String, String>,
        failureCauses: MutableMap<String, Throwable>,
    ): Boolean {
        val entry = oversized[documentId] ?: return false
        val current = runCatching { store.load(documentId) }.getOrNull()
        // A changed document earns one real attempt — but only where the content was ever the
        // problem. An intermediary's cap does not care what the document says, so editing it must
        // not be mistaken for a reason to re-send it; [rearmRefusedRequests] is what lifts those.
        if (current == null ||
            (entry.refusedBy != CouchError.Refuser.INTERMEDIARY &&
                current.updatedAt != entry.updatedAt)
        ) {
            oversized.remove(documentId)
            return false
        }
        val refusal = CouchError.Server(
            413,
            documentId,
            requestBytes = entry.requestBytes,
            refusedBy = entry.refusedBy,
        )
        failures[documentId] = refusal.detail
        failureCauses[documentId] = refusal
        suppressDependents(documentId, queue, index, suppressed)
        return true
    }

    /**
     * Holds back, for this pass alone, everything queued after [index] that names [documentId].
     *
     * The push order — assets, then folders and pages, then notebooks — exists precisely so a
     * reader never sees a manifest pointing at documents that have not landed. That invariant is
     * only as strong as the failure handling: the loop used to continue past a non-retriable
     * per-document refusal, so a 413'd page's notebook pushed moments later and every peer then
     * held a manifest naming a page the server will never have. A failed page holds back its
     * owning notebook; a failed asset holds back the queued pages and notebooks that reference it
     * (and those pages' notebooks). Nothing is dropped — the held documents stay dirty and go out
     * the moment their dependency does, or is edited down to size.
     */
    private fun suppressDependents(
        documentId: String,
        queue: List<String>,
        index: Int,
        suppressed: MutableSet<String>,
    ) {
        when (CouchDocId.split(documentId)?.first) {
            CouchDocType.PAGE -> owningNotebookOf(documentId)?.let { suppressed += it }

            CouchDocType.ASSET -> for (later in queue.subList(index + 1, queue.size)) {
                val type = CouchDocId.split(later)?.first
                if (type != CouchDocType.PAGE && type != CouchDocType.NOTEBOOK) continue
                val body = runCatching { store.load(later) }.getOrNull() ?: continue
                if (documentId !in body.referencedAssetIds) continue
                suppressed += later
                if (type == CouchDocType.PAGE) owningNotebookOf(later)?.let { suppressed += it }
            }

            else -> Unit
        }
    }

    private fun owningNotebookOf(pageDocumentId: String): String? {
        val body = runCatching { store.load(pageDocumentId) }.getOrNull() as? CouchDocBody.Page
        return body?.page?.notebookId?.let { CouchDocId.notebook(it) }
    }

    private enum class PushOutcome { PUSHED, MERGED_THEN_PUSHED, NOTHING_TO_PUSH }

    private suspend fun push(documentId: String): PushOutcome {
        var didMerge = false
        repeat(maxPushAttempts) { attempt ->
            // A growing pause between conflict rounds, not before the first attempt. Immediate
            // retries mostly re-lose the same race against a peer mid-burst; a beat is usually
            // enough for its writes to land so the next merge is against a settled document.
            if (attempt > 0 && conflictBackoffMs > 0) sleep(conflictBackoffMs * attempt)
            val local = store.load(documentId)
            if (local == null) {
                // Nothing locally: the document was never created, or was cleaned up after being
                // queued. Dropping it from the outbox is right — there is nothing to send.
                clearDirty(documentId)
                return PushOutcome.NOTHING_TO_PUSH
            }

            try {
                val rev = putBody(documentId, local)
                revs[documentId] = rev
                clearDirty(documentId)
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
                    clearDirty(documentId)
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
                    clearDirty(documentId)
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
                    clearDirty(documentId)
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
     * document. They are derived here from the documents being sent — a page's pictures and
     * background, a notebook's default background — and skipped once the server is known to hold
     * them, immutability meaning a revision we have seen can never go stale.
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
            val type = CouchDocId.split(documentId)?.first
            if (type != CouchDocType.PAGE && type != CouchDocType.NOTEBOOK) continue
            val body = runCatching { store.load(documentId) }.getOrNull() ?: continue
            queue += body.referencedAssetIds.filter { it !in revs }
        }
        return queue.sortedWith(compareBy({ rank(it) }, { it }))
    }

    /**
     * Protocol §6.7: a device whose local database was wiped looks exactly like a user who deleted
     * everything. Ten-plus notebook tombstones that are also most of what this device knows is
     * treated as the former until a human says otherwise.
     */
    private fun isNotebookTombstone(documentId: String): Boolean =
        CouchDocId.split(documentId)?.first == CouchDocType.NOTEBOOK &&
            (runCatching { store.load(documentId) }.getOrNull()?.isDeleted ?: false)

    /**
     * What waits for the user's answer once the guard has tripped: notebook tombstones — the
     * ones the guard counts — **and** folder tombstones. A wiped database queues both kinds, and
     * a folder `_deleted` that sailed past the guard destroyed the folder tree on the server
     * while the notebooks politely waited for permission.
     */
    private fun isHeldTombstone(documentId: String): Boolean {
        val type = CouchDocId.split(documentId)?.first
        if (type != CouchDocType.NOTEBOOK && type != CouchDocType.FOLDER) return false
        return runCatching { store.load(documentId) }.getOrNull()?.isDeleted ?: false
    }

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
        // A longpoll is bounded the same way. It used to be sent with no limit at all, on the
        // reasoning that it is one wait for one notification — but the batch that follows a wait is
        // whatever changed *while* it waited, and after this device or a proxy has been
        // disconnected for a day that is the same unbounded response the paging above exists to
        // prevent, ink and inline base64 and all. On a device with this one's memory that is the
        // difference between slow and dead.
        //
        // Once a longpoll comes back full, the backlog is already on the server and there is
        // nothing left to wait for, so the loop drops to normal requests and drains at full speed
        // until a short batch says the server is caught up. Parking a fresh longpoll instead would
        // wait for a *new* change before collecting the ones already queued.
        val identity = verifyDatabaseIdentity()
        if (enforceDatabaseIdentity && !identity.isUsable) {
            // Nothing is applied and the checkpoint does not move. A device that cannot tell whose
            // history it is reading must not act on it.
            throw CouchError.DatabaseIdentity(identity)
        }

        var report = PullReport(databaseIdentity = identity)
        var longpollThisPass = longpoll
        while (true) {
            val since = mutex.withLock { lastSeq }
            val changes = client.changes(
                since = since,
                longpoll = longpollThisPass,
                timeoutMs = timeoutMs,
                limit = CATCH_UP_BATCH_SIZE,
            )
            val batch = mutex.withLock { applyChanges(changes, since) }
            report = report.merge(batch)
            if (batch.discardedStaleBatches > 0) {
                // A batch an overlapping pull already superseded says nothing about how far behind
                // the server is — its row count describes a window that has since moved. Ask again
                // from the current checkpoint instead of concluding anything from it.
                //
                // Except on a longpoll: the caller asked to wait for one notification, and the pull
                // that won has already delivered what this one was waiting for.
                if (longpoll) break else continue
            }
            // The server is caught up when it returns a short batch; a full one may have more
            // behind it. A full batch that did not move the checkpoint would ask the same question
            // forever — no CouchDB does that, which is why it is worth refusing to loop on it here
            // rather than finding out on a device with the battery draining.
            if (changes.rows.size < CATCH_UP_BATCH_SIZE) break
            longpollThisPass = false
            if (mutex.withLock { lastSeq } == since) break
        }
        // Also outside the lock: this is a download queue, and the blobs belong to the store rather
        // than to any state this engine guards. Fetching them under the lock would reintroduce
        // exactly the stall the split above exists to remove, just with images instead of waiting.
        val assets = fetchMissingAssets()
        return report.copy(
            fetchedAssets = assets.fetched,
            missingAssets = assets.missing,
            corruptAssets = assets.corrupt,
            assetFailures = assets.failures,
            clockSkewSeconds = client.clockSkewSeconds,
        )
    }

    /**
     * Apply one feed batch. Callers hold [mutex].
     *
     * [since] is the checkpoint this batch was fetched from. The lock is released across the feed
     * request, so a second pull — a foreground catch-up while the longpoll waits — can run to
     * completion in that gap, and its checkpoint is then newer than the one this batch describes.
     *
     * The whole batch is dropped in that case, not merely its checkpoint. Keeping the rows was
     * defensible while the argument was about content — merges are idempotent, so re-applying what
     * the winner already applied lands on the identical document — but [revs] is not part of that
     * argument. It caches "the revision the next push must build on", and writing an older revision
     * into it moves it *backwards*: the next push then quotes a stale `_rev` and takes a 409 it did
     * not need, and the echo check stops recognising this device's own writes, which is a push
     * ping-pong rather than a merge.
     *
     * Dropping the batch costs nothing. The next request starts from the winner's checkpoint, so
     * anything this batch carried that the winner did not is returned again immediately.
     */
    private suspend fun applyChanges(changes: CouchDbClient.Changes, since: String): PullReport {
        // Reports the winner's checkpoint, not this batch's: `merge` folds `lastSeq` forward, and
        // the default "0" would read as a checkpoint reset.
        if (lastSeq != since) {
            return PullReport(discardedStaleBatches = 1, lastSeq = lastSeq)
        }

        val applied = mutableListOf<String>()
        val pushBack = mutableListOf<String>()
        val skippedEchoes = mutableListOf<String>()
        val conflictCopies = mutableListOf<String>()

        for (row in changes.rows) {
            // Protocol bookkeeping, not a library item (§1.1). Recorded and stepped past: it is
            // neither merged nor conflict-copied, and a `sync-meta:` id this build does not know is
            // still recognisably ours rather than a document from a future schema.
            //
            // Ahead of the echo check, so it never reaches any of the report's lists. A caller
            // counting what a pull brought down is asking about the library, and bookkeeping
            // turning up as a "skipped echo" is noise in every report that mentions it.
            if (CouchMetaDocId.isReserved(row.id)) {
                revs[row.id] = row.rev
                continue
            }
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

            // A live row without a body cannot be applied and must not be checkpointed past. The
            // parser rejects such a response before it reaches here, so this is defence rather than
            // a path — but it throws instead of skipping, because skipping is exactly the silent
            // loss the strict parser exists to prevent: a pull that fails is retried, a change the
            // checkpoint stepped over is gone. It used to record the revision and move on.
            val json = row.json
            val incoming: CouchDocBody
            if (json != null) {
                val decoded = decode(row.id, json, row.deleted)
                if (decoded == null) {
                    // Valid transport, unmergeable content — a future schema, most often. Preserved
                    // beside the local copy and checkpointed: unlike a missing body, nothing is
                    // lost by moving past it.
                    store.applyConflictCopy(row.id, json)
                    conflictCopies += row.id
                    revs[row.id] = row.rev
                    continue
                }
                incoming = decoded
            } else {
                val type = CouchDocId.split(row.id)?.first
                if (!row.deleted || type == null) {
                    throw CouchError.MalformedResponse("_changes row ${row.id} carried no document")
                }
                // A tombstone CouchDB answered without a body, because `include_docs` found the
                // document already deleted. `deletedAt` is left empty for the reason `decode`
                // gives: stamping "now" would outrank every local edit and destroy work done after
                // the deletion.
                incoming = CouchDocBody.Deleted(CouchDeletedDoc(type = type, updatedBy = deviceId))
            }

            val local = store.load(row.id)
            val merged: CouchDocBody
            if (local != null) {
                val result = CouchMerge.mergeBodies(local, incoming)
                if (result == null) {
                    // Nothing to preserve when the body never arrived; the tombstone is the fact,
                    // and the revision still has to be recorded so the next push builds on it.
                    if (json != null) {
                        store.applyConflictCopy(row.id, json)
                        conflictCopies += row.id
                    }
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
                // The local copy carried content the server has not seen — push it back. Recorded
                // durably as well, because the content that made it a push-back is content only
                // this device holds: losing the intent here loses it for good.
                dirty.add(row.id)
                runCatching { store.enqueueOutbox(listOf(row.id)) }
                pushBack += row.id
            }
        }

        // Unconditional now, unlike the guarded assignment this replaces: the batch was checked
        // against [since] before any row was touched, and this function holds [mutex] throughout,
        // so no overlapping pull can have moved the checkpoint in between.
        lastSeq = changes.lastSeq
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
    /**
     * Every branch below used to be a bare `continue`, so five different failures — an unreadable
     * id, a transport error, a peer that has not uploaded the bytes, a digest mismatch, a store
     * that would not write — all looked identical from outside: the pull reported success and the
     * image simply was not there. The badge said idle while the page had a hole in it.
     *
     * They are still non-fatal, and for the same reason: the page and its ink are already applied,
     * and an image on its way is a picture that has not appeared yet, not lost work. What changes is
     * that the pull now says so.
     */
    private suspend fun fetchMissingAssets(): AssetResults {
        val wanted = runCatching { store.missingAssetIds() }.getOrNull().orEmpty()
        val fetched = mutableListOf<String>()
        val missing = mutableListOf<String>()
        val corrupt = mutableListOf<String>()
        val failures = LinkedHashMap<String, String>()

        for (assetId in wanted) {
            val sha = CouchAssetId.sha256HexOfAssetId(assetId)
            if (sha == null) {
                // Not a content-addressed id at all — nothing to fetch and nothing to verify
                // against, so this one will never resolve by retrying.
                failures[assetId] = "not a content-addressed asset id"
                continue
            }

            val blob = try {
                client.getAttachment(assetId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failures[assetId] = (e as? CouchError)?.detail ?: e.toString()
                continue
            }
            if (blob == null) {
                // A 404: the peer that referenced this image has not uploaded the bytes yet. The
                // commonest case by far, and the one that resolves itself.
                missing += assetId
                continue
            }

            // The id is a promise about the bytes. Checking it costs one hash and turns a truncated
            // or mis-served download into a retry rather than into a corrupt image that would then
            // be re-uploaded under a name that does not describe it.
            if (CouchAssetId.sha256Hex(blob.bytes) != sha) {
                // Logged apart from the rest and never stored: writing it would put bytes under an
                // id that does not describe them, and the next push would upload them.
                corrupt += assetId
                failures[assetId] = "downloaded bytes did not match the asset digest"
                continue
            }

            val now = Instant.now().toString()
            val asset = CouchAsset.of(
                blob.bytes, at = now, updatedBy = deviceId, contentType = blob.contentType
            )
            try {
                store.apply(assetId, CouchDocBody.Asset(asset), basedOn = null)
                fetched += assetId
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Out of space, a row locked: local, usually transient, and retried on the next
                // pull because the store still lists the asset as missing.
                failures[assetId] = e.toString()
            }
        }
        return AssetResults(fetched, missing, corrupt, failures)
    }

    private class AssetResults(
        val fetched: List<String>,
        val missing: List<String>,
        val corrupt: List<String>,
        val failures: Map<String, String>,
    )

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

    /**
     * Retires this engine. Called by the host before the stack it belongs to is replaced — a
     * settings change, or CouchDB being switched off. Requests already in flight may still finish;
     * what stops is persistence: an invalidated engine's state describes a configuration that no
     * longer exists, and the state key deliberately excludes credentials and the device id, so a
     * password change rebuilds onto the *same* key — a late write from the old engine would land
     * squarely on top of whatever the replacement has persisted since. bopa's
     * `CouchSyncEngine.invalidate()` is the twin of this method.
     */
    fun invalidate() {
        invalidated.set(true)
    }

    private fun persist() {
        // An invalidated engine's state describes a configuration that no longer exists; writing
        // it would overwrite whatever the replacement engine has persisted since.
        if (invalidated.get()) return
        onStateChange?.invoke(currentState)
    }

    // endregion

    companion object {
        private const val DEFAULT_MAX_PUSH_ATTEMPTS = 5

        /** See [conflictBackoffMs]: retry *n* of a conflicted push waits `n × this`. */
        private const val CONFLICT_BACKOFF_MS = 250L

        /** Protocol §6.7: below this many notebook tombstones, a flush is never refused. */
        private const val MASS_DELETION_FLOOR = 10

        /**
         * Rows per catch-up request. Small enough that a library of any size arrives in pieces this
         * device can hold and apply, large enough that a routine catch-up is still one round trip.
         */
        private const val CATCH_UP_BATCH_SIZE = 100
    }
}

// endregion
