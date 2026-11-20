package dev.soupslurpr.beautyxt.ui.editor

import androidx.compose.material3.Typography
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextFieldTextStyles
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import android.content.res.Configuration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.StringReader
import java.io.StringWriter
import java.util.Locale
import kotlin.system.measureNanoTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextAppearanceTest {
    @Test
    fun adjacentStepsUseTheSameGridAsTheSlider() {
        for ((option, value, neighbours) in listOf(
            Triple(AppearanceOption.LineSpacing, 28f / 18f, 1.55f to 1.6f),
            Triple(AppearanceOption.ParagraphSpacing, 12f / 18f, .6f to .7f),
            Triple(AppearanceOption.Weight, 450f, 400f to 500f)
        )) {
            assertEquals(neighbours.first, option.nextStep(value, -1))
            assertEquals(neighbours.second, option.nextStep(value, 1))
            assertEquals(neighbours.second, option.nextStep(neighbours.first, 1))
        }
        for (option in AppearanceOption.entries) {
            var value = option.minimum
            assertEquals(value, option.nextStep(value, -1))
            repeat(option.intervals) {
                val next = option.nextStep(value, 1)
                assertTrue(next > value)
                assertEquals(next, option.stepped(next))
                assertEquals(value, option.nextStep(next, -1))
                value = next
            }
            assertEquals(option.maximum, value)
            assertEquals(value, option.nextStep(value, 1))
        }
    }

    @Test
    fun textColumnCentersTextOutsideTheGutterAndAdaptsMargins() {
        val appearance = TextAppearance().change(AppearanceOption.ColumnWidth, 300f)
        for (group in AppearanceGroup.entries) {
            assertEquals(DocumentTextColumn(250.dp, 300.dp, 0.dp), appearance.textColumn(group, 800.dp))
            assertEquals(DocumentTextColumn(250.dp, 300.dp, 40.dp), appearance.textColumn(group, 800.dp, 40.dp))
            assertEquals(DocumentTextColumn(64.dp, 272.dp, 40.dp), appearance.textColumn(group, 360.dp, 40.dp))
            assertEquals(DocumentTextColumn(12.dp, 96.dp, 0.dp), appearance.textColumn(group, 120.dp, 40.dp))
            assertEquals(DocumentTextColumn(0.dp, 80.dp, 0.dp), appearance.textColumn(group, 80.dp))
            assertEquals(DocumentTextColumn(64.dp, 712.dp, 40.dp), appearance.change(AppearanceOption.FullWidth, 1f)
                .textColumn(group, 800.dp, 40.dp))
        }
    }

    @Test
    fun effectiveAppearanceIgnoresExplicitDefaultsAndInactiveOptions() {
        val source = AppearanceGroup.Source
        val defaults = TextAppearance()
        val explicit = defaults.change(AppearanceOption.Size, AppearanceOption.Size.default(source))
        assertEquals(defaults, explicit.effective(source))
        val full = defaults.change(AppearanceOption.FullWidth, 1f)
        assertEquals(full, full.change(AppearanceOption.ColumnWidth, 320f).effective(source))
        assertEquals(defaults, defaults.change(AppearanceOption.HeadingScale, 150f).effective(source))
    }






    @Test
    fun emphasisShapingUsesAnAccessOrderedBoundedCacheAndNormalizedAdjustment() {
        val calls = mutableListOf<Triple<Int, Int, Int>>()
        val cache = MarkdownEmphasisCache { family, weight, adjustment ->
            calls += Triple(family, weight, adjustment)
            MarkdownEmphasis(weight)
        }
        repeat(64) { cache.resolve(1, it + 100, 0) }
        cache.resolve(1, 100, Configuration.FONT_WEIGHT_ADJUSTMENT_UNDEFINED)
        cache.resolve(2, 100, 0)
        cache.resolve(1, 100, 0)
        assertEquals(65, calls.size)
        cache.resolve(1, 101, 0)
        cache.resolve(1, 100, 300)
        assertEquals(listOf(Triple(1, 101, 0), Triple(1, 100, 300)), calls.takeLast(2))
        assertEquals(67, calls.size)
    }

    @Test
    fun clipboardTracksNewSourceSelectionsAndRetainsTabsAfterCutOrCopyCollapse() {
        val selection = SourceClipboardSelection()
        val source = "\tword word"
        selection.capture(source, TextRange(0, 5))
        selection.capture(source, TextRange(5))
        assertEquals("\tword", selection.original(" word"))
        assertEquals("\tword", selection.original(" word"))
        selection.capture(source, TextRange(5, 10))
        assertEquals(" word", selection.original(" word"))
        assertEquals("different text", selection.original("different text"))
    }




    @Test
    fun persistenceDistinguishesExplicitDefaultValueFromFollowingDefaults() {
        val chosen = DocumentAppearances().change(AppearanceGroup.Markdown,
            TextAppearance().change(AppearanceOption.Size, 18f).change(AppearanceOption.Font, 1f))
        val saved = StringWriter().also { writeDocumentAppearances(chosen, it) }.toString()
        val restored = readDocumentAppearances(StringReader(saved))
        assertEquals(chosen, restored)
        assertEquals(18f, restored.markdown.custom[AppearanceOption.Size])
        assertTrue(restored.source.custom.isEmpty())
        val reset = restored.change(AppearanceGroup.Markdown, restored.markdown.change(AppearanceOption.Size, null))
        val resetSaved = StringWriter().also { writeDocumentAppearances(reset, it) }.toString()
        assertFalse(resetSaved.contains("Markdown.size"))
        assertEquals(1f, readDocumentAppearances(StringReader(resetSaved)).markdown.custom[AppearanceOption.Font])
    }

    @Test
    fun invalidPersistedValuesFallBackIndependently() {
        val loaded = readDocumentAppearances(StringReader("""
            Source.size=Infinity
            Source.font=0.5
            Source.margin=-20
            Markdown.size=23.5
            Markdown.weight=NaN
            Markdown.code_scale=999999
            Markdown.unknown=20
        """.trimIndent()))
        assertTrue(loaded.source.custom.isEmpty())
        assertEquals(mapOf(AppearanceOption.Size to 23.5f), loaded.markdown.custom)
    }

    @Test
    fun tabPresentationRetainsOffsetsUnicodeAndFindHighlights() {
        val source = "😀\tCafe\u0301\n\t日本語"
        val highlight = SpanStyle(background = Color.Yellow)
        val original = AnnotatedString(source, listOf(AnnotatedString.Range(highlight, 2, 8)))
        val result = sourceDisplayText(original, TextAppearance().change(AppearanceOption.TabWidth, 8f))
        assertEquals(source.length, result.length)
        assertEquals(source.replace('\t', ' '), result.text)
        assertEquals(source, original.text)
        assertEquals(listOf(2, 9), result.spanStyles.filter { it.item.textGeometricTransform != null }.map { it.start })
        (2 until 8).forEach { offset ->
            assertTrue(result.spanStyles.any { it.item.background == Color.Yellow && offset in it.start until it.end })
        }
    }

    @Test
    fun editableTabTransformationBoundsChangesAndMapsEveryCursorOffsetBothWays() {
        // The field's output and bidirectional cursor mapping are internal to Compose.
        val type = Class.forName("androidx.compose.foundation.text.input.internal.TransformedTextFieldState")
        val constructor = type.constructors.single { it.parameterCount == 4 }
        val outputText = type.getMethod("getOutputText")
        fun offsetMapping(name: String) = type.methods.single {
            it.name.startsWith("$name-") && it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
        }
        val toDisplay = offsetMapping("mapToTransformed")
        val toSource = offsetMapping("mapFromTransformed")
        val appearance = TextAppearance().change(AppearanceOption.TabWidth, 7f).change(AppearanceOption.WordSpacing, 25f)
        val tabStyle = tabDisplayStyle(7f)
        val spaceStyle = appearanceSpaceStyle(appearance, AppearanceGroup.Source)
        val styles = FindHighlightStyles(SpanStyle(background = Color.Yellow), SpanStyle(background = Color.Cyan))
        for (tabCount in listOf(0, 32, 33, 16_384)) {
            val source = when (tabCount) {
                0 -> "plain"
                32, 33 -> "\tX".repeat(8) + "\t 😀\te\u0301" + "\tX".repeat(tabCount - 10)
                else -> "\tX".repeat(tabCount)
            }
            val display = source.replace('\t', ' ')
            val selection = TextRange(source.length - 2, 2)
            val field = TextFieldState(source, selection)
            val clipboard = SourceClipboardSelection()
            val others = if (tabCount == 0) emptyList() else listOf(TextRange(0, 1))
            val current = source.indexOf('\t', source.length / 2).takeIf { it >= 0 }?.let { TextRange(it, it + 1) }
            val transform = sourceAppearanceTransformation(appearance, clipboard, source, others, current, styles)
            var changes = -1
            val timings = mutableListOf<Long>()
            val observed = OutputTransformation {
                timings += measureNanoTime { with(transform) { transformOutput() } }
                changes = this.changes.changeCount
            }
            val transformed = constructor.newInstance(field, null, null, observed)
            val output = outputText.invoke(transformed) as CharSequence
            assertEquals(display, output.toString())
            assertEquals(if (tabCount <= 32) tabCount else 1, changes)
            if (tabCount >= 16_384) {
                // Fresh output buffers exercise the full transformation. Allow for JVM warmup and GC.
                repeat(2) { outputText.invoke(constructor.newInstance(field, null, null, observed)) }
                val best = timings.min()
                println("Editable output for $tabCount tabs in ${source.length} UTF-16 units, best of 3: ${best / 1e6} ms")
                assertTrue("tab transformation took ${best / 1e6} ms", best < 200_000_000L)
            }
            for (offset in 0..source.length) {
                // Compose returns a packed TextRange. Both ends must be this exact offset.
                val caret = (offset.toLong() shl 32) or offset.toLong()
                assertEquals(caret, toDisplay.invoke(transformed, offset))
                assertEquals(caret, toSource.invoke(transformed, offset))
            }
            val expected = buildList {
                source.forEachIndexed { index, c ->
                    if (c == '\t' || c == ' ') add(AnnotatedString.Range(if (c == '\t') tabStyle else spaceStyle, index, index + 1))
                }
                others.forEach { add(AnnotatedString.Range(styles.other, it.start, it.end)) }
                current?.let { add(AnnotatedString.Range(styles.current, it.start, it.end)) }
            }
            val textStyles = output.javaClass.getMethod("getTextFieldTextStyles").invoke(output) as TextFieldTextStyles?
            val spans = textStyles?.getSpanStyles(TextRange(0, output.length)).orEmpty()
            assertEquals(expected.groupingBy { it }.eachCount(), spans.groupingBy { it }.eachCount())
            assertEquals(source, field.text.toString())
            assertEquals(selection, field.selection)
            assertEquals(source.substring(selection.min, selection.max), clipboard.original(display.substring(selection.min, selection.max)))
        }
    }

    @Test
    fun tabPresentationPreservesAuthoredGraphemeBoundariesForCombiningAndPrependNeighbors() {
        val type = Class.forName("androidx.compose.foundation.text.input.internal.TransformedTextFieldState")
        val constructor = type.constructors.single { it.parameterCount == 4 }
        val outputText = type.getMethod("getOutputText")
        val appearance = TextAppearance().change(AppearanceOption.TabWidth, 6f).change(AppearanceOption.WordSpacing, 25f)
        val styles = FindHighlightStyles(SpanStyle(background = Color.Yellow), SpanStyle(background = Color.Cyan))
        val cases = listOf(
            "A\t\u0301 B" to 1,
            "A\t\u200DB" to 1,
            "A\t\uD83C\uDFFB B" to 1,
            "\u0600\tB" to 1,
            ("\tx".repeat(35) + "A\t\u0301 B") to 71
        )
        for ((source, protectedTab) in cases) {
            val readOnly = sourceDisplayText(AnnotatedString(source), appearance)
            assertEquals('\t', readOnly.text[protectedTab])
            assertTrue(sourcePresentationMatches(readOnly.text, source))
            assertTrue(readOnly.spanStyles.none { it.item.textGeometricTransform != null && it.start == protectedTab })
            val readOnlyBreaks = java.text.BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(readOnly.text) }
            assertEquals(protectedTab + 1, readOnlyBreaks.following(protectedTab))

            val clipboard = SourceClipboardSelection()
            val field = TextFieldState(source, TextRange(protectedTab, protectedTab + 2))
            val transform = sourceAppearanceTransformation(appearance, clipboard, source, emptyList(), null, styles)
            val transformed = constructor.newInstance(field, null, null, transform)
            val output = (outputText.invoke(transformed) as CharSequence).toString()
            assertEquals('\t', output[protectedTab])
            assertTrue(sourcePresentationMatches(output, source))
            val editableBreaks = java.text.BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(output) }
            assertEquals(protectedTab + 1, editableBreaks.following(protectedTab))
            assertEquals(source.substring(protectedTab, protectedTab + 2), clipboard.original(output.substring(protectedTab, protectedTab + 2)))
        }
    }

    @Test
    fun readOnlySourceDisplayTextRemainsLinearOnTabAndSpaceHeavyRows() {
        val appearance = TextAppearance()
            .change(AppearanceOption.TabWidth, 4f)
            .change(AppearanceOption.WordSpacing, 25f)
        val rowText = "v\t ".repeat(1_365) // 4,095 UTF-16 units
        val highlight = SpanStyle(background = Color.Yellow)
        val rows = List(8) { AnnotatedString(rowText, listOf(AnnotatedString.Range(highlight, 0, 9))) }
        // Warm up JIT once, then measure 8 full rows.
        sourceDisplayText(rows.first(), appearance)
        lateinit var results: List<AnnotatedString>
        val elapsedNanos = measureNanoTime {
            results = rows.map { sourceDisplayText(it, appearance) }
        }
        println("Read-only sourceDisplayText for 8x4095 tabbed/spaced rows: ${elapsedNanos / 1e6} ms")
        assertTrue("sourceDisplayText took ${elapsedNanos / 1e6} ms", elapsedNanos < 50_000_000L)
        val first = results.first()
        assertEquals(rowText.replace('\t', ' '), first.text)
        assertTrue(first.spanStyles.any { it.item.background == Color.Yellow && it.start == 0 && it.end == 9 })
        assertEquals(1_365, first.spanStyles.count { it.item.textGeometricTransform != null })
        assertEquals(1_365, first.spanStyles.count { it.item.letterSpacing.isSp || it.item.letterSpacing.isEm })
    }

    @Test
    fun wordSpacingOnlyStylesExistingSpaces() {
        val original = AnnotatedString("A 😀 word\tword\nالعربية 日本語")
        val result = spacedDocumentText(original, TextAppearance().change(AppearanceOption.WordSpacing, 25f), AppearanceGroup.Markdown)
        assertEquals(original.text, result.text)
        assertEquals(listOf(1, 4, 22), result.spanStyles.map { it.start })
        assertTrue(result.spanStyles.all { it.end == it.start + 1 && original.text[it.start] == ' ' })
    }

    @Test
    fun sourceAndMarkdownSizesAreIndependentAndCodeFollowsBodyProportions() {
        val preferences = DocumentAppearances(source = TextAppearance().change(AppearanceOption.Size, 24f),
            markdown = TextAppearance().change(AppearanceOption.Size, 36f).change(AppearanceOption.CodeScale, 150f))
        val base = TextStyle(fontSize = 16.sp, lineHeight = 24.sp)
        assertEquals(24.sp, preferences.source.sourceStyle(base).fontSize)
        assertEquals(FontFamily.Monospace, preferences.source.sourceStyle(base).fontFamily)
        val code = preferences.markdown.markdownStyle(TextStyle(fontSize = 14.sp, lineHeight = 20.sp), MarkdownTextRole.Code)
        assertEquals(42.sp, code.fontSize)
        assertEquals(FontFamily.Monospace, code.fontFamily)
    }

    @Test
    fun headingSizesKeepTheirRelativeHierarchyAtEveryScale() {
        val typography = Typography()
        listOf(8f, 18f, 72f).forEach { bodySize ->
            listOf(75f, 100f, 200f).forEach { scale ->
                val appearance = TextAppearance().change(AppearanceOption.HeadingScale, scale)
                    .change(AppearanceOption.Size, bodySize)
                    .change(AppearanceOption.Weight, 700f)
                val styles = (1..6).map { level ->
                    appearance.markdownStyle(typography.markdownHeadingStyle(level), MarkdownTextRole.Heading)
                }
                assertTrue(styles.zipWithNext().all { (a, b) -> a.fontSize.value > b.fontSize.value })
                assertTrue(styles.all { it.fontWeight!!.weight > 700 })
                assertEquals(typography.headlineLarge.fontSize.value * bodySize / 18f * scale / 100f,
                    styles.first().fontSize.value, .001f)
            }
        }
    }

}
