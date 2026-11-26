//! Bounded, panic-free `CommonMark` container/fence/frontmatter context detector for Source assist.
//!
//! Runs synchronously in the app process without pulling `pulldown-cmark` out of `markdown-service`,
//! while sharing [`crate::frontmatter`] directly with `markdown-core`.

use crate::frontmatter::is_utf16_offset_inside_leading_frontmatter;

const MAX_CONTAINER_DEPTH: usize = 64;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum Container {
    Blockquote,
    ListItem { min_indent: usize },
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
struct OpenFence {
    marker: u8,
    marker_len: usize,
    owner_depth: usize,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum HtmlBlockKind {
    /// `CommonMark` HTML block type 1 (`<pre>`, `<script>`, `<style>`, `<textarea>`).
    Type1,
    /// `CommonMark` HTML block type 2 (`<!-- ... -->`).
    Comment,
    /// `CommonMark` HTML block type 3 (`<? ... ?>`).
    ProcessingInstruction,
    /// `CommonMark` HTML block type 4 (`<!LETTER ... >`).
    Declaration,
    /// `CommonMark` HTML block type 5 (`<![CDATA[ ... ]]>`).
    Cdata,
    /// `CommonMark` HTML block types 6 & 7 (block tag / standalone tag, ends on blank line).
    UntilBlankLine,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
struct OpenHtmlBlock {
    kind: HtmlBlockKind,
    owner_depth: usize,
}

/// Byte/column cursor over one line with `CommonMark` virtual-space tab expansion (4-column stops).
struct LineCursor<'a> {
    bytes: &'a [u8],
    pos: usize,
    col: usize,
    virtual_spaces: usize,
}

impl<'a> LineCursor<'a> {
    fn new(line: &'a str) -> Self {
        Self {
            bytes: line.as_bytes(),
            pos: 0,
            col: 0,
            virtual_spaces: 0,
        }
    }

    fn peek_indent_cols(&self) -> usize {
        let mut cols = self.virtual_spaces;
        let mut c = self.col;
        let mut p = self.pos;
        while let Some(&b) = self.bytes.get(p) {
            match b {
                b' ' => {
                    cols = cols.saturating_add(1);
                    c = c.saturating_add(1);
                    p += 1;
                }
                b'\t' => {
                    let tab = 4 - (c % 4);
                    cols = cols.saturating_add(tab);
                    c = c.saturating_add(tab);
                    p += 1;
                }
                _ => break,
            }
        }
        cols
    }

    fn is_rest_blank(&self) -> bool {
        let mut p = self.pos;
        while let Some(&b) = self.bytes.get(p) {
            if b != b' ' && b != b'\t' {
                return false;
            }
            p += 1;
        }
        true
    }

    fn byte_after_indent(&self) -> Option<u8> {
        let mut p = self.pos;
        while let Some(&b) = self.bytes.get(p) {
            if b != b' ' && b != b'\t' {
                return Some(b);
            }
            p += 1;
        }
        None
    }

    fn rest_after_indent(&self) -> &'a [u8] {
        let mut p = self.pos;
        while let Some(&b) = self.bytes.get(p) {
            if b != b' ' && b != b'\t' {
                break;
            }
            p += 1;
        }
        self.bytes.get(p..).unwrap_or(b"")
    }

    fn consume_indent_cols(&mut self, mut needed: usize) {
        let from_virtual = needed.min(self.virtual_spaces);
        self.virtual_spaces -= from_virtual;
        self.col = self.col.saturating_add(from_virtual);
        needed -= from_virtual;
        while needed > 0 {
            let Some(&b) = self.bytes.get(self.pos) else {
                break;
            };
            match b {
                b' ' => {
                    self.pos += 1;
                    self.col = self.col.saturating_add(1);
                    needed -= 1;
                }
                b'\t' => {
                    let tab = 4 - (self.col % 4);
                    self.pos += 1;
                    if tab <= needed {
                        self.col = self.col.saturating_add(tab);
                        needed -= tab;
                    } else {
                        self.col = self.col.saturating_add(needed);
                        self.virtual_spaces = self.virtual_spaces.saturating_add(tab - needed);
                        needed = 0;
                    }
                }
                _ => break,
            }
        }
    }

    fn try_consume_blockquote(&mut self) -> bool {
        let indent = self.peek_indent_cols();
        if indent > 3 || self.byte_after_indent() != Some(b'>') {
            return false;
        }
        self.consume_indent_cols(indent);
        if self.bytes.get(self.pos) != Some(&b'>') {
            return false;
        }
        self.pos += 1;
        self.col = self.col.saturating_add(1);
        // Optional single space (or 1 column of a tab) following `>`.
        match self.bytes.get(self.pos) {
            Some(b' ') => {
                self.pos += 1;
                self.col = self.col.saturating_add(1);
            }
            Some(b'\t') => {
                let tab = 4 - (self.col % 4);
                self.pos += 1;
                self.col = self.col.saturating_add(1);
                if tab > 1 {
                    self.virtual_spaces = self.virtual_spaces.saturating_add(tab - 1);
                }
            }
            _ => {}
        }
        true
    }

    /// Consumes a bullet or ordered list marker (indent <= 3) and returns the item's `min_indent`.
    ///
    /// When `in_paragraph` is `true`, enforces `CommonMark` §5.2 paragraph-interruption rules:
    /// an empty list item or an ordered list whose start number is not `1` cannot interrupt a
    /// paragraph.
    fn try_consume_list_marker(&mut self, in_paragraph: bool) -> Option<usize> {
        let indent = self.peek_indent_cols();
        if indent > 3 {
            return None;
        }
        let saved_pos = self.pos;
        let saved_col = self.col;
        let saved_virtual = self.virtual_spaces;
        self.consume_indent_cols(indent);

        let (marker_len, is_non_one_ordered) = match self.bytes.get(self.pos)? {
            b'-' | b'*' | b'+' => (1usize, false),
            b'0'..=b'9' => {
                let mut digits = 0usize;
                while digits < 9
                    && self
                        .bytes
                        .get(self.pos + digits)
                        .is_some_and(u8::is_ascii_digit)
                {
                    digits += 1;
                }
                if digits == 0 || !matches!(self.bytes.get(self.pos + digits), Some(b'.' | b')')) {
                    self.pos = saved_pos;
                    self.col = saved_col;
                    self.virtual_spaces = saved_virtual;
                    return None;
                }
                let num_bytes = self.bytes.get(self.pos..self.pos + digits).unwrap_or(b"");
                // CommonMark §5.2 checks the numeric value == 1 (including leading zeros like `01.`).
                let is_one = num_bytes
                    .iter()
                    .rev()
                    .enumerate()
                    .all(|(i, &b)| if i == 0 { b == b'1' } else { b == b'0' });
                (digits + 1, !is_one)
            }
            _ => {
                self.pos = saved_pos;
                self.col = saved_col;
                self.virtual_spaces = saved_virtual;
                return None;
            }
        };

        let after_marker = self.pos + marker_len;
        let next = self.bytes.get(after_marker).copied();
        if !matches!(next, None | Some(b' ' | b'\t')) {
            self.pos = saved_pos;
            self.col = saved_col;
            self.virtual_spaces = saved_virtual;
            return None;
        }

        // Advance across marker.
        self.pos = after_marker;
        self.col = self.col.saturating_add(marker_len);
        if in_paragraph && (is_non_one_ordered || self.is_rest_blank()) {
            self.pos = saved_pos;
            self.col = saved_col;
            self.virtual_spaces = saved_virtual;
            return None;
        }
        let spaces_after = self.peek_indent_cols();
        // CommonMark rule: empty list item or >= 5 spaces after marker uses 1 space width.
        let padding =
            if next.is_none() || spaces_after == 0 || spaces_after >= 5 || self.is_rest_blank() {
                1
            } else {
                spaces_after
            };
        if next.is_some() {
            self.consume_indent_cols(padding);
        }
        let marker_width = marker_len.saturating_add(padding);
        Some(indent.saturating_add(marker_width))
    }
}

fn is_thematic_break(cursor: &LineCursor<'_>) -> bool {
    if cursor.peek_indent_cols() > 3 {
        return false;
    }
    let rest = cursor.rest_after_indent();
    let Some(&first) = rest.first() else {
        return false;
    };
    if first != b'-' && first != b'*' && first != b'_' {
        return false;
    }
    let mut count = 0usize;
    for &b in rest {
        if b == first {
            count += 1;
        } else if b != b' ' && b != b'\t' {
            return false;
        }
    }
    count >= 3
}

fn is_atx_heading(cursor: &LineCursor<'_>) -> bool {
    if cursor.peek_indent_cols() > 3 {
        return false;
    }
    let rest = cursor.rest_after_indent();
    let hashes = rest.iter().take_while(|&&b| b == b'#').count();
    (1..=6).contains(&hashes) && matches!(rest.get(hashes), None | Some(b' ' | b'\t'))
}

fn is_setext_heading_underline(cursor: &LineCursor<'_>) -> bool {
    if cursor.peek_indent_cols() > 3 {
        return false;
    }
    let rest = cursor.rest_after_indent();
    let Some(&first) = rest.first() else {
        return false;
    };
    if first != b'=' && first != b'-' {
        return false;
    }
    let run = rest.iter().take_while(|&&b| b == first).count();
    run > 0
        && rest
            .get(run..)
            .unwrap_or(b"")
            .iter()
            .all(|&b| b == b' ' || b == b'\t')
}

const HTML_TYPE1_TAGS: &[&[u8]] = &[b"pre", b"script", b"style", b"textarea"];

const HTML_TYPE6_TAGS: &[&[u8]] = &[
    b"address",
    b"article",
    b"aside",
    b"base",
    b"basefont",
    b"blockquote",
    b"body",
    b"caption",
    b"center",
    b"col",
    b"colgroup",
    b"dd",
    b"details",
    b"dialog",
    b"dir",
    b"div",
    b"dl",
    b"dt",
    b"fieldset",
    b"figcaption",
    b"figure",
    b"footer",
    b"form",
    b"frame",
    b"frameset",
    b"h1",
    b"h2",
    b"h3",
    b"h4",
    b"h5",
    b"h6",
    b"head",
    b"header",
    b"hr",
    b"html",
    b"iframe",
    b"legend",
    b"li",
    b"link",
    b"main",
    b"menu",
    b"menuitem",
    b"nav",
    b"noframes",
    b"ol",
    b"optgroup",
    b"option",
    b"p",
    b"param",
    b"search",
    b"section",
    b"summary",
    b"table",
    b"tbody",
    b"td",
    b"tfoot",
    b"th",
    b"thead",
    b"title",
    b"tr",
    b"track",
    b"ul",
];

fn starts_with_ascii_ci(haystack: &[u8], needle: &[u8]) -> bool {
    haystack
        .get(..needle.len())
        .is_some_and(|prefix| prefix.eq_ignore_ascii_case(needle))
}

fn contains_ascii_ci(haystack: &[u8], needle: &[u8]) -> bool {
    haystack
        .windows(needle.len())
        .any(|w| w.eq_ignore_ascii_case(needle))
}

fn parse_html_block_start(cursor: &LineCursor<'_>, in_paragraph: bool) -> Option<HtmlBlockKind> {
    if cursor.peek_indent_cols() > 3 {
        return None;
    }
    let rest = cursor.rest_after_indent();
    if rest.first() != Some(&b'<') {
        return None;
    }
    // Type 2: `<!--`
    if rest.starts_with(b"<!--") {
        return Some(HtmlBlockKind::Comment);
    }
    // Type 3: `<?`
    if rest.starts_with(b"<?") {
        return Some(HtmlBlockKind::ProcessingInstruction);
    }
    // Type 5: `<![CDATA[`
    if rest.starts_with(b"<![CDATA[") {
        return Some(HtmlBlockKind::Cdata);
    }
    // Type 4: `<!` followed by an ASCII letter
    if rest.starts_with(b"<!") && rest.get(2).is_some_and(u8::is_ascii_alphabetic) {
        return Some(HtmlBlockKind::Declaration);
    }
    // Type 1: `<pre`, `<script`, `<style`, `<textarea` + space/tab/`>`/EOL
    let after_lt = rest.get(1..).unwrap_or(b"");
    for &tag in HTML_TYPE1_TAGS {
        if starts_with_ascii_ci(after_lt, tag)
            && matches!(after_lt.get(tag.len()), None | Some(b' ' | b'\t' | b'>'))
        {
            return Some(HtmlBlockKind::Type1);
        }
    }
    // Type 6: `<` or `</` + block tag name + space/tab/`>`/`/>`/EOL
    let tag_slice = after_lt.strip_prefix(b"/").unwrap_or(after_lt);
    let name_len = tag_slice
        .iter()
        .take_while(|&&b| b.is_ascii_alphanumeric())
        .count();
    if name_len > 0 {
        let name = tag_slice.get(..name_len).unwrap_or(b"");
        let after_name = tag_slice.get(name_len..).unwrap_or(b"");
        let valid_delim = matches!(after_name.first(), None | Some(b' ' | b'\t' | b'>'))
            || after_name.starts_with(b"/>");
        if valid_delim
            && HTML_TYPE6_TAGS
                .iter()
                .any(|&t| t.eq_ignore_ascii_case(name))
        {
            return Some(HtmlBlockKind::UntilBlankLine);
        }
    }
    // Type 7: standalone open/close tag on its own line (cannot interrupt a paragraph).
    if !in_paragraph && is_standalone_html_type7_tag(rest) {
        return Some(HtmlBlockKind::UntilBlankLine);
    }
    None
}

fn is_standalone_html_type7_tag(rest: &[u8]) -> bool {
    let Some(after_lt) = rest.strip_prefix(b"<") else {
        return false;
    };
    let (is_close, slice) = match after_lt.strip_prefix(b"/") {
        Some(s) => (true, s),
        None => (false, after_lt),
    };
    if !slice.first().is_some_and(u8::is_ascii_alphabetic) {
        return false;
    }
    let name_len = slice
        .iter()
        .take_while(|&&b| b.is_ascii_alphanumeric() || b == b'-')
        .count();
    let name = slice.get(..name_len).unwrap_or(b"");
    if !is_close
        && HTML_TYPE1_TAGS
            .iter()
            .any(|&t| t.eq_ignore_ascii_case(name))
    {
        return false;
    }
    let mut i = name_len;
    if is_close {
        while slice.get(i).is_some_and(|&b| b == b' ' || b == b'\t') {
            i += 1;
        }
        if slice.get(i) != Some(&b'>') {
            return false;
        }
        return slice
            .get(i + 1..)
            .unwrap_or(b"")
            .iter()
            .all(|&b| b == b' ' || b == b'\t');
    }
    // Open tag: scan attributes respecting quoted values until `>` or `/>`, then only spaces/tabs.
    let mut quote: Option<u8> = None;
    while let Some(&b) = slice.get(i) {
        if let Some(q) = quote {
            if b == q {
                quote = None;
            }
        } else {
            match b {
                b'\'' | b'"' => quote = Some(b),
                b'<' => return false,
                b'>' => {
                    return slice
                        .get(i + 1..)
                        .unwrap_or(b"")
                        .iter()
                        .all(|&c| c == b' ' || c == b'\t');
                }
                _ => {}
            }
        }
        i += 1;
    }
    false
}

fn html_block_closes_on_line(kind: HtmlBlockKind, bytes: &[u8]) -> bool {
    match kind {
        HtmlBlockKind::Type1 => {
            contains_ascii_ci(bytes, b"</pre>")
                || contains_ascii_ci(bytes, b"</script>")
                || contains_ascii_ci(bytes, b"</style>")
                || contains_ascii_ci(bytes, b"</textarea>")
        }
        HtmlBlockKind::Comment => bytes.windows(3).any(|w| w == b"-->"),
        HtmlBlockKind::ProcessingInstruction => bytes.windows(2).any(|w| w == b"?>"),
        HtmlBlockKind::Declaration => bytes.contains(&b'>'),
        HtmlBlockKind::Cdata => bytes.windows(3).any(|w| w == b"]]>"),
        HtmlBlockKind::UntilBlankLine => false,
    }
}

fn parse_fence_open(cursor: &LineCursor<'_>) -> Option<(u8, usize)> {
    if cursor.peek_indent_cols() > 3 {
        return None;
    }
    let rest = cursor.rest_after_indent();
    let &first = rest.first()?;
    if first != b'`' && first != b'~' {
        return None;
    }
    let count = rest.iter().take_while(|&&b| b == first).count();
    if count < 3 {
        return None;
    }
    let info = rest.get(count..).unwrap_or(b"");
    if first == b'`' && info.contains(&b'`') {
        return None;
    }
    Some((first, count))
}

fn is_fence_close(cursor: &LineCursor<'_>, fence: OpenFence) -> bool {
    if cursor.peek_indent_cols() > 3 {
        return false;
    }
    let rest = cursor.rest_after_indent();
    if rest.first() != Some(&fence.marker) {
        return false;
    }
    let count = rest.iter().take_while(|&&b| b == fence.marker).count();
    if count < fence.marker_len {
        return false;
    }
    rest.get(count..)
        .unwrap_or(b"")
        .iter()
        .all(|&b| b == b' ' || b == b'\t')
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

fn match_open_containers(cursor: &mut LineCursor<'_>, containers: &mut Vec<Container>) -> usize {
    let mut matched = 0usize;
    for &container in containers.iter() {
        match container {
            Container::Blockquote => {
                if cursor.try_consume_blockquote() {
                    matched += 1;
                } else {
                    break;
                }
            }
            Container::ListItem { min_indent } => {
                if cursor.is_rest_blank() {
                    // Blank lines keep open list items (and list-nested fences) alive.
                    matched += 1;
                } else if cursor.peek_indent_cols() >= min_indent {
                    cursor.consume_indent_cols(min_indent);
                    matched += 1;
                } else {
                    break;
                }
            }
        }
    }
    containers.truncate(matched);
    matched
}

fn consume_new_containers(
    cursor: &mut LineCursor<'_>,
    containers: &mut Vec<Container>,
    mut in_paragraph: bool,
) -> bool {
    let depth_before = containers.len();
    while containers.len() < MAX_CONTAINER_DEPTH {
        if cursor.try_consume_blockquote() {
            containers.push(Container::Blockquote);
            in_paragraph = false;
        } else if is_thematic_break(cursor) {
            // Thematic breaks (`- - -`, `***`, `___`) take precedence over list markers.
            break;
        } else if let Some(min_indent) = cursor.try_consume_list_marker(in_paragraph) {
            containers.push(Container::ListItem { min_indent });
            in_paragraph = false;
        } else {
            break;
        }
    }
    containers.len() > depth_before
}

enum HtmlStep {
    NotInHtml,
    ConsumedLine { literal_target: bool },
}

fn step_open_html_block(
    open_html: &mut Option<OpenHtmlBlock>,
    cursor: &LineCursor<'_>,
    matched_containers: usize,
    in_paragraph: &mut bool,
) -> HtmlStep {
    let Some(html) = *open_html else {
        return HtmlStep::NotInHtml;
    };
    if matched_containers < html.owner_depth {
        *open_html = None;
        return HtmlStep::NotInHtml;
    }
    if html.kind == HtmlBlockKind::UntilBlankLine && cursor.is_rest_blank() {
        *open_html = None;
        *in_paragraph = false;
        return HtmlStep::ConsumedLine {
            literal_target: false,
        };
    }
    if html_block_closes_on_line(html.kind, cursor.rest_after_indent()) {
        *open_html = None;
    }
    *in_paragraph = false;
    HtmlStep::ConsumedLine {
        literal_target: true,
    }
}

fn step_open_fence(
    open_fence: &mut Option<OpenFence>,
    cursor: &LineCursor<'_>,
    matched_containers: usize,
    is_target_line: bool,
    in_paragraph: &mut bool,
) -> bool {
    let Some(fence) = *open_fence else {
        return false;
    };
    if matched_containers < fence.owner_depth {
        *open_fence = None;
        return false;
    }
    if !is_target_line && is_fence_close(cursor, fence) {
        *open_fence = None;
        *in_paragraph = false;
        return true;
    }
    false
}

fn step_leaf_block(
    cursor: &mut LineCursor<'_>,
    containers: &[Container],
    open_html: &mut Option<OpenHtmlBlock>,
    open_fence: &mut Option<OpenFence>,
    in_paragraph: &mut bool,
    is_target_line: bool,
) -> bool {
    if cursor.is_rest_blank() {
        *in_paragraph = false;
    } else if let Some(kind) = parse_html_block_start(cursor, *in_paragraph) {
        if !html_block_closes_on_line(kind, cursor.rest_after_indent()) {
            *open_html = Some(OpenHtmlBlock {
                kind,
                owner_depth: containers.len(),
            });
        }
        *in_paragraph = false;
        if is_target_line {
            return true;
        }
    } else if is_thematic_break(cursor) {
        *in_paragraph = false;
        if is_target_line {
            return true;
        }
    } else if is_atx_heading(cursor) {
        *in_paragraph = false;
    } else if let Some((marker, marker_len)) = parse_fence_open(cursor) {
        *open_fence = Some(OpenFence {
            marker,
            marker_len,
            owner_depth: containers.len(),
        });
        *in_paragraph = false;
    } else if is_target_line && !*in_paragraph && cursor.peek_indent_cols() >= 4 {
        return true;
    } else if is_target_line && *in_paragraph && !containers.contains(&Container::Blockquote) {
        // Any non-blockquote target line that stayed inside an open paragraph without opening a
        // new container (e.g. `Paragraph\n    + `, `Paragraph\n1234567890. `, `Paragraph\n2. `,
        // `Paragraph\n+ `) is plain paragraph continuation text, never a list item.
        return true;
    } else {
        *in_paragraph = *in_paragraph || cursor.peek_indent_cols() < 4;
    }
    false
}

/// Returns whether `offset_utf16` lies inside leading YAML frontmatter, an open fenced code block,
/// an open HTML block, a thematic break, or an indented code block inside `CommonMark` containers.
#[must_use]
pub fn is_utf16_offset_in_markdown_literal_context(text: &str, offset_utf16: usize) -> bool {
    if is_utf16_offset_inside_leading_frontmatter(text, offset_utf16) {
        return true;
    }
    let limit = utf16_offset_to_byte(text, offset_utf16);
    let prefix = text.get(..limit).unwrap_or(text);
    if prefix.is_empty() {
        return false;
    }

    let mut containers: Vec<Container> = Vec::new();
    let mut open_fence: Option<OpenFence> = None;
    let mut open_html: Option<OpenHtmlBlock> = None;
    let mut in_paragraph = false;

    let mut line_start = 0usize;
    loop {
        let remaining = prefix.get(line_start..).unwrap_or("");
        let (raw_line, next_start, is_target_line) = match remaining.find('\n') {
            Some(nl) => (
                remaining.get(..nl).unwrap_or(""),
                line_start + nl + 1,
                false,
            ),
            None => (remaining, prefix.len(), true),
        };
        let line = raw_line.strip_suffix('\r').unwrap_or(raw_line);
        let mut cursor = LineCursor::new(line);

        // Step 1: Match open containers from outermost to innermost.
        let prev_container_depth = containers.len();
        let matched = match_open_containers(&mut cursor, &mut containers);
        if matched < prev_container_depth {
            in_paragraph = false;
        }

        // Step 2: Check open HTML block (CommonMark types 1–7) survival or closing condition.
        if let HtmlStep::ConsumedLine { literal_target } =
            step_open_html_block(&mut open_html, &cursor, matched, &mut in_paragraph)
        {
            if is_target_line {
                return literal_target;
            }
            line_start = next_start;
            continue;
        }

        // Step 3: Check open fence survival or closing fence line.
        let closed_by_closer = step_open_fence(
            &mut open_fence,
            &cursor,
            matched,
            is_target_line,
            &mut in_paragraph,
        );

        // Step 4: When outside any open fence, check Setext underlines before consuming new
        // containers (since `Title\n- ` is a Setext heading underline, not an empty list item).
        if open_fence.is_none() && !closed_by_closer {
            if in_paragraph && is_setext_heading_underline(&cursor) {
                in_paragraph = false;
                if is_target_line {
                    return true;
                }
                line_start = next_start;
                continue;
            }
            if consume_new_containers(&mut cursor, &mut containers, in_paragraph) {
                in_paragraph = false;
            }
            if step_leaf_block(
                &mut cursor,
                &containers,
                &mut open_html,
                &mut open_fence,
                &mut in_paragraph,
                is_target_line,
            ) {
                return true;
            }
        }

        if is_target_line {
            break;
        }
        line_start = next_start;
    }

    open_fence.is_some()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn at_end(text: &str) -> bool {
        is_utf16_offset_in_markdown_literal_context(text, text.encode_utf16().count())
    }

    #[test]
    fn handles_fences_indented_code_and_nested_containers() {
        assert!(at_end("```md\n- item"));
        assert!(!at_end("```\ncode\n```\n- item"));
        assert!(at_end("```\n    ```\n- literal"));
        assert!(at_end("    - "));
        assert!(at_end("1.  Item\n    ```\n    - "));
        assert!(at_end("- ```\n  - code"));
        assert!(!at_end("10. ```\n    code\n    ```\n- next"));
        assert!(!at_end("10. ```\n    code\n- outside"));
        assert!(!at_end("10. ```\n    code\n    ```\n    - nested"));
        // Outdented bare fence after 2-space list fence opens a new top-level code block.
        assert!(at_end("- item\n  ```\n```\n- fenced text"));
        // Nested list fence at outer item's 2-space indent followed by unindented fence.
        assert!(at_end("- outer\n  - inner\n  ```\n```\n- literal"));
        // Blockquote nested inside an indented list item with a fenced code block.
        assert!(at_end("10. item\n    > ~~~\n    > - literal"));
        // Leaving the blockquote closes the fence.
        assert!(!at_end("10. item\n    > ~~~\n    - outside"));
        // Indented code inside a blockquote inside a list item.
        assert!(at_end("10. item\n    >     - code"));
        // Normal list item inside a blockquote inside a list item is NOT literal code.
        assert!(!at_end("10. item\n    > - list"));
        // Leading thematic break vs frontmatter.
        assert!(!at_end("---\n\n- item"));
        assert!(!at_end("---\n# Heading\n- item"));
        assert!(at_end("---\ntags:\n  - tag"));
        assert!(at_end("---\ntitle: Doc\ntags:\n\n  - alpha"));
        // Thematic break (`- - -`) takes precedence over list markers, so a 2-space fence opens,
        // and the thematic break line itself is never treated as a list item.
        assert!(at_end("- - -"));
        assert!(at_end("* * *"));
        assert!(at_end("- - -\n  ```\n- "));
        // Fence marker inside HTML blocks (comments, <pre>, <div>, <?...?>, CDATA) does NOT open
        // a code fence for a following real list.
        assert!(!at_end("<!--\n```\n-->\n- item"));
        assert!(!at_end("<!-- ``` -->\n- item"));
        assert!(!at_end("<pre>\n```\n</pre>\n- item"));
        assert!(!at_end("<div>\n```\n</div>\n\n- item"));
        assert!(at_end("<div>\n```\n</div>\n- item"));
        assert!(!at_end("<?note\n```\n?>\n- item"));
        assert!(!at_end("<![CDATA[\n```\n]]>\n- item"));
        // ATX and Setext headings (including single `-` underline) end paragraph state and are
        // never treated as list items.
        assert!(at_end("# Heading\n<span>\n- item"));
        assert!(at_end("Title\n===\n<span>\n- item"));
        assert!(at_end("Title\n- "));
        assert!(at_end("Title\n-\n  <span>\n- item"));
        assert!(!at_end("Paragraph\n<span>\n- item"));
        // CommonMark §5.2: empty list items and ordered lists not starting at `1` cannot interrupt
        // a paragraph, so Enter must not delete or continue them.
        assert!(at_end("Paragraph\n2. "));
        assert!(at_end("Paragraph\n+ "));
        assert!(at_end("Paragraph\n2. item"));
        assert!(at_end("Paragraph\n    + "));
        assert!(at_end("Paragraph\n1234567890. "));
        assert!(!at_end("> line 1\n> line 2"));
        assert!(!at_end("Paragraph\n1. item"));
        assert!(!at_end("Paragraph\n01. item"));
        assert!(!at_end("Paragraph\n+ item"));
        assert!(!at_end("Paragraph\n\n2. "));
        assert!(!at_end("Paragraph\n\n+ "));
        assert!(!at_end("1. first\n2. "));
        assert!(!at_end("- first\n- "));
    }
}
