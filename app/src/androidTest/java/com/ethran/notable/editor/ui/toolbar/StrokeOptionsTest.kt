package com.ethran.notable.editor.ui.toolbar

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.ethran.notable.ui.theme.Kaleido
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ethran.notable.data.datastore.AppSettings
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.editor.utils.PenSetting
import com.ethran.notable.testing.ComposeUiSupportRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class StrokeOptionsTest {
    private val compose = createComposeRule()
    @get:Rule val composeGate = ComposeUiSupportRule(compose)

    private fun everyDiscreteSizeIsReachable(width: Int) {
        GlobalAppSettings.update(AppSettings(version = 1, continuousStrokeSlider = false))
        val changes = mutableListOf<PenSetting>()
        val sizes = (1..13).map { it.toString() to it.toFloat() }
        val ink = 0xff4a4a4a.toInt()
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(width.dp, 560.dp)) then
                DeviceConfigurationOverride.FontScale(1.5f)) {
                Column(Modifier.fillMaxWidth()) {
                    StrokeSizePicker(PenSetting(strokeSize = 1f, color = ink), changes::add,
                        sizes, widthOfPicker = width)
                }
            }
        }
        sizes.forEach { (label, size) ->
            compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
                .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
            assertEquals(PenSetting(strokeSize = size, color = ink), changes.last())
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.getExternalFilesDir(null), "ui-review").apply { mkdirs() }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(directory, "discrete-widths-$width-large-text.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    private fun fullPopup(continuous: Boolean): MutableList<PenSetting> {
        GlobalAppSettings.update(AppSettings(version = 1, continuousStrokeSlider = continuous,
            toolbarPosition = AppSettings.Position.Top))
        val changes = mutableListOf<PenSetting>()
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(320.dp, 560.dp)) then
                DeviceConfigurationOverride.FontScale(1.5f)) {
                var value by remember { mutableStateOf(PenSetting(1f, Kaleido.Inks.first())) }
                Column(Modifier.fillMaxWidth()) {
                    StrokeMenu(value, { value = it; changes += it }, {},
                        (1..13).map { it.toString() to it.toFloat() }, Kaleido.Inks.map { Color(it) })
                }
            }
        }
        return changes
    }

    @Test
    fun actualPopupKeepsEveryColorAndDiscreteSizeReachable() {
        val changes = fullPopup(continuous = false)
        Kaleido.Inks.forEach { ink ->
            compose.onNodeWithContentDescription(inkDescription(ink))
                .performScrollTo().assertIsDisplayed()
                .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
            assertEquals(PenSetting(1f, ink), changes.last())
            compose.onNodeWithContentDescription(inkDescription(ink)).assertIsSelected()
        }
        for (size in 1..13) {
            compose.onNodeWithText(size.toString()).performScrollTo().assertIsDisplayed()
                .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
            assertEquals(PenSetting(size.toFloat(), Kaleido.Inks.last()), changes.last())
            compose.onNodeWithText(size.toString()).assertIsSelected()
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.getExternalFilesDir(null), "ui-review").apply { mkdirs() }
        val bitmap = compose.onNode(isPopup()).captureToImage().asAndroidBitmap()
        File(directory, "stroke-popup-320-large-text.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    @Test
    fun continuousPopupKeepsNewInkWhenItsWidthChanges() {
        val changes = fullPopup(continuous = true)
        val ink = Kaleido.Inks.last()
        compose.onNodeWithContentDescription(inkDescription(ink)).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Stroke width").assertIsDisplayed()
            .performTouchInput { click(Offset(width - 1f, height / 2f)) }
        assertEquals(PenSetting(13f, ink), changes.last())
    }

    @Test fun discreteSizesRemainReachableAt320dp() = everyDiscreteSizeIsReachable(320)
    @Test fun discreteSizesRemainReachableAt600dp() = everyDiscreteSizeIsReachable(600)
}
