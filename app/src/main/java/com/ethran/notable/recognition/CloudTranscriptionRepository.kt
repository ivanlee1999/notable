package com.ethran.notable.recognition

import android.content.Context
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.io.File
import java.security.MessageDigest
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

/** Read-only remote text; only short source-bound titles persist between launches. */
@Singleton
class CloudTranscriptionRepository @Inject constructor(
    private val db: AppDatabase,
    private val settings: KvProxy,
    private val preferences: HandwritingPreferences,
    private val client: CloudTranscriptClient,
    val fence: CloudTranscriptFence,
    @param:IoDispatcher private val io: CoroutineDispatcher,
    @param:ApplicationContext context: Context,
) {
    private data class Source(val settings: SyncSettings, val options: HandwritingOptions,
        val notebookId: String, val revision: String, val fingerprint: String, val generation: Long,
        val databaseGeneration: String?)

    private data class Display(val content: TranscriptionContent, val source: Source, val title: String? = null)

    @Serializable private data class CachedTitle(val profile: String, val notebookId: String, val pageId: String,
        val revision: String, val fingerprint: String, val title: String)
    private val titleDirectory = File(context.filesDir, "recognition-titles")
    private val titleFileLock = Any()

    private suspend fun source(pageId: String, forTitle: Boolean = false): Source? {
        val options = preferences.options.value
        val marker = fence.state(pageId)
        if (!options.cloudTranscripts || (!forTitle && !options.textPreview) || !marker.settled) return null
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
            Source(config, options, book.id, revision, transcriptInkFingerprint(page, ink), marker.generation, state.databaseGeneration)
        }
    }

    fun observe(pageId: String): Flow<TranscriptionContent?> = observeDisplay(pageId, false).map { it?.content }

    /** Short, source-bound names survive offline restart without caching a second transcript. */
    fun observeTitle(pageId: String): Flow<String?> = observeDisplay(pageId, true).map { it?.title }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun observeDisplay(pageId: String, forTitle: Boolean): Flow<Display?> {
        val changes = db.invalidationTracker.createFlow("Page", "Stroke", "Notebook", "Folder", "Kv", "couch_outbox", "couch_deletion")
        return channelFlow<Display?> {
            val generation = AtomicLong()
            var previous: Source? = null
            var polling: Job? = null
            combine(changes, preferences.options, fence.changes) { _, _, _ -> Unit }.collect {
                val current = try { source(pageId, forTitle) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                if (current == previous && polling?.isActive == true) return@collect
                previous = current
                val token = generation.incrementAndGet()
                polling?.cancel()
                send(null)
                if (current != null) polling = launch {
                    if (forTitle) cachedTitle(pageId, current)?.takeIf { source(pageId, forTitle) == current }
                        ?.let { send(Display(TranscriptionContent(""), current, it)) }
                    while (isActive) {
                        val text = try {
                            val result = client.fetch(current.settings, current.options.transcriptDatabase, pageId,
                                current.notebookId, current.revision)
                            val content = result?.let { TranscriptionContent(it.text, it.format) }
                                ?.takeIf { result.inkFingerprint == current.fingerprint && source(pageId, forTitle) == current }
                            val title = content?.let { automaticTitle(it.text) }
                            if (forTitle && content != null && source(pageId, forTitle) == current)
                                runCatching { saveTitle(pageId, current, title) }
                            content?.let { Display(it, current, title) } ?: if (forTitle && source(pageId, forTitle) == current)
                                cachedTitle(pageId, current)?.let { Display(TranscriptionContent(""), current, it) } else null
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) {
                            if (forTitle && source(pageId, forTitle) == current)
                                cachedTitle(pageId, current)?.let { Display(TranscriptionContent(""), current, it) } else null
                        }
                        if (isActive && generation.get() == token) send(text)
                        delay(15_000)
                    }
                }
            }
        }
            .combine(preferences.options) { display, options -> display.takeIf { it?.source?.options == options && options.cloudTranscripts && (forTitle || options.textPreview) } }
            .combine(fence.changes) { display, _ -> display?.takeIf { fence.state(pageId).let { it.settled && it.generation == display.source.generation } } }
            .distinctUntilChanged().flowOn(io)
    }

    private fun profile(source: Source): String? = source.databaseGeneration?.takeIf { it.isNotBlank() }?.let { generation ->
        val config = source.settings
        hash(listOf(config.couchUrl, config.couchDatabase, config.couchUsername, config.couchPassword,
            source.options.transcriptDatabase, generation).joinToString("\u0000"))
    }
    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    private fun titleFile(pageId: String, profile: String): AtomicFile = AtomicFile(File(titleDirectory, "${hash("$profile/$pageId")}.json"))
    private fun cachedTitle(pageId: String, source: Source): String? = synchronized(titleFileLock) {
        val key = profile(source) ?: return@synchronized null
        runCatching { titleFile(pageId, key).openRead().bufferedReader(Charsets.UTF_8).use {
            couchJson.decodeFromString<CachedTitle>(it.readText())
        } }.getOrNull()?.takeIf { it.profile == key && it.notebookId == source.notebookId && it.pageId == pageId &&
            it.revision == source.revision && it.fingerprint == source.fingerprint }?.title
    }
    private fun saveTitle(pageId: String, source: Source, title: String?) = synchronized(titleFileLock) {
        val key = profile(source) ?: return@synchronized
        val file = titleFile(pageId, key)
        if (title == null) { file.delete(); return@synchronized }
        titleDirectory.mkdirs()
        val output = file.startWrite()
        try {
            output.write(couchJson.encodeToString(CachedTitle(key, source.notebookId, pageId, source.revision, source.fingerprint, title)).toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (failure: Throwable) { file.failWrite(output); throw failure }
    }
}
