//! Strict, bounded leading YAML frontmatter extraction shared between Reading and Source assist.

use std::ops::Range;

/// Maximum raw byte length of a frontmatter block (64 KiB).
pub const MAX_FRONTMATTER_BYTES: usize = 64 * 1024;

/// Maximum raw lines scanned inside a frontmatter block.
pub const MAX_FRONTMATTER_LINES: usize = 512;

/// Maximum distinct top-level properties emitted for one frontmatter card.
pub const MAX_FRONTMATTER_PROPERTIES: usize = 64;

/// Maximum UTF-8 byte length for one property key.
const MAX_KEY_BYTES: usize = 128;

/// Maximum UTF-8 byte length for one formatted property value.
const MAX_VALUE_BYTES: usize = 1024;

/// One parsed top-level frontmatter property with its exact source byte range.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct FrontmatterProperty {
    /// Trimmed top-level YAML property key.
    pub key: String,
    /// Formatted, bounded property value text.
    pub value: String,
    /// Exact UTF-8 source byte span covering this property in the input.
    pub byte_range: Range<usize>,
}

/// Extracted leading frontmatter and the byte offset where Markdown body resumes.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct FrontmatterExtract {
    /// Parsed top-level properties in source order.
    pub properties: Vec<FrontmatterProperty>,
    /// UTF-8 byte offset where the Markdown body begins after the closing delimiter.
    pub body_byte_offset: usize,
}

/// Extracts strictly validated leading `---` YAML frontmatter at byte 0.
///
/// Returns `None` if no valid bounded frontmatter exists, allowing normal
/// Markdown parsing (e.g., documents beginning with a thematic break).
#[must_use]
pub fn extract_frontmatter(input: &str) -> Option<FrontmatterExtract> {
    let first_line_end = line_end_after_open_fence(input)?;
    let rest = input.get(first_line_end..)?;
    let mut cursor = first_line_end;
    let mut lines: Vec<(Range<usize>, &str)> = Vec::new();
    let mut body_byte_offset: Option<usize> = None;

    for raw_line in rest.split_inclusive('\n') {
        let line_start = cursor;
        let line_end = cursor.checked_add(raw_line.len())?;
        if line_end > MAX_FRONTMATTER_BYTES || lines.len() >= MAX_FRONTMATTER_LINES {
            return None;
        }
        let content = strip_line_ending(raw_line);
        if is_closing_fence(content) {
            body_byte_offset = Some(line_end);
            break;
        }
        lines.push((line_start..line_end, content));
        cursor = line_end;
    }

    let body_byte_offset = body_byte_offset?;
    let properties = parse_frontmatter_lines(&lines)?;
    Some(FrontmatterExtract {
        properties,
        body_byte_offset,
    })
}

/// Returns whether a UTF-16 cursor offset lies inside valid leading YAML frontmatter.
///
/// Uses the exact same parser rules as [`extract_frontmatter`] for both completed frontmatter
/// blocks and in-progress unclosed frontmatter blocks being typed at the top of the document.
#[must_use]
pub fn is_utf16_offset_inside_leading_frontmatter(input: &str, offset_utf16: usize) -> bool {
    let offset_bytes = utf16_offset_to_byte(input, offset_utf16);
    if let Some(first_line) = input.split_inclusive('\n').next() {
        let first_content = strip_line_ending(first_line);
        if first_content != "---" {
            return false;
        }
        if offset_bytes <= first_content.len() {
            return true;
        }
    } else {
        return false;
    }

    if let Some(fm) = extract_frontmatter(input) {
        let closing_ends_with_nl = input
            .get(..fm.body_byte_offset)
            .is_some_and(|prefix| prefix.ends_with('\n'));
        return if closing_ends_with_nl {
            offset_bytes < fm.body_byte_offset
        } else {
            offset_bytes <= fm.body_byte_offset
        };
    }

    // No valid closed frontmatter: allow in-progress unclosed frontmatter only when no closing
    // delimiter has appeared yet, room remains for a closing `---` within the byte/line limits,
    // and all lines typed so far validate as real frontmatter properties.
    let Some(first_line_end) = line_end_after_open_fence(input) else {
        return false;
    };
    let Some(rest) = input.get(first_line_end..) else {
        return false;
    };
    let mut cursor = first_line_end;
    let mut lines: Vec<(Range<usize>, &str)> = Vec::new();
    for raw_line in rest.split_inclusive('\n') {
        let Some(line_end) = cursor.checked_add(raw_line.len()) else {
            return false;
        };
        // Reserve space for at least `\n---` (4 bytes) and one closing fence line within limits.
        if line_end.saturating_add(4) > MAX_FRONTMATTER_BYTES
            || lines.len().saturating_add(1) >= MAX_FRONTMATTER_LINES
        {
            return false;
        }
        let content = strip_line_ending(raw_line);
        if is_closing_fence(content) {
            // A closing delimiter already existed yet `extract_frontmatter` rejected the block.
            return false;
        }
        lines.push((cursor..line_end, content));
        cursor = line_end;
    }
    parse_frontmatter_lines(&lines).is_some() && offset_bytes <= cursor
}

