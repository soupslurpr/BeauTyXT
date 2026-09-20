/* Validates pathname-free memory files shared by isolated operations. */
package dev.soupslurpr.beautyxt.ipc

import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.FileDescriptor

private const val MFD_ALLOW_SEALING = 0x0002
private const val F_ADD_SEALS = 1033
private const val F_GET_SEALS = 1034
private const val F_SEAL_SEAL = 0x0001
private const val F_SEAL_SHRINK = 0x0002
private const val F_SEAL_GROW = 0x0004
private const val F_SEAL_WRITE = 0x0008
private const val REQUIRED_SEALS =
    F_SEAL_SEAL or F_SEAL_SHRINK or F_SEAL_GROW or F_SEAL_WRITE

/** Creates one verified empty anonymous file descriptor. */
internal fun createAnonymousDescriptor(name: String): FileDescriptor {
    val descriptor = Os.memfd_create(name, OsConstants.MFD_CLOEXEC or MFD_ALLOW_SEALING)
    try {
        val status = Os.fstat(descriptor)
        check(OsConstants.S_ISREG(status.st_mode)) { "anonymous buffer is not a regular file" }
        check(status.st_nlink == 0L) { "anonymous buffer has a filesystem link" }
        check(status.st_uid == Process.myUid()) { "anonymous buffer has an unexpected owner" }
        check(status.st_size == 0L) { "anonymous buffer is not empty" }
        return descriptor
    } catch (failure: Throwable) {
        closeRawDescriptor(descriptor)
        throw failure
    }
}

/** Prevents further writes, resizing, and seal changes on one anonymous file. */
internal fun sealAnonymousDescriptor(descriptor: ParcelFileDescriptor) {
    Os.fcntlInt(descriptor.fileDescriptor, F_ADD_SEALS, REQUIRED_SEALS)
}

/** Requires one exact app-owned anonymous descriptor and optional seals. */
internal fun validateAnonymousDescriptor(
    descriptor: ParcelFileDescriptor,
    expectedDevice: Long,
    expectedInode: Long,
    expectedBytes: Long,
    requireSeals: Boolean
) {
    val status = Os.fstat(descriptor.fileDescriptor)
    check(OsConstants.S_ISREG(status.st_mode)) { "anonymous buffer is not a regular file" }
    check(status.st_nlink == 0L) { "anonymous buffer has a filesystem link" }
    check(status.st_uid == Process.myUid()) { "anonymous buffer has an unexpected owner" }
    check(status.st_dev == expectedDevice && status.st_ino == expectedInode) {
        "anonymous buffer identity changed"
    }
    check(status.st_size == expectedBytes) { "anonymous buffer size is inconsistent" }
    if (requireSeals) {
        val seals = Os.fcntlInt(descriptor.fileDescriptor, F_GET_SEALS, 0)
        check(seals and REQUIRED_SEALS == REQUIRED_SEALS) {
            "anonymous buffer lacks required seals"
        }
    }
}

/** Closes one raw descriptor without masking the primary outcome. */
internal fun closeRawDescriptor(descriptor: FileDescriptor) {
    try {
        Os.close(descriptor)
    } catch (_: ErrnoException) {
        // Preserve the primary operation outcome.
    }
}
