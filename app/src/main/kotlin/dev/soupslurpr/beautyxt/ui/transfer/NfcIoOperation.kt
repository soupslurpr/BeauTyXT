package dev.soupslurpr.beautyxt.ui.transfer

import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private val nfcConnectionMutex = Mutex()

/**
 * Runs one exclusive tag operation and closes its connection off the UI thread.
 *
 * The operation must check its context after connecting and before each later
 * I/O step. Closing from another thread interrupts Android's blocked NDEF calls.
 * Join the worker and close again after interruption to cover a connect racing
 * the first close, before allowing another screen to use the NFC connection.
 */
internal suspend fun <Result> runNfcIo(
    closeConnection: () -> Unit,
    operation: (CoroutineContext) -> Result
): Result = nfcConnectionMutex.withLock {
    supervisorScope {
        val worker = async(Dispatchers.IO) {
            coroutineContext.ensureActive()
            operation(coroutineContext)
        }
        try {
            worker.await()
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                worker.cancel()
                if (!worker.isCompleted) {
                    closeNfcConnection(closeConnection)
                }
                worker.join()
                closeNfcConnection(closeConnection)
            }
        }
    }
}

/** Closes a lost or revoked tag without replacing the operation's primary result. */
private fun closeNfcConnection(closeConnection: () -> Unit) {
    try {
        closeConnection()
    } catch (_: IOException) {
    } catch (_: RuntimeException) {
    }
}
