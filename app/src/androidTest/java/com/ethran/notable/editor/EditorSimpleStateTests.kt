package com.ethran.notable.editor

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.PageDataManager
import com.ethran.notable.data.datastore.EditorSettingCacheManager
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
import com.ethran.notable.data.model.TemplatePlacement
import com.ethran.notable.editor.state.History
import com.ethran.notable.editor.state.Mode
import com.ethran.notable.io.ExportEngine
import com.ethran.notable.sync.SyncOrchestrator
import com.ethran.notable.testing.TestDatabaseFactory
import com.ethran.notable.ui.SnackDispatcher
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import com.ethran.notable.testing.trashRepositoryFor

@RunWith(AndroidJUnit4::class)
class EditorSimpleStateTests {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = TestDatabaseFactory.createInMemory(context)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun modeChange_updatesToolbarState() {
        val viewModel = createEditorViewModelForTest(
            context = ApplicationProvider.getApplicationContext(),
            db = db,
        )

        viewModel.onToolbarAction(ToolbarAction.ChangeMode(Mode.Erase))
        assertEquals(Mode.Erase, viewModel.toolbarState.value.mode)
    }

    /**
     * A template chosen for a *new* page after this one: the page is made where it was asked for,
     * printed with what was chosen, and the editor moves onto it.
     *
     * The middle page of three is deliberate — appending would pass a test written on the last
     * page, and "after" has to mean after *this* page.
     */
    @Test
    fun choosingATemplateForANewPageAfterInsertsItThereAndGoesToIt() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = repositoryFor(context, db)
        val (notebookId, pageIds) = seedNotebook(repository, pages = 3)
        val viewModel = createEditorViewModelForTest(context, db, repository)
        runBlocking { viewModel.loadToolbarState(notebookId, pageIds[1]) }

        viewModel.onToolbarAction(
            ToolbarAction.BackgroundChanged("lined", "lined", TemplatePlacement.NEW_PAGE_AFTER)
        )

