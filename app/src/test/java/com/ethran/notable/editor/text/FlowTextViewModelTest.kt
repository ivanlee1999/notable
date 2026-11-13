package com.ethran.notable.editor.text

import androidx.lifecycle.viewModelScope
import com.ethran.notable.data.JournalRepository
import com.ethran.notable.data.db.Block
import com.ethran.notable.io.PageContentRenderer
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.Date

@OptIn(ExperimentalCoroutinesApi::class)
class FlowTextViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = mockk<FlowTextRepository>()
    private val journal = mockk<JournalRepository>(relaxed = true)
    private val source = MutableStateFlow(emptyList<Block>())
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private lateinit var model: FlowTextViewModel
    private var revision = 0L
    private val a = Block(id = "a", pageId = "page", text = "A", orderKey = "a", createdAt = Date(1))
    private val b = a.copy(id = "b", text = "B", orderKey = "b")
    private var loaded = document(listOf(a))
    private val persisted = mutableListOf<FlowDraft>()
    private val saved = mutableListOf<FlowDraft>()

    private fun document(blocks: List<Block>, page: String = "page") = FlowDocument(page, "book",
        listOf(FlowPart.Text("run")), mapOf("run" to FlowRun("run", blocks,
            com.ethran.notable.markdown.MarkdownBlocks.join(blocks.map { it.text.orEmpty() }))), false, emptyList())

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        every { repository.nextRevision() } answers { ++revision }
        every { repository.observe(any()) } returns source
        coEvery { repository.load(any()) } answers { loaded.copy(revision = ++revision) }
        coEvery { repository.persist(any()) } coAnswers { persisted += firstArg<FlowDraft>() }
        coEvery { repository.save(any()) } coAnswers {
            val draft = firstArg<FlowDraft>(); saved += draft
            FlowSaveResult(draft.runs.associate { run -> run.token to run.original.map { it.block(draft.pageId) } }, emptySet(), emptyList())
        }
        coEvery { repository.keepBoth(any(), any(), any()) } returns Unit
        model = FlowTextViewModel(repository, journal, mockk<PageContentRenderer>(), scope, dispatcher)
    }

    @After fun cleanup() { model.viewModelScope.cancel(); scope.cancel(); Dispatchers.resetMain() }

    @Test fun compositionIsDurableButDoesNotAutosaveUntilTheImeCommits() = runTest(dispatcher) {
        model.open("page"); runCurrent()
        model.change("run", "中", true)
        advanceTimeBy(2_000); runCurrent()
        assertEquals("中", persisted.last().runs.single().source)
        assertTrue(saved.isEmpty())
        model.change("run", "中", false)
        advanceUntilIdle()
        assertEquals("中", saved.last().runs.single().source)
    }

    @Test fun aFailedSavePreventsNavigationAndRetainsTheNativeSource() = runTest(dispatcher) {
        model.open("page"); runCurrent()
        model.change("run", "Unsent thought", true); runCurrent()
        coEvery { repository.save(any()) } throws IOException("disk unavailable")
        var opened = false
        model.newThought { _, _ -> opened = true }
        assertFalse(opened)
        assertEquals("Unsent thought", model.state.value.document?.runs?.get("run")?.source)
        assertNotNull(model.state.value.error)
        coVerify(exactly = 0) { journal.createEntry(any(), any()) }
    }

    @Test fun untouchedRemoteParagraphDoesNotBecomeALocalEditOnTheSecondAutosave() = runTest(dispatcher) {
        loaded = document(listOf(a, b)); model.open("page"); runCurrent()
        coEvery { repository.save(any()) } coAnswers {
            val draft = firstArg<FlowDraft>(); saved += draft
            FlowSaveResult(mapOf("run" to listOf(a.copy(text = "A local"), b.copy(text = "B remote"))), emptySet(), emptyList())
        }
        model.change("run", "A local\n\nB", true); model.flush()
        assertEquals("B", model.state.value.document!!.runs.getValue("run").original[1].text)
        model.change("run", "A local again\n\nB", true); model.flush()
        assertEquals("B", saved.last().runs.single().original[1].text)
        assertEquals("A local again\n\nB", saved.last().runs.single().source)
    }

    @Test fun keepBothCompletionCannotReplaceTextTypedWhileItsIoWasRunning() = runTest(dispatcher) {
        model.open("page"); runCurrent()
        model.change("run", "My branch", true)
        coEvery { repository.save(any()) } returns FlowSaveResult(emptyMap(), setOf("run"), emptyList())
        assertFalse(model.flush())
        val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        coEvery { repository.keepBoth(any(), any(), any()) } coAnswers { started.complete(Unit); finish.await() }
        model.keepBoth(); runCurrent(); started.await()
        model.change("run", "Newer typing", true)
        finish.complete(Unit); runCurrent()
        assertEquals("Newer typing", model.state.value.document!!.runs.getValue("run").source)
        assertEquals("Newer typing", persisted.last().runs.single().source)
    }

    @Test fun anUntouchedRunRetainsDeletedParagraphIdsUntilTheVisibleFieldReloads() = runTest(dispatcher) {
        loaded = document(listOf(a, b)); model.open("page"); runCurrent()
        coEvery { repository.save(any()) } returns FlowSaveResult(mapOf("run" to listOf(b)), emptySet(), emptyList())
        assertTrue(model.flush())
        assertEquals(listOf(a, b), model.state.value.document!!.runs.getValue("run").original)
        assertEquals("A\n\nB", model.state.value.document!!.runs.getValue("run").source)
    }

    @Test fun restoreUsesTheInitiatingDraftFenceAndRetainsNewerTyping() = runTest(dispatcher) {
        model.open("page"); runCurrent()
        val initiating = model.state.value.document!!.revision
        val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        var fence = -1L
        coEvery { repository.keepBoth(any(), any(), any()) } coAnswers {
            fence = thirdArg(); started.complete(Unit); finish.await()
        }
        model.restoreVersion(a.copy(text = "A recovered version")); runCurrent(); started.await()
        model.change("run", "Newer live source", true)
        finish.complete(Unit); runCurrent()
        assertEquals(initiating, fence)
        assertEquals("Newer live source", model.state.value.document!!.runs.getValue("run").source)
        assertEquals("Newer live source", persisted.last().runs.single().source)
    }

    @Test fun explicitFlushDrainsTypingThatArrivesDuringTheFirstWrite() = runTest(dispatcher) {
        model.open("page"); runCurrent(); model.change("run", "First", true)
        val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        coEvery { repository.save(any()) } coAnswers {
            val draft = firstArg<FlowDraft>(); saved += draft
            if (saved.size == 1) { started.complete(Unit); finish.await() }
            FlowSaveResult(mapOf("run" to listOf(a.copy(text = draft.runs.single().source))), emptySet(), emptyList())
        }
        val flushing = async { model.flush() }; runCurrent(); started.await()
        model.change("run", "Latest", true); finish.complete(Unit); runCurrent()
        assertTrue(flushing.await())
        assertEquals(listOf("First", "Latest"), saved.map { it.runs.single().source })
    }

    @Test fun aFailedContextChangeKeepsThePreviousPageSourceVisible() = runTest(dispatcher) {
        model.open("page"); runCurrent(); model.change("run", "Still on old page", true)
        coEvery { repository.save(any()) } throws IOException("disk unavailable")
        loaded = document(listOf(a.copy(pageId = "other")), "other")
        model.open("other"); runCurrent()
        assertEquals("page", model.state.value.document?.pageId)
        assertEquals("Still on old page", model.state.value.document!!.runs.getValue("run").source)
        assertNotNull(model.state.value.error)
    }

    @Test fun newThoughtDoesNotMultiplyUntouchedCaptures() = runTest(dispatcher) {
        loaded = document(emptyList()); model.open("page"); runCurrent()
        model.newThought { _, _ -> fail("No blank capture should be created") }
        coVerify(exactly = 0) { journal.createEntry(any(), any()) }
    }

    @Test fun switchingAfterNewInkMarksCanvasWithoutReplacingAnUnsentTypedDraft() = runTest(dispatcher) {
        model.open("page"); runCurrent()
        model.change("run", "Unsent source", true)
        model.prepareForTyping(hasCanvas = true); runCurrent()
        assertTrue(model.state.value.document!!.hasCanvas)
        assertEquals("Unsent source", model.state.value.document!!.runs.getValue("run").source)
        coVerify(exactly = 1) { repository.load("page") }
    }
}
