package com.ethran.notable.ui

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
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
import org.junit.After
import org.junit.Before
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

    @Before
    @After
    fun resetBrowserLayout() {
        ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("note_browser", Context.MODE_PRIVATE).edit().clear().commit()
    }

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
    fun selectedPreviewPageSurvivesLazyRowExpansionAndDensityChanges() {
        val today = LocalDate.now().toString()
        entries.value = listOf(JournalEntry("first", today, 1), JournalEntry("second", today, 2))
        books.value = entries.value.map { Notebook(id = it.notebookId, title = it.notebookId,
            pageIds = listOf("${it.notebookId}-p1", "${it.notebookId}-p2")) }
        show()
        fun layout(label: String) {
            compose.onNodeWithContentDescription("Preview size").performClick()
            compose.onNode(hasText(label, substring = true) and hasAnyAncestor(isPopup())).performClick()
        }
        layout("2 × 2 · Large")
        compose.onNodeWithContentDescription("Preview pages of first").performClick()
        compose.onNodeWithText("Next page").performClick()
        compose.onNodeWithText("2/2 ▾").assertExists()
        compose.onNodeWithContentDescription("Expand first in place").performClick()
        compose.onNodeWithContentDescription("Collapse first").assertExists()
        layout("Reading feed")
        layout("2 × 3 · Medium")
        compose.onNodeWithContentDescription("Collapse first").performScrollTo().performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("first"))
        compose.onNodeWithText("2/2 ▾").assertExists()
        assertEquals(null, opened)
        compose.onNodeWithContentDescription("Edit first").performScrollTo().performClick()
        assertEquals("first-p2" to "first", opened)
    }

    @Test
    fun browsingAndExpandingDoesNotOpenTheEditorUntilEditIsTapped() {
        val today = LocalDate.now().toString()
        entries.value = (0..11).map { JournalEntry("entry-$it", today, it.toLong()) }
        books.value = entries.value.map { Notebook(id = it.notebookId, title = it.notebookId,
            pageIds = listOf("${it.notebookId}-page")) }
        show()
        compose.onNodeWithContentDescription("Preview size").performClick()
        compose.onNode(hasText("Reading feed", substring = true) and hasAnyAncestor(isPopup())).performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("entry-0"))
        assertEquals(null, opened)
        compose.onNodeWithContentDescription("Expand entry-0 in place").performClick()
        compose.waitForIdle()
        compose.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("Collapse entry-0"))
        compose.onNodeWithContentDescription("Collapse entry-0").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("Edit entry-0"))
        compose.waitForIdle()
        assertEquals(null, opened)
        compose.onNodeWithContentDescription("Edit entry-0").assertIsDisplayed().performClick()
        assertEquals("entry-0-page" to "entry-0", opened)
        coVerify(exactly = 0) { journal.createEntry(any()) }
    }

    @Test
    fun syncedTopicSpellingsShareOneTopicAcrossNotes() {
        val today = LocalDate.now().toString()
        entries.value = listOf(
            JournalEntry("first", today, 1, listOf("Product")),
            JournalEntry("second", today, 2, listOf("product")),
        )
        books.value = entries.value.map { Notebook(id = it.notebookId, title = it.notebookId,
            pageIds = listOf("${it.notebookId}-page")) }
        show()
        compose.onNodeWithText("Topics").performClick()
        compose.onAllNodesWithText("#Product").assertCountEquals(1)
        compose.onNodeWithText("#product").assertDoesNotExist()
        compose.onNodeWithText("#Product").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("first"))
        compose.onNodeWithText("first").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("second"))
        compose.onNodeWithText("second").assertIsDisplayed()
    }

    @Test
    fun successfulCreationRetryClearsThePreviousError() {
        var attempts = 0
        coEvery { journal.createEntry(any()) } answers {
            if (attempts++ == 0) error("Save failed")
            "new-book" to "new-page"
        }
        show()
        compose.onNodeWithText("New note").performClick()
        compose.onNodeWithText("Could not create note. Try again.").assertExists()
        compose.onNodeWithText("New note").performClick()
        compose.waitUntil { opened != null }
        compose.onNodeWithText("Could not create note. Try again.").assertDoesNotExist()
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
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("first"))
        compose.onNodeWithText("first").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("second"))
        compose.onNodeWithText("second").assertIsDisplayed()
        compose.onNodeWithText("old").assertDoesNotExist()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("#Product"))
        compose.onNodeWithText("#Product").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("old"))
        compose.onNodeWithText("old").assertIsDisplayed()
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
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Trashed entry"))
        compose.onNodeWithText("Trashed entry").assertIsDisplayed()
    }
}
