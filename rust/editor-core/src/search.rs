//! Shared Unicode matching and strict replacement expansion for both presentations.

use std::ops::Range;
use std::sync::atomic::{AtomicBool, Ordering};

use regex_automata::{
    Input, PatternID,
    meta::Regex,
    util::{captures::Captures, syntax},
};
use unicode_segmentation::UnicodeSegmentation;

use crate::{
    DocumentError, DocumentSnapshot, MAX_FIND_QUERY_BYTES, MAX_FIND_QUERY_UTF16_UNITS, Utf16Range,
    read_find_text,
};

/// Bounds native search context, independently of source and display limits.
pub const MAX_SEARCH_CONTEXT_UTF16: usize = 8 * 1024 * 1024;
/// Bounds one packet's match records.
pub const MAX_SEARCH_PAGE_MATCHES: usize = 256;
/// Bounds exact text and expanded replacements retained in one packet.
pub const MAX_SEARCH_PAGE_TEXT_UTF16: usize = 256 * 1024;
const MAX_SEARCH_WORK: usize = 128 * 1024 * 1024;
const MAX_COMPILED_BYTES: usize = 4 * 1024 * 1024;

/// Controls the common matcher; whole-word applies only to literal matching.
#[derive(Clone, Copy, Debug, Default)]
pub struct SearchOptions {
    /// Uses Rust regular expressions instead of literal text.
    pub regex: bool,
    /// Disables default Unicode simple case folding.
    pub match_case: bool,
    /// Requires Unicode default word boundaries at both literal endpoints.
    pub whole_word: bool,
}

/// Reports why a page ended; only `Complete` proves coverage through the scope end.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum SearchCompletion {
    /// The entire requested scope has been searched.
    Complete,
    /// More verified matches can be requested with the returned cursor.
    PageLimit,
    /// The work budget ended; verified results remain usable.
    WorkLimit,
    /// Exact assertion or word context exceeds the memory budget.
    ContextLimit,
    /// The query owner cancelled this work.
    Cancelled,
}

/// Continues iteration without repeating zero-width matches adjacent to a match.
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct SearchCursor {
    /// Identifies the next eligible global UTF-16 start.
    pub start: usize,
    /// Suppresses an empty match immediately after the previous nonempty match.
    pub skip_empty: bool,
}

/// Holds a bounded, revision-independent match in its input's coordinates.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct SearchHit {
    /// Identifies the half-open matched range; equal endpoints denote an insertion.
    pub range: Utf16Range,
    /// Contains the exact matched characters for review and later validation.
    pub text: String,
    /// Contains the expanded replacement when replacement review was requested.
    pub replacement: Option<String>,
    /// Contains short surrounding context for empty and nonempty matches.
    pub before: String,
    /// Contains short following context.
    pub after: String,
}

/// Returns verified hits and an explicit coverage/cancellation state.
#[derive(Debug)]
pub struct SearchPage {
    /// Contains sorted matches from this page only.
    pub hits: Vec<SearchHit>,
    /// Explains whether more work is needed.
    pub completion: SearchCompletion,
    /// Continues exactly after the last inspected match.
    pub next: SearchCursor,
}

/// Owns one bounded compiled pattern and cooperative cancellation flag.
pub struct SearchPattern {
    regex: Regex,
    options: SearchOptions,
    weight: usize,
    cancelled: AtomicBool,
}

impl SearchPattern {
    /// Compiles literal text or the pinned Rust regex dialect once per request.
    ///
    /// # Errors
    ///
    /// Rejects empty/oversized queries, unsupported syntax and excessive compiled size.
    pub fn new(query: &str, options: SearchOptions) -> Result<Self, DocumentError> {
        if query.is_empty()
            || query.len() > MAX_FIND_QUERY_BYTES
            || query.encode_utf16().count() > MAX_FIND_QUERY_UTF16_UNITS
        {
            return Err(DocumentError::InvalidFindRequest(
                "query is empty or exceeds its limit",
            ));
        }
        let pattern = if options.regex {
            query.to_owned()
        } else {
            regex::escape(query)
        };
        let regex = Regex::builder()
            .configure(Regex::config().nfa_size_limit(Some(MAX_COMPILED_BYTES))
                .dfa_size_limit(Some(MAX_COMPILED_BYTES)).hybrid_cache_capacity(MAX_COMPILED_BYTES))
            .syntax(syntax::Config::new().case_insensitive(!options.match_case))
            .build(&pattern)
            .map_err(|_| DocumentError::InvalidFindRequest(
                "invalid or oversized Rust regex; look-around and pattern backreferences are unsupported",
            ))?;
        Ok(Self {
            regex,
            options,
            weight: if options.regex { query.len().max(1) } else { 1 },
            cancelled: AtomicBool::new(false),
        })
    }

