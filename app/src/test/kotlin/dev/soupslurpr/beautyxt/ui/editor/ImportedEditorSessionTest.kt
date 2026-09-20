package dev.soupslurpr.beautyxt.ui.editor

import dev.soupslurpr.beautyxt.importing.client.ImportedSourceAccess
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies imported provider capabilities follow their requested editor policy. */
class ImportedEditorSessionTest {
    /** Verifies only preferred and verified write access can become an autosave source. */
    @Test
    fun selectsWritableSourcesOnlyUnderWritablePolicy() {
        assertTrue(
            shouldUseWritableImportedSource(
                sourceAccess = ImportedSourceAccess.ReadWrite,
                sourcePolicy = ImportedSourcePolicy.PreferWritable
            )
        )
        assertFalse(
            shouldUseWritableImportedSource(
                sourceAccess = ImportedSourceAccess.ReadOnly,
                sourcePolicy = ImportedSourcePolicy.PreferWritable
            )
        )
        assertFalse(
            shouldUseWritableImportedSource(
                sourceAccess = ImportedSourceAccess.ReadWrite,
                sourcePolicy = ImportedSourcePolicy.ReadOnly
            )
        )
    }
}
