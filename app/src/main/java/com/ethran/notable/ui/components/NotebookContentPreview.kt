package com.ethran.notable.ui.components

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.*
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.sync.SyncBadge
import com.ethran.notable.ui.theme.Kaleido
import compose.icons.FeatherIcons
import compose.icons.feathericons.Edit2
import compose.icons.feathericons.Maximize2
import compose.icons.feathericons.Minimize2
import compose.icons.feathericons.ChevronLeft
import compose.icons.feathericons.ChevronRight
import kotlin.math.abs

internal fun previewPage(pages: List<String>, current: String?, direction: Int): String? {
    val index = pages.indexOf(current)
    return if (index < 0) null else pages.getOrNull(index + direction)
}

enum class BrowserLayout(val label: String, val columns: Int, val rows: Int) {
    FEED("Reading feed", 1, 2),
    COMPACT("Compact list", 1, 6),
    LARGE("2 × 2 · Large", 2, 2),
    MEDIUM("2 × 3 · Medium", 2, 3),
    OVERVIEW("3 × 4 · Overview", 3, 4);

    fun columnsFor(width: Dp): Int = if (width < 600.dp) columns.coerceAtMost(2) else columns

    fun previewHeight(viewport: Dp): Dp = if (this == COMPACT) 72.dp
        else if (this == FEED) (viewport * .55f).coerceIn(180.dp, 360.dp)
        else ((viewport - 32.dp) / rows - 108.dp).coerceAtLeast(64.dp)

    companion object {
        fun fromKey(key: String?, compact: Boolean): BrowserLayout {
            val layout = entries.firstOrNull { it.name == key } ?: if (compact) FEED else LARGE
            return if (compact && layout == OVERVIEW) MEDIUM else layout
        }
    }
}

/** A local viewing preference, shared by Today, topics and folders. It never changes note data. */
@Composable
fun rememberBrowserLayout(compact: Boolean): Pair<BrowserLayout, (BrowserLayout) -> Unit> {
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("note_browser", Context.MODE_PRIVATE) }
    var stored by remember { mutableStateOf(preferences.getString("layout", null)) }
    DisposableEffect(preferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            if (key == "layout") stored = prefs.getString(key, null)
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return BrowserLayout.fromKey(stored, compact) to { layout: BrowserLayout ->
        stored = layout.name
        preferences.edit().putString("layout", layout.name).apply()
    }
}

@Composable
fun BrowserLayoutPicker(layout: BrowserLayout, onChoose: (BrowserLayout) -> Unit, compact: Boolean) {
    var open by remember { mutableStateOf(false) }
    Box {
        val label = if (!compact) layout.label else when (layout) {
            BrowserLayout.FEED -> "Feed"
            BrowserLayout.COMPACT -> "List"
            else -> "${layout.columns} × ${layout.rows}"
        }
        TextAction("$label ▾", { open = true },
            Modifier.semantics { contentDescription = "Preview size" })
        if (open) ActionMenu(onDismiss = { open = false }) {
            BrowserLayout.entries.filter { !compact || it != BrowserLayout.OVERVIEW }.forEach { choice ->
                MenuAction(choice.label, { open = false; onChoose(choice) }, choice == layout)
            }
            HandwritingPreviewMenuItems { open = false }
        }
    }
}

/** Expanding one note gives it a whole row without reordering or dropping its neighbors. */
internal fun <T> previewRows(items: List<T>, columns: Int, expanded: String?, id: (T) -> String): List<List<T>> {
    require(columns > 0)
    val rows = mutableListOf<List<T>>()
    var row = mutableListOf<T>()
    items.forEach { item ->
        if (id(item) == expanded) {
            if (row.isNotEmpty()) { rows += row; row = mutableListOf() }
            rows += listOf(item)
        } else {
            row += item
            if (row.size == columns) { rows += row; row = mutableListOf() }
        }
    }
    if (row.isNotEmpty()) rows += row
    return rows
}

