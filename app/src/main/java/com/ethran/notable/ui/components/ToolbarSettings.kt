package com.ethran.notable.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.ethran.notable.R
import com.ethran.notable.data.datastore.AppSettings
import com.ethran.notable.editor.ui.toolbar.ToolbarButton
import com.ethran.notable.editor.ui.toolbar.model.IconRef
import com.ethran.notable.editor.ui.toolbar.model.ToolbarElements
import com.ethran.notable.editor.ui.toolbar.model.ToolbarPen
import com.ethran.notable.editor.utils.Pen
import com.ethran.notable.ui.theme.InkaTheme
import android.graphics.Color as AndroidColor

/**
 * The "Toolbar" settings panel: the pens the rail writes with.
 *
 * The rail's *arrangement* is not a setting — it is a fixed sequence of groups, so a tool is
 * always in the same place under the hand (see `RailGroups`). What is genuinely personal is
 * what each implement writes like, and that is what this screen edits: the colour and nib a
 * pen starts at, and which colours and sizes its rail strips and stroke menu offer.
 *
 * Presets are shown in the two roles the rail gives them — the four it writes with, and the
 * rest, which it keeps in its overflow.
 */
@Composable
fun ToolbarSettings(
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
    @Suppress("UNUSED_PARAMETER") pageScrollState: ScrollState = rememberScrollState(),
) {
    val pens = settings.toolbarPens
    val onRail = ToolbarPen.railPresets(pens)
    val overflow = ToolbarPen.extraPresets(pens)

    fun commit(newPens: List<ToolbarPen>) =
        onSettingsChange(settings.copy(toolbarPens = newPens))

    var addPenOpen by remember { mutableStateOf(false) }
    var editedPresetId by remember { mutableStateOf<String?>(null) }

    Column {
        Text(
            text = stringResource(R.string.toolbar_settings_rail_hint),
            style = MaterialTheme.typography.caption,
            color = Color.Black,
            modifier = Modifier.padding(vertical = 4.dp)
        )

        SectionHeaderRow(stringResource(R.string.toolbar_settings_section_on_rail))
        onRail.forEach { preset ->
            PresetRow(
                preset = preset,
                onEdit = { editedPresetId = preset.id },
                // The four implements are the rail; deleting one would leave a button with
                // nothing behind it. A preset that is not the user's own (no stored preset of
                // that type) is a seed shown for editing, and deleting it means even less.
                onDelete = null,
            )
        }

        if (overflow.isNotEmpty()) {
            SectionHeaderRow(stringResource(R.string.toolbar_settings_section_overflow))
            overflow.forEach { preset ->
                PresetRow(
                    preset = preset,
                    onEdit = { editedPresetId = preset.id },
                    onDelete = { commit(pens.filterNot { it.id == preset.id }) },
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            EInkButton(onClick = { addPenOpen = true }, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.toolbar_settings_add_pen))
            }
            EInkButton(
                onClick = { commit(ToolbarPen.DEFAULT_PENS) },
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.toolbar_settings_reset))
            }
        }
        Spacer(Modifier.height(16.dp))
    }

    if (addPenOpen) {
        AddPenDialog(
            onPick = { pen ->
                val preset = ToolbarPen(
                    id = ToolbarPen.newId(),
                    pen = pen,
                    color = if (pen == Pen.MARKER) AndroidColor.LTGRAY else AndroidColor.BLACK,
                    size = if (pen == Pen.MARKER) 40f else 5f,
                )
                commit(pens + preset)
                addPenOpen = false
                editedPresetId = preset.id
            },
            onClose = { addPenOpen = false },
        )
    }

    settings.toolbarPens.find { it.id == editedPresetId }?.let { preset ->
        PenEditDialog(
            preset = preset,
            onChange = { updated ->
                commit(pens.map { if (it.id == updated.id) updated else it })
            },
            onClose = { editedPresetId = null },
        )
    }
}

