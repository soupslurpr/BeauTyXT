package dev.soupslurpr.beautyxt.ui

import android.view.inputmethod.EditorInfo
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest

/** Adds only Android's advisory non-personalization flag to each Compose input connection. */
internal fun keyboardPrivacyRequest(
    request: PlatformTextInputMethodRequest
): PlatformTextInputMethodRequest = PlatformTextInputMethodRequest { attributes ->
    request.createInputConnection(attributes).also {
        attributes.imeOptions =
            attributes.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
    }
}

// Stable identity avoids restarting a live input session on ordinary recomposition.
private val KeyboardPrivacyInterceptor = PlatformTextInputInterceptor { request, next ->
    next.startInputMethod(keyboardPrivacyRequest(request))
}

/** This is a request to the chosen keyboard, not isolation from it or a no-logging guarantee. */
@Composable
internal fun RequestKeyboardPrivacy(content: @Composable () -> Unit) {
    InterceptPlatformTextInput(KeyboardPrivacyInterceptor, content)
}
