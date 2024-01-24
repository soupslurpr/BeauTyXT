package dev.soupslurpr.beautyxt.ui

import android.annotation.SuppressLint
import android.app.Application
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.database.Cursor
import android.net.Uri
import android.os.DeadObjectException
import android.os.IBinder
import android.provider.OpenableColumns
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.soupslurpr.beautyxt.ITypstProjectViewModelRustLibraryAidlInterface
import dev.soupslurpr.beautyxt.PathAndPfd
import dev.soupslurpr.beautyxt.beautyxt_rs_typst_bindings.TypstCustomSeverity
import dev.soupslurpr.beautyxt.beautyxt_rs_typst_bindings.TypstCustomSourceDiagnostic
import dev.soupslurpr.beautyxt.beautyxt_rs_typst_bindings.TypstCustomTracepoint
import dev.soupslurpr.beautyxt.data.TypstProjectUiState
import dev.soupslurpr.beautyxt.returnHashSha256
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.FileOutputStream

private const val TAG = "TypstProjectViewModel"

class TypstProjectViewModel(application: Application) : AndroidViewModel(application) {

    /**
     * File state for this file
     */
    private val _uiState = MutableStateFlow(TypstProjectUiState())
    val uiState: StateFlow<TypstProjectUiState> = _uiState.asStateFlow()

    var rustService: ITypstProjectViewModelRustLibraryAidlInterface? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val rustService = ITypstProjectViewModelRustLibraryAidlInterface.Stub.asInterface(service)

            this@TypstProjectViewModel.rustService = rustService

            rustService.initializeTypstWorld()