        val after = eventually { pageIdsOf(repository, notebookId).takeIf { it.size == 4 } }
        val inserted = after[2]
        assertEquals(listOf(pageIds[0], pageIds[1], inserted, pageIds[2]), after)
        val page = runBlocking { repository.pageRepository.getById(inserted) }
        assertEquals("lined", page?.backgroundType)
        assertEquals("lined", page?.background)
        assertEquals(
            "the editor should have moved onto the page it just made",
            inserted,
            eventually { viewModel.toolbarState.value.pageId?.takeIf { it == inserted } },
        )
    }

    /** The mirror: "before" lands ahead of the open page and leaves the rest in order. */
    @Test
    fun choosingATemplateForANewPageBeforeInsertsItAheadOfTheOpenPage() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = repositoryFor(context, db)
        val (notebookId, pageIds) = seedNotebook(repository, pages = 3)
        val viewModel = createEditorViewModelForTest(context, db, repository)
        runBlocking { viewModel.loadToolbarState(notebookId, pageIds[1]) }

        viewModel.onToolbarAction(
            ToolbarAction.BackgroundChanged("dotted", "dotted", TemplatePlacement.NEW_PAGE_BEFORE)
        )

        val after = eventually { pageIdsOf(repository, notebookId).takeIf { it.size == 4 } }
        assertEquals(listOf(pageIds[0], after[1], pageIds[1], pageIds[2]), after)
        assertEquals(
            "dotted",
            runBlocking { repository.pageRepository.getById(after[1]) }?.backgroundType,
        )
    }

    /**
     * The default is what the picker has always done: print it here, make nothing.
     */
    @Test
    fun choosingATemplateForThisPageStillPrintsItOnTheOpenPage() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = repositoryFor(context, db)
        val (notebookId, pageIds) = seedNotebook(repository, pages = 3)
        val viewModel = createEditorViewModelForTest(context, db, repository)
        runBlocking { viewModel.loadToolbarState(notebookId, pageIds[1]) }

        viewModel.onToolbarAction(ToolbarAction.BackgroundChanged("squared", "squared"))

        assertEquals(
            "squared",
            eventually {
                runBlocking { repository.pageRepository.getById(pageIds[1]) }
                    ?.backgroundType?.takeIf { it == "squared" }
            },
        )
        assertEquals(pageIds, pageIdsOf(repository, notebookId))
    }

    /** A notebook of [pages] pages, made the way the library makes one. */
    private fun seedNotebook(
        repository: AppRepository,
        pages: Int,
    ): Pair<String, List<String>> = runBlocking {
        val notebook = Notebook(title = "Daily")
        repository.bookRepository.create(notebook)
        repeat(pages - 1) { index -> repository.newPageInBook(notebook.id, index + 1) }
        notebook.id to repository.bookRepository.getById(notebook.id)!!.pageIds
    }

    private fun pageIdsOf(repository: AppRepository, notebookId: String): List<String> =
        runBlocking { repository.bookRepository.getById(notebookId)!!.pageIds }

    /**
     * The view model answers on its own coroutines, so every assertion here is about a state the
     * database reaches rather than one it is in the instant after the call.
     */
    private fun <T : Any> eventually(timeoutMs: Long = 5_000, read: () -> T?): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            read()?.let { return it }
            Thread.sleep(20)
        }
        throw AssertionError("the expected state was not reached within ${timeoutMs}ms")
    }

    private fun createEditorViewModelForTest(
        context: Context,
        db: AppDatabase,
        appRepository: AppRepository = repositoryFor(context, db),
    ): EditorViewModel {
        val kvRepository = KvRepository(db.kvDao(), context)
        val editorSettingCacheManager = EditorSettingCacheManager(kvRepository)

        val exportEngine = mockk<ExportEngine>(relaxed = true)
        val pageDataManager = mockk<PageDataManager>(relaxed = true)
        val syncOrchestrator = mockk<SyncOrchestrator>(relaxed = true)
        val snackDispatcher = mockk<SnackDispatcher>(relaxed = true)
        val historyFactory = mockk<History.Factory>(relaxed = true)

        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        return EditorViewModel(
            context = context,
            appRepository = appRepository,
            editorSettingCacheManager = editorSettingCacheManager,
            exportEngine = exportEngine,
            pageDataManager = pageDataManager,
            syncOrchestrator = syncOrchestrator,
            couchSyncHost = mockk(relaxed = true),
            snackDispatcher = snackDispatcher,
            historyFactory = historyFactory,
            appScope = appScope,
        )
    }

    /** The same database the view model reads, so a test can seed it and read it back. */
    private fun repositoryFor(context: Context, db: AppDatabase): AppRepository {
        val bookRepository = BookRepository(db.notebookDao(), db.pageDao(), CouchOutboxRepository(db.couchOutboxDao()), db)
        val pageRepository = PageRepository(db.pageDao(), CouchOutboxRepository(db.couchOutboxDao()), db)
        val strokeRepository = StrokeRepository(db.strokeDao())
        val imageRepository = ImageRepository(db.ImageDao())
        val folderRepository = FolderRepository(db.folderDao(), CouchOutboxRepository(db.couchOutboxDao()), db)

        val kvRepository = KvRepository(db.kvDao(), context)
        val kvProxy = KvProxy(kvRepository, CryptoHelper())

        val notebookSyncStateRepository = NotebookSyncStateRepository(db.notebookSyncStateDao())
        val pageSyncStateRepository = PageSyncStateRepository(db.pageSyncStateDao())
        return AppRepository(
            bookRepository = bookRepository,
            pageRepository = pageRepository,
            strokeRepository = strokeRepository,
            imageRepository = imageRepository,
            folderRepository = folderRepository,
            notebookSyncStateRepository = notebookSyncStateRepository,
            pageSyncStateRepository = pageSyncStateRepository,
            deletedStrokeRepository = DeletedStrokeRepository(db.deletedStrokeDao()),
            deletedPageRepository = DeletedPageRepository(db.deletedPageDao()),
            deletedImageRepository = DeletedImageRepository(db.deletedImageDao()),
            couchDeletionRepository = CouchDeletionRepository(db.couchDeletionDao()),
            couchOutboxRepository = CouchOutboxRepository(db.couchOutboxDao()),
            trashRepository = trashRepositoryFor(db),
            kvProxy = kvProxy,
            db = db,
        )
    }
}
