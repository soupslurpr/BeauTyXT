/* Persists only content-free app theme preferences, outside legacy document-state cleanup. */
package dev.soupslurpr.beautyxt.ui.designsystem

import android.content.Context
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal sealed interface AppThemeStoreState {
    data object Loading : AppThemeStoreState
    data object LoadFailed : AppThemeStoreState
    data class Ready(val preferences: AppThemePreferences, val saveFailed: Boolean = false) : AppThemeStoreState
}

/** Resolves the effective True black state while keeping standard dark during load or failure. */
internal val AppThemeStoreState.trueBlackOrDefault: Boolean
    get() = (this as? AppThemeStoreState.Ready)?.preferences?.trueBlack == true

internal interface AppThemeStorage {
    fun read(): AppThemePreferences
    fun write(preferences: AppThemePreferences)
    fun reset()
}

private fun definitelyAbsent(file: File): Boolean =
    Files.notExists(file.toPath(), LinkOption.NOFOLLOW_LINKS)

/** AtomicFile owns backup recovery, including when the base file is missing. */
internal fun readSavedAppTheme(base: File, openRead: () -> InputStream): AppThemePreferences {
    val stream = try {
        openRead()
    } catch (missing: FileNotFoundException) {
        if (definitelyAbsent(base) && definitelyAbsent(File(base.path + ".bak"))) {
            return AppThemePreferences()
        }
        throw missing
    }
    return stream.use(::readAppThemePreferences)
}

/** Confirms the published stream matches the exact expected byte sequence without over-reading. */
internal fun matchesPublishedBytes(stream: InputStream, expected: ByteArray): Boolean {
    val observed = ByteArrayOutputStream(expected.size + 1)
    val chunk = ByteArray(minOf(expected.size + 1, 256))
    while (observed.size() <= expected.size) {
        val remaining = expected.size + 1 - observed.size()
        val read = stream.read(chunk, 0, minOf(chunk.size, remaining))
        if (read < 0) break
        observed.write(chunk, 0, read)
    }
    return observed.toByteArray().contentEquals(expected)
}

/** Defers directory lookup to the I/O worker and deletes the file when all settings are default. */
internal class AtomicAppThemeStorage(private val resolveBaseFile: () -> File) : AppThemeStorage {
    private var cachedFile: AtomicFile? = null

    private fun atomicFile(): AtomicFile =
        cachedFile ?: AtomicFile(resolveBaseFile()).also { cachedFile = it }

    override fun read(): AppThemePreferences {
        val file = atomicFile()
        return readSavedAppTheme(file.baseFile, file::openRead)
    }

    override fun write(preferences: AppThemePreferences) {
        if (preferences.isDefault) {
            reset()
            return
        }
        val file = atomicFile()
        val encoded = formatAppThemePreferences(preferences).toByteArray(Charsets.UTF_8)
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
        // AtomicFile can log a failed rename without throwing; verify exact canonical bytes.
        if (!file.openRead().use { matchesPublishedBytes(it, encoded) }) {
            throw IOException("App theme write was not published")
        }
    }

    override fun reset() {
        val file = atomicFile()
        file.delete()
        if (!listOf("", ".bak", ".new").all { definitelyAbsent(File(file.baseFile.path + it)) }) {
            throw IOException("Could not remove saved app theme")
        }
    }
}

/** Applies UI mutations atomically; one worker orders and conflates their disk snapshots. */
internal class AppThemeStore(storage: AppThemeStorage, dispatcher: CoroutineDispatcher) {
    constructor(context: Context) : this(
        AtomicAppThemeStorage { File(context.noBackupFilesDir, APP_THEME_FILE_NAME) },
        Dispatchers.IO
    )

    private sealed interface Operation {
        data object Read : Operation
        data object Reset : Operation
        data class Write(val version: Long, val preferences: AppThemePreferences) : Operation
    }

    private val operations = Channel<Operation>(Channel.CONFLATED)
    private var version = 0L
    private val mutableState = MutableStateFlow<AppThemeStoreState>(AppThemeStoreState.Loading)
    val state = mutableState.asStateFlow()

    init {
        operations.trySend(Operation.Read)
        CoroutineScope(dispatcher).launch {
            for (operation in operations) {
                try {
                    when (operation) {
                        Operation.Read ->
                            mutableState.value = AppThemeStoreState.Ready(storage.read())

                        Operation.Reset -> {
                            storage.reset()
                            mutableState.value = AppThemeStoreState.Ready(AppThemePreferences())
                        }

                        is Operation.Write -> {
                            storage.write(operation.preferences)
                            finishWrite(operation, failed = false)
                        }
                    }
                } catch (_: IOException) {
                    failed(operation)
                } catch (_: IllegalArgumentException) {
                    failed(operation)
                } catch (_: SecurityException) {
                    failed(operation)
                }
            }
        }
    }

    private fun failed(operation: Operation) {
        if (operation is Operation.Write) finishWrite(operation, failed = true)
        else mutableState.value = AppThemeStoreState.LoadFailed
    }

    @Synchronized
    private fun finishWrite(write: Operation.Write, failed: Boolean) {
        if (version == write.version) {
            mutableState.value = AppThemeStoreState.Ready(write.preferences, failed)
        }
    }

    @Synchronized
    private fun mutate(change: (AppThemePreferences) -> AppThemePreferences) {
        val current = mutableState.value as? AppThemeStoreState.Ready ?: return
        val next = change(current.preferences)
        version = Math.incrementExact(version)
        mutableState.value = current.copy(preferences = next)
        operations.trySend(Operation.Write(version, next))
    }

    fun setTrueBlack(enabled: Boolean) = mutate { it.copy(trueBlack = enabled) }
    fun retrySave() = mutate { it }

    @Synchronized
    private fun recover(operation: Operation) {
        if (mutableState.value != AppThemeStoreState.LoadFailed) return
        mutableState.value = AppThemeStoreState.Loading
        operations.trySend(operation)
    }

    fun retryLoad() = recover(Operation.Read)
    fun resetSavedTheme() = recover(Operation.Reset)
}
