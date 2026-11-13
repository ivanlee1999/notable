package com.ethran.notable.data

import android.content.Context
import android.graphics.Bitmap
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
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Date
import java.util.UUID
import java.io.File

@RunWith(AndroidJUnit4::class)
class ThumbnailSourceClockTest {
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
