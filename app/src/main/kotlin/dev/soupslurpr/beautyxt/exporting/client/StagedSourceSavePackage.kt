package dev.soupslurpr.beautyxt.exporting.client

import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import dev.soupslurpr.beautyxt.document.EditorDocumentSnapshot
import dev.soupslurpr.beautyxt.document.SourceSavePackageMetrics
import dev.soupslurpr.beautyxt.exporting.ExportProtocol
import java.io.FileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine

private const val STAGED_PACKAGE_NAME = "beautyxt-source-save"
private const val STAGED_PACKAGE_TIMEOUT_MILLIS = 300_000L
private const val SOURCE_SAVE_PACKAGE_HEADER_BYTES = 64L
private const val SOURCE_SAVE_PACKAGE_RECORD_BYTES = 24L
private const val MAX_SOURCE_SAVE_PACKAGE_RECORDS = 65_536L
private const val MAX_SOURCE_SAVE_PACKAGE_OVERHEAD_BYTES =
    SOURCE_SAVE_PACKAGE_HEADER_BYTES +
        MAX_SOURCE_SAVE_PACKAGE_RECORDS * SOURCE_SAVE_PACKAGE_RECORD_BYTES

// Android's public constants omit the Linux memfd sealing commands and flags.
private const val MFD_ALLOW_SEALING = 0x0002
private const val F_ADD_SEALS = 1033
private const val F_GET_SEALS = 1034
private const val F_SEAL_SEAL = 0x0001
private const val F_SEAL_SHRINK = 0x0002
private const val F_SEAL_GROW = 0x0004
private const val F_SEAL_WRITE = 0x0008
private const val REQUIRED_SEALS =
    F_SEAL_SEAL or F_SEAL_SHRINK or F_SEAL_GROW or F_SEAL_WRITE

/** Transfers one sealed source-save package and its optional immutable backing. */
internal data class StagedSourceSaveDescriptors(
    val packageDescriptor: ParcelFileDescriptor,
    val sourceBackingDescriptor: ParcelFileDescriptor?
)

