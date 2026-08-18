package com.ethran.notable.editor.drawing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The background source-row rule: a repeating background wraps, a plain one is paper and simply
 * ends. The old math applied the modulo to both — scroll any image-backed page far enough and the
 * image started over — guarded only by a comparison of page units against bitmap pixels.
 */
class BackgroundGeometryTest {

    // A 1000px-tall bitmap fitted to a sheet at 0.5 page units per bitmap pixel: the background
    // covers page rows 0..500.
    private val imageHeight = 1000
    private val scaleFactor = 0.5f

    @Test
    fun `within the image, the source row is just the scaled scroll`() {
        assertEquals(
            240f,
            BackgroundGeometry.sourceTopPx(120f, scaleFactor, imageHeight, repeat = false),
        )
        assertEquals(
            240f,
            BackgroundGeometry.sourceTopPx(120f, scaleFactor, imageHeight, repeat = true),
        )
    }

    @Test
    fun `a non-repeating background ends at its last row instead of wrapping`() {
        // scroll 600 page units = bitmap row 1200, past the 1000px image: nothing left to draw.
        assertNull(BackgroundGeometry.sourceTopPx(600f, scaleFactor, imageHeight, repeat = false))
    }

    @Test
    fun `the very last row still draws, the first row past it does not`() {
        assertEquals(
            999.9f,
            BackgroundGeometry.sourceTopPx(499.95f, scaleFactor, imageHeight, repeat = false)!!,
            0.01f,
        )
        assertNull(BackgroundGeometry.sourceTopPx(500f, scaleFactor, imageHeight, repeat = false))
    }

    @Test
    fun `a repeating background wraps modulo its height`() {
        // Bitmap row 1200 wraps to row 200 of the next tile.
        assertEquals(
            200f,
            BackgroundGeometry.sourceTopPx(600f, scaleFactor, imageHeight, repeat = true),
        )
    }

    @Test
    fun `a degenerate bitmap or scale draws nothing rather than dividing by zero`() {
        assertNull(BackgroundGeometry.sourceTopPx(100f, 0f, imageHeight, repeat = true))
        assertNull(BackgroundGeometry.sourceTopPx(100f, scaleFactor, 0, repeat = true))
    }
}
