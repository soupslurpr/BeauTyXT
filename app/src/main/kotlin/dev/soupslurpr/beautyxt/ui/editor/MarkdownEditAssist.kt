/* Pure, bounded Markdown editing assistance for Source mode. */
package dev.soupslurpr.beautyxt.ui.editor

import androidx.compose.ui.text.TextRange
import dev.soupslurpr.beautyxt.document.NativeDocument

/** Describes one pure local text edit and its resulting caret/selection. */
internal data class LocalEditResult(
    val text: String,
    val selection: TextRange
)

/** Available lightweight Markdown formatting actions for Source editing. */
internal enum class MarkdownFormatAction {
    Bold,
    Italic,
    Code,
    Link,
    Heading,
    BulletList,
    Checklist,
    Quote,
    Indent,
    Outdent
}

private data class TaskListPrefixMatch(
    val indent: String,
    val bullet: Char,
    val spaceAfterBullet: String,
    val spaceAfterBox: String,
    val bodyIsEmpty: Boolean
)

private data class BulletListPrefixMatch(
    val indent: String,
    val bullet: Char,
    val space: String,
    val bodyIsEmpty: Boolean
)

private data class OrderedListPrefixMatch(
    val indent: String,
    val numberStr: String,
    val delim: Char,
    val space: String,
    val taskSpaceAfterBox: String?,
    val bodyIsEmpty: Boolean
)

private data class BlockquotePrefixMatch(
    val prefix: String,
    val bodyIsEmpty: Boolean
)

private fun skipSpacesAndTabs(line: CharSequence, start: Int): Int {
    var i = start
    while (i < line.length && (line[i] == ' ' || line[i] == '\t')) {
        i += 1
    }
    return i
}

private fun parseTaskListPrefix(line: String): TaskListPrefixMatch? {
    val indentEnd = skipSpacesAndTabs(line, 0)
    if (indentEnd >= line.length) return null
    val bullet = line[indentEnd]
    if (bullet != '-' && bullet != '*' && bullet != '+') return null
    val afterBulletSpace = skipSpacesAndTabs(line, indentEnd + 1)
    if (afterBulletSpace == indentEnd + 1) return null
    if (afterBulletSpace + 3 > line.length) return null
    if (line[afterBulletSpace] != '[' || line[afterBulletSpace + 2] != ']') return null
    val mark = line[afterBulletSpace + 1]
    if (mark != ' ' && mark != 'x' && mark != 'X') return null
    val boxEnd = afterBulletSpace + 3
    val afterBoxSpace = skipSpacesAndTabs(line, boxEnd)
    // Accept both `- [ ] ` and bare `- [ ]` (empty task with no trailing space).
    if (afterBoxSpace == boxEnd && boxEnd < line.length) return null
    val spaceAfterBox = if (afterBoxSpace > boxEnd) line.substring(boxEnd, afterBoxSpace) else " "
    return TaskListPrefixMatch(
        indent = line.substring(0, indentEnd),
        bullet = bullet,
        spaceAfterBullet = line.substring(indentEnd + 1, afterBulletSpace),
        spaceAfterBox = spaceAfterBox,
        bodyIsEmpty = afterBoxSpace == line.length
    )
}

private fun isThematicBreakLine(line: String): Boolean {
    var col = 0
    var i = 0
    while (i < line.length) {
        when (line[i]) {
            ' ' -> {
                col += 1
                i += 1
            }
            '\t' -> {
                col += 4 - (col % 4)
                i += 1
            }
            else -> break
        }
    }
    if (col > 3 || i >= line.length) return false
    val marker = line[i]
    if (marker != '-' && marker != '*' && marker != '_') return false
    var count = 0
    for (idx in i until line.length) {
        val ch = line[idx]
        if (ch == marker) {
            count += 1
        } else if (ch != ' ' && ch != '\t') {
            return false
        }
    }
    return count >= 3
}

private fun parseBulletListPrefix(line: String): BulletListPrefixMatch? {
    if (isThematicBreakLine(line)) return null
    val indentEnd = skipSpacesAndTabs(line, 0)
    if (indentEnd >= line.length) return null
    val bullet = line[indentEnd]
    if (bullet != '-' && bullet != '*' && bullet != '+') return null
    val afterSpace = skipSpacesAndTabs(line, indentEnd + 1)
    if (afterSpace == indentEnd + 1) return null
    return BulletListPrefixMatch(
        indent = line.substring(0, indentEnd),
        bullet = bullet,
        space = line.substring(indentEnd + 1, afterSpace),
        bodyIsEmpty = afterSpace == line.length
    )
}

private fun parseOrderedListPrefix(line: String): OrderedListPrefixMatch? {
    val indentEnd = skipSpacesAndTabs(line, 0)
    var digitEnd = indentEnd
    while (digitEnd < line.length && line[digitEnd] in '0'..'9') {
        digitEnd += 1
    }
    val digitCount = digitEnd - indentEnd
    // CommonMark §5.2 allows at most 9 digits in an ordered list marker.
    if (digitCount !in 1..9 || digitEnd >= line.length) return null
    val delim = line[digitEnd]
    if (delim != '.' && delim != ')') return null
    val afterSpace = skipSpacesAndTabs(line, digitEnd + 1)
    if (afterSpace == digitEnd + 1) return null
    val hasTaskBox = afterSpace + 2 < line.length &&
        line[afterSpace] == '[' &&
        (line[afterSpace + 1] == ' ' || line[afterSpace + 1] == 'x' || line[afterSpace + 1] == 'X') &&
        line[afterSpace + 2] == ']'
    if (hasTaskBox) {
        val afterBox = afterSpace + 3
        val afterBoxSpace = skipSpacesAndTabs(line, afterBox)
        if (afterBoxSpace > afterBox || afterBox == line.length) {
            return OrderedListPrefixMatch(
                indent = line.substring(0, indentEnd),
                numberStr = line.substring(indentEnd, digitEnd),
                delim = delim,
                space = line.substring(digitEnd + 1, afterSpace),
                taskSpaceAfterBox = if (afterBoxSpace > afterBox) line.substring(afterBox, afterBoxSpace) else " ",
                bodyIsEmpty = afterBoxSpace == line.length
            )
        }
    }
    return OrderedListPrefixMatch(
        indent = line.substring(0, indentEnd),
        numberStr = line.substring(indentEnd, digitEnd),
        delim = delim,
        space = line.substring(digitEnd + 1, afterSpace),
        taskSpaceAfterBox = null,
        bodyIsEmpty = afterSpace == line.length
    )
}

