package dev.soupslurpr.beautyxt.ui.designsystem

import dev.soupslurpr.beautyxt.testing.QueuedSessionTestDispatcher
import java.io.ByteArrayInputStream
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

class AppThemeStoreTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun onlyConfirmedAbsenceOfBothFilesMeansFirstRun() {
        val base = File(folder.root, APP_THEME_FILE_NAME)
        val backup = File(base.path + ".bak")
        fun readMissing() = readSavedAppTheme(base) { throw FileNotFoundException() }
        assertEquals(AppThemePreferences(trueBlack = false), readMissing())

        backup.writeText("true_black=true\n")
        val recovered = readSavedAppTheme(base) {
            Files.move(backup.toPath(), base.toPath())
            base.inputStream()
        }
        assertEquals(AppThemePreferences(trueBlack = true), recovered)

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
        disk.read = { throw IllegalArgumentException("corrupt theme file") }
        val store = AppThemeStore(disk, io)
        assertEquals(AppThemeStoreState.Loading, store.state.value)
        io.runAll()
        assertEquals(AppThemeStoreState.LoadFailed, store.state.value)

        store.setTrueBlack(true)
        store.retrySave()
        assertTrue(disk.writes.isEmpty())

        disk.read = { AppThemePreferences(trueBlack = true) }
        store.retryLoad()
        assertEquals(AppThemeStoreState.Loading, store.state.value)
        store.resetSavedTheme()
        io.runAll()
        assertEquals(
            AppThemeStoreState.Ready(AppThemePreferences(trueBlack = true)),
            store.state.value
        )
        assertEquals(0, disk.resets)
        assertTrue(disk.writes.isEmpty())
    }

    @Test
    fun failedResetRemainsReadOnlyUntilConfirmedResetSucceeds() {
        val io = QueuedSessionTestDispatcher()
        val disk = TestStorage()
        disk.read = { throw IOException("unreadable") }
        val store = AppThemeStore(disk, io)
        io.runAll()
        assertEquals(AppThemeStoreState.LoadFailed, store.state.value)

        disk.resetError = true
        store.resetSavedTheme()
        io.runAll()
        assertEquals(AppThemeStoreState.LoadFailed, store.state.value)

        disk.resetError = false
        store.resetSavedTheme()
        io.runAll()
        assertEquals(
            AppThemeStoreState.Ready(AppThemePreferences(trueBlack = false)),
            store.state.value
        )
        assertEquals(2, disk.resets)
    }

    @Test
    fun rapidTogglesPreserveLatestChoiceAndIgnoreStaleWriteCompletions() {
        val io = QueuedSessionTestDispatcher()
        val disk = TestStorage()
        val store = AppThemeStore(disk, io)
        fun ready() = store.state.value as AppThemeStoreState.Ready
        io.runAll()
        assertEquals(AppThemePreferences(trueBlack = false), ready().preferences)

        store.setTrueBlack(true)
        store.setTrueBlack(false)
        store.setTrueBlack(true)
        disk.onWrite = {
            if (disk.writes.size == 1) {
                store.setTrueBlack(false)
                throw IOException("older write failed")
            }
            assertFalse(ready().saveFailed)
        }
        io.runAll()
        assertEquals(2, disk.writes.size)
        assertEquals(AppThemePreferences(trueBlack = false), ready().preferences)
        assertEquals(AppThemePreferences(trueBlack = false), disk.writes.last())
        assertFalse(ready().saveFailed)

        disk.onWrite = { throw IOException("latest write failed") }
        store.setTrueBlack(true)
        io.runAll()
        assertTrue(ready().saveFailed)
        assertEquals(AppThemePreferences(trueBlack = true), ready().preferences)

        disk.onWrite = {
            if (disk.writes.size == 4) {
                store.setTrueBlack(false)
            } else {
                // Stale success from version 5 must not clear failure before version 6 finishes.
                assertTrue(ready().saveFailed)
            }
        }
        store.retrySave()
        io.runAll()
        assertFalse(ready().saveFailed)
        assertEquals(AppThemePreferences(trueBlack = false), ready().preferences)
        assertEquals(AppThemePreferences(trueBlack = false), disk.writes.last())
    }

    @Test
    fun publishedByteVerificationRequiresExactCanonicalBytes() {
        val canonical = "true_black=true\n".toByteArray(Charsets.UTF_8)
        assertTrue(matchesPublishedBytes(ByteArrayInputStream(canonical), canonical))
        assertFalse(matchesPublishedBytes(ByteArrayInputStream("true_black=true\r\n".toByteArray()), canonical))
        assertFalse(matchesPublishedBytes(ByteArrayInputStream("true_black=true".toByteArray()), canonical))
        assertFalse(matchesPublishedBytes(ByteArrayInputStream("true_black=true\n\n".toByteArray()), canonical))
    }

    private class TestStorage : AppThemeStorage {
        var read: () -> AppThemePreferences = { AppThemePreferences() }
        var resets = 0
        var resetError = false
        val writes = mutableListOf<AppThemePreferences>()
        var onWrite: () -> Unit = {}

        override fun read() = read.invoke()
        override fun write(preferences: AppThemePreferences) {
            writes += preferences
            onWrite()
        }
        override fun reset() {
            resets++
            if (resetError) throw IOException("cannot delete")
        }
    }
}