// ---------------------------------------------------------------------------
// Rows
// ---------------------------------------------------------------------------

private val ROW_HEIGHT = 48.dp

@Composable
private fun SectionHeaderRow(title: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .background(MaterialTheme.colors.onSurface)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text = title, style = MaterialTheme.typography.subtitle2, color = Color.White)
    }
}

@Composable
private fun PresetRow(
    preset: ToolbarPen,
    onEdit: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .background(MaterialTheme.colors.background)
            .clickable { onEdit() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(8.dp))
        ToolIcon(
            icon = ToolbarElements.penIcon(preset.pen),
            penColor = Color(preset.color),
            onClick = onEdit,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = stringResource(penNameRes(preset.pen)),
            style = MaterialTheme.typography.body1,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = ToolbarElements.sizeLabel(preset.size),
            style = MaterialTheme.typography.caption,
            color = Color.Black,
        )
        Spacer(Modifier.width(8.dp))

        if (onDelete != null) {
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.toolbar_settings_delete),
                    tint = MaterialTheme.colors.onSurface,
                )
            }
        }
    }
}

/**
 * A tool's icon at list/dialog scale: [ToolbarButton]'s look (circular pen-color
 * background, white tint on dark colors) but smaller — the real 38 dp toolbar buttons
 * read oversized next to settings text.
 */
@Composable
private fun ToolIcon(icon: IconRef?, penColor: Color? = null, onClick: (() -> Unit)? = null) {
    val tint =
        if (penColor == Color.Black || penColor == Color.DarkGray) Color.White else Color.Black
    Box(
        modifier = Modifier
            .size(28.dp)
            .background(penColor ?: Color.Transparent, CircleShape)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        when (icon) {
            is IconRef.Drawable -> Icon(
                painterResource(icon.resId), null, tint = tint, modifier = Modifier.size(18.dp)
            )

            is IconRef.Vector -> Icon(
                icon.imageVector, null, tint = tint, modifier = Modifier.size(18.dp)
            )

            null -> Unit
        }
    }
}

// ---------------------------------------------------------------------------
// Dialogs
// ---------------------------------------------------------------------------

@Composable
private fun AddPenDialog(onPick: (Pen) -> Unit, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose) {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colors.background)
                .border(1.dp, MaterialTheme.colors.onSurface)
                .padding(16.dp)
        ) {
            Text(
                text = stringResource(R.string.toolbar_settings_pick_pen_type),
                style = MaterialTheme.typography.h6,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            ToolbarPen.BASE_TYPES.forEach { pen ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(pen) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ToolIcon(icon = ToolbarElements.penIcon(pen))
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(penNameRes(pen)))
                }
            }
        }
    }
}

/**
 * Per-pen editor: which colors and which sizes this pen offers — in the rail's ink strip and
 * nib dots, and in its stroke menu. Multi-select toggles over the candidate sets; the pen's
 * *current* color/size is still picked from the rail itself. At least one color and one size
 * must stay included (a single-size pen simply shows no nib dots).
 *
 * Toggles edit a local copy; the settings blob is written once, on dismiss — not per
 * tap. If an edit removes the option the pen currently draws with, the active
 * color/size is clamped to a surviving option so it never falls off its own palette.
 */
