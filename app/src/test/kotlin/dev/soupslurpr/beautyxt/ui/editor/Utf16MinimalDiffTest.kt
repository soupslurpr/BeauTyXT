package dev.soupslurpr.beautyxt.ui.editor

import dev.soupslurpr.beautyxt.document.Utf16Range
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private const val TEST_RANDOM_SEED = 0L
private const val TEST_RANDOM_CASES = 2_048
private const val TEST_MAX_INITIAL_SCALARS = 64
private const val TEST_MAX_REPLACEMENT_SCALARS = 16
private val TEST_SCALARS =
    intArrayOf(
        'a'.code,
        'z'.code,
        '\n'.code,
        0x0301,
        0x200d,
        0x1f600,
        0x1f601,
        0x1f680
    )

/** Verifies scalar-safe minimal UTF-16 replacement discovery. */
class Utf16MinimalDiffTest {
    /** Verifies that equal text does not produce a replacement. */
    @Test
    fun returnsNullForEqualText() {
        assertNull(findUtf16MinimalDiff("same 😀", "same 😀"))
    }

    /** Verifies minimal insertions, deletions, and multiline replacements. */
    @Test
    fun returnsMinimalAsciiAndMultilineReplacements() {
        assertEquals(
            Utf16MinimalDiff(
                oldRange = Utf16Range(start = 5, end = 5),
                replacement = " local"
            ),
            findUtf16MinimalDiff("hello", "hello local")
        )
        assertEquals(
            Utf16MinimalDiff(
                oldRange = Utf16Range(start = 5, end = 11),
                replacement = ""
            ),
            findUtf16MinimalDiff("hello local", "hello")
        )
        assertEquals(
            Utf16MinimalDiff(
                oldRange = Utf16Range(start = 6, end = 9),
                replacement = "two\nlines"
            ),
            findUtf16MinimalDiff("start one end", "start two\nlines end")
        )
    }

    /** Verifies that a shared high surrogate cannot become a range boundary. */
    @Test
    fun keepsSharedHighSurrogateInsideReplacement() {
        val replacement = findUtf16MinimalDiff("a😀z", "a😁z")

        assertEquals(
            Utf16MinimalDiff(
                oldRange = Utf16Range(start = 1, end = 3),
                replacement = "😁"
            ),
            replacement
        )
    }

    /** Verifies that a shared low surrogate cannot become a range boundary. */
    @Test
    fun keepsSharedLowSurrogateInsideReplacement() {
        val firstScalar = "\ud800\udc00"
        val secondScalar = "\ud801\udc00"

        assertEquals(
            Utf16MinimalDiff(
                oldRange = Utf16Range(start = 0, end = 2),
                replacement = secondScalar
            ),
            findUtf16MinimalDiff(firstScalar, secondScalar)
        )
    }

    /** Verifies that malformed UTF-16 fails before a replacement is published. */
    @Test
    fun rejectsUnpairedSurrogates() {
        val invalidHighSurrogate = "\ud800"
        val invalidLowSurrogate = "\udc00"

        assertEquals(
            "original text contains an unpaired surrogate",
            assertThrows(IllegalArgumentException::class.java) {
                findUtf16MinimalDiff(invalidHighSurrogate, "valid")
            }.message
        )
        assertEquals(
            "updated text contains an unpaired surrogate",
            assertThrows(IllegalArgumentException::class.java) {
                findUtf16MinimalDiff("valid", invalidLowSurrogate)
            }.message
        )
    }

    /** Verifies deterministic mixed-Unicode edits reconstruct every updated string. */
    @Test
    fun reconstructsDeterministicMixedUnicodeEdits() {
        val random = Random(TEST_RANDOM_SEED)
        repeat(TEST_RANDOM_CASES) {
            val original = randomText(random, random.nextInt(TEST_MAX_INITIAL_SCALARS + 1))
            val originalScalarOffsets = original.scalarOffsets()
            val startScalarIndex = random.nextInt(originalScalarOffsets.size)
            val endScalarIndex =
                startScalarIndex +
                    random.nextInt(originalScalarOffsets.size - startScalarIndex)
            val replacement =
                randomText(random, random.nextInt(TEST_MAX_REPLACEMENT_SCALARS + 1))
            val updated =
                buildString {
                    append(original, 0, originalScalarOffsets[startScalarIndex])
                    append(replacement)
                    append(original, originalScalarOffsets[endScalarIndex], original.length)
                }

            val minimalReplacement = findUtf16MinimalDiff(original, updated)
            if (original == updated) {
                assertNull(minimalReplacement)
            } else {
                val requiredReplacement = requireNotNull(minimalReplacement)
                val scalarOffsets = original.scalarOffsets()
                assertTrue(requiredReplacement.oldRange.start.toInt() in scalarOffsets)
                assertTrue(requiredReplacement.oldRange.end.toInt() in scalarOffsets)
                assertEquals(updated, original.apply(requiredReplacement))
            }
        }
    }

    /** Creates deterministic valid UTF-16 from a fixed scalar alphabet. */
    private fun randomText(random: Random, scalarCount: Int): String {
        require(scalarCount >= 0) { "scalar count must be nonnegative" }
        return buildString {
            repeat(scalarCount) {
                appendCodePoint(TEST_SCALARS[random.nextInt(TEST_SCALARS.size)])
            }
        }
    }

    /** Returns every scalar-aligned UTF-16 offset, including both ends. */
    private fun String.scalarOffsets(): IntArray {
        val offsets = ArrayList<Int>(length + 1)
        var offset = 0
        offsets += offset
        while (offset < length) {
            offset += Character.charCount(codePointAt(offset))
            offsets += offset
        }
        return offsets.toIntArray()
    }

    /** Applies one half-open local replacement to a test string. */
    private fun String.apply(diff: Utf16MinimalDiff): String = buildString {
        append(this@apply, 0, diff.oldRange.start.toInt())
        append(diff.replacement)
        append(this@apply, diff.oldRange.end.toInt(), this@apply.length)
    }
}
