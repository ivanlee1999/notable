package com.ethran.notable.recognition

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ethran.notable.data.db.*
import com.ethran.notable.sync.SyncBackend
import com.ethran.notable.sync.SyncSettings
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class NoteTitlesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private lateinit var titles: NoteTitles
    private lateinit var preferences: HandwritingPreferences
    private lateinit var original: HandwritingOptions
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cache = File(context.cacheDir, "note-title-test-${UUID.randomUUID()}")
    private val book = Notebook(id = "auto-title-book", title = "", pageIds = listOf("first", "second"), openPageId = "second")

    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        db.notebookDao().create(book)
        for (id in book.pageIds) db.pageDao().create(Page(id = id, notebookId = book.id))
        db.kvDao().set(Kv("journal.organization.auto-title-book", "original-date-and-tags"))
        preferences = HandwritingPreferences(context); original = preferences.options.value
        preferences.update(original.copy(cloudTranscripts = false))
        val config = mockk<KvProxy>(); coEvery { config.getSyncSettings() } returns SyncSettings(backend = SyncBackend.OFF)
        val cloud = CloudTranscriptionRepository(db, config, preferences, CloudTranscriptClient { error("No network when opted out") },
            CloudTranscriptFence(), Dispatchers.IO, object : ContextWrapper(context) { override fun getFilesDir(): File = cache })
        titles = NoteTitles(db, cloud, scope, Dispatchers.IO)
    }

    @After fun tearDown() { scope.cancel(); preferences.update(original); db.close(); cache.deleteRecursively() }
    private suspend fun name(expected: String) = withTimeout(6000) { titles.notebook(book.id).first { it == expected } }

    @Test fun typedTitlesFollowManifestOrderOfflineAndEraseFallsBackWithoutCanonicalWrites() = runBlocking {
        db.blockDao().upsertAll(listOf(Block(id = "later", pageId = "second", text = "## Later page")))
        assertEquals("Later page", name("Later page"))
        db.blockDao().upsertAll(listOf(Block(id = "earlier", pageId = "first", text = "# First page")))
        assertEquals("First page", name("First page"))
        db.blockDao().deleteByIds(listOf("earlier"))
        assertEquals("Later page", name("Later page"))
        db.blockDao().deleteByIds(listOf("later"))
        assertEquals("Untitled", name("Untitled"))
        assertEquals(book, db.notebookDao().getById(book.id))
        assertEquals("original-date-and-tags", db.kvDao().get("journal.organization.auto-title-book")?.value)
        assertTrue(db.couchOutboxDao().allIds().isEmpty())
    }

    @Test fun manualNotebookAndPageNamesWinAndLegacyNamesStayExact() = runBlocking {
        db.blockDao().upsertAll(listOf(Block(id = "text", pageId = "first", text = "# Content heading")))
        assertEquals("Content heading", name("Content heading"))
        val page = db.pageDao().getById("first")!!
        db.pageDao().update(page.copy(title = "Page name"))
        assertEquals("Page name", name("Page name"))
        for (manual in listOf("My chosen title", "16 Nov 2026 12:00", "Untitled", "  Exact spelling  ")) {
            db.notebookDao().update(book.copy(title = manual))
            assertEquals(manual, name(manual))
            db.blockDao().upsertAll(listOf(Block(id = "text", pageId = "first", text = "Different content")))
            assertEquals(manual, name(manual))
        }
    }
}
