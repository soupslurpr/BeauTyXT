/* Keeps presentation text resource-backed without retaining an Android context in session state. */
package dev.soupslurpr.beautyxt.ui

import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources

/** Describes app wording or already-sanitized content supplied by a document operation. */
internal sealed interface UiText {
    data class Resource(@StringRes val id: Int, val arguments: List<Any> = emptyList()) : UiText

    data class Quantity(
        @PluralsRes val id: Int,
        val count: Int,
        val arguments: List<Any> = emptyList()
    ) : UiText

    data class Literal(val value: String) : UiText
}

/** Resolves presentation text at the Android boundary, including its formatting locale. */
internal fun UiText.resolve(resources: Resources): String = when (this) {
    is UiText.Resource -> if (arguments.isEmpty()) {
        resources.getString(id)
    } else {
        resources.getString(id, *arguments.toTypedArray())
    }

    is UiText.Quantity -> if (arguments.isEmpty()) {
        resources.getQuantityString(id, count)
    } else {
        resources.getQuantityString(id, count, *arguments.toTypedArray())
    }

    is UiText.Literal -> value
}

/** Resolves again when the current Android resources change. */
@Composable
internal fun UiText.asString(): String = resolve(LocalResources.current)
