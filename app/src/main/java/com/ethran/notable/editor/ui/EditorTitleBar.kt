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
import com.ethran.notable.ui.components.RowRule
import com.ethran.notable.ui.noRippleClickable
import com.ethran.notable.ui.theme.Kaleido
import com.ethran.notable.ui.theme.kaleidoMetrics
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronLeft
import compose.icons.feathericons.FileText
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
                .height(metrics.hit)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Back goes one level out, not all the way home: out of a notebook is its page list.
            val backTarget = ToolbarAction.NavigateToPages
            Icon(
                imageVector = FeatherIcons.ChevronLeft,
                contentDescription = stringResource(R.string.editor_title_bar_back),
                tint = Kaleido.Ink,
                modifier = Modifier
                    .size(18.dp)
                    .noRippleClickable { onAction(backTarget) },
            )

            Text(
                // Empty only for the frame before the page has loaded.
                text = uiState.notebookTitle.orEmpty(),
                fontSize = metrics.bodySize,
                fontWeight = FontWeight.Bold,
                color = Kaleido.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = true),
            )

            Text(
                text = uiState.pageNumberInfo.replace("/", " / "),
                fontSize = 11.sp,
                color = Kaleido.Muted,
                maxLines = 1,
            )

            TitleBarButton(
                icon = FeatherIcons.FileText,
                contentDescription = stringResource(R.string.change_background),
                onClick = { onAction(ToolbarAction.ToggleBackgroundSelector(true)) },
            )
            TitleBarButton(
                icon = FeatherIcons.MoreHorizontal,
                contentDescription = stringResource(R.string.toolbar_element_menu),
                onClick = { onAction(ToolbarAction.ToggleMenu) },
            )
        }
        RowRule()
    }
}

/**
 * Everything the bar covers: a row at the size step the screen is drawn at — the same hit
 * target the rail and the Library use, so nothing in the chrome is smaller than the minimum
 * touch edge — plus the hairline that closes it. The rule is part of the band because a
 * stroke laid across it would otherwise sit on top of the chrome's own edge.
 *
 * Taken from [android.content.res.Configuration.screenWidthDp] rather than a measured
 * constraint so the drawing surface, which is armed off the composition, can work out the
 * band to exclude and arrive at the same answer.
 */
fun editorTitleBarHeight(screenWidthDp: Int): Dp =
    kaleidoMetrics(screenWidthDp.dp).hit + Kaleido.RowRule

/** A 28dp icon action. Smaller than a rail button: it is chrome, not a tool. */
@Composable
private fun TitleBarButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(28.dp)
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
