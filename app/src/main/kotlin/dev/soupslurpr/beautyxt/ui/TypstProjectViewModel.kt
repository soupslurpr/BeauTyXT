package dev.soupslurpr.beautyxt.ui

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import dev.soupslurpr.beautyxt.ProjectFilePathAndFd
import dev.soupslurpr.beautyxt.RenderException
import dev.soupslurpr.beautyxt.addProjectFiles
import dev.soupslurpr.beautyxt.clearProjectFiles
import dev.soupslurpr.beautyxt.data.TypstProjectUiState
import dev.soupslurpr.beautyxt.getProjectFileText
import dev.soupslurpr.beautyxt.initializeWorld
import dev.soupslurpr.beautyxt.setMainProjectFile
import dev.soupslurpr.beautyxt.testGetMainPdf
import dev.soupslurpr.beautyxt.testGetMainSvg
import dev.soupslurpr.beautyxt.updateProjectFile
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
        initializeWorld()

        _uiState.value.projectFolderUri.value = projectFolderUri

        val files: MutableList<ProjectFilePathAndFd> = mutableListOf()
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
                    val projectFile = ProjectFilePathAndFd(
                        file.uri.lastPathSegment?.removePrefix(
                            uiState.value.projectFolderUri.value
                                .lastPathSegment.orEmpty()
                        ).orEmpty(), parcelFileDescriptor.detachFd()
                    )
                    if (projectFile.path == "/main.typ") {
                        _uiState.value.currentOpenedPath.value = "/main.typ"
                        _uiState.value.currentOpenedDisplayName.value = "main.typ"
                        setMainProjectFile(projectFile)
                        _uiState.value.currentOpenedContent.value =
                            getProjectFileText(uiState.value.currentOpenedPath.value)
                    } else {
                        files.add(projectFile)
                    }
                }
            }
        }

        addProjectFiles(files)

        if (uiState.value.currentOpenedPath.value == "") {
            DocumentFile.fromTreeUri(context, uiState.value.projectFolderUri.value)?.createFile(
                "typst/application",
                "main.typ"
            )
            _uiState.value.currentOpenedPath.value = "/main.typ"
            _uiState.value.currentOpenedDisplayName.value = "main.typ"
            refreshProjectFiles(context)
            val files: MutableList<ProjectFilePathAndFd> = mutableListOf()
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
                        val projectFile = ProjectFilePathAndFd(
                            file.uri.lastPathSegment?.removePrefix(
                                uiState.value.projectFolderUri.value
                                    .lastPathSegment.orEmpty()
                            ).orEmpty(), parcelFileDescriptor.detachFd()
                        )
                        if (projectFile.path == "/main.typ") {
                            _uiState.value.currentOpenedPath.value = "/main.typ"
                            _uiState.value.currentOpenedDisplayName.value = "main.typ"
                            setMainProjectFile(projectFile)
                            _uiState.value.currentOpenedContent.value =
                                getProjectFileText(uiState.value.currentOpenedPath.value)
                        } else {
                            files.add(projectFile)
                        }
                    }
                }
            }

            addProjectFiles(files)
        }

        renderProjectToSvgs()
    }

    fun testExportDocumentToPdf(exportUri: Uri, context: Context) {
        val pdf = testGetMainPdf()

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
            _uiState.value.renderedProjectSvg.value = testGetMainSvg()
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

        _uiState.value.currentOpenedContent.value = getProjectFileText(uiState.value.currentOpenedPath.value)

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

        val files: MutableList<ProjectFilePathAndFd> = mutableListOf()
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
                    val projectFile = ProjectFilePathAndFd(
                        file.uri.lastPathSegment?.removePrefix(
                            projectFolderUri
                                .lastPathSegment.orEmpty()
                        ).orEmpty(), parcelFileDescriptor.detachFd()
                    )
                    if (projectFile.path == "/main.typ") {
                        setMainProjectFile(projectFile)
                    } else {
                        files.add(projectFile)
                    }
                }
            }
        }

        addProjectFiles(files)
    }

    fun updateProjectFileWithNewText(newText: String, path: String) {
        _uiState.value.currentOpenedContent.value = updateProjectFile(newText, path)
    }

    /** Set uiState to default values */
    fun clearUiState() {
        _uiState.value = TypstProjectUiState()
        clearProjectFiles()
    }

    override fun onCleared() {
        super.onCleared()
        clearUiState()
    }
}