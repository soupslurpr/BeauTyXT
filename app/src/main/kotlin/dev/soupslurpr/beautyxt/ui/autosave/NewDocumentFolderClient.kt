/* Creates one verified empty SAF child file in the user's opt-in autosave folder. */
package dev.soupslurpr.beautyxt.ui.autosave

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import dev.soupslurpr.beautyxt.R
import dev.soupslurpr.beautyxt.document.DocumentFormat
import dev.soupslurpr.beautyxt.document.DocumentRemovalAction
import dev.soupslurpr.beautyxt.document.DocumentRemovalCapabilities
import dev.soupslurpr.beautyxt.document.EditorDocumentSnapshot
import dev.soupslurpr.beautyxt.document.SourceVersion
import dev.soupslurpr.beautyxt.exporting.client.SourceDocumentExporter
import dev.soupslurpr.beautyxt.exporting.client.TransientSourceDescriptor
import dev.soupslurpr.beautyxt.importing.client.ImportedDocument
import dev.soupslurpr.beautyxt.importing.client.SelectedDocumentMetadata
import dev.soupslurpr.beautyxt.importing.client.SelectedDocumentSource
import dev.soupslurpr.beautyxt.importing.client.querySelectedDocumentMetadata
import dev.soupslurpr.beautyxt.ipc.BoundedResourceOpener
import dev.soupslurpr.beautyxt.normalizedProviderAuthority
import dev.soupslurpr.beautyxt.ui.UiText
import dev.soupslurpr.beautyxt.ui.editor.ConflictRecoverableEditorDocumentSource
import dev.soupslurpr.beautyxt.ui.editor.ExternalSourceComparisonInput
import dev.soupslurpr.beautyxt.ui.editor.RemovableEditorDocumentSource
import dev.soupslurpr.beautyxt.ui.editor.SelectedEditorDocumentSource
import dev.soupslurpr.beautyxt.ui.editor.WritableDocumentLease
import dev.soupslurpr.beautyxt.ui.editor.WritableDocumentRegistry
import dev.soupslurpr.beautyxt.ui.editor.WritableEditorDocumentSource
import dev.soupslurpr.beautyxt.ui.editor.combineCloseables
import dev.soupslurpr.beautyxt.ui.editor.probeWritableFileIdentity
import dev.soupslurpr.beautyxt.ui.editor.writableDocumentKeys
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

private const val MAX_CONCURRENT_FOLDER_CREATES = 2
private const val FOLDER_CREATE_TIMEOUT_MILLIS = 30_000L
private const val EMPTY_SOURCE_INSPECT_LIMIT_BYTES = 1L
private const val UNCAPTURED_GENERATION = -1L

private val processFolderCreateMutex = Mutex()
private val processFolderCreateOpener =
    BoundedResourceOpener(maximumConcurrentCalls = MAX_CONCURRENT_FOLDER_CREATES)

/** Describes whether new-document autosave is off, still loading settings, or armed. */
internal enum class NewDocumentAutosaveReadiness {
    Off,
    Loading,
    Armed
}

/** Reports the outcome of attempting lazy file creation for one new document session. */
internal sealed interface NewDocumentCreationResult {
    /** Provides one newly created, verified-empty, seekable source owned by the caller. */
    data class Created(
        val source: WritableEditorDocumentSource,
        val format: DocumentFormat,
        val displayName: String?,
        val metadata: SelectedDocumentMetadata?
    ) : NewDocumentCreationResult

    /** Indicates that automatic creation is currently off and the document stays in memory. */
    data object Disabled : NewDocumentCreationResult

    /** Indicates transient unavailability before creation was issued; later edits may retry. */
    data object Deferred : NewDocumentCreationResult

    /** Indicates one latched failure requiring explicit retry or manual Save. */
    data class Failed(
        val message: UiText,
        val canRetryCreate: Boolean
    ) : NewDocumentCreationResult
}

/** Narrow session boundary so EditorSession never sees folder URIs or persisted grants. */
internal interface NewDocumentAutosaveGate {
    /** Returns whether this session is off, awaiting startup load, or armed to auto-create. */
    val readiness: NewDocumentAutosaveReadiness

    /** Returns the configured default format for new auto-saved documents. */
    val preferredFormat: DocumentFormat

    /** Creates one empty file for the configured format inside the chosen folder. */
    suspend fun createNewDocument(
        suggestName: (DocumentFormat) -> String
    ): NewDocumentCreationResult
}

