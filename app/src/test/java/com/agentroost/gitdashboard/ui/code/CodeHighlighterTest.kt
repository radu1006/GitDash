package com.agentroost.gitdashboard.ui.code

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.agentroost.gitdashboard.ui.theme.CodePaletteDark
import com.agentroost.gitdashboard.ui.theme.CodePaletteLight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Language detection and the tokeniser, tested without Android. */
class CodeHighlighterTest {

    @Test
    fun `detects JVM languages from the extension`() {
        assertEquals(CodeLanguage.KOTLIN, CodeLanguage.fromFileName("Main.kt"))
        assertEquals(CodeLanguage.KOTLIN, CodeLanguage.fromFileName("build.gradle.kts"))
        assertEquals(CodeLanguage.JAVA, CodeLanguage.fromFileName("Main.JAVA"))
    }

    @Test
    fun `detects web languages from the extension`() {
        assertEquals(CodeLanguage.JAVASCRIPT, CodeLanguage.fromFileName("app.js"))
        assertEquals(CodeLanguage.JAVASCRIPT, CodeLanguage.fromFileName("app.jsx"))
        assertEquals(CodeLanguage.JAVASCRIPT, CodeLanguage.fromFileName("app.mjs"))
        assertEquals(CodeLanguage.JAVASCRIPT, CodeLanguage.fromFileName("app.cjs"))
        assertEquals(CodeLanguage.TYPESCRIPT, CodeLanguage.fromFileName("app.ts"))
        assertEquals(CodeLanguage.TYPESCRIPT, CodeLanguage.fromFileName("app.tsx"))
        assertEquals(CodeLanguage.TYPESCRIPT, CodeLanguage.fromFileName("app.mts"))
        assertEquals(CodeLanguage.TYPESCRIPT, CodeLanguage.fromFileName("app.cts"))
    }

    @Test
    fun `detects data and markup languages`() {
        assertEquals(CodeLanguage.PYTHON, CodeLanguage.fromFileName("main.py"))
        assertEquals(CodeLanguage.PYTHON, CodeLanguage.fromFileName("stub.pyi"))
        assertEquals(CodeLanguage.JSON, CodeLanguage.fromFileName("data.json"))
        assertEquals(CodeLanguage.JSON, CodeLanguage.fromFileName("data.jsonc"))
        assertEquals(CodeLanguage.YAML, CodeLanguage.fromFileName("ci.yaml"))
        assertEquals(CodeLanguage.YAML, CodeLanguage.fromFileName("ci.yml"))
        assertEquals(CodeLanguage.MARKDOWN, CodeLanguage.fromFileName("README.md"))
        assertEquals(CodeLanguage.MARKDOWN, CodeLanguage.fromFileName("NOTES.markdown"))
        assertEquals(CodeLanguage.XML, CodeLanguage.fromFileName("res.xml"))
        assertEquals(CodeLanguage.XML, CodeLanguage.fromFileName("index.html"))
        assertEquals(CodeLanguage.XML, CodeLanguage.fromFileName("index.htm"))
        assertEquals(CodeLanguage.XML, CodeLanguage.fromFileName("icon.svg"))
        assertEquals(CodeLanguage.XML, CodeLanguage.fromFileName("Info.plist"))
        assertEquals(CodeLanguage.SHELL, CodeLanguage.fromFileName("run.sh"))
        assertEquals(CodeLanguage.SHELL, CodeLanguage.fromFileName("run.bash"))
        assertEquals(CodeLanguage.SHELL, CodeLanguage.fromFileName("run.zsh"))
    }

    @Test
    fun `falls back to plain text for unknown or missing extensions`() {
        assertEquals(CodeLanguage.PLAIN, CodeLanguage.fromFileName("notes.txt"))
        assertEquals(CodeLanguage.PLAIN, CodeLanguage.fromFileName("Makefile"))
        assertEquals(CodeLanguage.PLAIN, CodeLanguage.fromFileName(".gitignore"))
        assertEquals(CodeLanguage.PLAIN, CodeLanguage.fromFileName("archive.tar.gz"))
        assertEquals(CodeLanguage.PLAIN, CodeLanguage.fromFileName(""))
        assertEquals(CodeLanguage.PLAIN, CodeLanguage.fromFileName("trailing."))
    }

