package com.ethran.notable.io

import com.ethran.notable.data.model.PageSizePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bound that stops an export allocating a bitmap large enough to kill the process.
 *
 * computeContentDimensions has no upper limit — it unions the sheet with the real extent of
 * every stroke and image — so a legacy pre-PageSplit page, or one carrying a stray far-off
 * stroke, could ask for any allocation at all.
 */
class ExportRenderScaleTest {

    @Test
    fun `an ordinary sheet is never scaled`() {
        for (preset in PageSizePreset.entries) {
            val size = preset.size
            assertEquals(
                "${preset.key} should export at full size",
                1f,
                exportRenderScale(size.width, size.height),
                0f,
            )
        }
    }

    @Test
    fun `a page at the bound is left alone`() {
        assertEquals(1f, exportRenderScale(MAX_EXPORT_EDGE_PX, 1000), 0f)
    }

    @Test
    fun `a runaway page is scaled to the bound`() {
        val scale = exportRenderScale(2_000, 40_000)
        assertEquals(MAX_EXPORT_EDGE_PX.toFloat() / 40_000f, scale, 1e-6f)
        // Both edges shrink together, so the page keeps its shape.
        assertEquals(MAX_EXPORT_EDGE_PX, (40_000 * scale).toInt())
        assertEquals(200, (2_000 * scale).toInt())
    }

    @Test
    fun `the bound applies to the wide edge too`() {
        val scale = exportRenderScale(12_000, 900)
        assertTrue(scale < 1f)
        assertEquals(MAX_EXPORT_EDGE_PX, (12_000 * scale).toInt())
    }
}
