package dev.soupslurpr.beautyxt.document

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlin.random.Random

private const val FORBIDDEN_STORAGE_MESSAGE = "operation identity entered app-owned storage"

/** Hashes exact bytes and rejects forbidden sequences without closing the caller's stream. */
internal fun fingerprintVerificationStream(
    input: InputStream,
    forbiddenPatterns: List<ByteArray>,
    bufferSize: Int
): ByteArray {
    require(forbiddenPatterns.isNotEmpty()) { "forbidden storage patterns must not be empty" }
    require(forbiddenPatterns.none(ByteArray::isEmpty)) {
        "forbidden storage pattern must not be empty"
    }
    require(bufferSize > 0) { "verification read buffer must be positive" }
    val overlap = forbiddenPatterns.maxOf(ByteArray::size) - 1
    val capacity = bufferSize.toLong() + overlap
    require(capacity <= Int.MAX_VALUE) { "verification read window exceeds array size" }
    val buffer = ByteArray(capacity.toInt())
    // ISO-8859-1 maps all 256 byte values bijectively to single UTF-16 code units.
    val patterns = forbiddenPatterns.map { String(it, Charsets.ISO_8859_1) }
    val digest = MessageDigest.getInstance("SHA-256")
    var carried = 0
    while (true) {
        var bytesRead = input.read(buffer, carried, bufferSize)
        if (bytesRead < 0) break
        if (bytesRead == 0) {
            val next = input.read()
            if (next < 0) break
            buffer[carried] = next.toByte()
            bytesRead = 1
        }
        digest.update(buffer, carried, bytesRead)
        val length = carried + bytesRead
        val window = String(buffer, 0, length, Charsets.ISO_8859_1)
        for (pattern in patterns) {
            check(window.indexOf(pattern) < 0) { FORBIDDEN_STORAGE_MESSAGE }
        }
        carried = minOf(overlap, length)
        buffer.copyInto(buffer, 0, length - carried, length)
    }
    return digest.digest()
}

