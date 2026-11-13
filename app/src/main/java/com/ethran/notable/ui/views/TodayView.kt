package com.ethran.notable.ui.views

import androidx.compose.foundation.background
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import com.ethran.notable.data.asciiLower
import com.ethran.notable.data.topicName
import com.ethran.notable.data.AutomaticTagPreferences
import com.ethran.notable.ui.components.TagManagerDialog
import com.ethran.notable.ui.components.ActionMenu
import com.ethran.notable.ui.components.MenuAction
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.editor.utils.autoEInkAnimationOnScroll
import com.ethran.notable.navigation.NavigationDestination
import com.ethran.notable.ui.components.NotebookContentPreview
import com.ethran.notable.ui.components.BrowserLayoutPicker
import com.ethran.notable.ui.components.rememberBrowserLayout
import com.ethran.notable.ui.components.journalDays
import com.ethran.notable.ui.components.JournalCalendar
import com.ethran.notable.ui.components.SquareButton
import com.ethran.notable.ui.components.RowRule
import com.ethran.notable.ui.components.TextAction
import com.ethran.notable.ui.dialogs.ActionButton
import com.ethran.notable.ui.dialogs.NamePromptDialog
import com.ethran.notable.ui.dialogs.ShowConfirmationDialog
import com.ethran.notable.ui.dialogs.NoteRenameDialog
import com.ethran.notable.ui.theme.Kaleido
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    private val contentTagger: com.ethran.notable.data.ContentTagger,
) : ViewModel() {
    val error = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    val dataReady = MutableStateFlow(false)
    private val reload = MutableStateFlow(0)
    private val tagsReload = MutableStateFlow(0)
    val tagsReady = MutableStateFlow(false)
    val tagCatalog by lazy { tagsReload.flatMapLatest {
        journal.tagCatalog.onEach { tagsReady.value = true }
            .catch { tagsReady.value = false; error.value = "Could not load tags. Try again." }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList()) }
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
    fun retryTags() { tagsReady.value = false; error.value = null; tagsReload.value++ }
    fun invalidateAutomaticTags() = contentTagger.invalidatePending()

    suspend fun renameTag(source: String, destination: String): Boolean = tagOperation { journal.renameTopic(source, destination) }
    suspend fun removeTag(source: String): Boolean = tagOperation { journal.removeTopicEverywhere(source) }
    private suspend fun tagOperation(operation: suspend () -> Unit): Boolean {
        if (busy.value || !tagsReady.value) return false
        busy.value = true; error.value = null
        contentTagger.invalidatePending()
        return try { withContext(Dispatchers.IO) { operation() }; true }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error.value = "Could not save tags. Your notes are kept. Try again."; false }
        finally { contentTagger.invalidatePending(); busy.value = false }
    }

    suspend fun moveToTrash(notebookId: String): Boolean {
        if (busy.value || !dataReady.value) return false
        busy.value = true; error.value = null
        return try {
            withContext(Dispatchers.IO) { check(app.trashRepository.trashNotebook(notebookId).isNotEmpty()) { "Note is missing" } }
            true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error.value = "Could not move this note to Trash. Try again."; false }
        finally { busy.value = false }
    }

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
    onTrash: () -> Unit = {},
    viewModel: TodayViewModel = hiltViewModel(),
) {
    val notes by viewModel.notes.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val dataReady by viewModel.dataReady.collectAsStateWithLifecycle()
    var today by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var selectedDate by rememberSaveable { mutableStateOf<String?>(null) }
    var topicsView by rememberSaveable { mutableStateOf(false) }
    var selectedTopic by rememberSaveable { mutableStateOf<String?>(null) }
    var editingTopics by rememberSaveable { mutableStateOf<String?>(null) }
    var managingTags by rememberSaveable { mutableStateOf(false) }
    var noteMenu by rememberSaveable { mutableStateOf<String?>(null) }
    var trashTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var renamingNotebook by rememberSaveable { mutableStateOf<String?>(null) }
    var renamingPage by rememberSaveable { mutableStateOf<String?>(null) }
    var expandedNote by rememberSaveable { mutableStateOf<String?>(null) }
    var dateMenu by remember { mutableStateOf(false) }
    var browseMenu by remember { mutableStateOf(false) }
    val previewPages = remember { mutableStateMapOf<String, String>() }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val automaticTagPreferences = remember(context) { AutomaticTagPreferences(context) }
    val automaticTags by automaticTagPreferences.enabled.collectAsStateWithLifecycle()
    LaunchedEffect(notes, dataReady) {
        if (dataReady) {
            if (notes.none { it.entry.notebookId == trashTarget }) trashTarget = null
            if (notes.none { it.entry.notebookId == noteMenu }) noteMenu = null
        }
    }
    val listState = rememberLazyListState()
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

    val topics = notes.flatMap { it.entry.topics }.sorted().distinctBy(::asciiLower)
        .sortedWith(String.CASE_INSENSITIVE_ORDER)
    val selectedKey = selectedTopic?.let(::asciiLower)
    val shown = when {
        topicsView && selectedKey != null -> notes.filter { note -> note.entry.topics.any { asciiLower(it) == selectedKey } }
        topicsView -> emptyList()
        else -> notes.filter { it.entry.date != null }
    }.sortedWith(compareByDescending<JournalNote> { it.entry.date }.thenByDescending { it.entry.createdAt })
    var pendingDateJump by remember { mutableStateOf<String?>(null) }
    fun jumpToDate(date: String) {
        selectedDate = date
        pendingDateJump = date
        topicsView = false
    }
    fun createNote() {
        val inherited = selectedTopic?.takeIf { topicsView }?.let(::listOf).orEmpty()
        scope.launch {
            viewModel.create(inherited)?.let { (book, page) ->
                selectedDate = null
                pendingDateJump = null
                topicsView = false
                onOpen(page, book)
            }
        }
    }
    fun open(note: JournalNote, selectedPage: String?) {
        val book = note.notebook
        val page = selectedPage?.takeIf { it in book.pageIds }
            ?: book.openPageId?.takeIf { it in book.pageIds } ?: book.pageIds.firstOrNull()
        if (page == null) onPages(book.id)
        else onOpen(page, book.id)
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxWidth < 600.dp
        val (layout, chooseLayout) = rememberBrowserLayout(compact)
        val columns = layout.columnsFor(maxWidth)
        val previewHeight = layout.previewHeight(maxHeight - 150.dp)
        val days = journalDays(shown, columns, expandedNote, { it.entry.date }, { it.entry.notebookId },
            selectedDate.takeUnless { topicsView })
        val datesByKey = days.flatMap { day ->
            listOf(day.key to day.date) + day.rows.map { "row:${it.first().entry.notebookId}" to day.date }
        }.toMap()
        val visibleDate by remember(days, listState) { derivedStateOf {
            listState.layoutInfo.visibleItemsInfo.firstOrNull()?.key?.let { datesByKey[it] }
        } }
        LaunchedEffect(pendingDateJump, days, topicsView) {
            if (topicsView) { pendingDateJump = null; return@LaunchedEffect }
            val target = pendingDateJump ?: return@LaunchedEffect
            val index = days.firstOrNull { it.date == target }?.index ?: return@LaunchedEffect
            listState.scrollToItem(index)
            pendingDateJump = null
        }
        Column(Modifier.fillMaxSize().background(Kaleido.Paper)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (topicsView) selectedTopic?.let { "#$it" } ?: "Tags" else "Journal",
                    fontSize = 24.sp, color = Kaleido.Ink, modifier = Modifier.weight(1f))
                if (topicsView) TextAction("Manage tags", { managingTags = true })
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!topicsView) Box(Modifier.weight(1f)) {
                    val date = visibleDate ?: selectedDate ?: today
                    TextAction((if (date == today) "Today" else LocalDate.parse(date)
                        .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))) + " ▾",
                        { dateMenu = true }, Modifier.fillMaxWidth().semantics { contentDescription = "Journal calendar" })
                    if (dateMenu) JournalCalendar(LocalDate.parse(date), LocalDate.parse(today),
                        notes.mapNotNull { it.entry.date }.groupingBy { it }.eachCount(),
                        { chosen -> dateMenu = false; jumpToDate(chosen.toString()) }, { dateMenu = false })
                }
                if (topicsView && selectedTopic != null) TextAction("All tags", { selectedTopic = null }, Modifier.weight(1f))
                if (!topicsView || selectedTopic != null) BrowserLayoutPicker(layout, chooseLayout, compact)
            }
            error?.let { message -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(message, Modifier.weight(1f), color = Kaleido.Ink)
                if (!dataReady) TextAction("Retry", viewModel::retry)
                else TextAction("Dismiss", { viewModel.error.value = null })
            } }
        }
        RowRule()
        LazyColumn(Modifier.weight(1f).autoEInkAnimationOnScroll(), state = listState, contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (topicsView && selectedTopic == null) {
                items(topics, key = { it }) { topic ->
                    TextAction("#$topic", { selectedTopic = topic; scope.launch { listState.scrollToItem(0) } }, Modifier.fillMaxWidth())
                }
                if (topics.isEmpty()) item { Text("No tags yet", color = Kaleido.Muted) }
            } else {
                days.forEach { day ->
                val date = day.date
                item(key = day.key) {
                    Text(when (date) {
                        today -> "Today"
                        LocalDate.parse(today).minusDays(1).toString() -> "Yesterday"
                        null -> "Library"
                        else -> LocalDate.parse(date).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
                    }, color = Kaleido.Muted, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                }
                if (day.rows.isEmpty()) item(key = "empty:$date") { Text("No entries", color = Kaleido.Muted) }
                items(day.rows, key = { row -> "row:${row.first().entry.notebookId}" }) { row ->
                    val rowColumns = if (row.any { it.entry.notebookId == expandedNote }) 1 else columns
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        row.forEach { note ->
                            Box(Modifier.weight(1f)) {
                                    Column {
                                        NotebookContentPreview(notebook = note.notebook, previewHeight = previewHeight,
                                            expanded = expandedNote == note.entry.notebookId,
                                            onExpand = { expandedNote = if (expandedNote == note.entry.notebookId) null else note.entry.notebookId },
                                            onEdit = { page -> open(note, page) },
                                            selectedPageId = previewPages[note.entry.notebookId],
                                            onPageChange = { previewPages[note.entry.notebookId] = it },
                                            onRename = { renamingPage = null; renamingNotebook = note.entry.notebookId },
                                            onRenamePage = { renamingPage = it; renamingNotebook = note.entry.notebookId },
                                            subtitle = if (topicsView) note.entry.date else null,
                                            actions = { Box {
                                                TextAction("•••", { noteMenu = note.entry.notebookId }, Modifier.size(48.dp)
                                                    .semantics { contentDescription = "Note actions for ${note.notebook.title}" }, enabled = !busy)
                                                if (noteMenu == note.entry.notebookId) ActionMenu({ noteMenu = null }) {
                                                    MenuAction("Edit tags", { noteMenu = null; editingTopics = note.entry.notebookId })
                                                    MenuAction("Move to Trash", { noteMenu = null; trashTarget = note.entry.notebookId })
                                                }
                                            } })
                                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            note.entry.topics.forEach { topic ->
                                                TextAction("#$topic", { topicsView = true; selectedTopic = topic })
                                            }
                                            TextAction("+ Tag", { editingTopics = note.entry.notebookId })
                                        }
                                    }
                            }
                        }
                        repeat(rowColumns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                }
                if (days.isEmpty()) item { Text("No notes", color = Kaleido.Muted) }
            }
        }
        RowRule()
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextAction("Today", { jumpToDate(today) },
                Modifier.weight(1f))
            TextAction(if (busy) "…" else "Write", ::createNote, Modifier.weight(1f), filled = true, enabled = !busy && dataReady)
            TextAction("Browse", { browseMenu = true }, Modifier.weight(1f))
        }
        }
    }
    if (browseMenu) Dialog(onDismissRequest = { browseMenu = false }) {
        Column(Modifier.background(Kaleido.Paper).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Browse", fontSize = 20.sp, color = Kaleido.Ink)
            TextAction("Tags", { browseMenu = false; topicsView = true; selectedTopic = null;
                scope.launch { listState.scrollToItem(0) } }, Modifier.fillMaxWidth())
            TextAction("Manage tags", { browseMenu = false; managingTags = true }, Modifier.fillMaxWidth())
            TextAction("Library", { browseMenu = false; onLibrary() }, Modifier.fillMaxWidth())
            TextAction("Trash", { browseMenu = false; onTrash() }, Modifier.fillMaxWidth())
            TextAction("Close", { browseMenu = false }, Modifier.fillMaxWidth())
        }
    }
    notes.firstOrNull { it.entry.notebookId == editingTopics }?.let { note ->
        TopicsDialog(note, topics, busy, { name, selected -> viewModel.topic(note, name, selected) },
            { editingTopics = null })
    }
    renamingNotebook?.let { id -> NoteRenameDialog(id, renamingPage, { renamingNotebook = null }) }
    if (managingTags) {
        val catalog by viewModel.tagCatalog.collectAsStateWithLifecycle()
        val ready by viewModel.tagsReady.collectAsStateWithLifecycle()
        TagManagerDialog(catalog, busy, error, automaticTags, {
            viewModel.invalidateAutomaticTags(); automaticTagPreferences.setEnabled(it)
        },
            onRename = { source, destination ->
                viewModel.renameTag(source, destination).also { success ->
                    if (success && selectedTopic?.let(::asciiLower) == asciiLower(source)) selectedTopic = topicName(destination)
                }
            }, onRemove = { source -> viewModel.removeTag(source).also { success ->
                if (success && selectedTopic?.let(::asciiLower) == asciiLower(source)) selectedTopic = null
            } }, onOpen = { topic ->
                managingTags = false; topicsView = true; selectedTopic = topic; scope.launch { listState.scrollToItem(0) }
            }, onDismiss = {
                if (ready && selectedTopic != null && catalog.none { asciiLower(it.name) == asciiLower(selectedTopic!!) }) selectedTopic = null
                managingTags = false
            }, ready = ready, onRetry = viewModel::retryTags)
    }
    notes.firstOrNull { it.entry.notebookId == trashTarget }?.let { note ->
        ShowConfirmationDialog("Move to Trash?", content = {
            Text("${note.notebook.title}" +
                (if (note.notebook.pageIds.size > 1) " and all ${note.notebook.pageIds.size} pages" else "") +
                " can be restored from Trash.")
            error?.let { Text(it) }
        }, onConfirm = { scope.launch { if (viewModel.moveToTrash(note.notebook.id)) trashTarget = null } },
            onCancel = { if (!busy) trashTarget = null }, confirmButtonText = "Move to Trash", confirmEnabled = !busy)
    }
}

@Composable
private fun TopicsDialog(note: JournalNote, topics: List<String>, busy: Boolean,
    onTopic: (String, Boolean) -> Unit, onDismiss: () -> Unit) {
    var adding by remember { mutableStateOf(false) }
    if (adding) NamePromptDialog("New tag", "", { name -> onTopic(name, true); adding = false }, { adding = false })
    else Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.background(Kaleido.Paper).padding(20.dp).heightIn(max = 480.dp)
            .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Tags", fontSize = 20.sp, color = Kaleido.Ink)
            topics.forEach { topic ->
                val checked = note.entry.topics.any { asciiLower(it) == asciiLower(topic) }
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .toggleable(value = checked, enabled = !busy, role = Role.Checkbox) { onTopic(topic, it) },
                    verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked, null, enabled = !busy)
                    Text(topic, color = Kaleido.Ink)
                }
            }
            ActionButton("New tag", enabled = !busy) { adding = true }
            ActionButton("Done", onClick = onDismiss)
        }
    }
}
