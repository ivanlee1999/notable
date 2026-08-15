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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.ethran.notable.data.datastore.AppSettings
import com.ethran.notable.data.datastore.BUTTON_SIZE
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.datastore.TOOLBAR_THICKNESS
import com.ethran.notable.editor.ToolbarAction
import com.ethran.notable.editor.ToolbarUiState
import com.ethran.notable.editor.state.Mode
import com.ethran.notable.editor.ui.toolbar.model.IconRef
import com.ethran.notable.editor.ui.toolbar.model.ToolbarElement
import com.ethran.notable.editor.ui.toolbar.model.ToolbarElementId
import com.ethran.notable.editor.ui.toolbar.model.ToolbarElements
import com.ethran.notable.editor.ui.toolbar.model.ToolbarPen
import com.ethran.notable.editor.utils.Eraser
import com.ethran.notable.editor.utils.Pen
import com.ethran.notable.editor.utils.PenSetting
import com.ethran.notable.ui.dialogs.BackgroundSelector
import com.ethran.notable.ui.noRippleClickable
import com.ethran.notable.ui.theme.Kaleido

/**
 * The tool rail: a fixed arrangement of groups, rendered through [ToolbarElementView].
 *
 * The rail is docked to one of the four edges — never floating, never draggable, because a
 * moving overlay costs a full-screen e-ink refresh. Docked left or right it becomes the
 * tablet arrangement: a vertical rail within thumb reach of the bezel, ending in the ink
 * strip for the pen currently in hand. Docked top or bottom — where a one-handed device
 * starts, see [com.ethran.notable.data.datastore.defaultToolbarPosition] — the strip has no
 * room to lie down, so the same choice becomes a single [InkSwatch] and a popover.
 *
 * The order of the groups is the rail's whole argument, and it does not vary:
 *
 *     tools │ nibs │ history │ overflow… │ pinned │ inks
 *
 * The four implements, then how broad the nib is, then undo — the sequence of a single
 * stroke, left where the hand already is. A user-ordered rail could express that too, but it
 * could equally express anything else, and a rail whose buttons move is one you have to read
 * before every tap. What stays configurable is what each implement *writes like*
 * ([ToolbarPen] presets, edited in settings), which is the part that is genuinely personal.
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
    val groups = remember(settings.toolbarPens) { RailGroups.of(settings.toolbarPens) }

    @Composable
    fun renderGroup(elements: List<ToolbarElement>) {
        for (element in elements) {
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
            renderGroup = { renderGroup(it) },
            groups = groups,
        )
    } else {
        HorizontalRail(
            position = settings.toolbarPosition,
            renderGroup = { renderGroup(it) },
            groups = groups,
            uiState = uiState,
            onAction = onAction,
        )
    }
}

/**
 * The rail's fixed groups, resolved against the user's pen presets.
 *
 * Held apart from the composables so the arrangement is a value a test can assert on, rather
 * than something only a rendered rail knows.
 */
data class RailGroups(
    /** The four implements, then the two tools that take no ink. */
    val tools: List<ToolbarElement>,
    /** Everything that is neither an implement nor a rail fixture; scrolls when short. */
    val overflow: List<ToolbarElement>,
    val history: List<ToolbarElement>,
    /** Where the rail ends: where you are, and the way out. */
    val pinned: List<ToolbarElement>,
) {
    companion object {
        fun of(pens: List<ToolbarPen>): RailGroups = RailGroups(
            tools = ToolbarPen.railPresets(pens).map(ToolbarElements::penElement) + listOf(
                ToolbarElements.of(ToolbarElementId.ERASER),
                ToolbarElements.of(ToolbarElementId.SELECT),
            ),
            overflow = listOf(ToolbarElements.of(ToolbarElementId.SHAPE)) +
                ToolbarPen.extraPresets(pens).map(ToolbarElements::penElement) +
                listOf(
                    ToolbarElements.of(ToolbarElementId.IMAGE),
                    ToolbarElements.of(ToolbarElementId.PASTE),
                    ToolbarElements.of(ToolbarElementId.RESET_VIEW),
                ),
            history = listOf(
                ToolbarElements.of(ToolbarElementId.UNDO),
                ToolbarElements.of(ToolbarElementId.REDO),
            ),
            pinned = listOf(
                ToolbarElements.of(ToolbarElementId.PAGE_NAV),
                ToolbarElements.of(ToolbarElementId.QUICK_NAV),
                ToolbarElements.of(ToolbarElementId.HOME),
                ToolbarElements.of(ToolbarElementId.MENU),
            ),
        )
    }
}

