package dev.soupslurpr.beautyxt.document

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Verifies immutable exact-byte source versions. */
class SourceVersionTest {
    /** Verifies construction and access cannot mutate retained digest bytes. */
    @Test
    fun retainsADefensiveDigestCopy() {
        val input = ByteArray(SHA_256_BYTE_COUNT) { index -> index.toByte() }
        val version = SourceVersion.from(byteLength = 7L, sha256 = input)
        input.fill(0)

        val firstCopy = version.copySha256()
        firstCopy.fill(0)

        assertArrayEquals(
            ByteArray(SHA_256_BYTE_COUNT) { index -> index.toByte() },
            version.copySha256()
        )
    }

    /** Verifies equality covers both the exact length and complete digest. */
    @Test
    fun comparesCompleteSourceVersions() {
        val digest = ByteArray(SHA_256_BYTE_COUNT) { index -> index.toByte() }
        val equal = SourceVersion.from(byteLength = 7L, sha256 = digest)

        assertEquals(equal, SourceVersion.from(byteLength = 7L, sha256 = digest))
        assertEquals(equal.hashCode(), SourceVersion.from(7L, digest).hashCode())
        assertNotEquals(equal, SourceVersion.from(byteLength = 8L, sha256 = digest))
        assertNotEquals(
            equal,
            SourceVersion.from(
                byteLength = 7L,
                sha256 = digest.copyOf().also { bytes -> bytes[0] = 1 }
            )
        )
    }

    /** Verifies malformed versions fail at their construction boundary. */
    @Test
    fun rejectsMalformedSourceVersions() {
        assertThrows(IllegalArgumentException::class.java) {
            SourceVersion.from(byteLength = -1L, sha256 = ByteArray(SHA_256_BYTE_COUNT))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SourceVersion.from(byteLength = 0L, sha256 = ByteArray(SHA_256_BYTE_COUNT - 1))
        }
    }
}
