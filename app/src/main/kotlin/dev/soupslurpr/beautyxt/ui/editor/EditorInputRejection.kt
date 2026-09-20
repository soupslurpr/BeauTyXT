package dev.soupslurpr.beautyxt.ui.editor

import androidx.annotation.StringRes
import dev.soupslurpr.beautyxt.R

/** Keeps input rejection meaning independent of the interface language. */
internal enum class EditorInputRejection(@param:StringRes val messageResource: Int) {
    DocumentSize(R.string.editor_input_document_size),
    BulkSize(R.string.editor_input_bulk_size),
    BulkUnavailable(R.string.editor_input_bulk_unavailable)
}
