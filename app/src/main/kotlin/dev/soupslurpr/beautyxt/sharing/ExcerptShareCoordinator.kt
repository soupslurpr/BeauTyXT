package dev.soupslurpr.beautyxt.sharing

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.ResultReceiver
import androidx.core.net.toUri
import java.util.UUID

internal data class ExcerptShareRequest(val id: String, val chooser: Intent)

/** Distinguishes a dismissed chooser from a receiver that may open its file later. */
internal class ExcerptShareCoordinator : AutoCloseable {
    private val pending = mutableMapOf<String, PendingExcerptShare>()
    private val delivered = mutableListOf<ExcerptShareLease>()
    val hasDeliveredFiles get() = delivered.isNotEmpty()

    fun prepare(context: Context, target: Intent, title: String, lease: ExcerptShareLease?): ExcerptShareRequest {
        val id = UUID.randomUUID().toString()
        val callback = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, ExcerptShareRefinementReceiver::class.java)
                .setData("beautyxt-excerpt-share:$id".toUri()),
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_MUTABLE
        )
        val entry = PendingExcerptShare(Intent(target), lease, callback)
        val chooser = Intent.createChooser(target, title)
            .putExtra(Intent.EXTRA_CHOOSER_REFINEMENT_INTENT_SENDER, callback.intentSender)
        pending[id] = entry
        ExcerptShareRefinements.add(id, entry)
        return ExcerptShareRequest(id, chooser)
    }

    fun returned(id: String) {
        val entry = remove(id) ?: return
        entry.lease?.let { if (entry.selected) delivered += it else it.close() }
    }

    fun failed(id: String) {
        remove(id)?.lease?.close()
    }

    private fun remove(id: String): PendingExcerptShare? {
        val entry = pending.remove(id) ?: return null
        ExcerptShareRefinements.remove(id)
        entry.callback.cancel()
        return entry
    }

    override fun close() {
        pending.keys.toList().forEach(::failed)
        releaseDeliveredFiles()
    }

    fun releaseDeliveredFiles() {
        delivered.forEach { it.close() }
        delivered.clear()
    }
}

private class PendingExcerptShare(
    val target: Intent,
    val lease: ExcerptShareLease?,
    val callback: PendingIntent,
    var selected: Boolean = false
)

private object ExcerptShareRefinements {
    private val entries = mutableMapOf<String, PendingExcerptShare>()

    @Synchronized
    fun add(id: String, entry: PendingExcerptShare) {
        check(entries.put(id, entry) == null)
    }

    @Synchronized
    fun remove(id: String) {
        entries.remove(id)
    }

    @Synchronized
    fun select(id: String?): Intent? {
        val entry = entries[id] ?: return null
        entry.selected = true
        return Intent(entry.target)
    }
}

/** The chooser waits for this acknowledgment before launching its selected receiver. */
class ExcerptShareRefinementReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reply = intent.getParcelableExtra(Intent.EXTRA_RESULT_RECEIVER, ResultReceiver::class.java) ?: return
        val id = intent.data?.takeIf { it.scheme == "beautyxt-excerpt-share" }?.schemeSpecificPart
        val target = ExcerptShareRefinements.select(id)
        if (target == null) {
            reply.send(Activity.RESULT_CANCELED, null)
        } else {
            // Ordinary chooser-result callbacks can arrive after activity results. Refinement
            // records ownership before handoff, so cancellation cannot revoke a selected file.
            reply.send(Activity.RESULT_OK, Bundle().apply { putParcelable(Intent.EXTRA_INTENT, target) })
        }
    }
}
