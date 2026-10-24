package com.fioiu8.dshpocket.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
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
import org.commonmark.node.HtmlBlock
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
    val rendered = remember(markdown, color, linkColor, fontSize) {
        markdownToAnnotatedString(markdown, color, linkColor, fontSize.value)
    }
    Text(rendered, modifier = modifier, fontSize = fontSize, lineHeight = lineHeight, color = color)
}

internal fun markdownToAnnotatedString(
    markdown: String,
    color: Color,
    linkColor: Color = Color(0xFF4A78B8),
    baseFontSize: Float = 16f,
): AnnotatedString {
    val document = Parser.builder().build().parse(markdown)
    val renderer = MarkdownRenderer(color, linkColor, baseFontSize)
    renderer.renderBlocks(document)
    return renderer.builder.toAnnotatedString()
}

/**
 * Streaming chat rebuilds this string for every chunk, so the renderer tracks the last
 * written character and the quote/list depth incrementally. Materialising the whole
 * AnnotatedString at every block boundary (the previous approach) made long replies
 * quadratic in the amount of streamed text.
 */
private class MarkdownRenderer(val color: Color, val linkColor: Color, val baseFontSize: Float) {
    val builder = AnnotatedString.Builder()
    var lastChar: Char? = null
    var quoteDepth = 0
    var listDepth = 0

    fun ensureLineStart() {
        if (lastChar != null && lastChar != '\n') append('\n')
    }

    fun append(text: String) {
        if (text.isEmpty()) return
        // Inside a quote, continued lines keep the bar prefix; nested quotes stack bars.
        val effective = if (quoteDepth > 0) text.replace("\n", "\n" + BAR.repeat(quoteDepth)) else text
        builder.append(effective)
        lastChar = effective.last()
    }

    fun append(char: Char) {
        val effective = if (char == '\n' && quoteDepth > 0) "\n" + BAR.repeat(quoteDepth) else char.toString()
        builder.append(effective)
        lastChar = effective.last()
    }

    fun renderBlocks(parent: Node) {
        var child = parent.firstChild
        while (child != null) {
            when (child) {
                is Paragraph -> renderInline(child)
                is Heading -> {
                    val size = when (child.level) {
                        1 -> 1.35f
                        2 -> 1.2f
                        else -> 1.08f
                    }
                    builder.pushStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = (baseFontSize * size).sp))
                    renderInline(child)
                    builder.pop()
                }
                is FencedCodeBlock -> appendCode(child.literal)
                is IndentedCodeBlock -> appendCode(child.literal)
                is BulletList -> renderList(child, null)
                // markerStartNumber replaces the deprecated startNumber (commonmark 0.22+);
                // it is null when the start marker is unset, so fall back to the old default of 1.
                is OrderedList -> renderList(child, child.markerStartNumber ?: 1)
                is BlockQuote -> {
                    builder.pushStyle(SpanStyle(color = color.copy(alpha = 0.82f), fontWeight = FontWeight.Medium))
                    quoteDepth += 1
                    // One bar per nesting level; outer levels already wrote theirs, and
                    // continued lines get the same prefix from append()'s newline handling.
                    append(BAR)
                    renderBlocks(child)
                    quoteDepth -= 1
                    builder.pop()
                }
                is ThematicBreak -> append("──────────")
                // commonmark gives HTML blocks no children; without this case their
                // content silently vanished. Showing the raw markup beats showing nothing.
                is HtmlBlock -> append(child.literal)
                else -> renderInline(child)
            }
            child = child.next
            if (child != null && lastChar != null) {
                ensureLineStart()
                if (child !is ListItem && child !is Paragraph && child !is Heading) append('\n')
            }
        }
    }

    fun renderList(parent: Node, start: Int?) {
        listDepth += 1
        var child = parent.firstChild
        var number = start ?: 1
        while (child != null) {
            if (child is ListItem) {
                ensureLineStart()
                append(INDENT.repeat(listDepth - 1) + if (start == null) "• " else "${number}. ")
                renderBlocks(child)
                if (start != null) number++
            }
            child = child.next
        }
        listDepth -= 1
    }

    fun renderInline(parent: Node) {
        var child = parent.firstChild
        while (child != null) {
            when (child) {
                is MarkdownNodeText -> append(child.literal)
                is Code -> {
                    builder.pushStyle(SpanStyle(background = color.copy(alpha = 0.12f), fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace))
                    append(child.literal)
                    builder.pop()
                }
                is Emphasis -> withStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) { renderInline(child) }
                is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { renderInline(child) }
                is Link -> {
                    // A real link annotation: tappable in Text, styled like a link, and
                    // unlike the old style-only rendering the URL is no longer invisible.
                    builder.pushLink(
                        LinkAnnotation.Url(
                            child.destination.orEmpty(),
                            TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
                        ),
                    )
                    renderInline(child)
                    builder.pop()
                }
                is SoftLineBreak -> append(' ')
                is HardLineBreak -> append('\n')
                is HtmlInline -> append(child.literal)
                else -> renderInline(child)
            }
            child = child.next
        }
    }

    private fun withStyle(style: SpanStyle, block: MarkdownRenderer.() -> Unit) {
        builder.pushStyle(style)
        block()
        builder.pop()
    }

    private fun appendCode(code: String) {
        ensureLineStart()
        builder.pushStyle(SpanStyle(background = color.copy(alpha = 0.09f), fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace))
        append(code.trimEnd())
        builder.pop()
    }

    companion object {
        private const val BAR = "│ "
        private const val INDENT = "  "
    }
}
