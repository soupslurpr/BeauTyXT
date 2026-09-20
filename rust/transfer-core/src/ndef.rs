//! Decodes bounded NDEF text records without granting them active behavior.

use std::char::decode_utf16;

use crate::{MAX_NFC_TEXT_BYTES, TransferError, TransferFormat, decode_envelope};

/// Contains the exact MIME type used by `BeauTyXT`'s custom NFC envelope.
pub const NFC_TRANSFER_MIME_TYPE: &str = "application/vnd.dev.soupslurpr.beautyxt.transfer";
/// Limits one complete serialized NDEF message.
pub const MAX_NFC_MESSAGE_BYTES: usize = 256 * 1024;
/// Limits the canonical UTF-8 text produced by one NDEF decode.
pub const MAX_NFC_DECODED_TEXT_BYTES: usize = 256 * 1024;
/// Limits one custom envelope so its complete long-form MIME record fits the message ceiling.
pub const MAX_NFC_ENVELOPE_BYTES: usize =
    MAX_NFC_MESSAGE_BYTES - LONG_RECORD_HEADER_BYTES - NFC_TRANSFER_MIME_TYPE.len();
/// Limits one structured NFC result packet returned to the Android client.
pub const MAX_NFC_RESULT_PACKET_BYTES: usize = MAX_NFC_MESSAGE_BYTES + 64;

/// Contains the binary prefix of one decoded NFC result packet.
pub const NFC_RESULT_PACKET_MAGIC: [u8; 4] = *b"BXNF";

const NFC_RESULT_PACKET_VERSION: u8 = 1;
const NFC_RESULT_PACKET_HEADER_BYTES: usize = 16;
const LONG_RECORD_HEADER_BYTES: usize = 6;
const NDEF_FLAG_MESSAGE_BEGIN: u8 = 0x80;
const NDEF_FLAG_MESSAGE_END: u8 = 0x40;
const NDEF_FLAG_CHUNK: u8 = 0x20;
const NDEF_FLAG_SHORT_RECORD: u8 = 0x10;
const NDEF_FLAG_ID_LENGTH: u8 = 0x08;
const NDEF_TNF_MASK: u8 = 0x07;
const NDEF_TNF_EMPTY: u8 = 0x00;
const NDEF_TNF_WELL_KNOWN: u8 = 0x01;
const NDEF_TNF_MIME_MEDIA: u8 = 0x02;
const NDEF_TNF_UNKNOWN: u8 = 0x05;
const NDEF_TNF_UNCHANGED: u8 = 0x06;
const NDEF_TNF_RESERVED: u8 = 0x07;
const TEXT_UTF16_FLAG: u8 = 0x80;
const TEXT_RESERVED_FLAG: u8 = 0x40;
const TEXT_LANGUAGE_LENGTH_MASK: u8 = 0x3f;
const TYPE_TEXT: &[u8] = b"T";
const TYPE_URI: &[u8] = b"U";
const TYPE_SMART_POSTER: &[u8] = b"Sp";
const MIME_TEXT_PLAIN: &str = "text/plain";
const MIME_TEXT_MARKDOWN: &str = "text/markdown";
const MIME_TEXT_X_MARKDOWN: &str = "text/x-markdown";
const MIME_APPLICATION_MARKDOWN: &str = "application/markdown";
const MIME_APPLICATION_X_MARKDOWN: &str = "application/x-markdown";
const MIME_TEXT_HTML: &str = "text/html";
const URI_PREFIXES: [&str; 36] = [
    "",
    "http://www.",
    "https://www.",
    "http://",
    "https://",
    "tel:",
    "mailto:",
    "ftp://anonymous:anonymous@",
    "ftp://ftp.",
    "ftps://",
    "sftp://",
    "smb://",
    "nfs://",
    "ftp://",
    "dav://",
    "news:",
    "telnet://",
    "imap:",
    "rtsp://",
    "urn:",
    "pop:",
    "sip:",
    "sips:",
    "tftp:",
    "btspp://",
    "btl2cap://",
    "btgoep://",
    "tcpobex://",
    "irdaobex://",
    "file://",
    "urn:epc:id:",
    "urn:epc:tag:",
    "urn:epc:pat:",
    "urn:epc:raw:",
    "urn:epc:",
    "urn:nfc:",
];

