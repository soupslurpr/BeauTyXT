package dev.soupslurpr.beautyxt.sharing

import android.content.ClipData
import android.content.Intent
import android.content.res.Resources
import androidx.core.net.toUri
import dev.soupslurpr.beautyxt.R
import dev.soupslurpr.beautyxt.ui.editor.DocumentSharePayload
import dev.soupslurpr.beautyxt.ui.editor.DocumentShareRequest

/** Creates one Android chooser for an exact, user-requested document payload. */
internal fun createDocumentShareChooser(
    request: DocumentShareRequest,
    resources: Resources
): Intent {
    val payload = request.payload
    val sendIntent =
        Intent(Intent.ACTION_SEND)
            .setType(payload.format.mimeType)
            .putExtra(Intent.EXTRA_TITLE, payload.title)
    when (payload) {
        is DocumentSharePayload.Source -> {
            val uri = payload.encodedUri.toUri()
            require(uri.scheme == "content" && !uri.authority.isNullOrBlank()) {
                "shared source must be a content URI"
            }
            sendIntent.putExtra(Intent.EXTRA_STREAM, uri)
            sendIntent.clipData = ClipData.newRawUri(payload.title, uri)
            sendIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        is DocumentSharePayload.Text ->
            sendIntent.putExtra(Intent.EXTRA_TEXT, payload.text)
    }
    val title = resources.getString(
        if (payload is DocumentSharePayload.Source) R.string.share_file else R.string.share_text
    )
    return Intent.createChooser(sendIntent, title)
}
