//! Strict, bounded parsing of `<details>` and `<summary>` HTML directives plus O(1)-memory
//! streaming `</details>` boundary detection.

use std::cell::RefCell;

use html5ever::TokenizerResult;
use html5ever::tendril::StrTendril;
use html5ever::tokenizer::{
    BufferQueue, EndTag, StartTag, TagToken, Token, TokenSink, TokenSinkResult, Tokenizer,
    TokenizerOpts, states::RawKind,
};

/// Maximum nesting depth for collapsible `<details>` containers.
pub(super) const MAX_DETAILS_DEPTH: usize = 8;

/// Maximum collapsible `<details>` groups in one document.
pub(super) const MAX_DETAILS_GROUPS: u16 = 1_024;

/// Maximum UTF-8 bytes allowed in one `<summary>` title.
const MAX_SUMMARY_BYTES: usize = 1024;

#[derive(Debug)]
enum SimpleToken {
    Start {
        name: String,
        self_closing: bool,
        attrs: Vec<(String, String)>,
    },
    End(String),
    Text(String),
}

#[derive(Default)]
struct SimpleSink {
    tokens: RefCell<Vec<SimpleToken>>,
    invalid: RefCell<bool>,
}

fn raw_data_result_for_start_tag(name: &str) -> TokenSinkResult<()> {
    match name {
        "script" => TokenSinkResult::RawData(RawKind::ScriptData),
        "style" | "xmp" | "iframe" | "noembed" | "noframes" => {
            TokenSinkResult::RawData(RawKind::Rawtext)
        }
        "textarea" | "title" => TokenSinkResult::RawData(RawKind::Rcdata),
        "plaintext" => TokenSinkResult::Plaintext,
        _ => TokenSinkResult::Continue,
    }
}

impl TokenSink for SimpleSink {
    type Handle = ();

    fn process_token(&self, token: Token, _line_number: u64) -> TokenSinkResult<Self::Handle> {
        let mut tokens = self.tokens.borrow_mut();
        if tokens.len() >= 1024 {
            *self.invalid.borrow_mut() = true;
            return TokenSinkResult::Continue;
        }
        match token {
            TagToken(tag) => {
                if tag.had_duplicate_attributes
                    || tag.attrs.iter().any(|attr| attr.name.prefix.is_some())
                {
                    *self.invalid.borrow_mut() = true;
                    return TokenSinkResult::Continue;
                }
                let name = tag.name.to_string();
                match tag.kind {
                    StartTag => {
                        let raw_result = if tag.self_closing {
                            TokenSinkResult::Continue
                        } else {
                            raw_data_result_for_start_tag(&name)
                        };
                        let attrs = tag
                            .attrs
                            .into_iter()
                            .map(|a| (a.name.local.to_string(), a.value.to_string()))
                            .collect();
                        tokens.push(SimpleToken::Start {
                            name,
                            self_closing: tag.self_closing,
                            attrs,
                        });
                        return raw_result;
                    }
                    EndTag => tokens.push(SimpleToken::End(name)),
                }
            }
            Token::CharacterTokens(text) => {
                if !text.is_empty() {
                    tokens.push(SimpleToken::Text(text.to_string()));
                }
            }
            Token::EOFToken => {}
            Token::CommentToken(_)
            | Token::DoctypeToken(_)
            | Token::NullCharacterToken
            | Token::ParseError(_) => *self.invalid.borrow_mut() = true,
        }
        TokenSinkResult::Continue
    }
}

fn tokenize(source: &str) -> Option<Vec<SimpleToken>> {
    if source.len() > 64 * 1024 {
        return None;
    }
    let input = BufferQueue::default();
    input.push_back(StrTendril::from(source));
    let tokenizer = Tokenizer::new(SimpleSink::default(), TokenizerOpts::default());
    if !matches!(tokenizer.feed(&input), TokenizerResult::Done) || !input.is_empty() {
        return None;
    }
    tokenizer.end();
    if *tokenizer.sink.invalid.borrow() {
        return None;
    }
    Some(tokenizer.sink.tokens.into_inner())
}

