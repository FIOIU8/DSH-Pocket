package com.fioiu8.dshpocket

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import com.fioiu8.dshpocket.ui.MarkdownSegment
import com.fioiu8.dshpocket.ui.buildMarkdownSegments
import com.fioiu8.dshpocket.ui.markdownToAnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the GFM table/strikethrough rendering split: a table becomes its own segment so
 * the chat screen can lay it out as a scrollable grid, while everything between tables
 * stays one incremental AnnotatedString.
 */
class MarkdownTableTest {

    @Test
    fun `tables split into their own segment with header and rows`() {
        val markdown = "before\n\n| a | b |\n| --- | --- |\n| 1 | 2 |\n| 3 | 4 |\n\nafter"
        val segments = buildMarkdownSegments(markdown, Color.Black, Color.Blue, 16f)

        assertEquals(3, segments.size)
        val table = segments[1] as MarkdownSegment.Table
        assertEquals(listOf("a", "b"), table.header.map { it.text })
        assertEquals(2, table.rows.size)
        assertEquals(listOf("1", "2"), table.rows[0].map { it.text })
        assertEquals(listOf("3", "4"), table.rows[1].map { it.text })
        assertEquals("before", (segments[0] as MarkdownSegment.Text).annotated.text.trim())
        assertEquals("after", (segments[2] as MarkdownSegment.Text).annotated.text.trim())
    }

    @Test
    fun `plain documents stay one text segment`() {
        val segments = buildMarkdownSegments("just **text**\n\n- a\n- b", Color.Black, Color.Blue, 16f)
        assertEquals(1, segments.size)
        assertTrue(segments.single() is MarkdownSegment.Text)
    }

    @Test
    fun `tables without a delimiter row still render rows`() {
        val markdown = "| x | y |\n| - | - |\n| 1 | 2 |"
        val segments = buildMarkdownSegments(markdown, Color.Black, Color.Blue, 16f)
        val table = segments.filterIsInstance<MarkdownSegment.Table>().single()
        assertEquals(listOf("x", "y"), table.header.map { it.text })
        assertEquals(1, table.rows.size)
    }

    @Test
    fun `strikethrough renders a line-through span`() {
        val rendered = markdownToAnnotatedString("~~gone~~ kept", Color.Black)
        assertEquals("gone kept", rendered.text)
        assertTrue(rendered.spanStyles.any { it.item.textDecoration == TextDecoration.LineThrough })
    }

    @Test
    fun `table text does not leak pipe syntax into text segments`() {
        val segments = buildMarkdownSegments("| a | b |\n| --- | --- |\n| 1 | 2 |", Color.Black, Color.Blue, 16f)
        assertTrue(segments.filterIsInstance<MarkdownSegment.Text>().isEmpty())
    }
}
