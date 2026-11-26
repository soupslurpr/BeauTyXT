package dev.soupslurpr.beautyxt.ui.editor

import android.net.Uri
import android.os.ParcelFileDescriptor
import dev.soupslurpr.beautyxt.document.DocumentRemovalAction
import dev.soupslurpr.beautyxt.document.DocumentRemovalCapabilities
import dev.soupslurpr.beautyxt.document.EditorDocumentSnapshot
import dev.soupslurpr.beautyxt.document.SourceVersion
import dev.soupslurpr.beautyxt.exporting.ExportProtocol
import dev.soupslurpr.beautyxt.exporting.client.DocumentExportException
import dev.soupslurpr.beautyxt.exporting.client.DocumentExportFailure
import dev.soupslurpr.beautyxt.exporting.client.SourceDocumentExporter
import dev.soupslurpr.beautyxt.exporting.client.StagedSourceSavePackage
import dev.soupslurpr.beautyxt.exporting.client.TransientSourceDescriptor
import dev.soupslurpr.beautyxt.importing.client.ImportedDocument
import dev.soupslurpr.beautyxt.importing.client.DescriptorFileIdentity
import dev.soupslurpr.beautyxt.importing.client.SelectedDocumentRemovalException
import dev.soupslurpr.beautyxt.importing.client.SelectedDocumentSource
import dev.soupslurpr.beautyxt.importing.client.probeDescriptorFileIdentityBounded
import dev.soupslurpr.beautyxt.importing.client.probeDescriptorFileSnapshotBounded
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Returns whether a post-capability-query probe still matches the exact guarded removal target. */
internal fun matchesGuardedRemovalIdentity(
    expectedInodeKey: String?,
    observed: DescriptorFileIdentity
): Boolean = when (observed) {
    is DescriptorFileIdentity.Regular -> expectedInodeKey != null && observed.key == expectedInodeKey
    DescriptorFileIdentity.NonRegular -> expectedInodeKey == null
    DescriptorFileIdentity.Indeterminate -> false
}

