//! Stores normalized document text in a persistent source-backed piece tree.

use std::cmp::Ordering;
use std::fs::File;
use std::io::{ErrorKind, Write};
use std::os::unix::fs::FileExt;
use std::sync::Arc;

use crate::{DocumentError, DocumentLineEnding as LineEnding, Utf16Range};

/// Limits every source and edit leaf to a bounded read allocation.
pub(crate) const MAX_PIECE_BYTES: usize = 64 * 1024;
const MAX_UTF8_SCALAR_BYTES: usize = 4;
const MIN_FULL_SOURCE_PIECE_BYTES: usize = MAX_PIECE_BYTES - (MAX_UTF8_SCALAR_BYTES - 1);
const MAX_CACHED_SOURCE_PIECES: usize = 4;
const MAX_CACHED_PIECE_POSITIONS: usize = 16;
const MAX_DENSE_BOUNDARY_PIECES: usize = 16;
const EDIT_BACKING_TRIM_RATIO: usize = 4;
const UTF8_BOM: &[u8; 3] = b"\xef\xbb\xbf";
pub(crate) const LINE_ENDING_LF_FLAG: u8 = 1;
pub(crate) const LINE_ENDING_CRLF_FLAG: u8 = 1 << 1;
pub(crate) const LINE_ENDING_CR_FLAG: u8 = 1 << 2;

impl LineEnding {
    /// Returns the serialized bytes for one logical newline.
    const fn bytes(self) -> &'static [u8] {
        match self {
            Self::Lf => b"\n",
            Self::CrLf => b"\r\n",
            Self::Cr => b"\r",
        }
    }
}

/// Summarizes normalized text in document order.
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub(crate) struct TextSummary {
    /// Stores the UTF-8 byte length.
    pub(crate) bytes: usize,

    /// Stores the exact serialized byte length, excluding a document BOM.
    pub(crate) serialized_bytes: usize,

    /// Stores the Unicode scalar count.
    pub(crate) chars: usize,

    /// Stores the UTF-16 code-unit length.
    pub(crate) utf16_units: usize,

    /// Stores the LF count used to derive logical lines.
    pub(crate) line_feeds: usize,

    /// Stores runs of non-whitespace Unicode scalars.
    pub(crate) words: usize,

    /// Stores the serialized line-ending styles currently present.
    line_endings: u8,

    /// Stores those styles excluding an initial serialized LF, for boundary repair.
    line_endings_after_initial_lf: u8,

    /// Stores whether the first scalar is part of a word when text is nonempty.
    first_is_word: Option<bool>,

    /// Stores whether the last scalar is part of a word when text is nonempty.
    last_is_word: Option<bool>,

    /// Stores the first serialized byte when text is nonempty.
    first_serialized_byte: Option<u8>,

    /// Stores the last serialized byte when text is nonempty.
    last_serialized_byte: Option<u8>,
}

impl TextSummary {
    /// Creates a summary for one valid UTF-8 slice.
    #[cfg(test)]
    fn from_text(text: &str) -> Self {
        Self::from_edit_text(text, LineEnding::Lf)
    }

    /// Creates a summary for normalized text using one serialized newline.
    fn from_edit_text(text: &str, line_ending: LineEnding) -> Self {
        debug_assert!(!text.contains('\r'));
        let mut chars = 0_usize;
        let mut utf16_units = 0_usize;
        let mut line_feeds = 0_usize;
        let mut words = WordSummary::default();
        for character in text.chars() {
            chars += 1;
            utf16_units += character.len_utf16();
            words.observe(character);
            if character == '\n' {
                line_feeds += 1;
            }
        }
        let expanded_line_ending_bytes = line_feeds
            .checked_mul(line_ending.bytes().len() - 1)
            .expect("bounded edit newline expansion must fit serialized metrics");
        let serialized_bytes = text
            .len()
            .checked_add(expanded_line_ending_bytes)
            .expect("bounded edit text must fit serialized metrics");
        let first_serialized_byte = text.as_bytes().first().map(|byte| {
            if *byte == b'\n' {
                line_ending.bytes()[0]
            } else {
                *byte
            }
        });
        let last_serialized_byte = text.as_bytes().last().map(|byte| {
            if *byte == b'\n' {
                *line_ending
                    .bytes()
                    .last()
                    .expect("line-ending serialization must be nonempty")
            } else {
                *byte
            }
        });
        let line_endings = if line_feeds == 0 {
            0
        } else {
            line_ending_flag(line_ending)
        };
        Self {
            bytes: text.len(),
            serialized_bytes,
            chars,
            utf16_units,
            line_feeds,
            words: words.count,
            line_endings,
            line_endings_after_initial_lf: if line_feeds
                == usize::from(first_serialized_byte == Some(b'\n'))
            {
                0
            } else {
                line_endings
            },
            first_is_word: words.first_is_word,
            last_is_word: words.last_is_word,
            first_serialized_byte,
            last_serialized_byte,
        }
    }

    /// Creates a logical summary for one raw UTF-8 source range.
    fn from_source_text(text: &str) -> Self {
        let mut bytes = 0;
        let mut chars = 0;
        let mut utf16_units = 0;
        let mut line_feeds = 0;
        let mut line_endings = 0;
        let mut line_endings_after_initial_lf = 0;
        let mut words = WordSummary::default();
        let mut characters = text.chars().peekable();
        while let Some(character) = characters.next() {
            words.observe(character);
            if character == '\r' {
                let flag = if characters.peek() == Some(&'\n') {
                    characters.next();
                    LINE_ENDING_CRLF_FLAG
                } else {
                    LINE_ENDING_CR_FLAG
                };
                line_endings |= flag;
                line_endings_after_initial_lf |= flag;
                bytes += 1;
                chars += 1;
                utf16_units += 1;
                line_feeds += 1;
            } else if character == '\n' {
                line_endings |= LINE_ENDING_LF_FLAG;
                if chars > 0 {
                    line_endings_after_initial_lf |= LINE_ENDING_LF_FLAG;
                }
                bytes += 1;
                chars += 1;
                utf16_units += 1;
                line_feeds += 1;
            } else {
                bytes += character.len_utf8();
                chars += 1;
                utf16_units += character.len_utf16();
            }
        }
        Self {
            bytes,
            serialized_bytes: text.len(),
            chars,
            utf16_units,
            line_feeds,
            words: words.count,
            line_endings,
            line_endings_after_initial_lf,
            first_is_word: words.first_is_word,
            last_is_word: words.last_is_word,
            first_serialized_byte: text.as_bytes().first().copied(),
            last_serialized_byte: text.as_bytes().last().copied(),
        }
    }

    /// Adds two ordered summaries without accepting counter overflow.
    fn checked_add(self, other: Self) -> Result<Self, DocumentError> {
        let boundary_repair_bytes = usize::from(
            self.last_serialized_byte == Some(b'\r') && other.first_serialized_byte == Some(b'\n'),
        );
        let joined_word =
            usize::from(self.last_is_word == Some(true) && other.first_is_word == Some(true));
        let separate_words = checked_metric_add(self.words, other.words)?;
        // Repair turns the right-hand initial LF into CRLF without changing the
        // left-hand CR. Keep LF present only if another LF survives on either side.
        let right_line_endings = if boundary_repair_bytes == 0 {
            other.line_endings
        } else {
            other.line_endings_after_initial_lf | LINE_ENDING_CRLF_FLAG
        };
        Ok(Self {
            bytes: checked_metric_add(self.bytes, other.bytes)?,
            serialized_bytes: checked_metric_add(
                checked_metric_add(self.serialized_bytes, other.serialized_bytes)?,
                boundary_repair_bytes,
            )?,
            chars: checked_metric_add(self.chars, other.chars)?,
            utf16_units: checked_metric_add(self.utf16_units, other.utf16_units)?,
            line_feeds: checked_metric_add(self.line_feeds, other.line_feeds)?,
            words: separate_words
                .checked_sub(joined_word)
                .expect("joined word summaries must each contain a word"),
            line_endings: self.line_endings | right_line_endings,
            line_endings_after_initial_lf: if self.first_serialized_byte.is_none() {
                other.line_endings_after_initial_lf
            } else {
                self.line_endings_after_initial_lf | right_line_endings
            },
            first_is_word: self.first_is_word.or(other.first_is_word),
            last_is_word: other.last_is_word.or(self.last_is_word),
            first_serialized_byte: self.first_serialized_byte.or(other.first_serialized_byte),
            last_serialized_byte: other.last_serialized_byte.or(self.last_serialized_byte),
        })
    }
}

/// Counts words during the existing scalar scan, retaining cross-piece boundaries.
#[derive(Default)]
struct WordSummary {
    count: usize,
    first_is_word: Option<bool>,
    last_is_word: Option<bool>,
}

impl WordSummary {
    /// Incorporates one scalar; collapsing CRLF does not change word boundaries.
    fn observe(&mut self, character: char) {
        let is_word = !character.is_whitespace();
        if is_word && self.last_is_word != Some(true) {
            self.count += 1;
        }
        self.first_is_word.get_or_insert(is_word);
        self.last_is_word = Some(is_word);
    }
}

/// Returns the presence flag for one serialized line-ending style.
const fn line_ending_flag(line_ending: LineEnding) -> u8 {
    match line_ending {
        LineEnding::Lf => LINE_ENDING_LF_FLAG,
        LineEnding::CrLf => LINE_ENDING_CRLF_FLAG,
        LineEnding::Cr => LINE_ENDING_CR_FLAG,
    }
}

/// Identifies one prefix position in the normalized logical model.
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub(crate) struct LogicalPosition {
    /// Stores the logical UTF-8 byte offset.
    pub(crate) bytes: usize,

    /// Stores the logical UTF-16 code-unit offset.
    pub(crate) utf16_units: usize,

    /// Stores the number of preceding logical line feeds.
    pub(crate) line_feeds: usize,
}

impl LogicalPosition {
    /// Creates a logical position from a complete text summary.
    const fn from_summary(summary: TextSummary) -> Self {
        Self {
            bytes: summary.bytes,
            utf16_units: summary.utf16_units,
            line_feeds: summary.line_feeds,
        }
    }

    /// Creates a logical position from one normalized UTF-8 slice.
    fn from_text(text: &str) -> Self {
        debug_assert!(!text.contains('\r'));
        let mut utf16_units = 0_usize;
        let mut line_feeds = 0_usize;
        for character in text.chars() {
            utf16_units += character.len_utf16();
            if character == '\n' {
                line_feeds += 1;
            }
        }
        Self {
            bytes: text.len(),
            utf16_units,
            line_feeds,
        }
    }

    /// Adds one complete text summary without accepting counter overflow.
    fn checked_add_summary(self, summary: TextSummary) -> Result<Self, DocumentError> {
        Ok(Self {
            bytes: checked_metric_add(self.bytes, summary.bytes)?,
            utf16_units: checked_metric_add(self.utf16_units, summary.utf16_units)?,
            line_feeds: checked_metric_add(self.line_feeds, summary.line_feeds)?,
        })
    }

    /// Adds another logical position without accepting counter overflow.
    fn checked_add(self, other: Self) -> Result<Self, DocumentError> {
        Ok(Self {
            bytes: checked_metric_add(self.bytes, other.bytes)?,
            utf16_units: checked_metric_add(self.utf16_units, other.utf16_units)?,
            line_feeds: checked_metric_add(self.line_feeds, other.line_feeds)?,
        })
    }

    /// Subtracts one already-counted text suffix without accepting invalid counters.
    fn checked_sub(self, other: Self) -> Result<Self, DocumentError> {
        Ok(Self {
            bytes: self
                .bytes
                .checked_sub(other.bytes)
                .ok_or_else(invalid_tree_summary)?,
            utf16_units: self
                .utf16_units
                .checked_sub(other.utf16_units)
                .ok_or_else(invalid_tree_summary)?,
            line_feeds: self
                .line_feeds
                .checked_sub(other.line_feeds)
                .ok_or_else(invalid_tree_summary)?,
        })
    }
}

/// Identifies the normalized contents of one logical line.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) struct LineBounds {
    /// Stores the global UTF-16 start offset of the line content.
    pub(crate) content_start_utf16: usize,

    /// Stores the exclusive global UTF-16 end offset of the line content.
    pub(crate) content_end_utf16: usize,

    /// Stores the UTF-16 length of the following line terminator.
    pub(crate) terminator_utf16_units: u8,
}

/// Contains a scalar-aligned bounded prefix of a document range.
#[derive(Debug, Eq, PartialEq)]
pub(crate) struct BoundedTextPrefix {
    /// Stores the copied UTF-8 text.
    pub(crate) text: String,

    /// Stores the copied UTF-16 code-unit length.
    pub(crate) utf16_units: usize,

    /// Indicates that the prefix reached the requested range end.
    pub(crate) reached_end: bool,
}

/// Contains one independently owned immutable source capability.
pub(crate) struct ClonedSource {
    /// Owns a duplicate of the source descriptor.
    pub(crate) file: File,

    /// Stores the source's exact raw byte length.
    pub(crate) byte_length: u64,
}

/// Reports whether sparse serialization can address one immutable source.
pub(crate) enum SourceClone {
    /// Indicates that the captured tree contains no surviving source pieces.
    None,

    /// Contains the sole immutable source referenced by the captured tree.
    Available(ClonedSource),

    /// Indicates that source pieces cannot be represented by one file capability.
    Unavailable,
}

/// Supplies ordered serialized segments without materializing source ranges.
pub(crate) trait SerializedSegmentVisitor {
    /// Visits one exact raw range of the immutable source.
    fn visit_source(&mut self, byte_start: u64, byte_length: u64) -> Result<(), DocumentError>;

    /// Visits one exact serialized payload segment.
    fn visit_payload(&mut self, payload: SerializedPayload<'_>) -> Result<(), DocumentError>;
}

/// Describes exact non-source bytes in one package record.
pub(crate) struct SerializedPayload<'piece> {
    kind: SerializedPayloadKind<'piece>,
    byte_length: usize,
}

impl SerializedPayload<'_> {
    /// Creates one payload over static exact bytes.
    const fn from_static(bytes: &'static [u8]) -> Self {
        Self {
            kind: SerializedPayloadKind::Static(bytes),
            byte_length: bytes.len(),
        }
    }

    /// Creates one payload over a complete serialized edit piece.
    fn from_edit(piece: &Piece) -> SerializedPayload<'_> {
        SerializedPayload {
            kind: SerializedPayloadKind::Edit(piece),
            byte_length: piece.summary.serialized_bytes,
        }
    }

    /// Returns the exact serialized payload byte length.
    pub(crate) const fn byte_length(&self) -> usize {
        self.byte_length
    }

    /// Writes this payload through one bounded serialization buffer.
    pub(crate) fn write_to(
        &self,
        writer: &mut impl Write,
        buffer: &mut [u8],
    ) -> Result<(), DocumentError> {
        match self.kind {
            SerializedPayloadKind::Static(bytes) => writer.write_all(bytes)?,
            SerializedPayloadKind::Edit(piece) => piece.write_to(writer, buffer)?,
        }
        Ok(())
    }
}

/// Identifies one borrowed source of exact serialized payload bytes.
#[derive(Clone, Copy)]
enum SerializedPayloadKind<'piece> {
    Static(&'static [u8]),
    Edit(&'piece Piece),
}

/// Coalesces consecutive raw source ranges before visiting them.
struct SerializedSegmentEmitter<'visitor, Visitor> {
    visitor: &'visitor mut Visitor,
    pending_source: Option<(u64, u64)>,
}

impl<'visitor, Visitor> SerializedSegmentEmitter<'visitor, Visitor>
where
    Visitor: SerializedSegmentVisitor,
{
    /// Creates one ordered segment emitter.
    const fn new(visitor: &'visitor mut Visitor) -> Self {
        Self {
            visitor,
            pending_source: None,
        }
    }

    /// Adds one source range and coalesces a contiguous predecessor.
    fn push_source(&mut self, byte_start: u64, byte_length: usize) -> Result<(), DocumentError> {
        let byte_length = u64::try_from(byte_length)
            .map_err(|_| invalid_data_error("source segment length exceeds u64"))?;
        if byte_length == 0 {
            return Err(invalid_data_error("source segment is empty"));
        }
        if let Some((pending_start, pending_length)) = self.pending_source {
            let pending_end = pending_start
                .checked_add(pending_length)
                .ok_or_else(invalid_tree_summary)?;
            if pending_end == byte_start {
                self.pending_source = Some((
                    pending_start,
                    pending_length
                        .checked_add(byte_length)
                        .ok_or_else(invalid_tree_summary)?,
                ));
                return Ok(());
            }
            self.flush_source()?;
        }
        self.pending_source = Some((byte_start, byte_length));
        Ok(())
    }

    /// Adds one payload after publishing any preceding source range.
    fn push_payload(&mut self, payload: SerializedPayload<'_>) -> Result<(), DocumentError> {
        if payload.byte_length() == 0 {
            return Err(invalid_data_error("serialized payload is empty"));
        }
        self.flush_source()?;
        self.visitor.visit_payload(payload)
    }

    /// Publishes every remaining ordered segment.
    fn finish(mut self) -> Result<(), DocumentError> {
        self.flush_source()
    }

    /// Publishes one pending source range.
    fn flush_source(&mut self) -> Result<(), DocumentError> {
        let Some((byte_start, byte_length)) = self.pending_source.take() else {
            return Ok(());
        };
        self.visitor.visit_source(byte_start, byte_length)
    }
}

/// Reports whether a replacement changed the persistent tree.
pub(crate) enum ReplaceOutcome {
    /// Indicates that the selected range already contains the replacement.
    Unchanged,

    /// Contains the new tree after a nonempty logical change.
    Replaced(PieceTree),
}

/// Owns an immutable, persistent AVL tree of normalized text pieces.
#[derive(Clone, Default)]
pub(crate) struct PieceTree {
    root: Tree,
    has_utf8_bom: bool,
    inserted_line_ending: LineEnding,
}

impl PieceTree {
    /// Creates an empty piece tree.
    #[must_use]
    pub(crate) const fn new() -> Self {
        Self {
            root: None,
            has_utf8_bom: false,
            inserted_line_ending: LineEnding::Lf,
        }
    }

    /// Creates an edit-backed tree from valid UTF-8 text.
    #[must_use]
    pub(crate) fn from_text(text: &str) -> Self {
        assert!(
            !text.contains('\r'),
            "normalized document text must not contain carriage returns"
        );
        let root = edit_tree(text, LineEnding::Lf);
        Self {
            root,
            has_utf8_bom: false,
            inserted_line_ending: LineEnding::Lf,
        }
    }

    /// Opens an immutable regular source without retaining its text in memory.
    ///
    /// # Errors
    ///
    /// Returns [`DocumentError::Io`] when the source is not a regular file,
    /// changes length, cannot be read, contains invalid UTF-8, or overflows a
    /// document metric.
    pub(crate) fn open_source(file: File) -> Result<Self, DocumentError> {
        let source = Arc::new(FileSource::new(file)?);
        Self::open_source_reader(source)
    }

