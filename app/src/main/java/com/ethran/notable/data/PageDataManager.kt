package com.ethran.notable.data

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.database.SQLException
import android.database.sqlite.SQLiteConstraintException
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.FileObserver
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Offset
import com.ethran.notable.BuildConfig
import com.ethran.notable.SCREEN_HEIGHT
import com.ethran.notable.SCREEN_WIDTH
import com.ethran.notable.data.db.Image
import com.ethran.notable.data.db.Page
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.db.getBackgroundType
import com.ethran.notable.data.events.AppEvent
import com.ethran.notable.data.events.AppEventBus
import com.ethran.notable.data.model.BackgroundType
import com.ethran.notable.data.model.BackgroundType.AutoPdf.getPage
import com.ethran.notable.data.model.BackgroundType.CoverImage
import com.ethran.notable.data.model.BackgroundType.ImageRepeating
import com.ethran.notable.editor.canvas.CanvasEventBus
import com.ethran.notable.editor.utils.saveHQPagePreview
import com.ethran.notable.editor.utils.savePageThumbnail
import com.ethran.notable.io.IN_IGNORED
import com.ethran.notable.io.fileObserverEventNames
import com.ethran.notable.io.loadBackgroundBitmap
import com.ethran.notable.io.waitForFileAvailable
import com.ethran.notable.utils.chunked
import com.ethran.notable.utils.logCallStack
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.launch
import java.io.File
import java.lang.ref.SoftReference
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max


// Save bitmap, to avoid loading from disk every time.
data class CachedBackground(val path: String, val pageNumber: Int, val scale: Float) {
    val id: String = keyOf(path, pageNumber)

    var bitmap: Bitmap? = loadBackgroundBitmap(path, pageNumber, scale)

    // Access-order stamp for the background pool's own LRU eviction (a budget line separate from
    // stroke/image page eviction); bumped whenever this background is set or read.
    var lastAccessSeq: Long = 0L

    fun bitmapBytes(): Long = bitmap?.allocationByteCount?.toLong() ?: 0L

    fun matches(filePath: String, pageNum: Int, targetScale: Float): Boolean {
        return path == filePath && pageNumber == pageNum && scale >= targetScale // Consider valid if our scale is larger
    }

    companion object {
        fun keyOf(path: String, pageNumber: Int): String {
            val md = MessageDigest.getInstance("SHA-1")
            val bytes = md.digest("$path#$pageNumber".toByteArray(Charsets.UTF_8))
            return bytes.take(8).joinToString("") { "%02x".format(it) }
        }
    }
}

/**
 * All in-memory state for a single cached page, owned by [PageDataManager] behind its single
 * [PageDataManager.lock]. Consolidating what used to be ~12 parallel maps into one object per page
 * removes the two-lock/many-map consistency hazard by construction (Phase 1b-1 of
 * docs/plans/crash-handling-plan.md) and is the minimal seed of the multipage plan's `PageStore`
 * owner object.
 *
 * Nullable collections mirror the old "map contains this pageId" semantics: null == not loaded, an
 * empty list == loaded-but-empty. [loaded] is the successor to the old `areListInitialized` check.
 */
internal class PageCacheEntry(val pageId: String) {
    var strokes: MutableList<Stroke>? = null
    var strokesById: HashMap<String, Stroke>? = null
    var images: MutableList<Image>? = null
    var imagesById: HashMap<String, Image>? = null

    // Per-page zoom (was the separate pageZoom map).
    var zoom: Float = 1f

    // Resident bytes of strokes+images only (backgrounds are pooled/counted separately, windowed
    // bitmaps are SoftReferences and excluded). Kept in sync with the manager's running total.
    var sizeBytes: Long = 0L
    var sizeComputed: Boolean = false

    // Pre-load resident estimate that admitted this page; kept for the estimate/actual calibration log.
    var estimateBytes: Long = 0L

    var loadJob: Job? = null
    var backgroundKey: String? = null

    // Whether this page's background is a Native (dotted/lined/blank) type, which has no bitmap to
    // cache — resolved once by [PageDataManager.preLoadBackground]. null = not yet known. Lets
    // [PageDataManager.ensureBackgroundLoaded] skip a pointless reload/DB fetch for native pages.
    var backgroundIsNative: Boolean? = null

    // Windowed screen bitmap (was bitmapCache); SoftReference so ART can reclaim it under pressure.
    var bitmap: SoftReference<Bitmap>? = null

    // Access-order stamp for LRU eviction; bumped on genuine access.
    var lastAccessSeq: Long = 0L

    val loaded: Boolean get() = strokes != null && images != null && sizeComputed
}

