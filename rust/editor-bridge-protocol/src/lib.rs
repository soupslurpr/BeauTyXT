//! Encodes bounded editor responses for the Android integration layer.

#![forbid(unsafe_code)]

use std::error::Error;
use std::fmt::{Display, Formatter};

use beautyxt_editor_core::{
    DocumentLineEnding, DocumentMetrics, EditWindowSnapshot, FindBatch, FindMatch,
    MAX_FIND_MATCH_UTF16_UNITS, RenderBlock, ViewportPosition, ViewportSnapshot,
};

/// Identifies a document-metrics packet in little-endian byte order.
pub const DOCUMENT_METRICS_PACKET_MAGIC: u32 = u32::from_le_bytes(*b"BEMT");

/// Identifies the current document-metrics packet layout.
pub const DOCUMENT_METRICS_PACKET_VERSION: u16 = 3;

/// Stores the fixed document-metrics packet length.
pub const DOCUMENT_METRICS_PACKET_BYTES: usize = 72;

const DOCUMENT_METRICS_PACKET_FLAGS: u16 = 0;

/// Identifies an edit-window packet in little-endian byte order.
pub const EDIT_WINDOW_PACKET_MAGIC: u32 = u32::from_le_bytes(*b"BEWT");

/// Identifies the current edit-window packet layout.
pub const EDIT_WINDOW_PACKET_VERSION: u16 = 3;

/// Stores the fixed edit-window packet header length.
pub const EDIT_WINDOW_HEADER_BYTES: usize = 128;

/// Identifies a viewport packet in little-endian byte order.
pub const VIEWPORT_PACKET_MAGIC: u32 = u32::from_le_bytes(*b"BTXT");

/// Identifies the current viewport packet layout.
pub const VIEWPORT_PACKET_VERSION: u16 = 4;

/// Stores the fixed viewport packet header length.
pub const VIEWPORT_HEADER_BYTES: usize = 108;

/// Stores the fixed metadata length preceding each block's UTF-8 bytes.
pub const RENDER_BLOCK_HEADER_BYTES: usize = 32;

/// Identifies a find packet in little-endian byte order.
pub const FIND_PACKET_MAGIC: u32 = u32::from_le_bytes(*b"BEFN");

/// Identifies the current fixed-size find packet layout.
pub const FIND_PACKET_VERSION: u16 = 2;

/// Stores the fixed find packet length.
pub const FIND_PACKET_BYTES: usize = 120;

const FIND_MATCH_FIELDS_BYTES: usize = 4 * size_of::<u64>();
const FIND_REMAINING_FIELDS_BYTES: usize = 2 * size_of::<u64>();

const SNAPSHOT_HAS_NEXT: u16 = 1;
const SNAPSHOT_HAS_PREVIOUS: u16 = 1 << 1;
const EDIT_WINDOW_HAS_PREVIOUS: u16 = 1;
const EDIT_WINDOW_HAS_NEXT: u16 = 1 << 1;
const BLOCK_CONTINUES_AT_START: u8 = 1;
const BLOCK_CONTINUES_AT_END: u8 = 1 << 1;
const METRICS_EDITABLE: u8 = 1;
const METRICS_HAS_UTF8_BOM: u8 = 1 << 1;
const METRICS_HAS_LF_LINE_ENDINGS: u8 = 1 << 2;
const METRICS_HAS_CRLF_LINE_ENDINGS: u8 = 1 << 3;
const METRICS_HAS_CR_LINE_ENDINGS: u8 = 1 << 4;
const INSERTED_LINE_ENDING_LF: u8 = 0;
const INSERTED_LINE_ENDING_CRLF: u8 = 1;
const INSERTED_LINE_ENDING_CR: u8 = 2;
const FIND_HAS_MATCH: u16 = 1;
const FIND_HAS_REMAINING_CANDIDATE: u16 = 1 << 1;

/// Reports data that cannot be represented by the packet format.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum EncodeError {
    /// Reports a platform-sized value that cannot fit an unsigned 64-bit field.
    ValueTooLarge(&'static str),

    /// Reports a packet length that overflows the current address space.
    PacketTooLarge,

    /// Reports inconsistent revision-bound snapshot metadata.
    InvalidSnapshot(&'static str),
}

impl Display for EncodeError {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::ValueTooLarge(field) => write!(formatter, "{field} does not fit the protocol"),
            Self::PacketTooLarge => formatter.write_str("editor packet length overflowed"),
            Self::InvalidSnapshot(message) => formatter.write_str(message),
        }
    }
}

impl Error for EncodeError {}

