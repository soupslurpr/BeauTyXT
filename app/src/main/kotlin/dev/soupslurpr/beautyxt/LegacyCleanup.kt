package dev.soupslurpr.beautyxt

import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Identifies whether this process can expose document workflows after migration. */
internal enum class LegacyCleanupStatus {
    Pending,
    Running,
    Ready,
    Failed
}

/** Owns one off-thread cleanup attempt shared by every activity in this process. */
internal class LegacyCleanup(
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val removeState: () -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutableStatus = MutableStateFlow(LegacyCleanupStatus.Pending)
    val status = mutableStatus.asStateFlow()

    /** Starts initial cleanup or an explicit retry without overlapping attempts. */
    fun start() {
        val previous = mutableStatus.value
        if (previous != LegacyCleanupStatus.Pending && previous != LegacyCleanupStatus.Failed) {
            return
        }
        if (!mutableStatus.compareAndSet(previous, LegacyCleanupStatus.Running)) {
            return
        }
        scope.launch {
            mutableStatus.value = try {
                removeState()
                LegacyCleanupStatus.Ready
            } catch (_: IOException) {
                LegacyCleanupStatus.Failed
            } catch (_: SecurityException) {
                LegacyCleanupStatus.Failed
            }
        }
    }

    /** Suspends one activity until cleanup succeeds, including after an explicit retry. */
    suspend fun awaitReady() {
        status.first { it == LegacyCleanupStatus.Ready }
    }
}
