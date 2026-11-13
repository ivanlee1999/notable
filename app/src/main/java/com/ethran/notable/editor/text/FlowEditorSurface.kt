package com.ethran.notable.editor.text

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ethran.notable.editor.utils.EpdRefreshArbiter
import com.ethran.notable.ui.components.RowRule
import com.ethran.notable.ui.components.TextAction
import kotlinx.coroutines.launch
import com.ethran.notable.ui.theme.Kaleido

/** One scrollable document. The paper card and its ink never become positioned text fields. */
@Composable
fun FlowEditorSurface(
    model: FlowTextViewModel,
    title: String,
    onBack: () -> Unit,
    onInk: () -> Unit,
    onTags: (() -> Unit)?,
    onSwitchNotebook: () -> Unit,
    onNewEntry: (String, String) -> Unit,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val document = state.document
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var active by remember(document?.pageId) { mutableStateOf(document?.runs?.keys?.firstOrNull()) }
    var format by remember { mutableStateOf<Pair<Long, FlowFormat>?>(null) }
    var history by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    fun leave(action: () -> Unit) {
        focus.clearFocus()
        scope.launch { if (model.flush()) action() }
    }
    Column(Modifier.fillMaxSize().background(Kaleido.Paper).imePadding()) {
        Row(Modifier.fillMaxWidth().height(49.dp), verticalAlignment = Alignment.CenterVertically) {
            TextAction("‹", { leave(onBack) }, Modifier.size(48.dp).semantics { contentDescription = "Back to journal" })
            Text(title, Modifier.weight(1f).clickable { leave(onSwitchNotebook) }, maxLines = 1,
                overflow = TextOverflow.Ellipsis, fontSize = 14.sp, color = Kaleido.Ink)
            TextAction("Ink", { leave(onInk) }, Modifier.height(48.dp).semantics { contentDescription = "Return to handwriting" })
            onTags?.let { TextAction("#", it, Modifier.size(48.dp).semantics { contentDescription = "Edit tags" }) }
        }
        RowRule()
        if (state.error != null) Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(state.error!!, color = Kaleido.Ink, fontSize = 14.sp)
            Row {
                if (state.conflicts.isNotEmpty()) TextAction("Keep both", model::keepBoth)
                else TextAction("Retry", model::retry)
            }
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(18.dp)) {
            if (state.loading) Text("Loading text…", color = Kaleido.Muted)
            if (document != null) {
                if (document.hasCanvas) {
                    val canvas by produceState<android.graphics.Bitmap?>(null, document.pageId) {
                        value = runCatching { model.canvas(document.pageId) }.getOrNull()
                    }
                    Text("Original handwriting", color = Kaleido.Muted, fontSize = 12.sp)
                    canvas?.let { bitmap ->
                        Image(bitmap.asImageBitmap(), "Original paper", Modifier.fillMaxWidth()
                            .heightIn(max = 240.dp).clickable { leave(onInk) })
                    }
                    Spacer(Modifier.height(18.dp))
                }
                document.parts.forEach { part -> when (part) {
                    is FlowPart.Text -> document.runs[part.token]?.let { run ->
                        FlowRunField(run, format?.takeIf { active == run.token },
                            onActive = { active = run.token }, onFocus = { model.focus(run.token, it) },
                            onChange = { text, composing -> model.change(run.token, text, composing) },
                            onNewThought = { scope.launch { model.newThought(onNewEntry) } })
                    }
                    is FlowPart.Content -> when (part.block.kind) {
                        "link" -> Text("↗ ${part.block.text ?: part.block.targetNotebookId.orEmpty()}", color = Kaleido.Ink)
                        "audio", "image" -> Text(if (part.block.kind == "image") "Image attachment" else "Audio recording",
                            color = Kaleido.Muted, modifier = Modifier.padding(vertical = 12.dp))
                    }
                } }
            }
        }
        RowRule()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            listOf("B" to FlowFormat.BOLD, "I" to FlowFormat.ITALIC, "H" to FlowFormat.HEADING,
                "☑" to FlowFormat.CHECKLIST, "↗" to FlowFormat.LINK).forEach { (label, kind) ->
                TextAction(label, { format = System.nanoTime() to kind }, Modifier.weight(1f).height(44.dp).semantics {
                    contentDescription = when (kind) {
                        FlowFormat.BOLD -> "Bold text"; FlowFormat.ITALIC -> "Italic text"; FlowFormat.HEADING -> "Heading"
                        FlowFormat.CHECKLIST -> "Checklist"; FlowFormat.LINK -> "Insert link"; FlowFormat.LIST -> "Bullet list"
                    }
                })
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            if (!document?.versions.isNullOrEmpty()) TextAction("${document!!.versions.size} versions", { history = true },
                Modifier.semantics { contentDescription = "Previous text versions" })
            else Text(if ((document?.revision ?: 0) <= state.durableRevision) "Saved on device" else "Saving…",
                color = Kaleido.Muted, fontSize = 12.sp)
            TextAction("+ New thought", { focus.clearFocus(); scope.launch { model.newThought(onNewEntry) } })
        }
    }
    if (history) Dialog(onDismissRequest = { history = false }) {
        Column(Modifier.fillMaxWidth().background(Kaleido.Paper).padding(18.dp)
            .verticalScroll(rememberScrollState())) {
            Text("Previous text versions", color = Kaleido.Ink, fontSize = 20.sp)
            document?.versions.orEmpty().forEach { version ->
                MarkdownParagraph(version.text.orEmpty(), Modifier.padding(top = 16.dp))
                Row {
                    TextAction("Copy text", { clipboard.setText(AnnotatedString(version.text.orEmpty())) })
                    TextAction("Restore as copy", { history = false; model.restoreVersion(version) })
                }
                RowRule()
            }
            TextAction("Done", { history = false })
        }
    }
}

