package dev.soupslurpr.beautyxt.ui.transfer

import dev.soupslurpr.beautyxt.R
import dev.soupslurpr.beautyxt.ui.UiText

/** Tracks explicit foreground consent separately from a tag's write outcome. */
internal sealed interface NfcWriteState {
    /** Requires the user to arm this screen before accepting a tag. */
    data object Ready : NfcWriteState

    /** Waits for one tag under the user's current foreground consent. */
    data object Armed : NfcWriteState

    /** Writes and verifies the currently selected tag. */
    data object Writing : NfcWriteState

    /** Requires fresh consent after an unsuccessful or interrupted attempt. */
    data class Failed(val message: UiText) : NfcWriteState

    /** Reports whether the current foreground session owns an armed writer. */
    val isArmed: Boolean
        get() = this == Armed || this == Writing

    /** Disarms a suspended writer without claiming an interrupted tag is unchanged. */
    fun paused(): NfcWriteState = when (this) {
        Armed -> Ready
        Writing -> Failed(UiText.Resource(R.string.nfc_write_interrupted))
        Ready, is Failed -> this
    }
}
