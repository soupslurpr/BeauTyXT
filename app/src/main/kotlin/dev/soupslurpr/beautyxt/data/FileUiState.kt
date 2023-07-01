package dev.soupslurpr.beautyxt.data

import android.net.Uri
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf

data class FileUiState(
    /** uri of file */
    val uri: Uri = Uri.EMPTY,
    /** name of file */
    val name: MutableState<String> = mutableStateOf(""),
    /** content of file */
    var content: MutableState<String> = mutableStateOf(""),
    /** mimeType of file */
    // TODO: add support for multiple file types (.md maybe 😉)
)