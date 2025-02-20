package oldtyxt.dev.soupslurpr.beautyxt.ui

import android.app.Service
import android.content.Intent
import android.os.IBinder
import oldtyxt.dev.soupslurpr.beautyxt.IFileViewModelRustLibraryAidlInterface

class FileViewModelRustLibraryIsolatedService : Service() {
    private val binder = object : IFileViewModelRustLibraryAidlInterface.Stub() {
        override fun markdownToHtml(markdown: String?): String? {
            return markdown?.let {
                oldtyxt.uniffi.beautyxt_rs_plain_text_and_markdown_bindings.markdownToHtml(
                    it
                )
            }
        }

        override fun markdownToDocx(markdown: String?): ByteArray? {
            return markdown?.let {
                oldtyxt.uniffi.beautyxt_rs_plain_text_and_markdown_bindings.markdownToDocx(
                    it
                )
            }
        }

        override fun plainTextToDocx(plainText: String?): ByteArray? {
            return plainText?.let {
                oldtyxt.uniffi.beautyxt_rs_plain_text_and_markdown_bindings.plainTextToDocx(
                    it
                )
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }
}