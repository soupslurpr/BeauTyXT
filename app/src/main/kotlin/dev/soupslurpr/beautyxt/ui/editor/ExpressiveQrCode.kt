package dev.soupslurpr.beautyxt.ui.editor

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas as GraphicsCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.soupslurpr.beautyxt.R
import dev.soupslurpr.beautyxt.transfer.client.QrCodeGrid
import kotlin.math.abs
import kotlin.math.floor

private val QrCodeSurfacePadding = 12.dp
private const val QR_QUIET_ZONE_MODULES = 4
private const val QR_FINDER_MODULES = 7
private const val QR_ALIGNMENT_MODULES = 5
private const val QR_DATA_MODULE_INSET_FRACTION = 0.06f
private const val QR_DATA_MODULE_RADIUS_FRACTION = 0.28f
private const val QR_FINDER_CENTER_RADIUS_FRACTION = 0.72f
private const val QR_MIN_VERSION = 1
private const val QR_MAX_VERSION = 40
private const val QR_VERSION_DIMENSION_OFFSET = 17
private const val QR_VERSION_DIMENSION_STEP = 4
private const val QR_FIRST_ALIGNMENT_CENTER = 6
private const val QR_VERSION_WITH_ALIGNMENT_PATTERNS = 2
private const val QR_VERSION_WITH_VERSION_INFORMATION = 7
private const val QR_SPECIAL_ALIGNMENT_STEP_VERSION = 32
private const val QR_SPECIAL_ALIGNMENT_STEP = 26

/** Displays one Material 3 Expressive QR code using paired container colors. */
@Composable
internal fun ExpressiveQrCode(grid: QrCodeGrid, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.qr_code_description)
    val colors =
        scanSafeQrCodeColors(
            primaryContainer = MaterialTheme.colorScheme.primaryContainer,
            onPrimaryContainer = MaterialTheme.colorScheme.onPrimaryContainer
        )
    Surface(
        modifier =
            modifier.semantics {
                contentDescription = label
            },
        shape = MaterialTheme.shapes.extraLarge,
        color = colors.background,
        contentColor = colors.modules
    ) {
        Canvas(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(QrCodeSurfacePadding)
        ) {
            drawExpressiveQrGrid(
                grid = grid,
                moduleColor = colors.modules,
                backgroundColor = colors.background
            )
        }
    }
}

/** Contains one dynamically themed color pair in conventional QR polarity. */
internal data class QrCodeColors(val modules: Color, val background: Color)

/** Orders paired Material container colors into dark modules on a light background. */
internal fun scanSafeQrCodeColors(
    primaryContainer: Color,
    onPrimaryContainer: Color
): QrCodeColors {
    require(primaryContainer != Color.Unspecified) { "QR primary container color is unspecified" }
    require(onPrimaryContainer != Color.Unspecified) {
        "QR on-primary container color is unspecified"
    }
    return if (primaryContainer.luminance() <= onPrimaryContainer.luminance()) {
        QrCodeColors(modules = primaryContainer, background = onPrimaryContainer)
    } else {
        QrCodeColors(modules = onPrimaryContainer, background = primaryContainer)
    }
}

/** Renders one opaque, bounded export bitmap with the on-screen QR geometry. */
internal fun renderExpressiveQrCodeBitmap(grid: QrCodeGrid, colors: QrCodeColors): Bitmap {
    require(colors.modules != Color.Unspecified) { "module color for the QR image is unspecified" }
    require(colors.background != Color.Unspecified) {
        "background color for the QR image is unspecified"
    }
    require(colors.modules.luminance() < colors.background.luminance()) {
        "colors for the QR image do not use conventional polarity"
    }
    val sidePixels = EXPORTED_QR_CODE_SIDE_PIXELS
    val image =
        ImageBitmap(
            width = sidePixels,
            height = sidePixels,
            config = ImageBitmapConfig.Argb8888,
            hasAlpha = false
        )
    val size = Size(sidePixels.toFloat(), sidePixels.toFloat())
    CanvasDrawScope().draw(
        density = Density(1f),
        layoutDirection = LayoutDirection.Ltr,
        canvas = GraphicsCanvas(image),
        size = size
    ) {
        drawRect(color = colors.background)
        drawExpressiveQrGrid(
            grid = grid,
            moduleColor = colors.modules,
            backgroundColor = colors.background
        )
    }
    return image.asAndroidBitmap()
}

