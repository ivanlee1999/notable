package com.ethran.notable.editor.ui

import androidx.compose.runtime.Composable
import com.ethran.notable.ui.components.ActionMenu
import com.ethran.notable.ui.components.MenuAction
import com.ethran.notable.ui.components.RowRule
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ethran.notable.R
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.deletePage
import com.ethran.notable.ui.dialogs.NamePromptDialog
import com.ethran.notable.ui.dialogs.ShowSimpleConfirmationDialog
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
            confirmButtonText = "Delete page"
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


    ActionMenu(onDismiss = onClose, below = 0.dp) {
        if (notebookId != null && index != null) {
            if (index > 0) MenuAction("Move page earlier", {
                scope.launch {
                    appRepository.bookRepository.changePageIndex(notebookId, pageId, index - 1)
                    onClose()
                }
            })
            MenuAction("Move page later", {
                scope.launch {
                    appRepository.bookRepository.changePageIndex(notebookId, pageId, index + 1)
                    onClose()
                }
            })
            MenuAction("Add page after", {
                scope.launch {
                    appRepository.newPageInBook(notebookId, index + 1)
                    onClose()
                }
            })
        }
        MenuAction("Rename page", {
            scope.launch {
                renameInitialValue = appRepository.pageRepository.getById(pageId)?.title.orEmpty()
            }
        })
        MenuAction("Duplicate page", {
            scope.launch {
                appRepository.duplicatePage(pageId)
                onClose()
            }
        })
        if (canDelete) {
            RowRule()
            MenuAction("Delete page", { isConfirmingDelete = true })
        }
    }
}
