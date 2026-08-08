package com.ethran.notable.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import java.util.Date
import javax.inject.Inject

/**
 * Per-page sync bookkeeping, the page-level companion to [NotebookSyncState]. One row records what a
 * page looked like the last time it *committed* a sync, so the next sync can skip pages that have not
 * changed on either side: a page is uploaded only when its local edit is newer than
 * [syncedLocalUpdatedAt], and downloaded only when its remote ETag differs from [remoteEtag].
 *
 * Deliberately has **no** foreign key to [Page] (same reasoning as [NotebookSyncState]): the row
 * describes the last committed sync and is managed by the sync commit transaction, independent of the
 * local page row's lifecycle. Rows are written **only** inside the notebook's commit transaction
 * (after the manifest is published on upload / the atomic page swap on download); a killed sync
 * writes none, so the next sync re-transfers the same pages — "skip" stays as trustworthy as the
 * notebook badge.
 */
@Entity(tableName = "page_sync_state")
data class PageSyncState(
    @PrimaryKey val pageId: String,
    @ColumnInfo(index = true) val notebookId: String,
    /**
     * The page file's server ETag at the last committed sync, **in the server's own spelling**
     * (`W/` prefix and quotes intact). Read it through `ETag.parse` and compare with `ETag.matches`,
     * never `==`: the same validator arrives spelled differently from a PUT header and a PROPFIND,
     * so spelling equality is not content equality. A row may also hold a bare, unquoted value,
     * which `ETag` accepts and treats as strong.
     */
    val remoteEtag: String? = null,
    /**
     * The **change anchor**: the value `Page.updatedAt` had at that commit. Compared against the
     * page's current `updatedAt` to answer "edited since we last synced?" — both sides are the same
     * local edit clock, so equality means unchanged.
     */
    val syncedLocalUpdatedAt: Date,
    /** Wall-clock time of the last committed sync. Informational (log/UI); never compared. */
    val lastSyncedAt: Date,
)

@Dao
interface PageSyncStateDao {
    @Query("SELECT * FROM page_sync_state WHERE notebookId = :notebookId")
    suspend fun getByNotebook(notebookId: String): List<PageSyncState>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<PageSyncState>)

    @Query("DELETE FROM page_sync_state WHERE pageId IN (:pageIds)")
    suspend fun deleteByIds(pageIds: List<String>)

    @Query("DELETE FROM page_sync_state WHERE notebookId = :notebookId")
    suspend fun deleteByNotebook(notebookId: String)
}

class PageSyncStateRepository @Inject constructor(
    private val dao: PageSyncStateDao
) {
    suspend fun getByNotebook(notebookId: String): List<PageSyncState> = dao.getByNotebook(notebookId)
    suspend fun upsertAll(rows: List<PageSyncState>) {
        if (rows.isNotEmpty()) dao.upsertAll(rows)
    }

    suspend fun deleteByIds(pageIds: List<String>) {
        if (pageIds.isNotEmpty()) dao.deleteByIds(pageIds)
    }

    suspend fun deleteByNotebook(notebookId: String) = dao.deleteByNotebook(notebookId)
}