private fun parseBlockquotePrefix(line: String): BlockquotePrefixMatch? {
    var i = skipSpacesAndTabs(line, 0)
    var sawQuote = false
    while (i < line.length && line[i] == '>') {
        sawQuote = true
        i = skipSpacesAndTabs(line, i + 1)
    }
    if (!sawQuote) return null
    return BlockquotePrefixMatch(
        prefix = line.substring(0, i),
        bodyIsEmpty = i == line.length
    )
}

/**
 * Returns whether [offset] lies inside leading YAML frontmatter, an open code fence, or
 * indented code inside CommonMark containers via `beautyxt-syntax-core`.
 */
internal fun isInsideMarkdownCodeFenceOrFrontmatter(text: CharSequence, offset: Int): Boolean {
    val clamped = offset.coerceIn(0, text.length)
    return try {
        NativeDocument.isMarkdownLiteralContext(text.toString(), clamped)
    } catch (_: LinkageError) {
        false
    }
}

/**
 * Computes smart Markdown Enter continuation when a single `'\n'` was inserted at a collapsed caret.
 *
 * Returns `null` when no continuation applies (including inside code fences or frontmatter).
 */
internal fun computeMarkdownEnterContinuation(
    textBefore: CharSequence,
    selectionBefore: TextRange,
    textAfter: CharSequence,
    selectionAfter: TextRange,
    hasPreviousWindow: Boolean = false,
    isLiteralContext: (CharSequence, Int) -> Boolean = ::isInsideMarkdownCodeFenceOrFrontmatter
): LocalEditResult? {
    if (!selectionBefore.collapsed || !selectionAfter.collapsed) return null
    if (hasPreviousWindow) return null
    val insertPos = selectionBefore.start
    if (textAfter.length != textBefore.length + 1) return null
    if (selectionAfter.start != insertPos + 1) return null
    if (insertPos !in 0 until textAfter.length || textAfter[insertPos] != '\n') return null
    if (isLiteralContext(textBefore, insertPos)) return null

    // Locate the line immediately preceding the inserted newline and check remainder of the line.
    var lineStart = insertPos
    while (lineStart > 0 && textBefore[lineStart - 1] != '\n') {
        lineStart -= 1
    }
    val lineEndAfter = indexOfNewline(textBefore, insertPos, textBefore.length)
        .let { if (it >= 0) it else textBefore.length }
    val hasRestOfLine = insertPos < lineEndAfter
    val prevLine = textBefore.subSequence(lineStart, insertPos).toString()
    if (prevLine.isEmpty()) return null

    val quotePrefix = leadingBlockquotePrefix(prevLine)
    val innerLine = if (quotePrefix.isNotEmpty()) prevLine.substring(quotePrefix.length) else prevLine

    parseTaskListPrefix(innerLine)?.let { match ->
        if (match.bodyIsEmpty) {
            return if (hasRestOfLine) {
                null
            } else if (quotePrefix.isNotEmpty()) {
                clearInnerEmptyPrefix(textAfter, lineStart, insertPos + 1, quotePrefix)
            } else {
                clearEmptyPrefix(textAfter, lineStart, insertPos + 1)
            }
        }
        val nextPrefix = "$quotePrefix${match.indent}${match.bullet}${match.spaceAfterBullet}[ ]${match.spaceAfterBox}"
        return insertContinuationPrefix(textAfter, insertPos + 1, nextPrefix)
    }

    parseBulletListPrefix(innerLine)?.let { match ->
        if (match.bodyIsEmpty) {
            return if (hasRestOfLine) {
                null
            } else if (quotePrefix.isNotEmpty()) {
                clearInnerEmptyPrefix(textAfter, lineStart, insertPos + 1, quotePrefix)
            } else {
                clearEmptyPrefix(textAfter, lineStart, insertPos + 1)
            }
        }
        val nextPrefix = "$quotePrefix${match.indent}${match.bullet}${match.space}"
        return insertContinuationPrefix(textAfter, insertPos + 1, nextPrefix)
    }

    parseOrderedListPrefix(innerLine)?.let { match ->
        if (match.bodyIsEmpty) {
            return if (hasRestOfLine) {
                null
            } else if (quotePrefix.isNotEmpty()) {
                clearInnerEmptyPrefix(textAfter, lineStart, insertPos + 1, quotePrefix)
            } else {
                clearEmptyPrefix(textAfter, lineStart, insertPos + 1)
            }
        }
        val nextNum = match.numberStr.toLongOrNull()
            ?.let { it + 1L }
            ?.takeIf { it <= 999_999_999L }
            ?: return null
        val nextPrefix = if (match.taskSpaceAfterBox != null) {
            "$quotePrefix${match.indent}$nextNum${match.delim}${match.space}[ ]${match.taskSpaceAfterBox}"
        } else {
            "$quotePrefix${match.indent}$nextNum${match.delim}${match.space}"
        }
        return insertContinuationPrefix(textAfter, insertPos + 1, nextPrefix)
    }

    parseBlockquotePrefix(prevLine)?.let { match ->
        if (match.bodyIsEmpty) {
            return if (hasRestOfLine) null else clearEmptyPrefix(textAfter, lineStart, insertPos + 1)
        }
        return insertContinuationPrefix(textAfter, insertPos + 1, match.prefix)
    }

    return null
}

