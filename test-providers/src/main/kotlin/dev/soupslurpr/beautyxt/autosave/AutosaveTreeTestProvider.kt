package dev.soupslurpr.beautyxt.autosave

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentHashMap

private val DEFAULT_ROOT_PROJECTION = arrayOf(
    Root.COLUMN_ROOT_ID,
    Root.COLUMN_FLAGS,
    Root.COLUMN_TITLE,
    Root.COLUMN_DOCUMENT_ID,
    Root.COLUMN_MIME_TYPES
)

private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
    Document.COLUMN_DOCUMENT_ID,
    Document.COLUMN_DISPLAY_NAME,
    Document.COLUMN_MIME_TYPE,
    Document.COLUMN_FLAGS,
    Document.COLUMN_SIZE,
    Document.COLUMN_LAST_MODIFIED
)

/** Separate-UID synthetic DocumentsProvider backing seekable, pipe-only, prefilled, and read-only trees. */
class AutosaveTreeTestProvider : DocumentsProvider() {
    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cols = projection ?: DEFAULT_ROOT_PROJECTION
        val cursor = MatrixCursor(cols)
        addRoot(cursor, AutosaveTreeTestProviderContract.TREE_NORMAL, "Autosave Notes")
        addRoot(cursor, AutosaveTreeTestProviderContract.TREE_PIPE_ONLY, "Pipe Folder")
        addRoot(cursor, AutosaveTreeTestProviderContract.TREE_PREFILLED, "Prefilled Folder")
        addRoot(cursor, AutosaveTreeTestProviderContract.TREE_READ_ONLY, "Read-Only Folder")
        return cursor
    }

    private fun addRoot(cursor: MatrixCursor, id: String, title: String) {
        cursor.newRow().apply {
            add(Root.COLUMN_ROOT_ID, id)
            add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE or Root.FLAG_LOCAL_ONLY)
            add(Root.COLUMN_TITLE, title)
            add(Root.COLUMN_DOCUMENT_ID, id)
            add(Root.COLUMN_MIME_TYPES, "text/*")
        }
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val cols = projection ?: DEFAULT_DOCUMENT_PROJECTION
        val cursor = MatrixCursor(cols)
        when (documentId) {
            AutosaveTreeTestProviderContract.TREE_NORMAL,
            AutosaveTreeTestProviderContract.TREE_PIPE_ONLY,
            AutosaveTreeTestProviderContract.TREE_PREFILLED -> {
                cursor.newRow().apply {
                    add(Document.COLUMN_DOCUMENT_ID, documentId)
                    add(Document.COLUMN_DISPLAY_NAME, folderDisplayName(documentId))
                    add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
                    add(Document.COLUMN_FLAGS, Document.FLAG_DIR_SUPPORTS_CREATE)
                    add(Document.COLUMN_SIZE, 0L)
                    add(Document.COLUMN_LAST_MODIFIED, System.currentTimeMillis())
                }
            }

            AutosaveTreeTestProviderContract.TREE_READ_ONLY -> {
                cursor.newRow().apply {
                    add(Document.COLUMN_DOCUMENT_ID, documentId)
                    add(Document.COLUMN_DISPLAY_NAME, folderDisplayName(documentId))
                    add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
                    add(Document.COLUMN_FLAGS, 0)
                    add(Document.COLUMN_SIZE, 0L)
                    add(Document.COLUMN_LAST_MODIFIED, System.currentTimeMillis())
                }
            }

            else -> {
                val entry = records[documentId] ?: throw FileNotFoundException("unknown doc $documentId")
                cursor.newRow().apply {
                    add(Document.COLUMN_DOCUMENT_ID, documentId)
                    add(Document.COLUMN_DISPLAY_NAME, entry.displayName)
                    add(Document.COLUMN_MIME_TYPE, entry.mimeType)
                    add(
                        Document.COLUMN_FLAGS,
                        Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE
                    )
                    add(Document.COLUMN_SIZE, entry.file.length())
                    add(Document.COLUMN_LAST_MODIFIED, entry.file.lastModified())
                }
            }
        }
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        documentId.startsWith("$parentDocumentId/")

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String
    ): String {
        if (parentDocumentId == AutosaveTreeTestProviderContract.TREE_READ_ONLY) {
            throw SecurityException("read-only test tree")
        }
        val rootDir = treeDir(checkNotNull(context), parentDocumentId)
        rootDir.mkdirs()
        val uniqueName = deduplicateName(parentDocumentId, displayName)
        val docId = "$parentDocumentId/$uniqueName"
        val file = File(rootDir, uniqueName)
        if (!file.exists()) {
            file.createNewFile()
        }
        if (parentDocumentId == AutosaveTreeTestProviderContract.TREE_PREFILLED) {
            file.writeText("existing external bytes")
        }
        records[docId] = DocRecord(
            treeId = parentDocumentId,
            displayName = uniqueName,
            mimeType = mimeType,
            file = file
        )
        return docId
    }

    override fun deleteDocument(documentId: String) {
        val record = records.remove(documentId) ?: return
        record.file.delete()
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        val record = records[documentId] ?: throw FileNotFoundException("unknown doc $documentId")
        if (record.treeId == AutosaveTreeTestProviderContract.TREE_PIPE_ONLY && "w" in mode) {
            val pipe = ParcelFileDescriptor.createPipe()
            pipe[0].close()
            return pipe[1]
        }
        val pfdMode = ParcelFileDescriptor.parseMode(mode)
        return ParcelFileDescriptor.open(record.file, pfdMode)
    }

    private fun deduplicateName(treeId: String, requested: String): String {
        val existingNames = records.values.filter { it.treeId == treeId }.map { it.displayName }.toSet()
        if (requested !in existingNames) return requested
        val dot = requested.lastIndexOf('.')
        val stem = if (dot > 0) requested.substring(0, dot) else requested
        val ext = if (dot > 0) requested.substring(dot) else ""
        var index = 1
        while (true) {
            val candidate = "$stem ($index)$ext"
            if (candidate !in existingNames) return candidate
            index += 1
        }
    }

    private fun folderDisplayName(treeId: String): String = when (treeId) {
        AutosaveTreeTestProviderContract.TREE_NORMAL -> "Autosave Notes"
        AutosaveTreeTestProviderContract.TREE_PIPE_ONLY -> "Pipe Folder"
        AutosaveTreeTestProviderContract.TREE_PREFILLED -> "Prefilled Folder"
        AutosaveTreeTestProviderContract.TREE_READ_ONLY -> "Read-Only Folder"
        else -> treeId
    }

    internal data class DocRecord(
        val treeId: String,
        val displayName: String,
        val mimeType: String,
        val file: File
    )

    companion object {
        internal val records = ConcurrentHashMap<String, DocRecord>()

        internal fun treeDir(context: android.content.Context, treeId: String): File =
            File(File(context.cacheDir, "autosave-trees"), treeId)

        internal fun resetAll(context: android.content.Context) {
            records.clear()
            File(context.cacheDir, "autosave-trees").deleteRecursively()
        }
    }
}

