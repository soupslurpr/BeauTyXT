package dev.soupslurpr.beautyxt.autosave

import android.net.Uri
import android.provider.DocumentsContract

/** Defines the synthetic SAF tree provider used to verify opt-in new-document autosave. */
object AutosaveTreeTestProviderContract {
    const val PACKAGE = "dev.soupslurpr.beautyxt.debug.test.providers"
    const val AUTHORITY = "$PACKAGE.autosave-tree"
    const val CONTROL_AUTHORITY = "$PACKAGE.autosave-tree-control"

    const val TREE_NORMAL = "normal"
    const val TREE_PIPE_ONLY = "pipe-only"
    const val TREE_PREFILLED = "prefilled"
    const val TREE_READ_ONLY = "read-only"

    const val METHOD_RESET = "reset"
    const val METHOD_GRANT = "grant"
    const val METHOD_REVOKE = "revoke"
    const val METHOD_READ_FILES = "read_files"
    const val EXTRA_TREE_ID = "tree_id"
    const val EXTRA_RESULT_FILES = "result_files"
    const val EXTRA_RESULT_URIS = "result_uris"

    fun treeUri(treeId: String): Uri = DocumentsContract.buildTreeDocumentUri(AUTHORITY, treeId)
    fun controlUri(): Uri = Uri.parse("content://$CONTROL_AUTHORITY")
}
