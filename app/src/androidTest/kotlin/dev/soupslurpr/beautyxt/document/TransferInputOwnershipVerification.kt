/* Verifies anonymous transfer input ownership across coroutine cancellation. */
package dev.soupslurpr.beautyxt.document

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import dev.soupslurpr.beautyxt.ipc.SealedInput
import dev.soupslurpr.beautyxt.transfer.client.AnonymousTransferInput
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope

/** Releases sealed inputs after normal use and cancellation during preparation. */
internal fun verifyTransferInputOwnership() = runBlocking {
    for (cancelProduction in listOf(false, true)) {
        var identity: StructStat? = null
        supervisorScope {
            val operation = async(Dispatchers.IO) {
                val producerContext = currentCoroutineContext()
                val snapshot = object : EditorDocumentSnapshot {
                    override fun writeSnapshot(
                        outputRawFileDescriptor: Int,
                        cancellationRawFileDescriptor: Int,
                        timeoutMillis: Long
                    ): Long {
                        ParcelFileDescriptor.fromFd(outputRawFileDescriptor).use { descriptor ->
                            identity = Os.fstat(descriptor.fileDescriptor)
                        }
                        if (cancelProduction) producerContext.cancel()
                        return 0L
                    }

                    override fun close() = Unit
                }
                AnonymousTransferInput.fromSnapshot(
                    snapshot = snapshot,
                    expectedBytes = 0L,
                    maxBytes = 1L
                ).use { input ->
                    currentCoroutineContext().ensureActive()
                    input.takeReader().use { descriptor ->
                        check(Os.fstat(descriptor.fileDescriptor).st_size == 0L) {
                            "empty transfer input changed size"
                        }
                    }
                }
            }
            val failure = runCatching { operation.await() }.exceptionOrNull()
            if (cancelProduction) {
                check(failure is CancellationException) {
                    "transfer preparation did not preserve cancellation"
                }
            } else {
                check(failure == null) { "normal transfer preparation failed" }
            }
        }
        requireAnonymousFileReleased(checkNotNull(identity))
    }
    verifySealedStreamOwnership()
}

/** Checks generated input seals, bounded output, and cleanup on preparation failure. */
private fun verifySealedStreamOwnership() {
    var identity: StructStat? = null
    SealedInput.fromStream(3L) { output -> output.write(byteArrayOf(1, 2, 3)) }.use { input ->
        check(input.byteCount == 3L) { "sealed input byte count changed" }
        input.takeReader().use { descriptor ->
            identity = Os.fstat(descriptor.fileDescriptor)
            val buffer = ByteArray(3)
            check(Os.read(descriptor.fileDescriptor, buffer, 0, buffer.size) == buffer.size)
            check(buffer.contentEquals(byteArrayOf(1, 2, 3))) { "sealed input bytes changed" }
            val failure = runCatching {
                Os.write(descriptor.fileDescriptor, buffer, 0, buffer.size)
            }.exceptionOrNull()
            check(failure is ErrnoException && failure.errno == OsConstants.EPERM) {
                "sealed input remained writable"
            }
        }
    }
    requireAnonymousFileReleased(checkNotNull(identity))
    val failedSnapshot = object : EditorDocumentSnapshot {
        override fun writeSnapshot(
            outputRawFileDescriptor: Int,
            cancellationRawFileDescriptor: Int,
            timeoutMillis: Long
        ): Long {
            ParcelFileDescriptor.fromFd(outputRawFileDescriptor).use { descriptor ->
                identity = Os.fstat(descriptor.fileDescriptor)
            }
            throw IOException("synthetic producer failure")
        }

        override fun close() = Unit
    }
    val preparationFailure = runCatching {
        SealedInput.fromSnapshot(failedSnapshot, expectedBytes = 0L, maxBytes = 1L).close()
    }.exceptionOrNull()
    check(preparationFailure is IOException) { "producer failure did not propagate" }
    requireAnonymousFileReleased(checkNotNull(identity))
    for (exceedLimit in listOf(false, true)) {
        val failure = runCatching {
            SealedInput.fromStream(0L) { output ->
                if (exceedLimit) output.write(1)
                throw IOException("synthetic preparation failure")
            }.close()
        }.exceptionOrNull()
        check(failure is IOException) { "failed preparation did not report its failure" }
    }
}

/** Checks that no process descriptor still references the captured anonymous file. */
private fun requireAnonymousFileReleased(identity: StructStat) {
    val descriptors = checkNotNull(File("/proc/self/fd").list()) {
        "process descriptors are unavailable"
    }
    for (descriptor in descriptors) {
        val rawDescriptor = descriptor.toIntOrNull() ?: continue
        val status = try {
            ParcelFileDescriptor.fromFd(rawDescriptor).use { duplicate ->
                Os.fstat(duplicate.fileDescriptor)
            }
        } catch (failure: IOException) {
            if ((failure.cause as? ErrnoException)?.errno == OsConstants.EBADF) {
                continue
            }
            throw failure
        } catch (failure: ErrnoException) {
            if (failure.errno == OsConstants.EACCES) {
                // Ignore unrelated platform descriptors with restricted metadata.
                continue
            }
            throw failure
        }
        check(status.st_dev != identity.st_dev || status.st_ino != identity.st_ino) {
            "transfer preparation leaked its anonymous input"
        }
    }
}
