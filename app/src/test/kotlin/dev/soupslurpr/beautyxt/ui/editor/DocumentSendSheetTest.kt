/* Verifies truthful capacity descriptions for the outgoing document chooser. */
package dev.soupslurpr.beautyxt.ui.editor

import dev.soupslurpr.beautyxt.R
import dev.soupslurpr.beautyxt.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Test

/** Checks that payload eligibility does not claim a physical NFC tag will fit. */
class DocumentSendSheetTest {
    @Test
    fun leavesPhysicalTagCapacityUndetermined() {
        assertEquals(
            UiText.Resource(R.string.nfc_write_capacity, listOf("1,200")),
            nfcWriteCapacityDescription(DocumentTransferCapacity(1_200, 1_536))
        )
    }

    @Test
    fun preservesPendingAndOversizedPayloadExplanations() {
        assertEquals(
            UiText.Resource(R.string.transfer_checking),
            nfcWriteCapacityDescription(DocumentTransferCapacity(null, 1_536))
        )
        assertEquals(
            UiText.Resource(R.string.transfer_too_large, listOf("1,537", "1,536")),
            nfcWriteCapacityDescription(DocumentTransferCapacity(1_537, 1_536))
        )
    }
}
