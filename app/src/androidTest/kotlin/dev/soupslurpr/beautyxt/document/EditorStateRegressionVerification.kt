package dev.soupslurpr.beautyxt.document

import android.app.Instrumentation
import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.soupslurpr.beautyxt.HomeActivity
import dev.soupslurpr.beautyxt.sharing.IncomingDocumentShare
import dev.soupslurpr.beautyxt.sharing.IncomingTextOrigin
import dev.soupslurpr.beautyxt.ui.BeauTyXTSession
import dev.soupslurpr.beautyxt.ui.OpenStatus
import dev.soupslurpr.beautyxt.ui.designsystem.BeauTyXTTheme
import dev.soupslurpr.beautyxt.ui.editor.DocumentEditor
import dev.soupslurpr.beautyxt.ui.editor.EditorDocumentState
import dev.soupslurpr.beautyxt.ui.editor.EditorDocumentStatus
import dev.soupslurpr.beautyxt.ui.editor.EditorSession
import dev.soupslurpr.beautyxt.ui.editor.TRANSIENT_DOCUMENT_MAX_UTF16_UNITS
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Exercises drawing against a live Compose field and navigation against native paging. */
internal fun Instrumentation.verifyEditorStateRegressions() {
    verifyDirtyFindPositionMarkers()
    verifyReadingSelectionProjectionNotice()
    runBlocking {
        withContext(Dispatchers.Main) {
            verifyReadOnlyTerminalPositions()
            verifyReceivedTextNormalization()
        }
    }
}

