package com.ethran.notable.editor

import androidx.compose.ui.geometry.Offset
import com.ethran.notable.data.PageDataManager
import com.ethran.notable.editor.state.ClipboardStore
import com.ethran.notable.editor.state.History
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The navigation contract: under continuous scrolling, scrolling is the only navigation —
 * the last page's end grows the notebook by a silently appended page, and the top of any
 * page flows into the previous one. Discrete turns live only on Pagination's step path.
 */
class EditorControlTowerTest {
    private lateinit var scope: CoroutineScope
    private lateinit var page: PageView
    private lateinit var pageDataManager: PageDataManager
    private lateinit var history: History
    private lateinit var viewModel: EditorViewModel
    private lateinit var tower: EditorControlTower

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        page = mockk(relaxed = true)
        pageDataManager = mockk(relaxed = true)
        history = mockk(relaxed = true)
        viewModel = mockk(relaxed = true)
        tower = EditorControlTower(
            scope = scope,
            page = page,
            history = history,
            viewModel = viewModel,
            clipboardStore = mockk<ClipboardStore>(relaxed = true),
        )
        every { page.pageDataManager } returns pageDataManager
        every { page.isTransformationAllowed } returns true
        every { page.isAtVerticalEdge(any()) } returns true
        every { page.crossPageScrollActive } returns false
        every { page.nextPageId } returns null
        every { page.previousPageId } returns null
        every { page.currentPageId } returns "page-1"
        every { page.scroll } returns Offset.Zero
        every { page.zoomLevel.value } returns 1f
        // No page switch in flight: the toolbar already shows the current page.
        every { viewModel.toolbarState.value.pageId } returns "page-1"
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `scrolling off the last page's end appends a page without entering it`() {
        every { page.continuousScrollEnabled } returns true

        tower.requestScroll(Offset(0f, -240f))

        // Appended silently — the seam shows it and the scroll flows into it. No jump.
        coVerify(timeout = 2_000, exactly = 1) { pageDataManager.ensureNextPage("page-1") }
        verify(exactly = 0) { viewModel.goToNextPage() }
        verify(exactly = 0) { viewModel.changePage(any()) }
    }

    @Test
    fun `the append is latched once per gesture`() {
        every { page.continuousScrollEnabled } returns true

        tower.requestScroll(Offset(0f, -240f))
        tower.requestScroll(Offset(0f, -240f))

        coVerify(timeout = 2_000, exactly = 1) { pageDataManager.ensureNextPage(any()) }
    }

    @Test
    fun `scrolling mode keeps flowing across an existing page seam`() {
        every { page.continuousScrollEnabled } returns true
        every { page.crossPageScrollActive } returns true
        every { page.nextPageId } returns "page-2"

        tower.requestScroll(Offset(0f, -240f))

        verify(exactly = 0) { viewModel.goToNextPage() }
        verify(exactly = 0) { viewModel.changePage(any()) }
        coVerify(exactly = 0) { pageDataManager.ensureNextPage(any()) }
    }

    /**
     * The upward entry is not gated on this page having a next page: the seam illusion only
     * needs the *previous* page, so the top of the last page flows backward like any other.
     */
    @Test
    fun `scrolling up at the top of the last page enters the previous page at its end`() {
        every { page.continuousScrollEnabled } returns true
        every { page.previousPageId } returns "page-0"

        tower.requestScroll(Offset(0f, 240f))

        verify(exactly = 1) { viewModel.changePage("page-0") }
        verify(exactly = 1) { history.cleanHistory() }
        // Entered at its end: scroll preset far past any real extent; the entry clamp lands it.
        verify { pageDataManager.setPageScroll("page-0", Offset(0f, 1e7f)) }
    }

    /** Pagination's discrete turn lives on the step path, not on requestScroll. */
    @Test
    fun `pagination mode creates and enters the next page from a step at the last sheet`() {
        every { page.continuousScrollEnabled } returns false
        every { page.viewHeight } returns 1872

        tower.requestPageStep(1)

        verify(exactly = 1) { viewModel.goToNextPage() }
        verify(exactly = 1) { history.cleanHistory() }
    }

    /** requestScroll no longer turns pages discretely in any mode. */
    @Test
    fun `a raw scroll delta never turns the page discretely`() {
        every { page.continuousScrollEnabled } returns false

        tower.requestScroll(Offset(0f, -240f))

        verify(exactly = 0) { viewModel.goToNextPage() }
        verify(exactly = 0) { viewModel.goToPreviousPage() }
        coVerify(exactly = 0) { pageDataManager.ensureNextPage(any()) }
    }
}
