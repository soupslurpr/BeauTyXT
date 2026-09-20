package dev.soupslurpr.beautyxt.ui.transfer

import dev.soupslurpr.beautyxt.R
import dev.soupslurpr.beautyxt.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies that NFC write consent cannot survive leaving the foreground. */
class NfcWriteStateTest {
    /** Disarms a waiting writer without inventing a failed write. */
    @Test
    fun disarmsWaitingWriter() {
        assertTrue(NfcWriteState.Armed.isArmed)
        assertSame(NfcWriteState.Ready, NfcWriteState.Armed.paused())
        assertFalse(NfcWriteState.Ready.isArmed)
    }

    /** Leaves an interrupted writer idle with an explicitly uncertain tag outcome. */
    @Test
    fun reportsInterruptedWriteWithoutRemainingArmed() {
        assertTrue(NfcWriteState.Writing.isArmed)
        val interrupted = NfcWriteState.Writing.paused()
        assertFalse(interrupted.isArmed)
        assertEquals(
            NfcWriteState.Failed(
                UiText.Resource(R.string.nfc_write_interrupted)
            ),
            interrupted
        )
        assertSame(interrupted, interrupted.paused())
    }

    /** Preserves a useful error across further pauses without arming another attempt. */
    @Test
    fun keepsFailureUntilExplicitRetry() {
        val failed = NfcWriteState.Failed(UiText.Literal("The tag is read-only."))
        assertFalse(failed.isArmed)
        assertSame(failed, failed.paused())
        assertSame(NfcWriteState.Ready, NfcWriteState.Ready.paused())
    }
}
