package com.ethran.notable.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ethran.notable.data.db.*
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Date

@RunWith(AndroidJUnit4::class)
class TagManagementRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var store: JournalOrganizationStore
    private lateinit var journal: JournalRepository
    private fun revision(name: String, present: Boolean = true, counter: Long = 10) = TopicRevision(name, present, counter, "peer")
    private fun entry(id: String, vararg topics: TopicRevision) = OrganizationEntry(id, "2026-11-13", 100,
        topics.associateBy { asciiLower(it.name) })

    @Before fun setup(): Unit = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
        val outbox = CouchOutboxRepository(db.couchOutboxDao())
        val app = AppRepository(BookRepository(db.notebookDao(), db.pageDao(), outbox, db),
            mockk(), mockk(), mockk(), FolderRepository(db.folderDao(), outbox, db), mockk(), mockk(), mockk(), mockk(), mockk(),
            mockk(), mockk(), mockk(), outbox, mockk(), mockk(), db)
        journal = JournalRepository(app, db)
        store = JournalOrganizationStore(db)
        db.notebookDao().create(Notebook(id = "live", title = "Keep my note", pageIds = listOf("page"), createdAt = Date(1), updatedAt = Date(2)))
        db.pageDao().create(Page(id = "page", notebookId = "live", createdAt = Date(1), updatedAt = Date(2)))
        db.blockDao().upsertAll(listOf(Block(id = "text", pageId = "page", text = "Original content", createdAt = Date(1), updatedAt = Date(2))))
        db.notebookDao().create(Notebook(id = "trash", deletedAt = Date(5), createdAt = Date(1), updatedAt = Date(2)))
        db.folderDao().create(Folder(id = "trash-folder", title = "Trash parent", deletedAt = Date(5)))
        db.notebookDao().create(Notebook(id = "nested", parentFolderId = "trash-folder", createdAt = Date(1), updatedAt = Date(2)))
    }
    @After fun cleanup() { db.close() }

    @Test fun mergingIntoAnExistingTagKeepsItsRevisionAndEveryUnrelatedField() = runBlocking {
        val live = entry("live", revision("Work"), revision("Personal", counter = 7), revision("Other", counter = 4), revision("Removed", false, 8))
        val trash = entry("trash", revision("Work"), revision("Personal", false, 6))
        store.merge(live); store.merge(trash)
        val beforeBook = db.notebookDao().getById("live")
        val beforePage = db.pageDao().getById("page")
        val beforeBlocks = db.blockDao().getByPage("page")
        assertEquals(2, journal.renameTopic("Work", "personal"))
        val after = store.all().associateBy { it.notebookId }
        assertFalse(after.getValue("live").topics.getValue("work").present)
        assertEquals(live.topics.getValue("personal"), after.getValue("live").topics["personal"])
        assertEquals(live.topics.getValue("other"), after.getValue("live").topics["other"])
        assertEquals(live.topics.getValue("removed"), after.getValue("live").topics["removed"])
        assertTrue(after.getValue("trash").topics.getValue("personal").present)
        assertEquals(live.copy(topics = after.getValue("live").topics), after.getValue("live"))
        assertEquals(beforeBook, db.notebookDao().getById("live"))
        assertEquals(beforePage, db.pageDao().getById("page"))
        assertEquals(beforeBlocks, db.blockDao().getByPage("page"))
    }

    @Test fun caseOnlyRenameUsesOneKeyAndAnExactRepeatDoesNotCreateARevision() = runBlocking {
        store.merge(entry("live", revision("Work"), revision("Other")))
        assertEquals(1, journal.renameTopic("Work", "work"))
        val renamed = store.all().single()
        assertEquals(setOf("work", "other"), renamed.topics.keys)
        assertEquals("work", renamed.topics.getValue("work").name)
        assertTrue(renamed.topics.getValue("work").present)
        val counter = db.kvDao().get("journal.counter")
        assertEquals(0, journal.renameTopic("work", "work"))
        assertEquals(counter, db.kvDao().get("journal.counter"))
        assertEquals(renamed, store.all().single())
    }

    @Test fun removalIncludesDirectAndInheritedTrashAndCannotBeUndoneByAnOlderSyncRecord() = runBlocking {
        val records = listOf("live", "trash", "nested").map { entry(it, revision("Work"), revision("Keep")) }
        records.forEach { store.merge(it) }
        assertEquals(TagSummary("Work", 3, 2), journal.tagCatalog.first().single { it.name == "Work" })
        val originalBooks = db.notebookDao().getAll().toSet()
        assertEquals(3, journal.removeTopicEverywhere("Work"))
        val result = store.all().associateBy { it.notebookId }
        records.forEach { original ->
            val after = result.getValue(original.notebookId)
            assertEquals(original.date, after.date); assertEquals(original.createdAt, after.createdAt)
            assertEquals(original.topics["keep"], after.topics["keep"])
            assertFalse(after.topics.getValue("work").present)
            assertFalse(mergeOrganization(after, original).topics.getValue("work").present)
        }
        assertEquals(originalBooks, db.notebookDao().getAll().toSet())
        assertEquals("Original content", db.blockDao().getByPage("page").single().text)
    }

    @Test fun invalidInputRollsBackAndPendingRecordsWithoutNotesAreNotBulkEditedOrCounted() = runBlocking {
        store.merge(entry("live", revision("Work")))
        val pending = entry("pending", revision("Work"))
        store.merge(pending)
        assertEquals(TagSummary("Work", 1, 0), journal.tagCatalog.first().single())
        val before = store.all()
        assertTrue(runCatching { journal.renameTopic("Work", "  #  ") }.isFailure)
        assertEquals(before, store.all())
        assertEquals(1, journal.removeTopicEverywhere("Work"))
        assertEquals(pending, store.all().single { it.notebookId == "pending" })
    }

    @Test fun aMalformedOrganizationRecordPreventsEveryBulkWrite() = runBlocking {
        store.merge(entry("live", revision("Work")))
        val before = db.kvDao().getJournalOrganization()
        val counter = db.kvDao().get("journal.counter")
        db.kvDao().set(Kv("journal.organization.invalid", "broken"))
        assertTrue(runCatching { journal.removeTopicEverywhere("Work") }.isFailure)
        assertEquals(before.single(), db.kvDao().get(before.single().key))
        assertEquals(counter, db.kvDao().get("journal.counter"))
    }

    @Test fun canonicalLeadingHashLabelsAreNotRenamedAsDifferentTagKeys() = runBlocking {
        store.merge(entry("live", revision("#Literal")))
        assertEquals(1, journal.renameTopic("#Literal", "##literal"))
        val after = store.all().single()
        assertEquals(setOf("#literal"), after.topics.keys)
        assertEquals("#literal", after.topics.getValue("#literal").name)
        assertTrue(after.topics.getValue("#literal").present)
    }

    @Test fun movingANoteToTrashAndRestoringItKeepsItsContentDateAndTagRevisions() = runBlocking {
        val original = entry("live", revision("Work"), revision("Removed", false))
        store.merge(original)
        val beforePage = db.pageDao().getById("page")
        val beforeBlocks = db.blockDao().getByPage("page")
        val trash = com.ethran.notable.testing.trashRepositoryFor(db)
        trash.trashNotebook("live")
        assertNotNull(db.notebookDao().getById("live")!!.deletedAt)
        assertEquals(original, store.all().single())
        trash.restoreNotebook("live")
        assertNull(db.notebookDao().getById("live")!!.deletedAt)
        assertEquals(beforePage, db.pageDao().getById("page"))
        assertEquals(beforeBlocks, db.blockDao().getByPage("page"))
        assertEquals(original, store.all().single())
    }
}
