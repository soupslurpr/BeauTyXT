//! Verifies the large-document acceptance envelope without checked-in corpora.

use std::fs::{File, OpenOptions, remove_file};
use std::io::{ErrorKind, Seek, SeekFrom, Write, copy};
use std::ops::Range;
use std::path::PathBuf;

use beautyxt_editor_core::{
    Document, DocumentMetrics, EditWindowRequest, FindDirection, FindRequest,
    MAX_EDIT_WINDOW_UTF16_UNITS, MAX_VIEWPORT_BLOCKS, PreviousViewportRequest, Utf16Range,
    ViewportPosition, ViewportRequest,
};
use beautyxt_source_save_core::ValidatedPackageReader;

const MEBIBYTE: usize = 1024 * 1024;
const ACCEPTANCE_DOCUMENT_BYTES: usize = 100 * MEBIBYTE;
const ACCEPTANCE_LINE: &[u8] = b"00000000  alpha beta gamma  0123456789abcdef  markdown **text**\n";
const SOURCE_WRITE_BUFFER_BYTES: usize = 64 * 1024;
const MAX_TEMPORARY_FILE_ATTEMPTS: u32 = 128;
const TEMPORARY_FILE_PREFIX: &str = "beautyxt-editor-large-document";
const FNV_OFFSET_BASIS: u64 = 0xcbf2_9ce4_8422_2325;
const FNV_PRIME: u64 = 0x0000_0100_0000_01b3;
const START_INSERTION: &[u8] = b"START\n";
const MIDDLE_REPLACEMENT: &[u8] = b"MID";
const END_INSERTION: &[u8] = b"\nEND";
const MIDDLE_REMOVAL_BYTES: usize = MIDDLE_REPLACEMENT.len();
const PROBE_BYTES: usize = 64;
const FIND_ACCEPTANCE_CANDIDATE_UTF16_UNITS: usize = 16 * 1024;
const FIND_ACCEPTANCE_QUERY: &str = "MARKDOWN **TEXT**";
const FIND_ABSENT_QUERY: &str = "😀";

/// Owns one temporary source pathname until it is explicitly unlinked.
struct TemporarySourceFile {
    file: Option<File>,
    path: PathBuf,
    is_linked: bool,
}

impl TemporarySourceFile {
    /// Creates and fills one uniquely named temporary source file.
    fn create(bytes: usize) -> std::io::Result<Self> {
        let process_id = std::process::id();
        let mut last_collision = None;
        for attempt in 0..MAX_TEMPORARY_FILE_ATTEMPTS {
            let path = std::env::temp_dir().join(format!(
                "{TEMPORARY_FILE_PREFIX}-{process_id}-{attempt}.txt"
            ));
            match OpenOptions::new()
                .read(true)
                .write(true)
                .create_new(true)
                .open(&path)
            {
                Ok(file) => {
                    let mut source = Self {
                        file: Some(file),
                        path,
                        is_linked: true,
                    };
                    source.write_corpus(bytes)?;
                    return Ok(source);
                }
                Err(error) if error.kind() == ErrorKind::AlreadyExists => {
                    last_collision = Some(error);
                }
                Err(error) => return Err(error),
            }
        }

        Err(last_collision.unwrap_or_else(|| {
            std::io::Error::new(
                ErrorKind::AlreadyExists,
                "temporary source name space is exhausted",
            )
        }))
    }

    /// Transfers the generated source descriptor to the document engine.
    fn take_file(&mut self) -> File {
        self.file
            .take()
            .expect("temporary source descriptor should be available")
    }

    /// Removes the source pathname while its transferred descriptor remains open.
    fn unlink(&mut self) -> std::io::Result<()> {
        if !self.is_linked {
            return Ok(());
        }
        remove_file(&self.path)?;
        self.is_linked = false;
        Ok(())
    }

