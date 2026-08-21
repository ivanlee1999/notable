package com.ethran.notable.editor.drawing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.model.BackgroundType
import com.ethran.notable.data.model.PageSize
import com.ethran.notable.editor.utils.scaleRect
import com.onyx.android.sdk.extension.copy
import io.shipbook.shipbooksdk.ShipBook
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

private val log = ShipBook.getLogger("BackgroundsLog")

const val padding = 0
const val lineHeight = 80
const val dotSize = 6f
const val hexVerticalCount = 26


// Default paint for lines, dots, etc
private val defaultPaint = Paint().apply {
    this.color = Color.GRAY
    this.strokeWidth = 1f
}

// For drawing Hexagons
private val defaultPaintStroke = defaultPaint.copy().apply { this.style = Paint.Style.STROKE }
private val marginPaint = Paint().apply {
    this.color = Color.MAGENTA
    this.strokeWidth = 2f
}
private val paginationLinePaint = Paint().apply {
    color = Color.RED
    strokeWidth = 4f
    pathEffect = DashPathEffect(floatArrayOf(10f, 5f), 0f)
}

fun drawLinedBg(canvas: Canvas, scroll: Offset, scale: Float) {
    val height = (canvas.height / scale).toInt()
    val width = (canvas.width / scale).toInt()

    // white bg
    canvas.drawColor(Color.WHITE)


    val offset = IntOffset(lineHeight, lineHeight) - IntOffset(
        scroll.x.toInt() % lineHeight, scroll.y.toInt() % lineHeight
    )

    for (y in 0..height step lineHeight) {
        canvas.drawLine(
            padding.toFloat(),
            y.toFloat() + offset.y,
            (width - padding).toFloat(),
            y.toFloat() + offset.y,
            defaultPaint
        )
    }
}

fun drawDottedBg(canvas: Canvas, scroll: Offset, scale: Float) {
    val height = (canvas.height / scale).toInt()
    val width = (canvas.width / scale).toInt()
    // white bg
    canvas.drawColor(Color.WHITE)


    // dots
    val offset = IntOffset(lineHeight, lineHeight) - IntOffset(
        scroll.x.toInt() % lineHeight, scroll.y.toInt() % lineHeight
    )

    for (y in 0..height step lineHeight) {
        for (x in padding..width - padding step lineHeight) {
            canvas.drawOval(
                x.toFloat() + offset.x - dotSize / 2,
                y.toFloat() + offset.y - dotSize / 2,
                x.toFloat() + offset.x + dotSize / 2,
                y.toFloat() + offset.y + dotSize / 2,
                defaultPaint
            )
        }
    }

}

fun drawSquaredBg(canvas: Canvas, scroll: Offset, scale: Float) {
    val height = (canvas.height / scale).toInt()
    val width = (canvas.width / scale).toInt()

    // white bg
    canvas.drawColor(Color.WHITE)

    // paint

    val offset = IntOffset(lineHeight, lineHeight) - IntOffset(
        scroll.x.toInt() % lineHeight, scroll.y.toInt() % lineHeight
    )

    for (y in 0..height step lineHeight) {
        canvas.drawLine(
            padding.toFloat(),
            y.toFloat() + offset.y,
            (width - padding).toFloat(),
            y.toFloat() + offset.y,
            defaultPaint
        )
    }

    for (x in padding..width - padding step lineHeight) {
        canvas.drawLine(
            x.toFloat() + offset.x,
            padding.toFloat(),
            x.toFloat() + offset.x,
            height.toFloat(),
            defaultPaint
        )
    }
}

fun drawHexedBg(canvas: Canvas, scroll: Offset, scale: Float) {
    val height = (canvas.height / scale)
    val width = (canvas.width / scale)

    // background
    canvas.drawColor(Color.WHITE)


    // https://www.redblobgames.com/grids/hexagons/#spacing
    val r = max(width, height) / (hexVerticalCount * 1.5f) * scale
    val hexHeight = r * 2
    val hexWidth = r * sqrt(3f)

    val rows = (height / hexVerticalCount).toInt()
    val cols = (width / hexWidth).toInt() + 1

    for (row in 0..rows) {
        val offsetX = if (row % 2 == 0) 0f else hexWidth / 2
        for (col in 0..cols) {
            val x = col * hexWidth + offsetX - scroll.x.mod(hexWidth) - hexWidth
            val y = row * hexHeight * 0.75f - scroll.y.mod(hexHeight * 1.5f)
            drawHexagon(canvas, x, y, r)
        }
    }
}

