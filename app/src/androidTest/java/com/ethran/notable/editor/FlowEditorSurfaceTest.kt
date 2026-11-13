package com.ethran.notable.editor

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import com.ethran.notable.data.JournalRepository
import com.ethran.notable.data.db.Block
import com.ethran.notable.editor.text.*
import com.ethran.notable.io.PageContentRenderer
import com.ethran.notable.testing.ComposeUiSupportRule
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class FlowEditorSurfaceTest {
    private val compose = createComposeRule()
    @get:Rule val gate = ComposeUiSupportRule(compose)
    private val repository = mockk<FlowTextRepository>()
    private val journal = mockk<JournalRepository>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val saved = ConcurrentLinkedQueue<FlowDraft>()
    private lateinit var model: FlowTextViewModel
    private val opened = AtomicReference<Pair<String, String>?>(null)
    private var left = false

    private fun render(failSave: Boolean = false) {
        val sequence = AtomicLong()
        val source = MutableStateFlow(emptyList<Block>())
        every { repository.nextRevision() } answers { sequence.incrementAndGet() }
        every { repository.observe(any()) } returns source
        val canonical = AtomicReference(FlowDocument("page", "book", listOf(FlowPart.Text("run")),
            mapOf("run" to FlowRun("run", emptyList(), "")), false, emptyList()))
        coEvery { repository.load("page") } answers { canonical.get() }
        coEvery { repository.persist(any()) } returns Unit
        coEvery { repository.save(any()) } coAnswers {
            val draft = firstArg<FlowDraft>(); saved += draft
            if (failSave) throw IOException("disk unavailable")
            val run = draft.runs.single()
            val written = listOf(Block(id = "written", pageId = "page", text = run.source))
            // Blur refreshes clean text from disk. Model that disk write instead of returning
            // the initial empty fixture after an autosave won the race with the button tap.
            canonical.set(canonical.get().copy(runs = mapOf("run" to FlowRun("run", written, run.source)), revision = draft.revision))
            FlowSaveResult(mapOf("run" to written), emptySet(), emptyList())
        }
        coEvery { journal.createEntry(any(), any()) } returns ("new-book" to "new-page")
        model = FlowTextViewModel(repository, journal, mockk<PageContentRenderer>(), scope, Dispatchers.IO)
        model.open("page")
        compose.setContent { FlowEditorSurface(model, "Today", { left = true }, {}, {}, {}, { page, book -> opened.set(page to book) }) }
        compose.waitUntil(5_000) { model.state.value.document != null }
    }

    @After fun cleanup() {
        if (::model.isInitialized) model.viewModelScope.cancel()
        scope.cancel()
    }

    @Test fun nativeMultilineCaptureAndNewThoughtFlushTheSourceBeforeNavigation() {
        render()
        compose.onNodeWithContentDescription("Entry text").performClick().performTextInput("First thought\nSecond line")
        compose.onNodeWithContentDescription("Checklist").performClick()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.getExternalFilesDir(null), "ui-review").apply { mkdirs() }
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            File(directory, "native-flow-typing.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        }
        compose.onNodeWithText("+ New thought").performClick()
        try { compose.waitUntil(5_000) { opened.get() != null } }
        catch (failure: Throwable) {
            throw AssertionError("New thought did not navigate: state=${model.state.value}; saved=${saved.map { it.runs.map { run -> run.source } }}", failure)
        }
        assertEquals("new-page" to "new-book", opened.get())
        assertTrue(saved.last().runs.single().source.contains("\n"))
        assertTrue(saved.last().runs.single().source.contains("- [ ]"))
        assertTrue(saved.any { it.runs.single().intendedIds.isNotEmpty() })
    }

    @Test fun aFailedSaveKeepsTheEditableTextAndBlocksLeaving() {
        render(failSave = true)
        compose.onNodeWithContentDescription("Entry text").performClick().performTextInput("Keep this unsent thought")
        compose.onNodeWithContentDescription("Back to journal").performClick()
        compose.waitUntil(5_000) { model.state.value.error != null }
        assertFalse(left)
        compose.onNodeWithContentDescription("Entry text").assertTextContains("Keep this unsent thought")
        assertEquals("Keep this unsent thought", model.state.value.document!!.runs.getValue("run").source)
        coVerify(atLeast = 1) { repository.persist(match { it.runs.single().source == "Keep this unsent thought" }) }
    }
}
