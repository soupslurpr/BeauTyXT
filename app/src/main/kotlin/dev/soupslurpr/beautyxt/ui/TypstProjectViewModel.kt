package dev.soupslurpr.beautyxt.ui

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import dev.soupslurpr.beautyxt.RenderException
import dev.soupslurpr.beautyxt.TypstProjectFilePathAndFd
import dev.soupslurpr.beautyxt.addTypstProjectFiles
import dev.soupslurpr.beautyxt.clearTypstProjectFiles
import dev.soupslurpr.beautyxt.data.TypstProjectUiState
import dev.soupslurpr.beautyxt.getTypstPdf
import dev.soupslurpr.beautyxt.getTypstSvg
import dev.soupslurpr.beautyxt.initializeTypstWorld
import dev.soupslurpr.beautyxt.setMainTypstProjectFile
import dev.soupslurpr.beautyxt.updateTypstProjectFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.FileOutputStream

class TypstProjectViewModel : ViewModel() {

    /**
     * File state for this file
     */
    private val _uiState = MutableStateFlow(TypstProjectUiState())
    val uiState: StateFlow<TypstProjectUiState> = _uiState.asStateFlow()

    fun openProject(projectFolderUri: Uri, context: Context) {
        initializeTypstWorld()

        _uiState.value.projectFolderUri.value = projectFolderUri

        val files: MutableList<TypstProjectFilePathAndFd> = mutableListOf()
        val filesQueue = ArrayDeque<DocumentFile>()

        filesQueue.addAll(
            DocumentFile.fromTreeUri(context, uiState.value.projectFolderUri.value)?.listFiles().orEmpty()
        )

        while (filesQueue.isNotEmpty()) {
            val file = filesQueue.removeFirst()
            if (file.isDirectory) {
                filesQueue.addAll(file.listFiles())
            } else {
                val parcelFileDescriptor = context.contentResolver.openAssetFileDescriptor(file.uri, "rw")
                    ?.parcelFileDescriptor
                if (parcelFileDescriptor != null) {
                    val projectFile = TypstProjectFilePathAndFd(
                        file.uri.lastPathSegment?.removePrefix(
                            uiState.value.projectFolderUri.value
                                .lastPathSegment.orEmpty()
                        ).orEmpty(), parcelFileDescriptor.detachFd()
                    )
                    if (projectFile.path == "/main.typ") {
                        _uiState.value.currentOpenedPath.value = "/main.typ"
                        _uiState.value.currentOpenedDisplayName.value = "main.typ"
                        setMainTypstProjectFile(projectFile)
                        _uiState.value.currentOpenedContent.value =
                            dev.soupslurpr.beautyxt.getTypstProjectFileText(uiState.value.currentOpenedPath.value)
                    } else {
                        files.add(projectFile)
                    }
                }
            }
        }

        addTypstProjectFiles(files)

        if (uiState.value.currentOpenedPath.value == "") {
            DocumentFile.fromTreeUri(context, uiState.value.projectFolderUri.value)?.createFile(
                "typst/application",
                "main.typ"
            )
            _uiState.value.currentOpenedPath.value = "/main.typ"
            _uiState.value.currentOpenedDisplayName.value = "main.typ"
            refreshProjectFiles(context)
            val files: MutableList<TypstProjectFilePathAndFd> = mutableListOf()
            val filesQueue = ArrayDeque<DocumentFile>()

            filesQueue.addAll(
                DocumentFile.fromTreeUri(context, uiState.value.projectFolderUri.value)?.listFiles().orEmpty()
            )

            while (filesQueue.isNotEmpty()) {
                val file = filesQueue.removeFirst()
                if (file.isDirectory) {
                    filesQueue.addAll(file.listFiles())
                } else {
                    val parcelFileDescriptor = context.contentResolver.openAssetFileDescriptor(file.uri, "rw")
                        ?.parcelFileDescriptor
                    if (parcelFileDescriptor != null) {
                        val projectFile = TypstProjectFilePathAndFd(
                            file.uri.lastPathSegment?.removePrefix(
                                uiState.value.projectFolderUri.value
                                    .lastPathSegment.orEmpty()
                            ).orEmpty(), parcelFileDescriptor.detachFd()
                        )
                        if (projectFile.path == "/main.typ") {
                            _uiState.value.currentOpenedPath.value = "/main.typ"
                            _uiState.value.currentOpenedDisplayName.value = "main.typ"
                            setMainTypstProjectFile(projectFile)
                            _uiState.value.currentOpenedContent.value =
                                dev.soupslurpr.beautyxt.getTypstProjectFileText(uiState.value.currentOpenedPath.value)
                        } else {
                            files.add(projectFile)
                        }
                    }
                }
            }

            addTypstProjectFiles(files)
        }

        renderProjectToSvgs()
    }

