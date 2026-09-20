package dev.soupslurpr.beautyxt.printing

import android.print.PrintAttributes
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val MILS_PER_INCH = 1_000.0
private const val POINTS_PER_INCH = 72.0
private const val MAXIMUM_RASTER_DPI = 216
private const val MINIMUM_RASTER_DPI = 72
private const val MAXIMUM_RASTER_DIMENSION_PIXELS = 8_192
private const val MAXIMUM_RASTER_PIXELS = 8_000_000L
private const val HEADER_HEIGHT_POINTS = 18.0
private const val FOOTER_HEIGHT_POINTS = 15.0
private const val HEADER_TEXT_SIZE_POINTS = 9.0
private const val FOOTER_TEXT_SIZE_POINTS = 8.0

/**
 * Describes the bounded Android layout grid and its physical PDF placement.
 * The grid does not allocate a page bitmap: text and shapes remain vector, while only
 * color/synthetic or clipped glyph fallbacks use its bounded sampling density.
 */
internal data class PrintRasterLayout(
    val pageWidthPoints: Int,
    val pageHeightPoints: Int,
    val contentLeftPoints: Int,
    val contentBottomPoints: Int,
    val contentWidthPoints: Int,
    val contentHeightPoints: Int,
    val rasterDpi: Int,
    val rasterWidthPixels: Int,
    val rasterHeightPixels: Int,
    val bodyLeftPixels: Int,
    val bodyTopPixels: Int,
    val bodyRightPixels: Int,
    val bodyBottomPixels: Int,
    val headerBaselinePixels: Float,
    val headerDividerPixels: Float,
    val footerDividerPixels: Float,
    val footerBaselinePixels: Float,
    val bodyTextSizePixels: Float,
    val headerTextSizePixels: Float,
    val footerTextSizePixels: Float,
    val monochrome: Boolean = false
) {
    init {
        require(pageWidthPoints > 0 && pageHeightPoints > 0) {
            "print page dimensions must be positive"
        }
        require(contentWidthPoints > 0 && contentHeightPoints > 0) {
            "print content dimensions must be positive"
        }
        require(rasterDpi >= MINIMUM_RASTER_DPI) {
            "print raster density is too low"
        }
        require(rasterWidthPixels > 0 && rasterHeightPixels > 0) {
            "print raster dimensions must be positive"
        }
        require(
            rasterWidthPixels <= MAXIMUM_RASTER_DIMENSION_PIXELS &&
                rasterHeightPixels <= MAXIMUM_RASTER_DIMENSION_PIXELS &&
                rasterWidthPixels.toLong() * rasterHeightPixels <= MAXIMUM_RASTER_PIXELS
        ) {
            "print raster exceeds its memory bound"
        }
        require(bodyLeftPixels < bodyRightPixels && bodyTopPixels < bodyBottomPixels) {
            "print body dimensions must be positive"
        }
    }

    /** Returns the bounded body width available to Android text layout. */
    val bodyWidthPixels: Int
        get() = bodyRightPixels - bodyLeftPixels
}