private fun leadingBlockquotePrefix(line: String): String {
    val quote = parseBlockquotePrefix(line) ?: return ""
    return quote.prefix
}

private fun clearInnerEmptyPrefix(
    textAfter: CharSequence,
    lineStart: Int,
    afterNewline: Int,
    retainedPrefix: String
): LocalEditResult {
    val updated = buildString(textAfter.length - (afterNewline - lineStart) + retainedPrefix.length) {
        append(textAfter, 0, lineStart)
        append(retainedPrefix)
        append(textAfter, afterNewline, textAfter.length)
    }
    return LocalEditResult(updated, TextRange(lineStart + retainedPrefix.length))
}

private fun clearEmptyPrefix(textAfter: CharSequence, lineStart: Int, afterNewline: Int): LocalEditResult {
    val updated = buildString(textAfter.length - (afterNewline - lineStart)) {
        append(textAfter, 0, lineStart)
        append(textAfter, afterNewline, textAfter.length)
    }
    return LocalEditResult(updated, TextRange(lineStart))
}

private fun insertContinuationPrefix(
    textAfter: CharSequence,
    caretPos: Int,
    prefix: String
): LocalEditResult {
    val updated = buildString(textAfter.length + prefix.length) {
        append(textAfter, 0, caretPos)
        append(prefix)
        append(textAfter, caretPos, textAfter.length)
    }
    return LocalEditResult(updated, TextRange(caretPos + prefix.length))
}

/**
 * Wraps a non-empty selection when a single paired delimiter character is typed over it.
 */
internal fun computeMarkdownWrapSelection(
    textBefore: CharSequence,
    selectionBefore: TextRange,
    textAfter: CharSequence,
    selectionAfter: TextRange
): LocalEditResult? {
    if (selectionBefore.collapsed) return null
    val start = selectionBefore.min
    val end = selectionBefore.max
    val expectedLen = textBefore.length - (end - start) + 1
    if (textAfter.length != expectedLen) return null
    if (!selectionAfter.collapsed || selectionAfter.start != start + 1) return null
    if (start !in 0 until textAfter.length) return null
    val typed = textAfter[start]
    if (typed == '`') {
        return wrapCodeSpan(textBefore, start, end)
    }
    val closing = when (typed) {
        '*' -> '*'
        '_' -> '_'
        '~' -> '~'
        '(' -> ')'
        '[' -> ']'
        '"' -> '"'
        else -> return null
    }
    val selected = textBefore.subSequence(start, end)
    val updated = buildString(textBefore.length + 2) {
        append(textBefore, 0, start)
        append(typed)
        append(selected)
        append(closing)
        append(textBefore, end, textBefore.length)
    }
    return LocalEditResult(updated, TextRange(start + 1, end + 1))
}

/** Applies one lightweight Markdown formatting action to a bounded text buffer and selection. */
internal fun applyMarkdownFormatAction(
    text: CharSequence,
    selection: TextRange,
    action: MarkdownFormatAction,
    hasPreviousWindow: Boolean = false
): LocalEditResult? {
    val start = selection.min.coerceIn(0, text.length)
    val end = selection.max.coerceIn(start, text.length)
    return when (action) {
        MarkdownFormatAction.Bold -> toggleWrap(text, start, end, "**", "**")
        MarkdownFormatAction.Italic -> toggleWrap(text, start, end, "*", "*")
        MarkdownFormatAction.Code -> toggleCodeWrap(text, start, end)
        MarkdownFormatAction.Link -> applyLink(text, start, end)
        MarkdownFormatAction.Heading -> toggleLinePrefix(text, start, end, "# ", hasPreviousWindow)
        MarkdownFormatAction.BulletList -> toggleLinePrefix(text, start, end, "- ", hasPreviousWindow)
        MarkdownFormatAction.Checklist -> toggleLinePrefix(text, start, end, "- [ ] ", hasPreviousWindow)
        MarkdownFormatAction.Quote -> toggleLinePrefix(text, start, end, "> ", hasPreviousWindow)
        MarkdownFormatAction.Indent -> indentSelectedLines(text, start, end, outdent = false, hasPreviousWindow = hasPreviousWindow)
        MarkdownFormatAction.Outdent -> indentSelectedLines(text, start, end, outdent = true, hasPreviousWindow = hasPreviousWindow)
    }
}

