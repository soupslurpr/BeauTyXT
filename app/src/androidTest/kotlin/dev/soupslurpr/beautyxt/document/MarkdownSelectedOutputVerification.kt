package dev.soupslurpr.beautyxt.document

import android.app.Instrumentation
import dev.soupslurpr.beautyxt.markdown.*
import dev.soupslurpr.beautyxt.markdown.client.IsolatedMarkdownRenderer
import dev.soupslurpr.beautyxt.ui.editor.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** Exercises the real producer, strict decoder, selected serializer, and parser again. */
internal fun Instrumentation.verifyMarkdownSelectedOutputRoundTrips() = runBlocking {
    suspend fun render(source: String): MarkdownPreviewDocument = withContext(Dispatchers.IO) {
        RustDocument.createEmpty().use { document ->
            val metrics = document.replace(0, Utf16Range(0, 0), source)
            document.captureSnapshot(metrics.revision).use { snapshot ->
                IsolatedMarkdownRenderer(targetContext).renderPreview(snapshot, metrics.serializedByteLength)
            }
        }
    }
    suspend fun roundTrip(source: String, claimOnly: Boolean = false): Pair<MarkdownPreviewDocument, MarkdownPreviewDocument> {
        val original = render(source)
        val end = if (claimOnly) 0 else original.blocks.lastIndex
        val selection = DocumentSelection.Reading(0, ReadingPoint(0, -readingListPrefix(original.blocks.first()).length),
            ReadingPoint(end, original.blocks[end].text.length))
        val excerpt = selectedMarkdown(original, selection)
        requireSupportedExcerptListStarts(excerpt.document)
        check(excerpt.notices.none { it.kind == ExcerptNoticeKind.MissingNote }) { "Missing actual note: ${excerpt.markdown}" }
        check("PRIVATE SIBLING" !in excerpt.markdown) { "Excerpt imported unselected prose" }
        return original to render(excerpt.markdown)
    }

    for (source in listOf(
        "|A|B|\n|-|-|\n|C||\n|a\tb|c|\n|||\n|D|E|\n",
        "<table><tr><th>A</th><th>B</th></tr><tr><th>C</th><th>D</th></tr><tr><td>E</td><td>F</td></tr></table>",
        "<table><tr><td><strong>A</strong></td><td>B<br>line</td></tr><tr><td>C</td><td>D</td></tr></table>",
        "<div><table><tr><td>A</td><td>B</td></tr></table><table><tr><td>C</td><td>D</td></tr></table></div>"
    )) {
        val (original, reparsed) = roundTrip(source)
        check(reparsed.blocks.all { it.kind == MarkdownBlockKind.TableRow }) { "Table became prose: $source" }
        check(original.blocks.map { it.text } == reparsed.blocks.map { it.text }) { "Table lost selected cells or rows: $source" }
        check(original.blocks.map { it.isTableHeader to it.startsTable } == reparsed.blocks.map { it.isTableHeader to it.startsTable })
        check(original.blocks.map { it.tableCellSeparators } == reparsed.blocks.map { it.tableCellSeparators })
    }
    for (html in listOf(
        "<table><tr><td>A</td><td>B</td></tr><tr><td>C</td><td>D</td></tr></table>",
        "<table><tr><th>A</th><th>B</th></tr><tr><th>C</th><th>D</th></tr><tr><td>E</td><td>F</td></tr></table>"
    )) {
        for (indent in 2..4) {
            val (original, reparsed) = roundTrip("- outer\n\n${" ".repeat(indent)}$html\n- next\n")
            check(original.blocks.map(::selectedOutputBlockShape) == reparsed.blocks.map(::selectedOutputBlockShape))
            val parent = reparsed.blocks.first()
            check(parent.text == "outer" && reparsed.blocks.last().text == "next")
            check(reparsed.blocks.filter { it.kind == MarkdownBlockKind.TableRow }.all { parent.containsSourceOf(it) })
            check(parent.source.end <= reparsed.blocks.last().source.start)
        }
    }
    for (source in listOf("first  \nsecond", "<strong>first<br>second</strong>", "1\\) Literal",
        "[label](https://example.com/?q=&amp;amp;)", "[label](https://example.com/?a=1&b=2)")) {
        val (original, reparsed) = roundTrip(source)
        check(original.blocks.map { it.kind to it.text } == reparsed.blocks.map { it.kind to it.text }) { "Prose changed: $source" }
        check(original.blocks.flatMap { it.spans }.map { it.styles to it.destination } ==
            reparsed.blocks.flatMap { it.spans }.map { it.styles to it.destination }) { "Style or link changed: $source" }
    }
    for (source in listOf("-\n", "-\n  - child\n- sibling\n", "- ```text\n  exact\n  ```\n- sibling\n",
        "> - ```text\n>   exact\n>   ```\n", "-\n  > ```text\n  > exact\n  > ```\n", "- outer\n  > quote\n")) {
        val (original, reparsed) = roundTrip(source)
        check(original.blocks.map { Triple(it.kind, it.text, it.listDepth) } ==
            reparsed.blocks.map { Triple(it.kind, it.text, it.listDepth) }) { "Empty or code-only list changed: $source" }
        check(original.blocks.map { it.quoteDepth } == reparsed.blocks.map { it.quoteDepth })
    }
    val quotedList = "> - outer\n>\n>   > quote\n>\n>   ```text\n>   code child\n>   ```\n> - next\n"
    for (note in listOf(false, true)) {
        val source = if (note) "Claim[^n]\n\n[^n]:\n" + quotedList.lineSequence().filter { it.isNotEmpty() }
            .joinToString("\n", postfix = "\n") { "    $it" } else quotedList
        val (original, reparsed) = roundTrip(source, claimOnly = note)
        val expected = if (note) original.blocks.filter { it.markdownFootnoteLabel() == "n" } else original.blocks
        val actual = if (note) reparsed.blocks.filter { it.markdownFootnoteLabel() == "1" } else reparsed.blocks
        check(expected.map(::selectedOutputBlockShape) == actual.map(::selectedOutputBlockShape)) {
            "Quoted list changed its content or structure: $source"
        }
        val parent = actual.first()
        check(actual.filter { it.text == "quote" || it.kind == MarkdownBlockKind.Code }.all { parent.containsSourceOf(it) })
        check(parent.source.end <= actual.last().source.start)
    }
    for (source in listOf(
        "> [!NOTE]\n> first\n\n> [!TIP]\n> second\n",
        "> > [!NOTE]\n> > first\n>\n> > [!TIP]\n> > second\n",
        "> - outer\n>\n>   > [!NOTE]\n>   > quote\n> - next\n"
    )) {
        val (original, reparsed) = roundTrip(source)
        check(original.blocks.map { Triple(it.text, it.quoteKind, it.quoteDepth) } ==
            reparsed.blocks.map { Triple(it.text, it.quoteKind, it.quoteDepth) })
        check(original.blocks.map { it.startsQuoteAlert } == reparsed.blocks.map { it.startsQuoteAlert })
    }
    for (fixture in markdownAlertFixtures()) {
        val original = render(fixture.source)
        val end = fixture.last ?: if (fixture.claimOnly) 0 else original.blocks.lastIndex
        val start = if (fixture.selectMarker) -readingListPrefix(original.blocks[fixture.first]).length else 0
        val excerpt = selectedMarkdown(original, DocumentSelection.Reading(0, ReadingPoint(fixture.first, start),
            ReadingPoint(end, original.blocks[end].text.length)))
        check("PRIVATE first" !in excerpt.markdown) { "Alert selection imported omitted text" }
        val reparsed = render(excerpt.markdown)
        val expected = if (fixture.claimOnly) excerpt.document.blocks.filter { it.markdownFootnoteLabel() != null }
            else excerpt.document.blocks
        val actual = if (fixture.claimOnly) reparsed.blocks.filter { it.markdownFootnoteLabel() != null }
            else reparsed.blocks
        check(alertOutputShape(expected) == alertOutputShape(actual)) {
            "Alert owner changed ${fixture.name}: ${excerpt.markdown}"
        }
    }
    for (size in listOf(100, 5000, 262500)) {
        for (nestedItem in listOf(false, true)) {
            val prefix = if (nestedItem) "- outer\n  - " else "- "
            val indent = if (nestedItem) "    " else "  "
            val source = "$prefix${"a".repeat(size)}\n$indent```text\n${indent}code child\n$indent```\n- sibling\n"
            val (original, reparsed) = roundTrip(source)
            val codeIndex = reparsed.blocks.indexOfFirst { it.kind == MarkdownBlockKind.Code }
            val parents = reparsed.blocks.take(codeIndex).filter {
                it.kind == MarkdownBlockKind.ListItem && it.listDepth == if (nestedItem) 2 else 1
            }
            check(parents.joinToString("") { it.text } == "a".repeat(size))
            val extent = MarkdownSourceRange(parents.minOf { it.source.start }, parents.maxOf { it.source.end })
            val child = reparsed.blocks[codeIndex]
            check(child.text == "code child\n" && child.quoteDepth == 0)
            check(extent.start <= child.source.start && extent.end >= child.source.end) { "Code detached from $size-character parent" }
            check(reparsed.blocks.last().text == "sibling" && extent.end <= reparsed.blocks.last().source.start)
            val originalCode = original.blocks.indexOfFirst { it.kind == MarkdownBlockKind.Code }
            val clipped = selectedMarkdown(original, DocumentSelection.Reading(0, ReadingPoint(originalCode, 0),
                ReadingPoint(originalCode, original.blocks[originalCode].text.length)))
            val detached = render(clipped.markdown)
            check(detached.blocks.single().kind == MarkdownBlockKind.Code && detached.blocks.single().text == "code child\n")
        }
    }
    for (fixture in markdownListFixtures()) {
        val (original, reparsed) = roundTrip(fixture.source, claimOnly = fixture.note)
        val expected = if (fixture.note) original.blocks.filter { it.markdownFootnoteLabel() == "n" } else original.blocks
        val actual = if (fixture.note) reparsed.blocks.filter { it.markdownFootnoteLabel() == "1" } else reparsed.blocks
        check(expected.map(::selectedOutputBlockShape) == actual.map(::selectedOutputBlockShape)) {
            "List container round trip changed ${fixture.name}"
        }
        if (fixture.name.startsWith("html-detached")) {
            val code = actual.last()
            check(code.kind == MarkdownBlockKind.Code && actual.dropLast(1).all { it.source.end <= code.source.start }) {
                "Equal coarse HTML ranges invented list ownership"
            }
        }
        val child = actual.firstOrNull { it.kind == MarkdownBlockKind.Code || it.kind == MarkdownBlockKind.TableRow }
        if (child != null && fixture.name.startsWith("list-")) {
            val parent = actual.last { it.kind == MarkdownBlockKind.ListItem && it.text == "parent" }
            check(parent.containsSourceOf(child)) { "Child detached in ${fixture.name}" }
            val next = actual.first { it.text == "next" }
            check(next.listDepth == parent.listDepth && parent.source.end <= next.source.start)
            val from = original.blocks.indexOfFirst { it.kind == child.kind }
            var to = from
            if (child.kind == MarkdownBlockKind.TableRow) while (to + 1 < original.blocks.size &&
                original.blocks[to + 1].kind == MarkdownBlockKind.TableRow && !original.blocks[to + 1].startsTable) to++
            val clipped = selectedMarkdown(original, DocumentSelection.Reading(0, ReadingPoint(from, 0),
                ReadingPoint(to, original.blocks[to].text.length)))
            val detached = render(clipped.markdown)
            check(detached.blocks.none { it.kind == MarkdownBlockKind.ListItem })
            check(detached.blocks.none { "outer" in it.text || "parent" in it.text || "sibling" in it.text })
        }
    }
    for (source in listOf("before\n\n---\n\nafter\n", "- parent\n\n  ---\n\n- next\n",
        "before\n\n```\n```\n\nafter\n", "before\n\n```text\n```\n\nafter\n",
        "before\n\n##\n\nafter\n", "- parent\n\n  ```text\n  ```\n\n- next\n")) {
        val (original, reparsed) = roundTrip(source)
        check(original.blocks.map(::selectedOutputBlockShape) == reparsed.blocks.map(::selectedOutputBlockShape)) {
            "Selected empty structural block changed: $source"
        }
    }
    for (scope in 0..2) {
        val base = "999999999. first\n999999999. second\n\n           ```text\n           code child\n           ```\n"
        val source = when (scope) {
            1 -> base.trimEnd('\n').lineSequence().joinToString("\n", postfix = "\n") { "> $it" }
            2 -> "Claim[^n]\n\n[^n]:\n" + base.trimEnd('\n').lineSequence().joinToString("\n", postfix = "\n") { "    $it" }
            else -> base
        }
        val original = render(source)
        val second = original.blocks.indexOfFirst { it.text == "second" }
        val code = original.blocks.indexOfFirst { it.kind == MarkdownBlockKind.Code }
        val clipped = selectedMarkdown(original, DocumentSelection.Reading(0, ReadingPoint(second, 0),
            ReadingPoint(code, original.blocks[code].text.length)))
        val first = clipped.document.blocks.first()
        check(first.startsList && first.isOrderedListItem && first.listNumber == 1_000_000_000L)
        check(runCatching { requireSupportedExcerptListStarts(clipped.document) }.exceptionOrNull()
            is UnsupportedExcerptListStartException)
        val childOnly = selectedMarkdown(original, DocumentSelection.Reading(0, ReadingPoint(code, 0),
            ReadingPoint(code, original.blocks[code].text.length)))
        requireSupportedExcerptListStarts(childOnly.document)
        check(render(childOnly.markdown).blocks.single().kind == MarkdownBlockKind.Code)
    }
    val partialSource = render("- outer\n\n  100. parent\n\n       ```text\n       code child\n       ```\n\n  101. next\n\n- sibling\n")
    val parentIndex = partialSource.blocks.indexOfFirst { it.text == "parent" }
    val nextIndex = partialSource.blocks.indexOfFirst { it.text == "next" }
    val partial = selectedMarkdown(partialSource, DocumentSelection.Reading(0, ReadingPoint(parentIndex, 0),
        ReadingPoint(nextIndex, partialSource.blocks[nextIndex].text.length)))
    val promoted = render(partial.markdown)
    check(promoted.blocks.filter { it.kind == MarkdownBlockKind.ListItem }.map { it.listDepth to it.listNumber } == listOf(1 to 100L, 1 to 101L))
    check(promoted.blocks.none { it.text == "outer" || it.text == "sibling" })
    check(promoted.blocks.first().containsSourceOf(promoted.blocks.single { it.kind == MarkdownBlockKind.Code }))
    val orphan = selectedMarkdown(partialSource, DocumentSelection.Reading(0, ReadingPoint(nextIndex, 0),
        ReadingPoint(partialSource.blocks.lastIndex, partialSource.blocks.last().text.length)))
    val orphaned = render(orphan.markdown)
    check(orphaned.blocks.map { it.text } == listOf("next", "sibling") && orphaned.blocks.all { it.listDepth == 1 })
    val continued = render("100. parent\n\n     > quote child\n101. next\n")
    val continuedIndex = continued.blocks.indexOfFirst { it.text == "quote child" }
    val clippedContinuation = selectedMarkdown(continued, DocumentSelection.Reading(0, ReadingPoint(continuedIndex, 0),
        ReadingPoint(continuedIndex, continued.blocks[continuedIndex].text.length)))
    val continuedOnly = render(clippedContinuation.markdown).blocks.single()
    check(continuedOnly.text == "quote child" && continuedOnly.kind == MarkdownBlockKind.Paragraph && continuedOnly.listDepth == 0 && continuedOnly.quoteDepth == 1)
    for (scope in 0..2) {
        val base = "100. parent\n\n     > quote child\n\n     ```text\n     code child\n     ```\n101. next\n"
        val source = when (scope) {
            1 -> base.trimEnd('\n').lineSequence().joinToString("\n", postfix = "\n") { "> $it" }
            2 -> "Claim[^n]\n\n[^n]:\n" + base.trimEnd('\n').lineSequence().joinToString("\n", postfix = "\n") { "    $it" }
            else -> base
        }
        val original = render(source)
        val quoteIndex = original.blocks.indexOfFirst { it.text == "quote child" }
        val codeIndex = original.blocks.indexOfFirst { it.kind == MarkdownBlockKind.Code }
        val clipped = selectedMarkdown(original, DocumentSelection.Reading(0, ReadingPoint(quoteIndex, 0),
            ReadingPoint(codeIndex, original.blocks[codeIndex].text.length)))
        val result = render(clipped.markdown).blocks
        check(result.map { it.kind } == listOf(MarkdownBlockKind.Paragraph, MarkdownBlockKind.Code))
        check(result.map { it.text } == listOf("quote child", "code child\n"))
        check(result[0].quoteDepth == original.blocks[quoteIndex].quoteDepth && result[1].quoteDepth == original.blocks[codeIndex].quoteDepth)
        check(result.all { it.listDepth == 0 && it.markdownFootnoteLabel() == null })
    }
    for (definition in listOf(
        "First.\n\n    - Detail one\n    - Detail two\n\n    ```rust\n    exact code\n    ```\n\n    # Heading\n\n    |A|B|\n    |-|-|\n    |C|D|\n\n    Last.\n",
        "\n    - list only\n", "\n    ```text\n    code only\n    ```\n", "# heading only\n",
        "\n    - ```text\n      nested code\n      ```\n",
        "\n    -\n      - ```text\n        deeper code\n        ```\n"
    )) {
        val (original, reparsed) = roundTrip("Claim[^n]\n\nPRIVATE SIBLING\n\n[^n]: $definition", claimOnly = true)
        val expected = original.blocks.filter { it.markdownFootnoteLabel() == "n" }
        val actual = reparsed.blocks.filter { it.markdownFootnoteLabel() == "1" }
        check(expected.isNotEmpty() && expected.map { it.kind to it.text } == actual.map { it.kind to it.text }) {
            "Footnote structure changed: $definition; expected=$expected; actual=$actual"
        }
        check(expected.map { it.listDepth to it.isOrderedListItem } == actual.map { it.listDepth to it.isOrderedListItem })
        check(reparsed.blocks.none { "PRIVATE SIBLING" in it.text })
    }
    val html = render("<div><p>Public</p><p>Private sibling</p></div>")
    check(DocumentSelection.Reading(0, ReadingPoint(0, 0), ReadingPoint(0, 6)).exactSource(html) == null)
    val inline = render("<strong>Public</strong>")
    check(DocumentSelection.Reading(0, ReadingPoint(0, 1), ReadingPoint(0, 4)).exactSource(inline) == Utf16Range(9, 12))
    for (row in listOf("|\$x\$|foo|bar|", "|foo|bar|\$x\$|")) {
        val mathTable = render("|A|B|C|\n|-|-|-|\n$row\n")
        check(readingSearchUnits(mathTable, false).units.none { "foo\tbar" in it.text })
    }
    val nested = render("- Parent\n  - Child\n- Next")
    check(markdownRenderedPositionForSourceOffset(nested.blocks, 14) == MarkdownRenderedSourcePosition(1, 1))
}

