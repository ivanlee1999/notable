package com.ethran.notable.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.ethran.notable.R
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.deletePage
import com.ethran.notable.ui.dialogs.NamePromptDialog
import com.ethran.notable.ui.dialogs.ShowSimpleConfirmationDialog
import com.ethran.notable.ui.noRippleClickable
import com.ethran.notable.ui.rememberCouchSyncController
import kotlinx.coroutines.launch


@Composable
fun PageMenu(
    appRepository: AppRepository,
    notebookId: String? = null,
    pageId: String,
    index: Int? = null,
    canDelete: Boolean,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val couchSync = rememberCouchSyncController()

    // The existing name is fetched before the prompt opens, not alongside it: the dialog captures
    // its initial value on first composition, so a title arriving a frame later would be missed.
    var renameInitialValue by remember { mutableStateOf<String?>(null) }
    var isConfirmingDelete by remember { mutableStateOf(false) }

    // The same confirmation the page overview asks for. Deleting a page was safe in one surface
    // and a one-tap permanent delete in the other, which makes the safety a property of where you
    // happened to be standing rather than of the action.
    if (isConfirmingDelete) {
        ShowSimpleConfirmationDialog(
            title = "Delete this page?",
            message = "The page and everything on it are deleted here and on every device you "
                + "sync with. It cannot be undone.",
            onConfirm = {
                // Closed from inside the coroutine for the same reason the rename is: `onClose`
                // unmounts this composable and `scope` dies with it.
                scope.launch {
                    deletePage(appRepository, pageId, context.filesDir, couchSync)
                    onClose()
                }
            },
            onCancel = { onClose() },
            confirmButtonText = "Delete"
        )
        return
    }

    if (renameInitialValue != null) {
        NamePromptDialog(
            title = stringResource(R.string.name_prompt_page_title),
            initialValue = renameInitialValue!!,
            onConfirm = { name ->
                // Closing from inside the coroutine, not beside it: `onClose` unmounts this
                // composable, and `scope` dies with it — a rename launched and *then* closed can
                // be cancelled before the write lands.
                scope.launch {
                    appRepository.pageRepository.rename(pageId, name)
                    onClose()
                }
            },
            onDismiss = { onClose() }
        )
        return
    }


    Popup(
        alignment = Alignment.TopStart,
        onDismissRequest = { onClose() },
        properties = PopupProperties(focusable = true)
    ) {
        Column(
            Modifier
                .border(1.dp, Color.Black, RectangleShape)
                .background(Color.White)
                .width(IntrinsicSize.Max)
        ) {
            if (notebookId != null && index != null) {
                Box(
                    Modifier
                        .padding(10.dp)
                        .noRippleClickable {
                            scope.launch {
                                appRepository.bookRepository.changePageIndex(
                                    notebookId,
                                    pageId,
                                    index - 1
                                )
                            }
                        }
                ) {
                    Text("Move Left")
                }

                Box(
                    Modifier
                        .padding(10.dp)
                        .noRippleClickable {
                            scope.launch {
                                appRepository.bookRepository.changePageIndex(
                                    notebookId,
                                    pageId,
                                    index + 1
                                )
                            }
                        }) {
                    Text("Move right")
                }
                Box(
                    Modifier
                        .padding(10.dp)
                        .noRippleClickable {
                            scope.launch {
                                appRepository.newPageInBook(notebookId, index + 1)
                            }
                        }) {
                    Text("Insert after")
                }
            }

            Box(
                Modifier
                    .padding(10.dp)
                    .noRippleClickable {
                        scope.launch {
                            renameInitialValue =
                                appRepository.pageRepository.getById(pageId)?.title.orEmpty()
                        }
                    }) {
                Text(stringResource(R.string.rename))
            }

            Box(
                Modifier
                    .padding(10.dp)
                    .noRippleClickable {
                        scope.launch {
                            appRepository.duplicatePage(pageId)
                        }
                    }) {
                Text("Duplicate")
            }
            if (canDelete) {
                Box(
                    Modifier
                        .padding(10.dp)
                        .noRippleClickable { isConfirmingDelete = true }) {
                    Text("Delete")
                }
            }
        }
    }
}
