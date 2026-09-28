//! Owns persistent revisions without retaining duplicate removed/inserted strings.

use std::collections::{BTreeMap, VecDeque};

use crate::{
    Document, DocumentError, DocumentMetrics, MAX_EDITABLE_UTF16_UNITS,
    piece_tree::{AllocationId, PieceTree},
};

/// Bounds the combined number of native Undo and Redo entries.
pub const MAX_HISTORY_ENTRIES: usize = 128;
/// Bounds distinct changed tree/chunk allocations retained by live history.
///
/// Original source storage is shared and separately bounded by the source limit.
/// A changed allocation is charged once across all entries, including its full backing when a piece
/// uses only a substring. This includes newly inserted live text, so accepting an
/// edit never depends on immediately dropping its Undo. Allocator bookkeeping and
/// the bounded journal/map containers are additional to this payload budget.
pub const MAX_HISTORY_BYTES: usize = 64 * 1024 * 1024;

/// Reports the available native history tokens and charged allocation bytes.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct HistoryState {
    /// Identifies the original revision produced by the newest undoable edit.
    pub undo: u64,
    /// Identifies the original revision produced by the next redoable edit.
    pub redo: u64,
    /// Identifies the oldest retained Undo, or zero for an empty stack.
    pub oldest_undo: u64,
    /// Counts distinct retained changed allocations, including shared chunks once.
    pub retained_bytes: usize,
    /// Counts both stacks together.
    pub entries: usize,
}

struct Entry {
    token: u64,
    before: PieceTree,
    after: PieceTree,
    allocations: Vec<(AllocationId, usize)>,
}

pub(super) struct History {
    undo: VecDeque<Entry>,
    redo: Vec<Entry>,
    allocations: BTreeMap<AllocationId, (usize, usize)>,
    bytes: usize,
    max_bytes: usize,
    max_entries: usize,
}

impl Default for History {
    fn default() -> Self {
        Self {
            undo: VecDeque::new(),
            redo: Vec::new(),
            allocations: BTreeMap::new(),
            bytes: 0,
            max_bytes: MAX_HISTORY_BYTES,
            max_entries: MAX_HISTORY_ENTRIES,
        }
    }
}

impl History {
    fn record(
        &mut self,
        token: u64,
        before: &PieceTree,
        after: &PieceTree,
    ) -> Result<(), DocumentError> {
        let old = before.allocations();
        let new = after.allocations();
        let allocations: Vec<_> = old
            .iter()
            .filter(|(id, _)| !new.contains_key(id))
            .chain(new.iter().filter(|(id, _)| !old.contains_key(id)))
            .map(|(id, size)| (*id, *size))
            .collect();
        // Preflight before discarding Redo or evicting even one existing entry.
        if allocations.iter().map(|(_, bytes)| bytes).sum::<usize>() > self.max_bytes {
            return Err(DocumentError::HistoryLimit);
        }
        while let Some(entry) = self.redo.pop() {
            self.release(&entry);
        }
        for (id, bytes) in &allocations {
            let retained = self.allocations.entry(*id).or_insert((0, *bytes));
            if retained.0 == 0 {
                self.bytes += bytes;
            }
            retained.0 += 1;
        }
        self.undo.push_back(Entry {
            token,
            before: before.clone(),
            after: after.clone(),
            allocations,
        });
        while self.bytes > self.max_bytes || self.undo.len() > self.max_entries {
            let entry = self.undo.pop_front().expect("the new entry alone fits");
            self.release(&entry);
        }
        Ok(())
    }

    fn release(&mut self, entry: &Entry) {
        for (id, _) in &entry.allocations {
            let retained = self
                .allocations
                .get_mut(id)
                .expect("entry allocation is tracked");
            retained.0 -= 1;
            if retained.0 == 0 {
                self.bytes -= retained.1;
                self.allocations.remove(id);
            }
        }
    }
}

