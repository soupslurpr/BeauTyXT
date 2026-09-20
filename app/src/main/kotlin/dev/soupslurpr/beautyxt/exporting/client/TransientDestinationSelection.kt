package dev.soupslurpr.beautyxt.exporting.client

import android.net.Uri

/** Owns a selected URI only until its destination descriptor is requested. */
internal class TransientDestinationSelection private constructor(private var selectedUri: Uri?) :
    AutoCloseable {
    /** Consumes the selected URI exactly once before descriptor acquisition. */
    @Synchronized
    fun takeUri(): Uri = checkNotNull(selectedUri) { "destination selection is unavailable" }.also {
        selectedUri = null
    }

    /** Discards an unconsumed selection without retaining its URI. */
    @Synchronized
    override fun close() {
        selectedUri = null
    }

    companion object {
        /** Creates one transient wrapper for a system-picker result. */
        fun from(uri: Uri): TransientDestinationSelection =
            TransientDestinationSelection(selectedUri = uri)
    }
}
