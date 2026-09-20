package dev.soupslurpr.beautyxt.ui.editor

import dev.soupslurpr.beautyxt.document.DocumentRemovalAction
import dev.soupslurpr.beautyxt.document.DocumentRemovalCapabilities
import dev.soupslurpr.beautyxt.ui.UiText

/** Describes the retained provider-removal state for one open source. */
internal sealed interface DocumentRemovalStatus {
    /** Indicates that live provider capabilities have not been requested. */
    data object Idle : DocumentRemovalStatus

    /** Indicates that live provider capabilities are being queried. */
    data object Checking : DocumentRemovalStatus

    /** Contains the provider's newest independent removal capabilities. */
    data class Ready(val capabilities: DocumentRemovalCapabilities) : DocumentRemovalStatus

    /** Retains one explicitly selected removal until the user confirms it. */
    data class Confirming(
        val action: DocumentRemovalAction,
        val capabilities: DocumentRemovalCapabilities
    ) : DocumentRemovalStatus {
        init {
            require(capabilities.supports(action)) {
                "confirmed document removal is not advertised"
            }
        }
    }

    /** Indicates that one confirmed provider operation is running. */
    data class Removing(val action: DocumentRemovalAction) : DocumentRemovalStatus

    /** Contains one sanitized capability or operation failure. */
    data class Failed(val message: UiText) : DocumentRemovalStatus

    /** Records one provider-confirmed removal until the editor closes. */
    data class Succeeded(val action: DocumentRemovalAction) : DocumentRemovalStatus
}