    /// Opens an immutable positioned source without retaining its text.
    fn open_source_reader<Source>(source: Arc<Source>) -> Result<Self, DocumentError>
    where
        Source: ReadAtSource + 'static,
    {
        let source: Arc<dyn ReadAtSource> = source;
        let fixed_length = source.len();
        let length = usize::try_from(fixed_length)
            .map_err(|_| invalid_data_error("source byte length exceeds the supported range"))?;
        let mut bom_probe = [0_u8; UTF8_BOM.len()];
        let bom_probe_bytes = length.min(bom_probe.len());
        source.read_exact_at(&mut bom_probe[..bom_probe_bytes], 0)?;
        let has_utf8_bom = bom_probe_bytes == UTF8_BOM.len() && &bom_probe == UTF8_BOM;
        let mut offset = usize::from(has_utf8_bom) * UTF8_BOM.len();
        let mut source_buffer = vec![0_u8; MAX_PIECE_BYTES].into_boxed_slice();
        let mut leaves = Vec::new();
        let mut line_endings = LineEndingStatistics::default();
        leaves
            .try_reserve_exact(length.div_ceil(MIN_FULL_SOURCE_PIECE_BYTES))
            .map_err(|_| invalid_data_error("source index allocation failed"))?;

        while offset < length {
            let candidate_bytes = MAX_PIECE_BYTES.min(length - offset);
            source.read_exact_at(
                &mut source_buffer[..candidate_bytes],
                u64::try_from(offset).map_err(|_| {
                    invalid_data_error("source byte offset exceeds the supported range")
                })?,
            )?;
            let candidate = &source_buffer[..candidate_bytes];
            let reached_end = offset + candidate_bytes == length;
            let mut piece_bytes = valid_source_prefix(candidate, reached_end)?;
            if !reached_end && piece_bytes == candidate.len() && candidate.ends_with(b"\r") {
                piece_bytes -= 1;
            }
            debug_assert!(piece_bytes > 0);
            let text = std::str::from_utf8(&candidate[..piece_bytes]).map_err(|_| {
                invalid_data_error("source changed while its UTF-8 index was built")
            })?;
            line_endings.observe(text);
            let piece = Piece::from_source(source.clone(), offset, text)?;
            leaves.push(Arc::new(Node::Leaf(piece)));
            offset = offset
                .checked_add(piece_bytes)
                .ok_or_else(invalid_tree_summary)?;
        }

        source.validate_length(fixed_length)?;
        Ok(Self {
            root: build_balanced_tree(&leaves)?,
            has_utf8_bom,
            inserted_line_ending: line_endings.preferred(),
        })
    }

    /// Returns the complete tree summary.
    #[must_use]
    pub(crate) fn summary(&self) -> TextSummary {
        tree_summary(&self.root)
    }

    /// Returns the exact serialized byte length, including an optional BOM.
    #[must_use]
    pub(crate) fn serialized_bytes(&self) -> usize {
        self.summary()
            .serialized_bytes
            .checked_add(usize::from(self.has_utf8_bom) * UTF8_BOM.len())
            .expect("serialized document length must fit its tree summary")
    }

    /// Returns the logical line count, including the final empty line.
    #[must_use]
    pub(crate) fn line_count(&self) -> usize {
        self.summary()
            .line_feeds
            .checked_add(1)
            .expect("line count must fit the document summary")
    }

    /// Returns whether serialization begins with a UTF-8 byte-order mark.
    #[must_use]
    pub(crate) const fn has_utf8_bom(&self) -> bool {
        self.has_utf8_bom
    }

    /// Returns whether serialization contains an LF line ending.
    #[must_use]
    pub(crate) fn has_lf_line_endings(&self) -> bool {
        self.summary().line_endings & LINE_ENDING_LF_FLAG != 0
    }

    /// Returns whether serialization contains a CRLF line ending.
    #[must_use]
    pub(crate) fn has_crlf_line_endings(&self) -> bool {
        self.summary().line_endings & LINE_ENDING_CRLF_FLAG != 0
    }

    /// Returns whether serialization contains a CR line ending.
    #[must_use]
    pub(crate) fn has_cr_line_endings(&self) -> bool {
        self.summary().line_endings & LINE_ENDING_CR_FLAG != 0
    }

    /// Returns the serialized form used for newly inserted logical newlines.
    #[must_use]
    pub(crate) const fn inserted_line_ending(&self) -> LineEnding {
        self.inserted_line_ending
    }

    /// Creates one bounded read session over this tree.
    pub(crate) fn reader(&self) -> PieceTreeReader<'_> {
        PieceTreeReader {
            tree: self,
            cache: PieceReadCache::new(),
        }
    }

    /// Returns the content and terminator bounds for one LF-delimited line.
    ///
    /// # Errors
    ///
    /// Returns [`DocumentError::InvalidViewportRequest`] when the line is
    /// outside the document and [`DocumentError::Io`] when source text cannot
    /// be read.
    fn line_bounds(
        &self,
        line: usize,
        cache: &mut PieceReadCache,
    ) -> Result<LineBounds, DocumentError> {
        if line >= self.line_count() {
            return Err(DocumentError::InvalidViewportRequest(
                "start line is outside the document",
            ));
        }

        let content_start = if line == 0 {
            LogicalPosition::default()
        } else {
            self.line_feed_position(line - 1, cache)?.after
        };
        let (content_end, terminator_utf16_units) = if line < self.summary().line_feeds {
            let line_feed = self.line_feed_position(line, cache)?;
            (line_feed.before, 1)
        } else {
            (LogicalPosition::from_summary(self.summary()), 0)
        };

        Ok(LineBounds {
            content_start_utf16: content_start.utf16_units,
            content_end_utf16: content_end.utf16_units,
            terminator_utf16_units,
        })
    }

    /// Reads a scalar-aligned prefix of one global UTF-16 range.
    ///
    /// # Errors
    ///
    /// Returns an error when the range is invalid or misaligned, a metric
    /// overflows, or source text cannot be read.
    fn bounded_text_prefix(
        &self,
        range: Utf16Range,
        max_utf16_units: usize,
        cache: &mut PieceReadCache,
    ) -> Result<BoundedTextPrefix, DocumentError> {
        self.validate_range(range)?;
        let start = self.position_at_utf16(range.start, cache)?;
        let end = self.position_at_utf16(range.end, cache)?;
        let range_bytes = start.bytes..end.bytes;
        let mut state = PrefixState::new(range_bytes.len(), max_utf16_units);
        collect_bounded_prefix(&self.root, 0, &range_bytes, &mut state, cache)?;
        Ok(BoundedTextPrefix {
            text: state.text,
            utf16_units: state.utf16_units,
            reached_end: state.copied_bytes == range_bytes.len(),
        })
    }

    /// Replaces one exact global UTF-16 range persistently.
    ///
    /// # Errors
    ///
    /// Returns an error when the range is invalid or misaligned, a metric
    /// overflows, or source text cannot be read.
    pub(crate) fn replace(
        &self,
        range: Utf16Range,
        replacement: &str,
    ) -> Result<ReplaceOutcome, DocumentError> {
        self.validate_range(range)?;
        let mut cache = PieceReadCache::new();
        let start = self.position_at_utf16(range.start, &mut cache)?;
        let end = self.position_at_utf16(range.end, &mut cache)?;
        let byte_range = start.bytes..end.bytes;
        if byte_range_equals(
            &self.root,
            0,
            &byte_range,
            replacement.as_bytes(),
            &mut cache,
        )? {
            return Ok(ReplaceOutcome::Unchanged);
        }

        let (prefix, remainder) = split_tree(self.root.clone(), start.bytes, &mut cache)?;
        let (_, suffix) = split_tree(remainder, byte_range.len(), &mut cache)?;
        let with_replacement = compact_concatenate_trees(
            prefix,
            edit_tree(replacement, self.inserted_line_ending),
            &mut cache,
        )?;
        let root = compact_concatenate_trees(with_replacement, suffix, &mut cache)?;
        Ok(ReplaceOutcome::Replaced(Self {
            root,
            has_utf8_bom: self.has_utf8_bom,
            inserted_line_ending: self.inserted_line_ending,
        }))
    }

    /// Streams the complete serialized document through a bounded buffer.
    ///
    /// # Errors
    ///
    /// Returns [`DocumentError::Io`] when source text cannot be read or the
    /// destination cannot be written.
    pub(crate) fn write_to(&self, writer: &mut impl Write) -> Result<(), DocumentError> {
        if self.has_utf8_bom {
            writer.write_all(UTF8_BOM)?;
        }
        let mut source_buffer = vec![0_u8; MAX_PIECE_BYTES].into_boxed_slice();
        let mut previous_serialized_byte = None;
        write_tree(
            &self.root,
            writer,
            &mut source_buffer,
            &mut previous_serialized_byte,
        )
    }

    /// Duplicates the sole source capability needed by sparse serialization.
    pub(crate) fn try_clone_source(&self) -> SourceClone {
        let mut observed_source: Option<Arc<dyn ReadAtSource>> = None;
        let mut source_is_unavailable = false;
        visit_tree_pieces(&self.root, &mut |piece| {
            let PieceBacking::Source(source) = &piece.backing else {
                return;
            };
            if observed_source
                .as_ref()
                .is_some_and(|observed| !Arc::ptr_eq(observed, source))
            {
                source_is_unavailable = true;
                return;
            }
            observed_source.get_or_insert_with(|| Arc::clone(source));
        });
        if source_is_unavailable {
            return SourceClone::Unavailable;
        }
        let Some(source) = observed_source else {
            return SourceClone::None;
        };
        match source.try_clone_file() {
            Ok(Some(file)) => SourceClone::Available(ClonedSource {
                file,
                byte_length: source.len(),
            }),
            Ok(None) | Err(_) => SourceClone::Unavailable,
        }
    }

    /// Visits coalesced source ranges and exact payloads in serialized order.
    ///
    /// # Errors
    ///
    /// Returns an error when source segments are requested without a source
    /// capability or a visitor rejects one segment.
    pub(crate) fn visit_serialized_segments(
        &self,
        source_available: bool,
        visitor: &mut impl SerializedSegmentVisitor,
    ) -> Result<(), DocumentError> {
        let mut emitter = SerializedSegmentEmitter::new(visitor);
        if self.has_utf8_bom {
            if source_available {
                emitter.push_source(0, UTF8_BOM.len())?;
            } else {
                emitter.push_payload(SerializedPayload::from_static(UTF8_BOM))?;
            }
        }
        let mut previous_serialized_byte = None;
        visit_tree_pieces_result(&self.root, &mut |piece| {
            if previous_serialized_byte == Some(b'\r')
                && piece.summary.first_serialized_byte == Some(b'\n')
            {
                emitter.push_payload(SerializedPayload::from_static(b"\r"))?;
            }
            match &piece.backing {
                PieceBacking::Source(_) if source_available => {
                    emitter.push_source(piece.byte_start, piece.summary.serialized_bytes)?;
                }
                PieceBacking::Source(_) => {
                    return Err(invalid_data_error(
                        "source segment has no transferable capability",
                    ));
                }
                PieceBacking::Edit { .. } => {
                    emitter.push_payload(SerializedPayload::from_edit(piece))?;
                }
            }
            previous_serialized_byte = piece.summary.last_serialized_byte;
            Ok(())
        })?;
        emitter.finish()
    }

    /// Validates a global UTF-16 range against the current tree.
    fn validate_range(&self, range: Utf16Range) -> Result<(), DocumentError> {
        let document_utf16_units = self.summary().utf16_units;
        if range.start > range.end || range.end > document_utf16_units {
            return Err(DocumentError::InvalidRange {
                range,
                document_utf16_units,
            });
        }
        Ok(())
    }

    /// Resolves one exact UTF-16 boundary to its prefix summary.
    fn position_at_utf16(
        &self,
        offset: usize,
        cache: &mut PieceReadCache,
    ) -> Result<LogicalPosition, DocumentError> {
        let total = self.summary();
        if offset > total.utf16_units {
            return Err(DocumentError::MisalignedUtf16Offset { offset });
        }
        position_at_utf16(
            &self.root,
            offset,
            LogicalPosition::default(),
            offset,
            cache,
        )
    }

    /// Finds one LF and the summaries immediately before and after it.
    fn line_feed_position(
        &self,
        index: usize,
        cache: &mut PieceReadCache,
    ) -> Result<LineFeedPosition, DocumentError> {
        if index >= self.summary().line_feeds {
            return Err(DocumentError::InvalidViewportRequest(
                "line feed is outside the document",
            ));
        }
        find_line_feed(&self.root, index, LogicalPosition::default(), cache)
    }

    /// Validates every structural and text invariant for tests.
    #[cfg(test)]
    pub(crate) fn validate_invariants(&self) -> Result<(), DocumentError> {
        let mut cache = PieceReadCache::new();
        let validation = validate_tree(&self.root, &mut cache)?;
        if validation.summary != self.summary() {
            return Err(invalid_tree_summary());
        }
        Ok(())
    }

    /// Returns the current leaf count for deterministic compaction tests.
    #[cfg(test)]
    fn leaf_count(&self) -> usize {
        count_tree_leaves(&self.root)
    }

    /// Returns the materialized edit-leaf count for source-retention tests.
    #[cfg(test)]
    fn edit_leaf_count(&self) -> usize {
        count_tree_edit_leaves(&self.root)
    }

    /// Returns the largest retained edit backing for memory-bound tests.
    #[cfg(test)]
    fn largest_edit_backing_bytes(&self) -> usize {
        largest_tree_edit_backing_bytes(&self.root)
    }
}

/// Reuses a bounded set of decoded source pieces for one logical operation.
pub(crate) struct PieceTreeReader<'tree> {
    tree: &'tree PieceTree,
    cache: PieceReadCache,
}

impl PieceTreeReader<'_> {
    /// Returns the content and terminator bounds for one LF-delimited line.
    ///
    /// # Errors
    ///
    /// Returns [`DocumentError::InvalidViewportRequest`] when the line is
    /// outside the document and [`DocumentError::Io`] when source text cannot
    /// be read.
    pub(crate) fn line_bounds(&mut self, line: usize) -> Result<LineBounds, DocumentError> {
        self.tree.line_bounds(line, &mut self.cache)
    }

    /// Reads a scalar-aligned prefix of one global UTF-16 range.
    ///
    /// # Errors
    ///
    /// Returns an error when the range is invalid or misaligned, a metric
    /// overflows, or source text cannot be read.
    pub(crate) fn bounded_text_prefix(
        &mut self,
        range: Utf16Range,
        max_utf16_units: usize,
    ) -> Result<BoundedTextPrefix, DocumentError> {
        self.tree
            .bounded_text_prefix(range, max_utf16_units, &mut self.cache)
    }

    /// Returns the nearest scalar boundary at or before a UTF-16 offset.
    ///
    /// # Errors
    ///
    /// Returns an error when the offset is outside the document or source text
    /// cannot be read.
    pub(crate) fn scalar_boundary_at_or_before(
        &mut self,
        offset: usize,
    ) -> Result<usize, DocumentError> {
        if offset > self.tree.summary().utf16_units {
            return Err(DocumentError::InvalidRange {
                range: Utf16Range::new(offset, offset),
                document_utf16_units: self.tree.summary().utf16_units,
            });
        }
        match self.tree.position_at_utf16(offset, &mut self.cache) {
            Ok(_) => Ok(offset),
            Err(DocumentError::MisalignedUtf16Offset { .. }) => offset
                .checked_sub(1)
                .ok_or(DocumentError::MisalignedUtf16Offset { offset }),
            Err(error) => Err(error),
        }
    }

    /// Returns the nearest scalar boundary at or after a UTF-16 offset.
    ///
    /// # Errors
    ///
    /// Returns an error when the offset is outside the document or source text
    /// cannot be read.
    pub(crate) fn scalar_boundary_at_or_after(
        &mut self,
        offset: usize,
    ) -> Result<usize, DocumentError> {
        let document_utf16_units = self.tree.summary().utf16_units;
        if offset > document_utf16_units {
            return Err(DocumentError::InvalidRange {
                range: Utf16Range::new(offset, offset),
                document_utf16_units,
            });
        }
        match self.tree.position_at_utf16(offset, &mut self.cache) {
            Ok(_) => Ok(offset),
            Err(DocumentError::MisalignedUtf16Offset { .. }) => offset
                .checked_add(1)
                .filter(|aligned_offset| *aligned_offset <= document_utf16_units)
                .ok_or(DocumentError::MisalignedUtf16Offset { offset }),
            Err(error) => Err(error),
        }
    }

    /// Returns the exact prefix summary at one scalar-aligned UTF-16 offset.
    ///
    /// # Errors
    ///
    /// Returns an error when the offset is outside the document, divides a
    /// surrogate pair, or source text cannot be read.
    pub(crate) fn position_at_utf16(
        &mut self,
        offset: usize,
    ) -> Result<LogicalPosition, DocumentError> {
        if offset > self.tree.summary().utf16_units {
            return Err(DocumentError::InvalidRange {
                range: Utf16Range::new(offset, offset),
                document_utf16_units: self.tree.summary().utf16_units,
            });
        }
        self.tree.position_at_utf16(offset, &mut self.cache)
    }
}

/// Reads immutable bytes at exact offsets without shared cursor state.
trait ReadAtSource: Send + Sync {
    /// Returns the fixed source byte length.
    fn len(&self) -> u64;

    /// Duplicates the immutable file capability when one is available.
    fn try_clone_file(&self) -> std::io::Result<Option<File>> {
        Ok(None)
    }

    /// Reads bytes beginning at one absolute source offset.
    fn read_at(&self, buffer: &mut [u8], offset: u64) -> std::io::Result<usize>;

    /// Verifies that the source still has its indexed byte length.
    fn validate_length(&self, expected_length: u64) -> std::io::Result<()> {
        if self.len() != expected_length {
            return Err(std::io::Error::new(
                ErrorKind::InvalidData,
                "source length changed while its index was built",
            ));
        }
        Ok(())
    }

    /// Fills a bounded buffer or reports a premature source end.
    fn read_exact_at(&self, mut buffer: &mut [u8], mut offset: u64) -> std::io::Result<()> {
        let requested_end = offset
            .checked_add(u64::try_from(buffer.len()).map_err(|_| {
                std::io::Error::new(ErrorKind::InvalidInput, "source read length exceeds u64")
            })?)
            .ok_or_else(|| {
                std::io::Error::new(ErrorKind::InvalidInput, "source read range overflowed")
            })?;
        if requested_end > self.len() {
            return Err(std::io::Error::new(
                ErrorKind::UnexpectedEof,
                "source read exceeds its fixed length",
            ));
        }

        while !buffer.is_empty() {
            match self.read_at(buffer, offset) {
                Ok(0) => {
                    return Err(std::io::Error::new(
                        ErrorKind::UnexpectedEof,
                        "source ended before its fixed length",
                    ));
                }
                Ok(read_bytes) => {
                    if read_bytes > buffer.len() {
                        return Err(std::io::Error::new(
                            ErrorKind::InvalidData,
                            "source read count exceeds its buffer",
                        ));
                    }
                    let (_, remainder) = buffer.split_at_mut(read_bytes);
                    buffer = remainder;
                    offset = offset
                        .checked_add(u64::try_from(read_bytes).map_err(|_| {
                            std::io::Error::new(
                                ErrorKind::InvalidData,
                                "source read count exceeds u64",
                            )
                        })?)
                        .ok_or_else(|| {
                            std::io::Error::new(
                                ErrorKind::InvalidData,
                                "source read offset overflowed",
                            )
                        })?;
                }
                Err(error) if error.kind() == ErrorKind::Interrupted => {}
                Err(error) => return Err(error),
            }
        }
        Ok(())
    }
}

