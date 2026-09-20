package dev.soupslurpr.beautyxt.printing

import android.graphics.Bitmap
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import kotlin.math.roundToInt

private const val PDF_CATALOG_OBJECT_ID = 1
private const val PDF_PAGES_OBJECT_ID = 2
private const val PDF_STRUCTURE_OBJECT_ID = 3
private const val PDF_DOCUMENT_OBJECT_ID = 4
private const val PDF_PARENT_TREE_OBJECT_ID = 5
private const val PDF_BLANK_GLYPH_OBJECT_ID = 6
private const val FIRST_DYNAMIC_OBJECT_ID = 7
private const val PDF_FONT_CODES = 255
private const val PDF_CMAP_BATCH_SIZE = 100
private const val MAXIMUM_PDF_OBJECTS = 2_000_000
private const val PDF_GLYPH_UNITS = 1_000
private const val CANCELLATION_GLYPH_INTERVAL = 128
private const val CANCELLATION_ROW_INTERVAL = 32
private const val INITIAL_OBJECT_CAPACITY = 64
private const val PDF_XREF_OFFSET_DIGITS = 10
private const val MAXIMUM_PDF_BYTES = 9_000_000_000L
private const val MAXIMUM_PDF_XREF_OFFSET = 9_999_999_999L
private const val DEFLATE_BUFFER_BYTES = 64 * 1_024
private const val UNRECORDED_OBJECT_OFFSET = -1L

private val PDF_HEADER_BYTES =
    byteArrayOf(
        '%'.code.toByte(),
        'P'.code.toByte(),
        'D'.code.toByte(),
        'F'.code.toByte(),
        '-'.code.toByte(),
        '1'.code.toByte(),
        '.'.code.toByte(),
        '7'.code.toByte(),
        '\n'.code.toByte(),
        '%'.code.toByte(),
        0xe2.toByte(),
        0xe3.toByte(),
        0xcf.toByte(),
        0xd3.toByte(),
        '\n'.code.toByte()
    )

/** Identifies a measured Unicode glyph at the PDF font's normalized em size. */
private data class PdfGlyphKey(val text: String, val width: Int)

/** Normalizes one measured glyph advance without losing its original Unicode sequence. */
private fun pdfGlyphKey(glyph: PdfTextGlyph, layout: PrintRasterLayout): PdfGlyphKey {
    val scaleX = layout.contentWidthPoints.toFloat() / layout.rasterWidthPixels
    val scaleY = layout.contentHeightPoints.toFloat() / layout.rasterHeightPixels
    val aspect = (glyph.right - glyph.left) * scaleX / ((glyph.bottom - glyph.top) * scaleY)
    return PdfGlyphKey(glyph.text, (aspect * PDF_GLYPH_UNITS).roundToInt().coerceAtLeast(1))
}

