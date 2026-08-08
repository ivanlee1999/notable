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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
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

    // Per-page write generation, bumped on every set. A scheduled removal captures the generation
    // live at schedule time; the deferred cleanup only fires if it still matches. This is what makes
    // an evict-then-immediately-reselect safe: the reload writes fresh viewport values (bumping the
    // generation) before the stale removal runs, so the removal is dropped instead of erasing them.
    private val generation = ConcurrentHashMap<String, Long>()
    private val generationSeq = AtomicLong(0)

    // Serializes a setter's write+bump against the collector's compare+remove so the two can't
    // interleave (a setter landing between the collector's equality check and its removal would
    // otherwise still lose the fresh value). Held only by setters and the collector — never by
    // [scheduleRemoval], which runs under the cache lock and must not block on a snapshot commit —
    // and never while the cache lock is held, so no lock-ordering inversion. Composition reads take
    // it not at all (the getters are lock-free).
    private val removalLock = Any()

    private data class Removal(val pageId: String, val generation: Long?)

    // Eviction can burst (trimToBudget drops many pages at once) and pruning is pure cleanup, so a
    // generous buffer that drops the oldest on overflow is fine: a leaked height/scroll entry for a
    // page nobody is looking at costs a map slot, and is overwritten if the page comes back.
    private val removals = MutableSharedFlow<Removal>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        scope.launch {
            removals.collect { (pageId, scheduledGeneration) ->
                synchronized(removalLock) {
                    mutateUiState {
                        // Only forget the page if nothing wrote its viewport since the removal was
                        // scheduled. A reload that re-selected the page bumps its generation, so a
                        // stale removal drops out here instead of wiping the fresh height/scroll/zoom.
                        // The lock makes this check-and-remove atomic against a concurrent setter.
                        if (generation[pageId] == scheduledGeneration) {
                            heights.remove(pageId)
                            scrolls.remove(pageId)
                            zooms.remove(pageId)
                            generation.remove(pageId)
                        }
                    }
                }
            }
        }
    }

    /**
     * Forget [pageId]'s viewport state, eventually.
     *
     * The one method that may be called under a lock — it appends to a buffered flow and returns,
     * running no Compose code inline. The removal is applied on this class's own scope, and carries
     * the page's current write [generation] so a reload that re-selects the page before the cleanup
     * runs (bumping the generation) cancels the stale removal rather than losing its fresh values.
     */
    fun scheduleRemoval(pageId: String) {
        removals.tryEmit(Removal(pageId, generation[pageId]))
    }

    /** Stored content height, or null if this page has none yet. Lock-free; safe in composition. */
    fun height(pageId: String): Int? = heights[pageId]

    fun setHeight(pageId: String, height: Int) {
        synchronized(removalLock) {
            mutateUiState {
                heights[pageId] = height
                generation[pageId] = generationSeq.incrementAndGet()
            }
        }
    }

    /**
     * Stored scroll, or null if this page has none yet. A pure read: callers apply their own
     * fallback rather than having one written back here, since writing from composition would race
     * the recomposer.
     */
    fun scroll(pageId: String): Offset? = scrolls[pageId]

    fun setScroll(pageId: String, scroll: Offset) {
        synchronized(removalLock) {
            mutateUiState {
                scrolls[pageId] = scroll
                generation[pageId] = generationSeq.incrementAndGet()
            }
        }
    }

    /**
     * Stored zoom, or null if this page has none yet. Nullable like [scroll] so the caller decides
     * what "never zoomed" means (1f, unzoomed) rather than that default being baked in here.
     */
    fun zoom(pageId: String): Float? = zooms[pageId]

    fun setZoom(pageId: String, zoom: Float) {
        synchronized(removalLock) {
            mutateUiState {
                zooms[pageId] = zoom
                generation[pageId] = generationSeq.incrementAndGet()
            }
        }
    }

    private inline fun <T> mutateUiState(block: () -> T): T = Snapshot.withMutableSnapshot(block)
}