@Composable
private fun HorizontalRail(
    position: AppSettings.Position,
    groups: RailGroups,
    uiState: ToolbarUiState,
    onAction: (ToolbarAction) -> Unit,
    renderGroup: @Composable (List<ToolbarElement>) -> Unit,
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
            // Structural: the toggle is always first.
            ToolbarElementView(
                element = ToolbarElements.of(ToolbarElementId.TOGGLE),
                uiState = uiState,
                onAction = onAction,
                onPickImage = {},
            )
            ToolbarDivider()

            renderGroup(groups.tools)
            NibGroup(uiState = uiState, onAction = onAction)
            ToolbarDivider()
            renderGroup(groups.history)

            // The overflow takes the slack, and scrolls when there is none — so the fixed
            // groups either side of it keep their places on a narrow screen.
            ToolbarDivider()
            Row(
                Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState())
            ) {
                renderGroup(groups.overflow)
            }

            ToolbarDivider()
            renderGroup(groups.pinned)

            InkSwatch(uiState = uiState, onAction = onAction)
        }
        if (position == AppSettings.Position.Top) ToolbarEdgeRule(vertical = false)
    }
}

/**
 * The inks the pen in hand can take, and which one it is holding.
 *
 * The ink in hand always leads, so the strip shows a selection and the pen's real colour is
 * never the one that got cut. Four fits the rail without scrolling; a pen offering more keeps
 * the rest in its stroke menu. Empty when there is nothing to choose between.
 */
@Composable
private fun inkOptions(uiState: ToolbarUiState): Pair<PenSetting, List<Int>>? {
    val presetId = uiState.penPresetId
    val preset = GlobalAppSettings.current.toolbarPens.find { it.id == presetId } ?: return null
    val current = uiState.penSettings[presetId] ?: preset.setting()
    val inks = (listOf(current.color) + preset.effectiveColorOptions()).distinct().take(4)
    return if (inks.size < 2) null else current to inks
}

/**
 * One square of colour: what the pen is holding, and the way to change it.
 *
 * The vertical rail can afford to lay the inks out down its foot ([InkStrip]); across the
 * bottom of a horizontal one there is no room that is not already a tool, so the same choice
 * is spent on a single swatch and the alternatives open over the canvas. Which keeps the
 * one-handed arrangement honest: the ink in hand is visible at a glance without a menu, and
 * changing it is still one tap deeper than on the tablet rather than buried in the pen's
 * stroke menu.
 */
@Composable
private fun InkSwatch(
    uiState: ToolbarUiState,
    onAction: (ToolbarAction) -> Unit,
) {
    val (current, inks) = inkOptions(uiState) ?: return
    var isOpen by remember { mutableStateOf(false) }
    val placement = toolbarPopupPlacement()

    ToolbarDivider()
    Box(
        Modifier
            .size(BUTTON_SIZE.dp)
            .noRippleClickable { isOpen = !isOpen },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(25.dp)
                .background(Color(current.color))
                .border(1.dp, Kaleido.Ink)
        )
    }

    if (isOpen) Popup(
        offset = placement.offset,
        onDismissRequest = { isOpen = false },
        properties = PopupProperties(focusable = true),
        alignment = placement.alignment,
    ) {
        Row(
            Modifier
                .padding(placement.padding)
                .background(Kaleido.Rail)
                .border(Kaleido.SectionRule, Kaleido.Ink)
                .padding(5.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            inks.forEach { ink ->
                val selected = ink == current.color
                Box(
                    Modifier
                        .size(36.dp)
                        .background(Color(ink))
                        .border(
                            width = if (selected) 2.dp else 1.dp,
                            color = if (selected) Kaleido.Ink else Kaleido.Edge
                        )
                        .noRippleClickable {
                            onAction(
                                ToolbarAction.ChangePenSetting(
                                    uiState.penPresetId, PenSetting(current.strokeSize, ink)
                                )
                            )
                            isOpen = false
                        }
                )
            }
        }
    }
}

