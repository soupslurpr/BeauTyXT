//! Defines bounded, integrity-checked local text transfers and QR/NDEF processing.

#![forbid(unsafe_code)]

mod luminance;
mod ndef;

use std::error::Error;
use std::fmt::{Display, Formatter};

use qrcode::{Color, EcLevel, QrCode};
use rxing::{
    BarcodeFormat, BinaryBitmap, DecodeHints, Luma8LuminanceSource, common::HybridBinarizer,
    multi::MultipleBarcodeReader, qrcode::cpp_port::QrReader,
};
use sha2::{Digest, Sha256};

pub use ndef::{
    MAX_NFC_DECODED_TEXT_BYTES, MAX_NFC_ENVELOPE_BYTES, MAX_NFC_MESSAGE_BYTES,
    MAX_NFC_RESULT_PACKET_BYTES, NFC_RESULT_PACKET_MAGIC, NFC_TRANSFER_MIME_TYPE, NfcTextSource,
    ReceivedNfcText, decode_ndef_message,
};

/// Limits one QR transfer to a deliberately small UTF-8 payload.
pub const MAX_QR_TEXT_BYTES: usize = 1_536;
/// Limits one optional human-readable NFC tag label.
pub const MAX_NFC_TAG_LABEL_BYTES: usize = 3;
/// Contains the fixed byte overhead of one transfer envelope without a tag label.
pub const ENVELOPE_BASE_OVERHEAD_BYTES: usize = 44;
/// Limits custom NFC text while reserving complete NDEF framing and a maximum label.
pub const MAX_NFC_TEXT_BYTES: usize =
    MAX_NFC_ENVELOPE_BYTES - ENVELOPE_BASE_OVERHEAD_BYTES - MAX_NFC_TAG_LABEL_BYTES;
/// Limits one grayscale scan frame to 1280 by 960 pixels.
pub const MAX_QR_FRAME_PIXELS: usize = 1_280 * 960;
/// Limits one QR symbol to the largest standard square dimension.
pub const MAX_QR_MODULES_PER_SIDE: usize = 177;
/// Contains the binary prefix of one transfer envelope.
pub const ENVELOPE_MAGIC: [u8; 4] = *b"BTXT";
/// Contains the binary prefix of one packed QR module packet.
pub const QR_PACKET_MAGIC: [u8; 4] = *b"BXQR";

const ENVELOPE_VERSION: u8 = 2;
const QR_PACKET_VERSION: u8 = 1;
const ENVELOPE_DIGEST_BYTES: usize = 32;
const ENVELOPE_HEADER_BYTES: usize = 12;
const QR_PACKET_HEADER_BYTES: usize = 11;
const MIN_QR_FRAME_SIDE: usize = 48;

/// Selects the source representation carried by one local transfer.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum TransferFormat {
    /// Carries ordinary plain text.
    PlainText,
    /// Carries Markdown source text.
    Markdown,
}

impl TryFrom<u8> for TransferFormat {
    type Error = TransferError;

    fn try_from(value: u8) -> Result<Self, Self::Error> {
        match value {
            0 => Ok(Self::PlainText),
            1 => Ok(Self::Markdown),
            _ => Err(TransferError::UnsupportedEnvelope),
        }
    }
}

impl From<TransferFormat> for u8 {
    fn from(format: TransferFormat) -> Self {
        match format {
            TransferFormat::PlainText => 0,
            TransferFormat::Markdown => 1,
        }
    }
}

/// Owns one validated text value received from a local transfer.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ReceivedText {
    format: TransferFormat,
    tag_label: Option<String>,
    text: String,
}

impl ReceivedText {
    /// Returns the received source format.
    #[must_use]
    pub const fn format(&self) -> TransferFormat {
        self.format
    }

    /// Returns the optional human-readable NFC tag label.
    #[must_use]
    pub fn tag_label(&self) -> Option<&str> {
        self.tag_label.as_deref()
    }

