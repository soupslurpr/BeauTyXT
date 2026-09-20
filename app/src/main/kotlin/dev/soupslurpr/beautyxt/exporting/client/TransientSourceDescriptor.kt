package dev.soupslurpr.beautyxt.exporting.client

import android.os.ParcelFileDescriptor
import java.io.IOException

/** Owns one transient source descriptor until an isolated operation claims it. */
internal class TransientSourceDescriptor private constructor(
    private var descriptor: ParcelFileDescriptor?
) : AutoCloseable {
    /** Transfers the only source capability to one isolated operation. */
    @Synchronized
    fun takeDescriptor(): ParcelFileDescriptor =
        checkNotNull(descriptor) { "source descriptor is unavailable" }.also {
            descriptor = null
        }

    /** Closes an unclaimed source capability exactly once. */
    @Synchronized
    override fun close() {
        descriptor?.closeQuietly()
        descriptor = null
    }

    companion object {
        /** Creates one owner around a freshly opened source capability. */
        fun from(descriptor: ParcelFileDescriptor): TransientSourceDescriptor =
            TransientSourceDescriptor(descriptor)
    }
}

/** Closes one source descriptor whose operation did not claim it. */
private fun ParcelFileDescriptor.closeQuietly() {
    try {
        close()
    } catch (_: IOException) {
        // Descriptor ownership still ends when close reports an error.
    } catch (_: RuntimeException) {
        // Descriptor ownership still ends when close reports an error.
    }
}
