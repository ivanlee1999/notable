package com.ethran.notable.editor.ui.toolbar

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ethran.notable.data.datastore.AppSettings
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.editor.ToolbarAction
import com.ethran.notable.editor.ToolbarUiState
import com.ethran.notable.editor.state.Mode
import com.ethran.notable.editor.ui.toolbar.model.NibWidth
import com.ethran.notable.editor.ui.toolbar.model.ToolbarPen
import com.ethran.notable.editor.ui.toolbar.model.baseWidth
import com.ethran.notable.editor.utils.Eraser
import com.ethran.notable.editor.utils.Pen
import com.ethran.notable.testing.ComposeUiSupportRule
import com.ethran.notable.ui.theme.Kaleido
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

    companion object {
        /**
         * Narrower than a Palma-class panel (~412dp), and so narrower than any device this
         * runs on — which is the point: [rail] may only ever narrow the window, never widen it.
         */
        private val NARROW_WIDTH = 320.dp

        /**
         * Shorter than any panel this runs on, for the same reason [NARROW_WIDTH] is narrower
         * than any of them: a pinned size may only ever shrink the window.
         */
        private val SHORT_HEIGHT = 560.dp
    }

    private fun state(mode: Mode = Mode.Draw, eraser: Eraser = Eraser.PEN) = ToolbarUiState(
        isToolbarOpen = true,
        mode = mode,
        pen = Pen.BALLPEN,
        penPresetId = ballpen.id,
        penSettings = ToolbarPen.defaultPenSettings,
        eraser = eraser,
        pageNumberInfo = "1/1",
    )

    /**
     * Renders the rail against the default settings, collecting what it asks for.
     *
     * [width] narrows the rail *below* the window when a test is about a one-handed panel;
     * `null` lets it take the window as it is. It can only ever narrow — forcing a width wider
     * than the device puts the rail's tail outside the window, where every assertion about
     * being displayed fails for a reason that has nothing to do with the rail. That is a real
     * mistake this file made: pinned at a tablet's 900dp, it passed on the 10" AVD and failed
     * on a CI emulator half that wide.
     *
     * So the two kinds of assertion here mean different things, and are not interchangeable:
     * [assertExists] for "the rail offers this", which is true at any size because what does
     * not fit is scrolled to; `assertIsDisplayed` only where being *on screen* is the actual
     * guarantee, which is the pinned group.
     */
    private fun rail(
        uiState: ToolbarUiState,
        width: Dp? = null,
        height: Dp? = null,
        position: AppSettings.Position = AppSettings.Position.Top,
    ): MutableList<ToolbarAction> {
        val actions = mutableListOf<ToolbarAction>()
        GlobalAppSettings.update(AppSettings(version = 1, toolbarPosition = position))
        composeRule.setContent {
            var modifier: Modifier = Modifier
            if (width != null) modifier = modifier.width(width)
            if (height != null) modifier = modifier.height(height)
            Box(modifier) {
                ToolbarContent(
                    uiState = uiState,
                    onAction = { actions.add(it) },
                    onDrawingStateCheck = {},
                )
            }
        }
        composeRule.waitForIdle()
        return actions
    }

    @Test
    fun theRailOffersTheFiveStepsAndNoOthers() {
        rail(state())
        NibWidth.entries.forEach { width ->
            composeRule.onNodeWithContentDescription("nib ${width.label}").assertExists()
        }
        assertEquals(NibWidth.entries.size, countMatching { it.startsWith("nib ") })
    }

    @Test
    fun tappingANibAsksForThatStepAtThePensOwnBaseWidth() {
        val actions = rail(state())
        val target = NibWidth.BROAD

        composeRule.onNodeWithContentDescription("nib ${target.label}")
            .performScrollTo().performClick()
        composeRule.waitForIdle()

        val change = actions.filterIsInstance<ToolbarAction.ChangePenSetting>().single()
        assertEquals(ballpen.id, change.presetId)
        assertEquals(target.sizeFor(ballpen.pen.baseWidth), change.setting.strokeSize)
        // Changing how broad the nib is must not change the ink it is carrying.
        assertEquals(ballpen.color, change.setting.color)
    }

    /**
     * The eraser is an implement with a width like any other.
     *
     * Its two kinds used to *replace* the nib row, which is how the eraser ended up the one
     * tool stuck at a single fixed size — there was nowhere left to ask for a bigger one. They
     * join the row now, and the row applies to the eraser while it is in hand.
     */
    @Test
    fun erasingKeepsTheNibsAndAddsTheErasersTwoKinds() {
        val actions = rail(state(mode = Mode.Erase))

        composeRule.onNodeWithContentDescription("rub out").assertExists()
        composeRule.onNodeWithContentDescription("erase whole strokes").assertExists()
        NibWidth.entries.forEach { width ->
            composeRule.onNodeWithContentDescription("nib ${width.label}").assertExists()
        }

        composeRule.onNodeWithContentDescription("nib ${NibWidth.HEAVY.label}")
            .performScrollTo().performClick()
        composeRule.waitForIdle()

        // The eraser's own width, not the pen's: a nib change here must never touch the ink.
        assertEquals(
            NibWidth.HEAVY,
            actions.filterIsInstance<ToolbarAction.ChangeEraserWidth>().single().width,
        )
        assertTrue(
            "Erasing must not rewrite the pen it is standing in for",
            actions.filterIsInstance<ToolbarAction.ChangePenSetting>().isEmpty(),
        )
    }

    @Test
    fun tappingAnEraserKindSelectsIt() {
        val actions = rail(state(mode = Mode.Erase))

        composeRule.onNodeWithContentDescription("erase whole strokes")
            .performScrollTo().performClick()
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
        composeRule.onNodeWithContentDescription("eraser").assertExists()
        composeRule.onNodeWithContentDescription("lasso").assertExists()
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

    /**
     * On a one-handed panel the rail cannot hold everything at once, and what it gives up must
     * be room in the scroller — never the way out of the editor.
     *
     * The menu is the only route to the page's own actions, so a rail that pushes it off the
     * edge strands the user. 0.19.0 did exactly that: with the tools, the nibs and the history
     * outside the scroller, their fixed width left the weighted overflow nothing, and the menu,
     * the library and the inks were clipped away. A tablet never sees it — at 800dp across,
     * every arrangement fits and looks correct.
     */
    @Test
    fun theMenuSurvivesARailTooNarrowToHoldEverything() {
        rail(state(), width = NARROW_WIDTH)
        composeRule.onNodeWithContentDescription("menu").assertIsDisplayed()
    }

    /**
     * And what the narrow rail does give up is only *room*: the tools are still reachable by
     * scrolling to them, not clipped out of existence.
     */
    @Test
    fun theToolsAreScrollableToOnARailTooNarrowToHoldThem() {
        rail(state(), width = NARROW_WIDTH)
        composeRule.onNodeWithContentDescription("nib ${NibWidth.HEAVY.label}")
            .performScrollTo()
            .assertIsDisplayed()
    }

    /**
     * The whole palette reaches the vertical rail, not a cut of it.
     *
     * The strip used to show four inks, chosen by cutting the pen's list at the size that
     * happened to fit one column — so eight of the twelve were reachable only through the
     * stroke menu, and which four you got moved every time the pen changed colour.
     */
    @Test
    fun theVerticalRailCarriesEveryInkThePenOffers() {
        rail(state(), position = AppSettings.Position.Left)
        Kaleido.Inks.forEach { ink ->
            composeRule.onNodeWithContentDescription(inkDescription(ink)).assertExists()
        }
        assertEquals(Kaleido.Inks.size, countMatching { it.startsWith("ink #") })
    }

    @Test
    fun tappingAnInkWritesItToThePenInHand() {
        val actions = rail(state(), position = AppSettings.Position.Left)
        val target = Kaleido.Inks.last()

        composeRule.onNodeWithContentDescription(inkDescription(target))
            .performScrollTo().performClick()
        composeRule.waitForIdle()

        val change = actions.filterIsInstance<ToolbarAction.ChangePenSetting>().single()
        assertEquals(ballpen.id, change.presetId)
        assertEquals(target, change.setting.color)
        // Changing the ink must not change how broad the nib is.
        assertEquals(ballpen.size, change.setting.strokeSize)
    }

    /**
     * The vertical counterpart of [theMenuSurvivesARailTooNarrowToHoldEverything], and the
     * reason the palette is inside the scroller rather than pinned under it.
     *
     * Twelve swatches are 119dp of rail. Fixed below the pinned group, they pushed it off the
     * bottom of a portrait panel — the menu is the only route to the page's own actions, so a
     * rail that loses it strands the user exactly as 0.19.0 did across the other axis. The
     * height here is well under any device's, because a pinned size may only ever shrink the
     * window: forcing one taller puts the rail's tail outside it and every assertion about
     * being displayed fails for reasons that have nothing to do with the rail.
     */
    @Test
    fun theMenuSurvivesARailTooShortToHoldEverything() {
        rail(state(), height = SHORT_HEIGHT, position = AppSettings.Position.Left)
        composeRule.onNodeWithContentDescription("menu").assertIsDisplayed()
    }

    /** And the palette it gave the room to is still reachable, by scrolling to it. */
    @Test
    fun theInksAreScrollableToOnARailTooShortToHoldThem() {
        rail(state(), height = SHORT_HEIGHT, position = AppSettings.Position.Left)
        composeRule.onNodeWithContentDescription(inkDescription(Kaleido.Inks.last()))
            .performScrollTo()
            .assertIsDisplayed()
    }

    /** How many nodes carry [description] — the overflow puts several behind the same one. */
    private fun count(description: String): Int =
        composeRule.onAllNodesWithContentDescription(description)
            .fetchSemanticsNodes(atLeastOneRootRequired = false).size

    /** How many nodes carry a content description matching [predicate]. */
    private fun countMatching(predicate: (String) -> Boolean): Int =
        composeRule.onAllNodes(
            SemanticsMatcher("content description matches") { node ->
                node.config.getOrNull(SemanticsProperties.ContentDescription)
                    .orEmpty().any(predicate)
            }
        ).fetchSemanticsNodes(atLeastOneRootRequired = false).size

}
