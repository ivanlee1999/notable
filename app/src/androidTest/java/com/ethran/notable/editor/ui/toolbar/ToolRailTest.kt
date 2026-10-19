package com.ethran.notable.editor.ui.toolbar

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.onRoot
import java.io.File
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
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
        greyscale: Boolean = false,
    ): MutableList<ToolbarAction> {
        val actions = mutableListOf<ToolbarAction>()
        GlobalAppSettings.update(AppSettings(version = 1, toolbarPosition = position, greyscaleInks = greyscale))
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

        composeRule.onNodeWithContentDescription("rub out").assertIsSelected()
        composeRule.onNodeWithContentDescription("erase whole strokes").assertIsNotSelected()
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

    @Test
    fun historySurvivesARailTooNarrowToHoldEverything() {
        rail(state(), width = NARROW_WIDTH)
        composeRule.onNodeWithContentDescription("undo").assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithContentDescription("redo").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("menu").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Pages").assertDoesNotExist()
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
        composeRule.onNodeWithContentDescription("Choose ink").performScrollTo().performClick()
        Kaleido.Inks.forEach { ink ->
            composeRule.onNodeWithContentDescription(inkDescription(ink)).assertExists()
        }
        assertEquals(Kaleido.Inks.size, countMatching { it.startsWith("Ink:") })
    }

    @Test
    fun quickBlueInkChangesColorInOneTapWithoutOpeningThePalette() {
        val actions = rail(state(), position = AppSettings.Position.Left)
        val target = Kaleido.Inks[8]
        composeRule.onNodeWithContentDescription("Quick ink: Blue")
            .performScrollTo().performClick()
        composeRule.waitForIdle()

        val change = actions.filterIsInstance<ToolbarAction.ChangePenSetting>().single()
        assertEquals(ballpen.id, change.presetId)
        assertEquals(target, change.setting.color)
        assertEquals(ballpen.size, change.setting.strokeSize)
        assertEquals(0, countMatching { it.startsWith("Ink:") })
    }

    @Test
    fun namedPaletteMarksTheCurrentInkWithoutRelyingOnColor() {
        rail(state())
        composeRule.onNodeWithContentDescription("Choose ink").performClick()
        composeRule.onNodeWithContentDescription(inkDescription(ballpen.color)).assertIsSelected()
        composeRule.onNodeWithContentDescription("Ink: Blue").assertIsNotSelected()
        composeRule.onNodeWithText("Blue", useUnmergedTree = true).assertExists()
    }

    @Test
    fun tappingAnInkWritesItToThePenInHand() {
        val actions = rail(state(), position = AppSettings.Position.Left)
        val target = Kaleido.Inks.last()
        composeRule.onNodeWithContentDescription("Choose ink").performScrollTo().performClick()

        composeRule.onNodeWithContentDescription(inkDescription(target))
            .performScrollTo().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
        composeRule.waitForIdle()

        val change = actions.filterIsInstance<ToolbarAction.ChangePenSetting>().single()
        assertEquals(ballpen.id, change.presetId)
        assertEquals(target, change.setting.color)
        // Changing the ink must not change how broad the nib is.
        assertEquals(ballpen.size, change.setting.strokeSize)
    }

    @Test
    fun historySurvivesARailTooShortToHoldEverything() {
        rail(state(), height = SHORT_HEIGHT, position = AppSettings.Position.Left)
        composeRule.onNodeWithContentDescription("undo").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("redo").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("menu").assertDoesNotExist()
    }

    /** And the palette it gave the room to is still reachable, by scrolling to it. */
    @Test
    fun theInksAreScrollableToOnARailTooShortToHoldThem() {
        rail(state(), height = SHORT_HEIGHT, position = AppSettings.Position.Left)
        composeRule.onNodeWithContentDescription("Choose ink").performScrollTo().performClick()
        composeRule.onNodeWithContentDescription(inkDescription(Kaleido.Inks.last()))
            .performScrollTo()
            .assertIsDisplayed()
    }

    private fun screenshot(name: String, popup: Boolean = false) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.getExternalFilesDir(null), "ui-review").apply { mkdirs() }
        composeRule.waitForIdle()
        val root = if (popup) composeRule.onNode(isPopup()) else composeRule.onRoot()
        val bitmap = root.captureToImage().asAndroidBitmap()
        File(directory, "$name.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    @Test
    fun reviewLeftRailAndNamedPalette() {
        rail(state(), height = SHORT_HEIGHT, position = AppSettings.Position.Left)
        screenshot("editor-rail-left")
        composeRule.onNodeWithContentDescription("Choose ink").performScrollTo().performClick()
        composeRule.onNodeWithContentDescription("Ink: Blue").assertIsDisplayed()
        screenshot("editor-palette-left", popup = true)
    }

    @Test
    fun reviewRightRailAndNamedPalette() {
        rail(state(), height = SHORT_HEIGHT, position = AppSettings.Position.Right)
        screenshot("editor-rail-right")
        composeRule.onNodeWithContentDescription("Choose ink").performScrollTo().performClick()
        composeRule.onNodeWithContentDescription("Ink: Blue").assertIsDisplayed()
        screenshot("editor-palette-right", popup = true)
    }

    @Test
    fun reviewNarrowRailAndNamedPalette() {
        rail(state(), width = NARROW_WIDTH)
        screenshot("editor-rail-narrow")
        composeRule.onNodeWithContentDescription("Choose ink").performClick()
        composeRule.onNodeWithContentDescription("Ink: Blue").assertIsDisplayed()
        screenshot("editor-palette-narrow", popup = true)
    }

    @Test
    fun greyscalePaletteUsesReadableNamesForEveryBuiltInInk() {
        rail(state(), width = NARROW_WIDTH, greyscale = true)
        composeRule.onNodeWithContentDescription("Choose ink").performClick()
        listOf("Black", "Dark grey", "Grey", "Light grey", "White").forEach { name ->
            composeRule.onNodeWithContentDescription("Ink: $name").assertIsDisplayed()
            composeRule.onNodeWithText(name).assertIsDisplayed()
        }
        screenshot("editor-palette-greyscale", popup = true)
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
