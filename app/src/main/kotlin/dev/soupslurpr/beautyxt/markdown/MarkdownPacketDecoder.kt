package dev.soupslurpr.beautyxt.markdown

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

private val PACKET_MAGIC = "BTXTMDR4".toByteArray(StandardCharsets.US_ASCII)
private const val PACKET_VERSION = 4L
private const val PACKET_HEADER_BYTES = 80L
private const val BLOCK_HEADER_BYTES = 72L
private const val SPAN_HEADER_BYTES = 24L
private const val SOURCE_MAP_HEADER_BYTES = 24L
private const val MAX_METADATA_BYTES = 4L * 1024L
private const val MAX_LINK_DESTINATION_BYTES = 4L * 1024L
private const val MAX_QUOTE_DEPTH = 32L
private const val MAX_LIST_DEPTH = 32L
private const val DOCUMENT_FLAG_RAW_HTML = 1L
private const val DOCUMENT_FLAGS_ALLOWED = DOCUMENT_FLAG_RAW_HTML
private const val BLOCK_FLAG_CONTINUATION = 1L
private const val BLOCK_FLAG_ORDERED_LIST = 1L shl 1
private const val BLOCK_FLAG_TASK_CHECKED = 1L shl 2
private const val BLOCK_FLAG_TASK_UNCHECKED = 1L shl 3
private const val BLOCK_FLAG_TABLE_HEADER = 1L shl 4
private const val BLOCK_FLAG_RAW_HTML = 1L shl 5
private const val BLOCK_FLAG_QUOTE_NOTE = 1L shl 6
private const val BLOCK_FLAG_QUOTE_TIP = 1L shl 7
private const val BLOCK_FLAG_QUOTE_IMPORTANT = 1L shl 8
private const val BLOCK_FLAG_QUOTE_WARNING = 1L shl 9
private const val BLOCK_FLAG_QUOTE_CAUTION = 1L shl 10
private const val BLOCK_FLAG_QUOTE_ALERT_START = 1L shl 11
private const val BLOCK_FLAG_TABLE_START = 1L shl 12
private const val BLOCK_FLAG_LIST_ITEM_CONTINUATION = 1L shl 13
private const val BLOCK_FLAGS_QUOTE_KIND =
    BLOCK_FLAG_QUOTE_NOTE or
        BLOCK_FLAG_QUOTE_TIP or
        BLOCK_FLAG_QUOTE_IMPORTANT or
        BLOCK_FLAG_QUOTE_WARNING or
        BLOCK_FLAG_QUOTE_CAUTION
private const val BLOCK_FLAGS_ALLOWED =
    BLOCK_FLAG_CONTINUATION or
        BLOCK_FLAG_ORDERED_LIST or
        BLOCK_FLAG_TASK_CHECKED or
        BLOCK_FLAG_TASK_UNCHECKED or
        BLOCK_FLAG_TABLE_HEADER or
        BLOCK_FLAG_RAW_HTML or
        BLOCK_FLAGS_QUOTE_KIND or
        BLOCK_FLAG_QUOTE_ALERT_START or
        BLOCK_FLAG_TABLE_START or
        BLOCK_FLAG_LIST_ITEM_CONTINUATION
internal const val MARKDOWN_SPAN_STYLE_EMPHASIS = 1
internal const val MARKDOWN_SPAN_STYLE_STRONG = 1 shl 1
internal const val MARKDOWN_SPAN_STYLE_CODE = 1 shl 2
internal const val MARKDOWN_SPAN_STYLE_STRIKETHROUGH = 1 shl 3
internal const val MARKDOWN_SPAN_STYLE_SUPERSCRIPT = 1 shl 4
internal const val MARKDOWN_SPAN_STYLE_SUBSCRIPT = 1 shl 5
internal const val MARKDOWN_SPAN_STYLE_FOOTNOTE_REFERENCE = 1 shl 6
internal const val MARKDOWN_SPAN_STYLE_MATH = 1 shl 7
internal const val MARKDOWN_SPAN_STYLE_DISPLAY_MATH = 1 shl 8
private const val SPAN_STYLES_ALLOWED =
    MARKDOWN_SPAN_STYLE_EMPHASIS or
        MARKDOWN_SPAN_STYLE_STRONG or
        MARKDOWN_SPAN_STYLE_CODE or
        MARKDOWN_SPAN_STYLE_STRIKETHROUGH or
        MARKDOWN_SPAN_STYLE_SUPERSCRIPT or
        MARKDOWN_SPAN_STYLE_SUBSCRIPT or
        MARKDOWN_SPAN_STYLE_FOOTNOTE_REFERENCE or
        MARKDOWN_SPAN_STYLE_MATH or
        MARKDOWN_SPAN_STYLE_DISPLAY_MATH
