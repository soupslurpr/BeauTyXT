package dev.soupslurpr.beautyxt.illustration

/** Loaded only by the isolated math service. Fonts are bundled in every build variant. */
internal object NativeMathRenderer {
    init {
        System.loadLibrary("beautyxt_math_jni")
    }

    /** The first little-endian int is a status; successful results then carry a drawing packet. */
    @JvmStatic
    external fun render(source: ByteArray, display: Boolean): ByteArray
}
