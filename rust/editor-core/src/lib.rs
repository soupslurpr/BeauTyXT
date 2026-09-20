//! Provides `BeauTyXT`'s large-document editing model.

#![forbid(unsafe_code)]

mod piece_tree;

use std::error::Error;
use std::fmt::{Display, Formatter};
use std::fs::File;
use std::io::{ErrorKind, Write};
use std::ops::Range;

use beautyxt_source_save_core::{
    MAX_OUTPUT_BYTES, MAX_RECORD_COUNT, PackageEncoder, PackageHeader,
};
use memchr::memmem;
use piece_tree::{
    MAX_PIECE_BYTES, PieceTree, PieceTreeReader, ReplaceOutcome, SerializedPayload,
    SerializedSegmentVisitor, SourceClone,
};
use regex::RegexBuilder;
use unicode_segmentation::UnicodeSegmentation;

/// Limits an inline edit passed across the platform boundary.
pub const MAX_INLINE_REPLACEMENT_BYTES: usize = 1024 * 1024;

/// Limits the text exposed to one Android input method session.
pub const MAX_EDIT_WINDOW_UTF16_UNITS: usize = 32 * 1024;

/// Limits a single block returned to the Compose layer.
pub const MAX_RENDER_BLOCK_UTF16_UNITS: usize = 32 * 1024;

/// Limits the number of blocks returned in one viewport snapshot.
pub const MAX_VIEWPORT_BLOCKS: usize = 256;

/// Limits the combined text returned in one viewport snapshot.
pub const MAX_VIEWPORT_UTF16_UNITS: usize = 256 * 1024;

/// Limits the UTF-16 length of one literal find query.
pub const MAX_FIND_QUERY_UTF16_UNITS: usize = 4 * 1024;

/// Limits the UTF-8 byte length of one literal find query.
pub const MAX_FIND_QUERY_BYTES: usize = 16 * 1024;

/// Limits one case-folded source match to twice the query's UTF-16 bound.
pub const MAX_FIND_MATCH_UTF16_UNITS: usize = MAX_FIND_QUERY_UTF16_UNITS * 2;

/// Limits the candidate-start span inspected by one find request.
pub const MAX_FIND_CANDIDATE_UTF16_UNITS: usize = 256 * 1024;

/// Limits editable documents to Android's signed 32-bit text offset space.
pub const MAX_EDITABLE_UTF16_UNITS: usize = 2_147_483_647;

/// Identifies the serialized form used for newly inserted logical newlines.
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub enum DocumentLineEnding {
    /// Serializes one logical newline as LF.
    #[default]
    Lf,

    /// Serializes one logical newline as CRLF.
    CrLf,

    /// Serializes one logical newline as CR.
    Cr,
}

const MIN_RENDER_BLOCK_UTF16_UNITS: usize = 2;
const MIN_FIND_CANDIDATE_UTF16_UNITS: usize = 2;
const FIND_REGEX_SIZE_LIMIT_BYTES: usize = 4 * 1024 * 1024;
const CASE_INSENSITIVE_SCALAR_UTF16_UNITS: usize = 2;
const MAX_UTF8_SCALAR_BYTES: usize = 4;
const INITIAL_REVISION: u64 = 0;
const GRAPHEME_LOOKAHEAD_UTF16_UNITS: usize = 64;
const EDIT_WINDOW_GRAPHEME_CONTEXT_UTF16_UNITS: usize = 64;
const EDIT_WINDOW_GRAPHEME_PROBE_BEFORE_UTF16_UNITS: usize = 128;

/// Identifies a half-open range in global UTF-16 code units.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct Utf16Range {
    /// Stores the inclusive start offset.
    pub start: usize,

    /// Stores the exclusive end offset.
    pub end: usize,
}

impl Utf16Range {
    /// Creates a half-open UTF-16 range.
    #[must_use]
    pub const fn new(start: usize, end: usize) -> Self {
        Self { start, end }
    }

    /// Returns the number of UTF-16 code units in the range.
    #[must_use]
    pub const fn len(self) -> usize {
        self.end.saturating_sub(self.start)
    }

    /// Returns whether the range contains no code units.
    #[must_use]
    pub const fn is_empty(self) -> bool {
        self.start == self.end
    }
}

/// Describes the current size and revision of a document.
#[allow(
    clippy::struct_excessive_bools,
    reason = "the booleans report independent source-format and editability facts"
)]
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct DocumentMetrics {
    /// Stores the monotonically increasing document revision.
    pub revision: u64,

    /// Stores the logical normalized UTF-8 byte length.
    pub bytes: usize,

    /// Stores the exact byte length produced by serialization.
    pub serialized_bytes: usize,

    /// Stores the Unicode scalar count.
    pub chars: usize,

    /// Stores the UTF-16 code-unit length used by Android text APIs.
    pub utf16_units: usize,

    /// Stores the logical line count.
    pub lines: usize,

    /// Stores runs of non-whitespace Unicode scalars.
    pub words: usize,

    /// Indicates whether serialization begins with a UTF-8 byte-order mark.
    pub has_utf8_bom: bool,

    /// Indicates whether serialization contains an LF line ending.
    pub has_lf_line_endings: bool,

    /// Indicates whether serialization contains a CRLF line ending.
    pub has_crlf_line_endings: bool,

    /// Indicates whether serialization contains a CR line ending.
    pub has_cr_line_endings: bool,

    /// Stores the serialized form used for newly inserted logical newlines.
    pub inserted_line_ending: DocumentLineEnding,

    /// Indicates whether Android can represent every editable offset.
    pub is_editable: bool,
}

/// Identifies a resumable position within a logical line.
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct ViewportPosition {
    /// Stores the document revision that owns this position.
    pub revision: u64,

    /// Stores the zero-based logical line.
    pub line: usize,

    /// Stores the UTF-16 offset from the start of the logical line.
    pub utf16_offset: usize,
}

/// Configures a bounded viewport request.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct ViewportRequest {
    /// Stores the first position to include.
    pub start: ViewportPosition,

    /// Stores the maximum number of returned render blocks.
    pub max_blocks: usize,

    /// Stores the maximum UTF-16 length of one render block.
    pub max_block_utf16_units: usize,

    /// Stores the maximum combined UTF-16 length of returned text.
    pub max_total_utf16_units: usize,
}

impl Default for ViewportRequest {
    fn default() -> Self {
        Self {
            start: ViewportPosition::default(),
            max_blocks: 128,
            max_block_utf16_units: 8 * 1024,
            max_total_utf16_units: 128 * 1024,
        }
    }
}

/// Configures bounded viewport content ending before one position.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct PreviousViewportRequest {
    /// Stores the exclusive position following the returned blocks.
    pub end: ViewportPosition,

    /// Stores the maximum number of returned render blocks.
    pub max_blocks: usize,

    /// Stores the maximum UTF-16 length of one render block.
    pub max_block_utf16_units: usize,

    /// Stores the maximum combined UTF-16 length of returned text.
    pub max_total_utf16_units: usize,
}

/// Contains one bounded piece of a logical line for Compose layout.
#[derive(Clone, Eq, PartialEq)]
pub struct RenderBlock {
    /// Stores the zero-based logical line.
    pub logical_line: usize,

    /// Stores the block's global UTF-16 start offset.
    pub global_utf16_start: usize,

    /// Stores the block's exclusive global UTF-16 end offset.
    pub global_utf16_end: usize,

    /// Stores the line terminator length following this block.
    pub line_terminator_utf16_units: u8,

    /// Stores the block text without a line terminator.
    pub text: String,

    /// Indicates that text precedes this block on the same logical line.
    pub continues_at_start: bool,

    /// Indicates that text follows this block on the same logical line.
    pub continues_at_end: bool,
}

/// Contains an immutable, revision-tagged viewport response.
#[derive(Clone, Eq, PartialEq)]
pub struct ViewportSnapshot {
    /// Stores the metrics for the captured revision.
    pub metrics: DocumentMetrics,

    /// Stores the bounded render blocks.
    pub blocks: Vec<RenderBlock>,

    /// Stores the exclusive end anchor for the immediately preceding page.
    pub previous: Option<ViewportPosition>,

    /// Stores the forward anchor immediately following the returned blocks.
    pub next: Option<ViewportPosition>,
}

/// Configures one bounded, revision-tagged input method window.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct EditWindowRequest {
    /// Stores the document revision required by the caller.
    pub revision: u64,

    /// Stores the global UTF-16 selection that the window must contain.
    pub selection: Utf16Range,

    /// Stores the maximum UTF-16 length returned to the input method.
    pub max_utf16_units: usize,
}

/// Contains one bounded input method view of a document revision.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct EditWindowSnapshot {
    /// Stores the metrics for the captured revision.
    pub metrics: DocumentMetrics,

    /// Stores the global UTF-16 range represented by `text`.
    pub range: Utf16Range,

    /// Stores the global UTF-16 selection contained by the window.
    pub selection: Utf16Range,

    /// Stores the line-relative position corresponding to `range.start`.
    pub start: ViewportPosition,

    /// Stores the bounded document text exposed to the input method.
    pub text: String,

    /// Indicates that document text precedes the window.
    pub has_previous: bool,

    /// Indicates that document text follows the window.
    pub has_next: bool,
}

/// Chooses the traversal order for one bounded find request.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum FindDirection {
    /// Searches candidate starts in ascending document order.
    Forward,

    /// Searches candidate starts in descending document order.
    Backward,
}

/// Configures one bounded literal-text search over candidate start offsets.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct FindRequest<'query> {
    /// Stores the document revision required by the caller.
    pub revision: u64,

    /// Stores the nonempty normalized text to find.
    pub query: &'query str,

    /// Indicates that Unicode case differences must prevent a match.
    pub match_case: bool,

    /// Stores the half-open range containing allowed match starts.
    pub candidate_range: Utf16Range,

    /// Stores the order in which candidate starts are inspected.
    pub direction: FindDirection,

    /// Stores the maximum candidate-start span inspected by one batch.
    pub max_candidate_utf16_units: usize,
}

/// Identifies one literal revision-bound match in the logical document.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct FindMatch {
    /// Stores the global UTF-16 range containing the matched query.
    pub range: Utf16Range,

    /// Stores the line-relative position corresponding to the match start.
    pub start: ViewportPosition,
}

/// Contains one bounded literal-text search result and remaining candidate work.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct FindBatch {
    /// Stores the metrics for the searched revision.
    pub metrics: DocumentMetrics,

    /// Contains the first match in the requested traversal order.
    pub matched: Option<FindMatch>,

    /// Contains the unsearched candidate starts after a bounded miss.
    pub remaining_candidate_range: Option<Utf16Range>,
}

/// Describes one exact prepared source-save package.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct SourceSavePackageMetrics {
    /// Stores the complete encoded package byte length.
    pub package_bytes: u64,

    /// Stores the exact reconstructed document byte length.
    pub output_bytes: u64,

    /// Stores the serialized bytes embedded directly in the package.
    pub payload_bytes: u64,

    /// Stores the referenced immutable source length when one is needed.
    pub source_bytes: Option<u64>,

    /// Stores the number of sequential package records.
    pub record_count: u64,
}

/// Owns one immutable revision prepared for bounded source saving.
pub struct PreparedSourceSave {
    tree: PieceTree,
    document_metrics: DocumentMetrics,
    package_metrics: SourceSavePackageMetrics,
    source: Option<File>,
    mode: SourceSaveMode,
}

/// Selects sparse source extents or one complete inline payload.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum SourceSaveMode {
    Sparse,
    FullPayload,
}

/// Reports invalid document operations without exposing platform details.
#[derive(Debug)]
pub enum DocumentError {
    /// Reports an underlying streaming input or output failure.
    Io(std::io::Error),

    /// Reports an edit based on a superseded document revision.
    StaleRevision {
        /// Stores the revision required by the caller.
        expected: u64,

        /// Stores the current document revision.
        actual: u64,
    },

    /// Reports a viewport position from a superseded document revision.
    StaleViewport {
        /// Stores the revision carried by the viewport position.
        expected: u64,

        /// Stores the current document revision.
        actual: u64,
    },

    /// Reports a range that is reversed or outside the document.
    InvalidRange {
        /// Stores the rejected range.
        range: Utf16Range,

        /// Stores the current document length.
        document_utf16_units: usize,
    },

    /// Reports an offset that divides a Unicode scalar's surrogate pair.
    MisalignedUtf16Offset {
        /// Stores the rejected UTF-16 offset.
        offset: usize,
    },

    /// Reports an inline replacement that exceeds its boundary limit.
    ReplacementTooLarge {
        /// Stores the replacement length in bytes.
        bytes: usize,

        /// Stores the enforced byte limit.
        max_bytes: usize,
    },