/** Owns one verified, immutable, pathname-free source-save package. */
internal class StagedSourceSavePackage private constructor(
    val outputByteLength: Long,
    val packageByteLength: Long,
    val payloadByteLength: Long,
    val recordCount: Long,
    private val sourceBackingByteLength: Long?,
    private val packageIdentity: DescriptorIdentity,
    private var packageDescriptor: ParcelFileDescriptor?,
    private var sourceBackingIdentity: DescriptorIdentity? = null,
    private var sourceBackingDescriptor: ParcelFileDescriptor? = null
) : AutoCloseable {
    /** Transfers every validated package capability to one source-save operation. */
    @Synchronized
    fun takeDescriptors(): StagedSourceSaveDescriptors {
        val ownedPackage =
            checkNotNull(packageDescriptor) { "staged source-save package is unavailable" }
        validateReadyPackage(ownedPackage)
        val ownedBacking = sourceBackingDescriptor
        validateReadyBacking(ownedBacking)
        packageDescriptor = null
        sourceBackingDescriptor = null
        return StagedSourceSaveDescriptors(
            packageDescriptor = ownedPackage,
            sourceBackingDescriptor = ownedBacking
        )
    }

    /** Closes every unconsumed package capability exactly once. */
    @Synchronized
    override fun close() {
        val ownedPackage = packageDescriptor
        val ownedBacking = sourceBackingDescriptor
        packageDescriptor = null
        sourceBackingDescriptor = null
        closeDescriptors(ownedPackage, ownedBacking)
    }

    /** Completes and seals one exact native source-save package write. */
    private fun capture(
        snapshot: EditorDocumentSnapshot,
        cancellation: PackageCaptureCancellation
    ) {
        val ownedPackage =
            synchronized(this) {
                checkNotNull(packageDescriptor) {
                    "staged source-save package is unavailable"
                }
            }
        Os.ftruncate(ownedPackage.fileDescriptor, packageByteLength)
        validateDescriptor(
            descriptor = ownedPackage,
            identity = packageIdentity,
            expectedSize = packageByteLength
        )
        Os.fcntlInt(ownedPackage.fileDescriptor, F_ADD_SEALS, F_SEAL_GROW)
        requireSeals(ownedPackage, F_SEAL_GROW)
        Os.lseek(ownedPackage.fileDescriptor, 0L, OsConstants.SEEK_SET)

        val sourceBackingRawFileDescriptor =
            snapshot.writeSourceSavePackage(
                packageRawFileDescriptor = ownedPackage.fd,
                cancellationRawFileDescriptor = cancellation.readerRawFileDescriptor,
                timeoutMillis = STAGED_PACKAGE_TIMEOUT_MILLIS
            )
        var candidateBacking =
            if (sourceBackingRawFileDescriptor >= 0) {
                ParcelFileDescriptor.adoptFd(sourceBackingRawFileDescriptor)
            } else {
                null
            }
        try {
            check((candidateBacking != null) == (sourceBackingByteLength != null)) {
                "source-save backing descriptor does not match its metrics"
            }
            val candidateIdentity =
                candidateBacking?.let { descriptor ->
                    val expectedSize = checkNotNull(sourceBackingByteLength)
                    descriptorIdentity(descriptor, expectedSize).also {
                        requireSeals(descriptor, REQUIRED_SEALS)
                    }
                }

            validateDescriptor(
                descriptor = ownedPackage,
                identity = packageIdentity,
                expectedSize = packageByteLength
            )
            check(
                Os.lseek(
                    ownedPackage.fileDescriptor,
                    0L,
                    OsConstants.SEEK_CUR
                ) == packageByteLength
            ) {
                "source-save package ended at an unexpected offset"
            }
            Os.fcntlInt(ownedPackage.fileDescriptor, F_ADD_SEALS, REQUIRED_SEALS)
            requireSeals(ownedPackage, REQUIRED_SEALS)
            Os.lseek(ownedPackage.fileDescriptor, 0L, OsConstants.SEEK_SET)

            synchronized(this) {
                check(sourceBackingDescriptor == null) {
                    "source-save backing descriptor is already set"
                }
                sourceBackingIdentity = candidateIdentity
                sourceBackingDescriptor = candidateBacking
                candidateBacking = null
            }
            validateReadyPackage(ownedPackage)
            validateReadyBacking(sourceBackingDescriptor)
        } finally {
            closeParcelDescriptorQuietly(candidateBacking)
        }
    }

    /** Requires one unchanged, exact, sealed package descriptor. */
    private fun validateReadyPackage(ownedDescriptor: ParcelFileDescriptor) {
        validateDescriptor(
            descriptor = ownedDescriptor,
            identity = packageIdentity,
            expectedSize = packageByteLength
        )
        requireSeals(ownedDescriptor, REQUIRED_SEALS)
        Os.lseek(ownedDescriptor.fileDescriptor, 0L, OsConstants.SEEK_SET)
    }

    /** Requires the exact optional sealed source backing without moving its cursor. */
    private fun validateReadyBacking(ownedDescriptor: ParcelFileDescriptor?) {
        val expectedSize = sourceBackingByteLength
        check((ownedDescriptor != null) == (expectedSize != null)) {
            "source-save backing descriptor is unavailable"
        }
        if (ownedDescriptor == null) {
            return
        }
        validateDescriptor(
            descriptor = ownedDescriptor,
            identity = checkNotNull(sourceBackingIdentity),
            expectedSize = checkNotNull(expectedSize)
        )
        requireSeals(ownedDescriptor, REQUIRED_SEALS)
    }

    companion object {
        /** Captures and consumes one snapshot into a sealed compact package. */
        suspend fun capture(
            snapshot: EditorDocumentSnapshot,
            expectedBytes: Long
        ): StagedSourceSavePackage {
            require(expectedBytes >= ExportProtocol.MIN_BYTE_LIMIT) {
                "expected byte count must be nonnegative"
            }
            if (expectedBytes > ExportProtocol.MAX_BYTE_LIMIT) {
                throw DocumentExportException(DocumentExportFailure.TOO_LARGE)
            }

            val cancellation =
                try {
                    PackageCaptureCancellation.create()
                } catch (failure: Exception) {
                    closeSnapshotAfterFailure(snapshot, failure)
                    throw DocumentExportException(DocumentExportFailure.SNAPSHOT_FAILED, failure)
                }
            return suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation {
                    cancellation.cancel()
                }
                try {
                    Dispatchers.IO.dispatch(
                        continuation.context,
                        Runnable {
                            var stagedPackage: StagedSourceSavePackage? = null
                            var primaryFailure: Throwable? = null
                            try {
                                if (!continuation.isActive) {
                                    return@Runnable
                                }
                                val metrics = snapshot.prepareSourceSavePackage()
                                validateSourceSavePackageMetrics(metrics, expectedBytes)
                                stagedPackage = createEmpty(metrics)
                                stagedPackage.capture(snapshot, cancellation)
                                val completedPackage = checkNotNull(stagedPackage)
                                continuation.resume(completedPackage) { _, value, _ ->
                                    closeStagedPackageQuietly(value)
                                }
                                stagedPackage = null
                            } catch (failure: Exception) {
                                primaryFailure = failure
                                continuation.resumeWith(
                                    Result.failure(failure.asSnapshotFailure())
                                )
                            } catch (failure: LinkageError) {
                                primaryFailure = failure
                                continuation.resumeWith(
                                    Result.failure(
                                        DocumentExportException(
                                            DocumentExportFailure.SNAPSHOT_FAILED,
                                            failure
                                        )
                                    )
                                )
                            } finally {
                                closeCaptureResources(
                                    stagedPackage = stagedPackage,
                                    snapshot = snapshot,
                                    cancellation = cancellation,
                                    primaryFailure = primaryFailure
                                )
                            }
                        }
                    )
                } catch (failure: Exception) {
                    closeCaptureResources(
                        stagedPackage = null,
                        snapshot = snapshot,
                        cancellation = cancellation,
                        primaryFailure = failure
                    )
                    continuation.resumeWith(Result.failure(failure.asSnapshotFailure()))
                }
            }
        }

        /** Creates one empty anonymous package buffer with sealing enabled. */
        private fun createEmpty(metrics: SourceSavePackageMetrics): StagedSourceSavePackage {
            var rawDescriptor: FileDescriptor? = null
            var parcelDescriptor: ParcelFileDescriptor? = null
            try {
                rawDescriptor =
                    Os.memfd_create(
                        STAGED_PACKAGE_NAME,
                        OsConstants.MFD_CLOEXEC or MFD_ALLOW_SEALING
                    )
                val status = Os.fstat(rawDescriptor)
                requireAnonymousStatus(status.st_mode, status.st_nlink, status.st_uid)
                check(status.st_size == 0L) { "staged source-save package is not empty" }
                parcelDescriptor = ParcelFileDescriptor.dup(rawDescriptor)
                val ownedDescriptor = checkNotNull(parcelDescriptor)
                parcelDescriptor = null
                return StagedSourceSavePackage(
                    outputByteLength = metrics.outputByteLength,
                    packageByteLength = metrics.packageByteLength,
                    payloadByteLength = metrics.payloadByteLength,
                    recordCount = metrics.recordCount,
                    sourceBackingByteLength =
                        metrics.sourceBackingByteLength.takeIf {
                            metrics.hasSourceBacking
                        },
                    packageIdentity =
                        DescriptorIdentity(
                            device = status.st_dev,
                            inode = status.st_ino
                        ),
                    packageDescriptor = ownedDescriptor
                )
            } finally {
                rawDescriptor?.let(::closeRawDescriptorQuietly)
                closeParcelDescriptorQuietly(parcelDescriptor)
            }
        }

        /** Converts one implementation failure into a sanitized snapshot failure. */
        private fun Exception.asSnapshotFailure(): Exception =
            if (this is DocumentExportException) {
                this
            } else {
                DocumentExportException(DocumentExportFailure.SNAPSHOT_FAILED, this)
            }

        /** Closes every capture resource while preserving one primary failure. */
        private fun closeCaptureResources(
            stagedPackage: StagedSourceSavePackage?,
            snapshot: EditorDocumentSnapshot,
            cancellation: PackageCaptureCancellation,
            primaryFailure: Throwable?
        ) {
            listOf<AutoCloseable?>(stagedPackage, snapshot, cancellation).forEach { resource ->
                try {
                    resource?.close()
                } catch (cleanupFailure: Throwable) {
                    primaryFailure?.addSuppressed(cleanupFailure)
                }
            }
        }

        /** Closes one snapshot after capture setup fails. */
        private fun closeSnapshotAfterFailure(
            snapshot: EditorDocumentSnapshot,
            primaryFailure: Throwable
        ) {
            try {
                snapshot.close()
            } catch (cleanupFailure: Throwable) {
                primaryFailure.addSuppressed(cleanupFailure)
            }
        }

        /** Closes one undelivered staged result without masking cancellation. */
        private fun closeStagedPackageQuietly(stagedPackage: StagedSourceSavePackage) {
            try {
                stagedPackage.close()
            } catch (_: Exception) {
                // Cancellation remains authoritative after capture completed.
            }
        }

        /** Closes one raw descriptor without masking a primary operation. */
        private fun closeRawDescriptorQuietly(descriptor: FileDescriptor) {
            try {
                Os.close(descriptor)
            } catch (_: ErrnoException) {
                // Descriptor ownership has already ended or remains explicitly duplicated.
            }
        }
    }
}

