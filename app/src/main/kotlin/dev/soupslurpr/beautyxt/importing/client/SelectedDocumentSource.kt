package dev.soupslurpr.beautyxt.importing.client

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.system.Os
import android.system.OsConstants
import dev.soupslurpr.beautyxt.document.DocumentRemovalAction
import dev.soupslurpr.beautyxt.document.DocumentRemovalCapabilities
import dev.soupslurpr.beautyxt.document.SourceVersion
import dev.soupslurpr.beautyxt.ipc.acquireResourceWithTimeout
import dev.soupslurpr.beautyxt.ipc.openProviderDescriptor
import dev.soupslurpr.beautyxt.ipc.probeProviderDescriptorMetadata
import java.io.FileDescriptor
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

private const val SOURCE_DESCRIPTOR_OPEN_TIMEOUT_MILLIS = 30_000L
private const val WRITE_PROBE_DESCRIPTOR_OPEN_TIMEOUT_MILLIS = 5_000L
private const val READ_DESCRIPTOR_MODE = "r"
private const val READ_WRITE_DESCRIPTOR_MODE = "rw"
private const val LINUX_OPEN_PATH_FLAG = 0x20_0000

/** Reports that a selected source descriptor could not be opened. */
internal class SelectedDocumentSourceException(cause: Throwable? = null) :
    IOException("selected document descriptor unavailable", cause)

/** Result of probing one opened descriptor's kernel regular-file identity on bounded background I/O. */
internal sealed interface DescriptorFileIdentity {
    /** A regular file with a verified process-local kernel `(st_dev, st_ino)` key. */
    data class Regular(val key: String) : DescriptorFileIdentity

    /** Verified non-regular descriptor (pipe, socket, or virtual stream) with no regular inode. */
    data object NonRegular : DescriptorFileIdentity

    /** Probe timed out, failed, or observed an unidentifiable regular inode; callers must fail closed. */
    data object Indeterminate : DescriptorFileIdentity
}

/** Returns a process-local kernel inode identity key for regular files, or null for pipes/virtual FDs. */
internal fun regularFileIdentityKey(fileDescriptor: FileDescriptor): String? =
    try {
        val stat = Os.fstat(fileDescriptor)
        if (OsConstants.S_ISREG(stat.st_mode) && stat.st_ino != 0L) {
            "ino:${stat.st_dev}:${stat.st_ino}"
        } else {
            null
        }
    } catch (_: Exception) {
        null
    } catch (_: LinkageError) {
        null
    }

/** Snapshot of kernel identity and change metadata (`st_size`, `st_mtim`, `st_ctim`) for one FD. */
internal data class DescriptorFileSnapshot(
    val identity: DescriptorFileIdentity,
    val sizeBytes: Long? = null,
    val mtimeSec: Long? = null,
    val mtimeNsec: Long? = null,
    val ctimeSec: Long? = null,
    val ctimeNsec: Long? = null
)

/** Classifies [fileDescriptor] synchronously with kernel regular-file identity and change metadata. */
internal fun probeDescriptorFileSnapshot(fileDescriptor: FileDescriptor): DescriptorFileSnapshot =
    try {
        val stat = Os.fstat(fileDescriptor)
        when {
            OsConstants.S_ISREG(stat.st_mode) && stat.st_ino != 0L ->
                DescriptorFileSnapshot(
                    identity = DescriptorFileIdentity.Regular("ino:${stat.st_dev}:${stat.st_ino}"),
                    sizeBytes = stat.st_size,
                    mtimeSec = stat.st_mtim.tv_sec,
                    mtimeNsec = stat.st_mtim.tv_nsec,
                    ctimeSec = stat.st_ctim.tv_sec,
                    ctimeNsec = stat.st_ctim.tv_nsec
                )
            !OsConstants.S_ISREG(stat.st_mode) ->
                DescriptorFileSnapshot(identity = DescriptorFileIdentity.NonRegular)
            else ->
                DescriptorFileSnapshot(identity = DescriptorFileIdentity.Indeterminate)
        }
    } catch (_: Exception) {
        DescriptorFileSnapshot(identity = DescriptorFileIdentity.Indeterminate)
    } catch (_: LinkageError) {
        DescriptorFileSnapshot(identity = DescriptorFileIdentity.Indeterminate)
    }

