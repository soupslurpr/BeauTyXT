package dev.soupslurpr.beautyxt.data

import android.net.Uri
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import dev.soupslurpr.beautyxt.CustomSourceDiagnostic

data class TypstProjectUiState(
    /** Project folder uri */
    val projectFolderUri: MutableState<Uri> = mutableStateOf(Uri.EMPTY),
    /** Project folder name */
    val projectFolderName: MutableState<String> = mutableStateOf(""),
    /** All files in the project with their paths */
    val projectFiles: MutableCollection<Pair<String, Int>> = mutableStateListOf(),
    /** Project rendered to SVGs */
    val renderedProjectSvg: MutableState<ByteArray> = mutableStateOf(ByteArray(0)),
    /** Path (in relation to the project folder) of currently open .typ. */
    val currentOpenedPath: MutableState<String> = mutableStateOf(""),
    /** File name */
    val currentOpenedDisplayName: MutableState<String> = mutableStateOf(""),
    /** Content of current opened path */
    val currentOpenedContent: MutableState<String> = mutableStateOf(""),
    /** Source diagnostics */
    val sourceDiagnostics: MutableCollection<CustomSourceDiagnostic> = mutableStateListOf(),
)