impl Document {
    /// Returns native history availability without reading document text.
    #[must_use]
    pub fn history_state(&self) -> HistoryState {
        HistoryState {
            undo: self.history.undo.back().map_or(0, |entry| entry.token),
            redo: self.history.redo.last().map_or(0, |entry| entry.token),
            oldest_undo: self.history.undo.front().map_or(0, |entry| entry.token),
            retained_bytes: self.history.bytes,
            entries: self.history.undo.len() + self.history.redo.len(),
        }
    }

    /// Releases all history roots; independently captured snapshots remain valid.
    pub fn clear_history(&mut self) {
        self.history = History::default();
    }

    /// Restores the exact newest Undo/Redo tree as a fresh monotonic revision.
    ///
    /// # Errors
    /// Rejects stale revisions, mismatched/evicted history tokens, or revision
    /// exhaustion without changing either history stack or the document.
    pub fn restore_history(
        &mut self,
        revision: u64,
        token: u64,
        undo: bool,
    ) -> Result<DocumentMetrics, DocumentError> {
        self.require_revision(revision)?;
        let next = self
            .revision
            .checked_add(1)
            .ok_or(DocumentError::RevisionExhausted)?;
        let entry = if undo {
            self.history.undo.back()
        } else {
            self.history.redo.last()
        };
        if entry.is_none_or(|entry| entry.token != token) {
            return Err(DocumentError::InvalidReplacement(
                "history token is unavailable",
            ));
        }
        if undo {
            let entry = self
                .history
                .undo
                .pop_back()
                .ok_or(DocumentError::InvalidReplacement(
                    "history token is unavailable",
                ))?;
            self.tree = entry.before.clone();
            self.history.redo.push(entry);
        } else {
            let entry = self
                .history
                .redo
                .pop()
                .ok_or(DocumentError::InvalidReplacement(
                    "history token is unavailable",
                ))?;
            self.tree = entry.after.clone();
            self.history.undo.push_back(entry);
        }
        self.revision = next;
        Ok(self.metrics())
    }

    pub(super) fn require_revision(&self, expected: u64) -> Result<(), DocumentError> {
        if expected != self.revision {
            return Err(DocumentError::StaleRevision {
                expected,
                actual: self.revision,
            });
        }
        Ok(())
    }

