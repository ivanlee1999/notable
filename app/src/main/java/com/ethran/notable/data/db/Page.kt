package com.ethran.notable.data.db

import androidx.lifecycle.LiveData
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.withTransaction
import com.ethran.notable.data.model.BackgroundType
import com.ethran.notable.sync.couch.CouchDocId
import com.ethran.notable.utils.logCallStack
import io.shipbook.shipbooksdk.Log
import java.util.Date
import java.util.UUID
import javax.inject.Inject

@Entity(
    foreignKeys = [ForeignKey(
        entity = Folder::class,
        parentColumns = arrayOf("id"),
        childColumns = arrayOf("parentFolderId"),
        onDelete = ForeignKey.CASCADE
    ), ForeignKey(
        entity = Notebook::class,
        parentColumns = arrayOf("id"),
        childColumns = arrayOf("notebookId"),
        onDelete = ForeignKey.CASCADE
    )]
)
data class Page(
    @PrimaryKey val id: String = UUID.randomUUID().toString(), val scroll: Int = 0,
    @ColumnInfo(index = true) val notebookId: String? = null,
    @ColumnInfo(defaultValue = "blank") val background: String = "blank", // path or native subtype
    @ColumnInfo(defaultValue = "native") val backgroundType: String = "native", // image, imageRepeating, coverImage, native
    @ColumnInfo(index = true) val parentFolderId: String? = null,
    // Null, not "", for a page the user never named: the library then falls back to the creation
    // date instead of showing a blank label, and an empty string stays available as a real (if
    // odd) choice rather than being indistinguishable from "untitled".
    val title: String? = null,
    // The sheet this page's coordinates are laid out on, in page units (see [PageSize]).
    // Null for a page created before page sizes existed: nothing retrofits one, so those keep
    // falling back to the screen width they were written against. Denormalized from the notebook
    // at creation, the way [background] is, because it is what the renderer needs in hand — and
    // because a page outside a notebook has no notebook to ask.
    val pageWidth: Int? = null,
    val pageHeight: Int? = null,
    val createdAt: Date = Date(), val updatedAt: Date = Date()
)


data class PageWithData(
    @Embedded val page: Page,
    @Relation(
        parentColumn = "id",
        entityColumn = "pageId",
        entity = Stroke::class
    ) val strokes: List<Stroke>,
    @Relation(
        parentColumn = "id",
        entityColumn = "pageId",
        entity = Image::class
    ) val images: List<Image>
)


