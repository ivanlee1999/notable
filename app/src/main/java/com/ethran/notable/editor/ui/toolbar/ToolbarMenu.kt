package com.ethran.notable.editor.ui.toolbar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import com.ethran.notable.ui.components.MenuAction
import com.ethran.notable.ui.components.RowRule
import com.ethran.notable.ui.components.Kicker
import com.ethran.notable.ui.theme.Kaleido
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.ethran.notable.R
import com.ethran.notable.data.datastore.BUTTON_SIZE
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.editor.ToolbarAction
import com.ethran.notable.editor.ToolbarUiState
import com.ethran.notable.editor.state.Mode
import com.ethran.notable.editor.ui.toolbar.model.ToolbarPen
import com.ethran.notable.editor.utils.Pen
import com.ethran.notable.io.ExportFormat

/**
 * Menu for the toolbar, providing export options and other page-level actions.
 * Centralizes actions via [ToolbarAction].
 */
@Composable
fun ToolbarMenu(
    uiState: ToolbarUiState,
    onAction: (ToolbarAction) -> Unit,
) {
    val placement = toolbarMenuPlacement()

    Popup(
        alignment = placement.alignment,
        onDismissRequest = {
                onAction(ToolbarAction.ToggleMenu)
        },
        offset = placement.offset,
        properties = PopupProperties(focusable = true),
    ) {
        ToolbarMenuContent(
            uiState = uiState,
            onAction = onAction,
            padding = placement.padding,
        )
    }
}

@Composable
private fun ToolbarMenuContent(
    uiState: ToolbarUiState,
    onAction: (ToolbarAction) -> Unit,
    padding: PaddingValues = PaddingValues(bottom = (BUTTON_SIZE + 5).dp),
) {
    var exporting by remember { mutableStateOf(false) }
    fun run(action: ToolbarAction) {
        onAction(action)
        onAction(ToolbarAction.ToggleMenu)
    }
    Column(Modifier.padding(padding).widthIn(min = 200.dp, max = 280.dp)
        .heightIn(max = 420.dp).border(1.dp, Kaleido.Ink).background(Kaleido.Paper)
        .verticalScroll(rememberScrollState())) {
        if (exporting) {
            MenuAction("Back to menu", { exporting = false })
            RowRule()
            Kicker("This page", Modifier.padding(12.dp))
            listOf(ExportFormat.PDF, ExportFormat.PNG, ExportFormat.JPEG, ExportFormat.XOPP).forEach { format ->
                MenuAction(stringResource(R.string.export_page_to, format.name), { run(ToolbarAction.ExportPage(format)) })
            }
            if (uiState.notebookId != null) {
                RowRule()
                Kicker("Notebook", Modifier.padding(12.dp))
                listOf(ExportFormat.PDF, ExportFormat.PNG, ExportFormat.XOPP).forEach { format ->
                    MenuAction(stringResource(R.string.export_book_to, format.name), { run(ToolbarAction.ExportBook(format)) })
                }
            }
        } else {
            MenuAction("Library", { run(ToolbarAction.NavigateToLibrary) })
            if (uiState.notebookId != null) MenuAction("Pages", { run(ToolbarAction.NavigateToPages) })
            MenuAction(stringResource(R.string.change_background), { run(ToolbarAction.ToggleBackgroundSelector(true)) })
            val locked = GlobalAppSettings.current.canvasLocked
            MenuAction(stringResource(if (locked) R.string.toolbar_menu_unlock_canvas else R.string.toolbar_menu_lock_canvas),
                { run(ToolbarAction.ToggleCanvasLock(!locked)) })
            MenuAction("Export", { exporting = true })
            RowRule()
            MenuAction(stringResource(R.string.bug_report), { run(ToolbarAction.NavigateToBugReport) })
            RowRule()
            MenuAction(stringResource(R.string.clean_all_strokes), { run(ToolbarAction.ClearAllStrokes) })
        }
    }
}

@Composable
@Preview(showBackground = true)
fun ToolbarMenuPreview() {
    ToolbarMenuContent(
        uiState = ToolbarUiState(
            isMenuOpen = true,
            notebookId = "book1",
            mode = Mode.Draw,
            pen = Pen.BALLPEN,
            penSettings = ToolbarPen.defaultPenSettings
        ),
        onAction = {}
    )
}
