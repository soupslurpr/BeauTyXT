//! Exercises bounded byte-preserving UTF-8 import through the public API.

use std::io::{Error, ErrorKind, Read, Write};

use beautyxt_import_core::{
    HARD_MAX_INPUT_BYTES, HARD_MAX_OUTPUT_BYTES, ImportControl, ImportError, ImportInterruption,
    ImportLimits, ImportSummary, SHA_256_BYTE_COUNT, STREAM_BUFFER_BYTES, SourceFlags,
    UnsupportedBom, copy_raw_utf8,
};

const ONE_BYTE: usize = 1;
const SHORT_WRITE_BYTES: usize = 7;
const EMPTY_SHA256: [u8; SHA_256_BYTE_COUNT] = [
    0xe3, 0xb0, 0xc4, 0x42, 0x98, 0xfc, 0x1c, 0x14, 0x9a, 0xfb, 0xf4, 0xc8, 0x99, 0x6f, 0xb9, 0x24,
    0x27, 0xae, 0x41, 0xe4, 0x64, 0x9b, 0x93, 0x4c, 0xa4, 0x95, 0x99, 0x1b, 0x78, 0x52, 0xb8, 0x55,
];
const ABC_SHA256: [u8; SHA_256_BYTE_COUNT] = [
    0xba, 0x78, 0x16, 0xbf, 0x8f, 0x01, 0xcf, 0xea, 0x41, 0x41, 0x40, 0xde, 0x5d, 0xae, 0x22, 0x23,
    0xb0, 0x03, 0x61, 0xa3, 0x96, 0x17, 0x7a, 0x9c, 0xb4, 0x10, 0xff, 0x61, 0xf2, 0x00, 0x15, 0xad,
];

/// Reads an immutable source through deterministic bounded short reads.
struct ChunkedReader<'input> {
    input: &'input [u8],
    position: usize,
    max_chunk_bytes: usize,
    interrupt_before_read: bool,
    should_interrupt: bool,
    max_requested_bytes: usize,
}

impl<'input> ChunkedReader<'input> {
    /// Creates a deterministic reader with the requested maximum chunk size.
    fn new(input: &'input [u8], max_chunk_bytes: usize) -> Self {
        assert!(max_chunk_bytes > 0);
        Self {
            input,
            position: 0,
            max_chunk_bytes,
            interrupt_before_read: false,
            should_interrupt: false,
            max_requested_bytes: 0,
        }
    }

    /// Enables one interrupted result before every successful read.
    fn with_interruptions(mut self) -> Self {
        self.interrupt_before_read = true;
        self.should_interrupt = true;
        self
    }
}

impl Read for ChunkedReader<'_> {
    /// Reads the next deterministic source chunk.
    fn read(&mut self, buffer: &mut [u8]) -> std::io::Result<usize> {
        self.max_requested_bytes = self.max_requested_bytes.max(buffer.len());
        if self.interrupt_before_read && self.should_interrupt {
            self.should_interrupt = false;
            return Err(Error::from(ErrorKind::Interrupted));
        }
        self.should_interrupt = self.interrupt_before_read;

        let remaining_bytes = self.input.len() - self.position;
        let copied_bytes = remaining_bytes.min(buffer.len()).min(self.max_chunk_bytes);
        let input_end = self.position + copied_bytes;
        buffer[..copied_bytes].copy_from_slice(&self.input[self.position..input_end]);
        self.position = input_end;
        Ok(copied_bytes)
    }
}

/// Writes through deterministic short and interrupted writes.
#[derive(Default)]
struct ShortWriter {
    bytes: Vec<u8>,
    interrupt_before_write: bool,
    should_interrupt: bool,
    max_requested_bytes: usize,
}

impl ShortWriter {
    /// Enables one interrupted result before every successful write.
    fn with_interruptions() -> Self {
        Self {
            bytes: Vec::new(),
            interrupt_before_write: true,
            should_interrupt: true,
            max_requested_bytes: 0,
        }
    }
}