/// Adapts one immutable regular file to positioned source reads.
struct FileSource {
    file: File,
    length: u64,
}

impl FileSource {
    /// Creates a fixed-length adapter for one regular file.
    fn new(file: File) -> Result<Self, DocumentError> {
        let metadata = file.metadata()?;
        if !metadata.file_type().is_file() {
            return Err(invalid_data_error(
                "document source is not a regular seekable file",
            ));
        }
        Ok(Self {
            file,
            length: metadata.len(),
        })
    }

    /// Returns the source's current metadata byte length.
    fn current_length(&self) -> std::io::Result<u64> {
        Ok(self.file.metadata()?.len())
    }
}

impl ReadAtSource for FileSource {
    fn len(&self) -> u64 {
        self.length
    }

    fn try_clone_file(&self) -> std::io::Result<Option<File>> {
        self.file.try_clone().map(Some)
    }

    fn read_at(&self, buffer: &mut [u8], offset: u64) -> std::io::Result<usize> {
        self.file.read_at(buffer, offset)
    }

    fn validate_length(&self, expected_length: u64) -> std::io::Result<()> {
        if self.current_length()? != expected_length {
            return Err(std::io::Error::new(
                ErrorKind::InvalidData,
                "source length changed while its index was built",
            ));
        }
        Ok(())
    }
}

/// Stores one decoded source range within a bounded read session.
struct SourceCacheEntry {
    source: Arc<dyn ReadAtSource>,
    byte_start: u64,
    byte_length: usize,
    logical_text: String,
}

impl SourceCacheEntry {
    /// Returns whether this entry contains one exact source piece.
    fn contains(
        &self,
        source: &Arc<dyn ReadAtSource>,
        byte_start: u64,
        byte_length: usize,
    ) -> bool {
        Arc::ptr_eq(&self.source, source)
            && self.byte_start == byte_start
            && self.byte_length == byte_length
    }
}

/// Retains bounded decoded pieces and logical positions for one operation.
struct PieceReadCache {
    entries: Vec<SourceCacheEntry>,
    positions: Vec<CachedPiecePosition>,
    next_position_slot: usize,
}

/// Retains one validated local position with its immutable backing identity.
struct CachedPiecePosition {
    piece: Piece,
    position: LogicalPosition,
}

impl PieceReadCache {
    /// Creates an empty bounded piece cache.
    fn new() -> Self {
        Self {
            entries: Vec::with_capacity(MAX_CACHED_SOURCE_PIECES),
            positions: Vec::with_capacity(MAX_CACHED_PIECE_POSITIONS),
            next_position_slot: 0,
        }
    }

    /// Returns a nearby cached position when it is no farther than the origin.
    fn nearest_position(
        &self,
        piece: &Piece,
        distance: impl Fn(LogicalPosition) -> usize,
    ) -> LogicalPosition {
        self.positions
            .iter()
            .filter(|entry| {
                entry.piece.byte_start == piece.byte_start
                    && entry.piece.summary == piece.summary
                    && entry.piece.shares_backing(piece)
            })
            .map(|entry| entry.position)
            .min_by_key(|position| distance(*position))
            .filter(|position| distance(*position) <= distance(LogicalPosition::default()))
            .unwrap_or_default()
    }

    /// Remembers a bounded set of positions without retaining unrelated tree nodes.
    fn remember_position(&mut self, piece: &Piece, position: LogicalPosition) {
        if position.bytes == 0
            || self.positions.iter().any(|entry| {
                entry.position == position
                    && entry.piece.byte_start == piece.byte_start
                    && entry.piece.summary == piece.summary
                    && entry.piece.shares_backing(piece)
            })
        {
            return;
        }
        let entry = CachedPiecePosition {
            piece: piece.clone(),
            position,
        };
        if self.positions.len() == MAX_CACHED_PIECE_POSITIONS {
            self.positions[self.next_position_slot] = entry;
        } else {
            self.positions.push(entry);
        }
        self.next_position_slot = (self.next_position_slot + 1) % MAX_CACHED_PIECE_POSITIONS;
    }

    /// Runs an operation over one validated source piece.
    fn with_text<ResultValue>(
        &mut self,
        source: &Arc<dyn ReadAtSource>,
        byte_start: u64,
        byte_length: usize,
        operation: impl FnOnce(&str) -> ResultValue,
    ) -> Result<ResultValue, DocumentError> {
        if let Some(entry_index) = self
            .entries
            .iter()
            .position(|entry| entry.contains(source, byte_start, byte_length))
        {
            return Ok(operation(&self.entries[entry_index].logical_text));
        }

        let mut bytes = vec![0_u8; byte_length];
        source.read_exact_at(&mut bytes, byte_start)?;
        let source_text = String::from_utf8(bytes)
            .map_err(|_| invalid_data_error("source changed after its UTF-8 index was built"))?;
        let logical_text = if source_text.contains('\r') {
            normalize_source_text(&source_text)
        } else {
            source_text
        };
        if self.entries.len() == MAX_CACHED_SOURCE_PIECES {
            self.entries.remove(0);
        }
        self.entries.push(SourceCacheEntry {
            source: source.clone(),
            byte_start,
            byte_length,
            logical_text,
        });
        let logical_text = &self
            .entries
            .last()
            .expect("a source cache entry was just inserted")
            .logical_text;
        Ok(operation(logical_text))
    }

    /// Runs an operation over one source piece's raw and logical text.
    ///
    /// Raw text is reread into one bounded temporary allocation instead of
    /// doubling every logical cache entry. Only source-piece splitting and
    /// invariant validation require both representations concurrently.
    fn with_source_text<ResultValue>(
        &mut self,
        source: &Arc<dyn ReadAtSource>,
        byte_start: u64,
        byte_length: usize,
        operation: impl FnOnce(&str, &str) -> ResultValue,
    ) -> Result<ResultValue, DocumentError> {
        if let Some(entry_index) = self
            .entries
            .iter()
            .position(|entry| entry.contains(source, byte_start, byte_length))
        {
            let mut source_bytes = vec![0_u8; byte_length];
            source.read_exact_at(&mut source_bytes, byte_start)?;
            let source_text = String::from_utf8(source_bytes).map_err(|_| {
                invalid_data_error("source changed after its UTF-8 index was built")
            })?;
            return Ok(operation(
                &source_text,
                &self.entries[entry_index].logical_text,
            ));
        }

        let mut source_bytes = vec![0_u8; byte_length];
        source.read_exact_at(&mut source_bytes, byte_start)?;
        let source_text = String::from_utf8(source_bytes)
            .map_err(|_| invalid_data_error("source changed after its UTF-8 index was built"))?;
        let logical_text = normalize_source_text(&source_text);
        if self.entries.len() == MAX_CACHED_SOURCE_PIECES {
            self.entries.remove(0);
        }
        self.entries.push(SourceCacheEntry {
            source: source.clone(),
            byte_start,
            byte_length,
            logical_text,
        });
        let logical_text = &self
            .entries
            .last()
            .expect("a source cache entry was just inserted")
            .logical_text;
        Ok(operation(&source_text, logical_text))
    }
}

/// Identifies immutable storage underlying one piece.
#[derive(Clone)]
enum PieceBacking {
    Source(Arc<dyn ReadAtSource>),
    Edit {
        text: Arc<str>,
        line_ending: LineEnding,
    },
}

/// Refers to one nonempty scalar-aligned range in immutable storage.
#[derive(Clone)]
struct Piece {
    backing: PieceBacking,
    byte_start: u64,
    summary: TextSummary,
}

impl Piece {
    /// Creates a source piece from one already-read valid UTF-8 range.
    fn from_source(
        source: Arc<dyn ReadAtSource>,
        byte_start: usize,
        text: &str,
    ) -> Result<Self, DocumentError> {
        require_piece_text(text)?;
        let byte_start = u64::try_from(byte_start)
            .map_err(|_| invalid_data_error("source piece offset exceeds u64"))?;
        let byte_end = byte_start
            .checked_add(u64::try_from(text.len()).expect("piece length must fit u64"))
            .ok_or_else(invalid_tree_summary)?;
        if byte_end > source.len() {
            return Err(invalid_data_error("source piece exceeds its fixed length"));
        }
        Ok(Self {
            backing: PieceBacking::Source(source),
            byte_start,
            summary: TextSummary::from_source_text(text),
        })
    }

    /// Creates an edit piece over one valid UTF-8 backing range.
    fn from_edit(
        edit: Arc<str>,
        byte_start: usize,
        byte_end: usize,
        line_ending: LineEnding,
    ) -> Result<Self, DocumentError> {
        let text = edit
            .get(byte_start..byte_end)
            .ok_or_else(|| invalid_data_error("edit piece range is not scalar-aligned"))?;
        require_piece_text(text)?;
        if text.contains('\r') {
            return Err(invalid_data_error(
                "edit piece contains a non-normalized carriage return",
            ));
        }
        let summary = TextSummary::from_edit_text(text, line_ending);
        Ok(Self {
            backing: PieceBacking::Edit {
                text: edit,
                line_ending,
            },
            byte_start: u64::try_from(byte_start)
                .map_err(|_| invalid_data_error("edit piece offset exceeds u64"))?,
            summary,
        })
    }

    /// Runs an operation over this piece's complete validated text.
    fn with_text<ResultValue>(
        &self,
        cache: &mut PieceReadCache,
        operation: impl FnOnce(&str) -> ResultValue,
    ) -> Result<ResultValue, DocumentError> {
        match &self.backing {
            PieceBacking::Source(source) => cache.with_text(
                source,
                self.byte_start,
                self.summary.serialized_bytes,
                operation,
            ),
            PieceBacking::Edit { text: edit, .. } => {
                let byte_start = usize::try_from(self.byte_start)
                    .map_err(|_| invalid_data_error("edit piece offset exceeds usize"))?;
                let byte_end = byte_start
                    .checked_add(self.summary.bytes)
                    .ok_or_else(invalid_tree_summary)?;
                let text = edit
                    .get(byte_start..byte_end)
                    .ok_or_else(|| invalid_data_error("edit piece range is not scalar-aligned"))?;
                Ok(operation(text))
            }
        }
    }

    /// Splits this piece at one scalar-aligned local byte offset.
    fn split(
        &self,
        byte_offset: usize,
        cache: &mut PieceReadCache,
    ) -> Result<(Self, Self), DocumentError> {
        debug_assert!(byte_offset > 0 && byte_offset < self.summary.bytes);
        match &self.backing {
            PieceBacking::Source(source) => cache.with_source_text(
                source,
                self.byte_start,
                self.summary.serialized_bytes,
                |source_text, logical_text| -> Result<(Self, Self), DocumentError> {
                    if !logical_text.is_char_boundary(byte_offset) {
                        return Err(invalid_data_error(
                            "piece split offset is not scalar-aligned",
                        ));
                    }
                    let source_byte_offset =
                        source_byte_offset_at_logical_byte(source_text, byte_offset)?;
                    let (left_text, right_text) = source_text.split_at(source_byte_offset);
                    let right_byte_start = self
                        .byte_start
                        .checked_add(
                            u64::try_from(source_byte_offset)
                                .expect("bounded source offset must fit u64"),
                        )
                        .ok_or_else(invalid_tree_summary)?;
                    let left = Piece::from_source(
                        source.clone(),
                        usize::try_from(self.byte_start)
                            .map_err(|_| invalid_data_error("source piece offset exceeds usize"))?,
                        left_text,
                    )?;
                    let right = Piece::from_source(
                        source.clone(),
                        usize::try_from(right_byte_start)
                            .map_err(|_| invalid_data_error("source piece offset exceeds usize"))?,
                        right_text,
                    )?;
                    Ok((left, right))
                },
            )?,
            PieceBacking::Edit { text, line_ending } => {
                let is_char_boundary =
                    self.with_text(cache, |piece_text| piece_text.is_char_boundary(byte_offset))?;
                if !is_char_boundary {
                    return Err(invalid_data_error(
                        "piece split offset is not scalar-aligned",
                    ));
                }
                let left = self.edit_subpiece(text, *line_ending, 0, byte_offset)?;
                let right =
                    self.edit_subpiece(text, *line_ending, byte_offset, self.summary.bytes)?;
                Ok((left, right))
            }
        }
    }

    /// Creates one nonempty scalar-aligned edit subpiece.
    fn edit_subpiece(
        &self,
        edit: &Arc<str>,
        line_ending: LineEnding,
        byte_start: usize,
        byte_end: usize,
    ) -> Result<Self, DocumentError> {
        let piece_byte_start = usize::try_from(self.byte_start)
            .map_err(|_| invalid_data_error("edit piece offset exceeds usize"))?;
        let absolute_byte_start = piece_byte_start
            .checked_add(byte_start)
            .ok_or_else(invalid_tree_summary)?;
        let absolute_byte_end = piece_byte_start
            .checked_add(byte_end)
            .ok_or_else(invalid_tree_summary)?;
        let text = edit
            .get(absolute_byte_start..absolute_byte_end)
            .ok_or_else(|| invalid_data_error("edit subpiece range is not scalar-aligned"))?;
        require_piece_text(text)?;
        let summary = TextSummary::from_edit_text(text, line_ending);
        if summary.bytes <= edit.len() / EDIT_BACKING_TRIM_RATIO {
            return Ok(Self {
                backing: PieceBacking::Edit {
                    text: Arc::from(text),
                    line_ending,
                },
                byte_start: 0,
                summary,
            });
        }
        Ok(Self {
            backing: self.backing.clone(),
            byte_start: u64::try_from(absolute_byte_start)
                .map_err(|_| invalid_data_error("edit piece offset exceeds u64"))?,
            summary,
        })
    }

    /// Coalesces contiguous ranges from the same immutable backing.
    fn coalesce(&self, other: &Self) -> Result<Option<Self>, DocumentError> {
        let combined_backing_bytes = self
            .backing_byte_length()
            .checked_add(other.backing_byte_length())
            .ok_or_else(invalid_tree_summary)?;
        if combined_backing_bytes > MAX_PIECE_BYTES {
            return Ok(None);
        }
        let self_byte_end = self
            .byte_start
            .checked_add(
                u64::try_from(self.backing_byte_length()).map_err(|_| invalid_tree_summary())?,
            )
            .ok_or_else(invalid_tree_summary)?;
        if self_byte_end != other.byte_start {
            return Ok(None);
        }
        if !self.shares_backing(other) {
            return Ok(None);
        }
        Ok(Some(Self {
            backing: self.backing.clone(),
            byte_start: self.byte_start,
            summary: self.summary.checked_add(other.summary)?,
        }))
    }

    /// Returns whether two pieces refer to the same immutable storage and format.
    fn shares_backing(&self, other: &Self) -> bool {
        match (&self.backing, &other.backing) {
            (PieceBacking::Source(left), PieceBacking::Source(right)) => Arc::ptr_eq(left, right),
            (
                PieceBacking::Edit {
                    text: left,
                    line_ending: left_line_ending,
                },
                PieceBacking::Edit {
                    text: right,
                    line_ending: right_line_ending,
                },
            ) => Arc::ptr_eq(left, right) && left_line_ending == right_line_ending,
            (PieceBacking::Source(_), PieceBacking::Edit { .. })
            | (PieceBacking::Edit { .. }, PieceBacking::Source(_)) => false,
        }
    }

    /// Returns whether this piece owns materialized edit text.
    fn is_edit(&self) -> bool {
        matches!(self.backing, PieceBacking::Edit { .. })
    }

    /// Returns the byte length used to address this immutable backing.
    fn backing_byte_length(&self) -> usize {
        match self.backing {
            PieceBacking::Source(_) => self.summary.serialized_bytes,
            PieceBacking::Edit { .. } => self.summary.bytes,
        }
    }

    /// Merges ordered edit pieces into one bounded edit allocation.
    fn merge_edits(pieces: &[Self], cache: &mut PieceReadCache) -> Result<Self, DocumentError> {
        if pieces.is_empty() {
            return Err(invalid_data_error("cannot merge an empty piece sequence"));
        }
        if pieces.iter().any(|piece| !piece.is_edit()) {
            return Err(invalid_data_error("cannot materialize a source piece"));
        }
        let line_ending = match &pieces[0].backing {
            PieceBacking::Edit { line_ending, .. } => *line_ending,
            PieceBacking::Source(_) => unreachable!("source pieces were rejected"),
        };
        if pieces.iter().any(|piece| {
            !matches!(
                &piece.backing,
                PieceBacking::Edit {
                    line_ending: piece_line_ending,
                    ..
                } if *piece_line_ending == line_ending
            )
        }) {
            return Err(invalid_data_error(
                "cannot merge edits with different line endings",
            ));
        }
        let combined_bytes = pieces.iter().try_fold(0_usize, |bytes, piece| {
            bytes
                .checked_add(piece.summary.bytes)
                .ok_or_else(invalid_tree_summary)
        })?;
        if combined_bytes > MAX_PIECE_BYTES {
            return Err(invalid_data_error("merged piece exceeds its byte bound"));
        }

        let mut text = String::with_capacity(combined_bytes);
        for piece in pieces {
            piece.with_text(cache, |piece_text| text.push_str(piece_text))?;
        }
        let edit: Arc<str> = Arc::from(text);
        Self::from_edit(edit, 0, combined_bytes, line_ending)
    }

    /// Writes this piece while reusing one bounded source buffer.
    fn write_to(
        &self,
        writer: &mut impl Write,
        source_buffer: &mut [u8],
    ) -> Result<(), DocumentError> {
        match &self.backing {
            PieceBacking::Source(source) => {
                let destination = source_buffer
                    .get_mut(..self.summary.serialized_bytes)
                    .ok_or_else(|| invalid_data_error("source buffer is smaller than its piece"))?;
                source.read_exact_at(destination, self.byte_start)?;
                std::str::from_utf8(destination).map_err(|_| {
                    invalid_data_error("source changed after its UTF-8 index was built")
                })?;
                writer.write_all(destination)?;
            }
            PieceBacking::Edit {
                text: edit,
                line_ending,
            } => {
                let byte_start = usize::try_from(self.byte_start)
                    .map_err(|_| invalid_data_error("edit piece offset exceeds usize"))?;
                let byte_end = byte_start
                    .checked_add(self.summary.bytes)
                    .ok_or_else(invalid_tree_summary)?;
                let text = edit
                    .get(byte_start..byte_end)
                    .ok_or_else(|| invalid_data_error("edit piece range is not scalar-aligned"))?;
                write_edit_text(writer, text, *line_ending, source_buffer)?;
            }
        }
        Ok(())
    }
}

