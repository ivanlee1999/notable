package com.ethran.notable.sync.couch

import com.ethran.notable.data.JournalOrganizationStore
import com.ethran.notable.data.encodeOrganization
import com.ethran.notable.data.db.Kv
import com.ethran.notable.data.db.KvDao
import com.ethran.notable.data.db.KvProxy
import com.ethran.notable.di.ApplicationScope
import com.ethran.notable.sync.SyncScheduler
import com.ethran.notable.sync.SyncSettings
import com.ethran.notable.sync.SyncLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class JournalOrganizationSync @Inject constructor(
    private val store: JournalOrganizationStore,
    private val kv: KvDao,
    private val settings: KvProxy,
    private val scheduler: SyncScheduler,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val observing = AtomicBoolean()
    private val http = OkHttpClient.Builder().build()

    @OptIn(FlowPreview::class)
    fun start() {
        if (!observing.compareAndSet(false, true)) return
        scope.launch {
            store.changes.distinctUntilChanged().debounce(750)
                .retryWhen { cause, _ ->
                    if (cause is CancellationException) throw cause
                    delay(5_000)
                    true
                }.collect {
                    try {
                        val config = settings.getSyncSettings()
                        if (config.couchActive && config.autoSync && !config.downloadOnly && pendingCount() > 0) {
                            scheduler.triggerImmediateSync()
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        SyncLogger.w("JournalSync", "Journal sync is waiting for a retry")
                    }
                }
        }
    }

    suspend fun pendingCount(): Int = pendingIds().size

    suspend fun pendingIds(): List<String> {
        val config = settings.getSyncSettings()
        if (!config.couchActive || config.downloadOnly) return emptyList()
        val known = acknowledgements(config)
        return store.all().filter { known[it.notebookId] != encodeOrganization(it) }.map { "journal:${it.notebookId}" }
    }

    suspend fun sync(upload: Boolean) = mutex.withLock {
        val config = settings.getSyncSettings()
        if (!config.couchActive) return@withLock
        try {
            suspend fun checkActive() {
                val current = settings.getSyncSettings()
                if (!current.couchActive || current.couchUrl != config.couchUrl ||
                    current.couchDatabase != config.couchDatabase || current.couchUsername != config.couchUsername ||
                    current.couchPassword != config.couchPassword || current.downloadOnly != config.downloadOnly ||
                    current.uploadOnly != config.uploadOnly) throw CancellationException("Organization sync configuration changed")
            }
            val client = CouchDbClient(OkHttpCouchTransport(
                baseUrl = config.couchUrl,
                username = config.couchUsername.ifBlank { null },
                password = config.couchPassword.ifBlank { null },
                client = http,
            ), database = config.couchDatabase + "_organization")
            val observed = OrganizationSyncEngine(client, store::all, { incoming ->
                checkActive()
                store.merge(incoming)
            }, ::checkActive).sync(
                upload = upload && !config.downloadOnly,
                download = !config.uploadOnly,
            )
            checkActive()
            if (!config.uploadOnly || observed.isNotEmpty()) {
                val known = acknowledgements(config)
                // Acknowledge only the versions actually seen by the server, never a newer local edit.
                kv.set(Kv(stateKey(config), organizationAcknowledgements(known, observed, !config.uploadOnly).toString()))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw IOException("Journal dates and topics could not sync. Check ${config.couchDatabase}_organization and its permissions.", error)
        }
    }

    private suspend fun acknowledgements(config: SyncSettings): JsonObject =
        kv.get(stateKey(config))?.let {
            // Acknowledgements are a cache: a bad cache safely causes a resend, never loss of metadata.
            runCatching { Json.parseToJsonElement(it.value) as? JsonObject }.getOrNull()
        } ?: JsonObject(emptyMap())

    private fun stateKey(config: SyncSettings): String {
        val identity = listOf(config.couchUrl.trimEnd('/'), config.couchDatabase, config.couchUsername).joinToString("\u0000")
        val hash = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "journal.ack.$hash"
    }
}

internal fun organizationAcknowledgements(
    known: JsonObject, observed: Map<String, JsonObject>, completeSnapshot: Boolean,
): JsonObject = JsonObject(if (completeSnapshot) observed else known + observed)
