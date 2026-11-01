package com.agentroost.gitdashboard.ui.code

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontFamily
import java.util.Locale

/** Languages with a local, token-based highlighter. */
enum class CodeLanguage {
    KOTLIN,
    JAVA,
    JAVASCRIPT,
    TYPESCRIPT,
    PYTHON,
    JSON,
    YAML,
    MARKDOWN,
    XML,
    SHELL,
    PLAIN;

    companion object {
        /** Maps a file name/extension to a language; unknown extensions stay [PLAIN]. */
        fun fromFileName(fileName: String): CodeLanguage {
            val extension = fileName.substringAfterLast('.', missingDelimiterValue = "")
                .lowercase(Locale.ROOT)
            return when (extension) {
                "kt", "kts" -> KOTLIN
                "java" -> JAVA
                "js", "jsx", "mjs", "cjs" -> JAVASCRIPT
                "ts", "tsx", "mts", "cts" -> TYPESCRIPT
                "py", "pyi" -> PYTHON
                "json", "json5", "jsonc" -> JSON
                "yaml", "yml" -> YAML
                "md", "markdown" -> MARKDOWN
                "xml", "html", "htm", "xhtml", "svg", "plist" -> XML
                "sh", "bash", "zsh", "ksh", "fish" -> SHELL
                else -> PLAIN
            }
        }
    }
}

/**
 * Fixed palette for the code viewer.
 *
 * The viewer always paints code on [Background] (a GitHub-dark surface) so the token colours can be
 * resolved when the text is tokenised, off the main thread, instead of threading the Material theme
 * into the ViewModel. The surface stays a dark "code block" in both app themes.
 */
object CodeTheme {
    val Background = Color(0xFF0D1117)
    val Text = Color(0xFFE6EDF3)
    val Gutter = Color(0xFF6E7681)
    internal val Keyword = Color(0xFFFF7B72)
    internal val StringLiteral = Color(0xFFA5D6FF)
    internal val Comment = Color(0xFF8B949E)
    internal val Number = Color(0xFF79C0FF)
    internal val Property = Color(0xFF7EE787)
    internal val Annotation = Color(0xFFD2A8FF)
    internal val Tag = Color(0xFF7EE787)
    internal val Attribute = Color(0xFF79C0FF)
    internal val Heading = Color(0xFFFFA657)
}

/** Shared monospace style: no wrapping and a fixed line height so the gutter stays aligned. */
val CodeTextStyle: TextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 12.sp,
    lineHeight = 18.sp,
    color = CodeTheme.Text,
)

/**
 * Result of highlighting a file: the styled text, the gutter contents and the line count.
 *
 * Built in the ViewModel (on [kotlinx.coroutines.Dispatchers.Default]) so the composable only reads
 * immutable state.
 */
data class HighlightedCode(
    val annotated: AnnotatedString,
    val lineNumbers: String,
    val lineCount: Int,
)

/**
 * Small, dependency-free tokeniser for the languages this app browses.
 *
 * It is deliberately "good enough" rather than a parser: comments, strings, numbers, keywords,
 * annotations, YAML/JSON keys, XML tags/attributes and the usual Markdown markers. Anything it does
 * not understand is rendered with the default code colour, so a wrong guess only costs colour.
 */
object CodeHighlighter {

    /** Inputs larger than this are not tokenised; the viewer refuses to render them anyway. */
    const val MAX_HIGHLIGHT_CHARS = 200_000

    /** Public entry point: [fileName] decides the language (e.g. `Main.kt` -> Kotlin). */
    fun languageFor(fileName: String): CodeLanguage = CodeLanguage.fromFileName(fileName)

    fun highlight(code: String, language: CodeLanguage): HighlightedCode {
        val builder = AnnotatedString.Builder(code)
        if (code.length <= MAX_HIGHLIGHT_CHARS) {
            when (language) {
                CodeLanguage.PLAIN -> Unit
                CodeLanguage.MARKDOWN -> highlightMarkdown(code, builder)
                CodeLanguage.YAML -> highlightYaml(code, builder)
                CodeLanguage.XML -> highlightXml(code, builder)
                else -> highlightCode(code, language, builder)
            }
        }

        val lineCount = countLines(code)
        return HighlightedCode(
            annotated = builder.toAnnotatedString(),
            lineNumbers = buildLineNumbers(lineCount),
            lineCount = lineCount,
        )
    }

