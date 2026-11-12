package com.ethran.notable.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ethran.notable.data.db.AppDatabase
import com.ethran.notable.data.db.BookRepository
import com.ethran.notable.data.db.CouchOutboxRepository
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
}