private fun selectedOutputBlockShape(block: MarkdownRenderBlock) = listOf(
    block.kind, block.text, block.quoteDepth, block.listDepth, block.isOrderedListItem, block.continuesListItem,
    block.listNumber, block.startsList, block.isTableHeader, block.startsTable, block.tableCellSeparators,
    block.quoteKind, block.startsQuoteAlert, block.quoteAlertDepth,
    if (block.kind == MarkdownBlockKind.Code) emptyList() else block.spans.filter { it.styles and MARKDOWN_SPAN_STYLE_CODE != 0 }
        .map { Triple(it.start, it.end, it.styles) }
)

private fun MarkdownRenderBlock.containsSourceOf(child: MarkdownRenderBlock): Boolean =
    source.start <= child.source.start && source.end >= child.source.end

/** Joins transport text while comparing the alert and container semantics of each block. */
private fun alertOutputShape(blocks: List<MarkdownRenderBlock>): List<List<Any?>> {
    val result = ArrayList<List<Any?>>()
    var index = 0
    while (index < blocks.size) {
        val first = blocks[index++]
        val text = StringBuilder(first.text)
        while (index < blocks.size && blocks[index].continuesPrevious) {
            check(blocks[index].quoteAlertDepth == first.quoteAlertDepth)
            text.append(blocks[index++].text)
        }
        result += listOf(first.kind, text.toString(), first.quoteDepth, first.quoteKind, first.quoteAlertDepth,
            first.startsQuoteAlert, first.listDepth, first.listNumber, first.startsList, first.continuesListItem,
            first.metadata, first.markdownFootnoteLabel())
    }
    return result
}