fn utf16_offset_to_byte(text: &str, target_utf16: usize) -> usize {
    let mut utf16 = 0usize;
    for (byte_idx, ch) in text.char_indices() {
        if utf16 >= target_utf16 {
            return byte_idx;
        }
        utf16 = utf16.saturating_add(ch.len_utf16());
    }
    text.len()
}

fn parse_frontmatter_lines(lines: &[(Range<usize>, &str)]) -> Option<Vec<FrontmatterProperty>> {
    if lines.is_empty() {
        return None;
    }
    let mut properties: Vec<FrontmatterProperty> = Vec::new();
    let mut idx = 0usize;
    while let Some((range, line)) = lines.get(idx) {
        let trimmed = line.trim();
        if trimmed.is_empty() || trimmed.starts_with('#') {
            idx += 1;
            continue;
        }
        // Reject leading indentation on top-level lines (unexpected structure).
        if line.starts_with(' ') || line.starts_with('\t') {
            return None;
        }
        let (key, raw_value) = split_key_value(line)?;
        if key.is_empty()
            || key.len() > MAX_KEY_BYTES
            || !is_valid_key(key)
            || has_unsupported_yaml_construct(raw_value)
        {
            return None;
        }
        let mut entry_end = range.end;
        let formatted_value = if raw_value.is_empty() {
            // Expect zero or more flat block list items (`- item`) at one consistent indentation.
            let mut items = Vec::new();
            let mut expected_dash_indent: Option<usize> = None;
            let mut next = idx + 1;
            while let Some((item_range, item_line)) = lines.get(next) {
                let item_trimmed = item_line.trim();
                if item_trimmed.is_empty() || item_trimmed.starts_with('#') {
                    next += 1;
                    continue;
                }
                let leading_spaces = item_line.bytes().take_while(|&b| b == b' ').count();
                if item_line.as_bytes().get(leading_spaces) == Some(&b'\t') {
                    return None;
                }
                let after_indent = item_line.get(leading_spaces..).unwrap_or("");
                if let Some(after_dash) = after_indent.strip_prefix('-') {
                    if !after_dash.is_empty() && !after_dash.starts_with(' ') {
                        return None;
                    }
                    if let Some(expected) = expected_dash_indent {
                        if leading_spaces != expected {
                            return None;
                        }
                    } else {
                        expected_dash_indent = Some(leading_spaces);
                    }
                    let item_val = strip_inline_comment(after_dash.trim()).trim();
                    if item_val.is_empty()
                        || is_unsupported_sequence_item(item_val)
                        || item_val.starts_with('[')
                        || item_val == "-"
                        || item_val.starts_with("- ")
                        || item_val.starts_with("-\t")
                    {
                        return None;
                    }
                    let unquoted = unquote_scalar(item_val)?;
                    items.push(unquoted);
                    entry_end = item_range.end;
                    next += 1;
                    continue;
                }
                if item_line.starts_with(' ') || item_line.starts_with('\t') {
                    // Nested map or unsupported indented continuation -> bail safely.
                    return None;
                }
                break;
            }
            idx = next;
            join_bounded(&items)?
        } else {
            idx += 1;
            format_inline_value(raw_value)?
        };

        if properties.len() >= MAX_FRONTMATTER_PROPERTIES {
            return None;
        }
        properties.push(FrontmatterProperty {
            key: key.to_owned(),
            value: formatted_value,
            byte_range: range.start..entry_end,
        });
    }

    if properties.is_empty() {
        return None;
    }
    Some(properties)
}