    /// Returns the exact received UTF-8 text.
    #[must_use]
    pub fn text(&self) -> &str {
        &self.text
    }
}

/// Owns one bounded row-major QR module grid.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct QrModuleGrid {
    dimension: u16,
    packed_modules: Vec<u8>,
}

impl QrModuleGrid {
    /// Returns the number of modules on each side.
    #[must_use]
    pub const fn dimension(&self) -> u16 {
        self.dimension
    }

    /// Returns whether one row-major module is dark.
    ///
    /// # Panics
    ///
    /// Panics when either coordinate is outside the grid.
    #[must_use]
    pub fn is_dark(&self, row: usize, column: usize) -> bool {
        let dimension = usize::from(self.dimension);
        assert!(row < dimension, "QR module row must be in bounds");
        assert!(column < dimension, "QR module column must be in bounds");
        let module_index = row * dimension + column;
        self.packed_modules[module_index / 8] & (1 << (7 - module_index % 8)) != 0
    }

    /// Encodes the grid into one bounded packet for the Compose client.
    ///
    /// # Panics
    ///
    /// Panics only if an internally validated packed grid exceeds `u32`.
    #[must_use]
    pub fn to_packet(&self) -> Vec<u8> {
        let packed_length =
            u32::try_from(self.packed_modules.len()).expect("QR packet length should fit u32");
        let mut packet = Vec::with_capacity(QR_PACKET_HEADER_BYTES + self.packed_modules.len());
        packet.extend_from_slice(&QR_PACKET_MAGIC);
        packet.push(QR_PACKET_VERSION);
        packet.extend_from_slice(&self.dimension.to_be_bytes());
        packet.extend_from_slice(&packed_length.to_be_bytes());
        packet.extend_from_slice(&self.packed_modules);
        packet
    }
}

/// Describes one rejected local transfer without retaining its input.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum TransferError {
    /// Indicates that the text exceeds the selected transport limit.
    TextLimit,
    /// Indicates that input text is not valid UTF-8.
    InvalidUtf8,
    /// Indicates that an NFC tag label is not canonical ASCII text.
    InvalidTagLabel,
    /// Indicates that the envelope is malformed, damaged, or from another protocol.
    UnsupportedEnvelope,
    /// Indicates that an NDEF message or supported record is malformed.
    InvalidNdef,
    /// Indicates that an NDEF message does not contain one supported text record.
    UnsupportedNdef,
    /// Indicates that an NDEF message contains ambiguous supported content.
    AmbiguousNdef,
    /// Indicates that QR encoding could not represent the bounded envelope.
    QrEncoding,
    /// Indicates that the grayscale frame shape or length is invalid.
    InvalidFrame,
    /// Indicates that no QR symbol was detected in the frame.
    QrNotFound,
    /// Indicates that more than one distinct `BeauTyXT` transfer was detected.
    AmbiguousQr,
}

impl Display for TransferError {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        formatter.write_str(match self {
            Self::TextLimit => "transfer text exceeds its limit",
            Self::InvalidUtf8 => "transfer text is not valid UTF-8",
            Self::InvalidTagLabel => "NFC tag label is invalid",
            Self::UnsupportedEnvelope => "transfer envelope is unsupported",
            Self::InvalidNdef => "NDEF message is invalid",
            Self::UnsupportedNdef => "NDEF message is unsupported",
            Self::AmbiguousNdef => "NDEF message is ambiguous",
            Self::QrEncoding => "transfer QR encoding failed",
            Self::InvalidFrame => "QR frame is invalid",
            Self::QrNotFound => "QR code was not found",
            Self::AmbiguousQr => "multiple BeauTyXT QR codes were found",
        })
    }
}

impl Error for TransferError {}

