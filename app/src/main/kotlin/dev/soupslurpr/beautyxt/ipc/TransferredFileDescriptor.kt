/*
 * Copyright (C) 2006 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
// Modified for BeauTyXT: preserve the reliable-PFD wire protocol across NDK Binder.
// Exact AOSP source and complete license: repository-root CREDITS.

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

    /** Transfers data and reliable status separately so the NDK cannot detach the status. */
    @Synchronized
    override fun writeToParcel(destination: Parcel, flags: Int) {
        val ownedDescriptor =
            checkNotNull(descriptor) { "transferred descriptor is unavailable" }
        val parcel = Parcel.obtain()
        try {
            // PFD's public parcel representation contains a presence flag followed
            // by raw data/status descriptors. See the pinned AOSP source in CREDITS.
            ownedDescriptor.writeToParcel(parcel, flags or Parcelable.PARCELABLE_WRITE_RETURN_VALUE)
            descriptor = null
            parcel.setDataPosition(0)
            val hasStatus = parcel.readInt()
            check(hasStatus == 0 || hasStatus == 1)
            checkNotNull(parcel.readFileDescriptor()).use { data ->
                val status = if (hasStatus == 1) checkNotNull(parcel.readFileDescriptor()) else null
                status.use {
                    destination.writeTypedObject(data, 0)
                    destination.writeTypedObject(status, 0)
                }
            }
        } finally {
            parcel.recycle()
        }
    }

    /** Reports that this value contains a file descriptor. */
    override fun describeContents(): Int = Parcelable.CONTENTS_FILE_DESCRIPTOR

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<TransferredFileDescriptor> =
            object : Parcelable.Creator<TransferredFileDescriptor> {
                /** Reconstructs one receiving-process descriptor owner. */
                override fun createFromParcel(source: Parcel): TransferredFileDescriptor {
                    checkNotNull(source.readTypedObject(ParcelFileDescriptor.CREATOR)).use { data ->
                        source.readTypedObject(ParcelFileDescriptor.CREATOR).use { status ->
                            val parcel = Parcel.obtain()
                            try {
                                parcel.writeInt(if (status == null) 0 else 1)
                                parcel.writeFileDescriptor(data.fileDescriptor)
                                status?.let { parcel.writeFileDescriptor(it.fileDescriptor) }
                                parcel.setDataPosition(0)
                                return TransferredFileDescriptor(
                                    ParcelFileDescriptor.CREATOR.createFromParcel(parcel)
                                )
                            } finally {
                                parcel.recycle()
                            }
                        }
                    }
                }

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