/// Encodes document metrics into one fixed-size little-endian packet.
///
/// # Errors
///
/// Returns an error when a metric cannot fit its protocol field.
pub fn encode_document_metrics(metrics: &DocumentMetrics) -> Result<Vec<u8>, EncodeError> {
    let mut packet = Vec::with_capacity(DOCUMENT_METRICS_PACKET_BYTES);

    push_u32(&mut packet, DOCUMENT_METRICS_PACKET_MAGIC);
    push_u16(&mut packet, DOCUMENT_METRICS_PACKET_VERSION);
    push_u16(&mut packet, DOCUMENT_METRICS_PACKET_FLAGS);
    encode_metrics(&mut packet, metrics)?;

    debug_assert_eq!(packet.len(), DOCUMENT_METRICS_PACKET_BYTES);
    Ok(packet)
}

/// Encodes one bounded find batch into a fixed-size little-endian packet.
///
/// # Errors
///
/// Returns an error when the batch contains conflicting result variants,
/// invalid document coordinates, or a value outside the protocol range.
pub fn encode_find(batch: &FindBatch) -> Result<Vec<u8>, EncodeError> {
    validate_find_batch(batch)?;
    let mut packet = Vec::with_capacity(FIND_PACKET_BYTES);

    push_u32(&mut packet, FIND_PACKET_MAGIC);
    push_u16(&mut packet, FIND_PACKET_VERSION);
    push_u16(
        &mut packet,
        (u16::from(batch.matched.is_some()) * FIND_HAS_MATCH)
            | (u16::from(batch.remaining_candidate_range.is_some()) * FIND_HAS_REMAINING_CANDIDATE),
    );
    encode_metrics(&mut packet, &batch.metrics)?;

    if let Some(matched) = batch.matched {
        encode_find_match(&mut packet, matched)?;
    } else {
        packet.extend_from_slice(&[0; FIND_MATCH_FIELDS_BYTES]);
    }
    if let Some(remaining) = batch.remaining_candidate_range {
        push_usize(&mut packet, remaining.start, "remaining candidate start")?;
        push_usize(&mut packet, remaining.end, "remaining candidate end")?;
    } else {
        packet.extend_from_slice(&[0; FIND_REMAINING_FIELDS_BYTES]);
    }

    debug_assert_eq!(packet.len(), FIND_PACKET_BYTES);
    Ok(packet)
}

/// Validates one find batch before its coordinates cross the JNI boundary.
fn validate_find_batch(batch: &FindBatch) -> Result<(), EncodeError> {
    if batch.matched.is_some() && batch.remaining_candidate_range.is_some() {
        return Err(EncodeError::InvalidSnapshot(
            "find batch contains both a match and a remaining candidate",
        ));
    }
    if let Some(matched) = batch.matched {
        if matched.range.end < matched.range.start {
            return Err(EncodeError::InvalidSnapshot(
                "find match end precedes its start",
            ));
        }
        if matched.range.is_empty() {
            return Err(EncodeError::InvalidSnapshot("find match is empty"));
        }
        if matched.range.end > batch.metrics.utf16_units {
            return Err(EncodeError::InvalidSnapshot(
                "find match exceeds the document",
            ));
        }
        if matched.range.len() > MAX_FIND_MATCH_UTF16_UNITS {
            return Err(EncodeError::InvalidSnapshot(
                "find match exceeds the supported limit",
            ));
        }
        validate_find_start(matched, batch.metrics)?;
    }
    if let Some(remaining) = batch.remaining_candidate_range {
        if remaining.end < remaining.start {
            return Err(EncodeError::InvalidSnapshot(
                "remaining find candidate end precedes its start",
            ));
        }
        if remaining.is_empty() {
            return Err(EncodeError::InvalidSnapshot(
                "remaining find candidate is empty",
            ));
        }
        if remaining.end > batch.metrics.utf16_units {
            return Err(EncodeError::InvalidSnapshot(
                "remaining find candidate exceeds the document",
            ));
        }
    }
    Ok(())
}

/// Validates one match start against its global range and document metrics.
fn validate_find_start(matched: FindMatch, metrics: DocumentMetrics) -> Result<(), EncodeError> {
    if matched.start.revision != metrics.revision {
        return Err(EncodeError::InvalidSnapshot(
            "find match revision differs from its metrics",
        ));
    }
    if matched.start.line >= metrics.lines {
        return Err(EncodeError::InvalidSnapshot(
            "find match line exceeds the document",
        ));
    }
    if matched.start.utf16_offset > matched.range.start {
        return Err(EncodeError::InvalidSnapshot(
            "find match line-relative offset exceeds its global start",
        ));
    }
    if matched.start.line == 0 && matched.start.utf16_offset != matched.range.start {
        return Err(EncodeError::InvalidSnapshot(
            "first-line find match has an inconsistent start offset",
        ));
    }
    if matched.range.start == 0 && (matched.start.line != 0 || matched.start.utf16_offset != 0) {
        return Err(EncodeError::InvalidSnapshot(
            "document-origin find match has an inconsistent start position",
        ));
    }
    Ok(())
}

