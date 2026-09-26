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
    private const val MAX_RETAINED_SHARES = 4
    private const val MAX_RETAINED_BYTES = 256L * 1024 * 1024

    @Synchronized
    fun retain(context: Context, name: String, mimeType: String, input: SealedInput): ExcerptShareLease {
        if (entries.size >= MAX_RETAINED_SHARES ||
            entries.values.sumOf { it.input.byteCount } + input.byteCount > MAX_RETAINED_BYTES) throw ExcerptShareLimitException()
        val uri = Uri.Builder().scheme("content").authority("${context.packageName}.excerpts")
            .appendPath("selection").appendPath(UUID.randomUUID().toString()).appendPath(name).build()
        entries[uri] = Entry(name, mimeType, input)
        val application = context.applicationContext
        return ExcerptShareLease(uri) { release(application, uri) }
    }

    @Synchronized
    fun <T> withEntry(uri: Uri, read: (Entry) -> T): T = read(entries[uri] ?: throw FileNotFoundException("Excerpt is no longer available"))

    @Synchronized
    private fun release(context: Context, uri: Uri) {
        entries.remove(uri)?.let { entry ->
            try { context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            finally { entry.input.close() }
        }
    }
}