    /// Reports replacement text that violates the normalized document model.
    InvalidReplacement(&'static str),

    /// Reports a document that cannot fit Android's editable offset space.
    DocumentTooLargeForEditing {
        /// Stores the resulting document length.
        utf16_units: usize,

        /// Stores the enforced editable length limit.
        max_utf16_units: usize,
    },

    /// Reports a size-increasing edit that would exceed the source-save ceiling.
    DocumentTooLargeForSaving {
        /// Stores the exact serialized result, including source formatting.
        bytes: usize,

        /// Stores the enforced serialized byte limit.
        max_bytes: u64,
    },

    /// Reports an invalid viewport request.
    InvalidViewportRequest(&'static str),

    /// Reports an invalid input method window request.
    InvalidEditWindowRequest(&'static str),

    /// Reports an invalid bounded literal-text find request.
    InvalidFindRequest(&'static str),

    /// Reports an exhausted document revision counter.
    RevisionExhausted,
}

impl Display for DocumentError {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Io(error) => write!(formatter, "document I/O failed: {error}"),
            Self::StaleRevision { expected, actual } => {
                write!(
                    formatter,
                    "stale document revision: expected {expected}, actual {actual}"
                )
            }
            Self::StaleViewport { expected, actual } => {
                write!(
                    formatter,
                    "stale viewport revision: expected {expected}, actual {actual}"
                )
            }
            Self::InvalidRange {
                range,
                document_utf16_units,
            } => write!(
                formatter,
                "invalid UTF-16 range {}..{} for document length {document_utf16_units}",
                range.start, range.end
            ),
            Self::MisalignedUtf16Offset { offset } => {
                write!(formatter, "utf-16 offset {offset} divides a surrogate pair")
            }
            Self::ReplacementTooLarge { bytes, max_bytes } => {
                write!(
                    formatter,
                    "replacement length {bytes} exceeds limit {max_bytes}"
                )
            }
            Self::InvalidReplacement(reason) => {
                write!(formatter, "invalid replacement: {reason}")
            }
            Self::DocumentTooLargeForEditing {
                utf16_units,
                max_utf16_units,
            } => write!(
                formatter,
                "document length {utf16_units} exceeds editable limit {max_utf16_units}"
            ),
            Self::DocumentTooLargeForSaving { bytes, max_bytes } => write!(
                formatter,
                "serialized document length {bytes} exceeds save limit {max_bytes}"
            ),
            Self::InvalidViewportRequest(reason) => {
                write!(formatter, "invalid viewport request: {reason}")
            }
            Self::InvalidEditWindowRequest(reason) => {
                write!(formatter, "invalid edit window request: {reason}")
            }
            Self::InvalidFindRequest(reason) => {
                write!(formatter, "invalid find request: {reason}")
            }
            Self::RevisionExhausted => formatter.write_str("document revision exhausted"),
        }
    }
}

impl Error for DocumentError {
    fn source(&self) -> Option<&(dyn Error + 'static)> {
        match self {
            Self::Io(error) => Some(error),
            _ => None,
        }
    }
}

impl From<std::io::Error> for DocumentError {
    fn from(error: std::io::Error) -> Self {
        Self::Io(error)
    }
}

/// Owns a revisioned source-backed tree and exposes bounded document operations.
pub struct Document {
    tree: PieceTree,
    revision: u64,
}

impl Document {
    /// Creates an empty document.
    #[must_use]
    pub fn new() -> Self {
        Self {
            tree: PieceTree::new(),
            revision: INITIAL_REVISION,
        }
    }

    /// Creates a document from normalized UTF-8 text.
    ///
    /// # Panics
    ///
    /// Panics when `text` contains a non-normalized carriage return.
    #[must_use]
    pub fn from_text(text: &str) -> Self {
        Self {
            tree: PieceTree::from_text(text),
            revision: INITIAL_REVISION,
        }
    }

    /// Opens raw UTF-8 from an owned seekable source file.
    ///
    /// The caller must keep the file contents immutable for every document and
    /// snapshot that can still reference them. Opening detects length changes
    /// during indexing but cannot prevent same-length writes through another
    /// descriptor. The logical model strips an optional UTF-8 BOM and exposes
    /// CRLF, bare CR, and bare LF terminators uniformly as LF. Serialization
    /// preserves every surviving source byte and uses the detected predominant
    /// source line ending for newly inserted logical newlines.
    ///
    /// # Errors
    ///
    /// Returns [`DocumentError::Io`] when the source is not a stable regular
    /// file, cannot be read, contains invalid UTF-8, or overflows a document
    /// metric.
    pub fn open_source(source: File) -> Result<Self, DocumentError> {
        Ok(Self {
            tree: PieceTree::open_source(source)?,
            revision: INITIAL_REVISION,
        })
    }

    /// Returns the current document metrics.
    #[must_use]
    pub fn metrics(&self) -> DocumentMetrics {
        let summary = self.tree.summary();
        DocumentMetrics {
            revision: self.revision,
            bytes: summary.bytes,
            serialized_bytes: self.tree.serialized_bytes(),
            chars: summary.chars,
            utf16_units: summary.utf16_units,
            lines: self.tree.line_count(),
            words: summary.words,
            has_utf8_bom: self.tree.has_utf8_bom(),
            has_lf_line_endings: self.tree.has_lf_line_endings(),
            has_crlf_line_endings: self.tree.has_crlf_line_endings(),
            has_cr_line_endings: self.tree.has_cr_line_endings(),
            inserted_line_ending: self.tree.inserted_line_ending(),
            is_editable: summary.utf16_units <= MAX_EDITABLE_UTF16_UNITS,
        }
    }

    /// Creates an immutable, constant-time snapshot for concurrent saving.
    #[must_use]
    pub fn snapshot(&self) -> DocumentSnapshot {
        DocumentSnapshot {
            tree: self.tree.clone(),
            metrics: self.metrics(),
        }
    }

    /// Replaces a UTF-16 range and returns the resulting document metrics.
    ///
    /// # Errors
    ///
    /// Returns an error when the revision is stale, the range is invalid or
    /// misaligned, the replacement exceeds its inline limit or contains a
    /// non-normalized carriage return, the resulting document exceeds
    /// Android's editable offset space, a size increase exceeds the serialized
    /// source-save ceiling, or the revision counter is exhausted.
    pub fn replace(
        &mut self,
        expected_revision: u64,
        range: Utf16Range,
        replacement: &str,
    ) -> Result<DocumentMetrics, DocumentError> {
        self.replace_with_save_limit(expected_revision, range, replacement, MAX_OUTPUT_BYTES)
    }

    /// Applies the same atomic edit with a caller-supplied internal save ceiling.
    fn replace_with_save_limit(
        &mut self,
        expected_revision: u64,
        range: Utf16Range,
        replacement: &str,
        max_serialized_bytes: u64,
    ) -> Result<DocumentMetrics, DocumentError> {
        if expected_revision != self.revision {
            return Err(DocumentError::StaleRevision {
                expected: expected_revision,
                actual: self.revision,
            });
        }
        if replacement.len() > MAX_INLINE_REPLACEMENT_BYTES {
            return Err(DocumentError::ReplacementTooLarge {
                bytes: replacement.len(),
                max_bytes: MAX_INLINE_REPLACEMENT_BYTES,
            });
        }
        if replacement.contains('\r') {
            return Err(DocumentError::InvalidReplacement(
                "carriage returns are not normalized",
            ));
        }

        let document_utf16_units = self.tree.summary().utf16_units;
        if range.start > range.end || range.end > document_utf16_units {
            return Err(DocumentError::InvalidRange {
                range,
                document_utf16_units,
            });
        }

        let replacement_outcome = self.tree.replace(range, replacement)?;
        let ReplaceOutcome::Replaced(replacement_tree) = replacement_outcome else {
            return Ok(self.metrics());
        };
        let resulting_utf16_units = replacement_tree.summary().utf16_units;
        if resulting_utf16_units > MAX_EDITABLE_UTF16_UNITS {
            return Err(DocumentError::DocumentTooLargeForEditing {
                utf16_units: resulting_utf16_units,
                max_utf16_units: MAX_EDITABLE_UTF16_UNITS,
            });
        }

        let resulting_bytes = replacement_tree.serialized_bytes();
        if resulting_bytes > self.tree.serialized_bytes()
            && u64::try_from(resulting_bytes).unwrap_or(u64::MAX) > max_serialized_bytes
        {
            return Err(DocumentError::DocumentTooLargeForSaving {
                bytes: resulting_bytes,
                max_bytes: max_serialized_bytes,
            });
        }

        let next_revision = self
            .revision
            .checked_add(1)
            .ok_or(DocumentError::RevisionExhausted)?;
        self.tree = replacement_tree;
        self.revision = next_revision;
        Ok(self.metrics())
    }

    /// Returns the global UTF-16 start offset of one logical line.
    ///
    /// # Errors
    ///
    /// Returns an error when the revision is stale, the logical line is
    /// outside the document, or source metadata cannot be read.
    pub fn line_start_utf16(
        &self,
        revision: u64,
        logical_line: usize,
    ) -> Result<usize, DocumentError> {
        let metrics = self.metrics();
        if revision != metrics.revision {
            return Err(DocumentError::StaleRevision {
                expected: revision,
                actual: metrics.revision,
            });
        }
        if logical_line >= metrics.lines {
            return Err(DocumentError::InvalidViewportRequest(
                "logical line is outside the document",
            ));
        }
        let mut tree_reader = self.tree.reader();
        Ok(tree_reader.line_bounds(logical_line)?.content_start_utf16)
    }

    /// Returns a bounded input method window containing a global selection.
    ///
    /// Window edges prefer extended grapheme boundaries within bounded context
    /// but fall back to Unicode scalar boundaries for pathological clusters.
    /// The selection itself must use exact scalar-aligned UTF-16 offsets.
    ///
    /// # Errors
    ///
    /// Returns an error when the revision is stale, the selection is invalid
    /// or misaligned, the selection cannot fit the requested window, the
    /// request exceeds its hard limit, or source text cannot be read.
    pub fn edit_window(
        &self,
        request: EditWindowRequest,
    ) -> Result<EditWindowSnapshot, DocumentError> {
        let metrics = self.metrics();
        if request.revision != metrics.revision {
            return Err(DocumentError::StaleRevision {
                expected: request.revision,
                actual: metrics.revision,
            });
        }
        validate_edit_window_request(request, metrics.utf16_units)?;

        let mut tree_reader = self.tree.reader();
        tree_reader.position_at_utf16(request.selection.start)?;
        tree_reader.position_at_utf16(request.selection.end)?;

        let surrounding_utf16_units = request.max_utf16_units - request.selection.len();
        let preceding_utf16_units = surrounding_utf16_units / 2;
        let mut range_start = request
            .selection
            .start
            .saturating_sub(preceding_utf16_units);
        let mut range_end = range_start
            .saturating_add(request.max_utf16_units)
            .min(metrics.utf16_units);
        if range_end == metrics.utf16_units {
            range_start = range_start.min(range_end.saturating_sub(request.max_utf16_units));
        }

        range_start = tree_reader.scalar_boundary_at_or_after(range_start)?;
        range_end = tree_reader.scalar_boundary_at_or_before(range_end)?;
        debug_assert!(range_start <= request.selection.start);
        debug_assert!(range_end >= request.selection.end);

        range_start = preferred_grapheme_start(
            &mut tree_reader,
            range_start,
            request.selection.start,
            metrics.utf16_units,
        )?;
        range_end = preferred_grapheme_end(
            &mut tree_reader,
            range_end,
            request.selection.end,
            metrics.utf16_units,
        )?;
        range_start = preferred_logical_line_start(
            &mut tree_reader,
            range_start,
            range_end,
            request.selection.start,
        )?;
        range_end = preferred_logical_line_end(
            &mut tree_reader,
            range_start,
            range_end,
            request.selection.end,
            metrics.utf16_units,
        )?;
        let range = Utf16Range::new(range_start, range_end);
        let text = read_exact_text(&mut tree_reader, range)?;
        let prefix = tree_reader.position_at_utf16(range.start)?;
        let line_bounds = tree_reader.line_bounds(prefix.line_feeds)?;
        let line_utf16_offset = range
            .start
            .checked_sub(line_bounds.content_start_utf16)
            .ok_or(DocumentError::InvalidEditWindowRequest(
                "window start precedes its logical line",
            ))?;

        Ok(EditWindowSnapshot {
            metrics,
            range,
            selection: request.selection,
            start: ViewportPosition {
                revision: metrics.revision,
                line: prefix.line_feeds,
                utf16_offset: line_utf16_offset,
            },
            text,
            has_previous: range.start > 0,
            has_next: range.end < metrics.utf16_units,
        })
    }

    /// Finds one literal match within a bounded span of candidate start offsets.
    ///
    /// The query uses the document's normalized logical text and optionally
    /// applies Unicode simple case folding. Its UTF-16 range limits match starts
    /// rather than match ends, so a match may extend beyond the candidate range
    /// while remaining inside the document.
    ///
    /// # Errors
    ///
    /// Returns an error when the revision is stale, the query or work limit is
    /// invalid, a candidate boundary is outside the document or divides a
    /// surrogate pair, a metric overflows, or source text cannot be read.
    pub fn find(&self, request: FindRequest<'_>) -> Result<FindBatch, DocumentError> {
        find_in_tree(&self.tree, self.metrics(), request)
    }

    /// Returns a bounded snapshot suitable for virtualized Compose layout.
    ///
    /// # Errors
    ///
    /// Returns an error when the request exceeds a viewport limit or starts at
    /// an invalid or misaligned position.
    pub fn viewport(&self, request: ViewportRequest) -> Result<ViewportSnapshot, DocumentError> {
        viewport_in_tree(&self.tree, self.metrics(), request)
    }

    /// Returns bounded content immediately preceding one revision-bound position.
    ///
    /// Returned blocks remain in document order. The operation reads only the
    /// lines and bounded block suffixes needed to satisfy the request limits.
    ///
    /// # Errors
    ///
    /// Returns an error when the request exceeds a viewport limit, ends at the
    /// document origin, or uses an invalid, misaligned, or stale position.
    pub fn previous_viewport(
        &self,
        request: PreviousViewportRequest,
    ) -> Result<ViewportSnapshot, DocumentError> {
        validate_previous_viewport_request(request)?;
        let metrics = self.validated_viewport_position(request.end)?;
        let mut tree_reader = self.tree.reader();
        let page_end = canonical_viewport_position(&mut tree_reader, request.end, metrics.lines)?;
        if is_viewport_origin(page_end) {
            return Err(DocumentError::InvalidViewportRequest(
                "previous viewport end is at the document origin",
            ));
        }

        let mut reversed_blocks = Vec::with_capacity(request.max_blocks);
        let mut remaining_utf16_units = request.max_total_utf16_units;
        let mut position = page_end;

        while reversed_blocks.len() < request.max_blocks && remaining_utf16_units > 0 {
            if position.utf16_offset == 0 {
                if position.line == 0 {
                    break;
                }
                let previous_line = position.line - 1;
                let previous_line_bounds = tree_reader.line_bounds(previous_line)?;
                let previous_line_utf16_units = line_content_utf16_units(previous_line_bounds)?;
                position = ViewportPosition {
                    revision: metrics.revision,
                    line: previous_line,
                    utf16_offset: previous_line_utf16_units,
                };
                if previous_line_utf16_units == 0 {
                    reversed_blocks.push(RenderBlock {
                        logical_line: previous_line,
                        global_utf16_start: previous_line_bounds.content_start_utf16,
                        global_utf16_end: previous_line_bounds.content_end_utf16,
                        line_terminator_utf16_units: previous_line_bounds.terminator_utf16_units,
                        text: String::new(),
                        continues_at_start: false,
                        continues_at_end: false,
                    });
                    continue;
                }
            }

            let line_bounds = tree_reader.line_bounds(position.line)?;
            let content_utf16_units = line_content_utf16_units(line_bounds)?;
            if position.utf16_offset > content_utf16_units {
                return Err(DocumentError::InvalidViewportRequest(
                    "end offset is outside its logical line",
                ));
            }
            let global_end_utf16 = line_bounds
                .content_start_utf16
                .checked_add(position.utf16_offset)
                .ok_or(DocumentError::InvalidViewportRequest(
                    "end offset overflowed",
                ))?;
            let block_limit = request.max_block_utf16_units.min(remaining_utf16_units);
            let segment = bounded_segment_before(
                &mut tree_reader,
                line_bounds.content_start_utf16,
                global_end_utf16,
                block_limit,
            )?;
            let Some(segment) = segment else {
                break;
            };
            let local_start_utf16 = segment
                .global_start_utf16
                .checked_sub(line_bounds.content_start_utf16)
                .ok_or(DocumentError::InvalidViewportRequest(
                    "render block starts before its logical line",
                ))?;
            let reaches_content_end = global_end_utf16 == line_bounds.content_end_utf16;

            reversed_blocks.push(RenderBlock {
                logical_line: position.line,
                global_utf16_start: segment.global_start_utf16,
                global_utf16_end: global_end_utf16,
                line_terminator_utf16_units: if reaches_content_end {
                    line_bounds.terminator_utf16_units
                } else {
                    0
                },
                text: segment.text,
                continues_at_start: local_start_utf16 > 0,
                continues_at_end: !reaches_content_end,
            });
            remaining_utf16_units -= position.utf16_offset - local_start_utf16;
            position.utf16_offset = local_start_utf16;
        }

        let position = canonical_viewport_position(&mut tree_reader, position, metrics.lines)?;
        reversed_blocks.reverse();
        Ok(ViewportSnapshot {
            metrics,
            blocks: reversed_blocks,
            previous: previous_viewport_anchor(position),
            next: Some(page_end),
        })
    }

    /// Validates a viewport position and returns its revision's metrics.
    fn validated_viewport_position(
        &self,
        position: ViewportPosition,
    ) -> Result<DocumentMetrics, DocumentError> {
        validated_viewport_metrics(self.metrics(), position)
    }

    /// Writes the complete format-preserving UTF-8 serialization.
    ///
    /// # Errors
    ///
    /// Returns [`DocumentError::Io`] when the destination cannot be written.
    pub fn write_to(&self, mut writer: impl Write) -> Result<(), DocumentError> {
        self.tree.write_to(&mut writer)
    }
}

/// Owns an immutable piece-tree revision for lock-free streaming output.
pub struct DocumentSnapshot {
    tree: PieceTree,
    metrics: DocumentMetrics,
}

impl DocumentSnapshot {
    /// Returns the captured document metrics.
    #[must_use]
    pub const fn metrics(&self) -> DocumentMetrics {
        self.metrics
    }

    /// Finds one literal match within a bounded span of captured candidate starts.
    ///
    /// # Errors
    ///
    /// Returns an error when the revision is stale, the query or work limit is
    /// invalid, a candidate boundary is outside the snapshot or divides a
    /// surrogate pair, a metric overflows, or source text cannot be read.
    pub fn find(&self, request: FindRequest<'_>) -> Result<FindBatch, DocumentError> {
        find_in_tree(&self.tree, self.metrics, request)
    }

    /// Returns one bounded viewport from the captured immutable revision.
    ///
    /// # Errors
    ///
    /// Returns an error when the request exceeds a viewport limit or starts at
    /// an invalid, misaligned, or stale position.
    pub fn viewport(&self, request: ViewportRequest) -> Result<ViewportSnapshot, DocumentError> {
        viewport_in_tree(&self.tree, self.metrics, request)
    }

    /// Consumes this revision into a bounded immutable source-save plan.
    ///
    /// Sparse plans retain one duplicate of the sealed source descriptor and
    /// account only exact live edit bytes as payload. Pathologically
    /// fragmented or non-transferable sources fall back to one complete
    /// payload record.
    ///
    /// # Errors
    ///
    /// Returns an error when package metrics overflow or the serialized
    /// revision exceeds the source-save protocol limit.
    pub fn prepare_source_save(self) -> Result<PreparedSourceSave, DocumentError> {
        PreparedSourceSave::new(self.tree, self.metrics)
    }

    /// Writes the captured format-preserving UTF-8 serialization.
    ///
    /// # Errors
    ///
    /// Returns [`DocumentError::Io`] when the destination cannot be written.
    pub fn write_to(&self, mut writer: impl Write) -> Result<(), DocumentError> {
        self.tree.write_to(&mut writer)
    }
}

impl PreparedSourceSave {
    /// Creates the smallest supported package plan for one immutable tree.
    fn new(tree: PieceTree, document_metrics: DocumentMetrics) -> Result<Self, DocumentError> {
        let output_bytes = u64::try_from(document_metrics.serialized_bytes)
            .map_err(|_| invalid_source_save("serialized byte length exceeds u64"))?;
        if output_bytes > MAX_OUTPUT_BYTES {
            return Err(invalid_source_save(
                "serialized byte length exceeds the source-save limit",
            ));
        }

        let full_payload_header = PackageHeader::new(
            u64::from(output_bytes > 0),
            output_bytes,
            output_bytes,
            None,
        )
        .map_err(package_error)?;
        let mut counter = SourceSaveSegmentCounter::default();
        let sparse_counted = match tree.visit_serialized_segments(true, &mut counter) {
            Ok(()) => true,
            Err(_) if counter.record_limit_reached => false,
            Err(error) => return Err(error),
        };
        if sparse_counted && let SourceClone::Available(source) = tree.try_clone_source() {
            let source_bytes = Some(source.byte_length);
            let sparse_header = PackageHeader::new(
                counter.record_count,
                output_bytes,
                counter.payload_bytes,
                source_bytes,
            )
            .map_err(package_error)?;
            if sparse_header.package_bytes() < full_payload_header.package_bytes() {
                let package_metrics = package_metrics(sparse_header);
                return Ok(Self {
                    tree,
                    document_metrics,
                    package_metrics,
                    source: Some(source.file),
                    mode: SourceSaveMode::Sparse,
                });
            }
        }

        Ok(Self {
            tree,
            document_metrics,
            package_metrics: package_metrics(full_payload_header),
            source: None,
            mode: SourceSaveMode::FullPayload,
        })
    }

    /// Returns the captured document metrics.
    #[must_use]
    pub const fn document_metrics(&self) -> DocumentMetrics {
        self.document_metrics
    }

    /// Returns the exact package allocation and reconstruction metrics.
    #[must_use]
    pub const fn package_metrics(&self) -> SourceSavePackageMetrics {
        self.package_metrics
    }

    /// Writes the complete immutable package through bounded buffers.
    ///
    /// Sparse packages write only edit and boundary-repair payloads. Complete
    /// fallback packages stream source-backed text through the piece tree's
    /// fixed-size read buffer.
    ///
    /// # Errors
    ///
    /// Returns an error when package encoding, source reading, payload
    /// materialization, or destination writing fails.
    pub fn write_package(&self, writer: impl Write) -> Result<(), DocumentError> {
        let header = PackageHeader::new(
            self.package_metrics.record_count,
            self.package_metrics.output_bytes,
            self.package_metrics.payload_bytes,
            self.package_metrics.source_bytes,
        )
        .map_err(package_error)?;
        let mut encoder = PackageEncoder::new(writer, header).map_err(package_error)?;

        match self.mode {
            SourceSaveMode::Sparse => {
                let mut record_writer = SourceSaveRecordWriter {
                    encoder: &mut encoder,
                };
                self.tree
                    .visit_serialized_segments(true, &mut record_writer)?;
                encoder.finish_records().map_err(package_error)?;

                let mut payload_writer = SourceSavePayloadWriter {
                    encoder: &mut encoder,
                    buffer: vec![0_u8; MAX_PIECE_BYTES].into_boxed_slice(),
                };
                self.tree
                    .visit_serialized_segments(true, &mut payload_writer)?;
            }
            SourceSaveMode::FullPayload => {
                if self.package_metrics.output_bytes > 0 {
                    encoder
                        .write_payload_record(self.package_metrics.output_bytes)
                        .map_err(package_error)?;
                }
                encoder.finish_records().map_err(package_error)?;
                self.tree.write_to(&mut encoder)?;
            }
        }

        encoder.finish().map(drop).map_err(package_error)
    }

    /// Transfers the optional immutable source descriptor after package output.
    #[must_use]
    pub fn into_source(self) -> Option<File> {
        self.source
    }
}

/// Counts one sparse package without reading or materializing source bytes.
#[derive(Default)]
struct SourceSaveSegmentCounter {
    record_count: u64,
    payload_bytes: u64,
    record_limit_reached: bool,
}

impl SourceSaveSegmentCounter {
    /// Counts one more record or stops sparse planning at its fixed limit.
    fn count_record(&mut self) -> Result<(), DocumentError> {
        if self.record_count == MAX_RECORD_COUNT {
            self.record_limit_reached = true;
            return Err(invalid_source_save(
                "source-save record limit reached during planning",
            ));
        }
        self.record_count = self
            .record_count
            .checked_add(1)
            .ok_or_else(|| invalid_source_save("source-save record count overflowed"))?;
        Ok(())
    }
}

impl SerializedSegmentVisitor for SourceSaveSegmentCounter {
    fn visit_source(&mut self, _byte_start: u64, _byte_length: u64) -> Result<(), DocumentError> {
        self.count_record()
    }

    fn visit_payload(&mut self, payload: SerializedPayload<'_>) -> Result<(), DocumentError> {
        self.count_record()?;
        self.payload_bytes = self
            .payload_bytes
            .checked_add(
                u64::try_from(payload.byte_length())
                    .map_err(|_| invalid_source_save("source-save payload length exceeds u64"))?,
            )
            .ok_or_else(|| invalid_source_save("source-save payload length overflowed"))?;
        Ok(())
    }
}

/// Writes one sparse record table without reading source or payload bytes.
struct SourceSaveRecordWriter<'encoder, Writer> {
    encoder: &'encoder mut PackageEncoder<Writer>,
}

impl<Writer> SerializedSegmentVisitor for SourceSaveRecordWriter<'_, Writer>
where
    Writer: Write,
{
    fn visit_source(&mut self, byte_start: u64, byte_length: u64) -> Result<(), DocumentError> {
        self.encoder
            .write_source(byte_start, byte_length)
            .map_err(package_error)
    }

    fn visit_payload(&mut self, payload: SerializedPayload<'_>) -> Result<(), DocumentError> {
        let byte_length = u64::try_from(payload.byte_length())
            .map_err(|_| invalid_source_save("source-save payload length exceeds u64"))?;
        self.encoder
            .write_payload_record(byte_length)
            .map_err(package_error)
    }
}

/// Writes only bounded sparse payload segments in their table order.
struct SourceSavePayloadWriter<'encoder, Writer> {
    encoder: &'encoder mut PackageEncoder<Writer>,
    buffer: Box<[u8]>,
}