// DAO
@Dao
interface PageDao {
    @Query("SELECT * FROM page WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<Page>

    @Query("SELECT * FROM page WHERE id = (:pageId)")
    suspend fun getById(pageId: String): Page?

    @Transaction
    @Query("SELECT * FROM page WHERE id =:pageId")
    suspend fun getPageWithDataById(pageId: String): PageWithData?

    @Query("UPDATE page SET scroll=:scroll WHERE id =:pageId")
    suspend fun updateScroll(pageId: String, scroll: Int)

    // Renaming touches one column, so it does not read-modify-write the row and cannot race a
    // concurrent stroke save into overwriting the page's drawing state. updatedAt is stored as
    // epoch millis (Date <-> Long converter), matching [touchUpdatedAt].
    @Query("UPDATE page SET title=:title, updatedAt=:updatedAt WHERE id =:pageId")
    suspend fun updateTitle(pageId: String, title: String?, updatedAt: Long)

    // Bump only the edit timestamp, without a read-modify-write of the whole row. updatedAt is
    // stored as epoch millis (Date <-> Long converter), so a Long here matches the column format.
    @Query("UPDATE page SET updatedAt=:updatedAt WHERE id =:pageId")
    suspend fun touchUpdatedAt(pageId: String, updatedAt: Long)

    @Query("SELECT * FROM page WHERE notebookId is null AND parentFolderId is :folderId")
    fun getSinglePagesInFolder(folderId: String? = null): LiveData<List<Page>>

    @Query("SELECT id FROM page WHERE notebookId = :notebookId")
    suspend fun getPageIdsForNotebook(notebookId: String): List<String>

    /**
     * The owning notebook, and nothing else.
     *
     * Every ink save asks this question, through [PageRepository.touchUpdatedAt], purely to know
     * which notebook document to queue alongside the page. Answering it with [getById] read the
     * whole row — background, geometry, titles, timestamps — and threw all of it away. One column
     * is what the caller wanted; on a BOOX the difference is per stroke, not per session.
     *
     * Null for a page that has no notebook (a quick page) *and* for a page that no longer exists.
     * The caller treats both the same way: there is no notebook to queue.
     */
    @Query("SELECT notebookId FROM page WHERE id = :pageId")
    suspend fun getNotebookId(pageId: String): String?

    @Insert
    suspend fun create(page: Page): Long

    @Update
    suspend fun update(page: Page)

    @Query("DELETE FROM page WHERE id = :pageId")
    suspend fun delete(pageId: String)
}

/**
 * Like [BookRepository], every mutating method writes its row and its [CouchOutbox] entries in one
 * transaction, so a change cannot exist on disk without the record that it has to be sent.
 */
class PageRepository @Inject constructor(
    private val db: PageDao,
    private val outbox: CouchOutboxRepository,
    private val database: AppDatabase,
) {
    /**
     * Queues the page **and** the notebook that owns it — the rule `CouchSyncHost.markPageDirty`
     * already applied and the reason it gives: the notebook's `pageIds` manifest and its
     * `updatedAt` moved with the page, so a page pushed alone lands on the peer as a document no
     * manifest names.
     *
     * A page outside a notebook is still queued on its own, which is what `markPageDirty` does for
     * a quick page today.
     */
    private suspend fun queuePage(pageId: String, notebookId: String?) {
        val ids = mutableListOf(CouchDocId.page(pageId))
        if (notebookId != null) ids += CouchDocId.notebook(notebookId)
        outbox.queue(ids)
    }

    suspend fun create(page: Page): Long = database.withTransaction {
        val rowId = db.create(page)
        queuePage(page.id, page.notebookId)
        rowId
    }

    /**
     * Scroll position is device-local and deliberately not part of the synced document (see
     * `RoomCouchStore.applyPage`, which carries the local value across every incoming change), so
     * this queues nothing — otherwise reading a notebook would push as often as drawing in one.
     */
    suspend fun updateScroll(id: String, scroll: Int) {
        return db.updateScroll(id, scroll)
    }

    /**
     * Name a page, or clear its name with null. Bumps `updatedAt` so the rename is picked up as a
     * per-page change by incremental sync, the same way an edit to the drawing would be.
     */
    suspend fun rename(pageId: String, title: String?) {
        if (pageId.isEmpty()) return
        database.withTransaction {
            db.updateTitle(pageId, title, System.currentTimeMillis())
            // Read back rather than taken from the caller: the rename menu knows a page id and
            // nothing else, and the notebook has to travel with it.
            queuePage(pageId, db.getNotebookId(pageId))
        }
    }

    /**
     * Advance a page's edit timestamp — the per-page dirty signal for incremental sync.
     *
     * This is the hot path: `PageDataManager.bumpEditTimestamps` calls it on every ink save. The
     * notebook id is read with [PageDao.getNotebookId] rather than [PageDao.getById] for exactly
     * that reason — the whole row was being loaded, background and geometry included, so a single
     * `String?` could be picked out of it and the rest discarded.
     */
    suspend fun touchUpdatedAt(pageId: String, updatedAt: Long = System.currentTimeMillis()) {
        if (pageId.isEmpty()) return
        database.withTransaction {
            db.touchUpdatedAt(pageId, updatedAt)
            queuePage(pageId, db.getNotebookId(pageId))
        }
    }

    suspend fun getById(pageId: String): Page? {
        if (pageId.isEmpty())
        {
            Log.e("PageRepository", "PageId is empty!!")
            logCallStack("PageRepository.getById")
            return null
        }
        val page = db.getById(pageId)
        if (page == null) {
            Log.w("PageRepository", "Page not found: $pageId")
        }
        return page
    }

    suspend fun getByIds(ids: List<String>): List<Page> {
        return db.getByIds(ids)
    }

    suspend fun getPageIdsForNotebook(notebookId: String): List<String> {
        return db.getPageIdsForNotebook(notebookId)
    }
    suspend fun getWithDataById(pageId: String): PageWithData? {
        val data = db.getPageWithDataById(pageId)
        if (data == null) {
            Log.w("PageRepository", "Page not found: $pageId")
            return null
        }
        // Normalize legacy raw-pressure rows on load; in-memory strokes are always [0,1].
        return data.copy(strokes = data.strokes.map { it.withNormalizedPressure() })
    }


    fun getSinglePagesInFolder(folderId: String? = null): LiveData<List<Page>> {
        return db.getSinglePagesInFolder(folderId)
    }

    suspend fun update(page: Page) {
        database.withTransaction {
            db.update(page)
            // This is also the only write behind a background-only change, which changes no ink and
            // so was never queued by any drawing path.
            queuePage(page.id, page.notebookId)
        }
    }

    suspend fun delete(pageId: String) {
        database.withTransaction {
            // Read before the delete: afterwards there is no row left to ask which notebook owned
            // it, and the notebook is the document that actually carries the removal.
            val notebookId = db.getNotebookId(pageId)
            db.delete(pageId)
            if (notebookId != null) outbox.queue(CouchDocId.notebook(notebookId))
        }
    }


}

fun Page.getBackgroundType(): BackgroundType {
    return BackgroundType.fromKey(backgroundType)
}

// TODO: make it better
suspend fun Page.getParentFolder(bookRepository: BookRepository): String? {
    return if (notebookId != null) {
        val notebook = bookRepository.getById(notebookId)
        notebook?.parentFolderId
    } else {
        parentFolderId
    }
}
