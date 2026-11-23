/* Persists content-free new-document autosave state and owns the single persisted tree grant. */
package dev.soupslurpr.beautyxt.ui.autosave

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.provider.DocumentsContract
import android.util.AtomicFile
import androidx.core.net.toUri
import dev.soupslurpr.beautyxt.R
import dev.soupslurpr.beautyxt.document.DocumentFormat
import dev.soupslurpr.beautyxt.importing.client.BoundedProviderQueryRunner
import dev.soupslurpr.beautyxt.importing.client.sanitizeSelectedDocumentDisplayName
import dev.soupslurpr.beautyxt.normalizedProviderAuthority
import dev.soupslurpr.beautyxt.recordObservedTreeGrantAuthority
import dev.soupslurpr.beautyxt.ui.UiText
import dev.soupslurpr.beautyxt.ui.designsystem.matchesPublishedBytes
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val FOLDER_PROBE_TIMEOUT_MILLIS = 5_000L
private const val PERSISTABLE_READ_WRITE_FLAGS =
    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

/** Describes why an enabled autosave folder cannot currently create new files. */
internal enum class NewDocumentAutosaveUnavailableReason {
    RevokedOrMissing,
    InterruptedChange,
    TransientUnavailable
}

/** Describes the current resolved state of opt-in new-document autosave. */
internal sealed interface NewDocumentAutosaveStatus {
    /** Indicates that new documents remain in memory until explicitly saved. */
    data object Disabled : NewDocumentAutosaveStatus

    /** Indicates that one verified persisted tree grant is ready for new documents. */
    data class Enabled(val treeUri: String, val folderName: String?) : NewDocumentAutosaveStatus {
        init {
            require(treeUri.isNotBlank()) { "enabled tree URI must not be blank" }
        }
    }

    /** Indicates that autosave was turned on, but its folder cannot currently be used. */
    data class NeedsReselection(
        val reason: NewDocumentAutosaveUnavailableReason,
        val treeUri: String? = null
    ) : NewDocumentAutosaveStatus
}

/** Represents the observable lifecycle of [NewDocumentAutosaveStore]. */
internal sealed interface NewDocumentAutosaveStoreState {
    data object Loading : NewDocumentAutosaveStoreState
    data object LoadFailed : NewDocumentAutosaveStoreState
    data class Ready(
        val status: NewDocumentAutosaveStatus,
        val format: DocumentFormat = DocumentFormat.PlainText,
        val generation: Long = 0L,
        val hasActiveLeases: Boolean = false,
        val busy: Boolean = false,
        val saveFailed: Boolean = false,
        val errorMessage: UiText? = null
    ) : NewDocumentAutosaveStoreState
}

/** Outcome of atomically revalidating the enabled folder and acquiring a creation lease. */
internal sealed interface CreationLeaseResult {
    data class Acquired(
        val treeUri: String,
        val format: DocumentFormat,
        val generation: Long,
        val lease: AutoCloseable
    ) : CreationLeaseResult

    data object Disabled : CreationLeaseResult
    data object Deferred : CreationLeaseResult
    data class Unavailable(val reason: NewDocumentAutosaveUnavailableReason) : CreationLeaseResult
}

/** Snapshot of one Android persisted URI permission owned by this app. */
internal data class PersistedTreeGrant(
    val uri: String,
    val isReadPermission: Boolean,
    val isWritePermission: Boolean,
    val isTreeUri: Boolean,
    val persistedTime: Long = 0L
) {
    val isUsableReadWriteTree: Boolean
        get() = isReadPermission && isWritePermission && isTreeUri
}

/** Result of inspecting one tree root document without creating any probe file. */
internal sealed interface FolderProbeResult {
    data class Available(val displayName: String?) : FolderProbeResult
    data object NotWritableOrMissing : FolderProbeResult
    data object TransientFailure : FolderProbeResult
}

/** Abstracts disk storage for the content-free autosave marker. */
internal interface NewDocumentAutosaveStorage {
    fun read(): NewDocumentAutosaveMarker
    fun write(marker: NewDocumentAutosaveMarker)
    fun reset()
}

/** Abstracts Android SAF persisted-grant and directory-probe operations for testing. */
internal interface NewDocumentFolderAccess {
    fun isValidTreeUri(encodedUri: String): Boolean
    fun listPersistedGrants(): List<PersistedTreeGrant>
    fun takePersistableReadWriteGrant(encodedUri: String)
    fun releasePersistableReadWriteGrant(encodedUri: String)
    suspend fun probeTreeFolder(encodedUri: String): FolderProbeResult
}

private fun definitelyAbsent(file: File): Boolean =
    Files.notExists(file.toPath(), LinkOption.NOFOLLOW_LINKS)

