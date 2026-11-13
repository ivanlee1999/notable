package com.ethran.notable.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ethran.notable.data.db.AppDatabase
import com.ethran.notable.data.db.Block
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.db.Page
import com.ethran.notable.editor.text.FlowTextLayout
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Date

@RunWith(AndroidJUnit4::class)
class FlowPreviewTest {
    @Test fun sameClockFlowSourceChangesAreObservedWithoutLoadingStrokeBlobs() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val updates = Channel<String>(Channel.UNLIMITED)
        try {
            db.notebookDao().create(Notebook(id = "book"))
            db.pageDao().create(Page(id = "page", notebookId = "book", updatedAt = Date(1)))
            val block = Block(id = "text", pageId = "page", text = "first", updatedAt = Date(1))
            db.blockDao().upsertAll(listOf(block))
            val observer = launch { db.blockDao().observeByPage("page").collect { updates.send(it.single().text!!) } }
            withTimeout(5_000) {
                assertEquals("first", updates.receive())
                db.blockDao().upsertAll(listOf(block.copy(text = "merged same-clock source")))
                assertEquals("merged same-clock source", updates.receive())
            }
            assertFalse(db.pageDao().observeCanvasContent("page").first())
            assertEquals(1L, db.pageDao().observeUpdatedAt("page").first())
            observer.cancelAndJoin()
        } finally { db.close() }
    }

    @Test fun pureTextStartsAtTheTopWhileAnnotationsKeepTheirCoordinates() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            db.notebookDao().create(Notebook(id = "book"))
            db.pageDao().create(Page(id = "page", notebookId = "book"))
            val flow = Block(id = "text", pageId = "page", text = "# Heading\n\nReadable body")
            db.blockDao().upsertAll(listOf(flow))
            val pure = db.pageDao().getPageWithDataById("page")!!
            assertFalse(FlowTextLayout.hasCanvas(pure))
            assertEquals(32, FlowTextLayout.positioned(pure).single().y)
            val positioned = Block(id = "box", pageId = "page", text = "Original", x = 20, y = 70, width = 300)
            db.blockDao().upsertAll(listOf(positioned))
            val mixed = db.pageDao().getPageWithDataById("page")!!
            assertTrue(FlowTextLayout.hasCanvas(mixed))
            assertTrue(FlowTextLayout.positioned(mixed).single().y!! > 70)
            assertEquals(positioned, mixed.blocks.single { it.id == "box" })
        } finally { db.close() }
    }
}
