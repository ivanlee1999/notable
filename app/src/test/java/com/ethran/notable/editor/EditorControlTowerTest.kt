package com.ethran.notable.editor

import androidx.compose.ui.geometry.Offset
import com.ethran.notable.editor.state.ClipboardStore
import com.ethran.notable.editor.state.History
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

class EditorControlTowerTest {
    private lateinit var scope: CoroutineScope
    private lateinit var page: PageView
    private lateinit var history: History
    private lateinit var viewModel: EditorViewModel
    private lateinit var tower: EditorControlTower

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        page = mockk(relaxed = true)
        history = mockk(relaxed = true)
        viewModel = mockk(relaxed = true)
        tower = EditorControlTower(
            scope = scope,
            page = page,
            history = history,
            viewModel = viewModel,
            clipboardStore = mockk<ClipboardStore>(relaxed = true),
        )
        every { page.isTransformationAllowed } returns true
        every { page.isAtVerticalEdge(any()) } returns true
        every { page.crossPageScrollActive } returns false
        every { page.nextPageId } returns null
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `scrolling mode creates and enters the next page at the last sheet`() {
        every { page.continuousScrollEnabled } returns true

        tower.requestScroll(Offset(0f, -240f))

        verify(exactly = 1) { viewModel.goToNextPage() }
        verify(exactly = 1) { history.cleanHistory() }
    }

    @Test
    fun `pagination mode creates and enters the next page at the last sheet`() {
        every { page.continuousScrollEnabled } returns false

        tower.requestScroll(Offset(0f, -240f))

        verify(exactly = 1) { viewModel.goToNextPage() }
        verify(exactly = 1) { history.cleanHistory() }
    }

    @Test
    fun `scrolling mode keeps flowing across an existing page seam`() {
        every { page.continuousScrollEnabled } returns true
        every { page.crossPageScrollActive } returns true
        every { page.nextPageId } returns "page-2"

        tower.requestScroll(Offset(0f, -240f))

        verify(exactly = 0) { viewModel.goToNextPage() }
    }
}
