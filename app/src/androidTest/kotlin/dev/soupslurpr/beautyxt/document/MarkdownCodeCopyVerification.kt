/* Exercises whole-code copying through the real isolated renderer and Compose UI. */
package dev.soupslurpr.beautyxt.document

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Instrumentation
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.soupslurpr.beautyxt.HomeActivity
import dev.soupslurpr.beautyxt.markdown.client.IsolatedMarkdownRenderer
import dev.soupslurpr.beautyxt.sharing.readSharedTextSnapshot
import dev.soupslurpr.beautyxt.ui.designsystem.BeauTyXTTheme
import dev.soupslurpr.beautyxt.ui.editor.DocumentEditor
import dev.soupslurpr.beautyxt.ui.editor.EDIT_DRAFT_MAX_UTF16_UNITS
import dev.soupslurpr.beautyxt.ui.editor.EditorDocumentState
import dev.soupslurpr.beautyxt.ui.editor.EditorInputRejection
import dev.soupslurpr.beautyxt.ui.editor.EditorPresentation
import dev.soupslurpr.beautyxt.ui.editor.EditorSession
import dev.soupslurpr.beautyxt.ui.editor.MAX_BULK_FIELD_UTF16_UNITS
import dev.soupslurpr.beautyxt.ui.editor.MAX_MARKDOWN_CODE_COPY_UTF16_UNITS
import dev.soupslurpr.beautyxt.ui.editor.MarkdownPreviewStatus
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Checks clipboard fidelity, ownership, limits, accessible bounds and large text. */
internal fun Instrumentation.verifyMarkdownCodeCopy(capturePreviews: Boolean = false) {
    val originalFlags = uiAutomation.serviceInfo.flags
    uiAutomation.serviceInfo = uiAutomation.serviceInfo.apply {
        flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
    }
    try {
        verifyMarkdownCodeCopyFixtures(capturePreviews)
    } finally {
        uiAutomation.serviceInfo = uiAutomation.serviceInfo.apply { flags = originalFlags }
    }
}