/// Encodes exact UTF-8 text into an integrity-checked binary envelope.
///
/// # Errors
///
/// Returns [`TransferError::TextLimit`] for oversized text or an invalid limit,
/// and [`TransferError::InvalidUtf8`] for malformed UTF-8.
pub fn encode_envelope(
    text: &[u8],
    format: TransferFormat,
    tag_label: Option<&str>,
    max_text_bytes: usize,
) -> Result<Vec<u8>, TransferError> {
    if text.len() > max_text_bytes || max_text_bytes > MAX_NFC_DECODED_TEXT_BYTES {
        return Err(TransferError::TextLimit);
    }
    std::str::from_utf8(text).map_err(|_| TransferError::InvalidUtf8)?;
    let tag_label = tag_label.unwrap_or("").as_bytes();
    if !is_valid_tag_label(tag_label) {
        return Err(TransferError::InvalidTagLabel);
    }
    let tag_label_length =
        u8::try_from(tag_label.len()).map_err(|_| TransferError::InvalidTagLabel)?;
    let text_length = u32::try_from(text.len()).map_err(|_| TransferError::TextLimit)?;
    let mut envelope =
        Vec::with_capacity(ENVELOPE_BASE_OVERHEAD_BYTES + tag_label.len() + text.len());
    envelope.extend_from_slice(&ENVELOPE_MAGIC);
    envelope.push(ENVELOPE_VERSION);
    envelope.push(format.into());
    envelope.push(tag_label_length);
    envelope.push(0);
    envelope.extend_from_slice(&text_length.to_be_bytes());
    let mut hasher = Sha256::new();
    hasher.update(&envelope);
    hasher.update(tag_label);
    hasher.update(text);
    envelope.extend_from_slice(&hasher.finalize());
    envelope.extend_from_slice(tag_label);
    envelope.extend_from_slice(text);
    Ok(envelope)
}

/// Decodes one exact integrity-checked transfer envelope.
///
/// # Errors
///
/// Returns a [`TransferError`] when the envelope, limit, digest, format, or
/// contained UTF-8 text is invalid.
pub fn decode_envelope(
    envelope: &[u8],
    max_text_bytes: usize,
) -> Result<ReceivedText, TransferError> {
    if max_text_bytes > MAX_NFC_DECODED_TEXT_BYTES
        || envelope.len() < ENVELOPE_HEADER_BYTES + ENVELOPE_DIGEST_BYTES
    {
        return Err(TransferError::UnsupportedEnvelope);
    }
    if envelope[..ENVELOPE_MAGIC.len()] != ENVELOPE_MAGIC || envelope[4] != ENVELOPE_VERSION {
        return Err(TransferError::UnsupportedEnvelope);
    }
    let format = TransferFormat::try_from(envelope[5])?;
    let tag_label_length = usize::from(envelope[6]);
    if envelope[7] != 0 || tag_label_length > MAX_NFC_TAG_LABEL_BYTES {
        return Err(TransferError::UnsupportedEnvelope);
    }
    let text_length = usize::try_from(u32::from_be_bytes([
        envelope[8],
        envelope[9],
        envelope[10],
        envelope[11],
    ]))
    .map_err(|_| TransferError::TextLimit)?;
    if text_length > max_text_bytes {
        return Err(TransferError::TextLimit);
    }
    let expected_length = ENVELOPE_HEADER_BYTES
        .checked_add(ENVELOPE_DIGEST_BYTES)
        .and_then(|length| length.checked_add(tag_label_length))
        .and_then(|length| length.checked_add(text_length))
        .ok_or(TransferError::UnsupportedEnvelope)?;
    if envelope.len() != expected_length {
        return Err(TransferError::UnsupportedEnvelope);
    }
    let digest_start = ENVELOPE_HEADER_BYTES;
    let tag_label_start = digest_start + ENVELOPE_DIGEST_BYTES;
    let text_start = tag_label_start + tag_label_length;
    let tag_label = &envelope[tag_label_start..text_start];
    if !is_valid_tag_label(tag_label) {
        return Err(TransferError::UnsupportedEnvelope);
    }
    let text = &envelope[text_start..];
    let actual_digest = &envelope[digest_start..tag_label_start];
    let mut hasher = Sha256::new();
    hasher.update(&envelope[..ENVELOPE_HEADER_BYTES]);
    hasher.update(tag_label);
    hasher.update(text);
    let expected_digest = hasher.finalize();
    let expected_digest: &[u8] = expected_digest.as_ref();
    if actual_digest != expected_digest {
        return Err(TransferError::UnsupportedEnvelope);
    }
    let text = std::str::from_utf8(text)
        .map_err(|_| TransferError::InvalidUtf8)?
        .to_owned();
    let tag_label = if tag_label.is_empty() {
        None
    } else {
        Some(
            std::str::from_utf8(tag_label)
                .map_err(|_| TransferError::UnsupportedEnvelope)?
                .to_owned(),
        )
    };
    Ok(ReceivedText {
        format,
        tag_label,
        text,
    })
}

