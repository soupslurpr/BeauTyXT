package dev.soupslurpr.beautyxt.ui.editor

import androidx.compose.ui.text.TextRange
import dev.soupslurpr.beautyxt.markdown.SyntaxTokenRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownEditAssistTest {

    @Test
    fun enterContinuesBulletsChecklistsOrderedListsAndQuotes() {
        // Bullet list
        val bullet = computeMarkdownEnterContinuation(
            textBefore = "- item",
            selectionBefore = TextRange(6),
            textAfter = "- item\n",
            selectionAfter = TextRange(7)
        )
        assertEquals(LocalEditResult("- item\n- ", TextRange(9)), bullet)

        // Checked checklist resets to unchecked `- [ ] `
        val checklist = computeMarkdownEnterContinuation(
            textBefore = "  - [x] done",
            selectionBefore = TextRange(12),
            textAfter = "  - [x] done\n",
            selectionAfter = TextRange(13)
        )
        assertEquals(LocalEditResult("  - [x] done\n  - [ ] ", TextRange(21)), checklist)

        // Ordered list increments number
        val ordered = computeMarkdownEnterContinuation(
            textBefore = "3. third",
            selectionBefore = TextRange(8),
            textAfter = "3. third\n",
            selectionAfter = TextRange(9)
        )
        assertEquals(LocalEditResult("3. third\n4. ", TextRange(12)), ordered)

        // Blockquote preserves prefix
        val quote = computeMarkdownEnterContinuation(
            textBefore = "> note",
            selectionBefore = TextRange(6),
            textAfter = "> note\n",
            selectionAfter = TextRange(7)
        )
        assertEquals(LocalEditResult("> note\n> ", TextRange(9)), quote)

        // Empty prefix clears line on Enter
        val cleared = computeMarkdownEnterContinuation(
            textBefore = "- one\n- ",
            selectionBefore = TextRange(8),
            textAfter = "- one\n- \n",
            selectionAfter = TextRange(9)
        )
        assertEquals(LocalEditResult("- one\n", TextRange(6)), cleared)

        // Thematic breaks (`- - -`, `* * *`) must NOT be treated as bullet list items.
        assertNull(
            computeMarkdownEnterContinuation(
                textBefore = "- - -",
                selectionBefore = TextRange(5),
                textAfter = "- - -\n",
                selectionAfter = TextRange(6)
            )
        )
        assertNull(
            computeMarkdownEnterContinuation(
                textBefore = "* * *",
                selectionBefore = TextRange(5),
                textAfter = "* * *\n",
                selectionAfter = TextRange(6)
            )
        )
        // CommonMark §5.2: ordered markers have at most 9 digits and must not roll over to 10.
        assertNull(
            computeMarkdownEnterContinuation(
                textBefore = "1234567890. ",
                selectionBefore = TextRange(12),
                textAfter = "1234567890. \n",
                selectionAfter = TextRange(13)
            )
        )
        assertNull(
            computeMarkdownEnterContinuation(
                textBefore = "999999999. item",
                selectionBefore = TextRange(15),
                textAfter = "999999999. item\n",
                selectionAfter = TextRange(16)
            )
        )
    }

    @Test
    fun enterContinuationSkipsLiteralContexts() {
        assertNull(
            computeMarkdownEnterContinuation(
                textBefore = "```md\n- item",
                selectionBefore = TextRange(12),
                textAfter = "```md\n- item\n",
                selectionAfter = TextRange(13),
                isLiteralContext = { _, _ -> true }
            )
        )
        val frontmatterList = "---\ntags:\n  - tag"
        assertNull(
            computeMarkdownEnterContinuation(
                textBefore = frontmatterList,
                selectionBefore = TextRange(frontmatterList.length),
                textAfter = "$frontmatterList\n",
                selectionAfter = TextRange(frontmatterList.length + 1),
                isLiteralContext = { _, _ -> true }
            )
        )
    }

    @Test
    fun wrapsNonEmptySelectionWithPairedDelimiters() {
        val wrapped = computeMarkdownWrapSelection(
            textBefore = "hello world",
            selectionBefore = TextRange(6, 11),
            textAfter = "hello (",
            selectionAfter = TextRange(7)
        )
        assertEquals(LocalEditResult("hello (world)", TextRange(7, 12)), wrapped)
    }

    @Test
    fun appliesFormattingIndentDuplicateAndMoveLines() {
        val bolded = applyMarkdownFormatAction("hello", TextRange(0, 5), MarkdownFormatAction.Bold)!!
        assertEquals(LocalEditResult("**hello**", TextRange(2, 7)), bolded)
        val unbolded = applyMarkdownFormatAction(bolded.text, bolded.selection, MarkdownFormatAction.Bold)
        assertEquals(LocalEditResult("hello", TextRange(0, 5)), unbolded)

        val indented = indentSelectedLines("a\nb\nc", start = 0, end = 4, outdent = false)
        // Selection ends at offset 4 (col 0 of line 3 "c") -> excludes line "c"
        assertEquals(LocalEditResult("  a\n  b\nc", TextRange(2, 8)), indented)

        val dup = duplicateSelectedLines("one\ntwo", TextRange(0, 0))
        assertEquals(LocalEditResult("one\none\ntwo", TextRange(4, 4)), dup)

        val movedDown = moveSelectedLines("first\nsecond", TextRange(2, 2), moveUp = false)
        assertEquals(LocalEditResult("second\nfirst", TextRange(9, 9)), movedDown)

        // Alt+Down with trailing newline selected at EOF must keep selection within bounds.
        val movedToEof = moveSelectedLines("foo\nbar", TextRange(0, 4), moveUp = false)
        assertEquals(LocalEditResult("bar\nfoo", TextRange(4, 7)), movedToEof)

        // Alt+Down at an edit-window boundary with unseen following text must not join lines.
        assertNull(
            moveSelectedLines(
                text = "first\n",
                selection = TextRange(2, 2),
                moveUp = false,
                hasNextWindow = true
            )
        )
        assertNull(
            moveSelectedLines(
                text = "first\npartial_tail",
                selection = TextRange(2, 2),
                moveUp = false,
                hasNextWindow = true
            )
        )
        // Selected block itself partial at previous or next edit-window boundary must not move.
        assertNull(
            moveSelectedLines(
                text = "partial_prefix\nnext\n",
                selection = TextRange(2, 2),
                moveUp = false,
                hasPreviousWindow = true
            )
        )
        assertNull(
            moveSelectedLines(
                text = "prev\npartial_suffix",
                selection = TextRange(7, 7),
                moveUp = true,
                hasNextWindow = true
            )
        )
        // Duplicate, indent, and line-prefix actions must also respect edit-window boundaries.
        assertNull(
            duplicateSelectedLines(
                text = "partial_prefix\nnext\n",
                selection = TextRange(2, 2),
                hasPreviousWindow = true
            )
        )
        assertNull(
            duplicateSelectedLines(
                text = "prev\npartial_suffix",
                selection = TextRange(7, 7),
                hasNextWindow = true
            )
        )
        assertNull(
            indentSelectedLines(
                text = "partial_prefix\nnext\n",
                start = 2,
                end = 2,
                outdent = false,
                hasPreviousWindow = true
            )
        )
        assertNull(
            applyMarkdownFormatAction(
                text = "partial_prefix\nnext\n",
                selection = TextRange(2, 2),
                action = MarkdownFormatAction.BulletList,
                hasPreviousWindow = true
            )
        )

        // MarkdownAssistSuppression gates paste/drop edits once and expires cleanly.
        var now = 1_000L
        val gate = MarkdownAssistSuppression(nanoTime = { now })
        assertFalse(gate.consume())
        gate.arm()
        assertTrue(gate.consume())
        assertFalse(gate.consume())
        gate.arm()
        now += 300_000_000L
        assertFalse(gate.consume())

        // Pressing Enter right after "- " before "keep" must NOT strip the list marker.
        val splitBeforeBody = computeMarkdownEnterContinuation(
            textBefore = "- keep",
            selectionBefore = TextRange(2),
            textAfter = "- \nkeep",
            selectionAfter = TextRange(3)
        )
        assertNull(splitBeforeBody)

        // When previous edit-window context is unknown (possible open fence), Enter stays plain.
        assertNull(
            computeMarkdownEnterContinuation(
                textBefore = "- ",
                selectionBefore = TextRange(2),
                textAfter = "- \n",
                selectionAfter = TextRange(3),
                hasPreviousWindow = true
            )
        )

        // Linear O(N) prefix parsing must not backtrack on long whitespace + U+2028.
        val longBulletLine = "-" + " ".repeat(20_000) + "\u2028tail"
        val startNs = System.nanoTime()
        val longBulletResult = computeMarkdownEnterContinuation(
            textBefore = longBulletLine,
            selectionBefore = TextRange(longBulletLine.length),
            textAfter = "$longBulletLine\n",
            selectionAfter = TextRange(longBulletLine.length + 1),
            isLiteralContext = { _, _ -> false }
        )
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
        assertTrue("Expected linear bullet parse (< 250ms), took ${elapsedMs}ms", elapsedMs < 250L)
        assertTrue(longBulletResult != null && longBulletResult.text.endsWith("\n-" + " ".repeat(20_000)))

        // Smart Enter inside blockquotes preserves nested list markers (bullet, task, ordered).
        val quotedBullet = computeMarkdownEnterContinuation(
            textBefore = "> - item",
            selectionBefore = TextRange(8),
            textAfter = "> - item\n",
            selectionAfter = TextRange(9),
            isLiteralContext = { _, _ -> false }
        )
        assertEquals("> - item\n> - ", quotedBullet?.text)
        val quotedTask = computeMarkdownEnterContinuation(
            textBefore = "> - [x] done",
            selectionBefore = TextRange(12),
            textAfter = "> - [x] done\n",
            selectionAfter = TextRange(13),
            isLiteralContext = { _, _ -> false }
        )
        assertEquals("> - [x] done\n> - [ ] ", quotedTask?.text)
        val quotedOrdered = computeMarkdownEnterContinuation(
            textBefore = "> 1. first",
            selectionBefore = TextRange(10),
            textAfter = "> 1. first\n",
            selectionAfter = TextRange(11),
            isLiteralContext = { _, _ -> false }
        )
        assertEquals("> 1. first\n> 2. ", quotedOrdered?.text)
        val quotedEmptyBullet = computeMarkdownEnterContinuation(
            textBefore = "> - ",
            selectionBefore = TextRange(4),
            textAfter = "> - \n",
            selectionAfter = TextRange(5),
            isLiteralContext = { _, _ -> false }
        )
        assertEquals("> ", quotedEmptyBullet?.text)

        // Italic toggle on bold `**word**` wraps to `***word***` instead of stripping bold.
        val boldToBoldItalic = applyMarkdownFormatAction(
            text = "**word**",
            selection = TextRange(2, 6),
            action = MarkdownFormatAction.Italic
        )
        assertEquals("***word***", boldToBoldItalic?.text)
        assertEquals(TextRange(3, 7), boldToBoldItalic?.selection)
        val boldItalicToBold = applyMarkdownFormatAction(
            text = "***word***",
            selection = TextRange(3, 7),
            action = MarkdownFormatAction.Italic
        )
        assertEquals("**word**", boldItalicToBold?.text)
        assertEquals(TextRange(2, 6), boldItalicToBold?.selection)

        // Escaped literal asterisk `\*word*` must not be deleted by Italic toggle.
        val escapedItalic = applyMarkdownFormatAction(
            text = "\\*word*",
            selection = TextRange(2, 6),
            action = MarkdownFormatAction.Italic
        )
        assertEquals("\\**word**", escapedItalic?.text)
        assertEquals(TextRange(3, 7), escapedItalic?.selection)

        // Ordered tasks (`1. [x] done`, including inside quotes) preserve checkbox on Enter.
        val orderedTask = computeMarkdownEnterContinuation(
            textBefore = "1. [x] done",
            selectionBefore = TextRange(11),
            textAfter = "1. [x] done\n",
            selectionAfter = TextRange(12),
            isLiteralContext = { _, _ -> false }
        )
        assertEquals("1. [x] done\n2. [ ] ", orderedTask?.text)
        val quotedOrderedTask = computeMarkdownEnterContinuation(
            textBefore = "> 1. [x] done",
            selectionBefore = TextRange(13),
            textAfter = "> 1. [x] done\n",
            selectionAfter = TextRange(14),
            isLiteralContext = { _, _ -> false }
        )
        assertEquals("> 1. [x] done\n> 2. [ ] ", quotedOrderedTask?.text)

        // Empty tasks without trailing space (`- [ ]`, `1. [ ]`) clear on Enter instead of continuing.
        val bareEmptyBulletTask = computeMarkdownEnterContinuation(
            textBefore = "- [ ]",
            selectionBefore = TextRange(5),
            textAfter = "- [ ]\n",
            selectionAfter = TextRange(6),
            isLiteralContext = { _, _ -> false }
        )
        assertEquals("", bareEmptyBulletTask?.text)
        val bareEmptyOrderedTask = computeMarkdownEnterContinuation(
            textBefore = "1. [ ]",
            selectionBefore = TextRange(6),
            textAfter = "1. [ ]\n",
            selectionAfter = TextRange(7),
            isLiteralContext = { _, _ -> false }
        )
        assertEquals("", bareEmptyOrderedTask?.text)

        // CommonMark §6.1 inline code formatting handles selected backticks and toggles cleanly.
        val innerTick = applyMarkdownFormatAction(
            text = "a`b",
            selection = TextRange(0, 3),
            action = MarkdownFormatAction.Code
        )
        assertEquals("``a`b``", innerTick?.text)
        assertEquals(TextRange(2, 5), innerTick?.selection)
        val innerTickUnwrap = applyMarkdownFormatAction(
            text = innerTick!!.text,
            selection = innerTick.selection,
            action = MarkdownFormatAction.Code
        )
        assertEquals("a`b", innerTickUnwrap?.text)
        assertEquals(TextRange(0, 3), innerTickUnwrap?.selection)

        val edgeTicks = applyMarkdownFormatAction(
            text = "`a`",
            selection = TextRange(0, 3),
            action = MarkdownFormatAction.Code
        )
        assertEquals("`` `a` ``", edgeTicks?.text)
        assertEquals(TextRange(3, 6), edgeTicks?.selection)
        val edgeTicksUnwrap = applyMarkdownFormatAction(
            text = edgeTicks!!.text,
            selection = edgeTicks.selection,
            action = MarkdownFormatAction.Code
        )
        assertEquals("`a`", edgeTicksUnwrap?.text)
        assertEquals(TextRange(0, 3), edgeTicksUnwrap?.selection)

        // Selecting text directly between two existing code spans must neither consume nor merge them.
        val betweenSpans = applyMarkdownFormatAction(
            text = "`a` and `b`",
            selection = TextRange(3, 8),
            action = MarkdownFormatAction.Code
        )
        assertNull(betweenSpans)
        // Selecting the word separated by spaces wraps cleanly into a third independent code span.
        val wordBetweenSpans = applyMarkdownFormatAction(
            text = "`a` and `b`",
            selection = TextRange(4, 7),
            action = MarkdownFormatAction.Code
        )
        assertEquals("`a` `and` `b`", wordBetweenSpans?.text)
        assertEquals(TextRange(5, 8), wordBetweenSpans?.selection)
        // Unwrapping either individual code span still works cleanly.
        val unwrapSecondSpan = applyMarkdownFormatAction(
            text = "`a` and `b`",
            selection = TextRange(9, 10),
            action = MarkdownFormatAction.Code
        )
        assertEquals("`a` and b", unwrapSecondSpan?.text)
        assertEquals(TextRange(8, 9), unwrapSecondSpan?.selection)

        // Inside a code span, `\` before the closing backtick is literal (CommonMark §6.1);
        // wrapping text immediately after `` `a\` `` must not merge into the span.
        assertNull(
            applyMarkdownFormatAction(
                text = "`a\\`tail",
                selection = TextRange(4, 8),
                action = MarkdownFormatAction.Code
            )
        )
        // Wrapping `word` directly after an odd backslash `\word` would produce an escaped
        // opening backtick; even backslashes `\\word` wrap cleanly.
        assertNull(
            applyMarkdownFormatAction(
                text = "\\word",
                selection = TextRange(1, 5),
                action = MarkdownFormatAction.Code
            )
        )
        assertEquals(
            "\\\\`word`",
            applyMarkdownFormatAction(
                text = "\\\\word",
                selection = TextRange(2, 6),
                action = MarkdownFormatAction.Code
            )?.text
        )
        // An unmatched backtick in an earlier paragraph must not block unwrapping a later span.
        val unwrapAfterParagraph = applyMarkdownFormatAction(
            text = "loose ` tick\n\n`valid`",
            selection = TextRange(15, 20),
            action = MarkdownFormatAction.Code
        )
        assertEquals("loose ` tick\n\nvalid", unwrapAfterParagraph?.text)
        assertEquals(TextRange(14, 19), unwrapAfterParagraph?.selection)

        // An unmatched double-backtick in the SAME paragraph stays literal per CommonMark §6.1
        // and must not block unwrapping a later valid single-backtick span.
        val unwrapAfterDoubleTick = applyMarkdownFormatAction(
            text = "``unmatched `valid`",
            selection = TextRange(13, 18),
            action = MarkdownFormatAction.Code
        )
        assertEquals("``unmatched valid", unwrapAfterDoubleTick?.text)
        assertEquals(TextRange(12, 17), unwrapAfterDoubleTick?.selection)

        // Selecting across a blank line or Markdown block boundary (e.g. ATX headings) between
        // literal backticks must NOT delete both ticks.
        assertNull(
            applyMarkdownFormatAction(
                text = "a`\n\n`b",
                selection = TextRange(2, 4),
                action = MarkdownFormatAction.Code
            )
        )
        assertNull(
            applyMarkdownFormatAction(
                text = "# alpha `foo\n# bar`",
                selection = TextRange(9, 18),
                action = MarkdownFormatAction.Code
            )
        )
        assertNull(
            applyMarkdownFormatAction(
                text = "a `foo\n***\nbar`",
                selection = TextRange(3, 14),
                action = MarkdownFormatAction.Code
            )
        )
        assertNull(
            applyMarkdownFormatAction(
                text = "a `foo\n___\nbar`",
                selection = TextRange(3, 14),
                action = MarkdownFormatAction.Code
            )
        )
        assertNull(
            applyMarkdownFormatAction(
                text = "a `foo\n<!-- comment -->\nbar`",
                selection = TextRange(3, 27),
                action = MarkdownFormatAction.Code
            )
        )
        // First selected line starting an HTML block (`<!-- `foo -->\nbar``) must not unwrap across lines.
        assertNull(
            applyMarkdownFormatAction(
                text = "<!-- `foo -->\nbar`",
                selection = TextRange(6, 17),
                action = MarkdownFormatAction.Code
            )
        )
        // `<! 2` is NOT a CommonMark §4.6 HTML declaration and must not block unwrapping a valid multiline span.
        val validMultilineWithBang = applyMarkdownFormatAction(
            text = "`foo\n<! 2`",
            selection = TextRange(1, 9),
            action = MarkdownFormatAction.Code
        )
        assertEquals("foo\n<! 2", validMultilineWithBang?.text)
        assertEquals(TextRange(0, 8), validMultilineWithBang?.selection)

        // `<source>` is NOT a CommonMark §4.6 Type 6 block tag; multiline code spans cross it cleanly.
        val multilineWithSource = applyMarkdownFormatAction(
            text = "`foo\n<source> inline`",
            selection = TextRange(1, 20),
            action = MarkdownFormatAction.Code
        )
        assertEquals("foo\n<source> inline", multilineWithSource?.text)

        // Standalone `<ins>` at start of a block IS a CommonMark §4.6 Type 7 HTML block, including
        // multi-line selections strictly inside its body before the terminating blank line.
        assertNull(
            applyMarkdownFormatAction(
                text = "<ins>\n`foo\nbar`",
                selection = TextRange(0, 14),
                action = MarkdownFormatAction.Code
            )
        )
        assertNull(
            applyMarkdownFormatAction(
                text = "<ins>\nbar`",
                selection = TextRange(1, 9),
                action = MarkdownFormatAction.Code
            )
        )
        assertNull(
            applyMarkdownFormatAction(
                text = "<ins>\nline 1\nline 2\n</ins>",
                selection = TextRange(6, 19),
                action = MarkdownFormatAction.Code
            )
        )
        assertNull(
            applyMarkdownFormatAction(
                text = "<ins>\n# not a heading\nline 1\nline 2\n</ins>",
                selection = TextRange(22, 35),
                action = MarkdownFormatAction.Code
            )
        )
        // Closing Type 1 tag `</script>` does NOT interrupt a CommonMark paragraph.
        val multilineWithClosingScript = applyMarkdownFormatAction(
            text = "`foo\n</script> inline`",
            selection = TextRange(1, 21),
            action = MarkdownFormatAction.Code
        )
        assertEquals("foo\n</script> inline", multilineWithClosingScript?.text)

        // Typing a backtick over a selection containing backticks sizes the delimiter per CommonMark §6.1.
        val typedBacktickWrap = computeMarkdownWrapSelection(
            textBefore = "a`b",
            selectionBefore = TextRange(0, 3),
            textAfter = "`",
            selectionAfter = TextRange(1)
        )
        assertEquals("``a`b``", typedBacktickWrap?.text)
        assertEquals(TextRange(2, 5), typedBacktickWrap?.selection)
    }

    @Test
    fun decodesPackedSyntaxTokensAndComputesReadingTimeAndStats() {
        val token1 = (0L shl 32) or (2L shl 16) or SyntaxTokenRole.Keyword.protocolValue.toLong()
        val token2 = (3L shl 32) or (4L shl 16) or SyntaxTokenRole.Function.protocolValue.toLong()
        val (exitState, runs) = decodePackedSyntaxTokens(longArrayOf(7L, token1, token2), 12)
        assertEquals(7L, exitState)
        assertEquals(2, runs.size)
        assertEquals(SyntaxTokenRole.Keyword, runs[0].role)
        assertEquals(0, runs[0].start)
        assertEquals(2, runs[0].end)
        assertEquals(SyntaxTokenRole.Function, runs[1].role)

        assertNull(estimatedReadingTimeMinutes(0L))
        assertEquals(0, estimatedReadingTimeMinutes(199L))
        assertEquals(1, estimatedReadingTimeMinutes(200L))
        assertEquals(2, estimatedReadingTimeMinutes(201L))

        assertEquals(3L to 14L, countTextWordsAndChars("hello 😀 world!"))
    }
}