/** Checks binary matching, boundary retention, hashing, and stream ownership independently. */
internal fun verifyVerificationFileFingerprint() {
    fun verify(
        bytes: ByteArray,
        patterns: List<ByteArray>,
        bufferSize: Int,
        maximumRead: Int = Int.MAX_VALUE,
        zeroReadOnce: Boolean = false
    ) {
        val forbidden = patterns.any { verificationBytesContain(bytes, it) }
        val input = VerificationControlStream(bytes, maximumRead, zeroReadOnce)
        try {
            val result = runCatching { fingerprintVerificationStream(input, patterns, bufferSize) }
            check(!input.closed) { "fingerprinting closed its caller's stream" }
            if (forbidden) {
                val failure = result.exceptionOrNull()
                check(failure is IllegalStateException && failure.message == FORBIDDEN_STORAGE_MESSAGE) {
                    "binary pattern was not rejected: buffer=$bufferSize, failure=$failure"
                }
            } else {
                val actual = result.getOrThrow()
                val expected = MessageDigest.getInstance("SHA-256").digest(bytes)
                check(actual.contentEquals(expected)) { "fingerprinting changed the exact SHA-256" }
            }
        } finally {
            input.close()
        }
        check(input.closed)
    }

    val allBytes = ByteArray(256) { it.toByte() }
    for (value in 0..255) {
        val pattern = byteArrayOf(value.toByte())
        verify(allBytes, listOf(pattern), bufferSize = 17)
        val absent = allBytes.filter { it != value.toByte() }.toByteArray()
        verify(absent, listOf(pattern), bufferSize = 13, maximumRead = 5)
    }
    verify(byteArrayOf(), listOf(byteArrayOf(0)), bufferSize = 1)
    verify(byteArrayOf(0, 1, 2, 0x80.toByte(), 0xff.toByte()),
        listOf(byteArrayOf(0x80.toByte(), 0xff.toByte())), bufferSize = 2)
    verify(byteArrayOf(0, 1, 2, 0x80.toByte(), 0xff.toByte()),
        listOf(byteArrayOf(0x80.toByte(), 0, 0xff.toByte())), bufferSize = 2)
    verify(byteArrayOf(0xc0.toByte(), 0xaf.toByte(), 0xed.toByte(), 0xa0.toByte(), 0x80.toByte()),
        listOf(byteArrayOf(0xef.toByte(), 0xbf.toByte(), 0xbd.toByte())), bufferSize = 1)
    verify("aaaaaaaabaaaaa".toByteArray(),
        listOf("aaaab", "aaab", "ab", "zz").map(String::toByteArray), bufferSize = 3)
    verify("aaaaaaaaaaaaaaaa".toByteArray(), listOf("aaaaab".toByteArray()), bufferSize = 1)
    verify("00abcd00".toByteArray(),
        listOf("abcd", "abc", "abc", "bc", "b").map(String::toByteArray), bufferSize = 2)
    verify("abcdh".toByteArray(),
        listOf("abcdef", "abcdg").map(String::toByteArray), bufferSize = 2)

    val longPattern = ByteArray(33) { (it * 19 + 127).toByte() }
    for (size in listOf(1, 2, 3, 7, 16, 64)) {
        for (start in listOf(0, size - 1, size, size + 1, size * 2 - 1).distinct()) {
            val bytes = ByteArray(start) { 0x55 } + longPattern + ByteArray(size + 2) { 0x77 }
            verify(bytes, listOf(longPattern), size)
            verify(bytes, listOf(longPattern), size, maximumRead = 1)
        }
        verify(longPattern.copyOf(16), listOf(longPattern), size)
    }
    verify(byteArrayOf(0, 1, 2, 3), listOf(byteArrayOf(9)), 16,
        maximumRead = 2, zeroReadOnce = true)
    verify(byteArrayOf(0, 1, 2, 3), listOf(byteArrayOf(0)), 16,
        maximumRead = 2, zeroReadOnce = true)

    val random = Random(0x42545854)
    repeat(512) { case ->
        val bytes = ByteArray(random.nextInt(130)).also(random::nextBytes)
        val patterns = List(random.nextInt(1, 5)) { index ->
            if (case % 3 == 0 && index == 0 && bytes.isNotEmpty()) {
                val start = random.nextInt(bytes.size)
                bytes.copyOfRange(start, random.nextInt(start + 1, bytes.size + 1))
            } else {
                ByteArray(random.nextInt(1, 66)).also(random::nextBytes)
            }
        }
        verify(bytes, patterns, random.nextInt(1, 65),
            maximumRead = random.nextInt(1, 18), zeroReadOnce = case % 7 == 0)
    }

    val readFailure = IOException("injected verification read failure")
    var closed = false
    val failingInput = object : InputStream() {
        override fun read(): Int = throw readFailure
        override fun close() { closed = true }
    }
    try {
        check(runCatching {
            fingerprintVerificationStream(failingInput, listOf(byteArrayOf(1)), 4)
        }.exceptionOrNull() === readFailure)
        check(!closed) { "fingerprinting closed a failing caller-owned stream" }
    } finally {
        failingInput.close()
    }
    for ((patterns, size) in listOf(
        emptyList<ByteArray>() to 4,
        listOf(byteArrayOf()) to 4,
        listOf(byteArrayOf(1)) to 0,
        listOf(byteArrayOf(1)) to -1,
        listOf(byteArrayOf(1, 2)) to Int.MAX_VALUE
    )) {
        check(runCatching {
            fingerprintVerificationStream(ByteArrayInputStream(byteArrayOf()), patterns, size)
        }.exceptionOrNull() is IllegalArgumentException)
    }
}

/** Uses a direct byte oracle rather than the windowing or String-search implementation. */
private fun verificationBytesContain(bytes: ByteArray, pattern: ByteArray): Boolean {
    var start = 0
    while (start <= bytes.size - pattern.size) {
        var index = 0
        while (index < pattern.size && bytes[start + index] == pattern[index]) index += 1
        if (index == pattern.size) return true
        start += 1
    }
    return false
}

private class VerificationControlStream(
    bytes: ByteArray,
    private val maximumRead: Int,
    private var zeroReadOnce: Boolean
) : ByteArrayInputStream(bytes) {
    var closed = false
        private set

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (zeroReadOnce && length > 0) {
            zeroReadOnce = false
            return 0
        }
        return super.read(buffer, offset, minOf(length, maximumRead))
    }

    override fun close() {
        closed = true
        super.close()
    }
}
