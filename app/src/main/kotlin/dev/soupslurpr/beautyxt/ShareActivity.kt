package dev.soupslurpr.beautyxt

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import dev.soupslurpr.beautyxt.sharing.IncomingDocumentShare
import dev.soupslurpr.beautyxt.sharing.parseIncomingDocumentShare

/** Normalizes one external share into an independent document task. */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.action != Intent.ACTION_SEND) {
            finish()
            return
        }
        val share = parseIncomingDocumentShare(intent)
        if (
            share is IncomingDocumentShare.Source &&
            enforceIncomingCallerUriAccess(this, intent, initialCaller, share) is
                IncomingDocumentShare.Rejected
        ) {
            finish()
            return
        }
        try {
            startActivity(createSharedDocumentSessionIntent(this, intent))
        } catch (_: Exception) {
            finish()
            return
        }
        finish()
    }
}