    /// Streams the deterministic corpus through one fixed-size buffer.
    fn write_corpus(&mut self, bytes: usize) -> std::io::Result<()> {
        let file = self
            .file
            .as_mut()
            .expect("temporary source descriptor should be available");
        let repeated_bytes = bytes - bytes % ACCEPTANCE_LINE.len();
        let mut buffer = vec![0_u8; SOURCE_WRITE_BUFFER_BYTES].into_boxed_slice();
        let mut written_bytes = 0;

        while written_bytes < repeated_bytes {
            let chunk_bytes = (repeated_bytes - written_bytes).min(buffer.len());
            for (buffer_offset, byte) in buffer[..chunk_bytes].iter_mut().enumerate() {
                *byte = ACCEPTANCE_LINE[(written_bytes + buffer_offset) % ACCEPTANCE_LINE.len()];
            }
            file.write_all(&buffer[..chunk_bytes])?;
            written_bytes += chunk_bytes;
        }

        let trailing_bytes = bytes - written_bytes;
        buffer[..trailing_bytes].fill(b'x');
        file.write_all(&buffer[..trailing_bytes])?;
        file.flush()?;
        file.seek(SeekFrom::Start(0))?;
        Ok(())
    }
}

impl Drop for TemporarySourceFile {
    fn drop(&mut self) {
        if self.is_linked {
            let _ = remove_file(&self.path);
        }
    }
}

/// Counts and hashes streamed output while retaining bounded edge probes.
struct ProbeWriter {
    bytes: usize,
    hash: u64,
    prefix: Vec<u8>,
    suffix: [u8; PROBE_BYTES],
    suffix_length: usize,
    suffix_cursor: usize,
}

impl ProbeWriter {
    /// Creates an empty bounded probe writer.
    fn new() -> Self {
        Self {
            bytes: 0,
            hash: FNV_OFFSET_BASIS,
            prefix: Vec::with_capacity(PROBE_BYTES),
            suffix: [0; PROBE_BYTES],
            suffix_length: 0,
            suffix_cursor: 0,
        }
    }

    /// Returns the retained suffix in stream order.
    fn ordered_suffix(&self) -> Vec<u8> {
        if self.suffix_length < PROBE_BYTES {
            return self.suffix[..self.suffix_length].to_vec();
        }

        self.suffix[self.suffix_cursor..]
            .iter()
            .chain(&self.suffix[..self.suffix_cursor])
            .copied()
            .collect()
    }
}

impl Write for ProbeWriter {
    fn write(&mut self, buffer: &[u8]) -> std::io::Result<usize> {
        let missing_prefix_bytes = PROBE_BYTES.saturating_sub(self.prefix.len());
        self.prefix
            .extend_from_slice(&buffer[..buffer.len().min(missing_prefix_bytes)]);

        for byte in buffer {
            self.hash ^= u64::from(*byte);
            self.hash = self.hash.wrapping_mul(FNV_PRIME);
            self.suffix[self.suffix_cursor] = *byte;
            self.suffix_cursor = (self.suffix_cursor + 1) % PROBE_BYTES;
            self.suffix_length = (self.suffix_length + 1).min(PROBE_BYTES);
        }
        self.bytes += buffer.len();
        Ok(buffer.len())
    }

    fn flush(&mut self) -> std::io::Result<()> {
        Ok(())
    }
}

/// Incorporates one byte slice into an FNV-1a digest.
fn hash_bytes(hash: &mut u64, bytes: &[u8]) {
    for byte in bytes {
        *hash ^= u64::from(*byte);
        *hash = hash.wrapping_mul(FNV_PRIME);
    }
}

/// Incorporates one deterministic source range into an FNV-1a digest.
fn hash_source_range(hash: &mut u64, range: Range<usize>) {
    let repeated_bytes =
        ACCEPTANCE_DOCUMENT_BYTES - ACCEPTANCE_DOCUMENT_BYTES % ACCEPTANCE_LINE.len();
    for source_offset in range {
        let byte = if source_offset < repeated_bytes {
            ACCEPTANCE_LINE[source_offset % ACCEPTANCE_LINE.len()]
        } else {
            b'x'
        };
        *hash ^= u64::from(byte);
        *hash = hash.wrapping_mul(FNV_PRIME);
    }
}

/// Returns the exact digest after applying the acceptance edits.
fn expected_output_hash(middle: usize) -> u64 {
    let mut hash = FNV_OFFSET_BASIS;
    hash_bytes(&mut hash, START_INSERTION);
    hash_source_range(&mut hash, 0..middle);
    hash_bytes(&mut hash, MIDDLE_REPLACEMENT);
    hash_source_range(
        &mut hash,
        middle + MIDDLE_REMOVAL_BYTES..ACCEPTANCE_DOCUMENT_BYTES,
    );
    hash_bytes(&mut hash, END_INSERTION);
    hash
}

