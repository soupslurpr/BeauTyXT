/* Derives high-contrast syntax highlight styles from Material 3 theme roles. */
package dev.soupslurpr.beautyxt.ui.editor

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import dev.soupslurpr.beautyxt.markdown.SyntaxTokenRole

/** Closed theme-derived syntax styles for light, dark, and true-black OLED surfaces. */
@Immutable
internal data class SyntaxHighlightPalette(
    val keyword: SpanStyle,
    val stringLiteral: SpanStyle,
    val comment: SpanStyle,
    val number: SpanStyle,
    val type: SpanStyle,
    val function: SpanStyle,
    val attribute: SpanStyle,
    val operator: SpanStyle,
    val diffAdded: SpanStyle,
    val diffRemoved: SpanStyle,
    val diffHeader: SpanStyle
) {
    fun styleFor(role: SyntaxTokenRole): SpanStyle = when (role) {
        SyntaxTokenRole.Keyword -> keyword
        SyntaxTokenRole.StringLiteral -> stringLiteral
        SyntaxTokenRole.Comment -> comment
        SyntaxTokenRole.Number -> number
        SyntaxTokenRole.Type -> type
        SyntaxTokenRole.Function -> function
        SyntaxTokenRole.Attribute -> attribute
        SyntaxTokenRole.Operator -> operator
        SyntaxTokenRole.DiffAdded -> diffAdded
        SyntaxTokenRole.DiffRemoved -> diffRemoved
        SyntaxTokenRole.DiffHeader -> diffHeader
    }
}

/** Builds accessible syntax highlight styles derived from [colorScheme]. */
internal fun syntaxHighlightPalette(colorScheme: ColorScheme): SyntaxHighlightPalette {
    val isDark = colorScheme.surface.luminance() < 0.5f
    val stringColor = if (isDark) Color(0xFF86EFAC) else Color(0xFF166534)
    val numberColor = if (isDark) Color(0xFFFDBA74) else Color(0xFF9A3412)
    val typeColor = if (isDark) Color(0xFF7DD3FC) else Color(0xFF0369A1)
    val addedColor = if (isDark) Color(0xFF86EFAC) else Color(0xFF15803D)
    val removedColor = if (isDark) Color(0xFFFCA5A5) else Color(0xFFB91C1C)
    return SyntaxHighlightPalette(
        keyword = SpanStyle(color = colorScheme.primary, fontWeight = FontWeight.SemiBold),
        stringLiteral = SpanStyle(color = stringColor),
        comment = SpanStyle(
            color = colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
            fontStyle = FontStyle.Italic
        ),
        number = SpanStyle(color = numberColor),
        type = SpanStyle(color = typeColor),
        function = SpanStyle(color = colorScheme.tertiary, fontWeight = FontWeight.Medium),
        attribute = SpanStyle(color = colorScheme.secondary),
        operator = SpanStyle(color = colorScheme.onSurfaceVariant),
        diffAdded = SpanStyle(color = addedColor),
        diffRemoved = SpanStyle(color = removedColor),
        diffHeader = SpanStyle(color = colorScheme.primary, fontWeight = FontWeight.Bold)
    )
}

@Composable
internal fun rememberSyntaxHighlightPalette(): SyntaxHighlightPalette {
    val scheme = MaterialTheme.colorScheme
    return remember(scheme) { syntaxHighlightPalette(scheme) }
}