private data class MarkdownAlertFixture(val name: String, val source: String, val first: Int = 0,
    val last: Int? = null, val claimOnly: Boolean = false, val selectMarker: Boolean = false)

private fun markdownAlertFixtures(): List<MarkdownAlertFixture> {
    val inherited = "> [!NOTE]\n> PRIVATE first\n>\n> > deeper\n>\n> after\n"
    val nested = "> [!NOTE]\n> first\n>\n> > [!TIP]\n> > inner\n>\n> after\n"
    val listAlert = "- parent\n\n  > [!NOTE]\n  > > deeper\n  >\n  > after\n\n- next\n"
    val orderedAlert = "> [!NOTE]\n>\n> 2. item\n> 3. next\n"
    return listOf(
        MarkdownAlertFixture("first-content-nested-full", "> [!NOTE]\n> > deeper\n>\n> after\n"),
        MarkdownAlertFixture("inherited-partial", inherited, 1, 2),
        MarkdownAlertFixture("simple", "> [!NOTE]\n> first\n>\n> after\n"),
        MarkdownAlertFixture("plain", "> first\n>\n> > deeper\n>\n> after\n", 1, 2),
        MarkdownAlertFixture("deeper-only", inherited, 1, 1),
        MarkdownAlertFixture("outer-only", inherited, 2, 2),
        MarkdownAlertFixture("genuine-inner", "> before\n>\n> > [!NOTE]\n> > deeper\n>\n> after\n", 1, 2),
        MarkdownAlertFixture("ambiguous-v5-outer", "> [!NOTE]  \n> > deeper\n"),
        MarkdownAlertFixture("ambiguous-v5-inner", "> > [!NOTE]\n> > deeper\n"),
        MarkdownAlertFixture("nested-same-kind", nested.replace("[!TIP]", "[!NOTE]")),
        MarkdownAlertFixture("nested-different-kind", nested),
        MarkdownAlertFixture("nested-different-partial", nested, 1, 2),
        MarkdownAlertFixture("ordered-zero", "> [!NOTE]\n>\n> 0. item\n"),
        MarkdownAlertFixture("ordered-one-control", "> [!NOTE]\n> 1. item\n"),
        MarkdownAlertFixture("empty-ordered-one", "> [!NOTE]\n>\n> 1.\n>\n> after\n", selectMarker = true),
        MarkdownAlertFixture("empty-ordered-one-only", "> [!NOTE]\n>\n> 1.\n>\n> after\n", last = 0, selectMarker = true),
        MarkdownAlertFixture("empty-unordered", "> [!NOTE]\n>\n> -\n>\n> after\n", selectMarker = true),
        MarkdownAlertFixture("empty-unordered-only", "> [!NOTE]\n>\n> -\n>\n> after\n", last = 0, selectMarker = true),
        MarkdownAlertFixture("empty-list-owner", "- parent\n\n  > [!NOTE]\n  >\n  > 1.\n\n- after\n"),
        MarkdownAlertFixture("ordered-two", orderedAlert),
        MarkdownAlertFixture("ordered-two-first-only", orderedAlert, last = 0),
        MarkdownAlertFixture("ordered-clipped-second", "> [!NOTE]\n> 1. PRIVATE first\n> 2. second\n", 1, 1),
        MarkdownAlertFixture("ordered-html", "> [!NOTE]\n>\n> <ol start=\"2\"><li>item</li><li>next</li></ol>\n"),
        MarkdownAlertFixture("ordered-list-owner", "- parent\n\n  > [!NOTE]\n  >\n  > 2. item\n\n- next\n"),
        MarkdownAlertFixture("ordered-inner-owner", "> [!NOTE]\n> first\n>\n> > [!TIP]\n> >\n> > 2. inner\n>\n> after\n"),
        MarkdownAlertFixture("ordered-note-owner", "Claim[^n]\n\n[^n]:\n" + orderedAlert.trimEnd('\n').lineSequence()
            .joinToString("\n", postfix = "\n") { "    $it" }, claimOnly = true),
        MarkdownAlertFixture("structural-html", "> [!NOTE]\n> <blockquote><p>nested</p></blockquote>\n>\n> after\n"),
        MarkdownAlertFixture("transport-fragments", "> [!NOTE]\n> > ${"a".repeat(5_000)}\n>\n> after\n"),
        MarkdownAlertFixture("list-owner", listAlert),
        MarkdownAlertFixture("note-owner", "Claim[^n]\n\n[^n]:\n" + listAlert.trimEnd('\n').lineSequence()
            .joinToString("\n", postfix = "\n") { "    $it" }, claimOnly = true)
    )
}

