/* Verifies bounded decimal print input and exact physical-unit rounding. */
package dev.soupslurpr.beautyxt.printing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies exact transient print configuration parsing and unit conversion. */
class PrintSettingsTest {
    /** Keeps the error hint synchronized with the validator in both physical units. */
    @Test
    fun marginFeedbackUsesTheActualBound() {
        for ((unit, maximum) in listOf(
            PrintMarginUnit.Inches to "20",
            PrintMarginUnit.Millimetres to "508"
        )) {
            assertEquals(maximum, maximumPrintMarginText(unit))
            val draft = defaultPrintSetupDraft(formattedMarkdown = false).copy(marginUnit = unit)
            assertTrue(validatePrintSetup(draft.copy(topMargin = maximum)).isValid)
            assertFalse(
                validatePrintSetup(draft.copy(topMargin = (maximum.toInt() + 1).toString())).isValid
            )
        }
    }

    /** Rejects expressions and oversized input before arbitrary-precision arithmetic. */
    @Test
    fun rejectsMarginExpressionsAndOversizedFields() {
        val draft = defaultPrintSetupDraft(formattedMarkdown = false)
        for (input in listOf("1e1", "+1", "0,5", "0".repeat(40))) {
            assertFalse(input, validatePrintSetup(draft.copy(topMargin = input)).isValid)
        }
    }

    /** Rejects extreme exponents without attempting to expand their powers of ten. */
    @Test
    fun rejectsExtremeMarginExponents() {
        val draft = defaultPrintSetupDraft(formattedMarkdown = false)
        for (unit in PrintMarginUnit.entries) {
            for (input in listOf("1e2147483647", "1e-2147483647", "1e999999999")) {
                val validation =
                    validatePrintSetup(draft.copy(topMargin = input, marginUnit = unit))
                assertEquals(setOf(PrintMarginField.Top), validation.invalidMarginFields)
                assertFalse(validation.isValid)
            }
        }
    }

    /** Rounds millimetres directly to mils without an intermediate rounding step. */
    @Test
    fun roundsMillimetresOnlyOnce() {
        val draft = defaultPrintSetupDraft(formattedMarkdown = false).copy(
            marginUnit = PrintMarginUnit.Millimetres
        )
        for ((input, expectedMils) in listOf("0.0126873" to 0, "0.0127" to 1, "0.0380873" to 1)) {
            val settings = checkNotNull(validatePrintSetup(draft.copy(topMargin = input)).settings)
            assertEquals(input, expectedMils, settings.margins.topMils)
        }
    }

    /** Verifies the defaults retain independent half-inch physical margins. */
    @Test
    fun createsValidatedSourceDefaults() {
        val settings = defaultPrintSettings(formattedMarkdown = false)

        assertEquals(PrintContentMode.Source, settings.contentMode)
        assertEquals(PrintFontFamily.Monospace, settings.fontFamily)
        assertEquals(10, settings.fontSizePoints)
        assertEquals(500, settings.margins.topMils)
        assertEquals(500, settings.margins.bottomMils)
        assertEquals(500, settings.margins.leftMils)
        assertEquals(500, settings.margins.rightMils)
        assertTrue(settings.wrapLongLines)
        assertTrue(settings.showFileName)
        assertTrue(settings.showPageNumbers)
    }

    /** Starts formatted output with the same sans-serif family as the document preview. */
    @Test
    fun createsReadableMarkdownDefaults() {
        val settings = defaultPrintSettings(formattedMarkdown = true)
        assertEquals(PrintContentMode.FormattedMarkdown, settings.contentMode)
        assertEquals(PrintFontFamily.SansSerif, settings.fontFamily)
        assertEquals(12, settings.fontSizePoints)
    }

    /** Verifies millimetre display conversion preserves the exact stored mils. */
    @Test
    fun convertsMarginsWithoutFloatingPointDrift() {
        val inches =
            defaultPrintSetupDraft(formattedMarkdown = true).copy(
                topMargin = "0.125",
                bottomMargin = "0.25",
                leftMargin = "0.5",
                rightMargin = "1.75"
            )

        val millimetres = convertPrintMarginUnit(inches, PrintMarginUnit.Millimetres)
        assertEquals("3.175", millimetres.topMargin)
        assertEquals("6.35", millimetres.bottomMargin)
        assertEquals("12.7", millimetres.leftMargin)
        assertEquals("44.45", millimetres.rightMargin)

        val settings = checkNotNull(validatePrintSetup(millimetres).settings)
        assertEquals(125, settings.margins.topMils)
        assertEquals(250, settings.margins.bottomMils)
        assertEquals(500, settings.margins.leftMils)
        assertEquals(1_750, settings.margins.rightMils)
    }

    /** Verifies malformed fields never produce a partial print configuration. */
    @Test
    fun rejectsInvalidMarginsAndFontSizes() {
        val validation =
            validatePrintSetup(
                defaultPrintSetupDraft(formattedMarkdown = false).copy(
                    topMargin = "-1",
                    rightMargin = "not a number",
                    fontSize = "5"
                )
            )

        assertNull(validation.settings)
        assertEquals(
            setOf(PrintMarginField.Top, PrintMarginField.Right),
            validation.invalidMarginFields
        )
        assertTrue(validation.isFontSizeInvalid)
        assertFalse(validation.isValid)
    }
}
