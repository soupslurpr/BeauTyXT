package dev.soupslurpr.beautyxt.ui.editor

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private val InvalidActionRowWidth = (-1).dp
private val WideActionRowWidth = 600.dp
private val CompactChromeBoundaryWidth = 480.dp
private val NarrowChromeWidth = 479.dp
private val StandardPhoneChromeWidth = 411.dp
private val ExpandedEditorHeightBoundary = 480.dp
private val ShortExpandedEditorHeight = 479.dp
private val QrDialogBoundaryHeight = 600.dp
private val ShortQrDialogHeight = 599.dp
private val ExpectedStandardQrWidth = 360.dp
private val ExpectedCompactQrWidth = 160.dp
private val InvalidEditorHeight = (-1).dp
private val TallRecoveryViewportHeight = 800.dp
private val MediumRecoveryViewportHeight = 400.dp
private val ConstrainedRecoveryViewportHeight = 200.dp
private val MinimumRecoveryViewportHeight = 128.dp
private val ShorterThanMinimumRecoveryViewportHeight = 64.dp
private val ExpectedMaximumRecoveryHeight = 320.dp
private val ExpectedProportionalRecoveryHeight = 180.dp
private val ExpectedMinimumRecoveryHeight = 96.dp
private val NarrowLineNumberLabelWidth = 20.dp
private val WideLineNumberLabelWidth = 72.dp
private val ExpectedMinimumLineNumberGutterWidth = 40.dp
private val ExpectedWideLineNumberGutterWidth = 80.dp
private val ExactGutterFractionWidth = 320.dp
private val BelowGutterFractionWidth = 319.dp
private val ExactDocumentWidthBoundary = 200.dp
private val BelowDocumentWidthBoundary = 199.dp
private val BoundaryLineNumberGutterWidth = 80.dp
private val MinimumLineNumberGutterTestWidth = 40.dp
private const val ACTION_ROW_FONT_SCALE_BOUNDARY = 1.3f
private const val LARGE_ACTION_ROW_FONT_SCALE = 1.31f
private const val STANDARD_ACTION_ROW_FONT_SCALE = 1f
private const val INVALID_ACTION_ROW_FONT_SCALE = 0f
private const val ACCESSIBILITY_FONT_SCALE = 2f
private const val VISIBLE_IME_INSET_PIXELS = 640
private const val HIDING_IME_INSET_PIXELS = 320
private const val HIDDEN_IME_INSET_PIXELS = 0
private const val LANDSCAPE_WINDOW_WIDTH_PIXELS = 2_424
private const val LANDSCAPE_WINDOW_HEIGHT_PIXELS = 1_080
private const val PORTRAIT_WINDOW_WIDTH_PIXELS = 1_080
private const val PORTRAIT_WINDOW_HEIGHT_PIXELS = 2_424
private const val INVALID_WINDOW_DIMENSION_PIXELS = -1