/// Stores one immutable AVL node.
enum Node {
    Leaf(Piece),
    Branch {
        left: Arc<Self>,
        right: Arc<Self>,
        height: usize,
        summary: TextSummary,
    },
}

impl Node {
    /// Returns this node's exact subtree summary.
    fn summary(&self) -> TextSummary {
        match self {
            Self::Leaf(piece) => piece.summary,
            Self::Branch { summary, .. } => *summary,
        }
    }

    /// Returns this node's AVL height.
    fn height(&self) -> usize {
        match self {
            Self::Leaf(_) => 1,
            Self::Branch { height, .. } => *height,
        }
    }
}

type Tree = Option<Arc<Node>>;

/// Stores the summaries immediately before and after one LF.
struct LineFeedPosition {
    before: LogicalPosition,
    after: LogicalPosition,
}

/// Counts source line endings and records their first-observed order.
#[derive(Default)]
struct LineEndingStatistics {
    counts: [usize; 3],
    first_positions: [Option<usize>; 3],
    observed: usize,
}

impl LineEndingStatistics {
    /// Records every complete raw line ending in one source piece.
    fn observe(&mut self, text: &str) {
        let bytes = text.as_bytes();
        let mut byte_offset = 0;
        while byte_offset < bytes.len() {
            let line_ending = match bytes[byte_offset] {
                b'\r' if bytes.get(byte_offset + 1) == Some(&b'\n') => {
                    byte_offset += 2;
                    Some(LineEnding::CrLf)
                }
                b'\r' => {
                    byte_offset += 1;
                    Some(LineEnding::Cr)
                }
                b'\n' => {
                    byte_offset += 1;
                    Some(LineEnding::Lf)
                }
                _ => {
                    byte_offset += 1;
                    None
                }
            };
            if let Some(line_ending) = line_ending {
                let index = line_ending_index(line_ending);
                self.first_positions[index].get_or_insert(self.observed);
                self.counts[index] += 1;
                self.observed += 1;
            }
        }
    }

    /// Returns the most common style, choosing its first occurrence on ties.
    fn preferred(&self) -> LineEnding {
        let max_count = *self
            .counts
            .iter()
            .max()
            .expect("line-ending statistics must contain every style");
        if max_count == 0 {
            return LineEnding::Lf;
        }
        [LineEnding::Lf, LineEnding::CrLf, LineEnding::Cr]
            .into_iter()
            .filter(|line_ending| self.counts[line_ending_index(*line_ending)] == max_count)
            .min_by_key(|line_ending| {
                self.first_positions[line_ending_index(*line_ending)]
                    .expect("observed line-ending styles must have a first position")
            })
            .expect("at least one line-ending style must have the maximum count")
    }
}

/// Returns the stable statistics index for one line-ending style.
const fn line_ending_index(line_ending: LineEnding) -> usize {
    match line_ending {
        LineEnding::Lf => 0,
        LineEnding::CrLf => 1,
        LineEnding::Cr => 2,
    }
}

/// Accumulates one bounded scalar-aligned text prefix.
struct PrefixState {
    text: String,
    utf16_units: usize,
    copied_bytes: usize,
    range_bytes: usize,
    max_utf16_units: usize,
    stopped: bool,
}

impl PrefixState {
    /// Creates an empty bounded prefix accumulator.
    fn new(range_bytes: usize, max_utf16_units: usize) -> Self {
        Self {
            text: String::new(),
            utf16_units: 0,
            copied_bytes: 0,
            range_bytes,
            max_utf16_units,
            stopped: false,
        }
    }

    /// Appends complete scalars without exceeding the configured UTF-16 bound.
    fn append(&mut self, text: &str) {
        if self.stopped {
            return;
        }
        let mut appended_bytes = 0;
        for character in text.chars() {
            let character_utf16_units = character.len_utf16();
            let Some(next_utf16_units) = self.utf16_units.checked_add(character_utf16_units) else {
                self.stopped = true;
                break;
            };
            if next_utf16_units > self.max_utf16_units {
                self.stopped = true;
                break;
            }
            self.utf16_units = next_utf16_units;
            appended_bytes += character.len_utf8();
            self.copied_bytes += character.len_utf8();
            if self.copied_bytes == self.range_bytes {
                break;
            }
        }
        self.text.push_str(&text[..appended_bytes]);
    }
}

/// Tracks byte comparison across consecutive tree leaves.
struct CompareState<'replacement> {
    replacement: &'replacement [u8],
    compared_bytes: usize,
    matches: bool,
}

/// Builds one balanced tree over an immutable edit allocation.
fn edit_tree(text: &str, line_ending: LineEnding) -> Tree {
    if text.is_empty() {
        return None;
    }
    let mut leaves = Vec::with_capacity(text.len().div_ceil(MAX_PIECE_BYTES));
    let mut byte_start = 0;
    while byte_start < text.len() {
        let mut byte_end = byte_start.saturating_add(MAX_PIECE_BYTES).min(text.len());
        while !text.is_char_boundary(byte_end) {
            byte_end -= 1;
        }
        let edit: Arc<str> = Arc::from(&text[byte_start..byte_end]);
        let piece = Piece::from_edit(edit, 0, byte_end - byte_start, line_ending)
            .expect("valid edit pieces must satisfy their invariants");
        leaves.push(Arc::new(Node::Leaf(piece)));
        byte_start = byte_end;
    }
    build_balanced_tree(&leaves).expect("valid edit summaries must fit")
}

/// Builds a height-balanced immutable tree from ordered leaves.
fn build_balanced_tree(leaves: &[Arc<Node>]) -> Result<Tree, DocumentError> {
    if leaves.is_empty() {
        return Ok(None);
    }
    Ok(Some(build_balanced_nodes(leaves)?))
}

/// Builds one nonempty height-balanced subtree from ordered leaves.
fn build_balanced_nodes(leaves: &[Arc<Node>]) -> Result<Arc<Node>, DocumentError> {
    debug_assert!(!leaves.is_empty());
    if leaves.len() == 1 {
        return Ok(leaves[0].clone());
    }
    let middle = leaves.len() / 2;
    let left = build_balanced_nodes(&leaves[..middle])?;
    let right = build_balanced_nodes(&leaves[middle..])?;
    new_branch(left, right)
}

/// Creates one branch with exact checked summaries.
fn new_branch(left: Arc<Node>, right: Arc<Node>) -> Result<Arc<Node>, DocumentError> {
    let height = left
        .height()
        .max(right.height())
        .checked_add(1)
        .ok_or_else(invalid_tree_summary)?;
    let summary = left.summary().checked_add(right.summary())?;
    Ok(Arc::new(Node::Branch {
        left,
        right,
        height,
        summary,
    }))
}

/// Returns the summary of an optional tree.
fn tree_summary(tree: &Tree) -> TextSummary {
    tree.as_deref()
        .map_or_else(TextSummary::default, Node::summary)
}

/// Concatenates two ordered persistent AVL trees.
fn concatenate_trees(left: Tree, right: Tree) -> Result<Tree, DocumentError> {
    match (left, right) {
        (None, tree) | (tree, None) => Ok(tree),
        (Some(left), Some(right)) => Ok(Some(concatenate_nodes(left, right)?)),
    }
}

/// Concatenates trees while compacting similarly sized boundary pieces.
fn compact_concatenate_trees(
    left: Tree,
    right: Tree,
    cache: &mut PieceReadCache,
) -> Result<Tree, DocumentError> {
    let (Some(_), Some(_)) = (&left, &right) else {
        return concatenate_trees(left, right);
    };
    let left_piece = last_piece(&left)?;
    let right_piece = first_piece(&right)?;
    if let Some(coalesced_piece) = left_piece.coalesce(right_piece)? {
        let (left_remainder, _) = pop_last_piece(&left)?;
        let (_, right_remainder) = pop_first_piece(&right)?;
        let coalesced_tree = Some(Arc::new(Node::Leaf(coalesced_piece)));
        let with_prefix = compact_concatenate_trees(left_remainder, coalesced_tree, cache)?;
        return compact_concatenate_trees(with_prefix, right_remainder, cache);
    }

    let should_merge = should_merge_pieces(left_piece, right_piece);
    if should_merge {
        let (left_remainder, left_piece) = pop_last_piece(&left)?;
        let (right_piece, right_remainder) = pop_first_piece(&right)?;
        let merged_piece = Piece::merge_edits(&[left_piece, right_piece], cache)?;
        let merged_tree = Some(Arc::new(Node::Leaf(merged_piece)));
        let with_prefix = compact_concatenate_trees(left_remainder, merged_tree, cache)?;
        return compact_concatenate_trees(with_prefix, right_remainder, cache);
    }

    compact_dense_boundary(left, right, cache)
}

/// Compacts an overly fragmented boundary through one bounded allocation.
fn compact_dense_boundary(
    left: Tree,
    right: Tree,
    cache: &mut PieceReadCache,
) -> Result<Tree, DocumentError> {
    let mut left_pieces = Vec::with_capacity(MAX_DENSE_BOUNDARY_PIECES);
    collect_last_pieces(
        left.as_deref().ok_or_else(invalid_tree_summary)?,
        &mut left_pieces,
    );
    let mut right_pieces = Vec::with_capacity(MAX_DENSE_BOUNDARY_PIECES);
    collect_first_pieces(
        right.as_deref().ok_or_else(invalid_tree_summary)?,
        &mut right_pieces,
    );
    let Some((left_count, right_count)) = dense_boundary_piece_counts(&left_pieces, &right_pieces)?
    else {
        return concatenate_trees(left, right);
    };

    let (left_remainder, mut merged_pieces) = pop_last_pieces(left, left_count)?;
    let (mut right_boundary_pieces, right_remainder) = pop_first_pieces(right, right_count)?;
    merged_pieces.append(&mut right_boundary_pieces);
    let merged_piece = Piece::merge_edits(&merged_pieces, cache)?;
    let merged_tree = Some(Arc::new(Node::Leaf(merged_piece)));
    let with_prefix = concatenate_trees(left_remainder, merged_tree)?;
    concatenate_trees(with_prefix, right_remainder)
}

/// Selects one dense boundary range that fits a single bounded piece.
fn dense_boundary_piece_counts(
    left_pieces: &[Piece],
    right_pieces: &[Piece],
) -> Result<Option<(usize, usize)>, DocumentError> {
    let mut best_selection = None;
    let mut left_bytes = 0_usize;
    let mut left_contains_source = false;
    for (left_index, left_piece) in left_pieces.iter().enumerate() {
        left_bytes = left_bytes
            .checked_add(left_piece.summary.bytes)
            .ok_or_else(invalid_tree_summary)?;
        left_contains_source |= !left_piece.is_edit();
        let mut right_bytes = 0_usize;
        let mut right_contains_source = false;
        for (right_index, right_piece) in right_pieces.iter().enumerate() {
            right_bytes = right_bytes
                .checked_add(right_piece.summary.bytes)
                .ok_or_else(invalid_tree_summary)?;
            right_contains_source |= !right_piece.is_edit();
            let piece_count = left_index
                .checked_add(right_index)
                .and_then(|index_sum| index_sum.checked_add(2))
                .ok_or_else(invalid_tree_summary)?;
            if piece_count <= MAX_DENSE_BOUNDARY_PIECES {
                continue;
            }
            let combined_bytes = left_bytes
                .checked_add(right_bytes)
                .ok_or_else(invalid_tree_summary)?;
            if combined_bytes > MAX_PIECE_BYTES {
                break;
            }
            if left_contains_source || right_contains_source {
                continue;
            }
            let selection = (piece_count, combined_bytes, left_index + 1, right_index + 1);
            if best_selection.is_none_or(|best: (usize, usize, usize, usize)| {
                selection.0 > best.0 || (selection.0 == best.0 && selection.1 < best.1)
            }) {
                best_selection = Some(selection);
            }
        }
    }
    Ok(best_selection.map(|(_, _, left_count, right_count)| (left_count, right_count)))
}

/// Collects the nearest trailing pieces in reverse document order.
fn collect_last_pieces(node: &Node, pieces: &mut Vec<Piece>) {
    if pieces.len() == MAX_DENSE_BOUNDARY_PIECES {
        return;
    }
    match node {
        Node::Leaf(piece) => pieces.push(piece.clone()),
        Node::Branch { left, right, .. } => {
            collect_last_pieces(right, pieces);
            collect_last_pieces(left, pieces);
        }
    }
}

/// Collects the nearest leading pieces in document order.
fn collect_first_pieces(node: &Node, pieces: &mut Vec<Piece>) {
    if pieces.len() == MAX_DENSE_BOUNDARY_PIECES {
        return;
    }
    match node {
        Node::Leaf(piece) => pieces.push(piece.clone()),
        Node::Branch { left, right, .. } => {
            collect_first_pieces(left, pieces);
            collect_first_pieces(right, pieces);
        }
    }
}

/// Returns whether two boundary pieces should merge amortized-efficiently.
fn should_merge_pieces(left: &Piece, right: &Piece) -> bool {
    if !left.is_edit() || !right.is_edit() {
        return false;
    }
    let smaller_bytes = left.summary.bytes.min(right.summary.bytes);
    let larger_bytes = left.summary.bytes.max(right.summary.bytes);
    left.summary
        .bytes
        .checked_add(right.summary.bytes)
        .is_some_and(|combined_bytes| combined_bytes <= MAX_PIECE_BYTES)
        && larger_bytes <= smaller_bytes.saturating_mul(2)
}

/// Returns the first piece in one nonempty tree.
fn first_piece(tree: &Tree) -> Result<&Piece, DocumentError> {
    first_node_piece(tree.as_deref().ok_or_else(invalid_tree_summary)?)
}

/// Returns the first piece below one nonempty node.
fn first_node_piece(node: &Node) -> Result<&Piece, DocumentError> {
    match node {
        Node::Leaf(piece) => Ok(piece),
        Node::Branch { left, .. } => first_node_piece(left),
    }
}

/// Returns the last piece in one nonempty tree.
fn last_piece(tree: &Tree) -> Result<&Piece, DocumentError> {
    last_node_piece(tree.as_deref().ok_or_else(invalid_tree_summary)?)
}

/// Returns the last piece below one nonempty node.
fn last_node_piece(node: &Node) -> Result<&Piece, DocumentError> {
    match node {
        Node::Leaf(piece) => Ok(piece),
        Node::Branch { right, .. } => last_node_piece(right),
    }
}

/// Removes and returns the first piece from one nonempty tree.
fn pop_first_piece(tree: &Tree) -> Result<(Piece, Tree), DocumentError> {
    match tree.as_deref().ok_or_else(invalid_tree_summary)? {
        Node::Leaf(piece) => Ok((piece.clone(), None)),
        Node::Branch { left, right, .. } => {
            let (piece, left_remainder) = pop_first_piece(&Some(left.clone()))?;
            let remainder = concatenate_trees(left_remainder, Some(right.clone()))?;
            Ok((piece, remainder))
        }
    }
}

/// Removes and returns the last piece from one nonempty tree.
fn pop_last_piece(tree: &Tree) -> Result<(Tree, Piece), DocumentError> {
    match tree.as_deref().ok_or_else(invalid_tree_summary)? {
        Node::Leaf(piece) => Ok((None, piece.clone())),
        Node::Branch { left, right, .. } => {
            let (right_remainder, piece) = pop_last_piece(&Some(right.clone()))?;
            let remainder = concatenate_trees(Some(left.clone()), right_remainder)?;
            Ok((remainder, piece))
        }
    }
}

/// Removes trailing pieces and returns them in document order.
fn pop_last_pieces(mut tree: Tree, count: usize) -> Result<(Tree, Vec<Piece>), DocumentError> {
    if count == 0 {
        return Err(invalid_data_error("trailing piece count must be positive"));
    }
    let mut pieces = Vec::with_capacity(count);
    for _ in 0..count {
        let (remainder, piece) = pop_last_piece(&tree)?;
        tree = remainder;
        pieces.push(piece);
    }
    pieces.reverse();
    Ok((tree, pieces))
}

/// Removes leading pieces and returns them in document order.
fn pop_first_pieces(mut tree: Tree, count: usize) -> Result<(Vec<Piece>, Tree), DocumentError> {
    if count == 0 {
        return Err(invalid_data_error("leading piece count must be positive"));
    }
    let mut pieces = Vec::with_capacity(count);
    for _ in 0..count {
        let (piece, remainder) = pop_first_piece(&tree)?;
        tree = remainder;
        pieces.push(piece);
    }
    Ok((pieces, tree))
}

/// Concatenates two nonempty ordered AVL subtrees.
fn concatenate_nodes(left: Arc<Node>, right: Arc<Node>) -> Result<Arc<Node>, DocumentError> {
    if left.height() > right.height() + 1 {
        let Node::Branch {
            left: left_left,
            right: left_right,
            ..
        } = left.as_ref()
        else {
            return Err(invalid_tree_summary());
        };
        let joined_right = concatenate_nodes(left_right.clone(), right)?;
        return rebalance(left_left.clone(), joined_right);
    }
    if right.height() > left.height() + 1 {
        let Node::Branch {
            left: right_left,
            right: right_right,
            ..
        } = right.as_ref()
        else {
            return Err(invalid_tree_summary());
        };
        let joined_left = concatenate_nodes(left, right_left.clone())?;
        return rebalance(joined_left, right_right.clone());
    }
    new_branch(left, right)
}

/// Restores the AVL balance of two ordered nonempty subtrees.
fn rebalance(left: Arc<Node>, right: Arc<Node>) -> Result<Arc<Node>, DocumentError> {
    if left.height() > right.height() + 1 {
        let Node::Branch {
            left: left_left,
            right: left_right,
            ..
        } = left.as_ref()
        else {
            return Err(invalid_tree_summary());
        };
        if left_left.height() >= left_right.height() {
            return new_branch(left_left.clone(), new_branch(left_right.clone(), right)?);
        }
        let Node::Branch {
            left: middle_left,
            right: middle_right,
            ..
        } = left_right.as_ref()
        else {
            return Err(invalid_tree_summary());
        };
        return new_branch(
            new_branch(left_left.clone(), middle_left.clone())?,
            new_branch(middle_right.clone(), right)?,
        );
    }
    if right.height() > left.height() + 1 {
        let Node::Branch {
            left: right_left,
            right: right_right,
            ..
        } = right.as_ref()
        else {
            return Err(invalid_tree_summary());
        };
        if right_right.height() >= right_left.height() {
            return new_branch(new_branch(left, right_left.clone())?, right_right.clone());
        }
        let Node::Branch {
            left: middle_left,
            right: middle_right,
            ..
        } = right_left.as_ref()
        else {
            return Err(invalid_tree_summary());
        };
        return new_branch(
            new_branch(left, middle_left.clone())?,
            new_branch(middle_right.clone(), right_right.clone())?,
        );
    }
    new_branch(left, right)
}

