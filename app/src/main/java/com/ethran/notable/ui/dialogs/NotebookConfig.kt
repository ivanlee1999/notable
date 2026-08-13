package com.ethran.notable.ui.dialogs


import android.text.format.DateFormat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowCircleRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.net.toUri
import com.ethran.notable.R
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.db.Folder
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.model.BackgroundType
import com.ethran.notable.io.ExportEngine
import com.ethran.notable.io.getLinkedFilesDir
import com.ethran.notable.sync.SyncScheduler
import com.ethran.notable.sync.couch.CouchDocId
import com.ethran.notable.ui.LocalSnackContext
import com.ethran.notable.ui.messageRes
import com.ethran.notable.ui.noRippleClickable
import com.ethran.notable.ui.rememberCouchSyncController
import com.ethran.notable.ui.rememberKvProxy
import com.ethran.notable.ui.requestNotebookSync
import com.ethran.notable.ui.SnackConf
import com.ethran.notable.ui.components.BreadCrumb
import com.ethran.notable.ui.components.PagePreview
import com.ethran.notable.ui.components.getFolderList
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.launch

private val log = ShipBook.getLogger("NotebookConfig")

@Composable
fun NotebookConfigDialog(
    appRepository: AppRepository,
    exportEngine: ExportEngine,
    syncScheduler: SyncScheduler,
    bookId: String,
    onClose: () -> Unit) {
    val bookRepository  = appRepository.bookRepository

    val book by bookRepository.getByIdLive(bookId).observeAsState()
    val scope = rememberCoroutineScope()
    val snackManager = LocalSnackContext.current
    val couchSync = rememberCouchSyncController()
    val kvProxy = rememberKvProxy()
    val context = LocalContext.current

    // `update` records the outbox entry in the same transaction as the row, so every write below
    // is durably queued whether or not anything here remembers to say so. The call after it is
    // what makes the push prompt — it starts the debounce and moves the badge — rather than what
    // makes it happen at all.
    val saveBook: suspend (Notebook) -> Unit = { updated ->
        bookRepository.update(updated)
        couchSync.noteDocumentChanged(CouchDocId.notebook(updated.id))
    }

    if (book == null) return

    var isRenaming by remember { mutableStateOf(false) }
    val formattedCreatedAt = remember { DateFormat.format("dd MMM yyyy HH:mm", book!!.createdAt) }
    val formattedUpdatedAt = remember { DateFormat.format("dd MMM yyyy HH:mm", book!!.updatedAt) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showMoveDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showBackgroundSelector by remember { mutableStateOf(false) }


    var bookFolder by remember { mutableStateOf(book?.parentFolderId) }
    var breadcrumbFolders by remember { mutableStateOf<List<Folder>>(emptyList()) }
    LaunchedEffect(bookFolder) {
        breadcrumbFolders = getFolderList(appRepository, bookFolder)
    }


    if (showBackgroundSelector) {
        BackgroundSelector(
            initialPageBackgroundType = book!!.defaultBackgroundType,
            initialPageBackground = book!!.defaultBackground,
            isNotebookBgSelector = true,
            notebookId = book!!.id,
            onChange = { backgroundType, background ->
                if (background == null) {
                    if (book!!.defaultBackgroundType != backgroundType) {
                        val updatedBook = book!!.copy(
                            defaultBackgroundType = backgroundType
                        )
                        scope.launch {
                            saveBook(updatedBook)
                        }
                    }
                } else if (book!!.defaultBackgroundType != backgroundType || book!!.defaultBackground != background) {
                    val updatedBook = book!!.copy(
                        defaultBackgroundType = backgroundType,
                        defaultBackground = background
                    )
                    scope.launch {
                        saveBook(updatedBook)
                    }
                }
            }) {
            showBackgroundSelector = false
        }
    }
    // Confirmation Dialog for Deletion
    if (showDeleteDialog) {
        ShowSimpleConfirmationDialog(
            title = "Move to Trash?",
            message = "\"${book!!.title}\" (${book!!.pageIds.size} page" +
                "${if (book!!.pageIds.size == 1) "" else "s"}) leaves the library on your other " +
                "devices too, and stays recoverable from the Trash until you empty it.",
            onConfirm = {
                // Nothing is tombstoned here — deletion is two steps, stage then publish on purge,
                // so that a notebook can come back. What *is* published is the Trash itself: it is
                // a field of the notebook document, so this takes the notebook out of the library
                // everywhere, and a restore from any device puts it back on all of them. The
                // tombstone still waits for `AppRepository.deleteNotebookLocally`, which the purge
                // calls when the Trash is emptied.
                scope.launch {
                    appRepository.trashRepository.trashNotebook(bookId)
                        .forEach { couchSync.noteDocumentChanged(it) }
                }
                showDeleteDialog = false
                onClose()
            },
            onCancel = {
                showDeleteDialog = false
            },
            confirmButtonText = "Move to Trash")
        return
    }
    // Confirmation Dialog for Deletion
    if (showExportDialog) {
        ShowExportDialog(
            exportEngine = exportEngine,
            snackManager = snackManager,
            bookId = bookId,
            onConfirm = {
                showExportDialog = false
                onClose()
            },
            onCancel = {
                showExportDialog = false
            })
        return
    }
    // Folder Selection Dialog
    if (showMoveDialog) {

        ShowFolderSelectionDialog(
            appRepository = appRepository,
            notebookName = book!!.title,
            initialFolderId = book!!.parentFolderId,
            onCancel = { showMoveDialog = false },
            onConfirm = { selectedFolder ->
                showMoveDialog = false
                onClose()
                log.i("folder: $selectedFolder")
                val updatedBook = book!!.copy(parentFolderId = selectedFolder)
                bookFolder = selectedFolder
                scope.launch {
                    // be careful, not to cause race condition.
                    saveBook(updatedBook)
                }
            })
    }

    if (isRenaming) {
        NamePromptDialog(
            title = stringResource(R.string.name_prompt_notebook_title),
            initialValue = book!!.title,
            onConfirm = { name ->
                isRenaming = false
                val current = book!!
                if (current.title != name) {
                    scope.launch { saveBook(current.copy(title = name)) }
                }
            },
            onDismiss = { isRenaming = false }
        )
    }

    Dialog(
        onDismissRequest = {
            onClose()
        }) {
        Column(
            modifier = Modifier
                .background(Color.White)
                .fillMaxWidth()
                .border(2.dp, Color.Black, RectangleShape)
                .padding(16.dp)
                .padding(top = 24.dp, bottom = 16.dp)
        ) {
            // Header Section
            Row(Modifier.padding(bottom = 16.dp)) {
                Box(
                    modifier = Modifier
                        .size(200.dp, 250.dp)
                        .background(Color.Gray),
                    contentAlignment = Alignment.Center
                ) {
                    val pageId = book!!.pageIds[0]
                    PagePreview(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(3f / 4f)
                            .border(1.dp, Color.Black, RectangleShape), pageId
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {

                    /* -------------- Title Field -----------*/
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.details_notebook_title),
                            fontWeight = FontWeight.Bold,
                            fontSize = 24.sp
                        )
                        Spacer(Modifier.width(20.dp))
                        Text(
                            text = book!!.title,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Light,
                            fontSize = 24.sp,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(Modifier.width(20.dp))
                        Text(
                            text = stringResource(R.string.rename),
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            modifier = Modifier.noRippleClickable { isRenaming = true }
                        )
                    }

                    /* -------------- Template selection -----------*/
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = stringResource(R.string.details_notebook_default_background_template),
                        )
                        Spacer(modifier = Modifier.width(40.dp))
                        Button(
                            onClick = { showBackgroundSelector = !showBackgroundSelector },
                            colors = ButtonDefaults.buttonColors(
                                backgroundColor = Color(Color.White.toArgb()),
                                contentColor = Color.Black
                            ),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, Color.Black)
                        ) {
                            val typeName =
                                when (BackgroundType.fromKey(book?.defaultBackgroundType ?: "")) {
                                    BackgroundType.AutoPdf -> "Observe Pdf"
                                    BackgroundType.CoverImage -> "Cover Image"
                                    BackgroundType.Image -> "Image"
                                    BackgroundType.ImageRepeating -> "Repeating Image"
                                    BackgroundType.Native -> "Native"
                                    is BackgroundType.Pdf -> "Static pdf Page"
                                }
                            Text(
                                text = typeName,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Default.ArrowCircleRight,
                                contentDescription = "Expand selector",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    /* -------------- Linking to external files -----------*/


                    val defaultPath = getLinkedFilesDir().toUri().toString()
                    NotebookLinkRow(
                        isLinkedInit = book!!.linkedExternalUri != null,
                        linkedUriInit = book!!.linkedExternalUri,
                        // `book` is observed live, so the linked path follows a rename as soon as
                        // it is committed — the same thing the dialog's old editable `bookTitle`
                        // state gave this row before naming moved into its own prompt.
                        bookTitle = book!!.title,
                        defaultPath = defaultPath,
                        onLinkChanged = { newUri ->
                            val updated = book!!.copy(linkedExternalUri = newUri)
                            scope.launch {
                                saveBook(updated)
                            }
                        })

                    /* -------------- Other book info -----------*/
                    Text(stringResource(R.string.details_notebook_pages, book!!.pageIds.size))
                    Text("Size: TODO!")
                    Row {
                        Text(stringResource(R.string.details_notebook_in_folder))
                        BreadCrumb(folders = breadcrumbFolders, fontSize = 16)  { }
                    }
                    Text(stringResource(R.string.details_notebook_created, formattedCreatedAt))
                    Text(stringResource(R.string.details_notebook_last_updated, formattedUpdatedAt))
                }
            }

            Spacer(Modifier.height(16.dp))

            // Grid Actions Section. FlowRow, not Row: the actions are fixed-width and there are now
            // enough of them to overflow a narrow dialog, where a Row would clip the last one
            // off-screen rather than wrap it.
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ActionButton(stringResource(R.string.details_notebook_buttons_delete)) {
                    showDeleteDialog = true
                }
                ActionButton(stringResource(R.string.details_notebook_buttons_move)) {
                    showMoveDialog = true
                }
                ActionButton(stringResource(R.string.details_notebook_buttons_export)) {
                    showExportDialog = true
                }
                ActionButton(stringResource(R.string.details_notebook_buttons_copy)) {
                    onClose()
                    scope.launch {
                        snackManager.runWithSnack("Copying notebook…", 3000) {
                            val copyId = appRepository.duplicateNotebook(bookId)
                            if (copyId == null) "Could not copy this notebook"
                            else {
                                // Queued explicitly: the outbox is fed by ink edits, and a copy
                                // nobody has drawn in yet is not one of those.
                                couchSync.noteDocumentChanged(CouchDocId.notebook(copyId))
                                "Notebook copied"
                            }
                        }
                    }
                }
                // Sync this one notebook on demand. Until now the only ways to get a notebook to
                // the server were the periodic run, a full "Sync now" in settings, or closing the
                // note with sync-on-close enabled — none of which is reachable from the notebook
                // you are actually looking at and wondering about.
                ActionButton(stringResource(R.string.details_notebook_buttons_sync)) {
                    scope.launch {
                        val outcome = requestNotebookSync(kvProxy, syncScheduler, bookId)
                        snackManager.displaySnack(
                            SnackConf(text = context.getString(outcome.messageRes()), duration = 3000)
                        )
                    }
                }
            }
        }

    }

}

