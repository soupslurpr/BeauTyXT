package dev.soupslurpr.beautyxt.printing

private const val RED_WEIGHT = 77
private const val GREEN_WEIGHT = 150
private const val BLUE_WEIGHT = 29
private const val WEIGHT_SHIFT = 8
private const val ROUNDING_OFFSET = 128
private const val CHANNEL_MASK = 0xff
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8

/** Converts opaque sRGB pixels to eight-bit grayscale without allocating another page. */
internal fun printGrayscaleRow(colors: IntArray, grayscale: ByteArray, width: Int) {
    require(width in 0..minOf(colors.size, grayscale.size)) {
        "print row width exceeds its buffers"
    }
    for (column in 0 until width) {
        val color = colors[column]
        val red = color ushr RED_SHIFT and CHANNEL_MASK
        val green = color ushr GREEN_SHIFT and CHANNEL_MASK
        val blue = color and CHANNEL_MASK
        grayscale[column] =
            (
                (red * RED_WEIGHT + green * GREEN_WEIGHT + blue * BLUE_WEIGHT + ROUNDING_OFFSET)
                    ushr WEIGHT_SHIFT
                ).toByte()
    }
}