/// Verifies case-insensitive present and absent middle queries remain bounded.
fn verify_bounded_find(document: &Document, metrics: DocumentMetrics, middle: usize) {
    let find_batch = document
        .find(FindRequest {
            revision: metrics.revision,
            query: FIND_ACCEPTANCE_QUERY,
            match_case: false,
            candidate_range: Utf16Range::new(middle, metrics.utf16_units),
            direction: FindDirection::Forward,
            max_candidate_utf16_units: FIND_ACCEPTANCE_CANDIDATE_UTF16_UNITS,
        })
        .expect("middle literal find should remain bounded");
    let matched = find_batch
        .matched
        .expect("repeated acceptance query should be found");
    assert!(matched.range.start >= middle);
    assert!(matched.range.start - middle < FIND_ACCEPTANCE_CANDIDATE_UTF16_UNITS);
    assert_eq!(
        matched.range.len(),
        FIND_ACCEPTANCE_QUERY.encode_utf16().count()
    );

    let missing_batch = document
        .find(FindRequest {
            revision: metrics.revision,
            query: FIND_ABSENT_QUERY,
            match_case: false,
            candidate_range: Utf16Range::new(middle, metrics.utf16_units),
            direction: FindDirection::Forward,
            max_candidate_utf16_units: FIND_ACCEPTANCE_CANDIDATE_UTF16_UNITS,
        })
        .expect("middle missing find should remain bounded");
    let remaining_candidates = missing_batch
        .remaining_candidate_range
        .expect("bounded missing find should retain later candidates");
    assert_eq!(missing_batch.matched, None);
    assert!(remaining_candidates.start > middle);
    assert!(remaining_candidates.start - middle <= FIND_ACCEPTANCE_CANDIDATE_UTF16_UNITS);
    assert_eq!(remaining_candidates.end, metrics.utf16_units);
}

/// Reconstructs one sparse package and verifies its bounded large output.
fn verify_sparse_source_save(document: &Document, middle: usize) {
    let prepared_source_save = document
        .snapshot()
        .prepare_source_save()
        .expect("large sparse source save should prepare");
    let package_metrics = prepared_source_save.package_metrics();
    let expected_payload_bytes =
        START_INSERTION.len() + MIDDLE_REPLACEMENT.len() + END_INSERTION.len();
    assert_eq!(
        package_metrics.output_bytes,
        u64::try_from(document.metrics().serialized_bytes)
            .expect("serialized document length should fit u64")
    );
    assert_eq!(
        package_metrics.payload_bytes,
        u64::try_from(expected_payload_bytes).expect("test payload length should fit u64")
    );
    assert_eq!(
        package_metrics.source_bytes,
        Some(
            u64::try_from(ACCEPTANCE_DOCUMENT_BYTES)
                .expect("acceptance source length should fit u64")
        )
    );
    assert_eq!(package_metrics.record_count, 5);
    assert!(package_metrics.package_bytes < 1024);

    let mut package = Vec::new();
    prepared_source_save
        .write_package(&mut package)
        .expect("large sparse package should stream");
    assert_eq!(
        package.len(),
        usize::try_from(package_metrics.package_bytes)
            .expect("sparse package length should fit usize")
    );
    let source = prepared_source_save
        .into_source()
        .expect("large sparse package should retain its source");
    let mut reader = ValidatedPackageReader::new(&package, Some(&source))
        .expect("large sparse package should validate");
    let mut output = ProbeWriter::new();
    copy(&mut reader, &mut output).expect("large sparse package should reconstruct");

    assert_eq!(output.bytes, document.metrics().serialized_bytes);
    assert_eq!(
        output.bytes,
        ACCEPTANCE_DOCUMENT_BYTES + START_INSERTION.len() + END_INSERTION.len()
    );
    assert_eq!(output.hash, expected_output_hash(middle));
    assert!(output.prefix.starts_with(START_INSERTION));
    assert!(output.ordered_suffix().ends_with(END_INSERTION));
}