private const val SPAN_FLAG_LINK = 1L
private const val SPAN_FLAG_FOOTNOTE_REFERENCE = 1L shl 1
private const val SPAN_FLAGS_ALLOWED = SPAN_FLAG_LINK or SPAN_FLAG_FOOTNOTE_REFERENCE
private const val UNSIGNED_INT_MASK = 0xffff_ffffL

/** Reports malformed or unsupported output from the isolated Markdown renderer. */
internal class MarkdownProtocolException(message: String) : IllegalArgumentException(message)

/** Decodes and validates bounded version-four Markdown render packets. */
internal object MarkdownPacketDecoder {
    /** Decodes one complete little-endian render packet. */
    fun decode(packet: ByteArray): MarkdownPreviewDocument {
        if (packet.size.toLong() !in PACKET_HEADER_BYTES..MarkdownProtocol.MAX_PACKET_BYTES) {
            throw MarkdownProtocolException("Markdown packet size is outside its bounds")
        }
        val reader = MarkdownPacketReader(packet)
        if (!reader.readBytes(PACKET_MAGIC.size, "packet magic").contentEquals(PACKET_MAGIC)) {
            throw MarkdownProtocolException("Markdown packet has invalid magic")
        }
        if (reader.readUnsignedInt("packet version") != PACKET_VERSION) {
            throw MarkdownProtocolException("unsupported Markdown packet version")
        }
        if (reader.readUnsignedInt("header byte count") != PACKET_HEADER_BYTES) {
            throw MarkdownProtocolException("Markdown packet has an unsupported header")
        }
        val documentFlags = reader.readUnsignedInt("document flags")
        reader.requireAllowedFlags(
            flags = documentFlags,
            allowedFlags = DOCUMENT_FLAGS_ALLOWED,
            field = "document flags"
        )
        reader.requireZeroUnsignedInt("header reserved field")
        val inputBytes = reader.readSupportedUnsignedLong("input byte count")
        if (inputBytes > MarkdownProtocol.MAX_INPUT_BYTES) {
            throw MarkdownProtocolException("Markdown input byte count exceeds its limit")
        }
        val packetBytes = reader.readSupportedUnsignedLong("packet byte count")
        if (packetBytes != packet.size.toLong()) {
            throw MarkdownProtocolException("Markdown packet byte count is inconsistent")
        }
        val blockCount = reader.readUnsignedInt("block count")
        val spanCount = reader.readUnsignedInt("span count")
        if (blockCount > MarkdownProtocol.MAX_BLOCK_COUNT) {
            throw MarkdownProtocolException("Markdown block count exceeds its limit")
        }
        if (spanCount > MarkdownProtocol.MAX_SPAN_COUNT) {
            throw MarkdownProtocolException("Markdown span count exceeds its limit")
        }
        val textBytes = reader.readSupportedUnsignedLong("text byte count")
        val auxiliaryBytes = reader.readSupportedUnsignedLong("auxiliary byte count")
        val inputUtf16Length = reader.readSupportedUnsignedLong("input UTF-16 length")
        val sourceMapCount = reader.readUnsignedInt("source-map count")
        reader.requireZeroUnsignedInt("header trailing reserved field")
        if (inputUtf16Length > Int.MAX_VALUE.toLong()) {
            throw MarkdownProtocolException("Markdown input UTF-16 length exceeds its limit")
        }
        if (sourceMapCount > MarkdownProtocol.MAX_SOURCE_MAP_COUNT) {
            throw MarkdownProtocolException("Markdown source-map count exceeds its limit")
        }
        val minimumBytes =
            checkedAdd(
                PACKET_HEADER_BYTES,
                checkedAdd(
                    checkedMultiply(blockCount, BLOCK_HEADER_BYTES),
                    checkedAdd(
                        checkedMultiply(spanCount, SPAN_HEADER_BYTES),
                        checkedMultiply(sourceMapCount, SOURCE_MAP_HEADER_BYTES)
                    )
                )
            )
        if (
            checkedAdd(minimumBytes, checkedAdd(textBytes, auxiliaryBytes)) != packetBytes
        ) {
            throw MarkdownProtocolException("Markdown packet aggregate sizes are inconsistent")
        }

        val blocks = ArrayList<MarkdownRenderBlock>(blockCount.toInt())
        var decodedSpanCount = 0L
        var decodedSourceMapCount = 0L
        var decodedTextBytes = 0L
        var decodedAuxiliaryBytes = 0L
        repeat(blockCount.toInt()) {
            val decoded = decodeBlock(
                reader = reader,
                inputUtf16Length = inputUtf16Length,
                remainingSpans = spanCount - decodedSpanCount,
                remainingSourceMaps = sourceMapCount - decodedSourceMapCount
            )
            blocks += decoded.block
            decodedSpanCount = checkedAdd(decodedSpanCount, decoded.spanCount)
            decodedSourceMapCount =
                checkedAdd(decodedSourceMapCount, decoded.sourceMapCount)
            decodedTextBytes = checkedAdd(decodedTextBytes, decoded.textBytes)
            decodedAuxiliaryBytes =
                checkedAdd(decodedAuxiliaryBytes, decoded.auxiliaryBytes)
        }
        if (reader.remainingBytes != 0) {
            throw MarkdownProtocolException("Markdown packet has trailing bytes")
        }
        if (
            decodedSpanCount != spanCount ||
            decodedSourceMapCount != sourceMapCount ||
            decodedTextBytes != textBytes ||
            decodedAuxiliaryBytes != auxiliaryBytes
        ) {
            throw MarkdownProtocolException("Markdown packet decoded sizes are inconsistent")
        }
        val containsRawHtml = blocks.any(MarkdownRenderBlock::containsRawHtml)
        if (containsRawHtml != (documentFlags and DOCUMENT_FLAG_RAW_HTML != 0L)) {
            throw MarkdownProtocolException("Markdown raw HTML flag is inconsistent")
        }
        return MarkdownPreviewDocument(
            inputByteLength = inputBytes,
            inputUtf16Length = inputUtf16Length,
            blocks = blocks,
            spanCount = spanCount.toInt(),
            sourceMapCount = sourceMapCount.toInt(),
            containsRawHtml = containsRawHtml
        )
    }

