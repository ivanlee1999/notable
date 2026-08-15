package com.ethran.notable.editor.ui.toolbar

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ethran.notable.data.datastore.AppSettings
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.editor.ToolbarAction
import com.ethran.notable.editor.ToolbarUiState
import com.ethran.notable.editor.state.Mode
import com.ethran.notable.editor.ui.toolbar.model.ToolbarElements
import com.ethran.notable.editor.ui.toolbar.model.ToolbarPen
import com.ethran.notable.editor.utils.Eraser
import com.ethran.notable.editor.utils.Pen
import com.ethran.notable.testing.ComposeUiSupportRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The rail, rendered.
 *
 * [RailGroupsTest] asserts the arrangement as a value; this asserts the part only a composed
 * rail can answer — that the nib dots reach the screen, that tapping one asks for that size,
 * and that selecting the eraser puts its two kinds where the dots were. That swap is
 * conditional rendering driven by [ToolbarUiState.mode], which no JVM test can see.
 *
 * Gated by [ComposeUiSupportRule], like every Compose test here.
 */
@RunWith(AndroidJUnit4::class)
class ToolRailTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val composeGate = ComposeUiSupportRule(composeRule)

    private val ballpen = ToolbarPen.DEFAULT_PENS.first { it.id == "ball" }

    private fun state(mode: Mode = Mode.Draw, eraser: Eraser = Eraser.PEN) = ToolbarUiState(
        isToolbarOpen = true,
        mode = mode,
        pen = Pen.BALLPEN,
        penPresetId = ballpen.id,
        penSettings = ToolbarPen.defaultPenSettings,
        eraser = eraser,
        pageNumberInfo = "1/1",
    )

    /** Renders the rail against the default settings, collecting what it asks for. */
    private fun rail(uiState: ToolbarUiState): MutableList<ToolbarAction> {
        val actions = mutableListOf<ToolbarAction>()
        GlobalAppSettings.update(AppSettings(version = 1))
        composeRule.setContent {
            ToolbarContent(
                uiState = uiState,
                onAction = { actions.add(it) },
                onDrawingStateCheck = {},
            )
        }
        composeRule.waitForIdle()
        return actions
    }

    @Test
    fun nibDotsShowThePensOwnSizes() {
        rail(state())
        // The ballpen's configured sizes, each a dot. The rail is fixed, so these are always
        // on screen rather than behind the pen's stroke menu.
        ballpen.nibChoices(ballpen.size).forEach { size ->
            composeRule.onNodeWithContentDescription("nib ${sizeLabel(size)}").assertIsDisplayed()
        }
    }

    @Test
    fun tappingANibAsksForThatSize() {
        val actions = rail(state())
        val target = ballpen.nibChoices(ballpen.size).last { it != ballpen.size }

        composeRule.onNodeWithContentDescription("nib ${sizeLabel(target)}").performClick()
        composeRule.waitForIdle()

        val change = actions.filterIsInstance<ToolbarAction.ChangePenSetting>().single()
        assertEquals(ballpen.id, change.presetId)
        assertEquals(target, change.setting.strokeSize)
        // Changing how broad the nib is must not change the ink it is carrying.
        assertEquals(ballpen.color, change.setting.color)
    }

    @Test
    fun erasingReplacesTheNibsWithTheErasersTwoKinds() {
        rail(state(mode = Mode.Erase))

        composeRule.onNodeWithContentDescription("rub out").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("erase whole strokes").assertIsDisplayed()

        // A nib width means nothing to an eraser, so the dots are gone while it is in hand.
        ballpen.nibChoices(ballpen.size).forEach { size ->
            assertEquals(
                "Nib dot still shown while erasing",
                0,
                count("nib ${sizeLabel(size)}"),
            )
        }
    }

    @Test
    fun tappingAnEraserKindSelectsIt() {
        val actions = rail(state(mode = Mode.Erase))

        composeRule.onNodeWithContentDescription("erase whole strokes").performClick()
        composeRule.waitForIdle()

        assertEquals(
            Eraser.SELECT,
            actions.filterIsInstance<ToolbarAction.ChangeEraser>().single().eraser,
        )
    }

    @Test
    fun theFourImplementsAndTheTwoInklessToolsAreOnTheRail() {
        rail(state())
        // Counted rather than asserted single: every ballpen preset — the rail's and the three
        // coloured ones in the overflow — carries the same description.
        ToolbarPen.RAIL_TYPES.forEach { type ->
            assertTrue("No button for $type", count(type.penName) >= 1)
        }
        composeRule.onNodeWithContentDescription("eraser").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("lasso").assertIsDisplayed()
    }

    @Test
    fun aPresetTheImplementsDoNotStandForIsStillReachable() {
        // The green ballpen is not one of the four; it must still have a button somewhere.
        val green = ToolbarPen.DEFAULT_PENS.first { it.id == "green" }
        assertTrue(green in ToolbarPen.extraPresets(ToolbarPen.DEFAULT_PENS))

        rail(state())
        // Every ballpen preset shares one content description, so assert on the count:
        // one on the rail, plus the three coloured ones in the overflow.
        assertEquals(
            ToolbarPen.DEFAULT_PENS.count { it.pen == Pen.BALLPEN },
            count(Pen.BALLPEN.penName),
        )
    }

    /** How many nodes carry [description] — the overflow puts several behind the same one. */
    private fun count(description: String): Int =
        composeRule.onAllNodesWithContentDescription(description)
            .fetchSemanticsNodes(atLeastOneRootRequired = false).size

    private fun sizeLabel(size: Float): String = ToolbarElements.sizeLabel(size)
}