private fun toggleWrap(
    text: CharSequence,
    start: Int,
    end: Int,
    open: String,
    close: String
): LocalEditResult {
    val alreadyWrapped = if (open == "*" && close == "*") {
        // Distinguish `*italic*` / `***bold+italic***` (odd unescaped star count) from `**bold**`
        // (even) and literal escaped asterisks like `\*word*`.
        var leftStars = 0
        while (start - leftStars > 0 && text[start - leftStars - 1] == '*') {
            leftStars += 1
        }
        if (leftStars > 0 && hasOddPrecedingBackslashes(text, start - leftStars)) {
            leftStars -= 1
        }
        var rightStars = 0
        if (!hasOddPrecedingBackslashes(text, end)) {
            while (end + rightStars < text.length && text[end + rightStars] == '*') {
                rightStars += 1
            }
        }
        minOf(leftStars, rightStars) % 2 == 1
    } else {
        start >= open.length &&
            end + close.length <= text.length &&
            !hasOddPrecedingBackslashes(text, start - open.length) &&
            !hasOddPrecedingBackslashes(text, end) &&
            text.subSequence(start - open.length, start).contentEquals(open) &&
            text.subSequence(end, end + close.length).contentEquals(close)
    }
    if (alreadyWrapped) {
        val updated = buildString(text.length - open.length - close.length) {
            append(text, 0, start - open.length)
            append(text, start, end)
            append(text, end + close.length, text.length)
        }
        return LocalEditResult(updated, TextRange(start - open.length, end - open.length))
    }
    val updated = buildString(text.length + open.length + close.length) {
        append(text, 0, start)
        append(open)
        append(text, start, end)
        append(close)
        append(text, end, text.length)
    }
    return LocalEditResult(updated, TextRange(start + open.length, end + open.length))
}

private data class ParsedCodeSpan(
    val openStart: Int,
    val innerStart: Int,
    val innerEnd: Int,
    val closeEnd: Int
)

/** Returns whether `text[lineStart until lineEnd]` is an ATX heading line (single-line block). */
private fun isAtxHeadingLine(text: CharSequence, lineStart: Int, lineEnd: Int): Boolean {
    var i = lineStart
    var spaces = 0
    while (i < lineEnd && text[i] == ' ' && spaces < 3) {
        i += 1
        spaces += 1
    }
    var hashes = 0
    while (i < lineEnd && text[i] == '#' && hashes <= 6) {
        i += 1
        hashes += 1
    }
    return hashes in 1..6 && (i == lineEnd || text[i] == ' ' || text[i] == '\t' || text[i] == '\r')
}

/**
 * Returns whether `text[lineStart until lineEnd]` interrupts a CommonMark paragraph (blank line,
 * ATX heading, blockquote, fenced code, list item, or setext underline).
 */
private fun isBlockInterruptingLine(text: CharSequence, lineStart: Int, lineEnd: Int): Boolean {
    val contentEnd = if (lineEnd > lineStart && text[lineEnd - 1] == '\r') lineEnd - 1 else lineEnd
    if ((lineStart until contentEnd).all { text[it] == ' ' || text[it] == '\t' }) return true
    if (isAtxHeadingLine(text, lineStart, contentEnd)) return true
    var i = lineStart
    var spaces = 0
    while (i < contentEnd && text[i] == ' ' && spaces < 3) {
        i += 1
        spaces += 1
    }
    if (i >= contentEnd) return true
    val ch = text[i]
    if (ch == '>') return true
    if (ch == '`' || ch == '~') {
        var run = 0
        while (i + run < contentEnd && text[i + run] == ch) run += 1
        if (run >= 3) return true
    }
    // CommonMark §4.1 thematic breaks (`***`, `---`, `___`, with optional spaces/tabs).
    if (ch == '*' || ch == '-' || ch == '_') {
        var count = 0
        var valid = true
        for (k in i until contentEnd) {
            val c = text[k]
            if (c == ch) count += 1
            else if (c != ' ' && c != '\t') {
                valid = false
                break
            }
        }
        if (valid && count >= 3) return true
    }
    // CommonMark §4.6 HTML block openers (`<!--`, `<?`, `<!`, `<![CDATA[`, or Type 1/6 block tags).
    if (ch == '<' && startsHtmlBlock(text, i, contentEnd)) {
        return true
    }
    if ((ch == '-' || ch == '*' || ch == '+') && (i + 1 == contentEnd || text[i + 1] == ' ' || text[i + 1] == '\t')) {
        return true
    }
    if (ch == '=' || ch == '-') {
        var j = i
        while (j < contentEnd && text[j] == ch) j += 1
        while (j < contentEnd && (text[j] == ' ' || text[j] == '\t')) j += 1
        if (j == contentEnd) return true
    }
    if (ch in '0'..'9') {
        var j = i
        var digits = 0
        while (j < contentEnd && text[j] in '0'..'9' && digits <= 9) {
            j += 1
            digits += 1
        }
        if (digits in 1..9 && j < contentEnd && (text[j] == '.' || text[j] == ')') &&
            (j + 1 == contentEnd || text[j + 1] == ' ' || text[j + 1] == '\t')
        ) {
            return true
        }
    }
    return false
}

/** CommonMark 0.31.2 §4.6 Type 1 HTML block tag names (opening tags only). */
private val COMMONMARK_HTML_TYPE1_TAGS = setOf(
    "pre", "script", "style", "textarea"
)

/** CommonMark 0.31.2 §4.6 Type 6 HTML block tag names (opening or closing tags; `source` is NOT Type 6). */
private val COMMONMARK_HTML_TYPE6_TAGS = setOf(
    "address", "article", "aside", "base", "basefont", "blockquote", "body", "caption", "center",
    "col", "colgroup", "dd", "details", "dialog", "dir", "div", "dl", "dt", "fieldset",
    "figcaption", "figure", "footer", "form", "frame", "frameset", "h1", "h2", "h3", "h4", "h5",
    "h6", "head", "header", "hr", "html", "iframe", "legend", "li", "link", "main", "menu",
    "menuitem", "nav", "noframes", "ol", "optgroup", "option", "p", "param",
    "search", "section", "summary", "table", "tbody", "td", "tfoot",
    "th", "thead", "title", "tr", "track", "ul"
)