/// Splits a persistent tree at one scalar-aligned logical byte offset.
fn split_tree(
    tree: Tree,
    byte_offset: usize,
    cache: &mut PieceReadCache,
) -> Result<(Tree, Tree), DocumentError> {
    let Some(node) = tree else {
        if byte_offset == 0 {
            return Ok((None, None));
        }
        return Err(invalid_data_error("tree split exceeds the document"));
    };
    let total_bytes = node.summary().bytes;
    if byte_offset > total_bytes {
        return Err(invalid_data_error("tree split exceeds the document"));
    }
    if byte_offset == 0 {
        return Ok((None, Some(node)));
    }
    if byte_offset == total_bytes {
        return Ok((Some(node), None));
    }

    match node.as_ref() {
        Node::Leaf(piece) => {
            let (left, right) = piece.split(byte_offset, cache)?;
            Ok((
                Some(Arc::new(Node::Leaf(left))),
                Some(Arc::new(Node::Leaf(right))),
            ))
        }
        Node::Branch { left, right, .. } => {
            let left_bytes = left.summary().bytes;
            match byte_offset.cmp(&left_bytes) {
                Ordering::Less => {
                    let (prefix, left_remainder) =
                        split_tree(Some(left.clone()), byte_offset, cache)?;
                    let suffix = concatenate_trees(left_remainder, Some(right.clone()))?;
                    Ok((prefix, suffix))
                }
                Ordering::Equal => Ok((Some(left.clone()), Some(right.clone()))),
                Ordering::Greater => {
                    let (right_prefix, suffix) =
                        split_tree(Some(right.clone()), byte_offset - left_bytes, cache)?;
                    let prefix = concatenate_trees(Some(left.clone()), right_prefix)?;
                    Ok((prefix, suffix))
                }
            }
        }
    }
}

/// Resolves an exact global UTF-16 boundary to its complete prefix summary.
fn position_at_utf16(
    tree: &Tree,
    utf16_offset: usize,
    prefix: LogicalPosition,
    requested_offset: usize,
    cache: &mut PieceReadCache,
) -> Result<LogicalPosition, DocumentError> {
    let Some(node) = tree.as_deref() else {
        if utf16_offset == 0 {
            return Ok(prefix);
        }
        return Err(DocumentError::MisalignedUtf16Offset {
            offset: requested_offset,
        });
    };
    if utf16_offset == 0 {
        return Ok(prefix);
    }
    if utf16_offset == node.summary().utf16_units {
        return prefix.checked_add_summary(node.summary());
    }
    match node {
        Node::Leaf(piece) => {
            let nearest = cache.nearest_position(piece, |position| {
                position.utf16_units.abs_diff(utf16_offset)
            });
            if nearest.utf16_units == utf16_offset {
                return prefix.checked_add(nearest);
            }
            let position = piece.with_text(cache, |text| {
                if piece.summary.bytes == piece.summary.utf16_units {
                    return ascii_position_at_utf16(text, nearest, utf16_offset);
                }
                scan_utf16_position(text, nearest, utf16_offset)?.ok_or(
                    DocumentError::MisalignedUtf16Offset {
                        offset: requested_offset,
                    },
                )
            })??;
            cache.remember_position(piece, position);
            prefix.checked_add(position)
        }
        Node::Branch { left, right, .. } => {
            let left_summary = left.summary();
            match utf16_offset.cmp(&left_summary.utf16_units) {
                Ordering::Less => position_at_utf16(
                    &Some(left.clone()),
                    utf16_offset,
                    prefix,
                    requested_offset,
                    cache,
                ),
                Ordering::Equal => prefix.checked_add_summary(left_summary),
                Ordering::Greater => position_at_utf16(
                    &Some(right.clone()),
                    utf16_offset - left_summary.utf16_units,
                    prefix.checked_add_summary(left_summary)?,
                    requested_offset,
                    cache,
                ),
            }
        }
    }
}

/// Resolves an ASCII offset using its identical byte offset and newline count.
fn ascii_position_at_utf16(
    text: &str,
    origin: LogicalPosition,
    utf16_offset: usize,
) -> Result<LogicalPosition, DocumentError> {
    let line_feeds = if origin.utf16_units <= utf16_offset {
        let traversed = text
            .get(origin.bytes..utf16_offset)
            .ok_or_else(invalid_tree_summary)?;
        checked_metric_add(
            origin.line_feeds,
            memchr::memchr_iter(b'\n', traversed.as_bytes()).count(),
        )?
    } else {
        let traversed = text
            .get(utf16_offset..origin.bytes)
            .ok_or_else(invalid_tree_summary)?;
        origin
            .line_feeds
            .checked_sub(memchr::memchr_iter(b'\n', traversed.as_bytes()).count())
            .ok_or_else(invalid_tree_summary)?
    };
    Ok(LogicalPosition {
        bytes: utf16_offset,
        utf16_units: utf16_offset,
        line_feeds,
    })
}

/// Scans from a known position in either direction without splitting a scalar.
fn scan_utf16_position(
    text: &str,
    origin: LogicalPosition,
    utf16_offset: usize,
) -> Result<Option<LogicalPosition>, DocumentError> {
    let mut position = origin;
    if origin.utf16_units < utf16_offset {
        let suffix = text.get(origin.bytes..).ok_or_else(invalid_tree_summary)?;
        for character in suffix.chars() {
            position.bytes += character.len_utf8();
            position.utf16_units += character.len_utf16();
            position.line_feeds += usize::from(character == '\n');
            if position.utf16_units >= utf16_offset {
                break;
            }
        }
    } else if origin.utf16_units > utf16_offset {
        let prefix = text.get(..origin.bytes).ok_or_else(invalid_tree_summary)?;
        for character in prefix.chars().rev() {
            position = position.checked_sub(LogicalPosition {
                bytes: character.len_utf8(),
                utf16_units: character.len_utf16(),
                line_feeds: usize::from(character == '\n'),
            })?;
            if position.utf16_units <= utf16_offset {
                break;
            }
        }
    }
    Ok((position.utf16_units == utf16_offset).then_some(position))
}

/// Finds one LF by subtree summary without scanning preceding text.
fn find_line_feed(
    tree: &Tree,
    line_feed_index: usize,
    prefix: LogicalPosition,
    cache: &mut PieceReadCache,
) -> Result<LineFeedPosition, DocumentError> {
    let node = tree.as_deref().ok_or_else(invalid_tree_summary)?;
    match node {
        Node::Leaf(piece) => {
            let nearest = cache.nearest_position(piece, |position| {
                position.line_feeds.abs_diff(line_feed_index)
            });
            let position =
                piece.with_text(cache, |text| scan_line_feed(text, nearest, line_feed_index))??;
            cache.remember_position(piece, position.before);
            cache.remember_position(piece, position.after);
            Ok(LineFeedPosition {
                before: prefix.checked_add(position.before)?,
                after: prefix.checked_add(position.after)?,
            })
        }
        Node::Branch { left, right, .. } => {
            let left_summary = left.summary();
            if line_feed_index < left_summary.line_feeds {
                find_line_feed(&Some(left.clone()), line_feed_index, prefix, cache)
            } else {
                find_line_feed(
                    &Some(right.clone()),
                    line_feed_index - left_summary.line_feeds,
                    prefix.checked_add_summary(left_summary)?,
                    cache,
                )
            }
        }
    }
}

/// Finds a local newline by scanning from a nearby validated position.
fn scan_line_feed(
    text: &str,
    origin: LogicalPosition,
    line_feed_index: usize,
) -> Result<LineFeedPosition, DocumentError> {
    let mut observed_line_feeds = origin.line_feeds;
    if origin.line_feeds <= line_feed_index {
        let suffix = text.get(origin.bytes..).ok_or_else(invalid_tree_summary)?;
        for (byte_index, byte) in suffix.bytes().enumerate() {
            if byte != b'\n' {
                continue;
            }
            if observed_line_feeds == line_feed_index {
                let before =
                    origin.checked_add(LogicalPosition::from_text(&suffix[..byte_index]))?;
                let after = before.checked_add(LogicalPosition::from_text("\n"))?;
                return Ok(LineFeedPosition { before, after });
            }
            observed_line_feeds += 1;
        }
    } else {
        let prefix = text.get(..origin.bytes).ok_or_else(invalid_tree_summary)?;
        for (byte_index, byte) in prefix.bytes().enumerate().rev() {
            if byte != b'\n' {
                continue;
            }
            observed_line_feeds -= 1;
            if observed_line_feeds == line_feed_index {
                let after =
                    origin.checked_sub(LogicalPosition::from_text(&prefix[byte_index + 1..]))?;
                let before = after.checked_sub(LogicalPosition::from_text("\n"))?;
                return Ok(LineFeedPosition { before, after });
            }
        }
    }
    Err(invalid_tree_summary())
}

/// Appends overlapping leaf text until a UTF-16 prefix limit is reached.
fn collect_bounded_prefix(
    tree: &Tree,
    tree_byte_start: usize,
    byte_range: &std::ops::Range<usize>,
    state: &mut PrefixState,
    cache: &mut PieceReadCache,
) -> Result<(), DocumentError> {
    if state.stopped || state.copied_bytes == state.range_bytes {
        return Ok(());
    }
    let Some(node) = tree.as_deref() else {
        return Ok(());
    };
    let tree_byte_end = tree_byte_start
        .checked_add(node.summary().bytes)
        .ok_or_else(invalid_tree_summary)?;
    if byte_range.end <= tree_byte_start || byte_range.start >= tree_byte_end {
        return Ok(());
    }
    match node {
        Node::Leaf(piece) => {
            let overlap_start = byte_range.start.max(tree_byte_start) - tree_byte_start;
            let overlap_end = byte_range.end.min(tree_byte_end) - tree_byte_start;
            piece.with_text(cache, |text| -> Result<(), DocumentError> {
                let overlap = text.get(overlap_start..overlap_end).ok_or_else(|| {
                    invalid_data_error("bounded text range is not scalar-aligned")
                })?;
                state.append(overlap);
                Ok(())
            })??;
        }
        Node::Branch { left, right, .. } => {
            collect_bounded_prefix(
                &Some(left.clone()),
                tree_byte_start,
                byte_range,
                state,
                cache,
            )?;
            let right_byte_start = tree_byte_start
                .checked_add(left.summary().bytes)
                .ok_or_else(invalid_tree_summary)?;
            collect_bounded_prefix(
                &Some(right.clone()),
                right_byte_start,
                byte_range,
                state,
                cache,
            )?;
        }
    }
    Ok(())
}

/// Compares one logical byte range against a replacement without joining it.
fn byte_range_equals(
    tree: &Tree,
    tree_byte_start: usize,
    byte_range: &std::ops::Range<usize>,
    replacement: &[u8],
    cache: &mut PieceReadCache,
) -> Result<bool, DocumentError> {
    if byte_range.len() != replacement.len() {
        return Ok(false);
    }
    let mut state = CompareState {
        replacement,
        compared_bytes: 0,
        matches: true,
    };
    compare_byte_range(tree, tree_byte_start, byte_range, &mut state, cache)?;
    Ok(state.matches && state.compared_bytes == replacement.len())
}

/// Compares overlapping leaves while a range remains equal.
fn compare_byte_range(
    tree: &Tree,
    tree_byte_start: usize,
    byte_range: &std::ops::Range<usize>,
    state: &mut CompareState<'_>,
    cache: &mut PieceReadCache,
) -> Result<(), DocumentError> {
    if !state.matches || state.compared_bytes == state.replacement.len() {
        return Ok(());
    }
    let Some(node) = tree.as_deref() else {
        return Ok(());
    };
    let tree_byte_end = tree_byte_start
        .checked_add(node.summary().bytes)
        .ok_or_else(invalid_tree_summary)?;
    if byte_range.end <= tree_byte_start || byte_range.start >= tree_byte_end {
        return Ok(());
    }
    match node {
        Node::Leaf(piece) => {
            let overlap_start = byte_range.start.max(tree_byte_start) - tree_byte_start;
            let overlap_end = byte_range.end.min(tree_byte_end) - tree_byte_start;
            piece.with_text(cache, |text| {
                let Some(overlap) = text.as_bytes().get(overlap_start..overlap_end) else {
                    state.matches = false;
                    return;
                };
                let replacement_end = state.compared_bytes + overlap.len();
                let Some(expected) = state.replacement.get(state.compared_bytes..replacement_end)
                else {
                    state.matches = false;
                    return;
                };
                state.matches = overlap == expected;
                state.compared_bytes = replacement_end;
            })?;
        }
        Node::Branch { left, right, .. } => {
            compare_byte_range(
                &Some(left.clone()),
                tree_byte_start,
                byte_range,
                state,
                cache,
            )?;
            let right_byte_start = tree_byte_start
                .checked_add(left.summary().bytes)
                .ok_or_else(invalid_tree_summary)?;
            compare_byte_range(
                &Some(right.clone()),
                right_byte_start,
                byte_range,
                state,
                cache,
            )?;
        }
    }
    Ok(())
}

/// Visits every leaf in serialized document order.
fn visit_tree_pieces(tree: &Tree, visitor: &mut impl FnMut(&Piece)) {
    let Some(node) = tree.as_deref() else {
        return;
    };
    visit_node_pieces(node, visitor);
}

/// Visits every leaf below one node in serialized document order.
fn visit_node_pieces(node: &Node, visitor: &mut impl FnMut(&Piece)) {
    match node {
        Node::Leaf(piece) => visitor(piece),
        Node::Branch { left, right, .. } => {
            visit_node_pieces(left, visitor);
            visit_node_pieces(right, visitor);
        }
    }
}

/// Visits every leaf in order while preserving the first failure.
fn visit_tree_pieces_result(
    tree: &Tree,
    visitor: &mut impl FnMut(&Piece) -> Result<(), DocumentError>,
) -> Result<(), DocumentError> {
    let Some(node) = tree.as_deref() else {
        return Ok(());
    };
    visit_node_pieces_result(node, visitor)
}

/// Visits every leaf below one node while preserving the first failure.
fn visit_node_pieces_result(
    node: &Node,
    visitor: &mut impl FnMut(&Piece) -> Result<(), DocumentError>,
) -> Result<(), DocumentError> {
    match node {
        Node::Leaf(piece) => visitor(piece),
        Node::Branch { left, right, .. } => {
            visit_node_pieces_result(left, visitor)?;
            visit_node_pieces_result(right, visitor)
        }
    }
}

/// Streams one tree in logical order.
fn write_tree(
    tree: &Tree,
    writer: &mut impl Write,
    source_buffer: &mut [u8],
    previous_serialized_byte: &mut Option<u8>,
) -> Result<(), DocumentError> {
    let Some(node) = tree.as_deref() else {
        return Ok(());
    };
    match node {
        Node::Leaf(piece) => {
            if *previous_serialized_byte == Some(b'\r')
                && piece.summary.first_serialized_byte == Some(b'\n')
            {
                writer.write_all(b"\r")?;
            }
            piece.write_to(writer, source_buffer)?;
            *previous_serialized_byte = piece.summary.last_serialized_byte;
            Ok(())
        }
        Node::Branch { left, right, .. } => {
            write_tree(
                &Some(left.clone()),
                writer,
                source_buffer,
                previous_serialized_byte,
            )?;
            write_tree(
                &Some(right.clone()),
                writer,
                source_buffer,
                previous_serialized_byte,
            )
        }
    }
}

/// Returns normalized LF text for one bounded raw UTF-8 source piece.
fn normalize_source_text(source_text: &str) -> String {
    if !source_text.contains('\r') {
        return source_text.to_owned();
    }

    let mut logical_text = String::with_capacity(source_text.len());
    let bytes = source_text.as_bytes();
    let mut copied_bytes = 0;
    for carriage_return in memchr::memchr_iter(b'\r', bytes) {
        logical_text.push_str(&source_text[copied_bytes..carriage_return]);
        logical_text.push('\n');
        copied_bytes = carriage_return + 1;
        if bytes.get(copied_bytes) == Some(&b'\n') {
            copied_bytes += 1;
        }
    }
    logical_text.push_str(&source_text[copied_bytes..]);
    logical_text
}

/// Maps one logical UTF-8 boundary to its raw source byte boundary.
fn source_byte_offset_at_logical_byte(
    source_text: &str,
    logical_byte_offset: usize,
) -> Result<usize, DocumentError> {
    let mut source_byte_offset = 0;
    let mut observed_logical_bytes = 0;
    while source_byte_offset < source_text.len() {
        if observed_logical_bytes == logical_byte_offset {
            return Ok(source_byte_offset);
        }
        let remaining = &source_text[source_byte_offset..];
        let character = remaining.chars().next().ok_or_else(invalid_tree_summary)?;
        if character == '\r' {
            source_byte_offset += if remaining.as_bytes().get(1) == Some(&b'\n') {
                2
            } else {
                1
            };
            observed_logical_bytes += 1;
        } else {
            source_byte_offset += character.len_utf8();
            observed_logical_bytes += character.len_utf8();
        }
        if observed_logical_bytes > logical_byte_offset {
            return Err(invalid_data_error(
                "logical source offset is not scalar-aligned",
            ));
        }
    }
    if observed_logical_bytes == logical_byte_offset {
        Ok(source_byte_offset)
    } else {
        Err(invalid_data_error(
            "logical source offset exceeds its piece",
        ))
    }
}

/// Streams normalized edit text using its selected line ending.
fn write_edit_text(
    writer: &mut impl Write,
    text: &str,
    line_ending: LineEnding,
    buffer: &mut [u8],
) -> Result<(), DocumentError> {
    if line_ending == LineEnding::Lf {
        writer.write_all(text.as_bytes())?;
        return Ok(());
    }

    let mut buffered_bytes = 0;
    for byte in text.bytes() {
        let serialized = if byte == b'\n' {
            line_ending.bytes()
        } else {
            std::slice::from_ref(&byte)
        };
        if buffer.len() - buffered_bytes < serialized.len() {
            writer.write_all(&buffer[..buffered_bytes])?;
            buffered_bytes = 0;
        }
        let buffer_end = buffered_bytes
            .checked_add(serialized.len())
            .expect("bounded serialization buffer offset must fit usize");
        buffer[buffered_bytes..buffer_end].copy_from_slice(serialized);
        buffered_bytes = buffer_end;
    }
    writer.write_all(&buffer[..buffered_bytes])?;
    Ok(())
}

/// Returns the longest valid UTF-8 prefix ending within one source candidate.
fn valid_source_prefix(candidate: &[u8], reached_end: bool) -> Result<usize, DocumentError> {
    match std::str::from_utf8(candidate) {
        Ok(_) => Ok(candidate.len()),
        Err(error) if error.error_len().is_some() || reached_end => Err(invalid_data_error(
            "document source contains invalid or truncated UTF-8",
        )),
        Err(error) if error.valid_up_to() > 0 => Ok(error.valid_up_to()),
        Err(_) => Err(invalid_data_error(
            "document source has no bounded valid UTF-8 prefix",
        )),
    }
}

/// Requires one piece to be nonempty, bounded, and valid UTF-8.
fn require_piece_text(text: &str) -> Result<(), DocumentError> {
    if text.is_empty() || text.len() > MAX_PIECE_BYTES {
        return Err(invalid_data_error("piece length is outside its bounds"));
    }
    Ok(())
}