/** Identifies the machine-readable function modules in one standard QR matrix. */
internal class QrFunctionModules(private val dimension: Int) {
    private val version: Int
    private val alignmentCenters: IntArray

    init {
        require(dimension >= qrDimension(QR_MIN_VERSION)) {
            "QR dimension is below the standard minimum"
        }
        require(dimension <= qrDimension(QR_MAX_VERSION)) {
            "QR dimension exceeds the standard maximum"
        }
        require((dimension - QR_VERSION_DIMENSION_OFFSET) % QR_VERSION_DIMENSION_STEP == 0) {
            "QR dimension does not identify a standard version"
        }
        version = (dimension - QR_VERSION_DIMENSION_OFFSET) / QR_VERSION_DIMENSION_STEP
        alignmentCenters = alignmentPatternCenters(version = version, dimension = dimension)
    }

    /** Returns whether one coordinate belongs to a finder pattern. */
    fun isFinder(row: Int, column: Int): Boolean {
        requireCoordinates(row = row, column = column)
        val finalFinderStart = dimension - QR_FINDER_MODULES
        val topFinder =
            row < QR_FINDER_MODULES &&
                (column < QR_FINDER_MODULES || column >= finalFinderStart)
        val bottomFinder = row >= finalFinderStart && column < QR_FINDER_MODULES
        return topFinder || bottomFinder
    }

    /** Returns whether one coordinate should retain exact square geometry. */
    fun isStructural(row: Int, column: Int): Boolean {
        requireCoordinates(row = row, column = column)
        if (isFinder(row = row, column = column)) {
            return true
        }
        if (row == QR_FIRST_ALIGNMENT_CENTER || column == QR_FIRST_ALIGNMENT_CENTER) {
            return true
        }
        if (
            (row == 8 && (column <= 8 || column >= dimension - 8)) ||
            (column == 8 && (row <= 8 || row >= dimension - 8))
        ) {
            return true
        }
        if (
            version >= QR_VERSION_WITH_VERSION_INFORMATION &&
            (
                (row < 6 && column in dimension - 11..dimension - 9) ||
                    (column < 6 && row in dimension - 11..dimension - 9)
                )
        ) {
            return true
        }
        return isAlignmentPattern(row = row, column = column)
    }

    /** Returns the alignment centers for deterministic verification. */
    fun alignmentCenters(): IntArray = alignmentCenters.copyOf()

    /** Returns whether one coordinate belongs to a non-finder alignment pattern. */
    private fun isAlignmentPattern(row: Int, column: Int): Boolean {
        val finalFinderCenter = dimension - QR_FIRST_ALIGNMENT_CENTER - 1
        alignmentCenters.forEach { rowCenter ->
            alignmentCenters.forEach { columnCenter ->
                val usesFirstRow = rowCenter == QR_FIRST_ALIGNMENT_CENTER
                val usesFirstColumn = columnCenter == QR_FIRST_ALIGNMENT_CENTER
                val usesFinalRow = rowCenter == finalFinderCenter
                val usesFinalColumn = columnCenter == finalFinderCenter
                val overlapsTopFinder =
                    usesFirstRow && (usesFirstColumn || usesFinalColumn)
                val overlapsBottomFinder = usesFinalRow && usesFirstColumn
                if (
                    !overlapsTopFinder &&
                    !overlapsBottomFinder &&
                    abs(row - rowCenter) <= QR_ALIGNMENT_MODULES / 2 &&
                    abs(column - columnCenter) <= QR_ALIGNMENT_MODULES / 2
                ) {
                    return true
                }
            }
        }
        return false
    }