impl<Writer> SerializedSegmentVisitor for SourceSavePayloadWriter<'_, Writer>
where
    Writer: Write,
{
    fn visit_source(&mut self, _byte_start: u64, _byte_length: u64) -> Result<(), DocumentError> {
        Ok(())
    }

    fn visit_payload(&mut self, payload: SerializedPayload<'_>) -> Result<(), DocumentError> {
        payload.write_to(self.encoder, &mut self.buffer)
    }
}

/// Converts one validated protocol header into public immutable metrics.
const fn package_metrics(header: PackageHeader) -> SourceSavePackageMetrics {
    SourceSavePackageMetrics {
        package_bytes: header.package_bytes(),
        output_bytes: header.output_bytes(),
        payload_bytes: header.payload_bytes(),
        source_bytes: header.source_bytes(),
        record_count: header.record_count(),
    }
}

/// Converts one package protocol failure into a composable document error.
fn package_error(error: impl Error + Send + Sync + 'static) -> DocumentError {
    DocumentError::Io(std::io::Error::new(ErrorKind::InvalidData, error))
}

/// Creates one source-save invariant error without document content.
fn invalid_source_save(message: &'static str) -> DocumentError {
    DocumentError::Io(std::io::Error::new(ErrorKind::InvalidData, message))
}

impl Default for Document {
    fn default() -> Self {
        Self::new()
    }
}

/// Finds one literal match through a bounded reader shared by live and captured revisions.
fn find_in_tree(
    tree: &PieceTree,
    metrics: DocumentMetrics,
    request: FindRequest<'_>,
) -> Result<FindBatch, DocumentError> {
    if request.revision != metrics.revision {
        return Err(DocumentError::StaleRevision {
            expected: request.revision,
            actual: metrics.revision,
        });
    }
    let query_utf16_units = validate_find_request(request, metrics.utf16_units)?;
    let mut tree_reader = tree.reader();
    tree_reader.position_at_utf16(request.candidate_range.start)?;
    tree_reader.position_at_utf16(request.candidate_range.end)?;
    if request.candidate_range.is_empty() {
        return Ok(FindBatch {
            metrics,
            matched: None,
            remaining_candidate_range: None,
        });
    }

    let candidate_batch = bounded_find_candidate_range(&mut tree_reader, request)?;
    let lookahead_utf16_units = if request.match_case {
        query_utf16_units
    } else {
        request
            .query
            .chars()
            .count()
            .checked_mul(CASE_INSENSITIVE_SCALAR_UTF16_UNITS)
            .ok_or(DocumentError::InvalidFindRequest(
                "case-insensitive find lookahead overflowed",
            ))?
    };
    let search_end_target = candidate_batch
        .end
        .saturating_add(lookahead_utf16_units)
        .min(metrics.utf16_units);
    let search_end = tree_reader.scalar_boundary_at_or_before(search_end_target)?;
    let search_range = Utf16Range::new(candidate_batch.start, search_end);
    let search_text = read_find_text(&mut tree_reader, search_range)?;
    let candidate_end_utf16_units = candidate_batch
        .end
        .checked_sub(candidate_batch.start)
        .ok_or(DocumentError::InvalidFindRequest(
            "candidate batch range is reversed",
        ))?;
    let candidate_end_byte = find_byte_offset_at_utf16(
        &search_text,
        candidate_end_utf16_units,
        "candidate batch end is not scalar-aligned",
    )?;
    let matched_byte_range = if request.match_case {
        find_case_sensitive_match(
            &search_text,
            candidate_end_byte,
            request.query,
            request.direction,
        )?
    } else {
        find_case_insensitive_match(
            &search_text,
            candidate_end_byte,
            request.query,
            request.direction,
        )?
    };
    let matched = matched_byte_range
        .map(|byte_range| {
            create_find_match(
                &mut tree_reader,
                metrics,
                &search_text,
                candidate_batch.start,
                byte_range,
                request.query,
                request.match_case,
            )
        })
        .transpose()?;
    let remaining_candidate_range = if matched.is_some() {
        None
    } else {
        remaining_find_candidate_range(request, candidate_batch)
    };

    Ok(FindBatch {
        metrics,
        matched,
        remaining_candidate_range,
    })
}

/// Validates one literal query, candidate range, and per-request work limit.
fn validate_find_request(
    request: FindRequest<'_>,
    document_utf16_units: usize,
) -> Result<usize, DocumentError> {
    if request.query.is_empty() {
        return Err(DocumentError::InvalidFindRequest("query is empty"));
    }
    if request.query.contains('\r') {
        return Err(DocumentError::InvalidFindRequest(
            "query contains a non-normalized carriage return",
        ));
    }
    if request.query.len() > MAX_FIND_QUERY_BYTES {
        return Err(DocumentError::InvalidFindRequest(
            "query byte length exceeds the supported range",
        ));
    }
    let query_utf16_units = request.query.encode_utf16().count();
    if query_utf16_units > MAX_FIND_QUERY_UTF16_UNITS {
        return Err(DocumentError::InvalidFindRequest(
            "query utf-16 length exceeds the supported range",
        ));
    }
    if !(MIN_FIND_CANDIDATE_UTF16_UNITS..=MAX_FIND_CANDIDATE_UTF16_UNITS)
        .contains(&request.max_candidate_utf16_units)
    {
        return Err(DocumentError::InvalidFindRequest(
            "candidate work limit is outside the supported range",
        ));
    }
    if request.candidate_range.start > request.candidate_range.end
        || request.candidate_range.end > document_utf16_units
    {
        return Err(DocumentError::InvalidRange {
            range: request.candidate_range,
            document_utf16_units,
        });
    }
    Ok(query_utf16_units)
}

/// Returns one exact byte range whose start belongs to the candidate batch.
fn find_case_sensitive_match(
    search_text: &str,
    candidate_end_byte: usize,
    query: &str,
    direction: FindDirection,
) -> Result<Option<Range<usize>>, DocumentError> {
    let searchable_end_byte = candidate_end_byte
        .checked_add(query.len().saturating_sub(1))
        .ok_or(DocumentError::InvalidFindRequest(
            "find lookahead byte length overflowed",
        ))?
        .min(search_text.len());
    let searchable_bytes = &search_text.as_bytes()[..searchable_end_byte];
    let query_bytes = query.as_bytes();
    let start = match direction {
        FindDirection::Forward => memmem::find(searchable_bytes, query_bytes),
        FindDirection::Backward => memmem::rfind(searchable_bytes, query_bytes),
    };
    start
        .map(|start| {
            let end = start
                .checked_add(query.len())
                .filter(|end| *end <= search_text.len())
                .ok_or(DocumentError::InvalidFindRequest(
                    "find match byte range overflowed",
                ))?;
            Ok(start..end)
        })
        .transpose()
}

/// Returns one Unicode case-folded byte range in the requested traversal order.
fn find_case_insensitive_match(
    search_text: &str,
    candidate_end_byte: usize,
    query: &str,
    direction: FindDirection,
) -> Result<Option<Range<usize>>, DocumentError> {
    let escaped_query = regex::escape(query);
    let matcher = RegexBuilder::new(&escaped_query)
        .case_insensitive(true)
        .size_limit(FIND_REGEX_SIZE_LIMIT_BYTES)
        .build()
        .map_err(|_| {
            DocumentError::InvalidFindRequest("case-insensitive query could not be compiled")
        })?;
    match direction {
        FindDirection::Forward => Ok(matcher
            .find(search_text)
            .filter(|matched| matched.start() < candidate_end_byte)
            .map(|matched| matched.range())),
        FindDirection::Backward => {
            let max_match_bytes = query.chars().count() * MAX_UTF8_SCALAR_BYTES;
            let mut candidate_start = 0;
            let mut candidate_end = candidate_end_byte;
            let mut last_match = None;
            // Search the upper half, then discard starts that cannot improve the result.
            while candidate_start < candidate_end {
                let midpoint = search_text
                    .floor_char_boundary(candidate_start + (candidate_end - candidate_start) / 2);
                // Keep enough lookahead for one simple-case-folded literal.
                let search_end =
                    search_text.floor_char_boundary(candidate_end.saturating_add(max_match_bytes));
                match matcher
                    .find_at(&search_text[..search_end], midpoint)
                    .filter(|matched| matched.start() < candidate_end)
                {
                    Some(found) => {
                        candidate_start = next_scalar_byte_offset(search_text, found.start())?;
                        last_match = Some(found.range());
                    }
                    None => candidate_end = midpoint,
                }
            }
            Ok(last_match)
        }
    }
}

/// Returns the byte boundary immediately after one scalar-aligned position.
fn next_scalar_byte_offset(text: &str, byte_offset: usize) -> Result<usize, DocumentError> {
    let suffix = text
        .get(byte_offset..)
        .ok_or(DocumentError::InvalidFindRequest(
            "find match start is not scalar-aligned",
        ))?;
    let character = suffix
        .chars()
        .next()
        .ok_or(DocumentError::InvalidFindRequest(
            "find matcher returned an empty terminal match",
        ))?;
    byte_offset
        .checked_add(character.len_utf8())
        .ok_or(DocumentError::InvalidFindRequest(
            "find traversal byte offset overflowed",
        ))
}

/// Selects one nonempty scalar-aligned batch without exceeding its candidate budget.
fn bounded_find_candidate_range(
    tree_reader: &mut PieceTreeReader<'_>,
    request: FindRequest<'_>,
) -> Result<Utf16Range, DocumentError> {
    let candidate_batch = match request.direction {
        FindDirection::Forward => {
            let end_target = request
                .candidate_range
                .start
                .saturating_add(request.max_candidate_utf16_units)
                .min(request.candidate_range.end);
            Utf16Range::new(
                request.candidate_range.start,
                tree_reader.scalar_boundary_at_or_before(end_target)?,
            )
        }
        FindDirection::Backward => {
            let start_target = request
                .candidate_range
                .end
                .saturating_sub(request.max_candidate_utf16_units)
                .max(request.candidate_range.start);
            Utf16Range::new(
                tree_reader.scalar_boundary_at_or_after(start_target)?,
                request.candidate_range.end,
            )
        }
    };
    if candidate_batch.is_empty() || candidate_batch.len() > request.max_candidate_utf16_units {
        return Err(DocumentError::InvalidFindRequest(
            "candidate batch cannot make bounded progress",
        ));
    }
    Ok(candidate_batch)
}

/// Reads one complete bounded search range from normalized logical text.
fn read_find_text(
    tree_reader: &mut PieceTreeReader<'_>,
    range: Utf16Range,
) -> Result<String, DocumentError> {
    let prefix = tree_reader.bounded_text_prefix(range, range.len())?;
    if !prefix.reached_end || prefix.utf16_units != range.len() {
        return Err(DocumentError::InvalidFindRequest(
            "find text did not reach its requested range",
        ));
    }
    Ok(prefix.text)
}

/// Creates one literal global range and line-relative cursor from a local byte match.
fn create_find_match(
    tree_reader: &mut PieceTreeReader<'_>,
    metrics: DocumentMetrics,
    search_text: &str,
    global_search_start_utf16: usize,
    local_match_byte_range: Range<usize>,
    query: &str,
    match_case: bool,
) -> Result<FindMatch, DocumentError> {
    let local_prefix = search_text.get(..local_match_byte_range.start).ok_or(
        DocumentError::InvalidFindRequest("match start is not scalar-aligned"),
    )?;
    let matched_text =
        search_text
            .get(local_match_byte_range)
            .ok_or(DocumentError::InvalidFindRequest(
                "match byte range is not scalar-aligned",
            ))?;
    if match_case && matched_text != query {
        return Err(DocumentError::InvalidFindRequest(
            "matched bytes conflict with the query",
        ));
    }
    if matched_text.is_empty() {
        return Err(DocumentError::InvalidFindRequest("find match is empty"));
    }
    let local_match_start_utf16 = local_prefix.encode_utf16().count();
    let global_match_start_utf16 = global_search_start_utf16
        .checked_add(local_match_start_utf16)
        .ok_or(DocumentError::InvalidFindRequest(
            "match start offset overflowed",
        ))?;
    let global_match_end_utf16 = global_match_start_utf16
        .checked_add(matched_text.encode_utf16().count())
        .filter(|end| *end <= metrics.utf16_units)
        .ok_or(DocumentError::InvalidFindRequest(
            "match end exceeds the document",
        ))?;
    let prefix = tree_reader.position_at_utf16(global_match_start_utf16)?;
    let line_bounds = tree_reader.line_bounds(prefix.line_feeds)?;
    let line_utf16_offset = global_match_start_utf16
        .checked_sub(line_bounds.content_start_utf16)
        .ok_or(DocumentError::InvalidFindRequest(
            "match starts before its logical line",
        ))?;

    Ok(FindMatch {
        range: Utf16Range::new(global_match_start_utf16, global_match_end_utf16),
        start: ViewportPosition {
            revision: metrics.revision,
            line: prefix.line_feeds,
            utf16_offset: line_utf16_offset,
        },
    })
}

/// Returns the unsearched candidate starts after one bounded miss.
fn remaining_find_candidate_range(
    request: FindRequest<'_>,
    candidate_batch: Utf16Range,
) -> Option<Utf16Range> {
    match request.direction {
        FindDirection::Forward => (candidate_batch.end < request.candidate_range.end)
            .then(|| Utf16Range::new(candidate_batch.end, request.candidate_range.end)),
        FindDirection::Backward => (candidate_batch.start > request.candidate_range.start)
            .then(|| Utf16Range::new(request.candidate_range.start, candidate_batch.start)),
    }
}

/// Returns the byte offset at one scalar-aligned local UTF-16 boundary.
fn find_byte_offset_at_utf16(
    text: &str,
    target_utf16_offset: usize,
    error_message: &'static str,
) -> Result<usize, DocumentError> {
    let mut utf16_offset = 0;
    for (byte_offset, character) in text.char_indices() {
        if utf16_offset == target_utf16_offset {
            return Ok(byte_offset);
        }
        utf16_offset += character.len_utf16();
    }
    if utf16_offset == target_utf16_offset {
        return Ok(text.len());
    }
    Err(DocumentError::InvalidFindRequest(error_message))
}

/// Validates the fixed bounds of one input method window request.
fn validate_edit_window_request(
    request: EditWindowRequest,
    document_utf16_units: usize,
) -> Result<(), DocumentError> {
    if request.max_utf16_units == 0 || request.max_utf16_units > MAX_EDIT_WINDOW_UTF16_UNITS {
        return Err(DocumentError::InvalidEditWindowRequest(
            "window length is outside the supported range",
        ));
    }
    if request.selection.start > request.selection.end
        || request.selection.end > document_utf16_units
    {
        return Err(DocumentError::InvalidRange {
            range: request.selection,
            document_utf16_units,
        });
    }
    if request.selection.len() > request.max_utf16_units {
        return Err(DocumentError::InvalidEditWindowRequest(
            "selection length exceeds the window limit",
        ));
    }
    Ok(())
}

/// Selects a nearby grapheme boundary without expanding a scalar-safe window.
fn preferred_grapheme_start(
    tree_reader: &mut PieceTreeReader<'_>,
    scalar_start: usize,
    selection_start: usize,
    document_utf16_units: usize,
) -> Result<usize, DocumentError> {
    if scalar_start == 0 {
        return Ok(0);
    }

    let probe_start = tree_reader.scalar_boundary_at_or_after(
        scalar_start.saturating_sub(EDIT_WINDOW_GRAPHEME_CONTEXT_UTF16_UNITS),
    )?;
    let search_end = scalar_start
        .saturating_add(EDIT_WINDOW_GRAPHEME_CONTEXT_UTF16_UNITS)
        .min(selection_start);
    let probe_end = tree_reader.scalar_boundary_at_or_before(
        search_end
            .saturating_add(EDIT_WINDOW_GRAPHEME_CONTEXT_UTF16_UNITS)
            .min(document_utf16_units),
    )?;
    let probe = read_exact_text(tree_reader, Utf16Range::new(probe_start, probe_end))?;
    let mut boundary = probe_start;
    for grapheme in probe.graphemes(true) {
        if boundary >= scalar_start && boundary <= selection_start {
            return Ok(boundary);
        }
        boundary = boundary
            .checked_add(grapheme.encode_utf16().count())
            .ok_or(DocumentError::InvalidEditWindowRequest(
                "grapheme boundary overflowed",
            ))?;
    }
    if boundary >= scalar_start && boundary <= selection_start {
        return Ok(boundary);
    }
    Ok(scalar_start)
}