/** Runs [probeDescriptorFileSnapshot] on bounded detached background I/O, failing closed on timeout. */
internal suspend fun probeDescriptorFileSnapshotBounded(
    descriptor: ParcelFileDescriptor,
    timeoutMillis: Long = WRITE_PROBE_DESCRIPTOR_OPEN_TIMEOUT_MILLIS
): DescriptorFileSnapshot =
    probeProviderDescriptorMetadata(
        descriptor = descriptor,
        timeoutMillis = timeoutMillis,
        defaultValue = DescriptorFileSnapshot(identity = DescriptorFileIdentity.Indeterminate),
        probe = ::probeDescriptorFileSnapshot
    )

/** Classifies [fileDescriptor] synchronously as regular-with-inode, non-regular, or indeterminate. */
internal fun probeDescriptorFileIdentity(fileDescriptor: FileDescriptor): DescriptorFileIdentity =
    probeDescriptorFileSnapshot(fileDescriptor).identity

/** Runs [probeDescriptorFileIdentity] on bounded detached background I/O, failing closed on timeout. */
internal suspend fun probeDescriptorFileIdentityBounded(
    descriptor: ParcelFileDescriptor,
    timeoutMillis: Long = WRITE_PROBE_DESCRIPTOR_OPEN_TIMEOUT_MILLIS
): DescriptorFileIdentity =
    probeProviderDescriptorMetadata(
        descriptor = descriptor,
        timeoutMillis = timeoutMillis,
        defaultValue = DescriptorFileIdentity.Indeterminate,
        probe = ::probeDescriptorFileIdentity
    )

/** Returns whether [fileDescriptor] is an empty (0-byte) regular file. */
internal fun isEmptyRegularFile(fileDescriptor: FileDescriptor): Boolean =
    try {
        val stat = Os.fstat(fileDescriptor)
        OsConstants.S_ISREG(stat.st_mode) && stat.st_size == 0L
    } catch (_: Exception) {
        false
    } catch (_: LinkageError) {
        false
    }

/** Runs [isEmptyRegularFile] on bounded detached background I/O so proxy FDs cannot freeze UI or hang past timeout. */
internal suspend fun isEmptyRegularFileBounded(
    descriptor: ParcelFileDescriptor,
    timeoutMillis: Long = WRITE_PROBE_DESCRIPTOR_OPEN_TIMEOUT_MILLIS
): Boolean =
    probeProviderDescriptorMetadata(
        descriptor = descriptor,
        timeoutMillis = timeoutMillis,
        defaultValue = false,
        probe = ::isEmptyRegularFile
    )

/** Runs [isCompatibleAutosaveDescriptor] on bounded detached background I/O. */
internal suspend fun isCompatibleAutosaveDescriptorBounded(
    descriptor: ParcelFileDescriptor,
    timeoutMillis: Long = WRITE_PROBE_DESCRIPTOR_OPEN_TIMEOUT_MILLIS
): Boolean =
    probeProviderDescriptorMetadata(
        descriptor = descriptor,
        timeoutMillis = timeoutMillis,
        defaultValue = false,
        probe = ::isCompatibleAutosaveDescriptor
    )

/** Returns whether one descriptor meets the observable native autosave preconditions. */
internal fun isCompatibleAutosaveDescriptor(fileDescriptor: FileDescriptor): Boolean {
    val openFlags =
        Os.fcntlInt(
            fileDescriptor,
            OsConstants.F_GETFL,
            0
        )
    val hasCompatibleFlags =
        (openFlags and OsConstants.O_ACCMODE) == OsConstants.O_RDWR &&
            (openFlags and OsConstants.O_APPEND) == 0 &&
            (openFlags and LINUX_OPEN_PATH_FLAG) == 0
    return hasCompatibleFlags &&
        OsConstants.S_ISREG(Os.fstat(fileDescriptor).st_mode) &&
        Os.lseek(fileDescriptor, 0L, OsConstants.SEEK_SET) == 0L
}