/** Returns one memory-bounded page raster for complete Android print attributes. */
internal fun printRasterLayout(
    attributes: PrintAttributes,
    settings: PrintSettings = defaultPrintSettings(formattedMarkdown = false)
): PrintRasterLayout {
    val mediaSize =
        requireNotNull(attributes.mediaSize) {
            "print media size is unavailable"
        }
    val margins =
        requireNotNull(attributes.minMargins) {
            "print margins are unavailable"
        }
    val pageWidthPoints = milsToPoints(mediaSize.widthMils)
    val pageHeightPoints = milsToPoints(mediaSize.heightMils)
    val contentLeftPoints = milsToPoints(max(margins.leftMils, settings.margins.leftMils))
    val contentTopPoints = milsToPoints(max(margins.topMils, settings.margins.topMils))
    val contentRightPoints =
        pageWidthPoints - milsToPoints(max(margins.rightMils, settings.margins.rightMils))
    val contentBottomFromTopPoints =
        pageHeightPoints - milsToPoints(max(margins.bottomMils, settings.margins.bottomMils))
    val contentWidthPoints = contentRightPoints - contentLeftPoints
    val contentHeightPoints = contentBottomFromTopPoints - contentTopPoints
    require(contentWidthPoints > 0 && contentHeightPoints > 0) {
        "print margins leave no content area"
    }

    val requestedDpi =
        attributes.resolution?.let { resolution ->
            min(resolution.horizontalDpi, resolution.verticalDpi)
        } ?: MAXIMUM_RASTER_DPI
    require(requestedDpi > 0) { "print resolution must be positive" }
    val rasterDpi =
        boundedRasterDpi(
            requestedDpi = requestedDpi,
            widthPoints = contentWidthPoints,
            heightPoints = contentHeightPoints
        )
    val rasterWidthPixels = pointsToPixels(contentWidthPoints.toDouble(), rasterDpi)
    val rasterHeightPixels = pointsToPixels(contentHeightPoints.toDouble(), rasterDpi)
    val rasterPixels = Math.multiplyExact(rasterWidthPixels.toLong(), rasterHeightPixels.toLong())
    require(rasterPixels <= MAXIMUM_RASTER_PIXELS) {
        "print raster exceeds its pixel limit"
    }

    val headerHeightPixels =
        if (settings.showFileName) pointsToPixels(HEADER_HEIGHT_POINTS, rasterDpi) else 0
    val footerHeightPixels =
        if (settings.showPageNumbers) pointsToPixels(FOOTER_HEIGHT_POINTS, rasterDpi) else 0
    val bodyLeftPixels = 0
    val bodyRightPixels = rasterWidthPixels
    val bodyTopPixels = headerHeightPixels
    val bodyBottomPixels = rasterHeightPixels - footerHeightPixels
    require(bodyRightPixels - bodyLeftPixels >= pointsToPixels(POINTS_PER_INCH, rasterDpi)) {
        "print content is too narrow"
    }
    require(bodyBottomPixels - bodyTopPixels >= pointsToPixels(POINTS_PER_INCH, rasterDpi)) {
        "print content is too short"
    }

    return PrintRasterLayout(
        pageWidthPoints = pageWidthPoints,
        pageHeightPoints = pageHeightPoints,
        contentLeftPoints = contentLeftPoints,
        contentBottomPoints = pageHeightPoints - contentBottomFromTopPoints,
        contentWidthPoints = contentWidthPoints,
        contentHeightPoints = contentHeightPoints,
        rasterDpi = rasterDpi,
        rasterWidthPixels = rasterWidthPixels,
        rasterHeightPixels = rasterHeightPixels,
        bodyLeftPixels = bodyLeftPixels,
        bodyTopPixels = bodyTopPixels,
        bodyRightPixels = bodyRightPixels,
        bodyBottomPixels = bodyBottomPixels,
        headerBaselinePixels =
            pointsToPixels(HEADER_TEXT_SIZE_POINTS, rasterDpi).toFloat(),
        headerDividerPixels = (bodyTopPixels - pointsToPixels(4.0, rasterDpi)).toFloat(),
        footerDividerPixels = (bodyBottomPixels + pointsToPixels(4.0, rasterDpi)).toFloat(),
        footerBaselinePixels =
            (rasterHeightPixels - pointsToPixels(2.0, rasterDpi)).toFloat(),
        bodyTextSizePixels =
            pointsToPixels(settings.fontSizePoints.toDouble(), rasterDpi).toFloat(),
        headerTextSizePixels = pointsToPixels(HEADER_TEXT_SIZE_POINTS, rasterDpi).toFloat(),
        footerTextSizePixels = pointsToPixels(FOOTER_TEXT_SIZE_POINTS, rasterDpi).toFloat(),
        monochrome = attributes.colorMode == PrintAttributes.COLOR_MODE_MONOCHROME
    )
}

/** Returns a uniform raster density within both memory and dimension limits. */
private fun boundedRasterDpi(requestedDpi: Int, widthPoints: Int, heightPoints: Int): Int {
    require(widthPoints > 0 && heightPoints > 0) {
        "print point dimensions must be positive"
    }
    val widthInches = widthPoints / POINTS_PER_INCH
    val heightInches = heightPoints / POINTS_PER_INCH
    val pixelLimitedDpi =
        sqrt(MAXIMUM_RASTER_PIXELS.toDouble() / (widthInches * heightInches))
    val dimensionLimitedDpi =
        min(
            MAXIMUM_RASTER_DIMENSION_PIXELS / widthInches,
            MAXIMUM_RASTER_DIMENSION_PIXELS / heightInches
        )
    val bounded =
        floor(
            minOf(
                requestedDpi.toDouble(),
                MAXIMUM_RASTER_DPI.toDouble(),
                pixelLimitedDpi,
                dimensionLimitedDpi
            )
        ).toInt()
    require(bounded >= MINIMUM_RASTER_DPI) {
        "print media size is too large for safe rasterization"
    }
    return bounded
}

/** Converts one nonnegative physical mil measurement to PDF points. */
private fun milsToPoints(mils: Int): Int {
    require(mils >= 0) { "print mil measurement must be nonnegative" }
    return (mils / MILS_PER_INCH * POINTS_PER_INCH).roundToInt()
}

/** Converts one positive PDF point measurement to raster pixels. */
private fun pointsToPixels(points: Double, dpi: Int): Int {
    require(points >= 0.0) { "print point measurement must be nonnegative" }
    require(dpi > 0) { "print raster density must be positive" }
    return (points / POINTS_PER_INCH * dpi).roundToInt()
}
