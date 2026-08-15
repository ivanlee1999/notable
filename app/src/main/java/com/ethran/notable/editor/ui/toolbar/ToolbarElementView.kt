package com.ethran.notable.editor.ui.toolbar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.ethran.notable.R
import com.ethran.notable.data.datastore.BUTTON_SIZE
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.editor.ToolbarAction
import com.ethran.notable.editor.ToolbarUiState
import com.ethran.notable.editor.state.Mode
import com.ethran.notable.editor.ui.toolbar.model.ActionElement
import com.ethran.notable.editor.ui.toolbar.model.CustomElement
import com.ethran.notable.editor.ui.toolbar.model.CustomKind
import com.ethran.notable.editor.ui.toolbar.model.EraserSubmenuSpec
import com.ethran.notable.editor.ui.toolbar.model.IconRef
import com.ethran.notable.editor.ui.toolbar.model.ModeElement
import com.ethran.notable.editor.ui.toolbar.model.PenElement
import com.ethran.notable.editor.ui.toolbar.model.ShapeElement
import com.ethran.notable.editor.ui.toolbar.model.ToolbarElement
import com.ethran.notable.editor.utils.Eraser
import com.ethran.notable.ui.noRippleClickable
import com.ethran.notable.editor.state.Shape
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowUpRight
import compose.icons.feathericons.Circle
import compose.icons.feathericons.Square

/**
 * The single generic renderer for toolbar elements: draws the button (via [ToolbarButton]),
 * handles selected state, and opens the element's declared submenu. Stateless except
 * transient popup-open state; all real mutation flows through [ToolbarAction].
 *
 * [onPickImage] exists because the image picker's activity-result launcher is Compose
 * infrastructure owned by ToolbarContent, not something a [ToolbarAction] can express.
 */
@Composable
fun ToolbarElementView(
    element: ToolbarElement,
    uiState: ToolbarUiState,
    onAction: (ToolbarAction) -> Unit,
    onPickImage: () -> Unit,
) {
    when (element) {
        is PenElement -> PenElementView(element, uiState, onAction)

        is ShapeElement -> ShapeElementView(element, uiState, onAction)

        is ModeElement -> ModeElementView(element, uiState, onAction)

        is ActionElement ->
            ToolbarButton(
                isSelected = element.isSelected(uiState),
                onSelect = { onAction(element.action) },
                iconId = (element.icon as? IconRef.Drawable)?.resId,
                vectorIcon = (element.icon as? IconRef.Vector)?.imageVector,
                contentDescription = element.contentDescription,
            )

        is CustomElement -> when (element.kind) {
            CustomKind.IMAGE_PICKER ->
                ToolbarButton(
                    iconId = (element.icon as? IconRef.Drawable)?.resId,
                    vectorIcon = (element.icon as? IconRef.Vector)?.imageVector,
                    contentDescription = element.contentDescription,
                    onSelect = onPickImage,
                )

            CustomKind.PAGE_NAV -> {
                // A vertical rail is one button wide, so "3/128" has to set smaller and
                // lose its side padding to fit.
                val vertical = isToolbarVertical()
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .height(35.dp)
                        .padding(horizontal = if (vertical) 0.dp else 10.dp)
                ) {
                    Text(
                        text = uiState.pageNumberInfo,
                        fontWeight = FontWeight.Light,
                        fontSize = if (vertical) 11.sp else TextUnit.Unspecified,
                        modifier = Modifier.noRippleClickable { onAction(ToolbarAction.NavigateToPages) },
                        textAlign = TextAlign.Center
                    )
                }
            }

            CustomKind.MENU ->
                Column {
                    ToolbarButton(
                        onSelect = { onAction(ToolbarAction.ToggleMenu) },
                        iconId = (element.icon as? IconRef.Drawable)?.resId,
                        contentDescription = element.contentDescription,
                    )
                    if (uiState.isMenuOpen) {
                        ToolbarMenu(
                            uiState = uiState,
                            onAction = onAction
                        )
                    }
                }
        }
    }
}

