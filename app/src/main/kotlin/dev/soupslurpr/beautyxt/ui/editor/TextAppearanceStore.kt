/* Persists only app-wide display preferences, outside legacy document-state cleanup. */
package dev.soupslurpr.beautyxt.ui.editor

import android.content.Context
import android.util.AtomicFile
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

internal sealed interface TextAppearanceStoreState {
    data object Loading : TextAppearanceStoreState
    data object LoadFailed : TextAppearanceStoreState
    data class Ready(val preferences: DocumentAppearances, val saveFailed: Boolean = false) : TextAppearanceStoreState
}

internal interface AppearanceStorage {
    fun read(): DocumentAppearances
    fun write(preferences: DocumentAppearances)
    fun reset()
}

private fun definitelyAbsent(file: File): Boolean = Files.notExists(file.toPath(), LinkOption.NOFOLLOW_LINKS)

/** AtomicFile owns backup recovery, including when the base file is missing. */
internal fun readSavedAppearances(base: File, openRead: () -> InputStream): DocumentAppearances {
    val stream = try {
        openRead()
    } catch (missing: FileNotFoundException) {
        if (definitelyAbsent(base) && definitelyAbsent(File(base.path + ".bak"))) return DocumentAppearances()
        throw missing
    }
    return stream.bufferedReader().use(::readDocumentAppearances)
}

internal class AtomicAppearanceStorage(base: File) : AppearanceStorage {
    private val file = AtomicFile(base)
    override fun read() = readSavedAppearances(file.baseFile, file::openRead)

    override fun write(preferences: DocumentAppearances) {
        val stream = file.startWrite()
        try {
            val writer = stream.writer()
            writeDocumentAppearances(preferences, writer)
            writer.flush()
            stream.fd.sync()
            file.finishWrite(stream)
        } catch (failure: Throwable) {
            file.failWrite(stream)
            throw failure
        }
        // AtomicFile can log a failed rename without throwing.
        if (file.openRead().bufferedReader().use(::readDocumentAppearances) != preferences)
            throw IOException("Appearance write was not published")
    }

    override fun reset() {
        file.delete()
        if (!listOf("", ".bak", ".new").all { definitelyAbsent(File(file.baseFile.path + it)) })
            throw IOException("Could not remove saved appearance")
    }
}

/** Applies UI mutations atomically; one worker orders and conflates their disk snapshots. */
internal class TextAppearanceStore(storage: AppearanceStorage, dispatcher: CoroutineDispatcher) {
    constructor(context: Context) : this(
        AtomicAppearanceStorage(File(context.noBackupFilesDir, "text-appearance.properties")), Dispatchers.IO
    )

    private sealed interface Operation {
        data object Read : Operation
        data object Reset : Operation
        data class Write(val version: Long, val preferences: DocumentAppearances) : Operation
    }

    private val operations = Channel<Operation>(Channel.CONFLATED)
    private var version = 0L
    private val mutableState = MutableStateFlow<TextAppearanceStoreState>(TextAppearanceStoreState.Loading)
    val state = mutableState.asStateFlow()

    init {
        operations.trySend(Operation.Read)
        CoroutineScope(dispatcher).launch {
            for (operation in operations) {
                try {
                    when (operation) {
                        Operation.Read -> mutableState.value = TextAppearanceStoreState.Ready(storage.read())
                        Operation.Reset -> {
                            storage.reset()
                            mutableState.value = TextAppearanceStoreState.Ready(DocumentAppearances())
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
        else mutableState.value = TextAppearanceStoreState.LoadFailed
    }

    @Synchronized
    private fun finishWrite(write: Operation.Write, failed: Boolean) {
        if (version == write.version) mutableState.value = TextAppearanceStoreState.Ready(write.preferences, failed)
    }

    @Synchronized
    private fun mutate(change: (DocumentAppearances) -> DocumentAppearances) {
        val current = mutableState.value as? TextAppearanceStoreState.Ready ?: return
        val next = change(current.preferences)
        version = Math.incrementExact(version)
        mutableState.value = current.copy(preferences = next)
        operations.trySend(Operation.Write(version, next))
    }

    fun updateOption(group: AppearanceGroup, option: AppearanceOption, value: Float?) =
        mutate { it.change(group, it[group].change(option, value)) }

    fun update(group: AppearanceGroup, appearance: TextAppearance) = mutate { it.change(group, appearance) }
    fun resetGroup(group: AppearanceGroup) = update(group, TextAppearance())
    fun retrySave() = mutate { it }

    @Synchronized
    private fun recover(operation: Operation) {
        if (mutableState.value != TextAppearanceStoreState.LoadFailed) return
        mutableState.value = TextAppearanceStoreState.Loading
        operations.trySend(operation)
    }

    fun retryLoad() = recover(Operation.Read)
    fun resetSavedAppearance() = recover(Operation.Reset)
}
