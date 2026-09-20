package dev.soupslurpr.beautyxt.ipc

import android.content.ContentResolver
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import java.io.IOException

private val providerDescriptors = BoundedResourceOpener(maximumConcurrentCalls = 4)

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
