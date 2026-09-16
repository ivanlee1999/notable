package com.ethran.notable.editor.text

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import androidx.compose.ui.geometry.Offset
import com.ethran.notable.data.db.Block
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * How a text box is sized and set, in **page units** (1 unit = 0.15 mm).
 *
 * One value rather than constants scattered through the editor, because the same numbers are
 * needed in four places that must agree — the canvas, the height written to the block, the
 * thumbnail, and the export — and because the iPad has to be given the same ones.
 * `TextBoxMetrics` in bopa's `TextBoxLayout.swift` is this table in Swift.
 */
data class TextBoxMetrics(
    /**
     * The em size of ordinary text. 32 units is 4.8 mm, near enough to 13.6 pt on paper: the size
     * a printed note is set at, and legible on a 10.3" BOOX and an 11" iPad at the zoom each
     * opens a page at.
     */
    val body: Float = 32f,
    val headingScales: List<Float> = listOf(2f, 1.5f, 1.25f),
    /** Multiplied into the font's natural leading. */
    val lineHeightMultiple: Float = 1.3f,
    /** Inset on every side, included in the block's stored width. */
    val padding: Float = 12f,
    /**
     * How far a list item's wrapped lines are indented, so they align under the first word rather
     * than under the bullet.
     */
    val listIndent: Float = 40f,
    /** The width a new box is given, before the right-margin clamp. */
    val preferredWidth: Float = 560f,
    /**
     * The narrowest a box may be. Below this a word cannot break sensibly and the box becomes a
     * column of single letters.
     */
    val minimumWidth: Float = 160f,
    /** Space kept between a box and the right edge of the sheet. */
    val rightMargin: Float = 40f,
) {
    /** The em size for a line, in page units. */
    fun sizeFor(kind: MarkdownText.LineKind): Float =
        if (kind is MarkdownText.LineKind.Heading) {
            body * headingScales[min(max(kind.level, 1), headingScales.size) - 1]
        } else {
            body
        }

    companion object {
        val STANDARD = TextBoxMetrics()
    }
}

/**
 * Setting a text box's markdown: building the styled text, measuring it, and drawing it.
 *
 * Everything is in page units, drawn onto a canvas the caller has already scaled — the same
 * convention `drawImage` and the stroke renderers follow. One implementation therefore serves the
 * editor, the thumbnail and the export, and a box cannot come out a different shape in any of them.
 */
object TextBoxLayout {