/** Saves revisions to one retained source through anonymous isolated staging. */
internal class SelectedEditorDocumentSource private constructor(
    private val selectedSource: SelectedDocumentSource,
    private val exporter: SourceDocumentExporter,
    private val readOnlyImporter: (suspend (Uri) -> ImportedDocument)?,
    private val writeLease: WritableDocumentLease?,
    initialReadWriteDescriptor: ParcelFileDescriptor?
) : ConflictRecoverableEditorDocumentSource,
    RemovableEditorDocumentSource {
    private val initialDescriptorLock = Any()
    private var pendingInitialReadWriteDescriptor: ParcelFileDescriptor? = initialReadWriteDescriptor
    private val verifiedBaselineLock = Any()
    private var verifiedBaselineInodeKey: String? = selectedSource.fileIdentityKey
    override val canCompareExternalSource: Boolean
        get() = readOnlyImporter != null

    /** Opens a read-only external snapshot paired with its exact imported version, leaving the baseline untouched. */
    override suspend fun openExternalForComparison(): ExternalSourceComparisonInput? {
        val importer = readOnlyImporter ?: return null
        val imported = importer(selectedSource.uri)
        return try {
            val snapshot = imported.captureSnapshot(0L)
            ExternalSourceComparisonInput(
                snapshot = snapshot,
                sourceVersion = imported.sourceVersion,
                owner = imported
            )
        } catch (failure: Throwable) {
            try {
                imported.close()
            } catch (closeFailure: Throwable) {
                failure.addSuppressed(closeFailure)
            }
            throw failure
        }
    }
    /** Returns whether this source retains the exact encoded content URI. */
    override fun matchesSourceUri(encodedUri: String): Boolean =
        selectedSource.matchesUri(encodedUri)

    /** Returns the exact encoded source URI for a user-initiated temporary grant. */
    override fun encodedShareUri(): String = selectedSource.uri.toString()

    override val fileIdentityKey: String?
        get() = synchronized(verifiedBaselineLock) { verifiedBaselineInodeKey } ?: selectedSource.fileIdentityKey

    override val writableLeaseOwnerId: java.util.UUID?
        get() = writeLease?.ownerId

    /** Returns the source provider's live trash and deletion capabilities. */
    override suspend fun queryRemovalCapabilities(): DocumentRemovalCapabilities =
        selectedSource.queryRemovalCapabilities()

    /** Completes one provider-authorized source removal operation after verifying baseline identity and ownership. */
    override suspend fun remove(action: DocumentRemovalAction) {
        val expectedBaselineInode = synchronized(verifiedBaselineLock) { verifiedBaselineInodeKey }
        val expectedSourceVersion = selectedSource.sourceVersion
        val lease = writeLease
        if (lease == null) {
            selectedSource.remove(
                action = action,
                verifyTargetBeforeDispatch = {
                    verifyRemovalMatchesBaseline(
                        expectedInodeKey = expectedBaselineInode,
                        expectedSourceVersion = expectedSourceVersion,
                        requireInodeMatch = expectedBaselineInode != null,
                        useReadWriteProbeWhenUnversioned = false
                    )
                }
            )
            return
        }
        val openedIdentity = probeLiveRemovalIdentity(useReadWrite = true)
        val inodeKey = when (openedIdentity) {
            is DescriptorFileIdentity.Regular -> openedIdentity.key
            DescriptorFileIdentity.NonRegular -> null
            DescriptorFileIdentity.Indeterminate -> throw SelectedDocumentRemovalException()
        }
        if (
            expectedBaselineInode != null &&
            !matchesGuardedRemovalIdentity(expectedInodeKey = expectedBaselineInode, observed = openedIdentity)
        ) {
            throw SelectedDocumentRemovalException()
        }
        val guardedInodeKey = expectedBaselineInode ?: inodeKey
        val removalKeys = buildSet {
            add(writableDocumentKey(selectedSource.applicationContext, selectedSource.uri))
            guardedInodeKey?.let(::add)
        }
        val removalGuard =
            lease.acquireRemovalGuard(removalKeys) ?: throw SelectedDocumentRemovalException()
        removalGuard.use {
            selectedSource.remove(
                action = action,
                verifyTargetBeforeDispatch = {
                    verifyRemovalMatchesBaseline(
                        expectedInodeKey = guardedInodeKey,
                        expectedSourceVersion = expectedSourceVersion,
                        requireInodeMatch = true,
                        useReadWriteProbeWhenUnversioned = true
                    )
                }
            )
        }
    }

    private suspend fun probeLiveRemovalIdentity(useReadWrite: Boolean): DescriptorFileIdentity =
        try {
            val descriptor =
                if (useReadWrite) {
                    selectedSource.openReadWriteDescriptorForProbe()
                } else {
                    selectedSource.openReadDescriptorForProbe()
                }
            descriptor.use { probed ->
                probeDescriptorFileIdentityBounded(probed)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            throw SelectedDocumentRemovalException(failure)
        } catch (failure: LinkageError) {
            throw SelectedDocumentRemovalException(failure)
        }

    private suspend fun verifyRemovalMatchesBaseline(
        expectedInodeKey: String?,
        expectedSourceVersion: SourceVersion?,
        requireInodeMatch: Boolean,
        useReadWriteProbeWhenUnversioned: Boolean
    ) {
        if (expectedSourceVersion != null) {
            try {
                // Verify both inode identity and exact content version against the same opened
                // read descriptor so a concurrent atomic replacement cannot slip between checks.
                val descriptor = selectedSource.openReadDescriptorForProbe()
                var handedToVerify = false
                try {
                    val preVerifySnapshot = probeDescriptorFileSnapshotBounded(descriptor)
                    if (preVerifySnapshot.identity == DescriptorFileIdentity.Indeterminate) {
                        throw SelectedDocumentRemovalException()
                    }
                    if (
                        requireInodeMatch &&
                        !matchesGuardedRemovalIdentity(
                            expectedInodeKey = expectedInodeKey,
                            observed = preVerifySnapshot.identity
                        )
                    ) {
                        throw SelectedDocumentRemovalException()
                    }
                    handedToVerify = true
                    TransientSourceDescriptor.from(descriptor).use { sourceDescriptor ->
                        exporter.verifySource(
                            source = sourceDescriptor,
                            expectedSourceVersion = expectedSourceVersion
                        )
                    }
                    // Re-check live inode and file-change metadata (st_size, st_mtim, st_ctim)
                    // immediately after hashing so neither an atomic replacement nor an in-place
                    // edit during verification can be discarded by SAF removal.
                    val postVerifySnapshot =
                        selectedSource.openReadDescriptorForProbe().use { probed ->
                            probeDescriptorFileSnapshotBounded(probed)
                        }
                    if (
                        postVerifySnapshot.identity == DescriptorFileIdentity.Indeterminate ||
                        postVerifySnapshot != preVerifySnapshot ||
                        (
                            requireInodeMatch &&
                                !matchesGuardedRemovalIdentity(
                                    expectedInodeKey = expectedInodeKey,
                                    observed = postVerifySnapshot.identity
                                )
                            )
                    ) {
                        throw SelectedDocumentRemovalException()
                    }
                } catch (failure: Throwable) {
                    if (!handedToVerify) {
                        try {
                            descriptor.close()
                        } catch (_: Exception) {
                            // Ignore close failure after verification failure.
                        }
                    }
                    throw failure
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: SelectedDocumentRemovalException) {
                throw failure
            } catch (failure: Exception) {
                throw SelectedDocumentRemovalException(failure)
            } catch (failure: LinkageError) {
                throw SelectedDocumentRemovalException(failure)
            }
        } else if (requireInodeMatch) {
            val liveIdentity = probeLiveRemovalIdentity(useReadWrite = useReadWriteProbeWhenUnversioned)
            if (!matchesGuardedRemovalIdentity(expectedInodeKey = expectedInodeKey, observed = liveIdentity)) {
                throw SelectedDocumentRemovalException()
            }
        }
    }

    /** Stages one immutable revision before opening its destructive destination. */
    override suspend fun saveRevision(snapshot: EditorDocumentSnapshot, expectedBytes: Long) {
        StagedSourceSavePackage.capture(
            snapshot = snapshot,
            expectedBytes = expectedBytes
        ).use { stagedPackage ->
            withContext(NonCancellable) {
                saveStagedPackage(
                    stagedPackage = stagedPackage,
                    expectedSourceVersion = selectedSource.sourceVersion
                )
            }
        }
    }

    /** Inspects the current source before conditionally applying an explicit overwrite. */
    override suspend fun overwriteRevision(snapshot: EditorDocumentSnapshot, expectedBytes: Long) {
        StagedSourceSavePackage.capture(
            snapshot = snapshot,
            expectedBytes = expectedBytes
        ).use { stagedPackage ->
            withContext(NonCancellable) {
                val previousSourceVersion = selectedSource.sourceVersion
                val observedSourceVersion =
                    TransientSourceDescriptor.from(
                        selectedSource.openReadDescriptor()
                    ).use { sourceDescriptor ->
                        exporter.inspectSource(
                            source = sourceDescriptor,
                            maximumBytes = ExportProtocol.MAX_BYTE_LIMIT
                        )
                    }
                selectedSource.advanceSourceVersion(
                    expectedVersion = previousSourceVersion,
                    newVersion = observedSourceVersion
                )
                saveStagedPackage(
                    stagedPackage = stagedPackage,
                    expectedSourceVersion = observedSourceVersion
                )
            }
        }
    }

    /** Overwrites only if the live file still matches [reviewedExternalVersion], advancing from the live baseline. */
    override suspend fun overwriteReviewedRevision(
        snapshot: EditorDocumentSnapshot,
        expectedBytes: Long,
        reviewedExternalVersion: SourceVersion
    ) {
        StagedSourceSavePackage.capture(
            snapshot = snapshot,
            expectedBytes = expectedBytes
        ).use { stagedPackage ->
            withContext(NonCancellable) {
                val priorLiveBaseline = selectedSource.sourceVersion
                saveStagedPackage(
                    stagedPackage = stagedPackage,
                    expectedSourceVersion = reviewedExternalVersion,
                    baselineToAdvanceFrom = priorLiveBaseline
                )
            }
        }
    }

    /** Saves and verifies one staged package against an exact source baseline. */
    private suspend fun saveStagedPackage(
        stagedPackage: StagedSourceSavePackage,
        expectedSourceVersion: SourceVersion?,
        baselineToAdvanceFrom: SourceVersion? = expectedSourceVersion
    ) {
        val preverifiedInitialDescriptor = synchronized(initialDescriptorLock) {
            pendingInitialReadWriteDescriptor.also { pendingInitialReadWriteDescriptor = null }
        }
        val rawDescriptor = preverifiedInitialDescriptor ?: selectedSource.openReadWriteDescriptorForProbe()
        val openedIdentity =
            try {
                probeDescriptorFileIdentityBounded(rawDescriptor)
            } catch (failure: Throwable) {
                try {
                    rawDescriptor.close()
                } catch (_: Exception) {
                    // Ignore close error on rejected descriptor.
                }
                throw failure
            }
        val inFlightInodePin =
            if (writeLease != null) {
                when (openedIdentity) {
                    is DescriptorFileIdentity.Regular ->
                        writeLease.pinInodeKey(openedIdentity.key) ?: run {
                            try {
                                rawDescriptor.close()
                            } catch (_: Exception) {
                                // Ignore close error on rejected descriptor.
                            }
                            throw DocumentExportException(DocumentExportFailure.DESTINATION_UNAVAILABLE)
                        }
                    DescriptorFileIdentity.NonRegular ->
                        writeLease.pinInodeKey(null) ?: run {
                            try {
                                rawDescriptor.close()
                            } catch (_: Exception) {
                                // Ignore close error on rejected descriptor.
                            }
                            throw DocumentExportException(DocumentExportFailure.DESTINATION_UNAVAILABLE)
                        }
                    DescriptorFileIdentity.Indeterminate -> {
                        try {
                            rawDescriptor.close()
                        } catch (_: Exception) {
                            // Ignore close error on rejected descriptor.
                        }
                        throw DocumentExportException(DocumentExportFailure.DESTINATION_UNAVAILABLE)
                    }
                }
            } else {
                null
            }
        val inFlightPinRef = java.util.concurrent.atomic.AtomicReference<AutoCloseable?>(inFlightInodePin)
        val receipt =
            try {
                TransientSourceDescriptor.from(rawDescriptor).use { sourceDescriptor ->
                    exporter.saveSource(
                        source = sourceDescriptor,
                        stagedPackage = stagedPackage,
                        expectedSourceVersion = expectedSourceVersion,
                        releaseLeaseOnUncertainExit = { inFlightPinRef.getAndSet(null) }
                    )
                }
            } finally {
                inFlightPinRef.getAndSet(null)?.close()
            }
        try {
            TransientSourceDescriptor.from(
                selectedSource.openReadDescriptor()
            ).use { sourceDescriptor ->
                exporter.verifySource(
                    source = sourceDescriptor,
                    expectedSourceVersion = receipt.sourceVersion
                )
            }
            selectedSource.advanceSourceVersion(
                expectedVersion = baselineToAdvanceFrom,
                newVersion = receipt.sourceVersion
            )
            if (openedIdentity is DescriptorFileIdentity.Regular) {
                synchronized(verifiedBaselineLock) {
                    verifiedBaselineInodeKey = openedIdentity.key
                }
            }
        } catch (failure: Exception) {
            throw DocumentExportException(
                DocumentExportFailure.SOURCE_UNCERTAIN,
                failure
            )
        } catch (failure: LinkageError) {
            throw DocumentExportException(
                DocumentExportFailure.SOURCE_UNCERTAIN,
                failure
            )
        }
    }

    /** Forgets the selected source capability when its editor closes. */
    override fun close() {
        val unclaimed = synchronized(initialDescriptorLock) {
            pendingInitialReadWriteDescriptor.also { pendingInitialReadWriteDescriptor = null }
        }
        try {
            unclaimed?.close()
        } catch (_: Exception) {
            // Ignore close failure on unclaimed initial descriptor.
        }
        synchronized(verifiedBaselineLock) {
            verifiedBaselineInodeKey = null
        }
        selectedSource.close()
    }

    companion object {
        /** Takes an imported source that already has an exact byte version. */
        fun takeImportedOwnership(
            selectedSource: SelectedDocumentSource,
            exporter: SourceDocumentExporter,
            readOnlyImporter: (suspend (Uri) -> ImportedDocument)? = null,
            writeLease: WritableDocumentLease? = null
        ): SelectedEditorDocumentSource {
            requireNotNull(selectedSource.sourceVersion) {
                "imported document source requires an exact version"
            }
            return SelectedEditorDocumentSource(
                selectedSource = selectedSource,
                exporter = exporter,
                readOnlyImporter = readOnlyImporter,
                writeLease = writeLease,
                initialReadWriteDescriptor = null
            )
        }

        /** Takes a newly created source that has no prior save baseline. */
        fun takeCreatedOwnership(
            selectedSource: SelectedDocumentSource,
            exporter: SourceDocumentExporter,
            readOnlyImporter: (suspend (Uri) -> ImportedDocument)? = null,
            writeLease: WritableDocumentLease? = null,
            initialReadWriteDescriptor: ParcelFileDescriptor? = null
        ): SelectedEditorDocumentSource {
            require(selectedSource.sourceVersion == null) {
                "created document source already has an exact version"
            }
            return SelectedEditorDocumentSource(
                selectedSource = selectedSource,
                exporter = exporter,
                readOnlyImporter = readOnlyImporter,
                writeLease = writeLease,
                initialReadWriteDescriptor = initialReadWriteDescriptor
            )
        }
    }
}
