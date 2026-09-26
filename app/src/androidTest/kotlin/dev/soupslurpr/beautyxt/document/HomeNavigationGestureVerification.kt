/* Checks page motion through real Android edge input, including gesture release. */
package dev.soupslurpr.beautyxt.document

import android.app.Instrumentation
import android.graphics.Rect
import android.os.SystemClock
import android.provider.Settings
import android.view.InputDevice
import android.view.MotionEvent
import kotlin.math.abs

/** Requires gesture navigation; checks rendered page positions, not animation configuration. */
internal fun Instrumentation.verifyHomeNavigationGestures() {
    check(Settings.Secure.getInt(targetContext.contentResolver, "navigation_mode", -1) == 2) {
        "Home navigation gestures requires Android gesture navigation"
    }
    val home = startHomeDestination("About")
    fun settle() {
        uiAutomation.waitForIdle(500L, 15_000L)
        uiAutomation.clearCache()
    }
    fun titleBounds(title: String): Rect? {
        uiAutomation.clearCache()
        val node = uiAutomation.rootInActiveWindow?.findNode {
            it.text?.toString() == title && it.isVisibleToUser &&
                Rect().also(it::getBoundsInScreen).bottom <
                home.windowManager.currentWindowMetrics.bounds.height() / 4
        } ?: return null
        return Rect().also(node::getBoundsInScreen)
    }
    fun settleAt(title: String) {
        waitForAccessibilityNode("toolbar title '$title'") {
            it.text?.toString() == title && it.isVisibleToUser &&
                Rect().also(it::getBoundsInScreen).bottom <
                home.windowManager.currentWindowMetrics.bounds.height() / 4
        }
        settle()
        // Accessibility idle does not imply that a Compose placement animation has ended.
        var previous: Rect? = null
        var stableSamples = 0
        val deadline = SystemClock.uptimeMillis() + 5_000L
        while (SystemClock.uptimeMillis() < deadline) {
            val current = titleBounds(title)
            stableSamples = if (current != null && current == previous) stableSamples + 1 else 0
            if (stableSamples == 4) return
            previous = current
            SystemClock.sleep(100L)
        }
        error("toolbar title '$title' did not settle")
    }
    try {
        settleAt("About BeauTyXT")
        val width = home.windowManager.currentWindowMetrics.bounds.width()
        val y = home.windowManager.currentWindowMetrics.bounds.centerY().toFloat()
        val tolerance = width / 50
        for (rightEdge in listOf(false, true)) {
            for (cancelled in listOf(true, false)) {
                if (cancelled) {
                    requireActionableText("Open-source licenses").performRequiredClick()
                    settleAt("Open-source licenses")
                }
                val original = checkNotNull(titleBounds("Open-source licenses")) {
                    "licenses title missing before gesture: rightEdge=$rightEdge, cancelled=$cancelled"
                }
                val downTime = SystemClock.uptimeMillis()
                var pointerDown = false
                fun touch(action: Int, distance: Float) {
                    val x = if (rightEdge) width - 2f - distance else 2f + distance
                    val event = MotionEvent.obtain(
                        downTime, SystemClock.uptimeMillis(), action, x, y, 0
                    ).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                    try {
                        check(uiAutomation.injectInputEvent(event, true)) {
                            "Android rejected touch action $action at $x,$y"
                        }
                        pointerDown = action != MotionEvent.ACTION_UP &&
                            action != MotionEvent.ACTION_CANCEL
                    } finally {
                        event.recycle()
                    }
                }
                touch(MotionEvent.ACTION_DOWN, 0f)
                try {
                    repeat(12) { step ->
                        touch(MotionEvent.ACTION_MOVE, width * .4f * (step + 1) / 12)
                        SystemClock.sleep(25L)
                    }
                    // Hold the finger while checking that the preview actually moved.
                    SystemClock.sleep(250L)
                    val preview = checkNotNull(titleBounds("Open-source licenses")) {
                        "outgoing licenses disappeared during the held Back gesture"
                    }
                    val displacement = if (rightEdge) original.right - preview.right
                        else preview.left - original.left
                    check(displacement > tolerance) {
                        "licenses moved against the gesture or stayed still: " +
                            "rightEdge=$rightEdge, original=$original, preview=$preview"
                    }
                    if (cancelled) {
                        repeat(12) { step ->
                            touch(MotionEvent.ACTION_MOVE, width * .4f * (11 - step) / 12)
                            SystemClock.sleep(25L)
                        }
                        touch(MotionEvent.ACTION_UP, 0f)
                        settleAt("Open-source licenses")
                        val restored = checkNotNull(titleBounds("Open-source licenses")) {
                            "licenses title missing after cancelled Back: rightEdge=$rightEdge"
                        }
                        check(abs(restored.left - original.left) <= tolerance) {
                            "cancelled Back did not restore its position: rightEdge=$rightEdge, " +
                                "original=$original, restored=$restored"
                        }
                    } else {
                        touch(MotionEvent.ACTION_UP, width * .4f)
                        var previous = preview
                        val deadline = SystemClock.uptimeMillis() + 700L
                        while (SystemClock.uptimeMillis() < deadline) {
                            val current = titleBounds("Open-source licenses")
                            if (current != null) {
                                check(
                                    if (rightEdge) current.right <= previous.right + tolerance
                                    else current.left >= previous.left - tolerance
                                ) {
                                    "licenses reversed on release: rightEdge=$rightEdge, " +
                                        "previous=$previous, current=$current"
                                }
                                previous = current
                            }
                            SystemClock.sleep(16L)
                        }
                        settleAt("About BeauTyXT")
                        checkNotNull(titleBounds("About BeauTyXT")) {
                            "completed Back did not return to About"
                        }
                    }
                } finally {
                    if (pointerDown) touch(MotionEvent.ACTION_CANCEL, 0f)
                }
            }
        }
        // Toolbar Back must still navigate correctly after repeated gesture cycles.
        requireActionableText("Open-source licenses").performRequiredClick()
        settleAt("Open-source licenses")
        requireActionableContentDescription("Back").performRequiredClick()
        settleAt("About BeauTyXT")
        checkNotNull(titleBounds("About BeauTyXT")) { "toolbar Back did not return to About" }
        requireActionableContentDescription("Back").performRequiredClick()
        settle()
        requireActionableText("Open file")
    } finally {
        runOnMainSync(home::finishAndRemoveTask)
        waitForAccessibilityIdle()
    }
}
