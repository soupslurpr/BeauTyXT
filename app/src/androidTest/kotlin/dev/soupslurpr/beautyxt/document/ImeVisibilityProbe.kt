/* Observes keyboard continuity across real editor interactions. */
package dev.soupslurpr.beautyxt.document

import android.view.Choreographer
import android.view.View
import android.view.WindowInsets

/** Records every display frame's keyboard visibility without replacing window callbacks. */
internal class ImeVisibilityProbe(
    private val root: View,
    private val expectedVisible: Boolean = true
) : Choreographer.FrameCallback,
    AutoCloseable {
    private val choreographer = Choreographer.getInstance()
    private var frameCount = 0
    private var unexpectedFrameCount = 0
    private var closed = false

    init {
        check(root.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == expectedVisible) {
            "keyboard must match its expected visibility before observing continuity"
        }
        choreographer.postFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        frameCount += 1
        if (root.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) != expectedVisible) {
            unexpectedFrameCount += 1
        }
        choreographer.postFrameCallback(this)
    }

    /** Requires unchanged keyboard visibility after observation has stopped on the UI thread. */
    fun requireStableVisibility() {
        check(closed) { "keyboard visibility must be checked after observation stops" }
        check(frameCount > 0) { "keyboard continuity was not observed on any display frame" }
        check(unexpectedFrameCount == 0) {
            "keyboard visibility differed from $expectedVisible on " +
                "$unexpectedFrameCount of $frameCount observed frames"
        }
    }

    override fun close() {
        choreographer.removeFrameCallback(this)
        closed = true
    }
}
