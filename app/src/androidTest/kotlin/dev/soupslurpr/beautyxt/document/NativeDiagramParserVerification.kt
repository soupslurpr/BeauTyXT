/*
 * The regression input below is adapted from Merman's public fuzz artifact.
 * Copyright (c) 2026 merman contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package dev.soupslurpr.beautyxt.document

import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.util.Log
import dev.soupslurpr.beautyxt.illustration.IllustrationLimits
import dev.soupslurpr.beautyxt.illustration.IllustrationResult
import dev.soupslurpr.beautyxt.illustration.RestartingIllustrationWorker
import dev.soupslurpr.beautyxt.ipc.NativeServiceNames
import dev.soupslurpr.beautyxt.markdown.MarkdownBlockKind
import dev.soupslurpr.beautyxt.markdown.client.IsolatedMarkdownRenderer
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

// Upstream revision: 4c2ac78177f3429ed78abb864a1f9f13d415c55c.
// Artifact: timeout-8563c80f52c2fe202edcb6ab584af0e18e13a002.
// https://github.com/Latias94/merman/actions/runs/34949699661/job/104317506203
private val sequenceParserRegression =
    "sequenceDiagram\n  <|--ici-icipant A\n  pai()nt B\n%%\r\rako" +
        "\r".repeat(16) + ">B-: Horld\n"
private const val READY_DIAGRAM = "sequenceDiagram\nAlice->>Bob: Ready"
internal const val sequenceEmptyCommentRegression = "sequenceDiagram\n%%\n\nAlice->>Bob: Hello"

/** Real parser input must not bypass process-level containment or poison subsequent work. */
internal fun verifyNativeDiagramParserContainment(context: Context) = runBlocking {
    check(sequenceParserRegression.toByteArray(Charsets.UTF_8).size == 82)
    val appPid = Process.myPid()
    RestartingIllustrationWorker(
        context,
        NativeServiceNames.DIAGRAM,
        IllustrationLimits.MAX_DIAGRAM_SOURCE_BYTES
    ).use { worker ->
        check(worker.render(READY_DIAGRAM, true) is IllustrationResult.Rendered)
        val started = SystemClock.elapsedRealtime()
        val result = withTimeout(6_000) { worker.render(sequenceParserRegression, true) }
        val elapsed = SystemClock.elapsedRealtime() - started
        check(result is IllustrationResult.Fallback) { "invalid input rendered: $result" }
        check(elapsed < 5_500) { "parser failure relied on the seven-second client timeout" }
        check(Process.myPid() == appPid)
        Log.i("BeauTyXTVerification", "sequence parser fallback after ${elapsed}ms: $result")
        check(withTimeout(6_000) { worker.render(READY_DIAGRAM, true) } is IllustrationResult.Rendered) {
            "a parser failure prevented the next valid diagram"
        }

        // A minimized, valid empty comment also reproduced the no-progress loop. A fixed
        // upstream parser may render it; either result must remain bounded and recoverable.
        withTimeout(6_000) { worker.render(sequenceEmptyCommentRegression, true) }
        check(withTimeout(6_000) { worker.render(READY_DIAGRAM, true) } is IllustrationResult.Rendered)

        val cancelled = async { worker.render(sequenceParserRegression, true) }
        delay(100)
        withTimeout(1_500) { cancelled.cancelAndJoin() }
        check(withTimeout(6_000) { worker.render(READY_DIAGRAM, true) } is IllustrationResult.Rendered) {
            "cancelling a parser request prevented later rendering"
        }
    }

    // Markdown normalizes line endings, which also triggered the original no-progress loop.
    val source = sequenceParserRegression.replace('\r', '\n').trimEnd('\n')
    val markdown = "```mermaid\n$source\n```\n\n```mermaid\n$READY_DIAGRAM\n```"
    RustDocument.createEmpty().use { document ->
        val metrics = document.replace(0L, Utf16Range(0, 0), markdown)
        val preview = document.captureSnapshot(metrics.revision).use { snapshot ->
            withTimeout(10_000) {
                IsolatedMarkdownRenderer(context).render(snapshot, metrics.serializedByteLength)
            }
        }
        val blocks = preview.blocks.filter { it.kind == MarkdownBlockKind.Code }
        check(blocks.size == 2)
        check(blocks[0].text.trimEnd('\n') == source) { "parser fallback lost the original source" }
        check(blocks[0].spans.any { it.illustration is IllustrationResult.Fallback })
        check(blocks[1].spans.any { it.illustration is IllustrationResult.Rendered }) {
            "parser failure prevented the next preview block from rendering"
        }
    }
}
