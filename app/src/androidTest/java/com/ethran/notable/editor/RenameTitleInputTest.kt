package com.ethran.notable.editor

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.geometry.Offset
import com.ethran.notable.editor.topics.InkTopicRecognizer
import com.ethran.notable.testing.ComposeUiSupportRule
import com.ethran.notable.ui.dialogs.RenameTitleInput
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class RenameTitleInputTest {
    private val compose = createComposeRule()
    @get:Rule val gate = ComposeUiSupportRule(compose)
    private val recognizer = mockk<InkTopicRecognizer>()
    private val saved = AtomicReference<String?>(null)

    private fun render() {
        compose.setContent { RenameTitleInput("Original", recognizer, "en-US", false, null, saved::set, {}) }
        compose.onNodeWithText("Write", useUnmergedTree = true).performClick()
        compose.onNodeWithContentDescription("Write a name").performTouchInput {
            swipe(Offset(20f, 20f), Offset(100f, 50f), durationMillis = 200)
        }
    }

    @Test fun handwritingProducesAnEditableCandidateAndOnlySaveAcceptsIt() {
        coEvery { recognizer.recognize(any(), any(), any()) } returns "Handwritten name"
        render()
        compose.waitUntil(5_000) { runCatching {
            compose.onNodeWithContentDescription("Name candidate").assertTextContains("Handwritten name"); true
        }.getOrDefault(false) }
        assertNull(saved.get())
        compose.onNodeWithText("Save").performClick()
        assertEquals("Handwritten name", saved.get())
    }

    @Test fun lateRecognitionCannotReplaceATypedCandidate() {
        val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<String>(); val returned = CompletableDeferred<Unit>()
        coEvery { recognizer.recognize(any(), any(), any()) } coAnswers {
            started.complete(Unit); finish.await().also { returned.complete(Unit) }
        }
        render()
        compose.waitUntil(5_000) { started.isCompleted }
        compose.onNodeWithContentDescription("Name candidate").performTextReplacement("Typed name")
        finish.complete("Late handwriting")
        compose.waitUntil(5_000) { returned.isCompleted }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Name candidate").assertTextContains("Typed name")
        compose.onNodeWithText("Save").performClick()
        assertEquals("Typed name", saved.get())
    }
}
