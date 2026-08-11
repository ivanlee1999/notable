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
import com.ethran.notable.data.db.Folder
import com.ethran.notable.data.db.FolderRepository
import com.ethran.notable.sync.couch.CouchDocId
import com.ethran.notable.ui.noRippleClickable
import com.ethran.notable.ui.rememberCouchSyncController
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.launch

private val log = ShipBook.getLogger("FolderConfig")

@Composable
fun FolderConfigDialog(folderRepository: FolderRepository,
                       folderId: String,
                       onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val couchSync = rememberCouchSyncController()
    var folder by remember { mutableStateOf<Folder?>(null) }
    var folderTitle by remember { mutableStateOf("") }
    var isRenaming by remember { mutableStateOf(false) }

    LaunchedEffect(folderId) {
        val f = folderRepository.get(folderId)
        if (f == null) {
            io.shipbook.shipbooksdk.Log.e("FolderConfigDialog", "Folder not found")
            onClose()
        } else {
            folder = f
            folderTitle = f.title
        }
    }

    if (folder == null) return

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
                        // CouchDB learns about a change only when it is queued, and nothing else
                        // here queues a rename — so without this the new name stayed on this device.
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
                    text = "Delete Folder",
                    textAlign = TextAlign.Center,
                    modifier = Modifier.noRippleClickable {
                        // Same reason as a notebook deletion: absence is not a syncable fact, so
                        // the intent is recorded as a tombstone that survives being offline.
                        couchSync.noteDeleted(CouchDocId.folder(folderId))
                        scope.launch {
                            folderRepository.delete(folderId)
                            onClose()
                        }
                    })
            }
        }

    }
}