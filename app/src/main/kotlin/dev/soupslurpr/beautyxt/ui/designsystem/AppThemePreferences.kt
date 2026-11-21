/* Defines content-free, canonical app-wide theme preferences. */
package dev.soupslurpr.beautyxt.ui.designsystem

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

internal const val APP_THEME_FILE_NAME = "app-theme.properties"
internal const val MAX_APP_THEME_BYTES = 1_024
private const val TRUE_BLACK_KEY = "true_black"

/** Stores app-wide theme modifiers that compose with Android's system dark mode. */
internal data class AppThemePreferences(val trueBlack: Boolean = false) {
    val isDefault: Boolean get() = !trueBlack
}

/** Emits a deterministic, content-free ASCII representation when non-default. */
internal fun formatAppThemePreferences(preferences: AppThemePreferences): String =
    if (preferences.trueBlack) "$TRUE_BLACK_KEY=true\n" else ""

/** Parses bounded theme properties strictly, rejecting malformed or unknown entries. */
internal fun readAppThemePreferences(input: InputStream): AppThemePreferences {
    val buffer = ByteArrayOutputStream()
    val chunk = ByteArray(256)
    while (true) {
        val read = input.read(chunk)
        if (read < 0) break
        if (buffer.size() + read > MAX_APP_THEME_BYTES) {
            throw IllegalArgumentException("App theme file exceeds $MAX_APP_THEME_BYTES bytes")
        }
        buffer.write(chunk, 0, read)
    }
    val bytes = buffer.toByteArray()
    if (bytes.any { it == 0.toByte() }) {
        throw IllegalArgumentException("App theme file contains NUL bytes")
    }
    val text = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (failure: CharacterCodingException) {
        throw IllegalArgumentException("App theme file is not valid UTF-8", failure)
    }

    var trueBlack: Boolean? = null
    for (rawLine in text.split('\n')) {
        val line = rawLine.removeSuffix("\r")
        if (line.isEmpty()) continue
        val separator = line.indexOf('=')
        if (separator <= 0) {
            throw IllegalArgumentException("Malformed app theme line")
        }
        val key = line.substring(0, separator)
        val value = line.substring(separator + 1)
        when (key) {
            TRUE_BLACK_KEY -> {
                if (trueBlack != null) {
                    throw IllegalArgumentException("Duplicate $TRUE_BLACK_KEY entry")
                }
                trueBlack = when (value) {
                    "true" -> true
                    "false" -> false
                    else -> throw IllegalArgumentException("Invalid boolean value for $TRUE_BLACK_KEY")
                }
            }

            else -> throw IllegalArgumentException("Unknown app theme key")
        }
    }
    return AppThemePreferences(trueBlack = trueBlack ?: false)
}
