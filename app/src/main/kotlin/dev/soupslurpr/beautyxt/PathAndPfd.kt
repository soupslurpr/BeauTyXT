package dev.soupslurpr.beautyxt

import android.os.ParcelFileDescriptor
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class PathAndPfd(
    var path: String,
    var pfd: ParcelFileDescriptor
) : Parcelable
