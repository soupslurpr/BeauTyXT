/* Bounded off-UI Source syntax highlighting backed by beautyxt-syntax-core. */
package dev.soupslurpr.beautyxt.ui.editor

import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.soupslurpr.beautyxt.document.DocumentFormat
import dev.soupslurpr.beautyxt.document.NativeDocument
import dev.soupslurpr.beautyxt.document.Utf16Range
import dev.soupslurpr.beautyxt.markdown.MarkdownSyntaxRun
import dev.soupslurpr.beautyxt.markdown.SyntaxTokenRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal const val SYNTAX_LANGUAGE_PLAIN = 0
internal const val SYNTAX_LANGUAGE_MARKDOWN = 24
private const val SOURCE_SYNTAX_LOOKBACK_UTF16 = 8 * 1024L
private const val SOURCE_SYNTAX_MAX_TOKENS = 4_096
private const val SOURCE_SYNTAX_DEBOUNCE_MILLIS = 40L

/** Maps document format and display title to a stable `beautyxt-syntax-core` language id. */
internal fun resolveSourceSyntaxLanguageId(
    format: DocumentFormat,
    title: String
): Int {
    if (format == DocumentFormat.Markdown) return SYNTAX_LANGUAGE_MARKDOWN
    return try {
        NativeDocument.syntaxLanguageForFilename(title)
    } catch (_: LinkageError) {
        SYNTAX_LANGUAGE_PLAIN
    }
}

/** Decodes packed `[exitState, (start<<32)|(len<<16)|role, ...]` words from native syntax-core. */
internal fun decodePackedSyntaxTokens(
    packed: LongArray,
    textLength: Int
): Pair<Long, List<MarkdownSyntaxRun>> {
    if (packed.isEmpty()) return 0L to emptyList()
    val exitState = packed[0]
    if (packed.size == 1) return exitState to emptyList()
    val runs = ArrayList<MarkdownSyntaxRun>(packed.size - 1)
    var previousEnd = 0
    for (index in 1 until packed.size) {
        val word = packed[index]
        val start = (word ushr 32).toInt()
        val length = ((word ushr 16) and 0xffffL).toInt()
        val roleWire = (word and 0xffL).toInt()
        val role = SyntaxTokenRole.entries.firstOrNull { it.protocolValue == roleWire } ?: continue
        if (length <= 0 || start < previousEnd) continue
        val end = start + length
        if (end > textLength) break
        runs.add(MarkdownSyntaxRun(start = start, end = end, role = role))
        previousEnd = end
    }
    return exitState to runs
}

/** Highlights one bounded text chunk through `beautyxt-syntax-core`, returning empty on fallback. */
internal fun highlightSourceChunk(
    languageId: Int,
    entryState: Long,
    text: String,
    maxTokens: Int = SOURCE_SYNTAX_MAX_TOKENS
): Pair<Long, List<MarkdownSyntaxRun>> {
    if (languageId == SYNTAX_LANGUAGE_PLAIN || text.isEmpty()) {
        return entryState to emptyList()
    }
    return try {
        val packed = NativeDocument.highlightSyntax(languageId, entryState, text, maxTokens)
        decodePackedSyntaxTokens(packed, text.length)
    } catch (_: Throwable) {
        entryState to emptyList()
    }
}

/** Advances cross-line lexer state over a bounded lookback prefix without allocating tokens. */
internal fun advanceSourceSyntaxState(
    languageId: Int,
    entryState: Long,
    text: String
): Long {
    if (languageId == SYNTAX_LANGUAGE_PLAIN || text.isEmpty()) return entryState
    return try {
        NativeDocument.advanceSyntaxState(languageId, entryState, text)
    } catch (_: Throwable) {
        0L
    }
}

/** Holds highlighted runs bound to the exact text snapshot they were scanned from. */
internal data class HighlightedSourceText(
    val text: String,
    val runs: List<MarkdownSyntaxRun>
)

private data class ReadOnlySyntaxCacheKey(
    val revision: Long,
    val globalStart: Long,
    val languageId: Int,
    val textHash: Int
)

/** Computes bounded off-UI syntax runs for a read-only source block, scoped to [session]. */
@Composable
internal fun rememberReadOnlySourceSyntaxRuns(
    session: EditorSession,
    revision: Long,
    globalStart: Long,
    text: String,
    languageId: Int,
    enabled: Boolean
): List<MarkdownSyntaxRun> {
    val sessionCache = remember(session) {
        LruCache<ReadOnlySyntaxCacheKey, List<MarkdownSyntaxRun>>(96)
    }
    val cacheKey = remember(session, enabled, revision, globalStart, languageId, text) {
        if (!enabled || languageId == SYNTAX_LANGUAGE_PLAIN || text.isEmpty()) {
            null
        } else {
            ReadOnlySyntaxCacheKey(revision, globalStart, languageId, text.hashCode())
        }
    }
    var runs by remember(cacheKey) {
        mutableStateOf(cacheKey?.let { sessionCache.get(it) } ?: emptyList())
    }
    LaunchedEffect(cacheKey) {
        val key = cacheKey ?: run {
            runs = emptyList()
            return@LaunchedEffect
        }
        sessionCache.get(key)?.let { cached ->
            runs = cached
            return@LaunchedEffect
        }
        val computed = withContext(Dispatchers.Default) {
            val entryState = computeBoundedLookbackState(session, revision, globalStart, languageId)
            highlightSourceChunk(languageId, entryState, text).second
        }
        sessionCache.put(key, computed)
        runs = computed
    }
    return runs
}

/** Computes debounced off-UI syntax runs for the active bounded Source edit window. */
@Composable
internal fun rememberActiveSourceSyntaxRuns(
    session: EditorSession,
    revision: Long,
    windowStart: Long,
    text: String,
    languageId: Int,
    enabled: Boolean
): HighlightedSourceText? {
    var highlighted by remember(languageId, enabled, windowStart) {
        mutableStateOf<HighlightedSourceText?>(null)
    }
    LaunchedEffect(session, revision, windowStart, text, languageId, enabled) {
        if (!enabled || languageId == SYNTAX_LANGUAGE_PLAIN || text.isEmpty()) {
            highlighted = null
            return@LaunchedEffect
        }
        if (highlighted != null) {
            delay(SOURCE_SYNTAX_DEBOUNCE_MILLIS)
        }
        val computed = withContext(Dispatchers.Default) {
            val entryState = computeBoundedLookbackState(session, revision, windowStart, languageId)
            highlightSourceChunk(languageId, entryState, text).second
        }
        highlighted = HighlightedSourceText(text = text, runs = computed)
    }
    return highlighted
}

private suspend fun computeBoundedLookbackState(
    session: EditorSession,
    revision: Long,
    targetStart: Long,
    languageId: Int
): Long {
    if (targetStart <= 0L || languageId == SYNTAX_LANGUAGE_PLAIN) return 0L
    val lookbackStart = (targetStart - SOURCE_SYNTAX_LOOKBACK_UTF16).coerceAtLeast(0L)
    val prefix = try {
        session.state.readSourceRange(revision, Utf16Range(lookbackStart, targetStart))
    } catch (_: Exception) {
        return 0L
    }
    // Start at the first complete line inside the lookback window when truncated mid-file.
    val alignedPrefix = if (lookbackStart > 0L) {
        val nl = prefix.indexOf('\n')
        if (nl >= 0 && nl + 1 < prefix.length) prefix.substring(nl + 1) else return 0L
    } else {
        prefix
    }
    return advanceSourceSyntaxState(languageId, 0L, alignedPrefix)
}