fun drawHexagon(canvas: Canvas, centerX: Float, centerY: Float, r: Float) {
    val path = Path()
    for (i in 0..5) {
        val angle = Math.toRadians((30 + 60 * i).toDouble())
        val x = (centerX + r * cos(angle)).toFloat()
        val y = (centerY + r * sin(angle)).toFloat()
        if (i == 0) {
            path.moveTo(x, y)
        } else {
            path.lineTo(x, y)
        }
    }
    path.close()
    canvas.drawPath(path, defaultPaintStroke)
}


fun drawTitleBox(canvas: Canvas, sheet: PageSize) {

    // Draw label-like area in center
    val paint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    val borderPaint = Paint().apply {
        color = Color.DKGRAY
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }

    // The sheet, not the screen: a cover's label has to land in the same place on both devices.
    val canvasHeight = sheet.height
    val canvasWidth = sheet.width

    // Dimensions for the label box
    val labelWidth = canvasWidth * 0.8f
    val labelHeight = canvasHeight * 0.25f
    val left = (canvasWidth - labelWidth) / 2
    val top = (canvasHeight - labelHeight) / 2
    val right = left + labelWidth
    val bottom = top + labelHeight

    val rectF = RectF(left, top, right, bottom)
    val cornerRadius = 64f

    canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, paint)
    canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, borderPaint)
}


// Backgrounds are rendered oversampled (see loadBackgroundBitmap's resolutionModifier) and drawn
// downscaled onto the screen canvas. Bilinear filtering + dithering makes that downscale crisp
// instead of aliased, and the dither maps grey edges cleanly onto e-ink. Costs no extra memory.
private val bgBitmapPaint = Paint().apply {
    isFilterBitmap = true
    isAntiAlias = true
    isDither = true
}

fun drawBitmapToCanvas(
    canvas: Canvas, imageBitmap: Bitmap, scroll: Offset, scale: Float, repeat: Boolean,
    sheet: PageSize
) {
    canvas.drawColor(Color.WHITE)
    val imageWidth = imageBitmap.width
    val imageHeight = imageBitmap.height


//    val canvasWidth = canvas.width
    val canvasHeight = canvas.height
    // The background is fitted to the *sheet*, so a PDF or image page covers the same area of the
    // page on every device instead of being stretched to whatever screen is showing it.
    val widthOnCanvas = sheet.width

    val scaleFactor = widthOnCanvas.toFloat() / imageWidth
    val scaledHeight = (imageHeight * scaleFactor).toInt()

    // Where in the bitmap the visible part starts. For a repeating background the row wraps and
    // the loop below keeps tiling; for a plain one, null means the scroll is past the last row
    // and nothing is drawn — the white page ground above stands. The old code wrapped
    // unconditionally (modulo for both kinds) behind a guard that compared page units to bitmap
    // pixels, so a scrolled non-repeating background started over from its top.
    val srcTopY = BackgroundGeometry.sourceTopPx(scroll.y, scaleFactor, imageHeight, repeat)

    var filledHeight = 0
    if (srcTopY != null) {
        val rectOnImage = Rect(0, srcTopY.toInt(), imageWidth, imageHeight)
        val rectOnCanvas = Rect(
            -scroll.x.toInt(),
            0,
            widthOnCanvas - scroll.x.toInt(),
            ((imageHeight - srcTopY) * scaleFactor).toInt()
        )
        canvas.drawBitmap(imageBitmap, rectOnImage, rectOnCanvas, bgBitmapPaint)
        filledHeight = rectOnCanvas.bottom
    }
    // TODO: Should we also repeat horizontally?

    if (repeat) {
        var currentTop = filledHeight
        val srcRect = Rect(0, 0, imageWidth, imageHeight)
        while (currentTop < canvasHeight / scale) {

            val dstRect = Rect(
                -scroll.x.toInt(),
                currentTop,
                widthOnCanvas - scroll.x.toInt(),
                currentTop + scaledHeight
            )
            canvas.drawBitmap(imageBitmap, srcRect, dstRect, bgBitmapPaint)
            currentTop += scaledHeight
        }
    }
}

/**
 * @param sheet the page's own sheet, in page units — what the margin, the pagination lines and a
 *   fitted background image are measured against. It is the page that decides how wide a page is,
 *   never the screen: a screen-derived width is a different page on every device, so ink written
 *   past a narrower device's edge would sit outside the page there.
 */
