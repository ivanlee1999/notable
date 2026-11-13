package com.ethran.notable.editor.topics

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.viewModelScope
import com.ethran.notable.data.JournalEntry
import com.ethran.notable.data.JournalRepository
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.db.StrokePoint
import com.ethran.notable.editor.utils.Pen
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.Date

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EditorTopicsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val source = MutableStateFlow(listOf(JournalEntry("other", null, 1, listOf("#Idea", "Café"))))
    private val journal = mockk<JournalRepository>(relaxed = true)
    private val preferences = mockk<SharedPreferences>(relaxed = true)
    private val editor = mockk<SharedPreferences.Editor>(relaxed = true)
    private val context = mockk<Context>()
    private val models = mutableListOf<EditorTopicsViewModel>()

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        every { journal.entries } returns source
        every { context.getSharedPreferences(any(), any()) } returns preferences
        every { preferences.getString("language", null) } returns "en-US"
        every { preferences.edit() } returns editor
        every { editor.putString(any(), any()) } returns editor
    }
    @After fun cleanup() { models.forEach { it.viewModelScope.cancel() }; Dispatchers.resetMain() }

    private fun model(result: suspend (String) -> String = { "#Product" }): EditorTopicsViewModel {
        val recognizer = object : InkTopicRecognizer() {
            override fun languages() = listOf(TopicLanguage("en-US", "English"), TopicLanguage("fr", "French"))
            override suspend fun recognize(strokes: List<TopicInkStroke>, language: String, onDownloading: () -> Unit): String {
                onDownloading(); return result(language)
            }
        }
        return EditorTopicsViewModel(journal, recognizer, context).also { models += it }
    }
    private fun stroke(id: String = "ink", time: Long = 1) = Stroke(id = id, size = 2f, pen = Pen.BALLPEN,
        top = 10f, bottom = 30f, left = 20f, right = 40f, pageId = "page", createdAt = Date(time),
        points = listOf(StrokePoint(20f, 10f, dt = 0u), StrokePoint(40f, 30f, dt = 40u)))

    @Test fun copiedInkPreservesOriginalAndUsesStrokeTimeOrder() {
        val old = stroke("first", 10)
        val newer = stroke("second", 100)
        val copy = topicInk(listOf(newer, old))
        assertEquals(listOf(0L, 40L), copy[0].points.map { it.time })
        assertEquals(listOf(90L, 130L), copy[1].points.map { it.time })
        assertEquals(TopicInkPoint(0f, 0f, 0), copy.first().points.first())
        old.points.first().y = 500f
        assertEquals(0f, copy.first().points.first().y, 0f)
        assertEquals(2, old.points.size)
    }

    @Test fun choosingCanonicalHashTopicFromAnotherNoteEscapesOnlyOnce() = runTest(dispatcher) {
        val model = model(); runCurrent(); model.open("target", "page")
        model.topic("#Idea", true); runCurrent()
        coVerify(exactly = 1) { journal.addTopic("target", "##Idea") }
        model.topic("#Idea", false); runCurrent()
        coVerify(exactly = 1) { journal.removeTopic("target", "#Idea") }
        model.topic("##Typed", true, existing = false); runCurrent()
        coVerify(exactly = 1) { journal.addTopic("target", "##Typed") }
    }

    @Test fun recognitionDoesNotOverwriteTypingAndExplicitRetryCanReplaceIt() = runTest(dispatcher) {
        val result = CompletableDeferred<String>()
        val model = model { result.await() }; runCurrent(); model.open("target", "page", listOf(stroke())); runCurrent()
        assertTrue(model.recognizing.value)
        model.editCandidate("My topic"); result.complete("#Product"); runCurrent()
        assertEquals("My topic", model.candidate.value)
        model.recognize(); runCurrent()
        assertEquals("#Product", model.candidate.value)
        coVerify(exactly = 0) { journal.addTopic(any(), any()) }
    }

    @Test fun navigationCancelsRecognitionAndCannotTagNewNotebook() = runTest(dispatcher) {
        val result = CompletableDeferred<String>()
        val model = model { result.await() }; runCurrent(); model.open("old", "page", listOf(stroke())); runCurrent()
        model.retainContext("new", "other-page"); result.complete("#Old"); runCurrent()
        assertNull(model.request.value); assertFalse(model.recognizing.value)
        model.topic("Old", true, existing = false); runCurrent()
        coVerify(exactly = 0) { journal.addTopic(any(), any()) }
        model.open("new", "other-page"); model.topic("Café", true); runCurrent()
        coVerify(exactly = 1) { journal.addTopic("new", "Café") }
    }

    @Test fun recognitionFailureKeepsManualTopicEntryAvailable() = runTest(dispatcher) {
        val model = model { error("offline") }; runCurrent(); model.open("target", "page", listOf(stroke())); runCurrent()
        assertNotNull(model.error.value); assertFalse(model.recognizing.value)
        model.topic("Manual", true, existing = false); runCurrent()
        coVerify(exactly = 1) { journal.addTopic("target", "Manual") }
    }

    @Test fun regionalLocaleUsesItsAvailableLanguageBeforeEnglishFallback() = runTest(dispatcher) {
        every { preferences.getString("language", null) } returns "fr-FR"
        val model = model(); runCurrent(); model.open("target", "page")
        assertEquals("fr", model.language.value)
    }
}
