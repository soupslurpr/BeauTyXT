/* Describes active document operations and save outcomes. */
package dev.soupslurpr.beautyxt.ui.editor

import dev.soupslurpr.beautyxt.R
import dev.soupslurpr.beautyxt.ui.UiText

/** Describes lazy file creation for one new document before a source is adopted. */
internal sealed interface NewDocumentAutosaveState {
    /** Automatic new-document creation is off, inapplicable, or already superseded. */
    data object Inactive : NewDocumentAutosaveState

    /** Waiting for the first stable non-empty edit to create a file in the chosen folder. */
    data object Ready : NewDocumentAutosaveState

    /** Creating and verifying an empty file in the chosen folder. */
    data object Creating : NewDocumentAutosaveState

    /** Creation failed and latched until the user explicitly retries or saves manually. */
    data class Failed(val message: UiText, val canRetryCreate: Boolean) : NewDocumentAutosaveState
}

/** Selects subtle visual emphasis for the top-bar save status line. */
internal enum class EditorStatusTone {
    Standard,
    UnsavedAttention,
    Error
}

/** Returns one explanation of how a selected destination affects future saves. */
internal fun saveFormatDescription(
    purpose: SaveDestinationPurpose,
    replacesReadOnlySource: Boolean = false
): UiText = when {
    purpose == SaveDestinationPurpose.Copy ->
        UiText.Resource(R.string.save_format_copy)

    replacesReadOnlySource ->
        UiText.Resource(R.string.save_format_editable)

    else ->
        UiText.Resource(R.string.save_format_new)
}