/** Synchronous signature-gated control channel for AutosaveTreeTestProvider. */
class AutosaveTreeControlProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = checkNotNull(context)
        val targetPackage = "dev.soupslurpr.beautyxt.debug"
        val treeId = extras?.getString(AutosaveTreeTestProviderContract.EXTRA_TREE_ID)
            ?: AutosaveTreeTestProviderContract.TREE_NORMAL
        val treeUri = AutosaveTreeTestProviderContract.treeUri(treeId)
        val grantFlags =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        return when (method) {
            AutosaveTreeTestProviderContract.METHOD_RESET -> {
                AutosaveTreeTestProvider.resetAll(ctx)
                Bundle.EMPTY
            }

            AutosaveTreeTestProviderContract.METHOD_GRANT -> {
                ctx.grantUriPermission(targetPackage, treeUri, grantFlags)
                Bundle.EMPTY
            }

            AutosaveTreeTestProviderContract.METHOD_REVOKE -> {
                ctx.revokeUriPermission(
                    targetPackage,
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                Bundle.EMPTY
            }

            AutosaveTreeTestProviderContract.METHOD_READ_FILES -> {
                val filesBundle = Bundle()
                val uris = ArrayList<String>()
                AutosaveTreeTestProvider.records.entries
                    .filter { it.value.treeId == treeId }
                    .sortedBy { it.value.displayName }
                    .forEach { (docId, record) ->
                        filesBundle.putString(record.displayName, record.file.readText())
                        uris.add(DocumentsContract.buildDocumentUriUsingTree(treeUri, docId).toString())
                    }
                Bundle().apply {
                    putBundle(AutosaveTreeTestProviderContract.EXTRA_RESULT_FILES, filesBundle)
                    putStringArrayList(AutosaveTreeTestProviderContract.EXTRA_RESULT_URIS, uris)
                }
            }

            else -> throw IllegalArgumentException("unknown control method: $method")
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}
