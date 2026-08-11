package com.ethran.notable.editor.ui.toolbar

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ethran.notable.data.datastore.AppSettings
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.datastore.TOOLBAR_THICKNESS
import com.ethran.notable.editor.EditorViewModel

/**
 * Docks the toolbar to one of the four edges.
 *
 * This component is now decoupled from navigation and engine logic,
 * delegating actions to the [EditorViewModel].
 */
@Composable
fun PositionedToolbar(
    viewModel: EditorViewModel,
    onDrawingStateCheck: () -> Unit
) {
    val position = GlobalAppSettings.current.toolbarPosition
    val toolbarState by viewModel.toolbarState.collectAsStateWithLifecycle()

    val toolbar = @Composable {
        ToolbarContent(
            uiState = toolbarState,
            onAction = viewModel::onToolbarAction,
            onDrawingStateCheck = onDrawingStateCheck
        )
    }

    when (position) {
        AppSettings.Position.Top -> toolbar()

        AppSettings.Position.Bottom -> Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight()
        ) {
            Spacer(modifier = Modifier.weight(1f))
            toolbar()
        }

        AppSettings.Position.Left -> Row(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight()
        ) {
            toolbar()
            Spacer(modifier = Modifier.weight(1f))
        }

        AppSettings.Position.Right -> Row(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight()
        ) {
            Spacer(modifier = Modifier.weight(1f))
            toolbar()
        }
    }
}

/**
 * The band the docked rail occupies, for overlays drawn over the whole editor (the scroll
 * indicators) so they do not slide underneath it. Empty while the rail is collapsed.
 */
@Composable
fun toolbarInset(isToolbarOpen: Boolean): PaddingValues {
    if (!isToolbarOpen) return PaddingValues()
    val thickness = TOOLBAR_THICKNESS.dp
    return when (GlobalAppSettings.current.toolbarPosition) {
        AppSettings.Position.Top -> PaddingValues(top = thickness)
        AppSettings.Position.Bottom -> PaddingValues(bottom = thickness)
        AppSettings.Position.Left -> PaddingValues(start = thickness)
        AppSettings.Position.Right -> PaddingValues(end = thickness)
    }
}