/// Encodes one validated match into its fixed-width packet fields.
fn encode_find_match(packet: &mut Vec<u8>, matched: FindMatch) -> Result<(), EncodeError> {
    push_usize(packet, matched.range.start, "match range start")?;
    push_usize(packet, matched.range.end, "match range end")?;
    push_usize(packet, matched.start.line, "match start line")?;
    push_usize(
        packet,
        matched.start.utf16_offset,
        "match start utf-16 offset",
    )?;
    Ok(())
}

/// Encodes a viewport snapshot into one bounded little-endian packet.
///
/// # Errors
///
/// Returns an error when a value cannot fit its protocol field, a cursor uses
/// another revision, or the packet length would overflow the address space.
pub fn encode_viewport(snapshot: &ViewportSnapshot) -> Result<Vec<u8>, EncodeError> {
    validate_viewport_cursor_revision(
        snapshot.previous,
        snapshot.metrics.revision,
        "previous cursor revision differs from viewport metrics",
    )?;
    validate_viewport_cursor_revision(
        snapshot.next,
        snapshot.metrics.revision,
        "next cursor revision differs from viewport metrics",
    )?;
    let packet_bytes =
        snapshot
            .blocks
            .iter()
            .try_fold(VIEWPORT_HEADER_BYTES, |current_bytes, block| {
                current_bytes
                    .checked_add(RENDER_BLOCK_HEADER_BYTES)
                    .and_then(|bytes| bytes.checked_add(block.text.len()))
                    .ok_or(EncodeError::PacketTooLarge)
            })?;
    let mut packet = Vec::with_capacity(packet_bytes);

    push_u32(&mut packet, VIEWPORT_PACKET_MAGIC);
    push_u16(&mut packet, VIEWPORT_PACKET_VERSION);
    push_u16(
        &mut packet,
        (u16::from(snapshot.next.is_some()) * SNAPSHOT_HAS_NEXT)
            | (u16::from(snapshot.previous.is_some()) * SNAPSHOT_HAS_PREVIOUS),
    );
    encode_metrics(&mut packet, &snapshot.metrics)?;

    if let Some(previous) = snapshot.previous {
        push_usize(&mut packet, previous.line, "previous line")?;
        push_usize(&mut packet, previous.utf16_offset, "previous offset")?;
    } else {
        push_u64(&mut packet, 0);
        push_u64(&mut packet, 0);
    }
    if let Some(next) = snapshot.next {
        push_usize(&mut packet, next.line, "next line")?;
        push_usize(&mut packet, next.utf16_offset, "next offset")?;
    } else {
        push_u64(&mut packet, 0);
        push_u64(&mut packet, 0);
    }
    let block_count = u32::try_from(snapshot.blocks.len())
        .map_err(|_| EncodeError::ValueTooLarge("block count"))?;
    push_u32(&mut packet, block_count);

    for block in &snapshot.blocks {
        encode_render_block(&mut packet, block)?;
    }

    debug_assert_eq!(packet.len(), packet_bytes);
    Ok(packet)
}

/// Validates one optional viewport cursor against its snapshot revision.
fn validate_viewport_cursor_revision(
    cursor: Option<ViewportPosition>,
    expected_revision: u64,
    error_message: &'static str,
) -> Result<(), EncodeError> {
    if cursor.is_some_and(|position| position.revision != expected_revision) {
        return Err(EncodeError::InvalidSnapshot(error_message));
    }
    Ok(())
}

