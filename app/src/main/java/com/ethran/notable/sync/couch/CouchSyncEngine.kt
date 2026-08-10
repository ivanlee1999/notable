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
    val deletedAt: String,
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
    data class Deleted(val tombstone: CouchDeletedDoc) : CouchDocBody()

    val isDeleted: Boolean get() = this is Deleted

    /** `updatedAt` regardless of shape — the input to delete-vs-edit. */
    val updatedAt: String
        get() = when (this) {
            is Page -> page.updatedAt
            is Notebook -> notebook.updatedAt
            is Folder -> folder.updatedAt
            is Deleted -> tombstone.updatedAt
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
        deletedAt = CouchMerge.earlier(x.deletedAt, y.deletedAt),
        updatedAt = CouchMerge.later(x.updatedAt, y.updatedAt),
        updatedBy = if (xWins) x.updatedBy else y.updatedBy,
    )
}

private fun resolveTombstoneAgainstLive(
    tombstone: CouchDeletedDoc,
    live: CouchDocBody,
): CouchDocBody = when (CouchMerge.resolveDeletion(live.updatedAt, tombstone.deletedAt)) {
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

    /** Replaces local content with the merged result. */
    fun apply(documentId: String, body: CouchDocBody)

    /**
     * A document that could not be understood (undecodable, or a newer `schema`). The
     * implementation keeps the local copy untouched and materializes the remote one alongside it
     * under a new identity — protocol §6.5. Never overwrite on this path.
     */
    fun applyConflictCopy(documentId: String, json: JsonObject)
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
    )

    data class PullReport(
        val applied: List<String> = emptyList(),
        /** Documents where the local copy held content the server lacked; queued to push back. */
        val pushBack: List<String> = emptyList(),
        val skippedEchoes: List<String> = emptyList(),
        val conflictCopies: List<String> = emptyList(),
        val lastSeq: String = "0",
    )

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

    // region Push

    suspend fun flush(): FlushReport = mutex.withLock {
        val pushed = mutableListOf<String>()
        val merged = mutableListOf<String>()
        val stillDirty = mutableListOf<String>()
        val failures = LinkedHashMap<String, String>()
        val queue = orderedDirty()

        if (exceedsDeletionGuard(queue)) {
            return@withLock FlushReport(stillDirty = queue, blockedByDeletionGuard = true)
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
                if (error.isRetriable) break
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
                if (merged != local) store.apply(documentId, merged)
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
     * that have not landed yet.
     */
    private fun orderedDirty(): List<String> {
        fun rank(documentId: String): Int = when (CouchDocId.split(documentId)?.first) {
            CouchDocType.ASSET -> 0
            CouchDocType.FOLDER -> 1
            CouchDocType.PAGE -> 2
            else -> 3
        }
        return dirty.sortedWith(compareBy({ rank(it) }, { it }))
    }

    /**
     * Protocol §6.6: a device whose local database was wiped looks exactly like a user who deleted
     * everything. Ten-plus notebook tombstones that are also most of what this device knows is
     * treated as the former until a human says otherwise.
     */
    private fun exceedsDeletionGuard(queue: List<String>): Boolean {
        val tombstones = queue.filter {
            CouchDocId.split(it)?.first == CouchDocType.NOTEBOOK &&
                (runCatching { store.load(it) }.getOrNull()?.isDeleted ?: false)
        }
        if (tombstones.size < MASS_DELETION_FLOOR) return false
        val knownNotebooks = revs.keys.filter {
            CouchDocId.split(it)?.first == CouchDocType.NOTEBOOK
        }
        return tombstones.size * 2 > knownNotebooks.size
    }

    // endregion

    // region Pull

    /**
     * Applies everything the server has seen since the last checkpoint.
     *
     * [longpoll] holds the request open until a change arrives — the near-real-time path. A
     * non-longpoll call returns immediately and is used to catch up on foreground/reconnect.
     */
    suspend fun pull(
        longpoll: Boolean = false,
        timeoutMs: Long = CouchDbClient.DEFAULT_LONGPOLL_MS,
    ): PullReport = mutex.withLock {
        val applied = mutableListOf<String>()
        val pushBack = mutableListOf<String>()
        val skippedEchoes = mutableListOf<String>()
        val conflictCopies = mutableListOf<String>()

        val changes = client.changes(since = lastSeq, longpoll = longpoll, timeoutMs = timeoutMs)

        for (row in changes.rows) {
            // Our own write coming back. Applying it would be harmless (merges are idempotent) but
            // it would also mark the document dirty and start a push ping-pong.
            if (revs[row.id] == row.rev) {
                skippedEchoes += row.id
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

            store.apply(row.id, merged)
            applied += row.id
            // Record the server's revision either way: it is the base the next push must use.
            revs[row.id] = row.rev
            if (merged != incoming) {
                // The local copy carried content the server has not seen — push it back.
                dirty.add(row.id)
                pushBack += row.id
            }
        }

        lastSeq = changes.lastSeq
        persist()
        PullReport(
            applied = applied,
            pushBack = pushBack,
            skippedEchoes = skippedEchoes,
            conflictCopies = conflictCopies,
            lastSeq = changes.lastSeq,
        )
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
            return CouchDocBody.Deleted(
                decoded ?: CouchDeletedDoc(
                    type = type,
                    deletedAt = Instant.now().toString(),
                    updatedBy = deviceId,
                )
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
    }
}

// endregion
