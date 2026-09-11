package com.ethran.notable.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.ethran.notable.io.ExportEngine
import com.ethran.notable.io.ExportFormat
import com.ethran.notable.io.ExportOptions
import com.ethran.notable.io.ExportTarget
import com.ethran.notable.ui.SnackState
import com.ethran.notable.ui.rememberAppScope
import kotlinx.coroutines.launch

@Composable
fun ShowSimpleConfirmationDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    confirmButtonText: String = "Confirm",
    cancelButtonText: String = "Cancel",
    confirmEnabled: Boolean = true
) {
    ShowConfirmationDialog(
        title = title,
        content = { Text(text = message, fontSize = 16.sp) },
        onConfirm = onConfirm,
        onCancel = onCancel,
        onDismiss = onCancel,
        confirmButtonText = confirmButtonText,
        cancelButtonText = cancelButtonText,
        confirmEnabled = confirmEnabled
    )
}


@Composable
fun ShowConfirmationDialog(
    title: String,
    content: @Composable (() -> Unit),
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit = onCancel,
    confirmButtonText: String = "Confirm",
    cancelButtonText: String = "Cancel",
    confirmEnabled: Boolean = true
) {
    Dialog(onDismissRequest = { onDismiss() }) {
        Column(
            modifier = Modifier
                .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.85f)
                .background(Color.White)
                .border(1.dp, Color.Black, RectangleShape)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = title, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            content()
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                ActionButton(text = cancelButtonText, onClick = onCancel)
                ActionButton(text = confirmButtonText, enabled = confirmEnabled, onClick = onConfirm)
            }
        }
    }
}

@Composable
fun ShowExportDialog(
    exportEngine: ExportEngine,
    snackManager: SnackState,
    bookId: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    // The app's scope, not this dialog's: every button below calls `onConfirm()` right after
    // launching, and `onConfirm` closes the notebook dialog this one lives inside — which unmounts
    // this composable and would cancel a `rememberCoroutineScope()` export before it had read a
    // single page. The snack went with it, so a swallowed export looked exactly like a tap that
    // never registered. See [rememberAppScope].
    val scope = rememberAppScope()
    Dialog(onDismissRequest = { onCancel() }) {
        Column(
            modifier = Modifier
                .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.85f)
                .background(Color.White)
                .border(1.dp, Color.Black, RectangleShape)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = "Export notebook", fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text(
                text = "PDF creates a document you can share.\n\n" +
                    "XOPP preserves editable notes for importing. Saving it in Xournal++ changes " +
                    "pen-specific strokes to ballpoint pen strokes.",
                fontSize = 16.sp
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                ActionButton(
                    text = "Cancel", onClick = onCancel
                )
                ActionButton(
                    text = "Export as PDF", onClick = {
                        scope.launch {
                            snackManager.runWithSnack(
                                "Exporting notebook to PDF…"
                            ) {
                                exportEngine.export(
                                    target = ExportTarget.Book(bookId = bookId),
                                    format = ExportFormat.PDF,
                                    options = ExportOptions(
                                        copyToClipboard = false,
                                    )
                                )

                            }

                        }
                        onConfirm()
                    })
                ActionButton(
                    text = "Export as XOPP", onClick = {
                        scope.launch {
                            snackManager.runWithSnack(
                                "Exporting notebook to XOPP…"
                            ) {
                               exportEngine.export(
                                    target = ExportTarget.Book(bookId = bookId),
                                    format = ExportFormat.XOPP,
                                )
                            }
                        }

                        onConfirm()
                    })
            }
        }
    }
}