private fun Instrumentation.verifyDirtyFindPositionMarkers() {
    var synchronizationGate: CompletableDeferred<Unit>? = null
    val document = RustDocument.createEmpty()
    val metrics = document.replace(0, Utf16Range(0, 0), "abc")
    val session = EditorSession("Find position markers.txt",
        EditorDocumentState(document, initialRevision = metrics.revision),
        editSynchronizationDelay = { synchronizationGate?.await() }, findDelay = {})
    val activity = startActivitySync(Intent(targetContext, HomeActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as HomeActivity
    try {
        runBlocking {
            withContext(Dispatchers.Main) {
                activity.setContent { BeauTyXTTheme {
                    DocumentEditor(session, activity::finish, closesDocumentTask = false)
                } }
                session.openInitialEditor()
                withTimeout(10_000) { snapshotFlow { session.activeDraft }.first { it != null } }
                check(session.showFind(showKeyboard = false))
                session.updateFindRegex(true)
                check(session.updateFindFieldValue(TextFieldValue("\\z")))
                withTimeout(10_000) { snapshotFlow { session.isFindComplete }.first { it } }
                check(session.findResults.single().source == Utf16Range(3, 3))
                val revealBefore = session.findRevealRequest
                check(session.findNext())
                withTimeout(10_000) { snapshotFlow { session.findRevealRequest }.first { it > revealBefore } }
                session.collapseFindResultsForNavigation()
                awaitRegressionSourceLayout(activity, "abc")
                val draft = checkNotNull(session.activeDraft)
                for (text in listOf("ab", "xyz", "abcd")) {
                    synchronizationGate = CompletableDeferred()
                    draft.textFieldState.edit { replace(0, length, text); selection = TextRange(length) }
                    session.observeActiveEdit(draft, draft.captureFieldValue())
                    awaitRegressionSourceLayout(activity, text)
                    // Keep the old result through real layout and drawing of the dirty field.
                    repeat(3) { activity.window.decorView.invalidate(); awaitFrame() }
                    check(draft.hasChanges && session.findResults.single().source == Utf16Range(3, 3))
                    synchronizationGate?.complete(Unit)
                    withTimeout(10_000) { snapshotFlow {
                        !draft.hasChanges && session.isFindComplete &&
                            session.findResults.singleOrNull()?.source ==
                            Utf16Range(text.length.toLong(), text.length.toLong())
                    }.first { it } }
                    repeat(2) { awaitFrame() }
                    // Restore the same baseline before the next deletion/equal-length/growth case.
                    synchronizationGate = null
                    draft.textFieldState.edit { replace(0, length, "abc"); selection = TextRange(3) }
                    session.observeActiveEdit(draft, draft.captureFieldValue())
                    withTimeout(10_000) { snapshotFlow {
                        !draft.hasChanges && session.isFindComplete &&
                            session.findResults.singleOrNull()?.source == Utf16Range(3, 3)
                    }.first { it } }
                }
                session.closeFind()
                draft.textFieldState.edit { replace(0, length, "a") }
                session.observeActiveEdit(draft, draft.captureFieldValue())
                repeat(3) { awaitFrame() }
                check(session.findResults.isEmpty())
            }
        }
    } finally {
        synchronizationGate?.complete(Unit)
        runOnMainSync { activity.finishAndRemoveTask(); session.close() }
    }
}

private suspend fun awaitRegressionSourceLayout(activity: HomeActivity, text: String) {
    withTimeout(10_000) {
        while (highlightTextLayouts(activity.window.decorView).none { it.layoutInput.text.text == text }) {
            delay(20)
        }
    }
}

private suspend fun verifyReadOnlyTerminalPositions() {
    val longLine = "😀".repeat(100) + "end"
    for (text in listOf("", "a\nb", "a\nb\n", "\n\n", "😀\nβ", longLine, "$longLine\ntail")) {
        val document = RustDocument.createEmpty()
        val metrics = document.replace(0, Utf16Range(0, 0), text)
        val state = EditorDocumentState(document, initialRevision = metrics.revision)
        try {
            state.loadInitialViewport()
            val targets = buildSet {
                add(0L)
                add(text.length.toLong())
                text.forEachIndexed { index, char -> if (char == '\n') {
                    add(index.toLong())
                    add(index + 1L)
                } }
            }
            for (target in targets) {
                check(state.navigateToSourceOffset(metrics.revision, target)) {
                    "read-only source position $target could not be revealed in ${text.length} units"
                }
                check(state.status == EditorDocumentStatus.Ready && state.blocks.isNotEmpty())
                check(state.blocks.any { target in it.block.globalUtf16Start..it.block.globalUtf16End }) {
                    "source position $target was not retained in visible source context"
                }
                check(!state.hasDocumentChanges && state.metrics?.revision == metrics.revision)
            }
            if (text.startsWith("😀")) {
                val retained = state.blocks
                check(!state.navigateToSourceOffset(metrics.revision, 1))
                check(state.blocks === retained)
            }
            check(document.readRange(metrics.revision, Utf16Range(0, text.length.toLong())) == text)
        } finally {
            state.close()
        }
    }
}

private suspend fun verifyReceivedTextNormalization() {
    val logical = "😀 one\ntwo\nthree\n"
    for (text in listOf(logical, logical.replace("\n", "\r\n"), logical.replace('\n', '\r'),
        "x".repeat(TRANSIENT_DOCUMENT_MAX_UTF16_UNITS - 4) + "\r\n😀")) {
        val expected = text.replace("\r\n", "\n").replace('\r', '\n')
        val state = EditorDocumentState.createTransientText(text)
        try {
            state.loadInitialViewport()
            val metrics = checkNotNull(state.metrics)
            check(state.readSourceRange(metrics.revision, Utf16Range(0, expected.length.toLong())) == expected)
            check(metrics.utf16Length == expected.length.toLong())
            check(!metrics.hasCrlfLineEndings && !metrics.hasCrLineEndings)
            check(state.isUneditedReceivedContent && state.hasDocumentChanges)
        } finally {
            state.close()
        }
        if (text.length < 1024) {
            for (origin in listOf(IncomingTextOrigin.Qr, IncomingTextOrigin.Nfc)) {
                val session = BeauTyXTSession()
                try {
                    val offer = IncomingDocumentShare.Text(text, DocumentFormat.Markdown, origin = origin)
                    session.offerIncomingShare(offer)
                    check(session.editor == null && session.incomingShare == offer)
                    check(offer.text == text) { "review changed the raw $origin offer" }
                    check(session.openIncomingText()) { "confirmed $origin offer could not open" }
                    check(session.incomingShare == null && session.openStatus == OpenStatus.Idle)
                    val received = checkNotNull(session.editor).state
                    received.loadInitialViewport()
                    val metrics = checkNotNull(received.metrics)
                    check(received.readSourceRange(metrics.revision, Utf16Range(0, metrics.utf16Length)) == expected)
                    check(!metrics.hasCrlfLineEndings && !metrics.hasCrLineEndings)
                    check(received.isUneditedReceivedContent && received.hasDocumentChanges)
                } finally {
                    session.close()
                }
            }
        }
    }
}
