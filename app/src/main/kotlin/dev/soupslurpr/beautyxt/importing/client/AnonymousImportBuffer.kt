package dev.soupslurpr.beautyxt.importing.client

import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.FileDescriptor
import java.io.IOException

private const val BUFFER_NAME = "beautyxt-import"
private const val MFD_ALLOW_SEALING = 0x0002

/** Owns one pathname-free, seekable import buffer and its descriptors. */
internal class AnonymousImportBuffer private constructor(
    private val device: Long,
    private val inode: Long,
    private var reader: ParcelFileDescriptor?,
    private var writer: ParcelFileDescriptor?
) : AutoCloseable {
    /** Transfers the buffer writer for one synchronous Binder call. */
    @Synchronized
    fun takeWriter(): ParcelFileDescriptor =
        checkNotNull(writer) { "anonymous import writer is unavailable" }.also {
            writer = null
        }

    /** Validates and rewinds the completed byte-identical source buffer. */
    @Synchronized
    fun prepareReader(importedBytes: Long): Int {
        require(importedBytes >= 0L) { "imported byte count must be nonnegative" }
        val descriptor = checkNotNull(reader) { "anonymous import buffer is closed" }
        val status = Os.fstat(descriptor.fileDescriptor)
        requireAnonymousStatus(status.st_mode, status.st_nlink, status.st_uid)
        check(status.st_dev == device && status.st_ino == inode) {
            "anonymous import buffer identity changed"
        }
        check(status.st_size == importedBytes) {
            "anonymous import buffer size does not match the completed import"
        }
        Os.lseek(descriptor.fileDescriptor, 0L, OsConstants.SEEK_SET)
        return descriptor.fd
    }

    /** Closes every locally owned descriptor exactly once. */
    @Synchronized
    override fun close() {
        var failure: IOException? = null
        listOf(writer, reader).forEach { descriptor ->
            try {
                descriptor?.close()
            } catch (closeFailure: IOException) {
                if (failure == null) {
                    failure = closeFailure
                } else {
                    failure.addSuppressed(closeFailure)
                }
            }
        }
        writer = null
        reader = null
        failure?.let { throw it }
    }

    companion object {
        /** Creates one empty anonymous buffer with no filesystem pathname. */
        fun create(): AnonymousImportBuffer {
            var rawDescriptor: FileDescriptor? = null
            var reader: ParcelFileDescriptor? = null
            var writer: ParcelFileDescriptor? = null
            try {
                rawDescriptor =
                    Os.memfd_create(
                        BUFFER_NAME,
                        OsConstants.MFD_CLOEXEC or MFD_ALLOW_SEALING
                    )
                val status = Os.fstat(rawDescriptor)
                requireAnonymousStatus(status.st_mode, status.st_nlink, status.st_uid)
                check(status.st_size == 0L) { "anonymous import buffer is not empty" }

                reader = ParcelFileDescriptor.dup(rawDescriptor)
                writer = ParcelFileDescriptor.dup(rawDescriptor)
                return AnonymousImportBuffer(
                    device = status.st_dev,
                    inode = status.st_ino,
                    reader = reader,
                    writer = writer
                )
            } catch (failure: Exception) {
                closeQuietly(reader)
                closeQuietly(writer)
                throw DocumentImportException(
                    DocumentImportFailure.BUFFER_UNAVAILABLE,
                    failure
                )
            } finally {
                rawDescriptor?.let(::closeRawDescriptor)
            }
        }

        /** Requires an app-owned, unlinked regular descriptor. */
        private fun requireAnonymousStatus(mode: Int, linkCount: Long, ownerUid: Int) {
            check(OsConstants.S_ISREG(mode)) {
                "anonymous import buffer is not a regular file"
            }
            check(linkCount == 0L) { "anonymous import buffer has a filesystem link" }
            check(ownerUid == Process.myUid()) {
                "anonymous import buffer has an unexpected owner"
            }
        }

        /** Closes one raw descriptor without masking buffer creation failure. */
        private fun closeRawDescriptor(descriptor: FileDescriptor) {
            try {
                Os.close(descriptor)
            } catch (_: ErrnoException) {
                // Ownership of every duplicated descriptor remains explicit.
            }
        }

        /** Closes one parcel descriptor without masking buffer creation failure. */
        private fun closeQuietly(descriptor: ParcelFileDescriptor?) {
            try {
                descriptor?.close()
            } catch (_: IOException) {
                // Buffer creation already failed and preserves the primary error.
            }
        }
    }
}