/** Wraps one adopted source so closing the editor releases its in-memory folder grant lease. */
internal class LeasedEditorDocumentSource(
    private val delegate: SelectedEditorDocumentSource,
    private val lease: AutoCloseable
) : ConflictRecoverableEditorDocumentSource,
    RemovableEditorDocumentSource {
    private val closed = AtomicBoolean(false)

    override val canCompareExternalSource: Boolean
        get() = delegate.canCompareExternalSource

    override suspend fun openExternalForComparison(): ExternalSourceComparisonInput? =
        delegate.openExternalForComparison()

    override fun matchesSourceUri(encodedUri: String): Boolean =
        delegate.matchesSourceUri(encodedUri)

    override fun encodedShareUri(): String = delegate.encodedShareUri()

    override val fileIdentityKey: String?
        get() = delegate.fileIdentityKey

    override val writableLeaseOwnerId: java.util.UUID?
        get() = delegate.writableLeaseOwnerId

    override suspend fun saveRevision(snapshot: EditorDocumentSnapshot, expectedBytes: Long) {
        delegate.saveRevision(snapshot, expectedBytes)
    }

    override suspend fun overwriteRevision(snapshot: EditorDocumentSnapshot, expectedBytes: Long) {
        delegate.overwriteRevision(snapshot, expectedBytes)
    }

    override suspend fun overwriteReviewedRevision(
        snapshot: EditorDocumentSnapshot,
        expectedBytes: Long,
        reviewedExternalVersion: SourceVersion
    ) {
        delegate.overwriteReviewedRevision(snapshot, expectedBytes, reviewedExternalVersion)
    }

    override suspend fun queryRemovalCapabilities(): DocumentRemovalCapabilities =
        delegate.queryRemovalCapabilities()

    override suspend fun remove(action: DocumentRemovalAction) {
        delegate.remove(action)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        var failure: Throwable? = null
        try {
            delegate.close()
        } catch (closeFailure: Throwable) {
            failure = closeFailure
        }
        try {
            lease.close()
        } catch (closeFailure: Throwable) {
            if (failure == null) failure = closeFailure else failure.addSuppressed(closeFailure)
        }
        failure?.let { throw it }
    }
}

/**
 * Creates and validates new SAF documents in the opt-in folder without exposing URIs to workers.
 *
 * Provider files are never deleted unconditionally on failure or abandonment because SAF offers no
 * atomic delete-if-empty operation; preserving provider files prevents any risk of deleting user
 * data returned by an unexpected or externally modified provider.
 */