/// Encodes an edit-window snapshot into one bounded little-endian packet.
///
/// # Errors
///
/// Returns an error when a metric cannot fit its protocol field or when the
/// encoded packet length would overflow the current address space.
pub fn encode_edit_window(snapshot: &EditWindowSnapshot) -> Result<Vec<u8>, EncodeError> {
    let packet_bytes = EDIT_WINDOW_HEADER_BYTES
        .checked_add(snapshot.text.len())
        .ok_or(EncodeError::PacketTooLarge)?;
    let text_bytes = u32::try_from(snapshot.text.len())
        .map_err(|_| EncodeError::ValueTooLarge("edit-window byte length"))?;
    let mut packet = Vec::with_capacity(packet_bytes);

    push_u32(&mut packet, EDIT_WINDOW_PACKET_MAGIC);
    push_u16(&mut packet, EDIT_WINDOW_PACKET_VERSION);
    let mut flags = 0;
    if snapshot.has_previous {
        flags |= EDIT_WINDOW_HAS_PREVIOUS;
    }
    if snapshot.has_next {
        flags |= EDIT_WINDOW_HAS_NEXT;
    }
    push_u16(&mut packet, flags);
    encode_metrics(&mut packet, &snapshot.metrics)?;
    push_usize(&mut packet, snapshot.range.start, "edit-window start")?;
    push_usize(&mut packet, snapshot.range.end, "edit-window end")?;
    push_usize(&mut packet, snapshot.selection.start, "selection start")?;
    push_usize(&mut packet, snapshot.selection.end, "selection end")?;
    push_usize(&mut packet, snapshot.start.line, "edit-window start line")?;
    push_usize(
        &mut packet,
        snapshot.start.utf16_offset,
        "edit-window start offset",
    )?;
    push_u32(&mut packet, text_bytes);
    push_u32(&mut packet, 0);
    packet.extend_from_slice(snapshot.text.as_bytes());

    debug_assert_eq!(packet.len(), packet_bytes);
    Ok(packet)
}

/// Encodes common document metrics into a packet header.
fn encode_metrics(packet: &mut Vec<u8>, metrics: &DocumentMetrics) -> Result<(), EncodeError> {
    push_usize(packet, metrics.revision, "revision")?;
    push_usize(packet, metrics.bytes, "byte length")?;
    push_usize(packet, metrics.serialized_bytes, "serialized byte length")?;
    push_usize(packet, metrics.chars, "character length")?;
    push_usize(packet, metrics.utf16_units, "utf-16 length")?;
    push_usize(packet, metrics.lines, "line count")?;
    push_usize(packet, metrics.words, "word count")?;
    packet.push(
        (u8::from(metrics.is_editable) * METRICS_EDITABLE)
            | (u8::from(metrics.has_utf8_bom) * METRICS_HAS_UTF8_BOM)
            | (u8::from(metrics.has_lf_line_endings) * METRICS_HAS_LF_LINE_ENDINGS)
            | (u8::from(metrics.has_crlf_line_endings) * METRICS_HAS_CRLF_LINE_ENDINGS)
            | (u8::from(metrics.has_cr_line_endings) * METRICS_HAS_CR_LINE_ENDINGS),
    );
    packet.push(match metrics.inserted_line_ending {
        DocumentLineEnding::Lf => INSERTED_LINE_ENDING_LF,
        DocumentLineEnding::CrLf => INSERTED_LINE_ENDING_CRLF,
        DocumentLineEnding::Cr => INSERTED_LINE_ENDING_CR,
    });
    packet.extend_from_slice(&[0; 6]);
    Ok(())
}

/// Encodes one bounded render block into the packet.
fn encode_render_block(packet: &mut Vec<u8>, block: &RenderBlock) -> Result<(), EncodeError> {
    push_usize(packet, block.logical_line, "logical line")?;
    push_usize(packet, block.global_utf16_start, "global utf-16 start")?;
    push_usize(packet, block.global_utf16_end, "global utf-16 end")?;
    let mut flags = 0;
    if block.continues_at_start {
        flags |= BLOCK_CONTINUES_AT_START;
    }
    if block.continues_at_end {
        flags |= BLOCK_CONTINUES_AT_END;
    }
    packet.push(flags);
    packet.push(block.line_terminator_utf16_units);
    packet.extend_from_slice(&[0; 2]);
    let text_bytes = u32::try_from(block.text.len())
        .map_err(|_| EncodeError::ValueTooLarge("block byte length"))?;
    push_u32(packet, text_bytes);
    packet.extend_from_slice(block.text.as_bytes());
    Ok(())
}

/// Appends a platform-sized value as an unsigned 64-bit integer.
fn push_usize(
    packet: &mut Vec<u8>,
    value: impl TryInto<u64>,
    field: &'static str,
) -> Result<(), EncodeError> {
    let value = value
        .try_into()
        .map_err(|_| EncodeError::ValueTooLarge(field))?;
    push_u64(packet, value);
    Ok(())
}

/// Appends an unsigned 16-bit integer in little-endian byte order.
fn push_u16(packet: &mut Vec<u8>, value: u16) {
    packet.extend_from_slice(&value.to_le_bytes());
}

