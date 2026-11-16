package com.ethran.notable.ui.components

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.ethran.notable.testing.ComposeUiSupportRule
import com.ethran.notable.ui.theme.InkaTheme
import com.ethran.notable.ui.theme.Kaleido
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class TranscriptionTextTest {
    private val compose = createComposeRule()
    @get:Rule val gate = ComposeUiSupportRule(compose)
    private val mixed = "# Project\n\n## Tasks\n- [x] Done\n- [ ] Next\n  - detail\n1. First\n2. Second\n\n" +
        "| Item | Qty | Note |\n| :--- | ---: | :---: |\n| Milk | 2 | |\n| A \\| B | 1 | yes |"
    private var source by mutableStateOf(mixed)
    private var format by mutableStateOf<String?>("markdown")
    private fun render() { compose.setContent { InkaTheme {
        Column(Modifier.fillMaxSize().background(Kaleido.Paper).verticalScroll(rememberScrollState()).padding(12.dp)) {
            TranscriptionText(source, format, Modifier.fillMaxWidth())
        }
    } } }
    private fun screenshot(suffix: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val label = InstrumentationRegistry.getArguments().getString("layoutSize") ?: "native"
        val file = File(context.filesDir, "layout-review/$label-$suffix.png")
        file.parentFile?.mkdirs()
        compose.onRoot().captureToImage().asAndroidBitmap().let { image ->
            file.outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        }
    }

    @Test fun mixedHeadingsNestedTasksAndTableAreReadableAndInertAtTheNativeWidth() {
        render()
        compose.onNodeWithText("Project").assertIsDisplayed()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).assertCountEquals(2)
        compose.onNodeWithText("Done").assertIsOn().assertIsNotEnabled().assertHasNoClickAction()
        compose.onNodeWithText("Next").assertIsOff().assertIsNotEnabled().assertHasNoClickAction()
        val parent = compose.onNodeWithText("Next").fetchSemanticsNode().boundsInRoot.left
        val nested = compose.onNodeWithText("detail").fetchSemanticsNode().boundsInRoot.left
        assertTrue("Nested list must be indented", nested > parent)
        compose.onNodeWithText("1.").assertExists(); compose.onNodeWithText("2.").assertExists()
        screenshot("mixed")
        val table = compose.onNodeWithContentDescription("Recognized table")
        table.performScrollTo()
        compose.onNodeWithText("A | B").assertIsDisplayed()
        compose.onNodeWithText("yes").assertExists()
        val range = table.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
        if (range.maxValue() > 0f) {
            table.performTouchInput { swipeLeft() }
            compose.runOnIdle { assertTrue(range.value() > 0f) }
        }
        screenshot("table")
        compose.onNodeWithText("Done").performScrollTo().performTouchInput { click() }
        compose.onNodeWithText("Done").assertIsOn()
        assertEquals(mixed, source)
    }

    @Test fun missingAndUnknownFormatsStayLiteralWhileMarkdownOptInChangesPresentationOnly() {
        format = null; render()
        compose.onNodeWithContentDescription("Recognized handwriting").assertTextEquals(mixed)
        compose.onNodeWithContentDescription("Recognized table").assertDoesNotExist()
        compose.runOnIdle { format = "future-format" }
        compose.onNodeWithContentDescription("Recognized handwriting").assertTextEquals(mixed)
        compose.runOnIdle { format = "markdown" }
        compose.onNodeWithText("Project").assertExists()
        compose.onNodeWithText("Done").assertIsOn()
        assertEquals(mixed, source)
    }

    @Test fun generatedLinksImagesHtmlAndCodeNeverBecomeActionsOrRequests() {
        source = "[Visible label](https://invalid.test/private)\n![alt](https://invalid.test/image.png)\n" +
            "<img src=https://invalid.test/unsafe>\n```\n- [x] literal code\n```"
        render()
        compose.onNodeWithText("Visible label").assertExists().assertHasNoClickAction()
        compose.onNodeWithText("!alt").assertExists().assertHasNoClickAction()
        compose.onNodeWithText("<img src=https://invalid.test/unsafe>").assertExists()
        compose.onNodeWithText("- [x] literal code").assertExists()
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
        compose.onAllNodes(isToggleable()).assertCountEquals(0)
    }
}
