package com.ethran.notable.editor.text

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.*
import org.junit.Test

class FlowTextEditingTest {
    @Test fun formattingDoesNotTouchAnActiveImeComposition() {
        val value = TextFieldValue("中文", TextRange(2), TextRange(0, 2))
        assertEquals(value, formatFlowText(value, FlowFormat.BOLD))
    }

    @Test fun unicodeSelectionIsWrappedWithoutMovingOtherText() {
        val value = TextFieldValue("un café ☕", TextRange(3, 7))
        val result = formatFlowText(value, FlowFormat.BOLD)
        assertEquals("un **café** ☕", result.text)
        assertEquals("café", result.text.substring(result.selection.min, result.selection.max))
    }

    @Test fun checklistAddsASourceMarkerAtTheCurrentLineAndKeepsEnterAvailable() {
        val value = TextFieldValue("Heading\nBuy milk", TextRange(16))
        val result = formatFlowText(value, FlowFormat.CHECKLIST)
        assertEquals("Heading\n- [ ] Buy milk", result.text)
        assertEquals(22, result.selection.start)
    }

    @Test fun liveMarkdownStylingKeepsExactSourceAndIdentityOffsets() {
        val source = "## Titre\n\n**café** 中文 `code`"
        val transformed = FlowEditingTransformation.filter(AnnotatedString(source))
        assertEquals(source, transformed.text.text)
        for (index in 0..source.length) {
            assertEquals(index, transformed.offsetMapping.originalToTransformed(index))
            assertEquals(index, transformed.offsetMapping.transformedToOriginal(index))
        }
    }
}
