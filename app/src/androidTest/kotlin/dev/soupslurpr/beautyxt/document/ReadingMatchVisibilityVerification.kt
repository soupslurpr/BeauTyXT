package dev.soupslurpr.beautyxt.document

import android.app.Instrumentation
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import dev.soupslurpr.beautyxt.ui.editor.MarkdownPreviewStatus

/** A vertically correct jump is insufficient when the match lives in horizontally scrolled text. */
internal fun Instrumentation.verifyReadingMatchVisibility() {
    val text = """
        # Wide content

        ```text
        left marker ${"long code ".repeat(35)} right marker
        ```

        | First | Second | Third | Fourth | Fifth | Sixth | Seventh | Eighth |
        | --- | --- | --- | --- | --- | --- | --- | --- |
        | one | two | three | four | five | six | seven | table marker |

        A last paragraph.
    """.trimIndent()
    withReadingPage(text) { activity, session ->
        runOnMainSync { check(session.showFind(showKeyboard = false)) }
        for (needle in listOf("right marker", "left marker", "table marker")) {
            runOnMainSync { session.updateFindFieldValue(TextFieldValue(needle)) }
            awaitReadingCondition("matches for $needle did not complete") { session.isFindComplete && session.findResults.size == 1 }
            requireActionableContentDescription("Next match").performRequiredClick()
            awaitReadingCondition("$needle navigation did not settle") {
                session.findResultIndex == 0 &&
                    (session.markdownPreviewStatus as? MarkdownPreviewStatus.Ready)?.scrollRestoration == null
            }
            var measurements: List<Pair<Rect, Rect>> = emptyList()
            try {
                awaitReadingCondition("$needle remained outside the visible reading area") {
                    measurements = visibleMatchBounds(activity.window.decorView, needle)
                    measurements.any { (match, visible) ->
                        visible.width > 0 && visible.height > 0 &&
                            match.left >= visible.left - 2 && match.right <= visible.right + 2 &&
                            match.top >= visible.top - 2 && match.bottom <= visible.bottom + 2
                    }
                }
            } catch (failure: AssertionError) {
                error("${failure.message}; match and clipped text bounds: $measurements")
            }
        }
    }
}

private fun visibleMatchBounds(view: View, needle: String): List<Pair<Rect, Rect>> = buildList {
    if (view is ViewRootForTest) {
        view.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false).forEach { node ->
            val layouts = ArrayList<TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            layouts.forEach { layout ->
                val text = layout.layoutInput.text
                val start = text.text.indexOf(needle)
                if (start >= 0 && text.spanStyles.any {
                    it.item.textDecoration == TextDecoration.Underline && it.start <= start && it.end >= start + needle.length
                }) {
                    val first = layout.getBoundingBox(start)
                    val last = layout.getBoundingBox(start + needle.length - 1)
                    add(Rect(minOf(first.left, last.left), minOf(first.top, last.top),
                        maxOf(first.right, last.right), maxOf(first.bottom, last.bottom))
                        .translate(node.positionInWindow) to node.boundsInWindow)
                }
            }
        }
    }
    if (view is ViewGroup) repeat(view.childCount) { addAll(visibleMatchBounds(view.getChildAt(it), needle)) }
}
