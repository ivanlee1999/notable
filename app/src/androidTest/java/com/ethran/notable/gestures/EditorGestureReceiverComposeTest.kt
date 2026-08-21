package com.ethran.notable.gestures

import android.graphics.Rect
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ethran.notable.data.datastore.AppSettings
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.testing.ComposeUiSupportRule
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Drives the real Compose pointer receiver instead of bypassing it at EditorControlTower. */
@RunWith(AndroidJUnit4::class)
class EditorGestureReceiverComposeTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val composeGate = ComposeUiSupportRule(composeRule)

    private lateinit var settingsBefore: AppSettings

    @Before
    fun configureContinuousScrolling() {
        settingsBefore = GlobalAppSettings.current
        GlobalAppSettings.update(
            settingsBefore.copy(
                verticalNavigation = AppSettings.VerticalNavigation.Continuous,
                smoothScroll = true,
            )
        )
    }

    @After
    fun restoreSettings() {
        GlobalAppSettings.update(settingsBefore)
    }

    @Test
    fun quickSingleMoveSwipeReachesContinuousScrollAction() {
        val actions = RecordingGestureActions()
        composeRule.setContent {
            Box(Modifier.fillMaxSize().testTag("gesture-area")) {
                EditorGestureReceiver(actions)
            }
        }

        // Deliberately one MOVE event. This is the short e-ink-panel gesture that used to cross
        // the smooth-scroll threshold, establish its movement reference there, then lift with the
        // whole drag discarded as zero.
        composeRule.onNodeWithTag("gesture-area").performTouchInput {
            down(Offset(center.x, height * 0.8f))
            moveTo(Offset(center.x, height * 0.2f), delayMillis = 40)
            up()
        }
        composeRule.waitForIdle()

        assertTrue(
            "an upward swipe must reach requestScroll with an upward delta",
            actions.scrollDeltas.any { it.y < 0f },
        )
    }

    private class RecordingGestureActions : GestureActions {
        val scrollDeltas = mutableListOf<Offset>()

        override fun requestScroll(delta: Offset) { scrollDeltas += delta }
        override fun requestPageStep(direction: Int) = Unit
        override fun onGestureEnd() = Unit
        override fun onPinchToZoom(delta: Float, center: Offset?) = Unit
        override fun goToNextPage() = Unit
        override fun goToPreviousPage() = Unit
        override fun toggleTool() = Unit
        override fun toggleZen() = Unit
        override fun undo() = Unit
        override fun redo() = Unit
        override fun setIsDrawing(value: Boolean) = Unit
        override fun showHint(text: String) = Unit
        override fun selectRectangle(rect: Rect) = Unit
        override fun redrawCanvas() = Unit
    }
}
