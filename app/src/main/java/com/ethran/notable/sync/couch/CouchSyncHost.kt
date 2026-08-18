package com.ethran.notable.sync.couch

import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.BackgroundFileWatcher
import com.ethran.notable.data.db.KvDao
import com.ethran.notable.data.db.KvProxy
import com.ethran.notable.di.ApplicationScope
import com.ethran.notable.editor.canvas.CanvasEventBus
import com.ethran.notable.sync.COUCH_SYNC_STATE_KEY
import com.ethran.notable.sync.DEFAULT_DEVICE_ID
import com.ethran.notable.sync.SyncBackend
import com.ethran.notable.sync.SyncLogger
import com.ethran.notable.sync.SyncSettings
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the CouchDB stack — store, client, engine — and rebuilds it whenever the settings that went
 * into it change. bopa's `SyncBackendHost.swift` is the twin of this class.
 *
 * It exists so nothing above it has to branch on the backend: callers say "this page was edited" or
 * "push everything", and when WebDAV is selected every one of those is an inert no-op. The engine
 * captures its device id, credentials and checkpoint at construction, so a settings change is
 * expressed as a rebuild rather than as mutation — which is also what makes "switch backend" a
 * single, atomic decision rather than a half-applied one.
 */
@Singleton
class CouchSyncHost @Inject constructor(
    private val appRepository: AppRepository,
    private val kvProxy: KvProxy,
    private val kvDao: KvDao,
    private val backgroundFileWatcher: BackgroundFileWatcher,
    @param:ApplicationScope private val scope: CoroutineScope,
) : CouchSyncBackend {

    private val log = ShipBook.getLogger("CouchSyncHost")

    private class Stack(
        val settingsKey: String,
        /** Where this server's checkpoint is persisted — see [stateKey]. */
        val stateKey: String,
        val store: RoomCouchStore,
        val engine: CouchSyncEngine,
    )

    private val mutex = Mutex()
    private var stack: Stack? = null
    private val tombstonesPruned = java.util.concurrent.atomic.AtomicBoolean(false)

    private val _documentState = MutableStateFlow<CouchDocumentState?>(null)
    override val documentState: StateFlow<CouchDocumentState?> = _documentState.asStateFlow()

    /** Republish the per-document view whenever the engine's state moves, or the backend goes away. */
    private fun publish(state: CouchSyncState?) {
        _documentState.value = state?.let {
            CouchDocumentState(known = it.revs.keys.toSet(), queued = it.dirty)
        }
    }

    /**
     * Checkpoint writes, conflated onto one consumer.
     *
     * The engine's `onStateChange` is a plain callback but persisting is suspending, so the write
     * has to be handed off. Doing that with a bare `launch` per call would let two saves land out
     * of order and resurrect an older `lastSeq`; a conflated channel keeps only the newest state
     * and writes them in order.
     */
    private val stateWrites = Channel<StateWrite>(Channel.CONFLATED)

    /**
     * One checkpoint save, stamped with when it was requested. The stamp is what lets the
     * synchronous path (see [writeState]) jump the queue safely: a stale save still sitting in the
     * conflated channel must not land *after* — and therefore on top of — a newer state that was
     * written directly.
     */
    private class StateWrite(val seq: Long, val key: String, val state: CouchSyncState)

    private val stateWriteSeq = java.util.concurrent.atomic.AtomicLong(0)

    /** Per state key, the [StateWrite.seq] of the newest state actually on disk. */
    private val newestWrittenSeq = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Serializes the kv writes themselves, so the sync path and the channel cannot interleave. */
    private val kvWriteMutex = Mutex()

    init {
        scope.launch {
            for (write in stateWrites) {
                runCatching { writeState(write) }
                    .onFailure { log.w("Could not persist couch sync state: ${it.message}") }
            }
        }
    }

    /**
     * Lands one checkpoint on disk — unless a newer one for the same key already has. The engine's
     * ordinary saves ride the conflated channel and can be a moment behind; `discardHeldDeletions`
     * writes through here directly because its rewind must be durable before the tombstones are
     * dropped, and the guard is what keeps a stale channel write from undoing that.
     */
    private suspend fun writeState(write: StateWrite) {
        kvWriteMutex.withLock {
            if (write.seq <= (newestWrittenSeq[write.key] ?: Long.MIN_VALUE)) return
            kvProxy.setKv(write.key, write.state, CouchSyncState.serializer())
            newestWrittenSeq[write.key] = write.seq
        }
    }

    /** Shared pool. Per-call read timeouts (the longpoll) are set by [OkHttpCouchTransport]. */
    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    // region CouchSyncBackend

    override suspend fun isEnabled(): Boolean = stack() != null

    override suspend fun flush(): CouchSyncEngine.FlushReport =
        stack()?.engine?.flush() ?: CouchSyncEngine.FlushReport()

    override suspend fun pull(longpoll: Boolean): CouchSyncEngine.PullReport =
        stack()?.engine?.pull(longpoll) ?: CouchSyncEngine.PullReport()

    /**
     * Queues the page — and only the page. An ink save no longer moves anything on the notebook
     * (see `PageDataManager.bumpEditTimestamps`): its envelope is what the merge decides renames,
     * moves and page order by, and marking it dirty here pushed an unchanged document — a fresh
     * server revision per stroke, carrying nothing.
     *
     * A page with no notebook — a quick page — queues nothing, matching
     * [RoomCouchStore.allDocumentIds]: the protocol has no standalone page lifecycle (§6.4) and
     * pushing one lands a document no manifest will ever name. A page whose row is already gone
     * also queues nothing: its removal travels inside the notebook's manifest.
     */
    override suspend fun markPageDirty(pageId: String) {
        val engine = stack()?.engine ?: return
        appRepository.pageRepository.getById(pageId)?.notebookId ?: return
        engine.markDirty(listOf(CouchDocId.page(pageId)))
    }

    override suspend fun markDocumentDirty(documentId: String) {
        val engine = stack()?.engine ?: return
        SyncLogger.d(TAG, "Queued $documentId")
        engine.markDirty(listOf(documentId))
    }

    override suspend fun markEverythingDirty() {
        val current = stack() ?: return
        val ids = current.store.allDocumentIds()
        SyncLogger.i(TAG, "Queueing all ${ids.size} document(s) on this device")
        current.engine.markDirty(ids)
    }

    override suspend fun markUnsentDirty(): Int {
        val current = stack() ?: return 0
        val unsent = current.engine.neverSent(current.store.allDocumentIds())
        if (unsent.isNotEmpty()) {
            // Worth an INFO rather than a debug line: reaching here at all means something was
            // created locally that no edit ever queued, which until now was simply lost.
            SyncLogger.i(
                TAG,
                "Found ${unsent.size} document(s) the server has never seen; queueing them"
            )
            SyncLogger.d(TAG, "Never sent: ${unsent.joinToString(", ")}")
            current.engine.markDirty(unsent)
        }
        return unsent.size
    }

    override suspend fun recordDeletion(documentId: String) {
        val current = stack() ?: return
        current.store.recordDeletion(documentId)
        current.engine.markDirty(listOf(documentId))
    }

    override suspend fun approveHeldDeletions(ids: List<String>) {
        val current = stack() ?: return
        SyncLogger.i(TAG, "Confirmed ${ids.size} notebook deletion(s); they will be sent next flush")
        SyncLogger.d(TAG, "Confirmed: ${ids.joinToString(", ")}")
        current.engine.approveHeldDeletions(ids)
    }

    /**
     * Goes through the engine rather than straight at the store, so the in-memory outbox and the
     * two tables stop naming these documents together. Reaching around it would leave the engine
     * offering ids whose tombstones no longer exist, which `push` then quietly drops — the same
     * result by accident instead of on purpose.
     */
    override suspend fun discardHeldDeletions(ids: List<String>) {
        val current = stack() ?: return
        SyncLogger.i(
            TAG,
            "Discarded ${ids.size} notebook deletion(s); the server keeps them, so they will " +
                "come back to this device on the next pull"
        )
        SyncLogger.d(TAG, "Discarded: ${ids.joinToString(", ")}")
        current.engine.discardHeldDeletions(ids)
    }

    /**
     * The durable table as well as the engine's own set. The repositories queue a document by
     * writing its outbox row inside the transaction that changed the data, without going through
     * the engine at all, so the engine only learns about those at its next flush — and this number
     * is read precisely to decide whether that flush is worth making.
     */
    override suspend fun pendingCount(): Int {
        val current = stack() ?: return 0
        val queued = runCatching { current.store.pendingOutboxIds() }.getOrNull().orEmpty()
        return (queued + current.engine.currentState.dirty).toSet().size
    }

    // endregion

    /**
     * Divides every page of [notebookId] that outgrew its sheet — see
     * [RoomCouchStore.splitOversizedPages], which this makes runnable whether or not any server
     * is configured: what a page *is* does not depend on having somewhere to sync it, and a
     * notebook that has never seen a server still needs its tall pages divided on open.
     */
    suspend fun splitOversizedPages(notebookId: String): Set<String> {
        pruneExpiredTombstonesOnce()
        val store = stack()?.store ?: RoomCouchStore(
            appRepository = appRepository,
            kvDao = kvDao,
            deviceId = kvProxy.getSyncSettings().deviceId.ifBlank { DEFAULT_DEVICE_ID },
            // The same wiring the synced store gets: a division rewrites pages that the canvas
            // or the page cache may be holding, exactly like a pull applying a peer's edit.
            onPagesApplied = { pageIds ->
                scope.launch { CanvasEventBus.pagesChangedInDb.emit(pageIds) }
            },
        )
        return store.splitOversizedPages(notebookId)
    }

    /**
     * Prunes stroke and image tombstones past the protocol's 30-day horizon (§6.6, "Pruning") —
     * the rows exist so an erasure can outlive a merge race, not so every page document grows
     * with each sweep of the eraser for ever.
     *
     * Piggybacked on the first notebook open of the process rather than run per open: two
     * indexed DELETEs are cheap, but hygiene has no business running hundreds of times a
     * session. Deliberately not an edit — nothing is queued and no clock is bumped; the next
     * ordinary push of each page carries the shorter list. Page tombstones and outline entries
     * are never pruned (they hold structural identity the merge needs indefinitely).
     */
    private suspend fun pruneExpiredTombstonesOnce() {
        if (!tombstonesPruned.compareAndSet(false, true)) return
        appRepository.pruneExpiredTombstones(
            cutoffMillis = System.currentTimeMillis() - TOMBSTONE_PRUNE_HORIZON_MS
        )
    }

    // region Assembly

    /**
     * The current stack, rebuilt when the settings behind it changed, or null when CouchDB is not
     * the selected backend (or is not configured yet).
     */
    private suspend fun stack(): Stack? = mutex.withLock {
        val settings = kvProxy.getSyncSettings()
        if (!settings.couchActive) {
            // The single most consequential gate in the engine, and it used to be completely
            // silent: with no stack every edit, deletion and sync request below is a no-op, so a
            // misconfigured CouchDB looks exactly like one that is working and has nothing to do.
            if (stack != null) SyncLogger.i(TAG, "CouchDB no longer active; sync stack released")
            else SyncLogger.d(TAG, couchInactiveReason(settings))
            // Retired, not merely dropped: an in-flight flush or pull on the old engine keeps
            // running after this returns, and its persist would land on the shared state key.
            stack?.engine?.invalidate()
            stack = null
            publish(null)
            return@withLock null
        }

        val key = settingsKey(settings)
        stack?.takeIf { it.settingsKey == key }?.let { return@withLock it }
        // The settings changed under the old engine. Invalidate it *before* the replacement is
        // built: the state key excludes credentials and the device id, so a password or
        // device-name change rebuilds onto the very same key — and an old engine allowed to
        // finish its flush would persist its stale checkpoint over the successor's. bopa's
        // `SyncBackendHost` does the same, for the same reason.
        stack?.engine?.invalidate()
        val stateKey = stateKey(settings)

        val deviceId = settings.deviceId.ifBlank { DEFAULT_DEVICE_ID }
        val transport = runCatching {
            OkHttpCouchTransport(
                baseUrl = settings.couchUrl,
                username = settings.couchUsername.ifBlank { null },
                password = settings.couchPassword.ifBlank { null },
                client = http,
            )
        }.getOrElse {
            // An unparseable URL is a typo in settings, not a reason to take the app down. It goes
            // to the in-app log as well as ShipBook: the Sync now button stays enabled on a bad
            // URL, so without this the user taps it and absolutely nothing happens or is said.
            //
            // Redacted, because the whole point of the sync log is that it is kept on disk, sent to
            // ShipBook and copied into bug reports — and `https://user:password@host` is an ordinary
            // way to write a CouchDB URL. Showing the typo must not publish the password with it.
            val safeUrl = redactUserInfo(settings.couchUrl)
            SyncLogger.w(TAG, "CouchDB URL '$safeUrl' is not usable (${it.message}); nothing can sync")
            log.w("CouchDB URL '$safeUrl' is not usable: ${it.message}")
            stack = null
            publish(null)
            return@withLock null
        }

        val store = RoomCouchStore(
            appRepository = appRepository,
            kvDao = kvDao,
            deviceId = deviceId,
            // The engine applies changes off the UI thread, straight into Room. Whatever holds
            // these pages in memory — the open canvas, the page cache behind it — is still showing
            // what they held before the pull until it is told which ones moved.
            // Launched rather than offered: the store applies documents from a blocking loop that
            // cannot suspend, and `tryEmit` would abandon the notification whenever a bulk pull
            // outran the collector — leaving exactly the stale page this reports.
            onPagesApplied = { pageIds ->
                scope.launch { CanvasEventBus.pagesChangedInDb.emit(pageIds) }
            },
            // A page's rows say which background file to draw; only the file itself says what is
            // in it. A PDF that lands after the pages naming it therefore needs its own word —
            // see [BackgroundFileWatcher.noteChanged].
            onAssetFileWritten = backgroundFileWatcher::noteChanged,
        )
        val initial = loadState(settings, stateKey)
        val engine = CouchSyncEngine(
            client = CouchDbClient(transport, database = settings.couchDatabase),
            store = store,
            deviceId = deviceId,
            state = initial,
            onStateChange = {
                stateWrites.trySend(StateWrite(stateWriteSeq.incrementAndGet(), stateKey, it))
                // The badge follows the engine, not the persisted copy: it should change the moment
                // a document is queued or accepted, not once the checkpoint write lands.
                publish(it)
            },
            // The one save that may not be fire-and-forget: discarding held deletions must have its
            // checkpoint rewind on disk before the tombstones go. Failures propagate — the engine
            // aborts the discard rather than proceeding on a write that never landed.
            onStateChangeSync = { state ->
                writeState(StateWrite(stateWriteSeq.incrementAndGet(), stateKey, state))
                publish(state)
            },
        )
        publish(initial)
        Stack(key, stateKey, store, engine).also { stack = it }
    }

    /**
     * Everything the engine captures at construction. A change to any of it has to produce a new
     * engine, so it is spelled out here rather than inferred.
     */
    /** Which half of `couchActive` is false, so the log names the switch rather than the symptom. */
    private fun couchInactiveReason(settings: SyncSettings): String =
        if (settings.backend != SyncBackend.COUCHDB) "CouchDB is not the selected backend; ignoring"
        else "CouchDB is selected but needs a URL and database name; ignoring"

    private fun settingsKey(settings: SyncSettings): String = listOf(
        settings.couchUrl,
        settings.couchDatabase,
        settings.couchUsername,
        settings.couchPassword,
        settings.deviceId,
        // NUL-separated, so a value that contains the separator cannot forge a key matching a
        // different configuration.
    ).joinToString("\u0000")

    /**
     * Where this server's sync state lives. Namespaced by the server and database, because that is
     * what the state describes: `lastSeq` is a position in *one* server's change feed, and the
     * revisions are that server's revisions.
     *
     * All of it was previously kept under a single key, so pointing the app at a different server
     * — or a different database on the same one — handed the new server the old one's checkpoint.
     * A foreign `since` skips changes rather than failing loudly, and stale revisions suppress real
     * updates as though they were this device's own echoes.
     *
     * Credentials and the device id are deliberately *not* part of the name: changing a password
     * does not change what is on the server, and discarding the checkpoint would mean replaying the
     * whole feed for nothing. The URL is normalized first ([endpointIdentity]) for the same reason
     * seen from the other side: a trailing slash or a re-typed hostname's case does not change
     * what is on the server either — and `user:pass@` in the URL is a credential, not a place, so
     * it must be neither part of the key's identity nor able to discard the checkpoint by
     * appearing.
     */
    private fun stateKey(settings: SyncSettings): String =
        couchStateKey(settings.couchUrl, settings.couchDatabase)

    /**
     * A missing or unreadable checkpoint is not an error: every document re-pushes and the feed
     * replays from the start, which is slow but correct because every merge is idempotent.
     *
     * Before settling for that, one look under the key the *raw* URL used to hash to: state
     * written before normalization is moved to the normalized key, so nobody loses their
     * checkpoint to this update — and the legacy row is pruned, because nothing will read it
     * again. Other `COUCH_SYNC_STATE:` rows are left alone: a hash that matches neither
     * derivation is indistinguishable from another server's live checkpoint, which switching
     * back must still find.
     */
    private suspend fun loadState(settings: SyncSettings, key: String): CouchSyncState {
        runCatching { kvProxy.get(key, CouchSyncState.serializer()) }.getOrNull()?.let { return it }

        migrateLegacyCouchState(
            key = key,
            legacyKey = legacyCouchStateKey(settings.couchUrl, settings.couchDatabase),
            read = { runCatching { kvProxy.get(it, CouchSyncState.serializer()) }.getOrNull() },
            write = { k, state ->
                runCatching { kvProxy.setKv(k, state, CouchSyncState.serializer()) }
                    .onFailure { log.w("Could not migrate the sync checkpoint: ${it.message}") }
            },
            remove = { runCatching { kvDao.delete(it) } },
        )?.let {
            SyncLogger.i(TAG, "Moved this server's sync checkpoint to its normalized key")
            return it
        }

        // Correct but expensive, and it looks identical to a hung sync from outside: every
        // document re-pushes and the feed replays from the start.
        SyncLogger.w(TAG, "No readable sync checkpoint; replaying the whole feed")
        return CouchSyncState()
    }

    // endregion

    private companion object {
        const val TAG = "CouchSync"

        /** Thirty days, matching bopa's `CouchTombstones.prune` — one horizon per protocol. */
        const val TOMBSTONE_PRUNE_HORIZON_MS = 30L * 24 * 60 * 60 * 1000
    }
}

