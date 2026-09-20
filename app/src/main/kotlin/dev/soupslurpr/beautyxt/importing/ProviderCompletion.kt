package dev.soupslurpr.beautyxt.importing

/** Resolves a reliable provider's terminal status after native completion. */
internal inline fun resolveProviderCompletion(
    nativeResultCode: Int,
    checkInputError: () -> Boolean,
    handleInputError: () -> Unit
): Int {
    if (nativeResultCode != ImportProtocol.RESULT_SUCCESS || !checkInputError()) {
        return nativeResultCode
    }
    handleInputError()
    return ImportProtocol.RESULT_INPUT_IO
}
