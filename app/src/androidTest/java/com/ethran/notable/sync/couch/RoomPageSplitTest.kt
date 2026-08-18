package com.ethran.notable.sync.couch

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.TrashRepository
import com.ethran.notable.data.db.AppDatabase
import com.ethran.notable.data.db.BookRepository
import com.ethran.notable.data.db.CouchDeletionRepository
import com.ethran.notable.data.db.CouchOutboxRepository
import com.ethran.notable.data.db.CryptoHelper
import com.ethran.notable.data.db.DeletedImageRepository
import com.ethran.notable.data.db.DeletedPageRepository
import com.ethran.notable.data.db.DeletedStrokeRepository
import com.ethran.notable.data.db.FolderRepository
import com.ethran.notable.data.db.Image
import com.ethran.notable.data.db.ImageRepository
import com.ethran.notable.data.db.KvProxy
import com.ethran.notable.data.db.KvRepository
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.db.NotebookSyncStateRepository
import com.ethran.notable.data.db.Page
import com.ethran.notable.data.db.PageRepository
import com.ethran.notable.data.db.PageSyncStateRepository
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.db.StrokePoint
import com.ethran.notable.data.db.StrokeRepository
import com.ethran.notable.editor.utils.Pen
import com.ethran.notable.testing.TestDatabaseFactory
import com.ethran.notable.testing.trashRepositoryFor
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * Dividing a page that outgrew its sheet, over a real (in-memory) Room database — the Room half
 * of protocol §6.6, [RoomCouchStore.splitOversizedPages].
 *
 * The *rules* of the division (which sheet a stroke belongs to, the derived child ids, ink never
 * cut) are pinned by the shared vectors in `couch-sync-vectors/` through [PageSplit]. What these
 * tests pin is the application of those rules to Room: rows re-homed rather than copied, the
 * parent's tombstones recorded, the notebook's page list rewritten in place, everything queued —
 * and a second run finding nothing to do.
 */