/// Appends an unsigned 32-bit integer in little-endian byte order.
fn push_u32(packet: &mut Vec<u8>, value: u32) {
    packet.extend_from_slice(&value.to_le_bytes());
}

/// Appends an unsigned 64-bit integer in little-endian byte order.
fn push_u64(packet: &mut Vec<u8>, value: u64) {
    packet.extend_from_slice(&value.to_le_bytes());
}

#[cfg(test)]
mod tests {
    use beautyxt_editor_core::{
        Document, DocumentLineEnding, DocumentMetrics, EditWindowRequest, FindBatch, FindMatch,
        Utf16Range, ViewportPosition, ViewportRequest,
    };

    use super::{
        DOCUMENT_METRICS_PACKET_BYTES, DOCUMENT_METRICS_PACKET_MAGIC,
        DOCUMENT_METRICS_PACKET_VERSION, EDIT_WINDOW_HAS_NEXT, EDIT_WINDOW_HAS_PREVIOUS,
        EDIT_WINDOW_HEADER_BYTES, EDIT_WINDOW_PACKET_MAGIC, EDIT_WINDOW_PACKET_VERSION,
        EncodeError, FIND_HAS_MATCH, FIND_HAS_REMAINING_CANDIDATE, FIND_PACKET_BYTES,
        FIND_PACKET_MAGIC, FIND_PACKET_VERSION, METRICS_EDITABLE, METRICS_HAS_CRLF_LINE_ENDINGS,
        METRICS_HAS_LF_LINE_ENDINGS, METRICS_HAS_UTF8_BOM, RENDER_BLOCK_HEADER_BYTES,
        SNAPSHOT_HAS_NEXT, SNAPSHOT_HAS_PREVIOUS, VIEWPORT_HEADER_BYTES, VIEWPORT_PACKET_MAGIC,
        VIEWPORT_PACKET_VERSION, encode_document_metrics, encode_edit_window, encode_find,
        encode_viewport,
    };

    const TEST_PACKET_MAGIC_OFFSET: usize = 0;
    const TEST_PACKET_VERSION_OFFSET: usize = 4;
    const TEST_PACKET_FLAGS_OFFSET: usize = 6;
    const TEST_METRICS_REVISION_OFFSET: usize = 8;
    const TEST_METRICS_BYTES_OFFSET: usize = 16;
    const TEST_METRICS_SERIALIZED_BYTES_OFFSET: usize = 24;
    const TEST_METRICS_CHARS_OFFSET: usize = 32;
    const TEST_METRICS_UTF16_OFFSET: usize = 40;
    const TEST_METRICS_LINES_OFFSET: usize = 48;
    const TEST_METRICS_WORDS_OFFSET: usize = 56;
    const TEST_METRICS_FLAGS_OFFSET: usize = 64;
    const TEST_METRICS_LINE_ENDING_OFFSET: usize = 65;
    const TEST_METRICS_RESERVED_OFFSET: usize = 66;
    const TEST_REVISION: u64 = 7;
    const TEST_BYTES: usize = 15;
    const TEST_SERIALIZED_BYTES: usize = 18;
    const TEST_CHARS: usize = 12;
    const TEST_UTF16_UNITS: usize = 13;
    const TEST_LINES: usize = 2;
    const TEST_WORDS: usize = 3;

    /// Reads an unsigned 16-bit field from a packet.
    fn read_u16(packet: &[u8], offset: usize) -> u16 {
        u16::from_le_bytes(
            packet[offset..offset + 2]
                .try_into()
                .expect("test field should contain two bytes"),
        )
    }

    /// Reads an unsigned 32-bit field from a packet.
    fn read_u32(packet: &[u8], offset: usize) -> u32 {
        u32::from_le_bytes(
            packet[offset..offset + 4]
                .try_into()
                .expect("test field should contain four bytes"),
        )
    }

    /// Reads an unsigned 64-bit field from a packet.
    fn read_u64(packet: &[u8], offset: usize) -> u64 {
        u64::from_le_bytes(
            packet[offset..offset + 8]
                .try_into()
                .expect("test field should contain eight bytes"),
        )
    }