@Composable
private fun VerticalRail(
    position: AppSettings.Position,
    groups: RailGroups,
    uiState: ToolbarUiState,
    onAction: (ToolbarAction) -> Unit,
    renderGroup: @Composable (List<ToolbarElement>) -> Unit,
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

            renderGroup(groups.tools)
            NibGroup(uiState = uiState, onAction = onAction)
            ToolbarDivider()
            renderGroup(groups.history)

            // The overflow takes the slack, and scrolls when there is none — so the four
            // implements at the head and the inks at the foot never move down a short screen.
            ToolbarDivider()
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                renderGroup(groups.overflow)
            }

            ToolbarDivider()
            Column { renderGroup(groups.pinned) }

            InkStrip(uiState = uiState, onAction = onAction)
        }
        if (position == AppSettings.Position.Left) ToolbarEdgeRule(vertical = true)
    }
}

/**
 * The nibs the pen in hand can take, and the one it is writing with.
 *
 * Read from the pen's own configured sizes, so a highlighter's dots mean 25/40/60 and a
 * ballpen's mean 3/5/10 — what is being chosen is a size, not a name, and each implement
 * keeps its character. The size in hand leads the list for the same reason the ink does: it
 * has to survive the cut to four, or the rail would show no selection at all. Null when
 * there is nothing to choose between.
 */
@Composable
private fun nibOptions(uiState: ToolbarUiState): Pair<PenSetting, List<Float>>? {
    val presetId = uiState.penPresetId
    val preset = GlobalAppSettings.current.toolbarPens.find { it.id == presetId } ?: return null
    val current = uiState.penSettings[presetId] ?: preset.setting()
    val sizes = preset.nibChoices(current.strokeSize)
    return if (sizes.isEmpty()) null else current to sizes
}

/**
 * How broad the nib is, as the dot it draws — one tap, in the rail, next to the tool it
 * applies to.
 *
 * Sizes were reachable only through the pen's stroke menu, which is two taps and a popup over
 * the canvas for the single most-changed property a pen has. While the eraser is in hand the
 * same cells become its two kinds instead, because that is the choice that actually applies
 * then: a nib width means nothing to an eraser, and rubbing part of a stroke out and taking
 * the whole stroke away are different intentions that were themselves buried in a popup.
 */
@Composable
private fun NibGroup(uiState: ToolbarUiState, onAction: (ToolbarAction) -> Unit) {
    if (uiState.mode == Mode.Erase) {
        ToolbarDivider()
        for (eraser in listOf(Eraser.PEN, Eraser.SELECT)) {
            ToolbarButton(
                iconId = eraserIcon(eraser),
                isSelected = uiState.eraser == eraser,
                onSelect = { onAction(ToolbarAction.ChangeEraser(eraser)) },
                contentDescription = eraserDescription(eraser),
            )
        }
        return
    }

    val (current, sizes) = nibOptions(uiState) ?: return
    ToolbarDivider()
    sizes.forEachIndexed { index, size ->
        NibCell(
            diameter = nibDot(index, sizes.size),
            selected = size == current.strokeSize,
            label = "nib ${ToolbarElements.sizeLabel(size)}",
        ) {
            onAction(
                ToolbarAction.ChangePenSetting(
                    uiState.penPresetId, PenSetting(size, current.color)
                )
            )
        }
    }
}

/**
 * How big the dot for [index] of [count] is drawn — by rank, not by the stroke width itself.
 * A highlighter's 80 would otherwise want a dot wider than the rail, and the group has to read
 * as ascending nibs whatever numbers the pen behind it happens to carry.
 */
private fun nibDot(index: Int, count: Int): Dp =
    if (count < 2) 11.dp else (6f + 14f * index / (count - 1)).dp

/** A rail cell holding one dot: filled ink when it is the nib in hand, as a tool button is. */
@Composable
private fun NibCell(
    diameter: Dp,
    selected: Boolean,
    label: String,
    onSelect: () -> Unit,
) {
    Box(
        Modifier
            .size(BUTTON_SIZE.dp)
            .background(if (selected) Kaleido.Ink else Color.Transparent)
            .noRippleClickable(onSelect)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(diameter)
                .background(if (selected) Kaleido.Paper else Kaleido.Ink, CircleShape)
        )
    }
}

/**
 * The inks the pen in hand can take, at the foot of the rail.
 *
 * Saturated squares are the one thing a Kaleido panel prints cleanly, and this is the only
 * place in the editor colour is spent. Tapping one writes to the active preset — the same
 * edit the pen's stroke menu makes, one tap deep instead of two. The vertical rail has the
 * room to show them all at once; a horizontal one spends the same choice on [InkSwatch].
 */
@Composable
private fun InkStrip(
    uiState: ToolbarUiState,
    onAction: (ToolbarAction) -> Unit,
) {
    val (current, inks) = inkOptions(uiState) ?: return
    val presetId = uiState.penPresetId

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
