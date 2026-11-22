package dev.soupslurpr.beautyxt.ui.editor

import androidx.compose.ui.text.TextRange
import dev.soupslurpr.beautyxt.document.DIFF_LINE_ENDING_CRLF_BIT
import dev.soupslurpr.beautyxt.document.DIFF_LINE_ENDING_LF_BIT
import dev.soupslurpr.beautyxt.document.DiffHunk
import dev.soupslurpr.beautyxt.document.DiffLine
import dev.soupslurpr.beautyxt.document.DiffLineKind
import dev.soupslurpr.beautyxt.document.DocumentDiffPacketDecoder
import dev.soupslurpr.beautyxt.document.DocumentLineEnding
import dev.soupslurpr.beautyxt.document.DocumentMetrics
import dev.soupslurpr.beautyxt.document.EditorDocumentSnapshot
import dev.soupslurpr.beautyxt.document.RenderBlock
import dev.soupslurpr.beautyxt.document.SourceVersion
import dev.soupslurpr.beautyxt.document.ViewportCursor
import dev.soupslurpr.beautyxt.document.ViewportLimits
import dev.soupslurpr.beautyxt.document.ViewportSnapshot
import dev.soupslurpr.beautyxt.document.diffDocumentSnapshotsFallback
import dev.soupslurpr.beautyxt.exporting.client.DocumentExportException
import dev.soupslurpr.beautyxt.exporting.client.DocumentExportFailure
import dev.soupslurpr.beautyxt.testing.ImmediateSessionTestDispatcher
import dev.soupslurpr.beautyxt.testing.TestEditorDocument
import dev.soupslurpr.beautyxt.testing.TestEditorDocumentSnapshot
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceConflictCompareTest {
    private val versionExternalV1 = SourceVersion.from(
        byteLength = 12L,
        sha256 = ByteArray(32) { 1 }
    )
    private val versionExternalV2 = SourceVersion.from(
        byteLength = 18L,
        sha256 = ByteArray(32) { 2 }
    )

    @Test
    fun packetDecoderRoundTripsHunksAndRejectsTruncatedPacket() {
        val line1 = "alpha".toByteArray(Charsets.UTF_8)
        val line2 = "beta".toByteArray(Charsets.UTF_8)
        val buffer = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("BEDF".toByteArray(Charsets.US_ASCII))
        buffer.putInt(1)
        buffer.put((1 or (1 shl 3) or (1 shl 4)).toByte()) // externalHasBom + isPartial + formatDiffers
        buffer.put(DIFF_LINE_ENDING_CRLF_BIT.toByte())
        buffer.put(DIFF_LINE_ENDING_LF_BIT.toByte())
        buffer.put(0.toByte())
        buffer.putLong(100L) // externalBytes
        buffer.putLong(95L) // localBytes
        buffer.putLong(4L) // externalLines
        buffer.putLong(5L) // localLines
        buffer.putLong(5L) // comparedLines
        buffer.putLong(2L) // addedLines
        buffer.putLong(1L) // removedLines
        buffer.putInt(1) // hunk count
        buffer.putLong(2L) // ext start
        buffer.putLong(2L) // loc start
        buffer.putInt(2) // line count
        buffer.put(1.toByte()) // Removed
        buffer.putInt(line1.size)
        buffer.put(line1)
        buffer.put(2.toByte()) // Added
        buffer.putInt(line2.size)
        buffer.put(line2)

        val packet = buffer.array().copyOf(buffer.position())
        val decoded = DocumentDiffPacketDecoder.decode(packet)
        assertTrue(decoded.externalHasBom)
        assertFalse(decoded.localHasBom)
        assertEquals(DIFF_LINE_ENDING_CRLF_BIT, decoded.externalLineEndings)
        assertEquals(DIFF_LINE_ENDING_LF_BIT, decoded.localLineEndings)
        assertFalse(decoded.textIdentical)
        assertTrue(decoded.formatDiffers)
        assertTrue(decoded.isPartial)
        assertEquals(5L, decoded.comparedLines)
        assertEquals(2L, decoded.addedLines)
        assertEquals(1L, decoded.removedLines)
        assertEquals(
            listOf(
                DiffHunk(
                    externalStartLine = 2L,
                    localStartLine = 2L,
                    lines = listOf(
                        DiffLine(DiffLineKind.Removed, "alpha"),
                        DiffLine(DiffLineKind.Added, "beta")
                    )
                )
            ),
            decoded.hunks
        )

        val truncated = packet.copyOf(packet.size - 3)
        var threw = false
        try {
            DocumentDiffPacketDecoder.decode(truncated)
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue("truncated diff packet must throw", threw)

        // Huge hunk count in a tiny packet must be rejected before allocating ArrayList.
        val hostile = ByteBuffer.allocate(72).order(ByteOrder.LITTLE_ENDIAN)
        hostile.put("BEDF".toByteArray(Charsets.US_ASCII))
        hostile.putInt(1)
        hostile.putInt(0)
        repeat(7) { hostile.putLong(0L) }
        hostile.putInt(Int.MAX_VALUE)
        var hostileRejected = false
        try {
            DocumentDiffPacketDecoder.decode(hostile.array())
        } catch (_: IllegalArgumentException) {
            hostileRejected = true
        }
        assertTrue("oversized diff hunk count must throw before allocation", hostileRejected)
    }

    @Test
    fun fallbackDiffDetectsFormatOnlyBomAndLineEndingChanges() {
        val ext = FakeFormatSnapshot(
            text = "first\nsecond",
            hasBom = true,
            hasCrlf = true,
            hasLf = false
        )
        val loc = FakeFormatSnapshot(
            text = "first\nsecond",
            hasBom = false,
            hasCrlf = false,
            hasLf = true
        )
        val diff = diffDocumentSnapshotsFallback(external = ext, local = loc)
        assertTrue(diff.textIdentical)
        assertTrue(diff.hasFormatDifference)
        assertTrue(diff.hunks.isEmpty())
        assertEquals(0L, diff.addedLines)
        assertEquals(0L, diff.removedLines)
    }

    @Test
    fun openingAndDismissingCompareWritesNothingAndPreservesConflictState() {
        val document = TestEditorDocument("hello\nlocal")
        val source = FakeCompareConflictSource(
            externalText = "hello\nexternal",
            externalVersion = versionExternalV1
        )
        val session = createCompareSession(document, source)
        try {
            session.openInitialEditor()
            val draft = requireNotNull(session.activeDraft)
            draft.textFieldState.edit {
                replace(start = 6, end = length, text = "local-edit")
                selection = TextRange(length)
            }
            session.observeActiveEdit(draft, draft.captureFieldValue())
            assertTrue(session.sourceSaveStatus is SourceSaveStatus.Conflict)
            assertTrue(session.canCompareSourceConflict)

            assertTrue(session.requestSourceConflictComparison())
            val ready = session.sourceConflictComparison as? SourceConflictComparisonState.Ready
            assertNotNull("Compare changes should resolve to Ready", ready)
            assertEquals(session.state.metrics?.revision, ready!!.localRevision)
            assertEquals(versionExternalV1, ready.externalVersion)
            assertEquals(1L, ready.diff.addedLines)
            assertEquals(1L, ready.diff.removedLines)
            assertEquals(0, source.reviewedOverwriteCalls.size)
            assertEquals(0, source.unconditionalOverwriteCalls)
            assertEquals(1, source.externalOpenCount)

            session.dismissSourceConflictComparison()
            assertNull(session.sourceConflictComparison)
            assertTrue(session.sourceSaveStatus is SourceSaveStatus.Conflict)
            assertEquals(0, source.reviewedOverwriteCalls.size)
            assertEquals(0, source.unconditionalOverwriteCalls)
        } finally {
            session.close()
        }
    }

    @Test
    fun reviewedOverwriteRefusesWhenLocalEditsAdvanceAfterCompare() {
        val document = TestEditorDocument("line1\nlocal1")
        val source = FakeCompareConflictSource(
            externalText = "line1\nexternal1",
            externalVersion = versionExternalV1
        )
        val session = createCompareSession(document, source)
        try {
            session.openInitialEditor()
            val draft = requireNotNull(session.activeDraft)
            draft.textFieldState.edit {
                replace(start = 6, end = length, text = "local2")
                selection = TextRange(length)
            }
            session.observeActiveEdit(draft, draft.captureFieldValue())
            assertTrue(session.sourceSaveStatus is SourceSaveStatus.Conflict)

            assertTrue(session.requestSourceConflictComparison())
            assertFalse(session.hasNewerLocalEditsSinceComparison)

            // Another local edit lands after Compare was computed.
            draft.textFieldState.edit {
                replace(start = 6, end = length, text = "local3")
                selection = TextRange(length)
            }
            session.observeActiveEdit(draft, draft.captureFieldValue())

            assertTrue(session.hasNewerLocalEditsSinceComparison)
            assertFalse(
                "Reviewed overwrite must refuse once local revision advances",
                session.confirmReviewedSourceOverwrite()
            )
            assertEquals(0, source.reviewedOverwriteCalls.size)
        } finally {
            session.close()
        }
    }

    @Test
    fun reviewedOverwriteFailsWithConflictWhenExternalChangesAgainAndSucceedsWhenMatching() {
        val document = TestEditorDocument("a\nlocal")
        val source = FakeCompareConflictSource(
            externalText = "a\next-v1",
            externalVersion = versionExternalV1
        )
        val session = createCompareSession(document, source)
        try {
            session.openInitialEditor()
            val draft = requireNotNull(session.activeDraft)
            draft.textFieldState.edit {
                replace(start = 2, end = length, text = "local-new")
                selection = TextRange(length)
            }
            session.observeActiveEdit(draft, draft.captureFieldValue())
            assertTrue(session.sourceSaveStatus is SourceSaveStatus.Conflict)

            // Open Compare against v1, then external changes to v2 before confirm.
            assertTrue(session.requestSourceConflictComparison())
            source.externalVersion = versionExternalV2
            source.externalText = "a\next-v2"

            assertTrue(session.confirmReviewedSourceOverwrite())
            assertTrue(
                "External drift after Compare must return to Conflict without writing",
                session.sourceSaveStatus is SourceSaveStatus.Conflict
            )
            assertEquals(1, source.reviewedOverwriteCalls.size)
            assertEquals(versionExternalV1, source.reviewedOverwriteCalls.first().first)
            assertEquals(0, source.successfulReviewedWrites)

            // Refresh Compare against v2 and confirm overwrite cleanly.
            assertTrue(session.requestSourceConflictComparison())
            val ready = session.sourceConflictComparison as SourceConflictComparisonState.Ready
            assertEquals(versionExternalV2, ready.externalVersion)

            assertTrue(session.confirmReviewedSourceOverwrite())
            assertEquals(SourceSaveStatus.Saved, session.sourceSaveStatus)
            assertEquals(1, source.successfulReviewedWrites)
            assertEquals(versionExternalV2, source.reviewedOverwriteCalls.last().first)
        } finally {
            session.close()
        }
    }

    @Test
    fun retryAfterTransientReviewedOverwriteFailurePreservesReviewedVersionAndRejectsDrift() {
        val document = TestEditorDocument("a\nlocal")
        val source = FakeCompareConflictSource(
            externalText = "a\next-v1",
            externalVersion = versionExternalV1
        )
        source.transientFailureCountOnReviewedOverwrite = 1
        val session = createCompareSession(document, source)
        try {
            session.openInitialEditor()
            val draft = requireNotNull(session.activeDraft)
            draft.textFieldState.edit {
                replace(start = 2, end = length, text = "local-reviewed")
                selection = TextRange(length)
            }
            session.observeActiveEdit(draft, draft.captureFieldValue())
            assertTrue(session.sourceSaveStatus is SourceSaveStatus.Conflict)

            assertTrue(session.requestSourceConflictComparison())
            assertTrue(session.confirmReviewedSourceOverwrite())
            assertTrue(
                "Transient provider failure should enter Failed state",
                session.sourceSaveStatus is SourceSaveStatus.Failed
            )
            assertEquals(0, source.unconditionalOverwriteCalls)
            assertEquals(1, source.reviewedOverwriteCalls.size)

            // External file changes before Retry is tapped -> Retry must STILL use overwriteReviewedRevision
            // with versionExternalV1, detect mismatch, and return to Conflict instead of blind overwrite!
            source.externalVersion = versionExternalV2
            session.retrySourceSave()
            assertEquals(0, source.unconditionalOverwriteCalls)
            assertEquals(2, source.reviewedOverwriteCalls.size)
            assertEquals(versionExternalV1, source.reviewedOverwriteCalls.last().first)
            assertTrue(
                "Retry after external drift must return to Conflict without blind overwrite",
                session.sourceSaveStatus is SourceSaveStatus.Conflict
            )

            // Now test local edit drift before Retry: fail transiently once, edit locally, then Retry.
            source.transientFailureCountOnReviewedOverwrite = 1
            assertTrue(session.requestSourceConflictComparison())
            assertTrue(session.confirmReviewedSourceOverwrite())
            assertTrue(session.sourceSaveStatus is SourceSaveStatus.Failed)

            draft.textFieldState.edit {
                replace(start = 2, end = length, text = "local-unreviewed-edit")
                selection = TextRange(length)
            }
            session.observeActiveEdit(draft, draft.captureFieldValue())
            // Retrying after a local edit must return to Conflict and never blind-overwrite.
            session.retrySourceSave()
            assertTrue(
                "Retry after local drift must return to Conflict",
                session.sourceSaveStatus is SourceSaveStatus.Conflict
            )
            assertEquals(0, source.unconditionalOverwriteCalls)
        } finally {
            session.close()
        }
    }

    @Test
    fun outlineAndTableChunkBehaviorMatchSolFindings() {
        val entries = listOf(
            DocumentOutlineEntry(
                itemIndex = 2,
                level = 1,
                title = "First in alert",
                blockIndex = 2,
                itemPositionFraction = 0.0f
            ),
            DocumentOutlineEntry(
                itemIndex = 2,
                level = 2,
                title = "Second in alert",
                blockIndex = 4,
                itemPositionFraction = 0.90f
            )
        )
        assertEquals(0, activeOutlineEntry(entries, itemIndex = 2, itemScrollOffset = 0, itemSize = 1000))
        // Halfway through a long paragraph between headings must still highlight the first heading.
        assertEquals(0, activeOutlineEntry(entries, itemIndex = 2, itemScrollOffset = 600, itemSize = 1000))
        assertEquals(1, activeOutlineEntry(entries, itemIndex = 2, itemScrollOffset = 920, itemSize = 1000))
    }

    @Test
    fun compareWithPendingDebouncedEditSettlesWithoutLivelock() {
        val document = TestEditorDocument("a\nlocal")
        val source = FakeCompareConflictSource(
            externalText = "a\next-v1",
            externalVersion = versionExternalV1
        )
        val pendingDebounce = kotlinx.coroutines.CompletableDeferred<Unit>()
        var debounceCalls = 0
        val session = EditorSession(
            title = "note.md",
            state = EditorDocumentState(document, ImmediateSessionTestDispatcher),
            documentSource = source,
            operationDispatcher = ImmediateSessionTestDispatcher,
            editSynchronizationDelay = {
                debounceCalls++
                if (debounceCalls > 1) {
                    pendingDebounce.await()
                }
            }
        )
        try {
            session.openInitialEditor()
            val draft = requireNotNull(session.activeDraft)
            draft.textFieldState.edit {
                replace(start = 2, end = length, text = "first-edit")
                selection = TextRange(length)
            }
            session.observeActiveEdit(draft, draft.captureFieldValue())
            assertTrue(session.sourceSaveStatus is SourceSaveStatus.Conflict)

            // Second edit suspends inside debounce; requesting Compare must flush and resolve cleanly.
            draft.textFieldState.edit {
                replace(start = 2, end = length, text = "second-edit")
                selection = TextRange(length)
            }
            session.observeActiveEdit(draft, draft.captureFieldValue())
            assertTrue(session.requestSourceConflictComparison())
            val ready = session.sourceConflictComparison as? SourceConflictComparisonState.Ready
            assertNotNull("Compare must settle cleanly after cancelling pending debounce", ready)
            assertEquals(1L, ready!!.diff.addedLines)
        } finally {
            session.close()
        }
    }

    private fun createCompareSession(
        document: TestEditorDocument,
        source: FakeCompareConflictSource
    ): EditorSession = EditorSession(
        title = "note.md",
        state = EditorDocumentState(document, ImmediateSessionTestDispatcher),
        documentSource = source,
        operationDispatcher = ImmediateSessionTestDispatcher,
        editSynchronizationDelay = {}
    )
}

private class FakeCompareConflictSource(
    var externalText: String,
    var externalVersion: SourceVersion
) : ConflictRecoverableEditorDocumentSource {
    var externalOpenCount = 0
    var unconditionalOverwriteCalls = 0
    var successfulReviewedWrites = 0
    var transientFailureCountOnReviewedOverwrite = 0
    val reviewedOverwriteCalls = mutableListOf<Pair<SourceVersion, String>>()

    override val canCompareExternalSource: Boolean = true

    override fun matchesSourceUri(encodedUri: String): Boolean = encodedUri == "content://test/doc"

    override fun encodedShareUri(): String = "content://test/doc"

    override suspend fun openExternalForComparison(): ExternalSourceComparisonInput {
        externalOpenCount++
        val snap = FakeFormatSnapshot(externalText)
        return ExternalSourceComparisonInput(
            snapshot = snap,
            sourceVersion = externalVersion,
            owner = snap
        )
    }

    override suspend fun saveRevision(snapshot: EditorDocumentSnapshot, expectedBytes: Long) {
        (snapshot as? TestEditorDocumentSnapshot)?.consume()
        throw DocumentExportException(DocumentExportFailure.SOURCE_CONFLICT)
    }

    override suspend fun overwriteRevision(snapshot: EditorDocumentSnapshot, expectedBytes: Long) {
        unconditionalOverwriteCalls++
        (snapshot as? TestEditorDocumentSnapshot)?.consume()
    }

    override suspend fun overwriteReviewedRevision(
        snapshot: EditorDocumentSnapshot,
        expectedBytes: Long,
        reviewedExternalVersion: SourceVersion
    ) {
        val testSnap = snapshot as TestEditorDocumentSnapshot
        reviewedOverwriteCalls += reviewedExternalVersion to testSnap.text
        testSnap.consume()
        if (transientFailureCountOnReviewedOverwrite > 0) {
            transientFailureCountOnReviewedOverwrite--
            throw DocumentExportException(DocumentExportFailure.SERVICE_UNAVAILABLE)
        }
        if (reviewedExternalVersion != externalVersion) {
            throw DocumentExportException(DocumentExportFailure.SOURCE_CONFLICT)
        }
        successfulReviewedWrites++
    }

    override fun close() = Unit
}

private class FakeFormatSnapshot(
    val text: String,
    private val revision: Long = 0L,
    private val hasBom: Boolean = false,
    private val hasLf: Boolean = true,
    private val hasCrlf: Boolean = false
) : EditorDocumentSnapshot {
    override fun metrics(): DocumentMetrics {
        val lines = if (text.isEmpty()) 1L else text.count { it == '\n' } + 1L
        val rawBytes = text.toByteArray(Charsets.UTF_8).size.toLong() +
            (if (hasBom) 3L else 0L) +
            (if (hasCrlf) text.count { it == '\n' }.toLong() else 0L)
        return DocumentMetrics(
            revision = revision,
            byteLength = text.toByteArray(Charsets.UTF_8).size.toLong(),
            serializedByteLength = rawBytes,
            characterLength = text.codePointCount(0, text.length).toLong(),
            utf16Length = text.length.toLong(),
            lineCount = lines,
            wordCount = 1L,
            hasUtf8Bom = hasBom,
            hasLfLineEndings = hasLf,
            hasCrlfLineEndings = hasCrlf,
            hasCrLineEndings = false,
            insertedLineEnding = if (hasCrlf) DocumentLineEnding.CrLf else DocumentLineEnding.Lf,
            isEditable = true
        )
    }

    override fun viewport(cursor: ViewportCursor, limits: ViewportLimits): ViewportSnapshot {
        val parts = text.split('\n')
        val blocks = ArrayList<RenderBlock>(parts.size)
        var offset = 0L
        for (i in parts.indices) {
            val part = parts[i]
            val end = offset + part.length
            val hasNewline = i < parts.lastIndex
            blocks += RenderBlock(
                logicalLine = i.toLong(),
                globalUtf16Start = offset,
                globalUtf16End = end,
                lineTerminatorUtf16Units = if (hasNewline) 1 else 0,
                text = part,
                continuesAtStart = false,
                continuesAtEnd = false
            )
            offset = end + (if (hasNewline) 1L else 0L)
        }
        return ViewportSnapshot(
            metrics = metrics(),
            blocks = blocks,
            previous = null,
            next = null
        )
    }

    override fun writeSnapshot(
        outputRawFileDescriptor: Int,
        cancellationRawFileDescriptor: Int,
        timeoutMillis: Long
    ): Long = metrics().serializedByteLength

    override fun close() = Unit
}
