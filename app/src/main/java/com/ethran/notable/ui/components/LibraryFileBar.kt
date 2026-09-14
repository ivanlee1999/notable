package com.ethran.notable.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ethran.notable.R
import com.ethran.notable.editor.utils.autoEInkAnimationOnScroll
import com.ethran.notable.ui.noRippleClickable
import com.ethran.notable.ui.theme.Kaleido
import com.ethran.notable.ui.viewmodels.LibraryTree
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.Folder as FolderIcon
import compose.icons.feathericons.Inbox
import compose.icons.feathericons.RefreshCw

/** How wide the file bar is. Fixed: it is furniture, and a draggable splitter on e-ink is a
 *  full-screen refresh per frame. */
val FILE_BAR_WIDTH = 280.dp

/** One folder of the flattened tree, at its depth. */
private data class FileBarRow(
    /** The folder id. Also the row key, now the tree holds nothing else. */
    val id: String,
    val title: String,
    /** Everything filed directly in the folder — subfolders *and* notebooks. */
    val count: Int,
    val depth: Int,
    /**
     * Whether the folder holds *subfolders* — not whether it holds anything. A folder of nothing
     * but notebooks still counts above zero, and a triangle that opens onto nothing is worse
     * than no triangle at all.
     */
    val hasSubfolders: Boolean,
)

/**
 * The library's left column: the folder tree, always open.
 *
 * Every folder, nested — which is the thing the breadcrumb could never say. A breadcrumb tells
 * you the path you walked; it cannot tell you what else is there, so finding a note two folders
 * sideways meant walking back to the root and down again. The tree is that walk, already done.
 *
 * Folders only. The bar answers *where am I*; what is filed in a folder is the shelf's question,
 * and the shelf answers it with a cover, a page count and a sync badge where a 280dp row could
 * only repeat the title. Listing every note in both columns drew the library twice, and made the
 * column that should stay short exactly as long as the one beside it.
 *
 * Only above [com.ethran.notable.ui.theme.KALEIDO_WIDE_BREAKPOINT]: a one-handed device has one
 * column's worth of room and spends it on the shelf.
 *
 * Rows start *expanded* and [collapsed] holds what the user has explicitly shut, rather than the
 * other way round. A tree that opened closed would hide the very thing it exists to show, and a
 * folder arriving from a sync shows its contents without having to be opened first.
 */
@Composable
fun LibraryFileBar(
    tree: LibraryTree,
    selectedFolderId: String?,
    isSyncing: Boolean,
    onSelectFolder: (String?) -> Unit,
    onSyncNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var collapsed by rememberSaveable { mutableStateOf(emptySet<String>()) }

    val rows = remember(tree, collapsed) { flatten(tree, collapsed) }

    Row(modifier.fillMaxHeight().width(FILE_BAR_WIDTH + Kaleido.SectionRule)) {
        Column(
            Modifier
                .width(FILE_BAR_WIDTH)
                .fillMaxHeight()
                .background(Kaleido.Paper)
        ) {
            Masthead()

            LazyColumn(
                Modifier
                    .weight(1f)
                    .autoEInkAnimationOnScroll()
            ) {
                item(key = "all-notes") {
                    AllNotesRow(
                        count = tree.books.count { it.parentFolderId == null },
                        selected = selectedFolderId == null,
                        onClick = { onSelectFolder(null) },
                    )
                    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 4.dp)) {
                        SectionHeader(stringResource(R.string.home_folders))
                    }
                }
                items(rows, key = { it.id }) { row ->
                    FolderTreeRow(
                        row = row,
                        selected = selectedFolderId == row.id,
                        shut = row.id in collapsed,
                        onToggle = {
                            collapsed =
                                if (row.id in collapsed) collapsed - row.id else collapsed + row.id
                        },
                        onClick = { onSelectFolder(row.id) },
                    )
                }
                item(key = "tail") { Spacer(Modifier.height(16.dp)) }
            }

            Footer(isSyncing = isSyncing, onSyncNow = onSyncNow)
        }
        // The rule belongs to the bar, not to the shelf: it is what makes the two columns read
        // as one screen split rather than two panes floating side by side.
        Box(
            Modifier
                .width(Kaleido.SectionRule)
                .fillMaxHeight()
                .background(Kaleido.Ink)
        )
    }
}

/** The wordmark on its rule — the design's status strip, and nothing else. */
@Composable
private fun Masthead() {
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 8.dp)) {
        Text(
            text = stringResource(R.string.app_name).uppercase(),
            fontSize = Kaleido.KickerSize,
            letterSpacing = Kaleido.KickerTracking,
            color = Kaleido.Ink,
            maxLines = 1,
        )
        Spacer(Modifier.height(5.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(Kaleido.SectionRule)
                .background(Kaleido.Ink)
        )
    }
}

/**
 * The library root. Selected when no folder is, so the bar always says where you are — there is
 * no state in which nothing is lit.
 */