fn line_end_after_open_fence(input: &str) -> Option<usize> {
    let first_line = input.split_inclusive('\n').next()?;
    if !first_line.ends_with('\n') {
        return None;
    }
    let content = strip_line_ending(first_line);
    (content == "---").then_some(first_line.len())
}

fn strip_line_ending(raw_line: &str) -> &str {
    let no_lf = raw_line.strip_suffix('\n').unwrap_or(raw_line);
    no_lf.strip_suffix('\r').unwrap_or(no_lf)
}

fn is_closing_fence(content: &str) -> bool {
    let trimmed = content.trim_end_matches([' ', '\t']);
    trimmed == "---" || trimmed == "..."
}

fn split_key_value(line: &str) -> Option<(&str, &str)> {
    let colon = line.find(':')?;
    let key = line.get(..colon)?.trim();
    let after = line.get(colon + 1..)?;
    if !after.is_empty() && !after.starts_with([' ', '\t']) {
        return None;
    }
    Some((key, strip_inline_comment(after.trim())))
}

fn is_valid_key(key: &str) -> bool {
    key.bytes()
        .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'_' | b'-' | b'.' | b' '))
        && key
            .bytes()
            .next()
            .is_some_and(|b| b.is_ascii_alphanumeric() || b == b'_')
}

fn has_unsupported_yaml_construct(value: &str) -> bool {
    let trimmed = value.trim();
    if trimmed.is_empty() {
        return false;
    }
    matches!(
        trimmed,
        "|" | ">" | "|-" | ">-" | "|+" | ">+" | "{" | "}" | "?" | "-" | ":"
    ) || trimmed.starts_with(['&', '*', '!', '{', '}', '|', '>', ',', ']', '%', '@', '`'])
        || trimmed.starts_with("? ")
        || trimmed.starts_with("?\t")
        || trimmed.starts_with("- ")
        || trimmed.starts_with("-\t")
        || trimmed.starts_with(": ")
        || trimmed.starts_with(":\t")
}

fn strip_inline_comment(value: &str) -> &str {
    let bytes = value.as_bytes();
    let in_flow_seq = value.starts_with('[');
    let mut in_single = false;
    let mut in_double = false;
    let mut at_scalar_start = true;
    let mut i = 0;
    while let Some(&b) = bytes.get(i) {
        if in_double {
            if b == b'\\' && i + 1 < bytes.len() {
                i += 2;
                continue;
            }
            if b == b'"' {
                in_double = false;
            }
        } else if in_single {
            if b == b'\'' {
                if bytes.get(i + 1) == Some(&b'\'') {
                    i += 2;
                    continue;
                }
                in_single = false;
            }
        } else {
            let prev = i.checked_sub(1).and_then(|p| bytes.get(p)).copied();
            match b {
                b'"' if at_scalar_start => {
                    in_double = true;
                    at_scalar_start = false;
                }
                b'\'' if at_scalar_start => {
                    in_single = true;
                    at_scalar_start = false;
                }
                b'#' if prev.is_none_or(|p| p.is_ascii_whitespace()) => {
                    return value.get(..i).map_or(value, str::trim_end);
                }
                b'[' | b',' if in_flow_seq => {
                    at_scalar_start = true;
                }
                _ if b.is_ascii_whitespace() => {}
                _ => {
                    at_scalar_start = false;
                }
            }
        }
        i += 1;
    }
    value
}

