package com.fioiu8.dshpocket.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
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

/** GFM tables + strikethrough; the parser is immutable and shared across streaming rebuilds. */
private val MARKDOWN_PARSER: Parser = Parser.builder()
    .extensions(listOf(TablesExtension.create(), StrikethroughExtension.create()))
    .build()

private fun Node.childrenToList(): List<Node> = buildList {
    var child = firstChild
    while (child != null) {
        add(child)
        child = child.next
    }
}

@Composable
internal fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    color: Color,
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val segments = remember(markdown, color, linkColor, fontSize) {
        buildMarkdownSegments(markdown, color, linkColor, fontSize.value)
    }
    Column(modifier = modifier) {
        segments.forEach { segment ->
            when (segment) {
                is MarkdownSegment.Text -> Text(
                    segment.annotated,
                    fontSize = fontSize,
                    lineHeight = lineHeight,
                    color = color,
                )
                is MarkdownSegment.Table -> MarkdownTableBlock(
                    table = segment,
                    color = color,
                    fontSize = fontSize,
                )
            }
        }
    }
}

/** A markdown document split at top-level tables: text runs render as one AnnotatedString, tables as a grid. */
internal sealed interface MarkdownSegment {
    data class Text(val annotated: AnnotatedString) : MarkdownSegment
    data class Table(val header: List<AnnotatedString>, val rows: List<List<AnnotatedString>>) : MarkdownSegment
}

internal fun buildMarkdownSegments(
    markdown: String,
    color: Color,
    linkColor: Color,
    baseFontSize: Float,
): List<MarkdownSegment> {
    val document = MARKDOWN_PARSER.parse(markdown)
    val segments = ArrayList<MarkdownSegment>()
    var run = ArrayList<Node>()
    fun flushRun() {
        if (run.isEmpty()) return
        val renderer = MarkdownRenderer(color, linkColor, baseFontSize)
        renderer.renderBlockRun(run)
        segments.add(MarkdownSegment.Text(renderer.builder.toAnnotatedString()))
        run = ArrayList()
    }
    for (block in document.childrenToList()) {
        if (block is TableBlock) {
            flushRun()
            extractTable(block, color, linkColor, baseFontSize)?.let(segments::add)
        } else {
            run.add(block)
        }
    }
    flushRun()
    return segments
}

private fun extractTable(block: TableBlock, color: Color, linkColor: Color, baseFontSize: Float): MarkdownSegment.Table? {
    var head: TableHead? = null
    var body: TableBody? = null
    for (child in block.childrenToList()) {
        when (child) {
            is TableHead -> head = child
            is TableBody -> body = child
        }
    }
    fun cells(row: TableRow): List<AnnotatedString> = row.childrenToList()
        .filterIsInstance<TableCell>()
        .map { cell ->
            val renderer = MarkdownRenderer(color, linkColor, baseFontSize)
            renderer.renderInline(cell)
            renderer.builder.toAnnotatedString()
        }
    val header = head?.childrenToList()?.filterIsInstance<TableRow>()?.firstOrNull()?.let(::cells).orEmpty()
    val rows = body?.childrenToList()?.filterIsInstance<TableRow>()?.map(::cells).orEmpty()
    if (header.isEmpty() && rows.isEmpty()) return null
    return MarkdownSegment.Table(header, rows)
}