/// Verifies a 100 MiB document remains bounded at the platform boundary.
#[test]
#[ignore = "runs the 100 MiB large-document acceptance test"]
fn edits_and_streams_large_document() {
    let mut source = TemporarySourceFile::create(ACCEPTANCE_DOCUMENT_BYTES)
        .expect("temporary ASCII source should be writable");
    let open_result = Document::open_source(source.take_file());
    source
        .unlink()
        .expect("temporary source pathname should be removable after opening");
    let mut document = open_result.expect("ASCII source should be readable");
    let initial_metrics = document.metrics();
    let middle = ACCEPTANCE_DOCUMENT_BYTES / 2;

    assert_eq!(initial_metrics.bytes, ACCEPTANCE_DOCUMENT_BYTES);
    assert_eq!(initial_metrics.serialized_bytes, ACCEPTANCE_DOCUMENT_BYTES);
    assert_eq!(initial_metrics.utf16_units, ACCEPTANCE_DOCUMENT_BYTES);
    assert!(initial_metrics.lines > 1_000_000);

    let middle_line = initial_metrics.lines / 2;
    let snapshot = document
        .viewport(ViewportRequest {
            start: ViewportPosition {
                revision: initial_metrics.revision,
                line: middle_line,
                utf16_offset: 0,
            },
            max_blocks: MAX_VIEWPORT_BLOCKS,
            max_block_utf16_units: 256,
            max_total_utf16_units: 16 * 1024,
        })
        .expect("middle viewport should remain bounded");
    let snapshot_utf16_units = snapshot
        .blocks
        .iter()
        .map(|block| block.text.encode_utf16().count())
        .sum::<usize>();

    assert!(snapshot.blocks.len() <= MAX_VIEWPORT_BLOCKS);
    assert!(snapshot_utf16_units <= 16 * 1024);

    let previous_anchor = snapshot
        .previous
        .expect("middle viewport should expose a previous anchor");
    let previous_snapshot = document
        .previous_viewport(PreviousViewportRequest {
            end: previous_anchor,
            max_blocks: MAX_VIEWPORT_BLOCKS,
            max_block_utf16_units: 256,
            max_total_utf16_units: 16 * 1024,
        })
        .expect("previous middle viewport should remain bounded");
    let previous_snapshot_utf16_units = previous_snapshot
        .blocks
        .iter()
        .map(|block| block.text.encode_utf16().count())
        .sum::<usize>();

    assert!(previous_snapshot.blocks.len() <= MAX_VIEWPORT_BLOCKS);
    assert!(previous_snapshot_utf16_units <= 16 * 1024);
    assert_eq!(previous_snapshot.next, Some(previous_anchor));

    let edit_window = document
        .edit_window(EditWindowRequest {
            revision: initial_metrics.revision,
            selection: Utf16Range::new(middle, middle),
            max_utf16_units: MAX_EDIT_WINDOW_UTF16_UNITS,
        })
        .expect("middle edit window should remain bounded");

    assert!(edit_window.range.start <= middle);
    assert!(edit_window.range.end >= middle);
    assert!(edit_window.text.encode_utf16().count() <= MAX_EDIT_WINDOW_UTF16_UNITS);
    assert!(edit_window.has_previous);
    assert!(edit_window.has_next);
    verify_bounded_find(&document, initial_metrics, middle);

    document
        .replace(
            0,
            Utf16Range::new(ACCEPTANCE_DOCUMENT_BYTES, ACCEPTANCE_DOCUMENT_BYTES),
            std::str::from_utf8(END_INSERTION).expect("test insertion should be UTF-8"),
        )
        .expect("end insertion should succeed");
    document
        .replace(
            1,
            Utf16Range::new(middle, middle + MIDDLE_REMOVAL_BYTES),
            std::str::from_utf8(MIDDLE_REPLACEMENT).expect("test replacement should be UTF-8"),
        )
        .expect("middle replacement should succeed");
    document
        .replace(
            2,
            Utf16Range::new(0, 0),
            std::str::from_utf8(START_INSERTION).expect("test insertion should be UTF-8"),
        )
        .expect("start insertion should succeed");

    assert_eq!(document.metrics().revision, 3);
    verify_sparse_source_save(&document, middle);
}
