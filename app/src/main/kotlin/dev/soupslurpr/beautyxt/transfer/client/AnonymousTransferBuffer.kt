/* Prepares bounded transfer buffers and maps their domain failures. */
package dev.soupslurpr.beautyxt.transfer.client

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import dev.soupslurpr.beautyxt.document.EditorDocumentSnapshot
import dev.soupslurpr.beautyxt.ipc.SealedInput
import dev.soupslurpr.beautyxt.ipc.closeRawDescriptor
import dev.soupslurpr.beautyxt.ipc.createAnonymousDescriptor
import dev.soupslurpr.beautyxt.ipc.sealAnonymousDescriptor
import dev.soupslurpr.beautyxt.ipc.validateAnonymousDescriptor
import dev.soupslurpr.beautyxt.transfer.TransferProtocol
import java.io.FileDescriptor
import java.io.IOException

private const val OUTPUT_BUFFER_NAME = "beautyxt-transfer-output"

/** Prepares sealed transfer inputs without losing synchronous resource ownership. */
internal object AnonymousTransferInput {
    /** Copies one bounded byte array and reports preparation failure in transfer terms. */
    fun fromBytes(bytes: ByteArray, maxBytes: Long): SealedInput {
        require(bytes.size.toLong() <= maxBytes) { "transfer input exceeds its limit" }
        return prepare { SealedInput.fromBytes(bytes, maxBytes) }
    }

    /** Copies one exact snapshot and reports preparation failure in transfer terms. */
    fun fromSnapshot(
        snapshot: EditorDocumentSnapshot,
        expectedBytes: Long,
        maxBytes: Long
    ): SealedInput {
        require(expectedBytes in TransferProtocol.MIN_INPUT_BYTES..maxBytes) {
            "transfer snapshot exceeds its limit"
        }
        return prepare { SealedInput.fromSnapshot(snapshot, expectedBytes, maxBytes) }
    }

    /** Maps only preparation failures without changing the successful input owner. */
    private inline fun prepare(create: () -> SealedInput): SealedInput = try {
        create()
    } catch (failure: TransferException) {
        throw failure
    } catch (failure: Exception) {
        throw TransferException(TransferFailure.SnapshotFailed, failure)
    }
}

/** Owns one pathname-free output buffer and its exact read capability. */
internal class AnonymousTransferOutput private constructor(
    private val device: Long,
    private val inode: Long,
    private var reader: ParcelFileDescriptor?,
    private var writer: ParcelFileDescriptor?
) : AutoCloseable {
    /** Transfers the output writer for one synchronous Binder call. */
    @Synchronized
    fun takeWriter(): ParcelFileDescriptor =
        checkNotNull(writer) { "anonymous transfer writer is unavailable" }.also {
            writer = null
        }

    /** Seals, validates, and reads one exact completed output. */
    @Synchronized
    fun readCompleted(expectedBytes: Long): ByteArray {
        require(
            expectedBytes in
                TransferProtocol.MIN_OUTPUT_BYTES..TransferProtocol.MAX_OUTPUT_BYTES
        ) {
            "transfer output byte count exceeds its limits"
        }
        val descriptor = checkNotNull(reader) { "anonymous transfer output is closed" }
        validateAnonymousDescriptor(
            descriptor = descriptor,
            expectedDevice = device,
            expectedInode = inode,
            expectedBytes = expectedBytes,
            requireSeals = false
        )
        sealAnonymousDescriptor(descriptor)
        validateAnonymousDescriptor(
            descriptor = descriptor,
            expectedDevice = device,
            expectedInode = inode,
            expectedBytes = expectedBytes,
            requireSeals = true
        )
        Os.lseek(descriptor.fileDescriptor, 0L, OsConstants.SEEK_SET)
        val bytes = ByteArray(Math.toIntExact(expectedBytes))
        val duplicate = ParcelFileDescriptor.dup(descriptor.fileDescriptor)
        ParcelFileDescriptor.AutoCloseInputStream(duplicate).use { input ->
            var offset = 0
            while (offset < bytes.size) {
                val bytesRead = input.read(bytes, offset, bytes.size - offset)
                if (bytesRead < 0) {
                    throw IOException("transfer output ended before its declared size")
                }
                offset = Math.addExact(offset, bytesRead)
            }
            if (input.read() >= 0) {
                throw IOException("transfer output exceeds its declared size")
            }
        }
        return bytes
    }

    /** Closes every locally owned output capability exactly once. */
    @Synchronized
    override fun close() {
        closeQuietly(writer)
        closeQuietly(reader)
        writer = null
        reader = null
    }

    companion object {
        /** Creates one empty anonymous output buffer with sealing enabled. */
        fun create(): AnonymousTransferOutput {
            var rawDescriptor: FileDescriptor? = null
            var reader: ParcelFileDescriptor? = null
            var writer: ParcelFileDescriptor? = null
            try {
                rawDescriptor = createAnonymousDescriptor(OUTPUT_BUFFER_NAME)
                val status = Os.fstat(rawDescriptor)
                reader = ParcelFileDescriptor.dup(rawDescriptor)
                writer = ParcelFileDescriptor.dup(rawDescriptor)
                return AnonymousTransferOutput(
                    device = status.st_dev,
                    inode = status.st_ino,
                    reader = reader,
                    writer = writer
                ).also {
                    reader = null
                    writer = null
                }
            } catch (failure: Exception) {
                throw TransferException(TransferFailure.ProcessingFailed, failure)
            } finally {
                closeQuietly(reader)
                closeQuietly(writer)
                rawDescriptor?.let(::closeRawDescriptor)
            }
        }
    }
}

/** Closes one parcel descriptor without masking the primary outcome. */
private fun closeQuietly(descriptor: ParcelFileDescriptor?) {
    try {
        descriptor?.close()
    } catch (_: IOException) {
        // Descriptor ownership ends even when close reports an error.
    }
}
