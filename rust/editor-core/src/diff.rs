//! Bounded line-level and format-level diffing for read-only source conflict comparison.

use std::collections::HashMap;
use std::io::{self, Write};

use crate::{DocumentError, DocumentSnapshot, Utf16Range};

/// Maximum UTF-16 code units compared per side before reporting a partial diff.
pub const MAX_DIFF_UTF16_UNITS: usize = 256 * 1024;

/// Maximum logical lines compared per side before reporting a partial diff.
pub const MAX_DIFF_LINES: usize = 2048;

/// Maximum Myers edit distance explored before falling back to bounded alignment.
pub const MAX_MYERS_EDIT_DISTANCE: usize = 512;

/// Maximum displayed lines emitted across all hunks.
pub const MAX_DIFF_OUTPUT_LINES: usize = 512;

/// Maximum UTF-8 bytes retained for any single displayed diff line.
pub const MAX_DIFF_LINE_BYTES: usize = 512;

/// Number of unchanged context lines included before and after each change run.
pub const DIFF_CONTEXT_LINES: usize = 3;

/// Extra lines aligned past [`MAX_DIFF_LINES`] so boundary shifts do not invent false deletions or hide replacements.
const DIFF_BOUNDARY_LOOKAHEAD_LINES: usize = 64;

/// Lookahead window used by the linear fallback when Myers edit distance is exceeded.
const FALLBACK_LOOKAHEAD_LINES: usize = 8;

/// Line-ending bitmask bit for LF (`\n`).
pub const LINE_ENDING_LF_BIT: u8 = 1 << 0;

/// Line-ending bitmask bit for CRLF (`\r\n`).
pub const LINE_ENDING_CRLF_BIT: u8 = 1 << 1;

/// Line-ending bitmask bit for CR (`\r`).
pub const LINE_ENDING_CR_BIT: u8 = 1 << 2;

/// Classifies one line inside a conflict-comparison hunk.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(u8)]
pub enum DiffLineKind {
    /// Unchanged context line present in both external and local revisions.
    Context = 0,
    /// Line present in the external file on disk that is absent or changed locally.
    Removed = 1,
    /// Line present in the open local revision that is absent or changed on disk.
    Added = 2,
}

/// Holds one bounded display line inside a diff hunk.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct DiffLine {
    /// Identifies whether this line is context, external-only, or local-only.
    pub kind: DiffLineKind,
    /// Contains valid UTF-8 display text bounded by [`MAX_DIFF_LINE_BYTES`].
    pub text: String,
}

/// Groups nearby line changes with surrounding context and 1-based line origins.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct DiffHunk {
    /// Stores the 1-based starting line number in the external file.
    pub external_start_line: usize,
    /// Stores the 1-based starting line number in the local revision.
    pub local_start_line: usize,
    /// Contains ordered context, removed, and added lines for this hunk.
    pub lines: Vec<DiffLine>,
}

/// Summarizes text and format differences between two immutable snapshots.
#[allow(clippy::struct_excessive_bools)]
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct DocumentDiff {
    /// Indicates whether the external file begins with a UTF-8 BOM.
    pub external_has_bom: bool,
    /// Indicates whether the local revision begins with a UTF-8 BOM.
    pub local_has_bom: bool,
    /// Bitmask of serialized line endings present in the external file.
    pub external_line_endings: u8,
    /// Bitmask of serialized line endings present in the local revision.
    pub local_line_endings: u8,
    /// Exact serialized byte length of the external file.
    pub external_bytes: usize,
    /// Exact serialized byte length of the local revision.
    pub local_bytes: usize,
    /// Exact logical line count of the external file.
    pub external_lines: usize,
    /// Exact logical line count of the local revision.
    pub local_lines: usize,
    /// Count of logical lines inspected in the bounded prefix.
    pub compared_lines: usize,
    /// Count of local lines added or modified in the compared prefix.
    pub added_lines: usize,
    /// Count of external lines removed or modified in the compared prefix.
    pub removed_lines: usize,
    /// Indicates that normalized text is proven completely identical across both revisions.
    pub text_identical: bool,
    /// Indicates whether serialized BOM or line endings differ (including mixed-ending positions).
    pub format_differs: bool,
    /// Indicates that size, line, edit-distance, or output bounds truncated comparison.
    pub is_partial: bool,
    /// Contains bounded unified hunks for display.
    pub hunks: Vec<DiffHunk>,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum EditOp {
    Equal(usize, usize),
    Remove(usize),
    Add(usize),
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
struct PositionedOp {
    op: EditOp,
    ext_line: usize,
    loc_line: usize,
}

/// Compares a streamed byte sequence against an expected slice without a second buffer.
struct SliceMismatchWriter<'a> {
    expected: &'a [u8],
    offset: usize,
    mismatched: bool,
}

impl<'a> SliceMismatchWriter<'a> {
    const fn new(expected: &'a [u8]) -> Self {
        Self {
            expected,
            offset: 0,
            mismatched: false,
        }
    }

    const fn differs(&self) -> bool {
        self.mismatched || self.offset != self.expected.len()
    }
}

impl Write for SliceMismatchWriter<'_> {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        if !self.mismatched {
            let end = self.offset.saturating_add(buf.len());
            if end > self.expected.len() || &self.expected[self.offset..end] != buf {
                self.mismatched = true;
            } else {
                self.offset = end;
            }
        }
        Ok(buf.len())
    }

    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}

