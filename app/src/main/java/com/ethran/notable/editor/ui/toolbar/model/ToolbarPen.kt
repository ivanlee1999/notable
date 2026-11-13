package com.ethran.notable.editor.ui.toolbar.model

import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.editor.utils.DeviceCompat
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
        val DEFAULT_COLOR_OPTIONS: List<Int> get() = inksForThisDevice()

        /**
         * Everything the settings editor offers for inclusion in [colorOptions] — the same
         * twelve. There is no thirteenth colour that belongs on this panel, and a pen may still
         * be narrowed to any subset of them.
         */
        val COLOR_CANDIDATES: List<Int> get() = inksForThisDevice()

        /**
         * The strip this panel should offer: the hues, or the grey ramp on a screen that cannot
         * print hues.
         *
         * The setting is tri-state and defaults to following the device, which already knows the
         * answer — `DeviceCompat.isColorDevice()` has been consulted for thumbnail encoding and
         * pen-up refresh delays all along, just never for the palette. A Kaleido owner who
         * prefers greys and a mono owner who wants the hues back can both say so.
         *
         * A `get()` rather than a `val` because the answer changes when the setting does, and
         * these are read afresh every time the strip is laid out.
         */
        fun inksForThisDevice(): List<Int> =
            when (GlobalAppSettings.current.greyscaleInks) {
                true -> Kaleido.GreyInks
                false -> Kaleido.Inks
                null -> if (DeviceCompat.isColorDevice()) Kaleido.Inks else Kaleido.GreyInks
            }

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

        /** Legacy order, retained until the user customizes favorites. */
        val RAIL_TYPES = listOf(Pen.BALLPEN, Pen.FOUNTAIN, Pen.PENCIL, Pen.MARKER)

        /** Resolve exact favorite IDs; older settings retain their original default tools. */
        fun railPresets(pens: List<ToolbarPen>, favoriteIds: List<String>? = null): List<ToolbarPen> =
            if (favoriteIds == null) RAIL_TYPES.map { type ->
                pens.firstOrNull { it.pen == type } ?: DEFAULT_PENS.first { it.pen == type }
            } else favoriteIds.distinct().mapNotNull { id -> pens.firstOrNull { it.id == id } }

        /** Unpinned presets remain available in More. */
        fun extraPresets(pens: List<ToolbarPen>, favoriteIds: List<String>? = null): List<ToolbarPen> {
            val onRail = railPresets(pens, favoriteIds).mapTo(mutableSetOf()) { it.id }
            return pens.filterNot { it.id in onRail }
        }

        fun moveFavorite(ids: List<String>, id: String, offset: Int): List<String> {
            val result = ids.distinct().toMutableList()
            val index = result.indexOf(id)
            if (index < 0) return result
            val destination = (index + offset).coerceIn(0, result.lastIndex)
            result.removeAt(index); result.add(destination, id)
            return result
        }
    }
}
