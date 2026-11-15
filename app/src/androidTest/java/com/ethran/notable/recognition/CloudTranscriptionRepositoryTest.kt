package com.ethran.notable.recognition

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ethran.notable.data.db.*
import com.ethran.notable.editor.utils.Pen
import com.ethran.notable.sync.SyncBackend
import com.ethran.notable.sync.SyncSettings
import com.ethran.notable.sync.couch.*
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Base64
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class CloudTranscriptionRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private lateinit var preferences: HandwritingPreferences
    private lateinit var original: HandwritingOptions
    private lateinit var repository: CloudTranscriptionRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val values = Channel<String?>(Channel.UNLIMITED)
    private val config = AtomicReference(SyncSettings(syncEnabled = true, backend = SyncBackend.COUCHDB,
        couchUrl = "https://sync.invalid", couchUsername = "user", couchPassword = "secret"))
    private val gate = AtomicReference<CountDownLatch?>(null)
    private val started = Channel<Unit>(Channel.UNLIMITED)
    private val paths = mutableListOf<String>()
    private val page = Page(id = "cloud-page", notebookId = "cloud-book", pageWidth = 600, pageHeight = 800)
    private val stroke = Stroke(id = "ink", pageId = page.id, size = 3f, pen = Pen.BALLPEN, maxPressure = 1,
        top = 0f, bottom = 10f, left = 0f, right = 10f, points = listOf(StrokePoint(0f, 0f), StrokePoint(10f, 10f)),
        createdAt = Date(1000), updatedAt = Date(1000))
    private val wire = CouchStroke(id = "ink", createdAt = "1970-01-01T00:00:01Z", top = 0f, bottom = 10f,
        left = 0f, right = 10f, pointsData = Base64.getEncoder().encodeToString(encodeStrokePoints(stroke.points)))
    private val canonical = CouchPage(notebookId = page.notebookId, pageWidth = 600, pageHeight = 800,
        strokes = listOf(wire), createdAt = wire.createdAt)
    private val remote = CloudPageText(page.id, page.notebookId, "#remote untouched", "2-page",
        "sha256:" + "a".repeat(64), "scribe-v2:" + "b".repeat(64))

    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        preferences = HandwritingPreferences(context); original = preferences.options.value
        preferences.update(original.copy(cloudTranscripts = true, textPreview = true, transcriptDatabase = "notes_text"))
        db.notebookDao().create(Notebook(id = page.notebookId, pageIds = listOf(page.id)))
        db.pageDao().create(page); db.strokeDao().create(stroke)
        db.blockDao().upsertAll(listOf(Block(id = "typed", pageId = page.id, text = "original typed")))
        db.kvDao().set(Kv("journal.organization.cloud-book", "original tags"))
        checkpoint()
        val settings = mockk<KvProxy>(); coEvery { settings.getSyncSettings() } answers { config.get() }
        val client = CloudTranscriptClient { _ -> object : CouchTransport {
            override fun send(request: CouchRequest): CouchResponse {
                assertEquals("GET", request.method); assertNull(request.body)
                synchronized(paths) { paths += request.path }
                if (request.path.contains("pagetext:")) {
                    started.trySend(Unit)
                    gate.get()?.await(8, TimeUnit.SECONDS)
                    return CouchResponse(200, body = couchJson.encodeToString(remote).toByteArray())
                }
                val body = JsonObject(couchJson.encodeToJsonElement(canonical).jsonObject + ("_rev" to JsonPrimitive("2-page")))
                return CouchResponse(200, body = body.toString().toByteArray())
            }
        } }
        repository = CloudTranscriptionRepository(db, settings, preferences, client, CloudTranscriptFence(), Dispatchers.IO)
    }
    @After fun tearDown() { gate.get()?.countDown(); scope.cancel(); preferences.update(original); db.close() }
    private suspend fun checkpoint(dirty: Set<String> = emptySet(), revision: String = "2-page") {
        db.kvDao().set(Kv(couchStateKey(config.get().couchUrl, config.get().couchDatabase),
            couchJson.encodeToString(CouchSyncState(revs = mapOf("page:${page.id}" to revision), dirty = dirty))))
    }
    private fun observe() { scope.launch { repository.observe(page.id).collect { values.send(it) } } }
    private suspend fun next(predicate: (String?) -> Boolean): String? = withTimeout(6_000) {
        var value = values.receive()
        while (!predicate(value)) value = values.receive()
        value
    }
    private suspend fun assertNoRemoteForInterval() {
        val value = withTimeoutOrNull(500) { next { it != null } }
        assertNull(value)
    }

    @Test fun displaysExactSourceWithoutMutatingInkTypedTextOrTagsAndInvalidatesAfterCache() = runBlocking {
        val beforeInk = db.strokeDao().getByPage(page.id)
        val beforeText = db.blockDao().getByPage(page.id)
        observe(); assertEquals(remote.text, next { it != null })
        assertEquals(beforeInk, db.strokeDao().getByPage(page.id)); assertEquals(beforeText, db.blockDao().getByPage(page.id))
        assertEquals("original tags", db.kvDao().get("journal.organization.cloud-book")?.value)
        db.strokeDao().update(stroke.copy(points = listOf(StrokePoint(0f, 0f), StrokePoint(9f, 10f))))
        next { it == null }; assertNoRemoteForInterval()
    }
    @Test fun penDownHidesImmediatelyAndOlderWriteCannotReenableDuringNewStroke() = runBlocking {
        observe(); next { it != null }
        val owner = Any(); repository.fence.contact(owner, page.id, true)
        next { it == null }
        val first = repository.fence.changed(listOf(page.id))
        repository.fence.contact(owner, null, false)
        repository.fence.contact(owner, page.id, true)
        val staged = repository.fence.stage(owner)
        repository.fence.saved(first); assertNoRemoteForInterval()
        repository.fence.contact(owner, null, false); assertNoRemoteForInterval()
        repository.fence.unstaged(staged); assertEquals(remote.text, next { it != null })
    }
    @Test fun canceledContactRevalidatesAndRestoresCloudTextWithoutAContentWrite() = runBlocking {
        observe(); next { it != null }
        val owner = Any(); repository.fence.contact(owner, page.id, true)
        next { it == null }
        repository.fence.contact(owner, null, false)
        assertEquals(remote.text, next { it != null })
    }
    @Test fun pendingOutboxImmediatelyHidesAnAcceptedCloudResult() = runBlocking {
        observe(); next { it != null }
        db.couchOutboxDao().add(CouchOutbox("page:${page.id}", 1000))
        next { it == null }; assertNoRemoteForInterval()
    }
    @Test fun localChangeMidFetchDiscardsLateRemoteResponseEvenAtTheSameClock() = runBlocking {
        val held = CountDownLatch(1); gate.set(held); observe(); withTimeout(6000) { started.receive() }
        db.strokeDao().update(stroke.copy(left = 4f))
        held.countDown(); assertNoRemoteForInterval()
    }
    @Test fun optOutAndTranscriptDatabaseSwitchClearResultAndRejectOldInflightResponse() = runBlocking {
        observe(); next { it != null }
        preferences.update(preferences.options.value.copy(cloudTranscripts = false))
        next { it == null }; assertNoRemoteForInterval()
        while (started.tryReceive().isSuccess) { /* Drain the completed request. */ }
        val held = CountDownLatch(1); gate.set(held)
        preferences.update(preferences.options.value.copy(cloudTranscripts = true))
        withTimeout(6000) { started.receive() }
        preferences.update(preferences.options.value.copy(transcriptDatabase = "other_text", cloudTranscripts = false))
        held.countDown(); assertNoRemoteForInterval()
    }
    @Test fun changedAuthHostAndPendingOutboxRejectPreviouslyAcceptedResult() = runBlocking {
        observe(); next { it != null }
        config.set(config.get().copy(couchUrl = "https://other.invalid", couchPassword = "changed"))
        db.kvDao().set(Kv("SYNC_SETTINGS", "changed"))
        next { it == null }; assertNoRemoteForInterval()
        config.set(config.get().copy(couchUrl = "https://sync.invalid"))
        db.couchOutboxDao().add(CouchOutbox("page:${page.id}", 1000)); checkpoint()
        assertNoRemoteForInterval()
    }
    @Test fun missingOrDirtyCanonicalRevisionAndTrashedNotebookNeverFetch() = runBlocking {
        checkpoint(dirty = setOf("page:${page.id}")); observe(); assertNoRemoteForInterval()
        assertTrue(synchronized(paths) { paths.isEmpty() })
        db.notebookDao().update(db.notebookDao().getById(page.notebookId)!!.copy(deletedAt = Date(2000)))
        checkpoint(); assertNoRemoteForInterval(); assertTrue(synchronized(paths) { paths.isEmpty() })
    }
}