fun drawBg(
    canvas: Canvas,
    backgroundType: BackgroundType,
    background: String,
    sheet: PageSize,
    scroll: Offset = Offset.Zero,
    resourceBitmap: Bitmap?,
    scale: Float = 1f,          // When exporting, we change scale of canvas. therefore canvas.width/height is scaled
    repeat: Boolean = false,    // for repeating image
    showPagination: Boolean = true,
    clipRect: Rect? = null,     // before the scaling
) {

    log.v("Loading the background")
    clipRect?.let {
        canvas.save()
        canvas.clipRect(scaleRect(it, scale))
    }
    when (backgroundType) {
        // draw native background for it we don't need a resource
        is BackgroundType.Native -> {
            when (background) {
                "blank" -> canvas.drawColor(Color.WHITE)
                "dotted" -> drawDottedBg(canvas, scroll, scale)
                "lined" -> drawLinedBg(canvas, scroll, scale)
                "squared" -> drawSquaredBg(canvas, scroll, scale)
                "hexed" -> drawHexedBg(canvas, scroll, scale)
                else -> {
                    // Reaching the Native branch with an unknown value usually means the *type*
                    // detection misclassified a non-native background (e.g. a .pdf) as Native, not
                    // that the string is merely unknown — surface the type so it's obvious.
                    throw IllegalArgumentException(
                        "Unknown native background '$background' (type=$backgroundType)"
                    )
                }
            }
        }

        else -> {
            if (resourceBitmap != null) {
                drawBitmapToCanvas(canvas, resourceBitmap, scroll, scale, repeat, sheet)
                if (backgroundType is BackgroundType.CoverImage) {
                    drawTitleBox(canvas, sheet)
                }
            } else {
                log.i("No resource provided to draw, maybe out of pages in pdf?")
                canvas.drawColor(Color.WHITE)
            }
        }
    }
    drawMargin(canvas, scroll, scale, sheet)

    // Only where a break will actually fall: with pagination off the export is one continuous
    // page, and a line promising a break that never comes is worse than no line.
    val settings = GlobalAppSettings.current
    if (showPagination && settings.paginatePdf && settings.visualizePdfPagination) {
        drawPaginationLine(canvas, scroll, scale, sheet)
    }
    if (clipRect != null) {
        canvas.restore()
    }
}

/**
 * Marks the right edge of the sheet, so it is obvious when writing has left the page.
 *
 * It used to mark "what will still be visible in portrait", because the page *was* the screen and
 * there was no page edge to draw. Now there is one, and it is the same edge on every device — which
 * is the point: ink to the right of this line is off-page for the iPad too, rather than lost.
 *
 * A page that declares a size is bounded to its sheet (see `PageView.maxHorizontalScroll`), so the
 * edge sits *on* the right edge of the view and there is nothing beyond it to warn about — hence
 * the `>=`, which drops the line rather than painting it down the screen's own border. It still
 * draws where off-page space is genuinely visible: an undeclared page panned past the screen's
 * width, and any render wider than the sheet.
 */
fun drawMargin(canvas: Canvas, scroll: Offset, scale: Float, sheet: PageSize) {
    val margin = sheet.width - scroll.x
    if (margin < 0 || margin >= canvas.width / scale) return
    canvas.drawLine(
        margin, padding.toFloat(), margin, (canvas.height / scale - padding), marginPaint
    )
}

fun drawPaginationLine(canvas: Canvas, scroll: Offset, scale: Float, sheet: PageSize) {
    val textPaint = Paint().apply {
        color = Color.BLACK
        textSize = 24f
        isAntiAlias = true
    }

    // The sheet's own height, rather than the screen width times an assumed A4 ratio: subpage
    // breaks have to fall in the same place on both devices, and on a Letter page they are not
    // A4-shaped at all.
    val sheetWidth = sheet.width
    val pageHeight = sheet.height.toFloat()
    // A sheet with no height has no breaks to mark, and the walk below advances by exactly this
    // much: at zero it never reaches the bottom of the canvas and draws page labels forever. A
    // page falls back to the screen's dimensions when it declares no size of its own
    // (legacyScreenSheet), and those are zero wherever the EPD controller reports nothing — a
    // non-Onyx device, and any test process.
    if (pageHeight <= 0f) return

    // Convert scroll position to canvas coordinates
    // Calculate current page number (1-based)
    val currentPage = floor(scroll.y / pageHeight).toInt() + 1

    // Calculate position of first page break
    var yPos = (currentPage * pageHeight) - scroll.y

    var pageNum = currentPage
    while (yPos < canvas.height / scale) {
        if (yPos >= 0) { // Only draw visible lines
            val yPosScaled = yPos
            canvas.drawLine(
                0f, yPosScaled, sheetWidth.toFloat(), yPosScaled, paginationLinePaint
            )

            // Draw page number label (offset slightly below the line)
            canvas.drawText(
                "Subpage ${pageNum + 1}", 20f - scroll.x, yPosScaled + 30f, textPaint
            )
        } else {
            log.d("Skipping line at $yPos (above visible area)")
        }
        yPos += pageHeight
        pageNum++
    }
}
