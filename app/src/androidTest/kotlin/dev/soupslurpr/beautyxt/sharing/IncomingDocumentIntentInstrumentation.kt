package dev.soupslurpr.beautyxt.sharing

import android.app.Activity
import android.app.Instrumentation
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import dev.soupslurpr.beautyxt.HomeActivity
import dev.soupslurpr.beautyxt.MainActivity
import dev.soupslurpr.beautyxt.SelectDocumentContract
import dev.soupslurpr.beautyxt.ShareActivity
import dev.soupslurpr.beautyxt.createSharedDocumentSessionIntent
import dev.soupslurpr.beautyxt.document.DocumentFormat
import dev.soupslurpr.beautyxt.documentSessionShare

private const val TEST_UNTRUSTED_EXTRA = "example.untrusted.EXTRA"

/** Verifies Android file intents enter only their bounded, capability-aware routes. */
internal fun Instrumentation.verifyIncomingDocumentIntents() {
    verifyDocumentSelectionContract(targetContext)
    val sourceUri = Uri.parse("content://documents.example/document/notes.md")
    val viewSource =
        parseIncomingDocumentShare(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(sourceUri, "application/octet-stream")
        ) as IncomingDocumentShare.Source
    val editSource =
        parseIncomingDocumentShare(
            Intent(Intent.ACTION_EDIT)
                .setDataAndType(sourceUri, "text/markdown")
        ) as IncomingDocumentShare.Source
    check(viewSource.encodedUri == sourceUri.toString()) {
        "view intent changed its source URI"
    }
    check(viewSource.format == DocumentFormat.Markdown) {
        "view intent did not infer its Markdown source suffix"
    }
    check(viewSource.purpose == IncomingSourcePurpose.View) {
        "view intent did not retain read-only purpose"
    }
    check(editSource.purpose == IncomingSourcePurpose.Edit) {
        "edit intent did not retain editable purpose"
    }

    val sharedText =
        parseIncomingDocumentShare(
            Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "one\r\ntwo")
        ) as IncomingDocumentShare.Text
    check(sharedText.text == "one\ntwo") {
        "shared text was not normalized"
    }
    check(sharedText.format == DocumentFormat.PlainText) {
        "shared text did not retain its format"
    }

    for (text in listOf("x".repeat(50_000), "x".repeat(MAX_SHARED_TEXT_UTF8_BYTES.toInt()))) {
        val accepted = parseIncomingDocumentShare(
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        )
        check(accepted is IncomingDocumentShare.Text && accepted.text == text) {
            "direct text did not round-trip within the outgoing share budget"
        }
    }
    for (text in listOf("x".repeat(MAX_INCOMING_TEXT_UTF16_UNITS + 1), "😀".repeat(32_769))) {
        val rejected = parseIncomingDocumentShare(
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        )
        check(rejected is IncomingDocumentShare.Rejected) {
            "direct text exceeded its shared UTF-8 budget"
        }
    }

    val fileOffer =
        parseIncomingDocumentShare(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(Uri.parse("file:///sdcard/Download/notes.txt"), "text/plain")
        )
    check(fileOffer is IncomingDocumentShare.Rejected) {
        "non-content source intent was accepted"
    }
    val firstUri = Uri.parse("content://documents.example/document/one.txt")
    val secondUri = Uri.parse("content://documents.example/document/two.txt")
    val ambiguousOffer =
        parseIncomingDocumentShare(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(firstUri, "text/plain")
                .apply {
                    clipData =
                        ClipData.newRawUri("one", firstUri).apply {
                            addItem(ClipData.Item(secondUri))
                        }
                }
        )
    check(ambiguousOffer is IncomingDocumentShare.Rejected) {
        "ambiguous source intent was accepted"
    }

    check(resolvesMainActivity(targetContext, Intent.ACTION_VIEW, firstUri)) {
        "text view intent did not resolve to MainActivity"
    }
    check(resolvesMainActivity(targetContext, Intent.ACTION_EDIT, firstUri)) {
        "text edit intent did not resolve to MainActivity"
    }
    check(resolvesShareActivity(targetContext)) {
        "text share intent did not resolve to ShareActivity"
    }
    check(resolvesHomeActivity(targetContext)) {
        "launcher intent did not resolve to HomeActivity"
    }
    verifyDocumentActivityManifest(targetContext)

    val forwardedShareIntent =
        createSharedDocumentSessionIntent(
            context = targetContext,
            source =
                Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, "forwarded")
                    .putExtra(TEST_UNTRUSTED_EXTRA, "discard this")
        )
    val forwardedShare = documentSessionShare(forwardedShareIntent)
        as IncomingDocumentShare.Text
    check(forwardedShare.text == "forwarded") {
        "share handoff changed its bounded text payload"
    }
    check(forwardedShareIntent.data?.scheme == "beautyxt") {
        "share handoff did not receive an opaque task identity"
    }
    check(!forwardedShareIntent.hasExtra(TEST_UNTRUSTED_EXTRA)) {
        "share handoff retained an unrelated external extra"
    }

    val forwardedSourceIntent =
        createSharedDocumentSessionIntent(
            context = targetContext,
            source =
                Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_STREAM, secondUri)
                    .putExtra(TEST_UNTRUSTED_EXTRA, "discard this")
                    .apply {
                        clipData = ClipData.newRawUri("two", secondUri)
                        addFlags(
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                    }
        )
    val forwardedSource = documentSessionShare(forwardedSourceIntent)
        as IncomingDocumentShare.Source
    check(forwardedSource.encodedUri == secondUri.toString()) {
        "share handoff changed its provider source"
    }
    check(
        forwardedSourceIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0 &&
            forwardedSourceIntent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0 &&
            forwardedSourceIntent.clipData?.getItemAt(0)?.uri == secondUri
    ) {
        "share handoff lost its exact provider capabilities"
    }
    check(!forwardedSourceIntent.hasExtra(TEST_UNTRUSTED_EXTRA)) {
        "source handoff retained an unrelated external extra"
    }
}