/// Adds one document metric without accepting address-space overflow.
fn checked_metric_add(left: usize, right: usize) -> Result<usize, DocumentError> {
    left.checked_add(right).ok_or_else(invalid_tree_summary)
}

/// Creates an invalid-data error for a broken tree invariant.
fn invalid_tree_summary() -> DocumentError {
    invalid_data_error("piece tree summary is invalid")
}

/// Creates a composable invalid-data document error.
fn invalid_data_error(message: &'static str) -> DocumentError {
    DocumentError::Io(std::io::Error::new(ErrorKind::InvalidData, message))
}

/// Contains recursively recomputed test invariants.
#[cfg(test)]
struct TreeValidation {
    summary: TextSummary,
    height: usize,
}

/// Recomputes and validates every tree invariant for tests.
#[cfg(test)]
fn validate_tree(tree: &Tree, cache: &mut PieceReadCache) -> Result<TreeValidation, DocumentError> {
    let Some(node) = tree.as_deref() else {
        return Ok(TreeValidation {
            summary: TextSummary::default(),
            height: 0,
        });
    };
    match node {
        Node::Leaf(piece) => {
            let observed_summary = match &piece.backing {
                PieceBacking::Source(source) => cache.with_source_text(
                    source,
                    piece.byte_start,
                    piece.summary.serialized_bytes,
                    |source_text, _| TextSummary::from_source_text(source_text),
                )?,
                PieceBacking::Edit { line_ending, .. } => piece.with_text(cache, |text| {
                    TextSummary::from_edit_text(text, *line_ending)
                })?,
            };
            piece.with_text(cache, require_piece_text)??;
            if observed_summary != piece.summary {
                return Err(invalid_tree_summary());
            }
            let byte_end = piece
                .byte_start
                .checked_add(
                    u64::try_from(piece.backing_byte_length()).expect("piece length must fit u64"),
                )
                .ok_or_else(invalid_tree_summary)?;
            match &piece.backing {
                PieceBacking::Source(source) if byte_end > source.len() => {
                    return Err(invalid_data_error("source piece exceeds its fixed length"));
                }
                PieceBacking::Edit { text: edit, .. }
                    if edit.len() > MAX_PIECE_BYTES
                        || byte_end
                            > u64::try_from(edit.len()).expect("edit length must fit u64") =>
                {
                    return Err(invalid_data_error("edit piece exceeds its backing"));
                }
                PieceBacking::Source(_) | PieceBacking::Edit { .. } => {}
            }
            Ok(TreeValidation {
                summary: observed_summary,
                height: 1,
            })
        }
        Node::Branch {
            left,
            right,
            height,
            summary,
        } => {
            let left_validation = validate_tree(&Some(left.clone()), cache)?;
            let right_validation = validate_tree(&Some(right.clone()), cache)?;
            if left_validation.height.abs_diff(right_validation.height) > 1 {
                return Err(invalid_data_error("piece tree is not AVL-balanced"));
            }
            let expected_height = left_validation
                .height
                .max(right_validation.height)
                .checked_add(1)
                .ok_or_else(invalid_tree_summary)?;
            let expected_summary = left_validation
                .summary
                .checked_add(right_validation.summary)?;
            if *height != expected_height || *summary != expected_summary {
                return Err(invalid_tree_summary());
            }
            Ok(TreeValidation {
                summary: expected_summary,
                height: expected_height,
            })
        }
    }
}

/// Counts every leaf below one test tree.
#[cfg(test)]
fn count_tree_leaves(tree: &Tree) -> usize {
    tree.as_deref().map_or(0, count_node_leaves)
}

/// Counts every leaf below one nonempty test node.
#[cfg(test)]
fn count_node_leaves(node: &Node) -> usize {
    match node {
        Node::Leaf(_) => 1,
        Node::Branch { left, right, .. } => count_node_leaves(left)
            .checked_add(count_node_leaves(right))
            .expect("test tree leaf count should fit usize"),
    }
}

/// Counts every edit-backed leaf below one test tree.
#[cfg(test)]
fn count_tree_edit_leaves(tree: &Tree) -> usize {
    tree.as_deref().map_or(0, count_node_edit_leaves)
}

/// Counts every edit-backed leaf below one nonempty test node.
#[cfg(test)]
fn count_node_edit_leaves(node: &Node) -> usize {
    match node {
        Node::Leaf(piece) => usize::from(piece.is_edit()),
        Node::Branch { left, right, .. } => count_node_edit_leaves(left)
            .checked_add(count_node_edit_leaves(right))
            .expect("test tree edit-leaf count should fit usize"),
    }
}

/// Returns the largest edit backing below one test tree.
#[cfg(test)]
fn largest_tree_edit_backing_bytes(tree: &Tree) -> usize {
    tree.as_deref().map_or(0, largest_node_edit_backing_bytes)
}

/// Returns the largest edit backing below one nonempty test node.
#[cfg(test)]
fn largest_node_edit_backing_bytes(node: &Node) -> usize {
    match node {
        Node::Leaf(Piece {
            backing: PieceBacking::Edit { text: edit, .. },
            ..
        }) => edit.len(),
        Node::Leaf(Piece {
            backing: PieceBacking::Source(_),
            ..
        }) => 0,
        Node::Branch { left, right, .. } => {
            largest_node_edit_backing_bytes(left).max(largest_node_edit_backing_bytes(right))
        }
    }
}

#[cfg(test)]
mod tests {
    use std::collections::VecDeque;
    use std::fs::{File, OpenOptions, remove_file};
    use std::io::{ErrorKind, Write};
    use std::os::unix::fs::FileExt;
    use std::path::PathBuf;
    use std::sync::atomic::{AtomicBool, AtomicU64, Ordering as AtomicOrdering};
    use std::sync::{Arc, Mutex};

    use super::{
        LogicalPosition, MAX_CACHED_PIECE_POSITIONS, MAX_DENSE_BOUNDARY_PIECES, MAX_PIECE_BYTES,
        Piece, PieceReadCache, PieceTree, ReadAtSource, ReplaceOutcome, SerializedPayload,
        SerializedSegmentVisitor, SourceClone, TextSummary, UTF8_BOM, Utf16Range,
        normalize_source_text,
    };
    use crate::DocumentError;

    const TRACKING_SHORT_READ_BYTES: usize = 7;
    const ONE_BYTE_READ_BYTES: usize = 1;
    const VIEWPORT_TEST_LINES: usize = 256;
    const EDIT_TEST_OPERATIONS: usize = 256;
    const EDIT_POSITION_STRIDE: usize = 7_919;
    const EDIT_REMOVAL_STRIDE: usize = 17;
    const MAX_EDIT_REMOVAL_SCALARS: usize = 31;
    const SNAPSHOT_INTERVAL: usize = 53;
    const EDIT_COMPACTION_OPERATIONS: usize = 4_096;
    const MAX_COMPACTED_EDIT_LEAVES: usize = 128;
    const SCATTERED_EDIT_POSITIONS: usize = 1_024;
    const SCATTERED_EDIT_ROUNDS: usize = 4;
    const SCATTERED_DELETIONS: usize = 512;
    const FIDELITY_EDIT_OPERATIONS: usize = 128;
    const FIDELITY_POSITION_STRIDE: usize = 37;
    const FIDELITY_REMOVAL_STRIDE: usize = 11;
    const MAX_FIDELITY_REMOVAL_BYTES: usize = 5;
    const FIDELITY_SOURCE_REPETITIONS: usize = 32;
    const PACKAGE_SOURCE_REPETITIONS: usize = 4;
    const PACKAGE_SOURCE_PREFIX: &str = "beautyxt-piece-package-source";
    static NEXT_PACKAGE_SOURCE_ID: AtomicU64 = AtomicU64::new(0);

    #[test]
    fn preserves_unicode_word_counts_when_summarizing_normalized_source() {
        for text in ["", " \t\r\n", "😀alpha\u{2003}β\r\n東京\rword\n\u{a0}end"] {
            let normalized = text.replace("\r\n", "\n").replace('\r', "\n");
            for summary in [
                TextSummary::from_source_text(text),
                TextSummary::from_text(&normalized),
            ] {
                assert_eq!(summary.words, text.split_whitespace().count());
                assert_eq!(summary.chars, normalized.chars().count());
                assert_eq!(summary.utf16_units, normalized.encode_utf16().count());
                assert_eq!(summary.bytes, normalized.len());
                assert_eq!(
                    summary.first_is_word,
                    text.chars().next().map(|c| !c.is_whitespace())
                );
                assert_eq!(
                    summary.last_is_word,
                    text.chars().next_back().map(|c| !c.is_whitespace())
                );
            }
        }
    }

    #[test]
    fn bounds_bulk_prefix_copy_at_unicode_and_piece_boundaries() {
        let source = "a😀β\r\nword\r".repeat(MAX_PIECE_BYTES / 8);
        let normalized = source.replace("\r\n", "\n").replace('\r', "\n");
        let (source_tree, _) = open_tracking_tree(&source, usize::MAX);
        for tree in [source_tree, PieceTree::from_text(&normalized)] {
            let length = tree.summary().utf16_units;
            let mut reader = tree.reader();
            for limit in [
                0,
                1,
                2,
                3,
                4,
                5,
                MAX_PIECE_BYTES - 1,
                MAX_PIECE_BYTES,
                length,
            ] {
                let mut expected_units: Vec<_> = normalized.encode_utf16().take(limit).collect();
                if matches!(expected_units.last(), Some(0xd800..=0xdbff)) {
                    expected_units.pop();
                }
                let expected = String::from_utf16(&expected_units).unwrap();
                let prefix = reader
                    .bounded_text_prefix(Utf16Range::new(0, length), limit)
                    .unwrap();
                assert_eq!(prefix.text, expected);
                assert_eq!(prefix.utf16_units, expected_units.len());
                assert_eq!(prefix.reached_end, expected == normalized);
            }
        }
    }

    #[test]
    fn mixed_line_ending_summaries_match_serialization_in_any_tree_order() {
        let fragments = ["", "\n", "\r", "\r\n", "x", "x\n", "\n\n", "\n\r", "x\r"];
        for first in fragments {
            for second in fragments {
                for third in fragments {
                    let mut serialized = String::new();
                    for fragment in [first, second, third] {
                        if serialized.ends_with('\r') && fragment.starts_with('\n') {
                            serialized.push('\r');
                        }
                        serialized.push_str(fragment);
                    }
                    let expected = TextSummary::from_source_text(&serialized);
                    let left = TextSummary::from_source_text(first);
                    let middle = TextSummary::from_source_text(second);
                    let right = TextSummary::from_source_text(third);
                    assert_eq!(
                        left.checked_add(middle)
                            .and_then(|prefix| prefix.checked_add(right))
                            .expect("small summaries should fit"),
                        expected,
                        "left-associated {first:?}, {second:?}, {third:?}",
                    );
                    assert_eq!(
                        middle
                            .checked_add(right)
                            .and_then(|suffix| left.checked_add(suffix))
                            .expect("small summaries should fit"),
                        expected,
                        "right-associated {first:?}, {second:?}, {third:?}",
                    );
                }
            }
        }
    }

    /// Records one positioned source read request.
    #[derive(Clone, Copy, Debug, Eq, PartialEq)]
    struct ReadRequest {
        offset: u64,
        requested_bytes: usize,
    }

    /// Provides deterministic bytes and records every positioned read.
    struct TrackingSource {
        bytes: Arc<[u8]>,
        maximum_return_bytes: usize,
        read_requests: Mutex<Vec<ReadRequest>>,
    }

    impl TrackingSource {
        /// Creates a tracking source that may return bounded short reads.
        fn new(text: &str, maximum_return_bytes: usize) -> Self {
            assert!(maximum_return_bytes > 0);
            Self {
                bytes: Arc::from(text.as_bytes()),
                maximum_return_bytes,
                read_requests: Mutex::new(Vec::new()),
            }
        }

        /// Removes and returns every recorded read request.
        fn take_read_requests(&self) -> Vec<ReadRequest> {
            std::mem::take(
                &mut *self
                    .read_requests
                    .lock()
                    .expect("tracking read mutex should remain available"),
            )
        }
    }

    impl ReadAtSource for TrackingSource {
        fn len(&self) -> u64 {
            u64::try_from(self.bytes.len()).expect("test source length should fit u64")
        }

        fn read_at(&self, buffer: &mut [u8], offset: u64) -> std::io::Result<usize> {
            self.read_requests
                .lock()
                .expect("tracking read mutex should remain available")
                .push(ReadRequest {
                    offset,
                    requested_bytes: buffer.len(),
                });
            let byte_start = usize::try_from(offset).map_err(|_| {
                std::io::Error::new(ErrorKind::InvalidInput, "test source offset exceeds usize")
            })?;
            let Some(remaining) = self.bytes.get(byte_start..) else {
                return Ok(0);
            };
            let returned_bytes = remaining
                .len()
                .min(buffer.len())
                .min(self.maximum_return_bytes);
            buffer[..returned_bytes].copy_from_slice(&remaining[..returned_bytes]);
            Ok(returned_bytes)
        }
    }

    /// Owns one cloneable file source while recording every positioned read.
    struct CloneableTrackingSource {
        file: File,
        byte_length: u64,
        read_requests: Mutex<Vec<ReadRequest>>,
    }

    impl CloneableTrackingSource {
        /// Creates one unlinked deterministic source and returns its former path.
        fn create(bytes: &[u8]) -> (Arc<Self>, PathBuf) {
            let source_id = NEXT_PACKAGE_SOURCE_ID.fetch_add(1, AtomicOrdering::Relaxed);
            let path = std::env::temp_dir().join(format!(
                "{PACKAGE_SOURCE_PREFIX}-{}-{source_id}",
                std::process::id()
            ));
            let mut file = OpenOptions::new()
                .read(true)
                .write(true)
                .create_new(true)
                .open(&path)
                .expect("unique package test source should be creatable");
            file.write_all(bytes)
                .expect("package test source should be writable");
            file.flush()
                .expect("package test source should flush before indexing");
            (
                Arc::new(Self {
                    file,
                    byte_length: u64::try_from(bytes.len())
                        .expect("package test source length should fit u64"),
                    read_requests: Mutex::new(Vec::new()),
                }),
                path,
            )
        }

        /// Removes and returns every recorded positioned read.
        fn take_read_requests(&self) -> Vec<ReadRequest> {
            std::mem::take(
                &mut *self
                    .read_requests
                    .lock()
                    .expect("cloneable source read mutex should remain available"),
            )
        }
    }

    impl ReadAtSource for CloneableTrackingSource {
        fn len(&self) -> u64 {
            self.byte_length
        }

        fn try_clone_file(&self) -> std::io::Result<Option<File>> {
            self.file.try_clone().map(Some)
        }

        fn read_at(&self, buffer: &mut [u8], offset: u64) -> std::io::Result<usize> {
            self.read_requests
                .lock()
                .expect("cloneable source read mutex should remain available")
                .push(ReadRequest {
                    offset,
                    requested_bytes: buffer.len(),
                });
            self.file.read_at(buffer, offset)
        }
    }

    /// Counts serialized source and payload records without materializing payloads.
    #[derive(Default)]
    struct SegmentCounter {
        source_records: usize,
        payload_records: usize,
        payload_bytes: usize,
    }

    impl SerializedSegmentVisitor for SegmentCounter {
        fn visit_source(
            &mut self,
            _byte_start: u64,
            _byte_length: u64,
        ) -> Result<(), DocumentError> {
            self.source_records += 1;
            Ok(())
        }

        fn visit_payload(&mut self, payload: SerializedPayload<'_>) -> Result<(), DocumentError> {
            self.payload_records += 1;
            self.payload_bytes += payload.byte_length();
            Ok(())
        }
    }

    /// Returns one tree backed by a separately inspectable tracking source.
    fn open_tracking_tree(
        text: &str,
        maximum_return_bytes: usize,
    ) -> (PieceTree, Arc<TrackingSource>) {
        let source = Arc::new(TrackingSource::new(text, maximum_return_bytes));
        let tree = PieceTree::open_source_reader(source.clone())
            .expect("valid tracking source should open");
        (tree, source)
    }

    /// Streams one tree into a UTF-8 string for model comparison.
    fn tree_text(tree: &PieceTree) -> String {
        let mut output = Vec::new();
        tree.write_to(&mut output)
            .expect("valid tree should stream");
        String::from_utf8(output).expect("valid tree should contain UTF-8")
    }

    /// Reads complete normalized logical text for one bounded test tree.
    fn tree_logical_text(tree: &PieceTree) -> String {
        let summary = tree.summary();
        let mut reader = tree.reader();
        reader
            .bounded_text_prefix(Utf16Range::new(0, summary.utf16_units), summary.utf16_units)
            .expect("valid tree should expose its complete logical text")
            .text
    }

    /// Returns every scalar-aligned byte boundary in one string.
    fn scalar_boundaries(text: &str) -> Vec<usize> {
        let mut boundaries = Vec::with_capacity(text.chars().count() + 1);
        boundaries.push(0);
        boundaries.extend(
            text.char_indices()
                .skip(1)
                .map(|(byte_index, _)| byte_index),
        );
        if !text.is_empty() {
            boundaries.push(text.len());
        }
        boundaries
    }

    /// Returns the UTF-16 offset at one scalar-aligned byte boundary.
    fn utf16_offset(text: &str, byte_offset: usize) -> usize {
        text[..byte_offset].encode_utf16().count()
    }

    /// Returns breadth-first midpoint positions within one nonempty byte range.
    fn scattered_byte_positions(bytes: usize, count: usize) -> Vec<usize> {
        assert!(count <= bytes);
        let mut remaining_ranges = VecDeque::new();
        remaining_ranges.push_back(0..bytes);
        let mut positions = Vec::with_capacity(count);
        while positions.len() < count {
            let range = remaining_ranges
                .pop_front()
                .expect("breadth-first edit range should remain available");
            let byte_offset = range.start + range.len() / 2;
            positions.push(byte_offset);
            if range.start < byte_offset {
                remaining_ranges.push_back(range.start..byte_offset);
            }
            if byte_offset + 1 < range.end {
                remaining_ranges.push_back(byte_offset + 1..range.end);
            }
        }
        positions
    }

    /// Extracts the I/O kind from one expected tree-opening failure.
    fn opening_error_kind(result: Result<PieceTree, DocumentError>) -> ErrorKind {
        match result {
            Err(DocumentError::Io(error)) => error.kind(),
            Err(error) => panic!("unexpected document error: {error}"),
            Ok(_) => panic!("invalid source unexpectedly opened"),
        }
    }

