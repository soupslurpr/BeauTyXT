package dev.soupslurpr.beautyxt.illustration

import androidx.annotation.StringRes
import dev.soupslurpr.beautyxt.R
import dev.soupslurpr.beautyxt.markdown.MarkdownInlineSpan

/** Small, deterministic explanations shared by preview and print; parser messages never enter UI. */
internal fun illustrationFallbackMessages(spans: List<MarkdownInlineSpan>): List<Int> =
    spans.mapNotNull { (it.illustration as? IllustrationResult.Fallback)?.reason }
        .distinct().sortedBy { it.ordinal }.map(::illustrationFailureMessage)

@StringRes
internal fun illustrationFailureMessage(reason: IllustrationFailure): Int = when (reason) {
    IllustrationFailure.Unsupported -> R.string.markdown_illustration_unsupported
    IllustrationFailure.TooLarge -> R.string.markdown_illustration_too_complex
    IllustrationFailure.Unavailable -> R.string.markdown_illustration_unavailable
    IllustrationFailure.TimedOut -> R.string.markdown_illustration_timed_out
    IllustrationFailure.Invalid -> R.string.markdown_illustration_failed
    IllustrationFailure.Budget -> R.string.markdown_illustration_budget
}

/** An authored description takes precedence over reading syntax aloud; Copy keeps exact source. */
internal fun NativeIllustration.authoredDescription(): String? =
    listOf(title, description).filter { it.isNotBlank() }.joinToString("\n").ifEmpty { null }
