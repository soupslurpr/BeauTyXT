package dev.soupslurpr.beautyxt.ipc

import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Parcelable
import java.io.IOException

/** Transfers sole descriptor ownership across one Binder transaction. */
internal class TransferredFileDescriptor private constructor(
    private var descriptor: ParcelFileDescriptor?
) : Parcelable {
    /** Claims the descriptor exactly once in the receiving process. */
    @Synchronized
    fun takeDescriptor(): ParcelFileDescriptor =
        checkNotNull(descriptor) { "transferred descriptor is unavailable" }.also {
            descriptor = null
        }

    /** Closes an untransferred descriptor with a fixed terminal error. */
    @Synchronized
    fun closeWithError(message: String) {
        require(message.isNotEmpty()) { "descriptor error message must not be empty" }
        val ownedDescriptor = descriptor ?: return
        descriptor = null
        try {
            ownedDescriptor.closeWithError(message)
        } catch (_: IOException) {
            closeQuietly(ownedDescriptor)
        } catch (_: RuntimeException) {
            closeQuietly(ownedDescriptor)
        }
    }

    /** Writes the descriptor and silently releases the sending process's copy. */
    @Synchronized
    override fun writeToParcel(destination: Parcel, flags: Int) {
        val ownedDescriptor =
            checkNotNull(descriptor) { "transferred descriptor is unavailable" }
        ownedDescriptor.writeToParcel(
            destination,
            flags or Parcelable.PARCELABLE_WRITE_RETURN_VALUE
        )
        descriptor = null
    }

    /** Reports that this value contains a file descriptor. */
    override fun describeContents(): Int = Parcelable.CONTENTS_FILE_DESCRIPTOR

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<TransferredFileDescriptor> =
            object : Parcelable.Creator<TransferredFileDescriptor> {
                /** Reconstructs one receiving-process descriptor owner. */
                override fun createFromParcel(source: Parcel): TransferredFileDescriptor =
                    TransferredFileDescriptor(ParcelFileDescriptor.CREATOR.createFromParcel(source))

                /** Creates an array for Android's Parcelable machinery. */
                override fun newArray(size: Int): Array<TransferredFileDescriptor?> =
                    arrayOfNulls(size)
            }

        /** Claims one local descriptor for transfer. */
        fun from(descriptor: ParcelFileDescriptor): TransferredFileDescriptor =
            TransferredFileDescriptor(descriptor)

        /** Closes one descriptor after its ownership has already ended. */
        private fun closeQuietly(descriptor: ParcelFileDescriptor) {
            try {
                descriptor.close()
            } catch (_: IOException) {
                // Descriptor ownership ends even when close reports an error.
            }
        }
    }
}
