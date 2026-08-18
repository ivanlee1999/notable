package com.ethran.notable.ui.viewmodels

import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.db.BookRepository
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.db.Page
import com.ethran.notable.data.db.PageRepository
import com.ethran.notable.editor.canvas.CanvasEventBus
import com.ethran.notable.io.ThumbnailBackfillQueue
import com.ethran.notable.ui.SnackDispatcher
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The scrubber's start/preview/end lifecycle on [QuickNavViewModel].
 *
 * The rule pinned here: *every* commit runs the whole lifecycle. `onScrubStart` is what resets the
 * dedupe target and emits the pre-jump save; the scrubber's tap handler used to call only
 * preview/end, so after a drag to page N, tapping page N hit the stale
 * `lastScrubEndTargetPageId` and did nothing — no navigation, and no save either.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QuickNavScrubLifecycleTest {

    private val pageIds = listOf("p1", "p2", "p3")

    private lateinit var viewModel: QuickNavViewModel

    @Before
    fun setUp() {
        // viewModelScope launches on Dispatchers.Main; unconfined so the emissions this test
        // asserts on have happened by the time the call returns.
        Dispatchers.setMain(UnconfinedTestDispatcher())

        val pageRepository = mockk<PageRepository>()
        coEvery { pageRepository.getById("p1") } returns Page(id = "p1", notebookId = "nb1")
        coEvery { pageRepository.getByIds(any()) } returns emptyList()

        val bookRepository = mockk<BookRepository>()
        coEvery { bookRepository.getById("nb1") } returns Notebook(id = "nb1", pageIds = pageIds)

        val appRepository = mockk<AppRepository>(relaxed = true)
        every { appRepository.pageRepository } returns pageRepository
        every { appRepository.bookRepository } returns bookRepository
        coEvery { appRepository.getPageNumber("nb1", "p1") } returns 0

        viewModel = QuickNavViewModel(
            appRepository = appRepository,
            thumbnailBackfillQueue = mockk<ThumbnailBackfillQueue>(relaxed = true),
            snackDispatcher = mockk<SnackDispatcher>(relaxed = true),
        )

        // Seed the scrubber's page list the way the panel does, and wait for the IO launch.
        viewModel.loadPageData("p1")
        val deadline = System.currentTimeMillis() + 5_000
        while (viewModel.uiState.value.bookPageIds.isEmpty()) {
            check(System.currentTimeMillis() < deadline) { "book data never loaded" }
            Thread.sleep(5)
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Runs [gesture] with collectors on the bus, returning what was navigated to and saved. */
    private fun collectEmissions(gesture: () -> Unit): Pair<List<String>, Int> = runBlocking {
        val navigations = mutableListOf<String>()
        var saves = 0
        val collectors = listOf<Job>(
            launch(Dispatchers.Unconfined) { CanvasEventBus.changePage.collect { navigations += it } },
            launch(Dispatchers.Unconfined) { CanvasEventBus.saveCurrent.collect { saves += 1 } },
        )
        try {
            gesture()
        } finally {
            collectors.forEach { it.cancel() }
        }
        navigations.toList() to saves
    }

    @Test
    fun `tapping the page you last scrubbed to still navigates`() {
        val (navigations, saves) = collectEmissions {
            // Drag lifecycle to page 2...
            viewModel.onScrubStart()
            viewModel.onScrubPreview(1)
            viewModel.onScrubEnd(1)
            // ...then a tap on the same page. The tap handler runs the same lifecycle as the
            // drag — start included — which is what resets the dedupe and saves before the jump.
            viewModel.onScrubStart()
            viewModel.onScrubPreview(1)
            viewModel.onScrubEnd(1)
        }

        assertEquals(
            "the tap must navigate, not be deduped against the drag before it",
            listOf("p2", "p2"),
            navigations,
        )
        assertEquals("each gesture saves the page it is leaving", 2, saves)
    }

    /** What the dedupe is actually for: one gesture whose end callback fires twice. */
    @Test
    fun `a repeated end for one gesture commits once`() {
        val (navigations, _) = collectEmissions {
            viewModel.onScrubStart()
            viewModel.onScrubEnd(2)
            viewModel.onScrubEnd(2)
        }

        assertEquals(listOf("p3"), navigations)
    }

    @Test
    fun `scrubbing state opens and closes around the gesture`() {
        collectEmissions {
            viewModel.onScrubStart()
            assertTrue(CanvasEventBus.isScrubbing.value)
            viewModel.onScrubEnd(0)
        }
        assertEquals(false, CanvasEventBus.isScrubbing.value)
    }
}
