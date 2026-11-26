package dev.soupslurpr.beautyxt.ui.editor

import dev.soupslurpr.beautyxt.document.Utf16Range
import dev.soupslurpr.beautyxt.markdown.*
import dev.soupslurpr.beautyxt.testing.ImmediateSessionTestDispatcher
import dev.soupslurpr.beautyxt.testing.TestEditorDocument
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DocumentSelectionTest {
    private fun block(text: String, kind: MarkdownBlockKind = MarkdownBlockKind.Paragraph) = MarkdownRenderBlock(kind,
        false, false, false, false, false, false, 0, 0, 0, 0, text, "", emptyList(),
        tableCellSeparators = if (kind == MarkdownBlockKind.TableRow) text.indices.filter { text[it] == '\t' } else emptyList(),
        source = MarkdownSourceRange(0, text.length.toLong()),
        sourceMaps = listOf(MarkdownSourceMap(0, text.length, MarkdownSourceRange(0, text.length.toLong()))))
    private fun model(vararg blocks: MarkdownRenderBlock) = MarkdownPreviewDocument(500, blocks.toList(), 0, false)

    @Test fun refusesCoarseHtmlAndIncompleteOrOverlappingProvenance() {
        val html = model(block("Public").copy(source = MarkdownSourceRange(0, 46), sourceMaps = emptyList()),
            block("Private sibling").copy(source = MarkdownSourceRange(0, 46), sourceMaps = emptyList()))
        val public = DocumentSelection.Reading(0, ReadingPoint(0, 0), ReadingPoint(0, 6))
        assertNull(public.exactSource(html))
        val exact = model(block("Public").copy(source = MarkdownSourceRange(0, 23), sourceMaps = listOf(
            MarkdownSourceMap(0, 6, MarkdownSourceRange(8, 14)))))
        assertEquals(Utf16Range(8, 14), public.exactSource(exact))
        assertEquals(Utf16Range(9, 12), public.copy(anchor = ReadingPoint(0, 1), focus = ReadingPoint(0, 4)).exactSource(exact))
        val partial = block("A\tB", MarkdownBlockKind.TableRow).copy(sourceMaps = listOf(
            MarkdownSourceMap(0, 1, MarkdownSourceRange(0, 1)), MarkdownSourceMap(2, 3, MarkdownSourceRange(2, 3))))
        assertNull(exactReadingSourceRange(partial, 0, 3))
        assertEquals(Utf16Range(2, 3), exactReadingSourceRange(partial, 2, 3))
        val overlapping = block("AB").copy(sourceMaps = listOf(
            MarkdownSourceMap(0, 1, MarkdownSourceRange(0, 2)), MarkdownSourceMap(1, 2, MarkdownSourceRange(1, 2))))
        assertNull(exactReadingSourceRange(overlapping, 0, 2))
        assertNull(exactReadingSourceRange(overlapping, 0, 1))
        val sibling = model(block("Public"), block("Other").copy(sourceMaps = listOf(
            MarkdownSourceMap(0, 5, MarkdownSourceRange(0, 5)))))
        assertNull(public.exactSource(sibling))
        val joined = model(block("Public"), block("lic").copy(illustrationContinuation = true,
            source = MarkdownSourceRange(3, 6), sourceMaps = listOf(
                MarkdownSourceMap(0, 3, MarkdownSourceRange(3, 6)))))
        assertEquals(Utf16Range(0, 6), public.exactSource(joined))
    }

    @Test fun readingFindKeepsEveryCellSeparateWhenAnotherCellContainsMath() {
        val row = block("x\tfoo\tbar", MarkdownBlockKind.TableRow).copy(spans = listOf(
            MarkdownInlineSpan(0, 1, MARKDOWN_SPAN_STYLE_MATH, null)))
        val document = model(row)
        val units = readingSearchUnits(document, false).units
        assertEquals(listOf("x", "foo", "bar"), units.map { it.text })
        assertFalse(units.any { "foo\tbar" in it.text })
        val foo = units.single { it.text == "foo" }
        val hit = dev.soupslurpr.beautyxt.document.SearchHit(Utf16Range(0, 3), "foo", null, "", "")
        assertEquals(Utf16Range(2, 5), readingSearchResult(document, foo, hit).source)
        val literal = row.copy(text = "a\tb\tc", spans = emptyList(), tableCellSeparators = listOf(3))
        assertEquals(listOf("a\tb", "c"), readingSearchUnits(model(literal), false).units.map { it.text })
        val mathLast = row.copy(text = "foo\tbar\tx", tableCellSeparators = listOf(3, 7), spans = listOf(
            MarkdownInlineSpan(8, 9, MARKDOWN_SPAN_STYLE_MATH, null)))
        assertEquals(listOf("foo", "bar", "x"), readingSearchUnits(model(mathLast), false).units.map { it.text })
    }

    @Test fun individualFootnoteDigitsCopySearchAndExportWithoutImportingTheUnselectedNote() {
        fun reference(text: String, start: Int, end: Int, label: String) = block(text).copy(spans = listOf(
            MarkdownInlineSpan(start, end, MARKDOWN_SPAN_STYLE_FOOTNOTE_REFERENCE, label, MarkdownInlineDestinationKind.FootnoteReference)))
        val earlier = (1..9).map { reference("note$it", 0, 5, "note$it") }
        val marker = reference("See tenth here", 4, 9, "tenth")
        val document = model(*(earlier + marker + block("Private note", MarkdownBlockKind.Footnote).copy(metadata = "tenth")).toTypedArray())
        val presentation = markdownFootnotePresentation(marker.text, marker.spans, markdownFootnoteNumbers(document.blocks))!!
        assertEquals("See 10 here", presentation.text)
        val middle = readingPresentationPoint(9, 0, 5, marker.text, marker.spans, presentation)
        assertEquals(ReadingPoint(9, 4, 1), middle)
        assertEquals(5, readingPresentationOffset(middle, 0, marker.text, marker.spans, presentation))
        assertEquals(middle, atomicReadingPoint(document, middle, false))
        assertEquals(ReadingPoint(9, 9), atomicReadingPoint(document, ReadingPoint(9, 4, 2), true))
        val first = DocumentSelection.Reading(0, ReadingPoint(9, 4), middle)
        val last = first.copy(anchor = middle, focus = ReadingPoint(9, 9))
        assertEquals("1", selectedReadingText(document, first, 20))
        assertEquals("0", selectedReadingText(document, last, 20))
        assertNull(first.exactSource(document))
        assertNull(last.exactSource(document))
        for ((selection, digit) in listOf(first to "1", last to "0")) {
            val excerpt = selectedMarkdown(document, selection)
            assertEquals("<sup>$digit</sup>", excerpt.markdown)
            assertTrue(excerpt.notices.isEmpty())
            assertEquals(digit, excerpt.document.blocks.single().text)
        }
        val whole = first.copy(focus = ReadingPoint(9, 9))
        assertEquals("[^1]\n\n[^1]: Private note", selectedMarkdown(document, whole).markdown)
        val unit = readingSearchUnits(document, false).units.first()
        val position = unit.text.indexOf("10").toLong()
        assertEquals(Utf16Range(position + 1, position + 2), readingUnitScope(unit, last))
        val hit = dev.soupslurpr.beautyxt.document.SearchHit(Utf16Range(position + 1, position + 2), "0", null, "", "")
        val result = readingSearchResult(document, unit, hit)
        assertNull(result.source)
        assertEquals(4, result.segments.single().blockStart)
        assertEquals(1, result.segments.single().markerStart)
        assertEquals(9, result.segments.single().blockEnd)
        val insertion = readingSearchResult(document, unit, hit.copy(range = Utf16Range(position + 1, position + 1), text = ""))
        assertNull(insertion.source)
        assertEquals(1, insertion.segments.single().markerEnd)
    }

    @Test fun displayedListMarkersAreSelectableButAnItemSubstringDoesNotAcquireOne() {
        val document = model(block("first", MarkdownBlockKind.ListItem).copy(isOrderedListItem = true, listNumber = 3, startsList = true),
            block("second", MarkdownBlockKind.ListItem).copy(isOrderedListItem = true, listNumber = 4),
            block("separate", MarkdownBlockKind.ListItem).copy(startsList = true))
        assertEquals("3. first\n4. second\n\n• separate", selectedReadingText(document,
            DocumentSelection.Reading(0, ReadingPoint(0, -3), ReadingPoint(2, 8)), 100))
        assertEquals("irs", selectedReadingText(document,
            DocumentSelection.Reading(0, ReadingPoint(0, 1), ReadingPoint(0, 4)), 100))
        assertEquals("3.", selectedReadingText(document,
            DocumentSelection.Reading(0, ReadingPoint(0, -3), ReadingPoint(0, -1)), 100))
    }

    @Test fun selectingOnlyTheParagraphBoundaryCopiesItsLogicalSeparator() {
        val document = model(block("first"), block("second"))
        assertEquals("\n\n", selectedReadingText(document,
            DocumentSelection.Reading(0, ReadingPoint(0, 5), ReadingPoint(1, 0)), 100))
    }

    @Test fun exactSourceRejectsSelectionSpanningHiddenBlocks() {
        val document = model(
            block("before").copy(
                source = MarkdownSourceRange(0, 6),
                sourceMaps = listOf(MarkdownSourceMap(0, 6, MarkdownSourceRange(0, 6)))
            ),
            block("hidden body").copy(
                source = MarkdownSourceRange(8, 19),
                sourceMaps = listOf(MarkdownSourceMap(0, 11, MarkdownSourceRange(8, 19)))
            ),
            block("after").copy(
                source = MarkdownSourceRange(21, 26),
                sourceMaps = listOf(MarkdownSourceMap(0, 5, MarkdownSourceRange(21, 26)))
            )
        )
        val span = DocumentSelection.Reading(0, ReadingPoint(0, 0), ReadingPoint(2, 5))
        assertEquals(Utf16Range(0, 26), span.exactSource(document))
        assertNull(span.exactSource(document, isBlockVisible = { index, _ -> index != 1 }))
    }

    @Test fun readingFindScopeSkipsHiddenBlocksAcrossCollapsedDetails() {
        val document = model(
            block("before").copy(detailsId = 0),
            block("Summary", MarkdownBlockKind.DetailsSummary).copy(detailsId = 1),
            block("hidden secret").copy(detailsId = 1),
            block("after").copy(detailsId = 0)
        )
        val units = readingSearchUnits(document, false).units
        assertEquals(listOf("before", "Summary", "hidden secret", "after"), units.map { it.text })
        val span = DocumentSelection.Reading(0, ReadingPoint(0, 0), ReadingPoint(3, 5))
        val visibleOnly: (Int) -> Boolean = { blockIndex -> blockIndex != 2 }
        val scopedTexts = units.mapNotNull { unit ->
            readingUnitScope(unit, span, visibleOnly)?.let { scope ->
                unit.text.substring(scope.start.toInt(), scope.end.toInt())
            }
        }
        assertEquals(listOf("before", "Summary", "after"), scopedTexts)
    }

    @Test fun readingFindIncludesVisibleFrontmatterKeysAndSkipsWhenCollapsed() {
        val document = model(
            block("team", MarkdownBlockKind.Frontmatter).copy(metadata = "releaseOwner"),
            block("body")
        )
        val units = readingSearchUnits(document, false).units
        assertEquals(listOf("releaseOwner", "team", "body"), units.map { it.text })
        // Searching for "Owner" inside the frontmatter key must produce a valid non-inverted
        // key-scoped segment `[7, 12)` without crashing MarkdownPresentationText highlights.
        val keyResult = readingSearchResult(
            document = document,
            unit = units.first(),
            hit = dev.soupslurpr.beautyxt.document.SearchHit(Utf16Range(7, 12), "Owner", null, "release", "")
        )
        val keySeg = keyResult.segments.single()
        assertTrue(keySeg.isFrontmatterKey)
        assertEquals(7, keySeg.blockStart)
        assertEquals(12, keySeg.blockEnd)
        assertEquals(0, keySeg.markerStart)
        assertEquals(0, keySeg.markerEnd)
        assertFalse(keySeg.exact)
        assertNull(keyResult.source)
        val span = DocumentSelection.Reading(0, ReadingPoint(0, 0), ReadingPoint(1, 4))
        val collapsedScope = units.mapNotNull { unit ->
            readingUnitScope(unit, span) { blockIndex -> blockIndex != 0 }?.let { scope ->
                unit.text.substring(scope.start.toInt(), scope.end.toInt())
            }
        }
        assertEquals(listOf("body"), collapsedScope)
    }

    @Test fun readingFindPreservesListGroupsParagraphsWithinItemsAndSplitTableCells() {
        val list = model(block("first", MarkdownBlockKind.ListItem).copy(startsList = true, listDepth = 1),
            block("continued", MarkdownBlockKind.ListItem).copy(continuesListItem = true, listDepth = 1),
            block("child", MarkdownBlockKind.ListItem).copy(startsList = true, listDepth = 2),
            block("second", MarkdownBlockKind.ListItem).copy(listDepth = 1),
            block("separate", MarkdownBlockKind.ListItem).copy(startsList = true, listDepth = 1))
        assertEquals(listOf("• first\n\ncontinued\n• child\n• second", "• separate"), readingSearchUnits(list, false).units.map { it.text })
        val table = model(block("A\tlong", MarkdownBlockKind.TableRow),
            block(" cell\tC", MarkdownBlockKind.TableRow).copy(continuesPrevious = true), block("next", MarkdownBlockKind.TableRow))
        assertEquals(listOf("A", "long cell", "C", "next"), readingSearchUnits(table, false).units.map { it.text })
    }

    @Test fun readingSelectionScopeBoundsConsumptionAndRetainsOriginalFlowContext() {
        val document = model(block("before aaa after"), block("second"))
        val unit = readingSearchUnits(document, false).units.single()
        val selection = DocumentSelection.Reading(0, ReadingPoint(0, 8), ReadingPoint(0, 10))
        assertEquals("before aaa after\n\nsecond", unit.text)
        assertEquals(Utf16Range(8, 10), readingUnitScope(unit, selection))
        val across = selection.copy(focus = ReadingPoint(1, 3))
        assertEquals(Utf16Range(8, 21), readingUnitScope(unit, across))
    }

    @Test fun readingSelectionCopiesAcrossChunksAndParagraphsWithoutFormattingSyntax() {
        val document = model(block("First"), block(" line").copy(continuesPrevious = true), block("Second"))
        val selected = DocumentSelection.Reading(0, ReadingPoint(0, 2), ReadingPoint(2, 3))
        assertEquals("rst line\n\nSec", selectedReadingText(document, selected, 100))
        assertEquals(selectedReadingText(document, selected, 100), selectedReadingText(document,
            selected.copy(anchor = selected.focus, focus = selected.anchor), 100))
    }

    @Test fun paragraphExtensionCrossesLongParagraphsAsWholeUnits() {
        val document = model(block("First"), block(" paragraph").copy(continuesPrevious = true),
            block("Second"), block(" paragraph").copy(continuesPrevious = true), block("Third"))
        val next = readingAdjacentParagraphBoundary(document.blocks, ReadingPoint(0, 2), true)
        assertEquals("rst paragraph\n\nSecond paragraph", selectedReadingText(document,
            DocumentSelection.Reading(0, ReadingPoint(0, 2), next), 100))
        assertEquals(next, readingAdjacentParagraphBoundary(document.blocks, ReadingPoint(1, 3), true))
        assertEquals(ReadingPoint(0, 0), readingAdjacentParagraphBoundary(document.blocks, ReadingPoint(3, 4), false))
        assertEquals(ReadingPoint(2, 0), readingAdjacentParagraphBoundary(document.blocks, ReadingPoint(4, 2), false))
        assertEquals(ReadingPoint(4, 5), readingAdjacentParagraphBoundary(document.blocks, ReadingPoint(4, 2), true))
    }

    @Test fun paragraphExtensionRetainsListMarkersAndSkipsHiddenIllustrationFragments() {
        val document = model(block("item", MarkdownBlockKind.ListItem).copy(isOrderedListItem = true, listNumber = 12),
            block(" tail", MarkdownBlockKind.ListItem).copy(continuesPrevious = true),
            block("second paragraph", MarkdownBlockKind.ListItem).copy(continuesListItem = true),
            block("whole diagram", MarkdownBlockKind.Code),
            block("hidden fragment", MarkdownBlockKind.Code).copy(continuesPrevious = true, illustrationContinuation = true),
            block("Last"))
        assertEquals(ReadingPoint(0, -4), readingAdjacentParagraphBoundary(document.blocks, ReadingPoint(2, 1), false))
        assertEquals(ReadingPoint(2, 16), readingAdjacentParagraphBoundary(document.blocks, ReadingPoint(1, 1), true))
        assertEquals(ReadingPoint(3, 13), readingAdjacentParagraphBoundary(document.blocks, ReadingPoint(2, 1), true))
        assertEquals(ReadingPoint(5, 4), readingAdjacentParagraphBoundary(document.blocks, ReadingPoint(3, 1), true))
    }

    @Test fun tablePlainTextNeverAddsUnselectedHeadersOrCells() {
        val document = model(block("Name\tCost", MarkdownBlockKind.TableRow),
            block("Apples\t2", MarkdownBlockKind.TableRow), block("Pears\t3", MarkdownBlockKind.TableRow))
        val selected = DocumentSelection.Reading(0, ReadingPoint(1, 2), ReadingPoint(2, 5))
        assertEquals("ples\t2\nPears", selectedReadingText(document, selected, 100))
    }

    @Test fun wholeFormulasUseOriginalSourceAndNoncollidingFences() {
        val formula = block("Math: \\alpha+\\beta.").copy(spans = listOf(MarkdownInlineSpan(6, 18, MARKDOWN_SPAN_STYLE_MATH, null)))
        val document = model(formula)
        val start = atomicReadingPoint(document, ReadingPoint(0, 9), false)
        val end = atomicReadingPoint(document, ReadingPoint(0, 10), true)
        assertEquals(ReadingPoint(0, 6), start)
        assertEquals(ReadingPoint(0, 18), end)
        assertEquals("```tex\n\\alpha+\\beta\n```", selectedReadingText(document, DocumentSelection.Reading(0, start, end), 100))
        assertEquals("````text\n```\n````", excerptFence("```", "text"))
    }

    @Test fun sizeLimitRefusesCompleteUnicodePayloadWithoutTruncation() {
        val document = model(block("😀é"))
        val selection = DocumentSelection.Reading(0, ReadingPoint(0, 0), ReadingPoint(0, 3))
        assertEquals("😀é", selectedReadingText(document, selection, 6))
        assertThrows(SelectionLimitException::class.java) { selectedReadingText(document, selection, 5) }
    }

    @Test fun readingSelectionSkipsHiddenCollapsedDetailsBlocks() {
        val summary = block("Details title", MarkdownBlockKind.DetailsSummary).copy(detailsId = 1)
        val hiddenBody = block("Hidden secret").copy(detailsId = 1)
        val hiddenItem = block("Hidden item", MarkdownBlockKind.ListItem).copy(detailsId = 1)
        val document = model(block("Before"), summary, hiddenBody, hiddenItem, block("After"))
        val layout = prepareMarkdownPreview(document.blocks)
        val selection = DocumentSelection.Reading(0, ReadingPoint(0, 0), ReadingPoint(4, 5))
        val visibleOnly: (Int, MarkdownRenderBlock) -> Boolean = { _, b ->
            isMarkdownBlockVisibleInDetails(layout, b, emptyMap())
        }
        val copied = selectedReadingText(document, selection, 1_000, isBlockVisible = visibleOnly)
        assertTrue(copied.contains("Before"))
        assertTrue(copied.contains("Details title"))
        assertTrue(copied.contains("After"))
        assertFalse(copied.contains("Hidden secret"))
        assertFalse(copied.contains("Hidden item"))

        val formatted = selectedMarkdown(document, selection, isBlockVisible = visibleOnly)
        assertTrue(formatted.markdown.contains("Before"))
        assertTrue(formatted.markdown.contains("After"))
        assertFalse(formatted.markdown.contains("Hidden secret"))
        assertFalse(formatted.markdown.contains("Hidden item"))
    }

    @Test fun readingSelectionExcludesHiddenFootnoteDefinitionsAndCollapsedFrontmatter() {
        val frontmatter = block("secret: token", MarkdownBlockKind.Frontmatter)
        val ref = block("See note[^1]").copy(
            spans = listOf(
                MarkdownInlineSpan(
                    start = 8,
                    end = 12,
                    styles = MARKDOWN_SPAN_STYLE_FOOTNOTE_REFERENCE,
                    destination = "1",
                    destinationKind = MarkdownInlineDestinationKind.FootnoteReference
                )
            )
        )
        val summary = block("Notes", MarkdownBlockKind.DetailsSummary).copy(detailsId = 1)
        val hiddenDef = block("Collapsed definition", MarkdownBlockKind.Footnote).copy(
            detailsId = 1,
            metadata = "1",
            footnoteLabel = "1"
        )
        val document = model(frontmatter, ref, summary, hiddenDef)
        val layout = prepareMarkdownPreview(document.blocks)
        val visibleOnly: (Int, MarkdownRenderBlock) -> Boolean = { _, b ->
            b.kind != MarkdownBlockKind.Frontmatter &&
                isMarkdownBlockVisibleInDetails(layout, b, emptyMap())
        }
        val all = DocumentSelection.Reading(0, ReadingPoint(0, 0), ReadingPoint(3, hiddenDef.text.length))
        val copied = selectedReadingText(document, all, 1_000, isBlockVisible = visibleOnly)
        assertFalse(copied.contains("secret: token"))
        assertFalse(copied.contains("Collapsed definition"))

        val refOnly = DocumentSelection.Reading(0, ReadingPoint(1, 0), ReadingPoint(1, ref.text.length))
        val formatted = selectedMarkdown(document, refOnly, isBlockVisible = visibleOnly)
        assertFalse(formatted.markdown.contains("Collapsed definition"))
        assertTrue(formatted.notices.any { it.kind == ExcerptNoticeKind.MissingNote })

        // Reading selection stats also exclude collapsed Frontmatter and collapsed `<details>` bodies.
        val (words, _) = countReadingSelectionStats(document, all, isBlockVisible = visibleOnly)
        assertEquals(3L, words) // "See note[^1]" (2) + "Notes" (1)
    }

    @Test fun readingSelectionResolvesOriginalDuplicateHeadingAnchorsBeforeVisibilityFiltering() {
        val summary = block("Fold", MarkdownBlockKind.DetailsSummary).copy(detailsId = 1)
        val hiddenHeading = block("Topic", MarkdownBlockKind.Heading).copy(headingLevel = 1, detailsId = 1)
        val visibleHeading = block("Topic", MarkdownBlockKind.Heading).copy(headingLevel = 1)
        val links = block("hidden visible").copy(
            spans = listOf(
                MarkdownInlineSpan(
                    start = 0,
                    end = 6,
                    styles = 0,
                    destination = "#topic",
                    destinationKind = MarkdownInlineDestinationKind.Link
                ),
                MarkdownInlineSpan(
                    start = 7,
                    end = 14,
                    styles = 0,
                    destination = "#topic-1",
                    destinationKind = MarkdownInlineDestinationKind.Link
                )
            )
        )
        val document = model(summary, hiddenHeading, visibleHeading, links)
        val layout = prepareMarkdownPreview(document.blocks)
        val visibleOnly: (Int, MarkdownRenderBlock) -> Boolean = { _, b ->
            isMarkdownBlockVisibleInDetails(layout, b, emptyMap())
        }
        val selection = DocumentSelection.Reading(0, ReadingPoint(2, 0), ReadingPoint(3, links.text.length))
        val formatted = selectedMarkdown(document, selection, isBlockVisible = visibleOnly)
        assertTrue(formatted.markdown.contains("[visible](<#topic>)"))
        assertFalse(formatted.markdown.contains("[hidden](<#topic>)"))
        assertTrue(formatted.notices.any { it.kind == ExcerptNoticeKind.RemovedLink && it.detail == "#topic" })
    }

    @Test fun previewKeysAreContentIndependentExcerptsEscapeSummaryAndStatsCarryWordsAcrossFragments() {
        val codeA = block("secret_alpha", MarkdownBlockKind.Code).copy(metadata = "k")
        val codeB = block("different_beta", MarkdownBlockKind.Code).copy(metadata = "z")
        assertEquals(
            prepareMarkdownPreview(listOf(codeA)).items.single().stableKey,
            prepareMarkdownPreview(listOf(codeB)).items.single().stableKey
        )

        val summary = block("[literal](example.org)", MarkdownBlockKind.DetailsSummary).copy(detailsId = 1)
        val frontmatter = block("*raw*", MarkdownBlockKind.Frontmatter).copy(metadata = "key_1")
        val doc = model(frontmatter, summary)
        val excerpt = selectedMarkdown(doc, DocumentSelection.Reading(0, ReadingPoint(0, 0), ReadingPoint(1, summary.text.length)))
        assertTrue(excerpt.markdown.contains("\\[literal\\]\\(example\\.org\\)"))
        assertTrue(excerpt.markdown.contains("key\\_1: \\*raw\\*"))

        val frag1 = block("a".repeat(3_000))
        val frag2 = block("b".repeat(3_000)).copy(continuesPrevious = true)
        val fragDoc = model(frag1, frag2)
        val (words, chars) = countReadingSelectionStats(
            fragDoc,
            DocumentSelection.Reading(0, ReadingPoint(0, 0), ReadingPoint(1, 3_000))
        )
        assertEquals(1L, words)
        assertEquals(6_000L, chars)

        // Excerpt selection clips and rebases syntax runs alongside inline spans.
        val codeFrag1 = block("let n = ", MarkdownBlockKind.Code).copy(
            metadata = "rust",
            syntaxRuns = listOf(
                dev.soupslurpr.beautyxt.markdown.MarkdownSyntaxRun(
                    start = 0,
                    end = 3,
                    role = dev.soupslurpr.beautyxt.markdown.SyntaxTokenRole.Keyword
                )
            )
        )
        val codeFrag2 = block("7;", MarkdownBlockKind.Code).copy(
            metadata = "rust",
            continuesPrevious = true,
            syntaxRuns = listOf(
                dev.soupslurpr.beautyxt.markdown.MarkdownSyntaxRun(
                    start = 0,
                    end = 1,
                    role = dev.soupslurpr.beautyxt.markdown.SyntaxTokenRole.Number
                )
            )
        )
        val codeDoc = model(codeFrag1, codeFrag2)
        val codeExcerpt = selectedMarkdown(
            codeDoc,
            DocumentSelection.Reading(0, ReadingPoint(1, 0), ReadingPoint(1, 1))
        )
        val outBlock = codeExcerpt.document.blocks.single()
        assertEquals("7", outBlock.text)
        assertEquals(1, outBlock.syntaxRuns.size)
        assertEquals(0, outBlock.syntaxRuns[0].start)
        assertEquals(1, outBlock.syntaxRuns[0].end)
        assertEquals(dev.soupslurpr.beautyxt.markdown.SyntaxTokenRole.Number, outBlock.syntaxRuns[0].role)

        // Outline tracking maps through visible Reading indices when `<details>` collapses earlier items.
        val h1 = block("First", MarkdownBlockKind.Heading).copy(headingLevel = 1)
        val foldSummary = block("Fold", MarkdownBlockKind.DetailsSummary).copy(detailsId = 1)
        val hiddenH2 = block("Hidden", MarkdownBlockKind.Heading).copy(headingLevel = 2, detailsId = 1)
        val hiddenBody = block("Body").copy(detailsId = 1)
        val visibleH2 = block("Second", MarkdownBlockKind.Heading).copy(headingLevel = 2)
        val outlineLayout = prepareMarkdownPreview(listOf(h1, foldSummary, hiddenH2, hiddenBody, visibleH2))
        val lookup = visibleOutlineLookup(outlineLayout, emptyMap())
        // Visible items are [h1 (0), foldSummary (1), visibleH2 (2)] -> only visible headings 0 and 2 appear.
        assertEquals(listOf(0 to 0, 2 to 2), lookup.visibleEntries)
        assertEquals(2, activeOutlineEntry(outlineLayout.outline, itemIndex = 2, visibleLookup = lookup))
    }

    @Test fun nativeSourceSelectionSurvivesFindFocusAndUsesExactCharacters() = runBlocking {
        val source = "one **two**\nthree"
        val session = EditorSession("Test.md", EditorDocumentState(TestEditorDocument(source), ImmediateSessionTestDispatcher),
            operationDispatcher = ImmediateSessionTestDispatcher, findDelay = {}, editSynchronizationDelay = {})
        session.openInitialEditor()
        assertTrue(session.selectSource(4, 11))
        session.showFind()
        assertTrue(session.captureSelectionFindScope())
        assertEquals(Utf16Range(4, 11), session.capturedFindScope)
        assertEquals("**two**", session.selectedPlainText(100))
        session.closeFind()
        assertNotNull(session.documentSelection)
        session.close()
        assertNull(session.documentSelection)
    }

    @Test fun cuttingAndPastingGlobalSourceSelectionAreSingleUndoableActions() {
        val document = TestEditorDocument("first\nsecond\nthird")
        val session = EditorSession("Test.txt", EditorDocumentState(document, ImmediateSessionTestDispatcher),
            operationDispatcher = ImmediateSessionTestDispatcher, findDelay = {}, editSynchronizationDelay = {})
        session.openInitialEditor()
        assertTrue(session.selectSource(2, 15))
        var copied: String? = null
        assertTrue(session.replaceSelectedSource("") { copied = it })
        assertEquals("rst\nsecond\nth", copied)
        assertEquals("fiird", document.text)
        assertTrue(session.requestUndo())
        assertEquals("first\nsecond\nthird", document.text)
        assertTrue(session.selectSource(0, 5))
        assertTrue(session.replaceSelectedSource("new\r\nline"))
        assertEquals("new\nline\nsecond\nthird", document.text)
        assertTrue(session.requestUndo())
        assertEquals("first\nsecond\nthird", document.text)
        session.close()
    }

    @Test fun reloadInheritsInputsWithoutScopeSelectionOrHistory() {
        fun session() = EditorSession("Test.txt", EditorDocumentState(TestEditorDocument("cat cat"), ImmediateSessionTestDispatcher),
            operationDispatcher = ImmediateSessionTestDispatcher, findDelay = {}, editSynchronizationDelay = {}).also { it.openInitialEditor() }
        val original = session()
        val reloaded = session()
        original.selectSource(0, 3)
        original.showFind()
        original.captureSelectionFindScope()
        original.updateFindFieldValue(androidx.compose.ui.text.input.TextFieldValue("cat"))
        original.updateFindWholeWord(true)
        reloaded.inheritFindInputs(original)
        assertEquals("cat", reloaded.findFieldValue.text)
        assertTrue(reloaded.isFindWholeWord)
        assertNull(reloaded.capturedFindScope)
        assertNull(reloaded.documentSelection)
        assertFalse(reloaded.hasPreviousLocation)
        assertEquals(2, reloaded.findResults.size)
        original.close(); reloaded.close()
    }
}
