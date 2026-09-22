package dev.soupslurpr.beautyxt.document

import android.accessibilityservice.AccessibilityService
import android.app.Instrumentation
import android.content.ContentValues
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject

private const val PROFILE_PACKAGE = "dev.soupslurpr.beautyxt.staging"
private const val PROFILE_TIMEOUT_MS = 45_000L

/** External, opt-in workflow measurements; no performance assertions or production hooks. */
internal fun Instrumentation.profileStagingEditing(arguments: Bundle) {
    val workload = arguments.getString("profileWorkload") ?: "small"
    require(workload in setOf("small", "large", "markdown"))
    val run = arguments.getString("profileRun") ?: "manual"
    require(run.matches(Regex("[a-zA-Z0-9_-]{1,80}")))
    val measureMemory = arguments.getString("profileMemory") == "true"
    val holdPreview = arguments.getString("profileHoldPreview") == "true"
    require(!holdPreview || measureMemory && workload == "markdown")
    val directory = File(targetContext.filesDir, "editing-profile/$run").apply {
        check(mkdirs()) { "profile run already exists: $run" }
    }
    val result = JSONObject().put("run", run).put("workload", workload)
        .put("memorySampling", measureMemory)
        .put("holdPreview", holdPreview)
    val stage = AtomicReference("setup")
    val sampling = AtomicBoolean(measureMemory)
    var memoryThread: Thread? = null
    var document: Uri? = null
    val resolver = targetContext.contentResolver
    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }
    fun now() = System.nanoTime()
    fun msSince(start: Long) = (now() - start) / 1_000_000.0
    fun field() = uiAutomation.rootInActiveWindow?.findNode {
        it.packageName?.toString() == PROFILE_PACKAGE && it.isEditable
    }
    fun await(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + PROFILE_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(10)
        }
        error("editing profile timed out: $description")
    }
    fun key(code: Int) {
        val time = SystemClock.uptimeMillis()
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            check(uiAutomation.injectInputEvent(KeyEvent(time, time, action, code, 0), false))
        }
    }
    fun frames(label: String) {
        File(directory, "$label-frames.txt").writeText(shell("dumpsys gfxinfo $PROFILE_PACKAGE framestats"))
    }
    fun interval(label: String, block: () -> Unit) {
        stage.set(label)
        shell("dumpsys gfxinfo $PROFILE_PACKAGE reset")
        val start = now()
        block()
        val end = now()
        result.put(label + "StartNs", start).put(label + "EndNs", end)
        frames(label)
    }
    try {
        val text = when (workload) {
            "small" -> "\nProfile document\n" + "The quick brown fox edits a local file. 0123456789\n".repeat(160)
            "large" -> "\nProfile document\n" + "The quick brown fox edits a local file. 0123456789\n".repeat(350_000)
            else -> "\nProfile document\n\n\$\$\nx^2 + y^2 = z^2\n\$\$\n\n" +
                "```mermaid\nflowchart LR\nA[Open] --> B[Edit]\n```\n\n" +
                "A paragraph with **bold**, *emphasis*, and `code`.\n\n".repeat(160)
        }
        val original = text.toByteArray(Charsets.UTF_8)
        result.put("sourceBytes", original.size)
        val name = "beautyxt-profile-$run.${if (workload == "markdown") "md" else "txt"}"
        val mime = if (workload == "markdown") "text/markdown" else "text/plain"
        document = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }))
        val source = document
        checkNotNull(resolver.openOutputStream(source, "wt")).use { it.write(original) }
        check(resolver.update(source, ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }, null, null) == 1)
        shell("am force-stop $PROFILE_PACKAGE")
        targetContext.grantUriPermission(PROFILE_PACKAGE, source, flags)
        if (measureMemory) {
            memoryThread = thread(name = "editing-memory-sampler") {
                File(directory, "memory.jsonl").bufferedWriter().use { output ->
                    while (sampling.get()) {
                        val sample = JSONObject().put("timeNs", now()).put("stage", stage.get())
                        val reports = JSONArray()
                        val pids = shell("pgrep -f ^dev[.]soupslurpr[.]beautyxt[.]staging")
                            .split(Regex("\\s+")).mapNotNull(String::toIntOrNull)
                        for (pid in pids) {
                            reports.put(JSONObject().put("pid", pid)
                                .put("report", shell("dumpsys meminfo --local $pid")))
                        }
                        sample.put("processes", reports).put("endNs", now())
                        output.appendLine(sample.toString())
                        output.flush()
                        SystemClock.sleep(100)
                    }
                }
            }
        }
        stage.set("opening")
        val opened = now()
        targetContext.startActivity(Intent(Intent.ACTION_EDIT)
            .setClassName(PROFILE_PACKAGE, "dev.soupslurpr.beautyxt.MainActivity")
            .setDataAndType(source, mime)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or flags))
        await("editable source") { field()?.text?.startsWith("\nProfile document") == true }
        result.put("openToEditableMs", msSince(opened))
        check(checkNotNull(field()).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        await("focused editor") { field()?.isFocused == true }
        SystemClock.sleep(700)
        check(checkNotNull(field()).performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION,
            Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, 0)
            }))
        await("cursor at beginning") { field()?.textSelectionStart == 0 }
        val latencies = JSONArray()
        var lastKey = 0L
        var inserted = ""
        interval("typing") {
            repeat(26) { index ->
                val character = if (index == 12 || index == 25) ' ' else 'z'
                val started = now()
                lastKey = started
                key(if (character == ' ') KeyEvent.KEYCODE_SPACE else KeyEvent.KEYCODE_Z)
                inserted += character
                await("typed character ${index + 1}") {
                    field()?.text?.startsWith(inserted + "\nProfile document") == true
                }
                latencies.put(msSince(started))
                // Commit each word before pausing so IME composition does not defer autosave.
                SystemClock.sleep(if (index == 12) 500 else 70)
            }
        }
        result.put("keyToObservedTextMs", latencies)
        fun awaitSaved(prefix: String) {
            val expected = prefix.toByteArray() + original
            await("byte-exact source save") {
                checkNotNull(resolver.openFileDescriptor(source, "r")).use { descriptor ->
                    if (descriptor.statSize != expected.size.toLong()) false
                    else ParcelFileDescriptor.AutoCloseInputStream(descriptor).use {
                        it.readBytes().contentEquals(expected)
                    }
                }
            }
        }
        stage.set("foreground-save")
        awaitSaved(inserted)
        result.put("lastKeyToSavedBytesMs", msSince(lastKey))
        await("completed autosave status") {
            uiAutomation.rootInActiveWindow?.findNode {
                it.text?.toString() == "Saved automatically"
            } != null
        }
        result.put("lastKeyToSavedStatusMs", msSince(lastKey))
        // Start another autosave before scrolling or opening the uncached preview.
        key(KeyEvent.KEYCODE_Z)
        key(KeyEvent.KEYCODE_SPACE)
        inserted += "z "
        await("edit before navigation") {
            field()?.text?.startsWith(inserted + "\nProfile document") == true
        }
        key(KeyEvent.KEYCODE_BACK) // hide the IME
        SystemClock.sleep(500)
        if (workload == "markdown") {
            val preview = requireActionableContentDescription("Preview Markdown")
            stage.set("preview")
            val previewStart = now()
            preview.performRequiredClick()
            await("rendered Markdown") {
                val root = uiAutomation.rootInActiveWindow
                root?.findNode { it.contentDescription?.toString()?.startsWith("Formula:") == true } != null &&
                    root.findNode { it.contentDescription?.toString()?.startsWith("Diagram:") == true } != null
            }
            result.put("previewContentMs", msSince(previewStart))
            await("rendered illustrations") {
                val root = uiAutomation.rootInActiveWindow
                root != null && root.findNode { it.text?.toString() == "Rendering…" && it.isVisibleToUser } == null &&
                    root.findNode { it.contentDescription?.toString() == "Show diagram at text size" } != null
            }
            check(uiAutomation.rootInActiveWindow?.findNode {
                it.text?.toString()?.startsWith("Shown as source") == true
            } == null) { "an illustration fell back to source" }
            result.put("previewWithIllustrationsMs", msSince(previewStart))
            if (holdPreview) {
                stage.set("preview-settled")
                SystemClock.sleep(6_000)
            }
        }
        interval("scrolling") {
            val bounds = Rect().also { checkNotNull(uiAutomation.rootInActiveWindow).getBoundsInScreen(it) }
            val x = bounds.centerX().toFloat()
            repeat(6) { index ->
                val from = bounds.top + bounds.height() * if (index % 2 == 0) 0.72f else 0.35f
                val to = bounds.top + bounds.height() * if (index % 2 == 0) 0.35f else 0.72f
                val downTime = SystemClock.uptimeMillis()
                fun touch(action: Int, y: Float) {
                    val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
                    event.source = InputDevice.SOURCE_TOUCHSCREEN
                    try { check(uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
                }
                touch(MotionEvent.ACTION_DOWN, from)
                repeat(18) { step ->
                    SystemClock.sleep(12)
                    touch(MotionEvent.ACTION_MOVE, from + (to - from) * (step + 1) / 18)
                }
                touch(MotionEvent.ACTION_UP, to)
                SystemClock.sleep(160)
                // Read each gesture before gfxinfo's rolling frame buffer overwrites it.
                frames("scrolling-$index")
            }
        }
        // Verify a fresh edit is checkpointed when this same document is backgrounded.
        if (workload == "markdown") {
            key(KeyEvent.KEYCODE_BACK)
            await("source after preview") { field() != null }
        }
        check(checkNotNull(field()).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        SystemClock.sleep(500)
        check(checkNotNull(field()).text.toString().startsWith(inserted + "\nProfile document"))
        check(checkNotNull(field()).performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION,
            Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, inserted.length)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, inserted.length)
            }))
        await("background edit cursor") { field()?.textSelectionStart == inserted.length }
        stage.set("background-save")
        val backgroundEdit = now()
        key(KeyEvent.KEYCODE_Z)
        key(KeyEvent.KEYCODE_SPACE)
        inserted += "z "
        await("background edit") { field()?.text?.startsWith(inserted + "\nProfile document") == true }
        check(uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME))
        awaitSaved(inserted)
        result.put("backgroundEditToSavedBytesMs", msSince(backgroundEdit))
        result.put("exactSavedBytes", true)
        stage.set("settled")
        SystemClock.sleep(if (measureMemory) 1_000 else 250)
        result.put("passed", true)
        Log.i("BeauTyXTEditingProfile", result.toString())
    } catch (failure: Throwable) {
        result.put("passed", false).put("failure", failure.stackTraceToString())
        throw failure
    } finally {
        sampling.set(false)
        memoryThread?.join(10_000)
        File(directory, "result.json").writeText(result.toString(2))
        shell("am force-stop $PROFILE_PACKAGE")
        document?.let {
            try { resolver.delete(it, null, null) }
            finally { targetContext.revokeUriPermission(it, flags) }
        }
    }
}