/// Computes a bounded read-only line diff from `external` to `local`.
///
/// # Errors
///
/// Returns [`DocumentError`] if either immutable snapshot cannot read its bounded prefix.
pub fn diff_snapshots(
    external: &DocumentSnapshot,
    local: &DocumentSnapshot,
) -> Result<DocumentDiff, DocumentError> {
    let ext_metrics = external.metrics();
    let loc_metrics = local.metrics();
    let external_line_endings = line_ending_mask(
        ext_metrics.has_lf_line_endings,
        ext_metrics.has_crlf_line_endings,
        ext_metrics.has_cr_line_endings,
    );
    let local_line_endings = line_ending_mask(
        loc_metrics.has_lf_line_endings,
        loc_metrics.has_crlf_line_endings,
        loc_metrics.has_cr_line_endings,
    );

    let ext_prefix = external.tree.reader().bounded_text_prefix(
        Utf16Range::new(0, ext_metrics.utf16_units),
        MAX_DIFF_UTF16_UNITS,
    )?;
    let loc_prefix = local.tree.reader().bounded_text_prefix(
        Utf16Range::new(0, loc_metrics.utf16_units),
        MAX_DIFF_UTF16_UNITS,
    )?;

    let ext_prefix_cut = !ext_prefix.reached_end;
    let loc_prefix_cut = !loc_prefix.reached_end;
    let ext_all_lines: Vec<&str> = split_logical_lines(&ext_prefix.text);
    let loc_all_lines: Vec<&str> = split_logical_lines(&loc_prefix.text);
    let ext_complete_len = if ext_prefix_cut && ext_all_lines.len() > 1 {
        ext_all_lines.len() - 1
    } else {
        ext_all_lines.len()
    };
    let loc_complete_len = if loc_prefix_cut && loc_all_lines.len() > 1 {
        loc_all_lines.len() - 1
    } else {
        loc_all_lines.len()
    };
    let ext_truncated = ext_prefix_cut || ext_complete_len > MAX_DIFF_LINES;
    let loc_truncated = loc_prefix_cut || loc_complete_len > MAX_DIFF_LINES;
    let mut is_partial = ext_truncated || loc_truncated;
    let ext_limit = ext_complete_len.min(MAX_DIFF_LINES);
    let loc_limit = loc_complete_len.min(MAX_DIFF_LINES);
    let ext_align_len = ext_complete_len.min(MAX_DIFF_LINES + DIFF_BOUNDARY_LOOKAHEAD_LINES);
    let loc_align_len = loc_complete_len.min(MAX_DIFF_LINES + DIFF_BOUNDARY_LOOKAHEAD_LINES);
    let ext_align_truncated = ext_prefix_cut || ext_complete_len > ext_align_len;
    let loc_align_truncated = loc_prefix_cut || loc_complete_len > loc_align_len;
    let ext_align_lines = &ext_all_lines[..ext_align_len];
    let loc_align_lines = &loc_all_lines[..loc_align_len];

    let (mut raw_ops, myers_partial) = compute_bounded_ops(ext_align_lines, loc_align_lines);
    if myers_partial {
        is_partial = true;
    }
    trim_truncated_tail(&mut raw_ops, ext_align_truncated, loc_align_truncated);
    let (ops, compared_lines) = project_ops_to_preview(&raw_ops, ext_limit, loc_limit);

    let mut added_lines = 0_usize;
    let mut removed_lines = 0_usize;
    for entry in &ops {
        match entry.op {
            EditOp::Equal(_, _) => {}
            EditOp::Remove(_) => removed_lines += 1,
            EditOp::Add(_) => added_lines += 1,
        }
    }

    let (hunks, output_truncated) = build_hunks(ext_align_lines, loc_align_lines, &ops);
    if output_truncated {
        is_partial = true;
    }

    let text_identical = !is_partial
        && added_lines == 0
        && removed_lines == 0
        && ext_metrics.utf16_units == loc_metrics.utf16_units;

    let format_differs = snapshots_format_differs(
        external,
        local,
        ext_metrics,
        loc_metrics,
        external_line_endings,
        local_line_endings,
        text_identical,
    )?;

    Ok(DocumentDiff {
        external_has_bom: ext_metrics.has_utf8_bom,
        local_has_bom: loc_metrics.has_utf8_bom,
        external_line_endings,
        local_line_endings,
        external_bytes: ext_metrics.serialized_bytes,
        local_bytes: loc_metrics.serialized_bytes,
        external_lines: ext_metrics.lines,
        local_lines: loc_metrics.lines,
        compared_lines,
        added_lines,
        removed_lines,
        text_identical,
        format_differs,
        is_partial,
        hunks,
    })
}

const fn line_ending_mask(has_lf: bool, has_crlf: bool, has_cr: bool) -> u8 {
    (if has_lf { LINE_ENDING_LF_BIT } else { 0 })
        | (if has_crlf { LINE_ENDING_CRLF_BIT } else { 0 })
        | (if has_cr { LINE_ENDING_CR_BIT } else { 0 })
}

fn snapshots_format_differs(
    external: &DocumentSnapshot,
    local: &DocumentSnapshot,
    ext_metrics: crate::DocumentMetrics,
    loc_metrics: crate::DocumentMetrics,
    external_line_endings: u8,
    local_line_endings: u8,
    text_identical: bool,
) -> Result<bool, DocumentError> {
    if ext_metrics.has_utf8_bom != loc_metrics.has_utf8_bom
        || external_line_endings != local_line_endings
    {
        return Ok(true);
    }
    if !text_identical {
        return Ok(false);
    }
    if ext_metrics.serialized_bytes != loc_metrics.serialized_bytes {
        return Ok(true);
    }
    let mut ext_serialized = Vec::with_capacity(ext_metrics.serialized_bytes);
    external.write_to(&mut ext_serialized)?;
    let mut comparator = SliceMismatchWriter::new(&ext_serialized);
    local.write_to(&mut comparator)?;
    Ok(comparator.differs())
}

fn split_logical_lines(text: &str) -> Vec<&str> {
    if text.is_empty() {
        return Vec::new();
    }
    text.split('\n').collect()
}