@Composable
private fun FlowRunField(
    run: FlowRun,
    format: Pair<Long, FlowFormat>?,
    onActive: () -> Unit,
    onFocus: (Boolean) -> Unit,
    onChange: (String, Boolean) -> Unit,
    onNewThought: () -> Unit,
) {
    var value by remember(run.token) { mutableStateOf(TextFieldValue(run.source)) }
    val requester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(run.source) {
        if (value.text != run.source && value.composition == null) value = value.copy(text = run.source,
            selection = TextRange(value.selection.start.coerceAtMost(run.source.length), value.selection.end.coerceAtMost(run.source.length)))
    }
    LaunchedEffect(format) {
        if (format != null && value.composition == null) {
            value = formatFlowText(value, format.second)
            onChange(value.text, false)
            requester.requestFocus()
        }
    }
    DisposableEffect(focused) {
        val handle = if (focused) EpdRefreshArbiter.acquire("flow-text") else null
        onDispose { handle?.release() }
    }
    DisposableEffect(run.token) { onDispose { onFocus(false) } }
    BasicTextField(value, onValueChange = { next -> value = next; onChange(next.text, next.composition != null) },
        modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp).padding(bottom = 16.dp)
            .focusRequester(requester).onFocusChanged { focused = it.isFocused; onFocus(it.isFocused); if (it.isFocused) onActive() }
            .semantics { contentDescription = "Entry text" }
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && (event.isCtrlPressed || event.isMetaPressed) &&
                    event.key == Key.Enter && value.composition == null) { onNewThought(); true } else false
            },
        textStyle = TextStyle(color = Kaleido.Ink, fontSize = 18.sp, lineHeight = 28.sp),
        cursorBrush = SolidColor(Kaleido.Ink), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
        visualTransformation = FlowEditingTransformation,
        decorationBox = { field -> Box { if (value.text.isEmpty()) Text("Write a thought…", color = Kaleido.Muted, fontSize = 18.sp); field() } })
}
