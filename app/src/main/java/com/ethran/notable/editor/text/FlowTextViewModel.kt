package com.ethran.notable.editor.text

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ethran.notable.data.JournalRepository
import com.ethran.notable.di.ApplicationScope
import com.ethran.notable.di.IoDispatcher
import com.ethran.notable.io.PageContentRenderer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import com.ethran.notable.data.db.Block
import com.ethran.notable.markdown.MarkdownBlocks
import javax.inject.Inject

data class FlowEditorState(
    val document: FlowDocument? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val conflicts: Set<String> = emptySet(),
    val durableRevision: Long = document?.revision ?: 0,
)

@HiltViewModel
class FlowTextViewModel @Inject constructor(
    private val repository: FlowTextRepository,
    private val journal: JournalRepository,
    private val renderer: PageContentRenderer,
    @param:ApplicationScope private val applicationScope: CoroutineScope,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {
    private val mutableState = MutableStateFlow(FlowEditorState())
    val state = mutableState.asStateFlow()
    private val saveMutex = Mutex()
    private var opening = 0L
    private var debounce: Job? = null
    private val composingRuns = mutableSetOf<String>()
    private val focusedRuns = mutableSetOf<String>()
    private var observing: Job? = null
    private var requestedPage: String? = null

    fun open(pageId: String) {
        requestedPage = pageId
        if (mutableState.value.document?.pageId == pageId) return
        val previous = mutableState.value.document
        val request = ++opening
        composingRuns.clear(); focusedRuns.clear()
        observing?.cancel()
        mutableState.update { it.copy(loading = true) }
        viewModelScope.launch {
            try {
                if (previous != null && !saveDocument(previous)) {
                    if (request == opening) mutableState.update { it.copy(loading = false) }
                    return@launch
                }
                val document = withContext(io) { repository.load(pageId) }
                if (request == opening) {
                    mutableState.value = FlowEditorState(document)
                    observing?.cancel()
                    observing = viewModelScope.launch {
                        repository.observe(pageId).drop(1).collect { refreshIfClean() }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (request == opening) mutableState.value = FlowEditorState(error = "Could not load text. Your saved draft is kept on this device.")
            }
        }
    }

    fun change(token: String, source: String, composing: Boolean) {
        val document = mutableState.value.document ?: return
        val run = document.runs[token] ?: return
        val wasComposing = token in composingRuns
        if (composing) composingRuns += token else composingRuns -= token
        if (run.source == source && wasComposing == composing) return
        val candidates = run.intendedIds.toMutableList()
        while (candidates.size < (MarkdownBlocks.split(source).size - run.original.size).coerceAtLeast(0))
            candidates += java.util.UUID.randomUUID().toString()
        val updated = document.copy(runs = document.runs + (token to run.copy(source = source, intendedIds = candidates)),
            revision = repository.nextRevision())
        mutableState.update { it.copy(document = updated, error = null) }
        persist(updated)
        debounce?.cancel()
        if (!composing) debounce = viewModelScope.launch {
            delay(500)
            // The write outlives this debounce job and the editor's composition.
            applicationScope.launch { flush() }
        }
    }

    private fun persist(document: FlowDocument) {
        applicationScope.launch(io) {
            try {
                repository.persist(document.snapshot())
                mutableState.update { state -> if (state.document?.pageId == document.pageId &&
                    state.document.revision == document.revision) state.copy(durableRevision = document.revision) else state }
            }
            catch (_: Exception) {
                mutableState.update { state -> if (state.document?.pageId == document.pageId &&
                    state.document.revision == document.revision) state.copy(error = "Could not save text. Keep this note open and try again.") else state }
            }
        }
    }

    /** Called before leaving and on background. Failed saves retain both the field and its draft. */
    suspend fun flush(): Boolean {
        while (true) {
            val document = mutableState.value.document ?: return !mutableState.value.loading
            if (!saveDocument(document)) return false
            val current = mutableState.value.document ?: return false
            if (current.pageId != document.pageId) return false
            if (current.revision == document.revision) return true
        }
    }

    private suspend fun saveDocument(document: FlowDocument): Boolean = saveMutex.withLock {
        try {
            val result = withContext(io) { repository.save(document.snapshot()) }
            var newer: FlowDocument? = null
            mutableState.update { state ->
                val current = state.document
                if (current?.pageId != document.pageId) return@update state
                val updated = current.copy(runs = current.runs.mapValues { (token, run) ->
                    result.originals[token]?.let { saved ->
                        val sent = document.runs[token] ?: return@let run
                        // A peer may have removed a paragraph from an untouched run. Keep the
                        // field's exact baseline until reload; never zip surviving ids to it.
                        if (sent.source == MarkdownBlocks.join(sent.original.map { it.text.orEmpty() })) return@let run
                        val visible = MarkdownBlocks.split(sent.source)
                        // Rebase only what the field displayed. A peer's untouched paragraph
                        // must not become an apparent local edit on the next keystroke.
                        val based = saved.mapIndexed { index, block ->
                            block.copy(text = visible.getOrElse(index) { block.text.orEmpty() })
                        }
                        val count = (MarkdownBlocks.split(run.source).size - based.size).coerceAtLeast(0)
                        run.copy(original = based, intendedIds = List(count) { java.util.UUID.randomUUID().toString() })
                    } ?: run
                }, versions = result.versions,
                    revision = if (current.revision != document.revision) repository.nextRevision() else current.revision)
                if (current.revision != document.revision) newer = updated
                state.copy(document = updated, conflicts = result.conflicts,
                    durableRevision = if (current.revision == document.revision) current.revision else state.durableRevision,
                    error =
                    if (result.conflicts.isEmpty()) null else "This text changed on another device. Your version is saved; keep both to continue.")
            }
            newer?.let(::persist)
            result.conflicts.isEmpty()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            mutableState.update { state -> if (state.document?.pageId == document.pageId)
                state.copy(error = "Could not save text. Your draft is kept; try again before leaving.") else state }
            false
        }
    }

    fun flushInBackground() {
        val document = mutableState.value.document ?: return
        applicationScope.launch { saveDocument(document) }
    }

    fun finishOnDispose(after: () -> Unit) {
        val document = mutableState.value.document
        applicationScope.launch {
            if (document != null) saveDocument(document)
            withContext(Dispatchers.Main) { after() }
        }
    }

    fun retry() {
        if (mutableState.value.document == null) requestedPage?.let(::open)
        else applicationScope.launch { flush() }
    }

    fun focus(token: String, active: Boolean) {
        if (active) focusedRuns += token else { focusedRuns -= token; composingRuns -= token; refreshIfClean() }
    }

    fun prepareForTyping(hasCanvas: Boolean) {
        if (hasCanvas) mutableState.update { state ->
            state.copy(document = state.document?.copy(hasCanvas = true))
        }
        refreshIfClean()
    }

    fun refreshIfClean() {
        val current = mutableState.value.document ?: return
        if (focusedRuns.isNotEmpty() || composingRuns.isNotEmpty() || current.runs.values.any {
            com.ethran.notable.markdown.MarkdownBlocks.split(it.source) != it.original.map { b -> b.text.orEmpty() }
        }) return
        viewModelScope.launch {
            try {
                val loaded = withContext(io) { repository.load(current.pageId) }
                if (mutableState.value.document === current && focusedRuns.isEmpty()) mutableState.value = FlowEditorState(loaded)
            } catch (_: Exception) { /* The current readable document remains available. */ }
        }
    }

    fun keepBoth() {
        val state = mutableState.value
        val document = state.document ?: return
        viewModelScope.launch {
            saveMutex.withLock {
                try {
                    withContext(io) { repository.keepBoth(document.snapshot(), state.conflicts) }
                    val loaded = withContext(io) { repository.load(document.pageId) }
                    val current = mutableState.value.document
                    if (current?.pageId == document.pageId && current.revision == document.revision)
                        mutableState.value = FlowEditorState(loaded)
                    else current?.takeIf { it.pageId == document.pageId }?.let(::persist)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { mutableState.update { it.copy(error = "Could not keep both versions. Your draft is still available.") } }
            }
        }
    }

    fun restoreVersion(version: Block) {
        viewModelScope.launch {
            if (!flush()) return@launch
            val document = mutableState.value.document ?: return@launch
            try {
                val token = "restored-${java.util.UUID.randomUUID()}"
                val draft = FlowDraft(document.pageId, repository.nextRevision(),
                    listOf(StoredFlowRun(token, emptyList(), version.text.orEmpty())))
                withContext(io) { repository.keepBoth(draft, setOf(token), clearDraftThrough = document.revision) }
                val loaded = withContext(io) { repository.load(document.pageId) }
                val current = mutableState.value.document
                if (current?.pageId == document.pageId && current.revision == document.revision)
                    mutableState.value = FlowEditorState(loaded)
                else current?.takeIf { it.pageId == document.pageId }?.let(::persist)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(error = "Could not restore this version. The saved version is unchanged.") } }
        }
    }

    suspend fun newThought(onOpen: (String, String) -> Unit) {
        if (!flush()) return
        val current = mutableState.value.document
        if (current != null && !current.hasCanvas && current.runs.values.all { it.source.isBlank() }) return
        try {
            val (book, page) = withContext(io) { journal.createEntry() }
            onOpen(page, book)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutableState.update { it.copy(error = "Could not create a new thought. Your current text is saved.") } }
    }

    suspend fun canvas(pageId: String): Bitmap = withContext(io) { renderer.renderCanvasBitmap(pageId, 720) }
}
