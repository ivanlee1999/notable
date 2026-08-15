package com.ethran.notable.editor.ui.toolbar.model

import com.ethran.notable.editor.utils.Pen
import com.ethran.notable.editor.utils.PenSetting
import kotlinx.serialization.Serializable
import java.util.UUID
import android.graphics.Color as AndroidColor

/**
 * A user-created pen instance: a base [Pen] type plus its own color and size. The same
 * base type may appear multiple times (two ballpens in different colors), each as its own
 * toolbar button. The preset *is* the pen's setting — StrokeMenu edits write back to it,
 * persisted in `AppSettings.toolbarPens`.
 *
 * The rail's own four implements are resolved from this list by [railPresets]; anything
 * left over is reached through [extraPresets].
 */
@Serializable
data class ToolbarPen(
    /** Generated, stable, never reused. Layouts and the editor state reference it. */
    val id: String,
    /** Base type: which stroke style the pen produces. */
    val pen: Pen,
    val color: Int,
    val size: Float,
    /**
     * Which colors this pen's StrokeMenu offers, user-picked in the toolbar settings.
     * Null (also for pre-existing persisted presets) → [DEFAULT_COLOR_OPTIONS].
     */
    val colorOptions: List<Int>? = null,
    /** Which sizes this pen's StrokeMenu offers. Null → the base type's default set. */
    val sizeOptions: List<Float>? = null,
) {
    /** The preset's color/size as a fresh [PenSetting] (its fields are mutable). */
    fun setting(): PenSetting = PenSetting(size, color)

    fun effectiveColorOptions(): List<Int> = colorOptions ?: DEFAULT_COLOR_OPTIONS

    fun effectiveSizeOptions(): List<Float> =
        sizeOptions ?: if (pen == Pen.MARKER) DEFAULT_MARKER_SIZES else DEFAULT_STROKE_SIZES

    /**
     * The nibs the rail offers for this pen, given the size it is currently writing at.
     *
     * [currentSize] leads the list before the cut to four and only then is the result sorted,
     * so the size in hand cannot be the one that got dropped — a rail showing four dots and no
     * selection would be worse than showing none. Fewer than two means there is nothing to
     * choose between, and the rail draws no dots at all.
     */
    fun nibChoices(currentSize: Float): List<Float> {
        val sizes = (listOf(currentSize) + effectiveSizeOptions()).distinct().take(4).sorted()
        return if (sizes.size < 2) emptyList() else sizes
    }

    companion object {
        /** Matches the historical hardcoded StrokeMenu palette (compose defaults). */
        val DEFAULT_COLOR_OPTIONS: List<Int> = listOf(
            AndroidColor.RED, AndroidColor.GREEN, AndroidColor.BLUE, AndroidColor.CYAN,
            AndroidColor.MAGENTA, AndroidColor.YELLOW, AndroidColor.GRAY,
            AndroidColor.DKGRAY, AndroidColor.BLACK,
        )

        /** Everything the settings editor offers for inclusion in [colorOptions]. */
        val COLOR_CANDIDATES: List<Int> = DEFAULT_COLOR_OPTIONS + listOf(
            AndroidColor.LTGRAY,
            0xFFFFA500.toInt(), // orange
            0xFF800080.toInt(), // purple
            0xFF8B4513.toInt(), // brown
        )

        val DEFAULT_STROKE_SIZES = listOf(3f, 5f, 10f, 20f)
        val DEFAULT_MARKER_SIZES = listOf(25f, 40f, 60f, 80f)

        /** Everything the settings editor offers for inclusion in [sizeOptions]. */
        val SIZE_CANDIDATES = listOf(1f, 2f, 3f, 5f, 8f, 10f, 15f, 20f, 25f, 30f, 40f, 60f, 80f)

        fun newId(): String = UUID.randomUUID().toString().take(8)

        /** Base types offered when creating a preset — legacy color variants and the
         * erase-indicator DASHED are not placeable. Pen.PENCIL is charcoal V1 ("Charcoal
         * (classic)"), kept for the historical default preset; new work should prefer
         * Pen.CHARCOAL (V2). */
        val BASE_TYPES = listOf(
            Pen.BALLPEN, Pen.FOUNTAIN, Pen.BRUSH, Pen.MARKER,
            Pen.CHARCOAL, Pen.PENCIL, Pen.CALLIGRAPHY,
        )

        /**
         * The presets a fresh install starts with. Seed ids are stable, so a preset
         * survives an upgrade. The old red/blue/green pens were separate [Pen] values;
         * as presets they are plain ballpens with a color — new strokes persist
         * `pen = BALLPEN` and render identically.
         *
         * Only one of each [RAIL_TYPES] entry reaches the rail's tool group; the rest are
         * the user's own and live in the rail's overflow (see [extraPresets]).
         */
        val DEFAULT_PENS = listOf(
            ToolbarPen("ball", Pen.BALLPEN, AndroidColor.BLACK, 5f),
            ToolbarPen("red", Pen.BALLPEN, AndroidColor.RED, 5f),
            ToolbarPen("blue", Pen.BALLPEN, AndroidColor.BLUE, 5f),
            ToolbarPen("green", Pen.BALLPEN, AndroidColor.GREEN, 5f),
            ToolbarPen("pencil", Pen.PENCIL, AndroidColor.BLACK, 5f),
            ToolbarPen("brush", Pen.BRUSH, AndroidColor.BLACK, 5f),
            ToolbarPen("fountain", Pen.FOUNTAIN, AndroidColor.BLACK, 5f),
            ToolbarPen("marker", Pen.MARKER, AndroidColor.LTGRAY, 40f),
        )

        /**
         * Fallback pen settings keyed by preset id, derived from [DEFAULT_PENS] — the
         * single source of truth. EditorViewModel.DEFAULT_PEN_SETTINGS aliases this;
         * persisted user presets always win.
         */
        val defaultPenSettings: Map<String, PenSetting> =
            DEFAULT_PENS.associate { it.id to it.setting() }

        /**
         * The four implements the rail writes with, in rail order: a plain pen, a fountain
         * pen, a pencil and a highlighter.
         *
         * Fixed, so a writing tool is always in the same place under the hand — the rail is
         * furniture, not a list. What each one *writes like* is still the user's to set; that
         * lives in the preset behind it, not in which buttons exist.
         */
        val RAIL_TYPES = listOf(Pen.BALLPEN, Pen.FOUNTAIN, Pen.PENCIL, Pen.MARKER)

        /**
         * The preset behind each rail implement: the user's first preset of that base type,
         * so the colours and sizes they configured are what the button writes with, and the
         * seed preset when they have none of that type left.
         */
        fun railPresets(pens: List<ToolbarPen>): List<ToolbarPen> = RAIL_TYPES.map { type ->
            pens.firstOrNull { it.pen == type } ?: DEFAULT_PENS.first { it.pen == type }
        }

        /**
         * Presets the four fixed implements do not already stand for — a second ballpen in
         * another colour, a brush, a calligraphy nib.
         *
         * They keep a button rather than becoming unreachable: the rail is fixed, but a pen
         * the user made is still theirs. It just sits in the overflow rather than in the
         * group your thumb finds without looking.
         */
        fun extraPresets(pens: List<ToolbarPen>): List<ToolbarPen> {
            val onRail = railPresets(pens).mapTo(mutableSetOf()) { it.id }
            return pens.filterNot { it.id in onRail }
        }
    }
}