/** AtomicFile owns backup recovery, including when the base file is missing. */
internal fun readSavedNewDocumentAutosaveMarker(
    base: File,
    openRead: () -> InputStream
): NewDocumentAutosaveMarker {
    val stream = try {
        openRead()
    } catch (missing: FileNotFoundException) {
        if (definitelyAbsent(base) && definitelyAbsent(File(base.path + ".bak"))) {
            return NewDocumentAutosaveMarker()
        }
        throw missing
    }
    return stream.use(::readNewDocumentAutosaveMarker)
}

/** Writes the content-free marker atomically and deletes it completely when disabled. */
internal class AtomicNewDocumentAutosaveStorage(
    private val resolveBaseFile: () -> File
) : NewDocumentAutosaveStorage {
    private var cachedFile: AtomicFile? = null

    private fun atomicFile(): AtomicFile =
        cachedFile ?: AtomicFile(resolveBaseFile()).also { cachedFile = it }

    override fun read(): NewDocumentAutosaveMarker {
        val file = atomicFile()
        return readSavedNewDocumentAutosaveMarker(file.baseFile, file::openRead)
    }

    override fun write(marker: NewDocumentAutosaveMarker) {
        if (marker.isDefault) {
            reset()
            return
        }
        val file = atomicFile()
        val encoded = formatNewDocumentAutosaveMarker(marker).toByteArray(Charsets.UTF_8)
        val stream = file.startWrite()
        try {
            stream.write(encoded)
            stream.flush()
            stream.fd.sync()
            file.finishWrite(stream)
        } catch (failure: Throwable) {
            file.failWrite(stream)
            throw failure
        }
        syncParentDirectory(file.baseFile)
        if (!file.openRead().use { matchesPublishedBytes(it, encoded) }) {
            throw IOException("New-document autosave marker write was not published")
        }
    }

    override fun reset() {
        val file = atomicFile()
        file.delete()
        if (!listOf("", ".bak", ".new").all { definitelyAbsent(File(file.baseFile.path + it)) }) {
            throw IOException("Could not remove saved new-document autosave marker")
        }
        syncParentDirectory(file.baseFile)
    }

    private fun syncParentDirectory(baseFile: File) {
        val parent = baseFile.parentFile ?: return
        FileChannel.open(parent.toPath(), StandardOpenOption.READ).use { channel ->
            channel.force(true)
        }
    }
}

/** Inspects and manages Android SAF persisted tree grants without persisting URIs on disk. */
internal class AndroidNewDocumentFolderAccess(
    context: Context,
    queryDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)
) : NewDocumentFolderAccess {
    private val applicationContext = context.applicationContext
    private val contentResolver = applicationContext.contentResolver
    private val probeRunner =
        BoundedProviderQueryRunner(
            timeoutMillis = FOLDER_PROBE_TIMEOUT_MILLIS,
            dispatcher = queryDispatcher
        )

    override fun isValidTreeUri(encodedUri: String): Boolean {
        if (encodedUri.isBlank()) return false
        return try {
            val uri = encodedUri.toUri()
            val authority = uri.authority
            val segments = uri.pathSegments
            uri.scheme == ContentResolver.SCHEME_CONTENT &&
                !authority.isNullOrBlank() &&
                '@' !in authority &&
                DocumentsContract.isTreeUri(uri) &&
                segments.size == 2 &&
                segments[0] == "tree" &&
                !DocumentsContract.getTreeDocumentId(uri).isNullOrBlank()
        } catch (_: Exception) {
            false
        }
    }

    override fun listPersistedGrants(): List<PersistedTreeGrant> =
        contentResolver.persistedUriPermissions.map { permission ->
            val uri = permission.uri
            recordObservedTreeGrantAuthority(uri)
            val encoded = uri.toString()
            PersistedTreeGrant(
                uri = encoded,
                isReadPermission = permission.isReadPermission,
                isWritePermission = permission.isWritePermission,
                isTreeUri = isValidTreeUri(encoded),
                persistedTime = permission.persistedTime
            )
        }

    override fun takePersistableReadWriteGrant(encodedUri: String) {
        val uri = encodedUri.toUri()
        recordObservedTreeGrantAuthority(uri)
        contentResolver.takePersistableUriPermission(uri, PERSISTABLE_READ_WRITE_FLAGS)
    }

    override fun releasePersistableReadWriteGrant(encodedUri: String) {
        val uri = encodedUri.toUri()
        recordObservedTreeGrantAuthority(uri)
        try {
            contentResolver.releasePersistableUriPermission(uri, PERSISTABLE_READ_WRITE_FLAGS)
        } catch (_: SecurityException) {
            releaseIndividualFlagQuietly(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            releaseIndividualFlagQuietly(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    }

    override suspend fun probeTreeFolder(encodedUri: String): FolderProbeResult {
        if (!isValidTreeUri(encodedUri)) {
            return FolderProbeResult.NotWritableOrMissing
        }
        val treeUri = encodedUri.toUri()
        val rootDocumentUri = try {
            DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri)
            )
        } catch (_: Exception) {
            return FolderProbeResult.NotWritableOrMissing
        }
        val cancellationSignal = CancellationSignal()
        val outcome = probeRunner.query(cancellationAction = cancellationSignal::cancel) {
            probeRootRow(rootDocumentUri, cancellationSignal)
        }
        return outcome ?: FolderProbeResult.TransientFailure
    }

    private fun probeRootRow(
        rootDocumentUri: Uri,
        cancellationSignal: CancellationSignal
    ): FolderProbeResult = try {
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS
        )
        contentResolver.query(rootDocumentUri, projection, Bundle.EMPTY, cancellationSignal)?.use { cursor ->
            if (!cursor.moveToFirst()) {
                return@use FolderProbeResult.NotWritableOrMissing
            }
            val mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val mimeType = mimeIndex
                .takeIf { it >= 0 && !cursor.isNull(it) }
                ?.let(cursor::getString)
            if (mimeType != DocumentsContract.Document.MIME_TYPE_DIR) {
                return@use FolderProbeResult.NotWritableOrMissing
            }
            val flagsIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)
            val flags = flagsIndex
                .takeIf { it >= 0 && !cursor.isNull(it) }
                ?.let(cursor::getInt)
                ?: 0
            if ((flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE) == 0) {
                return@use FolderProbeResult.NotWritableOrMissing
            }
            val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val displayName = nameIndex
                .takeIf { it >= 0 && !cursor.isNull(it) }
                ?.let(cursor::getString)
                ?.let(::sanitizeSelectedDocumentDisplayName)
            FolderProbeResult.Available(displayName)
        } ?: FolderProbeResult.NotWritableOrMissing
    } catch (_: SecurityException) {
        FolderProbeResult.NotWritableOrMissing
    } catch (_: IllegalArgumentException) {
        FolderProbeResult.NotWritableOrMissing
    } catch (_: IllegalStateException) {
        FolderProbeResult.TransientFailure
    } catch (_: Exception) {
        FolderProbeResult.TransientFailure
    }

    private fun releaseIndividualFlagQuietly(uri: Uri, flag: Int) {
        try {
            contentResolver.releasePersistableUriPermission(uri, flag)
        } catch (_: SecurityException) {
            // Already absent.
        }
    }
}