/// Recognized HTML segment directive for collapsible `<details>` containers.
///
/// Unmatched `</details>` closers are split out incrementally during HTML streaming via
/// [`DetailsBalanceStream`], so completed segments only need to classify opens, summaries,
/// self-contained `<details>...</details>`, or unmatched trailing opens.
#[derive(Clone, Debug, Eq, PartialEq)]
pub(super) enum DetailsDirective {
    /// Opening `<details>` (optionally followed by `<summary>...</summary>` and/or leading HTML
    /// body content in the same segment).
    Open {
        initially_open: bool,
        summary: Option<String>,
        leading_body_html: Option<String>,
    },
    /// Standalone `<summary>...</summary>` immediately inside an already-open `<details>`.
    Summary(String),
    /// Single self-contained `<details>[<summary>...</summary>]<body...></details>` block.
    Complete {
        initially_open: bool,
        summary: Option<String>,
        body_html: String,
    },
    /// Unmatched trailing `<details>` openers inside an unsupported or mixed literal HTML block.
    UnsupportedOpens(usize),
}

fn validate_details_attrs(attrs: &[(String, String)]) -> Option<bool> {
    match attrs {
        [] => Some(false),
        [(name, value)]
            if name == "open" && (value.is_empty() || value.eq_ignore_ascii_case("open")) =>
        {
            Some(true)
        }
        _ => None,
    }
}

fn skip_whitespace(tokens: &[SimpleToken], idx: &mut usize) {
    while let Some(SimpleToken::Text(t)) = tokens.get(*idx) {
        if t.trim().is_empty() {
            *idx += 1;
        } else {
            break;
        }
    }
}

fn consume_summary(tokens: &[SimpleToken], idx: &mut usize) -> Option<String> {
    let mut cursor = *idx;
    match tokens.get(cursor)? {
        SimpleToken::Start {
            name,
            self_closing: false,
            attrs,
        } if name == "summary" && attrs.is_empty() => {
            cursor += 1;
        }
        _ => return None,
    }
    let mut text = String::new();
    let mut inline_depth = 0usize;
    while let Some(tok) = tokens.get(cursor) {
        match tok {
            SimpleToken::End(name) if name == "summary" && inline_depth == 0 => {
                cursor += 1;
                let normalized = collapse_whitespace(&text);
                if normalized.len() <= MAX_SUMMARY_BYTES {
                    *idx = cursor;
                    return Some(normalized);
                }
                return None;
            }
            SimpleToken::Text(t) => {
                if text.len().saturating_add(t.len()) > MAX_SUMMARY_BYTES * 2 {
                    return None;
                }
                text.push_str(t);
            }
            SimpleToken::Start {
                name,
                self_closing,
                attrs,
            } if is_safe_summary_inline_tag(name) && attrs.is_empty() => {
                if !self_closing {
                    inline_depth = inline_depth.saturating_add(1);
                }
            }
            SimpleToken::End(name) if is_safe_summary_inline_tag(name) && inline_depth > 0 => {
                inline_depth -= 1;
            }
            _ => return None,
        }
        cursor += 1;
    }
    None
}

fn is_safe_summary_inline_tag(name: &str) -> bool {
    matches!(
        name,
        "b" | "strong" | "i" | "em" | "code" | "span" | "s" | "del" | "sub" | "sup" | "mark"
    )
}

fn collapse_whitespace(input: &str) -> String {
    let mut out = String::new();
    let mut prev_ws = false;
    for ch in input.trim().chars() {
        if ch.is_whitespace() {
            if !prev_ws {
                out.push(' ');
                prev_ws = true;
            }
        } else {
            out.push(ch);
            prev_ws = false;
        }
    }
    out
}

fn consume_optional_summary(tokens: &[SimpleToken], idx: &mut usize) -> Option<String> {
    if !matches!(
        tokens.get(*idx),
        Some(SimpleToken::Start { name, .. }) if name == "summary"
    ) {
        return None;
    }
    let summary = consume_summary(tokens, idx);
    if summary.is_some() {
        skip_whitespace(tokens, idx);
    }
    summary
}

