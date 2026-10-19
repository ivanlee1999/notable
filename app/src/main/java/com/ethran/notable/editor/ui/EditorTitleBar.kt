package com.ethran.notable.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ethran.notable.R
import com.ethran.notable.editor.ToolbarAction
import com.ethran.notable.editor.ToolbarUiState
import com.ethran.notable.editor.ui.toolbar.ToolbarMenu
import com.ethran.notable.ui.components.RowRule
import com.ethran.notable.ui.noRippleClickable
import com.ethran.notable.ui.theme.Kaleido
import com.ethran.notable.ui.theme.kaleidoMetrics
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.ChevronLeft
import compose.icons.feathericons.MoreHorizontal

/**
 * The editor's own title bar: where you are, how far through, and the two page-level actions.
 *
 * Docked chrome like the tool rail — [Kaleido.Rail] over the writing surface, closed by a
 * hairline rather than a shadow, and never floating. It carries the things the rail cannot
 * say in an icon: the notebook's name and the position in it. The page-level actions that
 * belong to the *document* rather than the hand — the paper template and the menu — sit
 * here, so the rail is only ever tools.
 *
 * Hidden with the rail: collapsing the toolbar is the promise of a full sheet of paper, and
 * a title bar that outlived it would break that. [editorTitleBarHeight] must therefore be
 * excluded from the raw-drawing surface on exactly the same condition — see `setupSurface`.
 */
@Composable
fun EditorTitleBar(
    uiState: ToolbarUiState,
    onAction: (ToolbarAction) -> Unit,
    onSwitchNotebook: () -> Unit = { onAction(ToolbarAction.OpenQuickNav) },
) {
    val metrics = kaleidoMetrics(LocalConfiguration.current.screenWidthDp.dp)

    // The row and the hairline below it together come to editorTitleBarHeight — the band the
    // pen is kept out of.
    Column(
        Modifier
            .fillMaxWidth()
            .background(Kaleido.Rail)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Exit straight to the containing folder. Page management remains in the menu.
            TitleBarButton(
                icon = FeatherIcons.ChevronLeft,
                contentDescription = stringResource(R.string.editor_title_bar_back),
                hitSize = 48.dp,
                onClick = { onAction(ToolbarAction.NavigateToLibrary) },
            )

            // The name and chevron form one generous target. Separate chevron/template
            // buttons used to consume nearly the whole narrow header and reduce the name
            // to one character. Document settings remain in the menu.
            Row(
                Modifier.weight(1f).height(48.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription = "Switch notebook or page"
                        stateDescription = uiState.notebookTitle.orEmpty()
                    }
                    .noRippleClickable(onSwitchNotebook),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = uiState.notebookTitle.orEmpty(),
                    fontSize = metrics.bodySize,
                    fontWeight = FontWeight.Medium,
                    color = Kaleido.Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(FeatherIcons.ChevronDown, contentDescription = null,
                    tint = Kaleido.Muted, modifier = Modifier.size(14.dp))
            }

            Box(
                Modifier.size(48.dp)
                    .semantics {
                        contentDescription = "Pages"
                        stateDescription = "Page ${uiState.pageNumberInfo.replace("/", " of ")}"
                    }
                    .noRippleClickable { onAction(ToolbarAction.OpenQuickNav) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = uiState.pageNumberInfo.replace("/", " / "),
                    fontSize = 11.sp,
                    color = Kaleido.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Box {
                TitleBarButton(
                    icon = FeatherIcons.MoreHorizontal,
                    contentDescription = stringResource(R.string.toolbar_element_menu),
                    hitSize = 48.dp,
                    onClick = { onAction(ToolbarAction.ToggleMenu) },
                )
                if (uiState.isMenuOpen) ToolbarMenu(uiState, onAction, fromTitleBar = true)
            }
        }
        RowRule()
    }
}

/**
 * Shared with the raw-drawing exclusion band and overlay insets. Keep one fixed 48dp
 * interactive row at every window width; UI density scales the row and canvas exclusion
 * together. The width argument remains for callers that already pass configuration width.
 */
fun editorTitleBarHeight(@Suppress("UNUSED_PARAMETER") screenWidthDp: Int): Dp =
    48.dp + Kaleido.RowRule

/** The compact icon keeps the same finger-sized target as the rest of the chrome. */
@Composable
private fun TitleBarButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    hitSize: Dp,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(hitSize)
            .noRippleClickable(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Kaleido.Ink,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
@Preview(showBackground = true, widthDp = 780)
private fun EditorTitleBarPreview() {
    EditorTitleBar(
        uiState = ToolbarUiState(
            notebookId = "book1",
            notebookTitle = "Field Notes",
            pageNumberInfo = "3/128",
        ),
        onAction = {},
    )
}
