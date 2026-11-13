package com.ethran.notable.editor.topics

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Checkbox
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ethran.notable.data.asciiLower
import com.ethran.notable.data.topicName
import com.ethran.notable.ui.components.ScaledPopup
import com.ethran.notable.ui.components.TextAction
import com.ethran.notable.ui.dialogs.ActionButton
import com.ethran.notable.ui.dialogs.dialogMaxHeight
import com.ethran.notable.ui.theme.Kaleido

@Composable
fun EditorTopicButton(topics: List<String>, enabled: Boolean = true, onClick: () -> Unit) {
    val compact = LocalConfiguration.current.screenWidthDp < 500
    val label = if (topics.isEmpty()) "# Tags ▾" else if (compact) "# ${topics.size} ▾"
        else "#${topics.first()}${if (topics.size > 1) " +${topics.size - 1}" else ""} ▾"
    TopicAction(label, onClick, Modifier.widthIn(max = if (compact) 76.dp else 180.dp)
        .heightIn(min = 48.dp).semantics { contentDescription = "Edit note topics" }, enabled = enabled)
}

/** Focusable dropdown shares the editor's explicit drawing gate; its field also accepts handwriting IMEs. */
@Composable
fun EditorTopicsPopup(model: EditorTopicsViewModel) {
    val request by model.request.collectAsStateWithLifecycle()
    val target = request ?: return
    val entries by model.entries.collectAsStateWithLifecycle()
    val ready by model.ready.collectAsStateWithLifecycle()
    val saving by model.saving.collectAsStateWithLifecycle()
    val recognizing by model.recognizing.collectAsStateWithLifecycle()
    val status by model.status.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    val candidate by model.candidate.collectAsStateWithLifecycle()
    val language by model.language.collectAsStateWithLifecycle()
    val selected = entries.firstOrNull { it.notebookId == target.notebookId }?.topics.orEmpty()
    val all = entries.flatMap { it.topics }.sorted().distinctBy(::asciiLower)
    val matches = all.filter { candidate.isBlank() || asciiLower(it).contains(asciiLower(topicName(candidate))) }
    val offset = with(LocalDensity.current) { IntOffset(-8.dp.roundToPx(), 48.dp.roundToPx()) }
    ScaledPopup(alignment = Alignment.TopEnd, offset = offset, onDismissRequest = model::close,
        properties = PopupProperties(focusable = true)) {
        Column(Modifier.widthIn(max = 360.dp).fillMaxWidth(.94f)
            .heightIn(max = dialogMaxHeight().coerceAtMost(520.dp))
            .background(Kaleido.Paper).border(1.dp, Kaleido.Ink)
            .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Topics", Modifier.weight(1f), fontSize = 20.sp, color = Kaleido.Ink)
                TextAction("Done", model::close)
            }
            if (selected.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                selected.forEach { topic ->
                    TopicAction("#$topic ×", { model.topic(topic, false) },
                        Modifier.semantics { contentDescription = "Remove topic $topic" }, enabled = ready && !saving)
                }
            }
            BasicTextField(candidate, model::editCandidate, Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .border(1.dp, Kaleido.Grid).padding(12.dp)
                .semantics { contentDescription = "Find or create topic" },
                enabled = ready && !saving, singleLine = true,
                textStyle = TextStyle(color = Kaleido.Ink, fontSize = 16.sp),
                cursorBrush = SolidColor(Kaleido.Ink),
                decorationBox = { inner ->
                    Box { if (candidate.isEmpty()) Text("Find or create topic", color = Kaleido.Muted); inner() }
                })
            if (candidate.isNotBlank()) ActionButton("Add #${topicName(candidate)}", enabled = ready && !saving && topicName(candidate).isNotEmpty()) {
                model.topic(candidate, true, existing = false)
            }
            matches.forEach { topic ->
                val checked = selected.any { asciiLower(it) == asciiLower(topic) }
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .toggleable(checked, enabled = ready && !saving, role = Role.Checkbox) { model.topic(topic, it) },
                    verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked, null, enabled = ready && !saving)
                    Text(topic, color = Kaleido.Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (target.ink.isNotEmpty()) {
                var choosingLanguage by remember(target) { mutableStateOf(false) }
                TextAction(model.languages.firstOrNull { it.tag == language }?.name ?: language,
                    { choosingLanguage = !choosingLanguage }, Modifier.semantics { contentDescription = "Handwriting language" })
                if (choosingLanguage) {
                    var languageSearch by remember { mutableStateOf("") }
                    BasicTextField(languageSearch, { languageSearch = it }, Modifier.fillMaxWidth().heightIn(min = 44.dp)
                        .border(1.dp, Kaleido.Grid).padding(10.dp).semantics { contentDescription = "Find handwriting language" },
                        textStyle = TextStyle(color = Kaleido.Ink, fontSize = 16.sp))
                    model.languages.filter { it.name.contains(languageSearch, true) }.forEach { item ->
                        TextAction(item.name, { choosingLanguage = false; model.setLanguage(item.tag) }, Modifier.fillMaxWidth())
                    }
                }
                status?.let { Text(it, color = Kaleido.Muted) }
                if (!recognizing) TextAction("Read handwriting again", model::recognize)
            }
            error?.let { Text(it, color = Kaleido.Ink) }
            if (!ready) TextAction("Retry topics", model::load)
        }
    }
}

@Composable
private fun TopicAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(modifier.heightIn(min = 48.dp).border(1.dp, Kaleido.Ink)
        .clickable(enabled = enabled, role = Role.Button, indication = null,
            interactionSource = remember { MutableInteractionSource() }, onClick = onClick)
        .padding(horizontal = 10.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(label, color = if (enabled) Kaleido.Ink else Kaleido.Muted, fontSize = 14.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