@Composable
private fun PenEditDialog(
    preset: ToolbarPen,
    onChange: (ToolbarPen) -> Unit,
    onClose: () -> Unit,
) {
    var edited by remember(preset.id) { mutableStateOf(preset) }
    val includedColors = edited.effectiveColorOptions()
    val includedSizes = edited.effectiveSizeOptions()

    fun commitAndClose() {
        if (edited != preset) onChange(
            edited.copy(
                color = if (edited.color in edited.effectiveColorOptions()) edited.color
                else edited.effectiveColorOptions().first(),
                size = if (edited.size in edited.effectiveSizeOptions()) edited.size
                else edited.effectiveSizeOptions().minBy { kotlin.math.abs(it - edited.size) },
            )
        )
        onClose()
    }

    Dialog(onDismissRequest = ::commitAndClose) {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colors.background)
                .border(1.dp, MaterialTheme.colors.onSurface)
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ToolIcon(
                    icon = ToolbarElements.penIcon(edited.pen),
                    penColor = Color(edited.color),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = stringResource(penNameRes(edited.pen)),
                    style = MaterialTheme.typography.h6,
                )
            }

            Text(
                text = stringResource(R.string.toolbar_settings_color),
                style = MaterialTheme.typography.subtitle2,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
            )
            ToolbarPen.COLOR_CANDIDATES.chunked(7).forEach { rowColors ->
                Row {
                    rowColors.forEach { colorInt ->
                        val included = colorInt in includedColors
                        Box(
                            modifier = Modifier
                                .padding(2.dp)
                                .size(36.dp)
                                .background(Color(colorInt))
                                .border(
                                    if (included) 3.dp else 1.dp,
                                    Color.Black,
                                )
                                .clickable {
                                    // Re-added colors append at the end: the user's
                                    // stored palette order is preserved, not re-sorted
                                    // to candidate order.
                                    val updated =
                                        if (included) includedColors - colorInt
                                        else includedColors + colorInt
                                    if (updated.isNotEmpty())
                                        edited = edited.copy(colorOptions = updated)
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (included) Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = if (colorInt == AndroidColor.BLACK ||
                                    colorInt == AndroidColor.DKGRAY ||
                                    colorInt == AndroidColor.BLUE
                                ) Color.White else Color.Black,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }

            Text(
                text = stringResource(R.string.toolbar_settings_size),
                style = MaterialTheme.typography.subtitle2,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
            )
            ToolbarPen.SIZE_CANDIDATES.chunked(7).forEach { rowSizes ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    rowSizes.forEach { size ->
                        val included = size in includedSizes
                        ToolbarButton(
                            text = ToolbarElements.sizeLabel(size),
                            isSelected = included,
                            onSelect = {
                                val updated =
                                    if (included) includedSizes - size
                                    else (includedSizes + size).sorted()
                                if (updated.isNotEmpty())
                                    edited = edited.copy(sizeOptions = updated)
                            },
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Buttons
// ---------------------------------------------------------------------------

/** Pure black-on-white, square-cornered button — no theme grays, e-ink friendly. */
@Composable
private fun EInkButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        shape = RectangleShape,
        border = BorderStroke(1.dp, Color.Black),
        colors = ButtonDefaults.outlinedButtonColors(
            backgroundColor = Color.White,
            contentColor = Color.Black,
        ),
        content = content,
    )
}

// ---------------------------------------------------------------------------
// Names
// ---------------------------------------------------------------------------

private fun penNameRes(pen: Pen): Int = when (pen) {
    Pen.BALLPEN, Pen.REDBALLPEN, Pen.GREENBALLPEN, Pen.BLUEBALLPEN -> R.string.pen_name_ballpen
    Pen.PENCIL -> R.string.pen_name_pencil // charcoal V1, shown as "Pencil"
    Pen.CHARCOAL -> R.string.pen_name_charcoal
    Pen.BRUSH -> R.string.pen_name_brush
    Pen.FOUNTAIN -> R.string.pen_name_fountain
    Pen.MARKER -> R.string.pen_name_marker
    Pen.CALLIGRAPHY -> R.string.pen_name_calligraphy
    Pen.DASHED -> R.string.pen_name_ballpen // not placeable; unreachable in practice
}

// ----------------------------------- //
// --------      Previews      ------- //
// ----------------------------------- //

@Preview(showBackground = true)
@Composable
private fun ToolbarSettingsPreview() {
    InkaTheme {
        ToolbarSettings(settings = AppSettings(version = 1), onSettingsChange = {})
    }
}