/// Selects a preceding grapheme boundary without expanding a scalar-safe window.
fn preferred_grapheme_end(
    tree_reader: &mut PieceTreeReader<'_>,
    scalar_end: usize,
    selection_end: usize,
    document_utf16_units: usize,
) -> Result<usize, DocumentError> {
    if scalar_end == document_utf16_units {
        return Ok(document_utf16_units);
    }

    let probe_start = tree_reader.scalar_boundary_at_or_after(
        scalar_end.saturating_sub(EDIT_WINDOW_GRAPHEME_PROBE_BEFORE_UTF16_UNITS),
    )?;
    let probe_end = tree_reader.scalar_boundary_at_or_before(
        scalar_end
            .saturating_add(EDIT_WINDOW_GRAPHEME_CONTEXT_UTF16_UNITS)
            .min(document_utf16_units),
    )?;
    let probe = read_exact_text(tree_reader, Utf16Range::new(probe_start, probe_end))?;
    let mut boundary = probe_start;
    let mut preferred_boundary =
        (boundary >= selection_end && boundary <= scalar_end).then_some(boundary);
    for grapheme in probe.graphemes(true) {
        boundary = boundary
            .checked_add(grapheme.encode_utf16().count())
            .ok_or(DocumentError::InvalidEditWindowRequest(
                "grapheme boundary overflowed",
            ))?;
        if boundary > scalar_end {
            break;
        }
        if boundary >= selection_end {
            preferred_boundary = Some(boundary);
        }
    }
    Ok(preferred_boundary.unwrap_or(scalar_end))
}

/// Moves a partial leading line inward when the selection remains contained.
fn preferred_logical_line_start(
    tree_reader: &mut PieceTreeReader<'_>,
    range_start: usize,
    range_end: usize,
    selection_start: usize,
) -> Result<usize, DocumentError> {
    if range_start == 0 {
        return Ok(0);
    }
    let position = tree_reader.position_at_utf16(range_start)?;
    let line_bounds = tree_reader.line_bounds(position.line_feeds)?;
    if range_start == line_bounds.content_start_utf16 {
        return Ok(range_start);
    }
    let next_line_start = line_bounds
        .content_end_utf16
        .checked_add(usize::from(line_bounds.terminator_utf16_units))
        .ok_or(DocumentError::InvalidEditWindowRequest(
            "line-aligned window start overflowed",
        ))?;
    Ok(
        if next_line_start < range_end && next_line_start <= selection_start {
            next_line_start
        } else {
            range_start
        },
    )
}

/// Moves a partial trailing line inward when the selection remains contained.
fn preferred_logical_line_end(
    tree_reader: &mut PieceTreeReader<'_>,
    range_start: usize,
    range_end: usize,
    selection_end: usize,
    document_utf16_units: usize,
) -> Result<usize, DocumentError> {
    if range_end == document_utf16_units {
        return Ok(document_utf16_units);
    }
    let position = tree_reader.position_at_utf16(range_end)?;
    let line_bounds = tree_reader.line_bounds(position.line_feeds)?;
    if range_end == line_bounds.content_start_utf16 {
        return Ok(range_end);
    }
    Ok(
        if line_bounds.content_start_utf16 > range_start
            && line_bounds.content_start_utf16 >= selection_end
        {
            line_bounds.content_start_utf16
        } else {
            range_end
        },
    )
}

/// Reads one exact scalar-aligned range through the bounded tree reader.
fn read_exact_text(
    tree_reader: &mut PieceTreeReader<'_>,
    range: Utf16Range,
) -> Result<String, DocumentError> {
    let prefix = tree_reader.bounded_text_prefix(range, range.len())?;
    if !prefix.reached_end || prefix.utf16_units != range.len() {
        return Err(DocumentError::InvalidEditWindowRequest(
            "window text did not reach its requested range",
        ));
    }
    Ok(prefix.text)
}

/// Contains one bounded suffix selected for reverse viewport traversal.
struct PreviousRenderSegment {
    /// Stores the global UTF-16 start offset.
    global_start_utf16: usize,

    /// Stores the selected normalized text.
    text: String,
}

/// Returns one bounded viewport from an exact piece-tree revision.
fn viewport_in_tree(
    tree: &PieceTree,
    metrics: DocumentMetrics,
    request: ViewportRequest,
) -> Result<ViewportSnapshot, DocumentError> {
    validate_viewport_request(request)?;
    let metrics = validated_viewport_metrics(metrics, request.start)?;
    let mut blocks = Vec::with_capacity(request.max_blocks);
    let mut remaining_utf16_units = request.max_total_utf16_units;
    let mut tree_reader = tree.reader();
    let mut position = canonical_viewport_position(&mut tree_reader, request.start, metrics.lines)?;
    let page_start = position;

    while blocks.len() < request.max_blocks && remaining_utf16_units > 0 {
        let line_bounds = tree_reader.line_bounds(position.line)?;
        let content_utf16_units = line_content_utf16_units(line_bounds)?;

        if position.utf16_offset > content_utf16_units {
            return Err(DocumentError::InvalidViewportRequest(
                "start offset is outside its logical line",
            ));
        }
        if content_utf16_units > 0 && position.utf16_offset == content_utf16_units {
            if position.line + 1 < metrics.lines {
                position = ViewportPosition {
                    revision: metrics.revision,
                    line: position.line + 1,
                    utf16_offset: 0,
                };
                continue;
            }
            return Ok(ViewportSnapshot {
                metrics,
                blocks,
                previous: previous_viewport_anchor(page_start),
                next: None,
            });
        }

        let global_start_utf16 = line_bounds
            .content_start_utf16
            .checked_add(position.utf16_offset)
            .ok_or(DocumentError::InvalidViewportRequest(
                "start offset overflowed",
            ))?;
        let block_limit = request.max_block_utf16_units.min(remaining_utf16_units);
        let sample_utf16_units = block_limit.saturating_add(GRAPHEME_LOOKAHEAD_UTF16_UNITS);
        let prefix = tree_reader.bounded_text_prefix(
            Utf16Range::new(global_start_utf16, line_bounds.content_end_utf16),
            sample_utf16_units,
        )?;
        let (segment_end_byte, segment_utf16_units) =
            bounded_segment_end(&prefix.text, block_limit);
        if segment_end_byte == 0 && global_start_utf16 < line_bounds.content_end_utf16 {
            return Ok(ViewportSnapshot {
                metrics,
                blocks,
                previous: previous_viewport_anchor(page_start),
                next: Some(position),
            });
        }
        debug_assert!(segment_utf16_units <= prefix.utf16_units);
        let global_end_utf16 = global_start_utf16.checked_add(segment_utf16_units).ok_or(
            DocumentError::InvalidViewportRequest("render block offset overflowed"),
        )?;
        let reaches_content_end = prefix.reached_end && segment_end_byte == prefix.text.len();
        let text = {
            let mut text = prefix.text;
            text.truncate(segment_end_byte);
            text.into_boxed_str().into_string()
        };

        blocks.push(RenderBlock {
            logical_line: position.line,
            global_utf16_start: global_start_utf16,
            global_utf16_end: global_end_utf16,
            line_terminator_utf16_units: if reaches_content_end {
                line_bounds.terminator_utf16_units
            } else {
                0
            },
            text,
            continues_at_start: position.utf16_offset > 0,
            continues_at_end: !reaches_content_end,
        });
        remaining_utf16_units -= segment_utf16_units;

        if !reaches_content_end {
            position.utf16_offset += segment_utf16_units;
        } else if position.line + 1 < metrics.lines {
            position = ViewportPosition {
                revision: metrics.revision,
                line: position.line + 1,
                utf16_offset: 0,
            };
        } else {
            return Ok(ViewportSnapshot {
                metrics,
                blocks,
                previous: previous_viewport_anchor(page_start),
                next: None,
            });
        }
    }

    Ok(ViewportSnapshot {
        metrics,
        blocks,
        previous: previous_viewport_anchor(page_start),
        next: Some(position),
    })
}

/// Validates one viewport position against exact revision metrics.
fn validated_viewport_metrics(
    metrics: DocumentMetrics,
    position: ViewportPosition,
) -> Result<DocumentMetrics, DocumentError> {
    if position.revision != metrics.revision {
        return Err(DocumentError::StaleViewport {
            expected: position.revision,
            actual: metrics.revision,
        });
    }
    if position.line >= metrics.lines {
        return Err(DocumentError::InvalidViewportRequest(
            "viewport line is outside the document",
        ));
    }
    Ok(metrics)
}

/// Canonicalizes and validates one line-relative viewport position.
fn canonical_viewport_position(
    tree_reader: &mut PieceTreeReader<'_>,
    position: ViewportPosition,
    line_count: usize,
) -> Result<ViewportPosition, DocumentError> {
    let line_bounds = tree_reader.line_bounds(position.line)?;
    let content_utf16_units = line_content_utf16_units(line_bounds)?;
    if position.utf16_offset > content_utf16_units {
        return Err(DocumentError::InvalidViewportRequest(
            "viewport offset is outside its logical line",
        ));
    }
    let global_utf16_offset = line_bounds
        .content_start_utf16
        .checked_add(position.utf16_offset)
        .ok_or(DocumentError::InvalidViewportRequest(
            "viewport offset overflowed",
        ))?;
    tree_reader.position_at_utf16(global_utf16_offset)?;

    if content_utf16_units > 0
        && position.utf16_offset == content_utf16_units
        && position.line + 1 < line_count
    {
        return Ok(ViewportPosition {
            revision: position.revision,
            line: position.line + 1,
            utf16_offset: 0,
        });
    }
    Ok(position)
}

/// Returns the UTF-16 length of one validated logical line.
fn line_content_utf16_units(line_bounds: piece_tree::LineBounds) -> Result<usize, DocumentError> {
    line_bounds
        .content_end_utf16
        .checked_sub(line_bounds.content_start_utf16)
        .ok_or(DocumentError::InvalidViewportRequest(
            "logical line bounds are invalid",
        ))
}

/// Returns whether one position identifies the document origin.
const fn is_viewport_origin(position: ViewportPosition) -> bool {
    position.line == 0 && position.utf16_offset == 0
}

/// Returns the reverse-page anchor preceding one page start.
fn previous_viewport_anchor(page_start: ViewportPosition) -> Option<ViewportPosition> {
    (!is_viewport_origin(page_start)).then_some(page_start)
}

/// Selects one bounded, grapheme-preferred segment ending at an exact offset.
fn bounded_segment_before(
    tree_reader: &mut PieceTreeReader<'_>,
    line_start_utf16: usize,
    global_end_utf16: usize,
    max_utf16_units: usize,
) -> Result<Option<PreviousRenderSegment>, DocumentError> {
    if line_start_utf16 >= global_end_utf16 {
        return Ok(None);
    }
    let scalar_start = tree_reader.scalar_boundary_at_or_after(
        global_end_utf16
            .saturating_sub(max_utf16_units)
            .max(line_start_utf16),
    )?;
    if scalar_start >= global_end_utf16 {
        return Ok(None);
    }
    let probe_start = tree_reader.scalar_boundary_at_or_after(
        scalar_start
            .saturating_sub(GRAPHEME_LOOKAHEAD_UTF16_UNITS)
            .max(line_start_utf16),
    )?;
    let probe_range = Utf16Range::new(probe_start, global_end_utf16);
    let probe = tree_reader.bounded_text_prefix(probe_range, probe_range.len())?;
    if !probe.reached_end || probe.utf16_units != probe_range.len() {
        return Err(DocumentError::InvalidViewportRequest(
            "reverse render text did not reach its requested range",
        ));
    }

    let mut boundary_utf16 = probe_start;
    let mut preferred_start = None;
    for (byte_index, grapheme) in probe.text.grapheme_indices(true) {
        if boundary_utf16 >= scalar_start && boundary_utf16 < global_end_utf16 {
            preferred_start = Some((boundary_utf16, byte_index));
            break;
        }
        boundary_utf16 = boundary_utf16
            .checked_add(grapheme.encode_utf16().count())
            .ok_or(DocumentError::InvalidViewportRequest(
                "reverse grapheme boundary overflowed",
            ))?;
    }
    let (global_start_utf16, start_byte) = if let Some(preferred_start) = preferred_start {
        preferred_start
    } else {
        (
            scalar_start,
            byte_offset_at_utf16(&probe.text, scalar_start - probe_start)?,
        )
    };
    let text = probe.text[start_byte..].to_owned();
    let segment_utf16_units = global_end_utf16 - global_start_utf16;
    if text.is_empty()
        || segment_utf16_units > max_utf16_units
        || text.encode_utf16().count() != segment_utf16_units
    {
        return Err(DocumentError::InvalidViewportRequest(
            "reverse render segment violates its UTF-16 limit",
        ));
    }
    Ok(Some(PreviousRenderSegment {
        global_start_utf16,
        text,
    }))
}

/// Returns the byte offset at one scalar-aligned UTF-16 boundary.
fn byte_offset_at_utf16(text: &str, target_utf16_offset: usize) -> Result<usize, DocumentError> {
    let mut utf16_offset = 0;
    for (byte_offset, character) in text.char_indices() {
        if utf16_offset == target_utf16_offset {
            return Ok(byte_offset);
        }
        utf16_offset += character.len_utf16();
    }
    if utf16_offset == target_utf16_offset {
        return Ok(text.len());
    }
    Err(DocumentError::InvalidViewportRequest(
        "reverse render offset divides a Unicode character",
    ))
}

/// Validates the allocation and layout bounds of a viewport request.
fn validate_viewport_request(request: ViewportRequest) -> Result<(), DocumentError> {
    validate_viewport_limits(
        request.max_blocks,
        request.max_block_utf16_units,
        request.max_total_utf16_units,
    )
}

/// Validates the allocation and layout bounds of a reverse viewport request.
fn validate_previous_viewport_request(
    request: PreviousViewportRequest,
) -> Result<(), DocumentError> {
    validate_viewport_limits(
        request.max_blocks,
        request.max_block_utf16_units,
        request.max_total_utf16_units,
    )
}

/// Validates shared viewport allocation and layout limits.
fn validate_viewport_limits(
    max_blocks: usize,
    max_block_utf16_units: usize,
    max_total_utf16_units: usize,
) -> Result<(), DocumentError> {
    if max_blocks == 0 || max_blocks > MAX_VIEWPORT_BLOCKS {
        return Err(DocumentError::InvalidViewportRequest(
            "block count is outside the supported range",
        ));
    }
    if !(MIN_RENDER_BLOCK_UTF16_UNITS..=MAX_RENDER_BLOCK_UTF16_UNITS)
        .contains(&max_block_utf16_units)
    {
        return Err(DocumentError::InvalidViewportRequest(
            "block length is outside the supported range",
        ));
    }
    if !(MIN_RENDER_BLOCK_UTF16_UNITS..=MAX_VIEWPORT_UTF16_UNITS).contains(&max_total_utf16_units) {
        return Err(DocumentError::InvalidViewportRequest(
            "total text length is outside the supported range",
        ));
    }
    Ok(())
}

/// Finds a nonempty grapheme-aligned segment within a UTF-16 budget.
///
/// Falls back to a scalar boundary when one pathological grapheme exceeds the
/// bounded lookahead window.
fn bounded_segment_end(sample: &str, max_utf16_units: usize) -> (usize, usize) {
    let mut scalar_bytes = 0;
    let mut scalar_utf16_units = 0;
    let mut observed_utf16_units = 0;
    for (byte_index, character) in sample.char_indices() {
        let character_utf16_units = character.len_utf16();
        observed_utf16_units += character_utf16_units;
        if observed_utf16_units <= max_utf16_units {
            scalar_bytes = byte_index + character.len_utf8();
            scalar_utf16_units = observed_utf16_units;
        }
    }

    if scalar_bytes == 0 || scalar_bytes == sample.len() {
        return (scalar_bytes, scalar_utf16_units);
    }

    let mut grapheme_bytes = 0;
    let mut grapheme_utf16_units = 0;
    for grapheme in sample.graphemes(true) {
        let current_utf16_units = grapheme.encode_utf16().count();
        if grapheme_utf16_units + current_utf16_units > max_utf16_units {
            break;
        }
        grapheme_bytes += grapheme.len();
        grapheme_utf16_units += current_utf16_units;
    }

    if grapheme_bytes == 0 {
        (scalar_bytes, scalar_utf16_units)
    } else {
        (grapheme_bytes, grapheme_utf16_units)
    }
}

#[cfg(test)]
mod tests {
    use std::fs::{OpenOptions, remove_file};
    use std::io::Write;
    use std::sync::atomic::{AtomicU64, Ordering};

    use beautyxt_source_save_core::{
        PACKAGE_HEADER_BYTES, PackageHeader, PackageRecord, RECORD_HEADER_BYTES,
        RECORD_KIND_PAYLOAD, RECORD_KIND_SOURCE,
    };

    use crate::piece_tree::{MAX_PIECE_BYTES, SerializedSegmentVisitor};

    use super::{
        Document, DocumentError, DocumentLineEnding, DocumentMetrics, EditWindowRequest, FindBatch,
        FindDirection, FindMatch, FindRequest, GRAPHEME_LOOKAHEAD_UTF16_UNITS,
        MAX_EDIT_WINDOW_UTF16_UNITS, MAX_FIND_CANDIDATE_UTF16_UNITS, MAX_FIND_QUERY_BYTES,
        MAX_FIND_QUERY_UTF16_UNITS, MAX_RECORD_COUNT, PreviousViewportRequest,
        SourceSaveSegmentCounter, Utf16Range, ViewportPosition, ViewportRequest,
        find_case_insensitive_match,
    };

    const TEMPORARY_SOURCE_PREFIX: &str = "beautyxt-editor-core-source";
    const SOURCE_SAVE_TEST_BYTES: usize = 256 * 1024;
    static NEXT_TEMPORARY_SOURCE_ID: AtomicU64 = AtomicU64::new(0);

    /// Opens one source-backed document from deterministic temporary bytes.
    fn document_from_source(source_bytes: &[u8]) -> Document {
        let source_id = NEXT_TEMPORARY_SOURCE_ID.fetch_add(1, Ordering::Relaxed);
        let source_path = std::env::temp_dir().join(format!(
            "{TEMPORARY_SOURCE_PREFIX}-{}-{source_id}",
            std::process::id()
        ));
        let mut source = OpenOptions::new()
            .read(true)
            .write(true)
            .create_new(true)
            .open(&source_path)
            .expect("unique temporary source should be creatable");
        source
            .write_all(source_bytes)
            .expect("temporary source should be writable");
        source.flush().expect("temporary source should flush");
        let document = Document::open_source(source).expect("valid UTF-8 source should open");
        remove_file(source_path).expect("temporary source path should be removable");
        document
    }

    /// Verifies live facts preserve source format and track replacement text.
    #[test]
    fn reports_live_document_facts() {
        let mut document = document_from_source(b"\xef\xbb\xbfalpha\r\nbeta\rgamma\n");

        let source_metrics = document.metrics();
        assert_eq!(source_metrics.words, 3);
        assert!(source_metrics.has_utf8_bom);
        assert!(source_metrics.has_lf_line_endings);
        assert!(source_metrics.has_crlf_line_endings);
        assert!(source_metrics.has_cr_line_endings);
        assert_eq!(
            source_metrics.inserted_line_ending,
            DocumentLineEnding::CrLf
        );

        let edited_metrics = document
            .replace(
                source_metrics.revision,
                Utf16Range::new(0, source_metrics.utf16_units),
                "new words\n",
            )
            .expect("complete replacement should succeed");

        assert_eq!(edited_metrics.words, 2);
        assert!(edited_metrics.has_utf8_bom);
        assert!(!edited_metrics.has_lf_line_endings);
        assert!(edited_metrics.has_crlf_line_endings);
        assert!(!edited_metrics.has_cr_line_endings);
        assert_eq!(
            edited_metrics.inserted_line_ending,
            DocumentLineEnding::CrLf
        );
    }

