package dev.soupslurpr.beautyxt.ui.editor

import android.content.res.Resources
import dev.soupslurpr.beautyxt.printing.DocumentPrintAdapter
import dev.soupslurpr.beautyxt.printing.PrintDocumentContent
import dev.soupslurpr.beautyxt.printing.PrintSettings
import dev.soupslurpr.beautyxt.printing.suggestPrintDocumentName

/** Owns one exact immutable print representation until Android accepts its adapter. */
internal class DocumentPrintRequest(
    val generation: Long,
    val title: String,
    val settings: PrintSettings,
    content: PrintDocumentContent
) : AutoCloseable {
    private val requestLock = Any()
    private var ownedContent: PrintDocumentContent? = content

    init {
        require(generation > 0L) { "print generation must be positive" }
        require(title.isNotBlank()) { "print title must not be blank" }
    }

    /** Transfers the exact representation into one native Android print lifecycle. */
    fun claimAdapter(resources: Resources): DocumentPrintAdapter {
        val content =
            synchronized(requestLock) {
                checkNotNull(ownedContent) { "print request was already claimed or closed" }
                    .also { ownedContent = null }
            }
        var transferred = false
        try {
            val adapter =
                DocumentPrintAdapter(
                    jobName = title,
                    documentName = suggestPrintDocumentName(title),
                    content = content,
                    settings = settings,
                    resources = resources
                )
            transferred = true
            return adapter
        } finally {
            if (!transferred) {
                content.close()
            }
        }
    }

    /** Releases an unclaimed immutable representation exactly once. */
    override fun close() {
        val content =
            synchronized(requestLock) {
                ownedContent.also { ownedContent = null }
            }
        content?.close()
    }
}
