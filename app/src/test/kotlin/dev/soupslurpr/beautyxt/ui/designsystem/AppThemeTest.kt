package dev.soupslurpr.beautyxt.ui.designsystem

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.contentColorFor
import androidx.compose.ui.graphics.Color
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AppThemeTest {
    @Test
    fun trueBlackDarkSurfacesZeroPageTokensAndPreserveContainersAndInk() {
        val dark = darkColorScheme(
            primary = Color(0xFFB8C6EE),
            onPrimary = Color(0xFF21304F),
            secondary = Color(0xFFC0C6DC),
            tertiary = Color(0xFFEDD8FF),
            background = Color(0xFF0D0E12),
            onBackground = Color(0xFFCCCCCC),
            surface = Color(0xFF0D0E12),
            onSurface = Color(0xFFE3E2E9),
            surfaceVariant = Color(0xFF23262E),
            onSurfaceVariant = Color(0xFFC5C6D0),
            surfaceDim = Color(0xFF0D0E12),
            surfaceBright = Color(0xFF292C34),
            surfaceContainerLowest = Color(0xFF050507),
            surfaceContainerLow = Color(0xFF121318),
            surfaceContainer = Color(0xFF17191F),
            surfaceContainerHigh = Color(0xFF1D1F26),
            surfaceContainerHighest = Color(0xFF23262E),
            outline = Color(0xFF8F909A),
            outlineVariant = Color(0xFF44464F)
        )

        val trueBlack = dark.withTrueBlackDarkSurfaces()

        assertEquals(Color.Black, trueBlack.background)
        assertEquals(Color.Black, trueBlack.surface)
        assertEquals(Color.Black, trueBlack.surfaceDim)
        assertEquals(Color.Black, trueBlack.surfaceContainerLowest)
        assertEquals(dark.onSurface, trueBlack.onBackground)
        assertEquals(dark.onSurface, trueBlack.contentColorFor(trueBlack.surface))
        assertEquals(dark.onSurface, trueBlack.contentColorFor(trueBlack.background))

        assertEquals(dark.surfaceContainerLow, trueBlack.surfaceContainerLow)
        assertEquals(dark.surfaceContainer, trueBlack.surfaceContainer)
        assertEquals(dark.surfaceContainerHigh, trueBlack.surfaceContainerHigh)
        assertEquals(dark.surfaceContainerHighest, trueBlack.surfaceContainerHighest)
        assertEquals(dark.surfaceBright, trueBlack.surfaceBright)
        assertEquals(dark.surfaceVariant, trueBlack.surfaceVariant)
        assertEquals(dark.onSurfaceVariant, trueBlack.onSurfaceVariant)
        assertEquals(dark.primary, trueBlack.primary)
        assertEquals(dark.secondary, trueBlack.secondary)
        assertEquals(dark.tertiary, trueBlack.tertiary)
        assertEquals(dark.outline, trueBlack.outline)
        assertEquals(dark.outlineVariant, trueBlack.outlineVariant)
        assertEquals(dark.surfaceTint, trueBlack.surfaceTint)
        assertEquals(dark.primaryFixed, trueBlack.primaryFixed)
        assertEquals(dark.onPrimaryFixedVariant, trueBlack.onPrimaryFixedVariant)
    }

    @Test
    fun formattingIsCanonicalAndParsingAcceptsExactEntries() {
        assertTrue(AppThemePreferences().isDefault)
        assertEquals("", formatAppThemePreferences(AppThemePreferences(trueBlack = false)))
        val canonical = formatAppThemePreferences(AppThemePreferences(trueBlack = true))
        assertEquals("true_black=true\n", canonical)
        assertEquals(
            AppThemePreferences(trueBlack = true),
            readAppThemePreferences(ByteArrayInputStream(canonical.toByteArray(Charsets.UTF_8)))
        )
        assertEquals(
            AppThemePreferences(trueBlack = true),
            readAppThemePreferences(ByteArrayInputStream("true_black=true\r\n".toByteArray(Charsets.UTF_8)))
        )
        assertEquals(
            AppThemePreferences(trueBlack = false),
            readAppThemePreferences(ByteArrayInputStream("true_black=false\n".toByteArray(Charsets.UTF_8)))
        )
        assertEquals(
            AppThemePreferences(trueBlack = false),
            readAppThemePreferences(ByteArrayInputStream(ByteArray(0)))
        )
    }

    @Test
    fun malformedUnknownDuplicateAndOversizedThemeFilesAreRejected() {
        val malformedInputs = listOf(
            "true_black=1\n".toByteArray(),
            "true_black=TRUE\n".toByteArray(),
            "true_black=true \n".toByteArray(),
            "# comment\ntrue_black=true\n".toByteArray(),
            "unknown=true\n".toByteArray(),
            "true_black=true\ntrue_black=false\n".toByteArray(),
            "not_a_key_value_line\n".toByteArray(),
            byteArrayOf(0x00),
            byteArrayOf(0xFF.toByte(), 0xFE.toByte()),
            ByteArray(MAX_APP_THEME_BYTES + 1) { ' '.code.toByte() }
        )
        for (input in malformedInputs) {
            assertThrows(IllegalArgumentException::class.java) {
                readAppThemePreferences(ByteArrayInputStream(input))
            }
        }
    }

    @Test
    fun storeStateResolvesTrueBlackOnlyWhenReadyAndEnabled() {
        assertFalse(AppThemeStoreState.Loading.trueBlackOrDefault)
        assertFalse(AppThemeStoreState.LoadFailed.trueBlackOrDefault)
        assertFalse(AppThemeStoreState.Ready(AppThemePreferences(trueBlack = false)).trueBlackOrDefault)
        assertTrue(AppThemeStoreState.Ready(AppThemePreferences(trueBlack = true)).trueBlackOrDefault)
        assertTrue(
            AppThemeStoreState.Ready(AppThemePreferences(trueBlack = true), saveFailed = true)
                .trueBlackOrDefault
        )
    }
}
