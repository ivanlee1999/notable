package com.ethran.notable.ui.views

import androidx.compose.foundation.background
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Checkbox
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.JournalEntry
import com.ethran.notable.data.JournalRepository
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.editor.utils.autoEInkAnimationOnScroll
import com.ethran.notable.navigation.NavigationDestination
import com.ethran.notable.ui.components.NotebookListRow
import com.ethran.notable.ui.components.RowRule
import com.ethran.notable.ui.components.TextAction
import com.ethran.notable.ui.dialogs.ActionButton
import com.ethran.notable.ui.dialogs.NamePromptDialog
import com.ethran.notable.ui.theme.Kaleido
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject

object TodayDestination : NavigationDestination { override val route = "today" }

data class JournalNote(val entry: JournalEntry, val notebook: Notebook)

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModel @Inject constructor(
    private val journal: JournalRepository,
    private val app: AppRepository,
) : ViewModel() {
    val error = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    val dataReady = MutableStateFlow(false)
    private val reload = MutableStateFlow(0)
    val notes = reload.flatMapLatest { combine(journal.entries, app.bookRepository.getAllFlow(),
        app.folderRepository.getAllFlow()) { entries, books, folders ->
        val folderMap = folders.associateBy { it.id }
        val visible = books.filter { book ->
            var parent = book.parentFolderId
            val seen = mutableSetOf<String>()
            var found = true
            while (parent != null && seen.add(parent)) {
                val folder = folderMap[parent] ?: break
                if (folder.deletedAt != null) { found = false; break }
                parent = folder.parentFolderId
            }
            found
        }.associateBy { it.id }
        entries.mapNotNull { entry -> visible[entry.notebookId]?.let { JournalNote(entry, it) } }
            .sortedWith(compareByDescending<JournalNote> { it.entry.createdAt }.thenBy { it.entry.notebookId })
    }.onEach { dataReady.value = true }
        .catch { dataReady.value = false; error.value = "Could not load notes." } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun retry() { dataReady.value = false; error.value = null; reload.value++ }

    suspend fun create(topics: List<String> = emptyList()): Pair<String, String>? {
        if (busy.value || !dataReady.value) return null
        busy.value = true
        error.value = null
        return try { journal.createEntry(topics) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error.value = "Could not create note. Try again."; null }
        finally { busy.value = false }
    }

    fun topic(note: JournalNote, name: String, selected: Boolean) {
        if (busy.value || !dataReady.value) return
        busy.value = true
        error.value = null
        viewModelScope.launch {
            try {
                if (selected) journal.addTopic(note.entry.notebookId, name)
                else journal.removeTopic(note.entry.notebookId, name)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error.value = "Could not save topics. Try again." }
            finally { busy.value = false }
        }
    }
}

@Composable
fun TodayView(
    onOpen: (String, String) -> Unit,
    onLibrary: () -> Unit,
    onPages: (String) -> Unit,
    viewModel: TodayViewModel = hiltViewModel(),
) {
    val notes by viewModel.notes.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val dataReady by viewModel.dataReady.collectAsStateWithLifecycle()
    var today by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var selectedDate by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var topicsView by rememberSaveable { mutableStateOf(false) }
    var selectedTopic by rememberSaveable { mutableStateOf<String?>(null) }
    var editingTopics by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val owner = LocalLifecycleOwner.current
    fun refreshDate() {
        val now = LocalDate.now().toString()
        if (selectedDate == today) selectedDate = now
        today = now
    }
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) { refreshDate(); delay(60_000) }
        }
    }

    val topics = notes.flatMap { it.entry.topics }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
    val shown = when {
        topicsView && selectedTopic != null -> notes.filter { selectedTopic in it.entry.topics }
        topicsView -> emptyList()
        else -> notes.filter { it.entry.date == selectedDate }
    }
    fun open(note: JournalNote) {
        val book = note.notebook
        val page = book.openPageId?.takeIf { it in book.pageIds } ?: book.pageIds.firstOrNull()
        if (page == null) onPages(book.id)
        else onOpen(page, book.id)
    }

    Column(Modifier.fillMaxSize().background(Kaleido.Paper)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (topicsView) selectedTopic?.let { "#$it" } ?: "Topics" else if (selectedDate == today) "Today" else "Journal",
                    fontSize = 24.sp, color = Kaleido.Ink, modifier = Modifier.weight(1f))
                ActionButton(if (busy) "…" else "New note", enabled = !busy && dataReady) {
                    val inherited = selectedTopic?.takeIf { topicsView }?.let(::listOf).orEmpty()
                    scope.launch {
                        viewModel.create(inherited)?.let { (book, page) ->
                            selectedDate = LocalDate.now().toString()
                            topicsView = false
                            onOpen(page, book)
                        }
                    }
                }
            }
            if (!topicsView) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextAction("‹", { selectedDate = LocalDate.parse(selectedDate).minusDays(1).toString() },
                    Modifier.semantics { contentDescription = "Previous day" })
                Text(LocalDate.parse(selectedDate).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)),
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp), color = Kaleido.Ink)
                TextAction("›", { selectedDate = LocalDate.parse(selectedDate).plusDays(1).toString() },
                    Modifier.semantics { contentDescription = "Next day" })
            }
            if (topicsView && selectedTopic != null) TextAction("All topics", { selectedTopic = null })
            error?.let { message -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(message, Modifier.weight(1f), color = Kaleido.Ink)
                if (!dataReady) TextAction("Retry", viewModel::retry)
                else TextAction("Dismiss", { viewModel.error.value = null })
            } }
        }
        RowRule()
        LazyColumn(Modifier.weight(1f).autoEInkAnimationOnScroll(), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (topicsView && selectedTopic == null) {
                items(topics, key = { it }) { topic ->
                    TextAction("#$topic", { selectedTopic = topic }, Modifier.fillMaxWidth())
                }
                if (topics.isEmpty()) item { Text("No topics yet", color = Kaleido.Muted) }
            } else {
                items(shown, key = { it.entry.notebookId }) { note ->
                    Column {
                        NotebookListRow(note.notebook, 48.dp, { open(note) }, { editingTopics = note.entry.notebookId },
                            location = if (topicsView) note.entry.date else null)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            note.entry.topics.forEach { topic ->
                                TextAction("#$topic", { topicsView = true; selectedTopic = topic })
                            }
                            TextAction("+ Topic", { editingTopics = note.entry.notebookId })
                        }
                    }
                }
                if (shown.isEmpty()) item { Text("No notes", color = Kaleido.Muted) }
            }
        }
        RowRule()
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextAction("Today", { topicsView = false; selectedDate = LocalDate.now().toString() },
                Modifier.weight(1f), filled = !topicsView)
            TextAction("Topics", { topicsView = true; selectedTopic = null }, Modifier.weight(1f), filled = topicsView)
            TextAction("Library", onLibrary, Modifier.weight(1f))
        }
    }
    notes.firstOrNull { it.entry.notebookId == editingTopics }?.let { note ->
        TopicsDialog(note, topics, busy, { name, selected -> viewModel.topic(note, name, selected) },
            { editingTopics = null })
    }
}

@Composable
private fun TopicsDialog(note: JournalNote, topics: List<String>, busy: Boolean,
    onTopic: (String, Boolean) -> Unit, onDismiss: () -> Unit) {
    var adding by remember { mutableStateOf(false) }
    if (adding) NamePromptDialog("New topic", "", { name -> onTopic(name, true); adding = false }, { adding = false })
    else Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.background(Kaleido.Paper).padding(20.dp).heightIn(max = 480.dp)
            .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Topics", fontSize = 20.sp, color = Kaleido.Ink)
            topics.forEach { topic ->
                val checked = topic in note.entry.topics
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .toggleable(value = checked, enabled = !busy, role = Role.Checkbox) { onTopic(topic, it) },
                    verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked, null, enabled = !busy)
                    Text(topic, color = Kaleido.Ink)
                }
            }
            ActionButton("New topic", enabled = !busy) { adding = true }
            ActionButton("Done", onClick = onDismiss)
        }
    }
}
