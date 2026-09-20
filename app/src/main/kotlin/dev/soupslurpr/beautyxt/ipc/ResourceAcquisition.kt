package dev.soupslurpr.beautyxt.ipc

import kotlinx.coroutines.withTimeout

/** Transfers a resource only after its timed acquisition scope completes. */
internal suspend fun <Resource : AutoCloseable> acquireResourceWithTimeout(
    timeoutMillis: Long,
    acquire: suspend () -> Resource
): Resource {
    require(timeoutMillis > 0L) { "resource timeout must be positive" }
    var undeliveredResource: Resource? = null
    return try {
        withTimeout(timeoutMillis) {
            acquire().also { undeliveredResource = it }
        }
    } catch (failure: Throwable) {
        try {
            undeliveredResource?.close()
        } catch (cleanupFailure: Throwable) {
            failure.addSuppressed(cleanupFailure)
        }
        throw failure
    }
}
