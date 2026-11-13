package com.ethran.notable.editor.text

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import androidx.compose.ui.geometry.Offset
import com.ethran.notable.data.db.Block
import com.ethran.notable.data.db.PageWithData
import com.ethran.notable.data.model.sheet

/** A reflowed projection for raster previews/exports; it never changes a stored annotation. */
object FlowTextLayout {
    private const val MARGIN = 32
    private const val GAP = 24

    fun hasCanvas(data: PageWithData): Boolean = data.strokes.isNotEmpty() || data.images.isNotEmpty() ||
        TextBoxLayout.textBoxes(data.blocks).isNotEmpty() || data.page.backgroundType != "native"

    fun canvasDimensions(data: PageWithData): Pair<Int, Int> {
        val sheet = data.page.sheet()
        val text = TextBoxLayout.textBoxes(data.blocks).mapNotNull { TextBoxLayout.bounds(it) }
        return maxOf(sheet.width, (data.strokes.maxOfOrNull { it.right.toInt() } ?: 0) + 50,
            (data.images.maxOfOrNull { it.x + it.width } ?: 0) + 50, (text.maxOfOrNull { it.right } ?: 0) + 50) to
            maxOf(sheet.height, (data.strokes.maxOfOrNull { it.bottom.toInt() } ?: 0) + 50,
                (data.images.maxOfOrNull { it.y + it.height } ?: 0) + 50, (text.maxOfOrNull { it.bottom } ?: 0) + 50)
    }

    fun positioned(data: PageWithData, canvasHeight: Int = canvasDimensions(data).second): List<Block> {
        val width = data.page.sheet().width.coerceAtLeast(MARGIN * 2 + 1)
        var y = if (hasCanvas(data)) canvasHeight + GAP else MARGIN
        return FlowText.ordered(data.blocks).mapNotNull { block ->
            // Ink groups still point at canonical page strokes, drawn exactly once in the canvas.
            // Hidden immutable source versions are offered in the editor's history instead.
            val source = when (block.kind) {
                "md" -> block.text.orEmpty()
                "link" -> "↗ ${block.text?.takeIf(String::isNotBlank) ?: block.targetNotebookId.orEmpty()}"
                "audio" -> "Audio recording"
                "image" -> "Image attachment"
                else -> return@mapNotNull null
            }
            val w = width - MARGIN * 2
            val height = TextBoxLayout.measuredHeight(source, w.toFloat())
            block.copy(kind = "md", text = source, x = MARGIN, y = y, width = w, height = height).also {
                y += height + GAP
            }
        }
    }

    fun dimensions(data: PageWithData): Pair<Int, Int> {
        val canvas = canvasDimensions(data)
        val flow = positioned(data, canvas.second)
        if (flow.isEmpty()) return canvas
        val bottom = (flow.last().y ?: 0) + (flow.last().height ?: 0) + MARGIN
        return (if (hasCanvas(data)) canvas.first else data.page.sheet().width) to bottom
    }

    fun draw(canvas: Canvas, data: PageWithData, scroll: Offset) {
        positioned(data).forEach { TextBoxLayout.draw(canvas, it, scroll) }
    }
}
