package com.ethran.notable.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.Checkbox
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.ethran.notable.ui.dialogs.NoteRenameEntryPoint
import com.ethran.notable.ui.theme.Kaleido
import dagger.hilt.android.EntryPointAccessors

/** Mount inside the browser's existing menu so preview style is available while browsing. */
@Composable
fun HandwritingPreviewMenuItems(onChoose: () -> Unit = {}) {
    val context = LocalContext.current
    val services = remember(context) { EntryPointAccessors.fromApplication(context.applicationContext, NoteRenameEntryPoint::class.java) }
    val preferences = services.handwritingPreferences()
    val options by preferences.options.collectAsState()
    RowRule()
    MenuAction("Ink previews", { preferences.update(options.copy(textPreview = false)); onChoose() }, !options.textPreview)
    MenuAction("Text previews", { preferences.update(options.copy(textPreview = true)); onChoose() }, options.textPreview)
}

/** Shared local recognition preferences, independent of notebook content and sync settings. */
@Composable
fun HandwritingPreviewSettings() {
    val context = LocalContext.current
    val services = remember(context) { EntryPointAccessors.fromApplication(context.applicationContext, NoteRenameEntryPoint::class.java) }
    val preferences = services.handwritingPreferences()
    val options by preferences.options.collectAsState()
    var chooseLanguage by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val languages = remember { runCatching { services.inkRecognizer().languages() }.getOrDefault(emptyList()) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(options.automatic, { preferences.update(options.copy(automatic = it)) },
                modifier = Modifier.semantics { contentDescription = "Automatically read handwriting" })
            Text("Automatically read handwriting", color = Kaleido.Ink)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Handwriting previews", Modifier.weight(1f), color = Kaleido.Ink)
            TextAction(if (options.textPreview) "Text ▾" else "Ink ▾", { preferences.update(options.copy(textPreview = !options.textPreview)) },
                Modifier.semantics { contentDescription = "Handwriting preview mode" })
        }
        TextAction("Language: ${languages.firstOrNull { it.tag == options.language }?.name ?: options.language} ▾",
            { chooseLanguage = !chooseLanguage }, Modifier.semantics { contentDescription = "Handwriting recognition language" })
        if (chooseLanguage) {
            BasicTextField(query, { query = it }, Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(12.dp)
                .semantics { contentDescription = "Find recognition language" }, textStyle = TextStyle(color = Kaleido.Ink))
            languages.filter { it.name.contains(query, ignoreCase = true) || it.tag.contains(query, ignoreCase = true) }.forEach { language ->
                TextAction(language.name, { preferences.update(options.copy(language = language.tag)); chooseLanguage = false }, Modifier.fillMaxWidth())
            }
        }
    }
}
