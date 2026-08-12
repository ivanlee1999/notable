package com.ethran.notable.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.ethran.notable.R
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.TrashRepository
import com.ethran.notable.data.db.Folder
import com.ethran.notable.sync.couch.CouchDocId
import com.ethran.notable.ui.LocalSnackContext
import com.ethran.notable.ui.SnackConf
import com.ethran.notable.ui.noRippleClickable
import com.ethran.notable.ui.rememberCouchSyncController
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.launch

private val log = ShipBook.getLogger("FolderConfig")

@Composable
fun FolderConfigDialog(appRepository: AppRepository,
                       folderId: String,
                       onClose: () -> Unit) {
    val folderRepository = appRepository.folderRepository
    val trashRepository = appRepository.trashRepository
    val snackManager = LocalSnackContext.current
    val scope = rememberCoroutineScope()
    val couchSync = rememberCouchSyncController()
    var folder by remember { mutableStateOf<Folder?>(null) }
    var folderTitle by remember { mutableStateOf("") }
    var isRenaming by remember { mutableStateOf(false) }

    // What deleting this folder would actually take. Measured when the dialog opens rather than
    // when Delete is tapped, so the confirmation can be shown with the counts already in it
    // instead of appearing empty and then filling in.
    var deletionScope by remember { mutableStateOf<TrashRepository.DeletionScope?>(null) }
    var isConfirmingDelete by remember { mutableStateOf(false) }
    var isMoving by remember { mutableStateOf(false) }

    LaunchedEffect(folderId) {
        val f = folderRepository.get(folderId)
        if (f == null) {
            io.shipbook.shipbooksdk.Log.e("FolderConfigDialog", "Folder not found")
            onClose()
        } else {
            folder = f
            folderTitle = f.title
            deletionScope = trashRepository.scopeOfFolder(folderId)
        }
    }

    if (folder == null) return

    if (isConfirmingDelete) {
        ConfirmFolderDeletionDialog(
            title = folderTitle,
            scope = deletionScope,
            onConfirm = {
                isConfirmingDelete = false
                // Nothing is published here. The folder is only staged locally, so a peer that
                // still holds it is not wrong yet — the tombstones are written when the Trash is
                // emptied, all of them, in one transaction. See [TrashRepository].
                scope.launch {
                    trashRepository.trashFolder(folderId)
                    onClose()
                }
            },
            onCancel = { isConfirmingDelete = false }
        )
        return
    }

    // Folders could not be moved at all, so a tree built in the wrong shape had to be rebuilt by
    // hand. The same picker a notebook's Move uses, so the two actions behave alike.
    if (isMoving) {
        ShowFolderSelectionDialog(
            appRepository = appRepository,
            notebookName = folderTitle,
            initialFolderId = folder?.parentFolderId,
            onCancel = { isMoving = false },
            onConfirm = { destination ->
                isMoving = false
                scope.launch {
                    val moved = appRepository.moveFolder(folderId, destination)
                    if (moved) {
                        couchSync.noteDocumentChanged(CouchDocId.folder(folderId))
                        onClose()
                    } else {
                        // Dropping a folder into its own subtree is an ordinary slip with a drag,
                        // and the result would be a subtree that reaches no root and vanishes.
                        snackManager.displaySnack(
                            SnackConf(
                                text = "A folder cannot be moved inside itself",
                                duration = 3000
                            )
                        )
                    }
                }
            })
        return
    }

    if (isRenaming) {
        NamePromptDialog(
            title = stringResource(R.string.name_prompt_folder_title),
            initialValue = folderTitle,
            onConfirm = { name ->
                isRenaming = false
                val current = folder
                if (current != null && current.title != name) {
                    folderTitle = name
                    folder = current.copy(title = name)
                    scope.launch {
                        folderRepository.update(current.copy(title = name))
                        // The outbox entry went in with the row; this only starts the debounce so
                        // the new name leaves now rather than at the next flush.
                        couchSync.noteDocumentChanged(CouchDocId.folder(folderId))
                    }
                }
            },
            onDismiss = { isRenaming = false }
        )
    }

    Dialog(
        onDismissRequest = {
            log.i("Closing Directory Dialog - upstream")
            onClose()
        }
    ) {
        Column(
            modifier = Modifier
                .background(Color.White)
                .fillMaxWidth()
                .border(2.dp, Color.Black, RectangleShape)
        ) {
            Column(
                Modifier.padding(20.dp, 10.dp)
            ) {
                Text(text = "Folder Setting", fontWeight = FontWeight.Bold)
            }
            Box(
                Modifier
                    .height(1.dp)
                    .fillMaxWidth()
                    .background(Color.Black)
            )

            Column(
                Modifier.padding(20.dp, 10.dp)
            ) {

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Folder Title",
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = folderTitle,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Light,
                        fontSize = 16.sp,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.rename),
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        modifier = Modifier.noRippleClickable { isRenaming = true }
                    )
                }
            }

            Box(
                Modifier
                    .padding(20.dp, 0.dp)
                    .height(0.5.dp)
                    .fillMaxWidth()
                    .background(Color.Black)
            )

            Column(
                Modifier.padding(20.dp, 10.dp)
            ) {
                Text(
                    text = "Move Folder…",
                    textAlign = TextAlign.Center,
                    modifier = Modifier.noRippleClickable { isMoving = true })
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Move Folder to Trash",
                    textAlign = TextAlign.Center,
                    modifier = Modifier.noRippleClickable { isConfirmingDelete = true })
            }
        }

    }
}

/**
 * The confirmation that stands between one tap and a whole subtree.
 *
 * It says what is inside because the number is the decision: "Research" with three subfolders and
 * forty notebooks in it is a different act from an empty folder someone made by accident, and the
 * old dialog — which did not exist at all — could not tell them apart.
 */
@Composable
private fun ConfirmFolderDeletionDialog(
    title: String,
    scope: TrashRepository.DeletionScope?,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val contents = when {
        scope == null -> "Checking what is inside…"
        scope.isEmpty -> "It is empty."
        else -> buildString {
            append("It holds ")
            append(
                listOfNotNull(
                    scope.childFolderCount.takeIf { it > 0 }
                        ?.let { "$it folder${if (it == 1) "" else "s"}" },
                    scope.notebookCount.takeIf { it > 0 }
                        ?.let { "$it notebook${if (it == 1) "" else "s"}" },
                    scope.pageCount.takeIf { it > 0 }
                        ?.let { "$it page${if (it == 1) "" else "s"}" },
                ).joinToString(", ")
            )
            append(".")
        }
    }

    ShowSimpleConfirmationDialog(
        title = "Move \"$title\" to Trash?",
        message = "$contents Everything inside goes with it, and stays recoverable from the " +
            "Trash in your library until you empty it.",
        // Disabled-looking rather than disabled: the scope is one query and lands immediately, but
        // confirming before it arrives would delete without having shown what.
        onConfirm = { if (scope != null) onConfirm() },
        onCancel = onCancel,
        confirmButtonText = "Move to Trash",
    )
}