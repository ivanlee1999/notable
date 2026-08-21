package com.ethran.notable.ui.dialogs

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ethran.notable.data.model.BackgroundType
import com.ethran.notable.data.model.PageSizePreset
import com.ethran.notable.data.model.TemplatePlacement
import com.ethran.notable.testing.ComposeUiSupportRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The two places a template is chosen: while a notebook is being made, and while a page is open.
 *
 * Both were asked for by hand and both are easy to get wrong in a way no logic test would notice —
 * a section that never renders, a choice that is read before it is set. So they are driven here as
 * a person drives them: find the words on the screen, tap them, and see what the caller is told.
 */
@RunWith(AndroidJUnit4::class)
class TemplateChoiceComposeTests {

    private val composeRule = createComposeRule()

    @get:Rule
    val composeGate = ComposeUiSupportRule(composeRule)

    private val lecture = KnownTemplate(
        file = File("/storage/emulated/0/Download/Lecture 3.pdf"),
        backgroundType = BackgroundType.Pdf(0),
    )

    // region Creating a notebook

    /**
     * A document the library already uses is offered while the notebook is being made, and a
     * notebook made on one *follows* it — page two of the book is page two of the document, which is
     * what `autoPdf` means. Choosing it must not be reported as a printed template named by a path.
     */
    @Test
    fun aNotebookCanBeCreatedOnADocumentTheLibraryAlreadyUses() {
        var confirmed: Triple<String, String, String>? = null
        composeRule.setContent {
            NewNotebookDialog(
                initialName = "Daily",
                initialPageSize = PageSizePreset.DEFAULT.size,
                initialTemplate = "blank",
                onConfirm = { name, _, template, templateType ->
                    confirmed = Triple(name, template, templateType)
                },
                onDismiss = {},
                libraryTemplates = listOf(lecture),
            )
        }

        // Scrolled to, then asserted: the options scroll, and on a phone-width screen with the
        // keyboard up the library section starts below the fold. That it can be reached is the
        // claim worth making — `assertIsDisplayed` on its own only says the tablet is big enough.
        // "Create" is deliberately not scrolled to: it sits outside the scrolling body, so asking
        // for a scroll parent it does not have would fail.
        composeRule.onNodeWithText("Lecture 3").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Lecture 3").performClick()
        composeRule.onNodeWithText("Create").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        assertEquals(
            Triple("Daily", lecture.file.absolutePath, BackgroundType.AutoPdf.key),
            confirmed,
        )
    }

    /** The printed templates still answer as themselves, and are still what a fresh library shows. */
    @Test
    fun aNotebookCreatedOnAPrintedTemplateReportsThatTemplate() {
        var confirmed: Triple<String, String, String>? = null
        composeRule.setContent {
            NewNotebookDialog(
                initialName = "Daily",
                initialPageSize = PageSizePreset.DEFAULT.size,
                initialTemplate = "blank",
                onConfirm = { name, _, template, templateType ->
                    confirmed = Triple(name, template, templateType)
                },
                onDismiss = {},
                libraryTemplates = emptyList(),
            )
        }

        composeRule.onNodeWithText("Lines").performScrollTo().performClick()
        composeRule.onNodeWithText("Create").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        assertEquals(Triple("Daily", "lined", BackgroundType.Native.key), confirmed)
    }

    // endregion

    // region Choosing where a template goes

    private class Chosen(val type: String, val placement: TemplatePlacement)

    @Test
    fun aTemplateCanBeAskedForOnANewPageAfterTheOpenOne() {
        val chosen = mutableListOf<Chosen>()
        composeRule.setContent {
            BackgroundSelector(
                initialPageBackgroundType = "native",
                initialPageBackground = "blank",
                notebookId = "nb1",
                pageNumberInBook = 1,
                onChange = { type, _, placement -> chosen += Chosen(type, placement) },
                onClose = {},
            )
        }

        // Scrolled to, not merely clicked: the selector's body scrolls, and on a phone-width
        // screen the template chips start below the fold. `performClick` alone taps wherever the
        // node happens to sit, which on a short screen is not the node at all.
        composeRule.onNodeWithText("New page after").performScrollTo().performClick()
        composeRule.onNodeWithText("Lines").performScrollTo().performClick()
        composeRule.waitForIdle()

        assertEquals(1, chosen.size)
        assertEquals("native", chosen.first().type)
        assertEquals(TemplatePlacement.NEW_PAGE_AFTER, chosen.first().placement)
    }

    /**
     * The reset after a page is made. The taps that usually follow — which sheet, repeating or not —
     * are adjustments to the page just made, not requests for three more.
     */
    @Test
    fun theNextTemplateAfterANewPageGoesOnThatPage() {
        val chosen = mutableListOf<Chosen>()
        composeRule.setContent {
            BackgroundSelector(
                initialPageBackgroundType = "native",
                initialPageBackground = "blank",
                notebookId = "nb1",
                pageNumberInBook = 1,
                onChange = { type, _, placement -> chosen += Chosen(type, placement) },
                onClose = {},
            )
        }

        composeRule.onNodeWithText("New page before").performScrollTo().performClick()
        composeRule.onNodeWithText("Lines").performScrollTo().performClick()
        composeRule.onNodeWithText("Dot grid").performScrollTo().performClick()
        composeRule.waitForIdle()

        assertEquals(2, chosen.size)
        assertEquals(TemplatePlacement.NEW_PAGE_BEFORE, chosen[0].placement)
        assertEquals(TemplatePlacement.CURRENT_PAGE, chosen[1].placement)
    }

    /** A notebook's own default has no page to be before or after, so it is never asked. */
    @Test
    fun aNotebooksDefaultBackgroundIsNotAskedWhereToGo() {
        composeRule.setContent {
            BackgroundSelector(
                initialPageBackgroundType = "native",
                initialPageBackground = "blank",
                isNotebookBgSelector = true,
                notebookId = "nb1",
                onChange = { _, _, _ -> },
                onClose = {},
            )
        }

        composeRule.onNodeWithText("Add to").assertDoesNotExist()
        composeRule.onNodeWithText("New page after").assertDoesNotExist()
    }

    /** Nor is a loose page with no notebook, which has no neighbours either. */
    @Test
    fun aPageWithNoNotebookIsNotAskedWhereToGo() {
        composeRule.setContent {
            BackgroundSelector(
                initialPageBackgroundType = "native",
                initialPageBackground = "blank",
                notebookId = null,
                onChange = { _, _, _ -> },
                onClose = {},
            )
        }

        composeRule.onNodeWithText("Add to").assertDoesNotExist()
    }

    // endregion
}
