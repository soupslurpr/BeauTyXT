//! Exercises the release-scale streaming import acceptance envelope.

use std::io::{Read, Write};

use beautyxt_import_core::{
    ImportInterruption, ImportLimits, SHA_256_BYTE_COUNT, STREAM_BUFFER_BYTES, SourceFlags,
    copy_raw_utf8,
};
use sha2::{Digest, Sha256};

const ACCEPTANCE_MIN_INPUT_BYTES: u64 = 100 * 1024 * 1024;
const UTF8_BOM: &[u8] = &[0xef, 0xbb, 0xbf];
const SOURCE_PATTERN: &[u8] = "👩🏽‍💻 first\r\nβeta\rgamma\n終わり\r\n".as_bytes();
const SOURCE_PATTERN_REPETITIONS: u64 =
    (ACCEPTANCE_MIN_INPUT_BYTES - UTF8_BOM.len() as u64).div_ceil(SOURCE_PATTERN.len() as u64);
const EXPECTED_INPUT_BYTES: u64 =
    UTF8_BOM.len() as u64 + SOURCE_PATTERN_REPETITIONS * SOURCE_PATTERN.len() as u64;
const EXPECTED_OUTPUT_BYTES: u64 = EXPECTED_INPUT_BYTES;
const READ_CHUNK_SCHEDULE: &[usize] = &[1, 2, 3, 5, 8, 13, 257, 4093];
const MAX_SCHEDULED_READ_BYTES: usize = 4093;
const LINE_FEEDS_PER_PATTERN: u64 = 3;
const CARRIAGE_RETURNS_PER_PATTERN: u64 = 3;
const FNV1A_OFFSET_BASIS: u64 = 14_695_981_039_346_656_037;
const FNV1A_PRIME: u64 = 1_099_511_628_211;
const EXPECTED_OUTPUT_FNV1A: u64 = 3_830_205_933_983_041_828;
const _: () = assert!(EXPECTED_INPUT_BYTES >= ACCEPTANCE_MIN_INPUT_BYTES);

/// Generates a deterministic virtual source without retaining its corpus.
struct VirtualSource {
    position: u64,
    chunk_schedule_position: usize,
    max_requested_bytes: usize,
    max_returned_bytes: usize,
    split_utf8_sequence: bool,
    split_crlf: bool,
}

impl VirtualSource {
    /// Creates a virtual source positioned before its UTF-8 BOM.
    const fn new() -> Self {
        Self {
            position: 0,
            chunk_schedule_position: 0,
            max_requested_bytes: 0,
            max_returned_bytes: 0,
            split_utf8_sequence: false,
            split_crlf: false,
        }
    }

    /// Returns the source byte at a valid virtual offset.
    fn byte_at(position: u64) -> u8 {
        if position < UTF8_BOM.len() as u64 {
            let bom_position =
                usize::try_from(position).expect("BOM position must fit the platform size");
            return UTF8_BOM[bom_position];
        }
        let pattern_position =
            usize::try_from((position - UTF8_BOM.len() as u64) % SOURCE_PATTERN.len() as u64)
                .expect("pattern position must fit the platform size");
        SOURCE_PATTERN[pattern_position]
    }

    /// Copies one virtual range into the caller's bounded buffer.
    fn copy_range(&self, buffer: &mut [u8]) {
        let mut copied_bytes = 0;
        while copied_bytes < buffer.len() {
            let source_position = self.position + copied_bytes as u64;
            if source_position < UTF8_BOM.len() as u64 {
                let bom_position = usize::try_from(source_position)
                    .expect("BOM position must fit the platform size");
                let available_bom_bytes = UTF8_BOM.len() - bom_position;
                let copy_bytes = available_bom_bytes.min(buffer.len() - copied_bytes);
                let output_end = copied_bytes + copy_bytes;
                buffer[copied_bytes..output_end]
                    .copy_from_slice(&UTF8_BOM[bom_position..bom_position + copy_bytes]);
                copied_bytes = output_end;
                continue;
            }

            let pattern_position = usize::try_from(
                (source_position - UTF8_BOM.len() as u64) % SOURCE_PATTERN.len() as u64,
            )
            .expect("pattern position must fit the platform size");
            let available_pattern_bytes = SOURCE_PATTERN.len() - pattern_position;
            let copy_bytes = available_pattern_bytes.min(buffer.len() - copied_bytes);
            let output_end = copied_bytes + copy_bytes;
            buffer[copied_bytes..output_end]
                .copy_from_slice(&SOURCE_PATTERN[pattern_position..pattern_position + copy_bytes]);
            copied_bytes = output_end;
        }
    }

    /// Records whether a read boundary divides structured source bytes.
    fn record_boundary(&mut self, previous_position: u64) {
        if self.position >= EXPECTED_INPUT_BYTES || self.position == 0 {
            return;
        }
        let previous_byte = Self::byte_at(self.position - 1);
        let next_byte = Self::byte_at(self.position);
        self.split_utf8_sequence |= next_byte & 0b1100_0000 == 0b1000_0000;
        self.split_crlf |= previous_byte == b'\r' && next_byte == b'\n';
        debug_assert!(self.position > previous_position);
    }
}

