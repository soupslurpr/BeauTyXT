/* Reads transient share text through cancellation-safe snapshot capabilities. */
package dev.soupslurpr.beautyxt.sharing

import android.os.ParcelFileDescriptor
import dev.soupslurpr.beautyxt.document.EditorDocumentSnapshot
import dev.soupslurpr.beautyxt.ipc.ReliableSnapshotPipe
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

private const val SHARE_SNAPSHOT_TIMEOUT_MILLIS = 30_000L

/** Reports that one bounded transient text share could not be prepared. */
internal class DocumentShareException(cause: Throwable? = null) :
    IOException("document share unavailable", cause)

/** Reads one small immutable revision through an anonymous bounded pipe. */
internal suspend fun readSharedTextSnapshot(
    snapshot: EditorDocumentSnapshot,
    expectedBytes: Long
): String {
    require(expectedBytes in 0L..MAX_SHARED_TEXT_UTF8_BYTES) {
        "shared text byte count exceeds its limit"
    }
    if (expectedBytes == 0L) {
        return ""
    }
    return coroutineScope {
        val pipe = try {
            ReliableSnapshotPipe.create(SHARE_SNAPSHOT_TIMEOUT_MILLIS)
        } catch (failure: Exception) {
            throw DocumentShareException(failure)
        }
        pipe.use {
            ParcelFileDescriptor.AutoCloseInputStream(pipe.takeReader()).use { input ->
                val producer = async(Dispatchers.IO) {
                    try {
                        pipe.writeSnapshot(snapshot)
                    } catch (failure: Exception) {
                        throw DocumentShareException(failure)
                    } catch (failure: LinkageError) {
                        throw DocumentShareException(failure)
                    }
                }
                try {
                    val bytes = withContext(Dispatchers.IO) {
                        readExactBytes(input, Math.toIntExact(expectedBytes))
                    }
                    check(producer.await() == expectedBytes) {
                        "shared snapshot byte count changed"
                    }
                    bytes.decodeToString(throwOnInvalidSequence = true)
                } catch (cancellation: CancellationException) {
                    pipe.failWriter()
                    producer.cancel()
                    throw cancellation
                } catch (failure: Exception) {
                    pipe.failWriter()
                    producer.cancel()
                    throw DocumentShareException(failure)
                } catch (failure: LinkageError) {
                    pipe.failWriter()
                    producer.cancel()
                    throw DocumentShareException(failure)
                }
            }
        }
    }
}

/** Reads one exact pipe payload and rejects both truncation and trailing bytes. */
private fun readExactBytes(input: InputStream, expectedBytes: Int): ByteArray {
    require(expectedBytes > 0) { "expected shared text byte count must be positive" }
    val bytes = ByteArray(expectedBytes)
    var totalBytes = 0
    while (totalBytes < bytes.size) {
        val readBytes = input.read(bytes, totalBytes, bytes.size - totalBytes)
        if (readBytes < 0) {
            throw IOException("shared snapshot ended early")
        }
        totalBytes = Math.addExact(totalBytes, readBytes)
    }
    if (input.read() >= 0) {
        throw IOException("shared snapshot exceeded its expected size")
    }
    return bytes
}