@Composable
private fun AllNotesRow(count: Int, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .background(if (selected) Kaleido.Ink else Color.Transparent)
            .semantics { this.selected = selected }
            .noRippleClickable(onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val tint = if (selected) Kaleido.Paper else Kaleido.Ink
        Icon(FeatherIcons.Inbox, null, tint = tint, modifier = Modifier.size(GLYPH))
        Text(
            text = stringResource(R.string.home_view_name),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Count(count, tint)
    }
}

/**
 * A folder.
 *
 * The disclosure triangle is a sibling of the row's click target rather than nested inside it —
 * a clickable inside another clickable never sees the tap — so shutting a folder and selecting
 * it stay separate gestures on the same row.
 */
@Composable
private fun FolderTreeRow(
    row: FileBarRow,
    selected: Boolean,
    shut: Boolean,
    onToggle: () -> Unit,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .background(if (selected) Kaleido.Ink else Color.Transparent)
            .semantics { this.selected = selected }
            .padding(start = indent(row.depth), end = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = if (selected) Kaleido.Paper else Kaleido.Ink
        if (row.hasSubfolders) Box(
            Modifier
                .size(DISCLOSURE, ROW_HEIGHT)
                .semantics { stateDescription = if (shut) "Collapsed" else "Expanded" }
                .noRippleClickable(onToggle),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (shut) FeatherIcons.ChevronRight else FeatherIcons.ChevronDown,
                if (shut) "Expand ${row.title}" else "Collapse ${row.title}",
                tint = tint, modifier = Modifier.size(13.dp)
            )
        } else Spacer(Modifier.width(DISCLOSURE))

        Row(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .noRippleClickable(onClick)
                .padding(start = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(FeatherIcons.FolderIcon, null, tint = tint, modifier = Modifier.size(GLYPH))
            Text(
                text = row.title,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Count(row.count, tint)
        }
    }
}

/** The count at the end of a row: how many things are filed directly in the folder. */
@Composable
private fun Count(count: Int, tint: Color) {
    Text(text = count.toString(), fontSize = 11.sp, color = tint)
}

/** Sync now, next to the tree it refreshes rather than only at the top of the shelf. */
@Composable
private fun Footer(isSyncing: Boolean, onSyncNow: () -> Unit) {
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 12.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(Kaleido.SectionRule)
                .background(Kaleido.Ink)
        )
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .then(
                    if (isSyncing) Modifier.background(Kaleido.Ink)
                    else Modifier.border(1.dp, Kaleido.Ink)
                )
                .noRippleClickable(onSyncNow)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val tint = if (isSyncing) Kaleido.Paper else Kaleido.Ink
            Icon(FeatherIcons.RefreshCw, null, tint = tint, modifier = Modifier.size(14.dp))
            Text(
                text = stringResource(R.string.sync_now),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = tint,
                maxLines = 1,
            )
        }
    }
}

private val ROW_HEIGHT = 48.dp
private val GLYPH = 16.dp
private val DISCLOSURE = 44.dp

/**
 * Where a row at [depth] starts. The triangle sits in the 20dp gutter the rest of the panel
 * keeps clear, so the tree's glyph column stays put whether or not a row can be opened.
 */
// Keep titles and their actions visible even in deeply nested imported folders, as in Bopa.
private fun indent(depth: Int): Dp = minOf(10 + 12 * depth, 46).dp

/**
 * The tree as the flat row list the bar draws.
 *
 * Flattened here rather than drawn recursively so the whole thing can go through a LazyColumn: a
 * library of a few hundred notes is a few hundred rows, and composing all of them to show twenty
 * is what makes an e-ink list feel broken.
 *
 * A `parentFolderId` cycle — reachable through a half-merged sync, where two devices each moved a
 * folder inside the other — would otherwise recurse until the stack ran out. Tracking the ids
 * already on the path breaks it and drops the folder that closed the loop, which shows up as a
 * missing row rather than as a crash.
 */
private fun flatten(
    tree: LibraryTree,
    collapsed: Set<String>,
): List<FileBarRow> {
    val foldersByParent = tree.folders.groupBy { it.parentFolderId }
    val bookCounts = tree.books.groupingBy { it.parentFolderId }.eachCount()
    val out = mutableListOf<FileBarRow>()

    fun walk(parent: String?, depth: Int, ancestors: Set<String>) {
        for (folder in foldersByParent[parent].orEmpty().sortedBy { it.title.lowercase() }) {
            if (folder.id in ancestors) continue
            val subfolders = foldersByParent[folder.id]?.size ?: 0
            out += FileBarRow(
                id = folder.id,
                title = folder.title,
                count = subfolders + (bookCounts[folder.id] ?: 0),
                depth = depth,
                hasSubfolders = subfolders > 0,
            )
            if (folder.id !in collapsed) walk(folder.id, depth + 1, ancestors + folder.id)
        }
    }

    walk(null, 0, emptySet())
    return out
}