            openProject(application.applicationContext)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            rustService = null
        }
    }

    private var intentService = Intent(getApplication(), TypstProjectViewModelRustLibraryIsolatedService::class.java)

    fun bindService(projectFolderUri: Uri) {
        _uiState.value.projectFolderUri.value = projectFolderUri
        getApplication<Application>().bindIsolatedService(
            intentService,
            Context.BIND_AUTO_CREATE,
            returnHashSha256(projectFolderUri.toString().toByteArray()),
            ContextCompat.getMainExecutor(getApplication<Application>().applicationContext),
            serviceConnection,
        )
    }

    private fun stopAndUnbindService() {
        getApplication<Application>().stopService(intentService)
        try {
            getApplication<Application>().unbindService(serviceConnection)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Failed to unbind service: $e")
        }
    }

    fun openProject(context: Context) {
        viewModelScope.launch {
            val files: MutableList<PathAndPfd> = mutableListOf()
            val filesQueue = ArrayDeque<DocumentFile>()

            filesQueue.addAll(
                DocumentFile.fromTreeUri(context, uiState.value.projectFolderUri.value)?.listFiles().orEmpty()
            )

            while (filesQueue.isNotEmpty()) {
                val file = filesQueue.removeFirst()
                if (file.isDirectory) {
                    filesQueue.addAll(file.listFiles())
                } else {
                    @SuppressLint("Recycle") // We close the file descriptor in the Rust code
                    val parcelFileDescriptor = context.contentResolver.openAssetFileDescriptor(file.uri, "rw")
                        ?.parcelFileDescriptor
                    if (parcelFileDescriptor != null) {
                        val projectFile = PathAndPfd(
                            file.uri.lastPathSegment?.removePrefix(
                                uiState.value.projectFolderUri.value
                                    .lastPathSegment.orEmpty()
                            ).orEmpty(), parcelFileDescriptor
                        )
                        if (projectFile.path == "/main.typ") {
                            _uiState.value.currentOpenedPath.value = "/main.typ"
                            _uiState.value.currentOpenedDisplayName.value = "main.typ"
                            rustService!!.setMainTypstProjectFile(projectFile)
                            _uiState.value.currentOpenedContent.value =
                                rustService!!.getTypstProjectFileText(
                                    uiState.value
                                        .currentOpenedPath.value
                                )
                        } else {
                            files.add(projectFile)
                        }
                    }
                }
            }

            rustService!!.addTypstProjectFiles(files)

            if (uiState.value.currentOpenedPath.value == "") {
                DocumentFile.fromTreeUri(context, uiState.value.projectFolderUri.value)?.createFile(
                    "typst/application",
                    "main.typ"
                )
                _uiState.value.currentOpenedPath.value = "/main.typ"
                _uiState.value.currentOpenedDisplayName.value = "main.typ"
                refreshProjectFiles(context)

                files.clear()
                filesQueue.clear()

                filesQueue.addAll(
                    DocumentFile.fromTreeUri(context, uiState.value.projectFolderUri.value)?.listFiles().orEmpty()
                )

                while (filesQueue.isNotEmpty()) {
                    val file = filesQueue.removeFirst()
                    if (file.isDirectory) {
                        filesQueue.addAll(file.listFiles())
                    } else {
                        @SuppressLint("Recycle") // We close the file descriptor in the Rust code
                        val parcelFileDescriptor = context.contentResolver.openAssetFileDescriptor(file.uri, "rw")
                            ?.parcelFileDescriptor
                        if (parcelFileDescriptor != null) {
                            val projectFile = PathAndPfd(
                                file.uri.lastPathSegment?.removePrefix(
                                    uiState.value.projectFolderUri.value
                                        .lastPathSegment.orEmpty()
                                ).orEmpty(),
                                parcelFileDescriptor
                            )
                            if (projectFile.path == "/main.typ") {
                                _uiState.value.currentOpenedPath.value = "/main.typ"
                                _uiState.value.currentOpenedDisplayName.value = "main.typ"
                                rustService!!.setMainTypstProjectFile(projectFile)
                                _uiState.value.currentOpenedContent.value =
                                    rustService!!.getTypstProjectFileText(
                                        uiState.value.currentOpenedPath.value
                                    )
                            } else {
                                files.add(projectFile)
                            }
                        }
                    }
                }

                rustService!!.addTypstProjectFiles(files)
            }

            renderProjectToSvgs(rustService!!)
        }
    }

    fun exportDocumentToPdf(exportUri: Uri, context: Context) {
        viewModelScope.launch {
            val pdf = rustService!!.getTypstPdf()

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
    }

    fun renderProjectToSvgs(rustService: ITypstProjectViewModelRustLibraryAidlInterface) {
        viewModelScope.launch {
            var noException = true

            val bundle = rustService.getTypstSvg()

            val svg = bundle.getByteArray("svg")

            if (svg != null) {
                _uiState.value.renderedProjectSvg.value = svg
            } else {
                var sourceDiagnostics: MutableList<TypstCustomSourceDiagnostic> = mutableStateListOf()

                var index = 0
                while (true) {
                    val severity = bundle.getString("severity$index")
                    val span = if (bundle.containsKey("span$index")) {
                        bundle.getLong("span$index")
                    } else {
                        null
                    }
                    val message = bundle.getString("message$index")
                    val trace = bundle.getInt("trace$index")

                    val sourceDiagnostic = TypstCustomSourceDiagnostic(
                        severity = when (severity) {
                            "WARNING" -> TypstCustomSeverity.WARNING
                            "ERROR" -> TypstCustomSeverity.ERROR
                            else -> break
                        },
                        span = when (span) {
                            null -> break
                            else -> span.toULong()
                        },
                        message = when (message) {
                            null -> break
                            else -> message
                        },
                        trace = if (trace >= 0) {
                            val traceList: MutableList<TypstCustomTracepoint> = mutableListOf()
                            trace.downTo(0).reversed().forEach { traceIndex ->
                                val prefix = "trace${index}name${traceIndex}"
                                traceList.add(
                                    when (bundle.getString(prefix)) {
                                        "Call" -> TypstCustomTracepoint.Call(
                                            string = bundle.getString("${prefix}string"),
                                            span = bundle.getLong("${prefix}span").toULong(),
                                        )

                                        "Import" -> TypstCustomTracepoint.Import(
                                            bundle.getLong("${prefix}span").toULong()
                                        )

                                        "Show" -> TypstCustomTracepoint.Show(
                                            bundle.getString("${prefix}string") ?: return@forEach,
                                            bundle.getLong("${prefix}span").toULong()
                                        )

                                        null -> return@forEach
                                        else -> return@forEach
                                    }
                                )
                            }
                            traceList
                        } else {
                            listOf()
                        },
                        hints = bundle.getStringArrayList("hints$index").let {
                            it ?: ArrayList()
                        }
                    )
                    sourceDiagnostics.add(sourceDiagnostic)

                    index += 1
                }

                _uiState.value.sourceDiagnostics.clear()
                _uiState.value.sourceDiagnostics.addAll(sourceDiagnostics)
                noException = false
            }

            if (noException) {
                _uiState.value.sourceDiagnostics.clear()
            }
        }
    }

    fun setCurrentOpenedPath(uri: Uri, contentResolver: ContentResolver) {
        val projectFolderUri = uiState.value.projectFolderUri.value

        _uiState.value.currentOpenedPath.value = uri.lastPathSegment?.removePrefix(
            projectFolderUri.lastPathSegment
                .orEmpty()
        ).orEmpty()

        setTypstProjectFileText(uiState.value.currentOpenedPath.value)

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
        viewModelScope.launch {
            val projectFolderUri = uiState.value.projectFolderUri.value

            val files: MutableList<PathAndPfd> = mutableListOf()
            val filesQueue = ArrayDeque<DocumentFile>()

            filesQueue.addAll(DocumentFile.fromTreeUri(context, projectFolderUri)?.listFiles().orEmpty())

            while (filesQueue.isNotEmpty()) {
                val file = filesQueue.removeFirst()
                if (file.isDirectory) {
                    filesQueue.addAll(file.listFiles())
                } else {
                    @SuppressLint("Recycle") // We close the file descriptor in the Rust code
                    val parcelFileDescriptor = context.contentResolver.openAssetFileDescriptor(file.uri, "rw")
                        ?.parcelFileDescriptor
                    if (parcelFileDescriptor != null) {
                        val projectFile = PathAndPfd(
                            file.uri.lastPathSegment?.removePrefix(
                                projectFolderUri
                                    .lastPathSegment.orEmpty()
                            ).orEmpty(),
                            parcelFileDescriptor
                        )
                        if (projectFile.path == "/main.typ") {
                            rustService!!.setMainTypstProjectFile(projectFile)
                        } else {
                            files.add(projectFile)
                        }
                    }
                }
            }

            rustService!!.addTypstProjectFiles(files)
        }
    }

    fun updateProjectFileWithNewText(newText: String, path: String) {
        viewModelScope.launch {
            _uiState.value.currentOpenedContent.value =
                rustService!!.updateTypstProjectFile(newText, path)
        }
    }

    fun setTypstProjectFileText(path: String) {
        viewModelScope.launch {
            _uiState.value.currentOpenedContent.value = rustService!!.getTypstProjectFileText(path)
        }
    }

    /** Set uiState to default values */
    fun clearUiState() {
        viewModelScope.launch {
            _uiState.value = TypstProjectUiState()
            try {
                rustService!!.clearTypstProjectFiles()
            } catch (e: DeadObjectException) {
                Log.w(
                    TAG,
                    "Failed to call clearTypstProjectFiles(), might have already cleared as service is dead $e"
                )
            }
            stopAndUnbindService()
        }
    }

    override fun onCleared() {
        super.onCleared()
        clearUiState()
    }
}