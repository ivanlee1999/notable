package com.ethran.notable.editor.ui.toolbar.model

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ethran.notable.editor.utils.ERASER_SWATH_WIDTH
import com.ethran.notable.editor.utils.Pen

/**
 * How broad the nib is — five named steps, scaled off whatever implement is in hand.
 *
 * Named steps rather than a slider: on e-ink a slider is a drag that redraws the screen every
 * frame. Five rather than three, because fine/medium/broad covered ordinary writing but left no
 * hairline for annotating printed text and nothing heavy enough to letter a title with.
 *
 * The rail used to draw its dots from each preset's own `sizeOptions` list, which meant the same
 * dot could be a hairline on one pen and a slab on the next, and a pen with one configured size
 * showed no dots at all. A step now means the same thing everywhere: [scale] applied to the
 * implement's [Pen.baseWidth], so a highlighter's medium is still a highlighter and a pencil's is
 * still a pencil.
 *
 * Matches the iPad app's `ToolSelection.Width` step for step, including the dot sizes, so the two
 * rails read as one ramp.
 */
enum class NibWidth(
    val key: String,
    val label: String,
    /** Applied to the implement's own [Pen.baseWidth]. */
    val scale: Float,
    /**
     * The dot the rail draws for this step. Not the nib itself — that depends on the implement —
     * but the same ordering, so the row reads as one ramp whatever is in hand.
     */
    val dot: Dp,
) {
    HAIR("hair", "hairline", 0.3f, 3.dp),
    FINE("fine", "fine", 0.5f, 6.dp),
    MEDIUM("medium", "medium", 1f, 11.dp),
    BROAD("broad", "broad", 2f, 17.dp),
    HEAVY("heavy", "heavy", 3f, 24.dp);

    /** The stroke width this step means for [base], rounded to something a user could read back. */
    fun sizeFor(base: Float): Float = roundNib(base * scale)

    companion object {
        /**
         * The step [size] is closest to, on the ramp for [base].
         *
         * Nearest rather than exact, because a size can arrive from anywhere the ramp does not
         * reach — a preset persisted before the ramp existed, a width typed into the stroke menu,
         * a marker seeded at 40 when the ramp's steps are 24 and 48. A rail showing five dots and
         * no selection would be worse than showing none, so one is always lit.
         */
        fun nearest(base: Float, size: Float): NibWidth =
            entries.minBy { kotlin.math.abs(it.sizeFor(base) - size) }

        fun fromKeyOrDefault(key: String?): NibWidth =
            entries.firstOrNull { it.key == key } ?: MEDIUM

        /**
         * Half-point steps below 10 and whole ones above, so a hairline stays distinguishable
         * from a fine while a highlighter does not advertise a width of 72.4.
         */
        private fun roundNib(raw: Float): Float =
            if (raw < 10f) Math.round(raw * 2f) / 2f else Math.round(raw).toFloat()
    }
}

/**
 * The width this implement writes at [NibWidth.MEDIUM]; every other step scales from it.
 *
 * Per implement because they are different tools: a highlighter that came out the width of a pen
 * would not highlight anything. The eraser's base is [ERASER_SWATH_WIDTH] — the swath it has
 * always deleted — so an eraser nobody has touched still rubs out exactly what it used to.
 */
val Pen.baseWidth: Float
    get() = when (this) {
        Pen.BALLPEN, Pen.REDBALLPEN, Pen.GREENBALLPEN, Pen.BLUEBALLPEN, Pen.DASHED -> 5f
        Pen.FOUNTAIN, Pen.CALLIGRAPHY -> 6f
        Pen.PENCIL, Pen.CHARCOAL -> 4f
        Pen.BRUSH -> 5f
        Pen.MARKER -> 24f
    }

/** The eraser's ramp base, so its five steps are 9 / 15 / 30 / 60 / 90 page units. */
const val ERASER_BASE_WIDTH = ERASER_SWATH_WIDTH