/// Returns whether one optional label is canonical uppercase ASCII.
fn is_valid_tag_label(tag_label: &[u8]) -> bool {
    tag_label.len() <= MAX_NFC_TAG_LABEL_BYTES
        && tag_label
            .iter()
            .all(|byte| byte.is_ascii_uppercase() || byte.is_ascii_digit())
}

/// Encodes one bounded text value as a medium-error-correction QR module grid.
///
/// # Errors
///
/// Returns a [`TransferError`] when the text is invalid or cannot fit in the
/// bounded QR representation.
pub fn encode_qr_text(text: &[u8], format: TransferFormat) -> Result<QrModuleGrid, TransferError> {
    let envelope = encode_envelope(text, format, None, MAX_QR_TEXT_BYTES)?;
    let code = QrCode::with_error_correction_level(&envelope, EcLevel::M)
        .map_err(|_| TransferError::QrEncoding)?;
    let dimension = code.width();
    if dimension > MAX_QR_MODULES_PER_SIDE {
        return Err(TransferError::QrEncoding);
    }
    let module_count = dimension
        .checked_mul(dimension)
        .ok_or(TransferError::QrEncoding)?;
    let mut packed_modules = vec![0_u8; module_count.div_ceil(8)];
    for (module_index, color) in code.into_colors().into_iter().enumerate() {
        if color == Color::Dark {
            packed_modules[module_index / 8] |= 1 << (7 - module_index % 8);
        }
    }
    let dimension = u16::try_from(dimension).map_err(|_| TransferError::QrEncoding)?;
    Ok(QrModuleGrid {
        dimension,
        packed_modules,
    })
}

/// Detects one distinct `BeauTyXT` transfer in an exact grayscale camera frame.
///
/// # Errors
///
/// Returns a [`TransferError`] when the frame is invalid, no supported code is
/// found, or multiple distinct transfers are visible.
pub fn decode_qr_frame(
    width: usize,
    height: usize,
    luminance: &[u8],
) -> Result<ReceivedText, TransferError> {
    let pixel_count = width
        .checked_mul(height)
        .ok_or(TransferError::InvalidFrame)?;
    if width < MIN_QR_FRAME_SIDE
        || height < MIN_QR_FRAME_SIDE
        || pixel_count > MAX_QR_FRAME_PIXELS
        || luminance.len() != pixel_count
    {
        return Err(TransferError::InvalidFrame);
    }

    let mut scanner = quircs::Quirc::default();
    let scan = scan_qr_polarities(&mut scanner, width, height, luminance)?;

    scan.received.ok_or(if scan.saw_qr {
        TransferError::UnsupportedEnvelope
    } else {
        TransferError::QrNotFound
    })
}

/// Observes both QR polarities while reusing the detector's bounded storage.
fn scan_qr_polarities(
    scanner: &mut quircs::Quirc,
    width: usize,
    height: usize,
    luminance: &[u8],
) -> Result<QrPolarityScan, TransferError> {
    let mut standard = scan_qr_luminance(scanner, width, height, luminance)?;
    let mut inverted_luminance = Vec::with_capacity(luminance.len());
    inverted_luminance.extend(luminance.iter().map(|value| u8::MAX - value));
    let inverted = scan_qr_luminance(scanner, width, height, &inverted_luminance);
    inverted_luminance.fill(0);
    standard.merge(inverted?)?;
    Ok(standard)
}

