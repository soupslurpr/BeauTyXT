//! Produces `BeauTyXT`'s bounded, non-executable Markdown render model.

#![forbid(unsafe_code)]

mod autolink;
mod block_buffer;
mod bracket_math;
mod html;

use std::error::Error;
use std::fmt::{Display, Formatter};
use std::ops::Range;

use block_buffer::BlockBuffer;

use pulldown_cmark::{
    Alignment, BlockQuoteKind, CodeBlockKind, Event, HeadingLevel, Options, Parser, Tag, TagEnd,
};

/// Limits one Markdown preview input to 16 MiB.
pub const MAX_INPUT_BYTES: usize = 16 * 1024 * 1024;

/// Limits one encoded render packet to 32 MiB.
pub const MAX_PACKET_BYTES: usize = 32 * 1024 * 1024;

/// Limits one render model to 16,384 blocks.
pub const MAX_BLOCK_COUNT: usize = 16_384;

/// Limits one render model to 131,072 styled spans.
pub const MAX_SPAN_COUNT: usize = 131_072;

/// Limits source-map runs while allowing one extra split per presentation block.
pub const MAX_SOURCE_MAP_COUNT: usize = 262_144 + MAX_BLOCK_COUNT;

/// Limits one presentation block to 4 KiB of UTF-8 text.
pub const MAX_BLOCK_TEXT_BYTES: usize = 4 * 1024;

/// Fixes the render packet header width.
pub const PACKET_HEADER_BYTES: usize = 80;

/// Fixes every block header width.
pub const BLOCK_HEADER_BYTES: usize = 72;

/// Fixes every styled-span header width.
pub const SPAN_HEADER_BYTES: usize = 24;

/// Fixes every source-map header width.
pub const SOURCE_MAP_HEADER_BYTES: usize = 24;

/// Identifies every version-four Markdown render packet.
pub const PACKET_MAGIC: [u8; 8] = *b"BTXTMDR4";

/// Identifies the only supported Markdown render packet version.
pub const PACKET_VERSION: u32 = 4;

/// Indicates that a render model contains literal raw HTML.
pub const DOCUMENT_FLAG_RAW_HTML: u32 = 1;

/// Identifies an ordinary Markdown paragraph.
pub const BLOCK_KIND_PARAGRAPH: u32 = 1;

/// Identifies a Markdown heading.
pub const BLOCK_KIND_HEADING: u32 = 2;

/// Identifies a fenced or indented code block.
pub const BLOCK_KIND_CODE: u32 = 3;

/// Identifies one list item paragraph.
pub const BLOCK_KIND_LIST_ITEM: u32 = 4;

/// Identifies a thematic break.
pub const BLOCK_KIND_RULE: u32 = 5;

/// Identifies raw HTML displayed as inert literal text.
pub const BLOCK_KIND_HTML_LITERAL: u32 = 6;

/// Identifies one flattened table row.
pub const BLOCK_KIND_TABLE_ROW: u32 = 7;

/// Identifies one footnote definition.
pub const BLOCK_KIND_FOOTNOTE: u32 = 8;

/// Indicates that a block continues a bounded predecessor.
pub const BLOCK_FLAG_CONTINUATION: u32 = 1;

/// Indicates that a list item belongs to an ordered list.
pub const BLOCK_FLAG_ORDERED_LIST: u32 = 1 << 1;

/// Indicates that a task-list item is checked.
pub const BLOCK_FLAG_TASK_CHECKED: u32 = 1 << 2;

/// Indicates that a task-list item is unchecked.
pub const BLOCK_FLAG_TASK_UNCHECKED: u32 = 1 << 3;

/// Indicates that a table row is the header row.
pub const BLOCK_FLAG_TABLE_HEADER: u32 = 1 << 4;

/// Indicates that a block contains inert raw HTML text.
pub const BLOCK_FLAG_RAW_HTML: u32 = 1 << 5;

/// Indicates that a quoted block belongs to a GFM note.
pub const BLOCK_FLAG_QUOTE_NOTE: u32 = 1 << 6;

/// Indicates that a quoted block belongs to a GFM tip.
pub const BLOCK_FLAG_QUOTE_TIP: u32 = 1 << 7;

/// Indicates that a quoted block belongs to a GFM important alert.
pub const BLOCK_FLAG_QUOTE_IMPORTANT: u32 = 1 << 8;

/// Indicates that a quoted block belongs to a GFM warning.
pub const BLOCK_FLAG_QUOTE_WARNING: u32 = 1 << 9;

/// Indicates that a quoted block belongs to a GFM caution alert.
pub const BLOCK_FLAG_QUOTE_CAUTION: u32 = 1 << 10;

/// Indicates the first rendered block in one GFM alert.
pub const BLOCK_FLAG_QUOTE_ALERT_START: u32 = 1 << 11;

/// Indicates the first rendered row in one semantic table.
pub const BLOCK_FLAG_TABLE_START: u32 = 1 << 12;

/// Indicates a later paragraph in the same list item, not a transport fragment.
pub const BLOCK_FLAG_LIST_ITEM_CONTINUATION: u32 = 1 << 13;

const BLOCK_FLAGS_QUOTE_KIND: u32 = BLOCK_FLAG_QUOTE_NOTE
    | BLOCK_FLAG_QUOTE_TIP
    | BLOCK_FLAG_QUOTE_IMPORTANT
    | BLOCK_FLAG_QUOTE_WARNING
    | BLOCK_FLAG_QUOTE_CAUTION;

/// Applies emphasized styling to one UTF-16 range.
pub const SPAN_STYLE_EMPHASIS: u32 = 1;

/// Applies strong styling to one UTF-16 range.
pub const SPAN_STYLE_STRONG: u32 = 1 << 1;

/// Applies inline-code styling to one UTF-16 range.
pub const SPAN_STYLE_CODE: u32 = 1 << 2;

/// Applies strikethrough styling to one UTF-16 range.
pub const SPAN_STYLE_STRIKETHROUGH: u32 = 1 << 3;

/// Applies superscript styling to one UTF-16 range.
pub const SPAN_STYLE_SUPERSCRIPT: u32 = 1 << 4;

/// Applies subscript styling to one UTF-16 range.
pub const SPAN_STYLE_SUBSCRIPT: u32 = 1 << 5;

/// Identifies one navigable footnote-reference range.
pub const SPAN_STYLE_FOOTNOTE_REFERENCE: u32 = 1 << 6;

/// Marks one indivisible TeX formula; the source remains available as ordinary text.
pub const SPAN_STYLE_MATH: u32 = 1 << 7;

/// Requests display-style layout for an indivisible math span.
pub const SPAN_STYLE_DISPLAY_MATH: u32 = 1 << 8;

/// Indicates that one styled span carries a link destination.
pub const SPAN_FLAG_LINK: u32 = 1;

/// Indicates that one styled span carries a footnote label.
pub const SPAN_FLAG_FOOTNOTE_REFERENCE: u32 = 1 << 1;

const MAX_BUILDER_BLOCK_TEXT_BYTES: usize = 256 * 1024;
const MAX_METADATA_BYTES: usize = 4 * 1024;
const MAX_LINK_DESTINATION_BYTES: usize = 4 * 1024;
const MAX_NESTING_DEPTH: usize = 64;
const MAX_EVENT_COUNT: usize = 1_000_000;
const CHECKPOINT_EVENT_INTERVAL: usize = 1_024;
const MAX_QUOTE_DEPTH: u32 = 32;
const MAX_LIST_DEPTH: u32 = 32;
const SOFT_BREAK: &str = " ";
const HARD_BREAK: &str = "\n";
const TABLE_CELL_SEPARATOR: &str = "\t";
const CHECKED_TASK_PREFIX: &str = "☑ ";
const UNCHECKED_TASK_PREFIX: &str = "☐ ";
const IMAGE_PREFIX: &str = "Image: ";
const FOOTNOTE_PREFIX: &str = "[^";
const FOOTNOTE_SUFFIX: &str = "]";
const TABLE_ALIGNMENT_NONE: char = 'n';
const TABLE_ALIGNMENT_LEFT: char = 'l';
const TABLE_ALIGNMENT_CENTER: char = 'c';
const TABLE_ALIGNMENT_RIGHT: char = 'r';

const MAGIC_OFFSET: usize = 0;
const VERSION_OFFSET: usize = 8;
const HEADER_BYTES_OFFSET: usize = 12;
const DOCUMENT_FLAGS_OFFSET: usize = 16;
const HEADER_RESERVED_OFFSET: usize = 20;
const INPUT_BYTES_OFFSET: usize = 24;
const PACKET_BYTES_OFFSET: usize = 32;
const BLOCK_COUNT_OFFSET: usize = 40;
const SPAN_COUNT_OFFSET: usize = 44;
const TEXT_BYTES_OFFSET: usize = 48;
const AUXILIARY_BYTES_OFFSET: usize = 56;
const INPUT_UTF16_UNITS_OFFSET: usize = 64;
const SOURCE_MAP_COUNT_OFFSET: usize = 72;
const HEADER_TRAILING_RESERVED_OFFSET: usize = 76;

const BLOCK_KIND_OFFSET: usize = 0;
const BLOCK_FLAGS_OFFSET: usize = 4;
const BLOCK_HEADING_LEVEL_OFFSET: usize = 8;
const BLOCK_QUOTE_DEPTH_OFFSET: usize = 12;
const BLOCK_LIST_DEPTH_OFFSET: usize = 16;
const BLOCK_RESERVED_OFFSET: usize = 20;
const BLOCK_LIST_NUMBER_OFFSET: usize = 24;
const BLOCK_TEXT_BYTES_OFFSET: usize = 32;
const BLOCK_METADATA_BYTES_OFFSET: usize = 36;
const BLOCK_SPAN_COUNT_OFFSET: usize = 40;
const BLOCK_TRAILING_RESERVED_OFFSET: usize = 44;
const BLOCK_SOURCE_START_UTF16_OFFSET: usize = 48;
const BLOCK_SOURCE_END_UTF16_OFFSET: usize = 56;
const BLOCK_SOURCE_MAP_COUNT_OFFSET: usize = 64;
const BLOCK_SOURCE_RESERVED_OFFSET: usize = 68;

const SPAN_START_UTF16_OFFSET: usize = 0;
const SPAN_END_UTF16_OFFSET: usize = 4;
const SPAN_STYLES_OFFSET: usize = 8;
const SPAN_FLAGS_OFFSET: usize = 12;
const SPAN_DESTINATION_BYTES_OFFSET: usize = 16;
const SPAN_RESERVED_OFFSET: usize = 20;

const SOURCE_MAP_RENDERED_START_UTF16_OFFSET: usize = 0;
const SOURCE_MAP_RENDERED_END_UTF16_OFFSET: usize = 4;
const SOURCE_MAP_SOURCE_START_UTF16_OFFSET: usize = 8;
const SOURCE_MAP_SOURCE_END_UTF16_OFFSET: usize = 16;
const SOURCE_OFFSET_CHECKPOINT_BYTES: usize = 256;

/// Reports one stable bounded Markdown rendering failure.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum RenderError {
    /// Reports explicit cancellation before the render model commits.
    Cancelled,

    /// Reports expiration of the caller's render deadline.
    DeadlineExceeded,

    /// Reports Markdown input beyond the preview byte limit.
    InputLimit,

    /// Reports a parser event count beyond the defensive limit.
    EventLimit,

    /// Reports Markdown nesting beyond the defensive limit.
    NestingLimit,

    /// Reports a render model with too many blocks.
    BlockLimit,

    /// Reports a render model with too many styled spans.
    SpanLimit,

    /// Reports a render model with too many source-map runs.
    SourceMapLimit,

    /// Reports an encoded render packet beyond its byte limit.
    PacketLimit,

    /// Reports protocol arithmetic outside the supported representation.
    ArithmeticOverflow,
    /// Reports an internally inconsistent Markdown event sequence.
    State,
}

impl Display for RenderError {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        formatter.write_str(match self {
            Self::Cancelled => "markdown rendering was cancelled",
            Self::DeadlineExceeded => "markdown rendering exceeded its deadline",
            Self::InputLimit => "markdown input exceeds the preview limit",
            Self::EventLimit => "markdown event count exceeds the preview limit",
            Self::NestingLimit => "markdown nesting exceeds the preview limit",
            Self::BlockLimit => "markdown block count exceeds the preview limit",
            Self::SpanLimit => "markdown span count exceeds the preview limit",
            Self::SourceMapLimit => "markdown source-map count exceeds the preview limit",
            Self::PacketLimit => "markdown render packet exceeds the preview limit",
            Self::ArithmeticOverflow => "markdown render arithmetic overflowed",
            Self::State => "markdown parser produced an invalid event sequence",
        })
    }
}

impl Error for RenderError {}

/// Identifies an external interruption observed at a render checkpoint.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum RenderInterruption {
    /// Indicates explicit caller cancellation.
    Cancelled,

    /// Indicates that the caller's deadline elapsed.
    DeadlineExceeded,
}

/// Supplies cooperative cancellation and deadline checkpoints.
pub trait RenderControl {
    /// Checks whether the current render may continue.
    ///
    /// # Errors
    ///
    /// Returns the external interruption that must stop rendering.
    fn checkpoint(&mut self) -> Result<(), RenderInterruption>;
}

impl From<RenderInterruption> for RenderError {
    fn from(interruption: RenderInterruption) -> Self {
        match interruption {
            RenderInterruption::Cancelled => Self::Cancelled,
            RenderInterruption::DeadlineExceeded => Self::DeadlineExceeded,
        }
    }
}

/// Owns one complete encoded Markdown render packet and its validated metrics.
#[derive(Debug, Eq, PartialEq)]
pub struct RenderPacket {
    bytes: Vec<u8>,
    block_count: u32,
    span_count: u32,
    source_map_count: u32,
    contains_raw_html: bool,
}

impl RenderPacket {
    /// Returns the complete versioned packet bytes.
    #[must_use]
    pub fn as_bytes(&self) -> &[u8] {
        &self.bytes
    }

    /// Consumes the packet and returns its complete bytes.
    #[must_use]
    pub fn into_bytes(self) -> Vec<u8> {
        self.bytes
    }

    /// Returns the number of encoded render blocks.
    #[must_use]
    pub const fn block_count(&self) -> u32 {
        self.block_count
    }

    /// Returns the number of encoded styled spans.
    #[must_use]
    pub const fn span_count(&self) -> u32 {
        self.span_count
    }

    /// Returns the number of encoded source-map runs.
    #[must_use]
    pub const fn source_map_count(&self) -> u32 {
        self.source_map_count
    }