/// Identifies the inert source representation decoded from one NFC record.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum NfcTextSource {
    /// Carries a `BeauTyXT` custom envelope.
    BeauTyXT,
    /// Carries an NFC Forum Text RTD.
    Text,
    /// Carries an NFC Forum URI RTD.
    Uri,
    /// Carries a `text/plain` MIME record.
    PlainTextMime,
    /// Carries a supported Markdown MIME record.
    MarkdownMime,
    /// Carries a `text/html` MIME record for safe Markdown presentation.
    HtmlMime,
    /// Carries the single URI selected from an NFC Forum Smart Poster.
    SmartPoster,
}

impl From<NfcTextSource> for u8 {
    fn from(source: NfcTextSource) -> Self {
        match source {
            NfcTextSource::BeauTyXT => 0,
            NfcTextSource::Text => 1,
            NfcTextSource::Uri => 2,
            NfcTextSource::PlainTextMime => 3,
            NfcTextSource::MarkdownMime => 4,
            NfcTextSource::HtmlMime => 5,
            NfcTextSource::SmartPoster => 6,
        }
    }
}

/// Owns one validated inert text value decoded from a complete NDEF message.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ReceivedNfcText {
    source: NfcTextSource,
    format: TransferFormat,
    tag_label: Option<String>,
    smart_poster_title: Option<String>,
    text: String,
}

impl ReceivedNfcText {
    /// Returns the inert record representation that supplied the text.
    #[must_use]
    pub const fn source(&self) -> NfcTextSource {
        self.source
    }

    /// Returns the editor format selected for the received source.
    #[must_use]
    pub const fn format(&self) -> TransferFormat {
        self.format
    }

    /// Returns the optional human-readable custom tag label.
    #[must_use]
    pub fn tag_label(&self) -> Option<&str> {
        self.tag_label.as_deref()
    }

    /// Returns the optional inert Smart Poster title.
    #[must_use]
    pub fn smart_poster_title(&self) -> Option<&str> {
        self.smart_poster_title.as_deref()
    }

    /// Returns the canonical decoded UTF-8 text.
    #[must_use]
    pub fn text(&self) -> &str {
        &self.text
    }

    /// Encodes the result into one bounded packet for the Android client.
    ///
    /// # Errors
    ///
    /// Returns [`TransferError::TextLimit`] if combined bounded metadata cannot
    /// fit the isolated result packet.
    pub fn to_packet(&self) -> Result<Vec<u8>, TransferError> {
        let tag_label = self.tag_label.as_deref().unwrap_or("").as_bytes();
        let title = self.smart_poster_title.as_deref().unwrap_or("").as_bytes();
        let tag_label_length =
            u8::try_from(tag_label.len()).map_err(|_| TransferError::TextLimit)?;
        let title_length = u32::try_from(title.len()).map_err(|_| TransferError::TextLimit)?;
        let text_length = u32::try_from(self.text.len()).map_err(|_| TransferError::TextLimit)?;
        let packet_length = self.packet_length()?;
        let mut packet = Vec::with_capacity(packet_length);
        packet.extend_from_slice(&NFC_RESULT_PACKET_MAGIC);
        packet.push(NFC_RESULT_PACKET_VERSION);
        packet.push(self.source.into());
        packet.push(self.format.into());
        packet.push(tag_label_length);
        packet.extend_from_slice(&title_length.to_be_bytes());
        packet.extend_from_slice(&text_length.to_be_bytes());
        packet.extend_from_slice(tag_label);
        packet.extend_from_slice(title);
        packet.extend_from_slice(self.text.as_bytes());
        Ok(packet)
    }

    /// Checks the combined encoded length without copying the received content.
    fn packet_length(&self) -> Result<usize, TransferError> {
        let length = NFC_RESULT_PACKET_HEADER_BYTES
            .checked_add(self.tag_label.as_ref().map_or(0, String::len))
            .and_then(|length| {
                length.checked_add(self.smart_poster_title.as_ref().map_or(0, String::len))
            })
            .and_then(|length| length.checked_add(self.text.len()))
            .ok_or(TransferError::TextLimit)?;
        if length > MAX_NFC_RESULT_PACKET_BYTES {
            return Err(TransferError::TextLimit);
        }
        Ok(length)
    }
}