private fun Instrumentation.verifyMarkdownCodeCopyFixtures(capturePreviews: Boolean) {
    val short = "  val message = \"café 😀\"\n\tprintln(message)  \n\n"
    val fragmented = buildString {
        repeat(1_600) { append("line $it: café 😀\tvalue  \n") }
    }
    val fixtures = listOf(
        "# Reuse a snippet\n\n~~~kotlin\n$short~~~\n\nUnrelated prose.\n" to short,
        "~~~text\n$fragmented~~~\n" to fragmented,
        "> ~~~\n> quoted\n>   code\n> ~~~\n" to "quoted\n  code\n",
        "> [!NOTE]\n> ~~~rust\n> let value = 3;\n> ~~~\n" to "let value = 3;\n",
        "<pre><code>&lt;tag&gt; &amp; text\n</code></pre>\n" to "<tag> & text\n",
        "    indented\n    code\n" to "indented\ncode\n",
        "~~~\n${"x".repeat(MAX_MARKDOWN_CODE_COPY_UTF16_UNITS)}\n~~~\n" to null,
        "~~~\n~~~\n" to null
    )
    for ((fixtureIndex, fixture) in fixtures.withIndex()) {
        val (source, expected) = fixture
        val document = RustDocument.createEmpty()
        val metrics = document.replace(0L, Utf16Range(0L, 0L), source)
        val session = EditorSession(
            title = "Code snippets.md",
            state = EditorDocumentState(document, initialRevision = metrics.revision),
            initialPresentation = EditorPresentation.MarkdownPreview,
            markdownRenderer = IsolatedMarkdownRenderer(targetContext)
        )
        val activity = startActivitySync(
            Intent(targetContext, HomeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        ) as HomeActivity
        val clipboard = checkNotNull(activity.getSystemService(ClipboardManager::class.java))
        val scale = mutableFloatStateOf(1f)
        val denied = mutableStateOf(false)
        val deniedContext = object : ContextWrapper(activity) {
            override fun getSystemService(name: String): Any? {
                if (name == Context.CLIPBOARD_SERVICE) throw SecurityException("test denial")
                return super.getSystemService(name)
            }
        }
        val density = activity.resources.displayMetrics.density
        try {
            runBlocking {
                withContext(Dispatchers.Main) {
                    activity.setContent {
                        CompositionLocalProvider(
                            LocalDensity provides Density(density, scale.floatValue),
                            LocalContext provides if (denied.value) deniedContext else activity
                        ) {
                            BeauTyXTTheme {
                                Box(Modifier.width(320.dp).fillMaxHeight()) {
                                    DocumentEditor(
                                        session,
                                        activity::finish,
                                        closesDocumentTask = false
                                    )
                                }
                            }
                        }
                    }
                    session.openInitialEditor()
                    withTimeout(15_000) {
                        snapshotFlow { session.markdownPreviewStatus }
                            .first { it is MarkdownPreviewStatus.Ready }
                    }
                }
            }
            waitForAccessibilityIdle()
            runOnMainSync {
                clipboard.setPrimaryClip(ClipData.newPlainText("Test", "untouched"))
            }
            val description =
                waitForCodeCopyAppNode("Copy code control for fixture $fixtureIndex") {
                    it.contentDescription?.toString() == "Copy code"
                }
            if (expected == null) {
                check(!description.codeCopyControl().isEnabled) {
                    "empty or oversized code must not be copied in fixture $fixtureIndex"
                }
                runOnMainSync {
                    check(clipboard.primaryClip?.getItemAt(0)?.text?.toString() == "untouched")
                }
                continue
            }
            val button = codeCopyButton()
            val bounds = Rect().also(button::getBoundsInScreen)
            check(bounds.width() >= 48 * density - 1 && bounds.height() >= 48 * density - 1)
            button.performRequiredClick()
            waitForAccessibilityIdle()
            runOnMainSync {
                val clip = checkNotNull(clipboard.primaryClip)
                check(clip.itemCount == 1)
                check(clip.getItemAt(0).text?.toString() == expected) {
                    "code copy differs from complete rendered text for fixture $fixtureIndex"
                }
                check(clip.getItemAt(0).uri == null && clip.getItemAt(0).intent == null)
                check(clip.getItemAt(0).htmlText == null)
                check(clip.description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN))
                check(
                    clip.description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) != true
                )
                check(session.presentation == EditorPresentation.MarkdownPreview)
                check(session.state.metrics?.revision == metrics.revision)
            }
            if (fixtureIndex == 0) {
                for (fontScale in listOf(1f, 2f)) {
                    runOnMainSync { scale.floatValue = fontScale }
                    waitForAccessibilityIdle()
                    codeCopyButton().performRequiredClick()
                    waitForAccessibilityIdle()
                    if (capturePreviews) {
                        captureCodeCopyPreview("code-copy-$fontScale.webp")
                    }
                }
                runOnMainSync { denied.value = true }
                waitForAccessibilityIdle()
                codeCopyButton().performRequiredClick()
                waitForCodeCopyAppNode("copy failure feedback") {
                    it.text?.toString() == "Couldn’t copy code. Try again."
                }
                runOnMainSync { denied.value = false }
                waitForAccessibilityIdle()
                codeCopyButton().performRequiredClick()
                waitForAccessibilityIdle()
            }
            if (fixtureIndex == 1) {
                check(expected.length > EDIT_DRAFT_MAX_UTF16_UNITS)
                verifyCopiedCodeCanBePasted(activity, expected, capturePreviews)
            }
        } finally {
            runOnMainSync {
                clipboard.clearPrimaryClip()
                activity.finishAndRemoveTask()
                session.close()
            }
        }
    }
}

