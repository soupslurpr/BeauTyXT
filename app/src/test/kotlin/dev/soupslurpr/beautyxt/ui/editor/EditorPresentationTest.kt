package dev.soupslurpr.beautyxt.ui.editor

import dev.soupslurpr.beautyxt.R
import dev.soupslurpr.beautyxt.document.DocumentFormat
import dev.soupslurpr.beautyxt.printing.defaultPrintSettings
import dev.soupslurpr.beautyxt.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Test

private const val TEST_SAVE_GENERATION = 1L

/** Verifies user-visible editor state descriptions. */
class EditorPresentationTest {
    /** Verifies a newly created document clearly reports its unsaved state. */
    @Test
    fun describesNewDocumentsAsNotSaved() {
        assertEquals(
            UiText.Resource(R.string.status_not_saved),
            saveStateDescription(
                sourceSaveStatus = SourceSaveStatus.NoSource,
                saveStatus = SaveStatus.Idle,
                hasUnsavedChanges = true,
                saveUnavailableReason = null
            )
        )
    }

    /** Verifies every routine autosave phase has distinct feedback. */
    @Test
    fun describesRoutineAutosavePhases() {
        assertEquals(
            UiText.Resource(R.string.status_autosave_pending),
            saveStateDescription(
                sourceSaveStatus = SourceSaveStatus.Pending,
                saveStatus = SaveStatus.Idle,
                hasUnsavedChanges = true,
                saveUnavailableReason = null
            )
        )
        assertEquals(
            UiText.Resource(R.string.status_autosave_saving),
            saveStateDescription(
                sourceSaveStatus = SourceSaveStatus.Saving,
                saveStatus = SaveStatus.Idle,
                hasUnsavedChanges = true,
                saveUnavailableReason = null
            )
        )
        assertEquals(
            UiText.Resource(R.string.status_autosave_soon),
            saveStateDescription(
                sourceSaveStatus = SourceSaveStatus.Saved,
                saveStatus = SaveStatus.Idle,
                hasUnsavedChanges = true,
                saveUnavailableReason = null
            )
        )
        assertEquals(
            UiText.Resource(R.string.status_autosave_saved),
            saveStateDescription(
                sourceSaveStatus = SourceSaveStatus.Saved,
                saveStatus = SaveStatus.Idle,
                hasUnsavedChanges = false,
                saveUnavailableReason = null
            )
        )
    }

    /** Verifies save-format copy and source behavior is disclosed before selection. */
    @Test
    fun explainsDestinationOwnershipBeforeSaving() {
        assertEquals(
            UiText.Resource(R.string.save_format_copy),
            saveFormatDescription(SaveDestinationPurpose.Copy)
        )
        assertEquals(
            UiText.Resource(R.string.save_format_new),
            saveFormatDescription(SaveDestinationPurpose.SourceReplacement)
        )
        assertEquals(
            UiText.Resource(R.string.save_format_editable),
            saveFormatDescription(
                purpose = SaveDestinationPurpose.SourceReplacement,
                replacesReadOnlySource = true
            )
        )
    }

    /** Verifies picker-result preparation has explicit progress wording. */
    @Test
    fun describesDestinationPreparation() {
        val request =
            SaveDestinationRequest(
                generation = TEST_SAVE_GENERATION,
                format = DocumentFormat.PlainText,
                suggestedName = "document.txt",
                purpose = SaveDestinationPurpose.Copy
            )

        assertEquals(
            UiText.Resource(R.string.status_save_preparing),
            saveStateDescription(
                sourceSaveStatus = SourceSaveStatus.Saved,
                saveStatus = SaveStatus.PreparingDestination(request),
                hasUnsavedChanges = false,
                saveUnavailableReason = null
            )
        )
        assertEquals(
            UiText.Resource(R.string.status_save_cancelling),
            saveStateDescription(
                sourceSaveStatus = SourceSaveStatus.Saved,
                saveStatus = SaveStatus.CancellingDestinationPreparation(request),
                hasUnsavedChanges = false,
                saveUnavailableReason = null
            )
        )
    }

