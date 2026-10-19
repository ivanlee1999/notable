package com.ethran.notable.editor.ui

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.width
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import com.ethran.notable.R
import com.ethran.notable.data.datastore.BUTTON_SIZE
import com.ethran.notable.editor.EditorControlTower
import com.ethran.notable.editor.ui.toolbar.ToolbarButton
import com.ethran.notable.io.shareBitmap
import com.ethran.notable.ui.noRippleClickable
import compose.icons.FeatherIcons
import compose.icons.feathericons.Clipboard
import compose.icons.feathericons.Copy
import compose.icons.feathericons.Scissors
import compose.icons.feathericons.Share2

val strokeStyle = Stroke(
    width = 2f,
    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
)

@Composable
fun SelectedBitmap(
    context: Context,
    controlTower: EditorControlTower
) {
    val selectionState = controlTower.getSnapshotOfSelectionState()
    if (selectionState.selectedBitmap == null) return

    var selectionDisplaceOffset =
        controlTower.page.applyZoom(selectionState.selectionDisplaceOffset ?: return)
    val selectionRect =
        controlTower.page.toScreenCoordinates(selectionState.selectionRect ?: return)
    val selectionStartOffset =
        controlTower.page.applyZoom(selectionState.selectionStartOffset ?: IntOffset(0, 0))


    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .noRippleClickable {
                controlTower.applySelectionDisplace()
                selectionState.reset()
                controlTower.setIsDrawing(true)
            }) {
        Image(
            bitmap = selectionState.selectedBitmap!!.asImageBitmap(),
            contentDescription = "Selection bitmap",
            modifier = Modifier
                .offset { selectionStartOffset + selectionDisplaceOffset }
                .drawBehind {
                    drawRect(
                        color = Color.Gray,
                        topLeft = Offset(0f, 0f),
                        size = size,
                        style = strokeStyle
                    )
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        selectionState.selectionDisplaceOffset =
                            controlTower.page.removeZoom(
                                selectionDisplaceOffset + dragAmount.round()
                            )
                        selectionDisplaceOffset =
                            controlTower.page.applyZoom(
                                selectionState.selectionDisplaceOffset ?: return@detectDragGestures
                            )
                    }
                }
                .combinedClickable(
                    indication = null, interactionSource = remember { MutableInteractionSource() },
                    onClick = {},
                    onDoubleClick = { controlTower.duplicateSelection() }
                )
        )

        val density = LocalDensity.current
        val buttonCount = if (selectionState.isResizable()) 7 else 5
        val toolbarPadding = 4.dp
        val toolbarWidthPx = minOf(constraints.maxWidth,
            with(density) { (BUTTON_SIZE.dp * buttonCount + toolbarPadding * 2).roundToPx() })
        val toolbarHeightPx = with(density) { (BUTTON_SIZE.dp + toolbarPadding * 2).roundToPx() }
        val offset = selectionToolbarOffset(
            selectionOrigin = selectionStartOffset + selectionDisplaceOffset,
            selectionSize = IntSize(selectionRect.width(), selectionRect.height()),
            viewport = IntSize(constraints.maxWidth, constraints.maxHeight),
            toolbarSize = IntSize(toolbarWidthPx, toolbarHeightPx),
            gapPx = with(density) { 8.dp.roundToPx() },
        )
        Row(
            modifier = Modifier
                .offset { offset }
                .width(with(density) { toolbarWidthPx.toDp() })
                .background(Color.White)
                .horizontalScroll(rememberScrollState())
                .padding(toolbarPadding)
                .height(BUTTON_SIZE.dp)
        ) {
            ToolbarButton(
                vectorIcon = FeatherIcons.Share2,
                contentDescription = "Share selection",
                isSelected = false,
                onSelect = {
                    shareBitmap(context, controlTower.getSelectedBitmap())
                },
                modifier = Modifier.height(BUTTON_SIZE.dp)
            )
            ToolbarButton(
                iconId = R.drawable.delete,
                contentDescription = "Delete selection",
                isSelected = false,
                onSelect = {
                    controlTower.deleteSelection()
                },
                modifier = Modifier.height(BUTTON_SIZE.dp)
            )
            if (selectionState.isResizable()) {
                ToolbarButton(
                    iconId = R.drawable.plus,
                    contentDescription = "Enlarge selection",
                    isSelected = false,
                    onSelect = { controlTower.changeSizeOfSelection(10) },
                    modifier = Modifier.height(BUTTON_SIZE.dp)
                )
                ToolbarButton(
                    iconId = R.drawable.minus,
                    contentDescription = "Shrink selection",
                    isSelected = false,
                    onSelect = { controlTower.changeSizeOfSelection(-10) },
                    modifier = Modifier.height(BUTTON_SIZE.dp)
                )
            }
            ToolbarButton(
                vectorIcon = FeatherIcons.Scissors,
                contentDescription = "Cut selection",
                isSelected = false,
                onSelect = { controlTower.cutSelectionToClipboard(context) },
                modifier = Modifier.height(BUTTON_SIZE.dp)
            )
            ToolbarButton(
                vectorIcon = FeatherIcons.Clipboard,
                contentDescription = "Copy selection",
                isSelected = false,
                onSelect = { controlTower.copySelectionToClipboard(context) },
                modifier = Modifier.height(BUTTON_SIZE.dp)
            )
            ToolbarButton(
                vectorIcon = FeatherIcons.Copy,
                contentDescription = "Duplicate selection",
                isSelected = false,
                onSelect = { controlTower.duplicateSelection() },
                modifier = Modifier.height(BUTTON_SIZE.dp)
            )
        }
    }
}

/** All arguments are physical pixels; only the floating controls move, never the selection. */
internal fun selectionToolbarOffset(
    selectionOrigin: IntOffset,
    selectionSize: IntSize,
    viewport: IntSize,
    toolbarSize: IntSize,
    gapPx: Int,
): IntOffset {
    val centeredX = selectionOrigin.x + (selectionSize.width - toolbarSize.width) / 2
    val aboveY = selectionOrigin.y - toolbarSize.height - gapPx
    val preferredY = if (aboveY >= 0) aboveY else selectionOrigin.y + selectionSize.height + gapPx
    return IntOffset(
        centeredX.coerceIn(0, (viewport.width - toolbarSize.width).coerceAtLeast(0)),
        preferredY.coerceIn(0, (viewport.height - toolbarSize.height).coerceAtLeast(0)),
    )
}
