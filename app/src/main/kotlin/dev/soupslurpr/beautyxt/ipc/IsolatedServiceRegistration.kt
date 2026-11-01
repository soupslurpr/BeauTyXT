/* Owns Android connection registration even when a bind fails after registering. */
package dev.soupslurpr.beautyxt.ipc

/** Defers teardown until an in-flight bind has finished registering the connection. */
internal class IsolatedServiceRegistration(private val unbind: () -> Unit) : AutoCloseable {
    private val lock = Any()
    private var started = false
    private var binding = false
    private var registered = false
    private var closed = false

    /** Calls Android outside the gate and cleans up false/throwing attempts. */
    fun bind(register: () -> Boolean): Boolean {
        synchronized(lock) {
            check(!closed) { "isolated service registration is closed" }
            check(!started) { "isolated service binding was already started" }
            started = true
            binding = true
        }
        var accepted = false
        try {
            accepted = register()
            return accepted
        } finally {
            val release = synchronized(lock) {
                binding = false
                registered = accepted && !closed
                !registered
            }
            if (release) unbindQuietly()
        }
    }

    /** Closes once, or lets the pending registration perform post-bind cleanup. */
    override fun close() {
        val release = synchronized(lock) {
            if (closed) return
            closed = true
            if (!binding && registered) {
                registered = false
                true
            } else false
        }
        if (release) unbindQuietly()
    }

    private fun unbindQuietly() {
        try {
            unbind()
        } catch (_: RuntimeException) {
            // Android can reject unregistration when the attempt never registered.
        }
    }
}