    /// Verifies standalone document metrics use one fixed versioned packet.
    #[test]
    fn encodes_versioned_document_metrics_packet() {
        let metrics = DocumentMetrics {
            revision: TEST_REVISION,
            bytes: TEST_BYTES,
            serialized_bytes: TEST_SERIALIZED_BYTES,
            chars: TEST_CHARS,
            utf16_units: TEST_UTF16_UNITS,
            lines: TEST_LINES,
            words: TEST_WORDS,
            has_utf8_bom: true,
            has_lf_line_endings: true,
            has_crlf_line_endings: true,
            has_cr_line_endings: false,
            inserted_line_ending: DocumentLineEnding::CrLf,
            is_editable: true,
        };

        let packet = encode_document_metrics(&metrics).expect("metrics should be encodable");

        assert_eq!(packet.len(), DOCUMENT_METRICS_PACKET_BYTES);
        assert_eq!(
            read_u32(&packet, TEST_PACKET_MAGIC_OFFSET),
            DOCUMENT_METRICS_PACKET_MAGIC
        );
        assert_eq!(
            read_u16(&packet, TEST_PACKET_VERSION_OFFSET),
            DOCUMENT_METRICS_PACKET_VERSION
        );
        assert_eq!(read_u16(&packet, TEST_PACKET_FLAGS_OFFSET), 0);
        assert_eq!(
            read_u64(&packet, TEST_METRICS_REVISION_OFFSET),
            metrics.revision
        );
        assert_eq!(
            read_u64(&packet, TEST_METRICS_BYTES_OFFSET),
            metrics.bytes as u64
        );
        assert_eq!(
            read_u64(&packet, TEST_METRICS_SERIALIZED_BYTES_OFFSET),
            metrics.serialized_bytes as u64
        );
        assert_eq!(
            read_u64(&packet, TEST_METRICS_CHARS_OFFSET),
            metrics.chars as u64
        );
        assert_eq!(
            read_u64(&packet, TEST_METRICS_UTF16_OFFSET),
            metrics.utf16_units as u64
        );
        assert_eq!(
            read_u64(&packet, TEST_METRICS_LINES_OFFSET),
            metrics.lines as u64
        );
        assert_eq!(
            read_u64(&packet, TEST_METRICS_WORDS_OFFSET),
            metrics.words as u64
        );
        assert_eq!(
            packet[TEST_METRICS_FLAGS_OFFSET],
            METRICS_EDITABLE
                | METRICS_HAS_UTF8_BOM
                | METRICS_HAS_LF_LINE_ENDINGS
                | METRICS_HAS_CRLF_LINE_ENDINGS
        );
        assert_eq!(packet[TEST_METRICS_LINE_ENDING_OFFSET], 1);
        assert!(
            packet[TEST_METRICS_RESERVED_OFFSET..]
                .iter()
                .all(|byte| *byte == 0)
        );
    }

    /// Verifies the packet header and UTF-8 block payload remain stable.
    #[test]
    fn encodes_versioned_viewport_packet() {
        let document = Document::from_text("hello 😀\nnext");
        let snapshot = document
            .viewport(ViewportRequest {
                max_blocks: 1,
                max_block_utf16_units: 32,
                max_total_utf16_units: 32,
                ..ViewportRequest::default()
            })
            .expect("viewport should be valid");

        let packet = encode_viewport(&snapshot).expect("snapshot should be encodable");
        let text_bytes = "hello 😀".as_bytes();

        assert_eq!(read_u32(&packet, 0), VIEWPORT_PACKET_MAGIC);
        assert_eq!(read_u16(&packet, 4), VIEWPORT_PACKET_VERSION);
        assert_eq!(read_u16(&packet, 6), 1);
        assert_eq!(read_u64(&packet, 8), 0);
        assert_eq!(read_u64(&packet, 16), 15);
        assert_eq!(read_u64(&packet, 24), 15);
        assert_eq!(read_u64(&packet, 40), 13);
        assert_eq!(read_u64(&packet, 48), 2);
        assert_eq!(read_u64(&packet, 56), 3);
        assert_eq!(packet[64], METRICS_EDITABLE | METRICS_HAS_LF_LINE_ENDINGS);
        assert_eq!(read_u64(&packet, 72), 0);
        assert_eq!(read_u64(&packet, 80), 0);
        assert_eq!(read_u64(&packet, 88), 1);
        assert_eq!(read_u64(&packet, 96), 0);
        assert_eq!(read_u32(&packet, 104), 1);
        assert_eq!(read_u64(&packet, VIEWPORT_HEADER_BYTES), 0);
        assert_eq!(read_u64(&packet, VIEWPORT_HEADER_BYTES + 8), 0);
        assert_eq!(read_u64(&packet, VIEWPORT_HEADER_BYTES + 16), 8);
        assert_eq!(packet[VIEWPORT_HEADER_BYTES + 25], 1);
        assert_eq!(
            read_u32(&packet, VIEWPORT_HEADER_BYTES + 28),
            u32::try_from(text_bytes.len()).expect("test payload should fit")
        );
        assert_eq!(
            &packet[VIEWPORT_HEADER_BYTES + RENDER_BLOCK_HEADER_BYTES..],
            text_bytes
        );
    }