/** Requires one bounded, internally consistent native package description. */
internal fun validateSourceSavePackageMetrics(
    metrics: SourceSavePackageMetrics,
    expectedBytes: Long
) {
    require(expectedBytes in ExportProtocol.MIN_BYTE_LIMIT..ExportProtocol.MAX_BYTE_LIMIT) {
        "expected byte count exceeds export limits"
    }
    check(metrics.outputByteLength == expectedBytes) {
        "source-save package output length is unexpected"
    }
    check(metrics.payloadByteLength <= expectedBytes) {
        "source-save payload exceeds its output"
    }
    check(metrics.recordCount <= MAX_SOURCE_SAVE_PACKAGE_RECORDS) {
        "source-save package has too many records"
    }
    check((expectedBytes == 0L) == (metrics.recordCount == 0L)) {
        "source-save record count does not match its output"
    }
    check(metrics.hasSourceBacking == (metrics.payloadByteLength < expectedBytes)) {
        "source-save backing does not match source coverage"
    }
    check(
        !metrics.hasSourceBacking ||
            metrics.sourceBackingByteLength in 1L..ExportProtocol.MAX_BYTE_LIMIT
    ) {
        "source-save backing length is invalid"
    }
    val recordTableByteLength =
        Math.multiplyExact(metrics.recordCount, SOURCE_SAVE_PACKAGE_RECORD_BYTES)
    val expectedPackageByteLength =
        Math.addExact(
            SOURCE_SAVE_PACKAGE_HEADER_BYTES,
            Math.addExact(recordTableByteLength, metrics.payloadByteLength)
        )
    check(metrics.packageByteLength == expectedPackageByteLength) {
        "source-save package length is noncanonical"
    }
    check(
        metrics.packageByteLength <=
            Math.addExact(expectedBytes, MAX_SOURCE_SAVE_PACKAGE_OVERHEAD_BYTES)
    ) {
        "source-save package exceeds its bounded overhead"
    }
}