/** Pastes the actual copied snippet through Android and exercises the visible history controls. */
private fun Instrumentation.verifyCopiedCodeCanBePasted(
    activity: HomeActivity,
    expected: String,
    capturePreviews: Boolean
) {
    val document = RustDocument.createEmpty()
    val session = EditorSession(
        title = "Pasted text.txt",
        state = EditorDocumentState(document)
    )
    try {
        runOnMainSync {
            activity.setContent {
                BeauTyXTTheme {
                    DocumentEditor(session, activity::finish, closesDocumentTask = false)
                }
            }
            session.openInitialEditor()
        }
        waitForAccessibilityIdle()
        val editable =
            waitForCodeCopyAppNode("empty paste target") { it.isEditable && it.isEnabled }
        editable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        waitForAccessibilityIdle()
        var originalField: Any? = null
        runOnMainSync { originalField = checkNotNull(session.activeDraft).textFieldState }
        check(editable.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
            "the source editor did not accept the Android paste action"
        }
        awaitCopiedCodeLength(session, expected.length)
        val revision = checkNotNull(session.state.metrics).revision
        runBlocking {
            withContext(Dispatchers.Default) {
                document.captureSnapshot(revision).use { snapshot ->
                    check(
                        readSharedTextSnapshot(
                            snapshot,
                            expected.toByteArray(Charsets.UTF_8).size.toLong()
                        ) ==
                            expected
                    ) {
                        "the pasted native document differs from the copied code"
                    }
                }
            }
        }
        runOnMainSync {
            val draft = checkNotNull(session.activeDraft)
            check(draft.textFieldState === originalField) {
                "bulk paste replaced its focused field"
            }
            check(draft.textFieldState.text.length <= EDIT_DRAFT_MAX_UTF16_UNITS)
            check(draft.inputRejection == null) { "a complete copied block could not be pasted" }
        }

        runOnMainSync {
            val clipboard = checkNotNull(activity.getSystemService(ClipboardManager::class.java))
            clipboard.setPrimaryClip(
                ClipData.newPlainText(
                    "Oversized test input",
                    "x".repeat(MAX_BULK_FIELD_UTF16_UNITS + 1)
                )
            )
        }
        val pasteTarget = waitForCodeCopyAppNode("bounded paste target") {
            it.isEditable && it.isEnabled
        }
        check(pasteTarget.performAction(AccessibilityNodeInfo.ACTION_PASTE))
        waitForCodeCopyAppNode("oversized paste feedback") {
            it.text?.toString() ==
                "That insertion is too large. Insert smaller sections or open the content as a file."
        }
        runOnMainSync {
            val draft = checkNotNull(session.activeDraft)
            check(session.state.metrics?.revision == revision)
            check(draft.textFieldState === originalField)
            check(!draft.hasChanges && !session.hasPendingEditWindowAction)
            check(draft.inputRejection == EditorInputRejection.BulkSize)
        }
        if (capturePreviews) captureCodeCopyPreview("oversized-paste.webp")

        for ((action, length) in listOf("Undo" to 0, "Redo" to expected.length)) {
            waitForCodeCopyAppNode(action) { it.contentDescription?.toString() == action }
                .codeCopyControl().performRequiredClick()
            awaitCopiedCodeLength(session, length)
            runOnMainSync {
                check(checkNotNull(session.activeDraft).textFieldState === originalField) {
                    "bulk $action replaced its focused field"
                }
            }
        }
    } finally {
        runOnMainSync { session.close() }
    }
}

private fun Instrumentation.captureCodeCopyPreview(name: String) {
    val image = checkNotNull(uiAutomation.takeScreenshot())
    try {
        File(targetContext.cacheDir, name).outputStream().use {
            check(image.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, it))
        }
    } finally {
        image.recycle()
    }
}

private fun Instrumentation.awaitCopiedCodeLength(session: EditorSession, length: Int) {
    runBlocking {
        withContext(Dispatchers.Main) {
            withTimeout(15_000) {
                snapshotFlow {
                    session.state.metrics?.utf16Length == length.toLong() &&
                        !session.hasPendingEditWindowAction &&
                        session.activeDraft?.hasChanges == false
                }.first { it }
            }
        }
    }
    waitForAccessibilityIdle()
}

private fun Instrumentation.codeCopyButton(): AccessibilityNodeInfo {
    val node = waitForCodeCopyAppNode("enabled Copy code") {
        it.contentDescription?.toString() == "Copy code"
    }.codeCopyControl()
    check(node.isEnabled)
    return node
}

private fun AccessibilityNodeInfo.codeCopyControl(): AccessibilityNodeInfo {
    var node = this
    while (!node.isClickable) {
        node = checkNotNull(node.parent) { "Copy code has no button ancestor" }
    }
    return node
}

/** The clipboard confirmation owns a separate System UI window without hiding the app. */
private fun Instrumentation.waitForCodeCopyAppNode(
    description: String,
    predicate: (AccessibilityNodeInfo) -> Boolean
): AccessibilityNodeInfo {
    fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (predicate(node)) return node
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            find(child)?.let { return it }
        }
        return null
    }
    val deadline = SystemClock.uptimeMillis() + 10_000
    while (SystemClock.uptimeMillis() < deadline) {
        for (window in uiAutomation.windows) {
            val root = window.root ?: continue
            if (root.packageName?.toString() == targetContext.packageName) {
                find(root)?.let { return it }
            }
        }
        SystemClock.sleep(50)
    }
    error("code-copy app node did not appear: $description")
}
