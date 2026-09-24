/* Verifies usable controls and retained input when the editor's layout reflows. */
package dev.soupslurpr.beautyxt.document

import android.accessibilityservice.AccessibilityService
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.soupslurpr.beautyxt.HomeActivity
import dev.soupslurpr.beautyxt.markdown.client.IsolatedMarkdownRenderer
import dev.soupslurpr.beautyxt.ui.designsystem.BeauTyXTTheme
import dev.soupslurpr.beautyxt.ui.editor.DocumentEditor
import dev.soupslurpr.beautyxt.ui.editor.EditorDocumentState
import dev.soupslurpr.beautyxt.ui.editor.EditorSession
import dev.soupslurpr.beautyxt.ui.editor.FindStatus
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

private const val COMPACT_EDITOR_TEXT = "alpha beta alpha\n"
private const val COMPACT_EDITOR_QUERY = "alpha"
private const val MINIMUM_CONTROL_SIZE_DP = 48
private const val ACCESSIBILITY_BOUNDS_ROUNDING_PIXELS = 1
private const val MINIMUM_COMPACT_QUERY_WIDTH_DP = 160
private const val MAXIMUM_COMPACT_QUERY_HEIGHT_DP = 72

/** Exercises narrow-window history, sending, live Find, and text-scale reflow. */
internal fun Instrumentation.verifyCompactEditorControls() {
    val session = EditorSession(
        title = "Compact.md",
        state = EditorDocumentState(RustDocument.createEmpty()),
        markdownRenderer = IsolatedMarkdownRenderer(targetContext)
    )
    val width = mutableStateOf(256.dp)
    val fontScale = mutableFloatStateOf(1f)
    val activity = startActivitySync(
        Intent(targetContext, HomeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    ) as HomeActivity
    val density = activity.resources.displayMetrics.density
    try {
        runOnMainSync {
            activity.setContent {
                CompositionLocalProvider(
                    LocalDensity provides Density(density, fontScale.floatValue)
                ) {
                    BeauTyXTTheme {
                        Box(Modifier.width(width.value).fillMaxHeight()) {
                            DocumentEditor(session, activity::finish, closesDocumentTask = false)
                        }
                    }
                }
            }
        }
        val editor = waitForEditField()
        editor.performRequiredClick()
        editor.setVerificationText(COMPACT_EDITOR_TEXT)
        waitForEditorText(COMPACT_EDITOR_TEXT)
        // Android exposes integer screen bounds for fractionally positioned Compose controls.
        val minimumTouchPixels = (MINIMUM_CONTROL_SIZE_DP * density).roundToInt() -
            ACCESSIBILITY_BOUNDS_ROUNDING_PIXELS
        val narrowWidthPixels = (width.value.value * density).roundToInt()
        for (description in listOf(
            "Undo",
            "Preview Markdown",
            "Save document",
            "More document actions"
        )) {
            val action = requireActionableContentDescription(description)
            requireControlBounds(action, minimumTouchPixels, narrowWidthPixels)
        }
        requireActionableContentDescription("Undo").performRequiredClick()
        waitForEditorText("")
        requireActionableContentDescription("More document actions").performRequiredClick()
        requireActionableText("Redo").performRequiredClick()
        waitForEditorText(COMPACT_EDITOR_TEXT)
        requireActionableContentDescription("More document actions").performRequiredClick()
        requireActionableText("Send & export").performRequiredClick()
        requireActionableText("Share text")
        // Content appears before the sheet finishes opening and the IME relinquishes Back.
        waitForAccessibilityNode("expanded Send sheet dismissal") { node ->
            node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_DISMISS }
        }
        waitForAccessibilityIdle()
        check(uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)) {
            "could not dismiss the compact Send sheet"
        }
        waitForAccessibilityIdle()
        requireActionableContentDescription("Find in document").performRequiredClick()
        val query = waitForFindQuery()
        val queryBounds = Rect().also(query::getBoundsInScreen)
        check(queryBounds.width() >= MINIMUM_COMPACT_QUERY_WIDTH_DP * density) {
            "compact Find query is too narrow: $queryBounds"
        }
        check(queryBounds.height() <= MAXIMUM_COMPACT_QUERY_HEIGHT_DP * density) {
            "compact Find placeholder grew beyond one input row: $queryBounds"
        }
        query.setVerificationText(COMPACT_EDITOR_QUERY)
        waitForAccessibilityNode("live compact Find match") { node ->
            node.text?.toString() in setOf(
                "Match on line 1",
                "Wrapped to beginning · Match on line 1"
            )
        }
        awaitCompactFindMatch(session, 0L)
        for (description in listOf(
            "Close Find", "Clear search", "Match case", "Previous match", "Next match"
        )) {
            requireControlBounds(
                requireActionableContentDescription(description),
                minimumTouchPixels,
                narrowWidthPixels
            )
        }
        for ((index, layout) in listOf(320.dp to 2f, 400.dp to 1f).withIndex()) {
            val (newWidth, newFontScale) = layout
            runOnMainSync {
                width.value = newWidth
                fontScale.floatValue = newFontScale
            }
            waitForAccessibilityIdle()
            val retainedQuery = waitForFindQuery(
                COMPACT_EDITOR_QUERY,
                "after reflow to $newWidth at font scale $newFontScale"
            )
            check(Rect().also(retainedQuery::getBoundsInScreen).height() <= 96 * density) {
                "Find query grew beyond a single large-text row"
            }
            clickCompactActionAfterReflow("Next match")
            awaitCompactFindMatch(session, if (index == 0) 11L else 0L)
        }
        requireActionableContentDescription("Match case").performRequiredClick()
        awaitCompactFindMatch(session, 0L)
        requireActionableContentDescription("Clear search").performRequiredClick()
        val clearedQuery = waitForFindQuery("", "after clearing the query")
        runOnMainSync {
            check(session.isFindVisible && session.isFindCaseSensitive) {
                "clearing Find closed it or reset the case preference"
            }
            check(session.findStatus == FindStatus.Idle && session.findMatch == null) {
                "clearing Find retained the old result"
            }
            check(!session.canNavigateFind) { "empty Find still allows match navigation" }
        }
        clearedQuery.setVerificationText("beta")
        awaitCompactFindMatch(session, 6L)
        requireActionableContentDescription("Close Find").performRequiredClick()
        waitForEditorText(COMPACT_EDITOR_TEXT)
        waitForAccessibilityNode("direct Redo restored in the full-width toolbar") { node ->
            node.contentDescription?.toString() == "Redo"
        }
        requireActionableContentDescription("Send and export")
    } finally {
        runOnMainSync {
            activity.finishAndRemoveTask()
            session.close()
        }
        waitForAccessibilityIdle()
    }
}

