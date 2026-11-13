package com.ethran.notable.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ethran.notable.data.db.AppDatabase
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.db.Page
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Date

@RunWith(AndroidJUnit4::class)
class PreviewPageClockTest {
    @Test fun anOlderPagesClockStillInvalidatesItsPreviewWhenTheNotebookMaximumDoesNotChange() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val clocks = Channel<Long?>(Channel.UNLIMITED)
        try {
            db.notebookDao().create(Notebook(id = "book"))
            val older = Page(id = "older", notebookId = "book", updatedAt = Date(1))
            db.pageDao().create(older)
            db.pageDao().create(Page(id = "newest", notebookId = "book", updatedAt = Date(100)))
            val observer = launch { db.pageDao().observeUpdatedAt("older").distinctUntilChanged().collect { clocks.send(it) } }
            withTimeout(5_000) {
                assertEquals(1L, clocks.receive())
                db.pageDao().update(older.copy(updatedAt = Date(2)))
                assertEquals(2L, clocks.receive())
                assertEquals(Date(100), db.pageDao().lastEditedByNotebookFlow().first().single().lastEdited)
                db.pageDao().delete("older")
                assertEquals(null, clocks.receive())
            }
            observer.cancelAndJoin()
        } finally { db.close() }
    }
}