/** Verifies cancellation cannot turn returned picker data into a selected source. */
private fun verifyDocumentSelectionContract(context: Context) {
    val contract = SelectDocumentContract()
    val picker = contract.createIntent(context, Unit)
    check(
        picker.action == Intent.ACTION_OPEN_DOCUMENT &&
            picker.hasCategory(Intent.CATEGORY_OPENABLE) &&
            picker.type == "*/*" &&
            !picker.getStringArrayExtra(Intent.EXTRA_MIME_TYPES).isNullOrEmpty()
    ) {
        "document selection contract changed its picker restrictions"
    }
    val result =
        Intent()
            .setDataAndType(
                Uri.parse("content://documents.example/document/picker.md"),
                "text/markdown"
            ).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    check(contract.parseResult(Activity.RESULT_CANCELED, result) == null) {
        "cancelled picker result retained a source offer"
    }
    check(contract.parseResult(Activity.RESULT_FIRST_USER, result) == null) {
        "unsuccessful picker result retained a source offer"
    }
    check(contract.parseResult(Activity.RESULT_OK, null) == null) {
        "empty picker result fabricated a source offer"
    }
    check(contract.parseResult(Activity.RESULT_OK, result) === result) {
        "successful picker result lost its original MIME or grant metadata"
    }
}

/** Returns whether one external text action resolves to this app's main activity. */
private fun resolvesMainActivity(context: Context, action: String, uri: Uri): Boolean {
    val request =
        Intent(action)
            .setDataAndType(uri, "text/plain")
            .addCategory(Intent.CATEGORY_DEFAULT)
    return context.packageManager
        .queryIntentActivities(
            request,
            PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
        ).any { match ->
            match.activityInfo.packageName == context.packageName &&
                match.activityInfo.name == MainActivity::class.java.name
        }
}

/** Returns whether Android routes supported text shares through the dispatcher. */
private fun resolvesShareActivity(context: Context): Boolean {
    val request =
        Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .addCategory(Intent.CATEGORY_DEFAULT)
    return context.packageManager
        .queryIntentActivities(
            request,
            PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
        ).any { match ->
            match.activityInfo.packageName == context.packageName &&
                match.activityInfo.name == ShareActivity::class.java.name
        }
}

/** Returns whether Android routes launcher entry through stable Home. */
private fun resolvesHomeActivity(context: Context): Boolean {
    val request =
        Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(context.packageName)
    return context.packageManager
        .queryIntentActivities(
            request,
            PackageManager.ResolveInfoFlags.of(0L)
        ).any { match ->
            match.activityInfo.packageName == context.packageName &&
                match.activityInfo.name == HomeActivity::class.java.name
        }
}

/** Verifies the manifest enforces separate internal and external task policies. */
private fun verifyDocumentActivityManifest(context: Context) {
    val packageManager = context.packageManager
    val externalDocument =
        packageManager.getActivityInfo(
            ComponentName(context, MainActivity::class.java),
            PackageManager.ComponentInfoFlags.of(0L)
        )
    check(externalDocument.exported) {
        "MainActivity stopped accepting external document requests"
    }
    check(
        externalDocument.documentLaunchMode ==
            android.content.pm.ActivityInfo.DOCUMENT_LAUNCH_INTO_EXISTING
    ) {
        "MainActivity stopped canonicalizing external document tasks"
    }
}
