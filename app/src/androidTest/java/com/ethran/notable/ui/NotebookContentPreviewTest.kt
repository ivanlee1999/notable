package com.ethran.notable.ui

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.testing.ComposeUiSupportRule
import com.ethran.notable.ui.components.*
import com.ethran.notable.ui.theme.InkaTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class NotebookContentPreviewTest {
    private val compose = createComposeRule()
    @get:Rule val composeGate = ComposeUiSupportRule(compose)
    private val preferences = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("note_browser", Context.MODE_PRIVATE)

    @Before fun resetLayout() { preferences.edit().clear().commit() }
    @After fun clearLayout() { preferences.edit().clear().commit() }

    @Test fun previewPagesAndExpansionDoNotOpenEditorUntilEditIsTapped() {
        var edited: String? = null
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                var expanded by remember { mutableStateOf(false) }
                InkaTheme {
                    NotebookContentPreview(Notebook(id = "book", title = "Meeting", openPageId = "p2",
                        pageIds = listOf("p1", "p2", "p3")), previewHeight = 180.dp,
                        expanded = expanded, onExpand = { expanded = !expanded },
                        onEdit = { edited = it }, modifier = Modifier.width(320.dp))
                }
            }
        }
        compose.onNodeWithText("2/3 ▾").assertExists()
        compose.onNodeWithContentDescription("Preview pages of Meeting").performClick()
        compose.onNodeWithText("Next page").performClick()
        compose.onNodeWithText("3/3 ▾").assertExists()
        compose.onNodeWithContentDescription("Expand Meeting in place").performClick()
        compose.onNodeWithContentDescription("Collapse Meeting").assertExists()
        assertEquals(null, edited)
        compose.onNodeWithContentDescription("Edit Meeting").performClick()
        assertEquals("p3", edited)
    }

    @Test fun selectedPreviewFollowsPageIdentityWhenPagesReorderAndDisappear() {
        var book by mutableStateOf(Notebook(id = "book", title = "Meeting", openPageId = "p2",
            pageIds = listOf("p1", "p2", "p3")))
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                InkaTheme { NotebookContentPreview(book, 100.dp, false, {}, {}, Modifier.width(320.dp)) }
            }
        }
        compose.onNodeWithText("2/3 ▾").assertExists()
        compose.runOnIdle { book = book.copy(pageIds = listOf("p3", "p1", "p2")) }
        compose.onNodeWithText("3/3 ▾").assertExists()
        compose.runOnIdle { book = book.copy(pageIds = listOf("p3", "p1")) }
        compose.onNodeWithText("1/2 ▾").assertExists()
    }

    @Test fun previewDensityIsSharedAndPersistedWithoutChangingNotes() {
        compose.setContent {
            InkaTheme {
                val (one, choose) = rememberBrowserLayout(compact = true)
                val (two) = rememberBrowserLayout(compact = true)
                Column {
                    Text("First:${one.name}")
                    Text("Second:${two.name}")
                    BrowserLayoutPicker(one, choose, compact = true)
                }
            }
        }
        compose.onNodeWithText("First:FEED").assertExists()
        compose.onNodeWithContentDescription("Preview size").performClick()
        compose.onNodeWithText("2 × 3 · Medium").performClick()
        compose.onNodeWithText("First:MEDIUM").assertExists()
        compose.onNodeWithText("Second:MEDIUM").assertExists()
        assertEquals("MEDIUM", preferences.getString("layout", null))
    }

    @Test fun narrowPreviewKeepsPageExpansionAndEditControlsVisibleAtLargeText() {
        var edited: String? = null
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalInspectionMode provides true,
                LocalDensity provides Density(density.density, 1.5f)) {
                InkaTheme {
                    NotebookContentPreview(Notebook(id = "book", title = "Meeting",
                        pageIds = listOf("p1", "p2")), 100.dp, false, {}, { edited = it },
                        Modifier.width(156.dp))
                }
            }
        }
        compose.onNodeWithContentDescription("Preview pages of Meeting").assertIsDisplayed()
        compose.onNodeWithContentDescription("Expand Meeting in place").assertIsDisplayed()
        compose.onNodeWithContentDescription("Edit Meeting").assertIsDisplayed().performClick()
        assertEquals("p1", edited)
    }
}
