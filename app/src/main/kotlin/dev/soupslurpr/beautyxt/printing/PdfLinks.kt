package dev.soupslurpr.beautyxt.printing

/** Passive layout metadata; only the PDF writer interprets these spans. */
internal data class PdfLinkSpan(val destination: String, val internal: Boolean)
internal data class PdfAnchorSpan(val name: String)
internal data class PdfLinkBox(val target: PdfLinkSpan, val left: Float, val top: Float, val right: Float, val bottom: Float)
internal data class PdfAnchor(val name: String, val left: Float, val top: Float)
