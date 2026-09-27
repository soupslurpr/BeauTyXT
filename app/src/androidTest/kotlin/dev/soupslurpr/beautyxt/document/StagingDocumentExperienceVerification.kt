package dev.soupslurpr.beautyxt.document

import android.app.Activity
import android.app.ActivityManager
import android.app.Instrumentation
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import dev.soupslurpr.beautyxt.HomeActivity
import java.io.File
import java.security.MessageDigest

private const val EXPERIENCE_STAGING_PACKAGE = "dev.soupslurpr.beautyxt.staging"

/** Drives a packaged app without accessing its session state or native handles. */
internal fun Instrumentation.verifyPackagedDocumentExperience(
    app: String = EXPERIENCE_STAGING_PACKAGE,
    fromHome: Boolean = false
) {
    require(!fromHome || app == targetContext.packageName)
    val resolver = targetContext.contentResolver
    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    val paragraph = "A silver cat meets silver concatenate."
    val original = "# Staging review\n\n$paragraph\n\nSecond silver cat.\n\nPRIVATE TAIL\n"
    val name = "beautyxt-staging-experience-${SystemClock.uptimeMillis()}.md"
    var localActivity: Activity? = null

    fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(command))
        .bufferedReader().use { it.readText() }
    fun capture(name: String) {
        localActivity?.let { activity ->
            runOnMainSync { activity.window.insetsController?.hide(WindowInsets.Type.ime()) }
            waitForImeVisibility(activity, visible = false)
        }
        waitForAccessibilityIdle()
        SystemClock.sleep(300) // Let field labels and keyboard placement finish animating.
        uiAutomation.takeScreenshot()?.let { bitmap ->
            try {
                File(targetContext.cacheDir, "$name.png").outputStream().use {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally {
                bitmap.recycle()
            }
        }
    }
    fun key(code: Int, modifiers: Int = 0) {
        val down = SystemClock.uptimeMillis()
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            check(uiAutomation.injectInputEvent(KeyEvent(down, SystemClock.uptimeMillis(), action, code, 0, modifiers), true))
        }
        waitForAccessibilityIdle()
        uiAutomation.clearCache()
    }
    fun setText(node: AccessibilityNodeInfo, text: String) {
        check(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }))
        waitForAccessibilityIdle()
        uiAutomation.clearCache()
    }
    fun editableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var field: AccessibilityNodeInfo? = node
        while (field != null && !field.isEditable) field = field.parent
        return checkNotNull(field) { "The field label has no editable ancestor" }
    }
    fun query(text: String) {
        val labeled = waitForAccessibilityNode("staging Find input") {
            it.contentDescription?.toString() == "Find in document"
        }
        setText(editableAncestor(labeled), text)
    }
    fun count(expected: Int) {
        waitForAccessibilityNode("$expected staging matches") {
            it.text?.toString()?.lineSequence()?.any { line -> line == "$expected matches" } == true
        }
    }
    fun scrollTo(description: String, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            waitForAccessibilityIdle()
            uiAutomation.clearCache()
            val root = checkNotNull(uiAutomation.rootInActiveWindow)
            root.findNode { it.isVisibleToUser && predicate(it) }?.let { return it }
            val scroll = root.findNode { it.isVisibleToUser && it.isScrollable && it.actionList.any { action ->
                action.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            } }
            scroll?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            // The page can still be rendering, before the preview makes the list scrollable.
            SystemClock.sleep(50)
        }
        error("Did not reveal $description")
    }

    val source = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, "text/markdown")
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }))
    fun awaitSaved(expected: String) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (resolver.openInputStream(source)?.use { it.readBytes().contentEquals(expected.toByteArray()) } == true) return
            SystemClock.sleep(20)
        }
        error("Staging did not save the exact expected bytes")
    }
    try {
        checkNotNull(resolver.openOutputStream(source, "wt")).use { it.write(original.toByteArray()) }
        check(resolver.update(source, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1)
        if (app != targetContext.packageName) shell("am force-stop $app")
        targetContext.grantUriPermission(app, source, flags)
        if (fromHome) {
            localActivity = startActivitySync(Intent(targetContext, HomeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            selectHomeSource(source, "text/markdown")
        } else {
            val intent = Intent(Intent.ACTION_EDIT)
                .setClassName(app, "dev.soupslurpr.beautyxt.MainActivity")
                .setDataAndType(source, "text/markdown")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or flags)
            if (app == targetContext.packageName) localActivity = startActivitySync(intent)
            else targetContext.startActivity(intent)
        }
        waitForAccessibilityNode("minified source editor") { it.isEditable && it.text?.toString() == original }
        waitForAccessibilityIdle()
        uiAutomation.clearCache()
        key(KeyEvent.KEYCODE_H, KeyEvent.META_CTRL_ON)
        query("cat")
        count(3)
        requireActionableText("Whole word").performRequiredClick()
        count(2)
        requireActionableText("Regex").performRequiredClick()
        query("silver (cat)")
        count(2)
        setText(editableAncestor(waitForAccessibilityNode("staging replacement input") {
            it.text?.toString() == "Replace with"
        }), "golden ${'$'}1")
        requireActionableText("Apply 2 replacements")
        capture("document-experience-replacements")
        requireActionableText("Apply 2 replacements").performRequiredClick()
        awaitSaved(original.replace("silver cat", "golden cat"))
        requireActionableText("Undo").performRequiredClick()
        awaitSaved(original)
        requireActionableText("Return to document").performRequiredClick()
        requireActionableContentDescription("Close Find").performRequiredClick()
        requireActionableContentDescription("Preview Markdown").performRequiredClick()
        requireActionableContentDescription("Send and export").performRequiredClick()
        waitForAccessibilityNode("whole-document export scope") { it.text?.toString() == "Whole document" }
        requireActionableText("Share file").performRequiredClick()
        if (app == targetContext.packageName) {
            requireActionableText("Excerpt test receiver").performRequiredClick()
            requireActionableText("Read excerpt now").performRequiredClick()
            val originalBytes = original.toByteArray()
            val digest = MessageDigest.getInstance("SHA-256").digest(originalBytes).joinToString("") { "%02x".format(it) }
            waitForAccessibilityNode("exact saved source in separate receiver") {
                it.text?.toString()?.contains("Read ${originalBytes.size} bytes; SHA-256 $digest;") == true
            }
            requireActionableText("Finish excerpt receiver").performRequiredClick()
        } else {
            // Staging deliberately lacks the debug-only fixture permission. Verify its
            // real chooser handoff; the debug journeys check separate-UID receipt above.
            waitForAccessibilityNode("staging saved file in the system share chooser") {
                it.packageName?.toString() != app && it.text?.toString() == name
            }
            key(KeyEvent.KEYCODE_BACK)
        }
        requireActionableContentDescription("Send and export").performRequiredClick()
        requireActionableText("PDF").performRequiredClick()
        scrollTo("whole-document PDF") {
            it.contentDescription?.toString()?.let { text ->
                "Staging review" in text && "Second silver cat" in text && "PRIVATE TAIL" in text
            } == true
        }
        check(requireActionableText("Save PDF").isVisibleToUser)
        check(requireActionableText("Share PDF").isVisibleToUser)
        key(KeyEvent.KEYCODE_BACK)
        val passage = waitForAccessibilityNode("minified reading paragraph") { it.text?.toString() == paragraph }
        check(passage.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, paragraph.length)
        }))
        requireActionableText("Send/export")
        capture("document-experience-reading-selection")
        requireActionableText("Send/export").performRequiredClick()
        requireActionableText("PDF").performRequiredClick()
        val page = scrollTo("minified selected PDF", { it.contentDescription?.toString()?.contains(paragraph) == true })
        val pageText = page.contentDescription.toString()
        check("PRIVATE TAIL" !in pageText && "Second silver cat" !in pageText && "Staging review" !in pageText)
        waitForAccessibilityNode("minified PDF page count") { it.text?.toString() == "Page 1 of 1" }
        capture("staging-document-experience")
        awaitSaved(original)
        if (fromHome) {
            val home = checkNotNull(localActivity)
            // Back dismisses each document layer before it can pop the navigation entry.
            key(KeyEvent.KEYCODE_BACK)
            requireActionableText("Send/export")
            key(KeyEvent.KEYCODE_BACK)
            waitForAccessibilityIdle()
            uiAutomation.clearCache()
            requireActionableContentDescription("Show source text")
            check(uiAutomation.rootInActiveWindow?.findNode { it.text?.toString() == "Send/export" } == null)
            key(KeyEvent.KEYCODE_BACK)
            waitForEditorText(original)
            runOnMainSync { home.window.insetsController?.hide(WindowInsets.Type.ime()) }
            waitForImeVisibility(home, visible = false)
            SystemClock.sleep(500)
            key(KeyEvent.KEYCODE_BACK)
            requireActionableText("Open file")
            waitForAccessibilityIdle()
            check(!home.isFinishing && !home.isDestroyed && home.hasWindowFocus())
            val manager = checkNotNull(targetContext.getSystemService(ActivityManager::class.java))
            val task = checkNotNull(manager.appTasks.firstOrNull { it.taskInfo?.taskId == home.taskId })
            check(task.taskInfo?.numActivities == 1) { "Document tools escaped Home's navigation entry" }
            awaitSaved(original)
        }
    } catch (failure: Throwable) {
        uiAutomation.clearCache()
        uiAutomation.takeScreenshot()?.let { bitmap ->
            try { File(targetContext.cacheDir, "staging-document-experience-failure.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            } } finally { bitmap.recycle() }
        }
        val nodes = StringBuilder()
        uiAutomation.rootInActiveWindow?.findNode {
            nodes.appendLine("${it.className}: text=${it.text}; description=${it.contentDescription}; editable=${it.isEditable}; visible=${it.isVisibleToUser}")
            false
        }
        File(targetContext.cacheDir, "staging-document-experience-failure.txt").writeText(nodes.toString())
        throw failure
    } finally {
        if (app != targetContext.packageName) shell("am force-stop $app")
        else localActivity?.let { runOnMainSync { it.finishAndRemoveTask() }; waitForIdleSync() }
        try { resolver.delete(source, null, null) }
        finally { targetContext.revokeUriPermission(source, flags) }
    }
}