/// Decodes one complete bounded NDEF message into inert editable text.
///
/// Ordinary messages must contain exactly one top-level record. A single Smart
/// Poster may contain nested metadata, but it must resolve to exactly one URI.
///
/// # Errors
///
/// Returns a [`TransferError`] when framing, record semantics, Unicode,
/// ambiguity, or either 256 KiB resource ceiling is violated.
pub fn decode_ndef_message(message: &[u8]) -> Result<ReceivedNfcText, TransferError> {
    if message.len() > MAX_NFC_MESSAGE_BYTES {
        return Err(TransferError::TextLimit);
    }
    let mut selected_record = None;
    visit_ndef_records(message, |record| {
        if selected_record.replace(record).is_some() {
            return Err(TransferError::AmbiguousNdef);
        }
        Ok(())
    })?;
    decode_top_level_record(selected_record.ok_or(TransferError::InvalidNdef)?)
}

/// Borrows one fully framed NDEF record.
#[derive(Clone, Copy)]
struct NdefRecord<'message> {
    tnf: u8,
    record_type: &'message [u8],
    payload: &'message [u8],
}

/// Visits every strictly framed record in one complete NDEF message.
fn visit_ndef_records<'message>(
    message: &'message [u8],
    mut visitor: impl FnMut(NdefRecord<'message>) -> Result<(), TransferError>,
) -> Result<(), TransferError> {
    if message.is_empty() {
        return Err(TransferError::InvalidNdef);
    }
    let mut offset = 0_usize;
    let mut record_index = 0_usize;
    loop {
        let flags = read_byte(message, &mut offset)?;
        let message_begin = flags & NDEF_FLAG_MESSAGE_BEGIN != 0;
        let message_end = flags & NDEF_FLAG_MESSAGE_END != 0;
        let chunked = flags & NDEF_FLAG_CHUNK != 0;
        let short_record = flags & NDEF_FLAG_SHORT_RECORD != 0;
        let has_id_length = flags & NDEF_FLAG_ID_LENGTH != 0;
        let tnf = flags & NDEF_TNF_MASK;
        if message_begin != (record_index == 0)
            || chunked
            || tnf == NDEF_TNF_UNCHANGED
            || tnf == NDEF_TNF_RESERVED
        {
            return Err(TransferError::InvalidNdef);
        }
        let type_length = usize::from(read_byte(message, &mut offset)?);
        let payload_length = if short_record {
            usize::from(read_byte(message, &mut offset)?)
        } else {
            read_u32_length(message, &mut offset)?
        };
        let id_length = if has_id_length {
            usize::from(read_byte(message, &mut offset)?)
        } else {
            0
        };
        let record_type = read_slice(message, &mut offset, type_length)?;
        let id = read_slice(message, &mut offset, id_length)?;
        let payload = read_slice(message, &mut offset, payload_length)?;
        if (tnf == NDEF_TNF_EMPTY
            && (!record_type.is_empty() || !id.is_empty() || !payload.is_empty()))
            || (tnf == NDEF_TNF_UNKNOWN && !record_type.is_empty())
        {
            return Err(TransferError::InvalidNdef);
        }
        visitor(NdefRecord {
            tnf,
            record_type,
            payload,
        })?;
        record_index = record_index
            .checked_add(1)
            .ok_or(TransferError::InvalidNdef)?;
        if message_end {
            return if offset == message.len() {
                Ok(())
            } else {
                Err(TransferError::InvalidNdef)
            };
        }
        if offset == message.len() {
            return Err(TransferError::InvalidNdef);
        }
    }
}