    /// Verifies viewport packets independently identify both page anchors.
    #[test]
    fn encodes_bidirectional_viewport_anchors() {
        let document = Document::from_text("first\nsecond\nthird");
        let request = ViewportRequest {
            max_blocks: 1,
            max_block_utf16_units: 16,
            max_total_utf16_units: 16,
            ..ViewportRequest::default()
        };
        let first = document
            .viewport(request)
            .expect("first viewport should be valid");
        let second = document
            .viewport(ViewportRequest {
                start: first.next.expect("second line should remain"),
                ..request
            })
            .expect("second viewport should be valid");

        let packet = encode_viewport(&second).expect("snapshot should be encodable");

        assert_eq!(
            read_u16(&packet, 6),
            SNAPSHOT_HAS_NEXT | SNAPSHOT_HAS_PREVIOUS
        );
        assert_eq!(read_u64(&packet, 72), 1);
        assert_eq!(read_u64(&packet, 80), 0);
        assert_eq!(read_u64(&packet, 88), 2);
        assert_eq!(read_u64(&packet, 96), 0);
    }

    /// Verifies cursor revisions cannot be silently rebound during encoding.
    #[test]
    fn rejects_viewport_cursor_from_another_revision() {
        let document = Document::from_text("first\nsecond");
        let mut snapshot = document
            .viewport(ViewportRequest {
                max_blocks: 1,
                max_block_utf16_units: 16,
                max_total_utf16_units: 16,
                ..ViewportRequest::default()
            })
            .expect("viewport should be valid");
        snapshot.previous = Some(ViewportPosition {
            revision: snapshot.metrics.revision + 1,
            line: 0,
            utf16_offset: 0,
        });

        let result = encode_viewport(&snapshot);

        assert_eq!(
            result,
            Err(EncodeError::InvalidSnapshot(
                "previous cursor revision differs from viewport metrics"
            ))
        );
    }

    /// Verifies the edit-window header and multiline UTF-8 payload remain stable.
    #[test]
    fn encodes_versioned_edit_window_packet() {
        let document = Document::from_text("zero\nhello 😀\nnext");
        let snapshot = document
            .edit_window(EditWindowRequest {
                revision: 0,
                selection: Utf16Range::new(8, 8),
                max_utf16_units: 10,
            })
            .expect("edit window should be valid");

        let packet = encode_edit_window(&snapshot).expect("snapshot should be encodable");

        assert_eq!(read_u32(&packet, 0), EDIT_WINDOW_PACKET_MAGIC);
        assert_eq!(read_u16(&packet, 4), EDIT_WINDOW_PACKET_VERSION);
        assert_eq!(
            read_u16(&packet, 6),
            EDIT_WINDOW_HAS_PREVIOUS | EDIT_WINDOW_HAS_NEXT
        );
        assert_eq!(read_u64(&packet, 8), 0);
        assert_eq!(read_u64(&packet, 16), 20);
        assert_eq!(read_u64(&packet, 24), 20);
        assert_eq!(read_u64(&packet, 40), 18);
        assert_eq!(read_u64(&packet, 48), 3);
        assert_eq!(read_u64(&packet, 56), 4);
        assert_eq!(packet[64], METRICS_EDITABLE | METRICS_HAS_LF_LINE_ENDINGS);
        assert_eq!(read_u64(&packet, 72), snapshot.range.start as u64);
        assert_eq!(read_u64(&packet, 80), snapshot.range.end as u64);
        assert_eq!(read_u64(&packet, 88), 8);
        assert_eq!(read_u64(&packet, 96), 8);
        assert_eq!(read_u64(&packet, 104), snapshot.start.line as u64);
        assert_eq!(read_u64(&packet, 112), snapshot.start.utf16_offset as u64);
        assert_eq!(
            read_u32(&packet, 120),
            u32::try_from(snapshot.text.len()).expect("test payload should fit")
        );
        assert_eq!(read_u32(&packet, 124), 0);
        assert_eq!(
            &packet[EDIT_WINDOW_HEADER_BYTES..],
            snapshot.text.as_bytes()
        );
    }

