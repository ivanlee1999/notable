package com.ethran.notable.editor.topics

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ethran.notable.data.JournalEntry
import com.ethran.notable.data.JournalRepository
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.db.StrokePoint
import com.ethran.notable.editor.ToolbarUiState
import com.ethran.notable.editor.ui.EditorTitleBar
import com.ethran.notable.editor.utils.Pen
import com.ethran.notable.testing.ComposeUiSupportRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class EditorTopicsUiTest {
    private val compose = createComposeRule()
    @get:Rule val composeGate = ComposeUiSupportRule(compose)
    private val source = MutableStateFlow(listOf(JournalEntry("note", null, 1, listOf("French")),
        JournalEntry("other", null, 2, listOf("#Idea"))))
    private val journal = mockk<JournalRepository>()
    private lateinit var model: EditorTopicsViewModel

    private fun render(fontScale: Float = 1f) {
        every { journal.entries } returns source
        coEvery { journal.addTopic(any(), any()) } coAnswers {
            val id = firstArg<String>(); val raw = secondArg<String>()
            source.value = source.value.map { if (it.notebookId == id) it.copy(topics = (it.topics + raw.removePrefix("#")).distinct()) else it }
        }
        coEvery { journal.removeTopic(any(), any()) } coAnswers {
            val id = firstArg<String>(); val name = secondArg<String>()
            source.value = source.value.map { if (it.notebookId == id) it.copy(topics = it.topics - name) else it }
        }
        model = EditorTopicsViewModel(journal, object : InkTopicRecognizer() {
            override fun languages() = listOf(TopicLanguage("en-US", "English"))
            override suspend fun recognize(strokes: List<TopicInkStroke>, language: String, onDownloading: () -> Unit) = "#Product"
        }, ApplicationProvider.getApplicationContext<Context>())
        compose.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(DpSize(320.dp, 640.dp)) then
                    DeviceConfigurationOverride.FontScale(fontScale)
            ) {
                val entries by model.entries.collectAsStateWithLifecycle()
                EditorTitleBar(ToolbarUiState(notebookId = "note", pageId = "page", notebookTitle = "Notes"), {},
                    topics = { EditorTopicButton(entries.firstOrNull { it.notebookId == "note" }?.topics.orEmpty()) { model.open("note", "page") } })
                EditorTopicsPopup(model)
            }
        }
        compose.waitUntil { model.ready.value }
    }

    @After fun cleanup() { if (::model.isInitialized) model.viewModelScope.cancel() }

    @Test fun phoneWithLargeTextKeepsTheTagControlAndDropdownReachable() {
        render(fontScale = 1.5f)
        compose.onNodeWithContentDescription("Edit note topics")
            .assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithContentDescription("Find or create topic").assertIsDisplayed()
        compose.onNodeWithText("Done").assertIsDisplayed()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "ui-review").apply { mkdirs() }
        File(directory, "editor-topics-phone.png").outputStream().use {
            check(compose.onNode(isPopup()).captureToImage().asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    @Test fun titleBarAddsAndRemovesTopicsWithoutLeavingTheEditor() {
        render()
        compose.onNodeWithContentDescription("Edit note topics").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithContentDescription("Find or create topic").performTextInput("Work")
        compose.onNodeWithText("Add #Work").performClick()
        compose.waitUntil { source.value.first().topics.contains("Work") }
        compose.onNodeWithContentDescription("Remove topic Work").performClick()
        compose.waitUntil { !source.value.first().topics.contains("Work") }
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithContentDescription("Edit note topics").assertIsDisplayed()
        coVerify(exactly = 1) { journal.addTopic("note", "Work") }
        coVerify(exactly = 1) { journal.removeTopic("note", "Work") }
    }

    @Test fun existingLiteralHashTopicKeepsItsIdentityAcrossNotes() {
        render()
        compose.onNodeWithContentDescription("Edit note topics").performClick()
        compose.onNodeWithText("#Idea", useUnmergedTree = true).performClick()
        compose.waitUntil { source.value.first().topics.contains("#Idea") }
        compose.onNodeWithContentDescription("Remove topic #Idea").assertIsDisplayed()
        coVerify(exactly = 1) { journal.addTopic("note", "##Idea") }
    }

    @Test fun handwritingNeedsConfirmationAndLeavesSelectedInkUntouched() {
        render()
        val stroke = Stroke(size = 2f, pen = Pen.BALLPEN, top = 1f, bottom = 20f, left = 1f, right = 20f,
            pageId = "page", points = listOf(StrokePoint(1f, 1f), StrokePoint(20f, 20f)))
        val original = stroke.points.map { it.copy() }
        compose.runOnIdle { model.open("note", "page", listOf(stroke)) }
        compose.waitUntil { model.candidate.value == "#Product" }
        coVerify(exactly = 0) { journal.addTopic(any(), any()) }
        compose.onNodeWithText("Add #Product").performClick()
        compose.waitUntil { source.value.first().topics.contains("Product") }
        assertEquals(original, stroke.points)
        compose.runOnIdle { model.retainContext("new-note", "new-page") }
        compose.onNodeWithText("Done").assertDoesNotExist()
    }
}
