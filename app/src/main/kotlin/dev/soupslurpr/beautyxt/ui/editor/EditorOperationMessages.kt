package dev.soupslurpr.beautyxt.ui.editor

import dev.soupslurpr.beautyxt.R
import dev.soupslurpr.beautyxt.document.DocumentRemovalAction
import dev.soupslurpr.beautyxt.markdown.client.MarkdownRenderFailure
import dev.soupslurpr.beautyxt.transfer.client.TransferFailure
import dev.soupslurpr.beautyxt.ui.UiText

internal val CANCELLED_SAVE_MESSAGE =
    UiText.Resource(R.string.operation_cancelled_save)

internal val SOURCE_SAVE_FAILURE_MESSAGE =
    UiText.Resource(R.string.operation_source_save_failure)

internal val SOURCE_SAVE_TOO_LARGE_MESSAGE =
    UiText.Resource(R.string.operation_source_save_too_large)

internal val SOURCE_OVERWRITE_TARGET_TOO_LARGE_MESSAGE =
    UiText.Resource(R.string.operation_source_overwrite_target_too_large)

internal val MARKDOWN_PREVIEW_TOO_LARGE_MESSAGE =
    UiText.Resource(R.string.operation_markdown_preview_too_large)

internal val MARKDOWN_PREVIEW_TOO_COMPLEX_MESSAGE =
    UiText.Resource(R.string.operation_markdown_preview_too_complex)

internal val MARKDOWN_PREVIEW_INVALID_UTF8_MESSAGE =
    UiText.Resource(R.string.operation_markdown_preview_invalid_utf8)

internal val MARKDOWN_PREVIEW_TIMEOUT_MESSAGE =
    UiText.Resource(R.string.operation_markdown_preview_timeout)

internal val MARKDOWN_PREVIEW_SERVICE_MESSAGE =
    UiText.Resource(R.string.operation_markdown_preview_service)

internal val MARKDOWN_PREVIEW_FAILURE_MESSAGE =
    UiText.Resource(R.string.operation_markdown_preview_failure)

internal val SHARE_PREPARATION_FAILURE_MESSAGE =
    UiText.Resource(R.string.operation_share_preparation_failure)

internal val SHARE_SOURCE_FAILURE_MESSAGE =
    UiText.Resource(R.string.operation_share_source_failure)

internal val SHARE_TEXT_TOO_LARGE_MESSAGE =
    UiText.Resource(R.string.operation_share_text_too_large)

internal val PRINT_PREPARATION_FAILURE_MESSAGE =
    UiText.Resource(R.string.operation_print_preparation_failure)

internal val PRINT_LAUNCH_FAILURE_MESSAGE =
    UiText.Resource(R.string.operation_print_launch_failure)

internal val QR_SHARE_TOO_LARGE_MESSAGE =
    UiText.Resource(R.string.operation_qr_share_too_large)

internal val QR_SHARE_TIMEOUT_MESSAGE =
    UiText.Resource(R.string.operation_qr_share_timeout)

internal val QR_SHARE_SERVICE_MESSAGE =
    UiText.Resource(R.string.operation_qr_share_service)

internal val QR_SHARE_FAILURE_MESSAGE =
    UiText.Resource(R.string.operation_qr_share_failure)

internal val NFC_WRITE_TOO_LARGE_MESSAGE =
    UiText.Resource(R.string.operation_nfc_write_too_large)

internal val NFC_WRITE_TIMEOUT_MESSAGE =
    UiText.Resource(R.string.operation_nfc_write_timeout)

internal val NFC_WRITE_SERVICE_MESSAGE =
    UiText.Resource(R.string.operation_nfc_write_service)

internal val NFC_WRITE_FAILURE_MESSAGE =
    UiText.Resource(R.string.operation_nfc_write_failure)

internal val DOCUMENT_REMOVAL_CAPABILITY_FAILURE_MESSAGE =
    UiText.Resource(R.string.operation_document_removal_capability_failure)

internal val DOCUMENT_TRASH_FAILURE_MESSAGE =
    UiText.Resource(R.string.operation_document_trash_failure)

internal val DOCUMENT_DELETE_FAILURE_MESSAGE =
    UiText.Resource(R.string.operation_document_delete_failure)

/** Returns the uncertainty message for one attempted destructive operation. */
internal fun documentRemovalFailureMessage(action: DocumentRemovalAction): UiText = when (action) {
    DocumentRemovalAction.Trash -> DOCUMENT_TRASH_FAILURE_MESSAGE
    DocumentRemovalAction.Delete -> DOCUMENT_DELETE_FAILURE_MESSAGE
}

/** Returns one user-facing message for an isolated QR failure. */
internal fun qrShareFailureMessage(failure: TransferFailure): UiText = when (failure) {
    TransferFailure.TooLarge -> QR_SHARE_TOO_LARGE_MESSAGE

    TransferFailure.TimedOut -> QR_SHARE_TIMEOUT_MESSAGE

    TransferFailure.ServiceUnavailable,
    TransferFailure.ServiceBusy -> QR_SHARE_SERVICE_MESSAGE

    TransferFailure.InvalidUtf8,
    TransferFailure.InvalidNdef,
    TransferFailure.AmbiguousNdef,
    TransferFailure.Unsupported,
    TransferFailure.NoQrCode,
    TransferFailure.AmbiguousQr,
    TransferFailure.SnapshotFailed,
    TransferFailure.InvalidResponse,
    TransferFailure.ProcessingFailed -> QR_SHARE_FAILURE_MESSAGE
}

/** Returns one user-facing message for isolated NFC preparation failure. */
internal fun nfcWriteFailureMessage(failure: TransferFailure): UiText = when (failure) {
    TransferFailure.TooLarge -> NFC_WRITE_TOO_LARGE_MESSAGE

    TransferFailure.TimedOut -> NFC_WRITE_TIMEOUT_MESSAGE

    TransferFailure.ServiceUnavailable,
    TransferFailure.ServiceBusy -> NFC_WRITE_SERVICE_MESSAGE

    TransferFailure.InvalidUtf8,
    TransferFailure.InvalidNdef,
    TransferFailure.AmbiguousNdef,
    TransferFailure.Unsupported,
    TransferFailure.NoQrCode,
    TransferFailure.AmbiguousQr,
    TransferFailure.SnapshotFailed,
    TransferFailure.InvalidResponse,
    TransferFailure.ProcessingFailed -> NFC_WRITE_FAILURE_MESSAGE
}

/** Returns one user-visible message for a sanitized Markdown render failure. */
internal fun markdownPreviewFailureMessage(failure: MarkdownRenderFailure): UiText =
    when (failure) {
        MarkdownRenderFailure.TooLarge -> MARKDOWN_PREVIEW_TOO_LARGE_MESSAGE

        MarkdownRenderFailure.TooComplex -> MARKDOWN_PREVIEW_TOO_COMPLEX_MESSAGE

        MarkdownRenderFailure.InvalidUtf8 -> MARKDOWN_PREVIEW_INVALID_UTF8_MESSAGE

        MarkdownRenderFailure.TimedOut -> MARKDOWN_PREVIEW_TIMEOUT_MESSAGE

        MarkdownRenderFailure.ServiceUnavailable,
        MarkdownRenderFailure.ServiceBusy -> MARKDOWN_PREVIEW_SERVICE_MESSAGE

        MarkdownRenderFailure.SnapshotFailed,
        MarkdownRenderFailure.InvalidResponse,
        MarkdownRenderFailure.RenderFailed -> MARKDOWN_PREVIEW_FAILURE_MESSAGE
    }
