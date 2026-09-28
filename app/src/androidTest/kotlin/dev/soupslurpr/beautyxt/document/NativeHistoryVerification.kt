package dev.soupslurpr.beautyxt.document

import android.app.Instrumentation
import android.os.Debug
import android.os.ParcelFileDescriptor
import dev.soupslurpr.beautyxt.ipc.ReliableSnapshotPipe
import java.security.MessageDigest
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.Log
import dev.soupslurpr.beautyxt.sharing.readSharedTextSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

/** Exercises real JNI ownership, large Unicode transfer, and format-preserving history. */
internal fun Instrumentation.verifyNativeHistory() = runBlocking {
    val source = "\uFEFFbefore\r\nafter\rtail\n".toByteArray(Charsets.UTF_8)
    AnonymousTestBuffer.create("native-history-source", source, allowSealing = true).use { backing ->
        backing.duplicate().use { descriptor ->
            RustDocument.openSource(descriptor.fd, source.size.toLong()).use { document ->
                val raw = "😀é\r\nx\r".repeat(300_000)
                val logical = "😀é\nx\n".repeat(300_000)
                val metrics = document.replaceContent(0, Utf16Range(7, 7), DocumentInsertion(raw))
                check(metrics.revision == 1L && metrics.utf16Length == 18L + logical.length)
                check(metrics.insertedLineEnding == DocumentLineEnding.CrLf)
                val history = document.historyState()
                check(history.entries == 1L && history.undo == 1L)
                check(history.retainedBytes < logical.toByteArray().size + 250_000L)
                val captured = document.captureSnapshot(1)
                document.replace(1, Utf16Range(7, 7), "typed")
                val afterTyping = document.historyState()
                check(afterTyping.retainedBytes < history.retainedBytes + 200_000) {
                    "typing duplicated the large insertion in native history"
                }
                document.restoreHistory(2, 2, undo = true)
                val restored = document.restoreHistory(3, 1, undo = true)
                check(restored.revision == 4L && restored.serializedByteLength == source.size.toLong())
                document.captureSnapshot(4).use {
                    check(readSharedTextSnapshot(it, source.size.toLong()).toByteArray().contentEquals(source))
                }
                val redone = document.restoreHistory(4, 1, undo = false)
                check(redone.byteLength == metrics.byteLength)
                check(redone.serializedByteLength == metrics.serializedByteLength)
                val expected = "\uFEFFbefore\r\n" + logical.replace("\n", "\r\n") + "after\rtail\n"
                captured.use { check(snapshotDigest(it, metrics.serializedByteLength).contentEquals(
                    MessageDigest.getInstance("SHA-256").digest(expected.toByteArray()))) }

                // Cancellation must release private chunks and preserve the redo branch.
                val priorHistory = document.historyState()
                var checks = 0
                val cancelled = runCatching {
                    document.replaceContent(5, Utf16Range(0, 0), DocumentInsertion(raw)) {
                        if (++checks == 20) throw CancellationException("test cancellation")
                    }
                }.exceptionOrNull()
                check(cancelled is CancellationException)
                check(document.historyState() == priorHistory)
                check(document.replaceContent(5, Utf16Range(0, 0), DocumentInsertion("!")).revision == 6L)
                check(document.historyState().redo == 0L)
                check(runCatching { document.restoreHistory(5, 1, true) }.exceptionOrNull() is StaleDocumentRevisionException)
                document.clearHistory()
                check(document.historyState().entries == 0L && document.historyState().retainedBytes == 0L)
            }
        }
    }
}

/** Reaches the actual native memory boundary using one reusable 64 KiB buffer. */
internal fun Instrumentation.verifyNativeInsertionMemory() {
    val handle = NativeDocument.createEmpty()
    try {
        NativeDocument.replace(handle, 0, 0, 0, "seed")
        NativeDocument.restoreHistory(handle, 1, 1, true)
        val history = NativeDocument.historyState(handle)
        val chunk = ByteArray(64 * 1024) { 'x'.code.toByte() }
        NativeDocument.beginInsertion(handle, 2, 0, 0)
        var sampledPeak = Debug.getNativeHeapAllocatedSize()
        val failure = runCatching {
            repeat(1024) {
                NativeDocument.appendInsertion(handle, 2, chunk)
                if (it % 16 == 0) sampledPeak = maxOf(sampledPeak, Debug.getNativeHeapAllocatedSize())
            }
            NativeDocument.finishInsertion(handle, 2)
        }.exceptionOrNull()
        check(failure?.message?.contains("native history memory limit") == true)
        check(NativeDocument.historyState(handle).contentEquals(history))
        NativeDocument.cancelInsertion(handle)
        val restored = DocumentMetricsPacketDecoder.decode(NativeDocument.restoreHistory(handle, 2, 1, false))
        check(restored.utf16Length == 4L && restored.revision == 3L)
        Log.i("NativeHistoryVerification", "64 MiB insertion rejected atomically; sampled native heap peak=$sampledPeak")
    } finally { NativeDocument.close(handle) }
}

/** Streams the large snapshot through fixed-size buffers, independently of clipboard limits. */
private suspend fun snapshotDigest(snapshot: EditorDocumentSnapshot, bytes: Long): ByteArray = coroutineScope {
    ReliableSnapshotPipe.create(30_000).use { pipe ->
        ParcelFileDescriptor.AutoCloseInputStream(pipe.takeReader()).use { input ->
            val producer = async(Dispatchers.IO) { pipe.writeSnapshot(snapshot) }
            try {
                val digest = withContext(Dispatchers.IO) {
                    val result = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(8192)
                    var count = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        result.update(buffer, 0, read)
                        count += read
                        check(count <= bytes)
                    }
                    check(count == bytes)
                    result.digest()
                }
                check(producer.await() == bytes)
                digest
            } finally { pipe.failWriter(); producer.cancel() }
        }
    }
}