impl Write for ShortWriter {
    /// Writes the next deterministic output chunk.
    fn write(&mut self, buffer: &[u8]) -> std::io::Result<usize> {
        self.max_requested_bytes = self.max_requested_bytes.max(buffer.len());
        if self.interrupt_before_write && self.should_interrupt {
            self.should_interrupt = false;
            return Err(Error::from(ErrorKind::Interrupted));
        }
        self.should_interrupt = self.interrupt_before_write;

        let copied_bytes = buffer.len().min(SHORT_WRITE_BYTES);
        self.bytes.extend_from_slice(&buffer[..copied_bytes]);
        Ok(copied_bytes)
    }

    /// Accepts explicit flush requests without buffering independently.
    fn flush(&mut self) -> std::io::Result<()> {
        Ok(())
    }
}

/// Stops an import after a deterministic number of successful checkpoints.
struct InterruptAfter {
    remaining_checkpoints: usize,
    interruption: ImportInterruption,
}

impl ImportControl for InterruptAfter {
    /// Returns the configured interruption after its checkpoint countdown.
    fn checkpoint(&mut self) -> Result<(), ImportInterruption> {
        if self.remaining_checkpoints == 0 {
            return Err(self.interruption);
        }
        self.remaining_checkpoints -= 1;
        Ok(())
    }
}

/// Copies one source with a deterministic maximum read size.
fn copy(input: &[u8], max_chunk_bytes: usize) -> Result<(Vec<u8>, ImportSummary), ImportError> {
    let mut reader = ChunkedReader::new(input, max_chunk_bytes);
    let mut output = Vec::new();
    let limits = ImportLimits::new(HARD_MAX_INPUT_BYTES, HARD_MAX_OUTPUT_BYTES)
        .expect("hard limits must be valid");
    let mut control = || Ok::<(), ImportInterruption>(());
    let summary = copy_raw_utf8(&mut reader, &mut output, limits, &mut control)?;
    Ok((output, summary))
}

/// Verifies a BOM and mixed newline styles remain exact while setting flags.
#[test]
fn preserves_all_source_bytes_and_records_source_flags() {
    let input = b"\xef\xbb\xbfalpha\r\nbeta\ngamma\rdelta\r\r\nepsilon";

    let (output, summary) =
        copy(input, ONE_BYTE).expect("valid UTF-8 input must remain exact across one-byte reads");

    assert_eq!(output, input);
    assert_eq!(summary.input_bytes, input.len() as u64);
    assert_eq!(summary.output_bytes, input.len() as u64);
    assert!(summary.source_flags.contains(SourceFlags::UTF8_BOM));
    assert!(summary.source_flags.contains(SourceFlags::CRLF));
    assert!(summary.source_flags.contains(SourceFlags::LF));
    assert!(summary.source_flags.contains(SourceFlags::CR));
    assert_eq!(summary.source_flags.bits(), 0b1111);
}

/// Verifies SHA-256 covers every exact source byte across short reads.
#[test]
fn reports_exact_source_sha256() {
    let (_, empty_summary) = copy(&[], ONE_BYTE).expect("an empty source must hash successfully");
    let (_, abc_summary) = copy(b"abc", ONE_BYTE).expect("short reads must hash successfully");

    assert_eq!(empty_summary.sha256, EMPTY_SHA256);
    assert_eq!(abc_summary.sha256, ABC_SHA256);
}

/// Verifies UTF-8 BOM and CRLF recognition across one-byte short reads.
#[test]
fn handles_utf8_bom_and_crlf_under_one_byte_reads() {
    let input = b"\xef\xbb\xbfleft\r\nright";

    let (output, summary) = copy(input, ONE_BYTE).expect("one-byte reads must preserve state");

    assert_eq!(output, input);
    assert!(summary.source_flags.contains(SourceFlags::UTF8_BOM));
    assert!(summary.source_flags.contains(SourceFlags::CRLF));
    assert!(!summary.source_flags.contains(SourceFlags::LF));
    assert!(!summary.source_flags.contains(SourceFlags::CR));
}

/// Verifies empty and BOM-only sources retain every accepted source byte.
#[test]
fn accepts_empty_and_bom_only_sources() {
    let (empty_output, empty_summary) =
        copy(&[], ONE_BYTE).expect("an empty source must be valid UTF-8");
    let (bom_output, bom_summary) =
        copy(&[0xef, 0xbb, 0xbf], ONE_BYTE).expect("a BOM-only source must be valid UTF-8");

    assert!(empty_output.is_empty());
    assert_eq!(empty_summary.source_flags, SourceFlags::default());
    assert_eq!(bom_output, [0xef, 0xbb, 0xbf]);
    assert!(bom_summary.source_flags.contains(SourceFlags::UTF8_BOM));
    assert_eq!(bom_summary.input_bytes, 3);
    assert_eq!(bom_summary.output_bytes, 3);
}