fn split_flow_sequence_items(inner: &str) -> Option<Vec<&str>> {
    let bytes = inner.as_bytes();
    let mut items = Vec::new();
    let mut start = 0;
    let mut in_single = false;
    let mut in_double = false;
    let mut at_scalar_start = true;
    let mut i = 0;
    while let Some(&b) = bytes.get(i) {
        if in_double {
            if b == b'\\' && i + 1 < bytes.len() {
                i += 2;
                continue;
            }
            if b == b'"' {
                in_double = false;
            }
        } else if in_single {
            if b == b'\'' {
                if bytes.get(i + 1) == Some(&b'\'') {
                    i += 2;
                    continue;
                }
                in_single = false;
            }
        } else {
            match b {
                b'"' if at_scalar_start => {
                    in_double = true;
                    at_scalar_start = false;
                }
                b'\'' if at_scalar_start => {
                    in_single = true;
                    at_scalar_start = false;
                }
                b',' => {
                    items.push(inner.get(start..i)?);
                    start = i + 1;
                    at_scalar_start = true;
                }
                b'[' | b']' | b'{' | b'}' => return None,
                b if b.is_ascii_whitespace() => {}
                _ => at_scalar_start = false,
            }
        }
        i += 1;
    }
    if in_single || in_double {
        return None;
    }
    items.push(inner.get(start..)?);
    Some(items)
}

fn is_unsupported_sequence_item(trimmed: &str) -> bool {
    has_unsupported_yaml_construct(trimmed)
        || (!trimmed.starts_with('"')
            && !trimmed.starts_with('\'')
            && (trimmed.contains(": ") || trimmed.contains(":\t") || trimmed.ends_with(':')))
}

fn format_inline_value(raw: &str) -> Option<String> {
    if raw.starts_with('[') {
        let inner = raw.strip_prefix('[')?.strip_suffix(']')?;
        let parts = split_flow_sequence_items(inner)?;
        if parts.len() == 1 && parts.first().is_some_and(|p| p.trim().is_empty()) {
            return Some(String::new());
        }
        let mut items = Vec::with_capacity(parts.len());
        for (idx, part) in parts.iter().enumerate() {
            let trimmed = part.trim();
            if trimmed.is_empty() {
                // YAML 1.2.2 §7.4.1 permits a single trailing comma after non-empty entries,
                // never empty interior or leading entries like `[one, ,two]` or `[, one]`.
                if idx + 1 == parts.len() && !items.is_empty() {
                    continue;
                }
                return None;
            }
            if is_unsupported_sequence_item(trimmed) {
                return None;
            }
            items.push(unquote_scalar(trimmed)?);
        }
        return join_bounded(&items);
    }
    if !raw.starts_with('"')
        && !raw.starts_with('\'')
        && (raw.contains(": ") || raw.contains(":\t") || raw.ends_with(':'))
    {
        return None;
    }
    let val = unquote_scalar(raw)?;
    if val.len() > MAX_VALUE_BYTES {
        return None;
    }
    Some(val)
}

fn parse_hex_escape(chars: &mut std::str::Chars<'_>, digits: usize) -> Option<char> {
    let mut code = 0u32;
    for _ in 0..digits {
        let hex = chars.next()?;
        let val = hex.to_digit(16)?;
        code = (code << 4) | val;
    }
    let ch = char::from_u32(code)?;
    match ch {
        '\n' | '\r' | '\t' => Some(' '),
        c if c.is_control() => None,
        c => Some(c),
    }
}

