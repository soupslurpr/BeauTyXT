package dev.soupslurpr.beautyxt.printing

import kotlin.math.roundToLong

private const val PDF_DECIMAL_SCALE = 1_000L
private const val MAXIMUM_PDF_COORDINATE = 1_000_000f
private const val HEX_DIGITS = "0123456789ABCDEF"

/** Encodes UTF-16 code units as a PDF hexadecimal string without interpolating document syntax. */
internal fun pdfUnicodeHex(text: String): String = buildString(text.length * 4) {
    for (character in text) {
        append(HEX_DIGITS[character.code ushr 12])
        append(HEX_DIGITS[(character.code ushr 8) and 0xf])
        append(HEX_DIGITS[(character.code ushr 4) and 0xf])
        append(HEX_DIGITS[character.code and 0xf])
    }
}

/** Formats one finite PDF coordinate to a millipoint without locale or exponent notation. */
internal fun pdfCoordinate(value: Float): String = pdfDecimal(value, PDF_DECIMAL_SCALE)

/** Matrix coefficients need finer precision because their error scales across the whole page. */
internal fun pdfMatrixComponent(value: Float): String = pdfDecimal(value, 1_000_000L)

private fun pdfDecimal(value: Float, scale: Long): String {
    require(value.isFinite() && value in -MAXIMUM_PDF_COORDINATE..MAXIMUM_PDF_COORDINATE) {
        "PDF coordinate is outside its finite range"
    }
    val scaled = (value.toDouble() * scale).roundToLong()
    val magnitude = kotlin.math.abs(scaled)
    val fraction = magnitude % scale
    return buildString {
        if (scaled < 0L) append('-')
        append(magnitude / scale)
        if (fraction != 0L) {
            append('.')
            append(fraction.toString().padStart(scale.toString().length - 1, '0').trimEnd('0'))
        }
    }
}
