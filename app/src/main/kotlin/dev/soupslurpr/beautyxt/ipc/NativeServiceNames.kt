package dev.soupslurpr.beautyxt.ipc

/** Manifest component names for ART-free services; these are not JVM classes. */
internal object NativeServiceNames {
    const val IMPORT = "dev.soupslurpr.beautyxt.importing.IsolatedImportService"
    const val EXPORT = "dev.soupslurpr.beautyxt.exporting.IsolatedExportService"
    const val MARKDOWN = "dev.soupslurpr.beautyxt.markdown.IsolatedMarkdownService"
    const val TRANSFER = "dev.soupslurpr.beautyxt.transfer.IsolatedTransferService"
    const val MATH = "dev.soupslurpr.beautyxt.illustration.IsolatedMathService"
    const val DIAGRAM = "dev.soupslurpr.beautyxt.illustration.IsolatedDiagramService"
}