private fun startsHtmlBlock(
    text: CharSequence,
    start: Int,
    end: Int,
    allowType7: Boolean = false
): Boolean {
    if (start + 1 >= end || text[start] != '<') return false
    val second = text[start + 1]
    // CommonMark §4.6 Type 2 (`<!--`), Type 3 (`<?`), Type 4 (`<!` + ASCII letter), Type 5 (`<![CDATA[`).
    if (second == '?') return true
    if (second == '!') {
        val rest = text.subSequence(start, end)
        if (rest.startsWith("<!--") || rest.startsWith("<![CDATA[")) return true
        return start + 2 < end && (text[start + 2] in 'A'..'Z' || text[start + 2] in 'a'..'z')
    }
    var nameStart = start + 1
    val isClosing = second == '/'
    if (isClosing) {
        nameStart += 1
    }
    if (nameStart >= end || (text[nameStart] !in 'a'..'z' && text[nameStart] !in 'A'..'Z')) {
        return false
    }
    var nameEnd = nameStart + 1
    while (nameEnd < end && (text[nameEnd] in 'a'..'z' || text[nameEnd] in 'A'..'Z' || text[nameEnd] in '0'..'9' || text[nameEnd] == '-')) {
        nameEnd += 1
    }
    val afterOk = nameEnd == end ||
        text[nameEnd] == ' ' ||
        text[nameEnd] == '\t' ||
        text[nameEnd] == '>' ||
        (!isClosing && text[nameEnd] == '/' && nameEnd + 1 < end && text[nameEnd + 1] == '>')
    if (!afterOk) return false
    val tagName = text.subSequence(nameStart, nameEnd).toString().lowercase()
    if ((!isClosing && tagName in COMMONMARK_HTML_TYPE1_TAGS) || tagName in COMMONMARK_HTML_TYPE6_TAGS) {
        return true
    }
    // CommonMark §4.6 Type 7: a standalone complete open/close tag followed only by optional spaces/tabs.
    if (allowType7) {
        var cursor = nameEnd
        var inSingle = false
        var inDouble = false
        while (cursor < end) {
            val ch = text[cursor]
            when {
                inSingle -> if (ch == '\'') inSingle = false
                inDouble -> if (ch == '"') inDouble = false
                ch == '\'' -> inSingle = true
                ch == '"' -> inDouble = true
                ch == '<' -> return false
                ch == '>' -> {
                    val trailingBlank = (cursor + 1 until end).all { text[it] == ' ' || text[it] == '\t' }
                    return trailingBlank
                }
            }
            cursor += 1
        }
    }
    return false
}

/** Returns whether `text[lineStart until lineEnd]` begins a CommonMark §4.6 HTML block. */
private fun isHtmlBlockOpeningLine(
    text: CharSequence,
    lineStart: Int,
    lineEnd: Int,
    allowType7: Boolean = false
): Boolean {
    val contentEnd = if (lineEnd > lineStart && text[lineEnd - 1] == '\r') lineEnd - 1 else lineEnd
    var i = lineStart
    var spaces = 0
    while (i < contentEnd && text[i] == ' ' && spaces < 3) {
        i += 1
        spaces += 1
    }
    return i < contentEnd && text[i] == '<' && startsHtmlBlock(text, i, contentEnd, allowType7 = allowType7)
}

/** Returns whether the line preceding [lineStart] ends a block (start of text or interrupting line). */
private fun isAtStartOfBlock(text: CharSequence, lineStart: Int): Boolean {
    if (lineStart <= 0) return true
    val prevLineEnd = lineStart - 1
    var prevLineStart = prevLineEnd
    while (prevLineStart > 0 && text[prevLineStart - 1] != '\n') prevLineStart -= 1
    return isBlockInterruptingLine(text, prevLineStart, prevLineEnd)
}

/** Returns `[blockStart, blockEnd)` containing [pos], bounded by CommonMark block boundaries. */
private fun paragraphBoundsAround(text: CharSequence, pos: Int): Pair<Int, Int> {
    val clamped = pos.coerceIn(0, text.length)
    var lineStart = clamped
    while (lineStart > 0 && text[lineStart - 1] != '\n') {
        lineStart -= 1
    }
    var lineEnd = clamped
    while (lineEnd < text.length && text[lineEnd] != '\n') {
        lineEnd += 1
    }
    if (
        isAtxHeadingLine(text, lineStart, lineEnd) ||
        isHtmlBlockOpeningLine(text, lineStart, lineEnd, allowType7 = isAtStartOfBlock(text, lineStart))
    ) {
        return lineStart to lineEnd
    }
    var paragraphStart = lineStart
    while (paragraphStart > 0) {
        val prevLineEnd = paragraphStart - 1
        var prevLineStart = prevLineEnd
        while (prevLineStart > 0 && text[prevLineStart - 1] != '\n') {
            prevLineStart -= 1
        }
        if (isBlockInterruptingLine(text, prevLineStart, prevLineEnd)) break
        paragraphStart = prevLineStart
    }
    var paragraphEnd = lineEnd
    while (paragraphEnd < text.length) {
        val nextLineStart = paragraphEnd + 1
        var nextLineEnd = nextLineStart
        while (nextLineEnd < text.length && text[nextLineEnd] != '\n') {
            nextLineEnd += 1
        }
        if (isBlockInterruptingLine(text, nextLineStart, nextLineEnd)) break
        paragraphEnd = nextLineEnd
    }
    return paragraphStart to paragraphEnd
}

