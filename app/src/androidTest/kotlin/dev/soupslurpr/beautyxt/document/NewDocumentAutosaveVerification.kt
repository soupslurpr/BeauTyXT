/* End-to-end verification of opt-in new-document autosave, provider safety, and storage audits. */
package dev.soupslurpr.beautyxt.document

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import dev.soupslurpr.beautyxt.BeauTyXTApplication
import dev.soupslurpr.beautyxt.HomeActivity
import dev.soupslurpr.beautyxt.autosave.AutosaveTreeTestProviderContract
import dev.soupslurpr.beautyxt.exporting.client.IsolatedDocumentExporter
import dev.soupslurpr.beautyxt.importing.client.IsolatedDocumentImporter
import dev.soupslurpr.beautyxt.ui.autosave.NEW_DOCUMENT_AUTOSAVE_FILE_NAME
import dev.soupslurpr.beautyxt.ui.autosave.NewDocumentAutosaveStatus
import dev.soupslurpr.beautyxt.ui.autosave.NewDocumentAutosaveStoreState
import dev.soupslurpr.beautyxt.ui.autosave.NewDocumentCreationResult
import dev.soupslurpr.beautyxt.ui.autosave.NewDocumentFolderClient
import java.io.File
import kotlinx.coroutines.runBlocking

private const val INTERVIEW_NOTES_TOKEN = "Interview notes: BeauTyXT autosave verification token"

