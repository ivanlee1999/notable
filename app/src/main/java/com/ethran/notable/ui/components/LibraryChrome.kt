package com.ethran.notable.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.ethran.notable.ui.noRippleClickable
import com.ethran.notable.ui.theme.Kaleido

/**
 * The chrome the Library is assembled from: uppercase kickers, 2px section rules and
 * square hit targets. Nothing here rounds a corner, floats or tints — see [Kaleido].
 */

/** Small uppercase label. Carries a section, or names the screen above its title. */
@Composable
fun Kicker(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        fontSize = Kaleido.KickerSize,
        letterSpacing = Kaleido.KickerTracking,
        color = Kaleido.Muted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}

/** A kicker over the 2px rule that opens a section. */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (RowScope.() -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 5.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            Kicker(title, Modifier.weight(1f))
            trailing?.invoke(this)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(Kaleido.SectionRule)
                .background(Kaleido.Ink)
        )
    }
}

/** The hairline between rows inside a section. */
@Composable
fun RowRule(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(Kaleido.RowRule)
            .background(Kaleido.Rule)
    )
}

/**
 * A square action target: outlined for a secondary action, filled ink for the primary one.
 * Sized to the step's hit target, so it never falls below the minimum touch edge.
 */
@Composable
fun SquareButton(
    hit: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .size(hit)
            .then(
                if (filled) Modifier.background(Kaleido.Ink)
                else Modifier.border(1.dp, Kaleido.Ink)
            )
            .noRippleClickable(onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/**
 * A full-width list row: leading mark, label, optional meta, chevron. Folders use it, and
 * on a one-handed device so do notebooks — both read as the same kind of thing.
 */
@Composable
fun ListRow(
    hit: Dp,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    labelSize: TextUnit = 14.sp,
    secondary: String? = null,
    secondaryMaxLines: Int = 1,
    trailing: String? = null,
    onLongClick: (() -> Unit)? = null,
    /** Off for rows that act rather than navigate — a chevron would promise a screen. */
    showChevron: Boolean = true,
    leading: @Composable () -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = hit)
                .then(
                    if (onLongClick != null) Modifier.combinedNoRippleClickable(onClick, onLongClick)
                    else Modifier.noRippleClickable(onClick)
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            leading()
            Column(Modifier.weight(1f)) {
                Text(
                    text = label,
                    fontSize = labelSize,
                    fontWeight = FontWeight.SemiBold,
                    color = Kaleido.Ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (secondary != null) {
                    Text(
                        text = secondary,
                        fontSize = 12.sp,
                        color = Kaleido.Muted,
                        maxLines = secondaryMaxLines,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (trailing != null) {
                Text(text = trailing, fontSize = 11.sp, color = Kaleido.Muted)
            }
            if (showChevron) Chevron()
        }
        RowRule()
    }
}

/** The affordance at the end of every navigating row. */
@Composable
fun Chevron(color: Color = Kaleido.Edge, modifier: Modifier = Modifier) {
    Canvas(modifier.size(16.dp)) {
        val path = Path().apply {
            moveTo(size.width * 0.38f, size.height * 0.24f)
            lineTo(size.width * 0.66f, size.height * 0.50f)
            lineTo(size.width * 0.38f, size.height * 0.76f)
        }
        drawPath(path, color = color, style = Stroke(width = 2.dp.toPx()))
    }
}

/** [noRippleClickable] with a long-press branch — e-ink never wants a ripple. */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.combinedNoRippleClickable(
    onClick: () -> Unit,
    onLongClick: () -> Unit,
): Modifier = composed {
    combinedClickable(
        indication = null,
        interactionSource = remember { MutableInteractionSource() },
        onClick = onClick,
        onLongClick = onLongClick,
        onLongClickLabel = "More options",
        role = Role.Button,
    )
}

/** Shared flat controls keep their whole padded surface interactive. */
@Composable
fun TextAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
) {
    Box(
        modifier.heightIn(min = 48.dp)
            .background(if (filled) Kaleido.Ink else Kaleido.Paper)
            .border(1.dp, Kaleido.Ink)
            .noRippleClickable(onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (filled) Kaleido.Paper else Kaleido.Ink,
            fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun ActionMenu(
    onDismiss: () -> Unit,
    below: Dp = 48.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val offset = with(LocalDensity.current) { below.roundToPx() }
    Popup(alignment = Alignment.TopEnd, offset = IntOffset(0, offset),
        onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Column(Modifier.widthIn(min = 180.dp, max = 280.dp).heightIn(max = 420.dp)
            .background(Kaleido.Paper).border(1.dp, Kaleido.Ink)
            .verticalScroll(rememberScrollState()), content = content)
    }
}

@Composable
fun MenuAction(label: String, onClick: () -> Unit, isSelected: Boolean = false) {
    Text(
        text = if (isSelected) "✓ $label" else label,
        fontSize = 14.sp, color = Kaleido.Ink,
        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .semantics { selected = isSelected }
            .noRippleClickable(onClick).padding(horizontal = 16.dp, vertical = 14.dp),
    )
}
