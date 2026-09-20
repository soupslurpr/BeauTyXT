/* Exports code to the system clipboard only after an explicit user action. */
package dev.soupslurpr.beautyxt.ui.editor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import dev.soupslurpr.beautyxt.R

internal val LocalMarkdownCodeCopies =
    staticCompositionLocalOf<Map<Int, MarkdownCodeCopy>> { emptyMap() }

/** Gives the first fragment a copy action, without repeating it for lazy continuations. */
@Composable
internal fun MarkdownCodeHeader(
    metadata: String,
    copy: MarkdownCodeCopy?,
    copyDescription: String? = null,
    extraAction: (@Composable () -> Unit)? = null
) {
    val context = LocalContext.current
    val copyLabel = copyDescription ?: stringResource(R.string.markdown_copy_code)
    var copyFailed by remember(copy) { mutableStateOf(false) }
    if (metadata.isEmpty() && copy == null) return
    Column(verticalArrangement = Arrangement.spacedBy(EditorCompactSpacing)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(EditorCompactSpacing),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (metadata.isEmpty()) stringResource(R.string.markdown_code) else metadata,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
            extraAction?.invoke()
            if (copy != null) {
                TextButton(
                    onClick = { copyFailed = !copyMarkdownCode(context, copy) },
                    enabled = copy.canCopy,
                    modifier = Modifier.semantics { contentDescription = copyLabel }
                ) {
                    Text(stringResource(R.string.markdown_copy))
                }
            }
        }
        if (copy != null && !copy.canCopy && !copy.isEmpty) {
            Text(
                text = stringResource(R.string.markdown_code_copy_limit),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (copyFailed) {
            Text(
                text = stringResource(R.string.markdown_code_copy_failed),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

/** Uses plain text, with no URI grants or active content, and ordinary system previews. */
internal fun copyMarkdownCode(context: Context, copy: MarkdownCodeCopy): Boolean {
    val text = copy.textOrNull() ?: return false
    return try {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
        val clip = ClipData.newPlainText(context.getString(R.string.markdown_code), text)
        clipboard.setPrimaryClip(clip)
        // Android supplies copy confirmation; don't duplicate it or read the clipboard back.
        true
    } catch (_: RuntimeException) {
        false
    }
}
