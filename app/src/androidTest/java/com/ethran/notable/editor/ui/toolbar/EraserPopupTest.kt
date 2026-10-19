package com.ethran.notable.editor.ui.toolbar

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import org.junit.Assert.assertFalse
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ethran.notable.data.datastore.AppSettings
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.editor.ToolbarAction
import com.ethran.notable.editor.ToolbarUiState
import com.ethran.notable.editor.state.Mode
import com.ethran.notable.editor.ui.toolbar.model.ToolbarElementId
import com.ethran.notable.editor.ui.toolbar.model.ToolbarElements
import com.ethran.notable.testing.ComposeUiSupportRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class EraserPopupTest {
    private val compose = createComposeRule()
    @get:Rule val composeGate = ComposeUiSupportRule(compose)

    @Test fun largeFontPopupHasNamedFullSizeToggle() {
        GlobalAppSettings.update(AppSettings(version = 1, scribbleToEraseEnabled = false))
        val changes = mutableListOf<Boolean>()
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(320.dp, 560.dp)) then
                DeviceConfigurationOverride.FontScale(1.5f)) {
                var state by remember { mutableStateOf(ToolbarUiState(mode = Mode.Erase)) }
                Box {
                    ToolbarElementView(ToolbarElements.of(ToolbarElementId.ERASER), state, { action ->
                        when (action) {
                            is ToolbarAction.ToggleEraserManu -> state = state.copy(isStrokeSelectionOpen = action.isOpen)
                            is ToolbarAction.ToggleScribbleToErase -> changes += action.enabled
                            else -> Unit
                        }
                    }, {})
                }
            }
        }
        compose.onNodeWithContentDescription("eraser").performClick()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.getExternalFilesDir(null), "ui-review").apply { mkdirs() }
        val bitmap = compose.onNode(isPopup()).captureToImage().asAndroidBitmap()
        File(directory, "eraser-popup-320-large-text.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("Scribble\nto Erase", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertFalse("The two-line label must fit without clipping", layouts.single().hasVisualOverflow)
        val toggle = compose.onNodeWithText("Scribble\nto Erase")
        toggle.assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
            .assertIsOff().performClick()
        toggle.assertIsOn().performClick()
        toggle.assertIsOff()
        assertEquals(listOf(true, false), changes)
    }
}
