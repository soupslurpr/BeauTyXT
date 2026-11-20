package dev.soupslurpr.beautyxt.ui.editor

import dev.soupslurpr.beautyxt.testing.QueuedSessionTestDispatcher
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TextAppearanceStoreTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun onlyConfirmedAbsenceOfBothFilesMeansFirstRun() {
        val base = File(folder.root, "appearance")
        val backup = File(base.path + ".bak")
        fun readMissing() = readSavedAppearances(base) { throw FileNotFoundException() }
        assertEquals(DocumentAppearances(), readMissing())
        backup.writeText("Source.size=24")
        val recovered = readSavedAppearances(base) {
            Files.move(backup.toPath(), base.toPath())
            base.inputStream()
        }
        assertEquals(24f, recovered.source.custom[AppearanceOption.Size])
        assertThrows(FileNotFoundException::class.java) { readMissing() }
        Files.move(base.toPath(), backup.toPath())
        assertThrows(FileNotFoundException::class.java) { readMissing() }
        Files.delete(backup.toPath())
        Files.createSymbolicLink(base.toPath(), backup.toPath())
        assertThrows(FileNotFoundException::class.java) { readMissing() }
    }

    @Test
    fun failedLoadWithholdsWritesAndRetryOnlyReads() {
        val io = QueuedSessionTestDispatcher()
        val disk = TestStorage()
        val preferences = DocumentAppearances(source = TextAppearance().change(AppearanceOption.Size, 30f))
        disk.read = { throw IllegalArgumentException("unreadable properties") }
        val store = TextAppearanceStore(disk, io)
        assertEquals(TextAppearanceStoreState.Loading, store.state.value)
        io.runAll()
        assertEquals(TextAppearanceStoreState.LoadFailed, store.state.value)
        store.updateOption(AppearanceGroup.Source, AppearanceOption.Margin, 0f)
        store.resetGroup(AppearanceGroup.Markdown)
        store.retrySave()
        disk.read = { preferences }
        store.retryLoad()
        assertEquals(TextAppearanceStoreState.Loading, store.state.value)
        store.resetSavedAppearance()
        io.runAll()
        assertEquals(TextAppearanceStoreState.Ready(preferences), store.state.value)
        assertEquals(0, disk.resets)
        assertTrue(disk.writes.isEmpty())
    }

    @Test
    fun failedResetRemainsReadOnlyUntilTheConfirmedResetSucceeds() {
        val io = QueuedSessionTestDispatcher()
        val disk = TestStorage()
        disk.read = { throw IOException() }
        val store = TextAppearanceStore(disk, io)
        io.runAll()
        disk.resetError = true
        store.resetSavedAppearance()
        io.runAll()
        assertEquals(TextAppearanceStoreState.LoadFailed, store.state.value)
        disk.resetError = false
        store.resetSavedAppearance()
        io.runAll()
        assertEquals(TextAppearanceStoreState.Ready(DocumentAppearances()), store.state.value)
        assertEquals(2, disk.resets)
    }

    @Test
    fun optionMutationsMergeAcrossActivitiesAndStaleWriteCompletionsCannotPublish() {
        val io = QueuedSessionTestDispatcher()
        val disk = TestStorage()
        val store = TextAppearanceStore(disk, io)
        fun ready() = store.state.value as TextAppearanceStoreState.Ready
        io.runAll()
        store.updateOption(AppearanceGroup.Source, AppearanceOption.Size, 23f)
        store.updateOption(AppearanceGroup.Source, AppearanceOption.Margin, 12f)
        disk.onWrite = {
            if (disk.writes.size == 1) {
                store.updateOption(AppearanceGroup.Markdown, AppearanceOption.Size, 41f)
                throw IOException("older write failed")
            }
            assertFalse(ready().saveFailed)
        }
        io.runAll()
        assertEquals(2, disk.writes.size)
        assertEquals(mapOf(AppearanceOption.Size to 23f, AppearanceOption.Margin to 12f), ready().preferences.source.custom)
        assertEquals(41f, disk.writes.last().markdown.custom[AppearanceOption.Size])

        disk.onWrite = { throw IOException("latest write failed") }
        store.resetGroup(AppearanceGroup.Source)
        io.runAll()
        assertTrue(ready().saveFailed)
        assertTrue(ready().preferences.source.custom.isEmpty())
        disk.onWrite = {
            if (disk.writes.size == 4) store.updateOption(AppearanceGroup.Markdown, AppearanceOption.Weight, 450f)
            else assertTrue(ready().saveFailed) // Stale success has not cleared the latest failure indication.
        }
        store.retrySave()
        io.runAll()
        assertFalse(ready().saveFailed)
        assertEquals(mapOf(AppearanceOption.Size to 41f, AppearanceOption.Weight to 450f), disk.writes.last().markdown.custom)
        assertEquals(disk.writes.last(), ready().preferences)
    }

    private class TestStorage : AppearanceStorage {
        var read: () -> DocumentAppearances = { DocumentAppearances() }
        var resets = 0
        var resetError = false
        val writes = mutableListOf<DocumentAppearances>()
        var onWrite: () -> Unit = {}

        override fun read() = read.invoke()
        override fun write(preferences: DocumentAppearances) {
            writes += preferences
            onWrite()
        }
        override fun reset() {
            resets++
            if (resetError) throw IOException()
        }
    }
}