/** Reflow replaces semantics nodes; retry only clicks Android explicitly did not handle. */
private fun Instrumentation.clickCompactActionAfterReflow(description: String) {
    val deadline = SystemClock.uptimeMillis() + 10_000L
    while (SystemClock.uptimeMillis() < deadline) {
        val action = requireActionableContentDescription(description)
        if (action.refresh() && action.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
        SystemClock.sleep(50L)
    }
    error("compact action remained unavailable after reflow: $description")
}

/** Checks that one accepted UI action completed and moved to the intended native match. */
private fun awaitCompactFindMatch(session: EditorSession, offset: Long) = runBlocking {
    withContext(Dispatchers.Main) {
        withTimeout(10_000L) {
            snapshotFlow {
                val match = session.findStatus as? FindStatus.Match
                session.canNavigateFind && match?.match?.range?.start == offset
            }.first { it }
        }
    }
}

/** Resolves the Find label to its editable ancestor, including split Compose semantics. */
private fun Instrumentation.waitForFindQuery(
    expectedText: String? = null,
    stage: String = "on opening"
): AccessibilityNodeInfo {
    val label = waitForAccessibilityNode("focused Find query $stage") { node ->
        node.contentDescription?.toString() == "Find in document" &&
            node.editableAncestor()?.let { input ->
                input.isFocused && (expectedText == null || input.text?.toString() == expectedText)
            } == true
    }
    return checkNotNull(label.editableAncestor())
}

/** Locates the input that owns one semantics label without assuming a fixed tree depth. */
private fun AccessibilityNodeInfo.editableAncestor(): AccessibilityNodeInfo? {
    var input: AccessibilityNodeInfo? = this
    while (input != null && !input.isEditable) {
        input = input.parent
    }
    return input?.takeIf { it.isEnabled && it.isVisibleToUser }
}

/** Enters deterministic text through the actual Compose accessibility input path. */
private fun AccessibilityNodeInfo.setVerificationText(text: String) {
    check(
        performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
        )
    ) { "compact input rejected verification text" }
}

/** Requires a complete touch target within the constrained editor width. */
private fun requireControlBounds(
    node: AccessibilityNodeInfo,
    minimumSize: Int,
    availableWidth: Int
) {
    val bounds = Rect().also(node::getBoundsInScreen)
    check(bounds.width() >= minimumSize && bounds.height() >= minimumSize) {
        "compact action has an undersized touch target: $bounds"
    }
    check(bounds.left >= 0 && bounds.right <= availableWidth) {
        "compact action escapes the editor width: $bounds"
    }
}
