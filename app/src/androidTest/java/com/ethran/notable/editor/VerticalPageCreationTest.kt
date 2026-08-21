package com.ethran.notable.editor

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ethran.notable.data.db.AppDatabase
import com.ethran.notable.testing.TestDatabaseFactory
import com.ethran.notable.testing.TestNotebookSeeder
import com.ethran.notable.testing.createEditorViewModelForTest
import com.ethran.notable.testing.waitForCondition
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Proves the page-turn action used by both vertical-navigation modes persists and enters page 2. */
@RunWith(AndroidJUnit4::class)
class VerticalPageCreationTest {
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = TestDatabaseFactory.createInMemory(
            ApplicationProvider.getApplicationContext<Context>()
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test(timeout = 30_000)
    fun nextPageActionOnLastSheetCreatesAndEntersARealSecondPage() {
        val seeded = runBlocking {
            TestNotebookSeeder.seedNotebook(db, pageCount = 1, strokesPerPage = 1)
        }
        val firstPage = seeded.pageIds.single()
        val viewModel = createEditorViewModelForTest(
            context = ApplicationProvider.getApplicationContext(),
            db = db,
        )
        runBlocking { viewModel.loadToolbarState(seeded.notebookId, firstPage) }

        viewModel.goToNextPage()

        assertTrue(
            "the editor should enter the page it created",
            waitForCondition(15_000) {
                viewModel.toolbarState.value.pageId?.let { it != firstPage } == true
            },
        )
        val enteredPage = viewModel.toolbarState.value.pageId
        val stored = runBlocking { db.notebookDao().getById(seeded.notebookId) }

        assertNotNull(stored)
        assertEquals(2, stored!!.pageIds.size)
        assertEquals(enteredPage, stored.pageIds.last())
        assertNotEquals(firstPage, enteredPage)
        assertNotNull(runBlocking { db.pageDao().getById(enteredPage!!) })
    }
}
