package com.agentroost.gitdashboard.ui.readme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Behaviour of the pure markdown parser that feeds the README viewer. */
class MarkdownParserTest {

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

    private fun paragraph(block: MarkdownBlock): String {
        val paragraph = block as MarkdownBlock.Paragraph
        return flatten(paragraph.spans)
    }

    @Test
    fun `atx headings keep the level and inline formatting`() {
        val blocks = parse("# Title\n\n### **Bold** heading ###")

        assertEquals(2, blocks.size)
        val first = blocks[0] as MarkdownBlock.Heading
        assertEquals(1, first.level)
        assertEquals(listOf(MarkdownSpan.Plain("Title")), first.spans)

        val second = blocks[1] as MarkdownBlock.Heading
        assertEquals(3, second.level)
        assertEquals(
            listOf(
                MarkdownSpan.Strong(listOf(MarkdownSpan.Plain("Bold"))),
                MarkdownSpan.Plain(" heading"),
            ),
            second.spans,
        )
    }

    @Test
    fun `paragraphs keep strong emphasis code and link spans`() {
        val blocks = parse("Hello **bold** and *italic* and `code` and [site](https://example.com).")

        val spans = (blocks.single() as MarkdownBlock.Paragraph).spans
        assertEquals(
            listOf(
                MarkdownSpan.Plain("Hello "),
                MarkdownSpan.Strong(listOf(MarkdownSpan.Plain("bold"))),
                MarkdownSpan.Plain(" and "),
                MarkdownSpan.Emphasis(listOf(MarkdownSpan.Plain("italic"))),
                MarkdownSpan.Plain(" and "),
                MarkdownSpan.Code("code"),
                MarkdownSpan.Plain(" and "),
                MarkdownSpan.Link("site", "https://example.com"),
                MarkdownSpan.Plain("."),
            ),
            spans,
        )
    }

    @Test
    fun `underscores also mark strong and emphasis but not inside words`() {
        val blocks = parse("a _b_ __c__ snake_case_name")

        assertEquals(
            listOf(
                MarkdownSpan.Plain("a "),
                MarkdownSpan.Emphasis(listOf(MarkdownSpan.Plain("b"))),
                MarkdownSpan.Plain(" "),
                MarkdownSpan.Strong(listOf(MarkdownSpan.Plain("c"))),
                MarkdownSpan.Plain(" snake_case_name"),
            ),
            (blocks.single() as MarkdownBlock.Paragraph).spans,
        )
    }

    @Test
    fun `bullet lists keep one block per item`() {
        val blocks = parse("- one\n- two **bold**")

        val list = blocks.single() as MarkdownBlock.BulletList
        assertEquals(2, list.items.size)
        assertEquals("one", paragraph(list.items[0].single()))
        assertEquals(
            listOf(
                MarkdownSpan.Plain("two "),
                MarkdownSpan.Strong(listOf(MarkdownSpan.Plain("bold"))),
            ),
            (list.items[1].single() as MarkdownBlock.Paragraph).spans,
        )
    }

    @Test
    fun `ordered lists keep the first number`() {
        val blocks = parse("3. three\n4. four")

        val list = blocks.single() as MarkdownBlock.OrderedList
        assertEquals(3, list.start)
        assertEquals(listOf("three", "four"), list.items.map { paragraph(it.single()) })
    }

    @Test
    fun `fenced code keeps the info string language and the code`() {
        val blocks = parse("```kotlin\nval x = 1\nprintln(x)\n```")

        assertEquals(
            MarkdownBlock.CodeBlock("kotlin", "val x = 1\nprintln(x)"),
            blocks.single(),
        )
    }

    @Test
    fun `tilde fences work too and a missing info string is null`() {
        val blocks = parse("~~~python\nprint(1)\n~~~\n\n~~~\nplain\n~~~")

        assertEquals(MarkdownBlock.CodeBlock("python", "print(1)"), blocks[0])
        assertEquals(MarkdownBlock.CodeBlock(null, "plain"), blocks[1])
    }

    @Test
    fun `blockquotes contain the parsed blocks`() {
        val blocks = parse("> quoted **text**\n> second line")

        val quote = blocks.single() as MarkdownBlock.Quote
        val paragraph = quote.blocks.single() as MarkdownBlock.Paragraph
        assertEquals(
            listOf(
                MarkdownSpan.Plain("quoted "),
                MarkdownSpan.Strong(listOf(MarkdownSpan.Plain("text"))),
                MarkdownSpan.Plain("\nsecond line"),
            ),
            paragraph.spans,
        )
    }

    @Test
    fun `horizontal rules are dividers`() {
        val blocks = parse("a\n\n---\n\n***\n\n___")

        assertEquals(3, blocks.count { it == MarkdownBlock.Divider })
        assertEquals(MarkdownBlock.Divider, blocks[1])
    }

