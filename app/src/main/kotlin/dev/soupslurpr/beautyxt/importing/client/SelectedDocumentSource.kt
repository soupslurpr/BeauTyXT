package dev.soupslurpr.beautyxt.importing.client

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
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
    private val contentResolver: ContentResolver,
    private val isDocumentUri: Boolean
) : AutoCloseable {
    private val ownershipLock = Any()
    private var selectedUri: Uri? = uri
    private var currentSourceVersion: SourceVersion? = null

    val uri: Uri
        get() = synchronized(ownershipLock) {
            checkNotNull(selectedUri) { "selected document source is closed" }
        }

    /** Returns the exact expected source version, or null before its first save. */
    val sourceVersion: SourceVersion?
        get() = synchronized(ownershipLock) {
            checkNotNull(selectedUri) { "selected document source is closed" }
            currentSourceVersion
        }

    /** Returns whether this open source retains the exact encoded content URI. */
    fun matchesUri(encodedUri: String): Boolean {
        require(encodedUri.isNotBlank()) { "candidate source URI must not be blank" }
        return synchronized(ownershipLock) {
            selectedUri?.toString() == encodedUri
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

    /** Opens a fresh caller-owned read-write descriptor for one source save. */
    suspend fun openReadWriteDescriptor(): ParcelFileDescriptor = openDescriptor(
        mode = READ_WRITE_DESCRIPTOR_MODE,
        timeoutMillis = SOURCE_DESCRIPTOR_OPEN_TIMEOUT_MILLIS
    )

    /** Returns whether the provider currently exposes a compatible autosave descriptor. */
    suspend fun hasCompatibleWriteAccess(): Boolean = try {
        openDescriptor(
            mode = READ_WRITE_DESCRIPTOR_MODE,
            timeoutMillis = WRITE_PROBE_DESCRIPTOR_OPEN_TIMEOUT_MILLIS
        ).use { descriptor ->
            isCompatibleAutosaveDescriptor(descriptor.fileDescriptor)
        }
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
    suspend fun remove(action: DocumentRemovalAction) {
        val uri =
            synchronized(ownershipLock) {
                checkNotNull(selectedUri) { "selected document source is closed" }
            }
        removeSelectedDocument(
            contentResolver = contentResolver,
            uri = uri,
            isDocumentUri = isDocumentUri,
            action = action
        )
    }

    /** Forgets this in-process content capability exactly once. */
    override fun close() {
        synchronized(ownershipLock) {
            selectedUri = null
            currentSourceVersion = null
        }
    }

    /** Opens one provider descriptor with bounded, coroutine-aware cancellation. */
    private suspend fun openDescriptor(mode: String, timeoutMillis: Long): ParcelFileDescriptor {
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
        val sourceRemainsOpen =
            synchronized(ownershipLock) {
                selectedUri != null
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
