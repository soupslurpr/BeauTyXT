package dev.soupslurpr.beautyxt.data

import android.net.Uri
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf

data class FileUiState(
    /** uri of file */
    val uri: MutableState<Uri> = mutableStateOf(Uri.EMPTY),
    /** name of file */
    val name: MutableState<String> = mutableStateOf(""),
    /** content of file */
    val content: MutableState<String> = mutableStateOf(""),
    /** mimeType of file */
    val mimeType: MutableState<String?> = mutableStateOf(""),
    /** size of file */
    val size: MutableState<Long> = mutableLongStateOf(0L),
    /** Content (markdown) converted to HTML */
    val contentConvertedToHtml: MutableState<String> = mutableStateOf(""),
    /** whether the file is read only or not */
    val readOnly: MutableState<Boolean> = mutableStateOf(true),
)