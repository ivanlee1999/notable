package com.ethran.notable.editor.topics

import androidx.lifecycle.viewModelScope
import com.ethran.notable.editor.EditorViewModel
import com.ethran.notable.editor.ToolbarUiState
import com.ethran.notable.editor.canvas.CanvasEventBus
import com.ethran.notable.editor.utils.DeviceCompat
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TopicDrawingGateTest {
    @Test fun textAndToolOptionsKeepTheCanvasInactive() {
        assertFalse(ToolbarUiState(isFlowTextOpen = true).isDrawingAllowed)
        assertFalse(ToolbarUiState(isToolOptionsOpen = true).isDrawingAllowed)
        assertTrue(ToolbarUiState().isDrawingAllowed)
    }
    @Test fun olderDelayedResumeCannotDrawBehindTopicPicker() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        mockkObject(DeviceCompat)
        coEvery { DeviceCompat.delayBeforeResumingDrawing(any(), any()) } coAnswers { delay(500) }
        val model = EditorViewModel(context = mockk(relaxed = true), appRepository = mockk(relaxed = true),
            editorSettingCacheManager = mockk(relaxed = true), exportEngine = mockk(relaxed = true),
            pageDataManager = mockk(relaxed = true), syncOrchestrator = mockk(relaxed = true),
            couchSyncHost = mockk(relaxed = true), snackDispatcher = mockk(relaxed = true),
            historyFactory = mockk(relaxed = true), appScope = backgroundScope)
        try {
            val emitted = mutableListOf<Boolean>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { CanvasEventBus.isDrawing.collect { emitted += it } }
            model.updateDrawingState(); runCurrent(); advanceTimeBy(100)
            model.setTopicPickerOpen(true); runCurrent(); advanceTimeBy(1_000); runCurrent()
            assertEquals(listOf(false), emitted)
            assertFalse(model.toolbarState.value.isDrawingAllowed)
            model.setTopicPickerOpen(false); runCurrent(); advanceTimeBy(499); runCurrent()
            assertEquals(listOf(false), emitted)
            advanceTimeBy(1); runCurrent()
            assertEquals(listOf(false, true), emitted)
            model.setSelectionActive(true); model.setTopicPickerOpen(true); runCurrent()
            model.setTopicPickerOpen(false); runCurrent(); advanceTimeBy(1_000); runCurrent()
            assertFalse(model.toolbarState.value.isDrawingAllowed)
            assertFalse(emitted.last())
            assertFalse(ToolbarUiState(isTopicPickerOpen = true).isDrawingAllowed)
        } finally {
            model.viewModelScope.cancel()
            unmockkObject(DeviceCompat)
            Dispatchers.resetMain()
        }
    }
}