private data class MarkdownListFixture(val name: String, val source: String, val note: Boolean)

/** Source indentation uses the authored marker widths; the parser supplies the structural oracle. */
private fun markdownListFixtures(): List<MarkdownListFixture> = buildList {
    fun indent(text: String, width: Int) = text.trimEnd('\n').lineSequence().joinToString("\n") { " ".repeat(width) + it }
    fun addScopes(name: String, source: String, control: Boolean = false) {
        add(MarkdownListFixture(name, source, false))
        if (!control) {
            add(MarkdownListFixture("$name-quote", source.trimEnd('\n').lineSequence().joinToString("\n", postfix = "\n") { "> $it" }, false))
            add(MarkdownListFixture("$name-note", "Claim[^n]\n\n[^n]:\n" + indent(source, 4) + "\n", true))
        }
    }
    val children = listOf("```text\ncode child\n```", "> quote child", "## heading child",
        "<table><tr><td>A</td><td>B</td></tr><tr><td>C</td><td>D</td></tr></table>")
    for (number in listOf(10, 100, 123456, 123456789)) {
        val markerWidth = number.toString().length + 2
        children.forEachIndexed { index, child ->
            addScopes("list-$number-$index", "- outer\n\n  $number. parent\n\n" + indent(child, 2 + markerWidth) +
                "\n\n  ${number + 1}. next\n\n- sibling\n", control = number == 10)
        }
        addScopes("marker-$number", "$number. parent\n\n${" ".repeat(markerWidth)}- child\n${number + 1}. next\n", control = number == 10)
    }
    addScopes("deep-mixed", "123456789. outer\n\n" + indent("123456. middle\n\n" +
        indent("100. parent\n\n" + indent("- leaf\n\n  ```text\n  code child\n  ```\n- leaf sibling", 5) +
            "\n\n101. next", 8) + "\n\n123457. middle sibling", 11) + "\n\n123456790. sibling\n")
    addScopes("continued-item", "100. parent\n\n     ```text\n     code child\n     ```\n\n     back\n101. next\n")
    addScopes("derived-wide-group", "999999999. first\n999999999. second\n")
    addScopes("derived-wide-child", "999999999. first\n999999999. second\n\n           - child\n")
    addScopes("derived-wide-code", "999999999. first\n999999999. second\n\n           ```text\n           code child\n           ```\n1. next\n")
    add(MarkdownListFixture("html-detached-single", "<div><ul><li>parent</li></ul><pre><code>detached\n</code></pre></div>", false))
    add(MarkdownListFixture("html-detached-duplicate", "<div><ul><li>one</li><li>other</li></ul><pre><code>detached\n</code></pre></div>", false))
    add(MarkdownListFixture("html-owned-code", "<ul><li>parent<ul><li><pre><code>owned</code></pre></li></ul></li></ul>", false))
    add(MarkdownListFixture("html-owned-code-lf", "<ul><li>parent<ul><li><pre><code>owned\n</code></pre></li></ul></li></ul>", false))
}