    /** Decodes and validates one block and all of its inline spans. */
    private fun decodeBlock(
        reader: MarkdownPacketReader,
        inputUtf16Length: Long,
        remainingSpans: Long,
        remainingSourceMaps: Long
    ): DecodedBlock {
        val kindValue = reader.readUnsignedInt("block kind")
        if (kindValue > Int.MAX_VALUE.toLong()) {
            throw MarkdownProtocolException("Markdown block kind exceeds its range")
        }
        val kind = MarkdownBlockKind.fromProtocolValue(kindValue.toInt())
        val blockFlags = reader.readUnsignedInt("block flags")
        reader.requireAllowedFlags(blockFlags, BLOCK_FLAGS_ALLOWED, "block flags")
        val headingLevel = reader.readUnsignedInt("heading level")
        val quoteDepth = reader.readUnsignedInt("quote depth")
        val listDepth = reader.readUnsignedInt("list depth")
        reader.requireZeroUnsignedInt("block reserved field")
        val listNumber = reader.readSupportedUnsignedLong("list number")
        val textByteLength = reader.readUnsignedInt("block text byte count")
        val metadataByteLength = reader.readUnsignedInt("block metadata byte count")
        val blockSpanCount = reader.readUnsignedInt("block span count")
        reader.requireZeroUnsignedInt("block trailing reserved field")
        val sourceStart = reader.readSupportedUnsignedLong("block source start")
        val sourceEnd = reader.readSupportedUnsignedLong("block source end")
        val blockSourceMapCount = reader.readUnsignedInt("block source-map count")
        reader.requireZeroUnsignedInt("block source reserved field")
        if (textByteLength > MarkdownProtocol.MAX_BLOCK_TEXT_BYTES) {
            throw MarkdownProtocolException("Markdown block text exceeds its limit")
        }
        if (metadataByteLength > MAX_METADATA_BYTES) {
            throw MarkdownProtocolException("Markdown block metadata exceeds its limit")
        }
        if (blockSpanCount > MarkdownProtocol.MAX_SPAN_COUNT) {
            throw MarkdownProtocolException("Markdown block span count exceeds its limit")
        }
        if (blockSourceMapCount > MarkdownProtocol.MAX_SOURCE_MAP_COUNT) {
            throw MarkdownProtocolException("Markdown block source-map count exceeds its limit")
        }
        if (blockSpanCount > remainingSpans) {
            throw MarkdownProtocolException(
                "Markdown block span count exceeds the remaining aggregate"
            )
        }
        if (blockSourceMapCount > remainingSourceMaps) {
            throw MarkdownProtocolException(
                "Markdown block source-map count exceeds the remaining aggregate"
            )
        }
        if (sourceStart >= sourceEnd || sourceEnd > inputUtf16Length) {
            throw MarkdownProtocolException("Markdown block source range is invalid")
        }
        val text = reader.readUtf8(textByteLength, "block text")
        val metadata = reader.readUtf8(metadataByteLength, "block metadata")
        val spans = ArrayList<MarkdownInlineSpan>(blockSpanCount.toInt())
        var destinationBytes = 0L
        var previousSpanEnd = 0
        repeat(blockSpanCount.toInt()) {
            val decodedSpan = decodeSpan(reader, text, previousSpanEnd)
            spans += decodedSpan.span
            destinationBytes = checkedAdd(destinationBytes, decodedSpan.destinationBytes)
            previousSpanEnd = decodedSpan.span.end
        }
        val sourceMaps = ArrayList<MarkdownSourceMap>(blockSourceMapCount.toInt())
        var previousRenderedEnd = 0
        repeat(blockSourceMapCount.toInt()) {
            val sourceMap =
                decodeSourceMap(
                    reader = reader,
                    text = text,
                    previousRenderedEnd = previousRenderedEnd,
                    blockSourceStart = sourceStart,
                    blockSourceEnd = sourceEnd,
                    inputUtf16Length = inputUtf16Length
                )
            sourceMaps += sourceMap
            previousRenderedEnd = sourceMap.renderedEnd
        }
        if (
            (text.isEmpty() && sourceMaps.isNotEmpty()) ||
            (text.isNotEmpty() && previousRenderedEnd != text.length)
        ) {
            throw MarkdownProtocolException("Markdown source maps do not cover the block text")
        }

        val quoteKind = markdownQuoteKind(blockFlags)
        val tableAlignments = markdownTableAlignments(kind, text, metadata)
        validateBlockFields(
            kind = kind,
            flags = blockFlags,
            headingLevel = headingLevel,
            quoteDepth = quoteDepth,
            listDepth = listDepth,
            listNumber = listNumber,
            text = text,
            metadata = metadata
        )
        validateTaskMarker(blockFlags, text, spans)
        return DecodedBlock(
            block =
                MarkdownRenderBlock(
                    kind = kind,
                    continuesPrevious = blockFlags and BLOCK_FLAG_CONTINUATION != 0L,
                    isOrderedListItem = blockFlags and BLOCK_FLAG_ORDERED_LIST != 0L,
                    isTaskChecked = blockFlags and BLOCK_FLAG_TASK_CHECKED != 0L,
                    isTaskUnchecked = blockFlags and BLOCK_FLAG_TASK_UNCHECKED != 0L,
                    isTableHeader = blockFlags and BLOCK_FLAG_TABLE_HEADER != 0L,
                    containsRawHtml = blockFlags and BLOCK_FLAG_RAW_HTML != 0L,
                    headingLevel = headingLevel.toInt(),
                    quoteDepth = quoteDepth.toInt(),
                    listDepth = listDepth.toInt(),
                    listNumber = listNumber,
                    text = text,
                    metadata = metadata,
                    spans = spans,
                    source = MarkdownSourceRange(sourceStart, sourceEnd),
                    sourceMaps = sourceMaps,
                    quoteKind = quoteKind,
                    startsQuoteAlert = blockFlags and BLOCK_FLAG_QUOTE_ALERT_START != 0L,
                    tableAlignments = tableAlignments,
                    startsTable = blockFlags and BLOCK_FLAG_TABLE_START != 0L,
                    continuesListItem = blockFlags and BLOCK_FLAG_LIST_ITEM_CONTINUATION != 0L
                ),
            textBytes = textByteLength,
            auxiliaryBytes = checkedAdd(metadataByteLength, destinationBytes),
            spanCount = blockSpanCount,
            sourceMapCount = blockSourceMapCount
        )
    }

