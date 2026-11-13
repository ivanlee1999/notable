package com.ethran.notable.ui.dialogs

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.ethran.notable.data.AppRepository
import com.ethran.notable.editor.topics.InkTopicRecognizer
import com.ethran.notable.editor.topics.TopicInkPoint
import com.ethran.notable.editor.topics.TopicInkStroke
import com.ethran.notable.recognition.HandwritingPreferences
import com.ethran.notable.ui.components.TextAction
import com.ethran.notable.ui.theme.Kaleido
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@EntryPoint
@InstallIn(SingletonComponent::class)
interface NoteRenameEntryPoint {
    fun appRepository(): AppRepository
    fun inkRecognizer(): InkTopicRecognizer
    fun handwritingPreferences(): HandwritingPreferences
}

/** A title candidate is explicitly accepted; temporary handwriting never enters the page. */
@Composable
fun NoteRenameDialog(notebookId: String, pageId: String? = null, onDismiss: () -> Unit, onSaved: () -> Unit = {}) {
    val context = LocalContext.current
    val services = remember(context) { EntryPointAccessors.fromApplication(context.applicationContext, NoteRenameEntryPoint::class.java) }
    key(notebookId, pageId) {
        var initial by remember { mutableStateOf<String?>(null) }
        var error by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        val options by services.handwritingPreferences().options.collectAsState()
        LaunchedEffect(Unit) {
            try { initial = withContext(Dispatchers.IO) {
                val app = services.appRepository()
                val book = app.bookRepository.getById(notebookId) ?: error("Notebook is missing")
                check(book.deletedAt == null) { "Notebook is in Trash" }
                if (pageId == null) book.title else {
                    val page = app.pageRepository.getById(pageId) ?: error("Page is missing")
                    check(page.notebookId == notebookId) { "Page moved to another notebook" }
                    page.title.orEmpty()
                }
            } } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "Could not load this title." }
        }
        Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
            Column(Modifier.fillMaxWidth().heightIn(max = dialogMaxHeight()).background(Kaleido.Paper)
                .border(1.dp, Kaleido.Ink).verticalScroll(rememberScrollState()).padding(18.dp)) {
                Text(if (pageId == null) "Notebook name" else "Page name", fontSize = 20.sp, color = Kaleido.Ink)
                initial?.let { name ->
                    RenameTitleInput(name, services.inkRecognizer(), options.language, busy, error,
                        onSave = { title ->
                            busy = true; error = null
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) {
                                        val app = services.appRepository()
                                        if (pageId == null) app.bookRepository.rename(notebookId, title)
                                        else {
                                            val book = app.bookRepository.getById(notebookId) ?: error("Notebook is missing")
                                            check(book.deletedAt == null) { "Notebook is in Trash" }
                                            val page = app.pageRepository.getById(pageId) ?: error("Page is missing")
                                            check(page.notebookId == notebookId) { "Page moved to another notebook" }
                                            app.pageRepository.rename(pageId, title, expectedNotebookId = notebookId)
                                        }
                                    }
                                    onSaved(); onDismiss()
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { error = "Could not save the name. Your candidate is kept." }
                                finally { busy = false }
                            }
                        }, onDismiss = onDismiss)
                } ?: run { Text(error ?: "Loading…", color = Kaleido.Muted); TextAction("Cancel", onDismiss) }
            }
        }
    }
}

@Composable
internal fun RenameTitleInput(initial: String, recognizer: InkTopicRecognizer, language: String,
    busy: Boolean, saveError: String?, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    var writing by remember { mutableStateOf(false) }
    var strokes by remember { mutableStateOf(emptyList<TopicInkStroke>()) }
    var padGeneration by remember { mutableIntStateOf(0) }
    var candidateGeneration by remember { mutableIntStateOf(0) }
    var reading by remember { mutableStateOf(false) }
    var recognitionError by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var recognitionGeneration by remember { mutableIntStateOf(0) }
    val focus = LocalFocusManager.current
    val requester = remember { FocusRequester() }
    LaunchedEffect(writing) { if (writing) focus.clearFocus() else requester.requestFocus() }
    LaunchedEffect(strokes, language) {
        val request = ++recognitionGeneration
        if (strokes.isEmpty()) { reading = false; status = null; return@LaunchedEffect }
        val generation = candidateGeneration
        reading = true; recognitionError = null
        try {
            delay(600)
            val recognized = recognizer.recognize(strokes, language) {
                if (request == recognitionGeneration) status = "Downloading handwriting language…"
            }
            if (request == recognitionGeneration && generation == candidateGeneration)
                value = TextFieldValue(recognized, TextRange(recognized.length))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { if (request == recognitionGeneration) recognitionError = "Could not read this name. Try again or type it." }
        finally { if (request == recognitionGeneration) { reading = false; status = null } }
    }
    Row { TextAction("Type", { writing = false }); TextAction("Write", { writing = true }) }
    if (writing) {
        TemporaryNameInk(strokes, padGeneration, enabled = !busy) { strokes = it }
        TextAction("Clear handwriting", { padGeneration++; strokes = emptyList() })
    }
    BasicTextField(value, { value = it; candidateGeneration++; reading = false }, enabled = !busy,
        singleLine = true, textStyle = TextStyle(color = Kaleido.Ink, fontSize = 18.sp),
        cursorBrush = SolidColor(Kaleido.Ink), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (!reading && value.text.isNotBlank()) onSave(value.text.trim()) }),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).border(1.dp, Kaleido.Grid).padding(12.dp)
            .focusRequester(requester)
            .semantics { contentDescription = "Name candidate" })
    (saveError ?: recognitionError ?: status)?.let { Text(it, color = Kaleido.Muted, fontSize = 13.sp) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        ActionButton("Cancel", enabled = !busy, onClick = onDismiss)
        ActionButton(if (busy) "Saving…" else "Save", enabled = !busy && !reading && value.text.isNotBlank()) { onSave(value.text.trim()) }
    }
}

@Composable
private fun TemporaryNameInk(completed: List<TopicInkStroke>, generation: Int, enabled: Boolean, onChange: (List<TopicInkStroke>) -> Unit) {
    val currentCompleted by rememberUpdatedState(completed)
    var active by remember(generation) { mutableStateOf(emptyList<TopicInkPoint>()) }
    Canvas(Modifier.fillMaxWidth().height(150.dp).border(1.dp, Kaleido.Grid)
        .semantics { contentDescription = "Write a name" }.pointerInput(generation, enabled) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                active = listOf(TopicInkPoint(down.position.x, down.position.y, down.uptimeMillis))
                var cancelled = true
                do {
                    val event = awaitPointerEvent()
                    val point = event.changes.firstOrNull { it.id == down.id } ?: break
                    point.consume()
                    active = active + TopicInkPoint(point.position.x, point.position.y, point.uptimeMillis)
                    if (!point.pressed) { cancelled = false; break }
                } while (true)
                if (!cancelled) onChange(currentCompleted + TopicInkStroke(active))
                active = emptyList()
            }
        }) {
        (completed.map { it.points } + listOf(active)).forEach { points ->
            if (points.size == 1) drawCircle(Kaleido.Ink, 2f, Offset(points[0].x, points[0].y))
            points.zipWithNext().forEach { (a, b) -> drawLine(Kaleido.Ink, Offset(a.x, a.y), Offset(b.x, b.y), 3f) }
        }
    }
}
