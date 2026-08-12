package com.ethran.notable.data.model

import com.ethran.notable.SCREEN_HEIGHT
import com.ethran.notable.SCREEN_WIDTH
import com.ethran.notable.data.db.Page
import kotlin.math.max
import kotlin.math.min

/**
 * The coordinate space a page's strokes, images and background all live in.
 *
 * One unit is exactly 0.15 mm (~169.3 dpi). That constant is not arbitrary:
 *
 * - A4 lands on 1400 x 1980 units, next to the 1404 the iPad app hardcoded as "the page width"
 *   before page sizes existed, and close to the portrait width of the tablets this runs on — so a
 *   page written before this existed does not have to move far to become an A4 page.
 * - The ruled-line spacing of 80 units (`lineHeight` in `backgrounds.kt`) reads as 12 mm.
 * - [POINTS_PER_UNIT] converts to PostScript points, so A4 exports as 595.3 x 841.9 pt — exactly
 *   the [A4_WIDTH] x [A4_HEIGHT] box the PDF and .xopp exporters already target.
 *
 * A unit is deliberately **not** a device pixel. A page whose width is the screen's width — which
 * is what this app used everywhere — is a *different page on every device*: ink written past the
 * narrower device's edge is off-page there. That is the bug page sizes exist to remove. The page
 * is declared by the document; each device scales to fit it.
 *
 * Mirrors `NotableKit/Sources/NotableKit/PageSize.swift` in the bopa repo. The normative table
 * lives in that repo's `docs/notable-sync-protocol.md` §3.1.
 */
object PageUnits {
    /** Millimetres per page unit — the definition everything else derives from. */
    const val MILLIMETRES_PER_UNIT = 0.15

    /** PostScript points (1/72 in) per page unit, for PDF and .xopp export. */
    const val POINTS_PER_UNIT = MILLIMETRES_PER_UNIT / 25.4 * 72

    fun pointsFromUnits(units: Float): Float = (units * POINTS_PER_UNIT).toFloat()

    fun millimetresFromUnits(units: Int): Double = units * MILLIMETRES_PER_UNIT
}

/**
 * A page's sheet size, in page units.
 *
 * Portrait by convention (`width <= height`) and stored that way: turning the tablet sideways
 * changes how much of the sheet is on screen, not how big the sheet is. "Landscape" is a fit, not
 * a size, which is why there is no orientation field.
 *
 * [height] is the *sheet* height, not a limit on writing — the canvas still scrolls past the
 * bottom of the sheet as it always has. It is what pagination and export divide by.
 */
data class PageSize(val width: Int, val height: Int) {
    val widthInPoints: Float get() = PageUnits.pointsFromUnits(width.toFloat())
    val heightInPoints: Float get() = PageUnits.pointsFromUnits(height.toFloat())

    companion object {
        /**
         * What the iPad app lays out for a page that declares no size. Named here only so the two
         * fallbacks are documented in one place: *this* app's fallback for an undeclared page is
         * still its own screen width, which is exactly why the two disagree about notebooks
         * predating this field and agree about everything created after it. Nothing retrofits a
         * size onto an old notebook.
         */
        val LEGACY_UNDECLARED_ON_IPAD = PageSize(1404, 1872)

        /** Pairs two nullable dimensions back into a size, or null if either is missing. */
        fun of(width: Int?, height: Int?): PageSize? =
            if (width != null && height != null && width > 0 && height > 0) {
                PageSize(width, height)
            } else {
                null
            }
    }
}

/**
 * A named paper size offered when a notebook is created.
 *
 * The unit dimensions are written out rather than computed from [millimetresWidth]/
 * [millimetresHeight], because the same table exists in Swift: a rounding rule that differs by a
 * single unit between the two languages would put the apps back to disagreeing about the page.
 */
enum class PageSizePreset(
    /** Stable key, lowercase. Never localized — it is what a peer would match on. */
    val key: String,
    val displayName: String,
    val millimetresWidth: Double,
    val millimetresHeight: Double,
    val size: PageSize,
) {
    A4("a4", "A4", 210.0, 297.0, PageSize(1400, 1980)),
    A5("a5", "A5", 148.0, 210.0, PageSize(987, 1400)),
    A3("a3", "A3", 297.0, 420.0, PageSize(1980, 2800)),
    LETTER("letter", "Letter", 215.9, 279.4, PageSize(1439, 1863)),
    LEGAL("legal", "Legal", 215.9, 355.6, PageSize(1439, 2371));

    /** e.g. "A4 · 210×297 mm", for the settings picker. */
    val label: String
        get() = "$displayName · ${millimetresWidth.toInt()}×${millimetresHeight.toInt()} mm"

    companion object {
        /**
         * What a new notebook gets when nobody chooses. A4 because it is what this app's export
         * path already assumes ([A4_WIDTH]/[A4_HEIGHT]).
         */
        val DEFAULT = A4

        /** The preset with these exact dimensions, or null for a size no preset here names. */
        fun matching(size: PageSize?): PageSizePreset? = entries.firstOrNull { it.size == size }

        /** How a size reads in the UI: the preset's name, or the millimetres it works out to. */
        fun label(size: PageSize): String = matching(size)?.displayName ?: run {
            val width = Math.round(PageUnits.millimetresFromUnits(size.width))
            val height = Math.round(PageUnits.millimetresFromUnits(size.height))
            "${width}×${height} mm"
        }
    }
}

/**
 * The sheet a page is laid out on: its own declaration, or this device's screen for a page created
 * before page sizes existed.
 *
 * The screen fallback lives here, in one place, because it is the *only* place the screen is
 * allowed to decide how big a page is. The ink on those pages was written against the screen, so
 * moving the sheet under it would move every stroke relative to the paper — which is why nothing
 * retrofits a real paper size onto them.
 */
fun Page.sheet(): PageSize = declaredPageSize() ?: legacyScreenSheet()

/** The sheet this page declares, or null for one written before page sizes existed. */
fun Page.declaredPageSize(): PageSize? = PageSize.of(pageWidth, pageHeight)

/** Portrait dimensions of this device's screen — the pre-page-size notion of "a page". */
fun legacyScreenSheet(): PageSize = PageSize(
    width = min(SCREEN_WIDTH, SCREEN_HEIGHT),
    height = max(SCREEN_WIDTH, SCREEN_HEIGHT)
)
