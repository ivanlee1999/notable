package com.ethran.notable.editor

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.PageDataManager
import com.ethran.notable.data.PageViewportState
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
import com.ethran.notable.data.db.StrokeRepository
import com.ethran.notable.data.events.DefaultAppEventBus
import com.ethran.notable.data.model.PageSizePreset
import com.ethran.notable.editor.canvas.CanvasEventBus
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
 * Pins that [PageView.changePage] waits for in-flight stroke processing before swapping page
 * identity.
 *
 * A stroke finished just before a page switch is processed later on Main under
 * [CanvasEventBus.drawingInProgress], and it reads `currentPageId`/scroll/zoom at processing time
 * — so a switch that did not wait filed that stroke onto the NEW page at the new page's
 * scroll/zoom. The interleaving itself (firmware pen-up callback racing a tap) cannot be driven
 * deterministically from a test, so this pins the seam the fix uses: with the drawing lock held,
 * the switch must park; released, it must complete. `updateScroll`/`updateZoom` already behave
 * this way through the same [PageView] helper.
 */
@RunWith(AndroidJUnit4::class)
class PageViewChangePageTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private lateinit var db: AppDatabase
    private lateinit var repository: AppRepository
    private lateinit var manager: PageDataManager
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
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
        // Never leave the global lock held for the next test in the process.
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

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }

    @Test
    fun changePage_waits_for_the_drawing_lock_before_swapping_page_identity() = runBlocking {
        // A notebook with a real paper size, like every notebook made since page sizes exist.
        // A page that declares none falls back to legacyScreenSheet(), and SCREEN_WIDTH/HEIGHT
        // are 0 off Onyx hardware — every drawing path would then run on a 0x0 sheet.
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
            "the view must finish entering page A first",
            waitFor(5_000) { page.currentPageId == pageA },
        )

        // A stroke handler holds this for as long as it is turning pen-up points into ink.
        assertTrue(CanvasEventBus.drawingInProgress.tryLock())
        try {
            page.changePage(pageB)
            // The switch must park behind the lock rather than swap identity under the stroke.
            // (waitForDrawing gives up after 3s as a deadlock escape, so the window checked here
            // must stay well inside that.)
            Thread.sleep(800)
            assertEquals(
                "the switch must not proceed while a stroke is being processed",
                pageA,
                page.currentPageId,
            )
        } finally {
            CanvasEventBus.drawingInProgress.unlock()
        }

        assertTrue(
            "released, the parked switch must complete",
            waitFor(3_000) { page.currentPageId == pageB },
        )
    }
}