    /// Reconstructs one validated test package from its optional source bytes.
    fn reconstruct_source_save_package(package: &[u8], source: Option<&[u8]>) -> Vec<u8> {
        let encoded_header: &[u8; PACKAGE_HEADER_BYTES] = package
            .get(..PACKAGE_HEADER_BYTES)
            .expect("package should contain one complete header")
            .try_into()
            .expect("package header should have fixed width");
        let header = PackageHeader::decode(encoded_header).expect("package header should decode");
        assert_eq!(
            package.len(),
            usize::try_from(header.package_bytes()).expect("package length should fit usize")
        );
        let payload_start =
            usize::try_from(header.payload_start()).expect("payload start should fit usize");
        let record_count =
            usize::try_from(header.record_count()).expect("record count should fit usize");
        let mut output = Vec::with_capacity(
            usize::try_from(header.output_bytes()).expect("output length should fit usize"),
        );

        for record_index in 0..record_count {
            let record_start = PACKAGE_HEADER_BYTES
                .checked_add(
                    record_index
                        .checked_mul(RECORD_HEADER_BYTES)
                        .expect("record table offset should not overflow"),
                )
                .expect("record table offset should not overflow");
            let record_end = record_start
                .checked_add(RECORD_HEADER_BYTES)
                .expect("record table end should not overflow");
            let encoded_record: &[u8; RECORD_HEADER_BYTES] = package
                .get(record_start..record_end)
                .expect("package should contain the complete record table")
                .try_into()
                .expect("package record should have fixed width");
            let record =
                PackageRecord::decode(encoded_record).expect("package record should decode");
            let offset =
                usize::try_from(record.offset()).expect("record offset should fit test address");
            let byte_length = usize::try_from(record.byte_length())
                .expect("record length should fit test address");
            let range_end = offset
                .checked_add(byte_length)
                .expect("record range should not overflow");
            let bytes = match record.kind() {
                RECORD_KIND_SOURCE => source
                    .expect("source record should have a source backing")
                    .get(offset..range_end)
                    .expect("source record should remain within its backing"),
                RECORD_KIND_PAYLOAD => {
                    let payload_record_start = payload_start
                        .checked_add(offset)
                        .expect("payload record start should not overflow");
                    let payload_record_end = payload_start
                        .checked_add(range_end)
                        .expect("payload record end should not overflow");
                    package
                        .get(payload_record_start..payload_record_end)
                        .expect("payload record should remain within its backing")
                }
                _ => panic!("decoded package record should have a known kind"),
            };
            output.extend_from_slice(bytes);
        }

        assert_eq!(
            output.len(),
            usize::try_from(header.output_bytes()).expect("output length should fit usize")
        );
        output
    }

    /// Verifies UTF-8, scalar, UTF-16, and line metrics independently.
    #[test]
    fn reports_unicode_metrics() {
        let document = Document::from_text("a😀\nβ");

        let metrics = document.metrics();

        assert_eq!(metrics.bytes, 8);
        assert_eq!(metrics.serialized_bytes, 8);
        assert_eq!(metrics.chars, 4);
        assert_eq!(metrics.utf16_units, 5);
        assert_eq!(metrics.lines, 2);
        assert!(metrics.is_editable);
    }

    /// Verifies a rejected size increase preserves the revision and exact content.
    #[test]
    fn rejects_unsavable_edits_atomically() {
        let mut document = Document::from_text("a😀β");
        let before = document.metrics();
        let retained = document.snapshot();
        let error = document
            .replace_with_save_limit(0, Utf16Range::new(0, 1), "ab", 7)
            .expect_err("one byte beyond the save ceiling must be rejected");
        assert!(matches!(
            error,
            DocumentError::DocumentTooLargeForSaving {
                bytes: 8,
                max_bytes: 7
            }
        ));
        assert_eq!(document.metrics(), before);
        assert_eq!(retained.metrics(), before);
        let mut output = Vec::new();
        document
            .write_to(&mut output)
            .expect("document should stream");
        assert_eq!(output, "a😀β".as_bytes());

        let accepted = document
            .replace_with_save_limit(0, Utf16Range::new(0, 1), "ab", 8)
            .expect("an edit exactly at the ceiling should succeed");
        assert_eq!(accepted.revision, 1);
        assert_eq!(accepted.serialized_bytes, 8);
    }

    /// Verifies BOM and inserted CRLF bytes count before an edit becomes visible.
    #[test]
    fn save_limit_uses_preserved_source_format() {
        let source = "\u{feff}a\r\nb\r\n";
        let mut document = document_from_source(source.as_bytes());
        let before = document.metrics();
        assert_eq!(before.serialized_bytes, 9);
        assert_eq!(before.bytes, 4);
        assert!(matches!(
            document.replace_with_save_limit(0, Utf16Range::new(4, 4), "\n", 10),
            Err(DocumentError::DocumentTooLargeForSaving {
                bytes: 11,
                max_bytes: 10
            })
        ));
        assert_eq!(document.metrics(), before);
        let accepted = document
            .replace_with_save_limit(0, Utf16Range::new(4, 4), "\n", 11)
            .expect("the exact serialized boundary should remain editable");
        assert_eq!(accepted.serialized_bytes, 11);
        let mut output = Vec::new();
        document
            .write_to(&mut output)
            .expect("document should stream");
        assert_eq!(output, format!("{source}\r\n").as_bytes());
    }

    /// Verifies recovery remains possible when a preexisting document exceeds the ceiling.
    #[test]
    fn save_limit_allows_non_growing_recovery_edits() {
        let mut document = Document::from_text("abcdef");
        let shortened = document
            .replace_with_save_limit(0, Utf16Range::new(5, 6), "", 4)
            .expect("a reduction above the ceiling should succeed");
        assert_eq!(shortened.serialized_bytes, 5);
        let changed = document
            .replace_with_save_limit(1, Utf16Range::new(0, 1), "A", 4)
            .expect("a same-size recovery edit should succeed");
        assert_eq!(changed.serialized_bytes, 5);
        assert!(matches!(
            document.replace_with_save_limit(2, Utf16Range::new(5, 5), "x", 4),
            Err(DocumentError::DocumentTooLargeForSaving { .. })
        ));
        assert_eq!(document.metrics(), changed);
        let recovered = document
            .replace_with_save_limit(2, Utf16Range::new(4, 5), "", 4)
            .expect("a reduction to the ceiling should succeed");
        assert_eq!(recovered.serialized_bytes, 4);
        assert_eq!(recovered.revision, 3);
    }

    /// Verifies both traversal orders preserve overlapping exact matches.
    #[test]
    fn finds_overlapping_matches_in_both_directions() {
        let document = Document::from_text("banana banana");
        let metrics = document.metrics();
        let complete_range = Utf16Range::new(0, metrics.utf16_units);

        let first = document
            .find(FindRequest {
                revision: metrics.revision,
                query: "ana",
                match_case: true,
                candidate_range: complete_range,
                direction: FindDirection::Forward,
                max_candidate_utf16_units: MAX_FIND_CANDIDATE_UTF16_UNITS,
            })
            .expect("first forward match should be found");
        let overlapping = document
            .find(FindRequest {
                revision: metrics.revision,
                query: "ana",
                match_case: true,
                candidate_range: Utf16Range::new(2, metrics.utf16_units),
                direction: FindDirection::Forward,
                max_candidate_utf16_units: MAX_FIND_CANDIDATE_UTF16_UNITS,
            })
            .expect("overlapping forward match should be found");
        let last = document
            .find(FindRequest {
                revision: metrics.revision,
                query: "ana",
                match_case: true,
                candidate_range: complete_range,
                direction: FindDirection::Backward,
                max_candidate_utf16_units: MAX_FIND_CANDIDATE_UTF16_UNITS,
            })
            .expect("last backward match should be found");
        let previous = document
            .find(FindRequest {
                revision: metrics.revision,
                query: "ana",
                match_case: true,
                candidate_range: Utf16Range::new(0, 10),
                direction: FindDirection::Backward,
                max_candidate_utf16_units: MAX_FIND_CANDIDATE_UTF16_UNITS,
            })
            .expect("previous backward match should be found");

        assert_eq!(first.metrics, metrics);
        assert_eq!(
            first.matched,
            Some(FindMatch {
                range: Utf16Range::new(1, 4),
                start: ViewportPosition {
                    revision: metrics.revision,
                    line: 0,
                    utf16_offset: 1,
                },
            })
        );
        assert_eq!(first.remaining_candidate_range, None);
        assert_eq!(
            overlapping.matched.map(|matched| matched.range),
            Some(Utf16Range::new(3, 6))
        );
        assert_eq!(
            last.matched.map(|matched| matched.range),
            Some(Utf16Range::new(10, 13))
        );
        assert_eq!(
            previous.matched.map(|matched| matched.range),
            Some(Utf16Range::new(8, 11))
        );
    }

    /// Verifies supplementary and multiline matches retain global and line-relative offsets.
    #[test]
    fn reports_unicode_find_positions() {
        let document = Document::from_text("α😀x\nbefore😀\nafter");
        let metrics = document.metrics();

        let batch = document
            .find(FindRequest {
                revision: metrics.revision,
                query: "😀\na",
                match_case: true,
                candidate_range: Utf16Range::new(0, metrics.utf16_units),
                direction: FindDirection::Forward,
                max_candidate_utf16_units: MAX_FIND_CANDIDATE_UTF16_UNITS,
            })
            .expect("multiline supplementary query should be found");

        assert_eq!(
            batch.matched,
            Some(FindMatch {
                range: Utf16Range::new(11, 15),
                start: ViewportPosition {
                    revision: metrics.revision,
                    line: 1,
                    utf16_offset: 6,
                },
            })
        );
    }

    /// Verifies source CRLF, CR, and LF bytes expose one normalized search model.
    #[test]
    fn finds_across_normalized_source_line_endings() {
        let document = document_from_source(b"alpha\r\nbeta\rgamma\nomega");
        let metrics = document.metrics();
        let request = FindRequest {
            revision: metrics.revision,
            query: "ha\nbeta\ngam",
            match_case: true,
            candidate_range: Utf16Range::new(0, metrics.utf16_units),
            direction: FindDirection::Forward,
            max_candidate_utf16_units: MAX_FIND_CANDIDATE_UTF16_UNITS,
        };

        let live_batch = document
            .find(request)
            .expect("normalized source match should be found");
        let snapshot_batch = document
            .snapshot()
            .find(request)
            .expect("captured normalized source match should be found");

        assert_eq!(live_batch, snapshot_batch);
        assert_eq!(
            live_batch.matched,
            Some(FindMatch {
                range: Utf16Range::new(3, 14),
                start: ViewportPosition {
                    revision: metrics.revision,
                    line: 0,
                    utf16_offset: 3,
                },
            })
        );
    }

    /// Verifies exact matching crosses source pieces and a new edit backing.
    #[test]
    fn finds_across_source_and_edit_piece_boundaries() {
        let mut source_text = "x".repeat(MAX_PIECE_BYTES - 1);
        source_text.push_str("ac");
        let mut document = document_from_source(source_text.as_bytes());
        let original_metrics = document.metrics();
        let original_snapshot = document.snapshot();
        let near_boundary = Utf16Range::new(MAX_PIECE_BYTES - 2, original_metrics.utf16_units);

        let source_match = original_snapshot
            .find(FindRequest {
                revision: original_metrics.revision,
                query: "ac",
                match_case: true,
                candidate_range: near_boundary,
                direction: FindDirection::Forward,
                max_candidate_utf16_units: 8,
            })
            .expect("cross-source-piece query should be found");
        let edited_metrics = document
            .replace(
                original_metrics.revision,
                Utf16Range::new(MAX_PIECE_BYTES, MAX_PIECE_BYTES),
                "b",
            )
            .expect("boundary edit should succeed");
        let edited_match = document
            .find(FindRequest {
                revision: edited_metrics.revision,
                query: "abc",
                match_case: true,
                candidate_range: Utf16Range::new(MAX_PIECE_BYTES - 2, edited_metrics.utf16_units),
                direction: FindDirection::Forward,
                max_candidate_utf16_units: 8,
            })
            .expect("source-edit-source query should be found");

        assert_eq!(
            source_match.matched.map(|matched| matched.range),
            Some(Utf16Range::new(MAX_PIECE_BYTES - 1, MAX_PIECE_BYTES + 1))
        );
        assert_eq!(
            edited_match.matched.map(|matched| matched.range),
            Some(Utf16Range::new(MAX_PIECE_BYTES - 1, MAX_PIECE_BYTES + 2))
        );
    }

    /// Verifies bounded misses advance without accepting a match at the batch edge.
    #[test]
    fn returns_strict_find_continuations() {
        let forward_document = Document::from_text("xxxxneedle");
        let forward_metrics = forward_document.metrics();
        let first_forward = forward_document
            .find(FindRequest {
                revision: forward_metrics.revision,
                query: "needle",
                match_case: true,
                candidate_range: Utf16Range::new(0, forward_metrics.utf16_units),
                direction: FindDirection::Forward,
                max_candidate_utf16_units: 4,
            })
            .expect("first bounded forward batch should complete");
        let second_forward = forward_document
            .find(FindRequest {
                revision: forward_metrics.revision,
                query: "needle",
                match_case: true,
                candidate_range: first_forward
                    .remaining_candidate_range
                    .expect("forward miss should retain later candidates"),
                direction: FindDirection::Forward,
                max_candidate_utf16_units: 4,
            })
            .expect("second bounded forward batch should complete");

        assert_eq!(first_forward.matched, None);
        assert_eq!(
            first_forward.remaining_candidate_range,
            Some(Utf16Range::new(4, forward_metrics.utf16_units))
        );
        assert_eq!(
            second_forward.matched.map(|matched| matched.range),
            Some(Utf16Range::new(4, 10))
        );

        let backward_document = Document::from_text("needlexxxx");
        let backward_metrics = backward_document.metrics();
        let mut candidate_range = Utf16Range::new(0, backward_metrics.utf16_units);
        let mut observed_lengths = Vec::new();
        let backward_match = loop {
            observed_lengths.push(candidate_range.len());
            let batch = backward_document
                .find(FindRequest {
                    revision: backward_metrics.revision,
                    query: "needle",
                    match_case: true,
                    candidate_range,
                    direction: FindDirection::Backward,
                    max_candidate_utf16_units: 4,
                })
                .expect("bounded backward batch should complete");
            if let Some(matched) = batch.matched {
                break matched;
            }
            let remaining = batch
                .remaining_candidate_range
                .expect("backward miss should retain earlier candidates");
            assert!(remaining.len() < candidate_range.len());
            candidate_range = remaining;
        };

        assert_eq!(observed_lengths, vec![10, 6, 2]);
        assert_eq!(backward_match.range, Utf16Range::new(0, 6));
    }

    /// Verifies allowed match starts may consume bounded lookahead beyond their range.
    #[test]
    fn finds_queries_extending_past_candidate_range() {
        let document = Document::from_text("xxneedle");
        let metrics = document.metrics();

        let forward = document
            .find(FindRequest {
                revision: metrics.revision,
                query: "needle",
                match_case: true,
                candidate_range: Utf16Range::new(2, 3),
                direction: FindDirection::Forward,
                max_candidate_utf16_units: 2,
            })
            .expect("forward lookahead should find the complete query");
        let backward = document
            .find(FindRequest {
                revision: metrics.revision,
                query: "needle",
                match_case: true,
                candidate_range: Utf16Range::new(2, 3),
                direction: FindDirection::Backward,
                max_candidate_utf16_units: 2,
            })
            .expect("backward lookahead should find the complete query");

        assert_eq!(
            forward.matched.map(|matched| matched.range),
            Some(Utf16Range::new(2, 8))
        );
        assert_eq!(forward, backward);
    }

    /// Verifies supplementary scalars remain intact at every continuation edge.
    #[test]
    fn preserves_scalar_aligned_find_continuations() {
        let document = Document::from_text("😀😀x");
        let metrics = document.metrics();
        let mut forward_range = Utf16Range::new(0, metrics.utf16_units);
        let mut forward_ranges = Vec::new();
        while !forward_range.is_empty() {
            let batch = document
                .find(FindRequest {
                    revision: metrics.revision,
                    query: "missing",
                    match_case: true,
                    candidate_range: forward_range,
                    direction: FindDirection::Forward,
                    max_candidate_utf16_units: 2,
                })
                .expect("scalar-aligned forward batch should complete");
            assert_eq!(batch.matched, None);
            let Some(remaining) = batch.remaining_candidate_range else {
                break;
            };
            forward_ranges.push(remaining);
            forward_range = remaining;
        }

        let mut backward_range = Utf16Range::new(0, metrics.utf16_units);
        let mut backward_ranges = Vec::new();
        while !backward_range.is_empty() {
            let batch = document
                .find(FindRequest {
                    revision: metrics.revision,
                    query: "missing",
                    match_case: true,
                    candidate_range: backward_range,
                    direction: FindDirection::Backward,
                    max_candidate_utf16_units: 2,
                })
                .expect("scalar-aligned backward batch should complete");
            assert_eq!(batch.matched, None);
            let Some(remaining) = batch.remaining_candidate_range else {
                break;
            };
            backward_ranges.push(remaining);
            backward_range = remaining;
        }

        assert_eq!(
            forward_ranges,
            vec![Utf16Range::new(2, 5), Utf16Range::new(4, 5)]
        );
        assert_eq!(
            backward_ranges,
            vec![Utf16Range::new(0, 4), Utf16Range::new(0, 2)]
        );

        let mixed_document = Document::from_text("a😀x");
        let mixed_metrics = mixed_document.metrics();
        let forward_mixed = mixed_document
            .find(FindRequest {
                revision: mixed_metrics.revision,
                query: "missing",
                match_case: true,
                candidate_range: Utf16Range::new(0, mixed_metrics.utf16_units),
                direction: FindDirection::Forward,
                max_candidate_utf16_units: 2,
            })
            .expect("mixed-scalar forward batch should complete");
        let backward_mixed = mixed_document
            .find(FindRequest {
                revision: mixed_metrics.revision,
                query: "missing",
                match_case: true,
                candidate_range: Utf16Range::new(0, mixed_metrics.utf16_units),
                direction: FindDirection::Backward,
                max_candidate_utf16_units: 2,
            })
            .expect("mixed-scalar backward batch should complete");

        assert_eq!(
            forward_mixed.remaining_candidate_range,
            Some(Utf16Range::new(1, 4))
        );
        assert_eq!(
            backward_mixed.remaining_candidate_range,
            Some(Utf16Range::new(0, 3))
        );
    }