    fun exportDocumentToPdf(exportUri: Uri, context: Context) {
        val pdf = getTypstPdf()

        try {
            val contentResolver = context.contentResolver
            contentResolver.openFileDescriptor(exportUri, "wt")?.use {
                FileOutputStream(it.fileDescriptor).use {
                    it.write(pdf)
                }
            }
        } finally {

        }
        // TODO: Handle exceptions
    }

    fun renderProjectToSvgs() {
        var noException = true

        try {
            _uiState.value.renderedProjectSvg.value = getTypstSvg()
        } catch (e: RenderException.VecCustomSourceDiagnostic) {
            _uiState.value.sourceDiagnostics.clear()
            _uiState.value.sourceDiagnostics.addAll(e.customSourceDiagnostics)
            noException = false
        }

        if (noException) {
            _uiState.value.sourceDiagnostics.clear()
        }
    }

    fun setCurrentOpenedPath(uri: Uri, contentResolver: ContentResolver) {
        val projectFolderUri = uiState.value.projectFolderUri.value

        _uiState.value.currentOpenedPath.value = uri.lastPathSegment?.removePrefix(
            projectFolderUri.lastPathSegment
                .orEmpty()
        ).orEmpty()

        _uiState.value.currentOpenedContent.value = getTypstProjectFileText(uiState.value.currentOpenedPath.value)

        // Sets the display name

        // The query, because it only applies to a single document, returns only
        // one row. There's no need to filter, sort, or select fields,
        // because we want all fields for one document.
        val cursor: Cursor? = contentResolver.query(
            uri, null, null, null, null, null
        )

        cursor?.use {
            // moveToFirst() returns false if the cursor has 0 rows. Very handy for
            // "if there's anything to look at, look at it" conditionals.
            if (it.moveToFirst()) {

                // This is provider-specific, and might not necessarily be the file name.
                _uiState.value.currentOpenedDisplayName.value =
                    it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
            }
        }
    }

    fun refreshProjectFiles(context: Context) {
        val projectFolderUri = uiState.value.projectFolderUri.value

        val files: MutableList<TypstProjectFilePathAndFd> = mutableListOf()
        val filesQueue = ArrayDeque<DocumentFile>()

        filesQueue.addAll(DocumentFile.fromTreeUri(context, projectFolderUri)?.listFiles().orEmpty())

        while (filesQueue.isNotEmpty()) {
            val file = filesQueue.removeFirst()
            if (file.isDirectory) {
                filesQueue.addAll(file.listFiles())
            } else {
                val parcelFileDescriptor = context.contentResolver.openAssetFileDescriptor(file.uri, "rw")
                    ?.parcelFileDescriptor
                if (parcelFileDescriptor != null) {
                    val projectFile = TypstProjectFilePathAndFd(
                        file.uri.lastPathSegment?.removePrefix(
                            projectFolderUri
                                .lastPathSegment.orEmpty()
                        ).orEmpty(), parcelFileDescriptor.detachFd()
                    )
                    if (projectFile.path == "/main.typ") {
                        setMainTypstProjectFile(projectFile)
                    } else {
                        files.add(projectFile)
                    }
                }
            }
        }

        addTypstProjectFiles(files)
    }

    fun updateProjectFileWithNewText(newText: String, path: String) {
        _uiState.value.currentOpenedContent.value = updateTypstProjectFile(newText, path)
    }

    fun getTypstProjectFileText(path: String): String {
        return dev.soupslurpr.beautyxt.getTypstProjectFileText(path)
    }

    /** Set uiState to default values */
    private fun clearUiState() {
        _uiState.value = TypstProjectUiState()
        clearTypstProjectFiles()
    }

    override fun onCleared() {
        super.onCleared()
        clearUiState()
    }
}