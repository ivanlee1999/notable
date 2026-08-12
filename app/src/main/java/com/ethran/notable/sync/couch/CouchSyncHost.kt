package com.ethran.notable.sync.couch

import com.ethran.notable.data.AppRepository
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
    private val stateWrites = Channel<Pair<String, CouchSyncState>>(Channel.CONFLATED)

    init {
        scope.launch {
            for ((key, state) in stateWrites) {
                runCatching { kvProxy.setKv(key, state, CouchSyncState.serializer()) }
                    .onFailure { log.w("Could not persist couch sync state: ${it.message}") }
            }
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
     * Queues the page and the notebook that owns it.
     *
     * The notebook goes too because its `pageIds` manifest and `updatedAt` both moved: a page
     * pushed without it lands on the peer as a document no notebook points at.
     */
    override suspend fun markPageDirty(pageId: String) {
        val engine = stack()?.engine ?: return
        val ids = mutableListOf(CouchDocId.page(pageId))
        appRepository.pageRepository.getById(pageId)?.notebookId
            ?.let { ids += CouchDocId.notebook(it) }
        engine.markDirty(ids)
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
            stack = null
            publish(null)
            return@withLock null
        }

        val key = settingsKey(settings)
        stack?.takeIf { it.settingsKey == key }?.let { return@withLock it }
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
        )
        val initial = loadState(stateKey)
        val engine = CouchSyncEngine(
            client = CouchDbClient(transport, database = settings.couchDatabase),
            store = store,
            deviceId = deviceId,
            state = initial,
            onStateChange = {
                stateWrites.trySend(stateKey to it)
                // The badge follows the engine, not the persisted copy: it should change the moment
                // a document is queued or accepted, not once the checkpoint write lands.
                publish(it)
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
     * whole feed for nothing.
     *
     * Hashed to keep a URL's punctuation out of the key. Anyone upgrading replays from the start
     * once, which is slow and correct rather than fast and wrong.
     */
    private fun stateKey(settings: SyncSettings): String {
        val identity = listOf(settings.couchUrl, settings.couchDatabase).joinToString("\u0000")
        return "$COUCH_SYNC_STATE_KEY:" + CouchAssetId.sha256Hex(identity.toByteArray())
    }

    /**
     * A missing or unreadable checkpoint is not an error: every document re-pushes and the feed
     * replays from the start, which is slow but correct because every merge is idempotent.
     */
    private suspend fun loadState(key: String): CouchSyncState =
        runCatching { kvProxy.get(key, CouchSyncState.serializer()) }
            .getOrNull()
            ?: CouchSyncState().also {
                // Correct but expensive, and it looks identical to a hung sync from outside: every
                // document re-pushes and the feed replays from the start.
                SyncLogger.w(TAG, "No readable sync checkpoint; replaying the whole feed")
            }

    // endregion

    private companion object {
        const val TAG = "CouchSync"
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