    /// Stops subsequent pages and iteration between bounded matching operations.
    pub fn cancel(&self) {
        self.cancelled.store(true, Ordering::Relaxed);
    }

    /// Searches a logical string while preserving context outside the scope.
    ///
    /// Providing a replacement switches literals to nonoverlapping iteration.
    /// Regex iteration always follows Rust's nonoverlapping empty-match rules.
    ///
    /// # Errors
    ///
    /// Rejects invalid UTF-16 boundaries or invalid replacement syntax.
    pub fn search(
        &self,
        text: &str,
        scope: Utf16Range,
        cursor: SearchCursor,
        replacement: Option<&str>,
    ) -> Result<SearchPage, DocumentError> {
        let mut page = SearchPage {
            hits: Vec::new(),
            completion: SearchCompletion::Complete,
            next: cursor,
        };
        if text.encode_utf16().count() > MAX_SEARCH_CONTEXT_UTF16 {
            page.completion = SearchCompletion::ContextLimit;
            return Ok(page);
        }
        let span = byte_range(text, scope)?;
        if cursor.start < scope.start || cursor.start > scope.end {
            return Err(DocumentError::InvalidFindRequest(
                "search cursor is outside scope",
            ));
        }
        let template = replacement.map(|value| self.template(value)).transpose()?;
        let boundaries =
            (self.options.whole_word && !self.options.regex).then(|| word_boundaries(text));
        let mut start = byte_offset(text, cursor.start)?;
        let mut previous_end = if cursor.skip_empty { Some(start) } else { None };
        let mut caps = self.regex.create_captures();
        let mut units = 0;
        let mut work = 0_usize;
        // Byte-to-UTF-16 conversion advances with the iterator rather than repeatedly
        // counting the entire document prefix for every result.
        let mut measured_byte = start;
        let mut measured_units = cursor.start;
        while start <= span.end {
            if self.cancelled.load(Ordering::Relaxed) {
                page.completion = SearchCompletion::Cancelled;
                break;
            }
            if self.options.regex {
                work = work.saturating_add((span.end - start).saturating_mul(self.weight));
            }
            if work > MAX_SEARCH_WORK {
                page.completion = SearchCompletion::WorkLimit;
                break;
            }
            self.regex
                .captures(Input::new(text).span(start..span.end), &mut caps);
            let Some(found) = caps.get_match() else {
                break;
            };
            let range = found.range();
            if range.is_empty() && previous_end == Some(range.start) {
                let Some(next) = next_scalar(text, range.start) else {
                    break;
                };
                start = next;
                page.next = SearchCursor {
                    start: text[..start].encode_utf16().count(),
                    skip_empty: false,
                };
                continue;
            }
            let is_word = boundaries.as_ref().is_none_or(|bits| {
                bits[range.start / 8] & (1 << (range.start % 8)) != 0
                    && bits[range.end / 8] & (1 << (range.end % 8)) != 0
            });
            if is_word {
                let expanded = template
                    .as_ref()
                    .map(|parts| expand(parts, &caps, text))
                    .transpose()?;
                measured_units += text[measured_byte..range.start].encode_utf16().count();
                measured_byte = range.start;
                if !page.push_hit(text, range.clone(), measured_units, expanded, &mut units) {
                    break;
                }
            }
            let overlapping = !self.options.regex && replacement.is_none();
            if overlapping || range.is_empty() || !is_word {
                let Some(next) = next_scalar(text, range.start) else {
                    break;
                };
                start = next;
                previous_end = None;
            } else {
                start = range.end;
                previous_end = Some(start);
            }
            measured_units += text[measured_byte..start].encode_utf16().count();
            measured_byte = start;
            page.next = SearchCursor {
                start: measured_units,
                skip_empty: previous_end.is_some(),
            };
            if page.hits.len() == MAX_SEARCH_PAGE_MATCHES {
                page.completion = SearchCompletion::PageLimit;
                break;
            }
        }
        if page.completion == SearchCompletion::Complete {
            page.next = SearchCursor {
                start: scope.end,
                skip_empty: true,
            };
        }
        Ok(page)
    }

