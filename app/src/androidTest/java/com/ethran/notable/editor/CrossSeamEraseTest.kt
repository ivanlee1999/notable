package com.ethran.notable.editor

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.PageDataManager
import com.ethran.notable.data.PageViewportState
import com.ethran.notable.data.datastore.AppSettings
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.db.AppDatabase
import com.ethran.notable.data.db.BookRepository
import com.ethran.notable.data.db.CouchDeletionRepository
import com.ethran.notable.data.db.CouchOutboxRepository
import com.ethran.notable.data.db.CryptoHelper
import com.ethran.notable.data.db.DeletedImageRepository
import com.ethran.notable.data.db.DeletedPageRepository
import com.ethran.notable.data.db.DeletedStrokeRepository
import com.ethran.notable.data.db.FolderRepository
import com.ethran.notable.data.db.ImageRepository
import com.ethran.notable.data.db.KvProxy
import com.ethran.notable.data.db.KvRepository
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.db.NotebookSyncStateRepository
import com.ethran.notable.data.db.PageRepository
import com.ethran.notable.data.db.PageSyncStateRepository
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.db.StrokePoint
import com.ethran.notable.data.db.StrokeRepository
import com.ethran.notable.data.events.DefaultAppEventBus
import com.ethran.notable.data.model.PageSizePreset
import com.ethran.notable.data.model.SimplePointF
import com.ethran.notable.editor.canvas.CanvasEventBus
import com.ethran.notable.editor.state.History
import com.ethran.notable.editor.utils.Eraser
import com.ethran.notable.editor.utils.Pen
import com.ethran.notable.editor.utils.handleErase
import com.ethran.notable.sync.couch.CouchSyncController
import com.ethran.notable.testing.TestDatabaseFactory
import com.ethran.notable.testing.trashRepositoryFor
import com.ethran.notable.ui.SnackState
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pins that the eraser reaches the next page's ink where it shows under the seam.
 *
 * Under continuous scrolling the space past a page's end is the top of the next page (drawn by
 * `drawBeyondPageEnd`), and a stroke written there is filed onto that page — but erasing used to
 * search only the open page's strokes. Ink the writer had just put on the page below could be
 * seen and not taken back, which is what "I cannot erase anything on the second page" is.
 */