    /** Checks task-marker invariants before the presentation layer strips the marker. */
    private fun validateTaskMarker(flags: Long, text: String, spans: List<MarkdownInlineSpan>) {
        if (flags and BLOCK_FLAG_CONTINUATION != 0L) return
        val prefix = when {
            flags and BLOCK_FLAG_TASK_CHECKED != 0L -> MARKDOWN_CHECKED_TASK_PREFIX
            flags and BLOCK_FLAG_TASK_UNCHECKED != 0L -> MARKDOWN_UNCHECKED_TASK_PREFIX
            else -> return
        }
        if (!text.startsWith(prefix)) {
            throw MarkdownProtocolException("Markdown task marker is inconsistent")
        }
        if (spans.any { span -> span.start < prefix.length }) {
            throw MarkdownProtocolException("Markdown task marker contains inline styling")
        }
    }

    /** Decodes and validates one non-overlapping styled UTF-16 range. */
    private fun decodeSpan(
        reader: MarkdownPacketReader,
        text: String,
        previousSpanEnd: Int
    ): DecodedSpan {
        val start = reader.readUnsignedInt("span start")
        val end = reader.readUnsignedInt("span end")
        val styles = reader.readUnsignedInt("span styles")
        val flags = reader.readUnsignedInt("span flags")
        val destinationByteLength = reader.readUnsignedInt("inline destination byte count")
        reader.requireZeroUnsignedInt("span reserved field")
        reader.requireAllowedFlags(styles, SPAN_STYLES_ALLOWED.toLong(), "span styles")
        reader.requireAllowedFlags(flags, SPAN_FLAGS_ALLOWED, "span flags")
        if (destinationByteLength > MAX_LINK_DESTINATION_BYTES) {
            throw MarkdownProtocolException("Markdown inline destination exceeds its limit")
        }
        val destinationText =
            reader.readUtf8(destinationByteLength, "link destination")
        val isLink = flags and SPAN_FLAG_LINK != 0L
        val isFootnoteReference = flags and SPAN_FLAG_FOOTNOTE_REFERENCE != 0L
        if (isLink && isFootnoteReference) {
            throw MarkdownProtocolException("Markdown span has conflicting destinations")
        }
        val hasDestination = isLink || isFootnoteReference
        if (!hasDestination && destinationByteLength != 0L) {
            throw MarkdownProtocolException("Markdown span destination is inconsistent")
        }
        if (isFootnoteReference && destinationByteLength == 0L) {
            throw MarkdownProtocolException("Markdown footnote destination is empty")
        }
        val hasFootnoteStyle =
            styles and MARKDOWN_SPAN_STYLE_FOOTNOTE_REFERENCE.toLong() != 0L
        if (isFootnoteReference != hasFootnoteStyle) {
            throw MarkdownProtocolException("Markdown footnote span is inconsistent")
        }
        val math = styles and MARKDOWN_SPAN_STYLE_MATH.toLong() != 0L
        val displayMath = styles and MARKDOWN_SPAN_STYLE_DISPLAY_MATH.toLong() != 0L
        if ((displayMath && !math) || (
                math && (
                    hasFootnoteStyle ||
                        styles and (
                            MARKDOWN_SPAN_STYLE_CODE or MARKDOWN_SPAN_STYLE_SUPERSCRIPT or
                                MARKDOWN_SPAN_STYLE_SUBSCRIPT
                            ).toLong() != 0L
                    )
                )
        ) {
            throw MarkdownProtocolException("Markdown math span is inconsistent")
        }
        if (styles == 0L && !hasDestination) {
            throw MarkdownProtocolException("Markdown span has no presentation")
        }
        if (
            start > Int.MAX_VALUE.toLong() ||
            end > Int.MAX_VALUE.toLong() ||
            start >= end ||
            start < previousSpanEnd.toLong() ||
            end > text.length.toLong()
        ) {
            throw MarkdownProtocolException("Markdown span range is invalid")
        }
        val startIndex = start.toInt()
        val endIndex = end.toInt()
        if (!text.isUtf16ScalarBoundary(startIndex) || !text.isUtf16ScalarBoundary(endIndex)) {
            throw MarkdownProtocolException("Markdown span divides a Unicode character")
        }
        return DecodedSpan(
            span =
                MarkdownInlineSpan(
                    start = startIndex,
                    end = endIndex,
                    styles = styles.toInt(),
                    destination = if (hasDestination) destinationText else null,
                    destinationKind =
                        when {
                            isLink -> MarkdownInlineDestinationKind.Link

                            isFootnoteReference ->
                                MarkdownInlineDestinationKind.FootnoteReference

                            else -> null
                        }
                ),
            destinationBytes = destinationByteLength
        )
    }