internal fun markdownToAnnotatedString(
    markdown: String,
    color: Color,
    linkColor: Color = Color(0xFF4A78B8),
    baseFontSize: Float = 16f,
): AnnotatedString {
    val renderer = MarkdownRenderer(color, linkColor, baseFontSize)
    renderer.renderBlockRun(MARKDOWN_PARSER.parse(markdown).childrenToList())
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

    fun renderBlockRun(blocks: List<Node>) {
        for ((index, block) in blocks.withIndex()) {
            renderBlock(block)
            val next = blocks.getOrNull(index + 1) ?: continue
            if (lastChar != null) {
                ensureLineStart()
                if (next !is ListItem && next !is Paragraph && next !is Heading) append('\n')
            }
        }
    }

    private fun renderBlock(block: Node) {
        when (block) {
            is Paragraph -> renderInline(block)
            is Heading -> {
                val size = when (block.level) {
                    1 -> 1.35f
                    2 -> 1.2f
                    else -> 1.08f
                }
                builder.pushStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = (baseFontSize * size).sp))
                renderInline(block)
                builder.pop()
            }
            is FencedCodeBlock -> appendCode(block.literal)
            is IndentedCodeBlock -> appendCode(block.literal)
            is BulletList -> renderList(block, null)
            // markerStartNumber replaces the deprecated startNumber (commonmark 0.22+);
            // it is null when the start marker is unset, so fall back to the old default of 1.
            is OrderedList -> renderList(block, block.markerStartNumber ?: 1)
            is BlockQuote -> {
                builder.pushStyle(SpanStyle(color = color.copy(alpha = 0.82f), fontWeight = FontWeight.Medium))
                quoteDepth += 1
                // One bar per nesting level; outer levels already wrote theirs, and
                // continued lines get the same prefix from append()'s newline handling.
                append(BAR)
                renderBlockRun(block.childrenToList())
                quoteDepth -= 1
                builder.pop()
            }
            is ThematicBreak -> append("──────────")
            // commonmark gives HTML blocks no children; without this case their
            // content silently vanished. Showing the raw markup beats showing nothing.
            is HtmlBlock -> append(block.literal)
            else -> renderInline(block)
        }
    }

    fun renderList(parent: Node, start: Int?) {
        listDepth += 1
        var number = start ?: 1
        for (child in parent.childrenToList()) {
            if (child is ListItem) {
                ensureLineStart()
                append(INDENT.repeat(listDepth - 1) + if (start == null) "• " else "${number}. ")
                renderBlockRun(child.childrenToList())
                if (start != null) number++
            }
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
                is Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { renderInline(child) }
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

private data class TableCellSpec(val annotated: AnnotatedString, val row: Int, val column: Int, val drawBottomLine: Boolean, val header: Boolean)

/**
 * GFM table as a real grid: a custom layout aligns every column to its widest cell
 * (capped, so wide tables scroll horizontally instead of squeezing the text away).
 */
@Composable
private fun MarkdownTableBlock(table: MarkdownSegment.Table, color: Color, fontSize: TextUnit) {
    val dividerColor = MaterialTheme.colorScheme.outlineVariant
    val headerBackground = MaterialTheme.colorScheme.surfaceContainerLow
    val rows = remember(table) {
        if (table.header.isEmpty()) table.rows else listOf(table.header) + table.rows
    }
    if (rows.isEmpty()) return
    val hasHeaderRow = table.header.isNotEmpty()
    val specs = ArrayList<TableCellSpec>()
    rows.forEachIndexed { rowIndex, row ->
        row.forEachIndexed { columnIndex, cell ->
            specs.add(
                TableCellSpec(
                    annotated = cell,
                    row = rowIndex,
                    column = columnIndex,
                    // The last row's line would double with the container border.
                    drawBottomLine = rowIndex < rows.lastIndex,
                    header = hasHeaderRow && rowIndex == 0,
                ),
            )
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .border(1.dp, dividerColor),
    ) {
        Box(Modifier.horizontalScroll(rememberScrollState())) {
            Layout(
                content = {
                    specs.forEach { spec ->
                        Text(
                            spec.annotated,
                            color = color,
                            modifier = Modifier
                                .then(if (spec.header) Modifier.background(headerBackground) else Modifier)
                                .drawBehind {
                                    if (spec.drawBottomLine || spec.header) {
                                        val y = size.height - 0.5f
                                        drawLine(dividerColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                                    }
                                }
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                        )
                    }
                },
            ) { measurables, constraints ->
                require(measurables.size == specs.size)
                // One cell may not exceed this so wide tables scroll instead of overflowing.
                val looseMax = if (constraints.hasBoundedWidth) constraints.maxWidth else 920
                val natural = measurables.map { it.measure(Constraints(maxWidth = looseMax)) }
                val columnCount = (specs.maxOf { it.column } + 1)
                val columnWidths = IntArray(columnCount)
                for (index in measurables.indices) {
                    val column = specs[index].column
                    columnWidths[column] = maxOf(columnWidths[column], natural[index].width)
                }
                val rowCount = specs.maxOf { it.row } + 1
                val rowHeights = IntArray(rowCount)
                val sized = arrayOfNulls<Placeable?>(measurables.size)
                for (index in measurables.indices) {
                    val spec = specs[index]
                    val width = columnWidths[spec.column]
                    val height = natural[index].height
                    sized[index] = measurables[index].measure(Constraints.fixed(width, height))
                    rowHeights[spec.row] = maxOf(rowHeights[spec.row], height)
                }
                val gridWidth = columnWidths.sum()
                val gridHeight = rowHeights.sum()
                layout(gridWidth, gridHeight) {
                    val columnOffsets = IntArray(columnCount)
                    for (column in 1 until columnCount) columnOffsets[column] = columnOffsets[column - 1] + columnWidths[column - 1]
                    val rowOffsets = IntArray(rowCount)
                    for (row in 1 until rowCount) rowOffsets[row] = rowOffsets[row - 1] + rowHeights[row - 1]
                    for (index in measurables.indices) {
                        val spec = specs[index]
                        sized[index]?.placeRelative(columnOffsets[spec.column], rowOffsets[spec.row])
                    }
                }
            }
        }
    }
}
