package com.ethran.notable.editor.ui.toolbar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.ethran.notable.data.datastore.AppSettings
import com.ethran.notable.data.datastore.BUTTON_SIZE
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.ui.convertDpToPixel
import com.ethran.notable.ui.theme.Kaleido

/**
 * Where a toolbar submenu opens, and how the rail's own rules are drawn — both follow the
 * edge the rail is docked to.
 *
 * A [androidx.compose.ui.window.Popup] is placed against its button's bounds, so
 * [alignment] picks which edge of the button the menu grows from and [offset] clears the
 * rail. [padding] is applied *inside* the menu on the far side: the popup is a real window,
 * which the window manager keeps on screen, so padding it out pushes a menu at the bottom
 * or right edge back over the canvas instead of off the display.
 */
data class ToolbarPopupPlacement(
    val alignment: Alignment,
    val offset: IntOffset,
    val padding: PaddingValues,
)

@Composable
fun toolbarPopupPlacement(): ToolbarPopupPlacement {
    val context = LocalContext.current
    // One button plus a hairline: enough to clear the rail without floating free of it.
    val gap = convertDpToPixel(43.dp, context).toInt()
    val clear = (BUTTON_SIZE + 5).dp

    return when (GlobalAppSettings.current.toolbarPosition) {
        AppSettings.Position.Top, AppSettings.Position.Bottom -> ToolbarPopupPlacement(
            alignment = Alignment.TopCenter,
            offset = IntOffset(0, gap),
            padding = PaddingValues(bottom = clear),
        )

        AppSettings.Position.Left -> ToolbarPopupPlacement(
            alignment = Alignment.CenterStart,
            offset = IntOffset(gap, 0),
            padding = PaddingValues(),
        )

        AppSettings.Position.Right -> ToolbarPopupPlacement(
            alignment = Alignment.CenterEnd,
            offset = IntOffset(-gap, 0),
            padding = PaddingValues(),
        )
    }
}

/**
 * Placement for the overflow menu, which is wide enough that centring it on its button
 * would push it off the display. It hangs from the corner the button sits in instead.
 */
@Composable
fun toolbarMenuPlacement(): ToolbarPopupPlacement {
    val context = LocalContext.current
    val drop = convertDpToPixel(50.dp, context).toInt()
    val inset = convertDpToPixel(10.dp, context).toInt()
    val gap = convertDpToPixel(43.dp, context).toInt()
    val clear = (BUTTON_SIZE + 5).dp

    return when (GlobalAppSettings.current.toolbarPosition) {
        AppSettings.Position.Top, AppSettings.Position.Bottom -> ToolbarPopupPlacement(
            alignment = Alignment.TopEnd,
            offset = IntOffset(-inset, drop),
            padding = PaddingValues(bottom = clear),
        )

        AppSettings.Position.Left -> ToolbarPopupPlacement(
            alignment = Alignment.TopStart,
            offset = IntOffset(gap, -inset),
            padding = PaddingValues(),
        )

        AppSettings.Position.Right -> ToolbarPopupPlacement(
            alignment = Alignment.TopEnd,
            offset = IntOffset(-gap, -inset),
            padding = PaddingValues(),
        )
    }
}

/** True while the rail runs down an edge rather than across one. */
@Composable
fun isToolbarVertical(): Boolean = GlobalAppSettings.current.toolbarPosition.isVertical

/**
 * A placeable divider inside the rail — across it, whichever way the rail runs, so a user's
 * grouping survives moving the rail to another edge.
 */
@Composable
internal fun ToolbarDivider() {
    if (isToolbarVertical()) Box(
        Modifier
            .fillMaxWidth()
            .height(0.5.dp)
            .background(Kaleido.Ink)
    ) else Box(
        Modifier
            .fillMaxHeight()
            .width(0.5.dp)
            .background(Kaleido.Ink)
    )
}

/** The 2px rule that separates the rail from the writing surface. */
@Composable
internal fun ToolbarEdgeRule(vertical: Boolean) {
    if (vertical) Box(
        Modifier
            .fillMaxHeight()
            .width(Kaleido.SectionRule)
            .background(Kaleido.Ink)
    ) else Box(
        Modifier
            .fillMaxWidth()
            .height(Kaleido.SectionRule)
            .background(Kaleido.Ink)
    )
}
