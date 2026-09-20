/* Owns a QR image write independently of screen composition and configuration. */
package dev.soupslurpr.beautyxt.ui.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Serializes QR image destinations and writes within one retained document session. */
internal class QrImageExportController(
    private val scope: CoroutineScope,
    private val matchesSourceUri: (String) -> Boolean
) {
    var state by mutableStateOf(QrImageExportState())
        private set

    /** Keeps the selected format stable for the entire picker and write operation. */
    fun selectFormat(format: QrImageFormat): Boolean {
        if (!scope.isActive || state.status.isActive()) return false
        if (state.format != format) {
            state = state.copy(format = format, status = QrImageSaveStatus.Idle)
        }
        return true
    }

    /** Claims the destination picker only while no other image export owns it. */
    fun chooseDestination(generation: Long): Boolean {
        require(generation >= 0L) { "QR generation must be nonnegative" }
        if (!scope.isActive || state.status.isActive()) {
            return false
        }
        state = state.copy(status = QrImageSaveStatus.ChoosingDestination, generation = generation)
        return true
    }

    /** Releases one cancelled picker without changing another generation's operation. */
    fun cancelDestination(generation: Long) {
        finishDestination(generation, QrImageSaveStatus.Idle)
    }

    /** Records picker failure without cancelling an image that is already being written. */
    fun failDestination(generation: Long) {
        finishDestination(generation, QrImageSaveStatus.Failed)
    }

    /** Starts one write after rejecting stale results and the retained source URI. */
    fun saveSelectedDestination(
        generation: Long,
        encodedUri: String,
        write: suspend () -> Unit
    ): Boolean {
        require(encodedUri.isNotBlank()) { "QR image destination must not be blank" }
        if (!scope.isActive || !ownsDestination(generation)) {
            return false
        }
        if (matchesSourceUri(encodedUri)) {
            failDestination(generation)
            return false
        }
        state = state.copy(status = QrImageSaveStatus.Saving)
        scope.launch {
            try {
                write()
                state = state.copy(status = QrImageSaveStatus.Succeeded)
            } catch (cancellation: CancellationException) {
                state = state.copy(status = QrImageSaveStatus.Failed)
                throw cancellation
            } catch (_: Exception) {
                state = state.copy(status = QrImageSaveStatus.Failed)
            }
        }
        return true
    }

    /** Returns whether this generation still owns the unconsumed picker result. */
    private fun ownsDestination(generation: Long): Boolean =
        state.generation == generation && state.status == QrImageSaveStatus.ChoosingDestination

    /** Finishes only an unconsumed picker result owned by this generation. */
    private fun finishDestination(generation: Long, status: QrImageSaveStatus) {
        if (ownsDestination(generation)) {
            state = state.copy(status = status)
        }
    }
}
