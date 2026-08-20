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
import androidx.compose.foundation.layout.Spacer
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
import com.ethran.notable.editor.ui.toolbar.model.NibWidth
import com.ethran.notable.editor.ui.toolbar.model.ToolbarElement
import com.ethran.notable.editor.ui.toolbar.model.ToolbarElementId
import com.ethran.notable.editor.ui.toolbar.model.ToolbarElements
import com.ethran.notable.editor.ui.toolbar.model.ToolbarPen
import com.ethran.notable.editor.ui.toolbar.model.baseWidth
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
            onChange = { type, path, placement ->
                onAction(ToolbarAction.BackgroundChanged(type, path, placement))
            },
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

            // Everything the hand reaches for, in rail order, inside one scroller. The groups
            // are fixed relative to each other, not to the screen: a one-handed panel is not
            // wide enough for all of them, and whatever will not fit has to be reachable by
            // scrolling rather than clipped off the edge.
            Row(
                Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState())
            ) {
                renderGroup(groups.tools)
                NibGroup(uiState = uiState, onAction = onAction)
                ToolbarDivider()
                renderGroup(groups.history)
                ToolbarDivider()
                renderGroup(groups.overflow)
            }

            // Outside the scroller, so the way out of the editor is always at the trailing edge.
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
 * The whole palette, in its own order — twelve by default ([ToolbarPen.DEFAULT_COLOR_OPTIONS]),
 * fewer if the user has narrowed this pen in settings. It used to be cut to four, which meant
 * eight of the twelve were reachable only through the stroke menu and the row's order shuffled
 * every time the pen changed colour; a fixed palette is one you learn the shape of.
 *
 * The ink in hand only leads when it is not in the palette at all — a colour left over from a
 * narrowed pen or an older build — so that the strip never shows twelve swatches and no
 * selection. Null when there is nothing to choose between.
 */
@Composable
private fun inkOptions(uiState: ToolbarUiState): Pair<PenSetting, List<Int>>? {
    val presetId = uiState.penPresetId
    val preset = GlobalAppSettings.current.toolbarPens.find { it.id == presetId } ?: return null
    val current = uiState.penSettings[presetId] ?: preset.setting()
    val palette = preset.effectiveColorOptions()
    val inks = if (current.color in palette) palette else listOf(current.color) + palette
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
        // Two columns, like the vertical rail's strip — a single row of twelve would be wider
        // than the panel this arrangement exists for. Off the rail there is room for a proper
        // 36dp target, so the popover's swatches are the ones a finger wants.
        Column(
            Modifier
                .padding(placement.padding)
                .background(Kaleido.Rail)
                .border(Kaleido.SectionRule, Kaleido.Ink)
                .padding(5.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            inks.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    row.forEach { ink ->
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
                                            uiState.penPresetId,
                                            PenSetting(current.strokeSize, ink)
                                        )
                                    )
                                    isOpen = false
                                }
                                .semantics { contentDescription = inkDescription(ink) }
                        )
                    }
                    if (row.size == 1) Spacer(Modifier.size(36.dp))
                }
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

            // As on the horizontal rail: one scroller holding the groups in rail order, so a
            // short screen scrolls rather than clipping whatever falls past its bottom edge.
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                renderGroup(groups.tools)
                NibGroup(uiState = uiState, onAction = onAction)
                ToolbarDivider()
                renderGroup(groups.history)
                ToolbarDivider()
                renderGroup(groups.overflow)
            }

            // Outside the scroller: the way out, and the inks at the foot.
            ToolbarDivider()
            Column { renderGroup(groups.pinned) }

            InkStrip(uiState = uiState, onAction = onAction)
        }
        if (position == AppSettings.Position.Left) ToolbarEdgeRule(vertical = true)
    }
}

/**
 * How broad the nib is, as the dot it draws — one tap, in the rail, next to the tool it
 * applies to.
 *
 * Five fixed steps ([NibWidth]) scaled off whatever implement is in hand, so a step means the
 * same thing whichever tool it is under and every pen has the same five. The dots used to come
 * from each preset's own configured `sizeOptions`, which made the same dot a hairline on one
 * pen and a slab on the next, and drew no dots at all for a pen configured with a single size.
 *
 * While the eraser is in hand the row applies to *it* — the eraser is an implement with a
 * width like any other, and a rubber the size of a nib takes all day to clear a paragraph. Its
 * two kinds join the row below a rule rather than replacing it, which is how the eraser ended
 * up the one implement with a single fixed size.
 */