/// Decodes one supported top-level record without activating its content.
fn decode_top_level_record(record: NdefRecord<'_>) -> Result<ReceivedNfcText, TransferError> {
    match (record.tnf, record.record_type) {
        (NDEF_TNF_MIME_MEDIA, record_type) if record_type == NFC_TRANSFER_MIME_TYPE.as_bytes() => {
            let received = decode_envelope(record.payload, MAX_NFC_TEXT_BYTES)?;
            Ok(ReceivedNfcText {
                source: NfcTextSource::BeauTyXT,
                format: received.format(),
                tag_label: received.tag_label().map(str::to_owned),
                smart_poster_title: None,
                text: received.text().to_owned(),
            })
        }
        (NDEF_TNF_WELL_KNOWN, TYPE_TEXT) => Ok(ReceivedNfcText {
            source: NfcTextSource::Text,
            format: TransferFormat::PlainText,
            tag_label: None,
            smart_poster_title: None,
            text: decode_text_record(record.payload)?,
        }),
        (NDEF_TNF_WELL_KNOWN, TYPE_URI) => Ok(ReceivedNfcText {
            source: NfcTextSource::Uri,
            format: TransferFormat::PlainText,
            tag_label: None,
            smart_poster_title: None,
            text: decode_uri_record(record.payload)?,
        }),
        (NDEF_TNF_WELL_KNOWN, TYPE_SMART_POSTER) => decode_smart_poster(record.payload),
        (NDEF_TNF_MIME_MEDIA, _) => decode_mime_record(record.record_type, record.payload),
        _ => Err(TransferError::UnsupportedNdef),
    }
}

/// Decodes one supported UTF-8 MIME record.
fn decode_mime_record(
    record_type: &[u8],
    payload: &[u8],
) -> Result<ReceivedNfcText, TransferError> {
    let mime_type = std::str::from_utf8(record_type).map_err(|_| TransferError::InvalidNdef)?;
    if !mime_type.is_ascii() {
        return Err(TransferError::InvalidNdef);
    }
    let mime_type = mime_type.to_ascii_lowercase();
    let (source, format) = match mime_type.as_str() {
        MIME_TEXT_PLAIN => (NfcTextSource::PlainTextMime, TransferFormat::PlainText),
        MIME_TEXT_MARKDOWN
        | MIME_TEXT_X_MARKDOWN
        | MIME_APPLICATION_MARKDOWN
        | MIME_APPLICATION_X_MARKDOWN => (NfcTextSource::MarkdownMime, TransferFormat::Markdown),
        MIME_TEXT_HTML => (NfcTextSource::HtmlMime, TransferFormat::Markdown),
        _ => return Err(TransferError::UnsupportedNdef),
    };
    Ok(ReceivedNfcText {
        source,
        format,
        tag_label: None,
        smart_poster_title: None,
        text: decode_normalized_utf8(payload)?,
    })
}

/// Decodes one NFC Forum Text RTD with strict language and Unicode validation.
fn decode_text_record(payload: &[u8]) -> Result<String, TransferError> {
    let (&status, remainder) = payload.split_first().ok_or(TransferError::InvalidNdef)?;
    if status & TEXT_RESERVED_FLAG != 0 {
        return Err(TransferError::InvalidNdef);
    }
    let language_length = usize::from(status & TEXT_LANGUAGE_LENGTH_MASK);
    let (language, encoded_text) = remainder
        .split_at_checked(language_length)
        .ok_or(TransferError::InvalidNdef)?;
    if language.is_empty()
        || !language
            .iter()
            .all(|byte| byte.is_ascii_alphanumeric() || *byte == b'-')
    {
        return Err(TransferError::InvalidNdef);
    }
    if status & TEXT_UTF16_FLAG == 0 {
        decode_normalized_utf8(encoded_text)
    } else {
        decode_normalized_utf16(encoded_text)
    }
}

/// Decodes one NFC Forum URI RTD into inert plain text.
fn decode_uri_record(payload: &[u8]) -> Result<String, TransferError> {
    let (&prefix_code, suffix) = payload.split_first().ok_or(TransferError::InvalidNdef)?;
    let prefix = URI_PREFIXES
        .get(usize::from(prefix_code))
        .ok_or(TransferError::InvalidNdef)?;
    let suffix = std::str::from_utf8(suffix).map_err(|_| TransferError::InvalidUtf8)?;
    let text_length = prefix
        .len()
        .checked_add(suffix.len())
        .ok_or(TransferError::TextLimit)?;
    if text_length == 0 || text_length > MAX_NFC_DECODED_TEXT_BYTES {
        return Err(if text_length == 0 {
            TransferError::InvalidNdef
        } else {
            TransferError::TextLimit
        });
    }
    let mut uri = String::with_capacity(text_length);
    uri.push_str(prefix);
    uri.push_str(suffix);
    Ok(uri)
}