/// Parses one HTML segment as a `<details>` / `<summary>` directive or counts unmatched opens.
pub(super) fn parse_details_directive(source: &str) -> Option<DetailsDirective> {
    let lower = source.to_ascii_lowercase();
    if !lower.contains("details") && !lower.contains("summary") {
        return None;
    }
    let Some(tokens) = tokenize(source) else {
        let mut stream = DetailsBalanceStream::new();
        stream.feed_all(source);
        let opens = stream.take_trailing_opens();
        return (opens > 0).then_some(DetailsDirective::UnsupportedOpens(opens));
    };
    let mut idx = 0usize;
    skip_whitespace(&tokens, &mut idx);

    let parsed = match tokens.get(idx)? {
        SimpleToken::Start { name, .. } if name == "summary" => consume_summary(&tokens, &mut idx)
            .and_then(|summary| {
                skip_whitespace(&tokens, &mut idx);
                (idx == tokens.len()).then_some(DetailsDirective::Summary(summary))
            }),
        SimpleToken::Start {
            name,
            self_closing: false,
            attrs,
        } if name == "details" => {
            if let Some(initially_open) = validate_details_attrs(attrs) {
                idx += 1;
                skip_whitespace(&tokens, &mut idx);
                let summary = consume_optional_summary(&tokens, &mut idx);
                if idx == tokens.len() {
                    Some(DetailsDirective::Open {
                        initially_open,
                        summary,
                        leading_body_html: None,
                    })
                } else {
                    parse_complete_details(source, &tokens, idx, initially_open, summary.clone())
                        .or_else(|| {
                            parse_open_details_with_leading_body(
                                source,
                                &tokens,
                                idx,
                                initially_open,
                                summary,
                            )
                        })
                }
            } else {
                None
            }
        }
        _ => None,
    };
    if parsed.is_some() {
        return parsed;
    }
    let mut trailing_opens = 0usize;
    for tok in &tokens {
        match tok {
            SimpleToken::Start {
                name,
                self_closing: false,
                ..
            } if name == "details" => trailing_opens = trailing_opens.saturating_add(1),
            SimpleToken::End(name) if name == "details" => {
                trailing_opens = trailing_opens.saturating_sub(1);
            }
            _ => {}
        }
    }
    (trailing_opens > 0).then_some(DetailsDirective::UnsupportedOpens(trailing_opens))
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum ScanState {
    Data,
    TagOpen,
    Bang,
    ProcessingInstruction,
    ProcessingInstructionQuestion,
    CommentDash1,
    Comment,
    CommentEnd1,
    CommentEnd2,
    CdataOpen(u8),
    Cdata,
    CdataEnd1,
    CdataEnd2,
    StartTagName,
    EndTagName,
    TagRest,
    TagSingleQuote,
    TagDoubleQuote,
    RawText,
    RawTextLt,
    RawTextSlash,
    RawTextEndName,
    Plaintext,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum PendingTagAction {
    None,
    DetailsStart,
    DetailsEnd,
    RawText(&'static [u8]),
    Plaintext,
}

/// Classifies one top-level `</details>` boundary found during streaming HTML scanning.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(super) enum CloseTagKind {
    /// Unmatched `</details>` closing an enclosing `<details>` opened in an earlier HTML block.
    Unmatched,
    /// Closing `</details>` that returns this segment's local `<details>` open balance from 1 to 0.
    CompletedTopLevel,
}

/// One top-level `</details>` end tag located inside the current HTML chunk.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(super) struct CloseTagHit {
    /// Whether this tag closes an enclosing fold or completes a local `<details>...</details>`.
    pub(super) kind: CloseTagKind,
    /// Byte offset of `<` within the current chunk, or `None` if `<` began in an earlier chunk.
    pub(super) tag_start_in_chunk: Option<usize>,
    /// Exclusive byte offset right after `>` within the current chunk.
    pub(super) tag_end_in_chunk: usize,
    /// Whether the closing tag contained only optional ASCII whitespace before `>`.
    pub(super) is_clean_close_tag: bool,
}

/// Strictly O(1)-memory streaming `<details>` tag balance scanner that tracks HTML tag boundaries,
/// comments, quotes, and raw-text elements while reporting unmatched top-level `</details>` tags
/// at their exact byte offsets so mixed HTML blocks can split cleanly across fold boundaries.
#[derive(Debug)]
pub(super) struct DetailsBalanceStream {
    state: ScanState,
    trailing_opens: usize,
    tag_start_in_chunk: Option<usize>,
    clean_end_tag: bool,
    name_buf: [u8; 12],
    name_len: u8,
    name_overflow: bool,
    pending: PendingTagAction,
    last_was_slash: bool,
    raw_end_tag: &'static [u8],
}

impl DetailsBalanceStream {
    pub(super) fn new() -> Self {
        Self {
            state: ScanState::Data,
            trailing_opens: 0,
            tag_start_in_chunk: None,
            clean_end_tag: false,
            name_buf: [0; 12],
            name_len: 0,
            name_overflow: false,
            pending: PendingTagAction::None,
            last_was_slash: false,
            raw_end_tag: b"",
        }
    }

    pub(super) fn take_trailing_opens(&mut self) -> usize {
        std::mem::take(&mut self.trailing_opens)
    }

    fn reset_name(&mut self) {
        self.name_len = 0;
        self.name_overflow = false;
    }

    fn push_name_byte(&mut self, b: u8) {
        let idx = usize::from(self.name_len);
        if idx < self.name_buf.len() {
            self.name_buf[idx] = b.to_ascii_lowercase();
            self.name_len += 1;
        } else {
            self.name_overflow = true;
        }
    }

    fn current_name(&self) -> Option<&[u8]> {
        (!self.name_overflow).then_some(&self.name_buf[..usize::from(self.name_len)])
    }

    fn classify_start_tag(&mut self) {
        self.pending = match self.current_name() {
            Some(b"details") => PendingTagAction::DetailsStart,
            Some(b"script") => PendingTagAction::RawText(b"script"),
            Some(b"style") => PendingTagAction::RawText(b"style"),
            Some(b"textarea") => PendingTagAction::RawText(b"textarea"),
            Some(b"title") => PendingTagAction::RawText(b"title"),
            Some(b"xmp") => PendingTagAction::RawText(b"xmp"),
            Some(b"iframe") => PendingTagAction::RawText(b"iframe"),
            Some(b"noembed") => PendingTagAction::RawText(b"noembed"),
            Some(b"noframes") => PendingTagAction::RawText(b"noframes"),
            Some(b"plaintext") => PendingTagAction::Plaintext,
            _ => PendingTagAction::None,
        };
    }

    /// Commits the current tag and returns a [`CloseTagKind`] when a top-level `</details>` hits.
    fn commit_tag(&mut self) -> Option<CloseTagKind> {
        let self_closing = self.last_was_slash;
        let action = self.pending;
        self.pending = PendingTagAction::None;
        self.last_was_slash = false;
        self.state = ScanState::Data;
        match action {
            PendingTagAction::DetailsStart if !self_closing => {
                self.trailing_opens = self.trailing_opens.saturating_add(1);
                None
            }
            PendingTagAction::DetailsEnd => match self.trailing_opens {
                0 => Some(CloseTagKind::Unmatched),
                1 => {
                    self.trailing_opens = 0;
                    Some(CloseTagKind::CompletedTopLevel)
                }
                _ => {
                    self.trailing_opens -= 1;
                    None
                }
            },
            PendingTagAction::RawText(tag) if !self_closing => {
                self.raw_end_tag = tag;
                self.state = ScanState::RawText;
                None
            }
            PendingTagAction::Plaintext if !self_closing => {
                self.state = ScanState::Plaintext;
                None
            }
            PendingTagAction::None
            | PendingTagAction::DetailsStart
            | PendingTagAction::RawText(_)
            | PendingTagAction::Plaintext => None,
        }
    }

    /// Feeds the entire `source` slice ignoring top-level closes (for trailing-open counting).
    pub(super) fn feed_all(&mut self, mut source: &str) {
        while let Some(close) = self.scan_until_close(source) {
            source = source.get(close.tag_end_in_chunk..).unwrap_or("");
        }
    }

    /// Scans `chunk` until the first top-level `</details>` end tag (unmatched or completing).
    #[allow(clippy::too_many_lines)]
    pub(super) fn scan_until_close(&mut self, chunk: &str) -> Option<CloseTagHit> {
        const CDATA_PREFIX: &[u8; 7] = b"[CDATA[";
        self.tag_start_in_chunk = None;
        for (i, &b) in chunk.as_bytes().iter().enumerate() {
            match self.state {
                ScanState::Data => {
                    if b == b'<' {
                        self.tag_start_in_chunk = Some(i);
                        self.state = ScanState::TagOpen;
                    }
                }
                ScanState::TagOpen => match b {
                    b'!' => self.state = ScanState::Bang,
                    b'?' => self.state = ScanState::ProcessingInstruction,
                    b'/' => {
                        self.reset_name();
                        self.pending = PendingTagAction::None;
                        self.last_was_slash = false;
                        self.clean_end_tag = true;
                        self.state = ScanState::EndTagName;
                    }
                    b if b.is_ascii_alphabetic() => {
                        self.reset_name();
                        self.push_name_byte(b);
                        self.pending = PendingTagAction::None;
                        self.last_was_slash = false;
                        self.state = ScanState::StartTagName;
                    }
                    b'<' => {
                        self.tag_start_in_chunk = Some(i);
                        self.state = ScanState::TagOpen;
                    }
                    _ => self.state = ScanState::Data,
                },
                ScanState::ProcessingInstruction => {
                    if b == b'?' {
                        self.state = ScanState::ProcessingInstructionQuestion;
                    }
                }
                ScanState::ProcessingInstructionQuestion => match b {
                    b'>' => self.state = ScanState::Data,
                    b'?' => {}
                    _ => self.state = ScanState::ProcessingInstruction,
                },
                ScanState::Bang => {
                    self.state = if b == b'-' {
                        ScanState::CommentDash1
                    } else if b == b'[' {
                        ScanState::CdataOpen(1)
                    } else if b == b'>' {
                        ScanState::Data
                    } else {
                        self.pending = PendingTagAction::None;
                        self.last_was_slash = false;
                        ScanState::TagRest
                    };
                }
                ScanState::CdataOpen(matched) => {
                    let expected = CDATA_PREFIX.get(usize::from(matched)).copied();
                    self.state = if expected == Some(b) {
                        let next = matched + 1;
                        if usize::from(next) == CDATA_PREFIX.len() {
                            ScanState::Cdata
                        } else {
                            ScanState::CdataOpen(next)
                        }
                    } else if b == b'>' {
                        ScanState::Data
                    } else {
                        self.pending = PendingTagAction::None;
                        self.last_was_slash = false;
                        ScanState::TagRest
                    };
                }
                ScanState::Cdata => {
                    if b == b']' {
                        self.state = ScanState::CdataEnd1;
                    }
                }
                ScanState::CdataEnd1 => {
                    self.state = if b == b']' {
                        ScanState::CdataEnd2
                    } else {
                        ScanState::Cdata
                    };
                }
                ScanState::CdataEnd2 => match b {
                    b'>' => self.state = ScanState::Data,
                    b']' => {}
                    _ => self.state = ScanState::Cdata,
                },
                ScanState::CommentDash1 => {
                    self.state = if b == b'-' {
                        ScanState::Comment
                    } else if b == b'>' {
                        ScanState::Data
                    } else {
                        self.pending = PendingTagAction::None;
                        self.last_was_slash = false;
                        ScanState::TagRest
                    };
                }
                ScanState::Comment => {
                    if b == b'-' {
                        self.state = ScanState::CommentEnd1;
                    }
                }
                ScanState::CommentEnd1 => {
                    self.state = if b == b'-' {
                        ScanState::CommentEnd2
                    } else {
                        ScanState::Comment
                    };
                }
                ScanState::CommentEnd2 => match b {
                    b'>' => self.state = ScanState::Data,
                    b'-' => {}
                    _ => self.state = ScanState::Comment,
                },
                ScanState::StartTagName => match b {
                    b'>' => {
                        self.classify_start_tag();
                        let _ = self.commit_tag();
                    }
                    b'/' => {
                        self.classify_start_tag();
                        self.last_was_slash = true;
                        self.state = ScanState::TagRest;
                    }
                    b if b.is_ascii_whitespace() => {
                        self.classify_start_tag();
                        self.state = ScanState::TagRest;
                    }
                    _ => self.push_name_byte(b),
                },
                ScanState::EndTagName => match b {
                    b'>' => {
                        if self.current_name() == Some(b"details") {
                            self.pending = PendingTagAction::DetailsEnd;
                        }
                        if let Some(kind) = self.commit_tag() {
                            return Some(CloseTagHit {
                                kind,
                                tag_start_in_chunk: self.tag_start_in_chunk.take(),
                                tag_end_in_chunk: i + 1,
                                is_clean_close_tag: self.clean_end_tag,
                            });
                        }
                    }
                    b if b.is_ascii_whitespace() || b == b'/' => {
                        if b == b'/' {
                            self.clean_end_tag = false;
                        }
                        if self.current_name() == Some(b"details") {
                            self.pending = PendingTagAction::DetailsEnd;
                        }
                        self.state = ScanState::TagRest;
                    }
                    _ => self.push_name_byte(b),
                },
                ScanState::TagRest => match b {
                    b'>' => {
                        if let Some(kind) = self.commit_tag() {
                            return Some(CloseTagHit {
                                kind,
                                tag_start_in_chunk: self.tag_start_in_chunk.take(),
                                tag_end_in_chunk: i + 1,
                                is_clean_close_tag: self.clean_end_tag,
                            });
                        }
                    }
                    b'\'' => {
                        self.clean_end_tag = false;
                        self.last_was_slash = false;
                        self.state = ScanState::TagSingleQuote;
                    }
                    b'"' => {
                        self.clean_end_tag = false;
                        self.last_was_slash = false;
                        self.state = ScanState::TagDoubleQuote;
                    }
                    b'/' => {
                        self.clean_end_tag = false;
                        self.last_was_slash = true;
                    }
                    b if b.is_ascii_whitespace() => {}
                    _ => {
                        self.clean_end_tag = false;
                        self.last_was_slash = false;
                    }
                },
                ScanState::TagSingleQuote => {
                    if b == b'\'' {
                        self.state = ScanState::TagRest;
                    }
                }
                ScanState::TagDoubleQuote => {
                    if b == b'"' {
                        self.state = ScanState::TagRest;
                    }
                }
                ScanState::RawText => {
                    if b == b'<' {
                        self.state = ScanState::RawTextLt;
                    }
                }
                ScanState::RawTextLt => {
                    self.state = if b == b'/' {
                        self.reset_name();
                        ScanState::RawTextSlash
                    } else if b == b'<' {
                        ScanState::RawTextLt
                    } else {
                        ScanState::RawText
                    };
                }
                ScanState::RawTextSlash => {
                    if b.is_ascii_alphabetic() {
                        self.push_name_byte(b);
                        self.state = ScanState::RawTextEndName;
                    } else if b == b'<' {
                        self.state = ScanState::RawTextLt;
                    } else {
                        self.state = ScanState::RawText;
                    }
                }
                ScanState::RawTextEndName => match b {
                    b'>' => {
                        if self.current_name() == Some(self.raw_end_tag) {
                            self.state = ScanState::Data;
                        } else {
                            self.state = ScanState::RawText;
                        }
                    }
                    b if b.is_ascii_whitespace() || b == b'/' => {
                        if self.current_name() == Some(self.raw_end_tag) {
                            self.pending = PendingTagAction::None;
                            self.last_was_slash = false;
                            self.state = ScanState::TagRest;
                        } else {
                            self.state = ScanState::RawText;
                        }
                    }
                    b if b.is_ascii_alphabetic() => self.push_name_byte(b),
                    b'<' => self.state = ScanState::RawTextLt,
                    _ => self.state = ScanState::RawText,
                },
                ScanState::Plaintext => {}
            }
        }
        None
    }
}

fn parse_complete_details(
    source: &str,
    tokens: &[SimpleToken],
    body_token_start: usize,
    initially_open: bool,
    summary: Option<String>,
) -> Option<DetailsDirective> {
    let mut end_idx = tokens.len();
    while end_idx > body_token_start {
        if let SimpleToken::Text(t) = &tokens[end_idx - 1]
            && t.trim().is_empty()
        {
            end_idx -= 1;
        } else {
            break;
        }
    }
    if end_idx <= body_token_start {
        return None;
    }
    if !matches!(&tokens[end_idx - 1], SimpleToken::End(name) if name == "details") {
        return None;
    }
    let has_inner_details = tokens[body_token_start..end_idx - 1].iter().any(|tok| {
        matches!(
            tok,
            SimpleToken::Start { name, .. } | SimpleToken::End(name)
                if name == "details" || name == "summary"
        )
    });
    if has_inner_details {
        return None;
    }
    let trimmed = source.trim();
    if let Some(split) = trailing_details_close_start(trimmed) {
        let without_close = trimmed.get(..split)?;
        let body_start = find_body_start_after_open_and_summary(without_close, summary.is_some())?;
        let body_html = without_close.get(body_start..)?.trim().to_owned();
        return Some(DetailsDirective::Complete {
            initially_open,
            summary,
            body_html,
        });
    }
    None
}

fn parse_open_details_with_leading_body(
    source: &str,
    tokens: &[SimpleToken],
    body_token_start: usize,
    initially_open: bool,
    summary: Option<String>,
) -> Option<DetailsDirective> {
    let has_inner_details_or_summary = tokens[body_token_start..].iter().any(|tok| {
        matches!(
            tok,
            SimpleToken::Start { name, .. } | SimpleToken::End(name)
                if name == "details" || name == "summary"
        )
    });
    if has_inner_details_or_summary {
        return None;
    }
    let trimmed = source.trim();
    let body_start = find_body_start_after_open_and_summary(trimmed, summary.is_some())?;
    let body_html = trimmed.get(body_start..)?.trim();
    if body_html.is_empty() {
        return None;
    }
    Some(DetailsDirective::Open {
        initially_open,
        summary,
        leading_body_html: Some(body_html.to_owned()),
    })
}

/// Returns the byte offset of a trailing `</details...>` close tag (allowing ASCII whitespace
/// before `>`) when `trimmed` ends with one.
fn trailing_details_close_start(trimmed: &str) -> Option<usize> {
    let bytes = trimmed.as_bytes();
    if bytes.last() != Some(&b'>') {
        return None;
    }
    let mut end = bytes.len() - 1;
    while end > 0 && bytes.get(end - 1).is_some_and(u8::is_ascii_whitespace) {
        end -= 1;
    }
    let tag = b"</details";
    let start = end.checked_sub(tag.len())?;
    bytes
        .get(start..end)
        .is_some_and(|slice| slice.eq_ignore_ascii_case(tag))
        .then_some(start)
}

fn find_body_start_after_open_and_summary(src: &str, has_summary: bool) -> Option<usize> {
    let open_end = src.find('>')?.checked_add(1)?;
    if !has_summary {
        return Some(open_end);
    }
    let rest = src.get(open_end..)?;
    let lower = rest.to_ascii_lowercase();
    let close_start = lower.find("</summary")?;
    let after_name = close_start.checked_add("</summary".len())?;
    let tail = lower.get(after_name..)?;
    let close_gt = tail.find('>')?;
    if !tail
        .get(..close_gt)?
        .bytes()
        .all(|b| b.is_ascii_whitespace())
    {
        return None;
    }
    let rel_end = after_name.checked_add(close_gt)?.checked_add(1)?;
    open_end.checked_add(rel_end)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_open_summary_and_complete_details() {
        assert_eq!(
            parse_details_directive("<details open>\n<summary>More <b>info</b></summary>\n"),
            Some(DetailsDirective::Open {
                initially_open: true,
                summary: Some("More info".to_owned()),
                leading_body_html: None,
            })
        );
        assert_eq!(
            parse_details_directive("<details><summary>Title</summary><p>Intro</p>"),
            Some(DetailsDirective::Open {
                initially_open: false,
                summary: Some("Title".to_owned()),
                leading_body_html: Some("<p>Intro</p>".to_owned()),
            })
        );
        assert_eq!(
            parse_details_directive("<details><summary>Q</summary><p>A</p></details>"),
            Some(DetailsDirective::Complete {
                initially_open: false,
                summary: Some("Q".to_owned()),
                body_html: "<p>A</p>".to_owned(),
            })
        );
    }

    #[test]
    fn handles_multibyte_utf8_and_multiple_details_without_panicking() {
        assert_eq!(
            parse_details_directive("<details><summary>日本語</summary>😀😀😀😀</details"),
            Some(DetailsDirective::UnsupportedOpens(1))
        );
        assert_eq!(
            parse_details_directive("<details>éééééééééé"),
            Some(DetailsDirective::Open {
                initially_open: false,
                summary: None,
                leading_body_html: Some("éééééééééé".to_owned()),
            })
        );
        assert_eq!(
            parse_details_directive("<details><summary>unfinished          "),
            Some(DetailsDirective::UnsupportedOpens(1))
        );
        assert_eq!(
            parse_details_directive("<details class=\"a\">\n<details class=\"b\">\n"),
            Some(DetailsDirective::UnsupportedOpens(2))
        );
        assert_eq!(
            parse_details_directive(
                "<details><summary>Q</summary>a</details><details><p>b</p></details>"
            ),
            None
        );
        assert_eq!(
            parse_details_directive("<details><summary>日本語</summary ><p>😀</p></details>"),
            Some(DetailsDirective::Complete {
                initially_open: false,
                summary: Some("日本語".to_owned()),
                body_html: "<p>😀</p>".to_owned(),
            })
        );
        assert_eq!(
            parse_details_directive("<details><summary>Info</summary>Body</details >"),
            Some(DetailsDirective::Complete {
                initially_open: false,
                summary: Some("Info".to_owned()),
                body_html: "Body".to_owned(),
            })
        );
    }

    #[test]
    fn rejects_unsafe_attributes_and_streams_unmatched_closes() {
        assert_eq!(
            parse_details_directive("<details onclick=\"alert(1)\"><summary>X</summary>"),
            Some(DetailsDirective::UnsupportedOpens(1))
        );
        assert_eq!(
            parse_details_directive("<details><summary class=\"x\">X</summary>"),
            Some(DetailsDirective::UnsupportedOpens(1))
        );

        let mut stream = DetailsBalanceStream::new();
        let close = stream
            .scan_until_close("<p>Inside</p></details><p>Outside</p>")
            .expect("unmatched close");
        assert_eq!(close.kind, CloseTagKind::Unmatched);
        assert_eq!(close.tag_start_in_chunk, Some(13));
        assert_eq!(close.tag_end_in_chunk, 23);
        assert!(close.is_clean_close_tag);

        let mut complete_stream = DetailsBalanceStream::new();
        let completed = complete_stream
            .scan_until_close("<details><summary>Q</summary><p>A</p></details><p>Outside</p>")
            .expect("completed top-level close");
        assert_eq!(completed.kind, CloseTagKind::CompletedTopLevel);
        assert_eq!(completed.tag_end_in_chunk, 47);

        // Comments, `<script>` raw text, and CDATA must never trigger false `</details>` closes.
        let mut raw_stream = DetailsBalanceStream::new();
        assert!(
            raw_stream
                .scan_until_close(
                    "<!-- </details> --><script></details></script><svg><![CDATA[x > </details> z]]></svg>"
                )
                .is_none()
        );
    }
}