    fn template(&self, value: &str) -> Result<Vec<ReplacementPart>, DocumentError> {
        if value.contains('\r') || value.len() > MAX_SEARCH_PAGE_TEXT_UTF16 * 3 {
            return Err(DocumentError::InvalidFindRequest(
                "replacement is not normalized or exceeds its limit",
            ));
        }
        if !self.options.regex {
            return Ok(vec![ReplacementPart::Text(value.into())]);
        }
        let mut parts = Vec::new();
        let mut literal = String::new();
        let mut chars = value.chars().peekable();
        while let Some(character) = chars.next() {
            match character {
                '\\' => literal.push(match chars.next() {
                    Some('n') => '\n',
                    Some('t') => '\t',
                    Some('\\') => '\\',
                    _ => {
                        return Err(DocumentError::InvalidFindRequest(
                            "invalid replacement escape; use \\n, \\t, or \\\\",
                        ));
                    }
                }),
                '$' => {
                    if chars.peek() == Some(&'$') {
                        chars.next();
                        literal.push('$');
                        continue;
                    }
                    let mut name = String::new();
                    if chars.peek() == Some(&'{') {
                        chars.next();
                        let mut closed = false;
                        for next in chars.by_ref() {
                            if next == '}' {
                                closed = true;
                                break;
                            }
                            name.push(next);
                        }
                        if !closed || name.is_empty() {
                            return Err(DocumentError::InvalidFindRequest(
                                "invalid replacement capture",
                            ));
                        }
                    } else {
                        while chars.peek().is_some_and(char::is_ascii_digit) {
                            name.push(chars.next().unwrap());
                        }
                    }
                    let group = if name.chars().all(|c| c.is_ascii_digit()) && !name.is_empty() {
                        name.parse::<usize>()
                            .ok()
                            .filter(|index| *index < self.regex.captures_len())
                    } else {
                        self.regex.group_info().to_index(PatternID::ZERO, &name)
                    };
                    let group = group.ok_or(DocumentError::InvalidFindRequest(
                        "unknown replacement capture; use $0, $1, ${name}, or $$",
                    ))?;
                    if !literal.is_empty() {
                        parts.push(ReplacementPart::Text(std::mem::take(&mut literal)));
                    }
                    parts.push(ReplacementPart::Capture(group));
                }
                other => literal.push(other),
            }
        }
        if !literal.is_empty() {
            parts.push(ReplacementPart::Text(literal));
        }
        Ok(parts)
    }
}