/** Returns whether `text[from until until]` crosses a CommonMark block boundary. */
private fun containsBlockBoundary(text: CharSequence, from: Int, until: Int): Boolean {
    var hasNewline = false
    var index = from
    while (index < until) {
        if (text[index] == '\n') {
            hasNewline = true
            break
        }
        index += 1
    }
    if (!hasNewline) return false
    var firstLineStart = from
    while (firstLineStart > 0 && text[firstLineStart - 1] != '\n') firstLineStart -= 1
    var firstLineEnd = from
    while (firstLineEnd < text.length && text[firstLineEnd] != '\n') firstLineEnd += 1
    if (isAtxHeadingLine(text, firstLineStart, firstLineEnd) ||
        isHtmlBlockOpeningLine(text, firstLineStart, firstLineEnd, allowType7 = isAtStartOfBlock(text, firstLineStart))
    ) {
        return true
    }
    // Check whether the enclosing non-blank run began with an HTML block opener (Types 1-7).
    // Types 6 and 7 run until the next blank line even across `# heading` / list-like lines.
    var blockStart = firstLineStart
    while (blockStart > 0) {
        val prevEnd = blockStart - 1
        var prevStart = prevEnd
        while (prevStart > 0 && text[prevStart - 1] != '\n') prevStart -= 1
        val prevContentEnd = if (prevEnd > prevStart && text[prevEnd - 1] == '\r') prevEnd - 1 else prevEnd
        if ((prevStart until prevContentEnd).all { text[it] == ' ' || text[it] == '\t' }) {
            break
        }
        if (isHtmlBlockOpeningLine(text, prevStart, prevEnd, allowType7 = false)) return true
        blockStart = prevStart
    }
    if (blockStart < firstLineStart) {
        var blockStartEnd = blockStart
        while (blockStartEnd < text.length && text[blockStartEnd] != '\n') blockStartEnd += 1
        if (isHtmlBlockOpeningLine(text, blockStart, blockStartEnd, allowType7 = isAtStartOfBlock(text, blockStart))) {
            return true
        }
    }
    var cursor = firstLineEnd + 1
    while (cursor <= until && cursor <= text.length) {
        var lineEnd = cursor
        while (lineEnd < text.length && text[lineEnd] != '\n') lineEnd += 1
        if (isBlockInterruptingLine(text, cursor, lineEnd)) return true
        cursor = lineEnd + 1
    }
    return false
}

/**
 * Parses inline code spans in `text[paragraphStart until paragraphEnd]` per CommonMark §6.1:
 * an unescaped opening backtick run of length `L` pairs only with the next exact length-`L` run
 * in the same paragraph; unmatched runs remain literal text.
 */
private fun parseCommonMarkCodeSpans(
    text: CharSequence,
    paragraphStart: Int,
    paragraphEnd: Int
): List<ParsedCodeSpan> {
    val spans = ArrayList<ParsedCodeSpan>()
    var index = paragraphStart
    while (index < paragraphEnd) {
        if (text[index] != '`') {
            index += 1
            continue
        }
        if (hasOddPrecedingBackslashes(text, index)) {
            index += 1
            continue
        }
        var openLen = 0
        while (index + openLen < paragraphEnd && text[index + openLen] == '`') {
            openLen += 1
        }
        var search = index + openLen
        var matchedCloseStart = -1
        while (search < paragraphEnd) {
            if (text[search] == '`') {
                var closeLen = 0
                while (search + closeLen < paragraphEnd && text[search + closeLen] == '`') {
                    closeLen += 1
                }
                if (closeLen == openLen) {
                    matchedCloseStart = search
                    break
                }
                search += closeLen
            } else {
                search += 1
            }
        }
        if (matchedCloseStart >= 0) {
            spans.add(
                ParsedCodeSpan(
                    openStart = index,
                    innerStart = index + openLen,
                    innerEnd = matchedCloseStart,
                    closeEnd = matchedCloseStart + openLen
                )
            )
            index = matchedCloseStart + openLen
        } else {
            index += openLen
        }
    }
    return spans
}

/**
 * Toggles a CommonMark §6.1 inline code span around `start..end`, picking a backtick fence longer
 * than any contiguous run of backticks in the selection and padding with spaces when required.
 */
private fun toggleCodeWrap(
    text: CharSequence,
    start: Int,
    end: Int
): LocalEditResult? {
    if (!containsBlockBoundary(text, start, end)) {
        val (paragraphStart, paragraphEnd) = paragraphBoundsAround(text, start)
        if (end <= paragraphEnd) {
            val spans = parseCommonMarkCodeSpans(text, paragraphStart, paragraphEnd)
            val exactSpan = spans.firstOrNull { span ->
                (span.innerStart == start && span.innerEnd == end) ||
                    (span.innerStart + 1 == start &&
                        span.innerEnd - 1 == end &&
                        text[span.innerStart] == ' ' &&
                        text[span.innerEnd - 1] == ' ')
            }
            if (exactSpan != null) {
                val removedPrefix = start - exactSpan.openStart
                val updated = buildString(text.length - (exactSpan.closeEnd - exactSpan.openStart - (end - start))) {
                    append(text, 0, exactSpan.openStart)
                    append(text, start, end)
                    append(text, exactSpan.closeEnd, text.length)
                }
                return LocalEditResult(updated, TextRange(start - removedPrefix, end - removedPrefix))
            }
            // Collapsed cursor inside an empty single-backtick pair `|` (produced by tapping Code
            // with no selection): unwrap back to empty when not part of a longer run or real span.
            if (
                start == end &&
                start > 0 &&
                end < text.length &&
                text[start - 1] == '`' &&
                text[end] == '`' &&
                (start == 1 || text[start - 2] != '`') &&
                (end + 1 == text.length || text[end + 1] != '`') &&
                !hasOddPrecedingBackslashes(text, start - 1) &&
                spans.none { (start - 1) in it.openStart until it.closeEnd }
            ) {
                val updated = buildString(text.length - 2) {
                    append(text, 0, start - 1)
                    append(text, end + 1, text.length)
                }
                return LocalEditResult(updated, TextRange(start - 1, start - 1))
            }
        }
    }
    return wrapCodeSpan(text, start, end)
}

