package dev.soupslurpr.beautyxt.printing

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Verifies that PDF text and coordinates cannot become document-controlled syntax. */
class PdfTextEncodingTest {
    /** Preserves supplementary scalars and combining sequences as exact UTF-16 code units. */
    @Test
    fun encodesOriginalUnicode() {
        assertEquals("004100E9D83DDE0000650301", pdfUnicodeHex("Aé😀e\u0301"))
        assertEquals("", pdfUnicodeHex(""))
    }

    /** Encodes delimiters and controls rather than allowing them to escape a PDF string. */
    @Test
    fun encodesDocumentSyntax() {
        assertEquals("00290028005C003C003E000A000D0000", pdfUnicodeHex(")(\\<>\n\r\u0000"))
    }

    /** Writes short fixed-point numbers independently of the process locale. */
    @Test
    fun formatsCoordinatesWithoutLocaleOrExponent() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("0", pdfCoordinate(-0.0001f))
            assertEquals("-12.345", pdfCoordinate(-12.345f))
            assertEquals("36", pdfCoordinate(36f))
            assertEquals("0.001", pdfCoordinate(0.001f))
            assertEquals("1000000", pdfCoordinate(1_000_000f))
        } finally {
            Locale.setDefault(previous)
        }
    }

    /** Rejects nonfinite and excessive geometry before producing malformed PDF tokens. */
    @Test
    fun rejectsInvalidCoordinates() {
        for (value in floatArrayOf(
            Float.NaN,
            Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY,
            1_000_001f
        )) {
            assertThrows(IllegalArgumentException::class.java) { pdfCoordinate(value) }
            assertThrows(IllegalArgumentException::class.java) { pdfMatrixComponent(value) }
        }
    }

    @Test
    fun matrixCoefficientsKeepSubpixelPrecisionAcrossThePage() {
        assertEquals("0.333333", pdfMatrixComponent(1f / 3f))
        assertEquals("0.031875", pdfMatrixComponent(0.031875f))
        assertEquals("0", pdfMatrixComponent(-0.0000001f))
    }
}
