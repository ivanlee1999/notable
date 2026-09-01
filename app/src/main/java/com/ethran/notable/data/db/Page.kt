package com.ethran.notable.data.db

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
import com.ethran.notable.sync.SyncClock
import com.ethran.notable.sync.couch.CouchDocId
import com.ethran.notable.utils.logCallStack
import io.shipbook.shipbooksdk.Log
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import java.util.Date
import java.util.UUID
import javax.inject.Inject

/**
 * One sheet of a notebook.
 *
 * Every page belongs to a notebook: [notebookId] is not nullable, and the foreign key is the only
 * one on this table. That was not always so — before schema 48 a page with no notebook was a
 * "quick page", parked in a folder of its own through a `parentFolderId` column. It bought nothing the
 * library could not already do and cost the page its sync: the protocol has no standalone page
 * lifecycle (§6.4 — pages live and die with a notebook's `pageIds`), so a page belonging to no
 * notebook was never enumerated, never pushed, and never seen on the peer. `MIGRATION_47_48` gave
 * each of those pages a notebook of its own, and the column that made them expressible is gone.
 */
@Entity(
    foreignKeys = [ForeignKey(
        entity = Notebook::class,
        parentColumns = arrayOf("id"),
        childColumns = arrayOf("notebookId"),
        onDelete = ForeignKey.CASCADE
    )]
)
data class Page(
    @PrimaryKey val id: String = UUID.randomUUID().toString(), val scroll: Int = 0,
    @ColumnInfo(index = true) val notebookId: String,
    @ColumnInfo(defaultValue = "blank") val background: String = "blank", // path or native subtype
    @ColumnInfo(defaultValue = "native") val backgroundType: String = "native", // image, imageRepeating, coverImage, native
    // Null, not "", for a page the user never named: the library then falls back to the creation
    // date instead of showing a blank label, and an empty string stays available as a real (if
    // odd) choice rather than being indistinguishable from "untitled".
    val title: String? = null,
    // The sheet this page's coordinates are laid out on, in page units (see [PageSize]).
    // Null for a page created before page sizes existed: nothing retrofits one, and those
    // resolve to the canonical legacy sheet ([PageSize.LEGACY_UNDECLARED]) on every device.
    // Pages created now always declare one (see [Notebook.newPage]). Denormalized from the
    // notebook at creation, the way [background] is, because it is what the renderer needs in
    // hand.
    val pageWidth: Int? = null,
    val pageHeight: Int? = null,
    val createdAt: Date = SyncClock.nowDate(), val updatedAt: Date = SyncClock.nowDate(),
    /**
     * Which device last wrote this page, or null when that device is this one.
     *
     * Protocol §4 breaks a scalar tie on `updatedBy`, so the sync store has to be able to answer
     * "who wrote this" for a page that arrived from a peer. Room had no column for it and
     * `RoomCouchStore.loadPage` filled in this device's id unconditionally, which made the BOOX
     * claim authorship of every page the iPad wrote: the merge then read its own copy as different
     * from the incoming one, pushed it back, and the peer did the same in return. Whole revisions
     * of identical content were being written on both sides.
     *
     * Null means "this device" rather than storing the id, so the column needs no backfill and no
     * local write path has to learn what this device is called. It is cleared wherever `updatedAt`
     * is stamped — the two describe one event — which is [PageRepository.authored] for whole-row
     * writes and the `updatedBy=NULL` in [PageDao.updateTitle] / [PageDao.touchUpdatedAt] for the
     * single-column ones. Deliberately *not* cleared when a document is merely queued: drawing on a
     * page queues its notebook too, and that notebook was not authored by this edit.
     */
    val updatedBy: String? = null
)


/**
 * A background that is a file rather than a native template, without the row it came from.
 *
 * Shared by [PageDao.getFileBackgrounds] and [NotebookDao.getFileDefaultBackgrounds], which ask the
 * same question of two tables: CouchDB sync needs the set of files the library refers to, so it can
 * tell which of them it has yet to download.
 */
data class FileBackground(
    val background: String,
    val backgroundType: String,
)


