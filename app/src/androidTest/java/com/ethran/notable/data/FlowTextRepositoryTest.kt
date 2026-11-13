package com.ethran.notable.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ethran.notable.data.db.*
import com.ethran.notable.editor.text.*
import com.ethran.notable.io.ThumbnailBackfillQueue
import com.ethran.notable.io.ThumbnailGenerator
import com.ethran.notable.sync.SyncSettings
import com.ethran.notable.sync.couch.TextVersions
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Date

@RunWith(AndroidJUnit4::class)
class FlowTextRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var app: AppRepository
    private lateinit var repository: FlowTextRepository
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val source = Block(id = "paragraph", pageId = "page", text = "Original", orderKey = "a",
        createdAt = Date(1), updatedAt = Date(1))

    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val outbox = CouchOutboxRepository(db.couchOutboxDao())
        val kv = mockk<KvProxy>()
        coEvery { kv.getSyncSettings() } returns SyncSettings(deviceId = "android")
        app = AppRepository(
            BookRepository(db.notebookDao(), db.pageDao(), outbox, db),
            PageRepository(db.pageDao(), outbox, db), mockk(), mockk(), mockk(), mockk(), mockk(),
            mockk(), mockk(), mockk(), BlockRepository(db.blockDao()),
            DeletedBlockRepository(db.deletedBlockDao()), mockk(), outbox, mockk(), kv, db)
        val thumbnails = mockk<ThumbnailGenerator>()
        every { thumbnails.invalidate(any()) } returns emptySet()
        repository = FlowTextRepository(db, app, thumbnails, mockk<ThumbnailBackfillQueue>(relaxed = true))
        db.notebookDao().create(Notebook(id = "book", pageIds = listOf("page"), createdAt = Date(1)))
        db.pageDao().create(Page(id = "page", notebookId = "book", createdAt = Date(1)))
        db.blockDao().upsertAll(listOf(source))
    }

    @After fun cleanup() { db.close() }

    private suspend fun draft(text: String): FlowDraft {
        val document = repository.load("page")
        val run = document.runs.values.first()
        return FlowDraft("page", repository.nextRevision(), listOf(StoredFlowRun(run.token,
            run.original.map(StoredFlowBlock::from), text)))
    }

    @Test fun typingKeepsExistingInkAnnotationsAndJournalOrganizationUntouched() = runBlocking {
        val annotation = source.copy(id = "box", text = "Pinned annotation", x = 20, y = 30, width = 200)
        db.blockDao().upsertAll(listOf(annotation))
        val organization = "journal organization retained verbatim"
        db.kvDao().set(Kv("journal.entry.book", organization))
        val result = repository.save(draft("Edited\n\nA second paragraph"))
        assertTrue(result.conflicts.isEmpty())
        val blocks = db.blockDao().getByPage("page")
        assertEquals(annotation, blocks.single { it.id == "box" })
        assertEquals("Edited", blocks.single { it.id == "paragraph" }.text)
        assertEquals(listOf("Edited", "A second paragraph"), FlowText.ordered(blocks).map { it.text })
        assertEquals(organization, db.kvDao().get("journal.entry.book")?.value)
        assertTrue(db.couchOutboxDao().allIds().contains("page:page"))
    }

    @Test fun concurrentSourceStaysCanonicalWhileDraftIsDurableAndRecoverable() = runBlocking {
        val draft = draft("My unsent change")
        repository.persist(draft)
        val remote = source.copy(text = "Remote change", updatedAt = Date(50))
        db.blockDao().upsertAll(listOf(remote))
        val result = repository.save(draft)
        assertEquals(setOf(draft.runs.single().token), result.conflicts)
        val blocks = db.blockDao().getByPage("page")
        assertEquals(remote, blocks.single { it.id == source.id })
        assertEquals("My unsent change", blocks.single { it.kind == TextVersions.KIND }.text)
        assertNotNull(db.kvDao().get("editor.flow-draft.page"))
        val restored = repository.load("page")
        assertEquals("My unsent change", restored.runs.values.single().source)
        repository.keepBoth(draft, result.conflicts)
        val kept = db.blockDao().getByPage("page")
        assertEquals(listOf("Remote change", "My unsent change"), FlowText.ordered(kept).map { it.text })
        assertEquals(remote.text, kept.single { it.id == source.id }.text)
    }

    @Test fun paragraphInsertionKeepsNonTextBoundariesAndOtherRunsInOrder() = runBlocking {
        val boundary = source.copy(id = "audio", kind = "audio", text = null, orderKey = "b", payload = "{\"file\":\"recording\"}")
        val following = source.copy(id = "following", text = "After audio", orderKey = "c")
        db.blockDao().upsertAll(listOf(boundary, following))
        val before = repository.load("page")
        val first = before.runs.values.first()
        val firstSave = repository.save(FlowDraft("page", repository.nextRevision(), listOf(StoredFlowRun(first.token,
            first.original.map(StoredFlowBlock::from), "Original\n\nBefore audio"))))
        assertTrue(firstSave.conflicts.isEmpty())
        val after = repository.load("page")
        val last = after.runs.values.last()
        val secondSave = repository.save(FlowDraft("page", repository.nextRevision(), listOf(StoredFlowRun(last.token,
            last.original.map(StoredFlowBlock::from), "After audio\n\nLast paragraph"))))
        assertTrue(secondSave.conflicts.isEmpty())
        val ordered = FlowText.ordered(db.blockDao().getByPage("page"))
        assertEquals(listOf("Original", "Before audio", null, "After audio", "Last paragraph"), ordered.map { it.text })
        assertEquals(boundary.copy(orderKey = ordered.single { it.id == "audio" }.orderKey,
            updatedAt = ordered.single { it.id == "audio" }.updatedAt, deviceId = "android"), ordered.single { it.id == "audio" })
    }

    @Test fun deletedSourceIsNeverResurrectedByDraftRecovery() = runBlocking {
        val draft = draft("Kept local text")
        db.blockDao().deleteByIds(listOf(source.id))
        app.deletedBlockRepository.record("page", listOf(source.id), Date(50))
        val result = repository.save(draft)
        assertTrue(result.conflicts.isNotEmpty())
        assertFalse(db.blockDao().getByPage("page").any { it.id == source.id })
        repository.keepBoth(draft, result.conflicts)
        val restored = FlowText.ordered(db.blockDao().getByPage("page")).single()
        assertNotEquals(source.id, restored.id)
        assertEquals("Kept local text", restored.text)
    }

    @Test fun stalePendingDraftCannotRecreateARecordAfterSuccessfulSave() = runBlocking {
        val old = draft("Old pending")
        val latest = old.copy(revision = repository.nextRevision(), runs = old.runs.map { it.copy(source = "Latest") })
        repository.save(latest)
        repository.persist(old)
        assertNull(db.kvDao().get("editor.flow-draft.page"))
        assertEquals("Latest", db.blockDao().getByPage("page").single().text)
    }

    @Test fun aCrashAfterCanonicalSaveDoesNotDuplicateAlreadyWrittenParagraphs() = runBlocking {
        val old = draft("Changed\n\nSecond paragraph")
        db.blockDao().upsertAll(listOf(source.copy(text = "Changed"), source.copy(id = "second", orderKey = "b", text = "Second paragraph")))
        db.kvDao().set(Kv("editor.flow-draft.page", Json.encodeToString(old)))
        val restored = repository.load("page")
        assertEquals(listOf("paragraph", "second"), restored.runs.values.single().original.map { it.id })
        assertEquals("Changed\n\nSecond paragraph", restored.runs.values.single().source)
        repository.save(restored.snapshot())
        assertEquals(2, db.blockDao().getByPage("page").size)
    }

    @Test fun aNewRunUsesItsDurableIntendedIdsAfterCanonicalCreation() = runBlocking {
        db.blockDao().deleteByIds(listOf(source.id))
        val pending = FlowDraft("page", repository.nextRevision(), listOf(StoredFlowRun("new-run", emptyList(),
            "First\n\nSecond", listOf("intended-first", "intended-second"))))
        db.blockDao().upsertAll(listOf(source.copy(id = "intended-first", text = "First", orderKey = "a"),
            source.copy(id = "intended-second", text = "Second", orderKey = "b")))
        db.kvDao().set(Kv("editor.flow-draft.page", Json.encodeToString(pending)))
        val restored = repository.load("page")
        assertEquals(1, restored.runs.size)
        assertEquals(listOf("intended-first", "intended-second"), restored.runs.values.single().original.map { it.id })
        repository.save(restored.snapshot())
        assertEquals(2, db.blockDao().getByPage("page").size)
    }

    @Test fun restoringHistoryCannotDeleteANewerUnsentTypingDraft() = runBlocking {
        val first = draft("Initial text")
        val newer = first.copy(revision = repository.nextRevision(), runs = first.runs.map { it.copy(source = "Newer typing") })
        repository.persist(newer)
        val restore = FlowDraft("page", repository.nextRevision(), listOf(StoredFlowRun("restore", emptyList(), "Recovered source")))
        repository.keepBoth(restore, setOf("restore"), clearDraftThrough = first.revision)
        val retained = Json.decodeFromString<FlowDraft>(db.kvDao().get("editor.flow-draft.page")!!.value)
        assertEquals("Newer typing", retained.runs.single().source)
    }

    @Test fun replayingANewerCreationDraftKeepsAlreadyWrittenParagraphsInSourceOrder() = runBlocking {
        db.blockDao().deleteByIds(listOf(source.id))
        val pending = FlowDraft("page", repository.nextRevision(), listOf(StoredFlowRun("new-run", emptyList(),
            "First\n\nSecond", listOf("intended-first", "intended-second"))))
        db.blockDao().upsertAll(listOf(source.copy(id = "intended-first", text = "First", orderKey = "a")))
        db.kvDao().set(Kv("editor.flow-draft.page", Json.encodeToString(pending)))
        val restored = repository.load("page")
        val result = repository.save(restored.snapshot())
        assertTrue(result.conflicts.isEmpty())
        assertEquals(listOf("intended-first", "intended-second"), FlowText.ordered(db.blockDao().getByPage("page")).map { it.id })
        assertEquals(listOf("First", "Second"), FlowText.ordered(db.blockDao().getByPage("page")).map { it.text })
    }
}