private fun wrapCodeSpan(
    text: CharSequence,
    start: Int,
    end: Int
): LocalEditResult? {
    if (containsBlockBoundary(text, start, end)) {
        return null
    }
    val (paragraphStart, paragraphEnd) = paragraphBoundsAround(text, start)
    val spans = parseCommonMarkCodeSpans(text, paragraphStart, paragraphEnd)
    val startInsideCodeSpan = spans.any { start > it.openStart && start < it.closeEnd }
    // CommonMark §6.1 merges adjacent backtick runs and escapes an opening backtick immediately
    // preceded by an odd number of backslashes outside a code span. Refuse to produce broken spans.
    val escapedOpeningBacktick = !startInsideCodeSpan && hasOddPrecedingBackslashes(text, start)
    val leftBacktickInSpan = start > 0 && spans.any { (start - 1) in it.openStart until it.closeEnd }
    val leftBacktickIsEscapedLiteral =
        start > 0 &&
            text[start - 1] == '`' &&
            !leftBacktickInSpan &&
            hasOddPrecedingBackslashes(text, start - 1)
    val touchesLeftBacktick = start > 0 && text[start - 1] == '`' && !leftBacktickIsEscapedLiteral
    val touchesRightBacktick = end < text.length && text[end] == '`'
    if (escapedOpeningBacktick || touchesLeftBacktick || touchesRightBacktick) {
        return null
    }
    var maxBacktickRun = 0
    var currentRun = 0
    for (index in start until end) {
        if (text[index] == '`') {
            currentRun += 1
            if (currentRun > maxBacktickRun) {
                maxBacktickRun = currentRun
            }
        } else {
            currentRun = 0
        }
    }
    val delim = "`".repeat(maxBacktickRun + 1)
    val needsSpacePadding = start < end && (
        text[start] == '`' ||
            text[end - 1] == '`' ||
            (text[start] == ' ' && text[end - 1] == ' ' && (start until end).any { text[it] != ' ' })
        )
    val pad = if (needsSpacePadding) " " else ""
    val updated = buildString(text.length + (delim.length + pad.length) * 2) {
        append(text, 0, start)
        append(delim)
        append(pad)
        append(text, start, end)
        append(pad)
        append(delim)
        append(text, end, text.length)
    }
    val offset = delim.length + pad.length
    return LocalEditResult(updated, TextRange(start + offset, end + offset))
}

private fun hasOddPrecedingBackslashes(text: CharSequence, index: Int): Boolean {
    var count = 0
    var cursor = index - 1
    while (cursor >= 0 && text[cursor] == '\\') {
        count += 1
        cursor -= 1
    }
    return count % 2 == 1
}

private fun applyLink(text: CharSequence, start: Int, end: Int): LocalEditResult {
    val selected = text.subSequence(start, end)
    val updated = buildString(text.length + 4) {
        append(text, 0, start)
        append('[')
        append(selected)
        append("]()")
        append(text, end, text.length)
    }
    val nextSelection = if (start == end) {
        TextRange(start + 1)
    } else {
        TextRange(end + 3)
    }
    return LocalEditResult(updated, nextSelection)
}

private fun toggleLinePrefix(
    text: CharSequence,
    start: Int,
    end: Int,
    prefix: String,
    hasPreviousWindow: Boolean = false
): LocalEditResult? {
    val lineRange = selectedLinesRange(text, start, end)
    if (hasPreviousWindow && lineRange.first == 0) return null
    val lines = text.subSequence(lineRange.first, lineRange.second).toString().split('\n')
    val allPrefixed = lines.all { it.startsWith(prefix) }
    val transformed = lines.map { line ->
        if (allPrefixed) line.removePrefix(prefix) else prefix + line
    }
    val replacement = transformed.joinToString("\n")
    val updated = buildString(text.length - (lineRange.second - lineRange.first) + replacement.length) {
        append(text, 0, lineRange.first)
        append(replacement)
        append(text, lineRange.second, text.length)
    }
    val deltaFirst = if (allPrefixed) -prefix.length else prefix.length
    val deltaTotal = replacement.length - (lineRange.second - lineRange.first)
    val newStart = (start + deltaFirst).coerceIn(lineRange.first, updated.length)
    val newEnd = if (start == end) {
        newStart
    } else {
        (end + deltaTotal).coerceIn(newStart, updated.length)
    }
    return LocalEditResult(updated, TextRange(newStart, newEnd))
}

