//! Validates and publishes a complete replacement transaction once.

use beautyxt_source_save_core::MAX_OUTPUT_BYTES;

use crate::{
    Document, DocumentError, DocumentMetrics, DocumentSnapshot, MAX_EDITABLE_UTF16_UNITS,
    MAX_INLINE_REPLACEMENT_BYTES, Utf16Range, piece_tree::ReplaceOutcome, read_find_text,
};

/// Bounds the number of patches in one atomic, undoable transaction.
pub const MAX_BATCH_EDITS: usize = 4096;
/// Bounds the combined removed and inserted UTF-16 retained for one Undo.
pub const MAX_BATCH_HISTORY_UTF16_UNITS: usize = 256 * 1024;

/// Describes an exact patch in the original revision's coordinates.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct DocumentEdit {
    /// Identifies the original source range.
    pub range: Utf16Range,
    /// Verifies the exact text reviewed by the caller, including empty insertions.
    pub expected: String,
    /// Contains normalized replacement text, already expanded from captures.
    pub replacement: String,
}

impl Document {
    /// Publishes sorted, nonoverlapping patches as at most one new revision.
    ///
    /// All validation and tree construction precede publication. Rejected
    /// transactions leave both the current document and snapshots unchanged.
    /// The history bound is checked here as well as in the platform journal;
    /// callers can always retain the inverse without copying untouched text.
    ///
    /// # Errors
    ///
    /// Rejects stale revisions, mismatched or overlapping patches, invalid
    /// UTF-16 boundaries, and history, edit-count, or document-size overflow.
    pub fn replace_batch(
        &mut self,
        expected_revision: u64,
        edits: &[DocumentEdit],
    ) -> Result<DocumentMetrics, DocumentError> {
        self.replace_batch_with_limit(expected_revision, edits, MAX_OUTPUT_BYTES)
    }

    fn replace_batch_with_limit(
        &mut self,
        expected_revision: u64,
        edits: &[DocumentEdit],
        save_limit: u64,
    ) -> Result<DocumentMetrics, DocumentError> {
        if expected_revision != self.revision {
            return Err(DocumentError::StaleRevision {
                expected: expected_revision,
                actual: self.revision,
            });
        }
        if edits.len() > MAX_BATCH_EDITS {
            return Err(DocumentError::InvalidReplacement("too many batch edits"));
        }
        let mut retained_units = 0_usize;
        let mut previous: Option<Utf16Range> = None;
        let mut reader = self.tree.reader();
        for edit in edits {
            if edit.range.start > edit.range.end
                || edit.range.end > self.tree.summary().utf16_units
                || previous.is_some_and(|range| {
                    range.end > edit.range.start || range.start == edit.range.start
                })
            {
                return Err(DocumentError::InvalidReplacement(
                    "batch ranges must be valid, sorted, and nonoverlapping",
                ));
            }
            if edit.replacement.len() > MAX_INLINE_REPLACEMENT_BYTES
                || edit.replacement.contains('\r')
            {
                return Err(DocumentError::InvalidReplacement(
                    "batch replacement exceeds its limit or is not normalized",
                ));
            }
            retained_units = retained_units
                .checked_add(edit.expected.encode_utf16().count())
                .and_then(|units| units.checked_add(edit.replacement.encode_utf16().count()))
                .filter(|units| *units <= MAX_BATCH_HISTORY_UTF16_UNITS)
                .ok_or(DocumentError::InvalidReplacement(
                    "batch exceeds Undo text limit",
                ))?;
            if edit.expected.encode_utf16().count() != edit.range.len()
                || read_find_text(&mut reader, edit.range)? != edit.expected
            {
                return Err(DocumentError::InvalidReplacement(
                    "batch text differs from the reviewed revision",
                ));
            }
            previous = Some(edit.range);
        }
        let mut next_tree = self.tree.clone();
        let mut changed = false;
        for edit in edits.iter().rev() {
            if let ReplaceOutcome::Replaced(tree) =
                next_tree.replace(edit.range, &edit.replacement)?
            {
                next_tree = tree;
                changed = true;
            }
        }
        if !changed {
            return Ok(self.metrics());
        }
        let units = next_tree.summary().utf16_units;
        if units > MAX_EDITABLE_UTF16_UNITS {
            return Err(DocumentError::DocumentTooLargeForEditing {
                utf16_units: units,
                max_utf16_units: MAX_EDITABLE_UTF16_UNITS,
            });
        }
        let bytes = next_tree.serialized_bytes();
        if bytes > self.tree.serialized_bytes()
            && u64::try_from(bytes).unwrap_or(u64::MAX) > save_limit
        {
            return Err(DocumentError::DocumentTooLargeForSaving {
                bytes,
                max_bytes: save_limit,
            });
        }
        let next_revision = self
            .revision
            .checked_add(1)
            .ok_or(DocumentError::RevisionExhausted)?;
        self.tree = next_tree;
        self.revision = next_revision;
        Ok(self.metrics())
    }
}