    /// Verifies match packets preserve metrics, ranges, and line-relative starts.
    #[test]
    fn encodes_versioned_find_match_packet() {
        let batch = FindBatch {
            metrics: test_find_metrics(),
            matched: Some(test_find_match()),
            remaining_candidate_range: None,
        };

        let packet = encode_find(&batch).expect("find match should be encodable");

        assert_eq!(packet.len(), FIND_PACKET_BYTES);
        assert_eq!(read_u32(&packet, 0), FIND_PACKET_MAGIC);
        assert_eq!(read_u16(&packet, 4), FIND_PACKET_VERSION);
        assert_eq!(read_u16(&packet, 6), FIND_HAS_MATCH);
        assert_eq!(read_u64(&packet, 8), TEST_REVISION);
        assert_eq!(read_u64(&packet, 72), 6);
        assert_eq!(read_u64(&packet, 80), 8);
        assert_eq!(read_u64(&packet, 88), 0);
        assert_eq!(read_u64(&packet, 96), 6);
        assert_eq!(read_u64(&packet, 104), 0);
        assert_eq!(read_u64(&packet, 112), 0);
    }

    /// Verifies remaining and exhausted packets use canonical absent fields.
    #[test]
    fn encodes_find_progress_and_exhaustion() {
        let remaining_batch = FindBatch {
            metrics: test_find_metrics(),
            matched: None,
            remaining_candidate_range: Some(Utf16Range::new(8, TEST_UTF16_UNITS)),
        };
        let exhausted_batch = FindBatch {
            metrics: test_find_metrics(),
            matched: None,
            remaining_candidate_range: None,
        };

        let remaining_packet =
            encode_find(&remaining_batch).expect("find progress should be encodable");
        let exhausted_packet =
            encode_find(&exhausted_batch).expect("find exhaustion should be encodable");

        assert_eq!(read_u16(&remaining_packet, 6), FIND_HAS_REMAINING_CANDIDATE);
        assert!(remaining_packet[72..104].iter().all(|byte| *byte == 0));
        assert_eq!(read_u64(&remaining_packet, 104), 8);
        assert_eq!(read_u64(&remaining_packet, 112), TEST_UTF16_UNITS as u64);
        assert_eq!(read_u16(&exhausted_packet, 6), 0);
        assert!(exhausted_packet[72..].iter().all(|byte| *byte == 0));
    }

    /// Verifies conflicting or inconsistent find batches fail before encoding.
    #[test]
    fn rejects_invalid_find_batches() {
        let conflicting = FindBatch {
            metrics: test_find_metrics(),
            matched: Some(test_find_match()),
            remaining_candidate_range: Some(Utf16Range::new(8, TEST_UTF16_UNITS)),
        };
        assert_eq!(
            encode_find(&conflicting),
            Err(EncodeError::InvalidSnapshot(
                "find batch contains both a match and a remaining candidate"
            ))
        );

        let mut stale_match = test_find_match();
        stale_match.start.revision += 1;
        let stale = FindBatch {
            metrics: test_find_metrics(),
            matched: Some(stale_match),
            remaining_candidate_range: None,
        };
        assert_eq!(
            encode_find(&stale),
            Err(EncodeError::InvalidSnapshot(
                "find match revision differs from its metrics"
            ))
        );

        let empty_remaining = FindBatch {
            metrics: test_find_metrics(),
            matched: None,
            remaining_candidate_range: Some(Utf16Range::new(8, 8)),
        };
        assert_eq!(
            encode_find(&empty_remaining),
            Err(EncodeError::InvalidSnapshot(
                "remaining find candidate is empty"
            ))
        );

        let reversed_remaining = FindBatch {
            metrics: test_find_metrics(),
            matched: None,
            remaining_candidate_range: Some(Utf16Range::new(9, 8)),
        };
        assert_eq!(
            encode_find(&reversed_remaining),
            Err(EncodeError::InvalidSnapshot(
                "remaining find candidate end precedes its start"
            ))
        );
    }

    /// Returns deterministic metrics shared by find encoder tests.
    fn test_find_metrics() -> DocumentMetrics {
        DocumentMetrics {
            revision: TEST_REVISION,
            bytes: TEST_BYTES,
            serialized_bytes: TEST_SERIALIZED_BYTES,
            chars: TEST_CHARS,
            utf16_units: TEST_UTF16_UNITS,
            lines: TEST_LINES,
            words: TEST_WORDS,
            has_utf8_bom: false,
            has_lf_line_endings: false,
            has_crlf_line_endings: false,
            has_cr_line_endings: false,
            inserted_line_ending: DocumentLineEnding::Lf,
            is_editable: true,
        }
    }

    /// Returns one deterministic scalar-aligned find match.
    fn test_find_match() -> FindMatch {
        FindMatch {
            range: Utf16Range::new(6, 8),
            start: ViewportPosition {
                revision: TEST_REVISION,
                line: 0,
                utf16_offset: 6,
            },
        }
    }
}