@RunWith(AndroidJUnit4::class)
class CrossSeamEraseTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private lateinit var db: AppDatabase
    private lateinit var repository: AppRepository
    private lateinit var manager: PageDataManager
    private lateinit var scope: CoroutineScope

    private lateinit var settingsBefore: AppSettings

    @Before
    fun setUp() {
        // The seam only exists under "Scrolling"; the device's own saved setting must not decide
        // whether this test exercises anything.
        settingsBefore = GlobalAppSettings.current
        GlobalAppSettings.update(
            settingsBefore.copy(verticalNavigation = AppSettings.VerticalNavigation.Continuous)
        )
        db = TestDatabaseFactory.createInMemory(context)
        repository = repositoryFor(db)
        manager = PageDataManager(
            appRepository = repository,
            appEventBus = DefaultAppEventBus(),
            backgroundFileWatcher = mockk(relaxed = true) {
                every { invalidatedPages } returns MutableSharedFlow()
            },
            viewport = PageViewportState(),
            couchSync = mockk<CouchSyncController>(relaxed = true),
        )
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        GlobalAppSettings.update(settingsBefore)
        if (CanvasEventBus.drawingInProgress.isLocked) {
            runCatching { CanvasEventBus.drawingInProgress.unlock() }
        }
        scope.cancel()
        // Bounded: a page-load job on the same scope can outlive the test, and an unbounded join
        // would hang the run rather than fail it.
        runBlocking { withTimeoutOrNull(10_000) { manager.awaitPendingDbWrites() } }
        db.close()
    }

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

    /**
     * Polls a condition that has to read the database. Used instead of awaiting the manager's
     * pending writes: those are a chain per page and this test's page loads live on the same
     * scope, so joining all of them can outlast the test.
     */
    private suspend fun waitForDb(timeoutMs: Long, condition: suspend () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            delay(50)
        }
        return condition()
    }

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }

    /** A short horizontal stroke centred on ([x], [y]) in its page's own coordinates. */
    private fun strokeAt(pageId: String, x: Float, y: Float) = Stroke(
        size = 5f,
        pen = Pen.BALLPEN,
        pageId = pageId,
        top = y - 2f,
        bottom = y + 2f,
        left = x - 20f,
        right = x + 20f,
        points = (-20..20 step 4).map { StrokePoint(x = x + it, y = y, pressure = 1f) },
        color = android.graphics.Color.BLACK,
    )

    /** The editor open on the first page of a two-page notebook, with the second one behind it. */
    private class Seam(val page: PageView, val pageA: String, val pageB: String)

    private fun openSeam(): Seam = runBlocking {
        // A notebook with a real paper size, like every notebook made since page sizes exist:
        // its pages declare a sheet, so the seam falls where the paper ends rather than at this
        // process's (absent) screen dimensions.
        val a4 = PageSizePreset.A4.size
        val notebook = Notebook(
            title = "Notes",
            defaultPageWidth = a4.width,
            defaultPageHeight = a4.height,
        )
        repository.bookRepository.create(notebook)
        val pageA = repository.bookRepository.getById(notebook.id)!!.pageIds.single()
        val pageB = repository.newPageInBook(notebook.id, index = 1)!!

        val page = PageView(
            context = context,
            coroutineScope = scope,
            pageDataManager = manager,
            initialPageId = pageA,
            viewWidth = 200,
            viewHeight = 300,
            snackManager = SnackState(),
        )
        assertTrue(
            "the view must finish entering page A, with page B recorded behind it",
            waitFor(5_000) { page.currentPageId == pageA && page.nextPageId == pageB },
        )
        Seam(page, pageA, pageB)
    }

    /** A straight eraser swath through ([x], [y]) in the open page's coordinates. */
    private fun swathThrough(x: Float, y: Float): List<SimplePointF> =
        (-10..10 step 2).map { SimplePointF(x + it, y) }

    @Test
    fun erasing_below_the_seam_removes_the_next_pages_ink() = runBlocking {
        val seam = openSeam()
        val history = History(seam.page, DefaultAppEventBus())

        // One stroke on each page: the next page's sits 40 units below the seam on screen, the
        // open page's well above it.
        val below = strokeAt(seam.pageB, x = 60f, y = 40f)
        val above = strokeAt(seam.pageA, x = 60f, y = 40f)
        seam.page.addStrokes(listOf(above))
        manager.addStrokesToPage(seam.pageB, listOf(below))
        assertTrue(
            "the next page's ink must be resident — it is what the seam draws",
            waitFor(5_000) { manager.getStrokes(seam.pageB).any { it.id == below.id } },
        )

        // Read after the ink has landed: adding strokes re-measures the page, and the seam is
        // wherever the page ends *now*.
        val pageEnd = seam.page.height.toFloat()
        handleErase(
            page = seam.page,
            history = history,
            points = swathThrough(x = 60f, y = pageEnd + 40f),
            eraser = Eraser.PEN,
        )
        assertTrue(
            "the erase must reach the database",
            waitForDb(10_000) {
                repository.strokeRepository.existingIds(listOf(below.id)).isEmpty() &&
                    repository.deletedStrokeRepository.getByPage(seam.pageB).isNotEmpty()
            },
        )

        assertEquals(
            "the stroke under the seam must be gone from the next page's cache " +
                "(continuous=${seam.page.continuousScrollEnabled} next=${seam.page.nextPageId} " +
                "pageEnd=$pageEnd heightNow=${seam.page.height} zoom=${seam.page.zoomLevel.value})",
            emptyList<String>(),
            manager.getStrokes(seam.pageB).map { it.id },
        )
        assertEquals(
            "and from the database",
            emptySet<String>(),
            repository.strokeRepository.existingIds(listOf(below.id)),
        )
        assertEquals(
            "with a tombstone, or the peer that still holds it puts it back on the next merge",
            listOf(below.id),
            repository.deletedStrokeRepository.getByPage(seam.pageB).map { it.strokeId },
        )
        assertEquals(
            "the open page's own ink, at the same coordinates above the seam, must be untouched",
            listOf(above.id),
            manager.getStrokes(seam.pageA).map { it.id },
        )
    }

    @Test
    fun erasing_above_the_seam_leaves_the_next_page_alone() = runBlocking {
        val seam = openSeam()
        val history = History(seam.page, DefaultAppEventBus())

        val below = strokeAt(seam.pageB, x = 60f, y = 40f)
        manager.addStrokesToPage(seam.pageB, listOf(below))
        assertTrue(
            waitFor(5_000) { manager.getStrokes(seam.pageB).any { it.id == below.id } },
        )

        // The same coordinates, but on this page: the next page must not be reached by an erase
        // that never crossed the seam.
        handleErase(
            page = seam.page,
            history = history,
            points = swathThrough(x = 60f, y = 40f),
            eraser = Eraser.PEN,
        )
        assertEquals(
            listOf(below.id),
            manager.getStrokes(seam.pageB).map { it.id },
        )
        assertEquals(
            "no tombstone either",
            emptyList<String>(),
            repository.deletedStrokeRepository.getByPage(seam.pageB).map { it.strokeId },
        )
    }
}