/** One notebook's newest page clock — see [PageDao.lastEditedByNotebookFlow]. */
data class NotebookLastEdited(
    val notebookId: String,
    val lastEdited: Date,
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
    ) val images: List<Image>,
    /**
     * Typed text, pictures, recordings and ink groupings — see [Block].
     *
     * Defaulted so callers that build a [PageWithData] by hand — the XOPP and PDF importers —
     * keep compiling and keep meaning what they meant. Joining here rather than querying
     * separately puts blocks on all three paths that need them at once: the editor's page load,
     * the sync push, and the export renderer.
     */
    @Relation(
        parentColumn = "id",
        entityColumn = "pageId",
        entity = Block::class
    ) val blocks: List<Block> = emptyList()
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
    // `updatedBy` is cleared alongside `updatedAt`: the two describe one event, and a rename made
    // here is authored here even if the last write came from a peer. See [Page.updatedBy].
    @Query("UPDATE page SET title=:title, updatedAt=:updatedAt, updatedBy=NULL WHERE id =:pageId")
    suspend fun updateTitle(pageId: String, title: String?, updatedAt: Long)

    // Bump only the edit timestamp, without a read-modify-write of the whole row. updatedAt is
    // stored as epoch millis (Date <-> Long converter), so a Long here matches the column format.
    // `updatedBy` is cleared with it, for the reason given above [updateTitle].
    @Query("UPDATE page SET updatedAt=:updatedAt, updatedBy=NULL WHERE id =:pageId")
    suspend fun touchUpdatedAt(pageId: String, updatedAt: Long)

    // The newest page clock per notebook, for the library's "Last edited" order. Ink no longer
    // bumps the notebook's own envelope (that clock is what the merge decides renames and moves
    // by, and bumping it on every stroke let ink undo them) — so recency is read from the pages,
    // where the ink actually lands.
    @Query(
        "SELECT notebookId, MAX(updatedAt) AS lastEdited FROM page GROUP BY notebookId"
    )
    fun lastEditedByNotebookFlow(): Flow<List<NotebookLastEdited>>

    // One notebook's newest page clock — §6.4's liveness signal (see CouchLocalStore.contentClock):
    // ink no longer moves the notebook's envelope, so "was there work after the deletion" is
    // answered by the pages, where the work actually lands.
    @Query("SELECT MAX(updatedAt) FROM page WHERE notebookId = :notebookId")
    suspend fun newestUpdatedAtInNotebook(notebookId: String): Date?

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
     * Null only for a page that no longer exists — every page that does has a notebook.
     */
    @Query("SELECT notebookId FROM page WHERE id = :pageId")
    suspend fun getNotebookId(pageId: String): String?

    /**
     * The files pages are drawn on rather than the native templates — what CouchDB sync checks for
     * backgrounds whose bytes have not arrived, the way it checks image uris.
     *
     * Distinct, because an imported book is two hundred pages naming one PDF and this is asked on
     * every pull: the question is which files the library refers to, not which pages refer to them.
     */
    @Query(
        "SELECT DISTINCT background, backgroundType FROM page WHERE backgroundType != 'native'"
    )
    suspend fun getFileBackgrounds(): List<FileBackground>

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
     */
    private suspend fun queuePage(pageId: String, notebookId: String) {
        outbox.queue(listOf(CouchDocId.page(pageId), CouchDocId.notebook(notebookId)))
    }

    suspend fun create(page: Page): Long = database.withTransaction {
        val rowId = db.create(authored(page))
        queuePage(page.id, page.notebookId)
        rowId
    }

    /**
     * The row as it should be stored, with authorship resolved.
     *
     * A write made here is authored here, so `updatedBy` goes back to null; a write the sync engine
     * is landing carries the peer's id and has to keep it, or this device would claim every page it
     * receives and the merge would read its own copy as a change worth pushing back — see
     * [Page.updatedBy]. [RemoteApply] is the same marker the outbox already uses to tell the two
     * apart, so there is one definition of "this came from the server" rather than two.
     */
    private suspend fun authored(page: Page): Page =
        if (currentCoroutineContext()[RemoteApply] != null) page else page.copy(updatedBy = null)

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
            db.updateTitle(pageId, title, SyncClock.nowMs())
            // Read back rather than taken from the caller: the rename menu knows a page id and
            // nothing else, and the notebook has to travel with it. Null only if the page was
            // deleted underneath us, in which case there is nothing to send.
            queuePage(pageId, db.getNotebookId(pageId) ?: return@withTransaction)
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
    suspend fun touchUpdatedAt(pageId: String, updatedAt: Long = SyncClock.nowMs()) {
        if (pageId.isEmpty()) return
        database.withTransaction {
            db.touchUpdatedAt(pageId, updatedAt)
            // The page alone — deliberately not [queuePage], whose notebook half exists for
            // writes that move the manifest. An ink save moves nothing on the notebook any
            // more (see PageDataManager.bumpEditTimestamps), and queueing it here would push
            // an unchanged document on every stroke, churning its revision for nothing.
            outbox.queue(listOf(CouchDocId.page(pageId)))
        }
    }

    /** The newest page clock per notebook — the library's "Last edited" order reads this. */
    fun lastEditedByNotebookFlow(): Flow<List<NotebookLastEdited>> = db.lastEditedByNotebookFlow()

    /** One notebook's newest page clock, or null with no pages here — §6.4's liveness signal. */
    suspend fun newestUpdatedAtInNotebook(notebookId: String): Date? =
        db.newestUpdatedAtInNotebook(notebookId)

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

    suspend fun getFileBackgrounds(): List<FileBackground> {
        return db.getFileBackgrounds()
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


    suspend fun update(page: Page) {
        database.withTransaction {
            db.update(authored(page))
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
            // Null only if there was no such page, in which case nothing was removed to publish.
            if (notebookId != null) outbox.queue(CouchDocId.notebook(notebookId))
        }
    }


}

fun Page.getBackgroundType(): BackgroundType {
    return BackgroundType.fromKey(backgroundType)
}

/** Where this page sits in the library, which is wherever its notebook sits. */
suspend fun Page.getParentFolder(bookRepository: BookRepository): String? =
    bookRepository.getById(notebookId)?.parentFolderId
