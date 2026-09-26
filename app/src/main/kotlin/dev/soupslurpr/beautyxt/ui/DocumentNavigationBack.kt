package dev.soupslurpr.beautyxt.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf

/** Lets navigation commit Back through the current document's close guard. */
internal class DocumentNavigationBack {
    var action: (() -> Unit)? = null

    fun requestBack() {
        action?.invoke()
    }
}

internal val LocalDocumentNavigationBack = staticCompositionLocalOf<DocumentNavigationBack?> { null }

/** Keeps the latest close action installed until its exact screen leaves composition. */
@Composable
internal fun BindDocumentNavigationBack(onBack: () -> Unit) {
    val navigation = LocalDocumentNavigationBack.current
    val currentOnBack by rememberUpdatedState(onBack)
    DisposableEffect(navigation) {
        val action = { currentOnBack() }
        navigation?.action = action
        onDispose {
            if (navigation?.action === action) navigation.action = null
        }
    }
}
