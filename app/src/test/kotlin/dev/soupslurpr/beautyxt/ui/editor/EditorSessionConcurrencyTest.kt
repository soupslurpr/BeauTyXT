package dev.soupslurpr.beautyxt.ui.editor

import androidx.compose.ui.text.input.TextFieldValue
import dev.soupslurpr.beautyxt.document.DocumentFormat
import dev.soupslurpr.beautyxt.document.EditorDocumentSnapshot
import dev.soupslurpr.beautyxt.document.Utf16Range
import dev.soupslurpr.beautyxt.testing.ImmediateSessionTestDispatcher
import dev.soupslurpr.beautyxt.testing.QueuedSessionTestDispatcher
import dev.soupslurpr.beautyxt.testing.TestEditorDocument
import dev.soupslurpr.beautyxt.testing.TestEditorDocumentSnapshot
import dev.soupslurpr.beautyxt.transfer.client.NfcTransferEnvelope
import dev.soupslurpr.beautyxt.transfer.client.NfcTransferProcessor
import dev.soupslurpr.beautyxt.transfer.client.QrCodeGrid
import dev.soupslurpr.beautyxt.transfer.client.QrTransferProcessor
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises cancellation and snapshot ownership at controlled suspension boundaries. */
class EditorSessionConcurrencyTest {
    @Test
    fun changingQueriesDuringMatchNavigationFindsOnlyTheNewestQuery() {
        atPendingMatchWindow { session ->
            assertTrue(session.updateFindFieldValue(TextFieldValue("missing")))
            assertTrue(session.updateFindFieldValue(TextFieldValue("a")))
            assertTrue(session.updateFindFieldValue(TextFieldValue("beta")))
        }.use { fixture ->
            fixture.drain()
            val match = fixture.session.findStatus as FindStatus.Match
            assertEquals(Utf16Range(6L, 10L), match.match.range)
            assertEquals(EditorDocumentStatus.Ready, fixture.session.state.status)
            assertEquals("beta", fixture.session.findFieldValue.text)
        }
    }

    @Test
    fun changingCaseSensitivityDuringMatchNavigationCompletesTheNewSearch() {
        atPendingMatchWindow { session ->
            assertTrue(session.updateFindCaseSensitivity(true))
        }.use { fixture ->
            fixture.drain()
            assertTrue(fixture.session.findStatus is FindStatus.Match)
            assertEquals(EditorDocumentStatus.Ready, fixture.session.state.status)
            assertTrue(fixture.session.isFindCaseSensitive)
        }
    }

    @Test
    fun clearingQueryDuringMatchNavigationReleasesTheEditor() {
        atPendingMatchWindow { session ->
            assertTrue(session.updateFindFieldValue(TextFieldValue()))
        }.use { fixture ->
            fixture.drain()
            assertEquals(FindStatus.Idle, fixture.session.findStatus)
            assertEquals(EditorDocumentStatus.Ready, fixture.session.state.status)
            assertTrue(fixture.session.canCloseSafely)
        }
    }

    @Test
    fun closingFindDuringMatchNavigationPreservesTheDraftAndAllowsEditing() {
        for (nativeWindowAlreadyLoaded in listOf(false, true)) {
            val fixture = FindFixture()
            fixture.use {
                fixture.startMatchNavigation(nativeWindowAlreadyLoaded)
                val draft = checkNotNull(fixture.session.activeDraft)
                fixture.session.closeFind()
                fixture.drain()
                assertEquals(EditorDocumentStatus.Ready, fixture.session.state.status)
                assertEquals(FindStatus.Idle, fixture.session.findStatus)
                assertFalse(fixture.session.isFindVisible)
                assertTrue(fixture.session.canApplyEditorInput(draft))
                assertTrue(fixture.session.canCloseSafely)
                draft.textFieldState.edit { replace(0, length, "still editable") }
                fixture.session.observeActiveEdit(draft, draft.captureFieldValue())
                fixture.drain()
                assertEquals("still editable", fixture.document.text)
            }
        }
    }

    @Test
    fun newerQueryDiscardsAnAlreadyLoadedButUnpublishedMatchWindow() {
        FindFixture().use { fixture ->
            fixture.startMatchNavigation(nativeWindowAlreadyLoaded = true)
            assertTrue(fixture.session.updateFindFieldValue(TextFieldValue("beta")))
            fixture.drain()
            assertEquals(
                Utf16Range(6L, 10L),
                (fixture.session.findStatus as FindStatus.Match).match.range
            )
            assertEquals(EditorDocumentStatus.Ready, fixture.session.state.status)
        }
    }

    @Test
    fun autosaveContinuesWhileQrEncodingOwnsAnOlderSnapshot() {
        val gate = CompletableDeferred<Unit>()
        val delegate = TestQrTransferProcessor()
        val processor = object : QrTransferProcessor by delegate {
            override suspend fun encodeQr(
                snapshot: EditorDocumentSnapshot,
                expectedBytes: Long,
                format: DocumentFormat
            ): QrCodeGrid {
                gate.await()
                return delegate.encodeQr(snapshot, expectedBytes, format)
            }
        }
        TransferFixture(qrProcessor = processor).use { fixture ->
            assertTrue(fixture.session.requestQrShare())
            fixture.drain()
            assertTrue(fixture.session.qrShareStatus is QrShareStatus.Preparing)
            val snapshot = fixture.document.capturedSnapshots.single()
            fixture.editAndSaveWhileTransferWaits()
            assertFalse(snapshot.isClosed)
            gate.complete(Unit)
            fixture.drain()
            assertTrue(fixture.session.qrShareStatus is QrShareStatus.Ready)
            assertEquals(listOf("before"), delegate.encodedTexts)
            assertTrue(snapshot.isClosed)
        }
    }