    /** Decodes one ordered rendered range and its bounded logical source range. */
    private fun decodeSourceMap(
        reader: MarkdownPacketReader,
        text: String,
        previousRenderedEnd: Int,
        blockSourceStart: Long,
        blockSourceEnd: Long,
        inputUtf16Length: Long
    ): MarkdownSourceMap {
        val renderedStart = reader.readUnsignedInt("source-map rendered start")
        val renderedEnd = reader.readUnsignedInt("source-map rendered end")
        val sourceStart = reader.readSupportedUnsignedLong("source-map source start")
        val sourceEnd = reader.readSupportedUnsignedLong("source-map source end")
        if (
            renderedStart > Int.MAX_VALUE.toLong() ||
            renderedEnd > Int.MAX_VALUE.toLong() ||
            renderedStart >= renderedEnd ||
            renderedStart != previousRenderedEnd.toLong() ||
            renderedEnd > text.length.toLong()
        ) {
            throw MarkdownProtocolException("Markdown source-map rendered range is invalid")
        }
        val renderedStartIndex = renderedStart.toInt()
        val renderedEndIndex = renderedEnd.toInt()
        if (
            !text.isUtf16ScalarBoundary(renderedStartIndex) ||
            !text.isUtf16ScalarBoundary(renderedEndIndex)
        ) {
            throw MarkdownProtocolException("Markdown source map divides a Unicode character")
        }
        if (
            sourceStart >= sourceEnd ||
            sourceStart < blockSourceStart ||
            sourceEnd > blockSourceEnd ||
            sourceEnd > inputUtf16Length
        ) {
            throw MarkdownProtocolException("Markdown source-map source range is invalid")
        }
        return MarkdownSourceMap(
            renderedStart = renderedStartIndex,
            renderedEnd = renderedEndIndex,
            source = MarkdownSourceRange(sourceStart, sourceEnd)
        )
    }

