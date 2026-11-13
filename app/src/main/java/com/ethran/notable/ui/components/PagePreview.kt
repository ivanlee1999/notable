package com.ethran.notable.ui.components

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp
import com.ethran.notable.editor.text.FlowText
import com.ethran.notable.editor.text.FlowMarkdownView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import coil.request.CachePolicy
import com.ethran.notable.editor.utils.getContentPreviewFile
import com.ethran.notable.io.ThumbnailGeneratorEntryPoint
import dagger.hilt.EntryPoints
import kotlinx.coroutines.flow.filter
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File

/** The canonical file is already a cache; a same-clock merge must not reuse older Coil bytes. */
internal fun contentPreviewRequest(context: Context, file: File, refresh: Long): ImageRequest =
    ImageRequest.Builder(context).data(file)
        .memoryCachePolicy(CachePolicy.DISABLED).diskCachePolicy(CachePolicy.DISABLED)
        .memoryCacheKey("${file.absolutePath}:${file.lastModified()}:$refresh")
        .diskCacheKey("${file.absolutePath}:${file.lastModified()}:$refresh")
        .build()

/**
 * Renders a preview image for a page.
 *
 * Automatically listens to [ThumbnailGenerator] updates to refresh the image
 * when a new thumbnail is generated.
 */
@Composable
fun PagePreview(
    modifier: Modifier = Modifier,
    pageId: String,
    /**
     * Called once per mount to have a thumbnail rendered if the stored one no longer matches the
     * page. Not "the file is missing": a notebook whose cover was first drawn while it was still
     * empty *has* a thumbnail, and it is the one that needs replacing.
     */
    onPreviewNeeded: ((String) -> Unit)? = null,
    /**
     * What shows through until the thumbnail loads. Transparent lets a caller draw its own
     * placeholder underneath — the notebook cover paints the book's paper template there.
     */
    background: Color = com.ethran.notable.ui.theme.Kaleido.Canvas,
    version: Long = 0L,
    contentScale: ContentScale = ContentScale.Fit,
    alignment: Alignment = Alignment.Center,
    expandToContent: Boolean = false,
) {
    val isPreview = LocalInspectionMode.current
    val context = LocalContext.current

    if (isPreview) {
        Box(modifier = modifier.then(if (expandToContent) Modifier.aspectRatio(.75f) else Modifier).background(background))
        return
    }

    // Get the generator via EntryPoint since this is a stateless Composable
    val entryPoint = remember(context) {
        EntryPoints.get(
            context.applicationContext, ThumbnailGeneratorEntryPoint::class.java
        )
    }
    val thumbnailGenerator = entryPoint.thumbnailGenerator()
    val pageClock = remember(pageId, entryPoint) { entryPoint.pageRepository().observeUpdatedAt(pageId) }
    val pageVersion by pageClock.collectAsStateWithLifecycle(initialValue = null)
    val blockFlow = remember(pageId, entryPoint) { entryPoint.blockRepository().observeByPage(pageId) }
    val blocks by blockFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val canvasFlow = remember(pageId, entryPoint) { entryPoint.pageRepository().observeCanvasContent(pageId) }
    val canvasContent by canvasFlow.collectAsStateWithLifecycle(initialValue = true)
    val flow = FlowText.ordered(blocks).filter { it.kind in setOf("md", "link", "image", "audio") }
    val containsCanvas = canvasContent || blocks.any { it.x != null && it.y != null }

    // Key to force Coil to reload the image when the thumbnail changes
    var refreshTrigger by remember(pageId) { mutableLongStateOf(0L) }

    val imgFile = remember(pageId) {
        getContentPreviewFile(context, pageId)
    }

    // Listen for updates specifically for this pageId
    LaunchedEffect(pageId) {
        thumbnailGenerator.thumbnailUpdated.filter { it == pageId }.collect {
            refreshTrigger++
        }
    }

    // Declared after the collector above, so the subscription is in place before a render this
    // asks for can finish and report itself. Observe this page's clock, not its notebook's newest
    // page: an older page can change without moving that maximum. Do not key on refreshTrigger,
    // which would let generation ask for itself again when a peer's clock is in the future.
    LaunchedEffect(pageId, version, pageVersion, containsCanvas, flow.isNotEmpty()) {
        if (pageVersion == null) return@LaunchedEffect
        if (flow.isNotEmpty() && !containsCanvas) return@LaunchedEffect
        if (onPreviewNeeded != null) onPreviewNeeded(pageId)
        else entryPoint.thumbnailBackfillQueue().enqueue(listOf(pageId))
    }

    val painter = rememberAsyncImagePainter(
        model = contentPreviewRequest(context, imgFile, refreshTrigger)
    )

    if (flow.isNotEmpty()) {
        Column(modifier.clipToBounds().background(background)) {
            if (containsCanvas) {
                val size = painter.intrinsicSize
                val ratio = if (size.width > 0 && size.height > 0 && size.width.isFinite() && size.height.isFinite())
                    size.width / size.height else .75f
                Image(painter, contentDescription = null, contentScale = ContentScale.FillWidth,
                    alignment = Alignment.TopStart, modifier = Modifier.fillMaxWidth().then(
                        if (expandToContent) Modifier.aspectRatio(ratio) else Modifier.height(150.dp)))
            }
            FlowMarkdownView(flow, Modifier.fillMaxWidth().padding(12.dp))
        }
        return
    }

    Image(
        painter = painter,
        contentDescription = null,
        contentScale = contentScale,
        alignment = alignment,
        modifier = modifier.then(if (expandToContent) {
            val size = painter.intrinsicSize
            val ratio = if (size.width > 0 && size.height > 0 && size.width.isFinite() && size.height.isFinite())
                size.width / size.height else .75f
            Modifier.aspectRatio(ratio)
        } else Modifier).background(background)
    )
}