    @Test
    fun `languageFor delegates to the extension mapping`() {
        assertEquals(CodeLanguage.KOTLIN, CodeHighlighter.languageFor("src/Main.kt"))
        assertEquals(CodeLanguage.PLAIN, CodeHighlighter.languageFor("src/unknown"))
    }

    @Test
    fun `counts lines including trailing empty line`() {
        assertEquals(1, CodeHighlighter.countLines(""))
        assertEquals(1, CodeHighlighter.countLines("single"))
        assertEquals(2, CodeHighlighter.countLines("single\n"))
        assertEquals(3, CodeHighlighter.countLines("\n\n"))
    }

    @Test
    fun `line numbers cover every line`() {
        val highlighted = CodeHighlighter.highlight("a\nb\nc", CodeLanguage.PLAIN)

        assertEquals(3, highlighted.lineCount)
        assertEquals("1\n2\n3", highlighted.lineNumbers)
        assertEquals("a\nb\nc", highlighted.annotated.text)
    }

    @Test
    fun `plain text gets no span styles`() {
        val highlighted = CodeHighlighter.highlight("just some text 123", CodeLanguage.PLAIN)

        assertTrue(highlighted.annotated.spanStyles.isEmpty())
    }

    @Test
    fun `kotlin keywords comments strings annotations and numbers are styled`() {
        val code = """
            // a comment
            @Composable
            fun greet(name: String): String = "hi ${'$'}name"
            val count = 42
        """.trimIndent()

        val highlighted = CodeHighlighter.highlight(code, CodeLanguage.KOTLIN)
        val styled = highlighted.annotated.spanStyles
        val byColor = styled.groupBy { it.item.color }

        assertEquals(code, highlighted.annotated.text)
        assertTrue("keywords", byColor.containsKey(CodeTheme.Keyword))
        assertTrue("comments", byColor.containsKey(CodeTheme.Comment))
        assertTrue("strings", byColor.containsKey(CodeTheme.StringLiteral))
        assertTrue("annotations", byColor.containsKey(CodeTheme.Annotation))
        assertTrue("numbers", byColor.containsKey(CodeTheme.Number))
    }

    @Test
    fun `json keys are coloured differently from string values`() {
        val highlighted = CodeHighlighter.highlight("""{"key": "value"}""", CodeLanguage.JSON)
        val colors = highlighted.annotated.spanStyles.map { it.item.color }

        assertTrue("property colour for the key", CodeTheme.Property in colors)
        assertTrue("string colour for the value", CodeTheme.StringLiteral in colors)
    }

    @Test
    fun `every language preserves the input text`() {
        val code = "line one\nline two <>&\"'\n\tindent\n"

        for (language in CodeLanguage.entries) {
            assertEquals(
                "text changed for $language",
                code,
                CodeHighlighter.highlight(code, language).annotated.text,
            )
        }
    }

    @Test
    fun `text above the highlight budget is returned unstyled but intact`() {
        val code = "a".repeat(CodeHighlighter.MAX_HIGHLIGHT_CHARS + 1)

        val highlighted = CodeHighlighter.highlight(code, CodeLanguage.KOTLIN)

        assertEquals(code.length, highlighted.annotated.text.length)
        assertTrue(highlighted.annotated.spanStyles.isEmpty())
        assertEquals(1, highlighted.lineCount)
    }

    @Test
    fun `markdown headings and fences are styled`() {
        val code = "# Title\n\n```\ncode\n```\n- item\n"
        val highlighted = CodeHighlighter.highlight(code, CodeLanguage.MARKDOWN)
        val colors = highlighted.annotated.spanStyles.map { it.item.color }

        assertTrue(CodeTheme.Heading in colors)
        assertTrue(CodeTheme.Comment in colors)
        assertTrue(CodeTheme.Keyword in colors)
    }