/** Retains one exact content URI and its transient capability without persisting it. */
internal class SelectedDocumentSource private constructor(
    uri: Uri,
    internal val applicationContext: Context,
    private val contentResolver: ContentResolver,
    private val isDocumentUri: Boolean
) : AutoCloseable {
    private val ownershipLock = Any()
    private var selectedUri: Uri? = uri
    private var currentSourceVersion: SourceVersion? = null
    private var hasCompletedReadOpen = false
    private var observedReadFileIdentityKey: String? = null
    private var observedWriteFileIdentityKey: String? = null

    val uri: Uri
        get() = synchronized(ownershipLock) {
            checkNotNull(selectedUri) { "selected document source is closed" }
        }

    /** Returns the kernel `(st_dev, st_ino)` identity key observed on an opened regular FD, preferring `rw`. */
    val fileIdentityKey: String?
        get() = synchronized(ownershipLock) {
            observedWriteFileIdentityKey ?: observedReadFileIdentityKey
        }

    /** Returns the exact expected source version, or null before its first save. */
    val sourceVersion: SourceVersion?
        get() = synchronized(ownershipLock) {
            checkNotNull(selectedUri) { "selected document source is closed" }
            currentSourceVersion
        }

    /** Recognizes exact URIs and tree/document aliases of the same provider document. */
    fun matchesUri(encodedUri: String): Boolean {
        require(encodedUri.isNotBlank()) { "candidate source URI must not be blank" }
        return synchronized(ownershipLock) {
            val source = selectedUri ?: return@synchronized false
            if (source.toString() == encodedUri) return@synchronized true
            val candidate = encodedUri.toUri()
            candidate.scheme == ContentResolver.SCHEME_CONTENT && candidate.authority == source.authority && isDocumentUri &&
                runCatching { DocumentsContract.getDocumentId(candidate) == DocumentsContract.getDocumentId(source) }.getOrDefault(false)
        }
    }

    /** Advances the exact source version when its expected baseline remains current. */
    fun advanceSourceVersion(expectedVersion: SourceVersion?, newVersion: SourceVersion) {
        synchronized(ownershipLock) {
            checkNotNull(selectedUri) { "selected document source is closed" }
            check(currentSourceVersion == expectedVersion) {
                "selected document source version changed"
            }
            currentSourceVersion = newVersion
        }
    }

    /** Opens a fresh caller-owned read descriptor for initial validation. */
    suspend fun openReadDescriptor(): ParcelFileDescriptor = openDescriptor(
        mode = READ_DESCRIPTOR_MODE,
        timeoutMillis = SOURCE_DESCRIPTOR_OPEN_TIMEOUT_MILLIS
    )

    /** Opens a read descriptor for verification without mutating the source's baseline identity. */
    suspend fun openReadDescriptorForProbe(): ParcelFileDescriptor = openDescriptor(
        mode = READ_DESCRIPTOR_MODE,
        timeoutMillis = SOURCE_DESCRIPTOR_OPEN_TIMEOUT_MILLIS,
        recordObservedIdentity = false
    )

    /** Opens a fresh caller-owned read-write descriptor for one source save. */
    suspend fun openReadWriteDescriptor(): ParcelFileDescriptor = openDescriptor(
        mode = READ_WRITE_DESCRIPTOR_MODE,
        timeoutMillis = SOURCE_DESCRIPTOR_OPEN_TIMEOUT_MILLIS
    )

    /** Opens a read-write descriptor for verification without mutating the source's baseline identity. */
    suspend fun openReadWriteDescriptorForProbe(): ParcelFileDescriptor = openDescriptor(
        mode = READ_WRITE_DESCRIPTOR_MODE,
        timeoutMillis = SOURCE_DESCRIPTOR_OPEN_TIMEOUT_MILLIS,
        recordObservedIdentity = false
    )

    /**
     * Returns whether the provider currently exposes a compatible autosave descriptor with a
     * verified regular-file kernel inode, deriving both checks from the same probe so a timed-out
     * inode lookup can never leave a writable source protected only by its URI.
     */
    suspend fun hasCompatibleWriteAccess(): Boolean = try {
        val uri =
            synchronized(ownershipLock) {
                checkNotNull(selectedUri) { "selected document source is closed" }
            }
        acquireResourceWithTimeout(WRITE_PROBE_DESCRIPTOR_OPEN_TIMEOUT_MILLIS) {
            openProviderDescriptor(
                resolver = contentResolver,
                uri = uri,
                mode = READ_WRITE_DESCRIPTOR_MODE,
                closeUnclaimed = ::closeDescriptorQuietly
            )
        }.use { descriptor ->
            val verifiedIdentityKey =
                probeProviderDescriptorMetadata(
                    descriptor = descriptor,
                    timeoutMillis = WRITE_PROBE_DESCRIPTOR_OPEN_TIMEOUT_MILLIS,
                    defaultValue = null
                ) { fd ->
                    if (!isCompatibleAutosaveDescriptor(fd)) {
                        null
                    } else {
                        (probeDescriptorFileIdentity(fd) as? DescriptorFileIdentity.Regular)?.key
                    }
                }
            if (verifiedIdentityKey != null) {
                synchronized(ownershipLock) {
                    if (
                        selectedUri != null &&
                        (!hasCompletedReadOpen || observedReadFileIdentityKey == verifiedIdentityKey)
                    ) {
                        observedWriteFileIdentityKey = verifiedIdentityKey
                        true
                    } else {
                        false
                    }
                }
            } else {
                false
            }
        }
    } catch (_: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        false
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        false
    } catch (_: LinkageError) {
        false
    }

    /** Returns the source provider's live trash and deletion capabilities. */
    suspend fun queryRemovalCapabilities(): DocumentRemovalCapabilities {
        val uri =
            synchronized(ownershipLock) {
                checkNotNull(selectedUri) { "selected document source is closed" }
            }
        return querySelectedDocumentRemovalCapabilities(
            contentResolver = contentResolver,
            uri = uri,
            isDocumentUri = isDocumentUri
        )
    }

    /** Completes one freshly authorized provider removal operation. */
    suspend fun remove(
        action: DocumentRemovalAction,
        verifyTargetBeforeDispatch: (suspend () -> Unit)? = null
    ) {
        val uri =
            synchronized(ownershipLock) {
                checkNotNull(selectedUri) { "selected document source is closed" }
            }
        removeSelectedDocument(
            contentResolver = contentResolver,
            uri = uri,
            isDocumentUri = isDocumentUri,
            action = action,
            verifyTargetBeforeDispatch = verifyTargetBeforeDispatch
        )
    }

    /** Forgets this in-process content capability exactly once. */
    override fun close() {
        synchronized(ownershipLock) {
            selectedUri = null
            currentSourceVersion = null
            hasCompletedReadOpen = false
            observedReadFileIdentityKey = null
            observedWriteFileIdentityKey = null
        }
    }

    /** Opens one provider descriptor with bounded, coroutine-aware cancellation. */
    private suspend fun openDescriptor(
        mode: String,
        timeoutMillis: Long,
        recordObservedIdentity: Boolean = true
    ): ParcelFileDescriptor {
        require(timeoutMillis > 0L) { "descriptor timeout must be positive" }
        val uri =
            synchronized(ownershipLock) {
                checkNotNull(selectedUri) { "selected document source is closed" }
            }
        val descriptor =
            try {
                acquireResourceWithTimeout(timeoutMillis) {
                    openProviderDescriptor(
                        resolver = contentResolver,
                        uri = uri,
                        mode = mode,
                        closeUnclaimed = ::closeDescriptorQuietly
                    )
                }
            } catch (failure: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                throw SelectedDocumentSourceException(failure)
            } catch (failure: OperationCanceledException) {
                currentCoroutineContext().ensureActive()
                throw SelectedDocumentSourceException(failure)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                throw SelectedDocumentSourceException(failure)
            }
        val identityKey =
            if (recordObservedIdentity) {
                try {
                    (probeDescriptorFileIdentityBounded(descriptor) as? DescriptorFileIdentity.Regular)?.key
                } catch (failure: Throwable) {
                    closeDescriptorQuietly(descriptor)
                    throw failure
                }
            } else {
                null
            }
        val sourceRemainsOpen =
            synchronized(ownershipLock) {
                if (selectedUri != null) {
                    if (recordObservedIdentity) {
                        if (mode == READ_WRITE_DESCRIPTOR_MODE) {
                            if (identityKey != null) {
                                observedWriteFileIdentityKey = identityKey
                            }
                        } else {
                            hasCompletedReadOpen = true
                            if (identityKey != null) {
                                observedReadFileIdentityKey = identityKey
                            }
                        }
                    }
                    true
                } else {
                    false
                }
            }
        if (!sourceRemainsOpen) {
            closeDescriptorQuietly(descriptor)
            error("selected document source closed while opening")
        }
        return descriptor
    }

    companion object {
        /** Takes ownership of one source backed only by its temporary content grant. */
        fun takeOwnership(context: Context, uri: Uri): SelectedDocumentSource {
            require(uri.scheme == ContentResolver.SCHEME_CONTENT) {
                "selected document source must use the content scheme"
            }
            require(!uri.authority.isNullOrBlank()) {
                "selected document source must have an authority"
            }
            val applicationContext = context.applicationContext
            return SelectedDocumentSource(
                uri = uri,
                applicationContext = applicationContext,
                contentResolver = applicationContext.contentResolver,
                isDocumentUri =
                    try {
                        DocumentsContract.isDocumentUri(applicationContext, uri)
                    } catch (_: Exception) {
                        false
                    } catch (_: LinkageError) {
                        false
                    }
            )
        }
    }
}

/** Closes one descriptor whose ownership could not reach its caller. */
private fun closeDescriptorQuietly(descriptor: ParcelFileDescriptor) {
    try {
        descriptor.close()
    } catch (_: IOException) {
        // Descriptor ownership still ends when close reports an error.
    }
}
