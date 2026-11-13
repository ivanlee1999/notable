package com.ethran.notable.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.Checkbox
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.ethran.notable.data.TagSummary
import com.ethran.notable.data.asciiLower
import com.ethran.notable.data.topicName
import com.ethran.notable.ui.dialogs.ActionButton
import com.ethran.notable.ui.dialogs.ShowConfirmationDialog
import com.ethran.notable.ui.dialogs.dialogMaxHeight
import com.ethran.notable.ui.theme.Kaleido
import kotlinx.coroutines.launch

internal fun tagNoteCount(tag: TagSummary): String = "${tag.noteCount} ${if (tag.noteCount == 1) "note" else "notes"}" +
    if (tag.trashCount > 0) " · ${tag.trashCount} in Trash" else ""

@Composable
fun TagManagerDialog(tags: List<TagSummary>, busy: Boolean, error: String?, automatic: Boolean,
    onAutomaticChange: (Boolean) -> Unit, onRename: suspend (String, String) -> Boolean,
    onRemove: suspend (String) -> Boolean, onOpen: (String) -> Unit, onDismiss: () -> Unit,
    ready: Boolean = true, onRetry: () -> Unit = {}) {
    var search by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<TagSummary?>(null) }
    var removing by remember { mutableStateOf<TagSummary?>(null) }
    var menu by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
        Column(Modifier.fillMaxWidth().heightIn(max = dialogMaxHeight()).background(Kaleido.Paper)
            .border(1.dp, Kaleido.Ink).padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Manage tags", Modifier.weight(1f), fontSize = 20.sp, color = Kaleido.Ink)
                TextAction("Done", onDismiss, enabled = !busy)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(automatic, onAutomaticChange, enabled = !busy,
                    modifier = Modifier.semantics { contentDescription = "Automatic tags" })
                Text("Automatic tags", color = Kaleido.Ink)
            }
            Text("Matches tag names and #hashtags in text and recognized handwriting.", color = Kaleido.Muted, fontSize = 12.sp)
            BasicTextField(search, { search = it }, singleLine = true, textStyle = TextStyle(color = Kaleido.Ink, fontSize = 16.sp),
                cursorBrush = SolidColor(Kaleido.Ink), modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
                    .border(1.dp, Kaleido.Grid).padding(12.dp).semantics { contentDescription = "Find tags" },
                decorationBox = { field -> Box { if (search.isEmpty()) Text("Find tags", color = Kaleido.Muted); field() } })
            Text("Counts include Trash", color = Kaleido.Muted, fontSize = 12.sp)
            error?.let { Text(it, color = Kaleido.Ink, modifier = Modifier.padding(vertical = 8.dp)) }
            if (!ready) TextAction(if (error == null) "Loading tags…" else "Retry tags", onRetry)
            val filtered = tags.filter { asciiLower(it.name).contains(asciiLower(search.removePrefix("#"))) }
            LazyColumn(Modifier.weight(1f, fill = false)) {
                items(filtered, key = { asciiLower(it.name) }) { tag ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            TextAction("#${tag.name}", { onOpen(tag.name) }, Modifier.fillMaxWidth(), enabled = !busy && ready)
                            Text(tagNoteCount(tag), fontSize = 12.sp, color = Kaleido.Muted, modifier = Modifier.padding(start = 10.dp))
                        }
                        Box {
                            TextAction("•••", { menu = tag.name }, Modifier.size(48.dp).semantics { contentDescription = "Manage tag ${tag.name}" }, enabled = !busy && ready)
                            if (menu == tag.name) ActionMenu({ menu = null }) {
                                MenuAction("Rename or merge", { menu = null; renaming = tag })
                                MenuAction("Remove tag", { menu = null; removing = tag })
                            }
                        }
                    }
                    RowRule()
                }
                if (filtered.isEmpty() && ready) item { Text(if (search.isBlank()) "No tags" else "No matching tags", color = Kaleido.Muted, modifier = Modifier.padding(12.dp)) }
            }
        }
    }
    renaming?.let { tag ->
        RenameTagDialog(tag, tags, busy, error, { target -> scope.launch { if (onRename(tag.name, target)) renaming = null } },
            { if (!busy) renaming = null })
    }
    removing?.let { selected ->
        val tag = tags.firstOrNull { asciiLower(it.name) == asciiLower(selected.name) } ?: selected
        ShowConfirmationDialog("Remove #${tag.name}?", content = {
            Text("Remove this tag from ${tag.noteCount} ${if (tag.noteCount == 1) "note" else "notes"}" +
                (if (tag.trashCount > 0) ", including ${tag.trashCount} in Trash" else "") + ". Notes are kept.")
            error?.let { Text(it) }
        },
            onConfirm = { scope.launch { if (onRemove(tag.name)) removing = null } },
            onCancel = { if (!busy) removing = null }, confirmButtonText = "Remove tag", confirmEnabled = !busy)
    }
}

@Composable
private fun RenameTagDialog(source: TagSummary, tags: List<TagSummary>, busy: Boolean, error: String?,
    onSave: (String) -> Unit, onDismiss: () -> Unit) {
    val initial = if (source.name.startsWith("#")) "#${source.name}" else source.name
    var value by remember(source.name) { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val targetKey = asciiLower(topicName(value.text))
    val merges = targetKey != asciiLower(source.name) && tags.any { asciiLower(it.name) == targetKey }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().background(Kaleido.Paper).border(1.dp, Kaleido.Ink).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Rename #${source.name}", fontSize = 20.sp, color = Kaleido.Ink)
            Text(tagNoteCount(source), color = Kaleido.Muted, fontSize = 12.sp)
            BasicTextField(value, { value = it }, singleLine = true, enabled = !busy,
                textStyle = TextStyle(color = Kaleido.Ink, fontSize = 18.sp), cursorBrush = SolidColor(Kaleido.Ink),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).border(1.dp, Kaleido.Grid).padding(12.dp)
                    .semantics { contentDescription = "New tag name" })
            if (merges) Text("Merge into #${tags.first { asciiLower(it.name) == targetKey }.name}", color = Kaleido.Ink)
            error?.let { Text(it, color = Kaleido.Ink) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                ActionButton("Cancel", enabled = !busy, onClick = onDismiss)
                ActionButton(if (merges) "Merge tags" else "Rename", enabled = !busy && targetKey.isNotBlank()) { onSave(value.text) }
            }
        }
    }
}