@Composable
fun NotebookContentPreview(
    notebook: Notebook,
    previewHeight: Dp,
    expanded: Boolean,
    onExpand: () -> Unit,
    onEdit: (String?) -> Unit,
    modifier: Modifier = Modifier,
    version: Long = notebook.updatedAt.time,
    subtitle: String? = null,
    onPreviewNeeded: ((String) -> Unit)? = null,
    syncBadge: SyncBadge? = null,
    selectedPageId: String? = null,
    onPageChange: ((String) -> Unit)? = null,
    onRename: (() -> Unit)? = null,
    onRenamePage: ((String) -> Unit)? = null,
    actions: @Composable () -> Unit = {},
) {
    var localPage by rememberSaveable(notebook.id) {
        mutableStateOf(notebook.openPageId?.takeIf { it in notebook.pageIds } ?: notebook.pageIds.firstOrNull())
    }
    var pagesMenu by remember { mutableStateOf(false) }
    val page = (selectedPageId ?: localPage)?.takeIf { it in notebook.pageIds }
        ?: notebook.openPageId?.takeIf { it in notebook.pageIds } ?: notebook.pageIds.firstOrNull()
    val index = notebook.pageIds.indexOf(page).coerceAtLeast(0)
    fun selectPage(id: String) { if (onPageChange != null) onPageChange(id) else localPage = id }
    fun turn(direction: Int): Boolean = previewPage(notebook.pageIds, page, direction)?.let {
        selectPage(it); true
    } ?: false
    Column(modifier.fillMaxWidth().border(1.dp, Kaleido.Edge).background(Kaleido.Paper)) {
        Row(Modifier.fillMaxWidth().padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).heightIn(min = 48.dp)
                .then(if (onRename != null) Modifier.clickable(onClick = onRename)
                    .semantics { contentDescription = "Rename ${notebook.title}" } else Modifier)
                .padding(vertical = 8.dp), verticalArrangement = Arrangement.Center) {
                Text(notebook.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    fontSize = 14.sp, color = Kaleido.Ink)
                subtitle?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontSize = 12.sp, color = Kaleido.Muted) }
            }
            syncBadge?.iconOrNull()?.let { icon ->
                Icon(icon, syncBadge.label(), tint = Kaleido.Ink, modifier = Modifier.size(16.dp))
            }
            actions()
        }
        if (page != null) PagePreview(
            pageId = page, version = version, onPreviewNeeded = onPreviewNeeded,
            modifier = Modifier.fillMaxWidth().then(if (expanded) Modifier else Modifier.height(previewHeight))
                .clickable { onEdit(page) }
                .pointerInput(notebook.id, notebook.pageIds, page) {
                    val threshold = 48.dp.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (down.type != PointerType.Touch) return@awaitEachGesture
                        var distance = 0f
                        val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ ->
                            val delta = change.position - down.position
                            if (abs(delta.x) > abs(delta.y) * 1.35f) {
                                distance = delta.x; change.consume()
                            }
                        }
                        if (drag != null && horizontalDrag(drag.id) { change ->
                            distance += change.positionChange().x; change.consume()
                        } && abs(distance) >= threshold) turn(if (distance < 0) 1 else -1)
                    }
                }
                .clipToBounds().semantics { contentDescription = "${notebook.title} ink preview" },
            contentScale = ContentScale.FillWidth, alignment = Alignment.TopStart,
            expandToContent = expanded,
        ) else Box(Modifier.fillMaxWidth().height(previewHeight).background(Kaleido.Canvas))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cardWidth = maxWidth
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (notebook.pageIds.size > 1 && cardWidth >= 264.dp) SquareButton(48.dp, { turn(-1) }) {
                Icon(FeatherIcons.ChevronLeft, "Previous page of ${notebook.title}", tint = Kaleido.Ink)
            }
            if (page != null) {
                Box(Modifier.weight(1f)) {
                    SquareButton(48.dp, { pagesMenu = true }, Modifier.fillMaxWidth()
                        .semantics {
                            contentDescription = "Preview pages of ${notebook.title}"
                            customActions = listOf(CustomAccessibilityAction("Previous page") { turn(-1) },
                                CustomAccessibilityAction("Next page") { turn(1) })
                        }) { Text("${index + 1}/${notebook.pageIds.size} ▾", color = Kaleido.Ink,
                            fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    if (pagesMenu) ActionMenu(onDismiss = { pagesMenu = false }) {
                        if (index > 0) MenuAction("Previous page", { turn(-1); pagesMenu = false })
                        if (index < notebook.pageIds.lastIndex) MenuAction("Next page", { turn(1); pagesMenu = false })
                        MenuAction(if (expanded) "Collapse preview" else "Expand preview", { onExpand(); pagesMenu = false })
                        if (onRenamePage != null) MenuAction("Rename page", { pagesMenu = false; onRenamePage(page) })
                    }
                }
            } else Text("No pages", fontSize = 12.sp,
                color = Kaleido.Muted, modifier = Modifier.weight(1f).padding(start = 8.dp))
            if (notebook.pageIds.size > 1 && cardWidth >= 264.dp) SquareButton(48.dp, { turn(1) }) {
                Icon(FeatherIcons.ChevronRight, "Next page of ${notebook.title}", tint = Kaleido.Ink)
            }
            if (cardWidth >= 156.dp) SquareButton(48.dp, onExpand) {
                Icon(if (expanded) FeatherIcons.Minimize2 else FeatherIcons.Maximize2,
                    if (expanded) "Collapse ${notebook.title}" else "Expand ${notebook.title} in place", tint = Kaleido.Ink)
            }
            SquareButton(48.dp, { onEdit(page) }) {
                Icon(FeatherIcons.Edit2, "Edit ${notebook.title}", tint = Kaleido.Ink)
            }
        }
        }
    }
}
