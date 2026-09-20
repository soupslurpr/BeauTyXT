//! Bounds aggregate render allocations before packet encoding.

use super::{
    MAX_BLOCK_COUNT, MAX_PACKET_BYTES, MAX_SOURCE_MAP_COUNT, MAX_SPAN_COUNT, PACKET_HEADER_BYTES,
    RenderBlock, RenderError,
};

/// Owns render blocks whose aggregate wire representation fits the packet limits.
#[derive(Debug)]
pub(super) struct BlockBuffer {
    pub(super) blocks: Vec<RenderBlock>,
    pub(super) span_count: usize,
    pub(super) source_map_count: usize,
    packet_bytes: usize,
    max_packet_bytes: usize,
}

impl BlockBuffer {
    /// Creates an empty bounded block buffer.
    pub(super) fn new() -> Self {
        Self::with_byte_limit(MAX_PACKET_BYTES)
    }

    /// Creates a buffer limited to the space remaining in its parent model.
    pub(super) fn with_byte_limit(max_packet_bytes: usize) -> Self {
        assert!((PACKET_HEADER_BYTES..=MAX_PACKET_BYTES).contains(&max_packet_bytes));
        Self {
            blocks: Vec::new(),
            span_count: 0,
            source_map_count: 0,
            packet_bytes: PACKET_HEADER_BYTES,
            max_packet_bytes,
        }
    }

    /// Creates a bounded buffer containing one block.
    pub(super) fn from_block(block: RenderBlock) -> Result<Self, RenderError> {
        let mut buffer = Self::new();
        buffer.push(block)?;
        Ok(buffer)
    }

    /// Returns whether the buffer contains no render blocks.
    pub(super) fn is_empty(&self) -> bool {
        self.blocks.is_empty()
    }

    /// Returns the packet budget available to a nested block buffer.
    pub(super) fn remaining_packet_bytes(&self) -> usize {
        self.max_packet_bytes - self.packet_bytes + PACKET_HEADER_BYTES
    }

    /// Appends one block only when the resulting aggregate remains bounded.
    pub(super) fn push(&mut self, block: RenderBlock) -> Result<(), RenderError> {
        if self.blocks.len() >= MAX_BLOCK_COUNT {
            return Err(RenderError::BlockLimit);
        }
        let span_count = self
            .span_count
            .checked_add(block.spans.len())
            .ok_or(RenderError::ArithmeticOverflow)?;
        if span_count > MAX_SPAN_COUNT {
            return Err(RenderError::SpanLimit);
        }
        let source_map_count = self
            .source_map_count
            .checked_add(block.source_maps.len())
            .ok_or(RenderError::ArithmeticOverflow)?;
        if source_map_count > MAX_SOURCE_MAP_COUNT {
            return Err(RenderError::SourceMapLimit);
        }
        let packet_bytes = self
            .packet_bytes
            .checked_add(block.packet_bytes()?)
            .ok_or(RenderError::ArithmeticOverflow)?;
        if packet_bytes > self.max_packet_bytes {
            return Err(RenderError::PacketLimit);
        }
        self.blocks.push(block);
        self.span_count = span_count;
        self.source_map_count = source_map_count;
        self.packet_bytes = packet_bytes;
        Ok(())
    }

    /// Moves another buffer's blocks into this buffer with aggregate validation.
    pub(super) fn append(&mut self, other: Self) -> Result<(), RenderError> {
        for block in other.blocks {
            self.push(block)?;
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    //! Verifies nested byte budgets and failure-preserving counters.

    use super::{BlockBuffer, PACKET_HEADER_BYTES, RenderBlock, RenderError};
    use crate::{BLOCK_KIND_PARAGRAPH, BlockSpec, SourceRange};

    /// Preserves exact accounting when a child fills its parent's remaining budget.
    #[test]
    fn bounds_nested_buffers_without_resetting_the_budget() {
        let block_bytes = test_block().packet_bytes().unwrap();
        let mut parent = BlockBuffer::with_byte_limit(PACKET_HEADER_BYTES + 2 * block_bytes);
        parent.push(test_block()).unwrap();
        let mut child = BlockBuffer::with_byte_limit(parent.remaining_packet_bytes());
        child.push(test_block()).unwrap();

        assert_eq!(child.push(test_block()), Err(RenderError::PacketLimit));
        assert_eq!(child.blocks.len(), 1);
        assert_eq!(child.source_map_count, 1);
        parent.append(child).unwrap();
        assert_eq!(parent.blocks.len(), 2);
        assert_eq!(parent.source_map_count, 2);
        assert_eq!(parent.remaining_packet_bytes(), PACKET_HEADER_BYTES);
        assert_eq!(parent.push(test_block()), Err(RenderError::PacketLimit));
        assert_eq!(parent.blocks.len(), 2);
    }

    /// Creates one small mapped paragraph with no external resources.
    fn test_block() -> RenderBlock {
        let mut block = RenderBlock::new(BlockSpec {
            kind: BLOCK_KIND_PARAGRAPH,
            flags: 0,
            heading_level: 0,
            quote_depth: 0,
            list_depth: 0,
            list_number: 0,
            metadata: String::new(),
        });
        block
            .append_mapped("x", 0, None, SourceRange::new(0, 1))
            .unwrap();
        block
    }
}
