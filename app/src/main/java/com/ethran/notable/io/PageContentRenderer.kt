package com.ethran.notable.io

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.res.imageResource
import androidx.core.graphics.createBitmap
import com.ethran.notable.R
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.db.PageRepository
import com.ethran.notable.data.db.PageWithData
import com.ethran.notable.data.db.getBackgroundType
import com.ethran.notable.data.model.BackgroundType
import com.ethran.notable.data.model.BackgroundType.Native
import com.ethran.notable.data.model.PageSize
import com.ethran.notable.data.model.declaredPageSize
import com.ethran.notable.data.model.sheet
import com.ethran.notable.editor.drawing.drawBg
import com.ethran.notable.editor.drawing.drawImage
import com.ethran.notable.editor.drawing.StrokeRenderers
import com.ethran.notable.utils.ensureNotMainThread
import dagger.hilt.android.qualifiers.ApplicationContext
import io.shipbook.shipbooksdk.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

sealed class RenderTarget {
    object Full : RenderTarget()
    data class Thumbnail(val maxWidthPx: Int? = null, val maxHeightPx: Int? = null) : RenderTarget()
}

@Singleton
class PageContentRenderer @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val pageRepo: PageRepository,
    private val appRepository: AppRepository
) {

    suspend fun renderPageBitmap(pageId: String, target: RenderTarget): Bitmap {
        ensureNotMainThread("PageContentRenderer")
        val data = loadPageContent(pageId) ?: throw IllegalArgumentException("Page with id $pageId not found")

        return withContext(Dispatchers.Default) {
            val (contentWidth, contentHeight) = computeContentDimensions(data)
            val size = resolveRenderSize(contentWidth, contentHeight, target, data.page.sheet())

            Log.d("PageContentRenderer", "size: ${size.width}, ${size.height}, ${size.scale}")
            createBitmap(size.width, size.height).also { bitmap ->
                drawPage(
                    canvas = Canvas(bitmap),
                    data = data,
                    scroll = Offset.Zero,
                    scaleFactor = size.scale
                )
            }
        }
    }

    private data class RenderSize(
        val width: Int,
        val height: Int,
        val scale: Float
    )

    suspend fun loadPageContent(pageId: String): PageWithData? = withContext(Dispatchers.IO) {
        pageRepo.getWithDataById(pageId)
    }

    suspend fun resolveExportBackgroundType(data: PageWithData): BackgroundType {
        val pageNumber = withContext(Dispatchers.IO) {
            appRepository.getPageNumber(data.page.notebookId, data.page.id)
        }
        return data.page.getBackgroundType().resolveForExport(pageNumber)
    }

    suspend fun drawPage(
        canvas: Canvas,
        data: PageWithData,
        scroll: Offset,
        scaleFactor: Float
    ) {
        val resolvedBackgroundType = resolveExportBackgroundType(data)

        val pageNumber = if (resolvedBackgroundType is BackgroundType.Pdf) {
            resolvedBackgroundType.page
        } else {
            -1
        }

        val bgImage: Bitmap? = withContext(Dispatchers.IO) {
            when (resolvedBackgroundType) {
                BackgroundType.Image, BackgroundType.CoverImage, BackgroundType.AutoPdf,
                is BackgroundType.Pdf, BackgroundType.ImageRepeating -> {
                    if (resolvedBackgroundType is BackgroundType.Image && data.page.background == "iris") {
                        val resId = R.drawable.iris
                        ImageBitmap.imageResource(context.resources, resId).asAndroidBitmap()
                    } else {
                        loadBackgroundBitmap(data.page.background, pageNumber, scaleFactor)
                    }
                }

                Native -> null
            }
        }

        withContext(Dispatchers.Default) {
            canvas.scale(scaleFactor, scaleFactor)

            drawBg(
                canvas = canvas,
                backgroundType = resolvedBackgroundType,
                background = data.page.background,
                sheet = data.page.sheet(),
                scroll = scroll,
                resourceBitmap = bgImage,
                scale = scaleFactor,
                repeat = resolvedBackgroundType is BackgroundType.ImageRepeating,
            )

            data.images.forEach { drawImage(context, canvas, it, -scroll) }
            data.strokes.forEach { StrokeRenderers.current.drawStroke(canvas, it, -scroll) }
        }
    }

    // Returns (width, height)
    fun computeContentDimensions(data: PageWithData): Pair<Int, Int> {
        // The page's own sheet is the floor, not the screen: an export has to contain the page,
        // not whatever device happens to be running the export.
        val sheet = data.page.sheet()
        if (data.strokes.isEmpty() && data.images.isEmpty()) {
            return sheet.width to sheet.height
        }

        val strokeBottom = data.strokes.maxOfOrNull { it.bottom.toInt() } ?: 0
        val strokeRight = data.strokes.maxOfOrNull { it.right.toInt() } ?: 0
        val imageBottom = data.images.maxOfOrNull { it.y + it.height } ?: 0
        val imageRight = data.images.maxOfOrNull { it.x + it.width } ?: 0

        val rawHeight = maxOf(strokeBottom, imageBottom) +
                if (GlobalAppSettings.current.visualizePdfPagination) 0 else 50
        val rawWidth = maxOf(strokeRight, imageRight) + 50

        val height = rawHeight.coerceAtLeast(sheet.height)
        val width = rawWidth.coerceAtLeast(sheet.width)
        return width to height
    }

    private fun resolveRenderSize(
        contentWidth: Int,
        contentHeight: Int,
        target: RenderTarget,
        sheet: PageSize,
    ): RenderSize {
        return when (target) {
            RenderTarget.Full -> RenderSize(contentWidth, contentHeight, 1f)
            is RenderTarget.Thumbnail -> {
                // The sheet's aspect, so a thumbnail is page-shaped rather than device-shaped.
                val screenRatio = sheet.height.toFloat() / sheet.width.toFloat()

                val width: Int
                val height: Int
                val scale: Float

                if (target.maxWidthPx != null && target.maxHeightPx == null) {
                    val w = target.maxWidthPx.coerceAtLeast(1)
                    width = w
                    height = (w * screenRatio).toInt()
                    scale = w.toFloat() / contentWidth.toFloat()
                } else if (target.maxHeightPx != null && target.maxWidthPx == null) {
                    val h = target.maxHeightPx.coerceAtLeast(1)
                    height = h
                    width = (h / screenRatio).toInt()
                    scale = h.toFloat() / contentHeight.toFloat()
                } else if (target.maxWidthPx != null && target.maxHeightPx != null) {
                    val boundedWidth = target.maxWidthPx.coerceAtLeast(1)
                    val boundedHeight = target.maxHeightPx.coerceAtLeast(1)
                    scale = min(
                        1f,
                        min(
                            boundedWidth.toFloat() / contentWidth.toFloat(),
                            boundedHeight.toFloat() / contentHeight.toFloat()
                        )
                    )
                    width = (contentWidth * scale).toInt()
                    height = (contentHeight * scale).toInt()
                } else {
                    val w = com.ethran.notable.editor.utils.THUMBNAIL_WIDTH.coerceAtLeast(1)
                    width = w
                    height = (w * screenRatio).toInt()
                    scale = w.toFloat() / contentWidth.toFloat()
                }

                RenderSize(width.coerceAtLeast(1), height.coerceAtLeast(1), scale)
            }
        }
    }
}