/// Selects one inert URI and optional title from a nested Smart Poster message.
fn decode_smart_poster(payload: &[u8]) -> Result<ReceivedNfcText, TransferError> {
    let mut uri = None;
    let mut title = None;
    visit_ndef_records(payload, |record| {
        match (record.tnf, record.record_type) {
            (NDEF_TNF_WELL_KNOWN, TYPE_URI) => {
                if uri.is_some() {
                    return Err(TransferError::AmbiguousNdef);
                }
                uri = Some(decode_uri_record(record.payload)?);
            }
            (NDEF_TNF_WELL_KNOWN, TYPE_TEXT) => {
                let candidate = decode_text_record(record.payload)?;
                if title.is_none() && !candidate.is_empty() {
                    title = Some(candidate);
                }
            }
            _ => {}
        }
        Ok(())
    })?;
    let received = ReceivedNfcText {
        source: NfcTextSource::SmartPoster,
        format: TransferFormat::PlainText,
        tag_label: None,
        smart_poster_title: title,
        text: uri.ok_or(TransferError::InvalidNdef)?,
    };
    received.packet_length()?;
    Ok(received)
}

/// Strictly decodes UTF-8 and canonicalizes line endings.
fn decode_normalized_utf8(bytes: &[u8]) -> Result<String, TransferError> {
    let text = std::str::from_utf8(bytes).map_err(|_| TransferError::InvalidUtf8)?;
    normalize_line_endings(text)
}

/// Strictly decodes BOM-tagged UTF-16 and canonicalizes line endings.
fn decode_normalized_utf16(bytes: &[u8]) -> Result<String, TransferError> {
    let (byte_order, encoded) = match bytes.get(..2) {
        Some([0xfe, 0xff]) => (Utf16ByteOrder::BigEndian, &bytes[2..]),
        Some([0xff, 0xfe]) => (Utf16ByteOrder::LittleEndian, &bytes[2..]),
        _ => return Err(TransferError::InvalidNdef),
    };
    if encoded.len() % 2 != 0 {
        return Err(TransferError::InvalidNdef);
    }
    let units = encoded
        .as_chunks::<2>()
        .0
        .iter()
        .map(|pair| match byte_order {
            Utf16ByteOrder::BigEndian => u16::from_be_bytes([pair[0], pair[1]]),
            Utf16ByteOrder::LittleEndian => u16::from_le_bytes([pair[0], pair[1]]),
        });
    let decoded = decode_utf16(units)
        .collect::<Result<String, _>>()
        .map_err(|_| TransferError::InvalidUtf8)?;
    normalize_line_endings(&decoded)
}

/// Identifies byte order after a mandatory UTF-16 BOM.
#[derive(Clone, Copy)]
enum Utf16ByteOrder {
    BigEndian,
    LittleEndian,
}

/// Canonicalizes CR and CRLF while enforcing the decoded UTF-8 ceiling.
fn normalize_line_endings(text: &str) -> Result<String, TransferError> {
    if text.len() > MAX_NFC_DECODED_TEXT_BYTES {
        return Err(TransferError::TextLimit);
    }
    if !text.as_bytes().contains(&b'\r') {
        return Ok(text.to_owned());
    }
    let mut normalized = String::with_capacity(text.len());
    let mut characters = text.chars().peekable();
    while let Some(character) = characters.next() {
        if character == '\r' {
            normalized.push('\n');
            if characters.peek() == Some(&'\n') {
                characters.next();
            }
        } else {
            normalized.push(character);
        }
    }
    if normalized.len() > MAX_NFC_DECODED_TEXT_BYTES {
        return Err(TransferError::TextLimit);
    }
    Ok(normalized)
}

/// Reads one byte and advances a checked message offset.
fn read_byte(message: &[u8], offset: &mut usize) -> Result<u8, TransferError> {
    let byte = *message.get(*offset).ok_or(TransferError::InvalidNdef)?;
    *offset = offset.checked_add(1).ok_or(TransferError::InvalidNdef)?;
    Ok(byte)
}

/// Reads one network-order unsigned length and advances a checked message offset.
fn read_u32_length(message: &[u8], offset: &mut usize) -> Result<usize, TransferError> {
    let bytes = read_slice(message, offset, u32::BITS as usize / 8)?;
    usize::try_from(u32::from_be_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]))
        .map_err(|_| TransferError::InvalidNdef)
}

