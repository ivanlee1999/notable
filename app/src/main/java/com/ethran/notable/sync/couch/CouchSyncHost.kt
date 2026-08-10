package com.ethran.notable.sync.couch

import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.db.KvDao
import com.ethran.notable.data.db.KvProxy
import com.ethran.notable.di.ApplicationScope
import com.ethran.notable.editor.canvas.CanvasEventBus
import com.ethran.notable.sync.COUCH_SYNC_STATE_KEY
import com.ethran.notable.sync.DEFAULT_DEVICE_ID
import com.ethran.notable.sync.SyncSettings
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
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
        val store: RoomCouchStore,
        val engine: CouchSyncEngine,
    )

    private val mutex = Mutex()
    private var stack: Stack? = null

    /**
     * Checkpoint writes, conflated onto one consumer.
     *
     * The engine's `onStateChange` is a plain callback but persisting is suspending, so the write
     * has to be handed off. Doing that with a bare `launch` per call would let two saves land out
     * of order and resurrect an older `lastSeq`; a conflated channel keeps only the newest state
     * and writes them in order.
     */
    private val stateWrites = Channel<CouchSyncState>(Channel.CONFLATED)

    init {
        scope.launch {
            for (state in stateWrites) {
                runCatching { kvProxy.setKv(COUCH_SYNC_STATE_KEY, state, CouchSyncState.serializer()) }
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

    override suspend fun markEverythingDirty() {
        val current = stack() ?: return
        current.engine.markDirty(current.store.allDocumentIds())
    }

    override suspend fun recordDeletion(documentId: String) {
        val current = stack() ?: return
        current.store.recordDeletion(documentId)
        current.engine.markDirty(listOf(documentId))
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
            stack = null
            return@withLock null
        }

        val key = settingsKey(settings)
        stack?.takeIf { it.settingsKey == key }?.let { return@withLock it }

        val deviceId = settings.deviceId.ifBlank { DEFAULT_DEVICE_ID }
        val transport = runCatching {
            OkHttpCouchTransport(
                baseUrl = settings.couchUrl,
                username = settings.couchUsername.ifBlank { null },
                password = settings.couchPassword.ifBlank { null },
                client = http,
            )
        }.getOrElse {
            // An unparseable URL is a typo in settings, not a reason to take the app down.
            log.w("CouchDB URL '${settings.couchUrl}' is not usable: ${it.message}")
            stack = null
            return@withLock null
        }

        val store = RoomCouchStore(
            appRepository = appRepository,
            kvDao = kvDao,
            deviceId = deviceId,
            // The engine applies changes off the UI thread; the canvas has to be told to reload.
            onApplied = { scope.launch { CanvasEventBus.reloadFromDb.emit(Unit) } },
        )
        val engine = CouchSyncEngine(
            client = CouchDbClient(transport, database = settings.couchDatabase),
            store = store,
            deviceId = deviceId,
            state = loadState(),
            onStateChange = { stateWrites.trySend(it) },
        )
        Stack(key, store, engine).also { stack = it }
    }

    /**
     * Everything the engine captures at construction. A change to any of it has to produce a new
     * engine, so it is spelled out here rather than inferred.
     */
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
     * A missing or unreadable checkpoint is not an error: every document re-pushes and the feed
     * replays from the start, which is slow but correct because every merge is idempotent.
     */
    private suspend fun loadState(): CouchSyncState =
        runCatching { kvProxy.get(COUCH_SYNC_STATE_KEY, CouchSyncState.serializer()) }
            .getOrNull() ?: CouchSyncState()

    // endregion
}
