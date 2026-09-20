package dev.soupslurpr.beautyxt.ui.editor

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts

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