/**
 * Replace the `user:password@` part of a URL with `***@`, leaving the rest legible.
 *
 * Deliberately a regex over the authority rather than a URI parse: this is called precisely when a
 * URL failed to parse, so anything that needs it to be well-formed would fall back to printing the
 * raw string — the one outcome that must not happen. A string with no userinfo is returned
 * unchanged, so a URL without embedded credentials stays fully readable in the log.
 */
internal fun redactUserInfo(url: String): String =
    USER_INFO.replace(url) { "${it.groupValues[1]}***@" }

/** `scheme://` then everything up to the first `@` that is still inside the authority. */
private val USER_INFO = Regex("""^([a-zA-Z][a-zA-Z0-9+.\-]*://)[^/?#]*@""")

/**
 * Where the checkpoint for ([url], [database]) lives: the normalized endpoint identity, hashed to
 * keep a URL's punctuation out of the key. bopa's `CouchSyncStack.stateFileName` is the twin.
 */
internal fun couchStateKey(url: String, database: String): String {
    // NUL-separated, so a value that contains the separator cannot forge a key matching a
    // different configuration.
    val identity = listOf(endpointIdentity(url), database).joinToString("\u0000")
    return "$COUCH_SYNC_STATE_KEY:" + CouchAssetId.sha256Hex(identity.toByteArray())
}

