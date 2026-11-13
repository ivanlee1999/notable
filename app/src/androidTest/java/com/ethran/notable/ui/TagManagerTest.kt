package com.ethran.notable.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.ethran.notable.data.TagSummary
import com.ethran.notable.testing.ComposeUiSupportRule
import com.ethran.notable.ui.components.TagManagerDialog
import com.ethran.notable.ui.theme.InkaTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class TagManagerTest {
    private val compose = createComposeRule()
    @get:Rule val gate = ComposeUiSupportRule(compose)
    private var tags by mutableStateOf(listOf(TagSummary("Work", 3, 1), TagSummary("Personal", 2, 0)))
    private var automatic by mutableStateOf(true)
    private var error by mutableStateOf<String?>(null)
    private val renames = mutableListOf<Pair<String, String>>()
    private var removals = 0
    private var failRemove = false

    private fun render() {
        compose.setContent { InkaTheme {
            TagManagerDialog(tags, false, error, automatic, { automatic = it }, onRename = { from, to ->
                renames += from to to
                tags = tags.filterNot { it.name == from || it.name == to } + TagSummary(to, 4, 1)
                true
            }, onRemove = { name ->
                removals++
                if (failRemove) { error = "Save failed. Try again."; false }
                else { error = null; tags = tags.filterNot { it.name == name }; true }
            }, onOpen = {}, onDismiss = {})
        } }
    }

    private fun menu(name: String) { compose.onNodeWithContentDescription("Manage tag $name").performClick() }

    @Test fun searchCountsAndRenameAreAvailableWithoutOpeningANote() {
        render()
        compose.onNodeWithText("3 notes · 1 in Trash").assertExists()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.getExternalFilesDir(null), "ui-review").apply { mkdirs() }
        compose.onNode(isDialog()).captureToImage().asAndroidBitmap().let { bitmap ->
            File(directory, "tag-manager.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        }
        compose.onNodeWithContentDescription("Find tags").performTextInput("wor")
        compose.onNodeWithText("#Personal").assertDoesNotExist()
        menu("Work")
        compose.onNodeWithText("Rename or merge").performClick()
        compose.onNodeWithContentDescription("New tag name").performTextReplacement("Projects")
        compose.onNodeWithText("Rename", substring = false).performClick()
        compose.waitForIdle()
        assertEquals(listOf("Work" to "Projects"), renames)
    }

    @Test fun anExistingDestinationIsExplicitlyPresentedAsAMerge() {
        render(); menu("Work")
        compose.onNodeWithText("Rename or merge").performClick()
        compose.onNodeWithContentDescription("New tag name").performTextReplacement("Personal")
        compose.onNodeWithText("Merge into #Personal").assertExists()
        compose.onNodeWithText("Merge tags").performClick()
        compose.waitForIdle()
        assertEquals(listOf("Work" to "Personal"), renames)
        compose.onNodeWithText("#Work").assertDoesNotExist()
    }

    @Test fun removalRequiresConfirmationKeepsAFailedCandidateAndCanBeRetried() {
        render()
        compose.onNodeWithContentDescription("Automatic tags").performClick().assertIsOff()
        assertFalse(automatic)
        assertEquals(0, removals)
        menu("Work"); compose.onNodeWithText("Remove tag").performClick()
        compose.onNodeWithText("Remove this tag from 3 notes, including 1 in Trash. Notes are kept.").assertExists()
        assertEquals(0, removals)
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(0, removals)
        menu("Work"); compose.onNodeWithText("Remove tag").performClick()
        failRemove = true
        compose.onNodeWithText("Remove tag", substring = false).performClick()
        compose.waitForIdle()
        assertEquals(1, removals)
        compose.onAllNodesWithText("Save failed. Try again.").assertCountEquals(2)
        assertTrue(tags.any { it.name == "Work" })
        failRemove = false
        compose.onNodeWithText("Remove tag", substring = false).performClick()
        compose.waitForIdle()
        assertEquals(2, removals)
        compose.onNodeWithText("#Work").assertDoesNotExist()
    }
}