    /** Verifies the first accepted Save tap reports retained progress. */
    @Test
    fun describesQueuedSavePreparation() {
        assertEquals(
            UiText.Resource(R.string.status_save_queued),
            saveStateDescription(
                sourceSaveStatus = SourceSaveStatus.Saved,
                saveStatus =
                    SaveStatus.Queued(
                        purpose = SaveDestinationPurpose.Copy,
                        draftGeneration = TEST_SAVE_GENERATION
                    ),
                hasUnsavedChanges = true,
                saveUnavailableReason = null
            )
        )
    }

    /** Verifies native print preparation has concise progress and recovery wording. */
    @Test
    fun describesNativePrintPreparation() {
        assertEquals(
            UiText.Resource(R.string.status_print_queued),
            saveStateDescription(
                sourceSaveStatus = SourceSaveStatus.Saved,
                saveStatus = SaveStatus.Idle,
                hasUnsavedChanges = true,
                saveUnavailableReason = null,
                printStatus =
                    PrintStatus.Queued(
                        generation = TEST_SAVE_GENERATION,
                        draftGeneration = TEST_SAVE_GENERATION,
                        settings = defaultPrintSettings(formattedMarkdown = false)
                    )
            )
        )
        assertEquals(
            UiText.Resource(R.string.status_print_preparing),
            saveStateDescription(
                sourceSaveStatus = SourceSaveStatus.Saved,
                saveStatus = SaveStatus.Idle,
                hasUnsavedChanges = false,
                saveUnavailableReason = null,
                printStatus =
                    PrintStatus.Preparing(
                        TEST_SAVE_GENERATION,
                        defaultPrintSettings(formattedMarkdown = false)
                    )
            )
        )
        assertEquals(
            UiText.Resource(R.string.status_print_failed),
            saveStateDescription(
                sourceSaveStatus = SourceSaveStatus.Saved,
                saveStatus = SaveStatus.Idle,
                hasUnsavedChanges = false,
                saveUnavailableReason = null,
                printStatus =
                    PrintStatus.Failed(
                        generation = TEST_SAVE_GENERATION,
                        message = UiText.Resource(R.string.operation_print_launch_failure),
                        settings = defaultPrintSettings(formattedMarkdown = false)
                    )
            )
        )
    }

    /** Verifies a readable source never appears to be an unsaved editable document. */
    @Test
    fun describesViewOnlySource() {
        assertEquals(
            UiText.Resource(R.string.status_view_only),
            saveStateDescription(
                sourceSaveStatus = SourceSaveStatus.NoSource,
                saveStatus = SaveStatus.Idle,
                hasUnsavedChanges = false,
                saveUnavailableReason = null,
                isViewOnly = true
            )
        )
        assertEquals(
            UiText.Resource(R.string.status_editable_saving),
            saveStateDescription(
                sourceSaveStatus = SourceSaveStatus.Saving,
                saveStatus = SaveStatus.Idle,
                hasUnsavedChanges = false,
                saveUnavailableReason = null,
                isViewOnly = true
            )
        )
    }

    /** Verifies explicit-save terminal results remain distinct from autosave. */
    @Test
    fun describesExplicitSaveResults() {
        val request =
            SaveDestinationRequest(
                generation = TEST_SAVE_GENERATION,
                format = DocumentFormat.PlainText,
                suggestedName = "document.txt",
                purpose = SaveDestinationPurpose.Copy
            )

        assertEquals(
            UiText.Resource(R.string.save_copy_cancelled),
            saveStateDescription(
                sourceSaveStatus = SourceSaveStatus.Saved,
                saveStatus = SaveStatus.Cancelled(request),
                hasUnsavedChanges = false,
                saveUnavailableReason = null
            )
        )
        assertEquals(
            UiText.Resource(R.string.status_copy_saved),
            saveStateDescription(
                sourceSaveStatus = SourceSaveStatus.Saved,
                saveStatus = SaveStatus.Succeeded(request, hasNewerChanges = false),
                hasUnsavedChanges = false,
                saveUnavailableReason = null
            )
        )
    }
}
