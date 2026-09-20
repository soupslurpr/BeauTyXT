package dev.soupslurpr.beautyxt.markdown.client

import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import dev.soupslurpr.beautyxt.markdown.MarkdownProtocol
import java.io.FileDescriptor
import java.io.IOException

private const val BUFFER_NAME = "beautyxt-markdown"
private const val MFD_ALLOW_SEALING = 0x0002
private const val F_ADD_SEALS = 1033
private const val F_GET_SEALS = 1034
private const val F_SEAL_SEAL = 0x0001
private const val F_SEAL_SHRINK = 0x0002
private const val F_SEAL_GROW = 0x0004
private const val F_SEAL_WRITE = 0x0008
private const val REQUIRED_SEALS =
    F_SEAL_SEAL or F_SEAL_SHRINK or F_SEAL_GROW or F_SEAL_WRITE

/** Owns one pathname-free Markdown packet buffer and its capabilities. */
internal class AnonymousMarkdownPacket private constructor(
    private val device: Long,
    private val inode: Long,
    private var reader: ParcelFileDescriptor?,
    private var writer: ParcelFileDescriptor?
) : AutoCloseable {
    /** Transfers the packet writer for one synchronous Binder call. */
    @Synchronized
    fun takeWriter(): ParcelFileDescriptor =
        checkNotNull(writer) { "anonymous Markdown writer is unavailable" }.also {
            writer = null
        }

    /** Seals, validates, and reads one exact completed packet. */
    @Synchronized
    fun readCompletedPacket(expectedBytes: Long): ByteArray {
        require(
            expectedBytes in
                MarkdownProtocol.MIN_PACKET_BYTES..MarkdownProtocol.MAX_PACKET_BYTES
        ) {
            "Markdown packet byte count exceeds its limits"
        }
        val descriptor =
            checkNotNull(reader) { "anonymous Markdown packet is closed" }
        validateDescriptor(descriptor, expectedBytes)
        Os.fcntlInt(descriptor.fileDescriptor, F_ADD_SEALS, REQUIRED_SEALS)
        val seals = Os.fcntlInt(descriptor.fileDescriptor, F_GET_SEALS, 0)
        check(seals and REQUIRED_SEALS == REQUIRED_SEALS) {
            "anonymous Markdown packet lacks required seals"
        }
        validateDescriptor(descriptor, expectedBytes)
        Os.lseek(descriptor.fileDescriptor, 0L, OsConstants.SEEK_SET)
        val packet = ByteArray(expectedBytes.toInt())
        val duplicate = ParcelFileDescriptor.dup(descriptor.fileDescriptor)
        ParcelFileDescriptor.AutoCloseInputStream(duplicate).use { input ->
            var offset = 0
            while (offset < packet.size) {
                val bytesRead = input.read(packet, offset, packet.size - offset)
                if (bytesRead < 0) {
                    throw IOException("Markdown packet ended before its declared size")
                }
                offset += bytesRead
            }
            if (input.read() != -1) {
                throw IOException("Markdown packet exceeds its declared size")
            }
        }
        validateDescriptor(descriptor, expectedBytes)
        return packet
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

    /** Requires the original app-owned anonymous descriptor and exact size. */
    private fun validateDescriptor(descriptor: ParcelFileDescriptor, expectedBytes: Long) {
        val status = Os.fstat(descriptor.fileDescriptor)
        check(OsConstants.S_ISREG(status.st_mode)) {
            "anonymous Markdown packet is not a regular file"
        }
        check(status.st_nlink == 0L) {
            "anonymous Markdown packet has a filesystem link"
        }
        check(status.st_uid == Process.myUid()) {
            "anonymous Markdown packet has an unexpected owner"
        }
        check(status.st_dev == device && status.st_ino == inode) {
            "anonymous Markdown packet identity changed"
        }
        check(status.st_size == expectedBytes) {
            "anonymous Markdown packet size is inconsistent"
        }
    }

    companion object {
        /** Creates one empty anonymous packet buffer with sealing enabled. */
        fun create(): AnonymousMarkdownPacket {
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
                check(OsConstants.S_ISREG(status.st_mode)) {
                    "anonymous Markdown packet is not a regular file"
                }
                check(status.st_nlink == 0L) {
                    "anonymous Markdown packet has a filesystem link"
                }
                check(status.st_uid == Process.myUid()) {
                    "anonymous Markdown packet has an unexpected owner"
                }
                check(status.st_size == 0L) {
                    "anonymous Markdown packet is not empty"
                }
                reader = ParcelFileDescriptor.dup(rawDescriptor)
                writer = ParcelFileDescriptor.dup(rawDescriptor)
                return AnonymousMarkdownPacket(
                    device = status.st_dev,
                    inode = status.st_ino,
                    reader = reader,
                    writer = writer
                )
            } catch (failure: Exception) {
                closeQuietly(reader)
                closeQuietly(writer)
                throw MarkdownRenderException(
                    MarkdownRenderFailure.RenderFailed,
                    failure
                )
            } finally {
                rawDescriptor?.let(::closeRawDescriptor)
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
                // Buffer creation already preserves the primary failure.
            }
        }
    }
}
