package com.ethran.notable.ui.components

import android.text.format.DateUtils
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.model.BackgroundType
import com.ethran.notable.sync.SyncBadge
import com.ethran.notable.ui.noRippleClickable
import com.ethran.notable.ui.theme.Kaleido
import java.util.Date
import kotlin.math.sqrt

/**
 * A notebook as it appears in the Library.
 *
 * The cover is a drawn object rather than a tinted rectangle: the book's own paper
 * template struck in [Kaleido.Grid], under the first page's thumbnail once one exists, with
 * a saturated spine down the binding edge. That keeps colour to a single large fill — the
 * one thing a Kaleido panel prints cleanly — and keeps the cover legible if the colour
 * layer washes out, because the pattern and the border are mono.
 */
@Composable
fun NotebookCoverCard(
    notebook: Notebook,
    onOpen: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    syncBadge: SyncBadge? = null,
    onPreviewNeeded: (String) -> Unit = {},
) {
    Column(
        modifier
            .fillMaxWidth()
            .combinedNoRippleClickable(onClick = onOpen, onLongClick = onOpenSettings)
    ) {
        NotebookCoverFace(
            notebook = notebook,
            spine = Kaleido.Spine,
            syncBadge = syncBadge,
            onPreviewNeeded = onPreviewNeeded,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
        )
        Text(
            text = notebook.title,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = Kaleido.Ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 7.dp)
        )
        Text(
            text = editedLabel(notebook.updatedAt),
            fontSize = 10.sp,
            color = Kaleido.Muted,
            maxLines = 1
        )
    }
}

/** The one-handed variant: the same cover shrunk to a chip, on a [ListRow]. */
@Composable
fun NotebookListRow(
    notebook: Notebook,
    hit: Dp,
    onOpen: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    syncBadge: SyncBadge? = null,
    onPreviewNeeded: (String) -> Unit = {},
) {
    ListRow(
        hit = hit + 22.dp, // the 46dp chip needs more room than a plain row
        label = notebook.title,
        secondary = "${notebook.pageIds.size} p · ${editedLabel(notebook.updatedAt)}",
        onClick = onOpen,
        onLongClick = onOpenSettings,
        modifier = modifier,
        leading = {
            NotebookCoverFace(
                notebook = notebook,
                spine = 6.dp,
                syncBadge = syncBadge,
                showPageCount = false,
                onPreviewNeeded = onPreviewNeeded,
                modifier = Modifier.size(34.dp, 46.dp)
            )
        }
    )
}

@Composable
private fun NotebookCoverFace(
    notebook: Notebook,
    spine: Dp,
    modifier: Modifier = Modifier,
    syncBadge: SyncBadge? = null,
    showPageCount: Boolean = true,
    onPreviewNeeded: (String) -> Unit = {},
) {
    Box(
        modifier
            .background(Kaleido.Paper)
            .border(1.dp, Kaleido.Edge)
    ) {
        // Paper template first, so it shows through until the thumbnail arrives — and
        // stays visible for a book whose first page has never been rendered.
        Canvas(Modifier.fillMaxSize()) { drawTemplate(notebook.templateKey()) }

        notebook.pageIds.firstOrNull()?.let { firstPage ->
            PagePreview(
                modifier = Modifier.fillMaxSize(),
                pageId = notebook.openPageId ?: firstPage,
                onPreviewNeeded = onPreviewNeeded,
                background = Color.Transparent,
            )
        }

        Box(
            Modifier
                .fillMaxHeight()
                .width(spine)
                .background(Kaleido.spineFor(notebook.id))
        )

        if (showPageCount) {
            Text(
                text = "${notebook.pageIds.size} p",
                fontSize = 9.sp,
                letterSpacing = 1.3.sp,
                color = Kaleido.Ink,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = spine)
                    .background(Kaleido.Paper)
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            )
        }

        syncBadge?.iconOrNull()?.let { icon -> SyncCorner(icon, syncBadge) }
    }
}

@Composable
private fun BoxScope.SyncCorner(icon: ImageVector, badge: SyncBadge) {
    Icon(
        imageVector = icon,
        contentDescription = "Sync status: ${badge.name}",
        tint = Kaleido.Ink,
        modifier = Modifier
            .align(Alignment.TopEnd)
            .background(Kaleido.Paper)
            .padding(2.dp)
            .size(14.dp)
    )
}