/** Identifies one exact anonymous descriptor across validation steps. */
private data class DescriptorIdentity(val device: Long, val inode: Long)

/** Returns and validates one app-owned anonymous descriptor identity. */
private fun descriptorIdentity(
    descriptor: ParcelFileDescriptor,
    expectedSize: Long
): DescriptorIdentity {
    val status = Os.fstat(descriptor.fileDescriptor)
    requireAnonymousStatus(status.st_mode, status.st_nlink, status.st_uid)
    check(status.st_size == expectedSize) {
        "source-save descriptor size is unexpected"
    }
    return DescriptorIdentity(device = status.st_dev, inode = status.st_ino)
}

/** Requires one unchanged app-owned anonymous regular descriptor. */
private fun validateDescriptor(
    descriptor: ParcelFileDescriptor,
    identity: DescriptorIdentity,
    expectedSize: Long
) {
    val status = Os.fstat(descriptor.fileDescriptor)
    requireAnonymousStatus(status.st_mode, status.st_nlink, status.st_uid)
    check(status.st_dev == identity.device && status.st_ino == identity.inode) {
        "source-save descriptor identity changed"
    }
    check(status.st_size == expectedSize) {
        "source-save descriptor size is unexpected"
    }
}

/** Requires an app-owned, unlinked regular descriptor. */
private fun requireAnonymousStatus(mode: Int, linkCount: Long, ownerUid: Int) {
    check(OsConstants.S_ISREG(mode)) {
        "source-save descriptor is not a regular file"
    }
    check(linkCount == 0L) { "source-save descriptor has a filesystem link" }
    check(ownerUid == Process.myUid()) {
        "source-save descriptor has an unexpected owner"
    }
}

