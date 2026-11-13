package com.ethran.notable.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ethran.notable.data.db.AppDatabase
import com.ethran.notable.data.db.BookRepository
import com.ethran.notable.data.db.CouchOutboxRepository
import com.ethran.notable.data.db.Kv
import com.ethran.notable.data.db.Notebook
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.TimeZone
import java.util.UUID
import java.util.Date

@RunWith(AndroidJUnit4::class)
class JournalRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "journal-test-${UUID.randomUUID()}"
    private lateinit var db: AppDatabase
    private lateinit var journal: JournalRepository

    @Before
    fun setUp() = openDatabase()

    private fun openDatabase() {
        db = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .allowMainThreadQueries().build()
        val outbox = CouchOutboxRepository(db.couchOutboxDao())
        val books = BookRepository(db.notebookDao(), db.pageDao(), outbox, db)
        val app = AppRepository(
            bookRepository = books,
            pageRepository = mockk(),
            strokeRepository = mockk(),
            imageRepository = mockk(),
            folderRepository = mockk(),
            notebookSyncStateRepository = mockk(),
            pageSyncStateRepository = mockk(),
            deletedStrokeRepository = mockk(),
            deletedPageRepository = mockk(),
            deletedImageRepository = mockk(),
            blockRepository = mockk(),
            deletedBlockRepository = mockk(),
            couchDeletionRepository = mockk(),
            couchOutboxRepository = outbox,
            trashRepository = mockk(),
            kvProxy = mockk(),
            db = db,
        )
        journal = JournalRepository(app, db)
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun severalNotesOnOneDayKeepIndependentPagesAndTopics() = runBlocking {
        val first = journal.createEntry()
        val second = journal.createEntry()
        journal.addTopic(first.first, "Product")
        journal.addTopic(second.first, "Personal")

        val entries = journal.entries.first()
        assertEquals(2, entries.size)
        assertNotEquals(first.first, second.first)
        assertNotEquals(first.second, second.second)
        assertEquals(entries[0].date, entries[1].date)
        assertEquals(listOf("Product"), entries.single { it.notebookId == first.first }.topics)
        assertEquals(listOf("Personal"), entries.single { it.notebookId == second.first }.topics)
        assertEquals(first.first, db.pageDao().getById(first.second)?.notebookId)
        assertEquals(second.first, db.pageDao().getById(second.second)?.notebookId)
    }

    @Test
    fun creatingFromTopicPersistsCanonicalTopicsWithTheNewNote() = runBlocking {
        journal.createEntry(listOf("Product"))
        val id = journal.createEntry(listOf(" #product ", "PRODUCT", " Alex ")).first
        assertEquals(listOf("Product", "Alex"), journal.entries.first().single { it.notebookId == id }.topics)
    }

    @Test
    fun metadataFailureRollsBackNotebookPageAndInkOutbox() = runBlocking {
        db.openHelper.writableDatabase.execSQL(
            """CREATE TRIGGER reject_journal BEFORE INSERT ON kv
                WHEN NEW.`key` GLOB 'journal.entry.*'
                BEGIN SELECT RAISE(ABORT, 'metadata write failed'); END"""
        )
        try {
            journal.createEntry(listOf("Product"))
            fail("Expected metadata failure")
        } catch (_: android.database.sqlite.SQLiteConstraintException) {
            // Real SQLite failure, after the notebook/page/outbox were inserted.
        }
        assertTrue(db.notebookDao().getAll().isEmpty())
        assertTrue(db.couchOutboxDao().allIds().isEmpty())
        assertTrue(db.kvDao().getJournalEntries().isEmpty())
        assertTrue(db.kvDao().getJournalOrganization().isEmpty())
        db.query("SELECT COUNT(id) FROM page", emptyArray()).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
    }

    @Test
    fun topicsSurviveDatabaseReopenAndEditingDoesNotMoveCaptureDate() = runBlocking {
        val id = journal.createEntry().first
        journal.setTopics(id, listOf(" Product ", "#product", "Alex"))
        val original = journal.entries.first().single()
        db.close()
        openDatabase()
        assertEquals(original, journal.entries.first().single())

        val zone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"))
            journal.addTopic(id, "Research")
            val edited = journal.entries.first().single()
            assertEquals(original.date, edited.date)
            assertEquals(original.createdAt, edited.createdAt)
            assertEquals(listOf("Product", "Alex", "Research"), edited.topics)
        } finally {
            TimeZone.setDefault(zone)
        }
    }

    @Test
    fun concurrentTopicEditsDoNotOverwriteOneAnother() = runBlocking {
        val id = journal.createEntry().first
        coroutineScope {
            val first = async { journal.addTopic(id, "Product") }
            val second = async { journal.addTopic(id, "Alex") }
            first.await()
            second.await()
        }
        assertEquals(setOf("Product", "Alex"), journal.entries.first().single().topics.toSet())
        journal.addTopic(id, "#PRODUCT")
        assertEquals(2, journal.entries.first().single().topics.size)
        journal.removeTopic(id, " product ")
        assertFalse(journal.entries.first().single().topics.contains("Product"))
    }

    @Test
    fun legacyUnicodeTopicsNormalizeWithoutChangingLiteralHashNames() = runBlocking {
        val legacy = JournalEntry("legacy", "2026-11-11", 123, listOf("e\u0301", "é", "#Idea"))
        db.kvDao().set(Kv("journal.entry.legacy", Json.encodeToString(legacy)))
        val migrated = JournalOrganizationStore(db).all().single()
        assertEquals(setOf("é", "#idea"), migrated.topics.keys)
        assertEquals(listOf("é", "#Idea"), journal.entries.first().single().topics)
        db.close()
        openDatabase()
        assertEquals(migrated, JournalOrganizationStore(db).all().single())
    }

    @Test
    fun migratesLegacyRowsAndKeepsThemReadableAfterReopen() = runBlocking {
        val legacy = JournalEntry("legacy", "2026-11-11", 123, listOf("Product", "#Idea"))
        db.kvDao().set(Kv("journal.entry.legacy", Json.encodeToString(legacy)))
        val migrated = JournalOrganizationStore(db).all().single()
        assertEquals(legacy.date, migrated.date)
        assertEquals(legacy.createdAt, migrated.createdAt)
        assertEquals(setOf("product", "#idea"), migrated.topics.keys)
        assertEquals(setOf(0L), migrated.topics.values.map { it.counter }.toSet())
        db.close()
        openDatabase()
        assertEquals(migrated, JournalOrganizationStore(db).all().single())
        assertEquals(legacy, journal.entries.first().single())
    }

    @Test
    fun remoteApplyMergesAgainstCurrentLocalEditsAndRemovalSurvivesReopen() = runBlocking {
        val id = journal.createEntry(listOf("Product")).first
        val store = JournalOrganizationStore(db)
        val beforeNetwork = store.all().single()
        val remote = beforeNetwork.copy(topics = beforeNetwork.topics +
            ("alex" to TopicRevision("Alex", true, 80, "remote")))
        journal.addTopic(id, "Research")
        store.merge(remote)
        assertEquals(setOf("Product", "Research", "Alex"), journal.entries.first().single().topics.toSet())

        val removed = store.all().single().copy(topics = store.all().single().topics +
            ("product" to TopicRevision("Product", false, 100, "remote")))
        store.merge(removed)
        store.merge(beforeNetwork)
        assertFalse(store.all().single().topics.getValue("product").present)
        db.close()
        openDatabase()
        journal.addTopic(id, "Product")
        val after = JournalOrganizationStore(db).all().single()
        assertTrue(after.topics.getValue("product").present)
        assertTrue(after.topics.getValue("product").counter > 100)
        assertEquals(beforeNetwork.date, after.date)
        assertEquals(beforeNetwork.createdAt, after.createdAt)
    }

    @Test
    fun concurrentRemoteAndLocalIndependentTopicsSurvive() = runBlocking {
        val id = journal.createEntry().first
        val store = JournalOrganizationStore(db)
        val remote = store.all().single().copy(topics = mapOf("alex" to TopicRevision("Alex", true, 20, "remote")))
        coroutineScope {
            val local = async { journal.addTopic(id, "Product") }
            val incoming = async { store.merge(remote) }
            local.await()
            incoming.await()
        }
        assertEquals(setOf("Product", "Alex"), journal.entries.first().single().topics.toSet())
    }

    @Test
    fun taggingOrdinaryNotebookDoesNotCreateCaptureDate() = runBlocking {
        db.notebookDao().create(Notebook(id = "ordinary", title = "Old note", createdAt = Date(123)))
        journal.addTopic("ordinary", "Product")
        val record = JournalOrganizationStore(db).all().single()
        assertEquals(null, record.date)
        assertEquals(123L, record.createdAt)
        assertEquals(null, journal.entries.first().single().date)
    }

    @Test
    fun organizationCanArriveBeforeInkWithoutBeingPruned() = runBlocking {
        val incoming = OrganizationEntry("not-downloaded", "2026-11-11", 123,
            mapOf("product" to TopicRevision("Product", true, 8, "remote")))
        val store = JournalOrganizationStore(db)
        store.merge(incoming)
        assertTrue(db.notebookDao().getAll().isEmpty())
        assertEquals(listOf(incoming), store.all())
        assertEquals("not-downloaded", journal.entries.first().single().notebookId)
    }

    @Test
    fun malformedCanonicalDataDoesNotOverwriteLegacyMetadata() = runBlocking {
        val legacy = JournalEntry("legacy", "2026-11-11", 123, listOf("Product"))
        val encoded = Json.encodeToString(legacy)
        db.kvDao().set(Kv("journal.entry.legacy", encoded))
        db.kvDao().set(Kv("journal.organization.bad", "not json"))
        assertTrue(runCatching { JournalOrganizationStore(db).all() }.isFailure)
        assertEquals(encoded, db.kvDao().get("journal.entry.legacy")?.value)
        assertEquals("not json", db.kvDao().get("journal.organization.bad")?.value)
        assertEquals(null, db.kvDao().get("journal.organization.legacy"))
    }
}
