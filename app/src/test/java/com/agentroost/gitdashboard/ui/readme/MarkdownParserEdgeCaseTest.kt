package com.agentroost.gitdashboard.ui.readme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Edge cases of the markdown parser that the README viewer relies on, exercised directly.
 *
 * These complement [MarkdownParserTest]: nesting, the long-line budget, empty image sources and the
 * "unclosed fence must not leak image references" rule. None of these inputs may throw or hang.
 */
class MarkdownParserEdgeCaseTest {

    private fun parse(markdown: String): List<MarkdownBlock> = MarkdownParser.parse(markdown)

    private fun flatten(spans: List<MarkdownSpan>): String = spans.joinToString("") { span ->
        when (span) {
            is MarkdownSpan.Plain -> span.text
            is MarkdownSpan.Strong -> flatten(span.spans)
            is MarkdownSpan.Emphasis -> flatten(span.spans)
            is MarkdownSpan.Code -> span.text
            is MarkdownSpan.Link -> span.text
        }
    }

    private fun paragraphText(block: MarkdownBlock): String =
        flatten((block as MarkdownBlock.Paragraph).spans)

    @Test
    fun `a nested bullet list stays nested inside its parent item`() {
        val blocks = parse("- parent\n  - child\n- sibling")

        val outer = blocks.single() as MarkdownBlock.BulletList
        assertEquals(2, outer.items.size)
        assertEquals("parent", paragraphText(outer.items[0][0]))

        val nested = outer.items[0][1] as MarkdownBlock.BulletList
        assertEquals("child", paragraphText(nested.items.single().single()))
        assertEquals("sibling", paragraphText(outer.items[1].single()))
    }

    @Test
    fun `a nested ordered list keeps its own numbering`() {
        val blocks = parse("1. first\n   2. second\n2. third")

        val outer = blocks.single() as MarkdownBlock.OrderedList
        assertEquals(2, outer.items.size)
        val nested = outer.items[0][1] as MarkdownBlock.OrderedList
        assertEquals(2, nested.start)
        assertEquals("second", paragraphText(nested.items.single().single()))
    }

    @Test
    fun `deeply nested blockquotes stop at the depth guard instead of throwing`() {
        // 40 leading '>' force parseQuote to recurse; MAX_BLOCK_DEPTH must stop it cleanly.
        val markdown = buildString {
            for (level in 0 until 40) append(">")
            append(" deep text")
        }

        val result = runCatching { parse(markdown) }

        assertTrue("parser threw on deeply nested quotes: $result", result.isSuccess)
        assertTrue(result.getOrThrow().isNotEmpty())
    }

    @Test
    fun `a heavily indented list still parses without throwing`() {
        val markdown = buildString {
            for (level in 0 until 40) {
                append("  ".repeat(level))
                append("- level$level\n")
            }
        }

        val result = runCatching { parse(markdown) }

        assertTrue("parser threw on an over-indented list: $result", result.isSuccess)
        assertTrue(result.getOrThrow().isNotEmpty())
    }

    @Test
    fun `a very long single line parses without losing content`() {
        // The ViewModel caps a README at 200 000 chars, so this is the widest line that can reach us.
        val line = "a".repeat(200_000)

        val start = System.nanoTime()
        val blocks = parse(line)
        val elapsedMillis = (System.nanoTime() - start) / 1_000_000

        assertEquals(line, paragraphText(blocks.single()))
        assertTrue("a 200k-char line took ${elapsedMillis}ms", elapsedMillis < 10_000)
    }

    @Test
    fun `a hostile run of unmatched brackets parses in about linear time`() {
        // Each '[' (and each '[' of '![') has no closing delimiter. A per-position forward rescan
        // makes this quadratic; the guard is generous so only the algorithmic blow-up trips it.
        val inputs = listOf("[", "![")

        for (unit in inputs) {
            val markdown = unit.repeat(200_000 / unit.length)

            val start = System.nanoTime()
            val blocks = parse(markdown)
            val elapsedMillis = (System.nanoTime() - start) / 1_000_000

            assertEquals(markdown, paragraphText(blocks.single()))
            assertTrue(
                "a ${markdown.length}-char run of \"$unit\" took ${elapsedMillis}ms",
                elapsedMillis < 1_000,
            )
        }
    }

    @Test
    fun `quoted link candidates do not rescan the whole line`() {
        // Every '[' closes and is followed by '(', and a quote later in the line forces the
        // quote-aware paren scan for each candidate. Its bounded window keeps this linear.
        val markdown = "[a](".repeat(50_000) + "')"

        val start = System.nanoTime()
        val blocks = parse(markdown)
        val elapsedMillis = (System.nanoTime() - start) / 1_000_000

        assertEquals(markdown, paragraphText(blocks.single()))
        assertTrue("a ${markdown.length}-char quoted run took ${elapsedMillis}ms", elapsedMillis < 1_000)
    }

    @Test
    fun `an empty image source still becomes an image block with an empty src`() {
        val blocks = parse("![alt]()")

        assertEquals(MarkdownBlock.Image("alt", "", null), blocks.single())
    }

    @Test
    fun `an image with an empty alt keeps the source`() {
        val blocks = parse("![](./shot.png)")

        assertEquals(MarkdownBlock.Image("", "./shot.png", null), blocks.single())
    }

    @Test
    fun `an unclosed code fence swallows image syntax instead of producing an image`() {
        val blocks = parse("```md\n![shot](docs/shot.png)\n")

        val code = blocks.single() as MarkdownBlock.CodeBlock
        assertEquals("md", code.language)
        // The trailing newline is part of the unclosed fence's body.
        assertEquals("![shot](docs/shot.png)\n", code.code)
    }

    @Test
    fun `CRLF markdown keeps the image source and the surrounding prose`() {
        val blocks = parse("Intro\r\n\r\n![shot](images/shot.png)\r\n\r\nOutro\r\n")

        assertEquals(3, blocks.size)
        assertEquals("Intro", paragraphText(blocks[0]))
        assertEquals(MarkdownBlock.Image("shot", "images/shot.png", null), blocks[1])
        assertEquals("Outro", paragraphText(blocks[2]))
    }

    @Test
    fun `a title in parentheses is parsed without keeping the brackets`() {
        val blocks = parse("![shot](shot.png (a screenshot))")

        assertEquals(MarkdownBlock.Image("shot", "shot.png", "a screenshot"), blocks.single())
    }

    @Test
    fun `image syntax with unbalanced brackets is kept as text`() {
        val inputs = listOf("![alt]](", "![alt](shot.png", "![alt] (shot.png)", "![]")

        for (input in inputs) {
            val blocks = runCatching { parse(input) }
            assertTrue("parser threw for $input: $blocks", blocks.isSuccess)
            assertTrue(
                "$input must not produce an image block: ${blocks.getOrThrow()}",
                blocks.getOrThrow().none { it is MarkdownBlock.Image },
            )
        }
    }

    @Test
    fun `a malformed document still yields blocks for the readable parts`() {
        val markdown = "# Title\n\n| a | b |\n|---|---|\n\n> quote\n\n```\nopen\n\n**unclosed"

        val blocks = runCatching { parse(markdown) }

        assertTrue("parser threw on malformed markdown: $blocks", blocks.isSuccess)
        assertTrue(blocks.getOrThrow().size >= 3)
    }
}
