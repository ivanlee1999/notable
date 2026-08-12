package com.ethran.notable.data.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ethran.notable.data.AppRepository
import com.ethran.notable.sync.couch.CouchDocBody
import com.ethran.notable.sync.couch.CouchDocId
import com.ethran.notable.sync.couch.CouchDeletedDoc
import com.ethran.notable.sync.couch.CouchDocType
import com.ethran.notable.sync.couch.CouchNotebook
import com.ethran.notable.sync.couch.CouchPage
import com.ethran.notable.sync.couch.RoomCouchStore
import com.ethran.notable.testing.TestDatabaseFactory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.util.Date
import java.util.UUID

/**
 * The outbox, against a real (in-memory) Room database.
 *
 * These are the tests the review said were missing: the scenario fixtures drive the merge engine by
 * calling `markDirty` themselves, so they can prove the protocol converges while every one of the
 * app's mutation paths quietly fails to queue anything. Everything here goes through the same
 * repository methods the BOOX's own screens call, and asks only "what is in `couch_outbox`
 * afterwards?".
 *
 * Instrumentation rather than JVM tests for the same reason as `RoomCouchStoreTest`: Room's
 * in-memory builder needs an Android `Context` and a real SQLite.
 */
@RunWith(AndroidJUnit4::class)
class CouchOutboxTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private lateinit var db: AppDatabase
    private lateinit var repository: AppRepository
    private lateinit var images: File

    @Before
    fun setUp() {
        db = TestDatabaseFactory.createInMemory(context)
        repository = repositoryFor(db)
        images = File(context.cacheDir, "outbox-images-${UUID.randomUUID()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        db.close()
        images.deleteRecursively()
    }

    // region Fixtures

    private fun repositoryFor(db: AppDatabase) = AppRepository(
        bookRepository = BookRepository(
            db.notebookDao(), db.pageDao(), CouchOutboxRepository(db.couchOutboxDao()), db
        ),
        pageRepository = PageRepository(
            db.pageDao(), CouchOutboxRepository(db.couchOutboxDao()), db
        ),
        strokeRepository = StrokeRepository(db.strokeDao()),
        imageRepository = ImageRepository(db.ImageDao()),
        folderRepository = FolderRepository(
            db.folderDao(), CouchOutboxRepository(db.couchOutboxDao()), db
        ),
        notebookSyncStateRepository = NotebookSyncStateRepository(db.notebookSyncStateDao()),
        pageSyncStateRepository = PageSyncStateRepository(db.pageSyncStateDao()),
        deletedStrokeRepository = DeletedStrokeRepository(db.deletedStrokeDao()),
        deletedPageRepository = DeletedPageRepository(db.deletedPageDao()),
        deletedImageRepository = DeletedImageRepository(db.deletedImageDao()),
        couchDeletionRepository = CouchDeletionRepository(db.couchDeletionDao()),
        couchOutboxRepository = CouchOutboxRepository(db.couchOutboxDao()),
        kvProxy = KvProxy(KvRepository(db.kvDao(), context), CryptoHelper()),
        db = db,
    )

    private fun store() = RoomCouchStore(
        repository, db.kvDao(), deviceId = "boox", imagesFolder = { images }
    )

    private fun queued(): Set<String> = runBlocking { db.couchOutboxDao().allIds().toSet() }

    /**
     * Forgets what has been queued so far, so a test can assert on what *one* action queued rather
     * than on everything its fixture did on the way there.
     */
    private fun clearQueue() = runBlocking {
        db.couchOutboxDao().allIds().forEach { db.couchOutboxDao().delete(it) }
    }

    private fun stamp(second: Int): String =
        Instant.ofEpochSecond(1_770_000_000L + second).toString()

    /** A notebook with one page, as `BookRepository.create` makes it. Returns both ids. */
    private fun seedNotebook(): Pair<String, String> = runBlocking {
        val notebook = Notebook(title = "Notes")
        repository.bookRepository.create(notebook)
        val pageId = repository.bookRepository.getById(notebook.id)!!.pageIds.single()
        notebook.id to pageId
    }

    // endregion

    // region Creation

    /**
     * The defect the review opened with: creating a notebook queued the manifest and left the page
     * it names behind, so the peer received a notebook pointing at a document the server had never
     * been offered.
     */
    @Test
    fun creating_a_notebook_queues_the_notebook_and_its_first_page() {
        val (notebookId, pageId) = seedNotebook()

        assertEquals(
            setOf(CouchDocId.notebook(notebookId), CouchDocId.page(pageId)),
            queued(),
        )
    }

    @Test
    fun creating_a_folder_queues_it() = runBlocking {
        val folder = Folder(title = "Work")
        repository.folderRepository.create(folder)

        assertEquals(setOf(CouchDocId.folder(folder.id)), queued())
    }

    // endregion

    // region Page structure

    @Test
    fun adding_a_blank_page_queues_the_page_and_the_notebook() = runBlocking {
        val (notebookId, _) = seedNotebook()
        clearQueue()

        val newPageId = repository.newPageInBook(notebookId, index = 1)

        assertNotNull("the page should have been created", newPageId)
        assertEquals(
            setOf(CouchDocId.notebook(notebookId), CouchDocId.page(newPageId!!)),
            queued(),
        )
    }

    @Test
    fun duplicating_a_page_queues_the_duplicate_and_the_notebook() = runBlocking {
        val (notebookId, pageId) = seedNotebook()
        clearQueue()

        repository.duplicatePage(pageId)

        val duplicateId = repository.bookRepository.getById(notebookId)!!.pageIds
            .single { it != pageId }
        assertEquals(
            setOf(CouchDocId.notebook(notebookId), CouchDocId.page(duplicateId)),
            queued(),
        )
    }

    /**
     * Reorder is the case the "never sent" scan can never rescue: the notebook is one the server
     * already holds, so nothing about it looks unsent, and only the order of `pageIds` moved.
     */
    @Test
    fun reordering_pages_queues_the_notebook() = runBlocking {
        val (notebookId, pageId) = seedNotebook()
        repository.newPageInBook(notebookId, index = 1)
        clearQueue()

        repository.bookRepository.changePageIndex(notebookId, pageId, 1)

        assertEquals(setOf(CouchDocId.notebook(notebookId)), queued())
    }

    // endregion

    // region Page content

    @Test
    fun renaming_a_page_queues_the_page_and_the_notebook() = runBlocking {
        val (notebookId, pageId) = seedNotebook()
        clearQueue()

        repository.pageRepository.rename(pageId, "Chapter one")

        assertEquals(
            setOf(CouchDocId.notebook(notebookId), CouchDocId.page(pageId)),
            queued(),
        )
    }

    /**
     * A background change writes the page row and nothing else — no stroke, so none of the drawing
     * paths that queue anything runs. It stayed on the device it was chosen on.
     */
    @Test
    fun changing_only_the_background_queues_the_page_and_the_notebook() = runBlocking {
        val (notebookId, pageId) = seedNotebook()
        clearQueue()

        val page = repository.pageRepository.getById(pageId)!!
        repository.pageRepository.update(
            page.copy(background = "grid", backgroundType = "native", updatedAt = Date())
        )

        assertEquals(
            setOf(CouchDocId.notebook(notebookId), CouchDocId.page(pageId)),
            queued(),
        )
    }

    // endregion

    // region What must not be queued

    /**
     * Which page was open and where the reader had scrolled to are facts about this device — the
     * store carries the local value across every incoming change rather than sending it. Queueing
     * them would mean reading a notebook pushed as often as drawing in one.
     */
    @Test
    fun device_local_fields_queue_nothing() = runBlocking {
        val (notebookId, pageId) = seedNotebook()
        clearQueue()

        repository.bookRepository.setOpenPageId(notebookId, pageId)
        repository.pageRepository.updateScroll(pageId, 420)

        assertEquals(emptySet<String>(), queued())
    }

    /**
     * The trap in this whole design: landing a change *received from the server* goes through the
     * same repository methods a user edit does. If those queued, every pull would immediately queue
     * a push, the peer would receive the echo and queue one back, and the two devices would
     * ping-pong for as long as they were both switched on.
     */
    @Test
    fun applying_a_remote_change_queues_nothing() {
        val store = store()
        val notebookId = UUID.randomUUID().toString()
        val pageId = UUID.randomUUID().toString()

        store.apply(
            CouchDocId.page(pageId),
            CouchDocBody.Page(
                CouchPage(
                    notebookId = notebookId,
                    title = "From the iPad",
                    createdAt = stamp(0),
                    updatedAt = stamp(5),
                    updatedBy = "ipad",
                )
            ),
            basedOn = null,
        )
        store.apply(
            CouchDocId.notebook(notebookId),
            CouchDocBody.Notebook(
                CouchNotebook(
                    title = "From the iPad",
                    pageIds = listOf(pageId),
                    createdAt = stamp(0),
                    updatedAt = stamp(5),
                    updatedBy = "ipad",
                )
            ),
            basedOn = null,
        )

        assertNotNull(
            "the change should really have landed",
            runBlocking { repository.pageRepository.getById(pageId) },
        )
        assertEquals(
            "a pull must not queue a push, or the two devices ping-pong forever",
            emptySet<String>(),
            queued(),
        )
    }

    /** Including the placeholder notebook a page creates when it arrives ahead of its manifest. */
    @Test
    fun applying_a_remote_page_before_its_notebook_queues_nothing() {
        val store = store()
        val notebookId = UUID.randomUUID().toString()
        val pageId = UUID.randomUUID().toString()

        store.apply(
            CouchDocId.page(pageId),
            CouchDocBody.Page(
                CouchPage(
                    notebookId = notebookId,
                    createdAt = stamp(0),
                    updatedAt = stamp(5),
                    updatedBy = "ipad",
                )
            ),
            basedOn = null,
        )

        assertNotNull(
            "the placeholder notebook should exist",
            runBlocking { repository.bookRepository.getById(notebookId) },
        )
        assertEquals(emptySet<String>(), queued())
    }

    @Test
    fun applying_a_remote_deletion_queues_nothing() {
        val (notebookId, _) = seedNotebook()
        clearQueue()
        val store = store()

        store.apply(
            CouchDocId.notebook(notebookId),
            CouchDocBody.Deleted(
                CouchDeletedDoc(
                    type = CouchDocType.NOTEBOOK,
                    deletedAt = stamp(9),
                    updatedBy = "ipad",
                )
            ),
            basedOn = null,
        )

        assertNull(runBlocking { repository.bookRepository.getById(notebookId) })
        assertEquals(emptySet<String>(), queued())
    }

    /**
     * The conflict copy is a local notebook the user can read, but it is materialized *from* a
     * remote document this build could not understand — pushing it back would offer the server a
     * duplicate of something it already has.
     */
    @Test
    fun materializing_a_conflict_copy_queues_nothing() {
        val store = store()

        store.applyConflictCopy(
            CouchDocId.notebook(UUID.randomUUID().toString()),
            buildJsonObject { put("schema", JsonPrimitive(999)) },
        )

        assertEquals(emptySet<String>(), queued())
    }

    // endregion

    // region Deletions made here

    /**
     * The tombstone and the deletion have to land together. `RoomCouchStore.load` answers with the
     * tombstone ahead of the live rows, so a tombstone written beside a delete that then failed
     * would have this device publish the removal of a notebook it is still showing the user.
     */
    @Test
    fun deleting_a_notebook_records_its_tombstone_and_queues_it() = runBlocking {
        val (notebookId, _) = seedNotebook()
        clearQueue()

        repository.deleteNotebookLocally(notebookId, deletedAt = stamp(9))

        val documentId = CouchDocId.notebook(notebookId)
        assertNull(repository.bookRepository.getById(notebookId))
        assertEquals(stamp(9), repository.couchDeletionRepository.get(documentId)?.deletedAt)
        assertTrue("the tombstone has to be offered to the server", documentId in queued())
        assertTrue(
            "and the store must answer with it rather than with the rows",
            store().load(documentId)?.isDeleted == true,
        )
    }

    @Test
    fun deleting_a_folder_records_its_tombstone_and_queues_it() = runBlocking {
        val folder = Folder(title = "Work")
        repository.folderRepository.create(folder)
        clearQueue()

        repository.deleteFolderLocally(folder.id, deletedAt = stamp(9))

        val documentId = CouchDocId.folder(folder.id)
        assertNull(repository.folderRepository.get(folder.id))
        assertEquals(stamp(9), repository.couchDeletionRepository.get(documentId)?.deletedAt)
        assertTrue(documentId in queued())
    }

    // endregion

    // region The table itself

    /**
     * Re-queueing a document that is already waiting must not push its `queuedAt` forward: the
     * number worth having is how long the *oldest* unsent change has been waiting, and a page being
     * drawn on rewrites its row several times a minute.
     */
    @Test
    fun requeueing_keeps_the_instant_the_document_first_went_unsent() = runBlocking {
        val outbox = CouchOutboxRepository(db.couchOutboxDao())
        val documentId = CouchDocId.page("p1")

        db.couchOutboxDao().add(CouchOutbox(docId = documentId, queuedAt = 1_000L))
        outbox.queue(documentId)

        assertEquals(listOf(documentId), db.couchOutboxDao().allIds())
        assertEquals(1_000L, db.couchOutboxDao().get(documentId)?.queuedAt)
    }

    @Test
    fun clearing_forgets_only_the_document_named() = runBlocking {
        val outbox = CouchOutboxRepository(db.couchOutboxDao())
        outbox.queue(listOf(CouchDocId.page("p1"), CouchDocId.page("p2")))

        outbox.clear(CouchDocId.page("p1"))

        assertEquals(listOf(CouchDocId.page("p2")), db.couchOutboxDao().allIds())
    }

    // endregion
}
