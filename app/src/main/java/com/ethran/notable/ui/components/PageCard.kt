package com.ethran.notable.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ethran.notable.ui.theme.Kaleido
import compose.icons.FeatherIcons
import compose.icons.feathericons.MoreVertical

@Composable
fun PageCard(
    pageId: String,
    pageIndex: Int,
    isOpen: Boolean,
    isEditMode: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit,
    onAddAfter: () -> Unit,
    modifier: Modifier = Modifier,
    touchModifier: Modifier = Modifier,
    isReorderDragging: Boolean = true,
) {
    var showMenu by remember(pageId) { mutableStateOf(false) }
    val number = pageIndex + 1
    Column(modifier) {
        PagePreview(
            modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f)
                .border(if (isOpen) 2.dp else 1.dp, Kaleido.Ink)
                .semantics {
                    contentDescription = "Open page $number"
                    selected = isOpen
                }
                .clickable(enabled = isReorderDragging && !isEditMode, role = Role.Button,
                    interactionSource = remember { MutableInteractionSource() }, indication = null,
                    onClick = onOpen)
                .then(touchModifier), pageId = pageId,
        )
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(if (isOpen) "Page $number · Current" else "Page $number",
                color = Kaleido.Ink, modifier = Modifier.weight(1f).padding(end = 4.dp))
            Box {
                SquareButton(48.dp, { showMenu = true }) {
                    Icon(FeatherIcons.MoreVertical, "Page $number options", tint = Kaleido.Ink)
                }
                if (showMenu) ActionMenu(onDismiss = { showMenu = false }) {
                    MenuAction("Duplicate page", { showMenu = false; onDuplicate() })
                    MenuAction("Add page after", { showMenu = false; onAddAfter() })
                    RowRule()
                    MenuAction("Delete page", { showMenu = false; onDelete() })
                }
            }
        }
    }
}