/** Returns one quiet description of the current save state. */
internal fun saveStateDescription(
    sourceSaveStatus: SourceSaveStatus,
    saveStatus: SaveStatus,
    hasUnsavedChanges: Boolean,
    saveUnavailableReason: UiText?,
    isViewOnly: Boolean = false,
    shareStatus: ShareStatus = ShareStatus.Idle,
    printStatus: PrintStatus = PrintStatus.Idle,
    qrShareStatus: QrShareStatus = QrShareStatus.Idle,
    nfcWriteStatus: NfcWriteStatus = NfcWriteStatus.Idle,
    newDocumentAutosaveState: NewDocumentAutosaveState = NewDocumentAutosaveState.Inactive
): UiText = when {
    nfcWriteStatus is NfcWriteStatus.Configuring -> UiText.Resource(R.string.status_nfc_configuring)

    nfcWriteStatus is NfcWriteStatus.Queued && sourceSaveStatus.isActive() ->
        UiText.Resource(R.string.status_nfc_saving)

    nfcWriteStatus is NfcWriteStatus.Queued -> UiText.Resource(R.string.status_nfc_queued)

    nfcWriteStatus is NfcWriteStatus.Preparing -> UiText.Resource(R.string.status_nfc_preparing)

    nfcWriteStatus is NfcWriteStatus.Cancelling -> UiText.Resource(R.string.status_nfc_cancelling)

    nfcWriteStatus is NfcWriteStatus.Ready -> UiText.Resource(R.string.status_nfc_ready)

    nfcWriteStatus is NfcWriteStatus.Failed -> UiText.Resource(R.string.status_nfc_failed)

    nfcWriteStatus is NfcWriteStatus.Succeeded -> UiText.Resource(R.string.status_nfc_written)

    qrShareStatus is QrShareStatus.Queued && sourceSaveStatus.isActive() ->
        UiText.Resource(R.string.status_qr_saving)

    qrShareStatus is QrShareStatus.Queued -> UiText.Resource(R.string.status_qr_queued)

    qrShareStatus is QrShareStatus.Preparing -> UiText.Resource(R.string.status_qr_preparing)

    qrShareStatus is QrShareStatus.Cancelling -> UiText.Resource(R.string.status_qr_cancelling)

    qrShareStatus is QrShareStatus.Ready -> UiText.Resource(R.string.status_qr_ready)

    qrShareStatus is QrShareStatus.Failed -> UiText.Resource(R.string.status_qr_failed)

    shareStatus is ShareStatus.Queued && sourceSaveStatus.isActive() ->
        UiText.Resource(R.string.status_share_saving)

    shareStatus is ShareStatus.Queued -> UiText.Resource(R.string.status_share_queued)

    shareStatus is ShareStatus.Preparing -> UiText.Resource(R.string.status_share_preparing)

    shareStatus is ShareStatus.Cancelling -> UiText.Resource(R.string.status_share_cancelling)

    shareStatus is ShareStatus.Ready -> UiText.Resource(R.string.status_share_ready)

    shareStatus is ShareStatus.Failed -> UiText.Resource(R.string.status_share_failed)

    printStatus is PrintStatus.Queued -> UiText.Resource(R.string.status_print_queued)

    printStatus is PrintStatus.Preparing -> UiText.Resource(R.string.status_print_preparing)

    printStatus is PrintStatus.Cancelling -> UiText.Resource(R.string.status_print_cancelling)

    printStatus is PrintStatus.Ready -> UiText.Resource(R.string.status_print_ready)

    printStatus is PrintStatus.Failed -> UiText.Resource(R.string.status_print_failed)

    saveStatus is SaveStatus.Queued -> UiText.Resource(R.string.status_save_queued)

    saveStatus is SaveStatus.PreparingDestination -> UiText.Resource(R.string.status_save_preparing)

    saveStatus is SaveStatus.CancellingDestinationPreparation -> UiText.Resource(
        R.string.status_save_cancelling
    )

    saveStatus is SaveStatus.Exporting -> UiText.Resource(R.string.status_save_copy)

    saveStatus is SaveStatus.CancellingExport -> UiText.Resource(R.string.status_save_cancelling)

    saveStatus is SaveStatus.Failed -> failedSaveStateDescription(saveStatus.request.purpose)

    saveStatus is SaveStatus.Cancelled -> cancelledSaveTitle(saveStatus.request.purpose)

    saveStatus is SaveStatus.Succeeded -> UiText.Resource(R.string.status_copy_saved)

    isViewOnly && sourceSaveStatus is SourceSaveStatus.Failed ->
        UiText.Resource(R.string.status_editable_failed)

    sourceSaveStatus is SourceSaveStatus.Failed -> UiText.Resource(R.string.status_autosave_failed)

    sourceSaveStatus is SourceSaveStatus.Conflict -> UiText.Resource(
        R.string.status_autosave_conflict
    )

    sourceSaveStatus is SourceSaveStatus.Uncertain -> UiText.Resource(
        R.string.status_autosave_uncertain
    )

    sourceSaveStatus == SourceSaveStatus.Reloading -> UiText.Resource(
        R.string.status_source_reloading
    )

    isViewOnly && sourceSaveStatus == SourceSaveStatus.Pending -> UiText.Resource(
        R.string.status_editable_pending
    )

    isViewOnly && sourceSaveStatus == SourceSaveStatus.Saving -> UiText.Resource(
        R.string.status_editable_saving
    )

    sourceSaveStatus == SourceSaveStatus.Pending -> UiText.Resource(
        R.string.status_autosave_pending
    )

    sourceSaveStatus == SourceSaveStatus.Saving -> UiText.Resource(R.string.status_autosave_saving)

    saveUnavailableReason != null -> saveUnavailableReason

    isViewOnly -> UiText.Resource(R.string.status_view_only)

    newDocumentAutosaveState == NewDocumentAutosaveState.Creating ->
        UiText.Resource(R.string.status_new_autosave_creating)

    newDocumentAutosaveState is NewDocumentAutosaveState.Failed ->
        UiText.Resource(R.string.status_autosave_failed)

    sourceSaveStatus == SourceSaveStatus.NoSource &&
        newDocumentAutosaveState == NewDocumentAutosaveState.Ready &&
        !hasUnsavedChanges ->
        UiText.Resource(R.string.status_new_autosave_ready)

    sourceSaveStatus == SourceSaveStatus.NoSource &&
        newDocumentAutosaveState == NewDocumentAutosaveState.Ready &&
        hasUnsavedChanges ->
        UiText.Resource(R.string.status_autosave_soon)

    sourceSaveStatus == SourceSaveStatus.NoSource -> UiText.Resource(R.string.status_not_saved)

    hasUnsavedChanges -> UiText.Resource(R.string.status_autosave_soon)

    else -> UiText.Resource(R.string.status_autosave_saved)
}