@Composable
private fun PenElementView(
    element: PenElement,
    uiState: ToolbarUiState,
    onAction: (ToolbarAction) -> Unit,
) {
    var isStrokeMenuOpen by remember { mutableStateOf(false) }
    val isSelected = element.isSelected(uiState)
    val penSetting =
        uiState.penSettings[element.presetId] ?: element.setting.copy()

    Box {
        ToolbarButton(
            isSelected = isSelected,
            onSelect = {
                if (isSelected) isStrokeMenuOpen = !isStrokeMenuOpen
                else onAction(ToolbarAction.ChangePen(element.presetId))
            },
            penColor = Color(penSetting.color),
            iconId = (element.icon as? IconRef.Drawable)?.resId,
            vectorIcon = (element.icon as? IconRef.Vector)?.imageVector,
            contentDescription = element.contentDescription,
        )

        if (isStrokeMenuOpen) {
            StrokeMenu(
                value = penSetting,
                onChange = { onAction(ToolbarAction.ChangePenSetting(element.presetId, it)) },
                onClose = { isStrokeMenuOpen = false },
                sizeOptions = element.submenu.sizeOptions,
                colorOptions = element.submenu.colorOptions,
            )
        }
    }
}

/**
 * The shape button, and the picker behind it.
 *
 * Tapping toggles the tool the way it always did — pick it, tap again to go back to drawing —
 * and tapping it while it is already selected opens the picker instead, exactly as the eraser
 * button does. That is the whole reason the button shows the *current* shape rather than a
 * generic one: with four shapes behind it, a fixed icon would leave no way to tell what the next
 * drag is going to draw.
 */
@Composable
private fun ShapeElementView(
    element: ShapeElement,
    uiState: ToolbarUiState,
    onAction: (ToolbarAction) -> Unit,
) {
    val isSelected = element.isSelected(uiState)
    var isPickerOpen by remember { mutableStateOf(false) }

    Box {
        ToolbarButton(
            isSelected = isSelected,
            onSelect = {
                if (isSelected) isPickerOpen = !isPickerOpen
                else onAction(ToolbarAction.ChangeMode(Mode.Line))
            },
            penColor = Color.LightGray,
            iconId = shapeIcon(uiState.shape).let { (it as? IconRef.Drawable)?.resId },
            vectorIcon = shapeIcon(uiState.shape).let { (it as? IconRef.Vector)?.imageVector },
            contentDescription = uiState.shape.label,
        )

        if (isPickerOpen) {
            ShapeSubmenu(
                shapes = element.shapes,
                selected = uiState.shape,
                onPick = {
                    isPickerOpen = false
                    onAction(ToolbarAction.ChangeShape(it))
                },
                onDismiss = { isPickerOpen = false },
            )
        }
    }
}

@Composable
private fun ShapeSubmenu(
    shapes: List<Shape>,
    selected: Shape,
    onPick: (Shape) -> Unit,
    onDismiss: () -> Unit,
) {
    val placement = toolbarPopupPlacement()

    Popup(
        offset = placement.offset,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
        alignment = placement.alignment
    ) {
        Row(
            modifier = Modifier
                .padding(placement.padding) // keeps the menu on screen against the docked edge
                .background(Color.White)
                .border(1.dp, Color.Black)
                .height(IntrinsicSize.Max)
        ) {
            shapes.forEach { shape ->
                val icon = shapeIcon(shape)
                ToolbarButton(
                    iconId = (icon as? IconRef.Drawable)?.resId,
                    vectorIcon = (icon as? IconRef.Vector)?.imageVector,
                    isSelected = shape == selected,
                    onSelect = { onPick(shape) },
                    contentDescription = shape.label,
                    modifier = Modifier.height(BUTTON_SIZE.dp)
                )
            }
        }
    }
}