impl DocumentSnapshot {
    /// Searches one immutable revision, retaining original context at scope edges.
    ///
    /// # Errors
    ///
    /// Rejects invalid ranges, unreadable source text, and invalid replacements.
    pub fn search(
        &self,
        pattern: &SearchPattern,
        scope: Utf16Range,
        cursor: SearchCursor,
        replacement: Option<&str>,
    ) -> Result<SearchPage, DocumentError> {
        if scope.start > scope.end || scope.end > self.metrics.utf16_units {
            return Err(DocumentError::InvalidFindRequest(
                "scope is outside the document",
            ));
        }
        if cursor.start < scope.start || cursor.start > scope.end {
            return Err(DocumentError::InvalidFindRequest(
                "search cursor is outside scope",
            ));
        }
        let mut reader = self.tree.reader();
        let candidate_end = if pattern.options.regex {
            scope.end
        } else {
            reader.scalar_boundary_at_or_after(
                cursor
                    .start
                    .saturating_add(crate::MAX_FIND_CANDIDATE_UTF16_UNITS)
                    .min(scope.end),
            )?
        };
        let consumption_end = if pattern.options.regex {
            scope.end
        } else {
            reader.scalar_boundary_at_or_after(
                candidate_end
                    .saturating_add(crate::MAX_FIND_MATCH_UTF16_UNITS)
                    .min(scope.end),
            )?
        };
        // Regex assertions need the real neighboring scalars, not artificial
        // string boundaries. UAX word segmentation needs complete edge lines.
        let (start, end) = if pattern.options.whole_word && !pattern.options.regex {
            let first = reader.position_at_utf16(cursor.start)?;
            let last = reader.position_at_utf16(consumption_end)?;
            let start = reader.line_bounds(first.line_feeds)?.content_start_utf16;
            let end = reader.line_bounds(last.line_feeds)?.content_end_utf16;
            (start, end)
        } else {
            let start = reader.scalar_boundary_at_or_before(cursor.start.saturating_sub(1))?;
            let end = reader.scalar_boundary_at_or_after(
                consumption_end
                    .saturating_add(1)
                    .min(self.metrics.utf16_units),
            )?;
            (start, end)
        };
        if end - start > MAX_SEARCH_CONTEXT_UTF16 {
            return Ok(SearchPage {
                hits: Vec::new(),
                completion: SearchCompletion::ContextLimit,
                next: cursor,
            });
        }
        let text = read_find_text(&mut reader, Utf16Range::new(start, end))?;
        let mut result = pattern.search(
            &text,
            Utf16Range::new(cursor.start - start, consumption_end - start),
            SearchCursor {
                start: cursor.start - start,
                skip_empty: cursor.skip_empty,
            },
            replacement,
        )?;
        for hit in &mut result.hits {
            hit.range.start += start;
            hit.range.end += start;
        }
        result.next.start += start;
        if candidate_end < scope.end {
            // A literal match may end beyond this page, but its start belongs
            // to exactly one page. Replacement resumes after consumed source.
            result.hits.retain(|hit| hit.range.start < candidate_end);
            if result.completion == SearchCompletion::Complete || result.next.start >= candidate_end
            {
                result.completion = SearchCompletion::PageLimit;
                result.next = SearchCursor {
                    start: if replacement.is_some() {
                        result
                            .hits
                            .last()
                            .map_or(candidate_end, |hit| hit.range.end.max(candidate_end))
                    } else {
                        candidate_end
                    },
                    skip_empty: false,
                };
            }
        }
        Ok(result)
    }
}

impl SearchPage {
    fn push_hit(
        &mut self,
        text: &str,
        range: Range<usize>,
        start: usize,
        replacement: Option<String>,
        units: &mut usize,
    ) -> bool {
        let matched = &text[range.clone()];
        let matched_units = matched.encode_utf16().count();
        let cost = matched_units
            + replacement
                .as_ref()
                .map_or(0, |value| value.encode_utf16().count());
        if cost > MAX_SEARCH_PAGE_TEXT_UTF16 - *units {
            self.completion = if self.hits.is_empty() {
                SearchCompletion::ContextLimit
            } else {
                SearchCompletion::PageLimit
            };
            return false;
        }
        self.hits.push(SearchHit {
            range: Utf16Range::new(start, start + matched_units),
            text: matched.to_owned(),
            replacement,
            before: text[..range.start]
                .chars()
                .rev()
                .take(40)
                .collect::<Vec<_>>()
                .into_iter()
                .rev()
                .collect(),
            after: text[range.end..].chars().take(40).collect(),
        });
        *units += cost;
        true
    }
}

fn word_boundaries(text: &str) -> Vec<u8> {
    let mut bits = vec![0_u8; text.len() / 8 + 1];
    for offset in text
        .split_word_bound_indices()
        .map(|(offset, _)| offset)
        .chain(std::iter::once(text.len()))
    {
        bits[offset / 8] |= 1 << (offset % 8);
    }
    bits
}

enum ReplacementPart {
    Text(String),
    Capture(usize),
}

fn expand(
    parts: &[ReplacementPart],
    captures: &Captures,
    text: &str,
) -> Result<String, DocumentError> {
    let mut output = String::new();
    for part in parts {
        let value = match part {
            ReplacementPart::Text(value) => value,
            ReplacementPart::Capture(index) => captures
                .get_group(*index)
                .map_or("", |range| &text[range.range()]),
        };
        if output.len().saturating_add(value.len()) > MAX_SEARCH_PAGE_TEXT_UTF16 * 3 {
            return Err(DocumentError::InvalidFindRequest(
                "expanded replacement exceeds text limit",
            ));
        }
        output.push_str(value);
    }
    Ok(output)
}

fn byte_offset(text: &str, utf16: usize) -> Result<usize, DocumentError> {
    let mut offset = 0;
    for (byte, character) in text.char_indices() {
        if offset == utf16 {
            return Ok(byte);
        }
        offset += character.len_utf16();
        if offset > utf16 {
            break;
        }
    }
    if offset == utf16 {
        return Ok(text.len());
    }
    Err(DocumentError::InvalidFindRequest(
        "range is outside text or splits a Unicode scalar",
    ))
}

