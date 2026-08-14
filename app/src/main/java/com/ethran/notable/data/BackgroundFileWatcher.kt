package com.ethran.notable.data

import android.os.FileObserver
import com.ethran.notable.data.events.AppEvent
import com.ethran.notable.data.events.AppEventBus
import com.ethran.notable.io.IN_IGNORED
import com.ethran.notable.io.fileObserverEventNames
import com.ethran.notable.io.waitForFileAvailable
import com.ethran.notable.utils.chunked
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Watches the image/PDF files that pages use as backgrounds and reports which pages need their
 * cached background dropped when one changes on disk.
 *
 * Split out of [PageDataManager]. It used to be a pair of maps living next to the page cache, with
 * its registration path (`File.exists()`, `canRead()`, `FileObserver.startWatching()` — blocking
 * disk I/O and an inotify registration) reached from inside the cache's monitor, i.e. while the
 * drawing thread could be waiting on it. Its two maps were also read by the invalidation collector
 * without holding the lock that guarded them, so a concurrent registration could tear the read.
 *
 * The rules that keep it honest now:
 * - **This class owns [registryLock] and nothing else takes it.** Every method may be called with no
 *   other lock held, and none of them calls back into [PageDataManager].
 * - **Callers must not hold the page-cache lock** when calling [watch] / [unwatch]: both touch the
 *   filesystem.
 * - The class reports *page ids*, never file paths, so the mapping from file to pages — the part
 *   that needs the lock — never escapes.
 */