    /// Returns whether raw HTML is present as inert literal text.
    #[must_use]
    pub const fn contains_raw_html(&self) -> bool {
        self.contains_raw_html
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum InlineDestinationKind {
    Link,
    FootnoteReference,
}

#[derive(Clone, Debug, Eq, PartialEq)]
struct InlineDestination {
    kind: InlineDestinationKind,
    value: String,
}

#[derive(Clone, Debug, Eq, PartialEq)]
struct InlineSpan {
    start_utf16: u32,
    end_utf16: u32,
    styles: u32,
    destination: Option<InlineDestination>,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
struct SourceRange {
    start_utf16: u64,
    end_utf16: u64,
}

impl SourceRange {
    const fn new(start_utf16: u64, end_utf16: u64) -> Self {
        Self {
            start_utf16,
            end_utf16,
        }
    }

    const fn len(self) -> u64 {
        self.end_utf16.saturating_sub(self.start_utf16)
    }

    fn union(self, other: Self) -> Self {
        Self::new(
            self.start_utf16.min(other.start_utf16),
            self.end_utf16.max(other.end_utf16),
        )
    }

    fn slice_for_rendered(self, rendered_units: u32, slice_start: u32, slice_end: u32) -> Self {
        if self.len() == u64::from(rendered_units) {
            Self::new(
                self.start_utf16 + u64::from(slice_start),
                self.start_utf16 + u64::from(slice_end),
            )
        } else {
            self
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
struct InlineSourceMap {
    rendered_start_utf16: u32,
    rendered_end_utf16: u32,
    source: SourceRange,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
struct SourceOffsetCheckpoint {
    byte_offset: usize,
    logical_utf16: u64,
    previous_was_carriage_return: bool,
}

#[derive(Debug)]
struct SourceOffsetIndex<'a> {
    source: &'a str,
    checkpoints: Vec<SourceOffsetCheckpoint>,
    logical_utf16_units: u64,
}

impl<'a> SourceOffsetIndex<'a> {
    fn new(source: &'a str) -> Result<Self, RenderError> {
        let mut checkpoints = vec![SourceOffsetCheckpoint {
            byte_offset: 0,
            logical_utf16: 0,
            previous_was_carriage_return: false,
        }];
        let mut logical_utf16 = 0_u64;
        let mut previous_was_carriage_return = false;
        let mut next_checkpoint = SOURCE_OFFSET_CHECKPOINT_BYTES;
        for (byte_offset, character) in source.char_indices() {
            if byte_offset >= next_checkpoint {
                checkpoints.push(SourceOffsetCheckpoint {
                    byte_offset,
                    logical_utf16,
                    previous_was_carriage_return,
                });
                next_checkpoint = byte_offset
                    .checked_add(SOURCE_OFFSET_CHECKPOINT_BYTES)
                    .ok_or(RenderError::ArithmeticOverflow)?;
            }
            let added_utf16 = if character == '\n' && previous_was_carriage_return {
                0
            } else {
                u64::try_from(character.len_utf16()).map_err(|_| RenderError::ArithmeticOverflow)?
            };
            logical_utf16 = logical_utf16
                .checked_add(added_utf16)
                .ok_or(RenderError::ArithmeticOverflow)?;
            previous_was_carriage_return = character == '\r';
        }
        Ok(Self {
            source,
            checkpoints,
            logical_utf16_units: logical_utf16,
        })
    }

    fn source_range(&self, range: Range<usize>) -> Result<SourceRange, RenderError> {
        if range.start > range.end || range.end > self.source.len() {
            return Err(RenderError::State);
        }
        Ok(SourceRange::new(
            self.logical_utf16_at(range.start)?,
            self.logical_utf16_at(range.end)?,
        ))
    }

    fn logical_utf16_at(&self, byte_offset: usize) -> Result<u64, RenderError> {
        if byte_offset > self.source.len() || !self.source.is_char_boundary(byte_offset) {
            return Err(RenderError::State);
        }
        let checkpoint_index = self
            .checkpoints
            .partition_point(|checkpoint| checkpoint.byte_offset <= byte_offset)
            .checked_sub(1)
            .ok_or(RenderError::State)?;
        let checkpoint = self.checkpoints[checkpoint_index];
        let mut logical_utf16 = checkpoint.logical_utf16;
        let mut previous_was_carriage_return = checkpoint.previous_was_carriage_return;
        for character in self.source[checkpoint.byte_offset..byte_offset].chars() {
            if character != '\n' || !previous_was_carriage_return {
                logical_utf16 = logical_utf16
                    .checked_add(
                        u64::try_from(character.len_utf16())
                            .map_err(|_| RenderError::ArithmeticOverflow)?,
                    )
                    .ok_or(RenderError::ArithmeticOverflow)?;
            }
            previous_was_carriage_return = character == '\r';
        }
        Ok(logical_utf16)
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
struct BlockSpec {
    kind: u32,
    flags: u32,
    heading_level: u32,
    quote_depth: u32,
    list_depth: u32,
    list_number: u64,
    metadata: String,
}

#[derive(Debug, Eq, PartialEq)]
struct RenderBlock {
    spec: BlockSpec,
    text: String,
    utf16_units: u32,
    spans: Vec<InlineSpan>,
    span_bytes: usize,
    source_range: Option<SourceRange>,
    source_maps: Vec<InlineSourceMap>,
}

impl RenderBlock {
    fn new(spec: BlockSpec) -> Self {
        Self {
            spec,
            text: String::new(),
            utf16_units: 0,
            spans: Vec::new(),
            span_bytes: 0,
            source_range: None,
            source_maps: Vec::new(),
        }
    }

    fn include_source(&mut self, source: SourceRange) {
        self.source_range = Some(
            self.source_range
                .map_or(source, |current| current.union(source)),
        );
    }

    fn append(
        &mut self,
        text: &str,
        styles: u32,
        destination: Option<(InlineDestinationKind, &str)>,
    ) -> Result<(), RenderError> {
        let start_utf16 = self.utf16_units;
        let added_utf16 = u32::try_from(text.encode_utf16().count())
            .map_err(|_| RenderError::ArithmeticOverflow)?;
        self.utf16_units = self
            .utf16_units
            .checked_add(added_utf16)
            .ok_or(RenderError::ArithmeticOverflow)?;
        self.text.push_str(text);
        if start_utf16 == self.utf16_units || (styles == 0 && destination.is_none()) {
            return Ok(());
        }
        if let Some(previous) = self.spans.last_mut()
            && previous.end_utf16 == start_utf16
            && previous.styles == styles
            && styles & SPAN_STYLE_MATH == 0
            && !matches!(
                destination,
                Some((InlineDestinationKind::FootnoteReference, _))
            )
            && previous
                .destination
                .as_ref()
                .map(|destination| (destination.kind, destination.value.as_str()))
                == destination
        {
            previous.end_utf16 = self.utf16_units;
            return Ok(());
        }
        if self.spans.len() >= MAX_SPAN_COUNT {
            return Err(RenderError::SpanLimit);
        }
        let span_bytes = self
            .span_bytes
            .checked_add(SPAN_HEADER_BYTES)
            .and_then(|bytes| bytes.checked_add(destination.map_or(0, |(_, value)| value.len())))
            .ok_or(RenderError::ArithmeticOverflow)?;
        bounded_block_packet_bytes(
            self.text.len(),
            self.spec.metadata.len(),
            span_bytes,
            self.source_maps.len(),
        )?;
        self.spans.push(InlineSpan {
            start_utf16,
            end_utf16: self.utf16_units,
            styles,
            destination: destination.map(|(kind, value)| InlineDestination {
                kind,
                value: value.to_owned(),
            }),
        });
        self.span_bytes = span_bytes;
        Ok(())
    }

    fn append_mapped(
        &mut self,
        text: &str,
        styles: u32,
        destination: Option<(InlineDestinationKind, &str)>,
        source: SourceRange,
    ) -> Result<(), RenderError> {
        let rendered_start_utf16 = self.utf16_units;
        self.append(text, styles, destination)?;
        let rendered_end_utf16 = self.utf16_units;
        self.include_source(source);
        if rendered_start_utf16 == rendered_end_utf16 {
            return Ok(());
        }
        if source.start_utf16 == source.end_utf16 {
            return Err(RenderError::State);
        }
        if let Some(previous) = self.source_maps.last_mut()
            && previous.rendered_end_utf16 == rendered_start_utf16
            && previous.source.end_utf16 == source.start_utf16
            && previous.source.len()
                == u64::from(previous.rendered_end_utf16 - previous.rendered_start_utf16)
            && source.len() == u64::from(rendered_end_utf16 - rendered_start_utf16)
        {
            previous.rendered_end_utf16 = rendered_end_utf16;
            previous.source.end_utf16 = source.end_utf16;
            return Ok(());
        }
        if self.source_maps.len() >= MAX_SOURCE_MAP_COUNT {
            return Err(RenderError::SourceMapLimit);
        }
        self.source_maps.push(InlineSourceMap {
            rendered_start_utf16,
            rendered_end_utf16,
            source,
        });
        Ok(())
    }

    fn map_entire_text(&mut self, source: SourceRange) -> Result<(), RenderError> {
        self.include_source(source);
        if self.utf16_units != 0 && source.len() == 0 {
            return Err(RenderError::State);
        }
        if self.utf16_units != 0 && self.source_maps.is_empty() {
            self.source_maps.push(InlineSourceMap {
                rendered_start_utf16: 0,
                rendered_end_utf16: self.utf16_units,
                source,
            });
        }
        Ok(())
    }

    /// Returns this block's checked encoded size without allocating packet bytes.
    fn packet_bytes(&self) -> Result<usize, RenderError> {
        bounded_block_packet_bytes(
            self.text.len(),
            self.spec.metadata.len(),
            self.span_bytes,
            self.source_maps.len(),
        )
    }
}

/// Measures one block before its destination strings or packet bytes are allocated.
fn bounded_block_packet_bytes(
    text_bytes: usize,
    metadata_bytes: usize,
    span_bytes: usize,
    source_map_count: usize,
) -> Result<usize, RenderError> {
    let bytes = source_map_count
        .checked_mul(SOURCE_MAP_HEADER_BYTES)
        .and_then(|bytes| bytes.checked_add(BLOCK_HEADER_BYTES))
        .and_then(|bytes| bytes.checked_add(text_bytes))
        .and_then(|bytes| bytes.checked_add(metadata_bytes))
        .and_then(|bytes| bytes.checked_add(span_bytes))
        .ok_or(RenderError::ArithmeticOverflow)?;
    if bytes > MAX_PACKET_BYTES - PACKET_HEADER_BYTES {
        return Err(RenderError::PacketLimit);
    }
    Ok(bytes)
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
struct ListContext {
    next_number: Option<u64>,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
struct ListItemContext {
    ordered: bool,
    number: u64,
    source: SourceRange,
    first_paragraph: bool,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
struct QuoteContext {
    kind: Option<BlockQuoteKind>,
    starts_next_block: bool,
}

#[derive(Debug)]
struct DocumentBuilder {
    blocks: BlockBuffer,
    current: Option<RenderBlock>,
    quotes: Vec<QuoteContext>,
    lists: Vec<ListContext>,
    items: Vec<ListItemContext>,
    table_alignments: Option<String>,
    table_header: bool,
    table_cell_count: usize,
    footnote_label: Option<String>,
    footnote_source: Option<SourceRange>,
    contains_raw_html: bool,
}

impl DocumentBuilder {
    fn new() -> Self {
        Self {
            blocks: BlockBuffer::new(),
            current: None,
            quotes: Vec::new(),
            lists: Vec::new(),
            items: Vec::new(),
            table_alignments: None,
            table_header: false,
            table_cell_count: 0,
            footnote_label: None,
            footnote_source: None,
            contains_raw_html: false,
        }
    }

    fn start_block(&mut self, mut spec: BlockSpec, source: SourceRange) -> Result<(), RenderError> {
        if self.current.is_some() {
            return Err(RenderError::State);
        }
        spec.quote_depth =
            u32::try_from(self.quotes.len()).map_err(|_| RenderError::ArithmeticOverflow)?;
        if let Some(quote_index) = self.quotes.iter().rposition(|quote| quote.kind.is_some()) {
            let quote = &mut self.quotes[quote_index];
            spec.flags |= quote_kind_flag(quote.kind.ok_or(RenderError::State)?);
            if quote.starts_next_block {
                spec.flags |= BLOCK_FLAG_QUOTE_ALERT_START;
                quote.starts_next_block = false;
            }
        }
        let mut block = RenderBlock::new(spec);
        block.include_source(source);
        self.current = Some(block);
        Ok(())
    }

    fn start_text_block(
        &mut self,
        kind: u32,
        heading_level: u32,
        source: SourceRange,
    ) -> Result<(), RenderError> {
        let item = self.items.last().copied();
        let is_list_item = item.is_some() && kind == BLOCK_KIND_PARAGRAPH;
        let is_footnote =
            item.is_none() && kind == BLOCK_KIND_PARAGRAPH && self.footnote_label.is_some();
        let item = item.unwrap_or(ListItemContext {
            ordered: false,
            number: 0,
            source,
            first_paragraph: true,
        });
        let continues_list_item = is_list_item && !item.first_paragraph;
        if is_list_item {
            self.items
                .last_mut()
                .ok_or(RenderError::State)?
                .first_paragraph = false;
        }
        let source = if is_list_item {
            source.union(item.source)
        } else if is_footnote {
            source.union(self.footnote_source.ok_or(RenderError::State)?)
        } else {
            source
        };
        self.start_block(
            BlockSpec {
                kind: if is_footnote {
                    BLOCK_KIND_FOOTNOTE
                } else if is_list_item {
                    BLOCK_KIND_LIST_ITEM
                } else {
                    kind
                },
                flags: (if is_list_item && item.ordered {
                    BLOCK_FLAG_ORDERED_LIST
                } else {
                    0
                }) | if continues_list_item {
                    BLOCK_FLAG_LIST_ITEM_CONTINUATION
                } else {
                    0
                },
                heading_level,
                quote_depth: 0,
                list_depth: if is_list_item {
                    u32::try_from(self.lists.len()).map_err(|_| RenderError::ArithmeticOverflow)?
                } else {
                    0
                },
                list_number: if is_list_item && !continues_list_item {
                    item.number
                } else {
                    0
                },
                metadata: if is_footnote {
                    self.footnote_label.clone().ok_or(RenderError::State)?
                } else {
                    String::new()
                },
            },
            source,
        )
    }

    fn start_code_block(
        &mut self,
        kind: CodeBlockKind<'_>,
        source: SourceRange,
    ) -> Result<(), RenderError> {
        let metadata = match kind {
            CodeBlockKind::Indented => String::new(),
            CodeBlockKind::Fenced(info) => bounded_metadata(&info),
        };
        self.start_block(
            BlockSpec {
                kind: BLOCK_KIND_CODE,
                flags: 0,
                heading_level: 0,
                quote_depth: 0,
                list_depth: 0,
                list_number: 0,
                metadata,
            },
            source,
        )
    }

    fn start_html_block(&mut self, source: SourceRange) -> Result<(), RenderError> {
        self.start_block(
            BlockSpec {
                kind: BLOCK_KIND_HTML_LITERAL,
                flags: BLOCK_FLAG_RAW_HTML,
                heading_level: 0,
                quote_depth: 0,
                list_depth: 0,
                list_number: 0,
                metadata: String::new(),
            },
            source,
        )
    }

    fn start_table_row(&mut self, source: SourceRange) -> Result<(), RenderError> {
        self.table_cell_count = 0;
        self.start_block(
            BlockSpec {
                kind: BLOCK_KIND_TABLE_ROW,
                flags: if self.table_header {
                    BLOCK_FLAG_TABLE_HEADER | BLOCK_FLAG_TABLE_START
                } else {
                    0
                },
                heading_level: 0,
                quote_depth: 0,
                list_depth: 0,
                list_number: 0,
                metadata: self.table_alignments.clone().ok_or(RenderError::State)?,
            },
            source,
        )
    }

    fn enter_footnote(&mut self, label: &str, source: SourceRange) -> Result<(), RenderError> {
        if self.current.is_some() || self.footnote_label.is_some() || self.footnote_source.is_some()
        {
            return Err(RenderError::State);
        }
        self.footnote_label = Some(bounded_metadata(label));
        self.footnote_source = Some(source);
        Ok(())
    }

    fn leave_footnote(&mut self) -> Result<(), RenderError> {
        if self.current.is_some()
            || self.footnote_label.take().is_none()
            || self.footnote_source.take().is_none()
        {
            return Err(RenderError::State);
        }
        Ok(())
    }

    fn push_rule(&mut self, source: SourceRange) -> Result<(), RenderError> {
        if self.current.is_some() {
            return Err(RenderError::State);
        }
        self.start_block(
            BlockSpec {
                kind: BLOCK_KIND_RULE,
                flags: 0,
                heading_level: 0,
                quote_depth: 0,
                list_depth: 0,
                list_number: 0,
                metadata: String::new(),
            },
            source,
        )?;
        self.finish_block()
    }

    fn append(
        &mut self,
        mut text: &str,
        styles: u32,
        destination: Option<(InlineDestinationKind, &str)>,
        source: SourceRange,
    ) -> Result<(), RenderError> {
        let destination =
            destination.filter(|(_, value)| value.len() <= MAX_LINK_DESTINATION_BYTES);
        let rendered_utf16_units = u32::try_from(text.encode_utf16().count())
            .map_err(|_| RenderError::ArithmeticOverflow)?;
        let mut rendered_utf16_start = 0_u32;
        while !text.is_empty() {
            if self.current.is_none() {
                self.start_text_block(BLOCK_KIND_PARAGRAPH, 0, source)?;
            }
            let available = MAX_BUILDER_BLOCK_TEXT_BYTES
                .checked_sub(self.current.as_ref().ok_or(RenderError::State)?.text.len())
                .ok_or(RenderError::ArithmeticOverflow)?;
            if available == 0 {
                self.continue_current_block()?;
                continue;
            }
            let byte_count = bounded_char_boundary(text, available);
            if byte_count == 0 {
                self.continue_current_block()?;
                continue;
            }
            let chunk = &text[..byte_count];
            let chunk_utf16_units = u32::try_from(chunk.encode_utf16().count())
                .map_err(|_| RenderError::ArithmeticOverflow)?;
            let rendered_utf16_end = rendered_utf16_start
                .checked_add(chunk_utf16_units)
                .ok_or(RenderError::ArithmeticOverflow)?;
            let chunk_source = source.slice_for_rendered(
                rendered_utf16_units,
                rendered_utf16_start,
                rendered_utf16_end,
            );
            self.current
                .as_mut()
                .ok_or(RenderError::State)?
                .append_mapped(chunk, styles, destination, chunk_source)?;
            text = &text[byte_count..];
            rendered_utf16_start = rendered_utf16_end;
            if !text.is_empty() {
                self.continue_current_block()?;
            }
        }
        Ok(())
    }

    /// Appends one indivisible reference or preserves an oversized marker as literal text.
    fn append_footnote_reference(
        &mut self,
        label: &str,
        source: SourceRange,
    ) -> Result<(), RenderError> {
        let marker_bytes = label
            .len()
            .checked_add(FOOTNOTE_PREFIX.len() + FOOTNOTE_SUFFIX.len())
            .ok_or(RenderError::ArithmeticOverflow)?;
        if label.len() > MAX_LINK_DESTINATION_BYTES || marker_bytes > MAX_BLOCK_TEXT_BYTES {
            self.append(FOOTNOTE_PREFIX, SPAN_STYLE_CODE, None, source)?;
            self.append(label, SPAN_STYLE_CODE, None, source)?;
            return self.append(FOOTNOTE_SUFFIX, SPAN_STYLE_CODE, None, source);
        }
        if self
            .current
            .as_ref()
            .is_some_and(|block| block.text.len() > MAX_BUILDER_BLOCK_TEXT_BYTES - marker_bytes)
        {
            self.continue_current_block()?;
        }
        let mut marker = String::with_capacity(marker_bytes);
        marker.push_str(FOOTNOTE_PREFIX);
        marker.push_str(label);
        marker.push_str(FOOTNOTE_SUFFIX);
        self.append(
            &marker,
            SPAN_STYLE_CODE | SPAN_STYLE_FOOTNOTE_REFERENCE,
            Some((InlineDestinationKind::FootnoteReference, label)),
            source,
        )
    }

    /// Keeps an admitted formula together through builder and presentation fragmentation.
    fn append_math(
        &mut self,
        math: &str,
        display: bool,
        inline: &InlineState,
        source: SourceRange,
    ) -> Result<(), RenderError> {
        // Larger formulas remain literal TeX, and retain ordinary bounded fragmentation.
        // A tab is the table protocol's structural separator, not a drawable math glyph.
        if math.len() > MAX_BLOCK_TEXT_BYTES
            || math.contains('\t')
            || inline.styles() & (SPAN_STYLE_CODE | SPAN_STYLE_SUBSCRIPT | SPAN_STYLE_SUPERSCRIPT)
                != 0
        {
            return self.append(
                math,
                inline.styles() | SPAN_STYLE_CODE,
                inline.destination(),
                source,
            );
        }
        if self
            .current
            .as_ref()
            .is_some_and(|block| block.text.len() > MAX_BUILDER_BLOCK_TEXT_BYTES - math.len())
        {
            self.continue_current_block()?;
        }
        let styles =
            inline.styles() | SPAN_STYLE_MATH | if display { SPAN_STYLE_DISPLAY_MATH } else { 0 };
        self.append(math, styles, inline.destination(), source)
    }

    fn allows_extended_autolinks(&self) -> bool {
        self.current.as_ref().is_none_or(|block| {
            block.spec.kind != BLOCK_KIND_CODE
                && block.spec.kind != BLOCK_KIND_HTML_LITERAL
                && block.spec.flags & BLOCK_FLAG_RAW_HTML == 0
        })
    }

    fn mark_task(&mut self, checked: bool, source: SourceRange) -> Result<(), RenderError> {
        if self.current.is_none() {
            self.start_text_block(BLOCK_KIND_PARAGRAPH, 0, source)?;
        }
        let current = self.current.as_mut().ok_or(RenderError::State)?;
        if current.spec.kind != BLOCK_KIND_LIST_ITEM {
            return Err(RenderError::State);
        }
        current.spec.flags |= if checked {
            BLOCK_FLAG_TASK_CHECKED
        } else {
            BLOCK_FLAG_TASK_UNCHECKED
        };
        self.append(
            if checked {
                CHECKED_TASK_PREFIX
            } else {
                UNCHECKED_TASK_PREFIX
            },
            0,
            None,
            source,
        )
    }

    fn mark_raw_html(&mut self, source: SourceRange) -> Result<(), RenderError> {
        if self.current.is_none() {
            self.start_text_block(BLOCK_KIND_PARAGRAPH, 0, source)?;
        }
        self.contains_raw_html = true;
        self.current.as_mut().ok_or(RenderError::State)?.spec.flags |= BLOCK_FLAG_RAW_HTML;
        Ok(())
    }

    fn start_table_cell(&mut self, source: SourceRange) -> Result<(), RenderError> {
        if self.table_cell_count != 0 {
            self.append(TABLE_CELL_SEPARATOR, 0, None, source)?;
        }
        self.table_cell_count = self
            .table_cell_count
            .checked_add(1)
            .ok_or(RenderError::ArithmeticOverflow)?;
        Ok(())
    }

    fn enter_table(&mut self, alignments: &[Alignment]) -> Result<(), RenderError> {
        if self.table_alignments.is_some() || alignments.is_empty() {
            return Err(RenderError::State);
        }
        let mut encoded = String::with_capacity(alignments.len());
        encoded.extend(alignments.iter().map(|alignment| match alignment {
            Alignment::None => TABLE_ALIGNMENT_NONE,
            Alignment::Left => TABLE_ALIGNMENT_LEFT,
            Alignment::Center => TABLE_ALIGNMENT_CENTER,
            Alignment::Right => TABLE_ALIGNMENT_RIGHT,
        }));
        self.table_alignments = Some(encoded);
        Ok(())
    }

    fn leave_table(&mut self) -> Result<(), RenderError> {
        if self.current.is_some() || self.table_alignments.take().is_none() {
            return Err(RenderError::State);
        }
        Ok(())
    }

    fn finish_block(&mut self) -> Result<(), RenderError> {
        let block = self.current.take().ok_or(RenderError::State)?;
        self.push_block(block)
    }

    fn include_current_source_end(&mut self, source: SourceRange) -> Result<(), RenderError> {
        let current = self.current.as_mut().ok_or(RenderError::State)?;
        let start = current.source_range.ok_or(RenderError::State)?.start_utf16;
        current.include_source(SourceRange::new(start, source.end_utf16));
        Ok(())
    }

    fn finish_html_block(&mut self) -> Result<(), RenderError> {
        let block = self.current.take().ok_or(RenderError::State)?;
        if block.spec.flags & BLOCK_FLAG_CONTINUATION == 0
            && let Some(blocks) =
                html::render_safe_html_blocks(&block.text, block.spec.quote_depth)?
        {
            let quote_kind_flags = block.spec.flags & BLOCK_FLAGS_QUOTE_KIND;
            let starts_alert = block.spec.flags & BLOCK_FLAG_QUOTE_ALERT_START != 0;
            let source = block.source_range.ok_or(RenderError::State)?;
            for (block_index, mut safe_block) in blocks.blocks.into_iter().enumerate() {
                safe_block.spec.flags |= quote_kind_flags;
                if starts_alert && block_index == 0 {
                    safe_block.spec.flags |= BLOCK_FLAG_QUOTE_ALERT_START;
                }
                safe_block.map_entire_text(source)?;
                self.push_block(safe_block)?;
            }
            return Ok(());
        }
        self.contains_raw_html = true;
        self.push_block(block)
    }

    fn continue_current_block(&mut self) -> Result<(), RenderError> {
        let mut completed = self.current.take().ok_or(RenderError::State)?;
        if let Some(last_mapping) = completed.source_maps.last() {
            let start = completed
                .source_range
                .ok_or(RenderError::State)?
                .start_utf16;
            // End the chunk at consumed text, not at the enclosing parser tag's end.
            completed.source_range = Some(SourceRange::new(start, last_mapping.source.end_utf16));
        }
        let mut continuation_spec = completed.spec.clone();
        continuation_spec.flags |= BLOCK_FLAG_CONTINUATION;
        continuation_spec.flags &= !BLOCK_FLAG_TABLE_START;
        if completed.spec.kind == BLOCK_KIND_TABLE_ROW {
            continuation_spec.metadata = table_alignment_suffix(
                &completed.spec.metadata,
                completed.text.bytes().filter(|byte| *byte == b'\t').count(),
            )?;
        }
        if completed.spec.kind == BLOCK_KIND_HTML_LITERAL {
            self.contains_raw_html = true;
        }
        completed.text.shrink_to_fit();
        self.push_block(completed)?;
        self.current = Some(RenderBlock::new(continuation_spec));
        Ok(())
    }

    fn push_block(&mut self, block: RenderBlock) -> Result<(), RenderError> {
        self.blocks.push(block)
    }

    fn push_list(&mut self, start: Option<u64>) -> Result<(), RenderError> {
        if let Some(current) = self.current.as_ref() {
            if current.spec.kind != BLOCK_KIND_LIST_ITEM {
                return Err(RenderError::State);
            }
            self.finish_block()?;
        }
        if self.lists.len() >= usize::try_from(MAX_LIST_DEPTH).unwrap_or(usize::MAX) {
            return Err(RenderError::NestingLimit);
        }
        self.lists.push(ListContext { next_number: start });
        Ok(())
    }

    fn pop_list(&mut self) -> Result<(), RenderError> {
        if self.items.len() >= self.lists.len() || self.lists.pop().is_none() {
            return Err(RenderError::State);
        }
        Ok(())
    }

    fn start_item(&mut self, source: SourceRange) -> Result<(), RenderError> {
        if self.items.len().checked_add(1) != Some(self.lists.len()) {
            return Err(RenderError::State);
        }
        let list = self.lists.last_mut().ok_or(RenderError::State)?;
        let number = list.next_number.unwrap_or(0);
        let ordered = list.next_number.is_some();
        if let Some(next_number) = list.next_number {
            list.next_number = Some(next_number.saturating_add(1));
        }
        self.items.push(ListItemContext {
            ordered,
            number,
            source,
            first_paragraph: true,
        });
        Ok(())
    }

    fn finish_item(&mut self) -> Result<(), RenderError> {
        if self.current.is_some() {
            self.finish_block()?;
        }
        if self.items.len() != self.lists.len() || self.items.pop().is_none() {
            return Err(RenderError::State);
        }
        Ok(())
    }

    fn enter_quote(&mut self, kind: Option<BlockQuoteKind>) -> Result<(), RenderError> {
        if self.quotes.len() >= usize::try_from(MAX_QUOTE_DEPTH).unwrap_or(usize::MAX) {
            return Err(RenderError::NestingLimit);
        }
        self.quotes.push(QuoteContext {
            kind,
            starts_next_block: kind.is_some(),
        });
        Ok(())
    }

    fn leave_quote(&mut self) -> Result<(), RenderError> {
        self.quotes.pop().map(|_| ()).ok_or(RenderError::State)
    }

    fn finish(self) -> Result<RenderDocument, RenderError> {
        if self.current.is_some()
            || !self.quotes.is_empty()
            || !self.lists.is_empty()
            || !self.items.is_empty()
            || self.table_alignments.is_some()
            || self.footnote_label.is_some()
            || self.footnote_source.is_some()
        {
            return Err(RenderError::State);
        }
        let mut blocks = split_blocks_for_presentation(self.blocks.blocks)?;
        blocks.blocks.shrink_to_fit();
        Ok(RenderDocument {
            blocks: blocks.blocks,
            span_count: blocks.span_count,
            source_map_count: blocks.source_map_count,
            contains_raw_html: self.contains_raw_html,
        })
    }
}

#[derive(Debug)]
struct InlineState {
    emphasis_depth: usize,
    strong_depth: usize,
    code_depth: usize,
    strikethrough_depth: usize,
    superscript_depth: usize,
    subscript_depth: usize,
    link_depth: usize,
    link_destination: Option<String>,
    image_depth: usize,
    html_tags: Vec<html::SafeHtmlInlineTag>,
}

impl InlineState {
    fn new() -> Self {
        Self {
            emphasis_depth: 0,
            strong_depth: 0,
            code_depth: 0,
            strikethrough_depth: 0,
            superscript_depth: 0,
            subscript_depth: 0,
            link_depth: 0,
            link_destination: None,
            image_depth: 0,
            html_tags: Vec::new(),
        }
    }

    fn styles(&self) -> u32 {
        let mut styles = 0;
        if self.emphasis_depth != 0 {
            styles |= SPAN_STYLE_EMPHASIS;
        }
        if self.strong_depth != 0 {
            styles |= SPAN_STYLE_STRONG;
        }
        if self.code_depth != 0 {
            styles |= SPAN_STYLE_CODE;
        }
        if self.strikethrough_depth != 0 {
            styles |= SPAN_STYLE_STRIKETHROUGH;
        }
        if self.superscript_depth != 0 {
            styles |= SPAN_STYLE_SUPERSCRIPT;
        }
        if self.subscript_depth != 0 {
            styles |= SPAN_STYLE_SUBSCRIPT;
        }
        styles
    }

    fn destination(&self) -> Option<(InlineDestinationKind, &str)> {
        if self.image_depth == 0 {
            self.link_destination
                .as_deref()
                .map(|destination| (InlineDestinationKind::Link, destination))
        } else {
            None
        }
    }

    fn allows_extended_autolinks(&self) -> bool {
        self.code_depth == 0
            && self.link_depth == 0
            && self.link_destination.is_none()
            && self.image_depth == 0
    }

    fn is_balanced(&self) -> bool {
        self.emphasis_depth == 0
            && self.strong_depth == 0
            && self.code_depth == 0
            && self.strikethrough_depth == 0
            && self.superscript_depth == 0
            && self.subscript_depth == 0
            && self.link_depth == 0
            && self.link_destination.is_none()
            && self.image_depth == 0
            && self.html_tags.is_empty()
    }

    fn start_html_tag(
        &mut self,
        tag: html::SafeHtmlInlineTag,
        destination: Option<String>,
    ) -> Result<(), RenderError> {
        if self.html_tags.len() >= MAX_NESTING_DEPTH {
            return Err(RenderError::NestingLimit);
        }
        if tag == html::SafeHtmlInlineTag::Anchor {
            if self.link_depth != 0 || destination.is_none() {
                return Err(RenderError::State);
            }
            self.link_depth = 1;
            self.link_destination = destination;
        } else if destination.is_some() {
            return Err(RenderError::State);
        }
        self.increment_html_style(tag)?;
        self.html_tags.push(tag);
        Ok(())
    }

    fn end_html_tag(&mut self, tag: html::SafeHtmlInlineTag) -> Result<bool, RenderError> {
        if self.html_tags.last().copied() != Some(tag) {
            return Ok(false);
        }
        self.html_tags.pop();
        self.decrement_html_style(tag)?;
        if tag == html::SafeHtmlInlineTag::Anchor {
            if self.link_depth != 1 || self.link_destination.is_none() {
                return Err(RenderError::State);
            }
            self.link_depth = 0;
            self.link_destination = None;
        }
        Ok(true)
    }

    fn close_html_tags(&mut self) -> Result<(), RenderError> {
        while let Some(tag) = self.html_tags.pop() {
            self.decrement_html_style(tag)?;
            if tag == html::SafeHtmlInlineTag::Anchor {
                if self.link_depth != 1 || self.link_destination.is_none() {
                    return Err(RenderError::State);
                }
                self.link_depth = 0;
                self.link_destination = None;
            }
        }
        Ok(())
    }

    fn increment_html_style(&mut self, tag: html::SafeHtmlInlineTag) -> Result<(), RenderError> {
        match tag.styles() {
            SPAN_STYLE_EMPHASIS => increment_depth(&mut self.emphasis_depth),
            SPAN_STYLE_STRONG => increment_depth(&mut self.strong_depth),
            SPAN_STYLE_CODE => increment_depth(&mut self.code_depth),
            SPAN_STYLE_STRIKETHROUGH => increment_depth(&mut self.strikethrough_depth),
            SPAN_STYLE_SUPERSCRIPT => increment_depth(&mut self.superscript_depth),
            SPAN_STYLE_SUBSCRIPT => increment_depth(&mut self.subscript_depth),
            0 => Ok(()),
            _ => Err(RenderError::State),
        }
    }

    fn decrement_html_style(&mut self, tag: html::SafeHtmlInlineTag) -> Result<(), RenderError> {
        match tag.styles() {
            SPAN_STYLE_EMPHASIS => decrement_depth(&mut self.emphasis_depth),
            SPAN_STYLE_STRONG => decrement_depth(&mut self.strong_depth),
            SPAN_STYLE_CODE => decrement_depth(&mut self.code_depth),
            SPAN_STYLE_STRIKETHROUGH => decrement_depth(&mut self.strikethrough_depth),
            SPAN_STYLE_SUPERSCRIPT => decrement_depth(&mut self.superscript_depth),
            SPAN_STYLE_SUBSCRIPT => decrement_depth(&mut self.subscript_depth),
            0 => Ok(()),
            _ => Err(RenderError::State),
        }
    }
}

#[derive(Debug)]
struct RenderDocument {
    blocks: Vec<RenderBlock>,
    span_count: usize,
    source_map_count: usize,
    contains_raw_html: bool,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
struct EncodedDocumentMetrics {
    block_count: u32,
    span_count: u32,
    source_map_count: u32,
    text_bytes: u64,
    auxiliary_bytes: u64,
    packet_bytes: usize,
}

/// Parses Markdown and returns one strict, bounded binary render packet.
///
/// # Errors
///
/// Returns an error when the input or derived render model violates a
/// defensive limit or the parser produces an inconsistent event sequence.
pub fn render_markdown(markdown: &str) -> Result<RenderPacket, RenderError> {
    render_markdown_with_control(markdown, &mut Uninterrupted)
}

/// Returns the complete bounded Markdown feature set.
fn markdown_options() -> Options {
    let mut options = Options::empty();
    options.insert(Options::ENABLE_TABLES);
    options.insert(Options::ENABLE_STRIKETHROUGH);
    options.insert(Options::ENABLE_TASKLISTS);
    options.insert(Options::ENABLE_FOOTNOTES);
    options.insert(Options::ENABLE_GFM);
    options.insert(Options::ENABLE_SUPERSCRIPT);
    options.insert(Options::ENABLE_SUBSCRIPT);
    options.insert(Options::ENABLE_MATH);
    options
}

/// Validates the input size and observes the initial render checkpoint.
fn validate_markdown_input(
    markdown: &str,
    control: &mut impl RenderControl,
) -> Result<(), RenderError> {
    if markdown.len() > MAX_INPUT_BYTES {
        return Err(RenderError::InputLimit);
    }
    control.checkpoint()?;
    Ok(())
}

/// Parses Markdown with checkpoints and returns one bounded binary render packet.
///
/// # Errors
///
/// Returns an error when rendering is interrupted, the input or derived model
/// violates a defensive limit, or parser events are inconsistent.
pub fn render_markdown_with_control(
    markdown: &str,
    control: &mut impl RenderControl,
) -> Result<RenderPacket, RenderError> {
    validate_markdown_input(markdown, control)?;
    let options = markdown_options();
    let parser_input = markdown.strip_prefix('\u{feff}').unwrap_or(markdown);
    let source_offsets = SourceOffsetIndex::new(parser_input)?;
    let bracket_math = bracket_math::BracketMath::prepare(parser_input, options, control)?;
    let prepared_input = bracket_math.input.as_ref();
    let render_safe_inline_html = validate_safe_inline_html(prepared_input, options, control)?;
    control.checkpoint()?;
    let parser = Parser::new_ext(prepared_input, options).into_offset_iter();
    let mut builder = DocumentBuilder::new();
    let mut inline = InlineState::new();
    let mut nesting_depth = 0_usize;

    for (event_index, (event, byte_range)) in parser.enumerate() {
        if event_index >= MAX_EVENT_COUNT {
            return Err(RenderError::EventLimit);
        }
        if event_index % CHECKPOINT_EVENT_INTERVAL == 0 {
            control.checkpoint()?;
        }
        let source = source_offsets.source_range(byte_range.clone())?;
        match event {
            Event::Start(tag) => {
                nesting_depth = nesting_depth
                    .checked_add(1)
                    .ok_or(RenderError::ArithmeticOverflow)?;
                if nesting_depth > MAX_NESTING_DEPTH {
                    return Err(RenderError::NestingLimit);
                }
                handle_start(tag, source, &mut builder, &mut inline)?;
            }
            Event::End(tag) => {
                handle_end(tag, source, &mut builder, &mut inline)?;
                nesting_depth = nesting_depth.checked_sub(1).ok_or(RenderError::State)?;
            }
            Event::Text(text) => {
                append_text_with_extended_autolinks(&text, source, &mut builder, &inline)?;
            }
            Event::Code(code) => {
                builder.append(
                    &code,
                    inline.styles() | SPAN_STYLE_CODE,
                    inline.destination(),
                    source,
                )?;
            }
            Event::InlineMath(math) => {
                let mapped = math_source_range(&parser_input[byte_range], &math, false, source)?;
                builder.append_math(&math, false, &inline, mapped)?;
            }
            Event::DisplayMath(math) => {
                let display = bracket_math.display_style(&byte_range).unwrap_or(true);
                let mapped = math_source_range(&prepared_input[byte_range], &math, true, source)?;
                builder.append_math(&math, display, &inline, mapped)?;
            }
            Event::Html(html) => {
                builder.append(&html, inline.styles() | SPAN_STYLE_CODE, None, source)?;
            }
            Event::InlineHtml(html) => {
                handle_inline_html(
                    &html,
                    source,
                    render_safe_inline_html,
                    &mut builder,
                    &mut inline,
                )?;
            }
            Event::FootnoteReference(label) => {
                builder.append_footnote_reference(&label, source)?;
            }
            Event::SoftBreak => {
                builder.append(SOFT_BREAK, inline.styles(), inline.destination(), source)?;
            }
            Event::HardBreak => {
                builder.append(HARD_BREAK, inline.styles(), inline.destination(), source)?;
            }
            Event::Rule => builder.push_rule(source)?,
            Event::TaskListMarker(checked) => builder.mark_task(checked, source)?,
        }
    }
    inline.close_html_tags()?;
    if nesting_depth != 0 || !inline.is_balanced() {
        return Err(RenderError::State);
    }
    control.checkpoint()?;
    encode_document(
        &builder.finish()?,
        markdown.len(),
        source_offsets.logical_utf16_units,
        control,
    )
}

/// Maps verbatim TeX into the delimiters rather than treating the whole expression as encoded text.
/// The containing Markdown block still owns its full syntax range for navigation from source.
fn math_source_range(
    original: &str,
    math: &str,
    display: bool,
    source: SourceRange,
) -> Result<SourceRange, RenderError> {
    let delimiter = if display { "$$" } else { "$" };
    if original
        .strip_prefix(delimiter)
        .and_then(|body| body.strip_suffix(delimiter))
        != Some(math)
    {
        return Ok(source);
    }
    let units = if display { 2 } else { 1 };
    Ok(SourceRange::new(
        source
            .start_utf16
            .checked_add(units)
            .ok_or(RenderError::ArithmeticOverflow)?,
        source
            .end_utf16
            .checked_sub(units)
            .ok_or(RenderError::ArithmeticOverflow)?,
    ))
}

struct Uninterrupted;

impl RenderControl for Uninterrupted {
    fn checkpoint(&mut self) -> Result<(), RenderInterruption> {
        Ok(())
    }
}

/// Appends ordinary text while recognizing bounded GFM extended autolinks.
fn append_text_with_extended_autolinks(
    text: &str,
    source: SourceRange,
    builder: &mut DocumentBuilder,
    inline: &InlineState,
) -> Result<(), RenderError> {
    let styles = inline.styles();
    if !inline.allows_extended_autolinks() || !builder.allows_extended_autolinks() {
        return builder.append(text, styles, inline.destination(), source);
    }
    let rendered_utf16_units =
        u32::try_from(text.encode_utf16().count()).map_err(|_| RenderError::ArithmeticOverflow)?;
    let mut text_start = 0_usize;
    let mut rendered_utf16_start = 0_u32;
    while let Some(link) =
        autolink::find_extended_autolink(text, text_start, MAX_LINK_DESTINATION_BYTES)
    {
        let prefix = &text[text_start..link.start];
        let prefix_utf16_units = u32::try_from(prefix.encode_utf16().count())
            .map_err(|_| RenderError::ArithmeticOverflow)?;
        let prefix_end = rendered_utf16_start
            .checked_add(prefix_utf16_units)
            .ok_or(RenderError::ArithmeticOverflow)?;
        builder.append(
            prefix,
            styles,
            None,
            source.slice_for_rendered(rendered_utf16_units, rendered_utf16_start, prefix_end),
        )?;
        let link_text = &text[link.start..link.end];
        let link_utf16_units = u32::try_from(link_text.encode_utf16().count())
            .map_err(|_| RenderError::ArithmeticOverflow)?;
        let link_end = prefix_end
            .checked_add(link_utf16_units)
            .ok_or(RenderError::ArithmeticOverflow)?;
        builder.append(
            link_text,
            styles,
            Some((InlineDestinationKind::Link, link.destination.as_ref())),
            source.slice_for_rendered(rendered_utf16_units, prefix_end, link_end),
        )?;
        text_start = link.end;
        rendered_utf16_start = link_end;
    }
    builder.append(
        &text[text_start..],
        styles,
        None,
        source.slice_for_rendered(
            rendered_utf16_units,
            rendered_utf16_start,
            rendered_utf16_units,
        ),
    )
}

/// Validates all safe inline HTML pairs before omitting their source tags.
fn validate_safe_inline_html(
    markdown: &str,
    options: Options,
    control: &mut impl RenderControl,
) -> Result<bool, RenderError> {
    if !markdown.contains('<') {
        return Ok(true);
    }
    let mut tags = Vec::new();
    let mut markdown_link_depth = 0_usize;
    for (event_index, event) in Parser::new_ext(markdown, options).enumerate() {
        if event_index >= MAX_EVENT_COUNT {
            return Err(RenderError::EventLimit);
        }
        if event_index % CHECKPOINT_EVENT_INTERVAL == 0 {
            control.checkpoint()?;
        }
        match event {
            Event::InlineHtml(source) => match html::parse_safe_inline_tag(&source) {
                Some(html::SafeHtmlInlineToken::Start { tag, .. }) => {
                    if tags.len() >= MAX_NESTING_DEPTH {
                        return Ok(false);
                    }
                    if tag == html::SafeHtmlInlineTag::Anchor
                        && (markdown_link_depth != 0
                            || tags.contains(&html::SafeHtmlInlineTag::Anchor))
                    {
                        return Ok(false);
                    }
                    tags.push(tag);
                }
                Some(html::SafeHtmlInlineToken::End(tag)) => {
                    if tags.pop() != Some(tag) {
                        return Ok(false);
                    }
                }
                Some(html::SafeHtmlInlineToken::Break | html::SafeHtmlInlineToken::Image(_)) => {}
                None => return Ok(false),
            },
            Event::Start(Tag::Link { .. }) => {
                if tags.contains(&html::SafeHtmlInlineTag::Anchor) {
                    return Ok(false);
                }
                markdown_link_depth = markdown_link_depth
                    .checked_add(1)
                    .ok_or(RenderError::ArithmeticOverflow)?;
            }
            Event::End(TagEnd::Link) => {
                markdown_link_depth = markdown_link_depth
                    .checked_sub(1)
                    .ok_or(RenderError::State)?;
            }
            Event::End(tag) if ends_inline_html_scope(tag) && !tags.is_empty() => {
                return Ok(false);
            }
            _ => {}
        }
    }
    Ok(tags.is_empty() && markdown_link_depth == 0)
}

/// Returns whether one Markdown boundary closes an inline HTML scope.
const fn ends_inline_html_scope(tag: TagEnd) -> bool {
    matches!(
        tag,
        TagEnd::Paragraph
            | TagEnd::Heading(_)
            | TagEnd::CodeBlock
            | TagEnd::Item
            | TagEnd::TableHead
            | TagEnd::TableRow
    )
}

/// Applies one opening parser event to the bounded render state.
fn handle_start(
    tag: Tag<'_>,
    source: SourceRange,
    builder: &mut DocumentBuilder,
    inline: &mut InlineState,
) -> Result<(), RenderError> {
    match tag {
        Tag::Paragraph => builder.start_text_block(BLOCK_KIND_PARAGRAPH, 0, source),
        Tag::Heading { level, .. } => {
            builder.start_text_block(BLOCK_KIND_HEADING, heading_level(level), source)
        }
        Tag::BlockQuote(kind) => builder.enter_quote(kind),
        Tag::CodeBlock(kind) => builder.start_code_block(kind, source),
        Tag::HtmlBlock => builder.start_html_block(source),
        Tag::List(start) => builder.push_list(start),
        Tag::Item => builder.start_item(source),
        Tag::FootnoteDefinition(label) => builder.enter_footnote(&label, source),
        Tag::Table(alignments) => builder.enter_table(&alignments),
        Tag::TableHead => {
            builder.table_header = true;
            builder.start_table_row(source)
        }
        Tag::TableRow => builder.start_table_row(source),
        Tag::TableCell => builder.start_table_cell(source),
        Tag::Emphasis => increment_depth(&mut inline.emphasis_depth),
        Tag::Strong => increment_depth(&mut inline.strong_depth),
        Tag::Strikethrough => increment_depth(&mut inline.strikethrough_depth),
        Tag::Superscript => increment_depth(&mut inline.superscript_depth),
        Tag::Subscript => increment_depth(&mut inline.subscript_depth),
        Tag::Link { dest_url, .. } => {
            if inline.link_depth != 0 {
                return Err(RenderError::State);
            }
            inline.link_depth = 1;
            inline.link_destination = if dest_url.len() <= MAX_LINK_DESTINATION_BYTES {
                Some(dest_url.into_string())
            } else {
                None
            };
            Ok(())
        }
        Tag::Image { .. } => {
            inline.image_depth = inline
                .image_depth
                .checked_add(1)
                .ok_or(RenderError::ArithmeticOverflow)?;
            builder.append(IMAGE_PREFIX, inline.styles(), None, source)
        }
        Tag::MetadataBlock(_)
        | Tag::DefinitionList
        | Tag::DefinitionListTitle
        | Tag::DefinitionListDefinition => Err(RenderError::State),
    }
}

/// Applies one closing parser event to the bounded render state.
fn handle_end(
    tag: TagEnd,
    source: SourceRange,
    builder: &mut DocumentBuilder,
    inline: &mut InlineState,
) -> Result<(), RenderError> {
    match tag {
        TagEnd::Paragraph | TagEnd::Heading(_) | TagEnd::CodeBlock | TagEnd::TableRow => {
            inline.close_html_tags()?;
            builder.include_current_source_end(source)?;
            builder.finish_block()
        }
        TagEnd::HtmlBlock => {
            builder.include_current_source_end(source)?;
            builder.finish_html_block()
        }
        TagEnd::FootnoteDefinition => builder.leave_footnote(),
        TagEnd::BlockQuote(_) => builder.leave_quote(),
        TagEnd::List(_) => builder.pop_list(),
        TagEnd::Item => {
            inline.close_html_tags()?;
            if builder.current.is_some() {
                builder.include_current_source_end(source)?;
            }
            builder.finish_item()
        }
        TagEnd::Table => builder.leave_table(),
        TagEnd::TableCell => Ok(()),
        TagEnd::TableHead => {
            inline.close_html_tags()?;
            builder.include_current_source_end(source)?;
            builder.finish_block()?;
            builder.table_header = false;
            Ok(())
        }
        TagEnd::Emphasis => decrement_depth(&mut inline.emphasis_depth),
        TagEnd::Strong => decrement_depth(&mut inline.strong_depth),
        TagEnd::Strikethrough => decrement_depth(&mut inline.strikethrough_depth),
        TagEnd::Superscript => decrement_depth(&mut inline.superscript_depth),
        TagEnd::Subscript => decrement_depth(&mut inline.subscript_depth),
        TagEnd::Link => {
            if inline.link_depth != 1 {
                return Err(RenderError::State);
            }
            inline.link_depth = 0;
            inline.link_destination = None;
            Ok(())
        }
        TagEnd::Image => {
            inline.image_depth = inline
                .image_depth
                .checked_sub(1)
                .ok_or(RenderError::State)?;
            Ok(())
        }
        TagEnd::MetadataBlock(_)
        | TagEnd::DefinitionList
        | TagEnd::DefinitionListTitle
        | TagEnd::DefinitionListDefinition => Err(RenderError::State),
    }
}

/// Converts one allowlisted inline HTML tag or preserves it as inert source.
fn handle_inline_html(
    source: &str,
    source_range: SourceRange,
    render_safe_html: bool,
    builder: &mut DocumentBuilder,
    inline: &mut InlineState,
) -> Result<(), RenderError> {
    let rendered = render_safe_html
        && match html::parse_safe_inline_tag(source) {
            Some(html::SafeHtmlInlineToken::Start { tag, destination }) => {
                inline.start_html_tag(tag, destination)?;
                true
            }
            Some(html::SafeHtmlInlineToken::End(tag)) => inline.end_html_tag(tag)?,
            Some(html::SafeHtmlInlineToken::Break) => {
                builder.append(
                    HARD_BREAK,
                    inline.styles(),
                    inline.destination(),
                    source_range,
                )?;
                true
            }
            Some(html::SafeHtmlInlineToken::Image(label)) => {
                builder.append(&label, inline.styles(), inline.destination(), source_range)?;
                true
            }
            None => false,
        };
    if !rendered {
        builder.mark_raw_html(source_range)?;
        builder.append(
            source,
            inline.styles() | SPAN_STYLE_CODE,
            None,
            source_range,
        )?;
    }
    Ok(())
}

/// Increments one checked inline nesting depth.
fn increment_depth(depth: &mut usize) -> Result<(), RenderError> {
    *depth = depth
        .checked_add(1)
        .ok_or(RenderError::ArithmeticOverflow)?;
    Ok(())
}

/// Decrements one checked inline nesting depth.
fn decrement_depth(depth: &mut usize) -> Result<(), RenderError> {
    *depth = depth.checked_sub(1).ok_or(RenderError::State)?;
    Ok(())
}

/// Returns the protocol flag for one GFM blockquote alert kind.
const fn quote_kind_flag(kind: BlockQuoteKind) -> u32 {
    match kind {
        BlockQuoteKind::Note => BLOCK_FLAG_QUOTE_NOTE,
        BlockQuoteKind::Tip => BLOCK_FLAG_QUOTE_TIP,
        BlockQuoteKind::Important => BLOCK_FLAG_QUOTE_IMPORTANT,
        BlockQuoteKind::Warning => BLOCK_FLAG_QUOTE_WARNING,
        BlockQuoteKind::Caution => BLOCK_FLAG_QUOTE_CAUTION,
    }
}

/// Converts a parser heading level to its stable protocol value.
fn heading_level(level: HeadingLevel) -> u32 {
    match level {
        HeadingLevel::H1 => 1,
        HeadingLevel::H2 => 2,
        HeadingLevel::H3 => 3,
        HeadingLevel::H4 => 4,
        HeadingLevel::H5 => 5,
        HeadingLevel::H6 => 6,
    }
}

/// Copies metadata through a bounded UTF-8 boundary.
fn bounded_metadata(value: &str) -> String {
    let byte_count = bounded_char_boundary(value, MAX_METADATA_BYTES);
    value[..byte_count].to_owned()
}

/// Returns the largest character boundary within the byte limit.
fn bounded_char_boundary(value: &str, max_bytes: usize) -> usize {
    let mut byte_count = value.len().min(max_bytes);
    while byte_count != 0 && !value.is_char_boundary(byte_count) {
        byte_count -= 1;
    }
    byte_count
}

/// Splits semantic blocks into independently layout-safe presentation blocks.
fn split_blocks_for_presentation(blocks: Vec<RenderBlock>) -> Result<BlockBuffer, RenderError> {
    let mut presentation_blocks = BlockBuffer::new();
    presentation_blocks.blocks.reserve(blocks.len());
    for block in blocks {
        split_block_for_presentation(block, &mut presentation_blocks)?;
    }
    Ok(presentation_blocks)
}

/// Appends one semantic block as one or more bounded presentation blocks.
fn split_block_for_presentation(
    block: RenderBlock,
    blocks: &mut BlockBuffer,
) -> Result<(), RenderError> {
    if block.text.len() <= MAX_BLOCK_TEXT_BYTES
        && table_presentation_boundary(block.spec.kind, &block.text, block.text.len())
            == block.text.len()
    {
        let mut block = block;
        if block.spec.kind == BLOCK_KIND_TABLE_ROW {
            block.spec.metadata = table_alignment_chunk(&block.spec.metadata, 0, &block.text)?;
        } else if block.spec.flags & BLOCK_FLAG_CONTINUATION != 0 {
            block.spec.metadata.clear();
        }
        return push_presentation_block(block, blocks);
    }
    let RenderBlock {
        spec,
        text,
        utf16_units,
        spans,
        source_range,
        source_maps,
        ..
    } = block;
    let source_range = source_range.ok_or(RenderError::State)?;
    let mut byte_start = 0_usize;
    let mut utf16_start = 0_u32;
    let mut first_pending_span = 0_usize;
    let mut first_pending_source_map = 0_usize;
    while byte_start < text.len() {
        let remaining = &text[byte_start..];
        let byte_count =
            presentation_chunk_boundary(remaining, &spans[first_pending_span..], utf16_start)?;
        let byte_count = table_presentation_boundary(spec.kind, remaining, byte_count);
        if byte_count == 0 {
            return Err(RenderError::State);
        }
        let chunk_text = &remaining[..byte_count];
        let chunk_utf16_units = u32::try_from(chunk_text.encode_utf16().count())
            .map_err(|_| RenderError::ArithmeticOverflow)?;
        let utf16_end = utf16_start
            .checked_add(chunk_utf16_units)
            .ok_or(RenderError::ArithmeticOverflow)?;
        let chunk_spec =
            presentation_block_spec(&spec, byte_start, &text[..byte_start + byte_count])?;
        while first_pending_span < spans.len() && spans[first_pending_span].end_utf16 <= utf16_start
        {
            first_pending_span += 1;
        }
        let chunk_spans = presentation_spans(&spans[first_pending_span..], utf16_start, utf16_end);
        let chunk_span_bytes = encoded_span_bytes(&chunk_spans)?;
        while first_pending_source_map < source_maps.len()
            && source_maps[first_pending_source_map].rendered_end_utf16 <= utf16_start
        {
            first_pending_source_map += 1;
        }
        let chunk_source_maps = presentation_source_maps(
            &source_maps[first_pending_source_map..],
            utf16_start,
            utf16_end,
        );
        let chunk_source_range = presentation_source_range(
            source_range,
            &chunk_source_maps,
            byte_start == 0,
            utf16_end == utf16_units,
        );
        push_presentation_block(
            RenderBlock {
                spec: chunk_spec,
                text: chunk_text.to_owned(),
                utf16_units: chunk_utf16_units,
                spans: chunk_spans,
                span_bytes: chunk_span_bytes,
                source_range: Some(chunk_source_range),
                source_maps: chunk_source_maps,
            },
            blocks,
        )?;
        while first_pending_span < spans.len() && spans[first_pending_span].end_utf16 <= utf16_end {
            first_pending_span += 1;
        }
        while first_pending_source_map < source_maps.len()
            && source_maps[first_pending_source_map].rendered_end_utf16 <= utf16_end
        {
            first_pending_source_map += 1;
        }
        byte_start = byte_start
            .checked_add(byte_count)
            .ok_or(RenderError::ArithmeticOverflow)?;
        utf16_start = utf16_end;
    }
    assert_eq!(byte_start, text.len());
    assert_eq!(utf16_start, utf16_units);
    Ok(())
}

/// Rebases one semantic block specification for a presentation chunk.
fn presentation_block_spec(
    spec: &BlockSpec,
    byte_start: usize,
    text_through_chunk: &str,
) -> Result<BlockSpec, RenderError> {
    let is_continuation = byte_start != 0 || spec.flags & BLOCK_FLAG_CONTINUATION != 0;
    Ok(BlockSpec {
        kind: spec.kind,
        flags: if is_continuation {
            (spec.flags | BLOCK_FLAG_CONTINUATION) & !BLOCK_FLAG_TABLE_START
        } else {
            spec.flags
        },
        heading_level: spec.heading_level,
        quote_depth: spec.quote_depth,
        list_depth: spec.list_depth,
        list_number: spec.list_number,
        metadata: if spec.kind == BLOCK_KIND_TABLE_ROW {
            table_alignment_chunk(&spec.metadata, byte_start, text_through_chunk)?
        } else if is_continuation {
            String::new()
        } else {
            spec.metadata.clone()
        },
    })
}

/// Rebases styled spans intersecting one presentation chunk.
fn presentation_spans(spans: &[InlineSpan], utf16_start: u32, utf16_end: u32) -> Vec<InlineSpan> {
    spans
        .iter()
        .take_while(|span| span.start_utf16 < utf16_end)
        .filter_map(|span| {
            let start = span.start_utf16.max(utf16_start);
            let end = span.end_utf16.min(utf16_end);
            (start < end).then(|| InlineSpan {
                start_utf16: start - utf16_start,
                end_utf16: end - utf16_start,
                // A structural table boundary must never turn half a formula into a
                // different valid formula. Preserve literal source if one is clipped.
                styles: if span.styles & SPAN_STYLE_MATH != 0
                    && (start != span.start_utf16 || end != span.end_utf16)
                {
                    SPAN_STYLE_CODE
                } else {
                    span.styles
                },
                destination: span.destination.clone(),
            })
        })
        .collect()
}

/// Rebases source-map runs intersecting one presentation chunk.
fn presentation_source_maps(
    source_maps: &[InlineSourceMap],
    utf16_start: u32,
    utf16_end: u32,
) -> Vec<InlineSourceMap> {
    source_maps
        .iter()
        .take_while(|source_map| source_map.rendered_start_utf16 < utf16_end)
        .filter_map(|source_map| {
            let start = source_map.rendered_start_utf16.max(utf16_start);
            let end = source_map.rendered_end_utf16.min(utf16_end);
            (start < end).then(|| {
                let rendered_units = source_map
                    .rendered_end_utf16
                    .saturating_sub(source_map.rendered_start_utf16);
                InlineSourceMap {
                    rendered_start_utf16: start - utf16_start,
                    rendered_end_utf16: end - utf16_start,
                    source: source_map.source.slice_for_rendered(
                        rendered_units,
                        start - source_map.rendered_start_utf16,
                        end - source_map.rendered_start_utf16,
                    ),
                }
            })
        })
        .collect()
}

/// Returns one chunk's bounded source range including its outer syntax edges.
fn presentation_source_range(
    block_source: SourceRange,
    source_maps: &[InlineSourceMap],
    includes_block_start: bool,
    includes_block_end: bool,
) -> SourceRange {
    let mapped = source_maps
        .iter()
        .map(|source_map| source_map.source)
        .reduce(SourceRange::union)
        .unwrap_or(block_source);
    SourceRange::new(
        if includes_block_start {
            block_source.start_utf16
        } else {
            mapped.start_utf16
        },
        if includes_block_end {
            block_source.end_utf16
        } else {
            mapped.end_utf16
        },
    )
}

/// Returns the table-alignment suffix beginning at one zero-based cell.
fn table_alignment_suffix(metadata: &str, cell_start: usize) -> Result<String, RenderError> {
    if !metadata.is_ascii() || cell_start > metadata.len() {
        return Err(RenderError::State);
    }
    Ok(metadata[cell_start..].to_owned())
}

/// Keeps a table fragment within its independent per-cell metadata allowance.
fn table_presentation_boundary(kind: u32, text: &str, byte_count: usize) -> usize {
    if kind != BLOCK_KIND_TABLE_ROW {
        return byte_count;
    }
    // A row needs one alignment byte more than its separator count. Split before
    // that final separator rather than truncating or discarding an empty cell.
    text[..byte_count]
        .bytes()
        .enumerate()
        .filter(|(_, byte)| *byte == b'\t')
        .nth(MAX_METADATA_BYTES - 1)
        .map_or(byte_count, |(offset, _)| offset)
}

/// Returns alignment metadata rebased to one bounded table-row chunk.
fn table_alignment_chunk(
    metadata: &str,
    byte_start: usize,
    text_through_chunk: &str,
) -> Result<String, RenderError> {
    if byte_start > text_through_chunk.len() || !text_through_chunk.is_char_boundary(byte_start) {
        return Err(RenderError::State);
    }
    let cell_start = text_through_chunk[..byte_start]
        .bytes()
        .filter(|byte| *byte == b'\t')
        .count();
    let cell_count = text_through_chunk[byte_start..]
        .bytes()
        .filter(|byte| *byte == b'\t')
        .count()
        .checked_add(1)
        .ok_or(RenderError::ArithmeticOverflow)?;
    let cell_end = cell_start
        .checked_add(cell_count)
        .ok_or(RenderError::ArithmeticOverflow)?;
    if !metadata.is_ascii() || cell_end > metadata.len() {
        return Err(RenderError::State);
    }
    Ok(metadata[cell_start..cell_end].to_owned())
}

/// Adds one final block while enforcing aggregate presentation limits.
fn push_presentation_block(
    block: RenderBlock,
    blocks: &mut BlockBuffer,
) -> Result<(), RenderError> {
    if block.source_range.is_none() {
        return Err(RenderError::State);
    }
    blocks.push(block)
}

/// Measures the encoded headers and destinations of one span slice.
fn encoded_span_bytes(spans: &[InlineSpan]) -> Result<usize, RenderError> {
    spans.iter().try_fold(0_usize, |bytes, span| {
        bytes
            .checked_add(SPAN_HEADER_BYTES)
            .and_then(|bytes| {
                bytes.checked_add(
                    span.destination
                        .as_ref()
                        .map_or(0, |destination| destination.value.len()),
                )
            })
            .ok_or(RenderError::ArithmeticOverflow)
    })
}

/// Chooses a bounded UTF-8 boundary without dividing a footnote or math substitution.
fn presentation_chunk_boundary(
    value: &str,
    spans: &[InlineSpan],
    utf16_start: u32,
) -> Result<usize, RenderError> {
    let natural_boundary = natural_presentation_boundary(value);
    if natural_boundary == value.len() || spans.is_empty() {
        return Ok(natural_boundary);
    }
    let natural_units = u32::try_from(value[..natural_boundary].encode_utf16().count())
        .map_err(|_| RenderError::ArithmeticOverflow)?;
    let utf16_end = utf16_start
        .checked_add(natural_units)
        .ok_or(RenderError::ArithmeticOverflow)?;
    let Some(reference) = spans
        .iter()
        .take_while(|span| span.start_utf16 < utf16_end)
        .find(|span| {
            span.end_utf16 > utf16_end
                && (span.styles & SPAN_STYLE_MATH != 0
                    || span.destination.as_ref().is_some_and(|destination| {
                        destination.kind == InlineDestinationKind::FootnoteReference
                    }))
        })
    else {
        return Ok(natural_boundary);
    };
    let mut units = utf16_start;
    let mut reference_start = None;
    for (byte_offset, character) in value.char_indices() {
        if units == reference.start_utf16 {
            reference_start = Some(byte_offset);
        }
        if byte_offset > MAX_BLOCK_TEXT_BYTES {
            return reference_start.ok_or(RenderError::State);
        }
        if units == reference.end_utf16 {
            return Ok(byte_offset);
        }
        units = units
            .checked_add(u32::try_from(character.len_utf16()).expect("scalar width fits u32"))
            .ok_or(RenderError::ArithmeticOverflow)?;
    }
    if units == reference.end_utf16 && value.len() <= MAX_BLOCK_TEXT_BYTES {
        Ok(value.len())
    } else {
        reference_start.ok_or(RenderError::State)
    }
}

/// Chooses a natural UTF-8 boundary within one presentation-block limit.
fn natural_presentation_boundary(value: &str) -> usize {
    let scalar_boundary = bounded_char_boundary(value, MAX_BLOCK_TEXT_BYTES);
    if scalar_boundary == value.len() {
        return scalar_boundary;
    }
    let preferred_start = bounded_char_boundary(value, MAX_BLOCK_TEXT_BYTES / 2);
    let preferred = &value[preferred_start..scalar_boundary];
    if let Some(newline) = preferred.rfind('\n') {
        return preferred_start + newline + 1;
    }
    if let Some((offset, character)) = preferred
        .char_indices()
        .rev()
        .find(|(_, character)| character.is_whitespace())
    {
        return preferred_start + offset + character.len_utf8();
    }
    scalar_boundary
}

/// Encodes the complete render document into the strict binary protocol.
fn encode_document(
    document: &RenderDocument,
    input_bytes: usize,
    input_utf16_units: u64,
    control: &mut impl RenderControl,
) -> Result<RenderPacket, RenderError> {
    let metrics = encoded_document_metrics(document)?;
    let mut packet = Vec::with_capacity(metrics.packet_bytes);
    encode_packet_header(
        &mut packet,
        document,
        input_bytes,
        input_utf16_units,
        metrics,
    )?;
    for block in &document.blocks {
        control.checkpoint()?;
        encode_block(&mut packet, block)?;
    }
    if packet.len() != metrics.packet_bytes {
        return Err(RenderError::State);
    }
    Ok(RenderPacket {
        bytes: packet,
        block_count: metrics.block_count,
        span_count: metrics.span_count,
        source_map_count: metrics.source_map_count,
        contains_raw_html: document.contains_raw_html,
    })
}

/// Calculates exact aggregate sizes for one encoded document.
fn encoded_document_metrics(
    document: &RenderDocument,
) -> Result<EncodedDocumentMetrics, RenderError> {
    let block_count =
        u32::try_from(document.blocks.len()).map_err(|_| RenderError::ArithmeticOverflow)?;
    let span_count =
        u32::try_from(document.span_count).map_err(|_| RenderError::ArithmeticOverflow)?;
    let source_map_count =
        u32::try_from(document.source_map_count).map_err(|_| RenderError::ArithmeticOverflow)?;
    let text_bytes = document.blocks.iter().try_fold(0_u64, |total, block| {
        let length =
            u64::try_from(block.text.len()).map_err(|_| RenderError::ArithmeticOverflow)?;
        total
            .checked_add(length)
            .ok_or(RenderError::ArithmeticOverflow)
    })?;
    let auxiliary_bytes = document.blocks.iter().try_fold(0_u64, |total, block| {
        let metadata = u64::try_from(block.spec.metadata.len())
            .map_err(|_| RenderError::ArithmeticOverflow)?;
        block.spans.iter().try_fold(
            total
                .checked_add(metadata)
                .ok_or(RenderError::ArithmeticOverflow)?,
            |span_total, span| {
                let destination = u64::try_from(
                    span.destination
                        .as_ref()
                        .map_or(0, |destination| destination.value.len()),
                )
                .map_err(|_| RenderError::ArithmeticOverflow)?;
                span_total
                    .checked_add(destination)
                    .ok_or(RenderError::ArithmeticOverflow)
            },
        )
    })?;
    let block_headers = document
        .blocks
        .len()
        .checked_mul(BLOCK_HEADER_BYTES)
        .ok_or(RenderError::ArithmeticOverflow)?;
    let span_headers = document
        .span_count
        .checked_mul(SPAN_HEADER_BYTES)
        .ok_or(RenderError::ArithmeticOverflow)?;
    let source_map_headers = document
        .source_map_count
        .checked_mul(SOURCE_MAP_HEADER_BYTES)
        .ok_or(RenderError::ArithmeticOverflow)?;
    let packet_bytes = PACKET_HEADER_BYTES
        .checked_add(block_headers)
        .and_then(|bytes| bytes.checked_add(span_headers))
        .and_then(|bytes| bytes.checked_add(source_map_headers))
        .and_then(|bytes| bytes.checked_add(usize::try_from(text_bytes).ok()?))
        .and_then(|bytes| bytes.checked_add(usize::try_from(auxiliary_bytes).ok()?))
        .ok_or(RenderError::ArithmeticOverflow)?;
    if packet_bytes > MAX_PACKET_BYTES {
        return Err(RenderError::PacketLimit);
    }
    Ok(EncodedDocumentMetrics {
        block_count,
        span_count,
        source_map_count,
        text_bytes,
        auxiliary_bytes,
        packet_bytes,
    })
}

/// Writes the fixed header for one completely measured render packet.
fn encode_packet_header(
    packet: &mut Vec<u8>,
    document: &RenderDocument,
    input_bytes: usize,
    input_utf16_units: u64,
    metrics: EncodedDocumentMetrics,
) -> Result<(), RenderError> {
    packet.resize(PACKET_HEADER_BYTES, 0);
    packet[MAGIC_OFFSET..VERSION_OFFSET].copy_from_slice(&PACKET_MAGIC);
    encode_u32(packet, VERSION_OFFSET, PACKET_VERSION);
    encode_u32(
        packet,
        HEADER_BYTES_OFFSET,
        u32::try_from(PACKET_HEADER_BYTES).map_err(|_| RenderError::ArithmeticOverflow)?,
    );
    encode_u32(
        packet,
        DOCUMENT_FLAGS_OFFSET,
        u32::from(document.contains_raw_html) * DOCUMENT_FLAG_RAW_HTML,
    );
    encode_u32(packet, HEADER_RESERVED_OFFSET, 0);
    encode_u64(
        packet,
        INPUT_BYTES_OFFSET,
        u64::try_from(input_bytes).map_err(|_| RenderError::ArithmeticOverflow)?,
    );
    encode_u64(
        packet,
        PACKET_BYTES_OFFSET,
        u64::try_from(metrics.packet_bytes).map_err(|_| RenderError::ArithmeticOverflow)?,
    );
    encode_u32(packet, BLOCK_COUNT_OFFSET, metrics.block_count);
    encode_u32(packet, SPAN_COUNT_OFFSET, metrics.span_count);
    encode_u64(packet, TEXT_BYTES_OFFSET, metrics.text_bytes);
    encode_u64(packet, AUXILIARY_BYTES_OFFSET, metrics.auxiliary_bytes);
    encode_u64(packet, INPUT_UTF16_UNITS_OFFSET, input_utf16_units);
    encode_u32(packet, SOURCE_MAP_COUNT_OFFSET, metrics.source_map_count);
    encode_u32(packet, HEADER_TRAILING_RESERVED_OFFSET, 0);
    Ok(())
}

/// Appends one block and all of its inline spans to the packet.
fn encode_block(packet: &mut Vec<u8>, block: &RenderBlock) -> Result<(), RenderError> {
    let source_range = block.source_range.ok_or(RenderError::State)?;
    let header_offset = packet.len();
    packet.resize(
        header_offset
            .checked_add(BLOCK_HEADER_BYTES)
            .ok_or(RenderError::ArithmeticOverflow)?,
        0,
    );
    let header = &mut packet[header_offset..header_offset + BLOCK_HEADER_BYTES];
    encode_u32(header, BLOCK_KIND_OFFSET, block.spec.kind);
    encode_u32(header, BLOCK_FLAGS_OFFSET, block.spec.flags);
    encode_u32(header, BLOCK_HEADING_LEVEL_OFFSET, block.spec.heading_level);
    encode_u32(header, BLOCK_QUOTE_DEPTH_OFFSET, block.spec.quote_depth);
    encode_u32(header, BLOCK_LIST_DEPTH_OFFSET, block.spec.list_depth);
    encode_u32(header, BLOCK_RESERVED_OFFSET, 0);
    encode_u64(header, BLOCK_LIST_NUMBER_OFFSET, block.spec.list_number);
    encode_u32(
        header,
        BLOCK_TEXT_BYTES_OFFSET,
        u32::try_from(block.text.len()).map_err(|_| RenderError::ArithmeticOverflow)?,
    );
    encode_u32(
        header,
        BLOCK_METADATA_BYTES_OFFSET,
        u32::try_from(block.spec.metadata.len()).map_err(|_| RenderError::ArithmeticOverflow)?,
    );
    encode_u32(
        header,
        BLOCK_SPAN_COUNT_OFFSET,
        u32::try_from(block.spans.len()).map_err(|_| RenderError::ArithmeticOverflow)?,
    );
    encode_u32(header, BLOCK_TRAILING_RESERVED_OFFSET, 0);
    encode_u64(
        header,
        BLOCK_SOURCE_START_UTF16_OFFSET,
        source_range.start_utf16,
    );
    encode_u64(
        header,
        BLOCK_SOURCE_END_UTF16_OFFSET,
        source_range.end_utf16,
    );
    encode_u32(
        header,
        BLOCK_SOURCE_MAP_COUNT_OFFSET,
        u32::try_from(block.source_maps.len()).map_err(|_| RenderError::ArithmeticOverflow)?,
    );
    encode_u32(header, BLOCK_SOURCE_RESERVED_OFFSET, 0);
    packet.extend_from_slice(block.text.as_bytes());
    packet.extend_from_slice(block.spec.metadata.as_bytes());
    for span in &block.spans {
        encode_span(packet, span)?;
    }
    for source_map in &block.source_maps {
        encode_source_map(packet, source_map)?;
    }
    Ok(())
}

/// Appends one inline span to the packet.
fn encode_span(packet: &mut Vec<u8>, span: &InlineSpan) -> Result<(), RenderError> {
    let destination = span
        .destination
        .as_ref()
        .map_or("", |destination| destination.value.as_str());
    let header_offset = packet.len();
    packet.resize(
        header_offset
            .checked_add(SPAN_HEADER_BYTES)
            .ok_or(RenderError::ArithmeticOverflow)?,
        0,
    );
    let header = &mut packet[header_offset..header_offset + SPAN_HEADER_BYTES];
    encode_u32(header, SPAN_START_UTF16_OFFSET, span.start_utf16);
    encode_u32(header, SPAN_END_UTF16_OFFSET, span.end_utf16);
    encode_u32(header, SPAN_STYLES_OFFSET, span.styles);
    let flags = span
        .destination
        .as_ref()
        .map_or(0, |destination| match destination.kind {
            InlineDestinationKind::Link => SPAN_FLAG_LINK,
            InlineDestinationKind::FootnoteReference => SPAN_FLAG_FOOTNOTE_REFERENCE,
        });
    encode_u32(header, SPAN_FLAGS_OFFSET, flags);
    encode_u32(
        header,
        SPAN_DESTINATION_BYTES_OFFSET,
        u32::try_from(destination.len()).map_err(|_| RenderError::ArithmeticOverflow)?,
    );
    encode_u32(header, SPAN_RESERVED_OFFSET, 0);
    packet.extend_from_slice(destination.as_bytes());
    Ok(())
}

/// Appends one rendered-to-source UTF-16 range to the packet.
fn encode_source_map(
    packet: &mut Vec<u8>,
    source_map: &InlineSourceMap,
) -> Result<(), RenderError> {
    let header_offset = packet.len();
    packet.resize(
        header_offset
            .checked_add(SOURCE_MAP_HEADER_BYTES)
            .ok_or(RenderError::ArithmeticOverflow)?,
        0,
    );
    let header = &mut packet[header_offset..header_offset + SOURCE_MAP_HEADER_BYTES];
    encode_u32(
        header,
        SOURCE_MAP_RENDERED_START_UTF16_OFFSET,
        source_map.rendered_start_utf16,
    );
    encode_u32(
        header,
        SOURCE_MAP_RENDERED_END_UTF16_OFFSET,
        source_map.rendered_end_utf16,
    );
    encode_u64(
        header,
        SOURCE_MAP_SOURCE_START_UTF16_OFFSET,
        source_map.source.start_utf16,
    );
    encode_u64(
        header,
        SOURCE_MAP_SOURCE_END_UTF16_OFFSET,
        source_map.source.end_utf16,
    );
    Ok(())
}

/// Encodes one little-endian 32-bit integer at the given offset.
fn encode_u32(bytes: &mut [u8], offset: usize, value: u32) {
    bytes[offset..offset + size_of::<u32>()].copy_from_slice(&value.to_le_bytes());
}

/// Encodes one little-endian 64-bit integer at the given offset.
fn encode_u64(bytes: &mut [u8], offset: usize, value: u64) {
    bytes[offset..offset + size_of::<u64>()].copy_from_slice(&value.to_le_bytes());
}

#[cfg(test)]
mod tests {
    //! Verifies bounded Markdown parsing and deterministic packet encoding.

    use super::{
        BLOCK_FLAG_CONTINUATION, BLOCK_FLAG_ORDERED_LIST, BLOCK_FLAG_QUOTE_ALERT_START,
        BLOCK_FLAG_QUOTE_NOTE, BLOCK_FLAG_RAW_HTML, BLOCK_FLAG_TABLE_HEADER,
        BLOCK_FLAG_TASK_CHECKED, BLOCK_KIND_CODE, BLOCK_KIND_FOOTNOTE, BLOCK_KIND_HEADING,
        BLOCK_KIND_HTML_LITERAL, BLOCK_KIND_LIST_ITEM, BLOCK_KIND_PARAGRAPH, BLOCK_KIND_RULE,
        BLOCK_KIND_TABLE_ROW, DOCUMENT_FLAG_RAW_HTML, InlineSourceMap, MAX_BLOCK_TEXT_BYTES,
        MAX_INPUT_BYTES, MAX_LINK_DESTINATION_BYTES, PACKET_HEADER_BYTES, PACKET_MAGIC,
        RenderControl, RenderError, RenderInterruption, SPAN_FLAG_FOOTNOTE_REFERENCE,
        SPAN_STYLE_CODE, SPAN_STYLE_EMPHASIS, SPAN_STYLE_FOOTNOTE_REFERENCE, SPAN_STYLE_STRONG,
        SPAN_STYLE_SUBSCRIPT, SPAN_STYLE_SUPERSCRIPT, SourceRange, render_markdown,
        render_markdown_with_control,
    };

    const DOCUMENT_FLAGS_OFFSET: usize = 16;
    const BLOCK_COUNT_OFFSET: usize = 40;

    #[test]
    fn preserves_math_source_and_surrounding_link_style() {
        let packet =
            render_markdown("[**$\\frac{1}{2}$**](https://example.org)\n\n$$\nx^2\n$$").unwrap();
        let blocks = decode_blocks(packet.as_bytes());
        assert_eq!(blocks.len(), 2);
        assert_eq!(blocks[0].text, "\\frac{1}{2}");
        assert_eq!(
            blocks[0].styles,
            [super::SPAN_STYLE_MATH | SPAN_STYLE_STRONG]
        );
        assert_eq!(blocks[0].destinations, ["https://example.org"]);
        assert_eq!(blocks[0].source_maps[0].source.start_utf16, 4);
        assert_eq!(blocks[1].text, "\nx^2\n");
        assert_eq!(
            blocks[1].styles,
            [super::SPAN_STYLE_MATH | super::SPAN_STYLE_DISPLAY_MATH]
        );
    }

    #[test]
    fn display_math_maps_exact_tex_inside_its_delimiters() {
        let source = "😀\n\n$$\nx^2\n$$";
        let blocks = decode_blocks(render_markdown(source).unwrap().as_bytes());
        let math = &blocks[1];
        assert_eq!(math.text, "\nx^2\n");
        assert_eq!(math.source_maps[0].source, SourceRange::new(6, 11));
        assert_eq!(math.source, SourceRange::new(4, 13));
    }

    #[test]
    fn bracket_math_preserves_tex_styles_and_utf16_provenance() {
        let source = "😀 [**\\(\\frac{a_1}{b^2}\\)**](https://example.org)\n\n\\[\nx^2\n\\]";
        let blocks = decode_blocks(render_markdown(source).unwrap().as_bytes());
        assert_eq!(blocks[0].text, "😀 \\frac{a_1}{b^2}");
        assert!(
            blocks[0]
                .styles
                .contains(&(super::SPAN_STYLE_MATH | SPAN_STYLE_STRONG))
        );
        assert!(
            blocks[0]
                .destinations
                .contains(&"https://example.org".into())
        );
        assert!(
            blocks[0]
                .source_maps
                .iter()
                .any(|mapping| mapping.source.start_utf16 == 8)
        );
        assert_eq!(blocks[1].text, "\nx^2\n");
        assert_eq!(
            blocks[1].styles,
            [super::SPAN_STYLE_MATH | super::SPAN_STYLE_DISPLAY_MATH]
        );
    }

    #[test]
    fn bracket_math_never_reinterprets_code_destinations_or_escaped_openers() {
        for source in [
            "`\\(x\\)`",
            "```tex\n\\[x\\]\n```",
            "    \\(x\\)",
            "[link](https://example.org/\\(x\\))",
            "![\\(x\\)](image.png)",
            "\\\\(x\\\\)",
            "<code>\\(x\\)</code>",
            "<a title=\"\\(x\\)\">label</a>",
            "\\(unclosed",
            "\\(first\nsecond\\)",
        ] {
            let blocks = decode_blocks(render_markdown(source).unwrap().as_bytes());
            assert!(
                blocks.iter().all(|block| block
                    .styles
                    .iter()
                    .all(|style| style & super::SPAN_STYLE_MATH == 0)),
                "{source:?}"
            );
        }
    }

    #[test]
    fn bracket_math_handles_adjacency_quotes_lists_and_tables() {
        for source in [
            "\\(x\\)\\(y\\)",
            "> \\(x\\) and \\(y\\)",
            "- \\(x\\) and \\(y\\)",
            "| A | B |\n| - | - |\n| \\(x\\) | \\(y\\) |",
        ] {
            let blocks = decode_blocks(render_markdown(source).unwrap().as_bytes());
            let count = blocks
                .iter()
                .flat_map(|block| &block.styles)
                .filter(|&&style| style & super::SPAN_STYLE_MATH != 0)
                .count();
            assert_eq!(count, 2, "{source:?}: {blocks:?}");
        }
    }

    #[test]
    fn currency_and_escaped_dollars_remain_ordinary_text() {
        let source = "Prices: $5 to $10; escaped \\$20. Plain $ symbol.";
        let blocks = decode_blocks(render_markdown(source).unwrap().as_bytes());
        assert_eq!(
            blocks.concat_text(),
            "Prices: $5 to $10; escaped $20. Plain $ symbol."
        );
        assert!(blocks.iter().all(|block| {
            block
                .styles
                .iter()
                .all(|style| style & super::SPAN_STYLE_MATH == 0)
        }));
    }

    #[test]
    fn keeps_math_atomic_at_both_fragmentation_boundaries() {
        for boundary in [MAX_BLOCK_TEXT_BYTES, super::MAX_BUILDER_BLOCK_TEXT_BYTES] {
            let formula = "\\frac{1}{\\sqrt{x^2+y^2}}";
            let source = format!("{} ${formula}$ tail", "x".repeat(boundary - 6));
            let blocks = decode_blocks(render_markdown(&source).unwrap().as_bytes());
            let formulas: Vec<_> = blocks
                .iter()
                .filter(|block| block.styles.contains(&super::SPAN_STYLE_MATH))
                .collect();
            assert_eq!(formulas.len(), 1);
            assert!(formulas[0].text.contains(formula));
            assert!(
                blocks
                    .iter()
                    .all(|block| block.text.len() <= MAX_BLOCK_TEXT_BYTES)
            );
        }
    }

    #[test]
    fn oversized_and_outer_script_math_stays_literal() {
        for source in [
            format!("${}$", "x".repeat(MAX_BLOCK_TEXT_BYTES + 1)),
            "<sub>$x^2$</sub>".into(),
        ] {
            let blocks = decode_blocks(render_markdown(&source).unwrap().as_bytes());
            assert!(blocks.iter().all(|block| {
                block
                    .styles
                    .iter()
                    .all(|style| style & super::SPAN_STYLE_MATH == 0)
            }));
            assert!(
                blocks
                    .iter()
                    .flat_map(|block| &block.styles)
                    .any(|style| style & SPAN_STYLE_CODE != 0)
            );
        }
    }

    #[test]
    fn renders_commonmark_and_gfm_blocks_without_html_execution() {
        let markdown = concat!(
            "# Heading *one*\n\n",
            "1. **Bold** and [link](https://example.com)\n",
            "2. [x] complete\n\n",
            "> quoted\n\n",
            "```rust\nlet value = 1;\n```\n\n",
            "| A | B |\n| - | - |\n| 1 | 2 |\n\n",
            "---\n\n",
            "<script>alert('no')</script>\n",
        );
        let packet = render_markdown(markdown).expect("bounded Markdown should render");

        assert!(packet.contains_raw_html());
        assert!(packet.block_count() >= 9);
        assert!(packet.span_count() >= 3);
        assert_eq!(&packet.as_bytes()[..PACKET_MAGIC.len()], &PACKET_MAGIC);
        assert_eq!(
            decode_u32(packet.as_bytes(), DOCUMENT_FLAGS_OFFSET),
            DOCUMENT_FLAG_RAW_HTML
        );

        let decoded = decode_blocks(packet.as_bytes());
        assert_eq!(decoded[0].kind, BLOCK_KIND_HEADING);
        assert_eq!(decoded[0].heading_level, 1);
        assert!(decoded[0].styles.contains(&SPAN_STYLE_EMPHASIS));
        assert_eq!(decoded[1].kind, BLOCK_KIND_LIST_ITEM);
        assert_ne!(decoded[1].flags & BLOCK_FLAG_ORDERED_LIST, 0);
        assert!(decoded[1].styles.contains(&SPAN_STYLE_STRONG));
        assert_eq!(decoded[2].kind, BLOCK_KIND_LIST_ITEM);
        assert_ne!(decoded[2].flags & BLOCK_FLAG_TASK_CHECKED, 0);
        assert_eq!(decoded[2].text, "☑ complete");
        assert!(decoded.iter().any(|block| block.kind == BLOCK_KIND_CODE));
        assert!(decoded.iter().any(|block| {
            block.kind == BLOCK_KIND_TABLE_ROW && block.flags & BLOCK_FLAG_TABLE_HEADER != 0
        }));
        assert!(decoded.iter().any(|block| block.kind == BLOCK_KIND_RULE));
        let html = decoded
            .iter()
            .find(|block| block.kind == BLOCK_KIND_HTML_LITERAL)
            .expect("raw HTML should become a literal block");
        assert_ne!(html.flags & BLOCK_FLAG_RAW_HTML, 0);
        assert!(html.text.contains("<script>"));
        assert!(html.styles.contains(&SPAN_STYLE_CODE));
    }

    #[test]
    fn preserves_extended_markdown_semantics() {
        let markdown = concat!(
            "Subscript ~2~ and superscript ^2^.\n\n",
            "| Left | Center | Right |\n",
            "| :--- | :----: | ----: |\n",
            "| one | two | three |\n\n",
            "> [!NOTE]\n",
            "> First paragraph.\n",
            ">\n",
            "> Second paragraph.\n",
        );
        let packet = render_markdown(markdown).expect("extended Markdown should render");
        let decoded = decode_blocks(packet.as_bytes());

        assert!(decoded[0].styles.contains(&SPAN_STYLE_SUBSCRIPT));
        assert!(decoded[0].styles.contains(&SPAN_STYLE_SUPERSCRIPT));
        let table_rows: Vec<_> = decoded
            .iter()
            .filter(|block| block.kind == BLOCK_KIND_TABLE_ROW)
            .collect();
        assert_eq!(table_rows.len(), 2);
        assert!(table_rows.iter().all(|block| block.metadata == "lcr"));
        let note_blocks: Vec<_> = decoded
            .iter()
            .filter(|block| block.flags & BLOCK_FLAG_QUOTE_NOTE != 0)
            .collect();
        assert_eq!(note_blocks.len(), 2);
        assert_ne!(note_blocks[0].flags & BLOCK_FLAG_QUOTE_ALERT_START, 0);
        assert_eq!(note_blocks[1].flags & BLOCK_FLAG_QUOTE_ALERT_START, 0);
    }

    /// Keeps cached span sizes exact through merging and style changes.
    #[test]
    fn measures_merged_and_distinct_spans_exactly() {
        let mut builder = super::DocumentBuilder::new();
        let source = SourceRange::new(0, 1);
        builder
            .start_text_block(BLOCK_KIND_PARAGRAPH, 0, source)
            .unwrap();
        for (styles, destination) in [
            (0, None),
            (SPAN_STYLE_STRONG, None),
            (SPAN_STYLE_STRONG, None),
            (
                0,
                Some((super::InlineDestinationKind::Link, "https://example.org")),
            ),
            (
                0,
                Some((super::InlineDestinationKind::Link, "https://example.org")),
            ),
            (
                SPAN_STYLE_EMPHASIS,
                Some((super::InlineDestinationKind::Link, "https://example.org")),
            ),
            (0, None),
        ] {
            builder.append("x", styles, destination, source).unwrap();
            let block = builder.current.as_ref().unwrap();
            assert_eq!(
                block.span_bytes,
                super::encoded_span_bytes(&block.spans).unwrap()
            );
        }
    }

    /// Rejects destination amplification before completing one oversized block.
    #[test]
    fn bounds_destinations_during_block_construction() {
        let destination = "x".repeat(MAX_LINK_DESTINATION_BYTES);
        let mut builder = super::DocumentBuilder::new();
        let source = SourceRange::new(0, 1);
        builder
            .start_text_block(BLOCK_KIND_PARAGRAPH, 0, source)
            .unwrap();
        let attempts = super::MAX_PACKET_BYTES / MAX_LINK_DESTINATION_BYTES + 1;
        let result = (0..attempts).try_for_each(|span_index| {
            builder.append(
                "x",
                if span_index % 2 == 0 {
                    SPAN_STYLE_STRONG
                } else {
                    0
                },
                Some((super::InlineDestinationKind::Link, &destination)),
                source,
            )
        });

        assert_eq!(result, Err(RenderError::PacketLimit));
    }

    /// Bounds cumulative model bytes before final presentation splitting.
    #[test]
    fn bounds_destinations_across_completed_blocks() {
        let destination = "x".repeat(MAX_LINK_DESTINATION_BYTES);
        let mut builder = super::DocumentBuilder::new();
        let source = SourceRange::new(0, 1);
        let attempts = super::MAX_PACKET_BYTES / MAX_LINK_DESTINATION_BYTES + 1;
        let result = (0..attempts).try_for_each(|_| {
            builder.start_text_block(BLOCK_KIND_PARAGRAPH, 0, source)?;
            builder.append(
                "x",
                0,
                Some((super::InlineDestinationKind::Link, &destination)),
                source,
            )?;
            builder.finish_block()
        });

        assert_eq!(result, Err(RenderError::PacketLimit));
    }

    /// Bounds converted HTML before returning its aggregate block list.
    #[test]
    fn bounds_destinations_across_html_blocks() {
        let destination = "x".repeat(MAX_LINK_DESTINATION_BYTES);
        let contents = "<b>x</b>y".repeat(1_100);
        let paragraph = format!("<p><a href=\"{destination}\">{contents}</a></p>");
        let source = format!("<div>{}</div>", paragraph.repeat(4));
        assert!(source.len() < super::MAX_BUILDER_BLOCK_TEXT_BYTES);

        assert!(matches!(
            super::html::render_safe_html_blocks(&source, 0),
            Err(RenderError::PacketLimit),
        ));
    }

    /// Marks separate Markdown tables independently even without an intervening paragraph.
    #[test]
    fn preserves_adjacent_markdown_table_boundaries() {
        let markdown = "| A | B |\n| - | - |\n| 1 | 2 |\n\n| C |\n| - |\n| 3 |\n";
        let packet = render_markdown(markdown).expect("tables should render");
        let blocks = decode_blocks(packet.as_bytes());
        assert_eq!(blocks.len(), 4);
        assert_eq!(
            blocks
                .iter()
                .map(|block| block.flags & super::BLOCK_FLAG_TABLE_START != 0)
                .collect::<Vec<_>>(),
            [true, false, true, false],
        );
    }

    /// Distinguishes headerless HTML tables and keeps multiple header rows in one table.
    #[test]
    fn preserves_adjacent_html_table_boundaries() {
        let markdown = concat!(
            "<div><table><thead><tr><th>A</th></tr><tr><th>B</th></tr></thead>",
            "<tbody><tr><td>1</td></tr></tbody></table>",
            "<table><tr><td>2</td></tr><tr><td>3</td></tr></table></div>",
        );
        let packet = render_markdown(markdown).expect("HTML tables should render");
        let blocks = decode_blocks(packet.as_bytes());
        assert_eq!(blocks.len(), 5);
        assert_eq!(
            blocks
                .iter()
                .map(|block| block.flags & super::BLOCK_FLAG_TABLE_START != 0)
                .collect::<Vec<_>>(),
            [true, false, false, true, false],
        );
        assert!(
            blocks[..2]
                .iter()
                .all(|block| block.flags & BLOCK_FLAG_TABLE_HEADER != 0)
        );
    }

    /// Keeps alignment metadata bounded even when every table cell is empty.
    #[test]
    fn bounds_empty_table_row_alignment_chunks() {
        let cells = super::MAX_METADATA_BYTES + 1;
        for markdown in [
            format!(
                "|{}\n|{}\n|{}\n",
                " |".repeat(cells),
                " - |".repeat(cells),
                " |".repeat(cells)
            ),
            format!("<table><tr>{}</tr></table>", "<td></td>".repeat(cells)),
        ] {
            let packet = render_markdown(&markdown).expect("wide empty table should render");
            let blocks = decode_blocks(packet.as_bytes());
            assert!(!blocks.is_empty());
            assert!(
                blocks
                    .iter()
                    .all(|block| block.metadata.len() <= super::MAX_METADATA_BYTES)
            );
            assert!(blocks.iter().all(|block| block.metadata.len()
                == block.text.bytes().filter(|byte| *byte == b'\t').count() + 1));
        }
    }

    /// Marks only the first chunk when a long Markdown or HTML table row is split.
    #[test]
    fn preserves_table_starts_across_row_chunking() {
        let cell = "a".repeat(MAX_BLOCK_TEXT_BYTES * 3);
        for markdown in [
            format!("| {cell} |\n| - |\n| row |\n"),
            format!("<table><tr><td>{cell}</td></tr><tr><td>row</td></tr></table>"),
        ] {
            let packet = render_markdown(&markdown).expect("long table rows should render");
            let blocks = decode_blocks(packet.as_bytes());
            assert!(blocks.len() > 3);
            assert_ne!(blocks[0].flags & super::BLOCK_FLAG_TABLE_START, 0);
            assert!(
                blocks[1..]
                    .iter()
                    .all(|block| block.flags & super::BLOCK_FLAG_TABLE_START == 0)
            );
            assert_ne!(blocks[1].flags & BLOCK_FLAG_CONTINUATION, 0);
        }
    }

    #[test]
    fn links_extended_autolinks_and_footnote_references() {
        let markdown = concat!(
            "Visit www.example.com/help or email reader@example.com.[^note]\n\n",
            "[^note]: Local definition.\n",
        );
        let packet = render_markdown(markdown).expect("links should render");
        let decoded = decode_blocks(packet.as_bytes());
        let paragraph = &decoded[0];

        assert_eq!(
            paragraph.destinations.as_slice(),
            [
                "http://www.example.com/help",
                "mailto:reader@example.com",
                "note"
            ]
        );
        assert!(
            paragraph
                .span_flags
                .iter()
                .any(|flags| flags & SPAN_FLAG_FOOTNOTE_REFERENCE != 0)
        );
        assert!(
            paragraph
                .styles
                .iter()
                .any(|styles| styles & SPAN_STYLE_FOOTNOTE_REFERENCE != 0)
        );
    }

    /// Preserves adjacent references as distinct semantic substitutions.
    #[test]
    fn preserves_adjacent_footnote_references() {
        let packet = render_markdown("First[^note][^note]\n\n[^note]: A definition.")
            .expect("adjacent references should render");
        let blocks = decode_blocks(packet.as_bytes());
        assert_eq!(blocks[0].destinations, ["note", "note"]);
    }

    /// Keeps a footnote reference whole across presentation and builder boundaries.
    #[test]
    fn keeps_footnote_references_whole() {
        for boundary in [MAX_BLOCK_TEXT_BYTES, super::MAX_BUILDER_BLOCK_TEXT_BYTES] {
            for label in ["note", "two words", "é🌻"] {
                let marker = format!("[^{label}]");
                for overlap in 1..marker.len() {
                    let prefix_bytes = boundary - overlap;
                    let emoji = "😀";
                    let prefix = format!(
                        "{}{}",
                        emoji.repeat(prefix_bytes / emoji.len()),
                        "a".repeat(prefix_bytes % emoji.len()),
                    );
                    let markdown = format!("{prefix}{marker} after\n\n{marker}: A definition.");
                    let packet = render_markdown(&markdown).expect("long paragraph should render");
                    let blocks = decode_blocks(packet.as_bytes());
                    let count = blocks
                        .iter()
                        .flat_map(|block| &block.span_flags)
                        .filter(|flags| **flags & SPAN_FLAG_FOOTNOTE_REFERENCE != 0)
                        .count();
                    assert_eq!(
                        count, 1,
                        "label {label}, boundary {boundary}, overlap {overlap}"
                    );
                    assert!(
                        blocks
                            .iter()
                            .all(|block| block.text.len() <= MAX_BLOCK_TEXT_BYTES)
                    );
                    assert_eq!(
                        blocks.concat_text(),
                        format!("{prefix}{marker} afterA definition."),
                    );
                }
            }
        }
    }

    /// Fits a maximum-size substitution within one bounded presentation block.
    #[test]
    fn preserves_maximum_footnote_marker() {
        let label_bytes =
            MAX_BLOCK_TEXT_BYTES - super::FOOTNOTE_PREFIX.len() - super::FOOTNOTE_SUFFIX.len();
        for label in [
            "n".repeat(label_bytes),
            format!(
                "{} {}",
                "a".repeat(label_bytes / 2),
                "b".repeat(label_bytes / 2)
            ),
        ] {
            let marker = format!("[^{label}]");
            assert_eq!(marker.len(), MAX_BLOCK_TEXT_BYTES);
            let markdown = format!(
                "😀{marker}{}\n\n{marker}: Definition.",
                "c".repeat(MAX_BLOCK_TEXT_BYTES)
            );
            let packet = render_markdown(&markdown).expect("maximum footnote should render");
            let blocks = decode_blocks(packet.as_bytes());
            let references: Vec<_> = blocks
                .iter()
                .flat_map(|block| &block.destinations)
                .collect();
            assert_eq!(references, [&label]);
            assert!(
                blocks
                    .iter()
                    .all(|block| block.text.len() <= MAX_BLOCK_TEXT_BYTES)
            );
        }
    }

    /// Preserves complete destinations and visible text at the autolink byte ceiling.
    #[test]
    fn renders_bare_domains_without_linking_truncated_addresses() {
        let oversized_address = format!(
            "https://example.org/{}",
            "a".repeat(super::MAX_LINK_DESTINATION_BYTES),
        );
        let markdown = format!(
            "https://example.org www.example.org\n\n{oversized_address}\n\nhttps://example.net",
        );
        let packet = render_markdown(&markdown).expect("addresses should render");
        let blocks = decode_blocks(packet.as_bytes());
        let destinations: Vec<_> = blocks
            .iter()
            .flat_map(|block| block.destinations.iter().map(String::as_str))
            .collect();
        assert_eq!(
            destinations,
            [
                "https://example.org",
                "http://www.example.org",
                "https://example.net",
            ]
        );
        let text: String = blocks.iter().map(|block| block.text.as_str()).collect();
        assert!(text.contains(&oversized_address));
    }

    #[test]
    fn renders_attribute_free_semantic_html_into_safe_blocks_and_spans() {
        let markdown = concat!(
            "<p>Plain <strong>bold</strong> &amp; <em>emphasized</em>.<br>Next</p>\n",
            "<h2>HTML heading</h2>\n\n",
            "<blockquote>Quoted <code>code</code>, x<sup>2</sup>, H<sub>2</sub>O, and <del>gone</del>.</blockquote>\n",
            "<hr>\n\n",
            "Markdown with <span>inline HTML</span>.\n",
        );
        let packet = render_markdown(markdown).expect("safe semantic HTML should render");
        let decoded = decode_blocks(packet.as_bytes());

        assert!(!packet.contains_raw_html());
        assert_eq!(decoded[0].kind, super::BLOCK_KIND_PARAGRAPH);
        assert_eq!(decoded[0].text, "Plain bold & emphasized.\nNext");
        assert!(decoded[0].styles.contains(&SPAN_STYLE_STRONG));
        assert!(decoded[0].styles.contains(&super::SPAN_STYLE_EMPHASIS));
        assert_eq!(decoded[1].kind, BLOCK_KIND_HEADING);
        assert_eq!(decoded[1].heading_level, 2);
        assert_eq!(decoded[1].text, "HTML heading");
        assert_eq!(decoded[2].quote_depth, 1);
        assert_eq!(decoded[2].text, "Quoted code, x2, H2O, and gone.");
        assert!(decoded[2].styles.contains(&SPAN_STYLE_CODE));
        assert!(decoded[2].styles.contains(&super::SPAN_STYLE_SUPERSCRIPT));
        assert!(decoded[2].styles.contains(&super::SPAN_STYLE_SUBSCRIPT));
        assert!(decoded[2].styles.contains(&super::SPAN_STYLE_STRIKETHROUGH));
        assert_eq!(decoded[3].kind, BLOCK_KIND_RULE);
        assert_eq!(decoded[4].text, "Markdown with inline HTML.");
        assert_eq!(decoded[4].flags & BLOCK_FLAG_RAW_HTML, 0);
    }

    /// Trims collapsed indentation after explicit HTML line and table-cell boundaries.
    #[test]
    fn collapses_html_whitespace_at_line_and_cell_starts() {
        let markdown = concat!(
            "<p> alpha<br>   beta <strong>gamma </strong> delta </p>\n",
            "<table><tr><td>  alpha  </td><td> <strong> beta </strong>  gamma </td></tr></table>",
        );
        let packet = render_markdown(markdown).expect("indented HTML should render");
        let blocks = decode_blocks(packet.as_bytes());

        assert!(!packet.contains_raw_html());
        assert_eq!(blocks[0].text, "alpha\nbeta gamma delta");
        assert_eq!(blocks[1].text, "alpha\tbeta gamma");
    }

    #[test]
    fn renders_structural_safe_html_without_fetching_embedded_sources() {
        let markdown = concat!(
            "<div>\n",
            "<h2>Reference</h2>\n",
            "<ol start=\"3\">\n",
            "<li>First <a href=\"https://example.com/help\">link</a> and ",
            "<img src=\"https://example.com/private.png\" alt=\"diagram\"></li>\n",
            "<li><p>Second</p><ul><li>Nested</li></ul></li>\n",
            "</ol>\n",
            "<pre><code>let value = 2;\n</code></pre>\n",
            "<table><thead><tr><th>Name</th><th>Value</th></tr></thead>",
            "<tbody><tr><td>alpha</td><td><strong>2</strong></td></tr></tbody></table>\n",
            "</div>\n",
        );
        let packet = render_markdown(markdown).expect("structural safe HTML should render");
        let decoded = decode_blocks(packet.as_bytes());

        assert!(!packet.contains_raw_html());
        assert!(decoded.iter().any(|block| {
            block.kind == BLOCK_KIND_HEADING
                && block.heading_level == 2
                && block.text == "Reference"
        }));
        let list_items: Vec<_> = decoded
            .iter()
            .filter(|block| block.kind == BLOCK_KIND_LIST_ITEM)
            .collect();
        assert_eq!(list_items[0].list_number, 3);
        assert_eq!(list_items[0].text, "First link and Image: diagram");
        assert_eq!(list_items[0].destinations, ["https://example.com/help"]);
        assert_eq!(list_items[1].list_number, 4);
        assert_eq!(list_items[1].text, "Second");
        assert_eq!(list_items[2].list_depth, 2);
        assert_eq!(list_items[2].text, "Nested");
        assert!(
            decoded
                .iter()
                .any(|block| { block.kind == BLOCK_KIND_CODE && block.text == "let value = 2;\n" })
        );
        let table_rows: Vec<_> = decoded
            .iter()
            .filter(|block| block.kind == BLOCK_KIND_TABLE_ROW)
            .collect();
        assert_eq!(table_rows.len(), 2);
        assert!(table_rows.iter().all(|block| block.metadata == "nn"));
        assert_ne!(table_rows[0].flags & BLOCK_FLAG_TABLE_HEADER, 0);
        assert!(table_rows[1].styles.contains(&SPAN_STYLE_STRONG));
        assert!(
            decoded
                .iter()
                .all(|block| !block.text.contains("private.png"))
        );
    }

    #[test]
    fn renders_safe_inline_html_links_and_image_alternatives() {
        let markdown = concat!(
            "Paragraph with <a href=\"https://example.com?a=1&amp;b=2\">safe</a> and ",
            "<img src=\"https://example.com/private.png\" alt=\"diagram\">.\n",
        );
        let packet = render_markdown(markdown).expect("safe inline HTML should render");
        let decoded = decode_blocks(packet.as_bytes());

        assert!(!packet.contains_raw_html());
        assert_eq!(decoded[0].text, "Paragraph with safe and Image: diagram.");
        assert_eq!(decoded[0].destinations, ["https://example.com?a=1&b=2"]);
        assert!(!decoded[0].text.contains("private.png"));
    }

    #[test]
    fn preserves_html_with_attributes_as_inert_source() {
        let markdown = concat!(
            "<p style=\"color: red\">Styled paragraph</p>\n\n",
            "Text with <span class=\"accent\">styled content</span>.\n",
        );
        let packet = render_markdown(markdown).expect("unsupported HTML should remain inert");
        let decoded = decode_blocks(packet.as_bytes());

        assert!(packet.contains_raw_html());
        assert!(decoded.iter().any(|block| {
            block.kind == BLOCK_KIND_HTML_LITERAL && block.text.contains("style=\"color: red\"")
        }));
        assert!(decoded.iter().any(|block| {
            block.flags & BLOCK_FLAG_RAW_HTML != 0
                && block.text.contains("<span class=\"accent\">")
                && block.text.contains("</span>")
        }));
    }

    #[test]
    fn preserves_namespaced_html_attributes_as_inert_source() {
        let markdown = "Paragraph with <a xlink:href=\"https://example.com\">namespaced</a>.\n";
        let packet = render_markdown(markdown).expect("namespaced HTML should remain inert");
        let decoded = decode_blocks(packet.as_bytes());

        assert!(packet.contains_raw_html());
        assert_eq!(decoded.len(), 1);
        assert!(decoded[0].text.contains("xlink:href"));
        assert!(decoded[0].destinations.is_empty());
    }

    /// Preserves unsupported leading tags when a tight list omits paragraph events.
    #[test]
    fn preserves_leading_literal_html_in_tight_list_items() {
        for marker in ["- ", "1. ", "> - "] {
            for contents in [
                "<span class=\"accent\">styled content</span>",
                "<strong>broken</em>",
                "<a xlink:href=\"https://example.com\">namespaced</a>",
            ] {
                let markdown = format!("{marker}{contents}\n");
                let packet = render_markdown(&markdown)
                    .expect("unsupported inline HTML should not break the list preview");
                let blocks = decode_blocks(packet.as_bytes());

                assert!(packet.contains_raw_html());
                assert_eq!(blocks.len(), 1);
                assert_eq!(blocks[0].kind, BLOCK_KIND_LIST_ITEM);
                assert_eq!(blocks[0].text, contents);
                assert_ne!(blocks[0].flags & BLOCK_FLAG_RAW_HTML, 0);
                assert!(blocks[0].destinations.is_empty());
            }
        }
    }

    /// Preserves a marker without substitutions when it exceeds a presentation or label limit.
    #[test]
    fn leaves_oversized_footnote_labels_visible_but_not_interactive() {
        let marker_syntax_bytes = super::FOOTNOTE_PREFIX.len() + super::FOOTNOTE_SUFFIX.len();
        for label_bytes in [
            MAX_BLOCK_TEXT_BYTES - marker_syntax_bytes + 1,
            MAX_LINK_DESTINATION_BYTES + 1,
        ] {
            let label = "n".repeat(label_bytes);
            let markdown = format!("Before [^{label}] after.\n\n[^{label}]: Definition.\n");
            let packet =
                render_markdown(&markdown).expect("oversized footnote should remain visible");
            let decoded = decode_blocks(packet.as_bytes());

            assert!(decoded.concat_text().contains(&label));
            assert!(decoded.iter().all(|block| block.destinations.is_empty()));
            assert!(
                decoded
                    .iter()
                    .flat_map(|block| block.styles.iter())
                    .all(|styles| styles & SPAN_STYLE_FOOTNOTE_REFERENCE == 0)
            );
        }
    }

    #[test]
    fn preserves_malformed_inline_html_without_partial_conversion() {
        let markdown = "Before <strong>broken</em> after.\n";
        let packet = render_markdown(markdown).expect("malformed HTML should remain inert");
        let decoded = decode_blocks(packet.as_bytes());

        assert!(packet.contains_raw_html());
        assert_eq!(decoded.len(), 1);
        assert_eq!(decoded[0].text, markdown.trim_end());
        assert_ne!(decoded[0].flags & BLOCK_FLAG_RAW_HTML, 0);
        assert!(decoded[0].styles.contains(&SPAN_STYLE_CODE));
        assert!(!decoded[0].styles.contains(&SPAN_STYLE_STRONG));
    }

    #[test]
    fn keeps_loose_list_paragraphs_distinct_without_repeating_the_marker() {
        for markdown in [
            "3. First paragraph.\n\n   Second paragraph.\n\n4. Next item.\n",
            "<ol start=\"3\"><li><p>First paragraph.</p><p>Second paragraph.</p></li><li>Next item.</li></ol>\n",
        ] {
            let packet = render_markdown(markdown).expect("loose list should render");
            let blocks = decode_blocks(packet.as_bytes());
            assert_eq!(blocks.len(), 3);
            assert_eq!(blocks[0].list_number, 3);
            assert_eq!(blocks[1].list_number, 0);
            assert_eq!(blocks[2].list_number, 4);
            assert_eq!(blocks[1].text, "Second paragraph.");
            assert_eq!(blocks[1].flags & BLOCK_FLAG_CONTINUATION, 0);
        }
    }

    #[test]
    fn separates_tight_nested_list_items() {
        let markdown = "- Parent\n    1. Child\n";
        let packet = render_markdown(markdown).expect("nested list should render");
        let decoded = decode_blocks(packet.as_bytes());

        assert_eq!(decoded.len(), 2);
        assert_eq!(decoded[0].text, "Parent");
        assert_eq!(decoded[0].list_depth, 1);
        assert_eq!(decoded[1].text, "Child");
        assert_eq!(decoded[1].list_depth, 2);
        assert_eq!(decoded[1].list_number, 1);
        assert_ne!(decoded[1].flags & BLOCK_FLAG_ORDERED_LIST, 0);
    }

    #[test]
    fn splits_pathological_long_paragraphs_at_scalar_boundaries() {
        let markdown = "😀".repeat(MAX_BLOCK_TEXT_BYTES);
        let packet = render_markdown(&markdown).expect("long paragraph should split");
        let decoded = decode_blocks(packet.as_bytes());

        assert!(decoded.len() > 1);
        assert_eq!(decoded.concat_text(), markdown);
        assert!(
            decoded
                .iter()
                .all(|block| block.text.len() <= MAX_BLOCK_TEXT_BYTES
                    && block.text.is_char_boundary(block.text.len()))
        );
        assert!(
            decoded
                .iter()
                .skip(1)
                .all(|block| block.flags & BLOCK_FLAG_CONTINUATION != 0)
        );
    }

    #[test]
    fn splits_safe_html_after_semantic_conversion() {
        let content = format!("{}end", "safe ".repeat(MAX_BLOCK_TEXT_BYTES));
        let markdown = format!("<p>{content}</p>");
        let packet = render_markdown(&markdown).expect("long safe HTML should render");
        let decoded = decode_blocks(packet.as_bytes());

        assert!(!packet.contains_raw_html());
        assert!(decoded.len() > 1);
        assert_eq!(decoded.concat_text(), content);
        assert!(decoded.iter().all(|block| {
            block.kind == BLOCK_KIND_PARAGRAPH && block.text.len() <= MAX_BLOCK_TEXT_BYTES
        }));
        assert!(
            decoded
                .iter()
                .skip(1)
                .all(|block| block.flags & BLOCK_FLAG_CONTINUATION != 0)
        );
    }

    #[test]
    fn rebases_styling_across_presentation_blocks() {
        let content = format!("{}end", "styled ".repeat(MAX_BLOCK_TEXT_BYTES));
        let markdown = format!("**{content}**");
        let packet = render_markdown(&markdown).expect("long styled text should render");
        let decoded = decode_blocks(packet.as_bytes());

        assert!(decoded.len() > 1);
        assert_eq!(decoded.concat_text(), content);
        assert!(decoded.iter().all(|block| {
            block.text.len() <= MAX_BLOCK_TEXT_BYTES
                && block.styles.as_slice() == [SPAN_STYLE_STRONG]
        }));
        assert_eq!(packet.span_count() as usize, decoded.len());
    }

    #[test]
    fn rejects_input_beyond_the_hard_limit() {
        let markdown = "a".repeat(MAX_INPUT_BYTES + 1);

        assert_eq!(render_markdown(&markdown), Err(RenderError::InputLimit));
    }

    #[test]
    fn renders_input_at_the_hard_limit() {
        let markdown = "a".repeat(MAX_INPUT_BYTES);
        let packet = render_markdown(&markdown).expect("limit-sized Markdown should render");
        let decoded = decode_blocks(packet.as_bytes());

        assert_eq!(decoded.concat_text(), markdown);
    }

    /// Preserves exact text provenance beside collapsed whitespace and entities.
    #[test]
    fn preserves_exact_runs_beside_transformed_text() {
        let source = "alpha  \nβ😀 &amp; gamma\n";
        let packet = render_markdown(source).expect("mapped text should render");
        let blocks = decode_blocks(packet.as_bytes());
        let block = &blocks[0];
        for word in ["alpha", "β😀", "gamma"] {
            let rendered_offset = u32::try_from(
                block.text[..block.text.find(word).unwrap()]
                    .encode_utf16()
                    .count(),
            )
            .unwrap();
            let mapping = block
                .source_maps
                .iter()
                .find(|mapping| {
                    rendered_offset >= mapping.rendered_start_utf16
                        && rendered_offset < mapping.rendered_end_utf16
                })
                .expect("word should retain a source mapping");
            assert_eq!(
                mapping.source.len(),
                u64::from(mapping.rendered_end_utf16 - mapping.rendered_start_utf16)
            );
            assert_eq!(
                mapping.source.start_utf16
                    + u64::from(rendered_offset - mapping.rendered_start_utf16),
                source[..source.find(word).unwrap()].encode_utf16().count() as u64
            );
        }
    }

    /// Keeps bounded continuations from claiming the enclosing paragraph's whole range.
    #[test]
    fn bounds_source_ranges_across_builder_continuations() {
        let source = "word  \n".repeat(60_000);
        let packet = render_markdown(&source).expect("continued paragraph should render");
        let blocks = decode_blocks(packet.as_bytes());
        assert!(blocks.len() > 1);
        assert!(
            blocks
                .windows(2)
                .all(|pair| { pair[0].source.end_utf16 <= pair[1].source.start_utf16 })
        );
        assert_eq!(blocks.first().unwrap().source.start_utf16, 0);
        assert_eq!(blocks.last().unwrap().source.end_utf16, source.len() as u64);
    }

    /// Allows precise hard-break maps and chunk boundaries at the existing byte limit.
    #[test]
    fn renders_mapped_lines_at_the_input_limit() {
        const SOURCE_LINE_BYTES: usize = 128;
        let line = format!("{}{}\n", "a".repeat(100), " ".repeat(27));
        assert_eq!(line.len(), SOURCE_LINE_BYTES);
        let source = line.repeat(MAX_INPUT_BYTES / SOURCE_LINE_BYTES);
        let packet = render_markdown(&source).expect("mapped limit-sized text should render");
        assert!(packet.as_bytes().len() <= super::MAX_PACKET_BYTES);
        assert!(packet.source_map_count() as usize <= super::MAX_SOURCE_MAP_COUNT);
    }

    #[test]
    fn renders_footnote_definitions_as_labeled_blocks() {
        let markdown = "Body[^note]\n\n[^note]: Preview data is transient.\n";
        let packet = render_markdown(markdown).expect("footnote Markdown should render");
        let decoded = decode_blocks(packet.as_bytes());
        let footnote = decoded
            .iter()
            .find(|block| block.kind == BLOCK_KIND_FOOTNOTE)
            .expect("footnote definition should produce a block");

        assert_eq!(footnote.text, "Preview data is transient.");
        assert_eq!(footnote.metadata, "note");
    }

    #[test]
    fn renders_links_and_images_without_fetching_image_destinations() {
        let markdown = concat!(
            "Unicode [café 😀](https://example.com/path?q=1) and ",
            "![solar ☀](https://example.com/image.png).\n",
        );
        let packet = render_markdown(markdown).expect("links and images should render");
        let decoded = decode_blocks(packet.as_bytes());

        assert_eq!(decoded.len(), 1);
        assert_eq!(decoded[0].text, "Unicode café 😀 and Image: solar ☀.");
        assert_eq!(
            decoded[0].destinations.as_slice(),
            ["https://example.com/path?q=1"]
        );
        assert!(
            decoded[0]
                .destinations
                .iter()
                .all(|destination| !destination.contains("image.png"))
        );
    }

    #[test]
    fn encodes_empty_markdown_canonically() {
        let packet = render_markdown("").expect("empty Markdown should render");

        assert_eq!(packet.block_count(), 0);
        assert_eq!(packet.span_count(), 0);
        assert_eq!(packet.as_bytes().len(), PACKET_HEADER_BYTES);
        assert_eq!(decode_u32(packet.as_bytes(), BLOCK_COUNT_OFFSET), 0);
    }

    #[test]
    fn treats_a_leading_utf8_bom_as_source_format() {
        let markdown = "\u{feff}# Heading\n";
        let packet = render_markdown(markdown).expect("BOM-prefixed Markdown should render");
        let decoded = decode_blocks(packet.as_bytes());

        assert_eq!(decoded.len(), 1);
        assert_eq!(decoded[0].kind, BLOCK_KIND_HEADING);
        assert_eq!(decoded[0].text, "Heading");
        assert_eq!(decoded[0].source, SourceRange::new(0, 10));
        assert_eq!(
            u64::from_le_bytes(
                packet.as_bytes()[24..32]
                    .try_into()
                    .expect("input byte count should be exact")
            ),
            markdown.len() as u64
        );
        assert_eq!(
            u64::from_le_bytes(
                packet.as_bytes()[64..72]
                    .try_into()
                    .expect("input UTF-16 count should be exact")
            ),
            10
        );
    }

    #[test]
    fn maps_rendered_text_to_normalized_logical_utf16_ranges() {
        let markdown = "# café 😀\r\n\r\nplain";
        let packet = render_markdown(markdown).expect("mapped Markdown should render");
        let decoded = decode_blocks(packet.as_bytes());

        assert_eq!(decoded.len(), 2);
        assert_eq!(decoded[0].text, "café 😀");
        assert_eq!(decoded[0].source, SourceRange::new(0, 10));
        assert_eq!(
            decoded[0].source_maps,
            [InlineSourceMap {
                rendered_start_utf16: 0,
                rendered_end_utf16: 7,
                source: SourceRange::new(2, 9),
            }]
        );
        assert_eq!(decoded[1].text, "plain");
        assert_eq!(decoded[1].source, SourceRange::new(11, 16));
        assert_eq!(
            u64::from_le_bytes(
                packet.as_bytes()[64..72]
                    .try_into()
                    .expect("input UTF-16 count should be exact")
            ),
            16
        );
        assert_eq!(packet.source_map_count(), 2);
    }

    #[test]
    fn preserves_invisible_markdown_syntax_in_block_source_ranges() {
        let packet = render_markdown("**bold**").expect("styled Markdown should render");
        let decoded = decode_blocks(packet.as_bytes());

        assert_eq!(decoded[0].source, SourceRange::new(0, 8));
        assert_eq!(
            decoded[0].source_maps,
            [InlineSourceMap {
                rendered_start_utf16: 0,
                rendered_end_utf16: 4,
                source: SourceRange::new(2, 6),
            }]
        );
    }

    #[test]
    fn stops_at_an_explicit_render_checkpoint() {
        struct Cancelled;

        impl RenderControl for Cancelled {
            fn checkpoint(&mut self) -> Result<(), RenderInterruption> {
                Err(RenderInterruption::Cancelled)
            }
        }

        assert_eq!(
            render_markdown_with_control("# heading", &mut Cancelled),
            Err(RenderError::Cancelled)
        );
    }

    #[derive(Debug)]
    struct DecodedBlock {
        kind: u32,
        flags: u32,
        heading_level: u32,
        quote_depth: u32,
        list_depth: u32,
        list_number: u64,
        text: String,
        metadata: String,
        styles: Vec<u32>,
        span_flags: Vec<u32>,
        destinations: Vec<String>,
        source: SourceRange,
        source_maps: Vec<InlineSourceMap>,
    }

    trait DecodedBlocksExt {
        fn concat_text(&self) -> String;
    }

    impl DecodedBlocksExt for [DecodedBlock] {
        fn concat_text(&self) -> String {
            self.iter().map(|block| block.text.as_str()).collect()
        }
    }

    /// Decodes test packets and checks complete footnote-reference spans.
    fn decode_blocks(packet: &[u8]) -> Vec<DecodedBlock> {
        let block_count = decode_u32(packet, 40) as usize;
        let mut offset = PACKET_HEADER_BYTES;
        let mut blocks = Vec::with_capacity(block_count);
        for _block_index in 0..block_count {
            let header = &packet[offset..offset + super::BLOCK_HEADER_BYTES];
            offset += super::BLOCK_HEADER_BYTES;
            let text_bytes = decode_u32(header, 32) as usize;
            let metadata_bytes = decode_u32(header, 36) as usize;
            let span_count = decode_u32(header, 40) as usize;
            let source_map_count = decode_u32(header, 64) as usize;
            let text = String::from_utf8(packet[offset..offset + text_bytes].to_vec())
                .expect("encoded block text should be UTF-8");
            offset += text_bytes;
            let metadata = String::from_utf8(packet[offset..offset + metadata_bytes].to_vec())
                .expect("encoded block metadata should be UTF-8");
            offset += metadata_bytes;
            let mut styles = Vec::with_capacity(span_count);
            let mut span_flags = Vec::with_capacity(span_count);
            let mut destinations = Vec::new();
            for _span_index in 0..span_count {
                let span = &packet[offset..offset + super::SPAN_HEADER_BYTES];
                offset += super::SPAN_HEADER_BYTES;
                styles.push(decode_u32(span, 8));
                span_flags.push(decode_u32(span, 12));
                let destination_bytes = decode_u32(span, 16) as usize;
                if destination_bytes != 0 {
                    let destination =
                        String::from_utf8(packet[offset..offset + destination_bytes].to_vec())
                            .expect("encoded link destination should be UTF-8");
                    if decode_u32(span, 12) & SPAN_FLAG_FOOTNOTE_REFERENCE != 0 {
                        let start = decode_u32(span, 0) as usize;
                        let end = decode_u32(span, 4) as usize;
                        let marker: Vec<_> =
                            text.encode_utf16().skip(start).take(end - start).collect();
                        assert_eq!(
                            String::from_utf16(&marker)
                                .expect("reference contains complete scalars"),
                            format!("[^{destination}]"),
                        );
                    }
                    destinations.push(destination);
                }
                offset += destination_bytes;
            }
            let mut source_maps = Vec::with_capacity(source_map_count);
            for _source_map_index in 0..source_map_count {
                let source_map = &packet[offset..offset + super::SOURCE_MAP_HEADER_BYTES];
                offset += super::SOURCE_MAP_HEADER_BYTES;
                source_maps.push(InlineSourceMap {
                    rendered_start_utf16: decode_u32(source_map, 0),
                    rendered_end_utf16: decode_u32(source_map, 4),
                    source: SourceRange::new(
                        u64::from_le_bytes(
                            source_map[8..16]
                                .try_into()
                                .expect("source-map start bytes should be exact"),
                        ),
                        u64::from_le_bytes(
                            source_map[16..24]
                                .try_into()
                                .expect("source-map end bytes should be exact"),
                        ),
                    ),
                });
            }
            blocks.push(DecodedBlock {
                kind: decode_u32(header, 0),
                flags: decode_u32(header, 4),
                heading_level: decode_u32(header, 8),
                quote_depth: decode_u32(header, 12),
                list_depth: decode_u32(header, 16),
                list_number: u64::from_le_bytes(
                    header[24..32]
                        .try_into()
                        .expect("u64 bytes should be exact"),
                ),
                text,
                metadata,
                styles,
                span_flags,
                destinations,
                source: SourceRange::new(
                    u64::from_le_bytes(
                        header[48..56]
                            .try_into()
                            .expect("block source start bytes should be exact"),
                    ),
                    u64::from_le_bytes(
                        header[56..64]
                            .try_into()
                            .expect("block source end bytes should be exact"),
                    ),
                ),
                source_maps,
            });
        }
        assert_eq!(offset, packet.len());
        blocks
    }

    fn decode_u32(bytes: &[u8], offset: usize) -> u32 {
        u32::from_le_bytes(
            bytes[offset..offset + size_of::<u32>()]
                .try_into()
                .expect("u32 bytes should be exact"),
        )
    }
}