fn unquote_scalar(raw: &str) -> Option<String> {
    let trimmed = raw.trim();
    if let Some(rest) = trimmed.strip_prefix('"') {
        let mut out = String::with_capacity(rest.len());
        let mut chars = rest.chars();
        while let Some(ch) = chars.next() {
            match ch {
                '\\' => match chars.next()? {
                    'n' | 't' | 'r' => out.push(' '),
                    '"' => out.push('"'),
                    '\\' => out.push('\\'),
                    '/' => out.push('/'),
                    'x' => out.push(parse_hex_escape(&mut chars, 2)?),
                    'u' => out.push(parse_hex_escape(&mut chars, 4)?),
                    _ => return None,
                },
                '"' => {
                    // Reject any trailing text or extra quotes after the first closing quote.
                    if chars.next().is_some() {
                        return None;
                    }
                    return (out.len() <= MAX_VALUE_BYTES).then_some(out);
                }
                _ => out.push(ch),
            }
        }
        return None;
    }
    if let Some(rest) = trimmed.strip_prefix('\'') {
        let mut out = String::with_capacity(rest.len());
        let mut chars = rest.chars().peekable();
        while let Some(ch) = chars.next() {
            if ch == '\'' {
                if chars.peek() == Some(&'\'') {
                    let _ = chars.next();
                    out.push('\'');
                } else {
                    // Reject any trailing text or extra quotes after the first closing quote.
                    if chars.next().is_some() {
                        return None;
                    }
                    return (out.len() <= MAX_VALUE_BYTES).then_some(out);
                }
            } else {
                out.push(ch);
            }
        }
        return None;
    }
    (trimmed.len() <= MAX_VALUE_BYTES).then(|| trimmed.to_owned())
}

fn join_bounded(items: &[String]) -> Option<String> {
    let joined = items.join(", ");
    (joined.len() <= MAX_VALUE_BYTES).then_some(joined)
}

#[cfg(test)]
#[allow(clippy::expect_used, clippy::indexing_slicing)]
mod tests {
    use super::*;

    #[test]
    fn extracts_scalars_inline_lists_and_block_lists() {
        let doc = "---\ntitle: \"BeauTyXT Guide\"\ndate: 2026-11-24\ntags: [android, rust]\nauthors:\n  - soupslurpr\n  - contributor\n---\n# Heading\n";
        let fm = extract_frontmatter(doc).expect("valid frontmatter");
        assert_eq!(fm.properties.len(), 4);
        assert_eq!(fm.properties[0].key, "title");
        assert_eq!(fm.properties[0].value, "BeauTyXT Guide");
        assert_eq!(fm.properties[1].key, "date");
        assert_eq!(fm.properties[1].value, "2026-11-24");
        assert_eq!(fm.properties[2].key, "tags");
        assert_eq!(fm.properties[2].value, "android, rust");
        assert_eq!(fm.properties[3].key, "authors");
        assert_eq!(fm.properties[3].value, "soupslurpr, contributor");
        assert_eq!(&doc[fm.body_byte_offset..], "# Heading\n");
    }