    /// Verifies find rejects invalid queries, work bounds, ranges, and revisions.
    #[test]
    fn rejects_invalid_find_requests() {
        let document = Document::from_text("a😀z");
        let metrics = document.metrics();
        let valid_request = FindRequest {
            revision: metrics.revision,
            query: "a",
            match_case: true,
            candidate_range: Utf16Range::new(0, metrics.utf16_units),
            direction: FindDirection::Forward,
            max_candidate_utf16_units: 2,
        };

        assert!(matches!(
            document.find(FindRequest {
                revision: metrics.revision + 1,
                ..valid_request
            }),
            Err(DocumentError::StaleRevision {
                expected: 1,
                actual: 0,
            })
        ));
        assert_invalid_find_request(
            document.find(FindRequest {
                query: "",
                match_case: true,
                ..valid_request
            }),
            "query is empty",
        );
        assert_invalid_find_request(
            document.find(FindRequest {
                query: "a\rb",
                match_case: true,
                ..valid_request
            }),
            "query contains a non-normalized carriage return",
        );
        let oversized_byte_query = "ࠀ".repeat(MAX_FIND_QUERY_BYTES / 3 + 1);
        assert_invalid_find_request(
            document.find(FindRequest {
                query: &oversized_byte_query,
                match_case: true,
                ..valid_request
            }),
            "query byte length exceeds the supported range",
        );
        let oversized_utf16_query = "x".repeat(MAX_FIND_QUERY_UTF16_UNITS + 1);
        assert_invalid_find_request(
            document.find(FindRequest {
                query: &oversized_utf16_query,
                match_case: true,
                ..valid_request
            }),
            "query utf-16 length exceeds the supported range",
        );
        for invalid_work_limit in [0, 1, MAX_FIND_CANDIDATE_UTF16_UNITS + 1] {
            assert_invalid_find_request(
                document.find(FindRequest {
                    max_candidate_utf16_units: invalid_work_limit,
                    ..valid_request
                }),
                "candidate work limit is outside the supported range",
            );
        }
        for invalid_range in [Utf16Range::new(3, 2), Utf16Range::new(0, 5)] {
            assert!(matches!(
                document.find(FindRequest {
                    candidate_range: invalid_range,
                    ..valid_request
                }),
                Err(DocumentError::InvalidRange {
                    range,
                    document_utf16_units: 4,
                }) if range == invalid_range
            ));
        }
        assert!(matches!(
            document.find(FindRequest {
                candidate_range: Utf16Range::new(2, 3),
                ..valid_request
            }),
            Err(DocumentError::MisalignedUtf16Offset { offset: 2 })
        ));
    }

    /// Verifies an empty scalar-aligned candidate range is already exhausted.
    #[test]
    fn exhausts_empty_find_candidate_range() {
        let document = Document::from_text("a😀z");
        let metrics = document.metrics();

        let batch = document
            .find(FindRequest {
                revision: metrics.revision,
                query: "a",
                match_case: true,
                candidate_range: Utf16Range::new(3, 3),
                direction: FindDirection::Forward,
                max_candidate_utf16_units: 2,
            })
            .expect("empty aligned candidate range should be exhausted");

        assert_eq!(
            batch,
            FindBatch {
                metrics,
                matched: None,
                remaining_candidate_range: None,
            }
        );
    }

    /// Verifies snapshots retain their revision and text after live edits.
    #[test]
    fn finds_in_immutable_document_snapshots() {
        let mut document = Document::from_text("before needle");
        let original_metrics = document.metrics();
        let snapshot = document.snapshot();
        let edited_metrics = document
            .replace(
                original_metrics.revision,
                Utf16Range::new(0, "before".len()),
                "after",
            )
            .expect("live edit should succeed");

        let snapshot_match = snapshot
            .find(FindRequest {
                revision: original_metrics.revision,
                query: "needle",
                match_case: true,
                candidate_range: Utf16Range::new(0, original_metrics.utf16_units),
                direction: FindDirection::Forward,
                max_candidate_utf16_units: MAX_FIND_CANDIDATE_UTF16_UNITS,
            })
            .expect("captured revision should remain searchable");
        let live_match = document
            .find(FindRequest {
                revision: edited_metrics.revision,
                query: "needle",
                match_case: true,
                candidate_range: Utf16Range::new(0, edited_metrics.utf16_units),
                direction: FindDirection::Forward,
                max_candidate_utf16_units: MAX_FIND_CANDIDATE_UTF16_UNITS,
            })
            .expect("edited revision should be searchable");

        assert_eq!(snapshot_match.metrics, original_metrics);
        assert_eq!(
            snapshot_match.matched.map(|matched| matched.range),
            Some(Utf16Range::new(7, 13))
        );
        assert_eq!(live_match.metrics, edited_metrics);
        assert_eq!(
            live_match.matched.map(|matched| matched.range),
            Some(Utf16Range::new(6, 12))
        );
        assert!(matches!(
            snapshot.find(FindRequest {
                revision: edited_metrics.revision,
                query: "needle",
                match_case: true,
                candidate_range: Utf16Range::new(0, original_metrics.utf16_units),
                direction: FindDirection::Forward,
                max_candidate_utf16_units: MAX_FIND_CANDIDATE_UTF16_UNITS,
            }),
            Err(DocumentError::StaleRevision {
                expected: 1,
                actual: 0,
            })
        ));
        assert!(matches!(
            document.find(FindRequest {
                revision: original_metrics.revision,
                query: "needle",
                match_case: true,
                candidate_range: Utf16Range::new(0, edited_metrics.utf16_units),
                direction: FindDirection::Forward,
                max_candidate_utf16_units: MAX_FIND_CANDIDATE_UTF16_UNITS,
            }),
            Err(DocumentError::StaleRevision {
                expected: 0,
                actual: 1,
            })
        ));
    }

    /// Verifies exact find does not fold case or normalize Unicode composition.
    #[test]
    fn preserves_exact_find_semantics() {
        let document = Document::from_text("Needle e\u{301}");
        let metrics = document.metrics();

        for query in ["needle", "é"] {
            let batch = document
                .find(FindRequest {
                    revision: metrics.revision,
                    query,
                    match_case: true,
                    candidate_range: Utf16Range::new(0, metrics.utf16_units),
                    direction: FindDirection::Forward,
                    max_candidate_utf16_units: MAX_FIND_CANDIDATE_UTF16_UNITS,
                })
                .expect("valid exact query should complete");
            assert_eq!(batch.matched, None);
            assert_eq!(batch.remaining_candidate_range, None);
        }
    }

    /// Verifies default substring Find folds Unicode case without regex syntax.
    #[test]
    fn finds_case_insensitive_unicode_literals() {
        let document = Document::from_text("Needle \u{212a}elvin a+b needle");
        let metrics = document.metrics();
        let complete_range = Utf16Range::new(0, metrics.utf16_units);

        for (query, direction, expected_range) in [
            ("needle", FindDirection::Forward, Utf16Range::new(0, 6)),
            ("EED", FindDirection::Forward, Utf16Range::new(1, 4)),
            ("kELVIN", FindDirection::Forward, Utf16Range::new(7, 13)),
            ("A+B", FindDirection::Forward, Utf16Range::new(14, 17)),
            ("needle", FindDirection::Backward, Utf16Range::new(18, 24)),
        ] {
            let batch = document
                .find(FindRequest {
                    revision: metrics.revision,
                    query,
                    match_case: false,
                    candidate_range: complete_range,
                    direction,
                    max_candidate_utf16_units: MAX_FIND_CANDIDATE_UTF16_UNITS,
                })
                .expect("case-insensitive literal query should complete");
            assert_eq!(
                batch.matched.map(|matched| matched.range),
                Some(expected_range)
            );
        }

        let overlapping_document = Document::from_text("AaA");
        let overlapping_metrics = overlapping_document.metrics();
        for (direction, expected_range) in [
            (FindDirection::Forward, Utf16Range::new(0, 2)),
            (FindDirection::Backward, Utf16Range::new(1, 3)),
        ] {
            let batch = overlapping_document
                .find(FindRequest {
                    revision: overlapping_metrics.revision,
                    query: "aa",
                    match_case: false,
                    candidate_range: Utf16Range::new(0, overlapping_metrics.utf16_units),
                    direction,
                    max_candidate_utf16_units: MAX_FIND_CANDIDATE_UTF16_UNITS,
                })
                .expect("case-insensitive overlapping query should complete");
            assert_eq!(
                batch.matched.map(|matched| matched.range),
                Some(expected_range)
            );
        }
    }

    /// Verifies reverse case folding preserves overlapping matches and bounded starts.
    #[test]
    fn matches_exhaustive_case_folded_find_model() {
        let text = "AaA+\u{212a}kK\nſsΣσς𐐀𐐨ßẞ";
        let boundaries: Vec<_> = text
            .char_indices()
            .map(|(offset, _)| offset)
            .chain(std::iter::once(text.len()))
            .collect();
        for query in [
            "aa", "a+", "kk", "k\ns", "s", "σσ", "𐐀", "ß", "ss", ".", "absent",
        ] {
            let matcher = regex::RegexBuilder::new(&regex::escape(query))
                .case_insensitive(true)
                .build()
                .expect("literal reference matcher should compile");
            let reference_ranges: Vec<_> = boundaries
                .iter()
                .filter_map(|&start| {
                    matcher
                        .find_at(text, start)
                        .filter(|matched| matched.start() == start)
                        .map(|matched| matched.range())
                })
                .collect();
            for &start in &boundaries {
                for &end in boundaries.iter().filter(|&&end| end >= start) {
                    let expected = reference_ranges
                        .iter()
                        .rev()
                        .find(|matched| matched.start >= start && matched.start < end)
                        .map(|matched| matched.start - start..matched.end - start);
                    let actual = find_case_insensitive_match(
                        &text[start..],
                        end - start,
                        query,
                        FindDirection::Backward,
                    )
                    .expect("bounded reverse literal search should complete");
                    assert_eq!(actual, expected, "query {query:?}, range {start}..{end}");
                }
            }
        }
    }

    /// Verifies the longest supported query finds the last dense overlapping match.
    #[test]
    fn finds_last_dense_case_folded_match() {
        let document = Document::from_text(&"a".repeat(MAX_FIND_CANDIDATE_UTF16_UNITS));
        let query = "A".repeat(MAX_FIND_QUERY_UTF16_UNITS);
        let batch = document
            .find(FindRequest {
                revision: 0,
                query: &query,
                match_case: false,
                candidate_range: Utf16Range::new(0, MAX_FIND_CANDIDATE_UTF16_UNITS),
                direction: FindDirection::Backward,
                max_candidate_utf16_units: MAX_FIND_CANDIDATE_UTF16_UNITS,
            })
            .expect("dense overlapping literal search should complete");
        assert_eq!(
            batch.matched.map(|matched| matched.range),
            Some(Utf16Range::new(
                MAX_FIND_CANDIDATE_UTF16_UNITS - MAX_FIND_QUERY_UTF16_UNITS,
                MAX_FIND_CANDIDATE_UTF16_UNITS,
            ))
        );
        assert_eq!(batch.remaining_candidate_range, None);
    }

    /// Verifies every scalar-aligned range and direction against a complete text model.
    #[test]
    fn matches_exhaustive_bounded_find_model() {
        let text = "aba😀aba\nβaba😀";
        let document = Document::from_text(text);
        let scalar_boundaries = utf16_scalar_boundaries(text);
        let queries = ["a", "aba", "😀", "a\nβ", "😀a", "missing"];

        for start_index in 0..scalar_boundaries.len() {
            for end_index in start_index..scalar_boundaries.len() {
                let candidate_range =
                    Utf16Range::new(scalar_boundaries[start_index], scalar_boundaries[end_index]);
                for query in queries {
                    for direction in [FindDirection::Forward, FindDirection::Backward] {
                        let expected = modeled_find(text, query, candidate_range, direction);
                        let actual =
                            complete_bounded_find(&document, query, candidate_range, direction);
                        assert_eq!(
                            actual, expected,
                            "query {query:?}, range {candidate_range:?}, direction {direction:?}",
                        );
                    }
                }
            }
        }
    }

    /// Verifies edits use exact Android UTF-16 boundaries.
    #[test]
    fn replaces_utf16_range() {
        let mut document = Document::from_text("a😀z");

        let metrics = document
            .replace(0, Utf16Range::new(1, 3), "β")
            .expect("valid replacement should succeed");
        let mut output = Vec::new();
        document
            .write_to(&mut output)
            .expect("document should be writable");

        assert_eq!(
            metrics,
            DocumentMetrics {
                revision: 1,
                bytes: 4,
                serialized_bytes: 4,
                chars: 3,
                utf16_units: 3,
                lines: 1,
                words: 1,
                has_utf8_bom: false,
                has_lf_line_endings: false,
                has_crlf_line_endings: false,
                has_cr_line_endings: false,
                inserted_line_ending: DocumentLineEnding::Lf,
                is_editable: true,
            }
        );
        assert_eq!(output, "aβz".as_bytes());
    }

    /// Verifies offsets cannot divide a surrogate pair.
    #[test]
    fn rejects_misaligned_utf16_range() {
        let mut document = Document::from_text("a😀z");

        let error = document
            .replace(0, Utf16Range::new(2, 3), "")
            .expect_err("misaligned replacement should fail");

        assert!(matches!(
            error,
            DocumentError::MisalignedUtf16Offset { offset: 2 }
        ));
    }

    /// Verifies edits cannot introduce non-normalized carriage returns.
    #[test]
    fn rejects_non_normalized_replacement() {
        let mut document = Document::from_text("normalized\ntext");

        let error = document
            .replace(0, Utf16Range::new(0, 0), "carriage\rreturn")
            .expect_err("carriage return should fail");

        assert!(matches!(
            error,
            DocumentError::InvalidReplacement("carriage returns are not normalized")
        ));
        assert_eq!(document.metrics().revision, 0);
    }

    /// Verifies stale writers cannot overwrite a newer document revision.
    #[test]
    fn rejects_stale_revision() {
        let mut document = Document::from_text("abc");
        document
            .replace(0, Utf16Range::new(0, 0), "x")
            .expect("first edit should succeed");

        let error = document
            .replace(0, Utf16Range::new(0, 0), "y")
            .expect_err("stale edit should fail");

        assert!(matches!(
            error,
            DocumentError::StaleRevision {
                expected: 0,
                actual: 1
            }
        ));
    }

    /// Verifies exact line starts use normalized global UTF-16 coordinates.
    #[test]
    fn returns_logical_line_start_offsets() {
        let document = Document::from_text("zero\n😀 one\n");

        assert_eq!(document.line_start_utf16(0, 0).unwrap(), 0);
        assert_eq!(document.line_start_utf16(0, 1).unwrap(), 5);
        assert_eq!(document.line_start_utf16(0, 2).unwrap(), 12);
    }

    /// Verifies line lookup rejects stale revisions and out-of-range lines.
    #[test]
    fn rejects_invalid_logical_line_lookups() {
        let document = Document::from_text("zero\none");

        assert!(matches!(
            document.line_start_utf16(1, 0),
            Err(DocumentError::StaleRevision {
                expected: 1,
                actual: 0
            })
        ));
        assert!(matches!(
            document.line_start_utf16(0, 2),
            Err(DocumentError::InvalidViewportRequest(
                "logical line is outside the document"
            ))
        ));
    }

    /// Verifies an edit window remains centered, bounded, and globally tagged.
    #[test]
    fn returns_centered_edit_window() {
        let document = Document::from_text("0123456789abcdefghij");

        let snapshot = document
            .edit_window(EditWindowRequest {
                revision: 0,
                selection: Utf16Range::new(10, 10),
                max_utf16_units: 8,
            })
            .expect("bounded edit window should succeed");

        assert_eq!(snapshot.metrics, document.metrics());
        assert_eq!(snapshot.range, Utf16Range::new(6, 14));
        assert_eq!(snapshot.selection, Utf16Range::new(10, 10));
        assert_eq!(snapshot.text, "6789abcd");
        assert_eq!(
            snapshot.start,
            ViewportPosition {
                revision: 0,
                line: 0,
                utf16_offset: 6,
            }
        );
        assert!(snapshot.has_previous);
        assert!(snapshot.has_next);
    }

    /// Verifies a terminal edit window uses spare capacity before the selection.
    #[test]
    fn fills_edit_window_near_document_end() {
        let document = Document::from_text("0123456789");

        let snapshot = document
            .edit_window(EditWindowRequest {
                revision: 0,
                selection: Utf16Range::new(9, 9),
                max_utf16_units: 6,
            })
            .expect("terminal edit window should succeed");

        assert_eq!(snapshot.range, Utf16Range::new(4, 10));
        assert_eq!(snapshot.text, "456789");
        assert!(snapshot.has_previous);
        assert!(!snapshot.has_next);
    }

    /// Verifies edit window starts carry exact logical line coordinates.
    #[test]
    fn reports_edit_window_line_position() {
        let document = Document::from_text("zero\none\ntwo");

        let snapshot = document
            .edit_window(EditWindowRequest {
                revision: 0,
                selection: Utf16Range::new(10, 10),
                max_utf16_units: 3,
            })
            .expect("multiline edit window should succeed");

        assert_eq!(snapshot.range, Utf16Range::new(9, 12));
        assert_eq!(snapshot.text, "two");
        assert_eq!(
            snapshot.start,
            ViewportPosition {
                revision: 0,
                line: 2,
                utf16_offset: 0,
            }
        );
    }

    /// Verifies ordinary multiline windows omit partial lines at both edges.
    #[test]
    fn aligns_edit_window_to_logical_lines() {
        let text = "partial0\nalpha\nbravo\ncharlie\npartial4";
        let document = Document::from_text(text);
        let selection = text.find("bravo").expect("selection text should exist") + 2;

        let snapshot = document
            .edit_window(EditWindowRequest {
                revision: 0,
                selection: Utf16Range::new(selection, selection),
                max_utf16_units: 20,
            })
            .expect("multiline edit window should succeed");

        assert_eq!(snapshot.range, Utf16Range::new(9, 21));
        assert_eq!(snapshot.text, "alpha\nbravo\n");
        assert_eq!(snapshot.start.utf16_offset, 0);
        assert!(snapshot.has_previous);
        assert!(snapshot.has_next);
    }

