package com.ethran.notable.sync.couch

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.db.AppDatabase
import com.ethran.notable.data.db.BookRepository
import com.ethran.notable.data.db.CouchDeletionRepository
import com.ethran.notable.data.db.CouchOutboxRepository
import com.ethran.notable.data.db.CryptoHelper
import com.ethran.notable.data.db.DeletedImage
import com.ethran.notable.data.db.DeletedImageRepository
import com.ethran.notable.data.db.DeletedPageRepository
import com.ethran.notable.data.db.DeletedStroke
import com.ethran.notable.data.db.DeletedStrokeRepository
import com.ethran.notable.data.db.FolderRepository
import com.ethran.notable.data.db.ImageRepository
import com.ethran.notable.data.db.KvProxy
import com.ethran.notable.data.db.KvRepository
import com.ethran.notable.data.db.NotebookSyncStateRepository
import com.ethran.notable.data.db.PageRepository
import com.ethran.notable.data.db.PageSyncStateRepository
import com.ethran.notable.data.db.StrokeRepository
import com.ethran.notable.testing.TestDatabaseFactory
import com.ethran.notable.testing.trashRepositoryFor
import java.util.Date
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pins protocol §6.6 "Pruning": tombstones past the 30-day horizon are dropped, everything
 * younger survives, and only stroke and image tombstones are touched. The DAO queries existed
 * from the start — what this pins is that [AppRepository.pruneExpiredTombstones] actually
 * removes what the documents then stop carrying, because for months the queries had no caller
 * at all and every page document grew with each sweep of the eraser.
 */
@RunWith(AndroidJUnit4::class)
class RoomTombstonePruneTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private lateinit var db: AppDatabase
    private lateinit var repository: AppRepository

    @Before
    fun setUp() {
        db = TestDatabaseFactory.createInMemory(context)
        repository = repositoryFor(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun pruning_drops_the_expired_and_keeps_the_fresh() = runBlocking {
        val now = System.currentTimeMillis()
        val cutoff = now - 30L * 24 * 60 * 60 * 1000
        db.deletedStrokeDao().insertAll(
            listOf(
                DeletedStroke("ancient", pageId = "p1", deletedAt = Date(cutoff - 1_000)),
                DeletedStroke("recent", pageId = "p1", deletedAt = Date(now)),
            )
        )
        db.deletedImageDao().insertAll(
            listOf(
                DeletedImage("img-ancient", pageId = "p1", deletedAt = Date(cutoff - 1_000)),
                DeletedImage("img-recent", pageId = "p1", deletedAt = Date(now)),
            )
        )

        repository.pruneExpiredTombstones(cutoffMillis = cutoff)

        assertEquals(
            listOf("recent"),
            db.deletedStrokeDao().getByPage("p1").map { it.strokeId },
        )
        assertEquals(
            listOf("img-recent"),
            db.deletedImageDao().getByPage("p1").map { it.imageId },
        )
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
}