/// Contains supported transfer observations from one QR polarity scan.
struct QrPolarityScan {
    saw_qr: bool,
    received: Option<ReceivedText>,
}

impl QrPolarityScan {
    /// Validates raw bytes without a charset conversion or a first-code shortcut.
    fn observe(&mut self, payload: &[u8]) -> Result<(), TransferError> {
        self.saw_qr = true;
        let Ok(current) = decode_envelope(payload, MAX_QR_TEXT_BYTES) else {
            return Ok(());
        };
        if current.tag_label().is_some() {
            return Ok(());
        }
        self.merge(Self {
            saw_qr: true,
            received: Some(current),
        })
    }

    /// Combines observations without silently selecting between distinct texts.
    fn merge(&mut self, other: Self) -> Result<(), TransferError> {
        self.saw_qr |= other.saw_qr;
        if let Some(current) = other.received {
            match &self.received {
                None => self.received = Some(current),
                Some(previous) if previous == &current => {}
                Some(_) => return Err(TransferError::AmbiguousQr),
            }
        }
        Ok(())
    }
}

/// Scans one luminance polarity for a single distinct supported transfer.
fn scan_qr_luminance(
    scanner: &mut quircs::Quirc,
    width: usize,
    height: usize,
    luminance: &[u8],
) -> Result<QrPolarityScan, TransferError> {
    let mut scan = QrPolarityScan {
        saw_qr: false,
        received: None,
    };
    scan_quirc(scanner, width, height, luminance, &mut scan)?;
    // A faint second code must still participate in ambiguity detection, even
    // when the whole-frame threshold found a high-contrast code elsewhere.
    let mut local = luminance::local_threshold(width, height, luminance);
    let local_scan = scan_quirc(scanner, width, height, &local, &mut scan);
    local.fill(0);
    local_scan?;

    // The geometric detector handles dense, rotated modules and local lighting
    // better; quirc complements it at steep perspective angles. Both inspect
    // the full frame, and all observations participate in ambiguity detection.
    let source = Luma8LuminanceSource::new(
        luminance.to_vec(),
        u32::try_from(width).map_err(|_| TransferError::InvalidFrame)?,
        u32::try_from(height).map_err(|_| TransferError::InvalidFrame)?,
    )
    .map_err(|_| TransferError::InvalidFrame)?;
    let mut bitmap = BinaryBitmap::new(HybridBinarizer::new(source));
    let hints = DecodeHints {
        TryHarder: Some(true),
        PossibleFormats: Some([BarcodeFormat::QR_CODE].into()),
        ..DecodeHints::default()
    };
    if let Ok(codes) = QrReader.decode_multiple_with_hints(&mut bitmap, &hints) {
        for code in codes {
            // QrReader exposes payload bytes here; the legacy QRCodeMultiReader
            // exposes QR codewords instead and is not interchangeable.
            scan.observe(code.getRawBytes())?;
        }
    }
    Ok(scan)
}