    /// Verifies indexing handles a four-byte scalar across a piece boundary.
    #[test]
    fn indexes_cross_piece_utf8_without_retaining_text() {
        let ascii_bytes = MAX_PIECE_BYTES - 1;
        let text = format!("{}😀z", "a".repeat(ascii_bytes));
        let (tree, source) = open_tracking_tree(&text, usize::MAX);

        assert_eq!(
            source.take_read_requests(),
            vec![
                ReadRequest {
                    offset: 0,
                    requested_bytes: UTF8_BOM.len(),
                },
                ReadRequest {
                    offset: 0,
                    requested_bytes: MAX_PIECE_BYTES,
                },
                ReadRequest {
                    offset: u64::try_from(ascii_bytes).expect("test offset should fit u64"),
                    requested_bytes: 5,
                },
            ]
        );
        assert_eq!(tree.summary(), TextSummary::from_text(&text));
        assert_eq!(tree.line_count(), 1);
        assert!(source.take_read_requests().is_empty());

        let mut reader = tree.reader();
        let prefix = reader
            .bounded_text_prefix(Utf16Range::new(ascii_bytes, ascii_bytes + 3), 3)
            .expect("scalar-aligned source range should be readable");
        assert_eq!(prefix.text, "😀z");
        assert!(prefix.reached_end);

        let Err(error) = tree.replace(Utf16Range::new(ascii_bytes + 1, ascii_bytes + 2), "x")
        else {
            panic!("range dividing a surrogate pair unexpectedly succeeded");
        };
        assert!(matches!(
            error,
            DocumentError::MisalignedUtf16Offset { offset }
                if offset == ascii_bytes + 1
        ));
        assert_eq!(tree_text(&tree), text);
        tree.validate_invariants()
            .expect("source tree invariants should hold");
        assert!(
            source
                .take_read_requests()
                .iter()
                .all(|request| request.requested_bytes <= MAX_PIECE_BYTES)
        );
    }

    /// Verifies CRLF normalization spans adjacent source pieces exactly.
    #[test]
    fn recognizes_cross_piece_crlf() {
        let content_bytes = MAX_PIECE_BYTES - 1;
        let text = format!("{}\r\nz", "a".repeat(content_bytes));
        let (tree, _) = open_tracking_tree(&text, usize::MAX);
        let mut reader = tree.reader();

        let first_line = reader
            .line_bounds(0)
            .expect("first line should be readable");
        let second_line = reader
            .line_bounds(1)
            .expect("second line should be readable");

        assert_eq!(first_line.content_end_utf16, content_bytes);
        assert_eq!(first_line.terminator_utf16_units, 1);
        assert_eq!(second_line.content_start_utf16, MAX_PIECE_BYTES);
        assert_eq!(second_line.content_end_utf16, MAX_PIECE_BYTES + 1);
        assert_eq!(second_line.terminator_utf16_units, 0);
        let insertion_offset = tree.summary().utf16_units;
        let ReplaceOutcome::Replaced(edited_tree) = tree
            .replace(Utf16Range::new(insertion_offset, insertion_offset), "\n")
            .expect("cross-piece source insertion should succeed")
        else {
            panic!("text-changing insertion unexpectedly preserved the tree");
        };

        assert_eq!(tree_text(&edited_tree), format!("{text}\r\n"));
        edited_tree
            .validate_invariants()
            .expect("edited cross-piece CRLF tree should remain valid");
    }

    /// Verifies sparse segment planning coalesces source without reading it.
    #[test]
    fn plans_sparse_segments_without_source_reads() {
        let source_bytes = vec![b'x'; MAX_PIECE_BYTES * PACKAGE_SOURCE_REPETITIONS];
        let (source, source_path) = CloneableTrackingSource::create(&source_bytes);
        let tree = PieceTree::open_source_reader(source.clone())
            .expect("cloneable package source should open");
        remove_file(source_path).expect("package test source should become pathname-free");
        let middle = source_bytes.len() / 2;
        let ReplaceOutcome::Replaced(edited_tree) = tree
            .replace(Utf16Range::new(middle, middle + 1), "y")
            .expect("middle source replacement should succeed")
        else {
            panic!("text-changing source replacement unexpectedly preserved the tree");
        };
        source.take_read_requests();

        let SourceClone::Available(cloned_source) = edited_tree.try_clone_source() else {
            panic!("cloneable source should produce one package capability");
        };
        let mut counter = SegmentCounter::default();
        edited_tree
            .visit_serialized_segments(true, &mut counter)
            .expect("sparse source segments should remain valid");

        assert_eq!(
            cloned_source.byte_length,
            u64::try_from(source_bytes.len()).expect("test source length should fit u64")
        );
        assert_eq!(counter.source_records, 2);
        assert_eq!(counter.payload_records, 1);
        assert_eq!(counter.payload_bytes, 1);
        assert!(source.take_read_requests().is_empty());
    }

    /// Verifies one cross-piece edit preserves mixed raw source formatting.
    #[test]
    fn preserves_multileaf_unicode_and_mixed_endings_across_edit() {
        let leading_bytes = MAX_PIECE_BYTES - 1;
        let leading_text = "a".repeat(leading_bytes);
        let middle_text = "b".repeat(MAX_PIECE_BYTES);
        let source_text = format!("\u{feff}{leading_text}\r\n😀{middle_text}\rbeta\ngamma\r\n");
        let logical_text = format!("{leading_text}\n😀{middle_text}\nbeta\ngamma\n");
        let (tree, _) = open_tracking_tree(&source_text, TRACKING_SHORT_READ_BYTES);
        let replacement_start = leading_bytes - 1;
        let emoji_end = logical_text
            .find('😀')
            .expect("test logical text should contain its emoji")
            + '😀'.len_utf8();
        let replacement_range = Utf16Range::new(
            utf16_offset(&logical_text, replacement_start),
            utf16_offset(&logical_text, emoji_end),
        );

        let ReplaceOutcome::Replaced(edited_tree) = tree
            .replace(replacement_range, "Z\n")
            .expect("cross-piece Unicode replacement should succeed")
        else {
            panic!("text-changing cross-piece replacement unexpectedly preserved the tree");
        };

        let expected_logical_text = format!(
            "{}Z\n{middle_text}\nbeta\ngamma\n",
            "a".repeat(leading_bytes - 1)
        );
        let expected_serialized_text = format!(
            "\u{feff}{}Z\r\n{middle_text}\rbeta\ngamma\r\n",
            "a".repeat(leading_bytes - 1)
        );
        assert_eq!(tree_logical_text(&edited_tree), expected_logical_text);
        assert_eq!(tree_text(&edited_tree), expected_serialized_text);
        assert_eq!(
            edited_tree.serialized_bytes(),
            expected_serialized_text.len()
        );
        edited_tree
            .validate_invariants()
            .expect("cross-piece mixed-ending edit should remain valid");
    }

    /// Verifies bulk normalization preserves Unicode around every newline pairing.
    #[test]
    fn normalizes_adjacent_newlines_without_splitting_unicode() {
        let fragments = ["", "a", "😀", "β", "\r", "\n", "\r\n"];
        for first in fragments {
            for second in fragments {
                for third in fragments {
                    let text = format!("{first}{second}{third}");
                    assert_eq!(
                        normalize_source_text(&text),
                        text.replace("\r\n", "\n").replace('\r', "\n")
                    );
                }
            }
        }
    }

    /// Verifies raw source bytes remain exact while logical text is normalized.
    #[test]
    fn preserves_bom_and_mixed_source_line_endings() {
        let source_text = "\u{feff}alpha\r\nbeta\ngamma\rdelta";
        let expected_logical_text = "alpha\nbeta\ngamma\ndelta";
        let (tree, _) = open_tracking_tree(source_text, TRACKING_SHORT_READ_BYTES);

        assert_eq!(tree_logical_text(&tree), expected_logical_text);
        assert_eq!(tree_text(&tree), source_text);
        assert_eq!(tree.summary().bytes, expected_logical_text.len());
        assert_eq!(tree.serialized_bytes(), source_text.len());
        assert_eq!(tree.line_count(), 4);
        tree.validate_invariants()
            .expect("mixed-ending source tree should remain valid");
    }

    /// Verifies empty sources retain BOM state and default newlines to LF.
    #[test]
    fn preserves_empty_source_bom_and_default_line_ending() {
        for source_text in ["", "\u{feff}"] {
            let (tree, _) = open_tracking_tree(source_text, ONE_BYTE_READ_BYTES);
            let ReplaceOutcome::Replaced(edited_tree) = tree
                .replace(Utf16Range::new(0, 0), "\n")
                .expect("empty source newline insertion should succeed")
            else {
                panic!("text-changing insertion unexpectedly preserved the tree");
            };

            assert_eq!(tree_logical_text(&tree), "");
            assert_eq!(tree_text(&tree), source_text);
            assert_eq!(tree_text(&edited_tree), format!("{source_text}\n"));
            assert_eq!(tree.serialized_bytes(), source_text.len());
            assert_eq!(edited_tree.serialized_bytes(), source_text.len() + 1);
            edited_tree
                .validate_invariants()
                .expect("edited empty source tree should remain valid");
        }
    }

    /// Verifies inserted newlines use the predominant source line ending.
    #[test]
    fn serializes_inserted_newlines_with_predominant_style() {
        let source_text = "a\r\nb\r\nc\nd\r";
        let (tree, _) = open_tracking_tree(source_text, usize::MAX);
        let insertion_offset = tree.summary().utf16_units;
        let ReplaceOutcome::Replaced(edited_tree) = tree
            .replace(
                Utf16Range::new(insertion_offset, insertion_offset),
                "new\nlines\n",
            )
            .expect("normalized newline insertion should succeed")
        else {
            panic!("text-changing insertion unexpectedly preserved the tree");
        };

        assert_eq!(
            tree_text(&edited_tree),
            format!("{source_text}new\r\nlines\r\n")
        );
        assert_eq!(
            edited_tree.serialized_bytes(),
            source_text.len() + "new\r\nlines\r\n".len()
        );
        edited_tree
            .validate_invariants()
            .expect("predominant-style insertion should remain valid");
    }

    /// Verifies equal ending counts select whichever style occurred first.
    #[test]
    fn uses_first_source_line_ending_to_break_tie() {
        let source_text = "a\rb\nc\r\n";
        let (tree, _) = open_tracking_tree(source_text, usize::MAX);
        let insertion_offset = tree.summary().utf16_units;
        let ReplaceOutcome::Replaced(edited_tree) = tree
            .replace(
                Utf16Range::new(insertion_offset, insertion_offset),
                "tail\n",
            )
            .expect("normalized newline insertion should succeed")
        else {
            panic!("text-changing insertion unexpectedly preserved the tree");
        };

        assert_eq!(tree_text(&edited_tree), format!("{source_text}tail\r"));
        edited_tree
            .validate_invariants()
            .expect("first-style tie-break insertion should remain valid");
    }

    /// Verifies adjacent surviving CR and LF terminators remain two newlines.
    #[test]
    fn repairs_cr_lf_boundary_created_by_deletion() {
        let source_text = "a\rb\nc";
        let (tree, _) = open_tracking_tree(source_text, usize::MAX);
        let original_snapshot = tree.clone();
        let ReplaceOutcome::Replaced(edited_tree) = tree
            .replace(Utf16Range::new(2, 3), "")
            .expect("middle character deletion should succeed")
        else {
            panic!("text-changing deletion unexpectedly preserved the tree");
        };

        assert_eq!(tree_logical_text(&edited_tree), "a\n\nc");
        assert_eq!(tree_text(&edited_tree), "a\r\r\nc");
        assert_eq!(edited_tree.summary().bytes, 4);
        assert_eq!(edited_tree.serialized_bytes(), 5);
        assert_eq!(tree_text(&original_snapshot), source_text);
        edited_tree
            .validate_invariants()
            .expect("repaired-boundary tree should remain valid");
        original_snapshot
            .validate_invariants()
            .expect("persistent source snapshot should remain valid");
    }

    /// Verifies edits preserve untouched raw terminators and the UTF-8 BOM.
    #[test]
    fn preserves_source_format_around_edits() {
        let source_text = "\u{feff}one\r\ntwo\rthree\nfour";
        let (tree, _) = open_tracking_tree(source_text, usize::MAX);
        let ReplaceOutcome::Replaced(edited_tree) = tree
            .replace(Utf16Range::new(4, 7), "TWO")
            .expect("source text replacement should succeed")
        else {
            panic!("text-changing replacement unexpectedly preserved the tree");
        };

        assert_eq!(tree_logical_text(&edited_tree), "one\nTWO\nthree\nfour");
        assert_eq!(tree_text(&edited_tree), "\u{feff}one\r\nTWO\rthree\nfour");
        assert_eq!(edited_tree.serialized_bytes(), source_text.len());
        edited_tree
            .validate_invariants()
            .expect("format-preserving edit should remain valid");
    }

    /// Verifies deterministic edits always serialize back to the logical model.
    #[test]
    fn preserves_fidelity_across_persistent_mixed_ending_edits() {
        let source_text = format!(
            "\u{feff}{}",
            ["alpha\r", "beta\n", "gamma\r\n", "delta\n"]
                .concat()
                .repeat(FIDELITY_SOURCE_REPETITIONS)
        );
        let mut logical_model = normalize_source_text(
            source_text
                .strip_prefix('\u{feff}')
                .expect("test source should begin with a UTF-8 BOM"),
        );
        let (mut tree, _) = open_tracking_tree(&source_text, TRACKING_SHORT_READ_BYTES);
        let mut snapshots = Vec::new();

        for operation in 0..FIDELITY_EDIT_OPERATIONS {
            let start = operation.wrapping_mul(FIDELITY_POSITION_STRIDE)
                % logical_model.len().saturating_add(1);
            let removed_bytes =
                operation.wrapping_mul(FIDELITY_REMOVAL_STRIDE) % (MAX_FIDELITY_REMOVAL_BYTES + 1);
            let removed_bytes = removed_bytes.min(logical_model.len() - start);
            let end = start + removed_bytes;
            let replacement = match operation % 4 {
                0 => "x",
                1 => "",
                2 => "\n",
                _ => "y\nz",
            };

            match tree
                .replace(Utf16Range::new(start, end), replacement)
                .expect("ASCII fidelity replacement should succeed")
            {
                ReplaceOutcome::Unchanged => {
                    assert_eq!(&logical_model[start..end], replacement);
                }
                ReplaceOutcome::Replaced(replacement_tree) => {
                    tree = replacement_tree;
                }
            }
            logical_model.replace_range(start..end, replacement);
            let serialized = tree_text(&tree);
            let serialized_payload = serialized
                .strip_prefix('\u{feff}')
                .expect("edited serialization should preserve its UTF-8 BOM");

            assert_eq!(tree_logical_text(&tree), logical_model);
            assert_eq!(normalize_source_text(serialized_payload), logical_model);
            assert_eq!(serialized.len(), tree.serialized_bytes());
            assert_eq!(
                tree.summary(),
                TextSummary::from_source_text(serialized_payload)
            );
            tree.validate_invariants()
                .expect("edited mixed-ending tree should remain valid");

            if operation % SNAPSHOT_INTERVAL == 0 {
                snapshots.push((tree.clone(), logical_model.clone(), serialized));
            }
        }

        for (snapshot, expected_logical_text, expected_serialized_text) in snapshots {
            assert_eq!(tree_logical_text(&snapshot), expected_logical_text);
            assert_eq!(tree_text(&snapshot), expected_serialized_text);
            assert_eq!(snapshot.serialized_bytes(), expected_serialized_text.len());
            snapshot
                .validate_invariants()
                .expect("persistent fidelity snapshot should remain valid");
        }
    }

    /// Verifies one viewport read session loads a short-line source leaf once.
    #[test]
    fn reuses_source_leaf_across_viewport_lines() {
        let text = "x\n".repeat(MAX_PIECE_BYTES / 2);
        let (tree, source) = open_tracking_tree(&text, usize::MAX);
        source.take_read_requests();
        let mut reader = tree.reader();

        for line in 0..VIEWPORT_TEST_LINES {
            let bounds = reader
                .line_bounds(line)
                .expect("short logical line should be readable");
            let prefix = reader
                .bounded_text_prefix(
                    Utf16Range::new(bounds.content_start_utf16, bounds.content_end_utf16),
                    1,
                )
                .expect("short logical line should fit its viewport block");
            assert_eq!(prefix.text, "x");
        }

        assert_eq!(
            source.take_read_requests(),
            vec![ReadRequest {
                offset: 0,
                requested_bytes: MAX_PIECE_BYTES,
            }]
        );
    }

    /// Verifies cached positions remain exact through forward, reverse and evicting reads.
    #[test]
    fn preserves_cached_scalar_positions() {
        const SAMPLE_STRIDE: usize = 127;
        for line in ["alpha\nbeta\n", "a😀\nβ\n"] {
            let text = line.repeat(MAX_PIECE_BYTES / 4);
            let mut expected_positions = vec![LogicalPosition::default()];
            let mut position = LogicalPosition::default();
            for character in text.chars() {
                position.bytes += character.len_utf8();
                position.utf16_units += character.len_utf16();
                position.line_feeds += usize::from(character == '\n');
                expected_positions.push(position);
            }
            let samples: Vec<_> = expected_positions.iter().step_by(SAMPLE_STRIDE).collect();
            let (source_tree, _) = open_tracking_tree(&text, usize::MAX);
            let (crlf_tree, _) = open_tracking_tree(&text.replace('\n', "\r\n"), usize::MAX);
            for tree in [PieceTree::from_text(&text), source_tree, crlf_tree] {
                let mut reader = tree.reader();
                for &expected in samples.iter().chain(samples.iter().rev()) {
                    let actual = reader
                        .position_at_utf16(expected.utf16_units)
                        .expect("cached scalar boundary should remain readable");
                    assert_eq!(actual, *expected);
                    let bounds = reader
                        .line_bounds(expected.line_feeds)
                        .expect("interleaved line lookup should remain exact");
                    assert!(bounds.content_start_utf16 <= expected.utf16_units);
                    assert!(bounds.content_end_utf16 >= expected.utf16_units);
                    if text[expected.bytes..].starts_with('😀') {
                        assert!(matches!(
                            reader.position_at_utf16(expected.utf16_units + 1),
                            Err(DocumentError::MisalignedUtf16Offset { .. })
                        ));
                    }
                    assert!(reader.cache.positions.len() <= MAX_CACHED_PIECE_POSITIONS);
                }
                assert_eq!(reader.cache.positions.len(), MAX_CACHED_PIECE_POSITIONS);
            }
        }
    }

    /// Verifies identical summaries cannot reuse a different backing's positions.
    #[test]
    fn distinguishes_cached_position_backing_identity() {
        let first_text: Arc<str> = Arc::from("x😀\ny");
        let second_text: Arc<str> = Arc::from("x\n😀y");
        let first = Piece::from_edit(
            first_text.clone(),
            0,
            first_text.len(),
            super::LineEnding::Lf,
        )
        .expect("first test piece should be valid");
        let second = Piece::from_edit(
            second_text.clone(),
            0,
            second_text.len(),
            super::LineEnding::Lf,
        )
        .expect("second test piece should be valid");
        assert_eq!(first.summary, second.summary);
        let mut cache = PieceReadCache::new();
        let position = LogicalPosition::from_text("x😀");
        cache.remember_position(&first, position);

        assert_eq!(cache.nearest_position(&first.clone(), |_| 0), position);
        assert_eq!(
            cache.nearest_position(&second, |_| 0),
            LogicalPosition::default(),
        );
    }

