package dev.soupslurpr.beautyxt.illustration

import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.Os
import android.system.OsConstants

private const val MFD_ALLOW_SEALING = 0x0002
private const val ADD_SEALS = 1033
private const val GET_SEALS = 1034
private const val SEAL_SEAL = 0x0001
private const val SEAL_SHRINK = 0x0002
private const val SEAL_GROW = 0x0004
private const val SEAL_WRITE = 0x0008

/** Owns a fixed-capacity anonymous packet. The worker cannot grow or shrink its storage. */
internal class IllustrationPacketBuffer private constructor(
    private val descriptor: ParcelFileDescriptor,
    private val device: Long,
    private val inode: Long
) : AutoCloseable {
    fun duplicateWriter(): ParcelFileDescriptor =
        ParcelFileDescriptor.dup(descriptor.fileDescriptor)

    /** Seals all writes before copying and decoding worker-controlled bytes. */
    fun readCompleted(bytes: Int): ByteArray {
        require(bytes in IllustrationLimits.HEADER_BYTES..IllustrationLimits.MAX_PACKET_BYTES)
        validate()
        val required = SEAL_SEAL or SEAL_SHRINK or SEAL_GROW or SEAL_WRITE
        Os.fcntlInt(descriptor.fileDescriptor, ADD_SEALS, required)
        check(Os.fcntlInt(descriptor.fileDescriptor, GET_SEALS, 0) and required == required)
        val packet = ByteArray(bytes)
        var offset = 0
        while (offset < bytes) {
            val count = Os.pread(
                descriptor.fileDescriptor,
                packet,
                offset,
                bytes - offset,
                offset.toLong()
            )
            check(count > 0) { "Illustration packet ended early" }
            offset += count
        }
        validate()
        return packet
    }

    private fun validate() {
        val stat = Os.fstat(descriptor.fileDescriptor)
        check(OsConstants.S_ISREG(stat.st_mode) && stat.st_nlink == 0L)
        check(stat.st_uid == Process.myUid() && stat.st_dev == device && stat.st_ino == inode)
        check(stat.st_size == IllustrationLimits.MAX_PACKET_BYTES.toLong())
    }

    override fun close() = descriptor.close()

    companion object {
        fun create(): IllustrationPacketBuffer {
            val raw = Os.memfd_create(
                "beautyxt-illustration",
                OsConstants.MFD_CLOEXEC or MFD_ALLOW_SEALING
            )
            try {
                Os.ftruncate(raw, IllustrationLimits.MAX_PACKET_BYTES.toLong())
                Os.fcntlInt(raw, ADD_SEALS, SEAL_GROW or SEAL_SHRINK)
                val stat = Os.fstat(raw)
                return IllustrationPacketBuffer(
                    ParcelFileDescriptor.dup(raw),
                    stat.st_dev,
                    stat.st_ino
                )
            } finally {
                Os.close(raw)
            }
        }
    }
}