/// Reads one exact slice and advances a checked message offset.
fn read_slice<'message>(
    message: &'message [u8],
    offset: &mut usize,
    length: usize,
) -> Result<&'message [u8], TransferError> {
    let end = offset
        .checked_add(length)
        .ok_or(TransferError::InvalidNdef)?;
    let slice = message
        .get(*offset..end)
        .ok_or(TransferError::InvalidNdef)?;
    *offset = end;
    Ok(slice)
}

#[cfg(test)]
mod tests {
    //! Verifies strict isolated NDEF decoding and resource ceilings.

    use super::{
        MAX_NFC_MESSAGE_BYTES, MIME_TEXT_HTML, NDEF_TNF_MIME_MEDIA, NDEF_TNF_WELL_KNOWN,
        NFC_TRANSFER_MIME_TYPE, NfcTextSource, TYPE_SMART_POSTER, TYPE_TEXT, TYPE_URI,
        decode_ndef_message,
    };
    use crate::{MAX_NFC_TEXT_BYTES, TransferError, TransferFormat, encode_envelope};

    #[test]
    fn custom_envelope_fills_the_complete_message_ceiling() {
        let text = vec![b'x'; MAX_NFC_TEXT_BYTES];
        let envelope = encode_envelope(
            &text,
            TransferFormat::Markdown,
            Some("A01"),
            MAX_NFC_TEXT_BYTES,
        )
        .expect("maximum custom NFC text should encode");
        let message = single_record(
            NDEF_TNF_MIME_MEDIA,
            NFC_TRANSFER_MIME_TYPE.as_bytes(),
            &envelope,
        );

        assert_eq!(message.len(), MAX_NFC_MESSAGE_BYTES);
        let received = decode_ndef_message(&message).expect("maximum NDEF should decode");
        assert_eq!(received.format(), TransferFormat::Markdown);
        assert_eq!(received.tag_label(), Some("A01"));
        assert_eq!(received.text().as_bytes(), text);
    }

    #[test]
    fn text_record_strictly_decodes_utf8_and_normalizes_lines() {
        let message = single_record(
            NDEF_TNF_WELL_KNOWN,
            TYPE_TEXT,
            b"\x02enfirst\r\nsecond\rthird",
        );

        let received = decode_ndef_message(&message).expect("valid Text RTD should decode");

        assert_eq!(received.source(), NfcTextSource::Text);
        assert_eq!(received.text(), "first\nsecond\nthird");
    }

    #[test]
    fn text_record_strictly_decodes_bom_tagged_utf16() {
        let mut payload = b"\x82en\xfe\xff".to_vec();
        payload.extend("Private 🌞".encode_utf16().flat_map(u16::to_be_bytes));
        let message = single_record(NDEF_TNF_WELL_KNOWN, TYPE_TEXT, &payload);

        let received = decode_ndef_message(&message).expect("valid UTF-16 Text RTD should decode");

        assert_eq!(received.text(), "Private 🌞");
    }

    #[test]
    fn uri_record_expands_to_inert_plain_text() {
        let message = single_record(NDEF_TNF_WELL_KNOWN, TYPE_URI, b"\x04example.com/document");

        let received = decode_ndef_message(&message).expect("valid URI RTD should decode");

        assert_eq!(received.source(), NfcTextSource::Uri);
        assert_eq!(received.text(), "https://example.com/document");
    }

    #[test]
    fn html_mime_uses_markdown_presentation_without_execution() {
        let message = single_record(
            NDEF_TNF_MIME_MEDIA,
            MIME_TEXT_HTML.as_bytes(),
            b"<h1>Title</h1><script>alert(1)</script>",
        );

        let received = decode_ndef_message(&message).expect("bounded HTML text should decode");

        assert_eq!(received.source(), NfcTextSource::HtmlMime);
        assert_eq!(received.format(), TransferFormat::Markdown);
        assert_eq!(received.text(), "<h1>Title</h1><script>alert(1)</script>");
    }