/** Requires every permanent seal in one bit mask. */
private fun requireSeals(descriptor: ParcelFileDescriptor, requiredSeals: Int) {
    check(
        Os.fcntlInt(descriptor.fileDescriptor, F_GET_SEALS, 0) and requiredSeals ==
            requiredSeals
    ) {
        "source-save descriptor seals are incomplete"
    }
}

/** Closes two descriptors while preserving the first close failure. */
private fun closeDescriptors(first: ParcelFileDescriptor?, second: ParcelFileDescriptor?) {
    var primaryFailure: Exception? = null
    listOf(first, second).forEach { descriptor ->
        try {
            descriptor?.close()
        } catch (failure: Exception) {
            if (primaryFailure == null) {
                primaryFailure = failure
            } else {
                primaryFailure.addSuppressed(failure)
            }
        }
    }
    primaryFailure?.let { throw it }
}

/** Closes one parcel descriptor without masking a primary operation. */
private fun closeParcelDescriptorQuietly(descriptor: ParcelFileDescriptor?) {
    try {
        descriptor?.close()
    } catch (_: Exception) {
        // Descriptor creation or capture already preserves the primary error.
    }
}

/** Owns one explicit cancellation pipe for native package capture. */
private class PackageCaptureCancellation private constructor(
    private var reader: ParcelFileDescriptor?,
    private var writer: ParcelFileDescriptor?
) : AutoCloseable {
    /** Returns the borrowed native cancellation descriptor. */
    val readerRawFileDescriptor: Int
        @Synchronized get() =
            checkNotNull(reader) { "package cancellation reader is unavailable" }.fd

    /** Signals cancellation by closing the only local write capability. */
    @Synchronized
    fun cancel() {
        closeQuietly(writer)
        writer = null
    }

    /** Closes every cancellation capability exactly once. */
    @Synchronized
    override fun close() {
        closeQuietly(writer)
        closeQuietly(reader)
        writer = null
        reader = null
    }

    companion object {
        /** Creates one pipe whose write endpoint signals native cancellation. */
        fun create(): PackageCaptureCancellation {
            val descriptors = ParcelFileDescriptor.createPipe()
            try {
                check(descriptors.size == 2) {
                    "package cancellation pipe returned an invalid descriptor pair"
                }
                return PackageCaptureCancellation(
                    reader = descriptors[0],
                    writer = descriptors[1]
                )
            } catch (failure: Throwable) {
                descriptors.forEach { descriptor ->
                    try {
                        descriptor.close()
                    } catch (cleanupFailure: Exception) {
                        failure.addSuppressed(cleanupFailure)
                    }
                }
                throw failure
            }
        }
    }

    /** Closes one owned descriptor without masking capture completion. */
    private fun closeQuietly(descriptor: ParcelFileDescriptor?) {
        try {
            descriptor?.close()
        } catch (_: Exception) {
            // Cancellation descriptor ownership ends even when close reports an error.
        }
    }
}
