package dev.soupslurpr.beautyxt.importing.client

import android.provider.DocumentsContract
import dev.soupslurpr.beautyxt.document.DocumentRemovalAction
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies exact translation of independent provider removal flags. */
class SelectedDocumentRemovalTest {
    /** Verifies a provider may expose trash without permanent deletion. */
    @Test
    fun mapsTrashOnlyCapability() {
        val capabilities =
            documentRemovalCapabilities(
                DocumentsContract.Document.FLAG_SUPPORTS_TRASH
            )

        assertTrue(capabilities.supports(DocumentRemovalAction.Trash))
        assertFalse(capabilities.supports(DocumentRemovalAction.Delete))
    }

    /** Verifies a provider may expose permanent deletion without trash. */
    @Test
    fun mapsDeleteOnlyCapability() {
        val capabilities =
            documentRemovalCapabilities(
                DocumentsContract.Document.FLAG_SUPPORTS_DELETE
            )

        assertFalse(capabilities.supports(DocumentRemovalAction.Trash))
        assertTrue(capabilities.supports(DocumentRemovalAction.Delete))
    }

    /** Verifies unrelated document flags never imply a destructive operation. */
    @Test
    fun rejectsUnrelatedCapabilities() {
        val capabilities =
            documentRemovalCapabilities(
                DocumentsContract.Document.FLAG_SUPPORTS_WRITE or
                    DocumentsContract.Document.FLAG_SUPPORTS_RENAME
            )

        assertFalse(capabilities.supports(DocumentRemovalAction.Trash))
        assertFalse(capabilities.supports(DocumentRemovalAction.Delete))
    }
}