/// Verifies every unsupported Unicode BOM is classified after one-byte reads.
#[test]
fn rejects_utf16_and_utf32_boms_under_one_byte_reads() {
    let cases: [(&[u8], UnsupportedBom); 4] = [
        (&[0xff, 0xfe], UnsupportedBom::Utf16LittleEndian),
        (&[0xfe, 0xff], UnsupportedBom::Utf16BigEndian),
        (&[0xff, 0xfe, 0x00, 0x00], UnsupportedBom::Utf32LittleEndian),
        (&[0x00, 0x00, 0xfe, 0xff], UnsupportedBom::Utf32BigEndian),
    ];

    for (input, expected_bom) in cases {
        let error = copy(input, ONE_BYTE).expect_err("unsupported BOM must be rejected");
        assert!(matches!(
            error,
            ImportError::UnsupportedBom(actual_bom) if actual_bom == expected_bom
        ));
    }
}

/// Verifies valid multi-byte UTF-8 across every deterministic read boundary.
#[test]
fn validates_utf8_across_every_read_boundary() {
    let input = "ASCII é 中 👩🏽‍💻\r\nfin".as_bytes();

    for max_chunk_bytes in 1..=input.len() {
        let (output, summary) =
            copy(input, max_chunk_bytes).expect("valid split UTF-8 must copy successfully");
        assert_eq!(output, input);
        assert_eq!(summary.output_bytes, input.len() as u64);
    }
}

/// Verifies malformed and truncated UTF-8 reports the first raw byte offset.
#[test]
fn rejects_invalid_utf8_sequences() {
    let cases: [(&[u8], u64); 7] = [
        (&[b'a', 0xc0, 0x80], 1),
        (&[b'o', b'k', 0xe2, 0x28, 0xa1], 2),
        (&[0xed, 0xa0, 0x80], 0),
        (&[0xf4, 0x90, 0x80, 0x80], 0),
        (&[b'x', 0xf0, 0x9f], 1),
        (&[0x80], 0),
        (&[0xef, 0xbb, 0xbf, b'x', 0x80], 4),
    ];

    for (input, expected_offset) in cases {
        let error = copy(input, ONE_BYTE).expect_err("invalid UTF-8 must be rejected");
        assert!(matches!(
            error,
            ImportError::InvalidUtf8 { byte_offset } if byte_offset == expected_offset
        ));
    }
}

/// Verifies input limits accept the exact boundary and reject its next byte.
#[test]
fn enforces_exact_input_limit() {
    let exact_limits = ImportLimits::new(3, 3).expect("small limits must be valid");
    let mut exact_reader = ChunkedReader::new(b"abc", STREAM_BUFFER_BYTES);
    let mut exact_output = Vec::new();
    let mut exact_control = || Ok::<(), ImportInterruption>(());
    let summary = copy_raw_utf8(
        &mut exact_reader,
        &mut exact_output,
        exact_limits,
        &mut exact_control,
    )
    .expect("the exact input limit must succeed");
    assert_eq!(summary.input_bytes, 3);
    assert_eq!(exact_output, b"abc");

    let mut oversized_reader = ChunkedReader::new(b"abcd", STREAM_BUFFER_BYTES);
    let mut oversized_output = Vec::new();
    let mut oversized_control = || Ok::<(), ImportInterruption>(());
    let error = copy_raw_utf8(
        &mut oversized_reader,
        &mut oversized_output,
        exact_limits,
        &mut oversized_control,
    )
    .expect_err("one byte above the input limit must fail");
    assert!(matches!(
        error,
        ImportError::InputTooLarge {
            limit: 3,
            observed: 4
        }
    ));
}