impl Read for VirtualSource {
    /// Generates the next deterministically short source chunk.
    fn read(&mut self, buffer: &mut [u8]) -> std::io::Result<usize> {
        self.max_requested_bytes = self.max_requested_bytes.max(buffer.len());
        if self.position == EXPECTED_INPUT_BYTES {
            return Ok(0);
        }

        let scheduled_bytes = READ_CHUNK_SCHEDULE[self.chunk_schedule_position];
        self.chunk_schedule_position =
            (self.chunk_schedule_position + 1) % READ_CHUNK_SCHEDULE.len();
        let remaining_bytes = usize::try_from(EXPECTED_INPUT_BYTES - self.position)
            .expect("acceptance source size must fit the platform size");
        let returned_bytes = buffer.len().min(scheduled_bytes).min(remaining_bytes);
        let previous_position = self.position;
        self.copy_range(&mut buffer[..returned_bytes]);
        self.position += returned_bytes as u64;
        self.max_returned_bytes = self.max_returned_bytes.max(returned_bytes);
        self.record_boundary(previous_position);
        Ok(returned_bytes)
    }
}

/// Digests preserved output without retaining document bytes.
struct DigestWriter {
    fnv1a: u64,
    sha256: Sha256,
    output_bytes: u64,
    line_feeds: u64,
    carriage_returns: u64,
    max_requested_bytes: usize,
}

impl DigestWriter {
    /// Creates an empty deterministic output digest.
    fn new() -> Self {
        Self {
            fnv1a: FNV1A_OFFSET_BASIS,
            sha256: Sha256::new(),
            output_bytes: 0,
            line_feeds: 0,
            carriage_returns: 0,
            max_requested_bytes: 0,
        }
    }
}

impl Write for DigestWriter {
    /// Incorporates one bounded preserved output slice.
    fn write(&mut self, buffer: &[u8]) -> std::io::Result<usize> {
        self.max_requested_bytes = self.max_requested_bytes.max(buffer.len());
        for byte in buffer {
            self.fnv1a ^= u64::from(*byte);
            self.fnv1a = self.fnv1a.wrapping_mul(FNV1A_PRIME);
            self.line_feeds += u64::from(*byte == b'\n');
            self.carriage_returns += u64::from(*byte == b'\r');
        }
        self.sha256.update(buffer);
        self.output_bytes += buffer.len() as u64;
        Ok(buffer.len())
    }

    /// Accepts the final flush without allocating another buffer.
    fn flush(&mut self) -> std::io::Result<()> {
        Ok(())
    }
}

/// Verifies a 100 MiB virtual source stays bounded and remains byte-exact.
#[test]
#[ignore = "runs the 100 MiB streaming import acceptance test"]
fn streams_large_import_with_exact_digest() {
    let mut source = VirtualSource::new();
    let mut destination = DigestWriter::new();
    let limits = ImportLimits::new(EXPECTED_INPUT_BYTES, EXPECTED_OUTPUT_BYTES)
        .expect("acceptance limits must remain inside the hard envelope");
    let mut control = || Ok::<(), ImportInterruption>(());

    let summary = copy_raw_utf8(&mut source, &mut destination, limits, &mut control)
        .expect("the deterministic large source must copy successfully");

    let line_feeds = LINE_FEEDS_PER_PATTERN * SOURCE_PATTERN_REPETITIONS;
    let carriage_returns = CARRIAGE_RETURNS_PER_PATTERN * SOURCE_PATTERN_REPETITIONS;
    assert_eq!(summary.input_bytes, EXPECTED_INPUT_BYTES);
    assert_eq!(summary.output_bytes, EXPECTED_OUTPUT_BYTES);
    assert_eq!(summary.output_bytes, destination.output_bytes);
    assert!(summary.source_flags.contains(SourceFlags::UTF8_BOM));
    assert!(summary.source_flags.contains(SourceFlags::CRLF));
    assert!(summary.source_flags.contains(SourceFlags::LF));
    assert!(summary.source_flags.contains(SourceFlags::CR));
    assert_eq!(summary.source_flags.bits(), 0b1111);
    assert_eq!(destination.fnv1a, EXPECTED_OUTPUT_FNV1A);
    assert_eq!(destination.line_feeds, line_feeds);
    assert_eq!(destination.carriage_returns, carriage_returns);
    assert!(source.split_utf8_sequence);
    assert!(source.split_crlf);
    assert!(source.max_requested_bytes <= STREAM_BUFFER_BYTES);
    assert!(destination.max_requested_bytes <= STREAM_BUFFER_BYTES);
    assert!(source.max_returned_bytes <= MAX_SCHEDULED_READ_BYTES);
    let output_sha256: [u8; SHA_256_BYTE_COUNT] = destination.sha256.finalize().into();
    assert_eq!(summary.sha256, output_sha256);
}
