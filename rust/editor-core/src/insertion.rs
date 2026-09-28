//! Builds large replacements privately from bounded chunks, then publishes once.

use crate::{
    Document, DocumentError, DocumentMetrics, MAX_HISTORY_BYTES, Utf16Range,
    piece_tree::{MAX_PIECE_BYTES, PieceTree},
};
use beautyxt_source_save_core::MAX_OUTPUT_BYTES;

/// Bounds the UTF-8 allocation crossing the native insertion boundary per call.
pub const MAX_INSERTION_CHUNK_BYTES: usize = 64 * 1024;

pub(super) struct Insertion {
    revision: u64,
    range: Utf16Range,
    tree: PieceTree,
    buffer: String,
    bytes: usize,
    utf16_units: usize,
    matches_original: bool,
}

impl Insertion {
    fn append(&mut self, mut text: &str) -> Result<(), DocumentError> {
        while !text.is_empty() {
            let mut take = text.len().min(MAX_PIECE_BYTES - self.buffer.len());
            while !text.is_char_boundary(take) {
                take -= 1;
            }
            if take == 0 {
                self.flush()?;
            } else {
                self.buffer.push_str(&text[..take]);
                text = &text[take..];
            }
        }
        Ok(())
    }

    fn flush(&mut self) -> Result<(), DocumentError> {
        self.tree.append_chunk(&self.buffer)?;
        self.buffer.clear();
        Ok(())
    }
}

impl Document {
    /// Starts a private replacement for an exact revision and scalar-aligned range.
    ///
    /// # Errors
    /// Rejects stale revisions, invalid ranges, and an already active insertion.
    pub fn begin_insertion(
        &mut self,
        revision: u64,
        range: Utf16Range,
    ) -> Result<(), DocumentError> {
        self.require_revision(revision)?;
        if self.insertion.is_some() {
            return Err(DocumentError::InvalidReplacement(
                "an insertion is already active",
            ));
        }
        self.tree.reader().bounded_text_prefix(range, 0)?;
        self.insertion = Some(Insertion {
            revision,
            range,
            tree: self.tree.empty_insertion(),
            buffer: String::with_capacity(MAX_PIECE_BYTES),
            bytes: 0,
            utf16_units: 0,
            matches_original: true,
        });
        Ok(())
    }

    /// Adds a normalized bounded chunk without changing the published document.
    ///
    /// # Errors
    /// Rejects stale, missing, oversized, or non-normalized insertion data.
    /// Call `cancel_insertion` after any failure to release the private chunks.
    pub fn append_insertion(&mut self, revision: u64, chunk: &str) -> Result<(), DocumentError> {
        self.require_revision(revision)?;
        if chunk.len() > MAX_INSERTION_CHUNK_BYTES || chunk.contains('\r') {
            return Err(DocumentError::InvalidReplacement("invalid insertion chunk"));
        }
        let pending = self
            .insertion
            .as_mut()
            .filter(|pending| pending.revision == revision)
            .ok_or(DocumentError::InvalidReplacement(
                "insertion is unavailable",
            ))?;
        let bytes = pending.bytes.saturating_add(chunk.len());
        if PieceTree::insertion_allocation_bound(bytes) > MAX_HISTORY_BYTES {
            return Err(DocumentError::HistoryLimit);
        }
        let units = chunk.encode_utf16().count();
        let utf16_units = pending.utf16_units + units;
        let matches_original = pending.matches_original
            && utf16_units <= pending.range.len()
            && self
                .tree
                .reader()
                .bounded_text_prefix(
                    Utf16Range::new(pending.range.start + pending.utf16_units, pending.range.end),
                    units,
                )?
                .text
                == chunk;
        pending.append(chunk)?;
        pending.bytes = bytes;
        pending.utf16_units = utf16_units;
        pending.matches_original = matches_original;
        Ok(())
    }

    /// Releases an unfinished insertion without changing the document or history.
    pub fn cancel_insertion(&mut self) {
        self.insertion = None;
    }