    /// Verifies a selected long line retains bounded scalar-safe fallbacks.
    #[test]
    fn keeps_partial_edges_inside_a_selected_long_line() {
        let document = Document::from_text("before\n0123456789abcdefghij\nafter");

        let snapshot = document
            .edit_window(EditWindowRequest {
                revision: 0,
                selection: Utf16Range::new(17, 17),
                max_utf16_units: 8,
            })
            .expect("long-line edit window should succeed");

        assert_eq!(snapshot.range, Utf16Range::new(13, 21));
        assert_eq!(snapshot.text, "6789abcd");
        assert_eq!(snapshot.start.line, 1);
        assert_eq!(snapshot.start.utf16_offset, 6);
    }

    /// Verifies ordinary combining clusters are not divided at window edges.
    #[test]
    fn prefers_grapheme_aligned_edit_window_edges() {
        let document = Document::from_text("0123a\u{301}56789");

        let snapshot = document
            .edit_window(EditWindowRequest {
                revision: 0,
                selection: Utf16Range::new(8, 8),
                max_utf16_units: 6,
            })
            .expect("grapheme-aligned edit window should succeed");

        assert_eq!(snapshot.range, Utf16Range::new(6, 11));
        assert_eq!(snapshot.text, "56789");
        assert_eq!(snapshot.text.encode_utf16().count(), 5);
    }

    /// Verifies window bounds never divide a Unicode scalar's surrogate pair.
    #[test]
    fn aligns_edit_window_around_surrogate_pairs() {
        let document = Document::from_text("012😀456789");

        let snapshot = document
            .edit_window(EditWindowRequest {
                revision: 0,
                selection: Utf16Range::new(7, 7),
                max_utf16_units: 6,
            })
            .expect("scalar-aligned edit window should succeed");

        assert_eq!(snapshot.range, Utf16Range::new(5, 10));
        assert_eq!(snapshot.text, "45678");
        assert!(snapshot.text.encode_utf16().count() <= 6);
    }

    /// Verifies pathological clusters fall back to hard-bounded scalar edges.
    #[test]
    fn bounds_edit_window_inside_pathological_grapheme() {
        let mut text = String::from("a");
        text.extend(std::iter::repeat_n('\u{301}', 200));
        text.push('z');
        let document = Document::from_text(&text);

        let snapshot = document
            .edit_window(EditWindowRequest {
                revision: 0,
                selection: Utf16Range::new(100, 100),
                max_utf16_units: 32,
            })
            .expect("pathological edit window should remain bounded");

        assert_eq!(snapshot.range, Utf16Range::new(84, 116));
        assert_eq!(snapshot.text.encode_utf16().count(), 32);
        assert!(snapshot.has_previous);
        assert!(snapshot.has_next);
    }

    /// Verifies edit window requests reject stale, unsafe, and excessive input.
    #[test]
    fn rejects_invalid_edit_window_requests() {
        let document = Document::from_text("a😀z");
        let requests = [
            EditWindowRequest {
                revision: 1,
                selection: Utf16Range::new(0, 0),
                max_utf16_units: 4,
            },
            EditWindowRequest {
                revision: 0,
                selection: Utf16Range::new(2, 2),
                max_utf16_units: 4,
            },
            EditWindowRequest {
                revision: 0,
                selection: Utf16Range::new(0, 4),
                max_utf16_units: 3,
            },
            EditWindowRequest {
                revision: 0,
                selection: Utf16Range::new(0, 0),
                max_utf16_units: 0,
            },
            EditWindowRequest {
                revision: 0,
                selection: Utf16Range::new(0, 0),
                max_utf16_units: MAX_EDIT_WINDOW_UTF16_UNITS + 1,
            },
        ];

        let errors = requests.map(|request| {
            document
                .edit_window(request)
                .expect_err("invalid edit window should fail")
        });

        assert!(matches!(
            errors[0],
            DocumentError::StaleRevision {
                expected: 1,
                actual: 0
            }
        ));
        assert!(matches!(
            errors[1],
            DocumentError::MisalignedUtf16Offset { offset: 2 }
        ));
        assert!(matches!(
            errors[2],
            DocumentError::InvalidEditWindowRequest("selection length exceeds the window limit")
        ));
        assert!(matches!(
            errors[3],
            DocumentError::InvalidEditWindowRequest("window length is outside the supported range")
        ));
        assert!(matches!(
            errors[4],
            DocumentError::InvalidEditWindowRequest("window length is outside the supported range")
        ));
    }

    /// Verifies diverse scalar-aligned selections preserve every window invariant.
    #[test]
    fn preserves_edit_window_invariants_across_unicode_boundaries() {
        let text = "a😀e\u{301}\nβ👩\u{200d}💻z";
        let document = Document::from_text(text);
        let document_utf16_units = text.encode_utf16().count();
        let mut utf16_boundaries = vec![0];
        let mut utf16_offset = 0;
        for character in text.chars() {
            utf16_offset += character.len_utf16();
            utf16_boundaries.push(utf16_offset);
        }

        for &selection_start in &utf16_boundaries {
            for &selection_end in utf16_boundaries
                .iter()
                .filter(|boundary| **boundary >= selection_start)
            {
                let selection = Utf16Range::new(selection_start, selection_end);
                let minimum_window_utf16_units = selection.len().max(1);
                for max_utf16_units in minimum_window_utf16_units..=document_utf16_units {
                    let snapshot = document
                        .edit_window(EditWindowRequest {
                            revision: 0,
                            selection,
                            max_utf16_units,
                        })
                        .expect("scalar-aligned edit window should succeed");
                    let expected_text = &text[byte_offset_at_utf16(text, snapshot.range.start)
                        ..byte_offset_at_utf16(text, snapshot.range.end)];

                    assert!(snapshot.range.start <= selection.start);
                    assert!(snapshot.range.end >= selection.end);
                    assert!(snapshot.range.len() <= max_utf16_units);
                    assert_eq!(snapshot.selection, selection);
                    assert_eq!(snapshot.text, expected_text);
                    assert_eq!(snapshot.text.encode_utf16().count(), snapshot.range.len());
                    assert_eq!(snapshot.has_previous, snapshot.range.start > 0);
                    assert_eq!(snapshot.has_next, snapshot.range.end < document_utf16_units);
                }
            }
        }
    }

    /// Verifies long logical lines resume without exceeding block limits.
    #[test]
    fn chunks_long_logical_line() {
        let document = Document::from_text("abcdefghij\nnext");
        let request = ViewportRequest {
            start: ViewportPosition::default(),
            max_blocks: 2,
            max_block_utf16_units: 4,
            max_total_utf16_units: 8,
        };

        let snapshot = document
            .viewport(request)
            .expect("bounded viewport should succeed");

        assert_eq!(snapshot.blocks.len(), 2);
        assert_eq!(snapshot.blocks[0].text, "abcd");
        assert!(!snapshot.blocks[0].continues_at_start);
        assert!(snapshot.blocks[0].continues_at_end);
        assert_eq!(snapshot.blocks[1].text, "efgh");
        assert!(snapshot.blocks[1].continues_at_start);
        assert_eq!(
            snapshot.next,
            Some(ViewportPosition {
                revision: 0,
                line: 0,
                utf16_offset: 8
            })
        );
    }

    /// Verifies captured viewport traversal remains pinned after later edits.
    #[test]
    fn reads_immutable_snapshot_viewports_after_document_edits() {
        let mut document = Document::from_text("first\nsecond\nthird");
        let snapshot = document.snapshot();
        document
            .replace(0, Utf16Range::new(0, 5), "changed")
            .expect("later document edit should succeed");

        let first = snapshot
            .viewport(ViewportRequest {
                start: ViewportPosition::default(),
                max_blocks: 2,
                max_block_utf16_units: 16,
                max_total_utf16_units: 16,
            })
            .expect("captured viewport should remain readable");
        let second = snapshot
            .viewport(ViewportRequest {
                start: first.next.expect("captured viewport should continue"),
                max_blocks: 2,
                max_block_utf16_units: 16,
                max_total_utf16_units: 16,
            })
            .expect("captured continuation should remain readable");

        assert_eq!(first.metrics.revision, 0);
        assert_eq!(first.blocks[0].text, "first");
        assert_eq!(first.blocks[1].text, "second");
        assert_eq!(second.blocks[0].text, "third");
        assert!(second.next.is_none());
    }

    /// Verifies viewport pagination reaches later logical lines exactly.
    #[test]
    fn resumes_viewport_on_next_line() {
        let document = Document::from_text("first\nsecond\nthird");
        let request = ViewportRequest {
            max_blocks: 2,
            max_block_utf16_units: 16,
            max_total_utf16_units: 16,
            ..ViewportRequest::default()
        };

        let snapshot = document
            .viewport(request)
            .expect("first viewport should succeed");
        let next = snapshot.next.expect("more lines should remain");
        let next_snapshot = document
            .viewport(ViewportRequest {
                start: next,
                max_blocks: 2,
                max_block_utf16_units: 16,
                max_total_utf16_units: 16,
            })
            .expect("second viewport should succeed");

        assert_eq!(snapshot.blocks[0].text, "first");
        assert_eq!(snapshot.blocks[1].text, "second");
        assert_eq!(snapshot.previous, None);
        assert_eq!(next_snapshot.blocks[0].text, "third");
        assert_eq!(next_snapshot.previous, Some(next));
        assert_eq!(next_snapshot.next, None);
    }

    /// Verifies reverse paging returns contiguous content before a forward anchor.
    #[test]
    fn returns_contiguous_content_before_forward_anchor() {
        let document = Document::from_text("first\nsecond\nthird\nfourth");
        let limits = ViewportRequest {
            max_blocks: 2,
            max_block_utf16_units: 16,
            max_total_utf16_units: 16,
            ..ViewportRequest::default()
        };
        let first = document
            .viewport(limits)
            .expect("first viewport should succeed");
        let second_start = first.next.expect("second page should remain");
        let second = document
            .viewport(ViewportRequest {
                start: second_start,
                ..limits
            })
            .expect("second viewport should succeed");

        let previous = document
            .previous_viewport(PreviousViewportRequest {
                end: second
                    .previous
                    .expect("second page should have a previous anchor"),
                max_blocks: limits.max_blocks,
                max_block_utf16_units: limits.max_block_utf16_units,
                max_total_utf16_units: limits.max_total_utf16_units,
            })
            .expect("previous viewport should succeed");

        assert_eq!(
            previous
                .blocks
                .iter()
                .map(|block| block.text.as_str())
                .collect::<Vec<_>>(),
            vec!["first", "second"]
        );
        assert_eq!(
            previous.blocks.first().map(|block| block.logical_line),
            Some(0)
        );
        assert_eq!(
            previous.blocks.last().map(|block| block.logical_line),
            Some(1)
        );
        assert_eq!(previous.previous, None);
        assert_eq!(previous.next, Some(second_start));
    }

    /// Verifies reverse paging chunks long lines without gaps or overlap.
    #[test]
    fn reverses_chunks_inside_one_long_logical_line() {
        let document = Document::from_text("abcdefghij");
        let limits = ViewportRequest {
            max_blocks: 2,
            max_block_utf16_units: 4,
            max_total_utf16_units: 8,
            ..ViewportRequest::default()
        };
        let first = document
            .viewport(limits)
            .expect("first viewport should succeed");
        let next = first.next.expect("line suffix should remain");

        let previous = document
            .previous_viewport(PreviousViewportRequest {
                end: next,
                max_blocks: limits.max_blocks,
                max_block_utf16_units: limits.max_block_utf16_units,
                max_total_utf16_units: limits.max_total_utf16_units,
            })
            .expect("previous viewport should succeed");

        assert_eq!(
            previous
                .blocks
                .iter()
                .map(|block| block.text.as_str())
                .collect::<String>(),
            "abcdefgh"
        );
        assert_eq!(
            previous
                .blocks
                .first()
                .map(|block| block.global_utf16_start),
            Some(0)
        );
        assert_eq!(
            previous.blocks.last().map(|block| block.global_utf16_end),
            Some(8)
        );
        assert_eq!(previous.previous, None);
        assert_eq!(previous.next, Some(next));
    }

    /// Verifies reverse paging preserves empty logical lines as bounded blocks.
    #[test]
    fn reverses_across_an_empty_logical_line() {
        let document = Document::from_text("first\n\nthird");
        let limits = ViewportRequest {
            max_blocks: 2,
            max_block_utf16_units: 16,
            max_total_utf16_units: 16,
            ..ViewportRequest::default()
        };
        let first = document
            .viewport(limits)
            .expect("first viewport should succeed");
        let next = first.next.expect("third line should remain");

        let previous = document
            .previous_viewport(PreviousViewportRequest {
                end: next,
                max_blocks: limits.max_blocks,
                max_block_utf16_units: limits.max_block_utf16_units,
                max_total_utf16_units: limits.max_total_utf16_units,
            })
            .expect("previous viewport should succeed");

        assert!(previous.blocks == first.blocks);
        assert_eq!(previous.blocks[1].text, "");
    }

    /// Verifies reverse paging preserves scalar and nearby grapheme boundaries.
    #[test]
    fn prefers_safe_unicode_boundaries_while_reversing() {
        let text = "a\u{301}b😀c";
        let document = Document::from_text(text);
        let document_end = ViewportPosition {
            revision: 0,
            line: 0,
            utf16_offset: text.encode_utf16().count(),
        };

        let snapshot = document
            .previous_viewport(PreviousViewportRequest {
                end: document_end,
                max_blocks: 2,
                max_block_utf16_units: 2,
                max_total_utf16_units: 4,
            })
            .expect("Unicode reverse viewport should succeed");

        assert_eq!(
            snapshot
                .blocks
                .iter()
                .map(|block| block.text.as_str())
                .collect::<Vec<_>>(),
            vec!["😀", "c"]
        );
        assert_eq!(
            snapshot.previous,
            Some(ViewportPosition {
                revision: 0,
                line: 0,
                utf16_offset: 3,
            })
        );
        assert_eq!(snapshot.next, Some(document_end));
    }

    /// Verifies a residual budget cannot expose a noncanonical reverse anchor.
    #[test]
    fn canonicalizes_reverse_anchor_before_an_unfittable_scalar() {
        let preceding_line = "x".repeat(3);
        let text = format!("😀\n{preceding_line}\nnext");
        let document = Document::from_text(&text);
        let end = ViewportPosition {
            revision: 0,
            line: 2,
            utf16_offset: 0,
        };

        let snapshot = document
            .previous_viewport(PreviousViewportRequest {
                end,
                max_blocks: 2,
                max_block_utf16_units: 4,
                max_total_utf16_units: 4,
            })
            .expect("bounded reverse viewport should remain canonical");

        assert_eq!(snapshot.blocks.len(), 1);
        assert_eq!(snapshot.blocks[0].logical_line, 1);
        assert_eq!(snapshot.blocks[0].text, preceding_line);
        assert_eq!(
            snapshot.previous,
            Some(ViewportPosition {
                revision: 0,
                line: 1,
                utf16_offset: 0,
            })
        );
        assert_eq!(snapshot.next, Some(end));
    }

    /// Verifies pathological graphemes fall back to bounded scalar edges in reverse.
    #[test]
    fn bounds_reverse_pages_inside_pathological_grapheme() {
        let combining_mark = "\u{301}";
        let text = format!(
            "x{}y",
            combining_mark.repeat(GRAPHEME_LOOKAHEAD_UTF16_UNITS * 2)
        );
        let document = Document::from_text(&text);
        let document_end = ViewportPosition {
            revision: 0,
            line: 0,
            utf16_offset: text.encode_utf16().count(),
        };
        let max_block_utf16_units = 16;

        let snapshot = document
            .previous_viewport(PreviousViewportRequest {
                end: document_end,
                max_blocks: 2,
                max_block_utf16_units,
                max_total_utf16_units: max_block_utf16_units * 2,
            })
            .expect("pathological reverse viewport should remain bounded");

        assert_eq!(snapshot.blocks.len(), 2);
        assert_eq!(
            snapshot.blocks[0].text,
            combining_mark.repeat(max_block_utf16_units)
        );
        assert_eq!(snapshot.blocks[1].text, "y");
        assert!(
            snapshot
                .blocks
                .iter()
                .all(|block| { block.text.encode_utf16().count() <= max_block_utf16_units })
        );
        assert!(snapshot.previous.is_some());
        assert_eq!(snapshot.next, Some(document_end));
    }

    /// Verifies reverse viewport anchors cannot cross document revisions.
    #[test]
    fn rejects_stale_previous_viewport_position() {
        let mut document = Document::from_text("first\nsecond");
        let end = ViewportPosition {
            revision: 0,
            line: 1,
            utf16_offset: 0,
        };
        document
            .replace(0, Utf16Range::new(0, 0), "changed\n")
            .expect("edit should succeed");

        let result = document.previous_viewport(PreviousViewportRequest {
            end,
            max_blocks: 1,
            max_block_utf16_units: 16,
            max_total_utf16_units: 16,
        });
        let Err(error) = result else {
            panic!("stale previous cursor should fail");
        };

        assert!(matches!(
            error,
            DocumentError::StaleViewport {
                expected: 0,
                actual: 1
            }
        ));
    }

    /// Verifies reverse requests reject excessive, out-of-range, and unsafe input.
    #[test]
    fn rejects_invalid_previous_viewport_requests() {
        let document = Document::from_text("a😀z");
        let requests = [
            PreviousViewportRequest {
                end: ViewportPosition {
                    revision: 0,
                    line: 0,
                    utf16_offset: 4,
                },
                max_blocks: 0,
                max_block_utf16_units: 16,
                max_total_utf16_units: 16,
            },
            PreviousViewportRequest {
                end: ViewportPosition {
                    revision: 0,
                    line: 1,
                    utf16_offset: 0,
                },
                max_blocks: 1,
                max_block_utf16_units: 16,
                max_total_utf16_units: 16,
            },
            PreviousViewportRequest {
                end: ViewportPosition {
                    revision: 0,
                    line: 0,
                    utf16_offset: 2,
                },
                max_blocks: 1,
                max_block_utf16_units: 16,
                max_total_utf16_units: 16,
            },
        ];
        let errors = requests.map(|request| {
            let Err(error) = document.previous_viewport(request) else {
                panic!("invalid previous viewport should fail");
            };
            error
        });

        assert!(matches!(
            errors[0],
            DocumentError::InvalidViewportRequest("block count is outside the supported range")
        ));
        assert!(matches!(
            errors[1],
            DocumentError::InvalidViewportRequest("viewport line is outside the document")
        ));
        assert!(matches!(
            errors[2],
            DocumentError::MisalignedUtf16Offset { offset: 2 }
        ));
    }

    /// Verifies reverse paging rejects the document origin as an end anchor.
    #[test]
    fn rejects_previous_viewport_at_document_origin() {
        let document = Document::from_text("text");

        let result = document.previous_viewport(PreviousViewportRequest {
            end: ViewportPosition::default(),
            max_blocks: 1,
            max_block_utf16_units: 16,
            max_total_utf16_units: 16,
        });
        let Err(error) = result else {
            panic!("document origin should not have a previous page");
        };

        assert!(matches!(
            error,
            DocumentError::InvalidViewportRequest(
                "previous viewport end is at the document origin"
            )
        ));
    }

