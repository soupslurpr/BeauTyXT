package dev.soupslurpr.beautyxt.illustration

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction

/** Shared limits for isolated illustrations; these do not replace the document's limits. */
internal object IllustrationLimits {
    const val MAX_PACKET_BYTES = 512 * 1024
    const val MAX_PATHS = 2_048
    const val MAX_COMPONENTS = 100_000
    const val MAX_COORDINATE = 8_192f
    const val MAX_MATH_SOURCE_BYTES = 4 * 1024
    const val MAX_DIAGRAM_SOURCE_BYTES = 16 * 1024
    const val HEADER_BYTES = 48
    const val MAX_TITLE_BYTES = 1_024
    const val MAX_DESCRIPTION_BYTES = 4_096
    const val PATH_HEADER_BYTES = 16
    const val MAX_PATH_CLIPS = 8
    const val CLIP_HEADER_BYTES = 8
}

/** One validated appearance, with no executable commands or external resources. */
internal class NativeIllustration(
    val width: Float,
    val height: Float,
    val baseline: Float,
    val paths: List<IllustrationPath>,
    val packetBytes: Int,
    val title: String = "",
    val description: String = ""
)

/** A filled path: colors 0..3 are theme roles; other valid colors are explicit ARGB. */
internal class IllustrationPath(
    val color: Int,
    val evenOdd: Boolean,
    val components: FloatArray,
    val clips: List<IllustrationClip> = emptyList()
)

internal class IllustrationClip(val evenOdd: Boolean, val components: FloatArray)

/** Failure categories are presentation data, never messages returned by the native parser. */
internal enum class IllustrationFailure {
    Unsupported,
    TooLarge,
    Unavailable,
    TimedOut,
    Invalid,
    Budget
}

/** Assigned by the caller, never accepted as a worker instruction. */
internal enum class IllustrationKind { Math, Diagram }

/** A source-preserving result for one formula or diagram. */
internal sealed interface IllustrationResult {
    val kind: IllustrationKind
    data class Pending(override val kind: IllustrationKind) : IllustrationResult
    data class Rendered(
        val drawing: NativeIllustration,
        override val kind: IllustrationKind = IllustrationKind.Math
    ) : IllustrationResult
    data class Fallback(
        val reason: IllustrationFailure,
        override val kind: IllustrationKind = IllustrationKind.Math
    ) : IllustrationResult
}

internal fun IllustrationResult.forKind(kind: IllustrationKind): IllustrationResult = when (this) {
    is IllustrationResult.Pending -> copy(kind = kind)
    is IllustrationResult.Rendered -> copy(kind = kind)
    is IllustrationResult.Fallback -> copy(kind = kind)
}

/** Rejects a drawing before any of its commands reach Canvas or the PDF writer. */
internal class IllustrationProtocolException :
    IllegalArgumentException("Invalid illustration packet")

/** Decodes a complete immutable copy, validating counts before allocation and every command. */
internal object IllustrationPacketDecoder {
    fun decode(packet: ByteArray): NativeIllustration {
        valid(packet.size in IllustrationLimits.HEADER_BYTES..IllustrationLimits.MAX_PACKET_BYTES)
        val input = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(8).also(input::get)
        valid(magic.contentEquals(byteArrayOf(66, 84, 88, 84, 73, 76, 76, 51)))
        valid(input.int == 3 && input.int == packet.size)
        val pathCount = input.int
        valid(pathCount in 0..IllustrationLimits.MAX_PATHS)
        val width = input.float
        val height = input.float
        val baseline = input.float
        valid(finiteCoordinate(width) && width > 0f)
        valid(finiteCoordinate(height) && height > 0f)
        valid(baseline.isFinite() && baseline in 0f..height)
        val componentCount = input.int
        val clipCount = input.int
        val titleBytes = input.int
        val descriptionBytes = input.int
        valid(titleBytes in 0..IllustrationLimits.MAX_TITLE_BYTES)
        valid(descriptionBytes in 0..IllustrationLimits.MAX_DESCRIPTION_BYTES)
        valid(componentCount in 0..IllustrationLimits.MAX_COMPONENTS)
        valid(clipCount in 0..pathCount * IllustrationLimits.MAX_PATH_CLIPS)
        valid(
            pathCount.toLong() * IllustrationLimits.PATH_HEADER_BYTES +
                clipCount.toLong() * IllustrationLimits.CLIP_HEADER_BYTES +
                componentCount.toLong() * Float.SIZE_BYTES + titleBytes + descriptionBytes ==
                input.remaining().toLong()
        )
        val title = readText(input, titleBytes)
        val description = readText(input, descriptionBytes)
        val paths = ArrayList<IllustrationPath>(pathCount)
        var componentsRead = 0
        var clipsRead = 0
        repeat(pathCount) {
            valid(input.remaining() >= IllustrationLimits.PATH_HEADER_BYTES)
            val color = input.int
            valid(color in 0..3 || color ushr 24 != 0)
            val flags = input.int
            valid(flags in 0..1)
            val count = input.int
            val pathClipCount = input.int
            valid(count in 1..(componentCount - componentsRead))
            valid(
                pathClipCount in 0..minOf(IllustrationLimits.MAX_PATH_CLIPS, clipCount - clipsRead)
            )
            valid(count.toLong() * Float.SIZE_BYTES <= input.remaining())
            val components = FloatArray(count) { input.float }
            validateComponents(components)
            componentsRead += count
            val clips = List(pathClipCount) {
                valid(input.remaining() >= IllustrationLimits.CLIP_HEADER_BYTES)
                val clipFlags = input.int
                val clipComponents = input.int
                valid(clipFlags in 0..1 && clipComponents in 1..componentCount - componentsRead)
                valid(clipComponents.toLong() * Float.SIZE_BYTES <= input.remaining())
                val outline = FloatArray(clipComponents) { input.float }
                validateComponents(outline)
                componentsRead += clipComponents
                IllustrationClip(clipFlags == 1, outline)
            }
            clipsRead += pathClipCount
            paths += IllustrationPath(color, flags == 1, components, clips)
        }
        valid(componentsRead == componentCount && clipsRead == clipCount && !input.hasRemaining())
        return NativeIllustration(width, height, baseline, paths, packet.size, title, description)
    }

    private fun readText(input: ByteBuffer, length: Int): String {
        val bytes = input.slice().apply { limit(length) }
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(bytes).toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            throw IllustrationProtocolException()
        }
        input.position(input.position() + length)
        return text
    }

    private fun validateComponents(components: FloatArray) {
        var offset = 0
        var hasPoint = false
        while (offset < components.size) {
            val arity = when (components[offset++].toRawBits()) {
                0x0000_0000 -> {
                    hasPoint = true
                    2
                }

                0x3f80_0000 -> {
                    valid(hasPoint)
                    2
                }

                0x4000_0000 -> {
                    valid(hasPoint)
                    4
                }

                0x4040_0000 -> {
                    valid(hasPoint)
                    6
                }

                0x4080_0000 -> {
                    valid(hasPoint)
                    hasPoint = false
                    0
                }

                else -> throw IllustrationProtocolException()
            }
            valid(offset + arity <= components.size)
            repeat(arity) { valid(finiteCoordinate(components[offset++])) }
        }
    }

    private fun finiteCoordinate(value: Float): Boolean = value.isFinite() &&
        value in -IllustrationLimits.MAX_COORDINATE..IllustrationLimits.MAX_COORDINATE

    private fun valid(condition: Boolean) {
        if (!condition) throw IllustrationProtocolException()
    }
}
