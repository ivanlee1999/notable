package com.ethran.notable.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.JournalEntry
import com.ethran.notable.data.JournalRepository
import com.ethran.notable.data.db.Folder
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.testing.ComposeUiSupportRule
import com.ethran.notable.ui.theme.InkaTheme
import com.ethran.notable.ui.views.TodayView
import com.ethran.notable.ui.views.TodayViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.Date

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class TodayViewTest {
    private val compose = createComposeRule()
    @get:Rule val composeGate = ComposeUiSupportRule(compose)
    private val journal = mockk<JournalRepository>()
    private val app = mockk<AppRepository>(relaxed = true)
    private val entries = MutableStateFlow<List<JournalEntry>>(emptyList())
    private val books = MutableStateFlow<List<Notebook>>(emptyList())
    private val folders = MutableStateFlow<List<Folder>>(emptyList())
    private var opened: Pair<String, String>? = null

    private fun show() {
        every { journal.entries } returns entries
        every { app.bookRepository.getAllFlow() } returns books
        every { app.folderRepository.getAllFlow() } returns folders
        content(TodayViewModel(journal, app))
    }

    private fun content(model: TodayViewModel) {
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                InkaTheme {
                    TodayView(onOpen = { page, book -> opened = page to book },
                        onLibrary = {}, onPages = {}, viewModel = model)
                }
            }
        }
    }

    @Test
    fun openingTodayCreatesNothingAndNewNoteOpensItsEditor() {
        coEvery { journal.createEntry(any()) } returns ("new-book" to "new-page")
        show()
        compose.onNodeWithText("New note").assertIsEnabled()
        coVerify(exactly = 0) { journal.createEntry(any()) }
        compose.onNodeWithText("New note").performClick()
        compose.waitUntil { opened != null }
        assertEquals("new-page" to "new-book", opened)
        coVerify(exactly = 1) { journal.createEntry(emptyList()) }
    }

    @Test
    fun dayContainsIndependentNotesAndTopicShowsNotesAcrossDays() {
        val today = LocalDate.now().toString()
        entries.value = listOf(
            JournalEntry("first", today, 1, listOf("Product")),
            JournalEntry("second", today, 2),
            JournalEntry("old", LocalDate.now().minusDays(1).toString(), 0, listOf("Product")),
        )
        books.value = entries.value.map { Notebook(id = it.notebookId, title = it.notebookId,
            pageIds = listOf("${it.notebookId}-page")) }
        coEvery { journal.createEntry(any()) } returns ("new" to "new-page")
        show()
        compose.onNodeWithText("first").assertExists()
        compose.onNodeWithText("second").assertExists()
        compose.onNodeWithText("old").assertDoesNotExist()
        compose.onNodeWithText("#Product").performClick()
        compose.onNodeWithText("old").assertExists()
        compose.onNodeWithText("second").assertDoesNotExist()
        compose.onNodeWithText("New note").performClick()
        compose.waitUntil { opened != null }
        coVerify(exactly = 1) { journal.createEntry(listOf("Product")) }
    }

    @Test
    fun failedMetadataLoadBlocksCreationUntilSuccessfulRetry() {
        var fail = true
        every { journal.entries } returns flow {
            if (fail) error("Unreadable metadata")
            emit(emptyList())
        }
        every { app.bookRepository.getAllFlow() } returns books
        every { app.folderRepository.getAllFlow() } returns folders
        content(TodayViewModel(journal, app))
        compose.onNodeWithText("Could not load notes.").assertExists()
        compose.onNodeWithText("New note").assertIsNotEnabled()
        compose.onNodeWithText("Dismiss").assertDoesNotExist()
        compose.runOnIdle { fail = false }
        compose.onNodeWithText("Retry").performClick()
        compose.onNodeWithText("New note").assertIsEnabled()
        compose.onNodeWithText("Could not load notes.").assertDoesNotExist()
    }

    @Test
    fun topicPickerRemovesOnlyTheSelectedMembership() {
        val entry = JournalEntry("book", LocalDate.now().toString(), 1, listOf("Product", "Alex"))
        entries.value = listOf(entry)
        books.value = listOf(Notebook(id = "book", title = "Meeting", pageIds = listOf("page")))
        coEvery { journal.removeTopic("book", "Product") } coAnswers {
            entries.value = listOf(entry.copy(topics = listOf("Alex")))
        }
        show()
        compose.onNodeWithText("+ Topic").performClick()
        compose.onNodeWithText("Product").performClick()
        coVerify(exactly = 1) { journal.removeTopic("book", "Product") }
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("#Product").assertDoesNotExist()
        compose.onNodeWithText("#Alex").assertExists()
    }

    @Test
    fun folderTrashHidesNotesButMissingParentsDoNotHideInk() {
        val today = LocalDate.now().toString()
        entries.value = listOf(JournalEntry("trashed", today, 1), JournalEntry("orphan", today, 2))
        books.value = listOf(
            Notebook(id = "trashed", title = "Trashed entry", parentFolderId = "child"),
            Notebook(id = "orphan", title = "Orphan entry", parentFolderId = "missing"),
        )
        folders.value = listOf(
            Folder(id = "child", parentFolderId = "trash"),
            Folder(id = "trash", deletedAt = Date()),
        )
        show()
        compose.onNodeWithText("Trashed entry").assertDoesNotExist()
        compose.onNodeWithText("Orphan entry").assertExists()
        compose.runOnIdle { folders.value = folders.value.map { it.copy(deletedAt = null) } }
        compose.onNodeWithText("Trashed entry").assertExists()
    }
}