    @Test
    fun autosaveContinuesWhileNfcEncodingOwnsAnOlderSnapshot() {
        val gate = CompletableDeferred<Unit>()
        val delegate = TestNfcTransferProcessor()
        val processor = object : NfcTransferProcessor by delegate {
            override suspend fun encodeNfc(
                snapshot: EditorDocumentSnapshot,
                expectedBytes: Long,
                format: DocumentFormat,
                tagLabel: String?
            ): NfcTransferEnvelope {
                gate.await()
                return delegate.encodeNfc(snapshot, expectedBytes, format, tagLabel)
            }
        }
        TransferFixture(nfcProcessor = processor).use { fixture ->
            assertTrue(fixture.session.requestNfcWrite())
            val configuring = fixture.session.nfcWriteStatus as NfcWriteStatus.Configuring
            assertTrue(fixture.session.confirmNfcWriteConfiguration(configuring.generation, null))
            fixture.drain()
            assertTrue(fixture.session.nfcWriteStatus is NfcWriteStatus.Preparing)
            val snapshot = fixture.document.capturedSnapshots.single()
            fixture.editAndSaveWhileTransferWaits()
            assertFalse(snapshot.isClosed)
            gate.complete(Unit)
            fixture.drain()
            assertTrue(fixture.session.nfcWriteStatus is NfcWriteStatus.Ready)
            assertEquals(listOf("before"), delegate.encodedTexts)
            assertTrue(snapshot.isClosed)
        }
    }

    @Test
    fun cancellingQrEncodingClosesItsSnapshotWithoutCancellingAutosave() {
        val delegate = TestQrTransferProcessor()
        val processor = object : QrTransferProcessor by delegate {
            override suspend fun encodeQr(
                snapshot: EditorDocumentSnapshot,
                expectedBytes: Long,
                format: DocumentFormat
            ): QrCodeGrid = CompletableDeferred<QrCodeGrid>().await()
        }
        TransferFixture(qrProcessor = processor).use { fixture ->
            assertTrue(fixture.session.requestQrShare())
            fixture.drain()
            val snapshot = fixture.document.capturedSnapshots.single()
            fixture.editAndSaveWhileTransferWaits()
            fixture.session.cancelQrShare()
            fixture.drain()
            assertTrue(snapshot.isClosed)
            assertEquals(QrShareStatus.Idle, fixture.session.qrShareStatus)
            assertEquals(SourceSaveStatus.Saved, fixture.session.sourceSaveStatus)
            assertTrue(fixture.session.canCloseSafely)
        }
    }

    private fun atPendingMatchWindow(action: (EditorSession) -> Unit): FindFixture =
        FindFixture().also { fixture ->
            try {
                fixture.startMatchNavigation()
                action(fixture.session)
            } catch (failure: Throwable) {
                fixture.close()
                throw failure
            }
        }

    private class FindFixture : AutoCloseable {
        val document = TestEditorDocument("alpha beta")
        val worker = QueuedSessionTestDispatcher()
        val operations = QueuedSessionTestDispatcher()
        val session = EditorSession(
            title = "test.txt",
            state = EditorDocumentState(document, worker),
            operationDispatcher = operations,
            editSynchronizationDelay = {},
            findDelay = {}
        )

        fun startMatchNavigation(nativeWindowAlreadyLoaded: Boolean = false) {
            session.openInitialEditor()
            drain()
            assertTrue(session.showFind())
            assertTrue(session.updateFindFieldValue(TextFieldValue("alpha")))
            drainUntil { session.state.status == EditorDocumentStatus.LoadingEditWindow }
            if (nativeWindowAlreadyLoaded) assertTrue(worker.runNext())
        }

        fun drain() = drainUntil { operations.pendingCount == 0 && worker.pendingCount == 0 }

        private fun drainUntil(done: () -> Boolean) {
            repeat(100) {
                if (done()) return
                if (!operations.runNext()) worker.runNext()
            }
            check(done()) { "Find did not settle after 100 continuations" }
        }

        override fun close() {
            session.close()
            drain()
        }
    }

    private class TransferFixture(
        qrProcessor: QrTransferProcessor? = null,
        nfcProcessor: NfcTransferProcessor? = null
    ) : AutoCloseable {
        val document = TestEditorDocument("before")
        val source = TestEditorDocumentSource("content://test.documents/note.txt")
        val operations = QueuedSessionTestDispatcher()
        val session = EditorSession(
            title = "note.txt",
            state = EditorDocumentState(document, ImmediateSessionTestDispatcher),
            documentSource = source,
            operationDispatcher = operations,
            editSynchronizationDelay = {},
            qrTransferProcessor = qrProcessor,
            nfcTransferProcessor = nfcProcessor
        )

        init {
            session.openInitialEditor()
            drain()
        }

        fun editAndSaveWhileTransferWaits() {
            val draft = checkNotNull(session.activeDraft)
            assertTrue(session.canApplyEditorInput(draft))
            draft.textFieldState.edit { replace(0, length, "after") }
            session.observeActiveEdit(draft, draft.captureFieldValue())
            drain()
            assertEquals(listOf("after"), source.savedTexts)
            assertEquals(SourceSaveStatus.Saved, session.sourceSaveStatus)
            assertFalse(session.hasUnsavedChanges)
        }

        fun drain() {
            repeat(100) { if (!operations.runNext()) return }
            assertEquals("Autosave must not busy-retry a snapshot slot", 0, operations.pendingCount)
        }

        override fun close() {
            session.close()
            drain()
            assertTrue(document.capturedSnapshots.all(TestEditorDocumentSnapshot::isClosed))
        }
    }
}