/** Verifies deterministic responsive decisions for the editor layout. */
class DocumentEditorLayoutTest {
    /** Verifies invalid layout inputs fail before reaching composition. */
    @Test
    fun rejectsInvalidLayoutInputs() {
        assertThrows(IllegalArgumentException::class.java) {
            usesCompactEditorChromeActions(
                availableWidth = InvalidActionRowWidth,
                fontScale = STANDARD_ACTION_ROW_FONT_SCALE
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            usesCompactEditorChromeActions(
                availableWidth = CompactChromeBoundaryWidth,
                fontScale = INVALID_ACTION_ROW_FONT_SCALE
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            usesExpandedEditorTopBar(INVALID_ACTION_ROW_FONT_SCALE, TallRecoveryViewportHeight)
        }
        assertThrows(IllegalArgumentException::class.java) {
            usesExpandedEditorTopBar(STANDARD_ACTION_ROW_FONT_SCALE, InvalidEditorHeight)
        }
        assertThrows(IllegalArgumentException::class.java) {
            qrCodeDialogMaximumWidth(InvalidEditorHeight)
        }
    }

    /** Verifies short windows keep the complete QR code inside the scroll viewport. */
    @Test
    fun compactsQrCodesBelowTheDialogHeightBoundary() {
        assertEquals(
            ExpectedStandardQrWidth,
            qrCodeDialogMaximumWidth(QrDialogBoundaryHeight)
        )
        assertEquals(
            ExpectedCompactQrWidth,
            qrCodeDialogMaximumWidth(ShortQrDialogHeight)
        )
    }

    /** Verifies direct chrome actions compact only below the exact width boundary. */
    @Test
    fun compactsChromeActionsBelowTheWidthBoundary() {
        assertFalse(
            usesCompactEditorChromeActions(
                availableWidth = CompactChromeBoundaryWidth,
                fontScale = ACTION_ROW_FONT_SCALE_BOUNDARY
            )
        )
        assertTrue(
            usesCompactEditorChromeActions(
                availableWidth = NarrowChromeWidth,
                fontScale = STANDARD_ACTION_ROW_FONT_SCALE
            )
        )
        assertTrue(
            usesCompactEditorChromeActions(
                availableWidth = StandardPhoneChromeWidth,
                fontScale = STANDARD_ACTION_ROW_FONT_SCALE
            )
        )
    }

    /** Verifies large text compacts direct chrome actions at wide widths. */
    @Test
    fun compactsChromeActionsAboveTheFontScaleBoundary() {
        assertTrue(
            usesCompactEditorChromeActions(
                availableWidth = WideActionRowWidth,
                fontScale = LARGE_ACTION_ROW_FONT_SCALE
            )
        )
    }

    /** Verifies large text receives a full-width multiline operation status. */
    @Test
    fun expandsTheEditorTopBarAboveTheFontScaleBoundary() {
        assertFalse(
            usesExpandedEditorTopBar(ACTION_ROW_FONT_SCALE_BOUNDARY, TallRecoveryViewportHeight)
        )
        assertTrue(
            usesExpandedEditorTopBar(LARGE_ACTION_ROW_FONT_SCALE, TallRecoveryViewportHeight)
        )
    }

    /** Reserves document space instead of expanding chrome in short windows. */
    @Test
    fun keepsLargeTextChromeCompactBelowTheHeightBoundary() {
        assertFalse(
            usesExpandedEditorTopBar(ACCESSIBILITY_FONT_SCALE, ShortExpandedEditorHeight)
        )
        assertTrue(
            usesExpandedEditorTopBar(ACCESSIBILITY_FONT_SCALE, ExpandedEditorHeightBoundary)
        )
        assertFalse(
            usesExpandedEditorTopBar(ACCESSIBILITY_FONT_SCALE, ConstrainedRecoveryViewportHeight)
        )
    }

    /** Verifies only an embedded editor applies custom root-back motion. */
    @Test
    fun appliesMotionOnlyToEmbeddedDocumentBack() {
        assertTrue(
            usesEditorPredictiveBackMotion(
                closesDocumentTask = false,
                backClosesDocument = true
            )
        )
        assertFalse(
            usesEditorPredictiveBackMotion(
                closesDocumentTask = true,
                backClosesDocument = true
            )
        )
        assertFalse(
            usesEditorPredictiveBackMotion(
                closesDocumentTask = false,
                backClosesDocument = false
            )
        )
    }

    /** Verifies only a stationary visible IME reserves one Back event. */
    @Test
    fun reservesOnlyTheFirstBackForAStationaryIme() {
        assertTrue(
            reservesBackForIme(
                isImeVisible = true,
                imeBottomInsetPixels = VISIBLE_IME_INSET_PIXELS,
                imeTargetBottomInsetPixels = VISIBLE_IME_INSET_PIXELS,
                imeDismissalRequested = false
            )
        )
        assertFalse(
            reservesBackForIme(
                isImeVisible = false,
                imeBottomInsetPixels = HIDDEN_IME_INSET_PIXELS,
                imeTargetBottomInsetPixels = HIDDEN_IME_INSET_PIXELS,
                imeDismissalRequested = false
            )
        )
        assertFalse(
            reservesBackForIme(
                isImeVisible = false,
                imeBottomInsetPixels = VISIBLE_IME_INSET_PIXELS,
                imeTargetBottomInsetPixels = VISIBLE_IME_INSET_PIXELS,
                imeDismissalRequested = false
            )
        )
        assertFalse(
            reservesBackForIme(
                isImeVisible = true,
                imeBottomInsetPixels = HIDING_IME_INSET_PIXELS,
                imeTargetBottomInsetPixels = HIDDEN_IME_INSET_PIXELS,
                imeDismissalRequested = false
            )
        )
        assertFalse(
            reservesBackForIme(
                isImeVisible = true,
                imeBottomInsetPixels = VISIBLE_IME_INSET_PIXELS,
                imeTargetBottomInsetPixels = VISIBLE_IME_INSET_PIXELS,
                imeDismissalRequested = true
            )
        )
    }

    /** Prevents a second rapid Back from waiting for IME inset animation state. */
    @Test
    fun claimsOnlyOneRapidBackBeforeTheImeInsetsSettle() {
        val reservation = ImeBackReservation()

        assertTrue(
            reservation.tryClaim(
                isImeVisible = true,
                imeBottomInsetPixels = VISIBLE_IME_INSET_PIXELS,
                imeTargetBottomInsetPixels = VISIBLE_IME_INSET_PIXELS
            )
        )
        assertFalse(
            reservation.tryClaim(
                isImeVisible = true,
                imeBottomInsetPixels = VISIBLE_IME_INSET_PIXELS,
                imeTargetBottomInsetPixels = VISIBLE_IME_INSET_PIXELS
            )
        )

        reservation.release()

        assertTrue(
            reservation.tryClaim(
                isImeVisible = true,
                imeBottomInsetPixels = VISIBLE_IME_INSET_PIXELS,
                imeTargetBottomInsetPixels = VISIBLE_IME_INSET_PIXELS
            )
        )
    }

    /** Verifies a visible landscape IME reserves the screen for focused editing. */
    @Test
    fun usesFocusedEditingWithALandscapeIme() {
        assertTrue(
            usesImeFocusLayout(
                imeBottomInsetPixels = VISIBLE_IME_INSET_PIXELS,
                windowWidthPixels = LANDSCAPE_WINDOW_WIDTH_PIXELS,
                windowHeightPixels = LANDSCAPE_WINDOW_HEIGHT_PIXELS
            )
        )
        assertFalse(
            usesImeFocusLayout(
                imeBottomInsetPixels = HIDDEN_IME_INSET_PIXELS,
                windowWidthPixels = LANDSCAPE_WINDOW_WIDTH_PIXELS,
                windowHeightPixels = LANDSCAPE_WINDOW_HEIGHT_PIXELS
            )
        )
        assertFalse(
            usesImeFocusLayout(
                imeBottomInsetPixels = VISIBLE_IME_INSET_PIXELS,
                windowWidthPixels = PORTRAIT_WINDOW_WIDTH_PIXELS,
                windowHeightPixels = PORTRAIT_WINDOW_HEIGHT_PIXELS
            )
        )
    }

    /** Verifies invalid IME layout inputs fail before composition. */
    @Test
    fun rejectsInvalidImeLayoutInputs() {
        assertThrows(IllegalArgumentException::class.java) {
            reservesBackForIme(
                isImeVisible = false,
                imeBottomInsetPixels = INVALID_WINDOW_DIMENSION_PIXELS,
                imeTargetBottomInsetPixels = HIDDEN_IME_INSET_PIXELS,
                imeDismissalRequested = false
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            reservesBackForIme(
                isImeVisible = false,
                imeBottomInsetPixels = HIDDEN_IME_INSET_PIXELS,
                imeTargetBottomInsetPixels = INVALID_WINDOW_DIMENSION_PIXELS,
                imeDismissalRequested = false
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            usesImeFocusLayout(
                imeBottomInsetPixels = INVALID_WINDOW_DIMENSION_PIXELS,
                windowWidthPixels = LANDSCAPE_WINDOW_WIDTH_PIXELS,
                windowHeightPixels = LANDSCAPE_WINDOW_HEIGHT_PIXELS
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            usesImeFocusLayout(
                imeBottomInsetPixels = VISIBLE_IME_INSET_PIXELS,
                windowWidthPixels = INVALID_WINDOW_DIMENSION_PIXELS,
                windowHeightPixels = LANDSCAPE_WINDOW_HEIGHT_PIXELS
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            usesImeFocusLayout(
                imeBottomInsetPixels = VISIBLE_IME_INSET_PIXELS,
                windowWidthPixels = LANDSCAPE_WINDOW_WIDTH_PIXELS,
                windowHeightPixels = INVALID_WINDOW_DIMENSION_PIXELS
            )
        }
    }

    /** Verifies recovery messages cannot consume an unbounded tall viewport. */
    @Test
    fun capsRecoveryMessagesInTallViewports() {
        assertEquals(
            ExpectedMaximumRecoveryHeight,
            recoveryPanelMaxHeight(TallRecoveryViewportHeight)
        )
    }

    /** Verifies recovery messages remain proportional in medium viewports. */
    @Test
    fun scalesRecoveryMessagesInMediumViewports() {
        assertEquals(
            ExpectedProportionalRecoveryHeight,
            recoveryPanelMaxHeight(MediumRecoveryViewportHeight)
        )
    }

    /** Verifies constrained recovery messages retain a reachable action region. */
    @Test
    fun preservesMinimumRecoveryHeightInConstrainedViewports() {
        assertEquals(
            ExpectedMinimumRecoveryHeight,
            recoveryPanelMaxHeight(ConstrainedRecoveryViewportHeight)
        )
        assertEquals(
            ExpectedMinimumRecoveryHeight,
            recoveryPanelMaxHeight(MinimumRecoveryViewportHeight)
        )
        assertEquals(
            ShorterThanMinimumRecoveryViewportHeight,
            recoveryPanelMaxHeight(ShorterThanMinimumRecoveryViewportHeight)
        )
        assertEquals(0.dp, recoveryPanelMaxHeight(0.dp))
    }

    /** Verifies invalid recovery heights fail before reaching composition. */
    @Test
    fun rejectsInvalidRecoveryHeight() {
        assertThrows(IllegalArgumentException::class.java) {
            recoveryPanelMaxHeight(InvalidEditorHeight)
        }
    }

    /** Verifies measured labels produce one stable padded gutter width. */
    @Test
    fun sizesLineNumberGuttersFromTheWidestLabel() {
        assertEquals(
            ExpectedMinimumLineNumberGutterWidth,
            lineNumberGutterWidth(NarrowLineNumberLabelWidth)
        )
        assertEquals(
            ExpectedWideLineNumberGutterWidth,
            lineNumberGutterWidth(WideLineNumberLabelWidth)
        )
    }

    /** Verifies the gutter remains inline at its exact width fraction boundary. */
    @Test
    fun keepsLineNumberGutterAtTheFractionBoundary() {
        assertTrue(
            usesLineNumberGutter(
                availableWidth = ExactGutterFractionWidth,
                gutterWidth = BoundaryLineNumberGutterWidth
            )
        )
        assertFalse(
            usesLineNumberGutter(
                availableWidth = BelowGutterFractionWidth,
                gutterWidth = BoundaryLineNumberGutterWidth
            )
        )
    }

    /** Verifies the gutter moves above text before document width becomes cramped. */
    @Test
    fun movesLineNumberAboveCrampedDocumentText() {
        assertTrue(
            usesLineNumberGutter(
                availableWidth = ExactDocumentWidthBoundary,
                gutterWidth = MinimumLineNumberGutterTestWidth
            )
        )
        assertFalse(
            usesLineNumberGutter(
                availableWidth = BelowDocumentWidthBoundary,
                gutterWidth = MinimumLineNumberGutterTestWidth
            )
        )
    }

    /** Verifies invalid line-number layout inputs fail before composition. */
    @Test
    fun rejectsInvalidLineNumberLayoutInputs() {
        assertThrows(IllegalArgumentException::class.java) {
            lineNumberGutterWidth(InvalidActionRowWidth)
        }
        assertThrows(IllegalArgumentException::class.java) {
            usesLineNumberGutter(
                availableWidth = InvalidActionRowWidth,
                gutterWidth = MinimumLineNumberGutterTestWidth
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            usesLineNumberGutter(
                availableWidth = ExactDocumentWidthBoundary,
                gutterWidth = InvalidActionRowWidth
            )
        }
    }
}