// Cache manager companion object
@Singleton
class PageDataManager @Inject constructor(
    private val appRepository: AppRepository,
    private val appEventBus: AppEventBus
) {
    val log = ShipBook.getLogger("PageDataManager")
    private val dataScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Read from every thread (including under [lock] via [currentPage]/[isPinnedLocked]) and written
    // by the suspend setPage; @Volatile so a stale read can't unpin the real current page mid-evict.
    // A full fix (explicit pageId threading) stays the multipage plan's P18.
    @Volatile
    var pageFromDb: Page? = null

    // --- Single owner + single lock for all cached page state (Phase 1b-1) ---
    // Every read/write of [entries], [entriesTotalBytes], [backgroundCache] and the per-entry fields
    // happens under this monitor. It is a plain monitor (not a suspending Mutex) so the hot,
    // non-suspend accessors (drawing, selection) can take it; consequently NO suspend call may run
    // inside a synchronized(lock) block — suspend work (DB reads, cost estimation) is always done
    // outside the lock, and only its results are stored under it.
    private val lock = Any()

    // Insertion-ordered for stable iteration/logging; LRU is driven by [PageCacheEntry.lastAccessSeq].
    private val entries = LinkedHashMap<String, PageCacheEntry>()

    // Running total; invariant (enforced by construction + [assertTotalsLocked]):
    //   entriesTotalBytes == entries.values.sumOf { it.sizeBytes }
    private var entriesTotalBytes = 0L
    private var accessSeq = 0L

    // Shared background pool, deduped by CachedBackground.id. Backgrounds are large PDF/image
    // bitmaps (tens of MB each) and are managed on their OWN budget line ([backgroundCapLocked] /
    // [trimBackgroundsLocked]), independent of stroke/image page eviction — so a page full of cheap
    // strokes is never evicted just because backgrounds are big (the churn Phase 1b originally
    // caused; the plan's 1b-2 "own budget line" made concrete).
    private val backgroundCache = LinkedHashMap<String, CachedBackground>()
    private var bgAccessSeq = 0L

    // Fraction of the ART app-heap that stroke pages + backgrounds may use *together*. The remaining
    // ~30% is deliberately left uncounted for: the windowed canvas bitmap, Compose/UI objects, and
    // the transient stroke copy a page load allocates (the copy that actually OOMed in the P1 repro).
    // Raised from the original ~0.5 combined budget (which held only ~2 backgrounds and thrashed);
    // pushing it higher trades that safety margin — tune down first if the P1 repro OOMs.
    private val heapBudgetFraction = 0.7

    // Stroke/image page budget: [heapBudgetFraction] of the ART app-heap. Governs page (entry)
    // eviction only — backgrounds are NOT counted against it, so cheap stroke pages have huge
    // headroom and are essentially never evicted on a normal notebook. Strokes are user data and get
    // priority: a pathologically large page may use the whole ceiling, evicting backgrounds first.
    private val budget = CacheBudget(
        { Runtime.getRuntime().maxMemory() }, fraction = heapBudgetFraction, reserveBytes = 0L
    )

    // Always leave room for at least one full-screen background bitmap (the pinned current page's),
    // even when strokes dominate the heap.
    private val minBackgroundBytes = 32L * 1024 * 1024

    // observe background file changes
    // fileObservers: filename to observer
    // fileToPages: filename to files with this file
    private val fileObservers = mutableMapOf<String, FileObserver>()
    private val fileToPages = mutableMapOf<String, MutableSet<String>>()
    val invalidateFileFlow = MutableSharedFlow<String>(extraBufferCapacity = 64)

    // Needs to be observable by UI, for scroll bars (read during composition in ScrollIndicator).
    // These are Compose snapshot states, but they are written from background coroutines
    // (page loading, scroll/zoom, cache eviction). Writing a snapshot state from a non-composition
    // thread while the recomposer is applying its own snapshot throws
    // "Unsupported concurrent change during composition". All mutations must therefore go through
    // [mutateUiState], which commits them inside a global mutable snapshot so they are applied
    // atomically and coordinate with composition instead of racing it. They are kept OUT of
    // [PageCacheEntry]/[lock] on purpose: they must be readable lock-free from composition.
    private val pageHigh = mutableStateMapOf<String, Int>()
    private val pageScroll = mutableStateMapOf<String, Offset>()

    /**
     * Applies [block] (which mutates the UI-observable snapshot maps [pageHigh] / [pageScroll])
     * inside a global mutable snapshot. This is required because those maps are read during
     * composition while being written from arbitrary background threads; committing the change as
     * its own snapshot prevents the concurrent-modification crash in the recomposer.
     */
    private inline fun <T> mutateUiState(block: () -> T): T =
        Snapshot.withMutableSnapshot(block)

    private val currentPage: String
        get() = pageFromDb?.id.orEmpty()

    @Volatile
    private var currentPageNumber = -1

    fun getCurrentPageId(): String {
        return currentPage
    }

    val dataLoadingScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val saveTopic = MutableSharedFlow<String>()

    init {
        startFileInvalidationCollector()
    }

    /* ---------------- entry helpers (must hold [lock]) ---------------- */

    private fun getOrCreateEntryLocked(pageId: String): PageCacheEntry =
        entries.getOrPut(pageId) { PageCacheEntry(pageId) }

    private fun touchLocked(entry: PageCacheEntry) {
        entry.lastAccessSeq = ++accessSeq
    }

    /** A page is pinned (never evicted) while it is the current page or has an active load. */
    private fun isPinnedLocked(pageId: String, entry: PageCacheEntry): Boolean =
        pageId == currentPage || entry.loadJob?.isActive == true

    /** Distinct background bitmaps, counted once (dedup pool) — the background budget line. */
    private fun backgroundBytesLocked(): Long =
        backgroundCache.values.sumOf { it.bitmapBytes() }

    /** Total resident bytes for reporting only (stroke/image entries + pooled backgrounds). */
    private fun residentBytesLocked(): Long = entriesTotalBytes + backgroundBytesLocked()

    /**
     * Byte budget for the background pool: whatever is left under the shared heap ceiling
     * ([heapBudgetFraction]) after resident stroke/image bytes, floored so the current page's
     * background always fits. Strokes (resident + the [pendingEntryBytes] of a page about to load)
     * are weighted ×2, so a growing stroke page yields background heap ahead of its own load's
     * transient copy rather than after it. Recomputed on demand — cheap.
     */
    private fun backgroundCapLocked(pendingEntryBytes: Long = 0L): Long {
        val ceiling = (Runtime.getRuntime().maxMemory() * heapBudgetFraction).toLong()
        return (ceiling - 2 * entriesTotalBytes - 2 * pendingEntryBytes)
            .coerceAtLeast(minBackgroundBytes)
    }

    /**
     * Evict least-recently-used pooled backgrounds until the pool fits [backgroundCapLocked]. The
     * current page's background is pinned (never evicted). An evicted background leaves its page's
     * [PageCacheEntry.backgroundKey] intact and is transparently reloaded on demand by
     * [ensureBackgroundLoaded] when that page is next shown — so this is safe for still-resident
     * pages. Must hold [lock].
     */
    private fun trimBackgroundsLocked(pendingEntryBytes: Long = 0L) {
        val cap = backgroundCapLocked(pendingEntryBytes)
        var total = backgroundBytesLocked()
        if (total <= cap) return
        val currentKey = entries[currentPage]?.backgroundKey
        val victims = backgroundCache.values
            .asSequence()
            .filter { it.id != currentKey }
            .sortedBy { it.lastAccessSeq }
            .toList()
        var evicted = 0
        for (bg in victims) {
            if (total <= cap) break
            backgroundCache.remove(bg.id)
            total -= bg.bitmapBytes()
            evicted++
        }
        if (evicted > 0)
            log.d("trimBackgrounds evicted $evicted background(s) (cap=${cap / 1024 / 1024}MB, now=${total / 1024 / 1024}MB)")
    }

    /**
     * Ensures [pageId]'s background bitmap is resident, reloading it if the pool evicted it while the
     * page's strokes stayed cached (backgrounds and pages are on separate budget lines). No-op if
     * already present (just bumps its LRU stamp). Suspends on a reload (PDF/image decode); the check
     * runs before constructing anything so an already-cached background is never re-decoded.
     */
    private suspend fun ensureBackgroundLoaded(pageId: String) {
        if (pageId.isEmpty()) return
        val needsReload = synchronized(lock) {
            val entry = entries[pageId]
            when {
                // Native (dotted/lined/blank) pages have no bitmap background — nothing to reload.
                entry?.backgroundIsNative == true -> false
                else -> {
                    val bg = entry?.backgroundKey?.let { backgroundCache[it] }
                    if (bg?.bitmap != null) {
                        bg.lastAccessSeq = ++bgAccessSeq
                        false
                    } else true
                }
            }
        }
        if (needsReload) {
            log.d("Background not resident for $pageId — reloading")
            preLoadBackground(pageId)
        }
    }

    /**
     * Recompute an entry's stroke/image resident size and apply the delta to [entriesTotalBytes].
     * Costs O(#strokes) (summing `points.size`, each O(1)) — same order as the list copies already
     * done on edit — so it stays cheap even for a 12k-stroke page.
     */
    private fun recomputeEntrySizeLocked(entry: PageCacheEntry) {
        val strokeList = entry.strokes
        val imageList = entry.images
        val strokeCount = strokeList?.size ?: 0
        val pointCount = strokeList?.sumOf { it.points.size.toLong() } ?: 0L
        val imageCount = imageList?.size ?: 0
        val newSize = PageMemoryModel.entryBytes(strokeCount, pointCount, imageCount)
        entriesTotalBytes += newSize - entry.sizeBytes
        entry.sizeBytes = newSize
        entry.sizeComputed = true
        assertTotalsLocked()
    }

    private fun assertTotalsLocked() {
        if (!BuildConfig.DEBUG) return
        val sum = entries.values.sumOf { it.sizeBytes }
        if (sum != entriesTotalBytes)
            log.e("Cache size accounting drift: total=$entriesTotalBytes but sum(entries)=$sum")
    }

    private fun evictCandidatesLocked(): List<EvictCandidate> =
        entries.map { (id, e) -> EvictCandidate(id, e.sizeBytes, isPinnedLocked(id, e), e.lastAccessSeq) }

    /* ---------------- loading ---------------- */

    /**
     * Returns the existing loading Job for the page, or starts and returns a new one. Locking is
     * handled internally; suspend work (cost estimate, neighbor lookup) runs outside the lock.
     *
     * [isPrefetch] pages are opportunistic: they are admitted only into *spare* budget and never
     * evict. The current page ([isPrefetch] = false) is never refused — it evicts unpinned pages to
     * fit, and if it still doesn't fit it loads anyway after evicting everything unpinned and emits
     * an over-budget telemetry event.
     */
    private suspend fun getOrStartLoadingJob(
        pageId: String, bookId: String?, isPrefetch: Boolean
    ): Job? {
        if (pageId.isEmpty()) {
            log.e("Page id is empty")
            logCallStack("PageRepository.getById")
            return null
        }

        // Fast path: an active or already-loaded job needs no DB work.
        synchronized(lock) {
            val e = entries[pageId]
            val job = e?.loadJob
            if (job?.isActive == true) return job
            if (job?.isCompleted == true && e.loaded) return job
        }

        // Estimate the page's resident cost from its compressed blob size (suspend DB) — no lock.
        // null means the estimate failed: the current page then fails open (loads anyway) while a
        // prefetch fails closed (is skipped) — see [decideAdmission] (1b-R4).
        val estimate: Long? = try {
            estimatePageCostBytes(pageId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w("Cost estimate failed for $pageId: ${e.message}")
            null
        }

        // For the current-page path, cancel loads we no longer need (keep current + neighbors).
        if (!isPrefetch && bookId != null) cancelUnnecessaryLoading(pageId, bookId)

        var result: Job? = null
        synchronized(lock) {
            val existing = entries[pageId]?.loadJob
            val loaded = entries[pageId]?.loaded == true
            when {
                existing?.isActive == true -> result = existing
                existing?.isCompleted == true && loaded -> result = existing
                else -> {
                    val cap = budget.capBytes()
                    // Subtract what this page already holds so a partially-resident page (strokes
                    // drawn during load / partial re-load) isn't double-counted (1b-R5).
                    val alreadyResident = entries[pageId]?.sizeBytes ?: 0L
                    val decision = decideAdmission(
                        isPrefetch = isPrefetch,
                        estimateBytes = estimate,
                        alreadyResidentBytes = alreadyResident,
                        // Page eviction is gated on stroke/image bytes only; backgrounds have their
                        // own budget line ([trimBackgroundsLocked]) and never evict a stroke page.
                        residentBytes = entriesTotalBytes,
                        cap = cap,
                        candidates = evictCandidatesLocked(),
                    )
                    when (decision) {
                        AdmissionDecision.Skip -> {
                            log.d("Load of $pageId skipped (prefetch, no spare budget / unknown cost; cap=$cap)")
                        }

                        is AdmissionDecision.Load -> {
                            decision.evict.forEach { removePageLocked(it) }
                            // Shrink the background pool to leave heap for this page's incoming
                            // strokes, so a large current-page load can't OOM against resident
                            // backgrounds. Evicted backgrounds reload on demand.
                            val incoming = ((estimate ?: 0L) - alreadyResident).coerceAtLeast(0L)
                            trimBackgroundsLocked(pendingEntryBytes = incoming)
                            if (decision.overBudget) {
                                log.w("Over-budget load $pageId: est=$estimate cap=$cap — loading anyway")
                                appEventBus.tryEmit(
                                    AppEvent.LogMessage(
                                        reason = "PageDataManager.admission",
                                        message = "Over-budget page load: est=${(estimate ?: 0L) / 1024 / 1024}MB " +
                                            "cap=${cap / 1024 / 1024}MB pageId=$pageId"
                                    )
                                )
                            }
                            val entry = getOrCreateEntryLocked(pageId)
                            entry.estimateBytes = estimate ?: 0L
                            val newJob = dataLoadingScope.launch { loadPageFromDb(this, pageId) }
                            entry.loadJob = newJob
                            touchLocked(entry)
                            result = newJob
                        }
                    }
                }
            }
        }
        return result
    }

    /** Pre-load resident estimate for [pageId] from the summed compressed stroke-blob byte size. */
    private suspend fun estimatePageCostBytes(pageId: String): Long {
        val blobBytes = appRepository.strokeRepository.sumPointsLength(pageId)
        return PageMemoryModel.estimateResidentBytes(blobBytes)
    }

    /**
     * Ensures that the page is loaded; suspends until load is finished.
     */
    suspend fun requestCurrentPageLoadJoin() {
        val bookId = pageFromDb?.notebookId
        log.d("requestCurrentPageLoadJoin($currentPage)")
        getOrStartLoadingJob(currentPage, bookId, isPrefetch = false)?.join()
        // Strokes may be cached from a prior visit while the background pool evicted its bitmap;
        // reload it so the current page never draws blank.
        ensureBackgroundLoaded(currentPage)
    }

    private suspend fun cancelUnnecessaryLoading(pageId: String, bookId: String) {
        log.d("Canceling unnecessary loading of the Page($pageId)")
        val nextPageId =
            appRepository.getNextPageIdFromBookAndPage(pageId = pageId, notebookId = bookId)
        val prevPageId =
            appRepository.getPreviousPageIdFromBookAndPage(pageId = pageId, notebookId = bookId)
        val keep = listOfNotNull(nextPageId, prevPageId, pageId).toSet()

        synchronized(lock) {
            val toCancel = entries
                .filter { (id, e) -> e.loadJob?.isActive == true && id !in keep }
                .keys.toList()
            for (id in toCancel) {
                entries[id]?.loadJob?.cancel()
                log.d("Cancelled unnecessary load for page $id")
                removePageLocked(id)
            }
        }
    }

    suspend fun cacheNeighbors() {
        val bookId = pageFromDb?.notebookId ?: return
        log.d("cacheNeighbors($currentPage)")
        try {
            val nextPageId =
                appRepository.getNextPageIdFromBookAndPage(pageId = currentPage, notebookId = bookId)
            log.d("Caching next page $nextPageId")
            nextPageId?.let {
                getOrStartLoadingJob(it, null, isPrefetch = true)
                ensureNeighborBackground(it)
            }

            val prevPageId =
                appRepository.getPreviousPageIdFromBookAndPage(
                    pageId = currentPage,
                    notebookId = bookId
                )
            log.d("Caching prev page $prevPageId")
            prevPageId?.let {
                getOrStartLoadingJob(it, null, isPrefetch = true)
                ensureNeighborBackground(it)
            }
        } catch (e: CancellationException) {
            log.i("Caching was cancelled: ${e.message}")
        } catch (e: Exception) {
            log.e("Error caching neighbor pages", e)
            appEventBus.tryEmit(
                AppEvent.ActionHint("Error encountered while caching neighbors", 5000)
            )
        }
    }

    /**
     * Warms a neighbor's background only when its page is already resident: in that case its own
     * (prefetch) load short-circuits and won't re-run [preLoadBackground], so if the pool evicted
     * its background we reload it here. A not-yet-loaded neighbor loads its background as part of
     * its fresh load, so we skip it here to avoid decoding the same bitmap twice.
     */
    private suspend fun ensureNeighborBackground(pageId: String) {
        val alreadyLoaded = synchronized(lock) { entries[pageId]?.loaded == true }
        if (alreadyLoaded) ensureBackgroundLoaded(pageId)
    }

    /**
     * Requests that the given page is loaded, but doesn't wait.
     * If already loading, is a no-op. Loaded opportunistically (prefetch) into spare budget only.
     */
    fun requestPageLoad(pageId: String) {
        dataLoadingScope.launch {
            getOrStartLoadingJob(pageId, null, isPrefetch = true)
        }
    }

    private suspend fun preLoadBackground(pageId: String) {
        val pageDataFromDb = appRepository.pageRepository.getById(pageId)
        if (pageDataFromDb == null) {
            log.e("Background not found for page $pageId")
            return
        }
        val backgroundType = pageDataFromDb.getBackgroundType()
        val background = pageDataFromDb.background
        val pageNumber = when (backgroundType) {
            is BackgroundType.Pdf -> backgroundType.page
            is BackgroundType.AutoPdf -> backgroundType.getPage(
                appRepository, pageDataFromDb.notebookId, pageId
            ) ?: return

            // Native backgrounds (dotted/lined/blank) have no bitmap to cache; record that so
            // ensureBackgroundLoaded doesn't keep trying to reload a nonexistent background.
            BackgroundType.Native -> {
                synchronized(lock) { entries[pageId]?.backgroundIsNative = true }
                return
            }

            BackgroundType.Image, ImageRepeating, CoverImage -> -1
        }
        synchronized(lock) { entries[pageId]?.backgroundIsNative = false }
        val value = CachedBackground(background, pageNumber, 1f)
        log.i("Preloaded background: $value")
        setBackground(pageId, value)
    }

    private suspend fun loadPageFromDb(coroutineScope: CoroutineScope, pageId: String) {
        // This coroutine's own Job (identical to the entry's loadJob set in getOrStartLoadingJob).
        val myJob = coroutineScope.coroutineContext[Job]
        try {
            log.d("Loading page $pageId")
            preLoadBackground(pageId)

            // Suspend I/O happens OUTSIDE the lock.
            val pageWithData = appRepository.pageRepository.getWithDataById(pageId)
            if (pageWithData == null) {
                log.w("Missing page Data.")
                appEventBus.tryEmit(AppEvent.ActionHint("Missing Page Data", 2000))
                return
            }

            synchronized(lock) {
                // Commit only if this load still owns the entry. Cancellation is cooperative and
                // the commit block has no suspension point, so a cancel that lands after
                // getWithDataById returned would otherwise let this block re-create a
                // fully-populated "zombie" entry that the cancel path already removed (1b-R1).
                // Cancel+removePageLocked are always paired atomically under [lock], so a mismatched
                // (or absent) loadJob means this result is stale — discard it.
                val entry = entries[pageId]
                if (entry == null || entry.loadJob !== myJob) {
                    log.d("Discarding stale/cancelled load result for $pageId")
                    return
                }
                // Join with any strokes/images drawn during loading (append, don't replace).
                appendStrokesLocked(entry, pageWithData.strokes)
                appendImagesLocked(entry, pageWithData.images)
                entry.strokesById = HashMap(entry.strokes!!.associateBy { it.id })
                entry.imagesById = HashMap(entry.images!!.associateBy { it.id })
                recomputeEntrySizeLocked(entry)
                touchLocked(entry)
                logEstimateVsActualLocked(entry)
            }
            recomputeHeight(pageId)
        } catch (e: CancellationException) {
            log.w("Loading of page $pageId was cancelled.")
            if (!validatePageDataLoaded(pageId)) removePage(pageId)
            throw e  // rethrow cancellation
        } finally {
            log.d("Loaded page $pageId")
        }
    }

    // Copy-on-write: build a new list and swap the reference under [lock] instead of mutating in
    // place. A reader that took the old reference from getStrokes/getImages (which run without the
    // lock, on the drawing thread) then iterates an immutable snapshot — no ConcurrentModification
    // when a join-during-load appends here (1b-R2).
    private fun appendStrokesLocked(entry: PageCacheEntry, newStrokes: List<Stroke>) {
        val existing = entry.strokes
        entry.strokes = if (existing == null) newStrokes.toMutableList()
        else {
            log.d("Joining strokes drawn during page loading and existing strokes")
            ArrayList<Stroke>(existing.size + newStrokes.size).apply {
                addAll(existing); addAll(newStrokes)
            }
        }
    }

    private fun appendImagesLocked(entry: PageCacheEntry, newImages: List<Image>) {
        val existing = entry.images
        entry.images = if (existing == null) newImages.toMutableList()
        else {
            log.d("Joining images drawn during page loading and existing images")
            ArrayList<Image>(existing.size + newImages.size).apply {
                addAll(existing); addAll(newImages)
            }
        }
    }

    /** Emits the estimate/actual ratio used to calibrate [PageMemoryModel.BLOB_EXPANSION_K]. */
    private fun logEstimateVsActualLocked(entry: PageCacheEntry) {
        val est = entry.estimateBytes
        if (est > 0) {
            val ratio = entry.sizeBytes.toDouble() / est
            log.i(
                "Cache estimate/actual ${entry.pageId}: est=${est / 1024}KB " +
                    "actual=${entry.sizeBytes / 1024}KB ratio=${"%.2f".format(ratio)} " +
                    "(ratio>1 ⇒ K too small)"
            )
        }
    }

    /**
     * - Verifies loaded data presence and job consistency under [lock].
     * - If inconsistent (a completed/cancelled job but partial data, or vice versa), logs it,
     *   schedules a clear+reload, and returns false.
     */
    fun validatePageDataLoaded(pageId: String): Boolean {
        synchronized(lock) {
            val entry = entries[pageId]
            val job = entry?.loadJob
            if (job?.isActive == true) {
                log.d("isPageLoaded: Still loading page($pageId).")
                return false
            }
            val jobDone = job?.isCompleted ?: false
            val dataLoaded = entry?.loaded == true

            if (job != null && dataLoaded != jobDone) {
                appEventBus.tryEmit(
                    AppEvent.LogMessage(
                        reason = "PageDataManager.validatePageDataLoaded",
                        message = "Inconsistent state for page($pageId): dataLoaded=$dataLoaded, jobDone=$jobDone, job=$job, trying to fix."
                    )
                )
                dataLoadingScope.launch {
                    synchronized(lock) {
                        entries[pageId]?.loadJob?.cancel()
                        removePageLocked(pageId)
                    }
                }
                return false
            }
            return dataLoaded
        }
    }

    fun collectAndPersistBitmapsBatch(
        context: Context, scope: CoroutineScope
    ) {
        scope.launch(Dispatchers.IO) {
            saveTopic.buffer(10).chunked(1000).collect { pageIdBatch ->
                val uniquePageIds = pageIdBatch.distinct()
                if (uniquePageIds.isEmpty()) return@collect

                log.i("Persisting batch of bitmaps for pages: $uniquePageIds")

                for (pageId in uniquePageIds) {
                    val entry = synchronized(lock) { entries[pageId] }
                    val bitmap = entry?.bitmap?.get()
                    val currentZoomLevel = entry?.zoom
                    val currentScroll = pageScroll[pageId]

                    if (bitmap == null || bitmap.isRecycled) {
                        log.e("Page $pageId: Bitmap is recycled/null — cannot persist it")
                        continue
                    }

                    scope.launch(Dispatchers.IO) {
                        saveHQPagePreview(context, bitmap, pageId, currentScroll, currentZoomLevel)
                        savePageThumbnail(context, bitmap, pageId)
                    }
                }
            }
        }
    }

    /*
     * Sets current page, and starts loading it from db.
     */
    suspend fun setPage(pageId: String) {
        pageFromDb = appRepository.pageRepository.getById(pageId)
        if (pageFromDb == null) {
            log.e("Page($pageId) not found;")
            appEventBus.tryEmit(AppEvent.ActionHint("Page not found", 2000))
            currentPageNumber = -1
            return
        }
        pageFromDb?.notebookId?.let { notebookId ->
            currentPageNumber = appRepository.getPageNumber(notebookId, pageId)
        }
        synchronized(lock) { entries[pageId]?.let { touchLocked(it) } }
    }

    suspend fun refreshPageFromDb(pageId: String) {
        pageFromDb = appRepository.pageRepository.getById(pageId)
        log.i("Refresh current page, background: ${pageFromDb?.background}")
    }

    fun getCachedBitmap(pageId: String): Bitmap? = synchronized(lock) {
        entries[pageId]?.bitmap?.get()?.takeIf { !it.isRecycled && it.isMutable }
    }

    fun cacheBitmap(pageId: String, bitmap: Bitmap) = synchronized(lock) {
        getOrCreateEntryLocked(pageId).bitmap = SoftReference(bitmap)
    }

    fun getPageHeight(pageId: String): Int? = pageHigh[pageId]
    fun setPageHeight(pageId: String, height: Int) {
        mutateUiState { pageHigh[pageId] = height }
    }

    fun recomputeHeight(pageId: String): Int {
        synchronized(lock) {
            val list = entries[pageId]?.strokes
            if (list.isNullOrEmpty()) return SCREEN_HEIGHT
            val newHeight = max(list.maxOf { it.bottom }.plus(50).toInt(), SCREEN_HEIGHT)
            mutateUiState { pageHigh[pageId] = newHeight }
            return newHeight
        }
    }

    fun computeWidth(pageId: String): Int {
        synchronized(lock) {
            val list = entries[pageId]?.strokes
            if (list.isNullOrEmpty()) return SCREEN_WIDTH
            return max(list.maxOf { it.right }.plus(50).toInt(), SCREEN_WIDTH)
        }
    }

    /**
     * Returns the stored scroll for [pageId], or the page's persisted default if none is cached yet.
     * Pure read: never mutates [pageScroll] (writing from composition would race the recomposer).
     */
    fun getPageScroll(pageId: String): Offset {
        return pageScroll[pageId] ?: Offset(0f, pageFromDb?.scroll?.toFloat() ?: 0f)
    }

    fun setPageScroll(pageId: String, scroll: Offset) {
        mutateUiState { pageScroll[pageId] = scroll }
    }

    fun getPageZoom(pageId: String): Float = synchronized(lock) { entries[pageId]?.zoom ?: 1f }
    fun setPageZoom(pageId: String, zoom: Float) = synchronized(lock) {
        getOrCreateEntryLocked(pageId).zoom = zoom
    }


    fun isTransformationAllowedForCurrentPage(): Boolean {
        return when (pageFromDb?.backgroundType) {
            "native", null -> true
            "coverImage" -> false
            else -> true
        }
    }

    fun getCurrentPageNumber(): Int {
        if (currentPageNumber == -1)
            log.d("Current page number: $currentPageNumber")
        return currentPageNumber
    }

    fun getStrokes(pageId: String): List<Stroke> = synchronized(lock) {
        entries[pageId]?.strokes ?: emptyList()
    }

    fun setStrokes(pageId: String, strokes: List<Stroke>) = synchronized(lock) {
        val entry = getOrCreateEntryLocked(pageId)
        entry.strokes = strokes.toMutableList()
        recomputeEntrySizeLocked(entry)
    }

    fun getStrokesById(pageId: String): HashMap<String, Stroke> = synchronized(lock) {
        entries[pageId]?.strokesById ?: hashMapOf()
    }

    fun getImages(pageId: String): List<Image> = synchronized(lock) {
        entries[pageId]?.images ?: emptyList()
    }

    fun setImages(pageId: String, images: List<Image>) = synchronized(lock) {
        val entry = getOrCreateEntryLocked(pageId)
        entry.images = images.toMutableList()
        recomputeEntrySizeLocked(entry)
    }

    fun indexStrokes(scope: CoroutineScope, pageId: String) {
        scope.launch {
            synchronized(lock) {
                val list = entries[pageId]?.strokes ?: return@synchronized
                entries[pageId]?.strokesById = HashMap(list.associateBy { it.id })
            }
        }
    }

    fun indexImages(scope: CoroutineScope, pageId: String) {
        scope.launch {
            synchronized(lock) {
                val list = entries[pageId]?.images ?: return@synchronized
                entries[pageId]?.imagesById = HashMap(list.associateBy { it.id })
            }
        }
    }

    fun getStrokes(strokeIds: List<String>, pageId: String): List<Stroke?> = synchronized(lock) {
        val byId = entries[pageId]?.strokesById
        strokeIds.map { byId?.get(it) }
    }

    fun getImage(imageId: String, pageId: String): Image? = synchronized(lock) {
        entries[pageId]?.imagesById?.get(imageId)
    }

    fun getImages(imageIds: List<String>, pageId: String): List<Image?> = synchronized(lock) {
        val byId = entries[pageId]?.imagesById
        imageIds.map { byId?.get(it) }
    }


    // Assuming Rect uses 'left', 'top', 'right', 'bottom'
    fun getImagesInRectangle(inPageCoordinates: Rect, id: String): List<Image>? {
        synchronized(lock) {
            if (!validatePageDataLoaded(id)) return null
            val entry = entries[id] ?: return emptyList()
            touchLocked(entry)
            val imageList = entry.images ?: return emptyList()
            return imageList.filter { image ->
                image.x < inPageCoordinates.right && (image.x + image.width) > inPageCoordinates.left && image.y < inPageCoordinates.bottom && (image.y + image.height) > inPageCoordinates.top
            }
        }
    }

    fun getStrokesInRectangle(inPageCoordinates: Rect, id: String): List<Stroke>? {
        synchronized(lock) {
            if (!validatePageDataLoaded(id)) return null
            val entry = entries[id] ?: return emptyList()
            touchLocked(entry)
            val strokeList = entry.strokes ?: return emptyList()
            return strokeList.filter { stroke ->
                stroke.right > inPageCoordinates.left && stroke.left < inPageCoordinates.right && stroke.bottom > inPageCoordinates.top && stroke.top < inPageCoordinates.bottom
            }
        }
    }

    /**
     * Runs a DB content-write on [dataScope], catching SQL errors so a storage/device failure
     * (e.g. SQLiteDiskIOException on endTransaction — Crash #4) is logged with context instead of
     * escaping the coroutine and killing the process. For now this only logs and no-ops: the
     * in-memory state is untouched, so the next successful write re-persists it.
     */
    private fun launchDbWrite(op: String, block: suspend () -> Unit) {
        dataScope.launch {
            try {
                block()
            } catch (e: SQLException) {
                log.e(
                    "DB write '$op' failed on page $currentPage " +
                            "(notebook ${pageFromDb?.notebookId}): ${e.message}", e
                )
            }
        }
    }

    fun updateStrokesInDb(strokes: List<Stroke>) {
        launchDbWrite("updateStrokes(${strokes.size})") {
            appRepository.strokeRepository.update(strokes)
            bumpEditTimestamps()
        }
    }

    fun updateImagesInDb(images: List<Image>) {
        launchDbWrite("updateImages(${images.size})") {
            appRepository.imageRepository.update(images)
            bumpEditTimestamps()
        }
    }

    fun saveStrokesToDb(strokes: List<Stroke>) {
        launchDbWrite("saveStrokes(${strokes.size})") {
            try {
                appRepository.strokeRepository.create(strokes)
            } catch (_: SQLiteConstraintException) {
                // There were some rare bugs when strokes weren't unique when inserting from history
                // I'm not sure if it's still a problem, let's just show the message
                appEventBus.tryEmit(
                    AppEvent.LogMessage(
                        reason = "saveStrokesToPersistLayer",
                        message = "Attempted to create strokes that already exist"
                    )
                )
                appRepository.strokeRepository.update(strokes)
            }
            bumpEditTimestamps()
        }
    }

    fun saveImagesToDb(images: List<Image>) {
        launchDbWrite("saveImages(${images.size})") {
            appRepository.imageRepository.create(images)
            bumpEditTimestamps()
        }
    }

    fun removeStrokesFromDb(strokes: List<String>) {
        launchDbWrite("removeStrokes(${strokes.size})") {
            appRepository.strokeRepository.deleteAll(strokes)
            bumpEditTimestamps()
        }
    }

    fun removeImagesFromDb(images: List<String>) {
        launchDbWrite("removeImages(${images.size})") {
            appRepository.imageRepository.deleteAll(images)
            bumpEditTimestamps()
        }
    }

    // Bump the edit timestamps after a content write on the current page. The page timestamp is
    // the per-page dirty signal (for incremental upload); the notebook timestamp drives the
    // per-notebook sync Upload/Download decision. Both advance together on any stroke/image edit.
    private suspend fun bumpEditTimestamps() {
        val pageId = pageFromDb?.id
        if (!pageId.isNullOrEmpty()) {
            appRepository.pageRepository.touchUpdatedAt(pageId)
        }
        val notebookId = pageFromDb?.notebookId ?: return
        val notebook = appRepository.bookRepository.getById(notebookId) ?: return
        appRepository.bookRepository.update(notebook)
    }

    fun setScrollInDb() {
        launchDbWrite("scroll") {
            appRepository.pageRepository.updateScroll(
                currentPage,
                getPageScroll(currentPage).y.toInt()
            )
        }
    }

    fun getBackgroundType(): BackgroundType? {
        return pageFromDb?.getBackgroundType()
    }

    suspend fun getPageUpdatedAt(pageId: String): Long? {
        return appRepository.pageRepository.getById(pageId)?.updatedAt?.time
    }

    fun getBackgroundName(): String {
        return pageFromDb?.background ?: "blank"
    }

    fun setCurrentBackground(background: CachedBackground) {
        setBackground(currentPage, background)
    }

    fun setBackground(pageId: String, background: CachedBackground) {
        dataScope.launch {
            // we assume that the pageId is in current notebook.
            val observeBg = appRepository.isObservable(pageFromDb?.notebookId)

            synchronized(lock) {
                // Merge/upgrade the shared pool: keep the higher-scale (higher-quality) bitmap.
                val existing = backgroundCache[background.id]
                if (existing == null || background.scale > existing.scale) {
                    background.lastAccessSeq = ++bgAccessSeq
                    backgroundCache[background.id] = background
                    log.d("Cached background set: id=${background.id} scale=${background.scale}")
                } else {
                    existing.lastAccessSeq = ++bgAccessSeq
                    log.d("Cached background exists with equal/higher scale; reusing id=${existing.id} scale=${existing.scale}")
                }

                // Link this page to the background key.
                getOrCreateEntryLocked(pageId).backgroundKey = background.id

                if (observeBg) observeBackgroundFile(pageId, background.path)

                // Keep the pool within its own budget line right after every addition.
                trimBackgroundsLocked()
            }
        }
    }

    /**
     * Retrieves the cached background for the current page, or a default empty [CachedBackground]
     * if none is linked (prevents null-pointer crashes downstream).
     */
    fun getCurrentBackground(): CachedBackground {
        return synchronized(lock) {
            val key = entries[currentPage]?.backgroundKey
            val bg = if (key != null) backgroundCache[key] else null
            bg?.let { it.lastAccessSeq = ++bgAccessSeq }
            log.d("Background for page $currentPage (no. $currentPageNumber): $bg")
            bg ?: CachedBackground("", 0, 1.0f)
        }
    }

    suspend fun getPageNumberInCurrentNotebook(pageId: String): Int {
        val pageNumber =
            appRepository.getPageNumber(pageFromDb?.notebookId!!, pageId)
        log.d("Page number for page($pageNumber): $pageId")
        return pageNumber
    }

    /**
     * Start observing a background file for changes.
     * Registers the pageId to the file, and launches a FileObserver if not already present.
     */
    private fun observeBackgroundFile(pageId: String, filePath: String) {
        synchronized(fileObservers) {
            fileToPages.getOrPut(filePath) { mutableSetOf() }.add(pageId)
            if (fileObservers.containsKey(filePath)) return // Already observing this file

            val file = File(filePath)
            if (!file.exists() || !file.canRead()) {
                log.w("Cannot observe background file: $filePath does not exist or is not readable")
                return
            }
            val mask = (FileObserver.CREATE or
                    FileObserver.DELETE or
                    FileObserver.DELETE_SELF or
                    FileObserver.CLOSE_WRITE or
                    FileObserver.MOVED_TO or
                    FileObserver.MOVE_SELF)

            // Launch a FileObserver for this file
            val observer = object : FileObserver(file, mask) {
                override fun onEvent(event: Int, path: String?) {
                    dataLoadingScope.launch {
                        if (event == IN_IGNORED)
                            return@launch
                        val eventString = fileObserverEventNames(event)

                        log.d("Background file changed: $filePath [event=$eventString]")
                        if (event == DELETE || event == DELETE_SELF) {
                            log.d("Background file deleted.")
                            synchronized(fileObservers) {
                                fileObservers.remove(filePath)?.stopWatching()
                            }
                            if (!waitForFileAvailable(filePath)) {
                                log.w("File changed, but does not exist: $filePath")
                                appEventBus.tryEmit(
                                    AppEvent.ActionHint("Background does not exist", 3000)
                                )
                                return@launch
                            } else
                                observeBackgroundFile(pageId, filePath)
                        }

                        invalidateFileFlow.emit(filePath)
                    }
                }
            }
            observer.startWatching()
            fileObservers[filePath] = observer
        }
    }


    /**
     * Starts the collector to process file invalidation events.
     * Uses chunked batching to process all events received in a 10ms window.
     */
    fun startFileInvalidationCollector() {
        dataLoadingScope.launch {
            invalidateFileFlow.chunked(10) // Batch events every 20ms
                .collect { filePathBatch ->
                    val uniqueFilePaths = filePathBatch.distinct()
                    if (uniqueFilePaths.isEmpty()) return@collect
                    log.i("Persisting batch of fileChanges: $uniqueFilePaths")
                    for (filePath in uniqueFilePaths) {
                        // Invalidate all pages that use this file
                        fileToPages[filePath]?.forEach { pid ->
                            invalidateBackground(pid)
                            if (pid == currentPage) {
                                CanvasEventBus.forceUpdate.emit(null)
                                appEventBus.tryEmit(
                                    AppEvent.ActionHint("Background file changed", 4000)
                                )
                            }
                        }
                    }
                }
        }
    }

    /**
     * Stop observing the background file for the given page.
     * Cleans up observers if no more pages are using the file.
     */
    private fun stopObservingBackground(pageId: String) {
        synchronized(fileObservers) {
            val iterator = fileToPages.entries.iterator()
            while (iterator.hasNext()) {
                val (filePath, pageIds) = iterator.next()
                if (pageIds.remove(pageId) && pageIds.isEmpty()) {
                    fileObservers.remove(filePath)?.stopWatching()
                    iterator.remove()
                }
            }
        }
    }

    private fun invalidateBackground(pageId: String) {
        synchronized(lock) {
            // Remove page->bg link and drop the pooled bg if no other page references it.
            val entry = entries[pageId]
            val key = entry?.backgroundKey
            entry?.backgroundKey = null
            if (key != null) {
                val stillUsed = entries.values.any { it.backgroundKey == key }
                if (!stillUsed) {
                    backgroundCache.remove(key)
                    log.d("Invalidated background cache key=$key (no remaining pages)")
                } else {
                    log.d("Unlinked page $pageId from background key=$key (still used elsewhere)")
                }
            }
            entry?.bitmap = null // windowed bitmap for this page stays per-page
            log.d("Invalidated background cache for page: $pageId")
        }
    }

    fun onExit(targetPageId: String, windowedBitmap: Bitmap, scope: CoroutineScope) {
        log.i("Page exit, is page loaded: ${validatePageDataLoaded(targetPageId)}")
        if (validatePageDataLoaded(targetPageId)) {
            cacheBitmap(targetPageId, windowedBitmap)
            scope.launch {
                saveTopic.emit(targetPageId)
            }
            recomputeHeight(targetPageId)
            // Size accounting is kept current on every setStrokes/setImages, so no recompute here.
        }
    }

    /** --- cleaning and memory management ---- **/

    /**
     * Removes a page and all its resources; subtracts its bytes from the running total. Refuses to
     * remove the current page. Must hold [lock].
     */
    private fun removePageLocked(pageId: String): Boolean {
        if (pageId == currentPage) {
            appEventBus.tryEmit(
                AppEvent.LogMessage(
                    reason = "PageDataManager.removePage",
                    message = "Cannot remove current page, there is a bug in code"
                )
            )
            return false
        }
        log.d("Removing page $pageId")
        val entry = entries.remove(pageId)
        if (entry != null) {
            entriesTotalBytes -= entry.sizeBytes
            // Unlink and possibly drop the pooled background.
            val key = entry.backgroundKey
            if (key != null && entries.values.none { it.backgroundKey == key }) {
                backgroundCache.remove(key)
            }
        }
        // pageHigh/pageScroll are UI-observable snapshot state: remove them in a snapshot.
        mutateUiState {
            pageHigh.remove(pageId)
            pageScroll.remove(pageId)
        }
        stopObservingBackground(pageId)
        assertTotalsLocked()
        return true
    }

    /** Public entry point; there are currently no external callers, kept for internal/test use. */
    fun removePage(pageId: String): Boolean = synchronized(lock) { removePageLocked(pageId) }

    /**
     * Cancels and removes a currently loading page.
     */
    fun cancelLoadingPage(pageId: String) {
        dataLoadingScope.launch {
            log.d("Cancelling loading page: pageId=$pageId")
            synchronized(lock) {
                val entry = entries[pageId]
                if (entry?.loadJob?.isActive == true) {
                    entry.loadJob?.cancel()
                    removePageLocked(pageId)
                }
            }
        }
    }

    /**
     * Cancels and removes all currently loading pages, optionally ignoring [ignoredPageIds].
     */
    fun cancelLoadingPages(ignoredPageIds: List<String> = listOf()) {
        dataLoadingScope.launch {
            log.d("Cancelling loading pages, ignoring: $ignoredPageIds")
            synchronized(lock) {
                val toCancel = entries
                    .filter { (id, e) -> e.loadJob?.isActive == true && id !in ignoredPageIds }
                    .keys.toList()
                for (id in toCancel) {
                    entries[id]?.loadJob?.cancel()
                    log.d("Cancelled job for page $id")
                    removePageLocked(id)
                }
            }
        }
    }

    fun clearAllPages() {
        dataLoadingScope.launch {
            log.d("Clearing loaded pages")
            synchronized(lock) {
                for (id in entries.keys.toList()) {
                    entries[id]?.loadJob?.cancel()
                    removePageLocked(id)
                }
            }
        }
    }

    /** Resident MB currently held by the page cache (strokes/images + pooled backgrounds). */
    fun getUsedMemory(): Int = synchronized(lock) {
        (residentBytesLocked() / (1024 * 1024)).toInt()
    }

    /**
     * Evict unpinned pages, least-recently-used first, until the counted resident bytes fit the
     * heap budget. Replaces the old count-based `reduceCache(20)`.
     */
    fun trimToBudget() {
        synchronized(lock) {
            val cap = budget.capBytes()
            // Page eviction is gated on stroke/image bytes only; backgrounds are trimmed on their
            // own budget line below. On a normal notebook strokes are tiny, so this evicts nothing
            // and the current page + both neighbors stay resident (no churn).
            val victims = selectEvictions(evictCandidatesLocked(), entriesTotalBytes, 0L, cap)
            if (victims.isNotEmpty())
                log.d("trimToBudget evicting ${victims.size} page(s): $victims (cap=${cap / 1024 / 1024}MB)")
            victims.forEach { removePageLocked(it) }
            trimBackgroundsLocked()
            assertTotalsLocked()
        }
    }

    /** Drop every unpinned page (used on real device-memory pressure / backgrounding). */
    private fun dropAllUnpinned() {
        synchronized(lock) {
            val victims = entries.filter { (id, e) -> !isPinnedLocked(id, e) }.keys.toList()
            log.d("dropAllUnpinned evicting ${victims.size} page(s)")
            victims.forEach { removePageLocked(it) }
        }
    }

    fun registerComponentCallbacks(context: Context) {
        context.registerComponentCallbacks(object : ComponentCallbacks2 {
            @Suppress("DEPRECATION")
            override fun onTrimMemory(level: Int) {
                log.d("onTrimMemory: $level, usedMB: ${getUsedMemory()}")
                when (level) {
                    // Backgrounded / fully trimmed → drop everything we can.
                    ComponentCallbacks2.TRIM_MEMORY_COMPLETE,
                    ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> dropAllUnpinned()
                    // Any other pressure level → shrink back to the heap budget.
                    else -> trimToBudget()
                }
            }

            override fun onConfigurationChanged(newConfig: Configuration) {
                // No action needed for config changes
            }

            @Deprecated("Deprecated in Java")
            override fun onLowMemory() {
                dropAllUnpinned()
            }
        })
    }
}
