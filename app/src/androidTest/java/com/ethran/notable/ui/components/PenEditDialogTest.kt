package com.ethran.notable.ui.components

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ethran.notable.data.datastore.AppSettings
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.editor.ui.toolbar.inkDescription
import com.ethran.notable.editor.ui.toolbar.model.ToolbarElements
import com.ethran.notable.editor.ui.toolbar.model.ToolbarPen
import com.ethran.notable.testing.ComposeUiSupportRule
import com.ethran.notable.ui.theme.InkaTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class PenEditDialogTest {
    private val compose = createComposeRule()
    @get:Rule val composeGate = ComposeUiSupportRule(compose)

    @Test
    fun narrowLargeTextDialogKeepsEveryChoiceAndDoneReachable() {
        GlobalAppSettings.update(AppSettings(version = 1, greyscaleInks = false))
        var closed = false
        compose.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(DpSize(320.dp, 560.dp)) then
                    DeviceConfigurationOverride.FontScale(1.5f)
            ) {
                InkaTheme {
                    PenEditDialog(ToolbarPen.DEFAULT_PENS.first(), {}, { closed = true })
                }
            }
        }
        ToolbarPen.COLOR_CANDIDATES.forEach { color ->
            compose.onNodeWithContentDescription(inkDescription(color))
                .performScrollTo().assertIsDisplayed()
                .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        }
        ToolbarPen.SIZE_CANDIDATES.forEach { size ->
            compose.onNodeWithContentDescription("Nib size ${ToolbarElements.sizeLabel(size)}")
                .performScrollTo().assertIsDisplayed()
                .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        }
        compose.onNodeWithText("Done").performScrollTo().assertIsDisplayed()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.getExternalFilesDir(null), "ui-review").apply { mkdirs() }
        val bitmap = compose.onNode(isDialog()).captureToImage().asAndroidBitmap()
        File(directory, "pen-settings-narrow-large-text.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        compose.onNodeWithText("Done").performClick()
        compose.waitForIdle()
        assertTrue(closed)
    }
}
