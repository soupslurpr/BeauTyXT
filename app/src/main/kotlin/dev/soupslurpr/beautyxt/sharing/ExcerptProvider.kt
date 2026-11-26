package dev.soupslurpr.beautyxt.sharing

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import dev.soupslurpr.beautyxt.ipc.SealedInput
import java.io.FileNotFoundException
import java.util.UUID

/** Serves only session-owned excerpt capabilities; nothing is written to a private path. */
class ExcerptProvider : ContentProvider() {
    override fun onCreate() = true
    override fun getType(uri: Uri): String = ExcerptShares.withEntry(uri) { it.mimeType }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
        ExcerptShares.withEntry(uri) { entry ->
            val columns = projection?.filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }
                ?: listOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
            MatrixCursor(columns.toTypedArray(), 1).apply {
                addRow(columns.map<String, Any> { if (it == OpenableColumns.SIZE) entry.input.byteCount else entry.name }.toTypedArray())
            }
        }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Excerpt is read-only")
        return ExcerptShares.withEntry(uri) { it.input.openReadOnly(checkNotNull(context)) }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri = throw UnsupportedOperationException("read-only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("read-only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("read-only")
}

internal class ExcerptShareLease(val uri: Uri, private val release: () -> Unit) : AutoCloseable {
    override fun close() = release()
}

internal class ExcerptShareLimitException : IllegalStateException("Too many active excerpt shares")

/** Grants are scoped to an unpredictable exact URI and revoked when their owning session closes. */
internal object ExcerptShares {
    class Entry(val name: String, val mimeType: String, val input: SealedInput)
    private val entries = LinkedHashMap<Uri, Entry>()
    private val illustrationEntries = LinkedHashMap<Uri, Entry>()
    private val pendingIllustrationUris = HashSet<Uri>()
    private const val MAX_RETAINED_SHARES = 4
    private const val MAX_RETAINED_ILLUSTRATION_SHARES = 4
    private const val MAX_RETAINED_BYTES = 256L * 1024 * 1024

    private fun totalRetainedBytes(): Long =
        entries.values.sumOf { it.input.byteCount } + illustrationEntries.values.sumOf { it.input.byteCount }

    @Synchronized
    fun retain(context: Context, name: String, mimeType: String, input: SealedInput): ExcerptShareLease {
        // Verify the excerpt pool itself has capacity before evicting any working illustration shares.
        if (entries.size >= MAX_RETAINED_SHARES ||
            entries.values.sumOf { it.input.byteCount } + input.byteCount > MAX_RETAINED_BYTES) {
            throw ExcerptShareLimitException()
        }
        val application = context.applicationContext
        while (
            illustrationEntries.isNotEmpty() &&
            totalRetainedBytes() + input.byteCount > MAX_RETAINED_BYTES
        ) {
            releaseIllustration(application, illustrationEntries.keys.first())
        }
        val uri = Uri.Builder().scheme("content").authority("${context.packageName}.excerpts")
            .appendPath("selection").appendPath(UUID.randomUUID().toString()).appendPath(name).build()
        entries[uri] = Entry(name, mimeType, input)
        return ExcerptShareLease(uri) { release(application, uri) }
    }

    /**
     * Retains one illustration image share in a dedicated FIFO-evicting pool so illustration
     * shares across multiple windows can never starve document excerpt shares. Newly staged shares
     * do not evict committed working shares for slot count until [commitIllustrationShare] runs
     * after the share chooser launches, so overlapping or failed launches preserve active shares.
     */
    @Synchronized
    fun retainIllustration(context: Context, name: String, mimeType: String, input: SealedInput): ExcerptShareLease {
        // Verify the incoming illustration can fit alongside existing excerpt shares before evicting.
        if (entries.values.sumOf { it.input.byteCount } + input.byteCount > MAX_RETAINED_BYTES) {
            throw ExcerptShareLimitException()
        }
        val application = context.applicationContext
        while (
            illustrationEntries.isNotEmpty() &&
            totalRetainedBytes() + input.byteCount > MAX_RETAINED_BYTES
        ) {
            val evictable = illustrationEntries.keys.firstOrNull { it !in pendingIllustrationUris }
                ?: illustrationEntries.keys.first()
            releaseIllustration(application, evictable)
        }
        val uri = Uri.Builder().scheme("content").authority("${context.packageName}.excerpts")
            .appendPath("illustration").appendPath(UUID.randomUUID().toString()).appendPath(name).build()
        illustrationEntries[uri] = Entry(name, mimeType, input)
        pendingIllustrationUris.add(uri)
        return ExcerptShareLease(uri) { releaseIllustration(application, uri) }
    }

    /** Marks [uri] committed after chooser launch and trims committed shares to capacity. */
    @Synchronized
    fun commitIllustrationShare(context: Context, uri: Uri) {
        pendingIllustrationUris.remove(uri)
        // Refresh insertion order to commit time so FIFO eviction reflects actual share launches
        // and a delayed share can never evict itself immediately upon committing.
        illustrationEntries.remove(uri)?.let { committed ->
            illustrationEntries[uri] = committed
        }
        val application = context.applicationContext
        var committedCount = illustrationEntries.keys.count { it !in pendingIllustrationUris }
        while (committedCount > MAX_RETAINED_ILLUSTRATION_SHARES) {
            val oldestCommitted = illustrationEntries.keys.firstOrNull {
                it != uri && it !in pendingIllustrationUris
            } ?: break
            releaseIllustration(application, oldestCommitted)
            committedCount -= 1
        }
    }

    @Synchronized
    fun <T> withEntry(uri: Uri, read: (Entry) -> T): T =
        read(entries[uri] ?: illustrationEntries[uri] ?: throw FileNotFoundException("Excerpt is no longer available"))

    @Synchronized
    private fun release(context: Context, uri: Uri) {
        entries.remove(uri)?.let { entry ->
            try { context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            finally { entry.input.close() }
        }
    }

    @Synchronized
    private fun releaseIllustration(context: Context, uri: Uri) {
        pendingIllustrationUris.remove(uri)
        illustrationEntries.remove(uri)?.let { entry ->
            try { context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            finally { entry.input.close() }
        }
    }
}
