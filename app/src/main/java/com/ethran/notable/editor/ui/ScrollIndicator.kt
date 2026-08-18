package com.ethran.notable.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ethran.notable.editor.EditorViewModel
import com.ethran.notable.editor.PageView
import com.ethran.notable.ui.convertDpToPixel

/**
 * Vertical scroll indicator (right side).
 * Uses page.scroll.y (IntOffset) for the vertical position.
 */
@Composable
fun ScrollIndicator(viewModel: EditorViewModel, page: PageView) {
    val toolbarState by viewModel.toolbarState.collectAsStateWithLifecycle()
    val zoom by page.zoomLevel.collectAsStateWithLifecycle()
    BoxWithConstraints(
        modifier = Modifier
            .width(5.dp)
            .fillMaxHeight()
    ) {
        val viewportHeightPx = convertDpToPixel(this.maxHeight, LocalContext.current).toInt()

        // All page units inside — the viewport's pixel extent only means anything on the page
        // once divided by the zoom. See [ScrollIndicatorGeometry].
        val thumb = ScrollIndicatorGeometry.thumb(
            contentExtent = page.height.toFloat(),
            scroll = page.scroll.y,
            viewportPx = viewportHeightPx,
            zoom = zoom,
        ) ?: return@BoxWithConstraints

        val indicatorSizeDp = thumb.sizeFraction * this.maxHeight.value
        val indicatorPositionDp = thumb.offsetFraction * this.maxHeight.value

        if (!toolbarState.isToolbarOpen) return@BoxWithConstraints

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .offset(
                    y = indicatorPositionDp.dp
                )
                .background(Color.Black)
                .height(indicatorSizeDp.dp)
        )
    }
}

/**
 * Horizontal scroll indicator (bottom).
 * Uses page.scroll.x (IntOffset) for the horizontal position.
 */
@Composable
fun HorizontalScrollIndicator(viewModel: EditorViewModel, page: PageView) {
    val toolbarState by viewModel.toolbarState.collectAsStateWithLifecycle()
    val zoom by page.zoomLevel.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.weight(1f))
        BoxWithConstraints(
            modifier = Modifier
                .height(5.dp)
                .fillMaxWidth()
        ) {
            val viewportWidthPx = convertDpToPixel(this.maxWidth, LocalContext.current).toInt()

            // The extent is deliberately the page's own sheet rather than the ink extent
            // (PageDataManager.computeWidth): that takes a lock and walks every stroke, which is
            // not something to do during composition. The scroll-past-the-sheet case (where
            // out-of-bounds ink lives) is folded in by [ScrollIndicatorGeometry.thumb] — in page
            // units, since the viewport's pixels only mean anything divided by the zoom.
            val thumb = ScrollIndicatorGeometry.thumb(
                contentExtent = page.sheet.width.toFloat(),
                scroll = page.scroll.x,
                viewportPx = viewportWidthPx,
                zoom = zoom,
            ) ?: return@BoxWithConstraints

            val indicatorSizeDp = thumb.sizeFraction * this.maxWidth.value
            val indicatorPositionDp = thumb.offsetFraction * this.maxWidth.value

            if (!toolbarState.isToolbarOpen) return@BoxWithConstraints

            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .offset(
                        x = indicatorPositionDp.dp
                    )
                    .background(Color.Black)
                    .width(indicatorSizeDp.dp)
            )
        }
    }
}
