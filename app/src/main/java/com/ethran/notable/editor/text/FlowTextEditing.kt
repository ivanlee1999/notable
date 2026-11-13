package com.ethran.notable.editor.text

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.sp

enum class FlowFormat { BOLD, ITALIC, HEADING, LIST, CHECKLIST, LINK }

/** Toolbar edits are source edits; composition belongs to the native IME and is never rewritten. */
fun formatFlowText(value: TextFieldValue, format: FlowFormat): TextFieldValue {
    if (value.composition != null) return value
    val start = value.selection.min; val end = value.selection.max
    val selected = value.text.substring(start, end)
    val (replacement, selection) = when (format) {
        FlowFormat.BOLD -> "**$selected**" to TextRange(start + 2, end + 2)
        FlowFormat.ITALIC -> "*$selected*" to TextRange(start + 1, end + 1)
        FlowFormat.LINK -> "[$selected](url)" to TextRange(start + selected.length + 3, start + selected.length + 6)
        else -> {
            val prefix = when (format) { FlowFormat.HEADING -> "## "; FlowFormat.CHECKLIST -> "- [ ] "; else -> "- " }
            val lineStart = value.text.lastIndexOf('\n', (start - 1).coerceAtLeast(-1)) + 1
            return TextFieldValue(value.text.substring(0, lineStart) + prefix + value.text.substring(lineStart),
                TextRange(value.selection.start + prefix.length, value.selection.end + prefix.length))
        }
    }
    return TextFieldValue(value.text.replaceRange(start, end, replacement), selection)
}

/** Style source in place: identity offsets keep selection, Unicode and the caret honest. */
object FlowEditingTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val styled = buildAnnotatedString {
            append(text)
            Regex("(?m)^ {0,3}(#{1,3}) .*$").findAll(text.text).forEach { match ->
                val level = match.groupValues[1].length
                addStyle(SpanStyle(fontWeight = FontWeight.Medium,
                    fontSize = when (level) { 1 -> 25.sp; 2 -> 22.sp; else -> 19.sp }), match.range.first, match.range.last + 1)
            }
            Regex("\\*\\*[^\\n]+?\\*\\*").findAll(text.text).forEach { match ->
                addStyle(SpanStyle(fontWeight = FontWeight.Bold), match.range.first, match.range.last + 1)
            }
            Regex("`[^`\\n]+`").findAll(text.text).forEach { match ->
                addStyle(SpanStyle(fontFamily = FontFamily.Monospace), match.range.first, match.range.last + 1)
            }
        }
        return TransformedText(styled, OffsetMapping.Identity)
    }
}
