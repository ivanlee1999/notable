package com.ethran.notable.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ethran.notable.data.db.AppDatabase
import com.ethran.notable.data.db.Block
import com.ethran.notable.data.db.Folder
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.db.Page
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.db.StrokePoint
import com.ethran.notable.editor.utils.Pen
import com.ethran.notable.recognition.handwritingFingerprint
import com.ethran.notable.recognition.HandwritingOptions
import com.ethran.notable.recognition.HandwritingPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Date

@RunWith(AndroidJUnit4::class)
class ContentTaggerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private lateinit var preferences: AutomaticTagPreferences
    private lateinit var handwriting: HandwritingPreferences
    private lateinit var originalHandwriting: HandwritingOptions
    private lateinit var tagger: ContentTagger
    private lateinit var organization: JournalOrganizationStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val book = Notebook(createdAt = Date(1000), updatedAt = Date(1000))
    private val page = Page(notebookId = book.id, createdAt = Date(1000), updatedAt = Date(1000))
    private val block = Block(pageId = page.id, text = "#Work #new", createdAt = Date(1000), updatedAt = Date(1000))

    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        preferences = AutomaticTagPreferences(context).also { it.setEnabled(true) }
        handwriting = HandwritingPreferences(context)
        originalHandwriting = handwriting.options.value
        handwriting.update(originalHandwriting.copy(automatic = true, language = "en-US"))
        tagger = ContentTagger(db, preferences, handwriting, scope, Dispatchers.IO)
        organization = JournalOrganizationStore(db)
        db.notebookDao().create(book.copy(pageIds = listOf(page.id)))
        db.pageDao().create(page)
        db.blockDao().upsertAll(listOf(block))
        organization.capture(book.id, "2026-11-13", 1000, listOf("Keep", "Work"))
    }

    @After fun tearDown() { scope.cancel(); preferences.setEnabled(true); handwriting.update(originalHandwriting); db.close() }

    @Test fun addsOnlyNewAssignmentsAndPreservesCanonicalContentAndExistingRevisions() = runBlocking {
        val before = organization.all().single()
        val notebookBefore = db.notebookDao().getById(book.id)
        tagger.apply(book.id, page.id, ContentTagSource.Text(listOf(block)))
        val after = organization.all().single()
        assertEquals(before.date, after.date); assertEquals(before.createdAt, after.createdAt)
        assertEquals(before.topics["keep"], after.topics["keep"])
        assertEquals(before.topics["work"], after.topics["work"])
        assertTrue(after.topics.getValue("new").present)
        assertEquals(notebookBefore, db.notebookDao().getById(book.id))
        assertEquals(page, db.pageDao().getById(page.id))
        assertEquals(listOf(block), db.blockDao().getByPage(page.id))
        val once = after
        tagger.apply(book.id, page.id, ContentTagSource.Text(listOf(block)))
        assertEquals(once, organization.all().single())
    }

    @Test fun respectsManualRemovalsAndRemoteRevisionCounters() = runBlocking {
        val before = organization.all().single()
        val writer = java.util.UUID.randomUUID().toString()
        organization.merge(before.copy(topics = before.topics + ("work" to TopicRevision("Work", false, 500, writer))))
        val removed = organization.all().single().topics.getValue("work")
        tagger.apply(book.id, page.id, ContentTagSource.Text(listOf(block)))
        val after = organization.all().single()
        assertEquals(removed, after.topics["work"])
        assertTrue(after.topics.getValue("new").counter > 500)
    }

    @Test fun rejectsChangedTextEvenWhenTheRemoteClockDidNotChange() = runBlocking {
        val before = organization.all().single()
        db.blockDao().upsertAll(listOf(block.copy(text = "remote")))
        tagger.apply(book.id, page.id, ContentTagSource.Text(listOf(block)))
        assertEquals(before, organization.all().single())
    }

    @Test fun ignoresDisabledTaggingAndRemovedPages() = runBlocking {
        val before = organization.all().single()
        preferences.setEnabled(false)
        tagger.apply(book.id, page.id, ContentTagSource.Text(listOf(block)))
        preferences.setEnabled(true)
        db.notebookDao().update(book.copy(pageIds = emptyList()))
        tagger.apply(book.id, page.id, ContentTagSource.Text(listOf(block)))
        assertEquals(before, organization.all().single())
    }

    @Test fun ignoresTrashedNotesAndTrashedAncestors() = runBlocking {
        val before = organization.all().single()
        db.notebookDao().update(book.copy(pageIds = listOf(page.id), deletedAt = Date(2000)))
        tagger.apply(book.id, page.id, ContentTagSource.Text(listOf(block)))
        val folder = Folder(title = "Trash parent", deletedAt = Date(2000))
        db.folderDao().create(folder)
        db.notebookDao().update(book.copy(pageIds = listOf(page.id), parentFolderId = folder.id))
        tagger.apply(book.id, page.id, ContentTagSource.Text(listOf(block)))
        assertEquals(before, organization.all().single())
    }

    @Test fun titleClassificationUsesTheExactAuthoredSnapshot() = runBlocking {
        val local = page.copy(title = "#title", updatedAt = Date(2000))
        db.pageDao().update(local)
        db.pageDao().update(local.copy(title = "remote"))
        tagger.apply(book.id, page.id, ContentTagSource.PageTitle(local))
        assertNull(organization.all().single().topics["title"])
        db.pageDao().update(local)
        tagger.apply(book.id, page.id, ContentTagSource.PageTitle(local))
        assertTrue(organization.all().single().topics.getValue("title").present)
        val named = book.copy(pageIds = listOf(page.id), title = "#notebook", updatedAt = Date(3000))
        db.notebookDao().update(named)
        tagger.apply(book.id, null, ContentTagSource.NotebookTitle(named))
        assertTrue(organization.all().single().topics.getValue("notebook").present)
    }

    @Test fun recognizedInkMustStillMatchItsExactGeometry() = runBlocking {
        val stroke = Stroke(size = 2f, pen = Pen.FOUNTAIN, top = 0f, bottom = 1f, left = 0f, right = 1f,
            points = listOf(StrokePoint(0f, 0f)), pageId = page.id, createdAt = Date(1000), updatedAt = Date(1000))
        db.strokeDao().create(stroke)
        val fingerprint = handwritingFingerprint(listOf(stroke), "en-US")
        val source = ContentTagSource.Ink(fingerprint, "en-US", "#ink")
        db.strokeDao().update(stroke.copy(points = listOf(StrokePoint(1f, 0f))))
        tagger.apply(book.id, page.id, source)
        assertNull(organization.all().single().topics["ink"])
        db.strokeDao().update(stroke)
        handwriting.update(handwriting.options.value.copy(language = "fr"))
        tagger.apply(book.id, page.id, source)
        assertNull(organization.all().single().topics["ink"])
        handwriting.update(handwriting.options.value.copy(language = "en-US"))
        tagger.apply(book.id, page.id, source)
        assertTrue(organization.all().single().topics.getValue("ink").present)
        assertEquals(listOf(stroke), db.strokeDao().getByPage(page.id))
    }

    @Test fun pendingOrganizationWithoutANotebookIsNotAnAutomaticTagCatalog() = runBlocking {
        val pending = java.util.UUID.randomUUID().toString()
        organization.capture(pending, "2026-11-13", 1000, listOf("Ghost"))
        val local = block.copy(text = "Ghost")
        db.blockDao().upsertAll(listOf(local))
        tagger.apply(book.id, page.id, ContentTagSource.Text(listOf(local)))
        assertNull(organization.all().first { it.notebookId == book.id }.topics["ghost"])
        assertTrue(organization.all().first { it.notebookId == pending }.topics.getValue("ghost").present)
    }

    @Test fun explicitTagManagementInvalidatesAnOlderAutomaticRequest() = runBlocking {
        val pendingGeneration = tagger.currentGeneration()
        tagger.invalidatePending()
        organization.removeTopicEverywhere("Work")
        tagger.invalidatePending()
        val managed = organization.all().single()
        tagger.apply(book.id, page.id, ContentTagSource.Text(listOf(block)), pendingGeneration)
        assertEquals(managed, organization.all().single())
        // A genuinely later local edit may still create a new tag, without restoring removed Work.
        val later = block.copy(updatedAt = Date(4000))
        db.blockDao().upsertAll(listOf(later))
        tagger.apply(book.id, page.id, ContentTagSource.Text(listOf(later)))
        val after = organization.all().single()
        assertFalse(after.topics.getValue("work").present)
        assertTrue(after.topics.getValue("new").present)
    }
}