    /** Rejects coordinates outside this matrix before classification. */
    private fun requireCoordinates(row: Int, column: Int) {
        require(row in 0 until dimension) { "QR module row is outside the grid" }
        require(column in 0 until dimension) { "QR module column is outside the grid" }
    }
}

/** Draws one scan-safe expressive rendering of an exact QR module grid. */
private fun DrawScope.drawExpressiveQrGrid(
    grid: QrCodeGrid,
    moduleColor: Color,
    backgroundColor: Color
) {
    val totalModules = grid.dimension + QR_QUIET_ZONE_MODULES * 2
    val modulePixels = floor(size.minDimension / totalModules)
    if (modulePixels < 1f) {
        return
    }
    val codePixels = modulePixels * totalModules
    val codeOrigin =
        Offset(
            x = floor((size.width - codePixels) / 2f),
            y = floor((size.height - codePixels) / 2f)
        )
    val matrixOrigin =
        Offset(
            x = codeOrigin.x + QR_QUIET_ZONE_MODULES * modulePixels,
            y = codeOrigin.y + QR_QUIET_ZONE_MODULES * modulePixels
        )
    val functionModules = QrFunctionModules(grid.dimension)

    repeat(grid.dimension) { row ->
        repeat(grid.dimension) { column ->
            if (
                !functionModules.isFinder(row = row, column = column) &&
                grid.isDark(row = row, column = column)
            ) {
                if (functionModules.isStructural(row = row, column = column)) {
                    drawSquareModule(
                        row = row,
                        column = column,
                        matrixOrigin = matrixOrigin,
                        modulePixels = modulePixels,
                        color = moduleColor
                    )
                } else {
                    drawConnectedDataModule(
                        grid = grid,
                        row = row,
                        column = column,
                        matrixOrigin = matrixOrigin,
                        modulePixels = modulePixels,
                        color = moduleColor
                    )
                }
            }
        }
    }

    drawFinderEye(
        row = 0,
        column = 0,
        matrixOrigin = matrixOrigin,
        modulePixels = modulePixels,
        moduleColor = moduleColor,
        backgroundColor = backgroundColor
    )
    drawFinderEye(
        row = 0,
        column = grid.dimension - QR_FINDER_MODULES,
        matrixOrigin = matrixOrigin,
        modulePixels = modulePixels,
        moduleColor = moduleColor,
        backgroundColor = backgroundColor
    )
    drawFinderEye(
        row = grid.dimension - QR_FINDER_MODULES,
        column = 0,
        matrixOrigin = matrixOrigin,
        modulePixels = modulePixels,
        moduleColor = moduleColor,
        backgroundColor = backgroundColor
    )
}

/** Draws one unmodified square QR function module. */
private fun DrawScope.drawSquareModule(
    row: Int,
    column: Int,
    matrixOrigin: Offset,
    modulePixels: Float,
    color: Color
) {
    drawRect(
        color = color,
        topLeft = moduleOffset(row, column, matrixOrigin, modulePixels),
        size = Size(modulePixels, modulePixels)
    )
}

