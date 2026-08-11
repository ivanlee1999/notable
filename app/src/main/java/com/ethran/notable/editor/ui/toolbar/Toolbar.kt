package com.ethran.notable.editor.ui.toolbar

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ethran.notable.data.datastore.AppSettings
import com.ethran.notable.data.datastore.BUTTON_SIZE
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.datastore.TOOLBAR_THICKNESS
import com.ethran.notable.editor.ToolbarAction
import com.ethran.notable.editor.ToolbarUiState
import com.ethran.notable.editor.state.Mode
import com.ethran.notable.editor.ui.toolbar.model.IconRef
import com.ethran.notable.editor.ui.toolbar.model.ToolbarElementId
import com.ethran.notable.editor.ui.toolbar.model.ToolbarElements
import com.ethran.notable.editor.ui.toolbar.model.ToolbarLayout
import com.ethran.notable.editor.ui.toolbar.model.ToolbarPen
import com.ethran.notable.editor.utils.Pen
import com.ethran.notable.editor.utils.PenSetting
import com.ethran.notable.ui.dialogs.BackgroundSelector
import com.ethran.notable.ui.noRippleClickable
import com.ethran.notable.ui.theme.Kaleido

/**
 * Spec-driven toolbar: iterates a [ToolbarLayout] and renders each element through
 * [ToolbarElementView]. The layout is data; adding a tool means adding a registry
 * entry, not editing this file.
 *
 * The rail is docked to one of the four edges — never floating, never draggable, because a
 * moving overlay costs a full-screen e-ink refresh. Docked left or right it becomes the
 * tablet arrangement: a vertical rail within thumb reach of the bezel, ending in the ink
 * strip for the pen currently in hand.
 */
@Composable
fun ToolbarContent(
    uiState: ToolbarUiState,
    onAction: (ToolbarAction) -> Unit,
    onDrawingStateCheck: () -> Unit,
) {
    // Activity result launcher for picking images
    val pickMedia = rememberLauncherForActivityResult(contract = PickVisualMedia()) { uri ->
        uri?.let { onAction(ToolbarAction.ImagePicked(it)) }
    }

    // On exit or change of toolbar states, check if we should allow raw drawing
    LaunchedEffect(uiState.isBackgroundSelectorModalOpen, uiState.isMenuOpen) {
        onDrawingStateCheck()
    }

    if (uiState.isBackgroundSelectorModalOpen) {
        BackgroundSelector(
            initialPageBackgroundType = uiState.backgroundType,
            initialPageBackground = uiState.backgroundPath,
            initialPageNumberInPdf = uiState.backgroundPageNumber,
            notebookId = uiState.notebookId,
            pageNumberInBook = uiState.currentPageNumber,
            onChange = { type, path -> onAction(ToolbarAction.BackgroundChanged(type, path)) },
            onClose = { onAction(ToolbarAction.ToggleBackgroundSelector(false)) }
        )
    }

    if (!uiState.isToolbarOpen) {
        CollapsedToolbarButton(uiState, onAction)
        return
    }

    // Snapshot read: setting changes (toolbarPens, toolbarPosition, …) recompose the toolbar.
    val settings = GlobalAppSettings.current
    // Persisted layouts must be sanitized: they may predate elements, duplicate ids,
    // or omit the mandatory MENU entry. DEFAULT is validated by construction (tested);
    // its entries for deleted presets are skipped by resolve() below.
    val layout = remember(settings.toolbarLayout, settings.toolbarPens) {
        settings.toolbarLayout?.validated(settings.toolbarPens) ?: ToolbarLayout.DEFAULT
    }

    @Composable
    fun renderZone(names: List<String>) {
        for (name in names) {
            val element = ToolbarElements.resolve(name, settings.toolbarPens) ?: continue
            if (!element.visibleWhen(uiState, settings)) continue
            ToolbarElementView(
                element = element,
                uiState = uiState,
                onAction = onAction,
                onPickImage = {
                    pickMedia.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))
                },
            )
        }
    }

    if (settings.toolbarPosition.isVertical) {
        VerticalRail(
            position = settings.toolbarPosition,
            uiState = uiState,
            onAction = onAction,
            renderZone = { renderZone(it) },
            layout = layout,
        )
    } else {
        HorizontalRail(
            position = settings.toolbarPosition,
            renderZone = { renderZone(it) },
            layout = layout,
            uiState = uiState,
            onAction = onAction,
        )
    }
}

