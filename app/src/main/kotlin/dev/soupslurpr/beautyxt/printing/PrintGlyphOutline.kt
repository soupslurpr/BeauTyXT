package dev.soupslurpr.beautyxt.printing

import android.graphics.Path

internal const val PRINT_OUTLINE_EM_SIZE = 1_000f
internal const val MAXIMUM_PRINT_OUTLINE_COMPONENTS = 32 * 1_024

/** Decodes the bounded native command stream without accepting partial or nonfinite paths. */
internal fun printGlyphPath(components: FloatArray): Path {
    require(components.size <= MAXIMUM_PRINT_OUTLINE_COMPONENTS)
    require(components.all { it.isFinite() && kotlin.math.abs(it) <= 1_000_000f })
    val path = Path()
    var index = 0
    fun coordinate(): Float {
        require(index < components.size) { "truncated print glyph outline" }
        return components[index++]
    }
    while (index < components.size) {
        when (coordinate()) {
            0f -> path.moveTo(coordinate(), coordinate())

            1f -> path.lineTo(coordinate(), coordinate())

            2f -> path.quadTo(coordinate(), coordinate(), coordinate(), coordinate())

            3f -> path.cubicTo(
                coordinate(),
                coordinate(),
                coordinate(),
                coordinate(),
                coordinate(),
                coordinate()
            )

            4f -> path.close()

            else -> error("unknown print glyph command")
        }
    }
    return path
}
