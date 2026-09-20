/* Verifies exact transient share payloads and rejected producer outcomes. */
package dev.soupslurpr.beautyxt.document

import android.os.ParcelFileDescriptor
import dev.soupslurpr.beautyxt.sharing.DocumentShareException
import dev.soupslurpr.beautyxt.sharing.MAX_SHARED_TEXT_UTF8_BYTES
import dev.soupslurpr.beautyxt.sharing.readSharedTextSnapshot
import java.io.IOException
import kotlinx.coroutines.runBlocking

/** Verifies sharing preserves exact Unicode and rejects incomplete or failed snapshots. */
internal fun verifySharedTextSnapshots() = runBlocking {
    for (text in listOf("", "Hello 😀\n世界\n", "x".repeat(MAX_SHARED_TEXT_UTF8_BYTES.toInt()))) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        sharedSnapshot(bytes).use { snapshot ->
            check(readSharedTextSnapshot(snapshot, bytes.size.toLong()) == text) {
                "shared snapshot changed its exact text"
            }
        }
    }
    for ((bytes, expectedBytes) in listOf(
        byteArrayOf(1) to 2L,
        byteArrayOf(1, 2) to 1L,
        byteArrayOf(0xc0.toByte()) to 1L
    )) {
        sharedSnapshot(bytes).use { snapshot ->
            val failure = runCatching { readSharedTextSnapshot(snapshot, expectedBytes) }
                .exceptionOrNull()
            check(failure is DocumentShareException) {
                "shared snapshot accepted invalid content"
            }
        }
    }
    sharedSnapshot(byteArrayOf(1), failAfterWrite = true).use { snapshot ->
        val failure = runCatching { readSharedTextSnapshot(snapshot, 1L) }.exceptionOrNull()
        check(failure is DocumentShareException) {
            "shared snapshot accepted a failed producer"
        }
    }
}

/** Creates a descriptor-writing snapshot with an optional terminal producer failure. */
private fun sharedSnapshot(
    bytes: ByteArray,
    failAfterWrite: Boolean = false
): EditorDocumentSnapshot = object : EditorDocumentSnapshot {
    override fun writeSnapshot(
        outputRawFileDescriptor: Int,
        cancellationRawFileDescriptor: Int,
        timeoutMillis: Long
    ): Long {
        val output = ParcelFileDescriptor.fromFd(outputRawFileDescriptor)
        ParcelFileDescriptor.AutoCloseOutputStream(output).use { stream -> stream.write(bytes) }
        if (failAfterWrite) throw IOException("synthetic snapshot failure")
        return bytes.size.toLong()
    }

    override fun close() = Unit
}