@Composable
private fun HorizontalRail(
    position: AppSettings.Position,
    layout: ToolbarLayout,
    uiState: ToolbarUiState,
    onAction: (ToolbarAction) -> Unit,
    renderZone: @Composable (List<String>) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(TOOLBAR_THICKNESS.dp)
            .background(Kaleido.Rail)
    ) {
        if (position == AppSettings.Position.Bottom) ToolbarEdgeRule(vertical = false)
        Row(
            Modifier
                .height(BUTTON_SIZE.dp)
                .fillMaxWidth()
        ) {
            // Structural: the toggle is always first, never part of the layout.
            ToolbarElementView(
                element = ToolbarElements.of(ToolbarElementId.TOGGLE),
                uiState = uiState,
                onAction = onAction,
                onPickImage = {},
            )
            ToolbarDivider()

            // Left zone: scrolls horizontally.
            Row(
                Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState())
            ) {
                renderZone(layout.scrollable)
            }

            // Right zone: pinned.
            Row {
                renderZone(layout.pinned)
            }
        }
        if (position == AppSettings.Position.Top) ToolbarEdgeRule(vertical = false)
    }
}

@Composable
private fun VerticalRail(
    position: AppSettings.Position,
    layout: ToolbarLayout,
    uiState: ToolbarUiState,
    onAction: (ToolbarAction) -> Unit,
    renderZone: @Composable (List<String>) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxHeight()
            .width(TOOLBAR_THICKNESS.dp)
            .background(Kaleido.Rail)
    ) {
        if (position == AppSettings.Position.Right) ToolbarEdgeRule(vertical = true)
        Column(
            Modifier
                .width(BUTTON_SIZE.dp)
                .fillMaxHeight()
                // The rail's fill still runs to the edge; only its contents stop short, so the
                // ink strip at the foot is not hidden under a gesture bar.
                .navigationBarsPadding()
        ) {
            ToolbarElementView(
                element = ToolbarElements.of(ToolbarElementId.TOGGLE),
                uiState = uiState,
                onAction = onAction,
                onPickImage = {},
            )
            ToolbarDivider()

            // Scrolls; takes whatever height the pinned zone and the ink strip leave.
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                renderZone(layout.scrollable)
            }

            Column { renderZone(layout.pinned) }

            InkStrip(uiState = uiState, onAction = onAction)
        }
        if (position == AppSettings.Position.Left) ToolbarEdgeRule(vertical = true)
    }
}

/**
 * The inks the pen in hand can take, at the foot of the rail.
 *
 * Saturated squares are the one thing a Kaleido panel prints cleanly, and this is the only
 * place in the editor colour is spent. Tapping one writes to the active preset — the same
 * edit the pen's stroke menu makes, one tap deep instead of two. Only shown on a vertical
 * rail: across the bottom of a horizontal one there is no room that is not already a tool.
 */
@Composable
private fun InkStrip(
    uiState: ToolbarUiState,
    onAction: (ToolbarAction) -> Unit,
) {
    val presetId = uiState.penPresetId
    val preset = GlobalAppSettings.current.toolbarPens.find { it.id == presetId } ?: return
    val current = uiState.penSettings[presetId] ?: preset.setting()

    // The ink in hand always leads, so the strip shows a selection and the pen's real colour
    // is never the one that got cut. Four fits the rail without scrolling; a pen offering
    // more keeps the rest in its stroke menu.
    val inks = (listOf(current.color) + preset.effectiveColorOptions()).distinct().take(4)
    if (inks.size < 2) return

    ToolbarDivider()
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        inks.forEach { ink ->
            val selected = ink == current.color
            Box(
                Modifier
                    .size(25.dp)
                    .background(Color(ink))
                    .border(
                        width = if (selected) 2.dp else 1.dp,
                        color = if (selected) Kaleido.Ink else Kaleido.Edge
                    )
                    .noRippleClickable {
                        onAction(
                            ToolbarAction.ChangePenSetting(
                                presetId, PenSetting(current.strokeSize, ink)
                            )
                        )
                    }
            )
        }
    }
}

@Composable
private fun CollapsedToolbarButton(
    uiState: ToolbarUiState,
    onAction: (ToolbarAction) -> Unit,
) {
    val icon = ToolbarElements.presentlyUsedToolIcon(
        uiState, GlobalAppSettings.current.toolbarPens
    )
    ToolbarButton(
        onSelect = { onAction(ToolbarAction.ToggleToolbar) },
        iconId = (icon as? IconRef.Drawable)?.resId,
        vectorIcon = (icon as? IconRef.Vector)?.imageVector,
        penColor = if (uiState.mode != Mode.Erase)
            uiState.penSettings[uiState.penPresetId]?.color?.let { Color(it) }
        else null,
        contentDescription = "open toolbar",
        modifier = Modifier
            .height((BUTTON_SIZE + 1).dp)
            .padding(bottom = 1.dp)
    )
}

@Composable
@Preview(showBackground = true, widthDp = 1200)
fun ToolbarPreview() {
    val uiState = ToolbarUiState(
        isToolbarOpen = true,
        mode = Mode.Draw,
        pen = Pen.BALLPEN,
        penPresetId = ToolbarPen.DEFAULT_PENS.first().id,
        penSettings = ToolbarPen.defaultPenSettings,
        pageNumberInfo = "3/12",
        notebookId = "dummy_book"
    )

    ToolbarContent(
        uiState = uiState,
        onAction = {},
        onDrawingStateCheck = {}
    )
}