    @Test
    fun `yaml keys and comments are styled`() {
        val code = "name: value # comment\nlist:\n  - one\n"
        val highlighted = CodeHighlighter.highlight(code, CodeLanguage.YAML)
        val colors = highlighted.annotated.spanStyles.map { it.item.color }

        assertTrue(CodeTheme.Property in colors)
        assertTrue(CodeTheme.Comment in colors)
    }

    // ------------------------------------------------ theme recolouring (light/dark code surface)

    @Test
    fun `recolouring with the same palette returns the same instance`() {
        val annotated = CodeHighlighter.highlight("val x = 1", CodeLanguage.KOTLIN).annotated

        assertSame(annotated, annotated.recolored(from = CodePaletteDark, to = CodePaletteDark))
    }

    @Test
    fun `recolouring maps every highlighted token onto the light palette`() {
        val code = """
            // a comment
            @Composable
            fun greet(name: String): String = "hi"
            val count = 42
        """.trimIndent()
        val original = CodeHighlighter.highlight(code, CodeLanguage.KOTLIN).annotated
        assertTrue("expected styled spans to recolour", original.spanStyles.isNotEmpty())

        val recoloured = original.recolored(from = CodePaletteDark, to = CodePaletteLight)

        assertEquals(original.text, recoloured.text)
        assertEquals(
            "span ranges must not move",
            original.spanStyles.map { it.start to it.end },
            recoloured.spanStyles.map { it.start to it.end },
        )
        val darkColors = setOf(
            CodePaletteDark.keyword,
            CodePaletteDark.stringLiteral,
            CodePaletteDark.comment,
            CodePaletteDark.number,
            CodePaletteDark.property,
            CodePaletteDark.annotation,
            CodePaletteDark.tag,
            CodePaletteDark.attribute,
            CodePaletteDark.heading,
        )
        val lightColors = setOf(
            CodePaletteLight.keyword,
            CodePaletteLight.stringLiteral,
            CodePaletteLight.comment,
            CodePaletteLight.number,
            CodePaletteLight.property,
            CodePaletteLight.annotation,
            CodePaletteLight.tag,
            CodePaletteLight.attribute,
            CodePaletteLight.heading,
        )
        val recolouredColors = recoloured.spanStyles.map { it.item.color }

        assertTrue(
            "no dark token colour may survive the remap",
            recoloured.spanStyles.none { it.item.color in darkColors },
        )
        assertTrue(
            "every span must use the light palette",
            recoloured.spanStyles.all { it.item.color in lightColors },
        )
        assertTrue("keywords keep their colour family", CodePaletteLight.keyword in recolouredColors)
    }

    @Test
    fun `recolouring keeps span colours it does not know about`() {
        val custom = Color(0xFF123456)
        val annotated = AnnotatedString.Builder("custom").apply {
            addStyle(SpanStyle(color = custom, fontWeight = FontWeight.Bold), 0, 6)
        }.toAnnotatedString()

        val recoloured = annotated.recolored(from = CodePaletteDark, to = CodePaletteLight)
        val span = recoloured.spanStyles.single()

        assertEquals(custom, span.item.color)
        assertEquals("other span attributes are preserved", FontWeight.Bold, span.item.fontWeight)
        assertEquals(0, span.start)
        assertEquals(6, span.end)
    }

    @Test
    fun `recolouring preserves paragraph styles`() {
        val paragraph = ParagraphStyle(textAlign = TextAlign.Center)
        val annotated = AnnotatedString.Builder("val x = 1").apply {
            addStyle(SpanStyle(color = CodeTheme.Keyword), 0, 3)
            addStyle(paragraph, 0, 9)
        }.toAnnotatedString()

        val recoloured = annotated.recolored(from = CodePaletteDark, to = CodePaletteLight)

        assertEquals(1, recoloured.paragraphStyles.size)
        assertEquals(paragraph, recoloured.paragraphStyles.single().item)
        assertEquals(CodePaletteLight.keyword, recoloured.spanStyles.single().item.color)
    }
}
