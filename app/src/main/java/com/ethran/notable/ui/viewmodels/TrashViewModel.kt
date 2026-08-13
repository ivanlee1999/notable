package com.ethran.notable.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.asFlow
import androidx.lifecycle.viewModelScope
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.TrashRepository
import com.ethran.notable.data.db.Folder
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.sync.couch.CouchSyncController
import com.ethran.notable.ui.SnackConf
import com.ethran.notable.ui.SnackDispatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TrashUiState(
    val folders: List<Folder> = emptyList(),
    val notebooks: List<Notebook> = emptyList(),
) {
    val isEmpty: Boolean get() = folders.isEmpty() && notebooks.isEmpty()
    val size: Int get() = folders.size + notebooks.size
}

/**
 * The Trash screen's state and the four things it can do.
 *
 * Restoring and purging both reach the network — the Trash is synced — but only purging is
 * irreversible. See [TrashRepository] for why the two are split.
 */
@HiltViewModel
class TrashViewModel @Inject constructor(
    appRepository: AppRepository,
    private val trashRepository: TrashRepository,
    private val couchSync: CouchSyncController,
    private val snackDispatcher: SnackDispatcher,
) : ViewModel() {

    private val folderRepository = appRepository.folderRepository
    private val bookRepository = appRepository.bookRepository

    val uiState: StateFlow<TrashUiState> = combine(
        folderRepository.getTrashed().asFlow(),
        bookRepository.getTrashed().asFlow(),
    ) { folders, notebooks -> TrashUiState(folders, notebooks) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TrashUiState())

    fun restoreFolder(folderId: String) = restore { trashRepository.restoreFolder(folderId) }

    fun restoreNotebook(notebookId: String) = restore { trashRepository.restoreNotebook(notebookId) }

    /**
     * Every restore reaches the server: `deletedAt` is part of the document, so taking something
     * out of the Trash here is only true elsewhere once the peer has heard about it. The
     * repository returns the ids rather than the caller guessing which ones changed.
     */
    private fun restore(block: suspend () -> List<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            block().forEach { couchSync.noteDocumentChanged(it) }
        }
    }

    fun purgeFolder(folderId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val removed = trashRepository.purgeFolder(folderId)
            announcePurge(removed)
        }
    }

    fun purgeNotebook(notebookId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val removed = trashRepository.purgeNotebook(notebookId)
            announcePurge(removed)
        }
    }

    fun emptyTrash() {
        viewModelScope.launch(Dispatchers.IO) {
            val removed = trashRepository.emptyTrash()
            announcePurge(removed)
        }
    }

    /**
     * Say what went, then push. The tombstones are already written and durable by the time this
     * runs — the push is what makes them travel today rather than at the next sync, and failing to
     * reach the server changes nothing about the deletion having happened.
     */
    private suspend fun announcePurge(removed: TrashRepository.DeletionScope) {
        if (removed.folders.isEmpty() && removed.notebooks.isEmpty()) return
        val parts = listOfNotNull(
            removed.folders.size.takeIf { it > 0 }
                ?.let { "$it folder${if (it == 1) "" else "s"}" },
            removed.notebookCount.takeIf { it > 0 }
                ?.let { "$it notebook${if (it == 1) "" else "s"}" },
        )
        snackDispatcher.showOrUpdateSnack(
            SnackConf(text = "Deleted ${parts.joinToString(" and ")}", duration = 3000)
        )
        couchSync.requestPushNow()
    }
}
