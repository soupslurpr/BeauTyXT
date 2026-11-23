package dev.soupslurpr.beautyxt.ui.autosave

import dev.soupslurpr.beautyxt.document.DocumentFormat
import dev.soupslurpr.beautyxt.testing.QueuedSessionTestDispatcher
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Files
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Verifies ordered SAF grant transactions, reconciliation, leases, and fail-closed recovery. */
class NewDocumentAutosaveStoreTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun onlyConfirmedAbsenceOfBaseAndBackupMeansDisabled() {
        val base = File(folder.root, NEW_DOCUMENT_AUTOSAVE_FILE_NAME)
        val backup = File(base.path + ".bak")
        fun readMissing() = readSavedNewDocumentAutosaveMarker(base) { throw FileNotFoundException() }
        assertEquals(NewDocumentAutosaveMarker(), readMissing())

        backup.writeText("state=enabled\nformat=md\n")
        val recovered = readSavedNewDocumentAutosaveMarker(base) {
            Files.move(backup.toPath(), base.toPath())
            base.inputStream()
        }
        assertEquals(
            NewDocumentAutosaveMarker(NewDocumentAutosaveMarkerState.Enabled, DocumentFormat.Markdown),
            recovered
        )

        assertThrows(FileNotFoundException::class.java) { readMissing() }
        Files.move(base.toPath(), backup.toPath())
        assertThrows(FileNotFoundException::class.java) { readMissing() }
    }

    @Test
    fun absentMarkerReleasesOrphanPersistedGrantsInsteadOfAdopting() {
        val io = QueuedSessionTestDispatcher()
        val storage = FakeStorage()
        val access = FakeFolderAccess().apply {
            grants["content://provider/tree/orphan"] = PersistedTreeGrant(
                uri = "content://provider/tree/orphan",
                isReadPermission = true,
                isWritePermission = true,
                isTreeUri = true
            )
        }
        val store = NewDocumentAutosaveStore(storage, access, io)
        io.runAll()

        val ready = store.state.value as NewDocumentAutosaveStoreState.Ready
        assertEquals(NewDocumentAutosaveStatus.Disabled, ready.status)
        assertTrue(access.grants.isEmpty())
    }

    @Test
    fun selectChangeFormatAndDisableMaintainExactGrantAndMarkerOrder() {
        val io = QueuedSessionTestDispatcher()
        val storage = FakeStorage()
        val access = FakeFolderAccess().apply {
            probes["content://provider/tree/notes"] = FolderProbeResult.Available("Notes")
            probes["content://provider/tree/work"] = FolderProbeResult.Available("Work")
        }
        val store = NewDocumentAutosaveStore(storage, access, io)
        io.runAll()

        store.selectFolder(
            encodedUri = "content://provider/tree/notes",
            offeredRead = true,
            offeredWrite = true,
            offeredPersistable = true
        )
        io.runAll()

        val enabled = store.state.value as NewDocumentAutosaveStoreState.Ready
        assertEquals(
            NewDocumentAutosaveStatus.Enabled("content://provider/tree/notes", "Notes"),
            enabled.status
        )
        assertEquals(DocumentFormat.PlainText, enabled.format)
        assertEquals(setOf("content://provider/tree/notes"), access.grants.keys)
        assertEquals(
            listOf(
                "probe:content://provider/tree/notes",
                "write:Changing:PlainText",
                "take:content://provider/tree/notes",
                "write:Enabled:PlainText"
            ),
            access.events
        )

        store.setFormat(DocumentFormat.Markdown)
        io.runAll()
        val markdownReady = store.state.value as NewDocumentAutosaveStoreState.Ready
        assertEquals(DocumentFormat.Markdown, markdownReady.format)
        assertEquals(NewDocumentAutosaveMarkerState.Enabled, storage.marker.state)
        assertEquals(DocumentFormat.Markdown, storage.marker.format)
        assertNotNull(storage.marker.grantTime)

        access.events.clear()
        store.selectFolder(
            encodedUri = "content://provider/tree/work",
            offeredRead = true,
            offeredWrite = true,
            offeredPersistable = true
        )
        io.runAll()
        val changed = store.state.value as NewDocumentAutosaveStoreState.Ready
        assertEquals(
            NewDocumentAutosaveStatus.Enabled("content://provider/tree/work", "Work"),
            changed.status
        )
        assertEquals(DocumentFormat.Markdown, changed.format)
        assertEquals(setOf("content://provider/tree/work"), access.grants.keys)
        assertEquals(
            listOf(
                "probe:content://provider/tree/work",
                "write:Changing:Markdown",
                "release:content://provider/tree/notes",
                "take:content://provider/tree/work",
                "write:Enabled:Markdown"
            ),
            access.events
        )

        store.disable()
        io.runAll()
        val disabled = store.state.value as NewDocumentAutosaveStoreState.Ready
        assertEquals(NewDocumentAutosaveStatus.Disabled, disabled.status)
        assertTrue(storage.marker.isDefault)
        assertTrue(access.grants.isEmpty())
    }

    @Test
    fun failedFolderProbeRollsBackGrantAndPreservesPreviousFolder() {
        val io = QueuedSessionTestDispatcher()
        val storage = FakeStorage()
        val access = FakeFolderAccess().apply {
            probes["content://provider/tree/notes"] = FolderProbeResult.Available("Notes")
            probes["content://provider/tree/readonly"] = FolderProbeResult.NotWritableOrMissing
        }
        val store = NewDocumentAutosaveStore(storage, access, io)
        io.runAll()

        store.selectFolder("content://provider/tree/notes", true, true, true)
        io.runAll()

        store.selectFolder("content://provider/tree/readonly", true, true, true)
        io.runAll()

        val ready = store.state.value as NewDocumentAutosaveStoreState.Ready
        assertEquals(
            NewDocumentAutosaveStatus.Enabled("content://provider/tree/notes", "Notes"),
            ready.status
        )
        assertNotNull(ready.errorMessage)
        assertEquals(setOf("content://provider/tree/notes"), access.grants.keys)
        assertEquals(NewDocumentAutosaveMarkerState.Enabled, storage.marker.state)
    }

    @Test
    fun activeLeaseBlocksDisableAndFolderSwitchUntilEditorCloses() {
        val io = QueuedSessionTestDispatcher()
        val storage = FakeStorage()
        val access = FakeFolderAccess().apply {
            probes["content://provider/tree/notes"] = FolderProbeResult.Available("Notes")
            probes["content://provider/tree/work"] = FolderProbeResult.Available("Work")
        }
        val store = NewDocumentAutosaveStore(storage, access, io)
        io.runAll()

        store.selectFolder("content://provider/tree/notes", true, true, true)
        io.runAll()

        val acquired = io.runSuspend { store.revalidateAndAcquireCreationLease(expectedGeneration = null) }
        assertTrue(acquired is CreationLeaseResult.Acquired)
        val lease = (acquired as CreationLeaseResult.Acquired).lease
        assertTrue((store.state.value as NewDocumentAutosaveStoreState.Ready).hasActiveLeases)

        // Switching to a different folder or disabling must be refused while leased so Android
        // never holds two durable persisted grants simultaneously.
        store.selectFolder("content://provider/tree/work", true, true, true)
        io.runAll()
        assertEquals(
            NewDocumentAutosaveStatus.Enabled("content://provider/tree/notes", "Notes"),
            (store.state.value as NewDocumentAutosaveStoreState.Ready).status
        )
        assertEquals(setOf("content://provider/tree/notes"), access.grants.keys)

        store.disable()
        io.runAll()
        assertEquals(
            NewDocumentAutosaveStatus.Enabled("content://provider/tree/notes", "Notes"),
            (store.state.value as NewDocumentAutosaveStoreState.Ready).status
        )
        assertEquals(setOf("content://provider/tree/notes"), access.grants.keys)

        lease.close()
        io.runAll()
        assertFalse((store.state.value as NewDocumentAutosaveStoreState.Ready).hasActiveLeases)

        store.disable()
        io.runAll()
        assertEquals(
            NewDocumentAutosaveStatus.Disabled,
            (store.state.value as NewDocumentAutosaveStoreState.Ready).status
        )
        assertTrue(access.grants.isEmpty())
    }

    @Test
    fun revalidateAndAcquireCreationLeaseRejectsStaleGeneration() {
        val io = QueuedSessionTestDispatcher()
        val storage = FakeStorage()
        val access = FakeFolderAccess().apply {
            probes["content://provider/tree/notes"] = FolderProbeResult.Available("Notes")
            probes["content://provider/tree/work"] = FolderProbeResult.Available("Work")
        }
        val store = NewDocumentAutosaveStore(storage, access, io)
        io.runAll()

        store.selectFolder("content://provider/tree/notes", true, true, true)
        io.runAll()
        val firstGeneration = (store.state.value as NewDocumentAutosaveStoreState.Ready).generation

        store.selectFolder("content://provider/tree/work", true, true, true)
        io.runAll()

        assertEquals(
            CreationLeaseResult.Disabled,
            io.runSuspend { store.revalidateAndAcquireCreationLease(expectedGeneration = firstGeneration) }
        )
    }

    @Test
    fun changingMarkerOnRestartReleasesGrantsAndPromptsReselection() {
        val io = QueuedSessionTestDispatcher()
        val storage = FakeStorage(
            marker = NewDocumentAutosaveMarker(
                state = NewDocumentAutosaveMarkerState.Changing,
                format = DocumentFormat.Markdown
            )
        )
        val access = FakeFolderAccess().apply {
            grants["content://provider/tree/a"] = PersistedTreeGrant(
                "content://provider/tree/a",
                isReadPermission = true,
                isWritePermission = true,
                isTreeUri = true
            )
        }
        val store = NewDocumentAutosaveStore(storage, access, io)
        io.runAll()

        val ready = store.state.value as NewDocumentAutosaveStoreState.Ready
        assertEquals(
            NewDocumentAutosaveStatus.NeedsReselection(
                NewDocumentAutosaveUnavailableReason.InterruptedChange
            ),
            ready.status
        )
        assertEquals(DocumentFormat.Markdown, ready.format)
        assertTrue(access.grants.isEmpty())
    }

    @Test
    fun loadFailedResetDeletesMarkerAndReleasesStrayGrants() {
        val io = QueuedSessionTestDispatcher()
        val storage = FakeStorage(readFailure = IOException("corrupt"))
        val access = FakeFolderAccess().apply {
            grants["content://provider/tree/stray"] = PersistedTreeGrant(
                "content://provider/tree/stray",
                isReadPermission = true,
                isWritePermission = true,
                isTreeUri = true
            )
        }
        val store = NewDocumentAutosaveStore(storage, access, io)
        io.runAll()
        assertEquals(NewDocumentAutosaveStoreState.LoadFailed, store.state.value)

        store.resetSaved()
        io.runAll()
        val ready = store.state.value as NewDocumentAutosaveStoreState.Ready
        assertEquals(NewDocumentAutosaveStatus.Disabled, ready.status)
        assertTrue(access.grants.isEmpty())
    }

    @Test
    fun revalidateBeforeInitialLoadCompletesAcquiresLeaseInsteadOfDisabling() {
        var heldInit: Runnable? = null
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                if (heldInit == null) {
                    heldInit = block
                } else {
                    block.run()
                }
            }
        }
        val storage = FakeStorage(
            marker = NewDocumentAutosaveMarker(
                state = NewDocumentAutosaveMarkerState.Enabled,
                format = DocumentFormat.Markdown
            )
        )
        val access = FakeFolderAccess().apply {
            grants["content://provider/tree/notes"] = PersistedTreeGrant(
                uri = "content://provider/tree/notes",
                isReadPermission = true,
                isWritePermission = true,
                isTreeUri = true
            )
            probes["content://provider/tree/notes"] = FolderProbeResult.Available("Notes")
        }
        val store = NewDocumentAutosaveStore(
            storage = storage as NewDocumentAutosaveStorage,
            folderAccess = access as NewDocumentFolderAccess,
            dispatcher = dispatcher
        )
        // Init has not run yet: state is still Loading when creation acquires the mutex first.
        assertEquals(NewDocumentAutosaveStoreState.Loading, store.state.value)

        var result: CreationLeaseResult? = null
        CoroutineScope(dispatcher).launch {
            result = store.revalidateAndAcquireCreationLease(expectedGeneration = null)
        }
        assertTrue(result is CreationLeaseResult.Acquired)
        assertEquals(1, access.events.count { it == "probe:content://provider/tree/notes" })

        // Running the deferred init afterward must not redundantly re-probe or clear the lease.
        checkNotNull(heldInit).run()
        assertEquals(1, access.events.count { it == "probe:content://provider/tree/notes" })
        assertTrue((store.state.value as NewDocumentAutosaveStoreState.Ready).hasActiveLeases)

        (result as CreationLeaseResult.Acquired).lease.close()
        assertFalse((store.state.value as NewDocumentAutosaveStoreState.Ready).hasActiveLeases)
    }

    @Test
    fun crossProfileTreeAuthorityIsRejectedBeforeProbingOrPersisting() {
        val io = QueuedSessionTestDispatcher()
        val storage = FakeStorage()
        val access = FakeFolderAccess().apply {
            probes["content://10@provider/tree/work"] = FolderProbeResult.Available("Work")
        }
        val store = NewDocumentAutosaveStore(storage, access, io)
        io.runAll()

        store.selectFolder(
            encodedUri = "content://10@provider/tree/work",
            offeredRead = true,
            offeredWrite = true,
            offeredPersistable = true
        )
        io.runAll()

        val ready = store.state.value as NewDocumentAutosaveStoreState.Ready
        assertEquals(NewDocumentAutosaveStatus.Disabled, ready.status)
        assertNotNull(ready.errorMessage)
        assertTrue(access.grants.isEmpty())
        assertTrue(access.events.isEmpty())
    }

    @Test
    fun disableFailsClosedWhenGrantCannotBeRevoked() {
        val io = QueuedSessionTestDispatcher()
        val storage = FakeStorage()
        val access = FakeFolderAccess().apply {
            probes["content://provider/tree/notes"] = FolderProbeResult.Available("Notes")
        }
        val store = NewDocumentAutosaveStore(storage, access, io)
        io.runAll()

        store.selectFolder("content://provider/tree/notes", true, true, true)
        io.runAll()

        access.refuseRelease = true
        store.disable()
        io.runAll()

        val ready = store.state.value as NewDocumentAutosaveStoreState.Ready
        assertEquals(
            NewDocumentAutosaveStatus.Enabled("content://provider/tree/notes", "Notes"),
            ready.status
        )
        assertTrue(ready.saveFailed)
        assertEquals(NewDocumentAutosaveMarkerState.Enabled, storage.marker.state)
        assertEquals(setOf("content://provider/tree/notes"), access.grants.keys)
    }

    @Test
    fun transientUnavailableFolderRecoversOnNextRevalidation() {
        val io = QueuedSessionTestDispatcher()
        val storage = FakeStorage()
        val access = FakeFolderAccess().apply {
            probes["content://provider/tree/notes"] = FolderProbeResult.Available("Notes")
        }
        val store = NewDocumentAutosaveStore(storage, access, io)
        io.runAll()

        store.selectFolder("content://provider/tree/notes", true, true, true)
        io.runAll()
        val generation = (store.state.value as NewDocumentAutosaveStoreState.Ready).generation

        access.probes["content://provider/tree/notes"] = FolderProbeResult.TransientFailure
        assertEquals(
            CreationLeaseResult.Deferred,
            io.runSuspend { store.revalidateAndAcquireCreationLease(expectedGeneration = generation) }
        )
        val transientState = store.state.value as NewDocumentAutosaveStoreState.Ready
        assertEquals(
            NewDocumentAutosaveStatus.NeedsReselection(
                NewDocumentAutosaveUnavailableReason.TransientUnavailable,
                treeUri = "content://provider/tree/notes"
            ),
            transientState.status
        )

        access.probes["content://provider/tree/notes"] = FolderProbeResult.Available("Notes")
        val recovered = io.runSuspend {
            store.revalidateAndAcquireCreationLease(expectedGeneration = generation)
        }
        assertTrue(recovered is CreationLeaseResult.Acquired)
        (recovered as CreationLeaseResult.Acquired).lease.close()
        io.runAll()
    }

    @Test
    fun childTreeUriOrMissingPrefixGrantIsRejectedBeforeProbing() {
        val io = QueuedSessionTestDispatcher()
        val storage = FakeStorage()
        val access = FakeFolderAccess().apply {
            probes["content://provider/tree/notes/document/child"] = FolderProbeResult.Available("Child")
            probes["content://provider/tree/notes"] = FolderProbeResult.Available("Notes")
        }
        val store = NewDocumentAutosaveStore(storage, access, io)
        io.runAll()

        store.selectFolder("content://provider/tree/notes/document/child", true, true, true, offeredPrefix = true)
        io.runAll()
        assertEquals(NewDocumentAutosaveStatus.Disabled, (store.state.value as NewDocumentAutosaveStoreState.Ready).status)
        assertTrue(access.events.isEmpty())

        store.selectFolder("content://provider/tree/notes", true, true, true, offeredPrefix = false)
        io.runAll()
        assertEquals(NewDocumentAutosaveStatus.Disabled, (store.state.value as NewDocumentAutosaveStoreState.Ready).status)
        assertTrue(access.events.isEmpty())
    }

    @Test
    fun absentMarkerWithUnrevocableGrantRefusesToReportDisabled() {
        val io = QueuedSessionTestDispatcher()
        val storage = FakeStorage()
        val access = FakeFolderAccess().apply {
            refuseRelease = true
            grants["content://provider/tree/stuck"] = PersistedTreeGrant(
                uri = "content://provider/tree/stuck",
                isReadPermission = true,
                isWritePermission = true,
                isTreeUri = true
            )
        }
        val store = NewDocumentAutosaveStore(storage, access, io)
        io.runAll()

        assertEquals(NewDocumentAutosaveStoreState.LoadFailed, store.state.value)
        assertEquals(setOf("content://provider/tree/stuck"), access.grants.keys)
    }

    @Test
    fun reselectingTemporarilyUnavailableLeasedFolderRefreshesWithoutInterrupting() {
        val io = QueuedSessionTestDispatcher()
        val storage = FakeStorage()
        val access = FakeFolderAccess().apply {
            probes["content://provider/tree/notes"] = FolderProbeResult.Available("Notes")
        }
        val store = NewDocumentAutosaveStore(storage, access, io)
        io.runAll()

        store.selectFolder("content://provider/tree/notes", true, true, true)
        io.runAll()
        val acquired = io.runSuspend { store.revalidateAndAcquireCreationLease(expectedGeneration = null) }
            as CreationLeaseResult.Acquired

        access.probes["content://provider/tree/notes"] = FolderProbeResult.TransientFailure
        store.refresh()
        io.runAll()
        assertTrue((store.state.value as NewDocumentAutosaveStoreState.Ready).status is NewDocumentAutosaveStatus.NeedsReselection)

        access.probes["content://provider/tree/notes"] = FolderProbeResult.Available("Notes")
        store.selectFolder("content://provider/tree/notes", true, true, true)
        io.runAll()
        assertEquals(
            NewDocumentAutosaveStatus.Enabled("content://provider/tree/notes", "Notes"),
            (store.state.value as NewDocumentAutosaveStoreState.Ready).status
        )
        acquired.lease.close()
        io.runAll()
    }

    @Test
    fun mismatchedGrantTimeAfterAbruptRebootRefusesPreviousFolder() {
        val io = QueuedSessionTestDispatcher()
        val storage = FakeStorage(
            marker = NewDocumentAutosaveMarker(
                state = NewDocumentAutosaveMarkerState.Enabled,
                format = DocumentFormat.Markdown,
                grantTime = 2_000L
            )
        )
        val access = FakeFolderAccess().apply {
            grants["content://provider/tree/old"] = PersistedTreeGrant(
                uri = "content://provider/tree/old",
                isReadPermission = true,
                isWritePermission = true,
                isTreeUri = true,
                persistedTime = 1_000L
            )
            probes["content://provider/tree/old"] = FolderProbeResult.Available("Old Folder")
        }
        val store = NewDocumentAutosaveStore(storage, access, io)
        io.runAll()

        val ready = store.state.value as NewDocumentAutosaveStoreState.Ready
        assertEquals(
            NewDocumentAutosaveStatus.NeedsReselection(
                NewDocumentAutosaveUnavailableReason.InterruptedChange
            ),
            ready.status
        )
        assertTrue(access.grants.isEmpty())
    }

    @Test
    fun duplicateExactAndPrefixRecordsForSameTreeUriAreAccepted() {
        val io = QueuedSessionTestDispatcher()
        val storage = FakeStorage()
        val access = FakeFolderAccess().apply {
            duplicateRecordsPerGrant = true
            probes["content://provider/tree/notes"] = FolderProbeResult.Available("Notes")
        }
        val store = NewDocumentAutosaveStore(storage, access, io)
        io.runAll()

        store.selectFolder("content://provider/tree/notes", true, true, true)
        io.runAll()

        val ready = store.state.value as NewDocumentAutosaveStoreState.Ready
        assertEquals(
            NewDocumentAutosaveStatus.Enabled("content://provider/tree/notes", "Notes"),
            ready.status
        )

        // Startup reconciliation with two records for the same root URI carrying different
        // timestamps must succeed as long as one record matches marker.grantTime.
        val restarted = NewDocumentAutosaveStore(storage, access, io)
        io.runAll()
        assertEquals(
            NewDocumentAutosaveStatus.Enabled("content://provider/tree/notes", "Notes"),
            (restarted.state.value as NewDocumentAutosaveStoreState.Ready).status
        )
    }

    private fun <T> QueuedSessionTestDispatcher.runSuspend(block: suspend () -> T): T {
        var outcome: Result<T>? = null
        CoroutineScope(this).launch {
            outcome = runCatching { block() }
        }
        runAll()
        return checkNotNull(outcome) { "queued coroutine did not complete" }.getOrThrow()
    }

    private class FakeStorage(
        var marker: NewDocumentAutosaveMarker = NewDocumentAutosaveMarker(),
        var readFailure: Throwable? = null,
        private val events: MutableList<String>? = null
    ) : NewDocumentAutosaveStorage {
        var linkedEvents: MutableList<String>? = events

        override fun read(): NewDocumentAutosaveMarker {
            readFailure?.let { throw it }
            return marker
        }

        override fun write(marker: NewDocumentAutosaveMarker) {
            this.marker = marker
            linkedEvents?.add("write:${marker.state}:${marker.format}")
        }

        override fun reset() {
            marker = NewDocumentAutosaveMarker()
            linkedEvents?.add("reset")
        }
    }

    private class FakeFolderAccess : NewDocumentFolderAccess {
        val grants = linkedMapOf<String, PersistedTreeGrant>()
        val probes = linkedMapOf<String, FolderProbeResult>()
        val events = mutableListOf<String>()
        var refuseRelease = false
        var duplicateRecordsPerGrant = false
        private var nextPersistedTime = 1_000L

        override fun isValidTreeUri(encodedUri: String): Boolean {
            if (!encodedUri.startsWith("content://")) return false
            val withoutScheme = encodedUri.removePrefix("content://")
            val authority = withoutScheme.substringBefore('/')
            val pathSegments = withoutScheme.substringAfter('/', "").split('/').filter(String::isNotEmpty)
            return authority.isNotBlank() &&
                '@' !in authority &&
                pathSegments.size == 2 &&
                pathSegments[0] == "tree"
        }

        override fun listPersistedGrants(): List<PersistedTreeGrant> =
            if (duplicateRecordsPerGrant) {
                grants.values.flatMap { grant ->
                    listOf(grant.copy(persistedTime = grant.persistedTime - 50L), grant)
                }
            } else {
                grants.values.toList()
            }

        override fun takePersistableReadWriteGrant(encodedUri: String) {
            events.add("take:$encodedUri")
            grants[encodedUri] = PersistedTreeGrant(
                uri = encodedUri,
                isReadPermission = true,
                isWritePermission = true,
                isTreeUri = isValidTreeUri(encodedUri),
                persistedTime = nextPersistedTime++
            )
            check(grants.size <= 1) { "violated single persisted grant invariant: ${grants.keys}" }
        }

        override fun releasePersistableReadWriteGrant(encodedUri: String) {
            events.add("release:$encodedUri")
            if (!refuseRelease) {
                grants.remove(encodedUri)
            }
        }

        override suspend fun probeTreeFolder(encodedUri: String): FolderProbeResult {
            events.add("probe:$encodedUri")
            return probes[encodedUri] ?: FolderProbeResult.NotWritableOrMissing
        }
    }

    private fun NewDocumentAutosaveStore(
        storage: FakeStorage,
        access: FakeFolderAccess,
        io: QueuedSessionTestDispatcher
    ): NewDocumentAutosaveStore {
        storage.linkedEvents = access.events
        return NewDocumentAutosaveStore(
            storage = storage as NewDocumentAutosaveStorage,
            folderAccess = access as NewDocumentFolderAccess,
            dispatcher = io
        )
    }
}