@Composable
private fun NibGroup(uiState: ToolbarUiState, onAction: (ToolbarAction) -> Unit) {
    if (uiState.mode == Mode.Erase) {
        ToolbarDivider()
        NibRamp(NibWidth.fromKeyOrDefault(GlobalAppSettings.current.eraserWidth)) { width ->
            onAction(ToolbarAction.ChangeEraserWidth(width))
        }
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

    val presetId = uiState.penPresetId
    val preset = GlobalAppSettings.current.toolbarPens.find { it.id == presetId } ?: return
    val current = uiState.penSettings[presetId] ?: preset.setting()
    val base = preset.pen.baseWidth

    ToolbarDivider()
    NibRamp(NibWidth.nearest(base, current.strokeSize)) { width ->
        onAction(
            ToolbarAction.ChangePenSetting(
                presetId, PenSetting(width.sizeFor(base), current.color)
            )
        )
    }
}

/** The five steps as cells, ascending. */
@Composable
private fun NibRamp(selected: NibWidth, onSelect: (NibWidth) -> Unit) {
    for (width in NibWidth.entries) {
        NibCell(
            diameter = width.dot,
            selected = width == selected,
            label = "nib ${width.label}",
        ) { onSelect(width) }
    }
}

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
 * edit the pen's stroke menu makes, one tap deep instead of two.
 *
 * Two columns, not one. A single file of twelve swatches would be longer than the rest of the
 * rail put together, and the point of showing the palette at all is that a colour is one tap
 * away rather than one tap and a menu. Two columns is also what fits: the rail is
 * [BUTTON_SIZE] across, so the swatches are [SWATCH] rather than the 25dp a single file could
 * afford — narrower than the iPad's, which has 60pt of rail to spend.
 *
 * A ring rather than a fill marks the current one: the swatch itself has to stay pure colour,
 * or the ink you chose is the one you cannot see. A horizontal rail has no room for a grid and
 * spends the same choice on [InkSwatch].
 */
@Composable
private fun InkStrip(
    uiState: ToolbarUiState,
    onAction: (ToolbarAction) -> Unit,
) {
    val (current, inks) = inkOptions(uiState) ?: return
    val presetId = uiState.penPresetId

    ToolbarDivider()
    // A plain Column of Rows rather than a LazyVerticalGrid: the rail is already inside a
    // scrolling parent on the horizontal arrangement, and a lazy grid nested in a scroller
    // measures at infinite height.
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SWATCH_GAP)
    ) {
        inks.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(SWATCH_GAP)) {
                row.forEach { ink ->
                    InkSquare(ink = ink, selected = ink == current.color) {
                        onAction(
                            ToolbarAction.ChangePenSetting(
                                presetId, PenSetting(current.strokeSize, ink)
                            )
                        )
                    }
                }
                // An odd palette leaves a hole rather than centring its last swatch: the
                // columns have to stay columns.
                if (row.size == 1) Spacer(Modifier.size(SWATCH_CELL))
            }
        }
    }
}

/**
 * The swatch cell in the vertical rail: two of them and the gap between are exactly
 * [BUTTON_SIZE] across (18 + 1 + 18 = 37), which is what fixes the grid at two columns.
 * [SWATCH] is the colour inside it; the 2dp left over is where a selection ring goes.
 */
private val SWATCH_CELL = 18.dp
private val SWATCH = 14.dp
private val SWATCH_GAP = 1.dp

/**
 * One ink, as a cell holding a square of colour.
 *
 * The selection ring is drawn on the *cell*, around the colour, rather than as a border on the
 * swatch itself: at this size a 2dp border would eat a quarter of the square, so the ink you
 * chose would be the one you could see least. The swatch keeps its own hairline edge in both
 * states, because a pale ink against the rail needs one to read as a square at all.
 */
@Composable
private fun InkSquare(
    ink: Int,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Box(
        Modifier
            .size(SWATCH_CELL)
            .then(if (selected) Modifier.border(2.dp, Kaleido.Ink) else Modifier)
            .noRippleClickable(onSelect)
            .semantics { contentDescription = inkDescription(ink) },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(SWATCH)
                .background(Color(ink))
                .border(1.dp, Kaleido.Edge)
        )
    }
}

/**
 * What an ink swatch is called to a screen reader and to a test — the colour as #RRGGBB.
 *
 * By value rather than by a name, because a pen narrowed in settings may carry an ink that is
 * no longer in the palette, and every swatch still has to be addressable.
 */
internal fun inkDescription(ink: Int): String = "ink #%06X".format(ink and 0xFFFFFF)

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
