package dev.soupslurpr.beautyxt.ui.editor

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private const val VERSION_ONE_DIMENSION = 21
private const val VERSION_TWO_DIMENSION = 25
private const val VERSION_SEVEN_DIMENSION = 45
private const val VERSION_THIRTY_TWO_DIMENSION = 145
private const val VERSION_FORTY_DIMENSION = 177

/** Verifies scan-critical QR structures remain conventionally rendered. */
class ExpressiveQrCodeTest {
    /** Verifies light-theme role colors retain their natural QR polarity. */
    @Test
    fun ordersLightThemeColorsForScanning() {
        val background = Color(0xffafc6ff)
        val modules = Color(0xff17315e)

        assertEquals(
            QrCodeColors(modules = modules, background = background),
            scanSafeQrCodeColors(
                primaryContainer = background,
                onPrimaryContainer = modules
            )
        )
    }

    /** Verifies dark-theme role colors swap while remaining fully dynamic. */
    @Test
    fun ordersDarkThemeColorsForScanning() {
        val modules = Color(0xff4f638c)
        val background = Color(0xffd9e2ff)

        assertEquals(
            QrCodeColors(modules = modules, background = background),
            scanSafeQrCodeColors(
                primaryContainer = modules,
                onPrimaryContainer = background
            )
        )
    }

    /** Verifies alignment centers across ordinary and exceptional versions. */
    @Test
    fun computesStandardAlignmentCenters() {
        assertArrayEquals(
            intArrayOf(),
            QrFunctionModules(VERSION_ONE_DIMENSION).alignmentCenters()
        )
        assertArrayEquals(
            intArrayOf(6, 18),
            QrFunctionModules(VERSION_TWO_DIMENSION).alignmentCenters()
        )
        assertArrayEquals(
            intArrayOf(6, 22, 38),
            QrFunctionModules(VERSION_SEVEN_DIMENSION).alignmentCenters()
        )
        assertArrayEquals(
            intArrayOf(6, 34, 60, 86, 112, 138),
            QrFunctionModules(VERSION_THIRTY_TWO_DIMENSION).alignmentCenters()
        )
        assertArrayEquals(
            intArrayOf(6, 30, 58, 86, 114, 142, 170),
            QrFunctionModules(VERSION_FORTY_DIMENSION).alignmentCenters()
        )
    }

    /** Verifies the three finder regions are isolated from expressive data styling. */
    @Test
    fun identifiesEveryFinderRegion() {
        val functionModules = QrFunctionModules(VERSION_ONE_DIMENSION)

        assertTrue(functionModules.isFinder(row = 0, column = 0))
        assertTrue(functionModules.isFinder(row = 6, column = 20))
        assertTrue(functionModules.isFinder(row = 20, column = 6))
        assertFalse(functionModules.isFinder(row = 7, column = 7))
        assertFalse(functionModules.isFinder(row = 20, column = 20))
    }

    /** Verifies timing, format, alignment, and version modules stay square. */
    @Test
    fun identifiesNonFinderFunctionModules() {
        val versionTwoModules = QrFunctionModules(VERSION_TWO_DIMENSION)
        val versionSevenModules = QrFunctionModules(VERSION_SEVEN_DIMENSION)

        assertTrue(versionTwoModules.isStructural(row = 6, column = 12))
        assertTrue(versionTwoModules.isStructural(row = 8, column = 24))
        assertTrue(versionTwoModules.isStructural(row = 16, column = 20))
        assertFalse(versionTwoModules.isStructural(row = 15, column = 20))
        assertTrue(versionSevenModules.isStructural(row = 0, column = 34))
        assertTrue(versionSevenModules.isStructural(row = 34, column = 0))
        assertFalse(versionSevenModules.isStructural(row = 10, column = 10))
    }

    /** Verifies malformed dimensions and coordinates fail at their boundaries. */
    @Test
    fun rejectsInvalidGeometryInputs() {
        assertThrows(IllegalArgumentException::class.java) {
            scanSafeQrCodeColors(
                primaryContainer = Color.Unspecified,
                onPrimaryContainer = Color.Black
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            QrFunctionModules(VERSION_ONE_DIMENSION - 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            QrFunctionModules(VERSION_ONE_DIMENSION + 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            QrFunctionModules(VERSION_FORTY_DIMENSION + 4)
        }

        val functionModules = QrFunctionModules(VERSION_ONE_DIMENSION)
        assertThrows(IllegalArgumentException::class.java) {
            functionModules.isStructural(row = -1, column = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            functionModules.isFinder(row = 0, column = VERSION_ONE_DIMENSION)
        }
    }
}