/// Verifies copied output is checked against its independent byte limit.
#[test]
fn enforces_output_limit() {
    let limits = ImportLimits::new(3, 2).expect("small limits must be valid");
    let mut reader = ChunkedReader::new(b"abc", STREAM_BUFFER_BYTES);
    let mut output = Vec::new();
    let mut control = || Ok::<(), ImportInterruption>(());

    let error = copy_raw_utf8(&mut reader, &mut output, limits, &mut control)
        .expect_err("output above the declared limit must fail");

    assert!(matches!(
        error,
        ImportError::OutputTooLarge {
            limit: 2,
            observed: 3
        }
    ));
}

/// Verifies callers cannot raise either service-wide hard maximum.
#[test]
fn rejects_limits_above_hard_maxima() {
    let input_error = ImportLimits::new(HARD_MAX_INPUT_BYTES + 1, HARD_MAX_OUTPUT_BYTES)
        .expect_err("an excessive input limit must fail");
    let output_error = ImportLimits::new(HARD_MAX_INPUT_BYTES, HARD_MAX_OUTPUT_BYTES + 1)
        .expect_err("an excessive output limit must fail");

    assert!(matches!(input_error, ImportError::InputLimitTooHigh { .. }));
    assert!(matches!(
        output_error,
        ImportError::OutputLimitTooHigh { .. }
    ));
}

/// Verifies deterministic cancellation stops bounded streaming work.
#[test]
fn observes_cancellation_checkpoint() {
    let input = vec![b'x'; STREAM_BUFFER_BYTES * 2];
    let limits = ImportLimits::new(input.len() as u64, input.len() as u64)
        .expect("test limits must be valid");
    let mut reader = ChunkedReader::new(&input, STREAM_BUFFER_BYTES);
    let mut output = Vec::new();
    let mut control = InterruptAfter {
        remaining_checkpoints: 5,
        interruption: ImportInterruption::Cancelled,
    };

    let error = copy_raw_utf8(&mut reader, &mut output, limits, &mut control)
        .expect_err("the configured cancellation must stop importing");

    assert!(matches!(error, ImportError::Cancelled));
    assert!(output.len() < input.len());
}

/// Verifies an elapsed abstract deadline maps to its distinct result.
#[test]
fn observes_deadline_checkpoint() {
    let limits = ImportLimits::new(1, 1).expect("small limits must be valid");
    let mut reader = ChunkedReader::new(b"x", ONE_BYTE);
    let mut output = Vec::new();
    let mut control = InterruptAfter {
        remaining_checkpoints: 0,
        interruption: ImportInterruption::DeadlineExceeded,
    };

    let error = copy_raw_utf8(&mut reader, &mut output, limits, &mut control)
        .expect_err("the configured deadline must stop importing");

    assert!(matches!(error, ImportError::DeadlineExceeded));
    assert!(output.is_empty());
}

/// Verifies interrupted short reads and writes resume without data loss.
#[test]
fn retries_interrupted_short_io() {
    let input = b"start\r\nmiddle\rend";
    let limits = ImportLimits::new(input.len() as u64, input.len() as u64)
        .expect("test limits must be valid");
    let mut reader = ChunkedReader::new(input, ONE_BYTE).with_interruptions();
    let mut writer = ShortWriter::with_interruptions();
    let mut control = || Ok::<(), ImportInterruption>(());

    let summary = copy_raw_utf8(&mut reader, &mut writer, limits, &mut control)
        .expect("interrupted I/O must resume successfully");

    assert_eq!(writer.bytes, input);
    assert_eq!(summary.output_bytes, writer.bytes.len() as u64);
}

/// Verifies both streaming sides remain within the fixed buffer bound.
#[test]
fn keeps_streaming_buffers_bounded() {
    let input = vec![b'z'; STREAM_BUFFER_BYTES * 2 + 17];
    let limits = ImportLimits::new(input.len() as u64, input.len() as u64)
        .expect("test limits must be valid");
    let mut reader = ChunkedReader::new(&input, usize::MAX);
    let mut writer = ShortWriter::default();
    let mut control = || Ok::<(), ImportInterruption>(());

    let summary = copy_raw_utf8(&mut reader, &mut writer, limits, &mut control)
        .expect("bounded large input must copy successfully");

    assert_eq!(writer.bytes, input);
    assert_eq!(summary.output_bytes, writer.bytes.len() as u64);
    assert!(reader.max_requested_bytes <= STREAM_BUFFER_BYTES);
    assert!(writer.max_requested_bytes <= STREAM_BUFFER_BYTES);
}