/**
 * The key the same pair hashed to before the URL was normalized — read once at load so an update
 * migrates the checkpoint instead of silently replaying the whole feed.
 */
internal fun legacyCouchStateKey(url: String, database: String): String {
    val identity = listOf(url, database).joinToString("\u0000")
    return "$COUCH_SYNC_STATE_KEY:" + CouchAssetId.sha256Hex(identity.toByteArray())
}

/**
 * The part of a server address that says *which server*, with the differences that are not
 * differences folded away: a trailing slash and the case of the scheme and host are how the same
 * endpoint gets typed twice, and re-typing it must not throw the checkpoint away. Userinfo is
 * dropped for the same reason the password is not in the key at all — it is a credential, not a
 * place. bopa's `CouchSettings.endpointIdentity` is the twin of this function; an address that
 * does not parse is used as typed (trimmed), which is exactly as good as the old behaviour.
 */
internal fun endpointIdentity(serverUrl: String): String {
    val trimmed = serverUrl.trim()
    val uri = runCatching { java.net.URI(trimmed) }.getOrNull() ?: return trimmed
    val scheme = uri.scheme?.lowercase() ?: return trimmed
    val host = uri.host?.lowercase() ?: return trimmed
    val port = if (uri.port >= 0) ":${uri.port}" else ""
    var path = uri.rawPath.orEmpty()
    while (path.endsWith("/")) path = path.dropLast(1)
    val query = uri.rawQuery?.let { "?$it" }.orEmpty()
    return "$scheme://$host$port$path$query"
}

/**
 * The migration half of [couchStateKey]'s introduction, kept pure so a JVM test can drive it with
 * a map: when nothing lives under the normalized [key] but [legacyKey] (the raw-URL hash) holds
 * state, that state is copied to [key], the legacy row is removed, and the state is returned.
 * Returns null when there was nothing to migrate — including when the two keys coincide, which is
 * every URL normalization does not change.
 */
internal suspend fun migrateLegacyCouchState(
    key: String,
    legacyKey: String,
    read: suspend (String) -> CouchSyncState?,
    write: suspend (String, CouchSyncState) -> Unit,
    remove: suspend (String) -> Unit,
): CouchSyncState? {
    if (legacyKey == key) return null
    val legacy = read(legacyKey) ?: return null
    write(key, legacy)
    remove(legacyKey)
    return legacy
}