    pub(super) fn commit_tree(
        &mut self,
        tree: PieceTree,
        save_limit: u64,
    ) -> Result<DocumentMetrics, DocumentError> {
        let units = tree.summary().utf16_units;
        if units > MAX_EDITABLE_UTF16_UNITS {
            return Err(DocumentError::DocumentTooLargeForEditing {
                utf16_units: units,
                max_utf16_units: MAX_EDITABLE_UTF16_UNITS,
            });
        }
        let bytes = tree.serialized_bytes();
        if bytes > self.tree.serialized_bytes()
            && u64::try_from(bytes).unwrap_or(u64::MAX) > save_limit
        {
            return Err(DocumentError::DocumentTooLargeForSaving {
                bytes,
                max_bytes: save_limit,
            });
        }
        let revision = self
            .revision
            .checked_add(1)
            .ok_or(DocumentError::RevisionExhausted)?;
        self.history.record(revision, &self.tree, &tree)?;
        self.tree = tree;
        self.revision = revision;
        Ok(self.metrics())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::Utf16Range;

    fn text(document: &Document) -> String {
        let mut bytes = Vec::new();
        document.tree.write_to(&mut bytes).unwrap();
        String::from_utf8(bytes).unwrap()
    }

    #[test]
    fn restores_trees_with_monotonic_revisions_and_invalidates_redo_on_branch() {
        let mut doc = Document::from_text("before 😀");
        doc.replace(0, Utf16Range::new(0, 6), "after").unwrap();
        let saved = doc.snapshot();
        doc.restore_history(1, 1, true).unwrap();
        assert_eq!(text(&doc), "before 😀");
        assert_eq!(doc.history_state().redo, 1);
        doc.restore_history(2, 1, false).unwrap();
        assert_eq!(text(&doc), "after 😀");
        doc.restore_history(3, 1, true).unwrap();
        doc.replace(4, Utf16Range::new(0, 0), "new ").unwrap();
        assert_eq!(doc.history_state().redo, 0);
        assert!(doc.restore_history(5, 1, false).is_err());
        assert_eq!(saved.read_range(Utf16Range::new(0, 8)).unwrap(), "after 😀");
        doc.clear_history();
        assert_eq!(doc.history_state().retained_bytes, 0);
    }

    #[test]
    fn rejection_preserves_current_revision_both_stacks_and_budget() {
        let mut doc = Document::from_text("start");
        doc.replace(0, Utf16Range::new(0, 0), "a").unwrap();
        doc.replace(1, Utf16Range::new(0, 0), "b").unwrap();
        doc.restore_history(2, 2, true).unwrap();
        doc.history.max_bytes = 2048;
        let state = doc.history_state();
        assert!(matches!(
            doc.replace(3, Utf16Range::new(0, 0), &"x".repeat(4096)),
            Err(DocumentError::HistoryLimit)
        ));
        assert_eq!(doc.history_state(), state);
        assert_eq!(doc.metrics().revision, 3);
        assert_eq!(text(&doc), "astart");
        assert!(doc.restore_history(2, 1, true).is_err());
        assert!(doc.restore_history(3, 1, false).is_err());
        assert_eq!(doc.history_state(), state);
    }

    #[test]
    fn memory_eviction_removes_oldest_history_and_accounts_shared_allocations_once() {
        let mut doc = Document::new();
        doc.history.max_bytes = 200_000;
        for n in 0..20 {
            let end = doc.metrics().utf16_units;
            doc.replace(n, Utf16Range::new(end, end), &"x".repeat(8192))
                .unwrap();
            assert!(doc.history_state().retained_bytes <= 200_000);
        }
        let state = doc.history_state();
        assert!(state.oldest_undo > 1);
        assert_eq!(state.undo, 20);
        for _ in 0..state.entries {
            let state = doc.history_state();
            doc.restore_history(doc.revision, state.undo, true).unwrap();
            assert_eq!(doc.history_state().retained_bytes, state.retained_bytes);
        }
        assert_eq!(doc.history_state().undo, 0);
        assert!(doc.history_state().redo > 0);
    }
    #[test]
    fn every_obsolete_allocation_is_charged_across_eviction_undo_redo_and_branching() {
        let mut doc = Document::from_text(&"a".repeat(8192));
        doc.history.max_bytes = 160_000;
        let mut seed = 7_u64;
        for _ in 0..500 {
            seed = seed.wrapping_mul(6_364_136_223_846_793_005).wrapping_add(1);
            let state = doc.history_state();
            match seed % 7 {
                0 if state.undo != 0 => {
                    doc.restore_history(doc.revision, state.undo, true).unwrap();
                }
                1 if state.redo != 0 => {
                    doc.restore_history(doc.revision, state.redo, false)
                        .unwrap();
                }
                _ => {
                    let len = doc.metrics().utf16_units;
                    let start = usize::try_from(seed % u64::try_from(len + 1).unwrap()).unwrap();
                    let end = (start + 64).min(len);
                    let replacement = "xyz".repeat(usize::try_from(seed % 128).unwrap());
                    doc.replace(doc.revision, Utf16Range::new(start, end), &replacement)
                        .unwrap();
                }
            }
            let live = doc.tree.allocations();
            let mut retained = BTreeMap::new();
            for entry in doc.history.undo.iter().chain(&doc.history.redo) {
                retained.extend(entry.before.allocations());
                retained.extend(entry.after.allocations());
            }
            // Every allocation retained only by history must fit its charged set.
            for (id, bytes) in retained {
                if !live.contains_key(&id) {
                    assert_eq!(
                        doc.history.allocations.get(&id).map(|(_, size)| *size),
                        Some(bytes)
                    );
                }
            }
            assert_eq!(
                doc.history.bytes,
                doc.history
                    .allocations
                    .values()
                    .map(|(_, bytes)| bytes)
                    .sum::<usize>()
            );
            assert!(doc.history.bytes <= doc.history.max_bytes);
        }
    }
}