    @Test
    fun `standalone images become an image block with and without a title`() {
        val blocks = parse(
            "![shot](docs/shot.png \"Screenshot\")\n\n![plain](images/plain.png)",
        )

        assertEquals(
            MarkdownBlock.Image("shot", "docs/shot.png", "Screenshot"),
            blocks[0],
        )
        assertEquals(
            MarkdownBlock.Image("plain", "images/plain.png", null),
            blocks[1],
        )
    }

    @Test
    fun `a linked badge image renders as the image`() {
        val blocks = parse("[![Build](badge.svg)](https://ci.example.com)")

        assertEquals(MarkdownBlock.Image("Build", "badge.svg", null), blocks.single())
    }

    @Test
    fun `an image in a heading degrades to its alt text`() {
        val heading = parse("# ![Logo](logo.png)").single() as MarkdownBlock.Heading

        assertEquals(1, heading.level)
        assertEquals(listOf(MarkdownSpan.Plain("Logo")), heading.spans)
    }

    @Test
    fun `an image inside a paragraph splits the prose around it`() {
        val blocks = parse("before ![shot](a.png) after")

        assertEquals(3, blocks.size)
        assertEquals("before ", paragraph(blocks[0]))
        assertEquals(MarkdownBlock.Image("shot", "a.png", null), blocks[1])
        assertEquals(" after", paragraph(blocks[2]))
    }

    @Test
    fun `an image inside a list item is a block of that item`() {
        val blocks = parse("- ![badge](badge.svg)")

        val list = blocks.single() as MarkdownBlock.BulletList
        assertEquals(MarkdownBlock.Image("badge", "badge.svg", null), list.items.single().single())
    }

    @Test
    fun `backslash escapes produce literal characters`() {
        val blocks = parse("\\*not italic\\* and \\[not a link\\] and \\\\ slash")

        assertEquals("*not italic* and [not a link] and \\ slash", paragraph(blocks.single()))
    }

    @Test
    fun `an escaped hash is not a heading`() {
        val blocks = parse("\\# not a heading")

        assertTrue(blocks.single() is MarkdownBlock.Paragraph)
        assertEquals("# not a heading", paragraph(blocks.single()))
    }

    @Test
    fun `CRLF input parses the same as LF`() {
        val blocks = parse("# Title\r\n\r\nText\r\n")

        assertEquals(1, (blocks[0] as MarkdownBlock.Heading).level)
        assertEquals("Text", paragraph(blocks[1]))
    }

    @Test
    fun `github tables degrade to plain paragraph text`() {
        val blocks = parse("| Name | Value |\n| --- | --- |\n| a | b |")

        assertEquals(1, blocks.size)
        val text = paragraph(blocks.single())
        assertTrue(text.contains("| Name | Value |"))
        assertTrue(text.contains("| a | b |"))
    }

    @Test
    fun `raw html tags are stripped but their text is kept`() {
        val blocks = parse("<p align=\"center\">Hello <strong>world</strong><br>again</p>")

        assertEquals("Hello world\nagain", paragraph(blocks.single()))
    }

    @Test
    fun `malformed input never throws`() {
        val inputs = listOf(
            "",
            "\u0000",
            "```",
            "~~~",
            "[",
            "![",
            "![a](",
            "***",
            "> > >",
            "[[[[",
            "`",
            "\\",
            "#",
            "-",
            "1.",
            "\r",
            "\r\n\r",
            "<",
            "<!--",
            "![a](b \"unterminated)",
            "**",
            "__",
            "%",
            "||||",
            "```\nunclosed fence",
        )

        for (input in inputs) {
            val result = runCatching { MarkdownParser.parse(input) }
            assertTrue("parse threw for ${input.replace("\n", "\\n")}: $result", result.isSuccess)
            assertEquals(result.getOrThrow(), MarkdownParser.parse(input))
        }
    }

    @Test
    fun `an unclosed fence still yields a code block with the remaining lines`() {
        val blocks = parse("```\nval x = 1")

        assertEquals(MarkdownBlock.CodeBlock(null, "val x = 1"), blocks.single())
    }

    @Test
    fun `a link label may contain nested brackets and an escaped bracket`() {
        val blocks = parse("[a [b] \\] c](https://example.com)")

        assertEquals(
            listOf(MarkdownSpan.Link("a [b] ] c", "https://example.com")),
            (blocks.single() as MarkdownBlock.Paragraph).spans,
        )
    }

    @Test
    fun `a malformed link is kept as literal text`() {
        val blocks = parse("[broken](url")

        assertEquals("[broken](url", paragraph(blocks.single()))
    }
}
