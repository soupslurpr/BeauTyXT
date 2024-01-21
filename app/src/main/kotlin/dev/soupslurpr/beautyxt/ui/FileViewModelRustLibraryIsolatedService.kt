package dev.soupslurpr.beautyxt.ui

import android.app.Service
import android.content.Intent
import android.os.IBinder
import dev.soupslurpr.beautyxt.IFileViewModelRustLibraryAidlInterface

class FileViewModelRustLibraryIsolatedService : Service() {
    private val binder = object : IFileViewModelRustLibraryAidlInterface.Stub() {
        override fun markdownToHtml(markdown: String?): String {
            return dev.soupslurpr.beautyxt.bindings.markdownToHtml(markdown!!)
        }

        override fun markdownToDocx(markdown: String?): ByteArray {
            return dev.soupslurpr.beautyxt.bindings.markdownToDocx(markdown!!)
        }

        override fun plainTextToDocx(plainText: String?): ByteArray {
            return dev.soupslurpr.beautyxt.bindings.plainTextToDocx(plainText!!)
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }
}