/** Draws one softly rounded data module connected to every dark neighbor. */
private fun DrawScope.drawConnectedDataModule(
    grid: QrCodeGrid,
    row: Int,
    column: Int,
    matrixOrigin: Offset,
    modulePixels: Float,
    color: Color
) {
    val origin = moduleOffset(row, column, matrixOrigin, modulePixels)
    val inset = modulePixels * QR_DATA_MODULE_INSET_FRACTION
    val insetSize = modulePixels - inset * 2f
    drawRoundRect(
        color = color,
        topLeft = Offset(origin.x + inset, origin.y + inset),
        size = Size(insetSize, insetSize),
        cornerRadius = CornerRadius(modulePixels * QR_DATA_MODULE_RADIUS_FRACTION)
    )
    val halfModule = modulePixels / 2f
    if (grid.hasDarkModule(row = row, column = column - 1)) {
        drawRect(
            color = color,
            topLeft = Offset(origin.x, origin.y + inset),
            size = Size(halfModule, insetSize)
        )
    }
    if (grid.hasDarkModule(row = row, column = column + 1)) {
        drawRect(
            color = color,
            topLeft = Offset(origin.x + halfModule, origin.y + inset),
            size = Size(halfModule, insetSize)
        )
    }
    if (grid.hasDarkModule(row = row - 1, column = column)) {
        drawRect(
            color = color,
            topLeft = Offset(origin.x + inset, origin.y),
            size = Size(insetSize, halfModule)
        )
    }
    if (grid.hasDarkModule(row = row + 1, column = column)) {
        drawRect(
            color = color,
            topLeft = Offset(origin.x + inset, origin.y + halfModule),
            size = Size(insetSize, halfModule)
        )
    }
}

/** Keeps finder boundaries exact so dense grids can be located without corner bias. */
private fun DrawScope.drawFinderEye(
    row: Int,
    column: Int,
    matrixOrigin: Offset,
    modulePixels: Float,
    moduleColor: Color,
    backgroundColor: Color
) {
    val origin = moduleOffset(row, column, matrixOrigin, modulePixels)
    val outerSize = QR_FINDER_MODULES * modulePixels
    drawRect(
        color = moduleColor,
        topLeft = origin,
        size = Size(outerSize, outerSize)
    )
    val innerSize = (QR_FINDER_MODULES - 2) * modulePixels
    drawRect(
        color = backgroundColor,
        topLeft = Offset(origin.x + modulePixels, origin.y + modulePixels),
        size = Size(innerSize, innerSize)
    )
    val centerSize = (QR_FINDER_MODULES - 4) * modulePixels
    drawRoundRect(
        color = moduleColor,
        topLeft = Offset(origin.x + modulePixels * 2f, origin.y + modulePixels * 2f),
        size = Size(centerSize, centerSize),
        cornerRadius = CornerRadius(modulePixels * QR_FINDER_CENTER_RADIUS_FRACTION)
    )
}

/** Returns the pixel origin for one module coordinate. */
private fun moduleOffset(row: Int, column: Int, matrixOrigin: Offset, modulePixels: Float): Offset =
    Offset(
        x = matrixOrigin.x + column * modulePixels,
        y = matrixOrigin.y + row * modulePixels
    )

/** Returns whether a bounded coordinate contains a dark module. */
private fun QrCodeGrid.hasDarkModule(row: Int, column: Int): Boolean = row in 0 until dimension &&
    column in 0 until dimension &&
    isDark(row = row, column = column)

/** Returns one standard QR dimension for the supplied version. */
private fun qrDimension(version: Int): Int =
    QR_VERSION_DIMENSION_OFFSET + QR_VERSION_DIMENSION_STEP * version

/** Computes the standard alignment-pattern centers for one QR version. */
private fun alignmentPatternCenters(version: Int, dimension: Int): IntArray {
    if (version < QR_VERSION_WITH_ALIGNMENT_PATTERNS) {
        return IntArray(0)
    }
    val centerCount = version / 7 + 2
    val step =
        if (version == QR_SPECIAL_ALIGNMENT_STEP_VERSION) {
            QR_SPECIAL_ALIGNMENT_STEP
        } else {
            (version * 4 + centerCount * 2 + 1) / (centerCount * 2 - 2) * 2
        }
    return IntArray(centerCount) { index ->
        if (index == 0) {
            QR_FIRST_ALIGNMENT_CENTER
        } else {
            dimension -
                QR_FINDER_MODULES -
                (centerCount - 1 - index) * step
        }
    }
}
