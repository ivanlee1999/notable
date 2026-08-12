package com.ethran.notable.ui.views

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.ethran.notable.R
import com.ethran.notable.data.db.Folder
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.editor.utils.autoEInkAnimationOnScroll
import com.ethran.notable.navigation.NavigationDestination
import com.ethran.notable.ui.components.Kicker
import com.ethran.notable.ui.components.ListRow
import com.ethran.notable.ui.components.SectionHeader
import com.ethran.notable.ui.dialogs.ShowSimpleConfirmationDialog
import com.ethran.notable.ui.noRippleClickable
import com.ethran.notable.ui.theme.Kaleido
import com.ethran.notable.ui.theme.kaleidoMetrics
import com.ethran.notable.ui.viewmodels.TrashUiState
import com.ethran.notable.ui.viewmodels.TrashViewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.RotateCcw
import compose.icons.feathericons.Trash2

object TrashDestination : NavigationDestination {
    override val route = "trash"
}

/**
 * Recently Deleted, for folders and notebooks.
 *
 * Everything here is still on this device and still on the server: the Trash is a staging area,
 * not a deletion. Restore puts an item back where it came from — or at the top level, if that
 * folder is itself gone — and is the ordinary outcome. Delete permanently is the only irreversible
 * action in the app and the only one that publishes anything, so it always asks first.
 */
@Composable
fun TrashView(
    onBack: () -> Unit,
    viewModel: TrashViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    TrashContent(
        uiState = uiState,
        onBack = onBack,
        onRestoreFolder = viewModel::restoreFolder,
        onRestoreNotebook = viewModel::restoreNotebook,
        onPurgeFolder = viewModel::purgeFolder,
        onPurgeNotebook = viewModel::purgeNotebook,
        onEmptyTrash = viewModel::emptyTrash,
    )
}

