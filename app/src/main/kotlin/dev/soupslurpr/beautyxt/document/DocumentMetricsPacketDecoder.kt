package dev.soupslurpr.beautyxt.document

private const val DOCUMENT_METRICS_PACKET_MAGIC = 0x544d_4542L
private const val DOCUMENT_METRICS_PACKET_VERSION = 3
private const val DOCUMENT_METRICS_PACKET_BYTES = 72
private const val DOCUMENT_METRICS_ALLOWED_FLAGS = 0

/** Reports malformed or unsupported document-metrics data from the native bridge. */
internal class DocumentMetricsProtocolException(message: String) :
    IllegalArgumentException(message)

/** Decodes and validates versioned document-metrics packets from the Rust bridge. */
internal object DocumentMetricsPacketDecoder {
    /** Decodes one complete version 3 little-endian document-metrics packet. */
    fun decode(packet: ByteArray): DocumentMetrics {
        if (packet.size < DOCUMENT_METRICS_PACKET_BYTES) {
            throw DocumentMetricsProtocolException(
                "document-metrics packet is shorter than its header"
            )
        }

        val reader = DocumentPacketReader(packet, ::DocumentMetricsProtocolException)
        val magic = reader.readUnsignedInt("packet magic")
        if (magic != DOCUMENT_METRICS_PACKET_MAGIC) {
            throw DocumentMetricsProtocolException("document-metrics packet has invalid magic")
        }
        val version = reader.readUnsignedShort("packet version")
        if (version != DOCUMENT_METRICS_PACKET_VERSION) {
            throw DocumentMetricsProtocolException(
                "unsupported document-metrics packet version $version"
            )
        }
        val flags = reader.readUnsignedShort("document-metrics flags")
        reader.requireAllowedFlags(
            flags = flags,
            allowedFlags = DOCUMENT_METRICS_ALLOWED_FLAGS,
            field = "document-metrics flags"
        )
        val metrics = decodeDocumentMetrics(reader)
        if (reader.remainingBytes != 0) {
            throw DocumentMetricsProtocolException("document-metrics packet has trailing bytes")
        }
        return metrics
    }
}
