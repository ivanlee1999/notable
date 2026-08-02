package com.ethran.notable.data

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-page viewport state — the content height a page's strokes need, where it is scrolled to, and
 * how far it is zoomed in.
 *
 * This is **view** state, not cache state, and it is Compose snapshot state: read during
 * composition (the scroll indicator) and written from background coroutines (page load, scroll,
 * zoom, eviction). Writing a snapshot state from a non-composition thread while the recomposer is
 * applying its own snapshot throws "Unsupported concurrent change during composition", so every
 * mutation is committed inside a global mutable snapshot.
 *
 * It lives here rather than in [PageDataManager] because of what committing that snapshot *does*:
 * it runs Compose's global write observers and can wake the recomposer, i.e. it hands control to
 * arbitrary Compose code. When these maps were cache fields, eviction did that while holding the
 * cache monitor that the drawing and selection paths take. Now [PageDataManager] cannot commit a
 * snapshot at all — the only thing it does on eviction is [scheduleRemoval], which just enqueues.
 *
 * **Locking contract:** [scheduleRemoval] is the only method safe to call while holding a lock.
 * Every other method commits a snapshot and must be called with no lock held.
 */
@Singleton
class PageViewportState @Inject constructor() {
    private val heights = mutableStateMapOf<String, Int>()
    private val scrolls = mutableStateMapOf<String, Offset>()

    // Zoom is the one of the three not read from composition today (the editor mirrors it into a
    // StateFlow), but it is written from the same threads and cleared on the same eviction, so it
    // shares the mechanism rather than needing a second map with its own lock.
    private val zooms = mutableStateMapOf<String, Float>()

    // Eviction can burst (trimToBudget drops many pages at once) and pruning is pure cleanup, so a
    // generous buffer that drops the oldest on overflow is fine: a leaked height/scroll entry for a
    // page nobody is looking at costs a map slot, and is overwritten if the page comes back.
    private val removals = MutableSharedFlow<String>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        scope.launch {
            removals.collect { pageId ->
                mutateUiState {
                    heights.remove(pageId)
                    scrolls.remove(pageId)
                    zooms.remove(pageId)
                }
            }
        }
    }

    /**
     * Forget [pageId]'s viewport state, eventually.
     *
     * The one method that may be called under a lock — it appends to a buffered flow and returns,
     * running no Compose code inline. The removal is applied on this class's own scope. Nothing
     * observes the gap: a stale entry for an evicted page is only read again if the page is
     * reloaded, and a reload rewrites both values.
     */
    fun scheduleRemoval(pageId: String) {
        removals.tryEmit(pageId)
    }

    /** Stored content height, or null if this page has none yet. Lock-free; safe in composition. */
    fun height(pageId: String): Int? = heights[pageId]

    fun setHeight(pageId: String, height: Int) {
        mutateUiState { heights[pageId] = height }
    }

    /**
     * Stored scroll, or null if this page has none yet. A pure read: callers apply their own
     * fallback rather than having one written back here, since writing from composition would race
     * the recomposer.
     */
    fun scroll(pageId: String): Offset? = scrolls[pageId]

    fun setScroll(pageId: String, scroll: Offset) {
        mutateUiState { scrolls[pageId] = scroll }
    }

    /**
     * Stored zoom, or null if this page has none yet. Nullable like [scroll] so the caller decides
     * what "never zoomed" means (1f, unzoomed) rather than that default being baked in here.
     */
    fun zoom(pageId: String): Float? = zooms[pageId]

    fun setZoom(pageId: String, zoom: Float) {
        mutateUiState { zooms[pageId] = zoom }
    }

    private inline fun <T> mutateUiState(block: () -> T): T = Snapshot.withMutableSnapshot(block)
}