    /// Verifies a trailing single-unit budget does not emit empty duplicate blocks.
    #[test]
    fn stops_before_surrogate_pair_exceeds_budget() {
        let document = Document::from_text("a😀");
        let request = ViewportRequest {
            max_blocks: 3,
            max_block_utf16_units: 2,
            max_total_utf16_units: 2,
            ..ViewportRequest::default()
        };

        let snapshot = document
            .viewport(request)
            .expect("bounded viewport should succeed");

        assert_eq!(snapshot.blocks.len(), 1);
        assert_eq!(snapshot.blocks[0].text, "a");
        assert_eq!(
            snapshot.next,
            Some(ViewportPosition {
                revision: 0,
                line: 0,
                utf16_offset: 1
            })
        );
    }

    /// Verifies an identical replacement preserves the current revision.
    #[test]
    fn preserves_revision_for_no_op_replacement() {
        let mut document = Document::from_text("same");

        let metrics = document
            .replace(0, Utf16Range::new(0, 4), "same")
            .expect("identical replacement should succeed");

        assert_eq!(metrics, document.metrics());
        assert_eq!(metrics.revision, 0);
    }

    /// Verifies viewport cursors cannot cross document revisions.
    #[test]
    fn rejects_stale_viewport_position() {
        let mut document = Document::from_text("first\nsecond");
        let next = document
            .viewport(ViewportRequest {
                max_blocks: 1,
                max_block_utf16_units: 16,
                max_total_utf16_units: 16,
                ..ViewportRequest::default()
            })
            .expect("first viewport should succeed")
            .next
            .expect("second line should remain");
        document
            .replace(0, Utf16Range::new(0, 0), "changed\n")
            .expect("edit should succeed");

        let result = document.viewport(ViewportRequest {
            start: next,
            max_blocks: 1,
            max_block_utf16_units: 16,
            max_total_utf16_units: 16,
        });
        let Err(error) = result else {
            panic!("stale cursor should fail");
        };

        assert!(matches!(
            error,
            DocumentError::StaleViewport {
                expected: 0,
                actual: 1
            }
        ));
    }

    /// Verifies ordinary extended grapheme clusters remain in one block.
    #[test]
    fn preserves_grapheme_cluster_boundaries() {
        let document = Document::from_text("a\u{301}b");

        let snapshot = document
            .viewport(ViewportRequest {
                max_blocks: 2,
                max_block_utf16_units: 2,
                max_total_utf16_units: 4,
                ..ViewportRequest::default()
            })
            .expect("viewport should succeed");

        assert_eq!(snapshot.blocks.len(), 2);
        assert_eq!(snapshot.blocks[0].text, "a\u{301}");
        assert_eq!(snapshot.blocks[1].text, "b");
    }

    /// Verifies a short block does not retain a large discarded grapheme probe.
    #[test]
    fn releases_unused_render_block_capacity() {
        const BLOCK_UNITS: usize = 1024;
        let text = format!("ab{}", "\u{301}".repeat(BLOCK_UNITS * 2));
        let document = Document::from_text(&text);
        let snapshot = document
            .viewport(ViewportRequest {
                max_blocks: 1,
                max_block_utf16_units: BLOCK_UNITS,
                max_total_utf16_units: BLOCK_UNITS,
                ..ViewportRequest::default()
            })
            .expect("pathological grapheme probe should remain bounded");

        assert_eq!(snapshot.blocks[0].text, "a");
        assert_eq!(snapshot.blocks[0].text.capacity(), "a".len());
    }

    /// Verifies a cursor at a completed line advances without an empty block.
    #[test]
    fn canonicalizes_position_at_nonempty_line_end() {
        let document = Document::from_text("first\nsecond");

        let snapshot = document
            .viewport(ViewportRequest {
                start: ViewportPosition {
                    revision: 0,
                    line: 0,
                    utf16_offset: 5,
                },
                max_blocks: 1,
                max_block_utf16_units: 16,
                max_total_utf16_units: 16,
            })
            .expect("viewport should succeed");

        assert_eq!(snapshot.blocks.len(), 1);
        assert_eq!(snapshot.blocks[0].logical_line, 1);
        assert_eq!(snapshot.blocks[0].text, "second");
        assert_eq!(
            snapshot.previous,
            Some(ViewportPosition {
                revision: 0,
                line: 1,
                utf16_offset: 0,
            })
        );
    }

    /// Verifies immutable snapshots preserve the revision they captured.
    #[test]
    fn preserves_immutable_save_snapshot() {
        let mut document = Document::from_text("before");
        let snapshot = document.snapshot();
        document
            .replace(0, Utf16Range::new(0, 6), "after")
            .expect("edit should succeed");
        let mut output = Vec::new();

        snapshot
            .write_to(&mut output)
            .expect("snapshot should be writable");

        assert_eq!(snapshot.metrics().revision, 0);
        assert_eq!(output, b"before");
        assert_eq!(document.metrics().revision, 1);
    }

    /// Verifies one sparse edit prepares only its live replacement byte.
    #[test]
    fn prepares_sparse_source_save_without_copying_untouched_bytes() {
        let source = vec![b'x'; SOURCE_SAVE_TEST_BYTES];
        let mut document = document_from_source(&source);
        let middle = SOURCE_SAVE_TEST_BYTES / 2;
        let edited_metrics = document
            .replace(0, Utf16Range::new(middle, middle + 1), "y")
            .expect("middle source replacement should succeed");
        let prepared = document
            .snapshot()
            .prepare_source_save()
            .expect("sparse source save should prepare");
        let package_metrics = prepared.package_metrics();
        let expected_source_bytes =
            u64::try_from(SOURCE_SAVE_TEST_BYTES).expect("test source length should fit u64");

        assert_eq!(prepared.document_metrics(), edited_metrics);
        assert_eq!(package_metrics.output_bytes, expected_source_bytes);
        assert_eq!(package_metrics.payload_bytes, 1);
        assert_eq!(package_metrics.source_bytes, Some(expected_source_bytes));
        assert_eq!(package_metrics.record_count, 3);
        assert!(package_metrics.package_bytes < package_metrics.output_bytes);
        let mut package = Vec::new();
        prepared
            .write_package(&mut package)
            .expect("sparse package should write");
        let mut expected_output = source.clone();
        expected_output[middle] = b'y';
        assert_eq!(
            reconstruct_source_save_package(&package, Some(&source)),
            expected_output
        );
        assert!(prepared.into_source().is_some());
    }

    /// Verifies small imported edits avoid a larger sparse package.
    #[test]
    fn falls_back_to_full_payload_when_sparse_overhead_is_larger() {
        let source = b"abc";
        let mut document = document_from_source(source);
        let edited_metrics = document
            .replace(0, Utf16Range::new(1, 2), "y")
            .expect("small imported edit should succeed");
        let prepared = document
            .snapshot()
            .prepare_source_save()
            .expect("small imported source save should prepare");
        let package_metrics = prepared.package_metrics();

        assert_eq!(prepared.document_metrics(), edited_metrics);
        assert_eq!(package_metrics.output_bytes, 3);
        assert_eq!(package_metrics.payload_bytes, 3);
        assert_eq!(package_metrics.source_bytes, None);
        assert_eq!(package_metrics.record_count, 1);
        let mut package = Vec::new();
        prepared
            .write_package(&mut package)
            .expect("full-payload package should write");
        assert_eq!(reconstruct_source_save_package(&package, None), b"ayc");
        assert!(prepared.into_source().is_none());
    }

    /// Verifies sparse planning stops immediately after its record bound.
    #[test]
    fn stops_sparse_planning_at_record_limit() {
        let mut counter = SourceSaveSegmentCounter::default();
        for record_offset in 0..MAX_RECORD_COUNT {
            counter
                .visit_source(record_offset, 1)
                .expect("record within the sparse bound should count");
        }

        counter
            .visit_source(MAX_RECORD_COUNT, 1)
            .expect_err("record beyond the sparse bound should stop planning");

        assert_eq!(counter.record_count, MAX_RECORD_COUNT);
        assert!(counter.record_limit_reached);
    }

    /// Verifies prepared package ownership remains bound to its captured revision.
    #[test]
    fn preserves_prepared_source_save_revision() {
        let source = vec![b'x'; SOURCE_SAVE_TEST_BYTES];
        let mut document = document_from_source(&source);
        let first_metrics = document
            .replace(0, Utf16Range::new(1, 2), "a")
            .expect("first source replacement should succeed");
        let prepared = document
            .snapshot()
            .prepare_source_save()
            .expect("first edited revision should prepare");
        document
            .replace(first_metrics.revision, Utf16Range::new(2, 3), "b")
            .expect("later source replacement should succeed");
        let mut package = Vec::new();
        prepared
            .write_package(&mut package)
            .expect("prepared revision package should write");
        let mut expected_output = source.clone();
        expected_output[1] = b'a';

        assert_eq!(prepared.document_metrics(), first_metrics);
        assert_eq!(prepared.package_metrics().payload_bytes, 1);
        assert_eq!(prepared.package_metrics().record_count, 3);
        assert_eq!(
            reconstruct_source_save_package(&package, Some(&source)),
            expected_output
        );
        assert_eq!(document.metrics().revision, first_metrics.revision + 1);
        assert!(prepared.into_source().is_some());
    }

    /// Verifies edit-only revisions use one complete bounded payload record.
    #[test]
    fn falls_back_to_one_payload_without_a_source() {
        let text = "alpha\nbeta";
        let prepared = Document::from_text(text)
            .snapshot()
            .prepare_source_save()
            .expect("edit-only source save should prepare");
        let package_metrics = prepared.package_metrics();
        let expected_bytes = u64::try_from(text.len()).expect("test text length should fit u64");

        assert_eq!(package_metrics.output_bytes, expected_bytes);
        assert_eq!(package_metrics.payload_bytes, expected_bytes);
        assert_eq!(package_metrics.source_bytes, None);
        assert_eq!(package_metrics.record_count, 1);
        let mut package = Vec::new();
        prepared
            .write_package(&mut package)
            .expect("complete payload package should write");
        assert_eq!(
            reconstruct_source_save_package(&package, None),
            text.as_bytes()
        );
        assert!(prepared.into_source().is_none());
    }

    /// Verifies inserted newlines contribute their exact selected source form.
    #[test]
    fn counts_serialized_source_save_line_endings() {
        let source_text = format!(
            "\u{feff}{}",
            "line\r\n".repeat(SOURCE_SAVE_TEST_BYTES / "line\r\n".len())
        );
        let mut document = document_from_source(source_text.as_bytes());
        let initial_metrics = document.metrics();
        document
            .replace(
                initial_metrics.revision,
                Utf16Range::new(initial_metrics.utf16_units, initial_metrics.utf16_units),
                "tail\n",
            )
            .expect("trailing normalized line should insert");
        let prepared = document
            .snapshot()
            .prepare_source_save()
            .expect("line-ending-preserving source save should prepare");
        let mut expected_output = Vec::new();
        document
            .write_to(&mut expected_output)
            .expect("edited source should serialize");
        let mut package = Vec::new();
        prepared
            .write_package(&mut package)
            .expect("line-ending-preserving package should write");

        assert_eq!(prepared.package_metrics().payload_bytes, 6);
        assert_eq!(prepared.package_metrics().record_count, 2);
        assert_eq!(
            reconstruct_source_save_package(&package, Some(source_text.as_bytes())),
            expected_output
        );
        assert!(prepared.into_source().is_some());
    }

    /// Verifies CR-to-LF boundary repair remains explicit package payload.
    #[test]
    fn counts_source_save_boundary_repair_payload() {
        let padding = "x".repeat(SOURCE_SAVE_TEST_BYTES / 2);
        let source_text = format!("{padding}a\rb\nc{padding}");
        let mut document = document_from_source(source_text.as_bytes());
        let deleted_start = padding.len() + 2;
        document
            .replace(0, Utf16Range::new(deleted_start, deleted_start + 1), "")
            .expect("middle source character should delete");
        let prepared = document
            .snapshot()
            .prepare_source_save()
            .expect("boundary-repaired source save should prepare");
        let mut expected_output = Vec::new();
        document
            .write_to(&mut expected_output)
            .expect("boundary-repaired source should serialize");
        let mut package = Vec::new();
        prepared
            .write_package(&mut package)
            .expect("boundary-repaired package should write");

        assert_eq!(prepared.package_metrics().payload_bytes, 1);
        assert_eq!(prepared.package_metrics().record_count, 3);
        assert_eq!(
            reconstruct_source_save_package(&package, Some(source_text.as_bytes())),
            expected_output
        );
        assert!(prepared.into_source().is_some());
    }

    /// Verifies source metrics and snapshots preserve raw format exactly.
    #[test]
    fn preserves_raw_source_format_in_metrics_and_snapshots() {
        let source_text = "\u{feff}one\r\ntwo\rthree\n";
        let logical_text = "one\ntwo\nthree\n";
        let mut document = document_from_source(source_text.as_bytes());
        let original_snapshot = document.snapshot();
        let initial_metrics = document.metrics();
        let end_offset = initial_metrics.utf16_units;

        assert_eq!(initial_metrics.bytes, logical_text.len());
        assert_eq!(initial_metrics.serialized_bytes, source_text.len());
        assert_eq!(initial_metrics.chars, logical_text.chars().count());
        assert_eq!(initial_metrics.lines, 4);

        let edited_metrics = document
            .replace(
                initial_metrics.revision,
                Utf16Range::new(end_offset, end_offset),
                "tail\n",
            )
            .expect("normalized source insertion should succeed");
        let mut original_output = Vec::new();
        let mut edited_output = Vec::new();
        original_snapshot
            .write_to(&mut original_output)
            .expect("original snapshot should stream");
        document
            .write_to(&mut edited_output)
            .expect("edited document should stream");

        assert_eq!(original_output, source_text.as_bytes());
        assert_eq!(edited_output, format!("{source_text}tail\r\n").as_bytes());
        assert_eq!(
            edited_metrics.serialized_bytes,
            source_text.len() + "tail\r\n".len()
        );
        assert_eq!(original_snapshot.metrics(), initial_metrics);
    }

    /// Verifies boundary repair bytes are authoritative in captured metrics.
    #[test]
    fn counts_serialized_boundary_repair_bytes() {
        let mut document = document_from_source(b"a\rb\nc");
        let original_snapshot = document.snapshot();

        let metrics = document
            .replace(0, Utf16Range::new(2, 3), "")
            .expect("middle source character deletion should succeed");
        let edited_snapshot = document.snapshot();
        let mut original_output = Vec::new();
        let mut edited_output = Vec::new();
        original_snapshot
            .write_to(&mut original_output)
            .expect("original snapshot should stream");
        edited_snapshot
            .write_to(&mut edited_output)
            .expect("edited snapshot should stream");

        assert_eq!(metrics.bytes, 4);
        assert_eq!(metrics.serialized_bytes, 5);
        assert!(metrics.has_cr_line_endings);
        assert!(metrics.has_crlf_line_endings);
        assert!(!metrics.has_lf_line_endings);
        assert_eq!(original_output, b"a\rb\nc");
        assert_eq!(edited_output, b"a\r\r\nc");
        assert_eq!(
            edited_output.len(),
            edited_snapshot.metrics().serialized_bytes
        );
    }

    /// Verifies in-memory document output preserves normalized UTF-8 bytes.
    #[test]
    fn streams_document_round_trip() {
        let input = "one\nemoji 😀\nthree".as_bytes();
        let document = Document::from_text(
            std::str::from_utf8(input).expect("test input should contain valid UTF-8"),
        );
        let mut output = Vec::new();

        document
            .write_to(&mut output)
            .expect("memory writer should succeed");

        assert_eq!(output, input);
    }

    /// Searches every bounded continuation until one match or exact exhaustion.
    fn complete_bounded_find(
        document: &Document,
        query: &str,
        mut candidate_range: Utf16Range,
        direction: FindDirection,
    ) -> Option<Utf16Range> {
        let metrics = document.metrics();
        loop {
            let batch = document
                .find(FindRequest {
                    revision: metrics.revision,
                    query,
                    match_case: true,
                    candidate_range,
                    direction,
                    max_candidate_utf16_units: 2,
                })
                .expect("modeled bounded find should be valid");
            if let Some(matched) = batch.matched {
                return Some(matched.range);
            }
            let remaining = batch.remaining_candidate_range?;
            assert!(remaining.len() < candidate_range.len());
            candidate_range = remaining;
        }
    }

    /// Returns one exact modeled match among allowed scalar-aligned starts.
    fn modeled_find(
        text: &str,
        query: &str,
        candidate_range: Utf16Range,
        direction: FindDirection,
    ) -> Option<Utf16Range> {
        let query_utf16_units = query.encode_utf16().count();
        let mut candidates = Vec::new();
        let mut global_utf16_offset = 0;
        for (byte_offset, character) in text.char_indices() {
            if global_utf16_offset >= candidate_range.start
                && global_utf16_offset < candidate_range.end
                && text[byte_offset..].starts_with(query)
            {
                candidates.push(Utf16Range::new(
                    global_utf16_offset,
                    global_utf16_offset + query_utf16_units,
                ));
            }
            global_utf16_offset += character.len_utf16();
        }
        match direction {
            FindDirection::Forward => candidates.first().copied(),
            FindDirection::Backward => candidates.last().copied(),
        }
    }

    /// Returns every global UTF-16 boundary surrounding complete scalars.
    fn utf16_scalar_boundaries(text: &str) -> Vec<usize> {
        let mut boundaries = Vec::with_capacity(text.chars().count() + 1);
        let mut utf16_offset = 0;
        boundaries.push(utf16_offset);
        for character in text.chars() {
            utf16_offset += character.len_utf16();
            boundaries.push(utf16_offset);
        }
        boundaries
    }

    /// Asserts that one find operation fails with the exact invalid-request reason.
    fn assert_invalid_find_request(
        result: Result<FindBatch, DocumentError>,
        expected_reason: &'static str,
    ) {
        match result {
            Err(DocumentError::InvalidFindRequest(reason)) => {
                assert_eq!(reason, expected_reason);
            }
            Err(error) => panic!("expected invalid find request, received {error}"),
            Ok(_) => panic!("invalid find request unexpectedly succeeded"),
        }
    }

    /// Returns one byte offset corresponding to an exact UTF-16 boundary.
    fn byte_offset_at_utf16(text: &str, target_utf16_offset: usize) -> usize {
        let mut utf16_offset = 0;
        for (byte_offset, character) in text.char_indices() {
            if utf16_offset == target_utf16_offset {
                return byte_offset;
            }
            utf16_offset += character.len_utf16();
        }
        assert_eq!(utf16_offset, target_utf16_offset);
        text.len()
    }
}
