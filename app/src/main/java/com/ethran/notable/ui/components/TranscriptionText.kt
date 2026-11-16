package com.ethran.notable.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.Checkbox
import androidx.compose.material.CheckboxDefaults
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ethran.notable.editor.text.MarkdownText
import com.ethran.notable.recognition.TranscriptMarkdown
import com.ethran.notable.ui.theme.Kaleido

/** Generated content is selectable, inert native text: no URL actions, image requests, or HTML. */
@Composable
fun TranscriptionText(source: String, format: String?, modifier: Modifier = Modifier) {
    SelectionContainer(modifier) {
        if (format != "markdown") {
            Text(source, color = Kaleido.Ink, fontSize = 17.sp, lineHeight = 26.sp,
                modifier = Modifier.semantics { contentDescription = "Recognized handwriting" })
        } else {
            val blocks = remember(source) { TranscriptMarkdown.parse(source) }
            Column(Modifier.fillMaxWidth().semantics { contentDescription = "Recognized handwriting" }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                blocks.forEach { block -> when (block) {
                    is TranscriptMarkdown.Block.Line -> {
                        val size = when (block.heading) { 1 -> 25; 2 -> 22; 3 -> 20; else -> 17 }.sp
                        Row(Modifier.fillMaxWidth().padding(start = (block.indent.coerceAtMost(12) * 8).dp)
                            .then(when {
                                block.heading != null -> Modifier.semantics(mergeDescendants = true) { heading() }
                                block.checked != null -> Modifier.semantics(mergeDescendants = true) {}
                                else -> Modifier
                            }),
                            verticalAlignment = Alignment.Top) {
                            if (block.checked != null) Checkbox(block.checked, onCheckedChange = null, enabled = false,
                                modifier = Modifier.padding(end = 6.dp).size(24.dp).semantics {
                                    role = Role.Checkbox
                                    toggleableState = ToggleableState(block.checked)
                                    disabled()
                                }, colors = CheckboxDefaults.colors(disabledColor = Kaleido.Ink))
                            else block.marker?.let { Text(it, Modifier.widthIn(min = 24.dp), color = Kaleido.Ink, fontSize = 17.sp) }
                            InlineText(block.source, Modifier.weight(1f), size.value.toInt(), block.heading != null)
                        }
                    }
                    is TranscriptMarkdown.Block.Code -> Text(block.source, fontFamily = FontFamily.Monospace,
                        color = Kaleido.Ink, fontSize = 16.sp, lineHeight = 24.sp,
                        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp))
                    is TranscriptMarkdown.Block.Table -> TranscriptTable(block)
                } }
            }
        }
    }
}

@Composable
private fun InlineText(source: String, modifier: Modifier = Modifier, size: Int = 17, bold: Boolean = false) {
    val text = remember(source) { buildAnnotatedString {
        MarkdownText.parseInline(source).forEach { span -> withStyle(SpanStyle(
            fontWeight = if (MarkdownText.SpanTraits.BOLD in span.traits) FontWeight.Bold else null,
            fontStyle = if (MarkdownText.SpanTraits.ITALIC in span.traits) FontStyle.Italic else null,
            fontFamily = if (MarkdownText.SpanTraits.CODE in span.traits) FontFamily.Monospace else null,
        )) { append(span.text) } }
    } }
    Text(text, modifier, color = Kaleido.Ink, fontSize = size.sp, lineHeight = (size * 1.5f).sp,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
}

@Composable
private fun TranscriptTable(table: TranscriptMarkdown.Block.Table) {
    val columns = maxOf(table.header.size, table.rows.maxOfOrNull { it.size } ?: 0)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val width = (maxWidth / columns).coerceIn(112.dp, 240.dp)
        Column(Modifier.horizontalScroll(rememberScrollState()).semantics {
            contentDescription = "Recognized table"
            collectionInfo = CollectionInfo(table.rows.size + 1, columns)
        }) {
            (listOf(table.header) + table.rows).forEachIndexed { rowIndex, row ->
                Row(Modifier.height(IntrinsicSize.Min)) {
                    repeat(columns) { column ->
                        Box(Modifier.width(width).fillMaxHeight().border(1.dp, Kaleido.Muted).padding(8.dp)
                            .semantics(mergeDescendants = true) { collectionItemInfo = CollectionItemInfo(rowIndex, 1, column, 1) }) {
                            InlineText(row.getOrElse(column) { "" }, bold = rowIndex == 0)
                        }
                    }
                }
            }
        }
    }
}