fn byte_range(text: &str, range: Utf16Range) -> Result<Range<usize>, DocumentError> {
    if range.start > range.end {
        return Err(DocumentError::InvalidFindRequest("reversed scope"));
    }
    Ok(byte_offset(text, range.start)?..byte_offset(text, range.end)?)
}

fn next_scalar(text: &str, byte: usize) -> Option<usize> {
    text[byte..]
        .chars()
        .next()
        .map(|character| byte + character.len_utf8())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::Document;

    fn pattern(query: &str, regex: bool, whole_word: bool) -> SearchPattern {
        SearchPattern::new(
            query,
            SearchOptions {
                regex,
                whole_word,
                match_case: false,
            },
        )
        .unwrap()
    }

    fn all(
        query: &str,
        text: &str,
        regex: bool,
        whole_word: bool,
        replacement: Option<&str>,
    ) -> SearchPage {
        pattern(query, regex, whole_word)
            .search(
                text,
                Utf16Range::new(0, text.encode_utf16().count()),
                SearchCursor::default(),
                replacement,
            )
            .unwrap()
    }

    #[test]
    fn uses_simple_case_folding_without_unicode_normalization() {
        assert_eq!(all("σ", "Σ σ ς", false, false, None).hits.len(), 3);
        assert!(all("strasse", "Straße", false, false, None).hits.is_empty());
        assert_eq!(all("é", "é e\u{301}", false, false, None).hits.len(), 1);
    }

    #[test]
    fn whole_words_follow_pinned_unicode_default_segmentation() {
        let result = all("can", "can can't can’t can_do can-do", false, true, None);
        assert_eq!(
            result
                .hits
                .iter()
                .map(|hit| hit.range.start)
                .collect::<Vec<_>>(),
            vec![0, 23]
        );
        assert_eq!(all("猫", "猫犬", false, true, None).hits.len(), 1);
    }

    #[test]
    fn scopes_restrict_consumption_but_retain_original_assertion_context() {
        let document = Document::from_text("aaaa");
        for query in ["^a+", "a+$", r"\ba+\b", r"\Aa+", r"a+\z"] {
            let result = document
                .snapshot()
                .search(
                    &pattern(query, true, false),
                    Utf16Range::new(1, 3),
                    SearchCursor {
                        start: 1,
                        skip_empty: false,
                    },
                    None,
                )
                .unwrap();
            assert!(result.hits.is_empty(), "{query}");
        }
        let result = document
            .snapshot()
            .search(
                &pattern("a+", true, false),
                Utf16Range::new(1, 3),
                SearchCursor {
                    start: 1,
                    skip_empty: false,
                },
                None,
            )
            .unwrap();
        assert_eq!(result.hits[0].range, Utf16Range::new(1, 3));
        assert!(
            document
                .snapshot()
                .search(
                    &pattern("aa", false, true),
                    Utf16Range::new(1, 3),
                    SearchCursor {
                        start: 1,
                        skip_empty: false
                    },
                    None
                )
                .unwrap()
                .hits
                .is_empty()
        );
    }

    #[test]
    fn preserves_document_anchors_in_middle_line_scopes() {
        let document = Document::from_text("first\nsecond\nthird\nfourth\n");
        for query in [r"\Athird", r"third\z", "^third", "third$"] {
            assert!(
                document
                    .snapshot()
                    .search(
                        &pattern(query, true, false),
                        Utf16Range::new(13, 18),
                        SearchCursor {
                            start: 13,
                            skip_empty: false
                        },
                        None
                    )
                    .unwrap()
                    .hits
                    .is_empty()
            );
        }
        assert_eq!(
            document
                .snapshot()
                .search(
                    &pattern("(?m)^third$", true, false),
                    Utf16Range::new(13, 18),
                    SearchCursor {
                        start: 13,
                        skip_empty: false
                    },
                    None
                )
                .unwrap()
                .hits
                .len(),
            1
        );
    }

    #[test]
    fn literal_find_overlaps_but_replacement_does_not() {
        assert_eq!(all("ana", "banana", false, false, None).hits.len(), 2);
        assert_eq!(all("ana", "banana", false, false, Some("x")).hits.len(), 1);
    }

    #[test]
    fn source_literals_cross_work_pages_and_large_unbroken_lines() {
        let boundary = crate::MAX_FIND_CANDIDATE_UTF16_UNITS;
        let source = format!(
            "{}needle{}needle",
            "x".repeat(boundary - 2),
            "x".repeat(boundary)
        );
        let document = Document::from_text(&source);
        let pattern = pattern("needle", false, false);
        let scope = Utf16Range::new(0, source.len());
        let first = document
            .snapshot()
            .search(&pattern, scope, SearchCursor::default(), None)
            .unwrap();
        assert_eq!(first.hits.len(), 1);
        assert_eq!(first.hits[0].range.start, boundary - 2);
        assert_eq!(first.next.start, boundary);
        let middle = document
            .snapshot()
            .search(&pattern, scope, first.next, None)
            .unwrap();
        assert!(middle.hits.is_empty());
        let last = document
            .snapshot()
            .search(&pattern, scope, middle.next, None)
            .unwrap();
        assert_eq!(last.hits.len(), 1);
        assert_eq!(last.completion, SearchCompletion::Complete);
    }

    #[test]
    fn regex_empty_matches_follow_scalar_and_adjacent_match_rules() {
        assert_eq!(
            all("(?:)", "😀a", true, false, None)
                .hits
                .iter()
                .map(|hit| hit.range.start)
                .collect::<Vec<_>>(),
            vec![0, 2, 3]
        );
        assert_eq!(all("a*", "a", true, false, None).hits.len(), 1);
        assert_eq!(all("(?m)^", "a\nb\n", true, false, Some("X")).hits.len(), 3);
        let text = "a".repeat(300);
        let compiled = pattern("a?", true, false);
        let scope = Utf16Range::new(0, 300);
        let first = compiled
            .search(&text, scope, SearchCursor::default(), None)
            .unwrap();
        assert_eq!(first.completion, SearchCompletion::PageLimit);
        let next = compiled.search(&text, scope, first.next, None).unwrap();
        assert_eq!(first.hits.len() + next.hits.len(), 300);
        assert_eq!(next.completion, SearchCompletion::Complete);
    }

    #[test]
    fn replacement_captures_are_strict_and_expand_once() {
        assert_eq!(
            all(r"(?P<λέξη>cat)", "cat", true, false, Some("${λέξη}")).hits[0]
                .replacement
                .as_deref(),
            Some("cat")
        );
        let result = all(
            r"(?P<name>a)(b)?",
            "a",
            true,
            false,
            Some(r"${name}:$0:$2:${1}:$$:\n\t\\"),
        );
        assert_eq!(
            result.hits[0].replacement.as_deref(),
            Some("a:a::a:$:\n\t\\")
        );
        assert_eq!(
            all(r"(.+)", "$1\\n", true, false, Some("$1")).hits[0]
                .replacement
                .as_deref(),
            Some("$1\\n")
        );
        let compiled = pattern("(a)", true, false);
        for replacement in ["${1", "${missing}", "$2", "$name", "$", r"\q", "${}"] {
            assert!(
                compiled
                    .search(
                        "a",
                        Utf16Range::new(0, 1),
                        SearchCursor::default(),
                        Some(replacement)
                    )
                    .is_err(),
                "{replacement}"
            );
        }
        assert_eq!(
            all("a", "a", false, false, Some(r"$1\n")).hits[0]
                .replacement
                .as_deref(),
            Some(r"$1\n")
        );
    }

    #[test]
    fn cancellation_and_excessive_context_never_claim_complete_coverage() {
        let compiled = pattern("a", false, false);
        compiled.cancel();
        assert_eq!(
            compiled
                .search("a", Utf16Range::new(0, 1), SearchCursor::default(), None)
                .unwrap()
                .completion,
            SearchCompletion::Cancelled
        );
        let document = Document::from_text(&"a".repeat(MAX_SEARCH_CONTEXT_UTF16 + 1));
        let result = document
            .snapshot()
            .search(
                &pattern("z", true, false),
                Utf16Range::new(0, document.metrics().utf16_units),
                SearchCursor::default(),
                None,
            )
            .unwrap();
        assert!(result.hits.is_empty());
        assert_eq!(result.completion, SearchCompletion::ContextLimit);
    }
}
