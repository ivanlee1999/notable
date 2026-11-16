package com.ethran.notable.recognition

import androidx.room.withTransaction
import com.ethran.notable.data.db.AppDatabase
import com.ethran.notable.data.db.KvProxy
import com.ethran.notable.di.IoDispatcher
import com.ethran.notable.sync.SyncSettings
import com.ethran.notable.sync.couch.CouchSyncState
import com.ethran.notable.sync.couch.couchJson
import com.ethran.notable.sync.couch.couchStateKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/** Visible-page, read-only remote text. Memory-only results remain bound to exact local source. */
@Singleton
class CloudTranscriptionRepository @Inject constructor(
    private val db: AppDatabase,
    private val settings: KvProxy,
    private val preferences: HandwritingPreferences,
    private val client: CloudTranscriptClient,
    val fence: CloudTranscriptFence,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {
    private data class Source(val settings: SyncSettings, val options: HandwritingOptions,
        val notebookId: String, val revision: String, val fingerprint: String, val generation: Long)

    private data class Display(val content: TranscriptionContent, val source: Source)

    private suspend fun source(pageId: String): Source? {
        val options = preferences.options.value
        val marker = fence.state(pageId)
        if (!options.cloudTranscripts || !options.textPreview || !marker.settled) return null
        val config = settings.getSyncSettings()
        if (!config.couchActive || !config.syncEnabled) return null
        return db.withTransaction {
            val id = "page:$pageId"
            if (db.couchOutboxDao().get(id) != null || db.couchDeletionDao().get(id) != null) return@withTransaction null
            val state = db.kvDao().get(couchStateKey(config.couchUrl, config.couchDatabase))?.value
                ?.let { couchJson.decodeFromString<CouchSyncState>(it) } ?: return@withTransaction null
            val revision = state.revs[id]?.takeIf { it.isNotBlank() && id !in state.dirty } ?: return@withTransaction null
            val page = db.pageDao().getById(pageId) ?: return@withTransaction null
            val book = db.notebookDao().getById(page.notebookId) ?: return@withTransaction null
            if (book.deletedAt != null || pageId !in book.pageIds) return@withTransaction null
            var parent = book.parentFolderId
            val seen = mutableSetOf<String>()
            while (parent != null) {
                if (!seen.add(parent)) return@withTransaction null
                val folder = db.folderDao().get(parent) ?: return@withTransaction null
                if (folder.deletedAt != null) return@withTransaction null
                parent = folder.parentFolderId
            }
            val ink = db.strokeDao().getByPage(pageId)
            if (ink.isEmpty()) return@withTransaction null
            Source(config, options, book.id, revision, transcriptInkFingerprint(page, ink), marker.generation)
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observe(pageId: String): Flow<TranscriptionContent?> {
        val changes = db.invalidationTracker.createFlow("Page", "Stroke", "Notebook", "Folder", "Kv", "couch_outbox", "couch_deletion")
        return channelFlow<Display?> {
            val generation = AtomicLong()
            var previous: Source? = null
            var polling: Job? = null
            combine(changes, preferences.options, fence.changes) { _, _, _ -> Unit }.collect {
                val current = try { source(pageId) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                if (current == previous && polling?.isActive == true) return@collect
                previous = current
                val token = generation.incrementAndGet()
                polling?.cancel()
                send(null)
                if (current != null) polling = launch {
                    while (isActive) {
                        val text = try {
                            val result = client.fetch(current.settings, current.options.transcriptDatabase, pageId,
                                current.notebookId, current.revision)
                            result?.let { TranscriptionContent(it.text, it.format) }?.takeIf { result.inkFingerprint == current.fingerprint && source(pageId) == current }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { null }
                        if (isActive && generation.get() == token) send(text?.let { Display(it, current) })
                        delay(15_000)
                    }
                }
            }
        }
            .combine(preferences.options) { display, options -> display.takeIf { it?.source?.options == options && options.cloudTranscripts && options.textPreview } }
            .combine(fence.changes) { display, _ -> display?.content?.takeIf { fence.state(pageId).let { it.settled && it.generation == display.source.generation } } }
            .distinctUntilChanged().flowOn(io)
    }
}
