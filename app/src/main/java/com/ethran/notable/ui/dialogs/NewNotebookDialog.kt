package com.ethran.notable.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.ethran.notable.R
import com.ethran.notable.data.model.BackgroundType
import com.ethran.notable.data.model.PageSize
import com.ethran.notable.data.model.PageSizePreset
import com.ethran.notable.ui.noRippleClickable

/**
 * What a notebook is asked before it exists: its name, the sheet its pages are laid out on, and
 * the template printed on them.
 *
 * Page size is here rather than in the notebook's details because it is the one property that
 * cannot be changed afterwards — a page's ink is written against its sheet, so moving the sheet
 * would move every stroke relative to the paper. The template can be changed later; it is offered
 * here anyway because it is the same decision, made at the same moment, and asking for it now
 * costs no extra dialog.
 *
 * The templates offered are the five printed ones *and* every document this library is already
 * drawn on — the PDFs and pictures imported for other notebooks. That is what every other app of
 * this kind asks at this moment: GoodNotes picks a cover and a paper template while the notebook
 * is being made, Supernote applies a default template as the notebook is created. Choosing a
 * document here is the same as importing a PDF, minus the import: the notebook follows the
 * document's pages, so page two of the book is page two of the document.
 *
 * Every choice starts on the default from Settings, so accepting the whole dialog is one tap and
 * nobody who does not care about sheets has to learn what A5 is. Choices are laid out as chips
 * rather than dropdowns: a dropdown is an overlay that appears and disappears, and on e-ink each
 * of those is a full-screen refresh.
 */
@Composable
fun NewNotebookDialog(
    initialName: String,
    initialPageSize: PageSize,
    initialTemplate: String,
    onConfirm: (
        name: String, pageSize: PageSize, template: String, templateType: String,
    ) -> Unit,
    onDismiss: () -> Unit,
    // Read from the library by default; a parameter so what the dialog offers can be stated
    // rather than reached for, which is also what makes it testable without a device's files.
    libraryTemplates: List<KnownTemplate> = rememberKnownTemplates(),
) {
    // Selection spans the whole suggested name so the first keystroke replaces it, as in
    // [NamePromptDialog] — the two dialogs ask the same question about a name.
    var name by remember {
        mutableStateOf(
            TextFieldValue(text = initialName, selection = TextRange(0, initialName.length))
        )
    }
    // A size the presets do not name can only arrive from a notebook created elsewhere; the
    // picker offers presets, so fall back to the default rather than showing nothing selected.
    var pageSize by remember {
        mutableStateOf(PageSizePreset.matching(initialPageSize) ?: PageSizePreset.DEFAULT)
    }
    var template by remember { mutableStateOf(initialTemplate) }
    var templateType by remember { mutableStateOf(BackgroundType.Native.key) }


    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    // The name field takes focus on open, as it did when this was only a name prompt — so
    // replacing the suggested name stays one keystroke. That brings up the keyboard, which on a
    // short screen covers part of a dialog that is now taller than one line, hence the scroll:
    // every option stays reachable while the keyboard is up.
    val scrollState = rememberScrollState()

    fun commit() {
        val title = name.text.trim()
        if (title.isEmpty()) onDismiss() else onConfirm(title, pageSize.size, template, templateType)
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(Color.White)
                .border(1.dp, Color.Black, RectangleShape)
                .verticalScroll(scrollState)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.new_notebook_title),
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp
            )

            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Color(230, 230, 230, 255), RectangleShape)
                    .border(1.dp, Color.Black, RectangleShape)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                BasicTextField(
                    value = name,
                    onValueChange = { name = it },
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Light,
                        fontSize = 18.sp,
                        color = Color.Black
                    ),
                    cursorBrush = SolidColor(Color.Black),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { commit() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                )
            }

            OptionSection(label = stringResource(R.string.new_notebook_page_size)) {
                PageSizePreset.entries.forEach { preset ->
                    OptionChip(
                        label = preset.displayName,
                        detail = preset.millimetreLabel,
                        selected = preset == pageSize,
                        onClick = { pageSize = preset }
                    )
                }
            }

            OptionSection(label = stringResource(R.string.new_notebook_template)) {
                nativeTemplateOptions().forEach { (key, label) ->
                    OptionChip(
                        label = label,
                        selected = key == template && templateType == BackgroundType.Native.key,
                        onClick = {
                            template = key
                            templateType = BackgroundType.Native.key
                        }
                    )
                }
            }

            // Only when there are any: an empty section would be a heading over nothing, and a
            // library with no imported documents is the common case on a fresh install.
            if (libraryTemplates.isNotEmpty()) {
                OptionSection(label = stringResource(R.string.new_notebook_template_library)) {
                    libraryTemplates.forEach { known ->
                        val path = known.file.absolutePath
                        OptionChip(
                            label = known.label,
                            detail = known.asNotebookDefault.folderName,
                            selected = path == template,
                            onClick = {
                                template = path
                                templateType = known.asNotebookDefault.key
                            }
                        )
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.SpaceAround,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                ActionButton(text = stringResource(R.string.name_prompt_cancel), onClick = onDismiss)
                ActionButton(
                    text = stringResource(R.string.new_notebook_create),
                    onClick = { commit() })
            }
        }
    }
}

/** The native templates a page can be printed with, in the order Settings lists them. */
@Composable
private fun nativeTemplateOptions(): List<Pair<String, String>> = listOf(
    "blank" to stringResource(R.string.blank_page),
    "dotted" to stringResource(R.string.dot_grid),
    "lined" to stringResource(R.string.lines),
    "squared" to stringResource(R.string.small_squares_grid),
    "hexed" to stringResource(R.string.hexagon_grid)
)

@Composable
private fun OptionSection(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = label, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            content()
        }
    }
}

/**
 * One choice. Selection is a 2px border and bold text rather than a fill: an inverted chip on
 * e-ink is a black block that ghosts into whatever is drawn over it next.
 */
@Composable
private fun OptionChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    detail: String? = null
) {
    Column(
        modifier = Modifier
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) Color.Black else Color.Gray,
                shape = RectangleShape
            )
            .noRippleClickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
        if (detail != null) {
            Text(text = detail, fontSize = 11.sp, color = Color.Gray)
        }
    }
}