    #[test]
    fn handles_quoted_comments_commas_and_yaml_escapes() {
        let doc = "---\ntitle: \"Quarterly report\" # note\nnote: \"a # b\" # trailing\nowner: Alice's report # note\ntags: # block comment\n  - alpha\n  - beta\nflow: [\"a,b\", c]\npeople: [Alice's, Bob]\nunicode: [café, notes, \"日本語, テスト\"]\nescaped: \"A\\u0042 \\x43\"\n---\nBody\n";
        let fm = extract_frontmatter(doc).expect("valid quoted frontmatter");
        assert_eq!(fm.properties.len(), 8);
        assert_eq!(fm.properties[0].value, "Quarterly report");
        assert_eq!(fm.properties[1].value, "a # b");
        assert_eq!(fm.properties[2].value, "Alice's report");
        assert_eq!(fm.properties[3].value, "alpha, beta");
        assert_eq!(fm.properties[4].value, "a,b, c");
        assert_eq!(fm.properties[5].value, "Alice's, Bob");
        assert_eq!(fm.properties[6].value, "café, notes, 日本語, テスト");
        assert_eq!(fm.properties[7].value, "AB C");
        // Unsupported, malformed, or unterminated quoted values fall back to raw Markdown.
        assert!(extract_frontmatter("---\nname: \"bad\\z\"\n---\nBody\n").is_none());
        assert!(extract_frontmatter("---\nname: \"bad\\z\n---\nBody\n").is_none());
        assert!(extract_frontmatter("---\nname: 'unterminated\n---\nBody\n").is_none());
        assert!(extract_frontmatter("---\nname: \"bad\\uD800\"\n---\nBody\n").is_none());
        assert!(extract_frontmatter("---\nname: \"bad\\u0000\"\n---\nBody\n").is_none());
        assert!(extract_frontmatter("---\ntags: [\"unclosed, c]\n---\nBody\n").is_none());
        // Trailing text or unescaped interior quotes after the first closing quote are rejected.
        assert!(extract_frontmatter("---\nname: \"bad\"tail\"\n---\nBody\n").is_none());
        assert!(extract_frontmatter("---\nname: 'bad'tail'\n---\nBody\n").is_none());
        assert!(extract_frontmatter("---\ntags: [\"a\"b, \"c\"]\n---\nBody\n").is_none());
        // Unclosed or empty-slot YAML flow sequences fall back to raw Markdown.
        assert!(extract_frontmatter("---\ntags: [one, two\n---\nBody\n").is_none());
        assert!(extract_frontmatter("---\ntags: [one, ,two]\n---\nBody\n").is_none());
        assert!(extract_frontmatter("---\ntags: [, one]\n---\nBody\n").is_none());
        assert!(extract_frontmatter("---\ntags: [,]\n---\nBody\n").is_none());
        // Flow- and block-sequence implicit mappings fall back to raw Markdown.
        assert!(extract_frontmatter("---\ntags: [one: two, three]\n---\nBody\n").is_none());
        assert!(extract_frontmatter("---\ntags: [key:, three]\n---\nBody\n").is_none());
        assert!(extract_frontmatter("---\ntags:\n  - one: two\n  - three\n---\nBody\n").is_none());
        assert!(extract_frontmatter("---\ntags:\n  - key:\n  - three\n---\nBody\n").is_none());
        assert!(extract_frontmatter("---\ntags:\n  - - nested\n---\nBody\n").is_none());
        // Nested block sequence under a bare `-` (or inconsistent indentation) falls back cleanly.
        assert!(
            extract_frontmatter("---\ntags:\n  -\n    - alpha\n    - beta\n---\nBody\n").is_none()
        );
        assert!(extract_frontmatter("---\ntags:\n  - alpha\n    - beta\n---\nBody\n").is_none());
        // Nested block sequence separated by a tab (`- -\talpha`) falls back to raw Markdown.
        assert!(extract_frontmatter("---\ntags:\n  - -\talpha\n---\nBody\n").is_none());
        // Explicit YAML mapping keys (`? key`) in flow/block sequences fall back to raw Markdown.
        assert!(extract_frontmatter("---\ntags: [? key]\n---\nBody\n").is_none());
        assert!(extract_frontmatter("---\ntags:\n  - ? key\n---\nBody\n").is_none());
        // Block-sequence items support trailing YAML `#` comments without falling back.
        let block_comments =
            extract_frontmatter("---\ntags:\n  - \"a\" # note\n  - plain # comment\n---\nBody\n")
                .expect("block items with inline comments stay valid");
        assert_eq!(block_comments.properties[0].value, "a, plain");
        let block_quoted_colon = extract_frontmatter(
            "---\ntags:\n  - \"one: two\"\n  - https://example.org\n---\nBody\n",
        )
        .expect("quoted colon and URL in block sequence stay valid");
        assert_eq!(
            block_quoted_colon.properties[0].value,
            "one: two, https://example.org"
        );
        let quoted_colon =
            extract_frontmatter("---\ntags: [\"one: two\", https://example.org]\n---\nBody\n")
                .expect("quoted colon and URL plain scalar stay valid");
        assert_eq!(
            quoted_colon.properties[0].value,
            "one: two, https://example.org"
        );
        let trailing_comma = extract_frontmatter("---\ntags: [one, two,]\n---\nBody\n")
            .expect("valid trailing comma");
        assert_eq!(trailing_comma.properties[0].value, "one, two");
        let empty_seq = extract_frontmatter("---\ntags: []\n---\nBody\n").expect("valid empty seq");
        assert_eq!(empty_seq.properties[0].value, "");
        let escaped_single = extract_frontmatter("---\nname: 'it''s ok'\n---\nBody\n")
            .expect("escaped single quote");
        assert_eq!(escaped_single.properties[0].value, "it's ok");
        // `\u000A` and `\n` decode identically to a single space.
        let nl_doc = "---\na: \"x\\ny\"\nb: \"x\\u000Ay\"\n---\nBody\n";
        let nl_fm = extract_frontmatter(nl_doc).expect("consistent newline escapes");
        assert_eq!(nl_fm.properties[0].value, "x y");
        assert_eq!(nl_fm.properties[1].value, "x y");
        // Mid-scalar apostrophe must not swallow trailing YAML `#` comments.
        let apostrophe_comment =
            extract_frontmatter("---\ntitle: Follow-up 'notes # comment\n---\nBody\n")
                .expect("plain scalar with apostrophe and comment");
        assert_eq!(apostrophe_comment.properties[0].value, "Follow-up 'notes");
        let quoted_hash_seq =
            extract_frontmatter("---\ntags: ['a # b', \"c # d\"] # trailing\n---\nBody\n")
                .expect("quoted # inside flow sequence stays intact while trailing comment strips");
        assert_eq!(quoted_hash_seq.properties[0].value, "a # b, c # d");
    }