/** Streams vector pages with measured Unicode, local bitmap fallbacks, and logical structure. */
internal class StreamingPdfWriter(
    destination: OutputStream,
    private val checkCancellation: () -> Unit = {}
) {
    private val output = CountingOutputStream(destination, MAXIMUM_PDF_BYTES)
    private var objectOffsets =
        LongArray(INITIAL_OBJECT_CAPACITY) { UNRECORDED_OBJECT_OFFSET }
    private var nextObjectId = FIRST_DYNAMIC_OBJECT_ID
    private var pageObjects = IntArray(INITIAL_OBJECT_CAPACITY)
    private var sectionObjects = IntArray(INITIAL_OBJECT_CAPACITY)
    private var parentObjects = IntArray(INITIAL_OBJECT_CAPACITY)
    private var finished = false

    var writtenPageCount = 0
        private set

    init {
        checkCancellation()
        output.write(PDF_HEADER_BYTES)
    }

    /** Carries print cancellation through shaping and outline extraction, not only output writes. */
    fun canvas(page: PdfVectorPage, layout: PrintRasterLayout): VectorPrintCanvas =
        VectorPrintCanvas(
            page,
            layout.rasterWidthPixels,
            layout.rasterHeightPixels,
            checkCancellation
        )

    /** Writes one selected page and forgets its graphics, Unicode mappings and semantic segments. */
    fun writePage(page: PdfVectorPage, layout: PrintRasterLayout, text: PdfTextPage) {
        check(!finished) { "PDF writer is finished" }
        val pageObjectId = allocateObject()
        val contentObjectId = allocateObject()
        val visualObjects = IntArray(page.resources.size) { allocateObject() }
        val sectionObjectId = allocateObject()
        val parentObjectId = allocateObject()
        val textIndices = LinkedHashMap<PdfGlyphKey, Int>()
        for (segment in text.segments) {
            for (glyph in segment.glyphs) {
                textIndices.getOrPut(pdfGlyphKey(glyph, layout)) { textIndices.size }
            }
        }
        val unicode = textIndices.keys.toList()
        val fontObjects = IntArray((unicode.size + PDF_FONT_CODES - 1) / PDF_FONT_CODES) {
            allocateObject()
        }
        val structureObjects = IntArray(text.segments.size) { allocateObject() }
        val glyphObjects = LinkedHashMap<Int, Int>()
        for (glyph in unicode) {
            glyphObjects.getOrPut(glyph.width) {
                if (glyph.width == PDF_GLYPH_UNITS) PDF_BLANK_GLYPH_OBJECT_ID else allocateObject()
            }
        }
        rememberPage(pageObjectId, sectionObjectId, parentObjectId)

        writePageObject(
            objectId = pageObjectId,
            contentObjectId = contentObjectId,
            visualObjects = visualObjects,
            alphaValues = page.alphaValues,
            fontObjects = fontObjects,
            layout = layout
        )
        writeContentObject(
            objectId = contentObjectId,
            page = page,
            layout = layout,
            text = text,
            textIndices = textIndices
        )
        fontObjects.forEachIndexed { fontIndex, objectId ->
            val start = fontIndex * PDF_FONT_CODES
            writeFontObject(
                objectId,
                unicode.subList(start, minOf(start + PDF_FONT_CODES, unicode.size)),
                glyphObjects
            )
        }
        for ((width, objectId) in glyphObjects) {
            if (objectId != PDF_BLANK_GLYPH_OBJECT_ID) {
                writeStreamObject(objectId, "$width 0 0 0 $width $PDF_GLYPH_UNITS d1\n")
            }
        }
        page.resources.forEachIndexed { index, resource ->
            writeVisualResource(visualObjects[index], resource, page.monochrome)
        }
        writePageStructure(pageObjectId, sectionObjectId, parentObjectId, structureObjects, text)
        writtenPageCount = Math.incrementExact(writtenPageCount)
    }

    /** Finishes the page tree and cross-reference table exactly once. */
    fun finish() {
        check(!finished) { "PDF writer is finished" }
        require(writtenPageCount > 0) { "PDF must contain at least one page" }
        writeCatalogObject()
        writePagesObject()
        writeStructureTree()
        writeStreamObject(PDF_BLANK_GLYPH_OBJECT_ID, "1000 0 0 0 1000 1000 d1\n")
        val crossReferenceOffset = output.byteCount
        require(crossReferenceOffset <= MAXIMUM_PDF_XREF_OFFSET) {
            "PDF cross-reference offset exceeds its format limit"
        }
        writeCrossReferenceTable()
        writeAscii("trailer\n<< /Size ")
        writeAscii(nextObjectId.toString())
        writeAscii(" /Root $PDF_CATALOG_OBJECT_ID 0 R >>\nstartxref\n")
        writeAscii(crossReferenceOffset.toString())
        writeAscii("\n%%EOF\n")
        output.flush()
        finished = true
    }

    /** Writes one page dictionary referencing only this page's visual resources and text. */
    private fun writePageObject(
        objectId: Int,
        contentObjectId: Int,
        visualObjects: IntArray,
        alphaValues: Set<Int>,
        fontObjects: IntArray,
        layout: PrintRasterLayout
    ) {
        startObject(objectId)
        writeAscii("<< /Type /Page /Parent $PDF_PAGES_OBJECT_ID 0 R /MediaBox [0 0 ")
        writeAscii(layout.pageWidthPoints.toString())
        writeAscii(" ")
        writeAscii(layout.pageHeightPoints.toString())
        writeAscii("] /StructParents $writtenPageCount /Tabs /S /Resources << /XObject <<")
        visualObjects.forEachIndexed { index, id -> writeAscii(" /V$index $id 0 R") }
        writeAscii(" >> /ExtGState <<")
        for (alpha in alphaValues) {
            val value = pdfCoordinate(alpha / 255f)
            writeAscii(" /A$alpha << /Type /ExtGState /ca $value /CA $value >>")
        }
        writeAscii(" >> /Font <<")
        fontObjects.forEachIndexed { fontIndex, fontId -> writeAscii(" /F$fontIndex $fontId 0 R") }
        writeAscii(" >> >> /Contents ")
        writeAscii(contentObjectId.toString())
        writeAscii(" 0 R >>\nendobj\n")
    }

    /** Places the visual artifact and its nonpainting, measured original text at the same margins. */
    private fun writeContentObject(
        objectId: Int,
        page: PdfVectorPage,
        layout: PrintRasterLayout,
        text: PdfTextPage,
        textIndices: Map<PdfGlyphKey, Int>
    ) {
        writeCompressedStream(objectId) { compressed ->
            fun token(value: String) =
                compressed.write(value.toByteArray(StandardCharsets.US_ASCII))
            val scaleX = layout.contentWidthPoints.toFloat() / layout.rasterWidthPixels
            val scaleY = layout.contentHeightPoints.toFloat() / layout.rasterHeightPixels
            token(
                "/Artifact BMC\nq\n${pdfMatrixComponent(
                    scaleX
                )} 0 0 -${pdfMatrixComponent(scaleY)} " +
                    "${layout.contentLeftPoints} " +
                    "${layout.contentBottomPoints + layout.contentHeightPoints} cm\n" +
                    "0 0 ${layout.rasterWidthPixels} ${layout.rasterHeightPixels} re W n\n"
            )
            page.writeDrawing(compressed)
            token("Q\nEMC\n")
            text.segments.forEachIndexed { segmentIndex, segment ->
                // Preserve logical ActualText in the structure element, not a replacement for
                // the entire positioned run: PDFium would reorder an RTL replacement again.
                token("/${segment.role.token} << /MCID $segmentIndex >> BDC\n")
                var lineStart = 0
                while (lineStart < segment.glyphs.size) {
                    val first = segment.glyphs[lineStart]
                    var lineEnd = lineStart + 1
                    var isVisualOrder = true
                    while (lineEnd < segment.glyphs.size &&
                        segment.glyphs[lineEnd].top == first.top
                    ) {
                        if (segment.glyphs[lineEnd].left < segment.glyphs[lineEnd - 1].left) {
                            isVisualOrder = false
                        }
                        lineEnd++
                    }
                    val logicalLine = segment.glyphs.subList(lineStart, lineEnd)
                    val line = if (isVisualOrder) logicalLine else logicalLine.sortedBy { it.left }
                    var currentFont = -1
                    var cursor = 0f
                    val heightPoints = (first.bottom - first.top) * scaleY
                    val height = pdfCoordinate(heightPoints)
                    val bottom = pdfCoordinate(
                        layout.contentBottomPoints + layout.contentHeightPoints -
                            first.bottom * scaleY
                    )
                    for ((glyphIndex, glyph) in line.withIndex()) {
                        if (glyphIndex % CANCELLATION_GLYPH_INTERVAL == 0) checkCancellation()
                        val key = pdfGlyphKey(glyph, layout)
                        val index = textIndices.getValue(key)
                        val font = index / PDF_FONT_CODES
                        val left = layout.contentLeftPoints + glyph.left * scaleX
                        if (font != currentFont) {
                            if (currentFont != -1) token("] TJ ET\n")
                            val leftCoordinate = pdfCoordinate(left)
                            token("BT /F$font $height Tf 3 Tr 1 0 0 1 $leftCoordinate $bottom Tm [")
                            currentFont = font
                            cursor = left
                        }
                        val adjustment =
                            pdfCoordinate((cursor - left) / heightPoints * PDF_GLYPH_UNITS)
                        val code = (index % PDF_FONT_CODES + 1).toString(16).padStart(2, '0')
                        token(" $adjustment <$code>")
                        cursor = left + key.width.toFloat() / PDF_GLYPH_UNITS * heightPoints
                    }
                    if (currentFont != -1) token("] TJ ET\n")
                    lineStart = lineEnd
                }
                token("EMC\n")
            }
        }
    }

    /** Embeds an original blank Type 3 glyph with a page-local exact Unicode mapping. */
    private fun writeFontObject(
        objectId: Int,
        unicode: List<PdfGlyphKey>,
        glyphObjects: Map<Int, Int>
    ) {
        require(unicode.size in 1..PDF_FONT_CODES) { "PDF font code count is invalid" }
        val cmapId = allocateObject()
        startObject(objectId)
        writeAscii(
            "<< /Type /Font /Subtype /Type3 /FontBBox [0 0 ${unicode.maxOf { it.width }} 1000] " +
                "/FontMatrix [.001 0 0 .001 0 0] /CharProcs <<"
        )
        unicode.forEachIndexed { index, glyph ->
            writeAscii(" /g$index ${glyphObjects.getValue(glyph.width)} 0 R")
        }
        writeAscii(" >> /Encoding << /Type /Encoding /Differences [1")
        repeat(unicode.size) { writeAscii(" /g$it") }
        writeAscii("] >> /FirstChar 1 /LastChar ${unicode.size} /Widths [")
        unicode.forEach { writeAscii(" ${it.width}") }
        writeAscii(" ] /Resources << >> /ToUnicode $cmapId 0 R >>\nendobj\n")
        val cmap = buildString {
            append("/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n")
            append(
                "/CIDSystemInfo << /Registry (BeauTyXT) /Ordering (Unicode) /Supplement 0 >> def\n"
            )
            append("/CMapName /BeauTyXTUnicode def\n/CMapType 2 def\n")
            append("1 begincodespacerange\n<01> <FF>\nendcodespacerange\n")
            var start = 0
            while (start < unicode.size) {
                val end = minOf(start + PDF_CMAP_BATCH_SIZE, unicode.size)
                append("${end - start} beginbfchar\n")
                for (index in start until end) {
                    append('<')
                    append((index + 1).toString(16).padStart(2, '0'))
                    append("> <")
                    append(pdfUnicodeHex(unicode[index].text))
                    append(">\n")
                }
                append("endbfchar\n")
                start = end
            }
            append("endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n")
        }
        writeStreamObject(cmapId, cmap)
    }

    /** Writes a bounded ASCII stream whose length is known before serialization. */
    private fun writeStreamObject(objectId: Int, value: String) {
        val bytes = value.toByteArray(StandardCharsets.US_ASCII)
        startObject(objectId)
        writeAscii("<< /Length ${bytes.size} >>\nstream\n")
        output.write(bytes)
        writeAscii("\nendstream\nendobj\n")
    }

    /** Compresses a generated stream directly to the caller's destination with an indirect length. */
    private fun writeCompressedStream(
        objectId: Int,
        dictionary: String = "",
        write: (OutputStream) -> Unit
    ) {
        val lengthId = allocateObject()
        startObject(objectId)
        writeAscii("<< $dictionary /Filter /FlateDecode /Length $lengthId 0 R >>\nstream\n")
        val start = output.byteCount
        val deflater = Deflater(Deflater.BEST_SPEED)
        try {
            val compressed = DeflaterOutputStream(output, deflater, DEFLATE_BUFFER_BYTES)
            write(compressed)
            compressed.finish()
        } finally {
            deflater.end()
        }
        val length = output.byteCount - start
        writeAscii("\nendstream\nendobj\n")
        writeScalarObject(lengthId, length)
    }

    /** Records the page's text order without retaining its content after serialization. */
    private fun writePageStructure(
        pageId: Int,
        sectionId: Int,
        parentId: Int,
        elements: IntArray,
        text: PdfTextPage
    ) {
        startObject(sectionId)
        writeAscii("<< /Type /StructElem /S /Sect /P $PDF_DOCUMENT_OBJECT_ID 0 R /K [")
        elements.forEach { writeAscii(" $it 0 R") }
        writeAscii(" ] >>\nendobj\n")
        startObject(parentId)
        writeAscii("[")
        elements.forEach { writeAscii(" $it 0 R") }
        writeAscii(" ]\nendobj\n")
        elements.forEachIndexed { index, elementId ->
            val segment = text.segments[index]
            startObject(elementId)
            writeAscii(
                "<< /Type /StructElem /S /${segment.role.token} " +
                    "/P $sectionId 0 R /Pg $pageId 0 R /K $index"
            )
            segment.actualText?.let { writeAscii(" /ActualText <FEFF${pdfUnicodeHex(it)}>") }
            writeAscii(" >>\nendobj\n")
        }
    }

    /** Writes the document structure and the page-local marked-content parent lookup. */
    private fun writeStructureTree() {
        startObject(PDF_STRUCTURE_OBJECT_ID)
        writeAscii(
            "<< /Type /StructTreeRoot /K [$PDF_DOCUMENT_OBJECT_ID 0 R] " +
                "/ParentTree $PDF_PARENT_TREE_OBJECT_ID 0 R " +
                "/ParentTreeNextKey $writtenPageCount >>\nendobj\n"
        )
        startObject(PDF_DOCUMENT_OBJECT_ID)
        writeAscii("<< /Type /StructElem /S /Document /P $PDF_STRUCTURE_OBJECT_ID 0 R /K [")
        repeat(writtenPageCount) { writeAscii(" ${sectionObjects[it]} 0 R") }
        writeAscii(" ] >>\nendobj\n")
        startObject(PDF_PARENT_TREE_OBJECT_ID)
        writeAscii("<< /Nums [")
        repeat(writtenPageCount) { writeAscii(" $it ${parentObjects[it]} 0 R") }
        writeAscii(" ] >>\nendobj\n")
    }

    /** Retains only three primitive object references for each completed page. */
    private fun rememberPage(pageId: Int, sectionId: Int, parentId: Int) {
        if (writtenPageCount == pageObjects.size) {
            val capacity = Math.multiplyExact(pageObjects.size, 2)
            pageObjects = pageObjects.copyOf(capacity)
            sectionObjects = sectionObjects.copyOf(capacity)
            parentObjects = parentObjects.copyOf(capacity)
        }
        pageObjects[writtenPageCount] = pageId
        sectionObjects[writtenPageCount] = sectionId
        parentObjects[writtenPageCount] = parentId
    }

    /** Reserves a unique object identifier within the metadata memory budget. */
    private fun allocateObject(): Int {
        if (nextObjectId >= MAXIMUM_PDF_OBJECTS) {
            throw IOException("printed PDF exceeds its object limit")
        }
        return nextObjectId++
    }

    /** Streams reusable outlines or bounded, alpha-preserving native color-glyph images. */
    private fun writeVisualResource(
        objectId: Int,
        resource: PdfVisualResource,
        monochrome: Boolean
    ) {
        when (resource) {
            is PdfVisualResource.Outline -> {
                val bounds = resource.bounds
                val box = listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)
                    .joinToString(" ", transform = ::pdfCoordinate)
                writeCompressedStream(
                    objectId,
                    "/Type /XObject /Subtype /Form /FormType 1 /BBox [$box] /Resources << >>"
                ) { it.write(resource.commands) }
            }

            is PdfVisualResource.Image -> {
                val alphaId = allocateObject()
                writeImageObject(alphaId, resource.bitmap, monochrome = true, alphaOnly = true)
                writeImageObject(objectId, resource.bitmap, monochrome, alphaId = alphaId)
            }
        }
    }

    /** Writes one image row at a time; samples are unpremultiplied and the mask owns alpha. */
    private fun writeImageObject(
        objectId: Int,
        bitmap: Bitmap,
        monochrome: Boolean,
        alphaOnly: Boolean = false,
        alphaId: Int? = null
    ) {
        val channels = if (monochrome) 1 else 3
        val colors = IntArray(bitmap.width)
        val samples = ByteArray(bitmap.width * channels)
        val colorSpace = if (monochrome) "/DeviceGray" else "/DeviceRGB"
        val mask = alphaId?.let { "/SMask $it 0 R" }.orEmpty()
        writeCompressedStream(
            objectId,
            "/Type /XObject /Subtype /Image /Width ${bitmap.width} /Height ${bitmap.height} " +
                "/ColorSpace $colorSpace /BitsPerComponent 8 $mask"
        ) { compressed ->
            repeat(bitmap.height) { row ->
                if (row % CANCELLATION_ROW_INTERVAL == 0) checkCancellation()
                bitmap.getPixels(colors, 0, bitmap.width, 0, row, bitmap.width, 1)
                if (alphaOnly) {
                    colors.forEachIndexed { index, color ->
                        samples[index] =
                            (color ushr 24).toByte()
                    }
                } else if (monochrome) {
                    printGrayscaleRow(colors, samples, bitmap.width)
                } else {
                    colors.forEachIndexed { index, color ->
                        samples[index * 3] = (color ushr 16).toByte()
                        samples[index * 3 + 1] = (color ushr 8).toByte()
                        samples[index * 3 + 2] = color.toByte()
                    }
                }
                compressed.write(samples)
            }
        }
    }

    /** Writes one integer-valued indirect object. */
    private fun writeScalarObject(objectId: Int, value: Long) {
        require(value >= 0L) { "PDF scalar value must be nonnegative" }
        startObject(objectId)
        writeAscii(value.toString())
        writeAscii("\nendobj\n")
    }

    /** Writes the catalog after every forward reference is known. */
    private fun writeCatalogObject() {
        startObject(PDF_CATALOG_OBJECT_ID)
        writeAscii(
            "<< /Type /Catalog /Pages $PDF_PAGES_OBJECT_ID 0 R " +
                "/MarkInfo << /Marked true >> /StructTreeRoot $PDF_STRUCTURE_OBJECT_ID 0 R >>\nendobj\n"
        )
    }

    /** Writes the selected-page tree from compact retained object references. */
    private fun writePagesObject() {
        startObject(PDF_PAGES_OBJECT_ID)
        writeAscii("<< /Type /Pages /Count $writtenPageCount /Kids [")
        repeat(writtenPageCount) { pageIndex ->
            val pageObjectId = pageObjects[pageIndex]
            writeAscii(" $pageObjectId 0 R")
        }
        writeAscii(" ] >>\nendobj\n")
    }

    /** Writes a traditional cross-reference table for every exact object offset. */
    private fun writeCrossReferenceTable() {
        writeAscii("xref\n0 $nextObjectId\n")
        writeAscii("0000000000 65535 f \n")
        for (objectId in 1 until nextObjectId) {
            if (objectId % CANCELLATION_GLYPH_INTERVAL == 0) checkCancellation()
            val offset = objectOffsets[objectId]
            check(offset in 0L..MAXIMUM_PDF_XREF_OFFSET) {
                "PDF object offset is missing or too large"
            }
            writeFixedWidthDecimal(offset, PDF_XREF_OFFSET_DIGITS)
            writeAscii(" 00000 n \n")
        }
    }

    /** Starts one unique object and records its current byte offset. */
    private fun startObject(objectId: Int) {
        checkCancellation()
        require(objectId > 0) { "PDF object identifier must be positive" }
        ensureObjectCapacity(objectId)
        check(objectOffsets[objectId] == UNRECORDED_OBJECT_OFFSET) {
            "PDF object identifier was already written"
        }
        objectOffsets[objectId] = output.byteCount
        writeAscii(objectId.toString())
        writeAscii(" 0 obj\n")
    }

    /** Grows the primitive offset table for one object identifier. */
    private fun ensureObjectCapacity(objectId: Int) {
        if (objectId < objectOffsets.size) {
            return
        }
        var capacity = objectOffsets.size
        while (objectId >= capacity) {
            capacity = Math.multiplyExact(capacity, 2)
        }
        objectOffsets =
            objectOffsets.copyOf(capacity).also { offsets ->
                offsets.fill(
                    element = UNRECORDED_OBJECT_OFFSET,
                    fromIndex = objectOffsets.size,
                    toIndex = capacity
                )
            }
    }

    /** Writes one fixed-width nonnegative decimal without locale-sensitive formatting. */
    private fun writeFixedWidthDecimal(value: Long, width: Int) {
        require(value >= 0L) { "fixed-width PDF value must be nonnegative" }
        require(width > 0) { "fixed-width PDF field must be positive" }
        var remaining = value
        val bytes = ByteArray(width) { '0'.code.toByte() }
        var position = width - 1
        while (remaining > 0L && position >= 0) {
            bytes[position] = ('0'.code + (remaining % 10L).toInt()).toByte()
            remaining /= 10L
            position--
        }
        require(remaining == 0L) { "PDF value exceeds its fixed-width field" }
        output.write(bytes)
    }

    /** Writes one ASCII-only PDF token sequence. */
    private fun writeAscii(value: String) {
        output.write(value.toByteArray(StandardCharsets.US_ASCII))
    }
}

/** Counts output bytes and fails before the bounded PDF format limit is crossed. */
private class CountingOutputStream(
    private val destination: OutputStream,
    private val maxBytes: Long
) : OutputStream() {
    var byteCount = 0L
        private set

    init {
        require(maxBytes > 0L) { "output byte limit must be positive" }
    }

    /** Writes one byte while preserving the exact output count. */
    override fun write(value: Int) {
        reserve(1)
        destination.write(value)
        byteCount++
    }

    /** Writes one byte range while preserving the exact output count. */
    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset <= buffer.size - length) {
            "output byte range is invalid"
        }
        reserve(length)
        destination.write(buffer, offset, length)
        byteCount += length.toLong()
    }

    /** Flushes the wrapped print destination without closing it. */
    override fun flush() {
        destination.flush()
    }

    /** Fails before one write would cross the configured output bound. */
    private fun reserve(length: Int) {
        if (length < 0 || byteCount > maxBytes - length.toLong()) {
            throw IOException("printed PDF exceeds its output limit")
        }
    }
}
