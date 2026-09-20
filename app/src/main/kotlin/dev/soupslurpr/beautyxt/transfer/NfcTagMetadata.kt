package dev.soupslurpr.beautyxt.transfer

import java.util.Locale
import kotlin.random.Random

internal const val NFC_TAG_LABEL_SUGGESTION_ALPHABET =
    "123456789ACDEFHJKMNPQRTUVWXY"
private const val PACKED_LABEL_LENGTH_BITS = Byte.SIZE_BITS
private const val MAX_PACKED_LABEL_VALUE = 0xffff_ffffL
private const val REPORTED_TAG_ID_MAX_BYTES = 32
private const val HEX_BYTE_CHARACTERS = 2
private const val REPORTED_TAG_ID_SEPARATOR = ':'
private const val UPPERCASE_HEX_DIGITS = "0123456789ABCDEF"

/** Returns whether one optional tag label is canonical uppercase ASCII. */
internal fun isValidNfcTagLabel(tagLabel: String?): Boolean = tagLabel == null ||
    (
        tagLabel.length in 1..TransferProtocol.MAX_NFC_TAG_LABEL_BYTES.toInt() &&
            tagLabel.all { character ->
                character in 'A'..'Z' || character in '0'..'9'
            }
        )

/** Returns whether one manual field can be canonically uppercased as a tag label. */
internal fun isValidNfcTagLabelInput(input: String): Boolean =
    input.length <= TransferProtocol.MAX_NFC_TAG_LABEL_BYTES.toInt() &&
        input.all { character ->
            character in 'A'..'Z' ||
                character in 'a'..'z' ||
                character in '0'..'9'
        }

/** Returns a canonical optional label or null for an empty manual field. */
internal fun canonicalNfcTagLabelOrNull(input: String): String? {
    if (input.isEmpty()) {
        return null
    }
    require(isValidNfcTagLabelInput(input)) { "NFC tag label input is invalid" }
    val normalized = input.uppercase(Locale.ROOT)
    require(isValidNfcTagLabel(normalized)) { "NFC tag label is invalid" }
    return normalized
}

/** Packs one canonical optional tag label into the isolated-transfer argument. */
internal fun packNfcTagLabel(tagLabel: String?): Long {
    require(isValidNfcTagLabel(tagLabel)) { "NFC tag label is invalid" }
    if (tagLabel == null) {
        return 0L
    }
    var packed = tagLabel.length.toLong()
    tagLabel.forEachIndexed { index, character ->
        packed = packed or (character.code.toLong() shl ((index + 1) * Byte.SIZE_BITS))
    }
    return packed
}

/** Returns whether one packed optional tag label is canonical. */
internal fun isValidPackedNfcTagLabel(packed: Long): Boolean {
    if (packed !in 0L..MAX_PACKED_LABEL_VALUE) {
        return false
    }
    val labelLength = (packed and 0xffL).toInt()
    if (labelLength !in 0..TransferProtocol.MAX_NFC_TAG_LABEL_BYTES.toInt()) {
        return false
    }
    repeat(TransferProtocol.MAX_NFC_TAG_LABEL_BYTES.toInt()) { index ->
        val character =
            ((packed ushr ((index + 1) * PACKED_LABEL_LENGTH_BITS)) and 0xffL).toInt()
        if (index < labelLength) {
            if (character !in 'A'.code..'Z'.code && character !in '0'.code..'9'.code) {
                return false
            }
        } else if (character != 0) {
            return false
        }
    }
    return true
}

/** Unpacks one already validated optional NFC tag label. */
internal fun unpackNfcTagLabel(packed: Long): String? {
    require(isValidPackedNfcTagLabel(packed)) { "packed NFC tag label is invalid" }
    val labelLength = (packed and 0xffL).toInt()
    if (labelLength == 0) {
        return null
    }
    return buildString(labelLength) {
        repeat(labelLength) { index ->
            append(
                ((packed ushr ((index + 1) * PACKED_LABEL_LENGTH_BITS)) and 0xffL)
                    .toInt()
                    .toChar()
            )
        }
    }
}

/** Returns the optional label byte count from one validated packed value. */
internal fun packedNfcTagLabelLength(packed: Long): Long {
    require(isValidPackedNfcTagLabel(packed)) { "packed NFC tag label is invalid" }
    return packed and 0xffL
}

/** Suggests one non-confusable three-character physical tag label. */
internal fun suggestNfcTagLabel(random: Random = Random.Default): String =
    buildString(TransferProtocol.MAX_NFC_TAG_LABEL_BYTES.toInt()) {
        repeat(TransferProtocol.MAX_NFC_TAG_LABEL_BYTES.toInt()) {
            append(NFC_TAG_LABEL_SUGGESTION_ALPHABET.random(random))
        }
    }

/** Encodes Android's optional transient tag identifier for technical display. */
internal fun reportedNfcTagId(tagId: ByteArray): String? {
    if (tagId.isEmpty() || tagId.size > REPORTED_TAG_ID_MAX_BYTES) {
        return null
    }
    return buildString(tagId.size * (HEX_BYTE_CHARACTERS + 1) - 1) {
        tagId.forEachIndexed { index, byte ->
            if (index > 0) {
                append(REPORTED_TAG_ID_SEPARATOR)
            }
            val value = byte.toInt() and 0xff
            append(UPPERCASE_HEX_DIGITS[value ushr 4])
            append(UPPERCASE_HEX_DIGITS[value and 0x0f])
        }
    }
}

/** Returns whether one transient reported tag ID uses canonical hexadecimal text. */
internal fun isValidReportedNfcTagId(tagId: String?): Boolean {
    if (tagId == null) {
        return true
    }
    val byteCount = (tagId.length + 1) / (HEX_BYTE_CHARACTERS + 1)
    if (
        byteCount !in 1..REPORTED_TAG_ID_MAX_BYTES ||
        tagId.length != byteCount * (HEX_BYTE_CHARACTERS + 1) - 1
    ) {
        return false
    }
    return tagId.indices.all { index ->
        if ((index + 1) % (HEX_BYTE_CHARACTERS + 1) == 0) {
            tagId[index] == REPORTED_TAG_ID_SEPARATOR
        } else {
            val character = tagId[index]
            character in '0'..'9' || character in 'A'..'F'
        }
    }
}