    #[test]
    fn rejects_thematic_break_and_unsafe_yaml() {
        assert!(extract_frontmatter("---\n\nHello world\n---\n").is_none());
        assert!(extract_frontmatter("---\nanchor: &a foo\n---\nBody\n").is_none());
        assert!(extract_frontmatter("---\nnested:\n  child: value\n---\nBody\n").is_none());
        assert!(extract_frontmatter("---\ntitle: one: two\n---\nBody\n").is_none());
        assert!(extract_frontmatter("---\ntitle: trailing:\n---\nBody\n").is_none());
        assert!(extract_frontmatter("---\ntitle: @draft\n---\nBody\n").is_none());
        assert!(extract_frontmatter("---\ntitle: `code`\n---\nBody\n").is_none());
        assert!(extract_frontmatter("---\ntitle: %tag\n---\nBody\n").is_none());
        assert!(extract_frontmatter("---\ntitle: ,bad\n---\nBody\n").is_none());
        assert!(extract_frontmatter("---\ntitle: ]bad\n---\nBody\n").is_none());
        assert!(extract_frontmatter("---\ntitle: \"@draft\"\n---\nBody\n").is_some());
        assert!(extract_frontmatter("---\ncount: -42\n---\nBody\n").is_some());
        assert!(extract_frontmatter("---\ntitle: \"one: two\"\n---\nBody\n").is_some());
        assert!(extract_frontmatter("---\nurl: https://example.org\n---\nBody\n").is_some());
        assert!(extract_frontmatter("---\ntitle: unclosed\nBody\n").is_none());
    }

    #[test]
    fn matches_source_offset_helper_for_closed_unclosed_and_overlimit_frontmatter() {
        let unclosed = "---\ntags:\n  - tag";
        assert!(is_utf16_offset_inside_leading_frontmatter(
            unclosed,
            unclosed.encode_utf16().count()
        ));
        // 510 comment lines + `tags:` + `- item` = 512 body lines -> no room for closing fence.
        let mut at_limit = String::from("---\ntags:\n");
        for _ in 0..510 {
            at_limit.push_str("# c\n");
        }
        at_limit.push_str("  - item");
        assert!(!is_utf16_offset_inside_leading_frontmatter(
            &at_limit,
            at_limit.encode_utf16().count()
        ));
        // Multi-byte UTF-8 exceeding 64 KiB byte cap falls back to Markdown.
        let big_utf8 = format!("---\ntitle: {}\n- item", "é".repeat(33_000));
        assert!(!is_utf16_offset_inside_leading_frontmatter(
            &big_utf8,
            big_utf8.encode_utf16().count()
        ));
        // 65-char key (within 128-byte cap) is valid frontmatter.
        let long_key = format!("---\n{}:\n  - entry", "a".repeat(65));
        assert!(is_utf16_offset_inside_leading_frontmatter(
            &long_key,
            long_key.encode_utf16().count()
        ));
    }
}