fn compute_bounded_ops(ext: &[&str], loc: &[&str]) -> (Vec<EditOp>, bool) {
    let mut prefix = 0_usize;
    let min_len = ext.len().min(loc.len());
    while prefix < min_len && ext[prefix] == loc[prefix] {
        prefix += 1;
    }
    let mut suffix = 0_usize;
    while suffix < (min_len - prefix) && ext[ext.len() - 1 - suffix] == loc[loc.len() - 1 - suffix]
    {
        suffix += 1;
    }

    let mid_ext = &ext[prefix..ext.len() - suffix];
    let mid_loc = &loc[prefix..loc.len() - suffix];

    let mut ops = Vec::with_capacity(ext.len().max(loc.len()));
    for idx in 0..prefix {
        ops.push(EditOp::Equal(idx, idx));
    }

    let partial = if let Some(mid_ops) = myers_bounded(mid_ext, mid_loc, MAX_MYERS_EDIT_DISTANCE) {
        for op in mid_ops {
            ops.push(match op {
                EditOp::Equal(a, b) => EditOp::Equal(prefix + a, prefix + b),
                EditOp::Remove(a) => EditOp::Remove(prefix + a),
                EditOp::Add(b) => EditOp::Add(prefix + b),
            });
        }
        false
    } else {
        for op in fallback_bounded_ops(mid_ext, mid_loc) {
            ops.push(match op {
                EditOp::Equal(a, b) => EditOp::Equal(prefix + a, prefix + b),
                EditOp::Remove(a) => EditOp::Remove(prefix + a),
                EditOp::Add(b) => EditOp::Add(prefix + b),
            });
        }
        true
    };

    let ext_suffix_start = ext.len() - suffix;
    let loc_suffix_start = loc.len() - suffix;
    for offset in 0..suffix {
        ops.push(EditOp::Equal(
            ext_suffix_start + offset,
            loc_suffix_start + offset,
        ));
    }
    (ops, partial)
}

/// Linear-time bounded lookahead + indexed-anchor alignment used when Myers exceeds [`MAX_MYERS_EDIT_DISTANCE`].
/// Preserves unchanged lines across large insertions/deletions (even for repeated lines) and interleaves unresolved changes.
fn fallback_bounded_ops(ext: &[&str], loc: &[&str]) -> Vec<EditOp> {
    let ext_positions = line_positions(ext);
    let loc_positions = line_positions(loc);
    let mut ops = Vec::with_capacity(ext.len() + loc.len());
    let mut ext_idx = 0_usize;
    let mut loc_idx = 0_usize;
    while ext_idx < ext.len() && loc_idx < loc.len() {
        if ext[ext_idx] == loc[loc_idx] {
            ops.push(EditOp::Equal(ext_idx, loc_idx));
            ext_idx += 1;
            loc_idx += 1;
            continue;
        }
        let mut best: Option<(usize, usize)> = None;
        for step in 1..=FALLBACK_LOOKAHEAD_LINES {
            if ext_idx + step < ext.len() && ext[ext_idx + step] == loc[loc_idx] {
                best = Some((step, 0));
                break;
            }
            if loc_idx + step < loc.len() && ext[ext_idx] == loc[loc_idx + step] {
                best = Some((0, step));
                break;
            }
        }
        if best.is_none() {
            let ext_jump =
                next_position_after(&ext_positions, loc[loc_idx], ext_idx).map(|pos| pos - ext_idx);
            let loc_jump =
                next_position_after(&loc_positions, ext[ext_idx], loc_idx).map(|pos| pos - loc_idx);
            best = match (ext_jump, loc_jump) {
                (Some(skip_ext), Some(skip_loc)) => {
                    if skip_ext <= skip_loc {
                        Some((skip_ext, 0))
                    } else {
                        Some((0, skip_loc))
                    }
                }
                (Some(skip_ext), None) => Some((skip_ext, 0)),
                (None, Some(skip_loc)) => Some((0, skip_loc)),
                (None, None) => None,
            };
        }
        match best {
            Some((skip_ext, 0)) => {
                for offset in 0..skip_ext {
                    ops.push(EditOp::Remove(ext_idx + offset));
                }
                ext_idx += skip_ext;
            }
            Some((0, skip_loc)) => {
                for offset in 0..skip_loc {
                    ops.push(EditOp::Add(loc_idx + offset));
                }
                loc_idx += skip_loc;
            }
            _ => {
                ops.push(EditOp::Remove(ext_idx));
                ops.push(EditOp::Add(loc_idx));
                ext_idx += 1;
                loc_idx += 1;
            }
        }
    }
    while ext_idx < ext.len() {
        ops.push(EditOp::Remove(ext_idx));
        ext_idx += 1;
    }
    while loc_idx < loc.len() {
        ops.push(EditOp::Add(loc_idx));
        loc_idx += 1;
    }
    ops
}

fn line_positions<'a>(lines: &[&'a str]) -> HashMap<&'a str, Vec<usize>> {
    let mut positions: HashMap<&'a str, Vec<usize>> = HashMap::with_capacity(lines.len());
    for (idx, &line) in lines.iter().enumerate() {
        positions.entry(line).or_default().push(idx);
    }
    positions
}

fn next_position_after(
    positions: &HashMap<&str, Vec<usize>>,
    line: &str,
    after: usize,
) -> Option<usize> {
    let list = positions.get(line)?;
    let slot = list.partition_point(|&pos| pos <= after);
    list.get(slot).copied()
}

fn myers_bounded(ext: &[&str], loc: &[&str], max_distance: usize) -> Option<Vec<EditOp>> {
    let ext_len = ext.len();
    let loc_len = loc.len();
    if ext_len == 0 {
        return Some((0..loc_len).map(EditOp::Add).collect());
    }
    if loc_len == 0 {
        return Some((0..ext_len).map(EditOp::Remove).collect());
    }
    let limit = max_distance.min(ext_len + loc_len);
    let offset = limit;
    let mut frontier = vec![0_usize; 2 * limit + 1];
    let mut trace: Vec<Vec<usize>> = Vec::new();

    for distance in 0..=limit {
        trace.push(frontier.clone());
        let dist_isize = isize::try_from(distance).ok()?;
        let mut diag = -dist_isize;
        while diag <= dist_isize {
            let diag_idx = usize::try_from(diag + isize::try_from(offset).ok()?).ok()?;
            let mut ext_pos = if diag == -dist_isize
                || (diag != dist_isize && frontier[diag_idx - 1] < frontier[diag_idx + 1])
            {
                frontier[diag_idx + 1]
            } else {
                frontier[diag_idx - 1] + 1
            };
            let mut loc_pos = usize::try_from(isize::try_from(ext_pos).ok()? - diag).ok()?;
            while ext_pos < ext_len && loc_pos < loc_len && ext[ext_pos] == loc[loc_pos] {
                ext_pos += 1;
                loc_pos += 1;
            }
            frontier[diag_idx] = ext_pos;
            if ext_pos >= ext_len && loc_pos >= loc_len {
                return Some(backtrack_myers(&trace, ext_len, loc_len, offset));
            }
            diag += 2;
        }
    }
    None
}

