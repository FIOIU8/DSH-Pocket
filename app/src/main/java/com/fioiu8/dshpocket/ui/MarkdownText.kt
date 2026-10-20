package com.fioiu8.dshpocket.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlInline
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text as MarkdownNodeText
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser

@Composable
internal fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    color: Color,
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val rendered = remember(markdown, color, linkColor) { markdownToAnnotatedString(markdown, color, linkColor) }
    Text(rendered, modifier = modifier, fontSize = fontSize, lineHeight = lineHeight, color = color)
}

internal fun markdownToAnnotatedString(
    markdown: String,
    color: Color,
    linkColor: Color = Color(0xFF4A78B8),
): AnnotatedString {
    val document = Parser.builder().build().parse(markdown)
    return AnnotatedString.Builder().apply {
        renderBlocks(document, color, linkColor)
    }.toAnnotatedString()
}

private fun AnnotatedString.Builder.renderBlocks(parent: Node, color: Color, linkColor: Color) {
    var child = parent.firstChild
    while (child != null) {
        when (child) {
            is Paragraph -> renderInline(child, color, linkColor)
            is Heading -> {
                val size = when (child.level) {
                    1 -> 1.35f
                    2 -> 1.2f
                    else -> 1.08f
                }
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = (16f * size).sp))
                renderInline(child, color, linkColor)
                pop()
            }
            is FencedCodeBlock -> appendCode(child.literal, color)
            is IndentedCodeBlock -> appendCode(child.literal, color)
            is BulletList -> renderList(child, color, linkColor, null)
            // markerStartNumber replaces the deprecated startNumber (commonmark 0.22+);
            // it is null when the start marker is unset, so fall back to the old default of 1.
            is OrderedList -> renderList(child, color, linkColor, child.markerStartNumber ?: 1)
            is BlockQuote -> {
                pushStyle(SpanStyle(color = color.copy(alpha = 0.82f), fontWeight = FontWeight.Medium))
                append("│ ")
                renderBlocks(child, color, linkColor)
                pop()
            }
            is ThematicBreak -> append("──────────")
            else -> renderInline(child, color, linkColor)
        }
        child = child.next
        if (child != null && length > 0) {
            if (toAnnotatedString().text.lastOrNull() != '\n') append('\n')
            if (child !is ListItem && child !is Paragraph && child !is Heading) append('\n')
        }
    }
}

private fun AnnotatedString.Builder.renderList(parent: Node, color: Color, linkColor: Color, start: Int?) {
    var child = parent.firstChild
    var number = start ?: 1
    while (child != null) {
        if (child is ListItem) {
            if (length > 0 && toAnnotatedString().text.lastOrNull() != '\n') append('\n')
            append(if (start == null) "• " else "${number}. ")
            renderBlocks(child, color, linkColor)
            if (start != null) number++
        }
        child = child.next
    }
}

private fun AnnotatedString.Builder.renderInline(parent: Node, color: Color, linkColor: Color) {
    var child = parent.firstChild
    while (child != null) {
        when (child) {
            is MarkdownNodeText -> append(child.literal)
            is Code -> {
                pushStyle(SpanStyle(background = color.copy(alpha = 0.12f), fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace))
                append(child.literal)
                pop()
            }
            is Emphasis -> withStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) { renderInline(child, color, linkColor) }
            is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { renderInline(child, color, linkColor) }
            is Link -> withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) { renderInline(child, color, linkColor) }
            is SoftLineBreak -> append(' ')
            is HardLineBreak -> append('\n')
            is HtmlInline -> append(child.literal)
            else -> renderInline(child, color, linkColor)
        }
        child = child.next
    }
}

private fun AnnotatedString.Builder.withStyle(style: SpanStyle, block: AnnotatedString.Builder.() -> Unit) {
    pushStyle(style)
    block()
    pop()
}

private fun AnnotatedString.Builder.appendCode(code: String, color: Color) {
    if (length > 0 && toAnnotatedString().text.lastOrNull() != '\n') append('\n')
    pushStyle(SpanStyle(background = color.copy(alpha = 0.09f), fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace))
    append(code.trimEnd())
    pop()
}