/**
 * Owns opt-in new-document autosave preferences and the app's sole persisted SAF tree grant.
 *
 * All state mutations, grant transitions, and lease accounting serialize through [mutex] so
 * `state=enabled` always corresponds to at most one persisted tree grant, and disabling or
 * changing folders cannot proceed while an open editor actively leases that tree grant.
 */
internal class NewDocumentAutosaveStore(
    private val storage: NewDocumentAutosaveStorage,
    private val folderAccess: NewDocumentFolderAccess,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    constructor(context: Context) : this(
        storage = AtomicNewDocumentAutosaveStorage {
            File(context.noBackupFilesDir, NEW_DOCUMENT_AUTOSAVE_FILE_NAME)
        },
        folderAccess = AndroidNewDocumentFolderAccess(context),
        dispatcher = Dispatchers.IO
    )

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutex = Mutex()
    private val leaseLock = Any()
    private val activeLeases = mutableMapOf<String, Int>()
    private var generation = 0L
    private var committedGrantTime: Long? = null

    private val mutableState =
        MutableStateFlow<NewDocumentAutosaveStoreState>(NewDocumentAutosaveStoreState.Loading)
    val state = mutableState.asStateFlow()

    init {
        scope.launch {
            mutex.withLock {
                if (mutableState.value is NewDocumentAutosaveStoreState.Loading) {
                    loadAndReconcileLocked()
                }
            }
        }
    }

    /** Re-checks the configured folder grant and live display name when settings open. */
    fun refresh() {
        scope.launch {
            mutex.withLock {
                if (mutableState.value is NewDocumentAutosaveStoreState.Ready) {
                    loadAndReconcileLocked(clearError = false)
                }
            }
        }
    }

    /** Retries loading the marker file after [NewDocumentAutosaveStoreState.LoadFailed]. */
    fun retryLoad() {
        scope.launch {
            mutex.withLock {
                if (mutableState.value != NewDocumentAutosaveStoreState.LoadFailed) return@withLock
                mutableState.value = NewDocumentAutosaveStoreState.Loading
                loadAndReconcileLocked()
            }
        }
    }

    /** Clears a corrupt marker file and releases any persisted grants. */
    fun resetSaved() {
        scope.launch {
            mutex.withLock {
                if (mutableState.value != NewDocumentAutosaveStoreState.LoadFailed) return@withLock
                if (hasAnyActiveLease()) return@withLock
                mutableState.value = NewDocumentAutosaveStoreState.Loading
                try {
                    releaseGrantsLocked(keepUri = null)
                    if (folderAccess.listPersistedGrants().isNotEmpty()) {
                        mutableState.value = NewDocumentAutosaveStoreState.LoadFailed
                        return@withLock
                    }
                    storage.reset()
                    generation = Math.incrementExact(generation)
                    publishReadyLocked(
                        status = NewDocumentAutosaveStatus.Disabled,
                        format = DocumentFormat.PlainText
                    )
                } catch (_: IOException) {
                    mutableState.value = NewDocumentAutosaveStoreState.LoadFailed
                } catch (_: SecurityException) {
                    mutableState.value = NewDocumentAutosaveStoreState.LoadFailed
                }
            }
        }
    }

    /** Validates and commits one user-selected SAF tree folder as the sole persisted grant. */
    fun selectFolder(
        encodedUri: String,
        offeredRead: Boolean,
        offeredWrite: Boolean,
        offeredPersistable: Boolean,
        offeredPrefix: Boolean = true
    ) {
        scope.launch {
            mutex.withLock {
                val current = mutableState.value as? NewDocumentAutosaveStoreState.Ready
                    ?: return@withLock
                val format = current.format
                if (
                    !offeredRead ||
                    !offeredWrite ||
                    !offeredPersistable ||
                    !offeredPrefix ||
                    !folderAccess.isValidTreeUri(encodedUri)
                ) {
                    mutableState.value = current.copy(
                        busy = false,
                        errorMessage = UiText.Resource(R.string.autosave_folder_unusable)
                    )
                    return@withLock
                }

                val previousTreeUri = when (val status = current.status) {
                    is NewDocumentAutosaveStatus.Enabled -> status.treeUri
                    is NewDocumentAutosaveStatus.NeedsReselection -> status.treeUri
                    NewDocumentAutosaveStatus.Disabled -> null
                }
                val alreadyHeld = folderAccess.listPersistedGrants().any {
                    it.uri == encodedUri && it.isUsableReadWriteTree
                }

                // Selecting a folder whose persisted grant is still held is idempotent and refreshes
                // health directly without writing Changing or hitting an active-lease block.
                if (previousTreeUri == encodedUri && alreadyHeld) {
                    mutableState.value = current.copy(busy = true, saveFailed = false, errorMessage = null)
                    when (val probe = folderAccess.probeTreeFolder(encodedUri)) {
                        is FolderProbeResult.Available -> {
                            try {
                                val grantTime = folderAccess.listPersistedGrants()
                                    .filter { it.uri == encodedUri && it.isUsableReadWriteTree }
                                    .maxOfOrNull { it.persistedTime }
                                    ?.takeIf { it > 0L }
                                    ?: committedGrantTime
                                storage.write(
                                    NewDocumentAutosaveMarker(
                                        state = NewDocumentAutosaveMarkerState.Enabled,
                                        format = format,
                                        grantTime = grantTime
                                    )
                                )
                                committedGrantTime = grantTime
                            } catch (_: Exception) {
                                mutableState.value = current.copy(
                                    busy = false,
                                    saveFailed = true,
                                    errorMessage = UiText.Resource(R.string.autosave_save_failed)
                                )
                                return@withLock
                            }
                            publishReadyLocked(
                                status = NewDocumentAutosaveStatus.Enabled(encodedUri, probe.displayName),
                                format = format
                            )
                        }

                        FolderProbeResult.NotWritableOrMissing -> {
                            publishReadyLocked(
                                status = NewDocumentAutosaveStatus.NeedsReselection(
                                    NewDocumentAutosaveUnavailableReason.RevokedOrMissing,
                                    treeUri = encodedUri
                                ),
                                format = format,
                                errorMessage = UiText.Resource(R.string.autosave_folder_unusable)
                            )
                        }

                        FolderProbeResult.TransientFailure -> {
                            publishReadyLocked(
                                status = NewDocumentAutosaveStatus.NeedsReselection(
                                    NewDocumentAutosaveUnavailableReason.TransientUnavailable,
                                    treeUri = encodedUri
                                ),
                                format = format,
                                errorMessage = UiText.Resource(R.string.autosave_folder_unavailable)
                            )
                        }
                    }
                    return@withLock
                }

                // Refuse switching to a different folder while an open document leases the current one
                // so Android never holds two durable persisted grants simultaneously.
                if (hasOtherActiveLease(encodedUri)) {
                    mutableState.value = current.copy(
                        busy = false,
                        hasActiveLeases = true,
                        errorMessage = UiText.Resource(R.string.autosave_lease_notice)
                    )
                    return@withLock
                }

                mutableState.value = current.copy(busy = true, saveFailed = false, errorMessage = null)

                // Probe the candidate first using the temporary picker grant before altering the
                // marker or releasing any existing grant, so failures leave current state untouched.
                val probedName = when (val probe = folderAccess.probeTreeFolder(encodedUri)) {
                    is FolderProbeResult.Available -> probe.displayName

                    FolderProbeResult.NotWritableOrMissing -> {
                        mutableState.value = current.copy(
                            busy = false,
                            errorMessage = UiText.Resource(R.string.autosave_folder_unusable)
                        )
                        return@withLock
                    }

                    FolderProbeResult.TransientFailure -> {
                        mutableState.value = current.copy(
                            busy = false,
                            errorMessage = UiText.Resource(R.string.autosave_folder_unavailable)
                        )
                        return@withLock
                    }
                }

                // Single-grant commit sequence: mark changing -> release old & verify empty ->
                // take new & verify sole grant -> mark enabled. Android never holds two durable
                // persisted grants simultaneously.
                try {
                    storage.write(
                        NewDocumentAutosaveMarker(
                            state = NewDocumentAutosaveMarkerState.Changing,
                            format = format
                        )
                    )
                } catch (_: Exception) {
                    rollbackFailedSelectionLocked(
                        previous = current,
                        failedNewUri = encodedUri,
                        releaseNewGrant = false,
                        errorMessage = UiText.Resource(R.string.autosave_save_failed)
                    )
                    return@withLock
                }

                var tookNewGrant = false
                try {
                    releaseGrantsLocked(keepUri = null)
                    if (folderAccess.listPersistedGrants().isNotEmpty()) {
                        rollbackFailedSelectionLocked(
                            previous = current,
                            failedNewUri = encodedUri,
                            releaseNewGrant = false,
                            errorMessage = UiText.Resource(R.string.autosave_save_failed)
                        )
                        return@withLock
                    }
                    folderAccess.takePersistableReadWriteGrant(encodedUri)
                    tookNewGrant = true
                    val remainingGrants = folderAccess.listPersistedGrants()
                    val verifiedSoleGrant =
                        remainingGrants.isNotEmpty() &&
                            remainingGrants.all { it.uri == encodedUri && it.isUsableReadWriteTree }
                    if (!verifiedSoleGrant) {
                        rollbackFailedSelectionLocked(
                            previous = current,
                            failedNewUri = encodedUri,
                            releaseNewGrant = true,
                            errorMessage = UiText.Resource(R.string.autosave_folder_unusable)
                        )
                        return@withLock
                    }
                    val grantTime = remainingGrants
                        .maxOfOrNull { it.persistedTime }
                        ?.takeIf { it > 0L }
                    storage.write(
                        NewDocumentAutosaveMarker(
                            state = NewDocumentAutosaveMarkerState.Enabled,
                            format = format,
                            grantTime = grantTime
                        )
                    )
                    committedGrantTime = grantTime
                    generation = Math.incrementExact(generation)
                    publishReadyLocked(
                        status = NewDocumentAutosaveStatus.Enabled(
                            treeUri = encodedUri,
                            folderName = probedName
                        ),
                        format = format
                    )
                } catch (_: Exception) {
                    rollbackFailedSelectionLocked(
                        previous = current,
                        failedNewUri = encodedUri,
                        releaseNewGrant = tookNewGrant,
                        errorMessage = UiText.Resource(R.string.autosave_folder_unusable)
                    )
                }
            }
        }
    }

    /** Updates the default format for auto-created new documents without changing the tree grant. */
    fun setFormat(format: DocumentFormat) {
        scope.launch {
            mutex.withLock {
                val current = mutableState.value as? NewDocumentAutosaveStoreState.Ready
                    ?: return@withLock
                if (current.status is NewDocumentAutosaveStatus.Disabled) return@withLock
                if (current.format == format && !current.saveFailed) return@withLock
                val markerState =
                    if (
                        current.status is NewDocumentAutosaveStatus.NeedsReselection &&
                        current.status.reason == NewDocumentAutosaveUnavailableReason.InterruptedChange
                    ) {
                        NewDocumentAutosaveMarkerState.Changing
                    } else {
                        NewDocumentAutosaveMarkerState.Enabled
                    }
                try {
                    storage.write(
                        NewDocumentAutosaveMarker(
                            state = markerState,
                            format = format,
                            grantTime = if (markerState == NewDocumentAutosaveMarkerState.Enabled) {
                                committedGrantTime
                            } else {
                                null
                            }
                        )
                    )
                    generation = Math.incrementExact(generation)
                    publishReadyLocked(
                        status = current.status,
                        format = format,
                        errorMessage = current.errorMessage
                    )
                } catch (_: Exception) {
                    mutableState.value = current.copy(
                        saveFailed = true,
                        errorMessage = UiText.Resource(R.string.autosave_save_failed)
                    )
                }
            }
        }
    }

    /** Turns off new-document autosave, deletes the marker file, and releases all persisted grants. */
    fun disable() {
        scope.launch {
            mutex.withLock {
                val current = mutableState.value as? NewDocumentAutosaveStoreState.Ready
                    ?: return@withLock
                if (hasAnyActiveLease()) {
                    mutableState.value = current.copy(
                        busy = false,
                        hasActiveLeases = true,
                        errorMessage = UiText.Resource(R.string.autosave_lease_notice)
                    )
                    return@withLock
                }
                mutableState.value = current.copy(busy = true, saveFailed = false, errorMessage = null)
                try {
                    releaseGrantsLocked(keepUri = null)
                    if (folderAccess.listPersistedGrants().isNotEmpty()) {
                        mutableState.value = current.copy(
                            busy = false,
                            saveFailed = true,
                            errorMessage = UiText.Resource(R.string.autosave_save_failed)
                        )
                        return@withLock
                    }
                    storage.reset()
                } catch (_: Exception) {
                    mutableState.value = current.copy(
                        busy = false,
                        saveFailed = true,
                        errorMessage = UiText.Resource(R.string.autosave_save_failed)
                    )
                    return@withLock
                }
                committedGrantTime = null
                generation = Math.incrementExact(generation)
                publishReadyLocked(
                    status = NewDocumentAutosaveStatus.Disabled,
                    format = DocumentFormat.PlainText
                )
            }
        }
    }

    /** Clears one displayed dialog error without altering saved folder state. */
    fun dismissError() {
        scope.launch {
            mutex.withLock {
                val current = mutableState.value as? NewDocumentAutosaveStoreState.Ready
                    ?: return@withLock
                if (current.errorMessage != null) {
                    mutableState.value = current.copy(errorMessage = null)
                }
            }
        }
    }

    /**
     * Atomically waits for initial load, checks [expectedGeneration], revalidates the persisted
     * folder grant, and acquires a lease under [mutex] off the main dispatcher so settings
     * mutations cannot race creation. Releases the lease if cancellation drops the return value.
     */
    suspend fun revalidateAndAcquireCreationLease(
        expectedGeneration: Long?
    ): CreationLeaseResult {
        val handedOffLease = AtomicReference<AutoCloseable?>(null)
        try {
            val result = withContext(dispatcher) {
                mutex.withLock {
                    if (
                        mutableState.value is NewDocumentAutosaveStoreState.Loading ||
                        mutableState.value is NewDocumentAutosaveStoreState.Ready
                    ) {
                        loadAndReconcileLocked(clearError = false)
                    }
                    val ready = mutableState.value as? NewDocumentAutosaveStoreState.Ready
                        ?: return@withLock CreationLeaseResult.Disabled
                    if (expectedGeneration != null && ready.generation != expectedGeneration) {
                        return@withLock CreationLeaseResult.Disabled
                    }
                    when (val status = ready.status) {
                        NewDocumentAutosaveStatus.Disabled -> CreationLeaseResult.Disabled
                        is NewDocumentAutosaveStatus.NeedsReselection ->
                            when (status.reason) {
                                NewDocumentAutosaveUnavailableReason.TransientUnavailable ->
                                    CreationLeaseResult.Deferred

                                NewDocumentAutosaveUnavailableReason.RevokedOrMissing,
                                NewDocumentAutosaveUnavailableReason.InterruptedChange ->
                                    CreationLeaseResult.Unavailable(status.reason)
                            }

                        is NewDocumentAutosaveStatus.Enabled -> {
                            val lease = acquireLeaseLocked(status.treeUri)
                            handedOffLease.set(lease)
                            CreationLeaseResult.Acquired(
                                treeUri = status.treeUri,
                                format = ready.format,
                                generation = ready.generation,
                                lease = lease
                            )
                        }
                    }
                }
            }
            handedOffLease.set(null)
            return result
        } catch (cancellation: CancellationException) {
            handedOffLease.getAndSet(null)?.close()
            throw cancellation
        }
    }

    /**
     * Acquires an in-memory lease for a reloaded document URI if it belongs to the active or
     * currently leased tree, even when folder creation readiness has moved to NeedsReselection.
     * Releases the lease if cancellation drops the return value across dispatcher boundaries.
     */
    suspend fun tryAcquireLeaseForDocumentUri(documentUri: Uri): AutoCloseable? {
        val handedOffLease = AtomicReference<AutoCloseable?>(null)
        try {
            val result = withContext(dispatcher) {
                mutex.withLock {
                    val candidateTrees = buildSet {
                        val ready = mutableState.value as? NewDocumentAutosaveStoreState.Ready
                        when (val status = ready?.status) {
                            is NewDocumentAutosaveStatus.Enabled -> add(status.treeUri)
                            is NewDocumentAutosaveStatus.NeedsReselection -> status.treeUri?.let(::add)
                            else -> Unit
                        }
                        synchronized(leaseLock) {
                            addAll(activeLeases.keys)
                        }
                    }
                    val matchedTree = candidateTrees.firstOrNull { encodedTree ->
                        val treeUri = encodedTree.toUri()
                        val sameAuthority =
                            normalizedProviderAuthority(documentUri) != null &&
                                normalizedProviderAuthority(documentUri) ==
                                normalizedProviderAuthority(treeUri)
                        if (!sameAuthority) {
                            false
                        } else {
                            try {
                                DocumentsContract.isTreeUri(documentUri) &&
                                    DocumentsContract.getTreeDocumentId(documentUri) ==
                                    DocumentsContract.getTreeDocumentId(treeUri)
                            } catch (_: Exception) {
                                false
                            }
                        }
                    } ?: return@withLock null
                    val lease = acquireLeaseLocked(matchedTree)
                    handedOffLease.set(lease)
                    lease
                }
            }
            handedOffLease.set(null)
            return result
        } catch (cancellation: CancellationException) {
            handedOffLease.getAndSet(null)?.close()
            throw cancellation
        }
    }

    /** Acquires an in-memory lease directly for testing and reload handoff. */
    internal suspend fun acquireLease(treeUri: String): AutoCloseable = mutex.withLock {
        acquireLeaseLocked(treeUri)
    }

    private fun acquireLeaseLocked(treeUri: String): AutoCloseable {
        require(treeUri.isNotBlank()) { "leased tree URI must not be blank" }
        synchronized(leaseLock) {
            activeLeases[treeUri] = (activeLeases[treeUri] ?: 0) + 1
        }
        syncLeaseIndicatorLocked()
        val released = AtomicBoolean(false)
        return AutoCloseable {
            if (!released.compareAndSet(false, true)) return@AutoCloseable
            synchronized(leaseLock) {
                val remaining = (activeLeases[treeUri] ?: 0) - 1
                if (remaining <= 0) {
                    activeLeases.remove(treeUri)
                } else {
                    activeLeases[treeUri] = remaining
                }
            }
            scope.launch {
                mutex.withLock {
                    syncLeaseIndicatorLocked()
                }
            }
        }
    }

    private fun hasAnyActiveLease(): Boolean = synchronized(leaseLock) { activeLeases.isNotEmpty() }

    private fun hasOtherActiveLease(treeUri: String): Boolean =
        synchronized(leaseLock) { activeLeases.keys.any { it != treeUri } }

    private fun isTreeLeased(treeUri: String): Boolean =
        synchronized(leaseLock) { treeUri in activeLeases }

    private fun syncLeaseIndicatorLocked() {
        val current = mutableState.value as? NewDocumentAutosaveStoreState.Ready ?: return
        val leased = hasAnyActiveLease()
        if (current.hasActiveLeases != leased) {
            mutableState.value = current.copy(hasActiveLeases = leased)
        }
    }

    private suspend fun loadAndReconcileLocked(clearError: Boolean = true) {
        val marker = try {
            storage.read()
        } catch (_: IOException) {
            mutableState.value = NewDocumentAutosaveStoreState.LoadFailed
            return
        } catch (_: IllegalArgumentException) {
            mutableState.value = NewDocumentAutosaveStoreState.LoadFailed
            return
        } catch (_: SecurityException) {
            mutableState.value = NewDocumentAutosaveStoreState.LoadFailed
            return
        }

        val previousError = if (clearError) {
            null
        } else {
            (mutableState.value as? NewDocumentAutosaveStoreState.Ready)?.errorMessage
        }

        when (marker.state) {
            NewDocumentAutosaveMarkerState.Disabled -> {
                if (!hasAnyActiveLease()) {
                    releaseGrantsLocked(keepUri = null)
                    val remainingGrants = try {
                        folderAccess.listPersistedGrants()
                    } catch (_: Exception) {
                        mutableState.value = NewDocumentAutosaveStoreState.LoadFailed
                        return
                    }
                    if (remainingGrants.isNotEmpty()) {
                        mutableState.value = NewDocumentAutosaveStoreState.LoadFailed
                        return
                    }
                    publishReadyLocked(
                        status = NewDocumentAutosaveStatus.Disabled,
                        format = DocumentFormat.PlainText,
                        errorMessage = previousError
                    )
                } else {
                    publishReadyLocked(
                        status = NewDocumentAutosaveStatus.NeedsReselection(
                            NewDocumentAutosaveUnavailableReason.InterruptedChange
                        ),
                        format = DocumentFormat.PlainText,
                        errorMessage = previousError
                    )
                }
            }

            NewDocumentAutosaveMarkerState.Changing -> {
                if (!hasAnyActiveLease()) {
                    releaseGrantsLocked(keepUri = null)
                }
                publishReadyLocked(
                    status = NewDocumentAutosaveStatus.NeedsReselection(
                        NewDocumentAutosaveUnavailableReason.InterruptedChange
                    ),
                    format = marker.format,
                    errorMessage = previousError
                )
            }

            NewDocumentAutosaveMarkerState.Enabled -> {
                val grants = folderAccess.listPersistedGrants()
                val usableCandidates = grants.filter { it.isUsableReadWriteTree }
                val distinctUris = usableCandidates.map { it.uri }.distinct()
                val candidateUri = if (distinctUris.size == 1) distinctUris.single() else null
                val candidateGrants = if (candidateUri != null) {
                    usableCandidates.filter { it.uri == candidateUri }
                } else {
                    emptyList()
                }
                val grantTimeMatches = marker.grantTime == null ||
                    candidateGrants.any { it.persistedTime == marker.grantTime }
                if (candidateUri == null || !grantTimeMatches) {
                    committedGrantTime = null
                    if (!hasAnyActiveLease() && grants.isNotEmpty()) {
                        releaseGrantsLocked(keepUri = null)
                    }
                    publishReadyLocked(
                        status = NewDocumentAutosaveStatus.NeedsReselection(
                            if (candidateUri != null && !grantTimeMatches) {
                                NewDocumentAutosaveUnavailableReason.InterruptedChange
                            } else {
                                NewDocumentAutosaveUnavailableReason.RevokedOrMissing
                            }
                        ),
                        format = marker.format,
                        errorMessage = previousError
                    )
                    return
                }
                committedGrantTime = marker.grantTime
                    ?: candidateGrants.maxOfOrNull { it.persistedTime }?.takeIf { it > 0L }
                // Release any stray invalid grants while keeping the sole valid tree grant.
                releaseGrantsLocked(keepUri = candidateUri)
                when (val probe = folderAccess.probeTreeFolder(candidateUri)) {
                    is FolderProbeResult.Available -> {
                        publishReadyLocked(
                            status = NewDocumentAutosaveStatus.Enabled(
                                treeUri = candidateUri,
                                folderName = probe.displayName
                            ),
                            format = marker.format,
                            errorMessage = previousError
                        )
                    }

                    FolderProbeResult.NotWritableOrMissing -> {
                        publishReadyLocked(
                            status = NewDocumentAutosaveStatus.NeedsReselection(
                                NewDocumentAutosaveUnavailableReason.RevokedOrMissing,
                                treeUri = candidateUri
                            ),
                            format = marker.format,
                            errorMessage = previousError
                        )
                    }

                    FolderProbeResult.TransientFailure -> {
                        publishReadyLocked(
                            status = NewDocumentAutosaveStatus.NeedsReselection(
                                NewDocumentAutosaveUnavailableReason.TransientUnavailable,
                                treeUri = candidateUri
                            ),
                            format = marker.format,
                            errorMessage = previousError
                        )
                    }
                }
            }
        }
    }

    private fun rollbackFailedSelectionLocked(
        previous: NewDocumentAutosaveStoreState.Ready,
        failedNewUri: String,
        releaseNewGrant: Boolean,
        errorMessage: UiText
    ) {
        if (releaseNewGrant && !isTreeLeased(failedNewUri)) {
            folderAccess.releasePersistableReadWriteGrant(failedNewUri)
        }
        val previousEnabledUri = (previous.status as? NewDocumentAutosaveStatus.Enabled)?.treeUri
        val previousStillHeld = previousEnabledUri != null &&
            folderAccess.listPersistedGrants().any {
                it.uri == previousEnabledUri && it.isUsableReadWriteTree
            }
        try {
            when {
                previousStillHeld -> {
                    storage.write(
                        NewDocumentAutosaveMarker(
                            state = NewDocumentAutosaveMarkerState.Enabled,
                            format = previous.format,
                            grantTime = committedGrantTime
                        )
                    )
                    publishReadyLocked(
                        status = previous.status,
                        format = previous.format,
                        errorMessage = errorMessage
                    )
                }

                previous.status is NewDocumentAutosaveStatus.Disabled -> {
                    val remainingGrants = folderAccess.listPersistedGrants()
                    if (remainingGrants.isEmpty()) {
                        storage.reset()
                        publishReadyLocked(
                            status = NewDocumentAutosaveStatus.Disabled,
                            format = DocumentFormat.PlainText,
                            errorMessage = errorMessage
                        )
                    } else {
                        publishReadyLocked(
                            status = NewDocumentAutosaveStatus.NeedsReselection(
                                NewDocumentAutosaveUnavailableReason.InterruptedChange
                            ),
                            format = previous.format,
                            saveFailed = true,
                            errorMessage = errorMessage
                        )
                    }
                }

                else -> {
                    publishReadyLocked(
                        status = NewDocumentAutosaveStatus.NeedsReselection(
                            NewDocumentAutosaveUnavailableReason.InterruptedChange
                        ),
                        format = previous.format,
                        errorMessage = errorMessage
                    )
                }
            }
        } catch (_: Exception) {
            publishReadyLocked(
                status = NewDocumentAutosaveStatus.NeedsReselection(
                    NewDocumentAutosaveUnavailableReason.InterruptedChange
                ),
                format = previous.format,
                saveFailed = true,
                errorMessage = errorMessage
            )
        }
    }

    private fun releaseGrantsLocked(keepUri: String?) {
        val grants = try {
            folderAccess.listPersistedGrants()
        } catch (_: Exception) {
            return
        }
        grants.forEach { grant ->
            if (grant.uri != keepUri && !isTreeLeased(grant.uri)) {
                folderAccess.releasePersistableReadWriteGrant(grant.uri)
            }
        }
    }

    private fun publishReadyLocked(
        status: NewDocumentAutosaveStatus,
        format: DocumentFormat,
        saveFailed: Boolean = false,
        errorMessage: UiText? = null
    ) {
        mutableState.value = NewDocumentAutosaveStoreState.Ready(
            status = status,
            format = format,
            generation = generation,
            hasActiveLeases = hasAnyActiveLease(),
            busy = false,
            saveFailed = saveFailed,
            errorMessage = errorMessage
        )
    }
}