fn backtrack_myers(
    trace: &[Vec<usize>],
    mut ext_pos: usize,
    mut loc_pos: usize,
    offset: usize,
) -> Vec<EditOp> {
    let mut reversed = Vec::with_capacity(ext_pos + loc_pos);
    let offset_isize = isize::try_from(offset).expect("bounded offset fits isize");
    for distance in (1..trace.len()).rev() {
        let frontier = &trace[distance];
        let dist_isize = isize::try_from(distance).expect("bounded edit distance fits isize");
        let diag = isize::try_from(ext_pos).expect("bounded x fits isize")
            - isize::try_from(loc_pos).expect("bounded y fits isize");
        let diag_idx = usize::try_from(diag + offset_isize).expect("valid Myers diagonal index");
        let prev_diag = if diag == -dist_isize
            || (diag != dist_isize && frontier[diag_idx - 1] < frontier[diag_idx + 1])
        {
            diag + 1
        } else {
            diag - 1
        };
        let prev_ext = frontier
            [usize::try_from(prev_diag + offset_isize).expect("valid Myers diagonal index")];
        let prev_loc = usize::try_from(
            isize::try_from(prev_ext).expect("bounded prev_ext fits isize") - prev_diag,
        )
        .expect("nonnegative Myers y");

        while ext_pos > prev_ext && loc_pos > prev_loc {
            ext_pos -= 1;
            loc_pos -= 1;
            reversed.push(EditOp::Equal(ext_pos, loc_pos));
        }
        if ext_pos == prev_ext {
            loc_pos -= 1;
            reversed.push(EditOp::Add(loc_pos));
        } else {
            ext_pos -= 1;
            reversed.push(EditOp::Remove(ext_pos));
        }
    }
    while ext_pos > 0 && loc_pos > 0 {
        ext_pos -= 1;
        loc_pos -= 1;
        reversed.push(EditOp::Equal(ext_pos, loc_pos));
    }
    reversed.reverse();
    reversed
}

fn bound_line_text(line: &str) -> (String, bool) {
    if line.len() <= MAX_DIFF_LINE_BYTES {
        return (line.to_owned(), false);
    }
    let mut end = MAX_DIFF_LINE_BYTES;
    while end > 0 && !line.is_char_boundary(end) {
        end -= 1;
    }
    (line[..end].to_owned(), true)
}

/// Caps unpaired trailing edits after the final aligned `Equal` when the opposite aligned
/// buffer actually hit unread input (UTF-16 cap or lookahead ceiling), preserving genuine
/// paired replacements at the cutoff while preventing phantom deletions or additions.
fn trim_truncated_tail(
    ops: &mut Vec<EditOp>,
    ext_align_truncated: bool,
    loc_align_truncated: bool,
) {
    let last_equal_end = ops
        .iter()
        .rposition(|op| matches!(op, EditOp::Equal(_, _)))
        .map_or(0, |idx| idx + 1);
    if last_equal_end == 0 || last_equal_end >= ops.len() {
        return;
    }
    let tail = &ops[last_equal_end..];
    let tail_removes = tail
        .iter()
        .filter(|op| matches!(op, EditOp::Remove(_)))
        .count();
    let tail_adds = tail
        .iter()
        .filter(|op| matches!(op, EditOp::Add(_)))
        .count();
    if loc_align_truncated && tail_removes > tail_adds {
        let mut remaining_removes = tail_adds;
        let mut write = last_equal_end;
        for read in last_equal_end..ops.len() {
            let op = ops[read];
            let keep = match op {
                EditOp::Remove(_) => {
                    if remaining_removes > 0 {
                        remaining_removes -= 1;
                        true
                    } else {
                        false
                    }
                }
                EditOp::Add(_) | EditOp::Equal(_, _) => true,
            };
            if keep {
                ops[write] = op;
                write += 1;
            }
        }
        ops.truncate(write);
    } else if ext_align_truncated && tail_adds > tail_removes {
        let mut remaining_adds = tail_removes;
        let mut write = last_equal_end;
        for read in last_equal_end..ops.len() {
            let op = ops[read];
            let keep = match op {
                EditOp::Add(_) => {
                    if remaining_adds > 0 {
                        remaining_adds -= 1;
                        true
                    } else {
                        false
                    }
                }
                EditOp::Remove(_) | EditOp::Equal(_, _) => true,
            };
            if keep {
                ops[write] = op;
                write += 1;
            }
        }
        ops.truncate(write);
    }
}

/// Takes a contiguous aligned prefix until both sides have reached their preview limits
/// outside an active change run, keeping `(ext_line, loc_line)` strictly continuous and
/// preventing a boundary replacement from being split in half.
fn project_ops_to_preview(
    ops: &[EditOp],
    ext_limit: usize,
    loc_limit: usize,
) -> (Vec<PositionedOp>, usize) {
    let mut projected = Vec::with_capacity(ops.len().min(ext_limit + loc_limit));
    let mut ext_line = 0_usize;
    let mut loc_line = 0_usize;
    let mut in_change_run = false;
    for &op in ops {
        let is_equal = matches!(op, EditOp::Equal(_, _));
        if ext_line >= ext_limit && loc_line >= loc_limit && (!in_change_run || is_equal) {
            break;
        }
        in_change_run = !is_equal;
        projected.push(PositionedOp {
            op,
            ext_line,
            loc_line,
        });
        match op {
            EditOp::Equal(_, _) => {
                ext_line += 1;
                loc_line += 1;
            }
            EditOp::Remove(_) => {
                ext_line += 1;
            }
            EditOp::Add(_) => {
                loc_line += 1;
            }
        }
    }
    (projected, ext_line.max(loc_line))
}