    /** Validates block-kind-specific fields after their payload is decoded. */
    private fun validateBlockFields(
        kind: MarkdownBlockKind,
        flags: Long,
        headingLevel: Long,
        quoteDepth: Long,
        listDepth: Long,
        listNumber: Long,
        text: String,
        metadata: String
    ) {
        if (quoteDepth > MAX_QUOTE_DEPTH || listDepth > MAX_LIST_DEPTH) {
            throw MarkdownProtocolException("Markdown block nesting exceeds its limit")
        }
        val quoteKindFlags = flags and BLOCK_FLAGS_QUOTE_KIND
        if (quoteKindFlags != 0L && quoteKindFlags.countOneBits() != 1) {
            throw MarkdownProtocolException("Markdown quote has conflicting alert kinds")
        }
        val startsQuoteAlert = flags and BLOCK_FLAG_QUOTE_ALERT_START != 0L
        if ((quoteKindFlags != 0L || startsQuoteAlert) && quoteDepth == 0L) {
            throw MarkdownProtocolException("Markdown alert is outside a quote")
        }
        if (startsQuoteAlert && quoteKindFlags == 0L) {
            throw MarkdownProtocolException("Markdown alert start lacks an alert kind")
        }
        val isList = kind == MarkdownBlockKind.ListItem
        val hasOrderedFlag = flags and BLOCK_FLAG_ORDERED_LIST != 0L
        val hasCheckedFlag = flags and BLOCK_FLAG_TASK_CHECKED != 0L
        val hasUncheckedFlag = flags and BLOCK_FLAG_TASK_UNCHECKED != 0L
        val continuesListItem = flags and BLOCK_FLAG_LIST_ITEM_CONTINUATION != 0L
        if (hasCheckedFlag && hasUncheckedFlag) {
            throw MarkdownProtocolException("Markdown task item has conflicting states")
        }
        if (continuesListItem &&
            (!isList || hasCheckedFlag || hasUncheckedFlag || listNumber != 0L)
        ) {
            throw MarkdownProtocolException("Markdown list continuation has conflicting fields")
        }
        if (
            isList != (listDepth != 0L) ||
            (!isList && (hasOrderedFlag || hasCheckedFlag || hasUncheckedFlag)) ||
            (!isList && listNumber != 0L) ||
            (isList && !hasOrderedFlag && listNumber != 0L)
        ) {
            throw MarkdownProtocolException("Markdown list fields conflict with the block kind")
        }
        if (
            (kind == MarkdownBlockKind.Heading && headingLevel !in 1L..6L) ||
            (kind != MarkdownBlockKind.Heading && headingLevel != 0L)
        ) {
            throw MarkdownProtocolException("Markdown heading level conflicts with the block kind")
        }
        if (flags and BLOCK_FLAG_TABLE_HEADER != 0L && kind != MarkdownBlockKind.TableRow) {
            throw MarkdownProtocolException("Markdown table flag conflicts with the block kind")
        }
        if (
            flags and BLOCK_FLAG_TABLE_START != 0L &&
            (kind != MarkdownBlockKind.TableRow || flags and BLOCK_FLAG_CONTINUATION != 0L)
        ) {
            throw MarkdownProtocolException("Markdown table start conflicts with its block")
        }
        if (
            kind == MarkdownBlockKind.HtmlLiteral &&
            flags and BLOCK_FLAG_RAW_HTML == 0L
        ) {
            throw MarkdownProtocolException("Markdown HTML block lacks its literal flag")
        }
        if (
            metadata.isNotEmpty() &&
            kind != MarkdownBlockKind.Code &&
            kind != MarkdownBlockKind.Footnote &&
            kind != MarkdownBlockKind.TableRow
        ) {
            throw MarkdownProtocolException("Markdown metadata conflicts with the block kind")
        }
        if (kind == MarkdownBlockKind.Rule && text.isNotEmpty()) {
            throw MarkdownProtocolException("Markdown rule contains text")
        }
    }