    /** The text of [source], set at [metrics], ready to lay out at a width in page units. */
    fun styledText(
        source: String,
        metrics: TextBoxMetrics = TextBoxMetrics.STANDARD,
    ): Spanned {
        val builder = SpannableStringBuilder()
        val lines = MarkdownText.parse(source)

        for ((index, line) in lines.withIndex()) {
            val lineStart = builder.length
            val kind = line.kind

            val prefix = when (kind) {
                is MarkdownText.LineKind.Bullet -> "• "
                is MarkdownText.LineKind.Numbered -> "${kind.number}. "
                else -> ""
            }
            if (prefix.isNotEmpty()) builder.append(prefix)

            for (span in line.spans) {
                val start = builder.length
                builder.append(span.text)
                applyInlineSpans(builder, start, builder.length, span.traits)
            }

            // Relative to the paint's own size, which is the body size — so one TextPaint serves
            // the whole box and headings are a span rather than a second layout.
            if (kind is MarkdownText.LineKind.Heading) {
                val scale = metrics.sizeFor(kind) / metrics.body
                builder.setSpan(
                    RelativeSizeSpan(scale), lineStart, builder.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                // A heading is bold by being a heading, so nothing has to be typed to make it one.
                builder.setSpan(
                    StyleSpan(Typeface.BOLD), lineStart, builder.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
            if (prefix.isNotEmpty()) {
                builder.setSpan(
                    LeadingMarginSpan.Standard(0, metrics.listIndent.roundToInt()),
                    lineStart, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
            if (index < lines.size - 1) builder.append("\n")
        }
        return builder
    }

    private fun applyInlineSpans(
        builder: SpannableStringBuilder,
        start: Int,
        end: Int,
        traits: MarkdownText.SpanTraits,
    ) {
        if (start == end) return
        fun set(what: Any) =
            builder.setSpan(what, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        val bold = MarkdownText.SpanTraits.BOLD in traits
        val italic = MarkdownText.SpanTraits.ITALIC in traits
        when {
            bold && italic -> set(StyleSpan(Typeface.BOLD_ITALIC))
            bold -> set(StyleSpan(Typeface.BOLD))
            italic -> set(StyleSpan(Typeface.ITALIC))
        }
        if (MarkdownText.SpanTraits.CODE in traits) {
            set(TypefaceSpan("monospace"))
            // A monospaced face at the same em reads noticeably larger than the text around it
            // and breaks the line.
            set(RelativeSizeSpan(0.92f))
        }
        if (MarkdownText.SpanTraits.LINK in traits) set(UnderlineSpan())
    }

    /** The paint a box is set with, in page units. */
    fun paint(metrics: TextBoxMetrics = TextBoxMetrics.STANDARD): TextPaint =
        TextPaint().apply {
            isAntiAlias = true
            color = Color.BLACK
            textSize = metrics.body
        }

    /** Lays [source] out to wrap inside a box [width] page units wide, padding included. */
    fun layout(
        source: String,
        width: Float,
        metrics: TextBoxMetrics = TextBoxMetrics.STANDARD,
    ): StaticLayout {
        val text = styledText(source, metrics)
        val inner = max(width - metrics.padding * 2, 1f).roundToInt()
        return StaticLayout.Builder
            .obtain(text, 0, text.length, paint(metrics), inner)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, metrics.lineHeightMultiple)
            .setIncludePad(false)
            .build()
    }

    /**
     * How tall [source] is when set into a box [width] page units wide, padding included.
     *
     * Never less than one line: an empty box still has to be big enough to put a caret in.
     */
    fun measuredHeight(
        source: String,
        width: Float,
        metrics: TextBoxMetrics = TextBoxMetrics.STANDARD,
    ): Int {
        val oneLine = metrics.body * metrics.lineHeightMultiple
        val laid = layout(source, width, metrics).height.toFloat()
        return Math.ceil((max(laid, oneLine) + metrics.padding * 2).toDouble()).toInt()
    }

    /**
     * The width a new box gets at [x] on a sheet [pageWidth] wide: the preferred width, pulled in
     * so the box stops short of the right edge, and never below the minimum.
     *
     * A box narrower than the minimum is possible only on a sheet too narrow to hold one, where
     * running past the edge is better than a column one letter wide.
     */
    fun defaultWidth(
        x: Float,
        pageWidth: Float,
        metrics: TextBoxMetrics = TextBoxMetrics.STANDARD,
    ): Int = max(min(metrics.preferredWidth, pageWidth - x - metrics.rightMargin),
        metrics.minimumWidth).roundToInt()

    /**
     * Whether [block] is a text box: markdown, with a place of its own on the page.
     *
     * A flowing block has no place to be drawn at, and a block of another kind is not text. Both
     * are carried through the file untouched — this app has no UI for either, and a box of
     * question marks over somebody's ink is worse than nothing until it does.
     */
    fun isTextBox(block: Block): Boolean =
        block.kind == "md" && block.x != null && block.y != null

    /**
     * The text boxes of a page, oldest first — the order they are drawn in, so a hit test can walk
     * it backwards and find what the eye would pick.
     */
    fun textBoxes(blocks: List<Block>): List<Block> =
        blocks.filter(::isTextBox).sortedWith(compareBy({ it.createdAt }, { it.id }))

    /** The rectangle [block] occupies, in page units, or null if it is not a text box. */
    fun bounds(block: Block, metrics: TextBoxMetrics = TextBoxMetrics.STANDARD): Rect? {
        if (!isTextBox(block)) return null
        val x = block.x ?: return null
        val y = block.y ?: return null
        val width = block.width ?: metrics.preferredWidth.roundToInt()
        val height = max(block.height ?: 0, 1)
        return Rect(x, y, x + width, y + height)
    }

    /**
     * Draws one box onto a canvas already scaled to the page, offset by the scroll — the same
     * contract [com.ethran.notable.editor.drawing.drawImage] has.
     */
    fun draw(
        canvas: Canvas,
        block: Block,
        offset: Offset,
        metrics: TextBoxMetrics = TextBoxMetrics.STANDARD,
    ) {
        val text = block.text
        if (!isTextBox(block) || text.isNullOrEmpty()) return
        val x = block.x ?: return
        val y = block.y ?: return
        val width = (block.width ?: metrics.preferredWidth.roundToInt()).toFloat()

        canvas.save()
        // Clipped to the stored rectangle so a box whose height is stale — the other device
        // measured it with its own fonts — cannot spill over the ink beneath it.
        bounds(block, metrics)?.let {
            canvas.clipRect(
                it.left + offset.x, it.top + offset.y,
                it.right + offset.x, it.bottom + offset.y,
            )
        }
        canvas.translate(x + offset.x + metrics.padding, y + offset.y + metrics.padding)
        layout(text, width, metrics).draw(canvas)
        canvas.restore()
    }
}