    fun countLines(code: String): Int {
        var lines = 1
        for (character in code) {
            if (character == '\n') lines++
        }
        return lines
    }

    private fun buildLineNumbers(lineCount: Int): String {
        val builder = StringBuilder(lineCount * 4)
        for (line in 1..lineCount) {
            if (line > 1) builder.append('\n')
            builder.append(line)
        }
        return builder.toString()
    }

    // ---------------------------------------------------------------- generic code

    private data class CommentBlock(val open: String, val close: String)

    private data class LanguageSpec(
        val lineComments: List<String> = emptyList(),
        val blockComment: CommentBlock? = null,
        val tripleQuoted: Boolean = false,
        val backtickStrings: Boolean = false,
        val annotations: Boolean = false,
        val jsonKeys: Boolean = false,
        val keywords: Set<String> = emptySet(),
    )

    private fun specFor(language: CodeLanguage): LanguageSpec = when (language) {
        CodeLanguage.KOTLIN -> LanguageSpec(
            lineComments = listOf("//"),
            blockComment = CommentBlock("/*", "*/"),
            tripleQuoted = true,
            annotations = true,
            keywords = KOTLIN_KEYWORDS,
        )

        CodeLanguage.JAVA -> LanguageSpec(
            lineComments = listOf("//"),
            blockComment = CommentBlock("/*", "*/"),
            tripleQuoted = true,
            annotations = true,
            keywords = JAVA_KEYWORDS,
        )

        CodeLanguage.JAVASCRIPT -> LanguageSpec(
            lineComments = listOf("//"),
            blockComment = CommentBlock("/*", "*/"),
            backtickStrings = true,
            keywords = JS_KEYWORDS,
        )

        CodeLanguage.TYPESCRIPT -> LanguageSpec(
            lineComments = listOf("//"),
            blockComment = CommentBlock("/*", "*/"),
            backtickStrings = true,
            keywords = JS_KEYWORDS + TS_KEYWORDS,
        )

        CodeLanguage.PYTHON -> LanguageSpec(
            lineComments = listOf("#"),
            tripleQuoted = true,
            annotations = true,
            keywords = PYTHON_KEYWORDS,
        )

        CodeLanguage.SHELL -> LanguageSpec(
            lineComments = listOf("#"),
            keywords = SHELL_KEYWORDS,
        )

        CodeLanguage.JSON -> LanguageSpec(
            jsonKeys = true,
            keywords = setOf("true", "false", "null"),
        )

        else -> LanguageSpec()
    }

    private fun highlightCode(
        code: String,
        language: CodeLanguage,
        builder: AnnotatedString.Builder,
    ) {
        val spec = specFor(language)
        var index = 0

        while (index < code.length) {
            val current = code[index]

            val block = spec.blockComment
            if (block != null && code.startsWith(block.open, index)) {
                val end = indexOfOrEnd(code, block.close, index + block.open.length)
                builder.addStyle(SpanStyle(color = CodeTheme.Comment), index, end)
                index = end
                continue
            }

            if (current == '"' || current == '\'' || (current == '`' && spec.backtickStrings)) {
                val end = stringEnd(code, index, spec.tripleQuoted)
                val color = if (spec.jsonKeys && isFollowedByColon(code, end)) {
                    CodeTheme.Property
                } else {
                    CodeTheme.StringLiteral
                }
                builder.addStyle(SpanStyle(color = color), index, end)
                index = end
                continue
            }

            val lineComment = spec.lineComments.firstOrNull { code.startsWith(it, index) }
            if (lineComment != null) {
                val end = code.indexOf('\n', index).let { if (it < 0) code.length else it }
                builder.addStyle(SpanStyle(color = CodeTheme.Comment), index, end)
                index = end
                continue
            }

            if (current == '@' && spec.annotations) {
                var end = index + 1
                while (end < code.length && (code[end].isLetterOrDigit() || code[end] == '_' || code[end] == ':')) {
                    end++
                }
                if (end > index + 1) {
                    builder.addStyle(SpanStyle(color = CodeTheme.Annotation), index, end)
                    index = end
                    continue
                }
            }

            if (current.isDigit() && (index == 0 || !isIdentifierPart(code[index - 1]))) {
                var end = index + 1
                while (end < code.length && isNumberPart(code, end)) end++
                builder.addStyle(SpanStyle(color = CodeTheme.Number), index, end)
                index = end
                continue
            }

            if (isIdentifierStart(current)) {
                var end = index + 1
                while (end < code.length && isIdentifierPart(code[end])) end++
                if (code.substring(index, end) in spec.keywords) {
                    builder.addStyle(SpanStyle(color = CodeTheme.Keyword), index, end)
                }
                index = end
                continue
            }

            index++
        }
    }

