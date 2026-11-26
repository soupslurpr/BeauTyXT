/* Reads plain clipboard text explicitly on user action without dereferencing clip URIs. */
package dev.soupslurpr.beautyxt

import android.content.ClipboardManager
import android.content.Context
import dev.soupslurpr.beautyxt.document.DocumentFormat
import dev.soupslurpr.beautyxt.document.hasWellFormedUtf16
import dev.soupslurpr.beautyxt.sharing.MAX_SHARED_TEXT_UTF8_BYTES
import dev.soupslurpr.beautyxt.sharing.normalizeIncomingSharedText
import dev.soupslurpr.beautyxt.ui.editor.TRANSIENT_DOCUMENT_MAX_UTF16_UNITS

/** Describes the outcome of one explicit user-initiated clipboard paste request. */
internal sealed interface ClipboardPasteResult {
    /** Contains bounded, well-formed normalized text ready to open in a transient editor. */
    data class Ready(
        val text: String,
        val format: DocumentFormat
    ) : ClipboardPasteResult

    /** Indicates that the clipboard contains no direct text item (or only a URI/intent). */
    data object Empty : ClipboardPasteResult

    /** Indicates that the clipboard text exceeds BeauTyXT's transient direct-text limit. */
    data object TooLarge : ClipboardPasteResult
}

/**
 * Reads direct text from the system clipboard without calling `coerceToText()`.
 *
 * Call this only from an explicit user tap or a focused shortcut launch so Android never
 * observes background clipboard reads or silently dereferences `content://` clip items.
 */
internal fun readClipboardTextForPaste(context: Context): ClipboardPasteResult {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
        ?: return ClipboardPasteResult.Empty
    val clip = try {
        clipboard.primaryClip
    } catch (_: SecurityException) {
        return ClipboardPasteResult.Empty
    } catch (_: Exception) {
        return ClipboardPasteResult.Empty
    } ?: return ClipboardPasteResult.Empty
    if (clip.itemCount <= 0) {
        return ClipboardPasteResult.Empty
    }
    // Deliberately read `item.text` only; NEVER `coerceToText()`, which opens clip URIs.
    val rawText = clip.getItemAt(0)?.text ?: return ClipboardPasteResult.Empty
    return validateClipboardPasteText(rawText)
}

/** Validates and normalizes direct clipboard text within transient document bounds. */
internal fun validateClipboardPasteText(rawText: CharSequence?): ClipboardPasteResult {
    if (rawText == null || rawText.isEmpty()) {
        return ClipboardPasteResult.Empty
    }
    if (rawText.length > TRANSIENT_DOCUMENT_MAX_UTF16_UNITS) {
        return ClipboardPasteResult.TooLarge
    }
    val text = rawText.toString()
    if (!text.hasWellFormedUtf16()) {
        return ClipboardPasteResult.Empty
    }
    val normalized = normalizeIncomingSharedText(text)
    if (normalized.isEmpty()) {
        return ClipboardPasteResult.Empty
    }
    if (
        normalized.length > TRANSIENT_DOCUMENT_MAX_UTF16_UNITS ||
        normalized.toByteArray(Charsets.UTF_8).size > MAX_SHARED_TEXT_UTF8_BYTES
    ) {
        return ClipboardPasteResult.TooLarge
    }
    return ClipboardPasteResult.Ready(
        text = normalized,
        format = detectPastedTextFormat(normalized)
    )
}

/** Applies a lightweight structural heuristic to detect pasted Markdown vs plain text. */
internal fun detectPastedTextFormat(text: String): DocumentFormat {
    val lines = text.lineSequence().take(64).toList()
    var signals = 0
    for (index in lines.indices) {
        val line = lines[index]
        val trimmed = line.trimStart()
        if (index == 0 && (trimmed == "---" || trimmed == "+++")) {
            return DocumentFormat.Markdown
        }
        if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
            return DocumentFormat.Markdown
        }
        if (
            trimmed.startsWith("# ") ||
            trimmed.startsWith("## ") ||
            trimmed.startsWith("### ") ||
            trimmed.startsWith("#### ")
        ) {
            return DocumentFormat.Markdown
        }
        if (
            trimmed.startsWith("- [ ] ") ||
            trimmed.startsWith("- [x] ") ||
            trimmed.startsWith("- [X] ") ||
            trimmed.startsWith("* [ ] ") ||
            trimmed.startsWith("* [x] ")
        ) {
            return DocumentFormat.Markdown
        }
        if (trimmed.startsWith("> ") || trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
            signals += 1
        }
        if (line.contains("](") && line.contains("[")) {
            signals += 1
        }
        if (line.contains("**") || line.contains("`")) {
            signals += 1
        }
        if (signals >= 2) {
            return DocumentFormat.Markdown
        }
    }
    return DocumentFormat.PlainText
}
