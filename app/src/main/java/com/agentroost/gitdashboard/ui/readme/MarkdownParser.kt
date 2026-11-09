package com.agentroost.gitdashboard.ui.readme

/**
 * A block of markdown, ready to be laid out by the README viewer.
 *
 * Only the subset GitHub renders in READMEs and this app needs is modelled. Images are a block on
 * their own: an image that appears inline in a paragraph (or list item) is emitted as an [Image]
 * block between the surrounding prose, so the UI can fetch it while preserving reading order.
 */
sealed interface MarkdownBlock {

    /** ATX heading, `level` 1..6. */
    data class Heading(val level: Int, val spans: List<MarkdownSpan>) : MarkdownBlock

    /** One paragraph of prose. */
    data class Paragraph(val spans: List<MarkdownSpan>) : MarkdownBlock

    /** `-`, `*` and `+` list; each item is a list of blocks so an item can carry an image. */
    data class BulletList(val items: List<List<MarkdownBlock>>) : MarkdownBlock

    /** `1.` / `1)` list; [start] is the number the author wrote on the first item. */
    data class OrderedList(val start: Int, val items: List<List<MarkdownBlock>>) : MarkdownBlock

    /** Fenced code block; [language] is the first word of the info string, or null. */
    data class CodeBlock(val language: String?, val code: String) : MarkdownBlock

    /** Blockquote, possibly containing nested blocks. */
    data class Quote(val blocks: List<MarkdownBlock>) : MarkdownBlock

    /** Horizontal rule. */
    data object Divider : MarkdownBlock

    /** `![alt](src "title")`; [src] is resolved later by [ReadmeImageRefs]. */
    data class Image(val alt: String, val src: String, val title: String?) : MarkdownBlock
}

/** Inline formatting inside a [MarkdownBlock.Heading] or [MarkdownBlock.Paragraph]. */
sealed interface MarkdownSpan {

    /** Unformatted text, including decoded backslash escapes. */
    data class Plain(val text: String) : MarkdownSpan

    /** `**bold**` or `__bold__`. */
    data class Strong(val spans: List<MarkdownSpan>) : MarkdownSpan

    /** `*italic*` or `_italic_`. */
    data class Emphasis(val spans: List<MarkdownSpan>) : MarkdownSpan

    /** `` `inline code` ``. */
    data class Code(val text: String) : MarkdownSpan

    /** `[text](url)`; the label is flattened to text. */
    data class Link(val text: String, val url: String) : MarkdownSpan
}

/**
 * A small, dependency-free markdown parser for README rendering.
 *
 * Supported: ATX headings, paragraphs, bullet and ordered lists, fenced code (``` and ~~~ with an
 * info string), blockquotes, horizontal rules, backslash escapes, inline `![alt](src "title")`
 * images, and inline strong/emphasis/code/link spans. CRLF input is normalised.
 *
 * Deliberate limitations:
 * - **GitHub pipe tables are out of scope.** There is no table block: a table's rows are parsed as
 *   ordinary paragraph text (the pipes are kept verbatim), which renders as plain rows.
 * - Raw HTML tags are stripped and only their text content is kept (a `<br>` becomes a newline).
 *   HTML `<img>` tags are not converted to [MarkdownBlock.Image]; only markdown image syntax is.
 * - Linked images (`[![alt](src)](url)`) render as the image; the wrapper link is dropped.
 * - An image inside a heading renders as its alt text; images in paragraphs and list items become
 *   [MarkdownBlock.Image] blocks.
 * - Nested emphasis follows a simple delimiter match rather than CommonMark's full delimiter stack.
 *
 * [parse] is total: malformed input never throws, it just degrades to plain text.
 */
object MarkdownParser {

    private const val MAX_BLOCK_DEPTH = 20
    private const val MAX_INLINE_DEPTH = 8

    private val HEADING = Regex("""^ {0,3}(#{1,6})(?:[ \t]+(.*))?$""")
    private val CLOSING_HASHES = Regex("""[ \t]+#+[ \t]*$""")
    private val HTML_TAG = Regex("""^</?([A-Za-z][A-Za-z0-9-]*)(?:\s[^>]*)?/?>""")