    #[test]
    fn smart_poster_selects_one_uri_and_title_but_ignores_actions() {
        let nested = message_from_records(&[
            (NDEF_TNF_WELL_KNOWN, TYPE_URI, b"\x04example.com"),
            (NDEF_TNF_WELL_KNOWN, TYPE_TEXT, b"\x02enExample"),
            (NDEF_TNF_WELL_KNOWN, b"act", b"\x00"),
        ]);
        let message = single_record(NDEF_TNF_WELL_KNOWN, TYPE_SMART_POSTER, &nested);

        let received = decode_ndef_message(&message).expect("valid Smart Poster should decode");

        assert_eq!(received.source(), NfcTextSource::SmartPoster);
        assert_eq!(received.text(), "https://example.com");
        assert_eq!(received.smart_poster_title(), Some("Example"));
        assert_eq!(
            received.packet_length().unwrap(),
            received.to_packet().unwrap().len()
        );
    }

    /// Checks the combined metadata ceiling without first allocating a packet.
    #[test]
    fn measures_combined_result_packet_boundaries() {
        let title = "Example";
        let text_length = super::MAX_NFC_RESULT_PACKET_BYTES
            - super::NFC_RESULT_PACKET_HEADER_BYTES
            - title.len();
        let mut received = super::ReceivedNfcText {
            source: NfcTextSource::SmartPoster,
            format: TransferFormat::PlainText,
            tag_label: None,
            smart_poster_title: Some(title.to_owned()),
            text: "x".repeat(text_length),
        };
        assert_eq!(
            received.packet_length(),
            Ok(super::MAX_NFC_RESULT_PACKET_BYTES)
        );
        assert_eq!(
            received.to_packet().unwrap().len(),
            super::MAX_NFC_RESULT_PACKET_BYTES
        );

        received.text.push('x');
        assert_eq!(received.packet_length(), Err(TransferError::TextLimit));
        assert_eq!(received.to_packet(), Err(TransferError::TextLimit));
    }

    #[test]
    fn ordinary_multi_record_messages_are_ambiguous() {
        let message = message_from_records(&[
            (NDEF_TNF_WELL_KNOWN, TYPE_URI, b"\x04first.example"),
            (NDEF_TNF_WELL_KNOWN, TYPE_URI, b"\x04second.example"),
        ]);

        assert_eq!(
            decode_ndef_message(&message),
            Err(TransferError::AmbiguousNdef)
        );
    }

    #[test]
    fn smart_poster_rejects_multiple_uris() {
        let nested = message_from_records(&[
            (NDEF_TNF_WELL_KNOWN, TYPE_URI, b"\x04first.example"),
            (NDEF_TNF_WELL_KNOWN, TYPE_URI, b"\x04second.example"),
        ]);
        let message = single_record(NDEF_TNF_WELL_KNOWN, TYPE_SMART_POSTER, &nested);

        assert_eq!(
            decode_ndef_message(&message),
            Err(TransferError::AmbiguousNdef)
        );
    }

    #[test]
    fn smart_poster_uses_the_first_nonempty_title() {
        let nested = message_from_records(&[
            (NDEF_TNF_WELL_KNOWN, TYPE_URI, b"\x04example.com"),
            (NDEF_TNF_WELL_KNOWN, TYPE_TEXT, b"\x02enFirst"),
            (NDEF_TNF_WELL_KNOWN, TYPE_TEXT, b"\x02enSecond"),
        ]);
        let message = single_record(NDEF_TNF_WELL_KNOWN, TYPE_SMART_POSTER, &nested);

        let received = decode_ndef_message(&message).expect("multilingual titles should decode");

        assert_eq!(received.smart_poster_title(), Some("First"));
    }

    #[test]
    fn supported_records_may_carry_ignored_identifiers() {
        let message =
            single_record_with_id(NDEF_TNF_WELL_KNOWN, TYPE_TEXT, b"record-one", b"\x02enText");

        let received = decode_ndef_message(&message).expect("record ID should not alter text");

        assert_eq!(received.text(), "Text");
    }

    #[test]
    fn rejects_incomplete_chunked_and_trailing_framing() {
        let valid = single_record(NDEF_TNF_WELL_KNOWN, TYPE_TEXT, b"\x02enText");
        let mut incomplete = valid.clone();
        incomplete[0] &= !super::NDEF_FLAG_MESSAGE_END;
        let mut chunked = valid.clone();
        chunked[0] |= super::NDEF_FLAG_CHUNK;
        let mut trailing = valid;
        trailing.push(0);

        for malformed in [&incomplete, &chunked, &trailing] {
            assert_eq!(
                decode_ndef_message(malformed),
                Err(TransferError::InvalidNdef)
            );
        }
    }

