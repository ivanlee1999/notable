package com.ethran.notable.data.model

import com.ethran.notable.data.db.Page
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

/**
 * The page-size contract, pinned by value.
 *
 * This table exists twice — here and in `NotableKit/Sources/NotableKit/PageSize.swift` in the bopa
 * repo, whose `PageSizeTests` asserts the same numbers. A dimension that drifts by one unit between
 * the two puts the apps back to laying out different pages, which is the whole bug page sizes were
 * added to fix, so both suites check the literal values rather than recomputing them.
 *
 * Nothing here touches [legacyScreenSheet], which reads the screen globals and would drag the Onyx
 * SDK into a plain JVM test.
 */
class PageSizesTest {

    private fun page(width: Int? = null, height: Int? = null) = Page(
        id = "p-1",
        notebookId = "nb-1",
        pageWidth = width,
        pageHeight = height,
        createdAt = Date(1_700_000_000_000),
        updatedAt = Date(1_700_000_000_000),
    )

    // --- The unit ---

    @Test
    fun a_unit_is_0_15_mm() {
        assertEquals(0.15, PageUnits.MILLIMETRES_PER_UNIT, 1e-12)
        assertEquals(0.4251968, PageUnits.POINTS_PER_UNIT, 1e-6)
    }

    /** The reason for that unit: A4 in page units is A4 in points, so export needs no fudge. */
    @Test
    fun a4_converts_to_the_standard_pdf_page_box() {
        assertEquals(595.276f, PageSizePreset.A4.size.widthInPoints, 0.05f)
        assertEquals(841.89f, PageSizePreset.A4.size.heightInPoints, 0.5f)
    }

    /**
     * The way back, which is how an imported PDF gets a sheet. A4 has to land exactly on the A4 a
     * user would have picked, or a document and a chosen paper size of the same dimensions would be
     * two different sheets — and pages of each would not line up.
     */
    @Test
    fun a_pdf_page_measured_in_points_lands_on_the_paper_size_it_is() {
        assertEquals(PageSizePreset.A4.size, measured(595.276f, 841.89f))
        assertEquals(PageSizePreset.A5.size, measured(419.528f, 595.276f))
        // US Letter, which a great many scanned PDFs are.
        assertEquals(PageSizePreset.LETTER.size, measured(612f, 792f))
    }

    /** A landscape page is stored as it is: a document's geometry is not up for interpretation. */
    @Test
    fun a_landscape_pdf_page_keeps_its_shape() {
        assertEquals(PageSize(1980, 1400), measured(841.89f, 595.276f))
    }

    private fun measured(widthPt: Float, heightPt: Float) = PageSize(
        PageUnits.unitsFromPoints(widthPt),
        PageUnits.unitsFromPoints(heightPt),
    )

    // --- The preset table ---

    @Test
    fun preset_dimensions_are_the_agreed_values() {
        assertEquals(PageSize(1980, 2800), PageSizePreset.A3.size)
        assertEquals(PageSize(1400, 1980), PageSizePreset.A4.size)
        assertEquals(PageSize(987, 1400), PageSizePreset.A5.size)
        assertEquals(PageSize(1439, 1863), PageSizePreset.LETTER.size)
        assertEquals(PageSize(1439, 2371), PageSizePreset.LEGAL.size)
    }

    /** Each preset's units are its millimetres at 0.15 mm each. */
    @Test
    fun presets_match_their_millimetre_sizes() {
        for (preset in PageSizePreset.entries) {
            assertEquals(
                preset.name,
                preset.millimetresWidth / PageUnits.MILLIMETRES_PER_UNIT,
                preset.size.width.toDouble(),
                0.5
            )
            assertEquals(
                preset.name,
                preset.millimetresHeight / PageUnits.MILLIMETRES_PER_UNIT,
                preset.size.height.toDouble(),
                0.5
            )
        }
    }

    /** Portrait is the storage convention: "landscape" is a fit, not a size. */
    @Test
    fun every_preset_is_stored_portrait() {
        for (preset in PageSizePreset.entries) {
            assertTrue(preset.name, preset.size.width <= preset.size.height)
        }
    }

    @Test
    fun preset_keys_are_stable_lowercase_and_unique() {
        val keys = PageSizePreset.entries.map { it.key }
        assertEquals(keys, keys.map { it.lowercase() })
        assertEquals(keys.size, keys.toSet().size)
        assertEquals("a4", PageSizePreset.DEFAULT.key)
    }

    @Test
    fun a_size_is_recognised_by_its_dimensions_alone() {
        assertEquals(PageSizePreset.A4, PageSizePreset.matching(PageSize(1400, 1980)))
        assertNull(PageSizePreset.matching(PageSize(1401, 1980)))
    }

    /**
     * Letter is 215.9 mm wide, so truncating calls it 215 while the same sheet, arrived at from
     * its units, rounds to 216. One sheet, one number.
     */
    @Test
    fun a_preset_is_measured_in_rounded_millimetres() {
        assertEquals("216×279 mm", PageSizePreset.LETTER.millimetreLabel)
        assertEquals("Letter · 216×279 mm", PageSizePreset.LETTER.label)
        assertEquals("210×297 mm", PageSizePreset.A4.millimetreLabel)
    }

    /** A size from a build with more presets than this one still reads as something. */
    @Test
    fun an_unknown_size_is_labelled_in_millimetres() {
        assertEquals("A4", PageSizePreset.label(PageSizePreset.A4.size))
        assertEquals("300×600 mm", PageSizePreset.label(PageSize(2000, 4000)))
    }

    // --- Reading a size off a page ---

    @Test
    fun a_page_that_declares_nothing_has_no_declared_size() {
        assertNull(page().declaredPageSize())
    }

    /**
     * Half a declaration is not a sheet: guessing the other half from an aspect ratio is exactly
     * how the two apps would end up laying out different pages again.
     */
    @Test
    fun half_a_declaration_is_not_a_sheet() {
        assertNull(page(width = 1400).declaredPageSize())
        assertNull(page(height = 1980).declaredPageSize())
    }

    /** A peer writing 0 for "unset" must not produce a page nothing can lay out. */
    @Test
    fun non_positive_dimensions_are_not_a_sheet() {
        assertNull(PageSize.of(0, 1980))
        assertNull(PageSize.of(1400, -5))
    }

    @Test
    fun a_declared_page_is_laid_out_on_what_it_declares() {
        val declared = page(width = 1400, height = 1980)
        assertEquals(PageSizePreset.A4.size, declared.declaredPageSize())
        assertEquals(PageSizePreset.A4.size, declared.sheet())
    }
}
