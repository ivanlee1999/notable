package com.ethran.notable.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Tokens for the colour e-ink build.
 *
 * A Kaleido 3 panel prints colour through a filter array that is coarser than the mono
 * layer, so colour is only ever spent on *large saturated fills* — a notebook spine, a
 * folder chip, the selected ink. Never a coloured hairline, never coloured type at
 * interface sizes. Structure is carried by [SectionRule] black rules and rows at least
 * [KaleidoMetrics.hit] tall, so a screen still reads if the colour layer washes out.
 *
 * There are no pale tints and no gradients: on a paper-white panel a light fill is
 * indistinguishable from the paper it sits on. Patterns do that work instead.
 */
object Kaleido {
    /** Ground and ink. */
    val Ink = Color(0xFF201E1D)
    val Paper = Color(0xFFF6F5F3)

    /** The writing surface, a shade lighter than the chrome around it. */
    val Canvas = Color(0xFFFBFAF8)

    /** Docked chrome: the tool rail and the editor's own title bar. */
    val Rail = Color(0xFFEEECE9)

    /** The saturated fills. Large areas only. */
    val Red = Color(0xFFAE1800)
    val Coral = Color(0xFFDD2B0F)

    /**
     * The grey Notable already draws every native paper template in
     * (`android.graphics.Color.GRAY`), so a generated cover and the page it stands for
     * are struck in the same tone.
     */
    val Grid = Color(0xFF888888)

    /** Neutral ramp steps 400/600/700 — row rules, box edges, secondary type. */
    val Rule = Color(0xFFBAB6B6)
    val Edge = Color(0xFF7D7979)
    val Muted = Color(0xFF605D5D)

    /** Between sections. Survives a washed-out colour layer; a hairline would not. */
    val SectionRule = 2.dp

    /** Within a section, between rows. */
    val RowRule = 1.dp

    /** Width of a notebook's coloured spine on a full-size cover. */
    val Spine = 12.dp

    val KickerSize: TextUnit = 10.sp

    /** .16em at [KickerSize] — the letterspacing every uppercase kicker carries. */
    val KickerTracking: TextUnit = 1.6.sp

    /**
     * Fills a notebook spine may take. Ordered so the two reds do not land next to each
     * other for consecutive hashes.
     */
    val SpineFills = listOf(Red, Ink, Coral, Grid)

    /**
     * A stable spine colour for a notebook. Derived from the id rather than stored, so it
     * survives sync and never needs a schema column; identical on every device showing the
     * same notebook.
     */
    fun spineFor(id: String): Color =
        SpineFills[Math.floorMod(id.hashCode(), SpineFills.size)]
}

/**
 * The size step a screen is drawn at. Two builds of the same model: a tablet (Tab Air 10C
 * and up) and a one-handed device (Palma-class), which drops to a single column, smaller
 * type and 44dp targets.
 */
@Immutable
data class KaleidoMetrics(
    val wide: Boolean,
    /** Minimum interactive edge. Nothing tappable is ever smaller. */
    val hit: Dp,
    val pad: Dp,
    val titleSize: TextUnit,
    val bodySize: TextUnit,
    /** Notebook covers per row. 1 means the list variant, not a one-wide grid. */
    val coverColumns: Int,
) {
    val narrow: Boolean get() = !wide
}

/** Below this a device is treated as one-handed. */
val KALEIDO_WIDE_BREAKPOINT = 600.dp

/**
 * How wide a cover wants to be — three-up across the reference tablet, a 10.3" panel about
 * 940dp across. What the design fixes is the *cover*, not the column count: on anything
 * wider (a bigger panel, or the same one in landscape) three columns would blow the covers
 * up rather than show more of the library, so columns are derived from this instead.
 */
private val COVER_WIDTH = 300.dp

fun kaleidoMetrics(width: Dp): KaleidoMetrics =
    if (width >= KALEIDO_WIDE_BREAKPOINT) KaleidoMetrics(
        wide = true,
        hit = 48.dp,
        pad = 20.dp,
        titleSize = 30.sp,
        bodySize = 14.sp,
        coverColumns = (width / COVER_WIDTH).toInt().coerceAtLeast(3),
    ) else KaleidoMetrics(
        wide = false,
        hit = 44.dp,
        pad = 14.dp,
        titleSize = 24.sp,
        bodySize = 13.sp,
        coverColumns = 1,
    )