@Composable
fun ActionButton(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(100.dp, 40.dp)
            .background(Color.LightGray, RectangleShape)
            .border(1.dp, Color.Black, RectangleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(text, fontWeight = FontWeight.Bold)
    }
}


@Composable
fun NotebookLinkRow(
    isLinkedInit: Boolean,
    linkedUriInit: String?,
    bookTitle: String,
    defaultPath: String,
    onLinkChanged: (String?) -> Unit
) {
    var isLinked by remember { mutableStateOf(isLinkedInit) }

    // The linked uri is a folder; the export lands in it under the notebook's title, so show the
    // destination file rather than just the directory. Long uris keep only their head and tail.
    val linkText = linkedUriInit?.let {
        val folder = if (it.length > 32) "${it.take(5)}...${it.takeLast(7)}" else it
        "$folder/$bookTitle"
    } ?: "none"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.details_notebook_linked_to, linkText),
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (isLinked) {
            Button(
                onClick = {
                    isLinked = false
                    onLinkChanged(null)
                },
                modifier = Modifier.weight(0.3f, fill = false)
            ) {
                Text("Unlink")
            }
        } else {
            Button(
                onClick = {
                    isLinked = true
                    onLinkChanged(defaultPath)
                },
                modifier = Modifier.weight(0.3f, fill = false)
            ) {
                Text("Link")
            }
        }
    }
}