/**
 * Which native template the book's pages are drawn on. An imported PDF or image background
 * has no pattern of its own, so the cover falls back to blank paper and lets the thumbnail
 * carry it.
 */
private fun Notebook.templateKey(): String =
    if (defaultBackgroundType == BackgroundType.Native.key) defaultBackground else "blank"

/**
 * The five native templates, struck in the same grey the page itself uses so a cover and
 * the page it stands for look like one object. Spacing is a fraction of the cover, not a
 * fixed step, so a 34dp chip and a full-size cover read as the same paper.
 */
private fun DrawScope.drawTemplate(template: String) {
    val stroke = 1.dp.toPx()
    val step = size.width / 8f
    when (template) {
        "lined" -> {
            var y = step
            while (y < size.height) {
                drawLine(Kaleido.Grid, Offset(0f, y), Offset(size.width, y), stroke)
                y += step
            }
        }

        "dotted" -> {
            var y = step
            while (y < size.height) {
                var x = step
                while (x < size.width) {
                    drawCircle(Kaleido.Grid, radius = stroke, center = Offset(x, y))
                    x += step
                }
                y += step
            }
        }

        "squared" -> {
            var y = step
            while (y < size.height) {
                drawLine(Kaleido.Grid, Offset(0f, y), Offset(size.width, y), stroke)
                y += step
            }
            var x = step
            while (x < size.width) {
                drawLine(Kaleido.Grid, Offset(x, 0f), Offset(x, size.height), stroke)
                x += step
            }
        }

        "hexed" -> drawHexes(radius = size.width / 6f, stroke = stroke)

        // "blank" and anything unrecognised: bare paper.
        else -> Unit
    }
}

/** Pointy-top honeycomb, rows offset by half a cell. */
private fun DrawScope.drawHexes(radius: Float, stroke: Float) {
    val w = sqrt(3f) * radius
    val vStep = radius * 1.5f
    var row = 0
    var cy = 0f
    while (cy < size.height + radius) {
        var cx = if (row % 2 == 0) 0f else w / 2f
        while (cx < size.width + w) {
            val path = Path()
            for (corner in 0..5) {
                val angle = Math.toRadians((60.0 * corner) - 90.0)
                val px = cx + radius * kotlin.math.cos(angle).toFloat()
                val py = cy + radius * kotlin.math.sin(angle).toFloat()
                if (corner == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            path.close()
            drawPath(path, Kaleido.Grid, style = Stroke(width = stroke))
            cx += w
        }
        cy += vStep
        row++
    }
}

/** "12 min ago", "Yesterday" — the platform's own phrasing, so it localises for free. */
private fun editedLabel(updatedAt: Date): String =
    DateUtils.getRelativeTimeSpanString(
        updatedAt.time,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE
    ).toString()

/** Icon for a badge, or null when there is nothing worth showing. Shared with the file bar. */
internal fun SyncBadge.iconOrNull(): ImageVector? = when (this) {
    SyncBadge.SYNCED -> Icons.Default.CloudDone
    SyncBadge.NOT_SYNCED -> Icons.Default.CloudOff
    SyncBadge.SCHEDULED -> Icons.Default.Schedule
    SyncBadge.SYNCING -> Icons.Default.Sync
    SyncBadge.REMOTE_AHEAD -> Icons.Default.CloudDownload
    SyncBadge.CONFLICT -> Icons.Default.SyncProblem
    SyncBadge.ERROR -> Icons.Default.ErrorOutline
}

/** A blank slot that opens an action, drawn to the same frame as a cover. */
@Composable
fun CoverActionTile(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .background(Kaleido.Paper)
                .border(1.dp, Kaleido.Ink)
                .noRippleClickable(onClick),
            contentAlignment = Alignment.Center
        ) { icon() }
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = Kaleido.Ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 7.dp)
        )
        // Keeps the tile the same total height as a cover card, whose second line is the
        // edited stamp.
        Box(Modifier.height(14.dp))
    }
}
