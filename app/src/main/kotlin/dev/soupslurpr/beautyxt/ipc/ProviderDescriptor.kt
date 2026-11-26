package dev.soupslurpr.beautyxt.ipc

import android.content.ContentResolver
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import java.io.FileDescriptor
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout

private val providerDescriptors = BoundedResourceOpener(maximumConcurrentCalls = 4)
private val descriptorMetadataProbes = BoundedResourceOpener(maximumConcurrentCalls = 4)

/** Opens without truncation; callers apply their own timeout including waiting for a slot. */
internal suspend fun openProviderDescriptor(
    resolver: ContentResolver,
    uri: Uri,
    mode: String,
    closeUnclaimed: (ParcelFileDescriptor) -> Unit
): ParcelFileDescriptor {
    require(mode == "r" || mode == "rw") { "provider descriptor mode must not truncate" }
    val cancellation = CancellationSignal()
    return providerDescriptors.open(
        cancel = cancellation::cancel,
        closeUnclaimed = closeUnclaimed
    ) {
        resolver.openFileDescriptor(uri, mode, cancellation)
            ?: throw IOException("selected document provider returned no descriptor")
    }
}

/**
 * Runs one descriptor metadata syscall probe on bounded detached background I/O so proxy
 * descriptors whose `onGetSize()` callback blocks cannot freeze Main or delay timeout return.
 */
internal suspend fun <Result> probeProviderDescriptorMetadata(
    descriptor: ParcelFileDescriptor,
    timeoutMillis: Long,
    defaultValue: Result,
    probe: (FileDescriptor) -> Result
): Result {
    require(timeoutMillis > 0L) { "descriptor metadata timeout must be positive" }
    val duplicated =
        try {
            descriptor.dup()
        } catch (_: IOException) {
            return defaultValue
        } catch (_: IllegalStateException) {
            return defaultValue
        } catch (_: LinkageError) {
            return defaultValue
        }
    return probeOwnedDescriptorMetadata(
        ownedDescriptor = duplicated,
        timeoutMillis = timeoutMillis,
        defaultValue = defaultValue,
        probe = probe
    )
}

/** Takes ownership of [ownedDescriptor] (closing it once) and runs [probe] with detached timeout. */
internal suspend fun <Result> probeOwnedDescriptorMetadata(
    ownedDescriptor: ParcelFileDescriptor,
    timeoutMillis: Long,
    defaultValue: Result,
    probe: (FileDescriptor) -> Result
): Result {
    require(timeoutMillis > 0L) { "descriptor metadata timeout must be positive" }
    val ownershipClaimed = AtomicBoolean(false)
    return try {
        withTimeout(timeoutMillis) {
            descriptorMetadataProbes.open(
                cancel = {},
                closeUnclaimed = {}
            ) {
                if (!ownershipClaimed.compareAndSet(false, true)) {
                    return@open defaultValue
                }
                try {
                    probe(ownedDescriptor.fileDescriptor)
                } finally {
                    closeMetadataDescriptorQuietly(ownedDescriptor)
                }
            }
        }
    } catch (_: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        defaultValue
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        defaultValue
    } catch (_: LinkageError) {
        defaultValue
    } finally {
        if (ownershipClaimed.compareAndSet(false, true)) {
            closeMetadataDescriptorQuietly(ownedDescriptor)
        }
    }
}

private fun closeMetadataDescriptorQuietly(descriptor: ParcelFileDescriptor) {
    try {
        descriptor.close()
    } catch (_: IOException) {
        // Best-effort cleanup for dup'd probe descriptor.
    }
}
