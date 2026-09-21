package dev.soupslurpr.beautyxt.ui.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextDecoration
import org.junit.Assert.assertEquals
import org.junit.Test

class EditorFindHighlightsTest {
    /** Verifies an active overlapping match wins without changing any source character. */
    @Test
    fun distinguishesTheCurrentMatchInsideMergedCoverage() {
        val styles = FindHighlightStyles(
            SpanStyle(background = Color.LightGray),
            SpanStyle(background = Color.Blue, textDecoration = TextDecoration.Underline)
        )
        val text = "😀banana"
        val highlighted = findHighlightedText(text, listOf(TextRange(3, 8)), TextRange(5, 8), styles)
        assertEquals(text, highlighted.text)
        assertEquals(listOf(styles.other, styles.current), highlighted.spanStyles.map { it.item })
        assertEquals(listOf(3 to 8, 5 to 8), highlighted.spanStyles.map { it.start to it.end })
        assertEquals(emptyList<Any>(), findHighlightedText(text, emptyList(), null, styles).spanStyles)
    }
}
