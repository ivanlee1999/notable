package com.ethran.notable.recognition

import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.db.Page
import com.ethran.notable.data.db.PageText
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.db.isStaleFor
import com.ethran.notable.di.ApplicationScope
import com.ethran.notable.sync.SyncClock
import com.ethran.notable.sync.SyncSettings
import com.ethran.notable.sync.couch.OkHttpCouchTransport
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

private val log = ShipBook.getLogger("HandwritingRecognizer")

/**
 * Turns pages into text, in the background, some time after the writing stops.
 *
 * Recognition is deliberately not continuous. It runs when a page is done being written — on
 * page change, editor close, or the app going away — because the recognizer is a firmware
 * service reached over IPC, and asking it to keep up with a moving pen would cost battery to
 * produce text nobody reads until later anyway.
 *
 * A page is only recognized when its text is missing or older than its ink. Text that is current
 * is left alone even if the *other* device's engine produced it: the two engines disagree about
 * wording, and treating disagreement as staleness is what turns two devices into a pair that
 * rewrite each other's work indefinitely.
 */
@Singleton
class HandwritingRecognizer @Inject constructor(
    private val appRepository: AppRepository,
    private val engine: OnyxHwrEngine,
    private val http: OkHttpClient,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    private val pending = mutableSetOf<String>()
    private val pendingLock = Mutex()
    private val runLock = Mutex()

    @Volatile
    private var debounce: Job? = null

    /**
     * Notes that [pageId] has stopped changing. Returns immediately; recognition happens later,
     * and only if the page turns out to need it.
     */
    fun pageSettled(pageId: String) {
        if (pageId.isBlank()) return
        scope.launch {
            pendingLock.withLock { pending += pageId }
            debounce?.cancel()
            debounce = scope.launch {
                delay(DEBOUNCE_MS)
                drain()
            }
        }
    }

    /**
     * Recognizes everything outstanding now, without waiting out the debounce. For the app going
     * to the background, where waiting risks the process being killed with text unwritten.
     */
    fun flush() {
        scope.launch {
            debounce?.cancel()
            drain()
            engine.release()
        }
    }

    /**
     * Recognizes [pageId] regardless of whether its text looks current, and returns the result.
     * For the reader's "recognize again" button — the one case where the user has looked at the
     * text, decided it is wrong, and asked for another attempt.
     */
    suspend fun recognizeNow(pageId: String): PageText? {
        val settings = appRepository.kvProxy.getSyncSettings()
        return runLock.withLock { recognize(pageId, settings, force = true) }
    }

    /** Publishes any text that has not reached the server — after a spell offline, most of all. */
    fun publishPending() {
        scope.launch {
            val settings = appRepository.kvProxy.getSyncSettings()
            val publisher = publisher(settings) ?: return@launch
            runLock.withLock {
                for (text in appRepository.pageTextRepository.pendingPush()) {
                    publish(text, publisher)
                }
            }
        }
    }

    private suspend fun drain() {
        val settings = appRepository.kvProxy.getSyncSettings()
        if (!settings.recognizeHandwriting || !engine.isAvailable) {
            pendingLock.withLock { pending.clear() }
            return
        }

        val pages = pendingLock.withLock { pending.toList().also { pending.clear() } }
        if (pages.isEmpty()) return

        runLock.withLock {
            for (pageId in pages) {
                try {
                    recognize(pageId, settings, force = false)
                } catch (e: Exception) {
                    log.w("Recognizing $pageId failed: ${e.message}")
                }
            }
        }
    }

    /**
     * Recognizes one page and stores the result, or returns null when there was nothing to do.
     *
     * The page's clock is read *before* its strokes, so ink that lands during recognition leaves
     * the page looking stale afterwards and gets picked up next time. Reading it after would let
     * that ink be stamped as already recognized, and it would never be read at all.
     */
    private suspend fun recognize(
        pageId: String,
        settings: SyncSettings,
        force: Boolean,
    ): PageText? {
        val page = appRepository.pageRepository.getById(pageId) ?: return null
        val existing = appRepository.pageTextRepository.get(pageId)
        if (!force && existing != null && !existing.isStaleFor(page)) return null

        val clock = page.updatedAt.time
        val data = appRepository.pageRepository.getWithDataById(pageId) ?: return null

        val text = recognizeStrokes(data.strokes, page, settings.recognitionLanguage) ?: return null

        // Recognition that changed nothing is not an edit: rewriting the row would republish it
        // and wake every reader of the change feed for no reason.
        if (existing != null &&
            existing.text == text &&
            existing.engine == ENGINE_MYSCRIPT &&
            existing.language == settings.recognitionLanguage
        ) {
            return existing
        }

        val recognized = PageText(
            pageId = pageId,
            text = text,
            engine = ENGINE_MYSCRIPT,
            language = settings.recognitionLanguage,
            recognizedClock = clock,
            updatedAt = SyncClock.nowDate(),
            pendingPush = true,
        )
        appRepository.pageTextRepository.save(recognized)
        log.i("Recognized ${text.length} chars on $pageId")

        publisher(settings)?.let { publish(recognized, it, page) }
        return recognized
    }

    /**
     * Recognized text for a page's strokes, or null when the engine could not run.
     *
     * The recognizer reads a view of a fixed size, so the ink is handed over in view-sized
     * chunks cut at the gaps between lines, and their results are joined back into one page.
     */
    private suspend fun recognizeStrokes(
        strokes: List<Stroke>,
        page: Page,
        language: String,
    ): String? {
        if (strokes.isEmpty()) return ""

        val width = (page.pageWidth ?: DEFAULT_VIEW_WIDTH).toFloat()
        val height = (page.pageHeight ?: DEFAULT_VIEW_HEIGHT).toFloat()

        val chunks = withContext(Dispatchers.Default) {
            LineSegmentation.chunk(strokes, viewHeight = height)
        }

        val lines = mutableListOf<String>()
        for (chunk in chunks) {
            // A chunk taller than the view is a single oversized stroke — a diagram or a bracket.
            // It is handed over as-is; the recognizer makes of it what it can.
            val chunkHeight = maxOf(height, chunk.strokes.maxOf { it.bottom })
            val recognized = engine.recognize(chunk.strokes, width, chunkHeight, language)
                ?: return null
            if (recognized.isNotBlank()) lines += recognized.trim()
        }
        return lines.joinToString("\n")
    }

    private suspend fun publish(
        text: PageText,
        publisher: PageTextPublisher,
        page: Page? = null,
    ) {
        val resolved = page ?: appRepository.pageRepository.getById(text.pageId) ?: return
        val outcome = withContext(Dispatchers.IO) {
            publisher.publish(
                text = text,
                notebookId = resolved.notebookId,
                pageTitle = resolved.title,
                pageUpdatedAt = Date(text.recognizedClock),
            )
        }
        when (outcome) {
            // A page whose text the server already holds is as settled as one this device wrote.
            PublishOutcome.PUBLISHED, PublishOutcome.ALREADY_CURRENT ->
                appRepository.pageTextRepository.markPushed(text)
            // Left pending on purpose: publishPending() retries it when there is a network again.
            PublishOutcome.FAILED -> log.v("Text for ${text.pageId} stays pending")
        }
    }

    private fun publisher(settings: SyncSettings): PageTextPublisher? {
        if (!settings.recognitionPublishable) return null
        val transport = runCatching {
            OkHttpCouchTransport(
                baseUrl = settings.couchUrl,
                username = settings.couchUsername.ifBlank { null },
                password = settings.couchPassword.ifBlank { null },
                client = http,
            )
        }.getOrElse {
            log.w("Recognized text has nowhere to go: the server URL is not usable")
            return null
        }
        return PageTextPublisher(
            transport = transport,
            database = settings.recognitionDatabase,
            deviceId = settings.deviceId,
        )
    }

    private companion object {
        /**
         * How long a page must sit still before it is worth recognizing. Long enough to cover
         * turning a page and coming back, short enough that closing the notebook straight after
         * writing still catches it.
         */
        const val DEBOUNCE_MS = 5_000L

        /** A BOOX screen, for pages written before page sizes were recorded. */
        const val DEFAULT_VIEW_WIDTH = 1404
        const val DEFAULT_VIEW_HEIGHT = 1872
    }
}
