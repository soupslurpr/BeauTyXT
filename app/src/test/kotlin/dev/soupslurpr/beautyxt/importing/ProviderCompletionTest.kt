package dev.soupslurpr.beautyxt.importing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies reliable provider completion result handling. */
class ProviderCompletionTest {
    @Test
    fun skipsProviderCheckAfterNativeFailure() {
        var inputChecked = false
        var outputReset = false

        val resultCode =
            resolveProviderCompletion(
                nativeResultCode = ImportProtocol.RESULT_INVALID_UTF8,
                checkInputError = {
                    inputChecked = true
                    true
                },
                handleInputError = { outputReset = true }
            )

        assertEquals(ImportProtocol.RESULT_INVALID_UTF8, resultCode)
        assertFalse(inputChecked)
        assertFalse(outputReset)
    }

    @Test
    fun preservesSuccessfulProviderCompletion() {
        var outputReset = false

        val resultCode =
            resolveProviderCompletion(
                nativeResultCode = ImportProtocol.RESULT_SUCCESS,
                checkInputError = { false },
                handleInputError = { outputReset = true }
            )

        assertEquals(ImportProtocol.RESULT_SUCCESS, resultCode)
        assertFalse(outputReset)
    }

    @Test
    fun convertsProviderErrorAndResetsOutput() {
        var inputChecked = false
        var outputReset = false

        val resultCode =
            resolveProviderCompletion(
                nativeResultCode = ImportProtocol.RESULT_SUCCESS,
                checkInputError = {
                    inputChecked = true
                    true
                },
                handleInputError = { outputReset = true }
            )

        assertEquals(ImportProtocol.RESULT_INPUT_IO, resultCode)
        assertTrue(inputChecked)
        assertTrue(outputReset)
    }
}