/** Verifies real SAF tree grants, lazy verified autosave, provider rejections, and zero-leak audits. */
internal fun Instrumentation.verifyNewDocumentAutosave() {
    val app = targetContext.applicationContext as BeauTyXTApplication
    val store = app.newDocumentAutosave
    val markerFile = File(targetContext.noBackupFilesDir, NEW_DOCUMENT_AUTOSAVE_FILE_NAME)

    fun controlCall(method: String, treeId: String = AutosaveTreeTestProviderContract.TREE_NORMAL): Bundle =
        checkNotNull(
            targetContext.contentResolver.call(
                AutosaveTreeTestProviderContract.controlUri(),
                method,
                null,
                Bundle().apply { putString(AutosaveTreeTestProviderContract.EXTRA_TREE_ID, treeId) }
            )
        ) { "autosave tree control call returned null for $method" }

    fun readTreeFiles(treeId: String = AutosaveTreeTestProviderContract.TREE_NORMAL): Map<String, String> {
        val result = controlCall(AutosaveTreeTestProviderContract.METHOD_READ_FILES, treeId)
        val bundle = checkNotNull(
            result.getBundle(AutosaveTreeTestProviderContract.EXTRA_RESULT_FILES)
        )
        return bundle.keySet().sorted().associateWith { key -> bundle.getString(key).orEmpty() }
    }

    awaitReadingCondition("new-document autosave store did not load") {
        store.state.value is NewDocumentAutosaveStoreState.Ready
    }
    try {
        // Start clean: disabled, zero persisted grants, no marker file.
        runOnMainSync { store.disable() }
        awaitReadingCondition("autosave store did not settle to Disabled") {
            val ready = store.state.value as? NewDocumentAutosaveStoreState.Ready
            ready?.status == NewDocumentAutosaveStatus.Disabled &&
                !ready.busy &&
                !markerFile.exists() &&
                targetContext.contentResolver.persistedUriPermissions.isEmpty()
        }
        controlCall(AutosaveTreeTestProviderContract.METHOD_RESET)

        // 1. Default off experience: Home -> Autosave dialog shows Off, New document shows "Not saved".
        startActivitySync(
            Intent(targetContext, HomeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        requireActionableText("Autosave").performRequiredClick()
        waitForAccessibilityNode("Autosave dialog title") {
            it.text?.toString() == "New document autosave"
        }
        waitForAccessibilityNode("Autosave off status") {
            it.text?.toString() == "Off"
        }
        requireActionableText("Close").performRequiredClick()
        awaitReadingCondition("Autosave dialog did not close") {
            uiAutomation.rootInActiveWindow?.findNode { it.text?.toString() == "New document autosave" } == null
        }

        // 2. Grant read-only tree -> must be rejected cleanly without leaving a persisted grant.
        val readOnlyUri = AutosaveTreeTestProviderContract.treeUri(AutosaveTreeTestProviderContract.TREE_READ_ONLY).toString()
        controlCall(AutosaveTreeTestProviderContract.METHOD_GRANT, AutosaveTreeTestProviderContract.TREE_READ_ONLY)
        store.selectFolder(readOnlyUri, offeredRead = true, offeredWrite = true, offeredPersistable = true)
        awaitReadingCondition("read-only folder was not rejected cleanly") {
            val ready = store.state.value as? NewDocumentAutosaveStoreState.Ready
            ready != null &&
                !ready.busy &&
                ready.status == NewDocumentAutosaveStatus.Disabled &&
                ready.errorMessage != null &&
                !markerFile.exists() &&
                targetContext.contentResolver.persistedUriPermissions.isEmpty()
        }

        // 3. Grant normal seekable tree -> enables autosave, writes content-free marker only.
        val normalUri = AutosaveTreeTestProviderContract.treeUri(AutosaveTreeTestProviderContract.TREE_NORMAL).toString()
        controlCall(AutosaveTreeTestProviderContract.METHOD_GRANT, AutosaveTreeTestProviderContract.TREE_NORMAL)
        store.selectFolder(normalUri, offeredRead = true, offeredWrite = true, offeredPersistable = true)
        awaitReadingCondition("normal folder did not enable autosave") {
            val ready = store.state.value as? NewDocumentAutosaveStoreState.Ready
            ready != null &&
                !ready.busy &&
                ready.status == NewDocumentAutosaveStatus.Enabled(normalUri, "Autosave Notes") &&
                markerFile.isFile &&
                markerFile.readText().matches(Regex("""state=enabled\nformat=txt\ngrant_time=\d+\n"""))
        }

        // Switch default format to Markdown from the real Home dialog.
        requireActionableText("Autosave").performRequiredClick()
        waitForAccessibilityNode("live folder name in dialog") {
            it.text?.toString() == "Autosave Notes"
        }
        requireActionableText("Markdown (.md)").performRequiredClick()
        awaitReadingCondition("Markdown default format did not persist") {
            markerFile.isFile &&
                markerFile.readText().matches(Regex("""state=enabled\nformat=md\ngrant_time=\d+\n"""))
        }
        requireActionableText("Close").performRequiredClick()

        // 4. Open New document -> empty doc creates ZERO files, shows "Autosaves when you type".
        requireActionableText("New document").performRequiredClick()
        waitForAccessibilityNode("autosave ready status") {
            it.text?.toString() == "Autosaves when you type"
        }
        check(readTreeFiles().isEmpty()) { "empty new document unexpectedly created a file" }

        // Back out while empty -> still zero files created.
        requireActionableContentDescription("Back").performRequiredClick()
        waitForAccessibilityNode("Home after empty back") {
            it.text?.toString() == "New document"
        }
        check(readTreeFiles().isEmpty()) { "backing out of empty new document created a file" }

        // 5. Open New document, type interview notes -> lazily creates one timestamped .md file and verifies autosave.
        requireActionableText("New document").performRequiredClick()
        val editableField = waitForAccessibilityNode("new document editable field") { node ->
            node.isEditable
        }
        val setTextArgs = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                INTERVIEW_NOTES_TOKEN
            )
        }
        check(editableField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setTextArgs)) {
            "could not enter text into new document field"
        }
        waitForAccessibilityNode("verified autosave completed for new document") {
            it.text?.toString() == "Saved automatically"
        }
        val createdFiles = readTreeFiles()
        check(createdFiles.size == 1) {
            "expected exactly 1 auto-created file, got ${createdFiles.keys}"
        }
        val (createdName, createdContent) = createdFiles.entries.single()
        check(createdName.endsWith(".md") && createdName.matches(Regex("""\d{4}-\d{2}-\d{2} \d{2}\.\d{2}\.\d{2}\.md"""))) {
            "unexpected timestamped filename: $createdName"
        }
        check(createdContent == INTERVIEW_NOTES_TOKEN) {
            "saved provider content mismatch: $createdContent"
        }
        waitForAccessibilityNode("top bar updated to created filename") {
            it.text?.toString() == createdName
        }

        // Audit app storage while enabled: zero document bytes/names/folder URIs in app storage, exact 1 tree grant.
        StatelessSessionState.capture(
            context = targetContext,
            additionalForbiddenPatterns = listOf(
                INTERVIEW_NOTES_TOKEN.toByteArray(Charsets.UTF_8),
                createdName.toByteArray(Charsets.UTF_8),
                "Autosave Notes".toByteArray(Charsets.UTF_8),
                AutosaveTreeTestProviderContract.AUTHORITY.toByteArray(Charsets.UTF_8)
            ),
            expectedPersistedTreeUris = setOf(normalUri)
        )

        // Back closes cleanly with no discard confirmation since it's verified saved.
        sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        // First back may hide IME if shown; click Back icon if still in editor.
        if (uiAutomation.rootInActiveWindow?.findNode { it.text?.toString() == "Open file" } == null) {
            requireActionableContentDescription("Back").performRequiredClick()
        }
        waitForAccessibilityNode("returned to Home cleanly") {
            it.text?.toString() == "Open file"
        }
        awaitReadingCondition("exiting auto-saved editor did not release folder lease") {
            val ready = store.state.value as? NewDocumentAutosaveStoreState.Ready
            ready != null && !ready.hasActiveLeases
        }

        // 6. Verify pipe-only and prefilled providers fail closed without attaching or deleting foreign files.
        val exporter = IsolatedDocumentExporter(targetContext)
        val importer = IsolatedDocumentImporter(targetContext)
        val pipeUri = AutosaveTreeTestProviderContract.treeUri(AutosaveTreeTestProviderContract.TREE_PIPE_ONLY).toString()
        controlCall(AutosaveTreeTestProviderContract.METHOD_GRANT, AutosaveTreeTestProviderContract.TREE_PIPE_ONLY)
        store.selectFolder(pipeUri, offeredRead = true, offeredWrite = true, offeredPersistable = true)
        awaitReadingCondition("pipe tree did not select") {
            val ready = store.state.value as? NewDocumentAutosaveStoreState.Ready
            ready?.status == NewDocumentAutosaveStatus.Enabled(pipeUri, "Pipe Folder")
        }
        val pipeResult = runBlocking {
            NewDocumentFolderClient(targetContext, store, exporter) { uri ->
                importer.open(uri, allowSourceWriteAccess = false)
            }.createNewDocument { format -> "2026-11-23 15.07.03${format.filenameExtension}" }
        }
        check(pipeResult is NewDocumentCreationResult.Failed && !pipeResult.canRetryCreate) {
            "pipe-only provider should fail closed, got $pipeResult"
        }

        val prefilledUri = AutosaveTreeTestProviderContract.treeUri(AutosaveTreeTestProviderContract.TREE_PREFILLED).toString()
        controlCall(AutosaveTreeTestProviderContract.METHOD_GRANT, AutosaveTreeTestProviderContract.TREE_PREFILLED)
        store.selectFolder(prefilledUri, offeredRead = true, offeredWrite = true, offeredPersistable = true)
        awaitReadingCondition("prefilled tree did not select") {
            val ready = store.state.value as? NewDocumentAutosaveStoreState.Ready
            ready?.status == NewDocumentAutosaveStatus.Enabled(prefilledUri, "Prefilled Folder")
        }
        val prefilledResult = runBlocking {
            NewDocumentFolderClient(targetContext, store, exporter) { uri ->
                importer.open(uri, allowSourceWriteAccess = false)
            }.createNewDocument { format -> "2026-11-23 15.07.03${format.filenameExtension}" }
        }
        check(prefilledResult is NewDocumentCreationResult.Failed && !prefilledResult.canRetryCreate) {
            "prefilled provider should fail closed, got $prefilledResult"
        }
        check(readTreeFiles(AutosaveTreeTestProviderContract.TREE_PREFILLED).values.single() == "existing external bytes") {
            "prefilled foreign file must not be deleted or overwritten"
        }

        // 7. Verify picker authorization: a bare picker result without FLAG_GRANT_READ_URI_PERMISSION
        // for a URI under the active tree grant must be rejected without opening the file.
        val ungrantedChildUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(
            AutosaveTreeTestProviderContract.treeUri(AutosaveTreeTestProviderContract.TREE_PREFILLED),
            "existing-child"
        )
        returnHomePickerResult(
            resultCode = android.app.Activity.RESULT_OK,
            result = Intent().setDataAndType(ungrantedChildUri, "text/plain")
        )
        waitForAccessibilityNode("picker rejected ungranted tree child") {
            it.text?.toString() == "This content is unavailable or is not a supported text file."
        }
        requireActionableText("Dismiss").performRequiredClick()
        waitForAccessibilityNode("returned to Home after rejected picker result") {
            it.text?.toString() == "Open file"
        }

        // 8. Turn off from Home dialog -> marker deleted, zero persisted grants, default audit passes.
        requireActionableText("Autosave").performRequiredClick()
        requireActionableText("Turn off").performRequiredClick()
        awaitReadingCondition("turning off autosave did not clear marker and grants") {
            val ready = store.state.value as? NewDocumentAutosaveStoreState.Ready
            ready?.status == NewDocumentAutosaveStatus.Disabled &&
                !ready.busy &&
                !markerFile.exists() &&
                targetContext.contentResolver.persistedUriPermissions.isEmpty()
        }
        requireActionableText("Close").performRequiredClick()
        StatelessSessionState.capture(context = targetContext)
    } finally {
        runCatching {
            awaitReadingCondition("active folder leases did not clear before cleanup") {
                val ready = store.state.value as? NewDocumentAutosaveStoreState.Ready
                ready != null && !ready.hasActiveLeases
            }
        }
        store.disable()
        awaitReadingCondition("cleanup disable did not finish") {
            val ready = store.state.value as? NewDocumentAutosaveStoreState.Ready
            ready?.status == NewDocumentAutosaveStatus.Disabled &&
                !ready.busy &&
                !markerFile.exists() &&
                targetContext.contentResolver.persistedUriPermissions.isEmpty()
        }
        runCatching { controlCall(AutosaveTreeTestProviderContract.METHOD_RESET) }
    }
}