internal class NewDocumentFolderClient(
    context: Context,
    private val store: NewDocumentAutosaveStore,
    private val exporter: SourceDocumentExporter,
    private val readOnlyImporter: (suspend (Uri) -> ImportedDocument)?
) : NewDocumentAutosaveGate {
    private val applicationContext = context.applicationContext
    private val contentResolver = applicationContext.contentResolver
    private val capturedGeneration = AtomicLong(
        (store.state.value as? NewDocumentAutosaveStoreState.Ready)?.generation
            ?: UNCAPTURED_GENERATION
    )

    override val readiness: NewDocumentAutosaveReadiness
        get() = when (val current = store.state.value) {
            NewDocumentAutosaveStoreState.Loading -> NewDocumentAutosaveReadiness.Loading
            NewDocumentAutosaveStoreState.LoadFailed -> NewDocumentAutosaveReadiness.Off
            is NewDocumentAutosaveStoreState.Ready -> {
                val expected = capturedGeneration.get()
                if (expected != UNCAPTURED_GENERATION && current.generation != expected) {
                    NewDocumentAutosaveReadiness.Off
                } else {
                    when (val status = current.status) {
                        is NewDocumentAutosaveStatus.Enabled -> NewDocumentAutosaveReadiness.Armed
                        is NewDocumentAutosaveStatus.NeedsReselection ->
                            if (status.reason == NewDocumentAutosaveUnavailableReason.TransientUnavailable) {
                                NewDocumentAutosaveReadiness.Armed
                            } else {
                                NewDocumentAutosaveReadiness.Off
                            }
                        NewDocumentAutosaveStatus.Disabled -> NewDocumentAutosaveReadiness.Off
                    }
                }
            }
        }

    override val preferredFormat: DocumentFormat
        get() = (store.state.value as? NewDocumentAutosaveStoreState.Ready)?.format
            ?: DocumentFormat.PlainText

    override suspend fun createNewDocument(
        suggestName: (DocumentFormat) -> String
    ): NewDocumentCreationResult {
        val expectedGeneration = capturedGeneration.get().takeIf { it != UNCAPTURED_GENERATION }
        val leaseResult = store.revalidateAndAcquireCreationLease(expectedGeneration)
        val acquired = when (leaseResult) {
            CreationLeaseResult.Disabled -> return NewDocumentCreationResult.Disabled
            CreationLeaseResult.Deferred -> return NewDocumentCreationResult.Deferred
            is CreationLeaseResult.Unavailable ->
                return NewDocumentCreationResult.Failed(
                    message = UiText.Resource(R.string.autosave_create_folder_lost),
                    canRetryCreate = false
                )

            is CreationLeaseResult.Acquired -> {
                capturedGeneration.compareAndSet(UNCAPTURED_GENERATION, leaseResult.generation)
                leaseResult
            }
        }

        val effectiveFormat = acquired.format
        val suggestedName = suggestName(effectiveFormat)
        val treeUri = acquired.treeUri.toUri()
        val treeDocumentId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (_: Exception) {
            acquired.lease.close()
            return NewDocumentCreationResult.Failed(
                message = UiText.Resource(R.string.autosave_create_folder_lost),
                canRetryCreate = false
            )
        }
        val parentDocumentUri = try {
            DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocumentId)
        } catch (_: Exception) {
            acquired.lease.close()
            return NewDocumentCreationResult.Failed(
                message = UiText.Resource(R.string.autosave_create_folder_lost),
                canRetryCreate = false
            )
        }

        val dispatched = AtomicBoolean(false)
        var createdSourceToReleaseOnCancel: WritableEditorDocumentSource? = null
        var leaseTransferred = false
        try {
            val createdUri = withTimeout(FOLDER_CREATE_TIMEOUT_MILLIS) {
                processFolderCreateMutex.withLock {
                    dispatched.set(true)
                    processFolderCreateOpener.open(
                        cancel = {},
                        // Never unconditionally delete a late provider result whose identity and
                        // contents have not been verified.
                        closeUnclaimed = {}
                    ) {
                        DocumentsContract.createDocument(
                            contentResolver,
                            parentDocumentUri,
                            effectiveFormat.mimeType,
                            suggestedName
                        )
                    }
                }
            } ?: return NewDocumentCreationResult.Failed(
                message = UiText.Resource(R.string.autosave_create_failed),
                canRetryCreate = !dispatched.get()
            )

            val result = withContext(NonCancellable + Dispatchers.IO) {
                validateAndWrapCreatedChild(
                    treeUri = treeUri,
                    treeDocumentId = treeDocumentId,
                    createdUri = createdUri,
                    format = effectiveFormat,
                    lease = acquired.lease,
                    onCreatedSource = { source ->
                        createdSourceToReleaseOnCancel = source
                        leaseTransferred = true
                    }
                )
            }
            createdSourceToReleaseOnCancel = null
            return result
        } catch (_: TimeoutCancellationException) {
            return if (dispatched.get()) {
                NewDocumentCreationResult.Failed(
                    message = UiText.Resource(R.string.autosave_create_uncertain),
                    canRetryCreate = false
                )
            } else {
                NewDocumentCreationResult.Failed(
                    message = UiText.Resource(R.string.autosave_create_failed),
                    canRetryCreate = true
                )
            }
        } catch (cancellation: CancellationException) {
            createdSourceToReleaseOnCancel?.close()
            createdSourceToReleaseOnCancel = null
            throw cancellation
        } catch (_: SecurityException) {
            return NewDocumentCreationResult.Failed(
                message = UiText.Resource(R.string.autosave_create_folder_lost),
                canRetryCreate = false
            )
        } catch (_: Exception) {
            return NewDocumentCreationResult.Failed(
                message = if (dispatched.get()) {
                    UiText.Resource(R.string.autosave_create_uncertain)
                } else {
                    UiText.Resource(R.string.autosave_create_failed)
                },
                canRetryCreate = !dispatched.get()
            )
        } finally {
            createdSourceToReleaseOnCancel?.close()
            if (!leaseTransferred) {
                acquired.lease.close()
            }
        }
    }

    private suspend fun validateAndWrapCreatedChild(
        treeUri: Uri,
        treeDocumentId: String,
        createdUri: Uri,
        format: DocumentFormat,
        lease: AutoCloseable,
        onCreatedSource: (WritableEditorDocumentSource) -> Unit
    ): NewDocumentCreationResult {
        val childDocumentId = try {
            if (
                createdUri.scheme != ContentResolver.SCHEME_CONTENT ||
                normalizedProviderAuthority(createdUri) != normalizedProviderAuthority(treeUri) ||
                !DocumentsContract.isDocumentUri(applicationContext, createdUri)
            ) {
                return NewDocumentCreationResult.Failed(
                    message = UiText.Resource(R.string.autosave_create_incompatible),
                    canRetryCreate = false
                )
            }
            DocumentsContract.getDocumentId(createdUri)
        } catch (_: Exception) {
            return NewDocumentCreationResult.Failed(
                message = UiText.Resource(R.string.autosave_create_incompatible),
                canRetryCreate = false
            )
        }
        if (childDocumentId.isNullOrEmpty() || childDocumentId == treeDocumentId) {
            return NewDocumentCreationResult.Failed(
                message = UiText.Resource(R.string.autosave_create_incompatible),
                canRetryCreate = false
            )
        }

        val scopedChildUri = try {
            DocumentsContract.buildDocumentUriUsingTree(treeUri, childDocumentId)
        } catch (_: Exception) {
            createdUri
        }
        val metadata = querySelectedDocumentMetadata(contentResolver, scopedChildUri)
        val selectedSource = try {
            SelectedDocumentSource.takeOwnership(applicationContext, scopedChildUri)
        } catch (_: IllegalArgumentException) {
            return NewDocumentCreationResult.Failed(
                message = UiText.Resource(R.string.autosave_create_incompatible),
                canRetryCreate = false
            )
        }

        var unclaimedSource: SelectedDocumentSource? = selectedSource
        var unclaimedWriteLease: WritableDocumentLease? = null
        try {
            val observedVersion = try {
                TransientSourceDescriptor.from(selectedSource.openReadDescriptor()).use { descriptor ->
                    exporter.inspectSource(
                        source = descriptor,
                        maximumBytes = EMPTY_SOURCE_INSPECT_LIMIT_BYTES
                    )
                }
            } catch (_: Exception) {
                return NewDocumentCreationResult.Failed(
                    message = UiText.Resource(R.string.autosave_create_incompatible),
                    canRetryCreate = false
                )
            }
            if (observedVersion.byteLength != 0L) {
                return NewDocumentCreationResult.Failed(
                    message = UiText.Resource(R.string.autosave_create_incompatible),
                    canRetryCreate = false
                )
            }
            if (!selectedSource.hasCompatibleWriteAccess()) {
                return NewDocumentCreationResult.Failed(
                    message = UiText.Resource(R.string.autosave_create_incompatible),
                    canRetryCreate = false
                )
            }

            val createdSourceUri = selectedSource.uri
            unclaimedWriteLease = WritableDocumentRegistry.shared.tryAcquireRefreshing(
                documentKeys = writableDocumentKeys(
                    context = applicationContext,
                    uri = createdSourceUri,
                    knownFileIdentityKey = selectedSource.fileIdentityKey,
                    preferWriteProbe = true
                ),
                liveProbe = { probeWritableFileIdentity(applicationContext, createdSourceUri) },
                destinationIsEmpty = true
            )
            if (unclaimedWriteLease == null) {
                return NewDocumentCreationResult.Failed(
                    message = UiText.Resource(R.string.autosave_create_incompatible),
                    canRetryCreate = false
                )
            }

            val ownedEditorSource = SelectedEditorDocumentSource.takeCreatedOwnership(
                selectedSource = checkNotNull(unclaimedSource),
                exporter = exporter,
                readOnlyImporter = readOnlyImporter,
                writeLease = unclaimedWriteLease
            ).also { unclaimedSource = null }

            val combinedLease = checkNotNull(combineCloseables(lease, unclaimedWriteLease))
                .also { unclaimedWriteLease = null }
            val leasedSource = LeasedEditorDocumentSource(ownedEditorSource, combinedLease)
            onCreatedSource(leasedSource)
            return NewDocumentCreationResult.Created(
                source = leasedSource,
                format = format,
                displayName = metadata.displayName,
                metadata = metadata
            )
        } finally {
            unclaimedWriteLease?.close()
            unclaimedSource?.close()
        }
    }
}
