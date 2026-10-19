package com.ethran.notable.gestures

import android.graphics.Rect
import android.view.View
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeTimeoutException
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
        showFocusedReceiver(actions)

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

    /**
     * One axis, one meaning, per mode: a sideways swipe is Pagination's page turn and must not
     * navigate under continuous scrolling — the two metaphors on one page is what produced
     * duplicate blank pages (each stray swipe on the last page used to create one).
     */
    @Test
    fun sidewaysSwipeDoesNotTurnThePageUnderContinuousScrolling() {
        val actions = RecordingGestureActions()
        showFocusedReceiver(actions)

        composeRule.onNodeWithTag("gesture-area").performTouchInput {
            down(Offset(width * 0.9f, center.y))
            moveTo(Offset(width * 0.1f, center.y), delayMillis = 40)
            up()
        }
        composeRule.waitForIdle()

        assertTrue("no page turn under continuous scrolling", actions.nextPageCalls == 0)
        assertTrue("the swiper is told what navigates instead", actions.hints.isNotEmpty())
    }

    @Test
    fun sidewaysSwipeTurnsThePageUnderPagination() {
        GlobalAppSettings.update(
            GlobalAppSettings.current.copy(
                verticalNavigation = AppSettings.VerticalNavigation.Paged,
            )
        )
        val actions = RecordingGestureActions()
        showFocusedReceiver(actions)

        // Swipe left = next page, the paged default mapping.
        composeRule.onNodeWithTag("gesture-area").performTouchInput {
            down(Offset(width * 0.9f, center.y))
            moveTo(Offset(width * 0.1f, center.y), delayMillis = 40)
            up()
        }
        composeRule.waitForIdle()

        assertTrue("a sideways swipe is Pagination's page turn", actions.nextPageCalls == 1)
    }

    private fun showFocusedReceiver(actions: GestureActions) {
        var receiverView: View? = null
        composeRule.setContent {
            val view = LocalView.current
            SideEffect { receiverView = view }
            Box(Modifier.fillMaxSize().testTag("gesture-area")) {
                EditorGestureReceiver(actions)
            }
        }
        // Compose being idle does not mean the Activity's window has received focus yet.
        // The production receiver rejects unfocused input, so establish its actual precondition
        // before injecting the single gesture. Do not retry the gesture or weaken its assertions.
        var hostState = "host has not been composed"
        try {
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.runOnIdle {
                    val attached = receiverView?.isAttachedToWindow == true
                    val focused = receiverView?.hasWindowFocus() == true
                    hostState = "hostPresent=${receiverView != null}, attached=$attached, windowFocused=$focused"
                    attached && focused
                }
            }
        } catch (timeout: ComposeTimeoutException) {
            throw AssertionError("Gesture host did not become input-ready within 5000ms: $hostState", timeout)
        }
    }

    private class RecordingGestureActions : GestureActions {
        val scrollDeltas = mutableListOf<Offset>()
        val hints = mutableListOf<String>()
        var nextPageCalls = 0

        override fun requestScroll(delta: Offset) { scrollDeltas += delta }
        override fun requestPageStep(direction: Int) = Unit
        override fun onGestureEnd() = Unit
        override fun onPinchToZoom(delta: Float, center: Offset?) = Unit
        override fun goToNextPage() { nextPageCalls++ }
        override fun goToPreviousPage() = Unit
        override fun toggleTool() = Unit
        override fun toggleZen() = Unit
        override fun undo() = Unit
        override fun redo() = Unit
        override fun setIsDrawing(value: Boolean) = Unit
        override fun showHint(text: String) { hints += text }
        override fun selectRectangle(rect: Rect) = Unit
        override fun redrawCanvas() = Unit
    }
}
