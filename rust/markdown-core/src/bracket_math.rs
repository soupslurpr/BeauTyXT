//! Byte-position-preserving admission of LaTeX bracket delimiters before Markdown parsing.

use std::{borrow::Cow, collections::BTreeMap, ops::Range};

use pulldown_cmark::{Event, Options, Parser, Tag, TagEnd};

use super::{
    CHECKPOINT_EVENT_INTERVAL, MAX_BLOCK_TEXT_BYTES, MAX_EVENT_COUNT, RenderControl, RenderError,
};

pub(super) struct BracketMath<'a> {
    pub input: Cow<'a, str>,
    expressions: BTreeMap<usize, (usize, bool)>,
}

impl<'a> BracketMath<'a> {
    /// Reuses dollar-math parsing, replacing only the two-byte delimiters, never source contents.
    /// Candidate starts must be ordinary Markdown text, not code, URLs, images, or HTML attributes.
    pub fn prepare(
        source: &'a str,
        options: Options,
        control: &mut impl RenderControl,
    ) -> Result<Self, RenderError> {
        let mut result = Self {
            input: Cow::Borrowed(source),
            expressions: BTreeMap::new(),
        };
        if !source.contains("\\(") && !source.contains("\\[") {
            return Ok(result);
        }
        let mut boundaries = Vec::new();
        let mut code = false;
        let mut image_depth = 0;
        let mut inline_html = false;
        let mut consumed = 0;
        // Repeated unmatched openers cannot turn a bounded document into quadratic scanning.
        let mut scan_budget = source.len().saturating_mul(4);
        for (count, (event, range)) in Parser::new_ext(source, options)
            .into_offset_iter()
            .enumerate()
        {
            if count >= MAX_EVENT_COUNT {
                return Err(RenderError::EventLimit);
            }
            if count % CHECKPOINT_EVENT_INTERVAL == 0 {
                control.checkpoint()?;
            }
            match event {
                Event::Start(tag) => {
                    if boundaries.len() >= super::MAX_NESTING_DEPTH {
                        return Err(RenderError::NestingLimit);
                    }
                    if matches!(tag, Tag::CodeBlock(_)) {
                        code = true;
                    }
                    if matches!(tag, Tag::Image { .. }) {
                        image_depth += 1;
                    }
                    boundaries.push(range.end);
                }
                Event::End(tag) => {
                    boundaries.pop();
                    if tag == TagEnd::CodeBlock {
                        code = false;
                    }
                    if tag == TagEnd::Image {
                        image_depth -= 1;
                    }
                    if matches!(tag, TagEnd::Paragraph | TagEnd::Heading(_)) {
                        inline_html = false;
                    }
                }
                Event::InlineHtml(_) => {
                    inline_html = true;
                }
                Event::Text(_) if !code && image_depth == 0 && !inline_html => {
                    // CommonMark may put the escaped '(' in a separate text event and exclude '\\'.
                    let start = range.start.saturating_sub(1).max(consumed);
                    for offset in start..range.end {
                        if offset < consumed || !opener(source.as_bytes(), offset) {
                            continue;
                        }
                        let end_limit = boundaries.last().copied().unwrap_or(source.len());
                        let Some(end) = closing(source, offset, end_limit, &mut scan_budget) else {
                            continue;
                        };
                        let mut candidate = source[offset..end].to_owned();
                        candidate.replace_range(candidate.len() - 2.., "$$");
                        candidate.replace_range(..2, "$$");
                        let mut math = Parser::new_ext(&candidate, options)
                            .into_offset_iter()
                            .filter_map(|(event, range)| {
                                if let Event::DisplayMath(text) = event {
                                    Some((text, range))
                                } else {
                                    None
                                }
                            });
                        if !matches!(math.next(), Some((text, range)) if range == (0..candidate.len()) && text.as_ref() == &source[offset + 2..end - 2])
                            || math.next().is_some()
                        {
                            continue;
                        }
                        let input = result.input.to_mut();
                        if result.expressions.len() >= super::MAX_SPAN_COUNT {
                            return Err(RenderError::SpanLimit);
                        }
                        input.replace_range(end - 2..end, "$$");
                        input.replace_range(offset..offset + 2, "$$");
                        result
                            .expressions
                            .insert(offset, (end, source.as_bytes()[offset + 1] == b'['));
                        consumed = end;
                    }
                }
                _ => {}
            }
        }
        Ok(result)
    }

    pub fn display_style(&self, range: &Range<usize>) -> Option<bool> {
        self.expressions
            .get(&range.start)
            .and_then(|&(end, display)| (end == range.end).then_some(display))
    }
}

fn opener(bytes: &[u8], offset: usize) -> bool {
    bytes.get(offset) == Some(&b'\\')
        && matches!(bytes.get(offset + 1), Some(b'(' | b'['))
        && bytes[..offset]
            .iter()
            .rev()
            .take_while(|&&byte| byte == b'\\')
            .count()
            % 2
            == 0
}

fn closing(source: &str, start: usize, container_end: usize, budget: &mut usize) -> Option<usize> {
    let bytes = source.as_bytes();
    let display = bytes[start + 1] == b'[';
    let close = if display { b']' } else { b')' };
    let limit = container_end
        .min(bytes.len())
        .min(start + MAX_BLOCK_TEXT_BYTES + 4);
    let mut index = start + 2;
    while index + 1 < limit && *budget != 0 {
        *budget -= 1;
        if !display && matches!(bytes[index], b'\r' | b'\n') {
            return None;
        }
        if bytes[index] == b'\\' {
            if bytes[index + 1] == close {
                return (index > start + 2).then_some(index + 2);
            }
            index += 2;
        } else {
            index += 1;
        }
    }
    None
}
