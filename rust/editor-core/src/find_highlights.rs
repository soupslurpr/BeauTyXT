//! Finds display-only match coverage without scanning beyond a bounded source window.

use super::{
    CASE_INSENSITIVE_SCALAR_UTF16_UNITS, DocumentError, DocumentMetrics, FindDirection,
    FindRequest, PieceTree, Utf16Range, compile_case_insensitive_find, next_scalar_byte_offset,
    read_find_text, validate_find_request,
};
use memchr::memmem;

/// Limits one highlight request to the largest editable window or render block.
pub const MAX_FIND_HIGHLIGHT_UTF16_UNITS: usize = 32 * 1024;

/// Requests all literal-match coverage intersecting one displayed source range.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct FindHighlightRequest<'query> {
    /// Stores the document revision required by the caller.
    pub revision: u64,
    /// Stores the nonempty normalized literal query.
    pub query: &'query str,
    /// Disables Unicode simple case folding when true.
    pub match_case: bool,
    /// Stores the scalar-aligned, bounded range receiving highlights.
    pub range: Utf16Range,
}

/// Includes boundary-crossing matches, merging their coverage before returning it.
pub(super) fn find_highlights_in_tree(
    tree: &PieceTree,
    metrics: DocumentMetrics,
    request: FindHighlightRequest<'_>,
) -> Result<Vec<Utf16Range>, DocumentError> {
    if request.revision != metrics.revision {
        return Err(DocumentError::StaleRevision {
            expected: request.revision,
            actual: metrics.revision,
        });
    }
    let query_utf16_units = validate_find_request(
        FindRequest {
            revision: request.revision,
            query: request.query,
            match_case: request.match_case,
            candidate_range: request.range,
            direction: FindDirection::Forward,
            max_candidate_utf16_units: MAX_FIND_HIGHLIGHT_UTF16_UNITS,
        },
        metrics.utf16_units,
    )?;
    if request.range.len() > MAX_FIND_HIGHLIGHT_UTF16_UNITS {
        return Err(DocumentError::InvalidFindRequest(
            "highlight range exceeds its limit",
        ));
    }
    let mut reader = tree.reader();
    reader.position_at_utf16(request.range.start)?;
    reader.position_at_utf16(request.range.end)?;
    if request.range.is_empty() {
        return Ok(Vec::new());
    }
    let maximum_match_units = if request.match_case {
        query_utf16_units
    } else {
        request.query.chars().count() * CASE_INSENSITIVE_SCALAR_UTF16_UNITS
    };
    let context_units = maximum_match_units - 1;
    let start =
        reader.scalar_boundary_at_or_before(request.range.start.saturating_sub(context_units))?;
    let end = reader.scalar_boundary_at_or_after(
        request
            .range
            .end
            .saturating_add(context_units)
            .min(metrics.utf16_units),
    )?;
    let text = read_find_text(&mut reader, Utf16Range::new(start, end))?;
    let folded = if request.match_case {
        None
    } else {
        Some(compile_case_insensitive_find(request.query)?)
    };
    let exact = memmem::Finder::new(request.query.as_bytes());
    let mut highlights: Vec<Utf16Range> = Vec::new();
    let mut byte_offset = 0;
    let mut utf16_offset = start;
    while byte_offset < text.len() {
        let matched = if let Some(matcher) = &folded {
            matcher
                .find_at(&text, byte_offset)
                .map(|found| found.range())
        } else {
            exact.find(&text.as_bytes()[byte_offset..]).map(|offset| {
                let start = byte_offset + offset;
                start..start + request.query.len()
            })
        };
        let Some(matched) = matched else { break };
        utf16_offset += text[byte_offset..matched.start].encode_utf16().count();
        if utf16_offset >= request.range.end {
            break;
        }
        let match_end = utf16_offset + text[matched.clone()].encode_utf16().count();
        if match_end > request.range.start {
            let clipped = Utf16Range::new(
                utf16_offset.max(request.range.start),
                match_end.min(request.range.end),
            );
            if let Some(previous) = highlights
                .last_mut()
                .filter(|last| last.end >= clipped.start)
            {
                previous.end = previous.end.max(clipped.end);
            } else {
                highlights.push(clipped);
            }
            if clipped.end == request.range.end {
                break;
            }
        }
        // Advancing one scalar preserves overlapping matches, as Next does.
        byte_offset = next_scalar_byte_offset(&text, matched.start)?;
        utf16_offset += text[matched.start..byte_offset].encode_utf16().count();
    }
    Ok(highlights)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{Document, MAX_FIND_CANDIDATE_UTF16_UNITS};

    #[test]
    fn matches_navigation_coverage_at_every_display_boundary() {
        for (text, queries) in [
            ("banana BANANA", vec!["a", "ana", "BAN", "missing"]),
            ("\u{212a}kK ſsS Σςσ İıiI", vec!["k", "s", "σ", "i"]),
            ("😀a😀a😀 𐐀𐐨", vec!["😀a😀", "a", "𐐀"]),
            ("a\nb\na\nb", vec!["a\nb", "\n", "b\na"]),
        ] {
            let document = Document::from_text(text);
            let length = document.metrics().utf16_units;
            let boundaries: Vec<_> = (0..=text.len())
                .filter(|offset| text.is_char_boundary(*offset))
                .map(|offset| text[..offset].encode_utf16().count())
                .collect();
            for query in queries {
                for match_case in [false, true] {
                    let mut expected = vec![false; length];
                    for pair in boundaries.windows(2) {
                        if let Some(found) = document
                            .find(FindRequest {
                                revision: 0,
                                query,
                                match_case,
                                candidate_range: Utf16Range::new(pair[0], pair[1]),
                                direction: FindDirection::Forward,
                                max_candidate_utf16_units: MAX_FIND_CANDIDATE_UTF16_UNITS,
                            })
                            .unwrap()
                            .matched
                        {
                            expected[found.range.start..found.range.end].fill(true);
                        }
                    }
                    for &start in &boundaries {
                        for &end in boundaries.iter().filter(|end| **end >= start) {
                            let ranges = document
                                .find_highlights(FindHighlightRequest {
                                    revision: 0,
                                    query,
                                    match_case,
                                    range: Utf16Range::new(start, end),
                                })
                                .unwrap();
                            let mut actual = vec![false; end - start];
                            for range in &ranges {
                                assert!(range.start >= start && range.end <= end);
                                actual[range.start - start..range.end - start].fill(true);
                            }
                            assert!(ranges.windows(2).all(|pair| pair[0].end < pair[1].start));
                            assert_eq!(
                                actual,
                                expected[start..end],
                                "{text:?}, {query:?}, {match_case}, {start}..{end}"
                            );
                        }
                    }
                }
            }
        }
    }

    #[test]
    fn merges_dense_matches_and_keeps_work_bounded() {
        let document = Document::from_text(&"a".repeat(MAX_FIND_HIGHLIGHT_UTF16_UNITS * 8));
        for query in ["a", "aaa", &"a".repeat(4096)] {
            let range = Utf16Range::new(20_000, 20_000 + MAX_FIND_HIGHLIGHT_UTF16_UNITS);
            assert_eq!(
                document
                    .find_highlights(FindHighlightRequest {
                        revision: 0,
                        query,
                        match_case: true,
                        range,
                    })
                    .unwrap(),
                vec![range]
            );
        }
        assert!(
            document
                .find_highlights(FindHighlightRequest {
                    revision: 0,
                    query: "a",
                    match_case: false,
                    range: Utf16Range::new(0, MAX_FIND_HIGHLIGHT_UTF16_UNITS + 1),
                })
                .is_err()
        );
    }

    #[test]
    fn validates_ranges_and_retains_snapshot_revision() {
        let mut document = Document::from_text("😀one ONE");
        let snapshot = document.snapshot();
        let request = FindHighlightRequest {
            revision: 0,
            query: "one",
            match_case: false,
            range: Utf16Range::new(0, 9),
        };
        document.replace(0, Utf16Range::new(2, 5), "two").unwrap();
        assert!(document.find_highlights(request).is_err());
        assert_eq!(
            snapshot.find_highlights(request).unwrap(),
            vec![Utf16Range::new(2, 5), Utf16Range::new(6, 9)]
        );
        for range in [
            Utf16Range::new(1, 3),
            Utf16Range::new(3, 1),
            Utf16Range::new(0, 10),
        ] {
            assert!(
                snapshot
                    .find_highlights(FindHighlightRequest { range, ..request })
                    .is_err()
            );
        }
    }
}