    private fun isNumberPart(code: String, index: Int): Boolean {
        val character = code[index]
        if (character.isLetterOrDigit() || character == '_' || character == '.') return true
        // Exponent sign: 1e-9
        return (character == '+' || character == '-') &&
            (code[index - 1] == 'e' || code[index - 1] == 'E')
    }

    private fun isIdentifierStart(character: Char): Boolean =
        character.isLetter() || character == '_' || character == '$'

    private fun isIdentifierPart(character: Char): Boolean =
        character.isLetterOrDigit() || character == '_' || character == '$'

    /** Finds the end (exclusive) of the string literal starting at [start]. */
    private fun stringEnd(code: String, start: Int, tripleQuoted: Boolean): Int {
        val quote = code[start]
        val triple = tripleQuoted &&
            start + 2 < code.length &&
            code[start + 1] == quote &&
            code[start + 2] == quote
        val delimiter = if (triple) "$quote$quote$quote" else quote.toString()

        var index = start + delimiter.length
        while (index < code.length) {
            if (code.startsWith(delimiter, index)) return index + delimiter.length
            if (!triple) {
                if (code[index] == '\\') {
                    index += 2
                    continue
                }
                if (code[index] == '\n') return index
            }
            index++
        }
        return code.length
    }

    private fun isFollowedByColon(code: String, from: Int): Boolean {
        var index = from
        while (index < code.length && (code[index] == ' ' || code[index] == '\t')) index++
        return index < code.length && code[index] == ':'
    }

    private fun indexOfOrEnd(code: String, needle: String, from: Int): Int {
        val found = code.indexOf(needle, from)
        return if (found < 0) code.length else found + needle.length
    }

    // ---------------------------------------------------------------- YAML

    private fun highlightYaml(code: String, builder: AnnotatedString.Builder) {
        var lineStart = 0
        while (true) {
            val lineEnd = code.indexOf('\n', lineStart).let { if (it < 0) code.length else it }
            val comment = yamlCommentStart(code, lineStart, lineEnd)
            val contentEnd = if (comment >= 0) comment else lineEnd

            var index = lineStart
            while (index < contentEnd && (code[index] == ' ' || code[index] == '\t')) index++

            if (index < contentEnd && code[index] == '-') {
                builder.addStyle(SpanStyle(color = CodeTheme.Keyword), index, index + 1)
                index++
                while (index < contentEnd && code[index] == ' ') index++
            }

            val colon = code.indexOf(':', index)
            if (colon in index until contentEnd &&
                (colon + 1 >= contentEnd || code[colon + 1] == ' ' || code[colon + 1] == '\t')
            ) {
                builder.addStyle(SpanStyle(color = CodeTheme.Property), index, colon)
                index = colon + 1
            }

            index = highlightScalarValues(code, index, contentEnd, builder)

            if (comment >= 0) {
                builder.addStyle(SpanStyle(color = CodeTheme.Comment), comment, lineEnd)
            }

            if (lineEnd >= code.length) break
            lineStart = lineEnd + 1
        }
    }

    /** First `#` that starts a YAML comment (preceded by whitespace and outside quotes). */
    private fun yamlCommentStart(code: String, start: Int, end: Int): Int {
        var inSingle = false
        var inDouble = false
        for (index in start until end) {
            when (code[index]) {
                '\'' -> if (!inDouble) inSingle = !inSingle
                '"' -> if (!inSingle) inDouble = !inDouble
                '#' -> if (!inSingle && !inDouble &&
                    (index == start || code[index - 1] == ' ' || code[index - 1] == '\t')
                ) {
                    return index
                }
            }
        }
        return -1
    }

