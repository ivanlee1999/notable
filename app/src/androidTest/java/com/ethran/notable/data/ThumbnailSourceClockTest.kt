package com.ethran.notable.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ethran.notable.data.db.Page
import com.ethran.notable.data.db.PageRepository
import com.ethran.notable.editor.utils.PreviewSaveMode
import com.ethran.notable.editor.utils.getContentPreviewFile
import com.ethran.notable.editor.utils.savePageThumbnail
import com.ethran.notable.io.PageContentRenderer
import com.ethran.notable.io.ThumbnailEnsureResult
import com.ethran.notable.io.ThumbnailGenerator
import com.ethran.notable.io.ThumbnailBackfillQueue
import com.ethran.notable.data.events.DefaultAppEventBus
import com.ethran.notable.ui.components.contentPreviewRequest
import coil.ImageLoader
import coil.request.SuccessResult
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Date
import java.util.UUID
import java.io.File

@RunWith(AndroidJUnit4::class)
class ThumbnailSourceClockTest {
    @Test fun equalClockInvalidationDiscardsHeldRenderAndQueuesOnlyTheFreshImage() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val id = "preview-test-${UUID.randomUUID()}"
        val file = getContentPreviewFile(context, id)
        val pages = mockk<PageRepository>()
        val renderer = mockk<PageContentRenderer>()
        val page = Page(id = id, notebookId = "test", updatedAt = Date(1_000))
        coEvery { pages.getById(id) } returns page
        val oldStarted = CompletableDeferred<Unit>()
        val finishOld = CompletableDeferred<Unit>()
        val freshStarted = CompletableDeferred<Unit>()
        val finishFresh = CompletableDeferred<Unit>()
        var renders = 0
        coEvery { renderer.renderPageBitmap(id, any()) } coAnswers {
            val old = ++renders == 1
            if (old) { oldStarted.complete(Unit); finishOld.await() }
            else { freshStarted.complete(Unit); finishFresh.await() }
            Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888).apply {
                eraseColor(if (old) Color.BLACK else Color.WHITE)
            }
        }
        val generator = ThumbnailGenerator(context, renderer, pages, Dispatchers.Default)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val published = Channel<String>(Channel.UNLIMITED)
        scope.launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            generator.thumbnailUpdated.collect { published.send(it) }
        }
        try {
            withTimeout(10_000) {
                val queue = ThumbnailBackfillQueue(scope, Dispatchers.Default, generator, DefaultAppEventBus())
                queue.enqueue(listOf(id), PreviewSaveMode.STRICT_BW)
                oldStarted.await()
                val warm = generator.invalidate(listOf(id, "never-previewed-${UUID.randomUUID()}"))
                assertEquals(setOf(id), warm)
                queue.enqueue(warm.toList(), PreviewSaveMode.STRICT_BW)
                finishOld.complete(Unit)
                freshStarted.await()
                assertFalse("The superseded render must not install its old bytes", file.exists())
                assertTrue("The superseded render must not announce an update", published.tryReceive().isFailure)
                finishFresh.complete(Unit)
                assertEquals(id, published.receive())
                assertEquals(1_000L, file.lastModified())
                val image = BitmapFactory.decodeFile(file.absolutePath)
                try { assertEquals(Color.WHITE, image.getPixel(0, 0)) } finally { image.recycle() }
                assertEquals(ThumbnailEnsureResult.UP_TO_DATE, generator.ensureThumbnail(id, PreviewSaveMode.STRICT_BW))
                assertEquals(2, renders)
            }
        } finally { scope.cancel(); file.delete() }
    }

    @Test fun remountReadsNewCanonicalBytesEvenWhenThePathClockAndRequestKeyStayTheSame() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = getContentPreviewFile(context, "preview-test-${UUID.randomUUID()}")
        file.parentFile?.mkdirs()
        fun write(color: Int) {
            val image = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
            try { file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            finally { image.recycle() }
            check(file.setLastModified(1_000))
        }
        val loader = ImageLoader.Builder(context).build()
        try {
            write(Color.BLACK)
            val first = loader.execute(contentPreviewRequest(context, file, 0).newBuilder().allowHardware(false).build()) as SuccessResult
            assertEquals(Color.BLACK, (first.drawable as android.graphics.drawable.BitmapDrawable).bitmap.getPixel(0, 0))
            write(Color.WHITE)
            val second = loader.execute(contentPreviewRequest(context, file, 0).newBuilder().allowHardware(false).build()) as SuccessResult
            assertEquals(Color.WHITE, (second.drawable as android.graphics.drawable.BitmapDrawable).bitmap.getPixel(0, 0))
        } finally { loader.shutdown(); file.delete() }
    }

    @Test fun anEditDuringRenderCannotMakeTheOlderImageLookFresh() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val id = "preview-test-${UUID.randomUUID()}"
        val file = getContentPreviewFile(context, id)
        val pages = mockk<PageRepository>()
        val renderer = mockk<PageContentRenderer>()
        var page = Page(id = id, notebookId = "test", updatedAt = Date(1_000))
        coEvery { pages.getById(id) } answers { page }
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var renders = 0
        coEvery { renderer.renderPageBitmap(id, any()) } coAnswers {
            if (++renders == 1) { started.complete(Unit); finish.await() }
            Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        }
        val generator = ThumbnailGenerator(context, renderer, pages, Dispatchers.Default)
        try {
            withTimeout(10_000) {
                val first = async { generator.ensureThumbnail(id, PreviewSaveMode.STRICT_BW) }
                started.await()
                page = page.copy(updatedAt = Date(2_000))
                finish.complete(Unit)
                assertEquals(ThumbnailEnsureResult.GENERATED, first.await())
                assertEquals(1_000L, file.lastModified())
                assertEquals(ThumbnailEnsureResult.GENERATED, generator.ensureThumbnail(id, PreviewSaveMode.STRICT_BW))
                assertEquals(2_000L, file.lastModified())
                assertEquals(ThumbnailEnsureResult.UP_TO_DATE, generator.ensureThumbnail(id, PreviewSaveMode.STRICT_BW))
                assertEquals(2, renders)
            }
        } finally { file.delete() }
    }

    @Test fun aFailedSavePreservesThePreviousPreviewAndItsClock() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val id = "preview-test-${UUID.randomUUID()}"
        val file = getContentPreviewFile(context, id)
        file.parentFile?.mkdirs()
        file.writeText("previous preview")
        file.setLastModified(1_000)
        val blockedTemporary = File(file.parentFile, "${file.name}.tmp").apply { mkdir() }
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        try {
            assertFalse(savePageThumbnail(context, bitmap, id, PreviewSaveMode.STRICT_BW,
                targetFile = file, sourceClock = 2_000))
            assertEquals("previous preview", file.readText())
            assertEquals(1_000L, file.lastModified())
        } finally {
            bitmap.recycle()
            blockedTemporary.delete()
            file.delete()
        }
    }
}
