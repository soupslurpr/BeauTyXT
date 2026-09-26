package dev.soupslurpr.beautyxt.testing

import android.app.Activity
import android.content.ClipData
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore

/** Opens BeauTyXT from another UID and keeps a caller window behind its document task. */
class DocumentCallerActivity : Activity() {
    private var source: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Document caller fixture"
        source = savedInstanceState?.getParcelable("source", Uri::class.java)
        if (savedInstanceState != null) return

        val action = intent.getStringExtra(DocumentCallerContract.ACTION_EXTRA)
        require(action in setOf(Intent.ACTION_VIEW, Intent.ACTION_EDIT, Intent.ACTION_SEND))
        val request = Intent(action)
            .setPackage("dev.soupslurpr.beautyxt.debug")
            .setType("text/plain")
        if (action == Intent.ACTION_SEND && intent.hasExtra(Intent.EXTRA_TEXT)) {
            request.putExtra(Intent.EXTRA_TEXT, DocumentCallerContract.SHARED_TEXT)
        } else {
            val uri = checkNotNull(contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "external-caller.txt")
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            ))
            source = uri
            checkNotNull(contentResolver.openOutputStream(uri, "wt")).use {
                it.write(DocumentCallerContract.SOURCE_TEXT.toByteArray(Charsets.UTF_8))
            }
            check(contentResolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null) == 1)
            request.clipData = ClipData.newRawUri("Caller source", uri)
            request.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (action == Intent.ACTION_EDIT) {
                request.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            if (action == Intent.ACTION_SEND) request.putExtra(Intent.EXTRA_STREAM, uri)
            else request.setDataAndType(uri, "text/plain")
        }
        startActivity(request)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        source?.let { outState.putParcelable("source", it) }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (isFinishing) source?.let { contentResolver.delete(it, null, null) }
        super.onDestroy()
    }
}