    /** Styles quoted strings, numbers and booleans inside `[start, end)`. */
    private fun highlightScalarValues(
        code: String,
        start: Int,
        end: Int,
        builder: AnnotatedString.Builder,
    ): Int {
        var index = start
        while (index < end) {
            val current = code[index]
            if (current == '"' || current == '\'') {
                val stop = minOf(stringEnd(code, index, tripleQuoted = false), end)
                builder.addStyle(SpanStyle(color = CodeTheme.StringLiteral), index, stop)
                index = stop
                continue
            }
            if (current.isDigit() && (index == start || !isIdentifierPart(code[index - 1]))) {
                var stop = index + 1
                while (stop < end && isNumberPart(code, stop)) stop++
                builder.addStyle(SpanStyle(color = CodeTheme.Number), index, stop)
                index = stop
                continue
            }
            if (isIdentifierStart(current)) {
                var stop = index + 1
                while (stop < end && isIdentifierPart(code[stop])) stop++
                val word = code.substring(index, stop)
                if (word in YAML_KEYWORDS) {
                    builder.addStyle(SpanStyle(color = CodeTheme.Keyword), index, stop)
                }
                index = stop
                continue
            }
            index++
        }
        return end
    }

    // ---------------------------------------------------------------- XML/HTML

    private fun highlightXml(code: String, builder: AnnotatedString.Builder) {
        var index = 0
        while (index < code.length) {
            if (code.startsWith("<!--", index)) {
                val end = indexOfOrEnd(code, "-->", index + 4)
                builder.addStyle(SpanStyle(color = CodeTheme.Comment), index, end)
                index = end
                continue
            }
            if (code[index] != '<') {
                index++
                continue
            }

            val tagEnd = code.indexOf('>', index).let { if (it < 0) code.length else it + 1 }
            var cursor = index + 1
            if (cursor < tagEnd && code[cursor] == '/') cursor++

            if (cursor < tagEnd && (code[cursor] == '?' || code[cursor] == '!')) {
                builder.addStyle(SpanStyle(color = CodeTheme.Tag), index, tagEnd)
                index = tagEnd
                continue
            }

            val nameStart = cursor
            while (cursor < tagEnd && isXmlNamePart(code[cursor])) cursor++
            if (cursor > nameStart) {
                builder.addStyle(SpanStyle(color = CodeTheme.Tag), nameStart, cursor)
            }

            while (cursor < tagEnd) {
                val character = code[cursor]
                when {
                    character == '"' || character == '\'' -> {
                        val end = minOf(stringEnd(code, cursor, tripleQuoted = false), tagEnd)
                        builder.addStyle(SpanStyle(color = CodeTheme.StringLiteral), cursor, end)
                        cursor = end
                    }

                    isXmlNamePart(character) -> {
                        val attributeStart = cursor
                        while (cursor < tagEnd && isXmlNamePart(code[cursor])) cursor++
                        var afterName = cursor
                        while (afterName < tagEnd && code[afterName].isWhitespace()) afterName++
                        if (afterName < tagEnd && code[afterName] == '=') {
                            builder.addStyle(SpanStyle(color = CodeTheme.Attribute), attributeStart, cursor)
                        }
                    }

                    else -> cursor++
                }
            }
            index = tagEnd
        }
    }

    private fun isXmlNamePart(character: Char): Boolean =
        character.isLetterOrDigit() || character == '_' || character == ':' ||
            character == '-' || character == '.'

    // ---------------------------------------------------------------- Markdown

    private fun highlightMarkdown(code: String, builder: AnnotatedString.Builder) {
        var inFence = false
        var lineStart = 0
        while (true) {
            val lineEnd = code.indexOf('\n', lineStart).let { if (it < 0) code.length else it }
            val line = code.substring(lineStart, lineEnd)
            val trimmed = line.trimStart()
            val offset = lineStart + (line.length - trimmed.length)

            when {
                trimmed.startsWith("```") || trimmed.startsWith("~~~") -> {
                    builder.addStyle(SpanStyle(color = CodeTheme.Comment), lineStart, lineEnd)
                    inFence = !inFence
                }

                inFence -> builder.addStyle(
                    SpanStyle(color = CodeTheme.StringLiteral),
                    lineStart,
                    lineEnd,
                )

                else -> {
                    if (trimmed.startsWith("#")) {
                        builder.addStyle(
                            SpanStyle(color = CodeTheme.Heading, fontWeight = FontWeight.Bold),
                            offset,
                            lineEnd,
                        )
                    } else {
                        val markerLength = listMarkerLength(trimmed)
                        if (markerLength > 0) {
                            builder.addStyle(
                                SpanStyle(color = CodeTheme.Keyword),
                                offset,
                                offset + markerLength,
                            )
                        }
                    }
                    highlightInlineMarkdown(code, lineStart, lineEnd, builder)
                }
            }

            if (lineEnd >= code.length) break
            lineStart = lineEnd + 1
        }
    }

