package dev.soupslurpr.beautyxt.illustration

import android.content.Context

/**
 * Reuses a healthy isolated instance, but never reuses a dead one for a later source. The failed
 * request is not retried here; the caller controls result retention and work/attempt budgets.
 */
internal class RestartingIllustrationWorker(
    context: Context,
    private val serviceClass: Class<out IsolatedIllustrationService>,
    private val maximumInputBytes: Int
) : AutoCloseable {
    private val application = context.applicationContext
    private val lock = Any()
    private var current: IllustrationWorkerConnection? = null
    private var closed = false

    suspend fun render(source: String, display: Boolean): IllustrationResult {
        val worker = synchronized(lock) {
            if (closed) return IllustrationResult.Fallback(IllustrationFailure.Unavailable)
            current?.takeUnless { it.isClosed } ?: IllustrationWorkerConnection(
                application,
                serviceClass,
                maximumInputBytes
            ).also { current = it }
        }
        return worker.render(source, display)
    }

    /** Releases process memory while retaining the ability to render after foregrounding. */
    fun releaseInstance() {
        synchronized(lock) {
            current?.close()
            current = null
        }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            releaseInstance()
        }
    }
}