    /// Verifies middle operations avoid unrelated source pieces.
    #[test]
    fn reads_only_bounded_middle_source_pieces() {
        const SOURCE_PIECES: usize = 8;
        const TARGET_LINE: usize = 5;

        let source_piece = format!("{}\n", "x".repeat(MAX_PIECE_BYTES - 1));
        let text = source_piece.repeat(SOURCE_PIECES);
        let (tree, source) = open_tracking_tree(&text, TRACKING_SHORT_READ_BYTES);
        source.take_read_requests();
        let mut reader = tree.reader();

        let bounds = reader
            .line_bounds(TARGET_LINE)
            .expect("middle line should be readable");
        let prefix = reader
            .bounded_text_prefix(
                Utf16Range::new(
                    bounds.content_start_utf16,
                    bounds.content_start_utf16 + TRACKING_SHORT_READ_BYTES,
                ),
                TRACKING_SHORT_READ_BYTES,
            )
            .expect("middle prefix should be readable");
        let reads = source.take_read_requests();
        let earliest_expected_offset = (TARGET_LINE - 1) * MAX_PIECE_BYTES;

        assert_eq!(prefix.text, "x".repeat(TRACKING_SHORT_READ_BYTES));
        assert!(!reads.is_empty());
        assert!(reads.iter().all(|request| {
            request.requested_bytes <= MAX_PIECE_BYTES
                && request.offset
                    >= u64::try_from(earliest_expected_offset)
                        .expect("test source offset should fit u64")
        }));
    }

    /// Verifies deterministic mixed-text edits preserve all tree invariants.
    #[test]
    fn preserves_invariants_across_persistent_edits() {
        let initial_text = "alpha😀\nβeta\n".repeat(MAX_PIECE_BYTES / 8);
        let mut model = initial_text.clone();
        let mut tree = PieceTree::from_text(&initial_text);
        let mut snapshots = Vec::new();

        for operation in 0..EDIT_TEST_OPERATIONS {
            let boundaries = scalar_boundaries(&model);
            let start_index = operation.wrapping_mul(EDIT_POSITION_STRIDE) % boundaries.len();
            let remaining_scalars = boundaries.len() - 1 - start_index;
            let removed_scalars = operation
                .wrapping_mul(EDIT_REMOVAL_STRIDE)
                .min(MAX_EDIT_REMOVAL_SCALARS)
                .min(remaining_scalars);
            let end_index = start_index + removed_scalars;
            let start_byte = boundaries[start_index];
            let end_byte = boundaries[end_index];
            let replacement = match operation % 4 {
                0 => "x",
                1 => "",
                2 => "😀",
                _ => "β\n",
            };
            let range = Utf16Range::new(
                utf16_offset(&model, start_byte),
                utf16_offset(&model, end_byte),
            );

            match tree
                .replace(range, replacement)
                .expect("deterministic replacement should succeed")
            {
                ReplaceOutcome::Unchanged => {
                    assert_eq!(&model[start_byte..end_byte], replacement);
                }
                ReplaceOutcome::Replaced(replacement_tree) => {
                    tree = replacement_tree;
                }
            }
            model.replace_range(start_byte..end_byte, replacement);
            tree.validate_invariants()
                .expect("edited tree invariants should hold");
            assert_eq!(tree.summary(), TextSummary::from_text(&model));

            if operation % SNAPSHOT_INTERVAL == 0 {
                snapshots.push((tree.clone(), model.clone()));
            }
        }

        assert_eq!(tree_text(&tree), model);
        for (snapshot, expected_text) in snapshots {
            assert_eq!(tree_text(&snapshot), expected_text);
            snapshot
                .validate_invariants()
                .expect("persistent snapshot invariants should hold");
        }
    }

    /// Verifies tiny adjacent edits retain a bounded number of pieces.
    #[test]
    fn compacts_sequential_tiny_edits() {
        let mut model = String::new();
        let mut tree = PieceTree::new();

        for operation in 0..EDIT_COMPACTION_OPERATIONS {
            let byte_offset = match operation % 3 {
                0 => 0,
                1 => model.len(),
                _ => model.len() / 2,
            };
            let range = Utf16Range::new(byte_offset, byte_offset);
            let ReplaceOutcome::Replaced(replacement_tree) = tree
                .replace(range, "x")
                .expect("scalar-aligned insertion should succeed")
            else {
                panic!("text-changing insertion unexpectedly preserved the tree");
            };
            tree = replacement_tree;
            model.insert(byte_offset, 'x');
        }

        let leaf_count = tree.leaf_count();
        assert!(
            leaf_count <= MAX_COMPACTED_EDIT_LEAVES,
            "compacted edit tree retained {leaf_count} leaves"
        );
        assert_eq!(tree_text(&tree), model);
        tree.validate_invariants()
            .expect("compacted edit tree invariants should hold");
    }

    /// Verifies fragmented edit-backed regions rechunk within one bounded piece.
    #[test]
    fn compacts_fragmented_edit_backing() {
        const SOURCE_BYTES: usize = 64;

        let initial_text = "x".repeat(SOURCE_BYTES);
        let mut tree = PieceTree::from_text(&initial_text);
        let mut model = initial_text.as_bytes().to_vec();

        for byte_offset in (0..SOURCE_BYTES).step_by(2) {
            let ReplaceOutcome::Replaced(replacement_tree) = tree
                .replace(Utf16Range::new(byte_offset, byte_offset + 1), "y")
                .expect("dense edit-backed overwrite should succeed")
            else {
                panic!("text-changing dense overwrite unexpectedly preserved the tree");
            };
            tree = replacement_tree;
            model[byte_offset] = b'y';
        }

        assert!(tree.leaf_count() <= MAX_DENSE_BOUNDARY_PIECES);
        assert_eq!(tree_text(&tree).as_bytes(), model);
        tree.validate_invariants()
            .expect("fragmented edit-backed tree invariants should hold");
    }

    /// Verifies a tiny edit survivor does not retain its full old allocation.
    #[test]
    fn trims_tiny_edit_survivor_backing() {
        let initial_text = "x".repeat(MAX_PIECE_BYTES);
        let tree = PieceTree::from_text(&initial_text);

        let ReplaceOutcome::Replaced(trimmed_tree) = tree
            .replace(Utf16Range::new(1, MAX_PIECE_BYTES), "")
            .expect("large edit-backed deletion should succeed")
        else {
            panic!("text-changing edit-backed deletion unexpectedly preserved the tree");
        };

        assert_eq!(tree.largest_edit_backing_bytes(), MAX_PIECE_BYTES);
        assert_eq!(trimmed_tree.largest_edit_backing_bytes(), 1);
        assert_eq!(tree_text(&trimmed_tree), "x");
        trimmed_tree
            .validate_invariants()
            .expect("trimmed edit survivor invariants should hold");
    }

    /// Verifies scattered overwrites track live edits rather than edit history.
    #[test]
    fn bounds_fragmentation_across_scattered_overwrite_rounds() {
        let initial_text = "x".repeat(MAX_PIECE_BYTES);
        let mut model = initial_text.as_bytes().to_vec();
        let (mut tree, source) = open_tracking_tree(&initial_text, usize::MAX);
        source.take_read_requests();
        let positions = scattered_byte_positions(MAX_PIECE_BYTES, SCATTERED_EDIT_POSITIONS);

        let mut first_round_leaves = None;
        for round in 0..SCATTERED_EDIT_ROUNDS {
            let replacement = if round % 2 == 0 { "y" } else { "z" };
            let replacement_byte = replacement.as_bytes()[0];
            for &byte_offset in &positions {
                let ReplaceOutcome::Replaced(replacement_tree) = tree
                    .replace(Utf16Range::new(byte_offset, byte_offset + 1), replacement)
                    .expect("scattered overwrite should succeed")
                else {
                    panic!("text-changing scattered overwrite unexpectedly preserved the tree");
                };
                tree = replacement_tree;
                model[byte_offset] = replacement_byte;
            }

            let leaf_count = tree.leaf_count();
            if let Some(initial_leaf_count) = first_round_leaves {
                assert!(
                    leaf_count <= initial_leaf_count + MAX_DENSE_BOUNDARY_PIECES,
                    "overwrite history grew the tree from {initial_leaf_count} to {leaf_count} leaves"
                );
            } else {
                let maximum_live_edit_leaves = SCATTERED_EDIT_POSITIONS
                    .checked_mul(2)
                    .and_then(|leaves| leaves.checked_add(1))
                    .expect("test leaf limit should fit usize");
                assert!(leaf_count <= maximum_live_edit_leaves);
                first_round_leaves = Some(leaf_count);
            }
            tree.validate_invariants()
                .expect("scattered overwrite tree invariants should hold");
        }

        assert_eq!(tree_text(&tree).as_bytes(), model);
        assert!(tree.edit_leaf_count() <= SCATTERED_EDIT_POSITIONS);
        assert!(
            source
                .take_read_requests()
                .iter()
                .all(|request| request.requested_bytes <= MAX_PIECE_BYTES)
        );
    }

    /// Verifies sparse deletions and a large insertion retain source backing.
    #[test]
    fn keeps_scattered_deletions_and_large_insert_source_backed() {
        let source_text = "x".repeat(MAX_PIECE_BYTES);
        let (mut tree, source) = open_tracking_tree(&source_text, usize::MAX);
        let mut model = source_text.as_bytes().to_vec();
        let mut positions = scattered_byte_positions(MAX_PIECE_BYTES, SCATTERED_DELETIONS);
        positions.sort_unstable_by(|left, right| right.cmp(left));
        source.take_read_requests();

        for byte_offset in positions {
            let ReplaceOutcome::Replaced(replacement_tree) = tree
                .replace(Utf16Range::new(byte_offset, byte_offset + 1), "")
                .expect("scattered source deletion should succeed")
            else {
                panic!("text-changing source deletion unexpectedly preserved the tree");
            };
            tree = replacement_tree;
            model.remove(byte_offset);
        }

        let maximum_source_fragments = SCATTERED_DELETIONS
            .checked_add(1)
            .expect("test fragment limit should fit usize");
        assert!(tree.leaf_count() <= maximum_source_fragments);
        assert_eq!(tree.edit_leaf_count(), 0);
        tree.validate_invariants()
            .expect("scattered deletion tree invariants should hold");

        let insertion = "y".repeat(MAX_PIECE_BYTES / 4);
        let ReplaceOutcome::Replaced(inserted_tree) = tree
            .replace(Utf16Range::new(0, 0), &insertion)
            .expect("large insertion beside source fragments should succeed")
        else {
            panic!("text-changing large insertion unexpectedly preserved the tree");
        };
        model.splice(0..0, insertion.bytes());
        let maximum_result_leaves = maximum_source_fragments
            .checked_add(1)
            .expect("test result leaf limit should fit usize");
        assert!(inserted_tree.leaf_count() <= maximum_result_leaves);
        assert_eq!(inserted_tree.edit_leaf_count(), 1);
        assert_eq!(tree_text(&inserted_tree).as_bytes(), model);
        inserted_tree
            .validate_invariants()
            .expect("source fragments with a large insertion should remain valid");
        assert!(
            source
                .take_read_requests()
                .iter()
                .all(|request| request.requested_bytes <= MAX_PIECE_BYTES)
        );
    }

    /// Verifies source fragments remain source-backed and rejoin after restoration.
    #[test]
    fn preserves_source_backing_across_split_and_restoration() {
        let source_text = "x".repeat(MAX_PIECE_BYTES);
        let (tree, source) = open_tracking_tree(&source_text, usize::MAX);
        let snapshot = tree.clone();
        let middle = MAX_PIECE_BYTES / 2;
        source.take_read_requests();

        let ReplaceOutcome::Replaced(deleted_tree) = tree
            .replace(Utf16Range::new(middle, middle + 1), "")
            .expect("source deletion should succeed")
        else {
            panic!("source deletion unexpectedly preserved the tree");
        };
        assert_eq!(deleted_tree.leaf_count(), 2);
        assert_eq!(deleted_tree.edit_leaf_count(), 0);

        let ReplaceOutcome::Replaced(inserted_tree) = tree
            .replace(Utf16Range::new(middle, middle), "y")
            .expect("source insertion should succeed")
        else {
            panic!("source insertion unexpectedly preserved the tree");
        };
        let ReplaceOutcome::Replaced(restored_tree) = inserted_tree
            .replace(Utf16Range::new(middle, middle + 1), "")
            .expect("inserted text deletion should succeed")
        else {
            panic!("inserted text deletion unexpectedly preserved the tree");
        };

        assert_eq!(restored_tree.leaf_count(), 1);
        assert_eq!(restored_tree.edit_leaf_count(), 0);
        assert_eq!(tree_text(&snapshot), source_text);
        assert_eq!(tree_text(&restored_tree), source_text);
        let source_reads = source.take_read_requests();
        assert!(!source_reads.is_empty());
        assert!(
            source_reads
                .iter()
                .all(|request| request.requested_bytes <= MAX_PIECE_BYTES)
        );
        deleted_tree
            .validate_invariants()
            .expect("deleted source tree invariants should hold");
        restored_tree
            .validate_invariants()
            .expect("restored source tree invariants should hold");

        drop(deleted_tree);
        drop(inserted_tree);
        drop(tree);
        drop(snapshot);
        assert_eq!(Arc::strong_count(&source), 2);
        drop(restored_tree);
        assert_eq!(Arc::strong_count(&source), 1);
    }

    /// Verifies a no-op comparison can span multiple tree leaves.
    #[test]
    fn preserves_revision_tree_for_multileaf_noop() {
        let text = "ab😀\n".repeat(MAX_PIECE_BYTES);
        let tree = PieceTree::from_text(&text);
        let range = Utf16Range::new(0, text.encode_utf16().count());

        let outcome = tree
            .replace(range, &text)
            .expect("exact multileaf replacement should be comparable");

        assert!(matches!(outcome, ReplaceOutcome::Unchanged));
        tree.validate_invariants()
            .expect("unchanged tree invariants should hold");
    }

    /// Verifies interrupted and short positioned reads are retried exactly.
    #[test]
    fn retries_interrupted_and_short_reads() {
        struct InterruptedSource {
            source: TrackingSource,
            interrupt_next_read: AtomicBool,
        }

        impl ReadAtSource for InterruptedSource {
            fn len(&self) -> u64 {
                self.source.len()
            }

            fn read_at(&self, buffer: &mut [u8], offset: u64) -> std::io::Result<usize> {
                if self.interrupt_next_read.swap(false, AtomicOrdering::AcqRel) {
                    return Err(std::io::Error::new(
                        ErrorKind::Interrupted,
                        "deterministic test interruption",
                    ));
                }
                self.source.read_at(buffer, offset)
            }
        }

        let text = "😀\nshort reads remain exact";
        let source = Arc::new(InterruptedSource {
            source: TrackingSource::new(text, TRACKING_SHORT_READ_BYTES),
            interrupt_next_read: AtomicBool::new(true),
        });

        let tree = PieceTree::open_source_reader(source)
            .expect("interrupted short reads should be retried");

        assert_eq!(tree_text(&tree), text);
    }

    /// Verifies a premature source end is reported without partial indexing.
    #[test]
    fn rejects_premature_source_end() {
        struct PrematureSource;

        impl ReadAtSource for PrematureSource {
            fn len(&self) -> u64 {
                1
            }

            fn read_at(&self, _: &mut [u8], _: u64) -> std::io::Result<usize> {
                Ok(0)
            }
        }

        let result = PieceTree::open_source_reader(Arc::new(PrematureSource));

        assert_eq!(opening_error_kind(result), ErrorKind::UnexpectedEof);
    }

    /// Verifies an oversized source read count becomes invalid data.
    #[test]
    fn rejects_oversized_source_read_count() {
        struct OversizedReadSource;

        impl ReadAtSource for OversizedReadSource {
            fn len(&self) -> u64 {
                1
            }

            fn read_at(&self, buffer: &mut [u8], _: u64) -> std::io::Result<usize> {
                Ok(buffer.len() + 1)
            }
        }

        let result = PieceTree::open_source_reader(Arc::new(OversizedReadSource));

        assert_eq!(opening_error_kind(result), ErrorKind::InvalidData);
    }

    /// Verifies the source length is revalidated after indexing.
    #[test]
    fn rejects_post_index_length_change() {
        struct InvalidatedLengthSource {
            source: TrackingSource,
            validated: AtomicBool,
        }

        impl ReadAtSource for InvalidatedLengthSource {
            fn len(&self) -> u64 {
                self.source.len()
            }

            fn read_at(&self, buffer: &mut [u8], offset: u64) -> std::io::Result<usize> {
                self.source.read_at(buffer, offset)
            }

            fn validate_length(&self, _: u64) -> std::io::Result<()> {
                self.validated.store(true, AtomicOrdering::Release);
                Err(std::io::Error::new(
                    ErrorKind::InvalidData,
                    "deterministic post-index length change",
                ))
            }
        }

        let source = Arc::new(InvalidatedLengthSource {
            source: TrackingSource::new("stable while indexed", usize::MAX),
            validated: AtomicBool::new(false),
        });

        let result = PieceTree::open_source_reader(source.clone());

        assert_eq!(opening_error_kind(result), ErrorKind::InvalidData);
        assert!(source.validated.load(AtomicOrdering::Acquire));
    }

    /// Verifies invalid and truncated UTF-8 sources are rejected.
    #[test]
    fn rejects_invalid_source_utf8() {
        let invalid_source = Arc::new(ByteSource::new(vec![0xff]));
        let mut truncated_bytes = vec![b'a'; MAX_PIECE_BYTES - 1];
        truncated_bytes.extend_from_slice(&[0xf0, 0x9f]);
        let truncated_source = Arc::new(ByteSource::new(truncated_bytes));

        assert_eq!(
            opening_error_kind(PieceTree::open_source_reader(invalid_source)),
            ErrorKind::InvalidData
        );
        assert_eq!(
            opening_error_kind(PieceTree::open_source_reader(truncated_source)),
            ErrorKind::InvalidData
        );
    }

    /// Provides immutable raw bytes for invalid UTF-8 tests.
    struct ByteSource {
        bytes: Box<[u8]>,
    }

    impl ByteSource {
        /// Creates a source from arbitrary bytes.
        fn new(bytes: Vec<u8>) -> Self {
            Self {
                bytes: bytes.into_boxed_slice(),
            }
        }
    }

    impl ReadAtSource for ByteSource {
        fn len(&self) -> u64 {
            u64::try_from(self.bytes.len()).expect("test source length should fit u64")
        }

        fn read_at(&self, buffer: &mut [u8], offset: u64) -> std::io::Result<usize> {
            let byte_start = usize::try_from(offset).map_err(|_| {
                std::io::Error::new(ErrorKind::InvalidInput, "test source offset exceeds usize")
            })?;
            let Some(remaining) = self.bytes.get(byte_start..) else {
                return Ok(0);
            };
            let returned_bytes = remaining.len().min(buffer.len());
            buffer[..returned_bytes].copy_from_slice(&remaining[..returned_bytes]);
            Ok(returned_bytes)
        }
    }
}