    /** Returns the optional alert kind encoded by one validated block flag set. */
    private fun markdownQuoteKind(flags: Long): MarkdownQuoteKind? =
        when (flags and BLOCK_FLAGS_QUOTE_KIND) {
            0L -> null
            BLOCK_FLAG_QUOTE_NOTE -> MarkdownQuoteKind.Note
            BLOCK_FLAG_QUOTE_TIP -> MarkdownQuoteKind.Tip
            BLOCK_FLAG_QUOTE_IMPORTANT -> MarkdownQuoteKind.Important
            BLOCK_FLAG_QUOTE_WARNING -> MarkdownQuoteKind.Warning
            BLOCK_FLAG_QUOTE_CAUTION -> MarkdownQuoteKind.Caution
            else -> throw MarkdownProtocolException("Markdown quote has conflicting alert kinds")
        }

    /** Decodes exact table-column alignment metadata for one row. */
    private fun markdownTableAlignments(
        kind: MarkdownBlockKind,
        text: String,
        metadata: String
    ): List<MarkdownTableAlignment> {
        if (kind != MarkdownBlockKind.TableRow) {
            return emptyList()
        }
        val expectedCount = Math.addExact(text.count { character -> character == '\t' }, 1)
        if (metadata.length != expectedCount) {
            throw MarkdownProtocolException("Markdown table alignment count is inconsistent")
        }
        return metadata.map(MarkdownTableAlignment::fromProtocolValue)
    }
}

