package dev.soupslurpr.beautyxt.document

import java.security.MessageDigest

internal const val SHA_256_BYTE_COUNT = 32
private const val HASH_MULTIPLIER = 31

/** Identifies exact source bytes without retaining document content. */
internal class SourceVersion private constructor(val byteLength: Long, sha256: ByteArray) {
    private val sha256 = sha256.copyOf()

    /** Returns a defensive copy of the exact SHA-256 digest. */
    fun copySha256(): ByteArray = sha256.copyOf()

    override fun equals(other: Any?): Boolean = this === other ||
        (
            other is SourceVersion &&
                byteLength == other.byteLength &&
                MessageDigest.isEqual(sha256, other.sha256)
            )

    override fun hashCode(): Int =
        HASH_MULTIPLIER * byteLength.hashCode() + sha256.contentHashCode()

    override fun toString(): String = "SourceVersion(byteLength=$byteLength)"

    companion object {
        /** Creates a version from one validated byte length and SHA-256 digest. */
        fun from(byteLength: Long, sha256: ByteArray): SourceVersion {
            require(byteLength >= 0L) { "source byte length must be nonnegative" }
            require(sha256.size == SHA_256_BYTE_COUNT) {
                "source sha-256 digest has an unexpected length"
            }
            return SourceVersion(byteLength = byteLength, sha256 = sha256)
        }
    }
}
