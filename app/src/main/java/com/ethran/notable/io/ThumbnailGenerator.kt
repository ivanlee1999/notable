package com.ethran.notable.io

import android.content.Context
import com.ethran.notable.data.db.Page
import com.ethran.notable.data.db.PageRepository
import com.ethran.notable.di.IoDispatcher
import com.ethran.notable.editor.utils.PreviewSaveMode
import com.ethran.notable.editor.utils.THUMBNAIL_WIDTH
import com.ethran.notable.editor.utils.getContentPreviewFile
import com.ethran.notable.editor.utils.savePageThumbnail
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

enum class ThumbnailEnsureResult {
    GENERATED,
    UP_TO_DATE,
    PAGE_NOT_FOUND,
    SUPERSEDED,
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ThumbnailGeneratorEntryPoint {
    fun thumbnailGenerator(): ThumbnailGenerator
    fun thumbnailBackfillQueue(): ThumbnailBackfillQueue
    fun pageRepository(): PageRepository
}

/**
 * Responsible for generating and caching thumbnails for pages.
 *
 * A single lock fences invalidation against final publication. Concurrent requests share
 * one render; source clocks and explicit sync invalidation keep the derived cache current.
 */
@Singleton
class ThumbnailGenerator @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val pageContentRenderer: PageContentRenderer,
    private val pageRepository: PageRepository,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    private val log = ShipBook.getLogger("ThumbnailGenerator")
    private val cacheLock = Any()
    private val inFlight = mutableMapOf<String, CompletableDeferred<ThumbnailEnsureResult>>()
    private val generations = mutableMapOf<String, Long>()
    private val invalidated = mutableSetOf<String>()

    private val _thumbnailUpdated = MutableSharedFlow<String>(extraBufferCapacity = 64)

    /**
     * Flow of page IDs whose thumbnails have been updated (generated or refreshed).
     */
    val thumbnailUpdated = _thumbnailUpdated.asSharedFlow()

    /** Content can merge or an asset can arrive without advancing the page's maximum clock. */
    fun invalidate(pageIds: Collection<String>): Set<String> = synchronized(cacheLock) {
        pageIds.filterTo(linkedSetOf()) { id ->
            val file = getContentPreviewFile(context, id)
            val warm = file.exists() || id in inFlight
            if (warm) {
                generations[id] = (generations[id] ?: 0L) + 1
                invalidated += id
                file.delete()
            }
            warm
        }
    }


    /**
     * Checks if a thumbnail is up to date and generates it if necessary.
     * Returns immediately if a generation for the same [pageId] is already in progress.
     */
    suspend fun ensureThumbnail(pageId: String, mode: PreviewSaveMode): ThumbnailEnsureResult {
        val page = withContext(ioDispatcher) { pageRepository.getById(pageId) }
            ?: return ThumbnailEnsureResult.PAGE_NOT_FOUND

        if (!isThumbnailStale(page)) {
            return ThumbnailEnsureResult.UP_TO_DATE
        }

        val marker = CompletableDeferred<ThumbnailEnsureResult>()
        val (existing, generation) = synchronized(cacheLock) {
            inFlight.putIfAbsent(pageId, marker) to (generations[pageId] ?: 0L)
        }
        if (existing != null) return existing.await()

        try {
            val result = generate(page, mode, generation)
            if (result == ThumbnailEnsureResult.GENERATED) _thumbnailUpdated.emit(pageId)
            clearInFlight(pageId, marker)
            marker.complete(result)
            return result
        } catch (t: Throwable) {
            clearInFlight(pageId, marker)
            marker.completeExceptionally(t)
            throw t
        } finally {
            clearInFlight(pageId, marker)
        }
    }

    private fun clearInFlight(pageId: String, marker: CompletableDeferred<ThumbnailEnsureResult>) = synchronized(cacheLock) {
        if (inFlight[pageId] === marker) inFlight.remove(pageId)
    }


    private suspend fun generate(
        page: Page, mode: PreviewSaveMode, generation: Long
    ): ThumbnailEnsureResult {
        val bitmap = pageContentRenderer.renderPageBitmap(
            pageId = page.id,
            target = RenderTarget.Thumbnail(
                maxWidthPx = THUMBNAIL_WIDTH,
                maxHeightPx = null
            )
        )
        var superseded = false
        bitmap.useAndRecycle { rendered ->
            val saved = savePageThumbnail(context, rendered, page.id, mode,
                targetFile = getContentPreviewFile(context, page.id), sourceClock = page.updatedAt.time,
                commit = { temporary, destination -> synchronized(cacheLock) {
                    if ((generations[page.id] ?: 0L) != generation) {
                        superseded = true
                        false
                    } else temporary.renameTo(destination).also { committed ->
                        if (committed) {
                            invalidated.remove(page.id)
                        }
                    }
                } })
            check(saved || superseded) {
                "Could not save content preview"
            }
        }
        if (superseded) return ThumbnailEnsureResult.SUPERSEDED
        log.d("Thumbnail generated for pageId=${page.id}")
        return ThumbnailEnsureResult.GENERATED
    }

    /**
     * A thumbnail is stale iff it predates the page's last edit.
     *
     * The previous test demanded the file be a full minute *newer* than the edit, so every
     * thumbnail counted as stale for a minute after it was written. That was invisible while only
     * a missing file could ask for a render; now that [com.ethran.notable.ui.components.PagePreview]
     * asks on every mount, it would re-render every visible cover on every visit to the Library.
     */
    private suspend fun isThumbnailStale(page: Page): Boolean = withContext(ioDispatcher) {
        synchronized(cacheLock) {
            val thumbFile = getContentPreviewFile(context, page.id)
            page.id in invalidated || !thumbFile.exists() || thumbFile.lastModified() < page.updatedAt.time
        }
    }


    private inline fun android.graphics.Bitmap.useAndRecycle(block: (android.graphics.Bitmap) -> Unit) {
        try {
            block(this)
        } finally {
            if (!isRecycled) {
                recycle()
            }
        }
    }
}