@RunWith(AndroidJUnit4::class)
class RoomPageSplitTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private lateinit var db: AppDatabase
    private lateinit var repository: AppRepository
    private lateinit var store: RoomCouchStore
    private lateinit var images: File

    private val sheetWidth = 1400
    private val sheetHeight = 1000

    @Before
    fun setUp() {
        db = TestDatabaseFactory.createInMemory(context)
        repository = repositoryFor(db)
        store = RoomCouchStore(repository, db.kvDao(), deviceId = "boox", imagesFolder = { images })
        images = File(context.cacheDir, "split-images-${UUID.randomUUID()}").apply { mkdirs() }
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
        trashRepository = trashRepositoryFor(db),
        kvProxy = KvProxy(KvRepository(db.kvDao(), context), CryptoHelper()),
        db = db,
    )

    private fun stroke(pageId: String, top: Float, id: String = UUID.randomUUID().toString()) =
        Stroke(
            id = id,
            size = 3f,
            pen = Pen.BALLPEN,
            top = top,
            bottom = top + 100f,
            left = 10f,
            right = 20f,
            points = listOf(
                StrokePoint(x = 10f, y = top, pressure = 0.5f),
                StrokePoint(x = 20f, y = top + 100f, pressure = 0.5f),
            ),
            pageId = pageId,
        )

    /** A notebook holding one declared page with ink on three sheets and an image on the second. */
    private fun seedTallPage(): TallPage = runBlocking {
        val notebook = Notebook(title = "Tall", pageIds = emptyList())
        repository.bookRepository.createEmpty(notebook)
        val page = Page(
            notebookId = notebook.id,
            pageWidth = sheetWidth,
            pageHeight = sheetHeight,
        )
        repository.pageRepository.create(page)
        repository.bookRepository.addPage(notebook.id, page.id)

        val onSheet0 = stroke(page.id, top = 100f)
        val onSheet1 = stroke(page.id, top = 1100f)
        val onSheet2 = stroke(page.id, top = 2500f)
        repository.strokeRepository.create(listOf(onSheet0, onSheet1, onSheet2))
        val image = Image(
            x = 30, y = 1200, width = 50, height = 40,
            uri = "file:///local/picked.png",
            pageId = page.id,
        )
        repository.imageRepository.create(image)
        clearQueue()
        TallPage(notebook.id, page.id, onSheet0, onSheet1, onSheet2, image)
    }

    private data class TallPage(
        val notebookId: String,
        val pageId: String,
        val onSheet0: Stroke,
        val onSheet1: Stroke,
        val onSheet2: Stroke,
        val image: Image,
    )

    private fun queued(): Set<String> = runBlocking { db.couchOutboxDao().allIds().toSet() }

    private fun clearQueue() = runBlocking {
        db.couchOutboxDao().allIds().forEach { db.couchOutboxDao().delete(it) }
    }

    // endregion

    @Test
    fun a_tall_page_is_divided_into_sheets_with_derived_ids() = runBlocking {
        val seeded = seedTallPage()

        val rewritten = store.splitOversizedPages(seeded.notebookId)

        val child1 = PageSplit.childId(seeded.pageId, 1)
        val child2 = PageSplit.childId(seeded.pageId, 2)
        assertEquals(setOf(seeded.pageId, child1, child2), rewritten)

        val book = repository.bookRepository.getById(seeded.notebookId)!!
        assertEquals(listOf(seeded.pageId, child1, child2), book.pageIds)

        // The rows moved, they were not copied: same ids, on their new pages, at the top.
        val movedStroke = repository.strokeRepository.getStrokeWithPointsById(seeded.onSheet1.id)
        assertEquals(child1, movedStroke.pageId)
        assertEquals(100f, movedStroke.top, 0.01f)
        assertEquals(listOf(100f, 200f), movedStroke.points.map { it.y })
        val lastStroke = repository.strokeRepository.getStrokeWithPointsById(seeded.onSheet2.id)
        assertEquals(child2, lastStroke.pageId)
        assertEquals(500f, lastStroke.top, 0.01f)

        // The image travelled with its sheet — and kept the local file the wire model
        // cannot carry.
        val movedImage = repository.imageRepository.getImageWithPointsById(seeded.image.id)
        assertEquals(child1, movedImage.pageId)
        assertEquals(200, movedImage.y)
        assertEquals(seeded.image.uri, movedImage.uri)

        // Every page produced declares the sheet it was divided by.
        for (id in listOf(seeded.pageId, child1, child2)) {
            val page = repository.pageRepository.getById(id)!!
            assertEquals(sheetWidth, page.pageWidth)
            assertEquals(sheetHeight, page.pageHeight)
            assertEquals(seeded.notebookId, page.notebookId)
        }
    }

    @Test
    fun the_parent_remembers_the_ink_that_left_it() = runBlocking {
        val seeded = seedTallPage()

        store.splitOversizedPages(seeded.notebookId)

        // Tombstones scoped to the parent, for exactly the moved rows — what stops a peer's
        // tall copy resurrecting them into sheet 0 on merge.
        val strokeTombstones = repository.deletedStrokeRepository.getByPage(seeded.pageId)
        assertEquals(
            setOf(seeded.onSheet1.id, seeded.onSheet2.id),
            strokeTombstones.map { it.strokeId }.toSet(),
        )
        val imageTombstones = repository.deletedImageRepository.getByPage(seeded.pageId)
        assertEquals(setOf(seeded.image.id), imageTombstones.map { it.imageId }.toSet())

        // And the children carry none for the ink now living on them.
        assertTrue(
            repository.deletedStrokeRepository
                .getByPage(PageSplit.childId(seeded.pageId, 1)).isEmpty()
        )
    }

    @Test
    fun everything_the_division_wrote_is_queued() = runBlocking {
        val seeded = seedTallPage()

        store.splitOversizedPages(seeded.notebookId)

        val child1 = PageSplit.childId(seeded.pageId, 1)
        val child2 = PageSplit.childId(seeded.pageId, 2)
        assertEquals(
            setOf(
                CouchDocId.notebook(seeded.notebookId),
                CouchDocId.page(seeded.pageId),
                CouchDocId.page(child1),
                CouchDocId.page(child2),
            ),
            queued(),
        )
    }

    @Test
    fun a_second_run_finds_nothing_to_do() = runBlocking {
        val seeded = seedTallPage()
        store.splitOversizedPages(seeded.notebookId)
        val pageIdsAfterFirst = repository.bookRepository.getById(seeded.notebookId)!!.pageIds
        clearQueue()

        val rewritten = store.splitOversizedPages(seeded.notebookId)

        assertTrue(rewritten.isEmpty())
        assertEquals(
            pageIdsAfterFirst,
            repository.bookRepository.getById(seeded.notebookId)!!.pageIds,
        )
        assertTrue(queued().isEmpty())
    }

    @Test
    fun a_regrown_parent_does_not_rebuild_its_children() = runBlocking {
        val seeded = seedTallPage()
        store.splitOversizedPages(seeded.notebookId)
        val child1 = PageSplit.childId(seeded.pageId, 1)
        val child2 = PageSplit.childId(seeded.pageId, 2)

        // Ink drawn on the child after the division…
        repository.strokeRepository.create(listOf(stroke(child1, top = 50f, id = "child-ink")))
        // …and the parent re-grown underneath it, the way a peer that has not learned the
        // split pushes new below-sheet ink.
        repository.strokeRepository.create(listOf(stroke(seeded.pageId, top = 1300f, id = "regrown")))

        val rewritten = store.splitOversizedPages(seeded.notebookId)

        assertTrue(rewritten.isNotEmpty())
        val childStrokes = repository.pageRepository.getWithDataById(child1)!!.strokes
        assertEquals(
            setOf(seeded.onSheet1.id, "child-ink", "regrown"),
            childStrokes.map { it.id }.toSet(),
        )
        // The page list holds each page once, in the order the first division filed them.
        assertEquals(
            listOf(seeded.pageId, child1, child2),
            repository.bookRepository.getById(seeded.notebookId)!!.pageIds,
        )
    }

    @Test
    fun a_page_inside_its_sheet_is_untouched() = runBlocking {
        val notebook = Notebook(title = "Short", pageIds = emptyList())
        repository.bookRepository.createEmpty(notebook)
        val page = Page(notebookId = notebook.id, pageWidth = sheetWidth, pageHeight = sheetHeight)
        repository.pageRepository.create(page)
        repository.bookRepository.addPage(notebook.id, page.id)
        // Reaching past the sheet is not the same as starting past it: an overhanging stroke
        // must not divide the page, or every open would file a fresh empty page for ever.
        repository.strokeRepository.create(
            listOf(stroke(page.id, top = 950f))
        )
        clearQueue()

        val rewritten = store.splitOversizedPages(notebook.id)

        assertTrue(rewritten.isEmpty())
        assertEquals(
            listOf(page.id),
            repository.bookRepository.getById(notebook.id)!!.pageIds,
        )
        assertTrue(queued().isEmpty())
    }

    @Test
    fun an_undeclared_page_divides_by_the_sheet_both_apps_agree_on() = runBlocking {
        val notebook = Notebook(title = "Legacy", pageIds = emptyList())
        repository.bookRepository.createEmpty(notebook)
        // No declared size anywhere: the sheet has to be the shared constant, not this
        // device's screen, or the two apps would divide the same page differently.
        val page = Page(notebookId = notebook.id)
        repository.pageRepository.create(page)
        repository.bookRepository.addPage(notebook.id, page.id)
        repository.strokeRepository.create(
            listOf(stroke(page.id, top = 100f), stroke(page.id, top = 2000f))
        )
        clearQueue()

        store.splitOversizedPages(notebook.id)

        val child1 = PageSplit.childId(page.id, 1)
        val book = repository.bookRepository.getById(notebook.id)!!
        assertEquals(listOf(page.id, child1), book.pageIds)
        // 2000 lands on sheet 1 of the 1872-high legacy sheet, 128 from its top.
        val moved = repository.pageRepository.getWithDataById(child1)!!.strokes.single()
        assertEquals(128f, moved.top, 0.01f)
    }
}