    /** Parses [markdown] into blocks. Returns an empty list for blank input and never throws. */
    fun parse(markdown: String): List<MarkdownBlock> = try {
        parseBlocks(normalize(markdown).split('\n'))
    } catch (exception: Exception) {
        // Defensive: the parser is total by construction, but a README is untrusted input and a
        // rendering bug must never take down the viewer.
        emptyList()
    }

    private fun normalize(markdown: String): String =
        markdown
            .removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace('\r', '\n')

    // ---------------------------------------------------------------------------------------------
    // Blocks
    // ---------------------------------------------------------------------------------------------

    private fun parseBlocks(lines: List<String>, depth: Int = 0): List<MarkdownBlock> {
        if (depth > MAX_BLOCK_DEPTH) {
            val text = lines.filter { it.isNotBlank() }.joinToString("\n") { it.trim() }
            return if (text.isBlank()) emptyList() else blocksFromInlineText(text)
        }

        val blocks = mutableListOf<MarkdownBlock>()
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            val fence = fenceStart(line)
            val heading = HEADING.find(line)
            val marker = listMarker(line)
            when {
                line.isBlank() -> index++

                fence != null -> {
                    val (block, next) = parseFence(lines, index, fence)
                    blocks += block
                    index = next
                }

                heading != null -> {
                    blocks += parseHeading(heading)
                    index++
                }

                isDivider(line) -> {
                    blocks += MarkdownBlock.Divider
                    index++
                }

                blockquoteLine(line) != null -> {
                    val (block, next) = parseQuote(lines, index, depth)
                    blocks += block
                    index = next
                }

                marker != null -> {
                    val (block, next) = parseList(lines, index, marker.ordered, depth)
                    blocks += block
                    index = next
                }

                else -> {
                    val (paragraphBlocks, next) = parseParagraph(lines, index)
                    blocks += paragraphBlocks
                    index = next
                }
            }
        }
        return blocks
    }

    private fun parseHeading(match: MatchResult): MarkdownBlock.Heading {
        val level = match.groupValues[1].length
        val raw = match.groupValues[2].trim().replace(CLOSING_HASHES, "").trim()
        return MarkdownBlock.Heading(level, spansOnly(raw))
    }

    private data class Fence(val character: Char, val length: Int, val info: String)

    private fun fenceStart(line: String): Fence? {
        val trimmed = line.trimStart()
        if (line.length - trimmed.length > 3) return null
        val character = trimmed.firstOrNull() ?: return null
        if (character != '`' && character != '~') return null
        val length = trimmed.takeWhile { it == character }.length
        if (length < 3) return null
        val info = trimmed.substring(length)
        // A backtick fence's info string may not contain a backtick.
        if (character == '`' && info.contains('`')) return null
        return Fence(character, length, info.trim())
    }

    private fun parseFence(
        lines: List<String>,
        start: Int,
        fence: Fence,
    ): Pair<MarkdownBlock, Int> {
        val code = mutableListOf<String>()
        var index = start + 1
        while (index < lines.size) {
            if (isFenceClose(lines[index], fence)) {
                index++
                break
            }
            code += lines[index]
            index++
        }
        val language = fence.info
            .substringBefore(' ')
            .substringBefore('\t')
            .takeIf { it.isNotBlank() }
        return MarkdownBlock.CodeBlock(language, code.joinToString("\n")) to index
    }

    private fun isFenceClose(line: String, fence: Fence): Boolean {
        val trimmed = line.trimStart()
        val run = trimmed.takeWhile { it == fence.character }
        return run.length >= fence.length && trimmed.substring(run.length).isBlank()
    }

    private fun isDivider(line: String): Boolean {
        val compact = line.trim()
        if (compact.length < 3) return false
        val character = compact.first()
        if (character != '-' && character != '*' && character != '_') return false
        var count = 0
        for (value in compact) {
            when (value) {
                character -> count++
                ' ', '\t' -> Unit
                else -> return false
            }
        }
        return count >= 3
    }

    private fun blockquoteLine(line: String): String? {
        val trimmed = line.trimStart()
        if (line.length - trimmed.length > 3) return null
        if (!trimmed.startsWith(">")) return null
        return trimmed.removePrefix(">").removePrefix(" ")
    }

    private fun parseQuote(
        lines: List<String>,
        start: Int,
        depth: Int,
    ): Pair<MarkdownBlock, Int> {
        val content = mutableListOf<String>()
        var index = start
        while (index < lines.size) {
            val stripped = blockquoteLine(lines[index]) ?: break
            content += stripped
            index++
        }
        return MarkdownBlock.Quote(parseBlocks(content, depth + 1)) to index
    }

    private data class ListMarker(
        val ordered: Boolean,
        val number: Int,
        val content: String,
        val indent: Int,
    )

    private fun listMarker(line: String): ListMarker? {
        val trimmed = line.trimStart()
        val indent = line.length - trimmed.length
        if (indent > 3 || trimmed.length < 2) return null

        val first = trimmed[0]
        if (first == '-' || first == '*' || first == '+') {
            if (!trimmed[1].isWhitespace()) return null
            return ListMarker(ordered = false, number = 1, content = trimmed.substring(1).trimStart(), indent = indent)
        }

        val digits = trimmed.takeWhile { it.isDigit() }.take(9)
        if (digits.isEmpty()) return null
        val rest = trimmed.substring(digits.length)
        if (rest.length < 2 || (rest[0] != '.' && rest[0] != ')') || !rest[1].isWhitespace()) {
            return null
        }
        return ListMarker(
            ordered = true,
            number = digits.toIntOrNull() ?: 1,
            content = rest.substring(1).trimStart(),
            indent = indent,
        )
    }

    private fun parseList(
        lines: List<String>,
        start: Int,
        ordered: Boolean,
        depth: Int,
    ): Pair<MarkdownBlock, Int> {
        val first = listMarker(lines[start]) ?: return MarkdownBlock.BulletList(emptyList()) to start + 1
        val items = mutableListOf<MutableList<String>>()
        var current: MutableList<String>? = null
        var index = start

        while (index < lines.size) {
            val line = lines[index]
            if (line.isBlank()) {
                var next = index + 1
                while (next < lines.size && lines[next].isBlank()) next++
                if (next >= lines.size) {
                    index = next
                    break
                }
                val nextMarker = listMarker(lines[next])
                val nextIsIndented = lines[next].takeWhile { it == ' ' || it == '\t' }.length >= 2
                val continues = (nextMarker != null && nextMarker.ordered == ordered) || nextIsIndented
                if (continues) {
                    index++
                    continue
                }
                break
            }

            val marker = listMarker(line)
            if (marker != null && marker.ordered == ordered && marker.indent <= first.indent) {
                current = mutableListOf(marker.content)
                items += current
                index++
                continue
            }

            if (current != null && (marker != null || line.takeWhile { it == ' ' || it == '\t' }.isNotEmpty())) {
                current.add(line.trim())
                index++
                continue
            }

            break
        }

        val itemBlocks = items.map { itemLines -> parseBlocks(itemLines, depth + 1) }
        val block = if (ordered) {
            MarkdownBlock.OrderedList(first.number, itemBlocks)
        } else {
            MarkdownBlock.BulletList(itemBlocks)
        }
        return block to index
    }

    private fun parseParagraph(
        lines: List<String>,
        start: Int,
    ): Pair<List<MarkdownBlock>, Int> {
        val textLines = mutableListOf<String>()
        var index = start
        while (index < lines.size) {
            val line = lines[index]
            if (line.isBlank()) break
            if (textLines.isNotEmpty() && startsBlock(line)) break
            textLines += line.trim()
            index++
        }
        if (textLines.isEmpty()) return emptyList<MarkdownBlock>() to start + 1

        return blocksFromInlineText(textLines.joinToString("\n")) to index
    }

    private fun startsBlock(line: String): Boolean =
        fenceStart(line) != null ||
            HEADING.find(line) != null ||
            isDivider(line) ||
            blockquoteLine(line) != null ||
            listMarker(line) != null

    // ---------------------------------------------------------------------------------------------
    // Inlines
    // ---------------------------------------------------------------------------------------------

    private sealed interface InlinePiece {
        data class Spans(val spans: List<MarkdownSpan>) : InlinePiece
        data class ImageRef(val alt: String, val src: String, val title: String?) : InlinePiece
    }

    private fun spansOnly(text: String): List<MarkdownSpan> =
        parseInlinePieces(text, allowImages = true)
            .flatMap { piece ->
                when (piece) {
                    is InlinePiece.Spans -> piece.spans
                    is InlinePiece.ImageRef -> listOf(MarkdownSpan.Plain(piece.alt))
                }
            }

    private fun blocksFromInlineText(text: String): List<MarkdownBlock> {
        val pieces = parseInlinePieces(text, allowImages = true)
        val blocks = mutableListOf<MarkdownBlock>()
        val pending = mutableListOf<MarkdownSpan>()

        fun flush() {
            if (pending.isNotEmpty()) {
                blocks += MarkdownBlock.Paragraph(pending.toList())
                pending.clear()
            }
        }

        for (piece in pieces) {
            when (piece) {
                is InlinePiece.Spans -> pending += piece.spans
                is InlinePiece.ImageRef -> {
                    flush()
                    blocks += MarkdownBlock.Image(piece.alt, piece.src, piece.title)
                }
            }
        }
        flush()
        return blocks
    }

    private fun parseInlinePieces(
        text: String,
        allowImages: Boolean,
        depth: Int = 0,
    ): List<InlinePiece> {
        if (text.isEmpty()) return emptyList()
        if (depth > MAX_INLINE_DEPTH) return listOf(InlinePiece.Spans(listOf(MarkdownSpan.Plain(text))))

        val pieces = mutableListOf<InlinePiece>()
        val spans = mutableListOf<MarkdownSpan>()
        val plain = StringBuilder()

        fun flushPlain() {
            if (plain.isNotEmpty()) {
                appendSpan(spans, MarkdownSpan.Plain(plain.toString()))
                plain.clear()
            }
        }

        fun flushSpans() {
            flushPlain()
            if (spans.isNotEmpty()) {
                pieces += InlinePiece.Spans(spans.toList())
                spans.clear()
            }
        }

        var index = 0
        while (index < text.length) {
            val character = text[index]
            when {
                character == '\\' && index + 1 < text.length -> {
                    plain.append(text[index + 1])
                    index += 2
                }

                character == '`' -> {
                    val run = runLength(text, index, '`')
                    val close = findRun(text, index + run, '`', run)
                    if (close >= 0) {
                        flushPlain()
                        spans += MarkdownSpan.Code(normalizeCode(text.substring(index + run, close)))
                        index = close + run
                    } else {
                        plain.append(text, index, index + run)
                        index += run
                    }
                }

                character == '!' && allowImages && index + 1 < text.length && text[index + 1] == '[' -> {
                    val image = parseImage(text, index)
                    if (image != null) {
                        flushSpans()
                        pieces += image.piece
                        index = image.end
                    } else {
                        plain.append(character)
                        index++
                    }
                }

                character == '[' && allowImages -> {
                    val linkedImage = parseLinkedImage(text, index)
                    if (linkedImage != null) {
                        flushSpans()
                        pieces += linkedImage.piece
                        index = linkedImage.end
                    } else {
                        val link = parseLink(text, index)
                        if (link != null) {
                            flushPlain()
                            spans += MarkdownSpan.Link(link.text, link.url)
                            index = link.end
                        } else {
                            plain.append(character)
                            index++
                        }
                    }
                }

                character == '[' -> {
                    val link = parseLink(text, index)
                    if (link != null) {
                        flushPlain()
                        spans += MarkdownSpan.Link(link.text, link.url)
                        index = link.end
                    } else {
                        plain.append(character)
                        index++
                    }
                }

                character == '*' || character == '_' -> {
                    val emphasis = parseEmphasis(text, index, depth)
                    if (emphasis != null) {
                        flushPlain()
                        appendSpan(spans, emphasis.span)
                        index = emphasis.end
                    } else {
                        plain.append(character)
                        index++
                    }
                }

                character == '<' -> {
                    val html = parseHtml(text, index)
                    if (html != null) {
                        if (html.text.isNotEmpty()) plain.append(html.text)
                        index = html.end
                    } else {
                        plain.append(character)
                        index++
                    }
                }

                else -> {
                    plain.append(character)
                    index++
                }
            }
        }
        flushSpans()
        return pieces
    }

    private fun appendSpan(target: MutableList<MarkdownSpan>, span: MarkdownSpan) {
        if (span is MarkdownSpan.Plain) {
            if (span.text.isEmpty()) return
            val last = target.lastOrNull()
            if (last is MarkdownSpan.Plain) {
                target[target.lastIndex] = MarkdownSpan.Plain(last.text + span.text)
                return
            }
        }
        target += span
    }

    private data class EmphasisMatch(val span: MarkdownSpan, val end: Int)

    private fun parseEmphasis(text: String, start: Int, depth: Int): EmphasisMatch? {
        val character = text[start]
        val double = start + 1 < text.length && text[start + 1] == character
        if (character == '_' && start > 0 && text[start - 1].isLetterOrDigit()) return null

        val delimiter = if (double) "$character$character" else character.toString()
        val innerStart = start + delimiter.length
        var searchFrom = innerStart

        while (searchFrom < text.length) {
            val close = text.indexOf(delimiter, searchFrom)
            if (close < 0) return null
            if (isEscaped(text, close)) {
                searchFrom = close + 1
                continue
            }
            if (close == innerStart) {
                searchFrom = close + delimiter.length
                continue
            }
            if (character == '_' &&
                close + delimiter.length < text.length &&
                text[close + delimiter.length].isLetterOrDigit()
            ) {
                searchFrom = close + delimiter.length
                continue
            }

            val inner = text.substring(innerStart, close)
            val innerSpans = parseInlinePieces(inner, allowImages = false, depth = depth + 1)
                .flatMap { piece ->
                    when (piece) {
                        is InlinePiece.Spans -> piece.spans
                        is InlinePiece.ImageRef -> listOf(MarkdownSpan.Plain(piece.alt))
                    }
                }
            if (innerSpans.isEmpty()) {
                searchFrom = close + delimiter.length
                continue
            }

            val span = if (double) {
                MarkdownSpan.Strong(innerSpans)
            } else {
                MarkdownSpan.Emphasis(innerSpans)
            }
            return EmphasisMatch(span, close + delimiter.length)
        }
        return null
    }

    private data class LinkMatch(val text: String, val url: String, val end: Int)

    private fun parseLink(text: String, start: Int): LinkMatch? {
        if (text[start] != '[') return null
        val closeBracket = findClosingBracket(text, start + 1) ?: return null
        if (closeBracket + 1 >= text.length || text[closeBracket + 1] != '(') return null
        val closeParen = findClosingParen(text, closeBracket + 2) ?: return null

        val destination = parseDestination(text.substring(closeBracket + 2, closeParen)) ?: return null
        if (destination.url.isBlank()) return null
        val label = flattenToText(text.substring(start + 1, closeBracket))
        return LinkMatch(label, destination.url, closeParen + 1)
    }

    private data class ImageMatch(val piece: InlinePiece.ImageRef, val end: Int)

    /**
     * `[![alt](src)](url)` - a badge or a linked screenshot. The block model has no "linked image",
     * so the image wins and the surrounding link is ignored; showing the badge beats showing its
     * alt text.
     */
    private fun parseLinkedImage(text: String, start: Int): ImageMatch? {
        if (start + 1 >= text.length || text[start] != '[' || text[start + 1] != '!') return null
        val image = parseImage(text, start + 1) ?: return null
        if (image.end >= text.length || text[image.end] != ']') return null
        val parenOpen = image.end + 1
        if (parenOpen >= text.length || text[parenOpen] != '(') return null
        val closeParen = findClosingParen(text, parenOpen + 1) ?: return null
        return ImageMatch(image.piece, closeParen + 1)
    }

    private fun parseImage(text: String, start: Int): ImageMatch? {
        if (start + 1 >= text.length || text[start] != '!' || text[start + 1] != '[') return null
        val closeBracket = findClosingBracket(text, start + 2) ?: return null
        if (closeBracket + 1 >= text.length || text[closeBracket + 1] != '(') return null
        val closeParen = findClosingParen(text, closeBracket + 2) ?: return null

        val destination = parseDestination(text.substring(closeBracket + 2, closeParen)) ?: return null
        val alt = flattenToText(text.substring(start + 2, closeBracket))
        val piece = InlinePiece.ImageRef(alt = alt, src = destination.url, title = destination.title)
        return ImageMatch(piece, closeParen + 1)
    }

    private data class Destination(val url: String, val title: String?)

    private fun parseDestination(raw: String): Destination? {
        val inner = raw.trim()
        if (inner.isEmpty()) return Destination("", null)

        var index = 0
        val destination: String
        if (inner[0] == '<') {
            val close = inner.indexOf('>', 1)
            if (close < 0) return null
            destination = inner.substring(1, close)
            index = close + 1
        } else {
            val start = index
            while (index < inner.length && !inner[index].isWhitespace()) {
                index += if (inner[index] == '\\' && index + 1 < inner.length) 2 else 1
            }
            destination = inner.substring(start, index)
        }

        val rest = inner.substring(index).trim()
        val title = when {
            rest.isEmpty() -> null
            rest.length >= 2 && (rest.first() == '"' || rest.first() == '\'' || rest.first() == '(') -> {
                val expectedClose = if (rest.first() == '(') ')' else rest.first()
                if (rest.last() == expectedClose) unescape(rest.substring(1, rest.length - 1)) else null
            }

            else -> unescape(rest)
        }
        return Destination(unescape(destination), title)
    }

    private fun findClosingBracket(text: String, from: Int): Int? {
        var depth = 1
        var index = from
        while (index < text.length) {
            val character = text[index]
            when {
                character == '\\' -> index++
                character == '[' -> depth++
                character == ']' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
            index++
        }
        return null
    }

    private fun findClosingParen(text: String, from: Int): Int? {
        var depth = 1
        var quote: Char? = null
        var index = from
        while (index < text.length) {
            val character = text[index]
            when {
                character == '\\' -> index++
                quote != null -> if (character == quote) quote = null
                character == '"' || character == '\'' -> quote = character
                character == '(' -> depth++
                character == ')' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
            index++
        }
        return null
    }

    private fun flattenToText(raw: String): String {
        val pieces = parseInlinePieces(raw, allowImages = true, depth = MAX_INLINE_DEPTH)
        return pieces.joinToString(separator = "") { piece ->
            when (piece) {
                is InlinePiece.ImageRef -> piece.alt
                is InlinePiece.Spans -> spansToText(piece.spans)
            }
        }
    }

    private fun spansToText(spans: List<MarkdownSpan>): String = buildString {
        for (span in spans) {
            when (span) {
                is MarkdownSpan.Plain -> append(span.text)
                is MarkdownSpan.Strong -> append(spansToText(span.spans))
                is MarkdownSpan.Emphasis -> append(spansToText(span.spans))
                is MarkdownSpan.Code -> append(span.text)
                is MarkdownSpan.Link -> append(span.text)
            }
        }
    }

    private fun unescape(value: String): String = buildString {
        var index = 0
        while (index < value.length) {
            val character = value[index]
            if (character == '\\' && index + 1 < value.length) {
                append(value[index + 1])
                index += 2
            } else {
                append(character)
                index++
            }
        }
    }

    private fun normalizeCode(raw: String): String {
        val singleLine = raw.replace('\n', ' ')
        return if (singleLine.length >= 2 &&
            singleLine.first() == ' ' &&
            singleLine.last() == ' ' &&
            singleLine.any { it != ' ' }
        ) {
            singleLine.substring(1, singleLine.length - 1)
        } else {
            singleLine
        }
    }

    private fun runLength(text: String, start: Int, character: Char): Int {
        var index = start
        while (index < text.length && text[index] == character) index++
        return index - start
    }

    private fun findRun(text: String, from: Int, character: Char, length: Int): Int {
        var index = from
        while (index < text.length) {
            if (text[index] == character) {
                val run = runLength(text, index, character)
                if (run == length) return index
                index += run
            } else {
                index++
            }
        }
        return -1
    }

    private fun isEscaped(text: String, index: Int): Boolean {
        var backslashes = 0
        var cursor = index - 1
        while (cursor >= 0 && text[cursor] == '\\') {
            backslashes++
            cursor--
        }
        return backslashes % 2 == 1
    }

    private data class HtmlMatch(val text: String, val end: Int)

    private fun parseHtml(text: String, start: Int): HtmlMatch? {
        if (text.startsWith("<!--", start)) {
            val end = text.indexOf("-->", start + 4)
            return if (end < 0) HtmlMatch("", text.length) else HtmlMatch("", end + 3)
        }

        val match = HTML_TAG.find(text.substring(start)) ?: return null
        val name = match.groupValues[1].lowercase()
        return HtmlMatch(if (name == "br") "\n" else "", start + match.value.length)
    }
}
