package com.ethran.notable.sync

import com.ethran.notable.data.db.Page
import com.ethran.notable.data.db.PageSyncState

/**
 * The pure page-level upload decision: given a notebook's current pages and the
 * `page_sync_state` rows from its last committed sync, which pages need re-uploading. No I/O —
 * table-driven tests mirror [NotebookSyncPlanner].
 *
 * A page is **dirty** iff we have no committed sync row for it (never uploaded, or the last upload
 * crashed before commit) or it was edited after that row's anchor. Because a committed upload stores
 * `syncedLocalUpdatedAt = page.updatedAt`, an untouched page compares exactly equal and is skipped;
 * any later edit bumps `updatedAt` strictly past the anchor and re-dirties it — so a strict `>` is
 * correct and no clock tolerance is needed (both sides are the *same* local clock's values).
 */
object PageSyncSelector {
    fun selectDirtyPages(
        pages: List<Page>,
        rowsByPageId: Map<String, PageSyncState>,
    ): List<Page> = pages.filter { page ->
        val row = rowsByPageId[page.id] ?: return@filter true
        page.updatedAt.time > row.syncedLocalUpdatedAt.time
    }
}
