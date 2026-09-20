package dev.soupslurpr.beautyxt.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies canonical, deterministic handling of optional NFC tag metadata. */
class NfcTagMetadataTest {
    /** Round-trips every supported label length through the isolated-transfer argument. */
    @Test
    fun roundTripsCanonicalLabels() {
        listOf(null, "A", "A0", "A01").forEach { label ->
            val packed = packNfcTagLabel(label)

            assertTrue(isValidPackedNfcTagLabel(packed))
            assertEquals(label, unpackNfcTagLabel(packed))
            assertEquals((label?.length ?: 0).toLong(), packedNfcTagLabelLength(packed))
        }
    }

    /** Canonicalizes manual input while rejecting unsupported characters and lengths. */
    @Test
    fun canonicalizesManualLabels() {
        assertNull(canonicalNfcTagLabelOrNull(""))
        assertEquals("A01", canonicalNfcTagLabelOrNull("a01"))
        assertThrows(IllegalArgumentException::class.java) {
            canonicalNfcTagLabelOrNull("A-1")
        }
        assertThrows(IllegalArgumentException::class.java) {
            canonicalNfcTagLabelOrNull("A001")
        }
        assertThrows(IllegalArgumentException::class.java) {
            canonicalNfcTagLabelOrNull("ß")
        }
    }

    /** Rejects non-canonical bits in packed arguments before they cross the boundary. */
    @Test
    fun rejectsInvalidPackedLabels() {
        assertFalse(isValidPackedNfcTagLabel(4L))
        assertFalse(isValidPackedNfcTagLabel(1L or ('a'.code.toLong() shl Byte.SIZE_BITS)))
        assertFalse(isValidPackedNfcTagLabel(1L or ('A'.code.toLong() shl 16)))
        assertFalse(isValidPackedNfcTagLabel(-1L))
    }

    /** Encodes Android's reported identifier as transient canonical hexadecimal text. */
    @Test
    fun encodesReportedTagIdentifier() {
        assertNull(reportedNfcTagId(byteArrayOf()))
        assertEquals("00:7F:80:FF", reportedNfcTagId(byteArrayOf(0, 0x7f, -0x80, -1)))
        assertTrue(isValidReportedNfcTagId("00:7F:80:FF"))
        assertFalse(isValidReportedNfcTagId("00:7f"))
        assertFalse(isValidReportedNfcTagId("0:7F"))
    }
}