private fun shapeIcon(shape: Shape): IconRef = when (shape) {
    Shape.LINE -> IconRef.Drawable(R.drawable.line)
    Shape.RECT -> IconRef.Vector(FeatherIcons.Square)
    Shape.ELLIPSE -> IconRef.Vector(FeatherIcons.Circle)
    Shape.ARROW -> IconRef.Vector(FeatherIcons.ArrowUpRight)
}

@Composable
private fun ModeElementView(
    element: ModeElement,
    uiState: ToolbarUiState,
    onAction: (ToolbarAction) -> Unit,
) {
    val isSelected = element.isSelected(uiState)
    val submenu = element.submenu

    // The eraser button reflects the active eraser type, not its static spec icon.
    val iconId =
        if (submenu is EraserSubmenuSpec) eraserIcon(uiState.eraser)
        else (element.icon as? IconRef.Drawable)?.resId

    Box {
        ToolbarButton(
            isSelected = isSelected,
            onSelect = {
                if (isSelected && submenu is EraserSubmenuSpec)
                    onAction(ToolbarAction.ToggleEraserManu(!uiState.isStrokeSelectionOpen))
                else if (!isSelected)
                    onAction(ToolbarAction.ChangeMode(element.mode))
            },
            iconId = iconId,
            vectorIcon = (element.icon as? IconRef.Vector)?.imageVector,
            contentDescription = element.contentDescription,
        )

        if (submenu is EraserSubmenuSpec && uiState.isStrokeSelectionOpen) {
            EraserSubmenu(onAction = onAction)
        }
    }
}

internal fun eraserIcon(eraser: Eraser): Int =
    when (eraser) {
        Eraser.PEN -> R.drawable.eraser
        Eraser.SELECT -> R.drawable.eraser_select
    }

/** What each eraser does, for the rail cell that selects it. */
internal fun eraserDescription(eraser: Eraser): String =
    when (eraser) {
        Eraser.PEN -> "rub out"
        Eraser.SELECT -> "erase whole strokes"
    }

/**
 * The eraser popup: the global scribble-to-erase toggle.
 *
 * Which eraser is in hand used to be picked here too; it is now two cells in the rail, where
 * the nib widths sit for every other tool. What is left is a setting rather than a choice
 * about the stroke you are about to make, so it stays behind the second tap.
 */
@Composable
private fun EraserSubmenu(onAction: (ToolbarAction) -> Unit) {
    val placement = toolbarPopupPlacement()

    Popup(
        offset = placement.offset,
        onDismissRequest = { onAction(ToolbarAction.ToggleEraserManu(false)) },
        properties = PopupProperties(focusable = true),
        alignment = placement.alignment
    ) {
        Column(
            modifier = Modifier
                .padding(placement.padding) // keeps the menu on screen against the docked edge
                .background(Color.White)
                .border(1.dp, Color.Black)
                .height(IntrinsicSize.Max)
        ) {
            Row(
                modifier = Modifier
                    .padding(4.dp)
                    .height(26.dp)
                    .width(IntrinsicSize.Min)
                    .background(Color.White),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicText(
                    text = stringResource(R.string.toolbar_scribble_to_erase_two_lined_short),
                    modifier = Modifier.padding(end = 6.dp),
                    style = TextStyle(color = Color.Black, fontSize = 13.sp)
                )
                // Reflects the global flag; ToggleScribbleToErase persists it.
                val initialState = GlobalAppSettings.current.scribbleToEraseEnabled
                var isChecked by remember { mutableStateOf(initialState) }

                Box(
                    modifier = Modifier
                        .size(15.dp, 15.dp)
                        .border(1.dp, Color.Black)
                        .background(if (isChecked) Color.Black else Color.White)
                        .clickable {
                            isChecked = !isChecked
                            onAction(ToolbarAction.ToggleScribbleToErase(isChecked))
                        }
                )
            }
        }
    }
}

