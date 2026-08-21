package com.ethran.notable.data

import android.content.Context
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
import com.ethran.notable.data.db.PageRepository
import com.ethran.notable.data.db.PageSyncStateRepository
import com.ethran.notable.data.db.StrokeRepository
import com.ethran.notable.testing.TestDatabaseFactory
import com.ethran.notable.testing.trashRepositoryFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Turning past the last page and writing past its end can ask for "the next page" in the same
 * frame. The existence check used to run before the creating transaction, so both callers saw
 * "no next page" and each made one — the duplicate blank pages that used to pile up at the end
 * of a notebook. The check lives inside the transaction now; this pins it.
 */
@RunWith(AndroidJUnit4::class)
class PageCreationRaceTest {
    private lateinit var db: AppDatabase
    private lateinit var repository: AppRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = TestDatabaseFactory.createInMemory(context)
        repository = AppRepository(
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
            kvProxy = KvProxy(
                KvRepository(db.kvDao(), ApplicationProvider.getApplicationContext()),
                CryptoHelper()
            ),
            db = db,
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun concurrent_requests_for_the_next_page_create_exactly_one() = runBlocking {
        val notebook = Notebook(
            title = "Race", defaultPageWidth = 1400, defaultPageHeight = 1980
        )
        repository.bookRepository.create(notebook)
        val lastPage = repository.bookRepository.getById(notebook.id)!!.pageIds.single()

        val created = (1..8).map {
            async(Dispatchers.IO) {
                repository.getNextPageIdFromBookAndPageOrCreate(notebook.id, lastPage)
            }
        }.awaitAll()

        // Every caller got the same page, and the notebook grew by exactly one.
        assertEquals(1, created.toSet().size)
        val pages = repository.bookRepository.getById(notebook.id)!!.pageIds
        assertEquals(2, pages.size)
        assertEquals(created.toSet().single(), pages.last())
    }
}