/** Holds one decoded block plus its aggregate packet metrics. */
private data class DecodedBlock(
    val block: MarkdownRenderBlock,
    val textBytes: Long,
    val auxiliaryBytes: Long,
    val spanCount: Long,
    val sourceMapCount: Long
)

/** Holds one decoded span plus its variable payload length. */
private data class DecodedSpan(val span: MarkdownInlineSpan, val destinationBytes: Long)

/** Reads strict little-endian fields from one Markdown packet. */
private class MarkdownPacketReader(packet: ByteArray) {
    private val buffer = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
    private val utf8Decoder =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)

    val remainingBytes: Int
        get() = buffer.remaining()

    /** Reads one bounded fixed-width byte sequence. */
    fun readBytes(byteCount: Int, field: String): ByteArray {
        requireRemaining(byteCount, field)
        return ByteArray(byteCount).also(buffer::get)
    }

    /** Reads one unsigned little-endian integer. */
    fun readUnsignedInt(field: String): Long {
        requireRemaining(Int.SIZE_BYTES, field)
        return buffer.int.toLong() and UNSIGNED_INT_MASK
    }

    /** Reads one unsigned long representable by Kotlin's signed offsets. */
    fun readSupportedUnsignedLong(field: String): Long {
        requireRemaining(Long.SIZE_BYTES, field)
        val value = buffer.long
        if (value < 0L) {
            throw MarkdownProtocolException("$field exceeds the supported range")
        }
        return value
    }

    /** Reads one strictly valid UTF-8 payload. */
    fun readUtf8(byteLength: Long, field: String): String {
        if (byteLength > remainingBytes.toLong()) {
            throw MarkdownProtocolException("$field exceeds the packet bounds")
        }
        val length = byteLength.toInt()
        val start = buffer.position()
        val payload = buffer.slice().apply { limit(length) }
        val text =
            try {
                utf8Decoder.reset().decode(payload).toString()
            } catch (_: CharacterCodingException) {
                throw MarkdownProtocolException("$field is not valid UTF-8")
            }
        buffer.position(start + length)
        return text
    }

    /** Requires flags to contain only supported bits. */
    fun requireAllowedFlags(flags: Long, allowedFlags: Long, field: String) {
        if (flags and allowedFlags.inv() != 0L) {
            throw MarkdownProtocolException("$field contains unsupported bits")
        }
    }

    /** Requires one reserved integer to be zero. */
    fun requireZeroUnsignedInt(field: String) {
        if (readUnsignedInt(field) != 0L) {
            throw MarkdownProtocolException("$field must be zero")
        }
    }

    /** Requires a fixed-width field to remain inside the packet. */
    private fun requireRemaining(byteCount: Int, field: String) {
        if (remainingBytes < byteCount) {
            throw MarkdownProtocolException("$field exceeds the packet bounds")
        }
    }
}

/** Returns whether one UTF-16 offset is a Unicode scalar boundary. */
private fun String.isUtf16ScalarBoundary(offset: Int): Boolean {
    require(offset in 0..length) { "UTF-16 offset is outside the string" }
    return offset == 0 ||
        offset == length ||
        !(this[offset - 1].isHighSurrogate() && this[offset].isLowSurrogate())
}

/** Adds two packet sizes without accepting overflow. */
private fun checkedAdd(left: Long, right: Long): Long = try {
    Math.addExact(left, right)
} catch (_: ArithmeticException) {
    throw MarkdownProtocolException("Markdown packet size overflowed")
}

/** Multiplies two packet sizes without accepting overflow. */
private fun checkedMultiply(left: Long, right: Long): Long = try {
    Math.multiplyExact(left, right)
} catch (_: ArithmeticException) {
    throw MarkdownProtocolException("Markdown packet size overflowed")
}