@Singleton
class BackgroundFileWatcher @Inject constructor(
    private val appEventBus: AppEventBus,
) {
    private val log = ShipBook.getLogger("BackgroundFileWatcher")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Guards [observers] and [pagesByFile]. Never held across a call out of this class. */
    private val registryLock = Any()

    /** Absolute file path -> the running observer for it. */
    private val observers = mutableMapOf<String, FileObserver>()

    /** Absolute file path -> the pages whose background is that file. */
    private val pagesByFile = mutableMapOf<String, MutableSet<String>>()

    private val fileChanges = MutableSharedFlow<String>(extraBufferCapacity = 64)

    /**
     * Batches of page ids whose background file just changed, de-duplicated within a 10 ms window
     * (a single save often produces several inotify events). Collect this to drop the cached
     * bitmaps; the watcher itself holds no cache state and does not know what invalidation means.
     */
    val invalidatedPages: Flow<List<String>> =
        fileChanges.chunked(10).map { batch ->
            val paths = batch.distinct()
            synchronized(registryLock) {
                paths.flatMap { pagesByFile[it].orEmpty() }.distinct()
            }
        }

    /**
     * Start reporting changes to [filePath] against [pageId]. Registering the same file for a second
     * page only adds the id — one observer per file.
     *
     * Touches the filesystem: **must not** be called while holding the page-cache lock.
     */
    fun watch(pageId: String, filePath: String) {
        synchronized(registryLock) {
            pagesByFile.getOrPut(filePath) { mutableSetOf() }.add(pageId)
            if (observers.containsKey(filePath)) return // already observing this file

            val file = File(filePath)
            if (!file.exists() || !file.canRead()) {
                log.w("Cannot observe background file: $filePath does not exist or is not readable")
                return
            }
            val observer = newObserver(file, filePath)
            observer.startWatching()
            observers[filePath] = observer
        }
    }

    /**
     * Report a change to [filePath] that inotify could not have seen.
     *
     * There is one: a background whose file did not exist when the page was opened is registered
     * here without an observer ([watch] cannot watch what is not there), so the moment sync
     * downloads it — a PDF imported on the other device, arriving after the pages that use it —
     * nothing would say so, and those pages would go on showing the blank they decoded. The caller
     * knows it wrote the file; this turns that into the same event a local save produces.
     *
     * A path no page is drawn from is not an error: assets are written for pictures too, and those
     * simply map to nobody.
     */
    fun noteChanged(filePath: String) {
        val watched = synchronized(registryLock) { !pagesByFile[filePath].isNullOrEmpty() }
        if (!watched) return
        // Now that the file exists there is something to observe, so later edits report themselves.
        rearm(filePath)
        scope.launch { fileChanges.emit(filePath) }
    }

    /**
     * After a delete/recreate, re-install an observer for [filePath] — but **only if pages still use
     * it**, and without touching the page set.
     *
     * Re-arming by the page id that first created the observer (as the old code did) resurrects an
     * evicted page: with a PDF shared by several pages, the creator can be evicted while another page
     * still shows the file, and re-adding it leaves a zombie id in [pagesByFile] plus, if every real
     * user unwatched during [waitForFileAvailable], a live observer for a file nobody displays.
     * Re-checking [pagesByFile] here is the single source of truth for "does anyone still need this".
     */
    private fun rearm(filePath: String) {
        synchronized(registryLock) {
            if (pagesByFile[filePath].isNullOrEmpty()) return // all real users gone -> stay unwatched
            if (observers.containsKey(filePath)) return       // someone already re-armed it
            val file = File(filePath)
            if (!file.exists() || !file.canRead()) {
                log.w("Cannot re-observe background file: $filePath does not exist or is not readable")
                return
            }
            val observer = newObserver(file, filePath)
            observer.startWatching()
            observers[filePath] = observer
        }
    }

    /**
     * Queue [unwatch] to run off the calling thread.
     *
     * Unlike [watch] / [unwatch] this **is** safe to call while holding another lock: it only
     * dispatches. Page eviction happens under the cache lock and must not stop an inotify watch
     * inline; dropping the watch is pure cleanup, ordered against nothing, so deferring it changes
     * nothing observable.
     */
    fun unwatchAsync(pageId: String) {
        scope.launch { unwatch(pageId) }
    }

    /**
     * Stop reporting for [pageId], shutting down any observer left with no pages. Touches the
     * filesystem — see [unwatchAsync] if a lock is held.
     */
    fun unwatch(pageId: String) {
        synchronized(registryLock) {
            val iterator = pagesByFile.entries.iterator()
            while (iterator.hasNext()) {
                val (filePath, pageIds) = iterator.next()
                if (pageIds.remove(pageId) && pageIds.isEmpty()) {
                    observers.remove(filePath)?.stopWatching()
                    iterator.remove()
                }
            }
        }
    }

    private fun newObserver(file: File, filePath: String): FileObserver =
        object : FileObserver(file, WATCH_MASK) {
            override fun onEvent(event: Int, path: String?) {
                // FileObserver calls this on its own thread; do the work on our scope so nothing
                // blocks the inotify callback.
                scope.launch {
                    if (event == IN_IGNORED) return@launch
                    log.d("Background file changed: $filePath [event=${fileObserverEventNames(event)}]")

                    if (event == DELETE || event == DELETE_SELF) {
                        // The watch dies with the inode. Editors that save by replace (delete +
                        // recreate) therefore need a fresh observer once the new file appears.
                        log.d("Background file deleted.")
                        synchronized(registryLock) { observers.remove(filePath)?.stopWatching() }
                        if (!waitForFileAvailable(filePath)) {
                            log.w("File changed, but does not exist: $filePath")
                            appEventBus.tryEmit(
                                AppEvent.ActionHint("Background does not exist", 3000)
                            )
                            return@launch
                        }
                        // Re-arm by file path, re-checking who still uses it -- never by a captured
                        // page id, which may have been evicted while this observer waited.
                        rearm(filePath)
                    }

                    fileChanges.emit(filePath)
                }
            }
        }

    private companion object {
        private const val WATCH_MASK = FileObserver.CREATE or
            FileObserver.DELETE or
            FileObserver.DELETE_SELF or
            FileObserver.CLOSE_WRITE or
            FileObserver.MOVED_TO or
            FileObserver.MOVE_SELF
    }
}
