package com.ethran.notable.editor.ui.toolbar.model

import com.ethran.notable.editor.utils.Pen
import com.ethran.notable.editor.utils.PenSetting
import com.ethran.notable.ui.theme.Kaleido
import kotlinx.serialization.Serializable
import java.util.UUID

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

    companion object {
        /**
         * The inks a pen offers unless the user has narrowed them: the whole [Kaleido.Inks]
         * palette, which is what the rail lays out down its foot.
         *
         * It used to be the compose primaries — pure CYAN, MAGENTA and YELLOW among them — which
         * are the colours a Kaleido panel prints worst: at writing weight they come back as pale
         * grey. The twelve here are all deep enough to read as handwriting, and they are the same
         * twelve, in the same order, as the iPad app's.
         */
        val DEFAULT_COLOR_OPTIONS: List<Int> = Kaleido.Inks

        /**
         * Everything the settings editor offers for inclusion in [colorOptions] — the same
         * twelve. There is no thirteenth colour that belongs on this panel, and a pen may still
         * be narrowed to any subset of them.
         */
        val COLOR_CANDIDATES: List<Int> = Kaleido.Inks

        /**
         * The seed presets' own colours, by name. Taken from [Kaleido.Inks] rather than the
         * compose primaries the seeds used to carry, so a fresh install's red pen writes the
         * palette's red and not a pure #FF0000 the panel cannot print.
         */
        private val INK = Kaleido.Inks[0]
        private val RED = Kaleido.Inks[1]
        private val GREY = Kaleido.Inks[3]
        private val GREEN = Kaleido.Inks[6]
        private val BLUE = Kaleido.Inks[8]

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
            ToolbarPen("ball", Pen.BALLPEN, INK, Pen.BALLPEN.baseWidth),
            ToolbarPen("red", Pen.BALLPEN, RED, Pen.BALLPEN.baseWidth),
            ToolbarPen("blue", Pen.BALLPEN, BLUE, Pen.BALLPEN.baseWidth),
            ToolbarPen("green", Pen.BALLPEN, GREEN, Pen.BALLPEN.baseWidth),
            ToolbarPen("pencil", Pen.PENCIL, INK, Pen.PENCIL.baseWidth),
            ToolbarPen("brush", Pen.BRUSH, INK, Pen.BRUSH.baseWidth),
            ToolbarPen("fountain", Pen.FOUNTAIN, INK, Pen.FOUNTAIN.baseWidth),
            ToolbarPen("marker", Pen.MARKER, GREY, Pen.MARKER.baseWidth),
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
