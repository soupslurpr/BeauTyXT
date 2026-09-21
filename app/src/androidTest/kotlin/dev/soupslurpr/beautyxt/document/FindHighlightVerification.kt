/* Verifies native match coverage and transient styling in both production source layouts. */
package dev.soupslurpr.beautyxt.document

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.core.view.WindowCompat
import dev.soupslurpr.beautyxt.HomeActivity
import dev.soupslurpr.beautyxt.markdown.client.IsolatedMarkdownRenderer
import dev.soupslurpr.beautyxt.ui.designsystem.BeauTyXTTheme
import dev.soupslurpr.beautyxt.ui.editor.DocumentEditor
import dev.soupslurpr.beautyxt.ui.editor.EditorDocumentSource
import dev.soupslurpr.beautyxt.ui.editor.EditorDocumentState
import dev.soupslurpr.beautyxt.ui.editor.EditorSession
import dev.soupslurpr.beautyxt.ui.editor.FindStatus
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

private const val HIGHLIGHT_SOURCE = "River river RIVER\nbanana banana\nKk K\n😀 river\n"

/** Checks real Compose layouts so highlights cannot silently disappear from editable fields. */
internal fun Instrumentation.verifyFindHighlights(capturePreviews: Boolean = false) {
    val automation = if (capturePreviews) uiAutomation else null
    for (readOnly in listOf(false, true)) {
        val document = RustDocument.createEmpty()
        val metrics = document.replace(0, Utf16Range(0, 0), HIGHLIGHT_SOURCE)
        val source = if (readOnly) object : EditorDocumentSource {
            override fun matchesSourceUri(encodedUri: String) = false
            override fun encodedShareUri(): String? = null
            override fun close() = Unit
        } else null
        val session = EditorSession(
            title = "Find highlights.txt",
            state = EditorDocumentState(document, initialRevision = metrics.revision),
            documentSource = source,
            markdownRenderer = IsolatedMarkdownRenderer(targetContext)
        )
        val dark = mutableStateOf(false)
        val activity = startActivitySync(
            Intent(targetContext, HomeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        ) as HomeActivity
        try {
            runBlocking {
                withContext(Dispatchers.Main) {
                    activity.setContent {
                        BeauTyXTTheme(darkTheme = dark.value) {
                            DocumentEditor(session, activity::finish, closesDocumentTask = false)
                        }
                    }
                    session.openInitialEditor()
                    withTimeout(10_000) {
                        snapshotFlow {
                            session.canShowFind && (readOnly || session.activeDraft != null)
                        }.first { it }
                    }
                    check(session.showFind()) { "Find did not open after source became ready" }
                    withTimeout(10_000) { snapshotFlow { session.isFindVisible }.first { it } }
                    session.updateFindFieldValue(TextFieldValue("river"))
                    awaitHighlightMatch(session, 0)
                    for (darkTheme in listOf(false, true)) {
                        dark.value = darkTheme
                        WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
                            isAppearanceLightStatusBars = !darkTheme
                            isAppearanceLightNavigationBars = !darkTheme
                        }
                        val decorated = awaitHighlightedSource(activity, "River river RIVER") {
                            it.spanStyles.count { span -> span.item.textDecoration == TextDecoration.Underline } == 1 &&
                                it.spanStyles.count { span -> span.item.textDecoration == null } >= 3
                        }
                        val current = decorated.spanStyles.last()
                        check(current.start == 0 && current.end == 5) {
                            "current highlight has incorrect offsets: ${current.start}..${current.end}"
                        }
                        check(current.item.background != decorated.spanStyles.first().item.background) {
                            "current and other matches have identical backgrounds"
                        }
                        if (capturePreviews) {
                            delay(300)
                            withContext(Dispatchers.IO) {
                                val bitmap = checkNotNull(automation?.takeScreenshot())
                                try {
                                    val name = "find-${if (readOnly) "reading" else "editing"}-${if (darkTheme) "dark" else "light"}.png"
                                    File(targetContext.cacheDir, name).outputStream().use {
                                        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                                    }
                                } finally {
                                    bitmap.recycle()
                                }
                            }
                        }
                    }
                    session.updateFindCaseSensitivity(true)
                    awaitHighlightMatch(session, 6)
                    awaitHighlightedSource(activity, "River river RIVER") { text ->
                        text.spanStyles.filter { it.start < 17 }.map { it.start to it.end } ==
                            listOf(6 to 11, 6 to 11)
                    }
                    session.updateFindCaseSensitivity(false)
                    session.updateFindFieldValue(TextFieldValue("ana"))
                    awaitHighlightMatch(session, 19)
                    session.findNext()
                    awaitHighlightMatch(session, 21)
                    awaitHighlightedSource(activity, "banana banana") { text ->
                        val offset = if (readOnly) 0 else 18
                        text.spanStyles.any { it.start == offset + 1 && it.end == offset + 6 } &&
                            text.spanStyles.last().let { it.start == offset + 3 && it.end == offset + 6 }
                    }
                    // Superseded work must never restore decoration for an older query.
                    session.updateFindFieldValue(TextFieldValue("river"))
                    session.updateFindFieldValue(TextFieldValue("absent"))
                    withTimeout(10_000) {
                        snapshotFlow { session.findStatus }.first { it == FindStatus.NoMatches }
                    }
                    awaitHighlightedSource(activity, "banana banana") { it.spanStyles.isEmpty() }
                    session.updateFindFieldValue(TextFieldValue("r"))
                    awaitHighlightedSource(activity, "River river RIVER") { it.spanStyles.size >= 6 }
                    session.closeFind()
                    awaitHighlightedSource(activity, "River river RIVER") { it.spanStyles.isEmpty() }
                    check(!session.state.hasDocumentChanges) { "Find decoration dirtied the document" }
                    session.activeDraft?.let { draft ->
                        check(draft.textFieldState.text.toString() == HIGHLIGHT_SOURCE) {
                            "Find decoration changed the draft text"
                        }
                    }
                }
            }
        } finally {
            runOnMainSync {
                activity.finishAndRemoveTask()
                session.close()
            }
        }
    }
}

private suspend fun awaitHighlightMatch(session: EditorSession, start: Long) {
    withTimeout(10_000) {
        snapshotFlow { (session.findStatus as? FindStatus.Match)?.match?.range?.start }
            .first { it == start }
    }
}

/** Reads actual text layout annotations through Compose's testing semantics. */
private suspend fun awaitHighlightedSource(
    activity: HomeActivity,
    content: String,
    condition: (AnnotatedString) -> Boolean
): AnnotatedString = withTimeout(10_000) {
    while (true) {
        val texts = highlightTextLayouts(activity.window.decorView).map { it.layoutInput.text }
        texts.firstOrNull { it.text.contains(content) && condition(it) }?.let { return@withTimeout it }
        delay(30)
    }
    @Suppress("UNREACHABLE_CODE")
    error("highlighted source never appeared")
}

private fun highlightTextLayouts(view: View): List<TextLayoutResult> = buildList {
    if (view is ViewRootForTest) {
        view.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false).forEach { node ->
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(this)
        }
    }
    if (view is ViewGroup) {
        repeat(view.childCount) { addAll(highlightTextLayouts(view.getChildAt(it))) }
    }
}
