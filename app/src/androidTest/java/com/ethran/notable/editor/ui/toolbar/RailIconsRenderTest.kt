package com.ethran.notable.editor.ui.toolbar

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.content.res.ResourcesCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ethran.notable.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every icon the rail draws must actually put ink on the canvas.
 *
 * A vector whose `pathData` the parser cannot read does not fail the build and does not throw —
 * it inflates to a drawable that paints nothing, and the rail shows a blank cell. Nothing else
 * in the suite would notice, because a blank cell still has bounds, still has a content
 * description and still takes a tap.
 */
@RunWith(AndroidJUnit4::class)
class RailIconsRenderTest {

    private val railIcons = listOf(
        "ballpen" to R.drawable.ballpen,
        "fountain" to R.drawable.fountain,
        "pencil" to R.drawable.pencil,
        "marker" to R.drawable.marker,
        "eraser" to R.drawable.eraser,
        "eraser_select" to R.drawable.eraser_select,
        "lasso" to R.drawable.lasso,
    )

    @Test
    fun every_rail_icon_draws_something() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        for ((name, resId) in railIcons) {
            val drawable = ResourcesCompat.getDrawable(context.resources, resId, null)
            requireNotNull(drawable) { "$name did not inflate" }

            val size = 96
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(Canvas(bitmap))

            val painted = IntArray(size * size)
                .also { bitmap.getPixels(it, 0, size, 0, 0, size, size) }
                .count { it != 0 }
            bitmap.recycle()

            // A stroked 24dp glyph blown up to 96px covers a few hundred pixels at least; the
            // failure being guarded against paints exactly zero.
            assertTrue("$name drew nothing — its pathData did not parse", painted > 200)
        }
    }
}
