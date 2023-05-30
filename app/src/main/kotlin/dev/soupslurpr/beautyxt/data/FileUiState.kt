package dev.soupslurpr.beautyxt.data

import android.net.Uri

data class FileUiState(
    /** uri of file */
    val uri: Uri = Uri.EMPTY,
    /** name of file */
    val name: String = "",
    /** content of file */
    val content: String = "",
    /** mimeType of file */
    // TODO: add support for multiple file types (.md maybe 😉)
)