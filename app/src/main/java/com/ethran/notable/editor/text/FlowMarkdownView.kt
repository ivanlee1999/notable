package com.ethran.notable.editor.text

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ethran.notable.data.db.Block
import com.ethran.notable.ui.theme.Kaleido

/** Native text remains readable/selectable when a whole paper bitmap would make it tiny. */
@Composable
fun FlowMarkdownView(blocks: List<Block>, modifier: Modifier = Modifier) {
    SelectionContainer(modifier) {
        Column(Modifier.fillMaxWidth()) {
            FlowText.ordered(blocks).forEach { block ->
                when (block.kind) {
                    "md" -> MarkdownParagraph(block.text.orEmpty())
                    "link" -> Text("↗ ${block.text?.takeIf(String::isNotBlank) ?: block.targetNotebookId.orEmpty()}",
                        color = Kaleido.Ink, modifier = Modifier.padding(vertical = 8.dp))
                    "image", "audio" -> Text(if (block.kind == "image") "Image attachment" else "Audio recording",
                        color = Kaleido.Muted, modifier = Modifier.padding(vertical = 8.dp))
                }
            }
        }
    }
}

@Composable
fun MarkdownParagraph(source: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(bottom = 12.dp)) {
        MarkdownText.parse(source).forEach { line ->
            val heading = line.kind as? MarkdownText.LineKind.Heading
            val size = when (heading?.level) { 1 -> 25; 2 -> 22; 3 -> 19; else -> 17 }.sp
            val text = buildAnnotatedString {
                when (val kind = line.kind) {
                    is MarkdownText.LineKind.Bullet -> append("• ")
                    is MarkdownText.LineKind.Numbered -> append("${kind.number}. ")
                    else -> Unit
                }
                line.spans.forEach { span ->
                    val traits = span.traits
                    withStyle(SpanStyle(
                        fontWeight = if (MarkdownText.SpanTraits.BOLD in traits) FontWeight.Bold else null,
                        fontStyle = if (MarkdownText.SpanTraits.ITALIC in traits) FontStyle.Italic else null,
                        fontFamily = if (MarkdownText.SpanTraits.CODE in traits) FontFamily.Monospace else null,
                        textDecoration = if (MarkdownText.SpanTraits.LINK in traits) TextDecoration.Underline else null,
                    )) { append(span.text) }
                }
            }
            Text(text, fontSize = size, lineHeight = size * 1.5f, color = Kaleido.Ink,
                fontWeight = if (heading != null) FontWeight.Medium else FontWeight.Normal)
        }
    }
}
