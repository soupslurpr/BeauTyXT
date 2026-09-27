package dev.soupslurpr.beautyxt.document

import android.app.Instrumentation
import androidx.compose.ui.text.input.TextFieldValue
import dev.soupslurpr.beautyxt.ui.editor.EditorPresentation
import dev.soupslurpr.beautyxt.ui.editor.FindStatus

/** Crosses the retained-result boundary using actual Rust zero-width and UTF-16 semantics. */
internal fun Instrumentation.verifyFindContinuation() {
    withReadingPage("😀\n".repeat(4100), initialPresentation = EditorPresentation.Text) { _, session ->
        runOnMainSync {
            session.showFind(false)
            session.showReplace()
            session.updateFindRegex(true)
            session.updateReplacementFieldValue(TextFieldValue("line: "))
            session.updateFindFieldValue(TextFieldValue("(?m)^"))
        }
        awaitReadingCondition("dense regex search did not pause") {
            session.findStatus != FindStatus.Searching && session.canContinueFind
        }
        // The time budget can pause before the retained-result boundary on a busy device.
        // Continue those appended pages until the bounded review itself is full.
        repeat(20) {
            if (session.findResults.size < MAX_SEARCH_RESULTS) {
                runOnMainSync { check(session.continueFind()) }
                awaitReadingCondition("regex search did not reach its next pause") { session.findStatus != FindStatus.Searching }
            }
        }
        val offsets = ArrayList<Long>()
        runOnMainSync {
            check(session.findResults.size == MAX_SEARCH_RESULTS)
            check(!session.canApplyFindReplacements)
            offsets += session.findResults.map { it.hit.range.start }
            check(session.continueFind())
        }
        awaitReadingCondition("regex continuation did not finish its page") { session.findStatus != FindStatus.Searching }
        repeat(20) {
            if (session.canContinueFind) {
                runOnMainSync { check(session.continueFind()) }
                awaitReadingCondition("regex continuation did not finish its next page") { session.findStatus != FindStatus.Searching }
            }
        }
        runOnMainSync {
            offsets += session.findResults.map { it.hit.range.start }
            check(!session.canContinueFind && !session.canApplyFindReplacements) {
                "Continuation state: next=" + session.canContinueFind + ", apply=" + session.canApplyFindReplacements +
                    ", matches=" + session.findResults.size + ", earlier=" + session.hasEarlierFindResults +
                    ", status=" + session.findStatus + ", coverage=" + session.findCoverageMessage
            }
            check(offsets == (0L..4100L).map { it * 3 }) { "Continuation skipped or repeated a zero-width UTF-16 position" }
            session.updateFindFieldValue(TextFieldValue("absent"))
        }
        awaitReadingCondition("fresh query retained partial coverage") { session.isFindComplete }
        check(session.findResults.isEmpty())
    }
}