fn build_hunks(ext: &[&str], loc: &[&str], ops: &[PositionedOp]) -> (Vec<DiffHunk>, bool) {
    let change_indices: Vec<usize> = ops
        .iter()
        .enumerate()
        .filter_map(|(idx, entry)| (!matches!(entry.op, EditOp::Equal(_, _))).then_some(idx))
        .collect();
    if change_indices.is_empty() {
        return (Vec::new(), false);
    }

    let mut ranges: Vec<(usize, usize)> = Vec::new();
    for change in change_indices {
        let start = change.saturating_sub(DIFF_CONTEXT_LINES);
        let end = (change + DIFF_CONTEXT_LINES + 1).min(ops.len());
        if let Some(last) = ranges.last_mut()
            && start <= last.1
        {
            last.1 = last.1.max(end);
        } else {
            ranges.push((start, end));
        }
    }

    let total_added: usize = ranges
        .iter()
        .map(|&(start, end)| {
            ops[start..end]
                .iter()
                .filter(|entry| matches!(entry.op, EditOp::Add(_)))
                .count()
        })
        .sum();
    let mut reserved_adds = total_added.min(MAX_DIFF_OUTPUT_LINES / 2);

    let mut hunks = Vec::with_capacity(ranges.len());
    let mut emitted_lines = 0_usize;
    let mut truncated = false;
    for (range_start, range_end) in ranges {
        if emitted_lines >= MAX_DIFF_OUTPUT_LINES {
            truncated = true;
            break;
        }
        let mut first_emitted: Option<PositionedOp> = None;
        let mut lines = Vec::new();
        for &entry in &ops[range_start..range_end] {
            if emitted_lines >= MAX_DIFF_OUTPUT_LINES {
                truncated = true;
                break;
            }
            let (kind, raw, is_add) = match entry.op {
                EditOp::Add(b) => (DiffLineKind::Added, loc[b], true),
                EditOp::Remove(a) => (DiffLineKind::Removed, ext[a], false),
                EditOp::Equal(a, _) => (DiffLineKind::Context, ext[a], false),
            };
            if !is_add && emitted_lines + reserved_adds >= MAX_DIFF_OUTPUT_LINES {
                truncated = true;
                if let Some(start_entry) = first_emitted.take() {
                    hunks.push(DiffHunk {
                        external_start_line: start_entry.ext_line + 1,
                        local_start_line: start_entry.loc_line + 1,
                        lines: std::mem::take(&mut lines),
                    });
                }
                continue;
            }
            let (text, line_truncated) = bound_line_text(raw);
            if line_truncated {
                truncated = true;
            }
            first_emitted.get_or_insert(entry);
            lines.push(DiffLine { kind, text });
            emitted_lines += 1;
            if is_add {
                reserved_adds = reserved_adds.saturating_sub(1);
            }
        }
        if let Some(start_entry) = first_emitted {
            hunks.push(DiffHunk {
                external_start_line: start_entry.ext_line + 1,
                local_start_line: start_entry.loc_line + 1,
                lines,
            });
        }
    }
    (hunks, truncated)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::Document;

    fn snapshot_from_bytes(name: &str, bytes: &[u8]) -> DocumentSnapshot {
        let mut file = std::fs::File::options()
            .read(true)
            .write(true)
            .create(true)
            .truncate(true)
            .open(std::env::temp_dir().join(name))
            .expect("create temp diff file");
        std::io::Write::write_all(&mut file, bytes).expect("write diff bytes");
        let doc = Document::open_source(file).expect("open diff doc");
        doc.snapshot()
    }

    #[test]
    fn reports_format_only_changes_without_inventing_text_hunks() {
        let ext_snap =
            snapshot_from_bytes("beautyxt_diff_ext_fmt.txt", b"\xEF\xBB\xBFhello\r\nworld");
        let loc_snap = snapshot_from_bytes("beautyxt_diff_loc_fmt.txt", b"hello\nworld");

        let diff = diff_snapshots(&ext_snap, &loc_snap).expect("compute diff");
        assert!(diff.text_identical);
        assert!(diff.format_differs);
        assert!(!diff.is_partial);
        assert!(diff.external_has_bom);
        assert!(!diff.local_has_bom);
        assert_eq!(diff.external_line_endings, LINE_ENDING_CRLF_BIT);
        assert_eq!(diff.local_line_endings, LINE_ENDING_LF_BIT);
        assert!(diff.hunks.is_empty());
        assert_eq!(diff.added_lines, 0);
        assert_eq!(diff.removed_lines, 0);
    }

    #[test]
    fn detects_mixed_line_ending_position_changes_when_masks_and_text_match() {
        let ext_snap = snapshot_from_bytes("beautyxt_diff_mixed_ext.txt", b"a\nb\r\nc");
        let loc_snap = snapshot_from_bytes("beautyxt_diff_mixed_loc.txt", b"a\r\nb\nc");

        let diff = diff_snapshots(&ext_snap, &loc_snap).expect("compute mixed diff");
        assert!(diff.text_identical);
        assert!(diff.format_differs);
        assert_eq!(diff.external_line_endings, diff.local_line_endings);
        assert_eq!(diff.external_bytes, diff.local_bytes);
        assert!(diff.hunks.is_empty());
    }

    #[test]
    fn groups_context_around_multiple_changes() {
        let ext = Document::from_text("l1\nl2\nl3\nl4\nl5\nl6\nl7\nl8\nl9\nl10\nl11\nl12");
        let loc = Document::from_text("l1\nl2-ext\nl3\nl4\nl5\nl6\nl7\nl8\nl9\nl10\nl11-loc\nl12");
        let diff =
            diff_snapshots(&ext.snapshot(), &loc.snapshot()).expect("compute multi-hunk diff");
        assert!(!diff.text_identical);
        assert!(!diff.is_partial);
        assert_eq!(diff.added_lines, 2);
        assert_eq!(diff.removed_lines, 2);
        assert_eq!(diff.hunks.len(), 2);
        assert_eq!(diff.hunks[0].external_start_line, 1);
        assert_eq!(diff.hunks[1].external_start_line, 8);
    }

    #[test]
    fn fallback_preserves_unchanged_lines_and_shows_both_sides_beyond_myers_budget() {
        let mut ext_lines = Vec::new();
        let mut loc_lines = Vec::new();
        for idx in 0..521 {
            if idx % 2 == 0 {
                ext_lines.push(format!("same-{idx}"));
                loc_lines.push(format!("same-{idx}"));
            } else {
                ext_lines.push(format!("ext-{idx}"));
                loc_lines.push(format!("loc-{idx}"));
            }
        }
        let ext = Document::from_text(&ext_lines.join("\n"));
        let loc = Document::from_text(&loc_lines.join("\n"));
        let diff =
            diff_snapshots(&ext.snapshot(), &loc.snapshot()).expect("compute alternating diff");
        assert!(diff.is_partial);
        assert!(!diff.text_identical);
        assert_eq!(diff.removed_lines, 260);
        assert_eq!(diff.added_lines, 260);
        let all_lines: Vec<&DiffLine> = diff.hunks.iter().flat_map(|h| h.lines.iter()).collect();
        assert!(all_lines.iter().any(|l| l.kind == DiffLineKind::Context));
        assert!(all_lines.iter().any(|l| l.kind == DiffLineKind::Removed));
        assert!(all_lines.iter().any(|l| l.kind == DiffLineKind::Added));
    }

    #[test]
    fn partial_prefix_match_sets_text_identical_false() {
        let mut prefix = String::new();
        for idx in 0..=MAX_DIFF_LINES {
            use std::fmt::Write as _;
            let _ = writeln!(&mut prefix, "line-{idx}");
        }
        let ext = Document::from_text(&format!("{prefix}ext-tail"));
        let loc = Document::from_text(&format!("{prefix}loc-tail"));
        let diff = diff_snapshots(&ext.snapshot(), &loc.snapshot()).expect("compute partial diff");
        assert!(diff.is_partial);
        assert!(!diff.text_identical);
        assert_eq!(diff.compared_lines, MAX_DIFF_LINES);
        assert!(diff.hunks.is_empty());
    }

    #[test]
    fn inserting_top_line_in_long_file_does_not_invent_cutoff_deletion() {
        let mut base = String::new();
        for idx in 1..=3000 {
            use std::fmt::Write as _;
            let _ = writeln!(&mut base, "line-{idx}");
        }
        let ext = Document::from_text(&base);
        let loc = Document::from_text(&format!("inserted-top\n{base}"));
        let diff = diff_snapshots(&ext.snapshot(), &loc.snapshot()).expect("compute shifted diff");
        assert!(diff.is_partial);
        assert_eq!(diff.added_lines, 1);
        assert_eq!(diff.removed_lines, 0);
        assert_eq!(diff.hunks.len(), 1);
        assert!(
            diff.hunks[0]
                .lines
                .iter()
                .all(|line| line.kind != DiffLineKind::Removed)
        );
    }

    #[test]
    fn large_deletion_hunk_still_reserves_display_space_for_later_addition_hunk() {
        let mut ext_lines = Vec::new();
        let mut loc_lines = Vec::new();
        for idx in 0..560 {
            ext_lines.push(format!("deleted-{idx}"));
        }
        for idx in 0..20 {
            ext_lines.push(format!("shared-{idx}"));
            loc_lines.push(format!("shared-{idx}"));
        }
        loc_lines.push("local-addition-after-deletions".to_owned());
        let ext = Document::from_text(&ext_lines.join("\n"));
        let loc = Document::from_text(&loc_lines.join("\n"));
        let diff = diff_snapshots(&ext.snapshot(), &loc.snapshot()).expect("compute multi-hunk");
        assert!(diff.is_partial);
        assert_eq!(diff.added_lines, 1);
        let all_lines: Vec<&DiffLine> = diff.hunks.iter().flat_map(|h| h.lines.iter()).collect();
        assert!(all_lines.iter().any(|l| {
            l.kind == DiffLineKind::Added && l.text == "local-addition-after-deletions"
        }));
    }

    #[test]
    fn inserting_before_last_previewed_line_does_not_report_false_deletion() {
        let mut ext_lines = Vec::with_capacity(3000);
        let mut loc_lines = Vec::with_capacity(3001);
        for idx in 1..=3000 {
            let line = format!("line-{idx}");
            if idx == MAX_DIFF_LINES {
                loc_lines.push("inserted-right-before-cutoff".to_owned());
            }
            ext_lines.push(line.clone());
            loc_lines.push(line);
        }
        let ext = Document::from_text(&ext_lines.join("\n"));
        let loc = Document::from_text(&loc_lines.join("\n"));
        let diff = diff_snapshots(&ext.snapshot(), &loc.snapshot()).expect("compute cutoff insert");
        assert!(diff.is_partial);
        assert_eq!(diff.added_lines, 1);
        assert_eq!(diff.removed_lines, 0);
        assert_eq!(diff.hunks.len(), 1);
        assert!(
            diff.hunks[0]
                .lines
                .iter()
                .all(|line| line.kind != DiffLineKind::Removed)
        );
    }

    #[test]
    fn large_deletion_before_repeated_lines_still_aligns_and_shows_later_addition() {
        let mut ext_lines = Vec::new();
        let mut loc_lines = Vec::new();
        for idx in 0..600 {
            ext_lines.push(format!("distinct-{idx}"));
        }
        for _ in 0..1000 {
            ext_lines.push("repeated-shared-line".to_owned());
            loc_lines.push("repeated-shared-line".to_owned());
        }
        loc_lines.push("genuine-local-addition".to_owned());
        let ext = Document::from_text(&ext_lines.join("\n"));
        let loc = Document::from_text(&loc_lines.join("\n"));
        let diff = diff_snapshots(&ext.snapshot(), &loc.snapshot()).expect("compute repeated diff");
        assert!(diff.is_partial);
        assert_eq!(diff.removed_lines, 600);
        assert_eq!(diff.added_lines, 1);
        let all_lines: Vec<&DiffLine> = diff.hunks.iter().flat_map(|h| h.lines.iter()).collect();
        assert!(
            all_lines
                .iter()
                .any(|l| { l.kind == DiffLineKind::Added && l.text == "genuine-local-addition" })
        );
        let add_hunk = diff
            .hunks
            .iter()
            .find(|h| h.lines.iter().any(|l| l.text == "genuine-local-addition"))
            .expect("addition hunk present");
        assert_eq!(add_hunk.local_start_line, 1001);
    }

    #[test]
    fn replacement_on_last_previewed_line_is_preserved_even_with_repeated_suffix() {
        let mut ext_lines = Vec::with_capacity(2052);
        let mut loc_lines = Vec::with_capacity(2052);
        for idx in 1..MAX_DIFF_LINES {
            let line = format!("shared-{idx}");
            ext_lines.push(line.clone());
            loc_lines.push(line);
        }
        ext_lines.extend(["old", "old", "new", "tail"].into_iter().map(str::to_owned));
        loc_lines.extend(["new", "old", "new", "tail"].into_iter().map(str::to_owned));
        let ext = Document::from_text(&ext_lines.join("\n"));
        let loc = Document::from_text(&loc_lines.join("\n"));
        let diff =
            diff_snapshots(&ext.snapshot(), &loc.snapshot()).expect("compute boundary replace");
        assert!(diff.is_partial);
        assert_eq!(diff.removed_lines, 1);
        assert_eq!(diff.added_lines, 1);
        let all_lines: Vec<&DiffLine> = diff.hunks.iter().flat_map(|h| h.lines.iter()).collect();
        assert!(
            all_lines
                .iter()
                .any(|l| l.kind == DiffLineKind::Removed && l.text == "old")
        );
        assert!(
            all_lines
                .iter()
                .any(|l| l.kind == DiffLineKind::Added && l.text == "new")
        );
    }

    #[test]
    fn large_prepend_and_utf16_cap_prepend_do_not_invent_deletions() {
        // 65-line prepend (> 64-line lookahead) in a 3,000-line file
        let mut base_lines = Vec::with_capacity(3000);
        for idx in 1..=3000 {
            base_lines.push(format!("base-{idx}"));
        }
        let mut loc_lines = Vec::with_capacity(3065);
        for idx in 1..=65 {
            loc_lines.push(format!("prepended-{idx}"));
        }
        loc_lines.extend(base_lines.iter().cloned());
        let ext = Document::from_text(&base_lines.join("\n"));
        let loc = Document::from_text(&loc_lines.join("\n"));
        let diff = diff_snapshots(&ext.snapshot(), &loc.snapshot()).expect("compute 65-line shift");
        assert!(diff.is_partial);
        assert_eq!(diff.added_lines, 65);
        assert_eq!(diff.removed_lines, 0);

        // Prepending a 4,096-unit line when UTF-16 cap (256 Ki) truncates both files
        let mut long_base = Vec::with_capacity(1200);
        for idx in 0..1200 {
            long_base.push(format!("row-{idx:04}-{}", "x".repeat(250)));
        }
        let mut long_loc = vec!["p".repeat(4096)];
        long_loc.extend(long_base.iter().cloned());
        let ext2 = Document::from_text(&long_base.join("\n"));
        let loc2 = Document::from_text(&long_loc.join("\n"));
        let diff2 =
            diff_snapshots(&ext2.snapshot(), &loc2.snapshot()).expect("compute utf16 cap shift");
        assert!(diff2.is_partial);
        assert_eq!(diff2.added_lines, 1);
        assert_eq!(diff2.removed_lines, 0);
    }

    #[test]
    fn hunk_line_number_stays_exact_when_projection_drops_boundary_matches() {
        let mut ext_lines = Vec::with_capacity(2200);
        for idx in 1..=2200 {
            ext_lines.push(format!("line-{idx}"));
        }
        // Delete 20 lines at top so ext[20..2048] aligns with loc[0..2028], then insert at loc line 2033
        let mut loc_lines: Vec<String> = ext_lines[20..2052].to_vec();
        loc_lines.push("inserted-at-2033".to_owned());
        loc_lines.extend(ext_lines[2052..].iter().cloned());
        let ext = Document::from_text(&ext_lines.join("\n"));
        let loc = Document::from_text(&loc_lines.join("\n"));
        let diff =
            diff_snapshots(&ext.snapshot(), &loc.snapshot()).expect("compute projected header");
        let add_hunk = diff
            .hunks
            .iter()
            .find(|h| h.lines.iter().any(|l| l.text == "inserted-at-2033"))
            .expect("addition hunk present");
        let add_offset = add_hunk
            .lines
            .iter()
            .position(|l| l.text == "inserted-at-2033")
            .unwrap();
        let context_before = add_hunk.lines[..add_offset]
            .iter()
            .filter(|l| l.kind != DiffLineKind::Removed)
            .count();
        assert_eq!(add_hunk.local_start_line + context_before, 2033);
    }

    fn assert_hunks_continuous(ext_lines: &[String], loc_lines: &[String], hunks: &[DiffHunk]) {
        for hunk in hunks {
            let mut ext_pos = hunk.external_start_line - 1;
            let mut loc_pos = hunk.local_start_line - 1;
            for line in &hunk.lines {
                match line.kind {
                    DiffLineKind::Context => {
                        assert_eq!(bound_line_text(&ext_lines[ext_pos]).0, line.text);
                        assert_eq!(bound_line_text(&loc_lines[loc_pos]).0, line.text);
                        ext_pos += 1;
                        loc_pos += 1;
                    }
                    DiffLineKind::Removed => {
                        assert_eq!(bound_line_text(&ext_lines[ext_pos]).0, line.text);
                        ext_pos += 1;
                    }
                    DiffLineKind::Added => {
                        assert_eq!(bound_line_text(&loc_lines[loc_pos]).0, line.text);
                        loc_pos += 1;
                    }
                }
            }
        }
    }

    #[test]
    fn genuine_deletions_at_line_cutoff_survive_when_aligned_buffer_read_full_local_file() {
        let ext_lines: Vec<String> = (1..=MAX_DIFF_LINES)
            .map(|idx| format!("unique-{idx}"))
            .collect();
        let mut loc_lines: Vec<String> = (1..=10).map(|idx| format!("prepended-{idx}")).collect();
        loc_lines.extend(ext_lines[..MAX_DIFF_LINES - 8].iter().cloned());
        let ext = Document::from_text(&ext_lines.join("\n"));
        let loc = Document::from_text(&loc_lines.join("\n"));
        let diff = diff_snapshots(&ext.snapshot(), &loc.snapshot()).expect("compute tail delete");
        assert_eq!(diff.added_lines, 10);
        assert_eq!(diff.removed_lines, 8);
        assert_hunks_continuous(&ext_lines, &loc_lines, &diff.hunks);
    }

    #[test]
    fn replacement_at_utf16_cap_does_not_produce_phantom_deletions() {
        let ext_lines: Vec<String> = (0..1200)
            .map(|idx| format!("row-{idx:04}-{}", "x".repeat(250)))
            .collect();
        let mut loc_lines = vec!["p".repeat(4096)];
        loc_lines.extend(ext_lines.iter().cloned());
        // Determine the last complete local line before MAX_DIFF_UTF16_UNITS and replace it.
        let probe = Document::from_text(&loc_lines.join("\n"));
        let prefix = probe
            .snapshot()
            .tree
            .reader()
            .bounded_text_prefix(
                Utf16Range::new(0, probe.snapshot().metrics().utf16_units),
                MAX_DIFF_UTF16_UNITS,
            )
            .expect("probe utf16 prefix");
        let complete_local_len = split_logical_lines(&prefix.text).len() - 1;
        let replaced_idx = complete_local_len - 1;
        loc_lines[replaced_idx] = format!("edited-{replaced_idx:04}-{}", "y".repeat(247));

        let ext = Document::from_text(&ext_lines.join("\n"));
        let loc = Document::from_text(&loc_lines.join("\n"));
        let diff = diff_snapshots(&ext.snapshot(), &loc.snapshot()).expect("compute utf16 replace");
        assert!(diff.is_partial);
        assert_eq!(diff.added_lines, 2);
        assert_eq!(diff.removed_lines, 1);
        assert_hunks_continuous(&ext_lines, &loc_lines, &diff.hunks);
    }

    #[test]
    fn boundary_replacement_under_prepend_stays_complete_and_continuous() {
        let ext_lines: Vec<String> = (1..=2200).map(|idx| format!("line-{idx}")).collect();
        let mut loc_lines: Vec<String> = (1..=20).map(|idx| format!("prepend-{idx}")).collect();
        loc_lines.extend(ext_lines.iter().cloned());
        // External line 2,032 (0-based 2,031) is now local line 2,052 (0-based 2,051).
        loc_lines[2051] = "replaced-line-2032".to_owned();

        let ext = Document::from_text(&ext_lines.join("\n"));
        let loc = Document::from_text(&loc_lines.join("\n"));
        let diff =
            diff_snapshots(&ext.snapshot(), &loc.snapshot()).expect("compute boundary replace");
        assert!(diff.is_partial);
        assert_eq!(diff.added_lines, 21);
        assert_eq!(diff.removed_lines, 1);
        assert_hunks_continuous(&ext_lines, &loc_lines, &diff.hunks);
        let all_lines: Vec<&DiffLine> = diff.hunks.iter().flat_map(|h| h.lines.iter()).collect();
        assert!(
            all_lines
                .iter()
                .any(|l| { l.kind == DiffLineKind::Removed && l.text == "line-2032" })
        );
        assert!(
            all_lines
                .iter()
                .any(|l| { l.kind == DiffLineKind::Added && l.text == "replaced-line-2032" })
        );
    }

    #[test]
    fn multi_line_and_shifted_replacements_crossing_line_cutoff_stay_paired() {
        // Replace lines 2048..=2051 (4 lines) crossing MAX_DIFF_LINES.
        let ext_lines: Vec<String> = (1..=2200).map(|idx| format!("line-{idx}")).collect();
        let mut loc_lines = ext_lines.clone();
        for (offset, slot) in loc_lines[2047..2051].iter_mut().enumerate() {
            *slot = format!("edited-{}", 2048 + offset);
        }
        let ext = Document::from_text(&ext_lines.join("\n"));
        let loc = Document::from_text(&loc_lines.join("\n"));
        let diff = diff_snapshots(&ext.snapshot(), &loc.snapshot()).expect("compute multi replace");
        assert!(diff.is_partial);
        assert_eq!(diff.removed_lines, 4);
        assert_eq!(diff.added_lines, 4);
        assert_hunks_continuous(&ext_lines, &loc_lines, &diff.hunks);

        // Prepend 1 line and replace original line 2048 (0-based 2047 -> local 2048).
        let mut shifted_loc = vec!["prepended-top".to_owned()];
        shifted_loc.extend(ext_lines.iter().cloned());
        shifted_loc[2048] = "replaced-2048".to_owned();
        let loc2 = Document::from_text(&shifted_loc.join("\n"));
        let diff2 =
            diff_snapshots(&ext.snapshot(), &loc2.snapshot()).expect("compute shifted replace");
        assert!(diff2.is_partial);
        assert_eq!(diff2.removed_lines, 1);
        assert_eq!(diff2.added_lines, 2);
        assert_hunks_continuous(&ext_lines, &shifted_loc, &diff2.hunks);
        let all_lines: Vec<&DiffLine> = diff2.hunks.iter().flat_map(|h| h.lines.iter()).collect();
        assert!(
            all_lines
                .iter()
                .any(|l| { l.kind == DiffLineKind::Added && l.text == "replaced-2048" })
        );
    }
}