/// Records fully decoded symbols, leaving partial finder patterns as no result.
fn scan_quirc(
    scanner: &mut quircs::Quirc,
    width: usize,
    height: usize,
    luminance: &[u8],
    scan: &mut QrPolarityScan,
) -> Result<(), TransferError> {
    for candidate in scanner.identify(width, height, luminance) {
        let Ok(code) = candidate else { continue };
        let Ok(decoded) = code.decode() else { continue };
        scan.observe(&decoded.payload)?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    //! Verifies bounded envelopes, packed QR modules, and image decoding.

    use super::{
        ENVELOPE_BASE_OVERHEAD_BYTES, MAX_NFC_TEXT_BYTES, MAX_QR_TEXT_BYTES, TransferError,
        TransferFormat, decode_envelope, decode_qr_frame, encode_envelope, encode_qr_text,
    };

    const QUIET_ZONE_MODULES: usize = 4;
    const MODULE_SCALE: usize = 5;

    #[test]
    fn envelope_round_trip_preserves_exact_markdown() {
        let text = "# Heading\n\nPrivate 😀 text\n";
        let envelope = encode_envelope(
            text.as_bytes(),
            TransferFormat::Markdown,
            Some("A01"),
            MAX_NFC_TEXT_BYTES,
        )
        .expect("bounded Markdown should encode");

        let received =
            decode_envelope(&envelope, MAX_NFC_TEXT_BYTES).expect("valid envelope should decode");

        assert_eq!(received.format(), TransferFormat::Markdown);
        assert_eq!(received.tag_label(), Some("A01"));
        assert_eq!(received.text(), text);
    }

    #[test]
    fn envelope_rejects_modified_text() {
        let mut envelope = encode_envelope(
            b"private text",
            TransferFormat::PlainText,
            None,
            MAX_NFC_TEXT_BYTES,
        )
        .expect("bounded text should encode");
        *envelope.last_mut().expect("envelope should contain text") ^= 1;

        assert_eq!(
            decode_envelope(&envelope, MAX_NFC_TEXT_BYTES),
            Err(TransferError::UnsupportedEnvelope)
        );
    }

    #[test]
    fn envelope_rejects_modified_tag_label() {
        let mut envelope = encode_envelope(
            b"private text",
            TransferFormat::PlainText,
            Some("A01"),
            MAX_NFC_TEXT_BYTES,
        )
        .expect("bounded NFC text should encode");
        envelope[ENVELOPE_BASE_OVERHEAD_BYTES] = b'B';

        assert_eq!(
            decode_envelope(&envelope, MAX_NFC_TEXT_BYTES),
            Err(TransferError::UnsupportedEnvelope)
        );
    }

    #[test]
    fn envelope_rejects_noncanonical_tag_labels() {
        assert_eq!(
            encode_envelope(
                b"private text",
                TransferFormat::PlainText,
                Some("toolong"),
                MAX_NFC_TEXT_BYTES,
            ),
            Err(TransferError::InvalidTagLabel)
        );
        assert_eq!(
            encode_envelope(
                b"private text",
                TransferFormat::PlainText,
                Some("a1"),
                MAX_NFC_TEXT_BYTES,
            ),
            Err(TransferError::InvalidTagLabel)
        );
    }

    #[test]
    fn qr_round_trip_decodes_a_standard_luminance_frame() {
        let text = "A bounded BeauTyXT QR transfer";
        let grid = encode_qr_text(text.as_bytes(), TransferFormat::PlainText)
            .expect("bounded text should encode as QR");
        let (image_side, luminance) = render_qr_luminance(&grid, false);

        let received =
            decode_qr_frame(image_side, image_side, &luminance).expect("rendered QR should decode");

        assert_eq!(received.format(), TransferFormat::PlainText);
        assert_eq!(received.text(), text);
    }

    #[test]
    fn qr_round_trip_decodes_an_inverted_luminance_frame() {
        let text = "# Expressive QR\n\nDynamic color remains readable.\n";
        let grid = encode_qr_text(text.as_bytes(), TransferFormat::Markdown)
            .expect("bounded Markdown should encode as QR");
        let (image_side, luminance) = render_qr_luminance(&grid, true);

        let received =
            decode_qr_frame(image_side, image_side, &luminance).expect("inverted QR should decode");

        assert_eq!(received.format(), TransferFormat::Markdown);
        assert_eq!(received.text(), text);
    }

    #[test]
    fn qr_round_trip_decodes_the_inverted_text_limit() {
        let text = vec![b'x'; MAX_QR_TEXT_BYTES];
        let grid = encode_qr_text(&text, TransferFormat::PlainText)
            .expect("the documented QR text limit should encode");
        let (image_side, luminance) = render_qr_luminance(&grid, true);

        let received = decode_qr_frame(image_side, image_side, &luminance)
            .expect("the maximum inverted QR should decode");

        assert_eq!(received.format(), TransferFormat::PlainText);
        assert_eq!(received.text().as_bytes(), text);
    }

    #[test]
    fn qr_encoding_rejects_text_above_its_limit() {
        let text = vec![b'x'; MAX_QR_TEXT_BYTES + 1];

        assert_eq!(
            encode_qr_text(&text, TransferFormat::PlainText),
            Err(TransferError::TextLimit)
        );
    }

    #[test]
    fn qr_decodes_a_small_off_center_code_under_a_lighting_gradient() {
        let text = b"# Camera scan\n\nSmall, shaded and away from the guide.\n";
        let grid = encode_qr_text(text, TransferFormat::Markdown).unwrap();
        let mut scene = vec![245_u8; 1280 * 960];
        place_qr_in_scene(&mut scene, &grid, 860, 520, false, (65, 190));
        for (index, pixel) in scene.iter_mut().enumerate() {
            let brightness = 30 + 70 * (index % 1280) / 1279;
            *pixel = u8::try_from(usize::from(*pixel) * brightness / 100).unwrap();
        }

        let received = decode_qr_frame(1280, 960, &scene).unwrap();

        assert_eq!(received.text().as_bytes(), text);
        assert_eq!(received.format(), TransferFormat::Markdown);

        for pixel in &mut scene {
            *pixel = 255 - *pixel;
        }
        assert_eq!(
            decode_qr_frame(1280, 960, &scene)
                .unwrap()
                .text()
                .as_bytes(),
            text
        );
    }

    #[test]
    fn qr_decodes_an_off_center_rotated_inverted_code() {
        let text = b"A rotated code away from the guide";
        let grid = encode_qr_text(text, TransferFormat::PlainText).unwrap();
        let mut scene = vec![30_u8; 1280 * 960];
        place_qr_in_scene(&mut scene, &grid, 60, 90, true, (220, 40));

        assert_eq!(
            decode_qr_frame(1280, 960, &scene)
                .unwrap()
                .text()
                .as_bytes(),
            text
        );
    }

    #[test]
    fn qr_rejects_distinct_transfers_with_different_local_contrast() {
        let first = encode_qr_text(b"First document", TransferFormat::PlainText).unwrap();
        let second = encode_qr_text(b"Second document", TransferFormat::Markdown).unwrap();
        let mut scene = vec![255_u8; 1280 * 960];
        place_qr_in_scene(&mut scene, &first, 80, 120, false, (0, 255));
        place_qr_in_scene(&mut scene, &second, 850, 550, false, (105, 155));

        assert_eq!(
            decode_qr_frame(1280, 960, &scene),
            Err(TransferError::AmbiguousQr)
        );
    }

    #[test]
    fn qr_accepts_repeated_copies_of_the_same_transfer() {
        let text = b"The same document";
        let grid = encode_qr_text(text, TransferFormat::Markdown).unwrap();
        let mut scene = vec![240_u8; 1280 * 960];
        place_qr_in_scene(&mut scene, &grid, 70, 110, false, (20, 230));
        place_qr_in_scene(&mut scene, &grid, 850, 550, true, (90, 170));

        assert_eq!(
            decode_qr_frame(1280, 960, &scene)
                .unwrap()
                .text()
                .as_bytes(),
            text
        );
    }

    #[test]
    fn qr_keeps_scanning_when_finders_are_visible_but_data_is_unreadable() {
        let grid =
            encode_qr_text(b"A damaged camera observation", TransferFormat::PlainText).unwrap();
        let (side, mut image) = render_qr_luminance(&grid, false);
        let dimension = usize::from(grid.dimension());
        for row in 9..dimension {
            for column in 9..dimension {
                let top = (row + QUIET_ZONE_MODULES) * MODULE_SCALE;
                let left = (column + QUIET_ZONE_MODULES) * MODULE_SCALE;
                for y in top..top + MODULE_SCALE {
                    image[y * side + left..y * side + left + MODULE_SCALE].fill(255);
                }
            }
        }

        assert_eq!(
            decode_qr_frame(side, side, &image),
            Err(TransferError::QrNotFound)
        );
    }

    #[test]
    fn qr_reports_a_readable_unrelated_code_as_unsupported() {
        let code = qrcode::QrCode::new(b"https://example.com/unrelated").unwrap();
        let side = (code.width() + QUIET_ZONE_MODULES * 2) * MODULE_SCALE;
        let mut image = vec![255; side * side];
        for row in 0..code.width() {
            for column in 0..code.width() {
                if code[(column, row)] == qrcode::Color::Dark {
                    for y in 0..MODULE_SCALE {
                        let offset = ((row + QUIET_ZONE_MODULES) * MODULE_SCALE + y) * side
                            + (column + QUIET_ZONE_MODULES) * MODULE_SCALE;
                        image[offset..offset + MODULE_SCALE].fill(0);
                    }
                }
            }
        }
        assert_eq!(
            decode_qr_frame(side, side, &image),
            Err(TransferError::UnsupportedEnvelope)
        );
    }

    #[test]
    fn qr_rejects_empty_and_out_of_bounds_frames() {
        assert_eq!(
            decode_qr_frame(640, 480, &vec![127; 640 * 480]),
            Err(TransferError::QrNotFound)
        );
        assert_eq!(
            decode_qr_frame(1280, 961, &[]),
            Err(TransferError::InvalidFrame)
        );
        assert_eq!(
            decode_qr_frame(usize::MAX, 960, &[]),
            Err(TransferError::InvalidFrame)
        );
        assert_eq!(
            decode_qr_frame(47, 48, &vec![0; 47 * 48]),
            Err(TransferError::InvalidFrame)
        );
    }

    /// Places a complete four-pixel-per-module code in a larger camera scene.
    fn place_qr_in_scene(
        scene: &mut [u8],
        grid: &super::QrModuleGrid,
        left: usize,
        top: usize,
        rotated: bool,
        (dark, light): (u8, u8),
    ) {
        let dimension = usize::from(grid.dimension());
        let side = (dimension + 8) * 4;
        for y in 0..side {
            for x in 0..side {
                let (row, column) = if rotated {
                    (x / 4, (side - 1 - y) / 4)
                } else {
                    (y / 4, x / 4)
                };
                let is_dark = row >= 4
                    && column >= 4
                    && row < dimension + 4
                    && column < dimension + 4
                    && grid.is_dark(row - 4, column - 4);
                scene[(top + y) * 1280 + left + x] = if is_dark { dark } else { light };
            }
        }
    }

    /// Renders one exact module grid in the selected luminance polarity.
    fn render_qr_luminance(grid: &super::QrModuleGrid, inverted: bool) -> (usize, Vec<u8>) {
        let dimension = usize::from(grid.dimension());
        let framed_modules = dimension + QUIET_ZONE_MODULES * 2;
        let image_side = framed_modules * MODULE_SCALE;
        let background = if inverted { 0 } else { u8::MAX };
        let foreground = u8::MAX - background;
        let mut luminance = vec![background; image_side * image_side];
        for row in 0..dimension {
            for column in 0..dimension {
                if !grid.is_dark(row, column) {
                    continue;
                }
                let image_row = (row + QUIET_ZONE_MODULES) * MODULE_SCALE;
                let image_column = (column + QUIET_ZONE_MODULES) * MODULE_SCALE;
                for offset_row in 0..MODULE_SCALE {
                    let start = (image_row + offset_row) * image_side + image_column;
                    luminance[start..start + MODULE_SCALE].fill(foreground);
                }
            }
        }
        (image_side, luminance)
    }
}
