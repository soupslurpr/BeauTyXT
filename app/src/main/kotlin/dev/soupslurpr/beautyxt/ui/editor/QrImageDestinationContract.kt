package dev.soupslurpr.beautyxt.ui.editor

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import dev.soupslurpr.beautyxt.SelectedDestinationResult

/** Captures the MIME type and filename together before opening the system picker. */
internal data class QrImageDestinationRequest(val title: String, val format: QrImageFormat)

/** Avoids a recomposition race between changing format and launching CreateDocument. */
internal class QrImageDestinationContract : ActivityResultContract<QrImageDestinationRequest, Uri?>() {
    override fun createIntent(context: Context, input: QrImageDestinationRequest): Intent =
        ActivityResultContracts.CreateDocument(input.format.mimeType).createIntent(
            context,
            suggestQrImageDestinationName(input.title, input.format)
        ).addCategory(Intent.CATEGORY_OPENABLE)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        intent?.data?.takeIf { resultCode == Activity.RESULT_OK }
}

/** Preserves offered URI grant flags so QR image export cannot write via an ambient tree grant. */
internal class AuthorizedQrImageDestinationContract :
    ActivityResultContract<QrImageDestinationRequest, SelectedDestinationResult?>() {
    private val delegate = QrImageDestinationContract()

    override fun createIntent(context: Context, input: QrImageDestinationRequest): Intent =
        delegate.createIntent(context, input)

    override fun parseResult(resultCode: Int, intent: Intent?): SelectedDestinationResult? {
        if (resultCode != Activity.RESULT_OK || intent == null) return null
        val uri = intent.data ?: return null
        return SelectedDestinationResult(
            uri = uri,
            offersRead = (intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0,
            offersWrite = (intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0
        )
    }
}