    #[test]
    fn rejects_invalid_text_record_encodings() {
        let invalid_utf8 = single_record(NDEF_TNF_WELL_KNOWN, TYPE_TEXT, b"\x02en\xc3");
        let missing_utf16_bom = single_record(NDEF_TNF_WELL_KNOWN, TYPE_TEXT, b"\x82en\x00A");
        let invalid_language = single_record(NDEF_TNF_WELL_KNOWN, TYPE_TEXT, b"\x03e_nText");

        assert_eq!(
            decode_ndef_message(&invalid_utf8),
            Err(TransferError::InvalidUtf8)
        );
        assert_eq!(
            decode_ndef_message(&missing_utf16_bom),
            Err(TransferError::InvalidNdef)
        );
        assert_eq!(
            decode_ndef_message(&invalid_language),
            Err(TransferError::InvalidNdef)
        );
    }

    #[test]
    fn accepts_each_supported_markdown_mime_type() {
        for mime_type in [
            super::MIME_TEXT_MARKDOWN,
            super::MIME_TEXT_X_MARKDOWN,
            super::MIME_APPLICATION_MARKDOWN,
            super::MIME_APPLICATION_X_MARKDOWN,
        ] {
            let message = single_record(NDEF_TNF_MIME_MEDIA, mime_type.as_bytes(), b"# Text");
            let received = decode_ndef_message(&message).expect("Markdown MIME should decode");

            assert_eq!(received.source(), NfcTextSource::MarkdownMime);
            assert_eq!(received.format(), TransferFormat::Markdown);
            assert_eq!(received.text(), "# Text");
        }
    }

    #[test]
    fn rejects_messages_above_the_complete_ceiling() {
        let oversized = vec![0_u8; MAX_NFC_MESSAGE_BYTES + 1];

        assert_eq!(
            decode_ndef_message(&oversized),
            Err(TransferError::TextLimit)
        );
    }

    /// Encodes one exact single-record NDEF message.
    fn single_record(tnf: u8, record_type: &[u8], payload: &[u8]) -> Vec<u8> {
        message_from_records(&[(tnf, record_type, payload)])
    }

    /// Encodes one short single-record NDEF message with an ignored identifier.
    fn single_record_with_id(tnf: u8, record_type: &[u8], id: &[u8], payload: &[u8]) -> Vec<u8> {
        assert!(record_type.len() <= u8::MAX.into());
        assert!(id.len() <= u8::MAX.into());
        assert!(payload.len() <= u8::MAX.into());
        let mut message = vec![
            tnf | super::NDEF_FLAG_MESSAGE_BEGIN
                | super::NDEF_FLAG_MESSAGE_END
                | super::NDEF_FLAG_SHORT_RECORD
                | super::NDEF_FLAG_ID_LENGTH,
            u8::try_from(record_type.len()).expect("test type should fit"),
            u8::try_from(payload.len()).expect("test payload should fit"),
            u8::try_from(id.len()).expect("test ID should fit"),
        ];
        message.extend_from_slice(record_type);
        message.extend_from_slice(id);
        message.extend_from_slice(payload);
        message
    }

    /// Encodes one strictly framed NDEF message for parser tests.
    fn message_from_records(records: &[(u8, &[u8], &[u8])]) -> Vec<u8> {
        assert!(!records.is_empty());
        let mut message = Vec::new();
        for (index, (tnf, record_type, payload)) in records.iter().enumerate() {
            let short_record = payload.len() < 256;
            let mut flags = *tnf;
            if index == 0 {
                flags |= super::NDEF_FLAG_MESSAGE_BEGIN;
            }
            if index + 1 == records.len() {
                flags |= super::NDEF_FLAG_MESSAGE_END;
            }
            if short_record {
                flags |= super::NDEF_FLAG_SHORT_RECORD;
            }
            message.push(flags);
            message.push(u8::try_from(record_type.len()).expect("test type should fit"));
            if short_record {
                message.push(u8::try_from(payload.len()).expect("short payload should fit"));
            } else {
                message.extend_from_slice(
                    &u32::try_from(payload.len())
                        .expect("test payload should fit")
                        .to_be_bytes(),
                );
            }
            message.extend_from_slice(record_type);
            message.extend_from_slice(payload);
        }
        message
    }
}
