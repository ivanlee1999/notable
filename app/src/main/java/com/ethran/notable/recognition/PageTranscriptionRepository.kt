package com.ethran.notable.recognition

import android.content.Context
import android.util.AtomicFile
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.db.StrokeRepository
import com.ethran.notable.di.ApplicationScope
import com.ethran.notable.di.IoDispatcher
import com.ethran.notable.editor.topics.InkTopicRecognizer
import com.ethran.notable.editor.topics.topicInk
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.DataOutputStream
import java.io.File
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Exact source geometry and identity, independent of page clocks and database row order. */
internal fun handwritingFingerprint(strokes: List<Stroke>, language: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val sink = object : OutputStream() { override fun write(value: Int) {} }
    DataOutputStream(DigestOutputStream(sink, digest)).use { output ->
        fun text(value: String) { val bytes = value.toByteArray(Charsets.UTF_8); output.writeInt(bytes.size); output.write(bytes) }
        text("page-digital-ink-v1"); text(language)
        output.writeInt(strokes.size)
        for (stroke in strokes.sortedWith(compareBy<Stroke> { it.createdAt.time }.thenBy { it.id })) {
            text(stroke.id); text(stroke.pageId); text(stroke.pen.name)
            output.writeLong(stroke.createdAt.time); output.writeLong(stroke.updatedAt.time)
            output.writeInt(stroke.color); output.writeFloat(stroke.size); output.writeInt(stroke.maxPressure)
            output.writeFloat(stroke.left); output.writeFloat(stroke.top); output.writeFloat(stroke.right); output.writeFloat(stroke.bottom)
            output.writeInt(stroke.points.size)
            for (point in stroke.points) {
                output.writeFloat(point.x); output.writeFloat(point.y)
                output.writeBoolean(point.pressure != null); point.pressure?.let(output::writeFloat)
                output.writeBoolean(point.tiltX != null); point.tiltX?.let(output::writeInt)
                output.writeBoolean(point.tiltY != null); point.tiltY?.let(output::writeInt)
                output.writeBoolean(point.dt != null); point.dt?.let { output.writeInt(it.toInt()) }
            }
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

@Serializable
internal data class CachedTranscription(val fingerprint: String, val text: String)

data class HandwritingPreview(val text: String? = null, val textMode: Boolean = false)

/** Derived local files only. This worker never writes ink, typed blocks, dates, tags or outbox. */
@Singleton
class PageTranscriptionRepository @Inject constructor(
    @param:ApplicationContext context: Context,
    private val strokes: StrokeRepository,
    private val recognizer: InkTopicRecognizer,
    val preferences: HandwritingPreferences,
    @param:ApplicationScope private val scope: CoroutineScope,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {
    private val directory = File(context.cacheDir, "handwriting-text")
    private val queue = Channel<String>(Channel.UNLIMITED)
    private val lock = Any()
    private val fileLock = Any()
    private val pending = mutableSetOf<String>()
    private val again = mutableSetOf<String>()
    private val generations = mutableMapOf<String, Long>()
    private val failed = mutableMapOf<String, Pair<String, Long>>()
    private val updates = MutableSharedFlow<String>(extraBufferCapacity = 64)
    private val penContacts = MutableStateFlow<Set<Any>>(emptySet())
    private val json = Json { ignoreUnknownKeys = true }

    init { scope.launch(io) {
        for (pageId in queue) {
            var superseded = false
            try {
                penContacts.first { it.isEmpty() }
                var generation = synchronized(lock) { generations[pageId] }
                while (true) {
                    delay(800)
                    if (penContacts.value.isNotEmpty()) {
                        penContacts.first { it.isEmpty() }
                        generation = synchronized(lock) { generations[pageId] }
                        continue
                    }
                    val latest = synchronized(lock) { generations[pageId] }
                    if (latest == generation) break
                    generation = latest
                }
                synchronized(lock) { again.remove(pageId) }
                superseded = process(pageId)
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Recognition is optional; the original ink remains visible. */ }
            finally {
                val repeat = synchronized(lock) { pending.remove(pageId); generations.remove(pageId); again.remove(pageId) || superseded }
                updates.emit(pageId)
                if (repeat) request(pageId)
            }
        }
    } }

    /** Only a tiny contact marker runs on pen callbacks; recognition waits off that path. */
    fun penContact(owner: Any, down: Boolean) {
        penContacts.update { if (down) it + owner else it - owner }
    }

    /** Called after a durable ink write and when a preview needs an uncached transcript. */
    fun request(pageId: String) {
        if (pageId.isBlank() || !preferences.options.value.automatic) return
        synchronized(lock) {
            generations[pageId] = (generations[pageId] ?: 0) + 1
            if (!pending.add(pageId)) { again += pageId; return }
            if (queue.trySend(pageId).isFailure) pending.remove(pageId)
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observe(pageId: String): Flow<HandwritingPreview> = combine(strokes.observeByPage(pageId), preferences.options,
        updates.filter { it == pageId }.onStart { emit(pageId) }) { source, options, _ -> source to options }
        .mapLatest { (source, options) -> withContext(io) {
            val fingerprint = handwritingFingerprint(source, options.language)
            val cached = read(pageId)?.takeIf { it.fingerprint == fingerprint }
            if (source.isNotEmpty() && cached == null && options.automatic && canAttempt(pageId, fingerprint)) request(pageId)
            HandwritingPreview(cached?.text?.takeIf { it.isNotBlank() }, options.textPreview)
        } }.distinctUntilChanged()

    private suspend fun process(pageId: String): Boolean {
        val options = preferences.options.value
        if (!options.automatic) return false
        if (penContacts.value.isNotEmpty()) return true
        val source = strokes.getByPage(pageId)
        val fingerprint = handwritingFingerprint(source, options.language)
        if (read(pageId)?.fingerprint == fingerprint) return false
        if (!canAttempt(pageId, fingerprint)) return false
        if (source.isEmpty()) { synchronized(fileLock) { file(pageId).delete() }; synchronized(lock) { failed.remove(pageId) }; return false }
        try {
            if (penContacts.value.isNotEmpty()) return true
            val text = coroutineScope {
                val recognition = async { recognizer.recognize(topicInk(source), options.language) {} }
                val contact = launch { penContacts.first { it.isNotEmpty() }; recognition.cancel() }
                try { recognition.await() } finally { contact.cancel() }
            }
            if (preferences.options.value != options || handwritingFingerprint(strokes.getByPage(pageId), options.language) != fingerprint)
                return true
            synchronized(fileLock) {
                directory.mkdirs()
                val destination = file(pageId)
                val output = destination.startWrite()
                try { output.write(json.encodeToString(CachedTranscription(fingerprint, text)).toByteArray(Charsets.UTF_8)); destination.finishWrite(output) }
                catch (failure: Throwable) { destination.failWrite(output); throw failure }
            }
            synchronized(lock) { failed.remove(pageId) }
        } catch (cancelled: CancellationException) {
            if (!currentCoroutineContext().isActive) throw cancelled
            return true
        }
        catch (_: Exception) { synchronized(lock) { failed[pageId] = fingerprint to System.currentTimeMillis() } }
        return false
    }

    private fun file(pageId: String): AtomicFile {
        val name = MessageDigest.getInstance("SHA-256").digest(pageId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return AtomicFile(File(directory, "$name.json"))
    }
    private fun canAttempt(pageId: String, fingerprint: String): Boolean = synchronized(lock) {
        val failure = failed[pageId]
        failure?.first != fingerprint || System.currentTimeMillis() - failure.second >= 60_000
    }
    private fun read(pageId: String): CachedTranscription? = synchronized(fileLock) { runCatching {
        file(pageId).openRead().bufferedReader(Charsets.UTF_8).use { json.decodeFromString<CachedTranscription>(it.readText()) }
    }.getOrNull() }
}