    /// Publishes a complete insertion atomically with its native Undo revision.
    ///
    /// # Errors
    /// Rejects stale revisions and memory, save, or editable size overflow. The
    /// private insertion is consumed even on failure; published state is intact.
    pub fn finish_insertion(&mut self, revision: u64) -> Result<DocumentMetrics, DocumentError> {
        let mut pending = self
            .insertion
            .take()
            .ok_or(DocumentError::InvalidReplacement(
                "insertion is unavailable",
            ))?;
        self.require_revision(revision)?;
        if pending.revision != revision {
            return Err(DocumentError::InvalidReplacement(
                "insertion revision differs",
            ));
        }
        // Equality was checked as each bounded chunk arrived. Finishing never
        // performs a second whole-text comparison inside the publication call.
        if pending.matches_original && pending.utf16_units == pending.range.len() {
            return Ok(self.metrics());
        }
        pending.flush()?;
        let tree = self.tree.replace_tree(pending.range, &pending.tree)?;
        self.commit_tree(tree, MAX_OUTPUT_BYTES)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn multi_megabyte_unicode_paste_is_one_revision_and_shares_storage_with_redo() {
        let mut doc = Document::from_text("before\nafter");
        let chunk = "😀é\n".repeat(8192);
        doc.begin_insertion(0, Utf16Range::new(7, 7)).unwrap();
        for _ in 0..64 {
            doc.append_insertion(0, &chunk).unwrap();
        }
        assert_eq!(doc.metrics().revision, 0);
        assert_eq!(doc.history_state().entries, 0);
        let pasted = doc.finish_insertion(0).unwrap();
        assert_eq!(pasted.revision, 1);
        assert_eq!(pasted.utf16_units, 12 + 64 * 8192 * 4);
        let cost = doc.history_state().retained_bytes;
        assert!(cost < chunk.len() * 64 + 100_000);
        doc.restore_history(1, 1, true).unwrap();
        assert_eq!(doc.metrics().utf16_units, 12);
        assert_eq!(doc.history_state().retained_bytes, cost);
        doc.restore_history(2, 1, false).unwrap();
        assert_eq!(doc.metrics().bytes, pasted.bytes);
        let snapshot = doc.snapshot();
        doc.clear_history();
        assert_eq!(snapshot.metrics.utf16_units, pasted.utf16_units);
    }

    #[test]
    fn cancelled_failed_stale_and_unchanged_insertions_preserve_history() {
        let mut doc = Document::from_text("start😀end");
        doc.replace(0, Utf16Range::new(0, 0), "!").unwrap();
        let state = doc.history_state();
        doc.begin_insertion(1, Utf16Range::new(1, 6)).unwrap();
        doc.append_insertion(1, "sta").unwrap();
        doc.append_insertion(1, "rt").unwrap();
        assert_eq!(doc.finish_insertion(1).unwrap().revision, 1);
        assert_eq!(doc.history_state(), state);
        assert!(doc.begin_insertion(1, Utf16Range::new(7, 8)).is_err());
        doc.begin_insertion(1, Utf16Range::new(0, 0)).unwrap();
        doc.append_insertion(1, "discard").unwrap();
        assert!(doc.append_insertion(1, "\r").is_err());
        doc.cancel_insertion();
        assert_eq!(doc.history_state(), state);
        doc.begin_insertion(1, Utf16Range::new(0, 0)).unwrap();
        doc.restore_history(1, 1, true).unwrap();
        assert!(doc.finish_insertion(1).is_err());
        assert_eq!(doc.metrics().revision, 2);
        assert_eq!(doc.history_state().redo, 1);
    }
    #[test]
    fn coalesces_tiny_input_chunks_and_checks_equality_before_publication() {
        let mut doc = Document::new();
        doc.begin_insertion(0, Utf16Range::new(0, 0)).unwrap();
        for _ in 0..200_000 {
            doc.append_insertion(0, "x").unwrap();
        }
        let pending = doc.insertion.as_ref().unwrap();
        assert!(pending.buffer.capacity() <= MAX_PIECE_BYTES);
        assert!(pending.tree.allocations().len() < 20);
        assert_eq!(doc.finish_insertion(0).unwrap().revision, 1);
        doc.begin_insertion(1, Utf16Range::new(0, 200_000)).unwrap();
        for _ in 0..100 {
            doc.append_insertion(1, &"x".repeat(2000)).unwrap();
        }
        assert_eq!(doc.finish_insertion(1).unwrap().revision, 1);
        assert_eq!(doc.history_state().entries, 1);
    }
}