@Composable
private fun TrashContent(
    uiState: TrashUiState,
    onBack: () -> Unit,
    onRestoreFolder: (String) -> Unit,
    onRestoreNotebook: (String) -> Unit,
    onPurgeFolder: (String) -> Unit,
    onPurgeNotebook: (String) -> Unit,
    onEmptyTrash: () -> Unit,
) {
    var confirmingEmpty by remember { mutableStateOf(false) }

    if (confirmingEmpty) {
        ShowSimpleConfirmationDialog(
            title = stringResource(R.string.trash_confirm_empty_title),
            message = pluralStringResource(
                R.plurals.trash_confirm_empty_message, uiState.size, uiState.size
            ),
            onConfirm = {
                confirmingEmpty = false
                onEmptyTrash()
            },
            onCancel = { confirmingEmpty = false },
            confirmButtonText = stringResource(R.string.trash_delete_permanently),
        )
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Kaleido.Paper)
    ) {
        val metrics = kaleidoMetrics(maxWidth)

        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(
                        start = metrics.pad, end = metrics.pad,
                        top = metrics.pad, bottom = 12.dp
                    )
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Box(
                        Modifier
                            .size(metrics.hit)
                            .border(1.dp, Kaleido.Ink)
                            .noRippleClickable(onBack),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            FeatherIcons.ArrowLeft, stringResource(R.string.trash_back),
                            tint = Kaleido.Ink, modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Kicker(stringResource(R.string.trash_kicker))
                        Text(
                            text = stringResource(R.string.trash_title),
                            fontSize = metrics.titleSize,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = (-0.9).sp,
                            color = Kaleido.Ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (!uiState.isEmpty) {
                        Text(
                            text = stringResource(R.string.trash_empty_action),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = Kaleido.Ink,
                            modifier = Modifier
                                .border(1.dp, Kaleido.Ink)
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                                .noRippleClickable { confirmingEmpty = true }
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(Kaleido.SectionRule)
                        .background(Kaleido.Ink)
                )
            }

            if (uiState.isEmpty) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(metrics.pad),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = stringResource(R.string.trash_nothing_deleted),
                        color = Kaleido.Muted,
                        fontSize = 14.sp,
                    )
                }
                return@Column
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .autoEInkAnimationOnScroll(),
                contentPadding = PaddingValues(
                    start = metrics.pad, end = metrics.pad,
                    top = metrics.pad, bottom = metrics.pad * 2
                )
            ) {
                if (uiState.folders.isNotEmpty()) {
                    item(key = "folders-header") {
                        SectionHeader(stringResource(R.string.trash_section_folders))
                        Spacer(Modifier.height(12.dp))
                    }
                    items(uiState.folders, key = { "folder-${it.id}" }) { folder ->
                        TrashRow(
                            hit = metrics.hit,
                            label = folder.title,
                            deletedAtMillis = folder.deletedAt?.time,
                            // Everything under a trashed folder went with it, and the row is the
                            // only place that is ever said.
                            secondary = stringResource(R.string.trash_folder_subtitle),
                            onRestore = { onRestoreFolder(folder.id) },
                            onPurge = { onPurgeFolder(folder.id) },
                            purgeMessage = stringResource(
                                R.string.trash_purge_folder_message, folder.title
                            ),
                            leading = {
                                Box(
                                    Modifier
                                        .size(22.dp)
                                        .background(Kaleido.spineFor(folder.id))
                                )
                            }
                        )
                    }
                    item(key = "folders-gap") { Spacer(Modifier.height(22.dp)) }
                }

                if (uiState.notebooks.isNotEmpty()) {
                    item(key = "notebooks-header") {
                        SectionHeader(stringResource(R.string.trash_section_notebooks))
                        Spacer(Modifier.height(12.dp))
                    }
                    items(uiState.notebooks, key = { "book-${it.id}" }) { book ->
                        TrashRow(
                            hit = metrics.hit,
                            label = book.title,
                            deletedAtMillis = book.deletedAt?.time,
                            secondary = pluralStringResource(
                                R.plurals.trash_notebook_pages,
                                book.pageIds.size, book.pageIds.size
                            ),
                            onRestore = { onRestoreNotebook(book.id) },
                            onPurge = { onPurgeNotebook(book.id) },
                            purgeMessage = stringResource(
                                R.string.trash_purge_notebook_message, book.title
                            ),
                            leading = {
                                Box(
                                    Modifier
                                        .size(22.dp)
                                        .background(Kaleido.spineFor(book.id))
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * One trashed item: what it was, when it went, and the two ways out.
 *
 * Restore is a plain tap because it is reversible; deleting is behind its own confirmation because
 * it is the only thing on this screen that cannot be taken back.
 */
@Composable
private fun TrashRow(
    hit: Dp,
    label: String,
    deletedAtMillis: Long?,
    secondary: String,
    onRestore: () -> Unit,
    onPurge: () -> Unit,
    purgeMessage: String,
    leading: @Composable () -> Unit,
) {
    var confirming by remember { mutableStateOf(false) }

    if (confirming) {
        ShowSimpleConfirmationDialog(
            title = stringResource(R.string.trash_confirm_purge_title),
            message = stringResource(R.string.trash_confirm_purge_message, purgeMessage),
            onConfirm = {
                confirming = false
                onPurge()
            },
            onCancel = { confirming = false },
            confirmButtonText = stringResource(R.string.trash_delete_permanently),
        )
    }

    val when_ = deletedAtMillis?.let { DateFormat.format("dd MMM yyyy HH:mm", it).toString() }
    val subtitle =
        if (when_ == null) secondary
        else stringResource(R.string.trash_deleted_at, secondary, when_)

    Row(verticalAlignment = Alignment.CenterVertically) {
        ListRow(
            hit = hit,
            label = label,
            secondary = subtitle,
            // Tapping the row restores: the recoverable action is the one that should be easy to
            // hit, and the destructive one is the button that has to be aimed at.
            onClick = onRestore,
            showChevron = false,
            leading = leading,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .size(hit)
                .border(1.dp, Kaleido.Ink)
                .noRippleClickable(onRestore),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                FeatherIcons.RotateCcw, stringResource(R.string.trash_restore),
                tint = Kaleido.Ink, modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .size(hit)
                .border(1.dp, Kaleido.Ink)
                .noRippleClickable { confirming = true },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                FeatherIcons.Trash2, stringResource(R.string.trash_delete_permanently),
                tint = Kaleido.Ink, modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Preview(showBackground = true, name = "Trash", widthDp = 800, heightDp = 1000)
@Composable
private fun TrashPreview() = TrashContent(
    uiState = TrashUiState(
        folders = listOf(Folder(id = "f1", title = "Old research", deletedAt = java.util.Date())),
        notebooks = listOf(
            Notebook(id = "b1", title = "Scratch", pageIds = List(6) { "p$it" },
                deletedAt = java.util.Date())
        ),
    ),
    onBack = {}, onRestoreFolder = {}, onRestoreNotebook = {},
    onPurgeFolder = {}, onPurgeNotebook = {}, onEmptyTrash = {},
)
