package dev.soupslurpr.beautyxt.markdown

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

private val MarkdownLinkScheme = Regex("[A-Za-z][A-Za-z0-9+.-]*")
private val DirectViewSchemes = setOf("http", "https", "geo")
private val DirectSendToSchemes = setOf("mailto", "sms", "smsto")
private val BlockedSchemes =
    setOf("blob", "content", "data", "file", "filesystem", "intent", "javascript")

/** Identifies the constrained Android action used for one external Markdown link. */
internal enum class MarkdownExternalAction {
    View,
    Dial,
    SendTo
}

/** Describes one validated action requested by a rendered Markdown link. */
internal sealed interface MarkdownLinkAction {
    /** Navigates to one rendered heading without leaving BeauTyXT. */
    data class Heading(val fragment: String) : MarkdownLinkAction

    /** Hands one explicit destination to an Android application. */
    data class External(
        val destination: String,
        val scheme: String,
        val action: MarkdownExternalAction,
        val requiresConfirmation: Boolean
    ) : MarkdownLinkAction

    /** Reports one link BeauTyXT deliberately does not dispatch. */
    data class Unavailable(
        val destination: String,
        val reason: MarkdownLinkUnavailableReason,
        val scheme: String? = null
    ) : MarkdownLinkAction
}

/** Identifies why one Markdown link cannot be followed. */
internal enum class MarkdownLinkUnavailableReason {
    BlockedScheme,
    Invalid,
    RelativeDocument
}

/** Classifies one renderer-bounded Markdown destination without dispatching it. */
internal fun markdownLinkAction(destination: String): MarkdownLinkAction {
    if (
        destination.isEmpty() ||
        destination != destination.trim() ||
        destination.any(Character::isISOControl)
    ) {
        return MarkdownLinkAction.Unavailable(
            destination = destination,
            reason = MarkdownLinkUnavailableReason.Invalid
        )
    }
    if (destination.startsWith('#')) {
        val fragment = decodeMarkdownFragment(destination.drop(1))
            ?: return MarkdownLinkAction.Unavailable(
                destination = destination,
                reason = MarkdownLinkUnavailableReason.Invalid
            )
        return MarkdownLinkAction.Heading(fragment)
    }
    val schemeDelimiter = destination.indexOf(':')
    val firstPathDelimiter =
        sequenceOf('/', '?', '#')
            .map(destination::indexOf)
            .filter { index -> index >= 0 }
            .minOrNull()
    if (schemeDelimiter <= 0 || firstPathDelimiter?.let { schemeDelimiter > it } == true) {
        return MarkdownLinkAction.Unavailable(
            destination = destination,
            reason = MarkdownLinkUnavailableReason.RelativeDocument
        )
    }
    val rawScheme = destination.substring(0, schemeDelimiter)
    if (!MarkdownLinkScheme.matches(rawScheme) || destination.any(Char::isWhitespace)) {
        return MarkdownLinkAction.Unavailable(
            destination = destination,
            reason = MarkdownLinkUnavailableReason.Invalid
        )
    }
    val scheme = rawScheme.lowercase(Locale.ROOT)
    if (scheme in BlockedSchemes) {
        return MarkdownLinkAction.Unavailable(
            destination = destination,
            reason = MarkdownLinkUnavailableReason.BlockedScheme,
            scheme = scheme
        )
    }
    val action =
        when {
            scheme in DirectViewSchemes -> MarkdownExternalAction.View
            scheme == "tel" -> MarkdownExternalAction.Dial
            scheme in DirectSendToSchemes -> MarkdownExternalAction.SendTo
            else -> MarkdownExternalAction.View
        }
    return MarkdownLinkAction.External(
        destination = destination,
        scheme = scheme,
        action = action,
        requiresConfirmation =
            scheme !in DirectViewSchemes &&
                scheme != "tel" &&
                scheme !in DirectSendToSchemes
    )
}

/** Decodes one percent-encoded fragment while preserving literal plus signs. */
private fun decodeMarkdownFragment(fragment: String): String? = try {
    URLDecoder.decode(
        fragment.replace("+", "%2B"),
        StandardCharsets.UTF_8
    ).takeUnless { decoded -> decoded.any(Character::isISOControl) }
} catch (_: IllegalArgumentException) {
    null
}