/** Returns the top-bar status tone so unsaved memory-only documents and failures stand out. */
internal fun saveStateTone(
    sourceSaveStatus: SourceSaveStatus,
    saveStatus: SaveStatus,
    hasUnsavedChanges: Boolean,
    newDocumentAutosaveState: NewDocumentAutosaveState = NewDocumentAutosaveState.Inactive
): EditorStatusTone = when {
    saveStatus is SaveStatus.Failed ||
        sourceSaveStatus is SourceSaveStatus.Failed ||
        sourceSaveStatus is SourceSaveStatus.Conflict ||
        sourceSaveStatus is SourceSaveStatus.Uncertain ||
        newDocumentAutosaveState is NewDocumentAutosaveState.Failed ->
        EditorStatusTone.Error

    sourceSaveStatus == SourceSaveStatus.NoSource &&
        hasUnsavedChanges &&
        newDocumentAutosaveState == NewDocumentAutosaveState.Inactive ->
        EditorStatusTone.UnsavedAttention

    else -> EditorStatusTone.Standard
}

/** Returns one purpose-specific title for a failed explicit save. */
internal fun failedSaveTitle(purpose: SaveDestinationPurpose): UiText = when (purpose) {
    SaveDestinationPurpose.Copy -> UiText.Resource(R.string.save_copy_failed)
    SaveDestinationPurpose.SourceReplacement -> UiText.Resource(R.string.save_new_failed)
}

/** Returns one purpose-specific title for a cancelled explicit save. */
internal fun cancelledSaveTitle(purpose: SaveDestinationPurpose): UiText = when (purpose) {
    SaveDestinationPurpose.Copy -> UiText.Resource(R.string.save_copy_cancelled)
    SaveDestinationPurpose.SourceReplacement -> UiText.Resource(R.string.save_new_cancelled)
}

/** Returns one purpose-specific top-bar description for a failed explicit save. */
private fun failedSaveStateDescription(purpose: SaveDestinationPurpose): UiText = when (purpose) {
    SaveDestinationPurpose.Copy -> UiText.Resource(R.string.status_copy_failed)
    SaveDestinationPurpose.SourceReplacement -> UiText.Resource(R.string.status_save_failed)
}

/** Returns whether one source save is actively progressing. */
internal fun SourceSaveStatus.isActive(): Boolean = this == SourceSaveStatus.Pending ||
    this == SourceSaveStatus.Saving ||
    this == SourceSaveStatus.Reloading

/** Returns whether one explicit save is actively progressing. */
internal fun SaveStatus.isActive(): Boolean = this is SaveStatus.Queued ||
    this is SaveStatus.PreparingDestination ||
    this is SaveStatus.CancellingDestinationPreparation ||
    this is SaveStatus.Exporting ||
    this is SaveStatus.CancellingExport

/** Returns whether one document share is actively progressing. */
internal fun ShareStatus.isActive(): Boolean = this is ShareStatus.Queued ||
    this is ShareStatus.Preparing ||
    this is ShareStatus.Cancelling ||
    this is ShareStatus.Ready

/** Returns whether one native print request is actively progressing. */
internal fun PrintStatus.isActive(): Boolean = this is PrintStatus.Queued ||
    this is PrintStatus.Preparing ||
    this is PrintStatus.Cancelling ||
    this is PrintStatus.Ready

/** Returns whether one QR share is actively progressing. */
internal fun QrShareStatus.isActive(): Boolean = this is QrShareStatus.Queued ||
    this is QrShareStatus.Preparing ||
    this is QrShareStatus.Cancelling

/** Returns whether one NFC write is actively preparing. */
internal fun NfcWriteStatus.isActive(): Boolean = this is NfcWriteStatus.Queued ||
    this is NfcWriteStatus.Configuring ||
    this is NfcWriteStatus.Preparing ||
    this is NfcWriteStatus.Cancelling
