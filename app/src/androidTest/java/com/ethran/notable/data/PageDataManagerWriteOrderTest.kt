package com.ethran.notable.data

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import com.ethran.notable.data.db.Page
import com.ethran.notable.data.db.PageRepository
import com.ethran.notable.data.db.PageSyncStateRepository
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.db.StrokeDao
import com.ethran.notable.data.db.StrokePoint
import com.ethran.notable.data.db.StrokeRepository
import com.ethran.notable.data.events.DefaultAppEventBus
import com.ethran.notable.editor.utils.Pen
import com.ethran.notable.sync.couch.CouchSyncController
import com.ethran.notable.testing.TestDatabaseFactory
import com.ethran.notable.testing.trashRepositoryFor
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Per-page ordering of the fire-and-forget DB writes.
 *
 * Before the write chain, every content write was an independent coroutine on the IO pool with its
 * own retry/backoff, so erase-right-after-draw could invert: the insert sat in its retry backoff
 * while the delete ran to completion against a row that did not exist yet, and the insert then
 * landed after it — the stroke's row resurrected against its own tombstone. These tests force that
 * exact interleaving (the retry delay makes it deterministic) and pin that submission order wins.
 */
@RunWith(AndroidJUnit4::class)
class PageDataManagerWriteOrderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private lateinit var db: AppDatabase
    private lateinit var repository: AppRepository
    private lateinit var manager: PageDataManager
    private lateinit var strokeRepository: StrokeRepository

    /** How many upcoming stroke-insert attempts should fail, per page id. */
    private val failuresByPage = HashMap<String, AtomicInteger>()

    /**
     * A [StrokeDao] whose list-insert fails while a page's failure budget lasts. Plain interface
     * delegation rather than a MockK spy: `callOriginal` on a spied suspend function corrupts the
     * coroutine continuation and crashes the process.
     */
    private fun failingDao(real: StrokeDao): StrokeDao = object : StrokeDao by real {
        override suspend fun create(strokes: List<Stroke>) {
            val budget = strokes.firstOrNull()?.let { failuresByPage[it.pageId] }
            if (budget != null && budget.getAndDecrement() > 0) {
                throw SQLiteException("injected disk failure")
            }
            real.create(strokes)
        }
    }

    @Before
    fun setUp() {
        db = TestDatabaseFactory.createInMemory(context)
        strokeRepository = StrokeRepository(failingDao(db.strokeDao()))
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
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun repositoryFor(db: AppDatabase) = AppRepository(
        bookRepository = BookRepository(
            db.notebookDao(), db.pageDao(), CouchOutboxRepository(db.couchOutboxDao()), db
        ),
        pageRepository = PageRepository(
            db.pageDao(), CouchOutboxRepository(db.couchOutboxDao()), db
        ),
        strokeRepository = strokeRepository,
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

    private fun seedPage(): String = runBlocking {
        val notebook = Notebook(title = "Notes")
        repository.bookRepository.create(notebook)
        repository.bookRepository.getById(notebook.id)!!.pageIds.single()
    }

    private fun stroke(pageId: String) = Stroke(
        id = UUID.randomUUID().toString(),
        size = 5f,
        pen = Pen.BALLPEN,
        top = 10f,
        bottom = 20f,
        left = 10f,
        right = 20f,
        points = listOf(
            StrokePoint(x = 10f, y = 10f, pressure = 1f),
            StrokePoint(x = 20f, y = 20f, pressure = 1f),
        ),
        pageId = pageId,
    )

    private fun strokeExists(id: String): Boolean =
        runBlocking { strokeRepository.existingIds(listOf(id)).isNotEmpty() }

    /**
     * The resurrection scenario itself. The insert's first attempt fails, parking it in its 500ms
     * retry backoff; the delete for the same stroke is submitted while it waits. In submission
     * order the stroke must end up gone — before the chain, the delete ran first (a no-op against
     * a row not yet written) and the retried insert then landed after it, resurrecting the stroke
     * against its own tombstone.
     */
    @Test
    fun a_delete_submitted_after_a_retrying_insert_still_wins() = runBlocking {
        val pageId = seedPage()
        val stroke = stroke(pageId)
        failuresByPage[pageId] = AtomicInteger(1)

        manager.saveStrokesToDb(listOf(stroke))
        manager.removeStrokesFromDb(listOf(stroke.id), pageId)
        manager.awaitPendingDbWrites()

        assertTrue("the erased stroke must stay erased", !strokeExists(stroke.id))
        assertEquals(
            "the erasure must leave its tombstone",
            listOf(stroke.id),
            repository.deletedStrokeRepository.getByPage(pageId).map { it.strokeId },
        )
        assertEquals(
            "every write landed, so the page is safe again",
            SaveState.Saved,
            manager.saveState.value,
        )
    }

    /** Order also holds when nothing fails: insert then delete, submitted back-to-back. */
    @Test
    fun insert_then_delete_without_failures_leaves_the_stroke_erased() = runBlocking {
        val pageId = seedPage()
        repeat(20) {
            val stroke = stroke(pageId)
            manager.saveStrokesToDb(listOf(stroke))
            manager.removeStrokesFromDb(listOf(stroke.id), pageId)
            manager.awaitPendingDbWrites()
            assertTrue("run $it: the erased stroke must stay erased", !strokeExists(stroke.id))
        }
        assertEquals(SaveState.Saved, manager.saveState.value)
    }

    /**
     * The chain is per page, not global: a write stuck retrying on one page must not hold up
     * another page's writes. Page A's insert fails three times (≥3.5s of backoff); page B's
     * insert, submitted after it, has to land while A is still waiting.
     */
    @Test
    fun a_retrying_write_on_one_page_does_not_block_another_page() = runBlocking {
        val pageA = seedPage()
        val pageB = seedPage()
        val strokeA = stroke(pageA)
        val strokeB = stroke(pageB)
        failuresByPage[pageA] = AtomicInteger(3)

        manager.saveStrokesToDb(listOf(strokeA))
        manager.saveStrokesToDb(listOf(strokeB))

        val deadline = System.currentTimeMillis() + 2_000
        while (!strokeExists(strokeB.id) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        assertTrue("page B's write must land while page A is retrying", strokeExists(strokeB.id))
        assertTrue(
            "page A's insert must still be waiting out its backoff",
            !strokeExists(strokeA.id),
        )

        manager.awaitPendingDbWrites()
        assertTrue("page A's insert eventually lands", strokeExists(strokeA.id))
        assertEquals(SaveState.Saved, manager.saveState.value)
    }
}
