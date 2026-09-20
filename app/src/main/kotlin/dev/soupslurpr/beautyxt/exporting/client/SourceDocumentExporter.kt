package dev.soupslurpr.beautyxt.exporting.client

import dev.soupslurpr.beautyxt.document.SourceVersion

/** Defines isolated operations that conditionally replace and verify one source. */
internal interface SourceDocumentExporter {
    /** Conditionally replaces one source and returns its verified new version. */
    suspend fun saveSource(
        source: TransientSourceDescriptor,
        stagedPackage: StagedSourceSavePackage,
        expectedSourceVersion: SourceVersion?
    ): SourceSaveReceipt

    /** Verifies one exact source version through a read-only isolated probe. */
    suspend fun verifySource(
        source: TransientSourceDescriptor,
        expectedSourceVersion: SourceVersion
    )

    /** Returns one source's exact bounded version through a read-only isolated probe. */
    suspend fun inspectSource(source: TransientSourceDescriptor, maximumBytes: Long): SourceVersion
}