/** Indents or outdents every line intersecting `[start, end)`, excluding a trailing col-0 line. */
internal fun indentSelectedLines(
    text: CharSequence,
    start: Int,
    end: Int,
    outdent: Boolean,
    indentUnit: String = "  ",
    hasPreviousWindow: Boolean = false
): LocalEditResult? {
    val lineRange = selectedLinesRange(text, start, end)
    if (hasPreviousWindow && lineRange.first == 0) return null
    val lines = text.subSequence(lineRange.first, lineRange.second).toString().split('\n')
    var firstLineDelta = 0
    val transformed = lines.mapIndexed { index, line ->
        if (outdent) {
            val removed = when {
                line.startsWith(indentUnit) -> indentUnit.length
                line.startsWith('\t') -> 1
                line.startsWith(' ') -> 1
                else -> 0
            }
            if (index == 0) firstLineDelta = -removed
            line.substring(removed)
        } else {
            if (index == 0) firstLineDelta = indentUnit.length
            indentUnit + line
        }
    }
    val replacement = transformed.joinToString("\n")
    val updated = buildString(text.length - (lineRange.second - lineRange.first) + replacement.length) {
        append(text, 0, lineRange.first)
        append(replacement)
        append(text, lineRange.second, text.length)
    }
    val deltaTotal = replacement.length - (lineRange.second - lineRange.first)
    val newStart = (start + firstLineDelta).coerceIn(lineRange.first, updated.length)
    val newEnd = if (start == end) {
        newStart
    } else {
        (end + deltaTotal).coerceIn(newStart, updated.length)
    }
    return LocalEditResult(updated, TextRange(newStart, newEnd))
}

/** Duplicates the line(s) covered by [selection] immediately below them when complete in the window. */
internal fun duplicateSelectedLines(
    text: CharSequence,
    selection: TextRange,
    hasPreviousWindow: Boolean = false,
    hasNextWindow: Boolean = false
): LocalEditResult? {
    val start = selection.min.coerceIn(0, text.length)
    val end = selection.max.coerceIn(start, text.length)
    val lineRange = selectedLinesRange(text, start, end)
    if (hasPreviousWindow && lineRange.first == 0) return null
    if (hasNextWindow && lineRange.second == text.length) return null
    val chunk = text.subSequence(lineRange.first, lineRange.second)
    val inserted = "\n$chunk"
    val updated = buildString(text.length + inserted.length) {
        append(text, 0, lineRange.second)
        append(inserted)
        append(text, lineRange.second, text.length)
    }
    val shift = inserted.length
    return LocalEditResult(updated, TextRange(start + shift, end + shift))
}

/** Moves the line(s) covered by [selection] up or down by one line within the bounded window. */
internal fun moveSelectedLines(
    text: CharSequence,
    selection: TextRange,
    moveUp: Boolean,
    hasPreviousWindow: Boolean = false,
    hasNextWindow: Boolean = false
): LocalEditResult? {
    val start = selection.min.coerceIn(0, text.length)
    val end = selection.max.coerceIn(start, text.length)
    val (blockStart, blockEnd) = selectedLinesRange(text, start, end)
    // Both edges of the selected block must be complete inside this edit window so moving never
    // joins an unseen prefix or suffix outside the window onto an adjacent line.
    if (hasPreviousWindow && blockStart == 0) return null
    if (hasNextWindow && blockEnd == text.length) return null
    if (moveUp) {
        if (blockStart == 0) return null
        val prevLineEnd = blockStart - 1
        var prevLineStart = prevLineEnd
        while (prevLineStart > 0 && text[prevLineStart - 1] != '\n') prevLineStart -= 1
        if (hasPreviousWindow && prevLineStart == 0) return null
        val prevLine = text.subSequence(prevLineStart, prevLineEnd)
        val block = text.subSequence(blockStart, blockEnd)
        val updated = buildString(text.length) {
            append(text, 0, prevLineStart)
            append(block)
            append('\n')
            append(prevLine)
            append(text, blockEnd, text.length)
        }
        val shift = blockStart - prevLineStart
        val newStart = (start - shift).coerceIn(0, updated.length)
        val newEnd = (end - shift).coerceIn(newStart, updated.length)
        return LocalEditResult(updated, TextRange(newStart, newEnd))
    } else {
        if (blockEnd >= text.length) return null
        val nextLineStart = blockEnd + 1
        // At a bounded window tail ending in '\n' with unseen content beyond, do not treat EOF as an empty line.
        if (hasNextWindow && nextLineStart >= text.length) return null
        val nextNl = indexOfNewline(text, nextLineStart, text.length)
        if (hasNextWindow && nextNl < 0) return null
        val nextLineEnd = if (nextNl >= 0) nextNl else text.length
        val nextLine = text.subSequence(nextLineStart, nextLineEnd)
        val block = text.subSequence(blockStart, blockEnd)
        val updated = buildString(text.length) {
            append(text, 0, blockStart)
            append(nextLine)
            append('\n')
            append(block)
            append(text, nextLineEnd, text.length)
        }
        val shift = nextLine.length + 1
        val movedBlockEnd = blockStart + shift + block.length
        val newStart = (start + shift).coerceIn(0, updated.length)
        val rawEnd = if (end > blockEnd && movedBlockEnd == updated.length) {
            // Trailing newline of the moved block became the separator before it at EOF.
            movedBlockEnd
        } else {
            end + shift
        }
        val newEnd = rawEnd.coerceIn(newStart, updated.length)
        return LocalEditResult(updated, TextRange(newStart, newEnd))
    }
}

private fun selectedLinesRange(text: CharSequence, start: Int, end: Int): Pair<Int, Int> {
    var lineStart = start
    while (lineStart > 0 && text[lineStart - 1] != '\n') lineStart -= 1
    val effectiveEnd = if (end > start && end > 0 && text[end - 1] == '\n') end - 1 else end
    val nextNl = indexOfNewline(text, effectiveEnd, text.length)
    val lineEnd = if (nextNl >= 0) nextNl else text.length
    return lineStart to lineEnd
}

private fun indexOfNewline(text: CharSequence, from: Int, to: Int): Int {
    for (i in from until to) {
        if (text[i] == '\n') return i
    }
    return -1
}