impl DocumentSnapshot {
    /// Resolves an exact scalar-aligned source offset to its revisioned viewport cursor.
    ///
    /// # Errors
    ///
    /// Rejects invalid offsets and unreadable source metadata.
    pub fn position_at(&self, offset: usize) -> Result<crate::ViewportPosition, DocumentError> {
        let mut reader = self.tree.reader();
        let position = reader.position_at_utf16(offset)?;
        let line_start = reader.line_bounds(position.line_feeds)?.content_start_utf16;
        Ok(crate::ViewportPosition {
            revision: self.metrics.revision,
            line: position.line_feeds,
            utf16_offset: offset - line_start,
        })
    }

    /// Reads an exact bounded logical source range without consuming the snapshot.
    ///
    /// # Errors
    ///
    /// Rejects invalid or misaligned ranges and reads larger than the Undo budget.
    pub fn read_range(&self, range: Utf16Range) -> Result<String, DocumentError> {
        if range.start > range.end || range.len() > MAX_BATCH_HISTORY_UTF16_UNITS {
            return Err(DocumentError::InvalidReplacement(
                "source range exceeds text limit",
            ));
        }
        read_find_text(&mut self.tree.reader(), range)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn edit(start: usize, end: usize, expected: &str, replacement: &str) -> DocumentEdit {
        DocumentEdit {
            range: Utf16Range::new(start, end),
            expected: expected.into(),
            replacement: replacement.into(),
        }
    }

    fn text(document: &Document) -> String {
        document
            .snapshot()
            .read_range(Utf16Range::new(0, document.metrics().utf16_units))
            .unwrap()
    }

    #[test]
    fn publishes_once_and_retains_snapshot_and_exact_inverse() {
        let mut document = Document::from_text("cat 😀 cat\n");
        let original = document.snapshot();
        let after = document
            .replace_batch(
                0,
                &[
                    edit(0, 3, "cat", "kitten"),
                    edit(7, 10, "cat", "猫"),
                    edit(11, 11, "", "end"),
                ],
            )
            .unwrap();
        assert_eq!(after.revision, 1);
        assert_eq!(text(&document), "kitten 😀 猫\nend");
        assert_eq!(
            original.read_range(Utf16Range::new(0, 11)).unwrap(),
            "cat 😀 cat\n"
        );
        document
            .replace_batch(
                1,
                &[
                    edit(0, 6, "kitten", "cat"),
                    edit(10, 11, "猫", "cat"),
                    edit(12, 15, "end", ""),
                ],
            )
            .unwrap();
        assert_eq!(text(&document), "cat 😀 cat\n");
        assert_eq!(document.metrics().revision, 2);
    }

    #[test]
    fn late_invalid_patch_does_not_publish_earlier_edits() {
        let mut document = Document::from_text("cat cat");
        for edits in [
            vec![edit(0, 3, "cat", "dog"), edit(4, 7, "bad", "dog")],
            vec![edit(0, 3, "cat", "dog"), edit(2, 3, "t", "dog")],
            vec![edit(0, 0, "", "x"), edit(0, 0, "", "y")],
            vec![edit(
                0,
                3,
                "cat",
                "x".repeat(MAX_BATCH_HISTORY_UTF16_UNITS).as_str(),
            )],
        ] {
            assert!(document.replace_batch(0, &edits).is_err());
            assert_eq!(document.metrics().revision, 0);
            assert_eq!(text(&document), "cat cat");
        }
    }

    #[test]
    fn rejects_output_growth_and_stale_revision_without_any_change() {
        let mut document = Document::from_text("a b");
        let edits = [edit(0, 1, "a", "long"), edit(2, 3, "b", "long")];
        assert!(document.replace_batch_with_limit(0, &edits, 4).is_err());
        assert!(document.replace_batch(1, &edits).is_err());
        assert_eq!(text(&document), "a b");
        assert_eq!(document.metrics().revision, 0);
    }

    #[test]
    fn rejects_surrogate_splits_and_does_not_create_noop_revisions() {
        let mut document = Document::from_text("😀");
        assert!(document.replace_batch(0, &[edit(1, 1, "", "x")]).is_err());
        assert_eq!(
            document
                .replace_batch(0, &[edit(0, 2, "😀", "😀")])
                .unwrap()
                .revision,
            0
        );
        assert_eq!(document.replace_batch(0, &[]).unwrap().revision, 0);
    }
}