    private fun listMarkerLength(trimmed: String): Int {
        if (trimmed.startsWith("> ")) return 1
        if (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ")) return 1
        val digits = trimmed.takeWhile { it.isDigit() }
        return if (digits.isNotEmpty() && trimmed.getOrNull(digits.length) == '.') digits.length + 1 else 0
    }

    private fun highlightInlineMarkdown(
        code: String,
        start: Int,
        end: Int,
        builder: AnnotatedString.Builder,
    ) {
        var index = start
        while (index < end) {
            if (code[index] == '`') {
                val close = code.indexOf('`', index + 1)
                if (close in (index + 1) until end) {
                    builder.addStyle(SpanStyle(color = CodeTheme.StringLiteral), index, close + 1)
                    index = close + 1
                    continue
                }
            }
            if (code[index] == '[') {
                val labelEnd = code.indexOf(']', index + 1)
                if (labelEnd in (index + 1) until end &&
                    labelEnd + 1 < end &&
                    code[labelEnd + 1] == '('
                ) {
                    val urlEnd = code.indexOf(')', labelEnd + 2)
                    if (urlEnd in (labelEnd + 2)..end) {
                        builder.addStyle(
                            SpanStyle(
                                color = CodeTheme.Attribute,
                                textDecoration = TextDecoration.Underline,
                            ),
                            index,
                            minOf(urlEnd + 1, end),
                        )
                        index = urlEnd + 1
                        continue
                    }
                }
            }
            index++
        }
    }

    // ---------------------------------------------------------------- keywords

    private val KOTLIN_KEYWORDS = setOf(
        "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in",
        "interface", "is", "null", "object", "package", "return", "super", "this", "throw",
        "true", "try", "typealias", "typeof", "val", "var", "when", "while", "by", "catch",
        "constructor", "delegate", "dynamic", "field", "file", "finally", "get", "import",
        "init", "param", "property", "receiver", "set", "setparam", "where", "abstract",
        "actual", "annotation", "companion", "const", "crossinline", "data", "enum", "expect",
        "external", "final", "infix", "inline", "inner", "internal", "lateinit", "noinline",
        "open", "operator", "out", "override", "private", "protected", "public", "reified",
        "sealed", "suspend", "tailrec", "vararg", "it",
    )

    private val JAVA_KEYWORDS = setOf(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
        "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
        "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
        "interface", "long", "native", "new", "package", "private", "protected", "public",
        "record", "return", "sealed", "short", "static", "strictfp", "super", "switch",
        "synchronized", "this", "throw", "throws", "transient", "try", "var", "void",
        "volatile", "while", "true", "false", "null", "yield", "permits",
    )

    private val JS_KEYWORDS = setOf(
        "async", "await", "break", "case", "catch", "class", "const", "continue", "debugger",
        "default", "delete", "do", "else", "export", "extends", "false", "finally", "for",
        "function", "get", "if", "import", "in", "instanceof", "let", "new", "null", "of",
        "return", "set", "static", "super", "switch", "this", "throw", "true", "try",
        "typeof", "undefined", "var", "void", "while", "with", "yield",
    )

    private val TS_KEYWORDS = setOf(
        "abstract", "as", "bigint", "boolean", "declare", "enum", "implements", "infer",
        "interface", "is", "keyof", "namespace", "never", "number", "object", "private",
        "protected", "public", "readonly", "satisfies", "string", "symbol", "type", "unknown",
        "any",
    )

    private val PYTHON_KEYWORDS = setOf(
        "and", "as", "assert", "async", "await", "break", "case", "class", "continue", "def",
        "del", "elif", "else", "except", "False", "finally", "for", "from", "global", "if",
        "import", "in", "is", "lambda", "match", "None", "nonlocal", "not", "or", "pass",
        "raise", "return", "True", "try", "while", "with", "yield", "self",
    )

    private val SHELL_KEYWORDS = setOf(
        "if", "then", "elif", "else", "fi", "for", "while", "until", "do", "done", "case",
        "esac", "function", "in", "select", "return", "exit", "local", "export", "readonly",
        "declare", "unset", "shift", "source", "alias", "echo",
    )

    private val YAML_KEYWORDS = setOf("true", "false", "null", "yes", "no", "on", "off", "~")
}
