//! Family-based, strictly linear-time line scanners.

#![allow(clippy::too_many_lines)]

use crate::{LineState, SyntaxLanguage, SyntaxToken, TokenKind, char_utf16_len, count_utf16};

const MODE_NORMAL: u8 = 0;
const MODE_BLOCK_COMMENT: u8 = 1;
const MODE_TRIPLE_DOUBLE_QUOTE: u8 = 2;
const MODE_TRIPLE_SINGLE_QUOTE: u8 = 3;
const MODE_BACKTICK_STRING: u8 = 4;
const MODE_MD_FENCE_BACKTICK: u8 = 5;
const MODE_MD_FENCE_TILDE: u8 = 6;
const MODE_HTML_COMMENT: u8 = 7;
const MODE_RUST_RAW_STRING: u8 = 8;

const EX_TICK: u8 = 1 << 0;
const EX_DOLLAR: u8 = 1 << 1;
const EX_STAR: u8 = 1 << 2;
const EX_UNDER: u8 = 1 << 3;
const EX_TILDE: u8 = 1 << 4;
const EX_LINK: u8 = 1 << 5;

type Sink<'a> = Option<(&'a mut Vec<SyntaxToken>, &'a mut bool)>;

struct LineEmitter<'a> {
    base_utf16: u32,
    sink: Sink<'a>,
    remaining: usize,
    last_end: u32,
}

impl<'a> LineEmitter<'a> {
    fn new(base_utf16: u32, sink: Sink<'a>, cap: usize) -> Self {
        Self {
            base_utf16,
            sink,
            remaining: cap,
            last_end: base_utf16,
        }
    }

    fn emit(&mut self, start_rel: u32, end_rel: u32, kind: TokenKind) {
        if start_rel >= end_rel {
            return;
        }
        let Some((tokens, hit_cap)) = self.sink.as_mut() else {
            return;
        };
        let Some(start) = self.base_utf16.checked_add(start_rel) else {
            **hit_cap = true;
            return;
        };
        let Some(end) = self.base_utf16.checked_add(end_rel) else {
            **hit_cap = true;
            return;
        };
        if start < self.last_end {
            return;
        }
        if self.remaining == 0 {
            **hit_cap = true;
            return;
        }
        tokens.push(SyntaxToken {
            start_utf16: start,
            end_utf16: end,
            kind,
        });
        self.remaining -= 1;
        self.last_end = end;
    }
}

pub(crate) fn scan_line(
    language: SyntaxLanguage,
    line: &str,
    state: LineState,
    base_utf16: u32,
    sink: Sink<'_>,
    cap: usize,
) -> LineState {
    let mut emit = LineEmitter::new(base_utf16, sink, cap);
    match language {
        SyntaxLanguage::Plain => LineState::NORMAL,
        SyntaxLanguage::Diff => {
            scan_diff(line, &mut emit);
            LineState::NORMAL
        }
        SyntaxLanguage::Json => {
            scan_json(line, &mut emit);
            LineState::NORMAL
        }
        SyntaxLanguage::Yaml => {
            scan_yaml(line, &mut emit);
            LineState::NORMAL
        }
        SyntaxLanguage::Toml | SyntaxLanguage::Ini => {
            scan_toml_ini(line, language == SyntaxLanguage::Toml, &mut emit);
            LineState::NORMAL
        }
        SyntaxLanguage::HtmlXml => scan_html_xml(line, state, &mut emit),
        SyntaxLanguage::Css => scan_css(line, state, &mut emit),
        SyntaxLanguage::Markdown => scan_markdown(line, state, &mut emit),
        SyntaxLanguage::Python | SyntaxLanguage::Shell | SyntaxLanguage::Ruby => {
            scan_hash_family(language, line, state, &mut emit)
        }
        SyntaxLanguage::Sql => scan_sql(line, state, &mut emit),
        SyntaxLanguage::Lua => scan_lua(line, state, &mut emit),
        SyntaxLanguage::Rust
        | SyntaxLanguage::Kotlin
        | SyntaxLanguage::Java
        | SyntaxLanguage::C
        | SyntaxLanguage::Cpp
        | SyntaxLanguage::CSharp
        | SyntaxLanguage::Go
        | SyntaxLanguage::Swift
        | SyntaxLanguage::Zig
        | SyntaxLanguage::JavaScript
        | SyntaxLanguage::TypeScript => scan_c_family(language, line, state, &mut emit),
    }
}

struct Cursor<'a> {
    src: &'a str,
    bytes: &'a [u8],
    pos: usize,
    utf16: u32,
}

impl<'a> Cursor<'a> {
    const fn new(src: &'a str) -> Self {
        Self {
            src,
            bytes: src.as_bytes(),
            pos: 0,
            utf16: 0,
        }
    }

    const fn is_eof(&self) -> bool {
        self.pos >= self.bytes.len()
    }

    fn peek_byte(&self) -> Option<u8> {
        self.bytes.get(self.pos).copied()
    }

    fn peek_byte_at(&self, offset: usize) -> Option<u8> {
        self.pos
            .checked_add(offset)
            .and_then(|idx| self.bytes.get(idx).copied())
    }

    fn rest(&self) -> &'a str {
        self.src.get(self.pos..).unwrap_or("")
    }

    fn starts_with(&self, needle: &str) -> bool {
        self.rest().starts_with(needle)
    }

    fn advance_char(&mut self) -> Option<char> {
        let ch = self.rest().chars().next()?;
        self.pos = self.pos.saturating_add(ch.len_utf8());
        self.utf16 = self.utf16.saturating_add(char_utf16_len(ch));
        Some(ch)
    }

    fn advance_ascii(&mut self, count: usize) {
        for _ in 0..count {
            if self.pos < self.bytes.len() {
                let _ = self.advance_char();
            }
        }
    }

    fn advance_to_end(&mut self) {
        self.utf16 = self.utf16.saturating_add(count_utf16(self.rest()));
        self.pos = self.bytes.len();
    }

    fn skip_whitespace(&mut self) {
        while let Some(b) = self.peek_byte() {
            if b.is_ascii_whitespace() {
                self.advance_ascii(1);
            } else {
                break;
            }
        }
    }

    fn eat_ident(&mut self) -> &'a str {
        let start = self.pos;
        while let Some(b) = self.peek_byte() {
            if b.is_ascii_alphanumeric() || b == b'_' {
                self.advance_ascii(1);
            } else {
                break;
            }
        }
        self.src.get(start..self.pos).unwrap_or("")
    }

    fn eat_number(&mut self) {
        if self.peek_byte() == Some(b'0')
            && matches!(
                self.peek_byte_at(1),
                Some(b'x' | b'X' | b'b' | b'B' | b'o' | b'O')
            )
        {
            self.advance_ascii(2);
            while let Some(b) = self.peek_byte() {
                if b.is_ascii_hexdigit() || b == b'_' {
                    self.advance_ascii(1);
                } else {
                    break;
                }
            }
        } else {
            while let Some(b) = self.peek_byte() {
                if b.is_ascii_digit() || b == b'_' {
                    self.advance_ascii(1);
                } else {
                    break;
                }
            }
            if self.peek_byte() == Some(b'.')
                && self.peek_byte_at(1).is_some_and(|b| b.is_ascii_digit())
            {
                self.advance_ascii(1);
                while let Some(b) = self.peek_byte() {
                    if b.is_ascii_digit() || b == b'_' {
                        self.advance_ascii(1);
                    } else {
                        break;
                    }
                }
            }
            if matches!(self.peek_byte(), Some(b'e' | b'E')) {
                let sign_off = usize::from(matches!(self.peek_byte_at(1), Some(b'+' | b'-')));
                if self
                    .peek_byte_at(1 + sign_off)
                    .is_some_and(|b| b.is_ascii_digit())
                {
                    self.advance_ascii(1 + sign_off);
                    while let Some(b) = self.peek_byte() {
                        if b.is_ascii_digit() || b == b'_' {
                            self.advance_ascii(1);
                        } else {
                            break;
                        }
                    }
                }
            }
        }
        // Optional numeric type suffix (e.g. u64, f32, L, UL).
        while let Some(b) = self.peek_byte() {
            if b.is_ascii_alphanumeric() || b == b'_' {
                self.advance_ascii(1);
            } else {
                break;
            }
        }
    }

    fn eat_quoted(&mut self, quote: u8, allow_escapes: bool) {
        self.advance_ascii(1);
        while let Some(b) = self.peek_byte() {
            if allow_escapes && b == b'\\' {
                self.advance_ascii(1);
                let _ = self.advance_char();
            } else if b == quote {
                self.advance_ascii(1);
                return;
            } else {
                let _ = self.advance_char();
            }
        }
    }
}

#[allow(clippy::too_many_lines)]
fn scan_c_family(
    lang: SyntaxLanguage,
    line: &str,
    state: LineState,
    emit: &mut LineEmitter<'_>,
) -> LineState {
    let nested_comments = matches!(
        lang,
        SyntaxLanguage::Rust | SyntaxLanguage::Kotlin | SyntaxLanguage::Swift
    );
    let has_backtick_strings = matches!(
        lang,
        SyntaxLanguage::JavaScript | SyntaxLanguage::TypeScript | SyntaxLanguage::Go
    );
    let has_preprocessor = matches!(
        lang,
        SyntaxLanguage::C | SyntaxLanguage::Cpp | SyntaxLanguage::CSharp
    );
    let mut cur = Cursor::new(line);
    let mut mode = state.mode();
    let mut depth = state.param();

    if mode == MODE_BLOCK_COMMENT {
        let start = cur.utf16;
        let (closed, next_depth) = scan_block_comment(&mut cur, depth.max(1), nested_comments);
        emit.emit(start, cur.utf16, TokenKind::Comment);
        if !closed {
            return LineState::new(MODE_BLOCK_COMMENT, next_depth, 0, 0);
        }
        mode = MODE_NORMAL;
        depth = 0;
    } else if mode == MODE_BACKTICK_STRING && has_backtick_strings {
        let start = cur.utf16;
        let closed = scan_backtick_tail(&mut cur, lang != SyntaxLanguage::Go);
        emit.emit(start, cur.utf16, TokenKind::String);
        if !closed {
            return LineState::new(MODE_BACKTICK_STRING, 0, 0, 0);
        }
        mode = MODE_NORMAL;
    } else if mode == MODE_TRIPLE_DOUBLE_QUOTE
        && matches!(
            lang,
            SyntaxLanguage::Kotlin | SyntaxLanguage::Java | SyntaxLanguage::Swift
        )
    {
        let start = cur.utf16;
        let closed = scan_triple_tail(&mut cur, "\"\"\"", false);
        emit.emit(start, cur.utf16, TokenKind::String);
        if !closed {
            return LineState::new(MODE_TRIPLE_DOUBLE_QUOTE, 0, 0, 0);
        }
        mode = MODE_NORMAL;
    } else if mode == MODE_RUST_RAW_STRING && lang == SyntaxLanguage::Rust {
        let start = cur.utf16;
        let closed = scan_rust_raw_string_tail(&mut cur, depth);
        emit.emit(start, cur.utf16, TokenKind::String);
        if !closed {
            return LineState::new(MODE_RUST_RAW_STRING, depth, 0, 0);
        }
        mode = MODE_NORMAL;
        depth = 0;
    }

    let _ = mode;
    let _ = depth;

    // Leading preprocessor directive (`#include`, `#define`, `#if`, etc.) or Rust attribute (`#[...]`).
    cur.skip_whitespace();
    if has_preprocessor && cur.peek_byte() == Some(b'#') {
        let start = cur.utf16;
        cur.advance_ascii(1);
        cur.skip_whitespace();
        let _ = cur.eat_ident();
        emit.emit(start, cur.utf16, TokenKind::Attribute);
    }

    while let Some(b) = cur.peek_byte() {
        if b.is_ascii_whitespace() {
            cur.advance_ascii(1);
            continue;
        }
        // Line comment `//`
        if b == b'/' && cur.peek_byte_at(1) == Some(b'/') {
            let start = cur.utf16;
            cur.advance_to_end();
            emit.emit(start, cur.utf16, TokenKind::Comment);
            return LineState::NORMAL;
        }
        // Block comment `/*`
        if b == b'/' && cur.peek_byte_at(1) == Some(b'*') {
            let start = cur.utf16;
            cur.advance_ascii(2);
            let (closed, next_depth) = scan_block_comment(&mut cur, 1, nested_comments);
            emit.emit(start, cur.utf16, TokenKind::Comment);
            if !closed {
                return LineState::new(MODE_BLOCK_COMMENT, next_depth, 0, 0);
            }
            continue;
        }
        // Rust attribute `#[...]` / `#![...]`
        if lang == SyntaxLanguage::Rust
            && b == b'#'
            && (cur.peek_byte_at(1) == Some(b'[')
                || (cur.peek_byte_at(1) == Some(b'!') && cur.peek_byte_at(2) == Some(b'[')))
        {
            let start = cur.utf16;
            let mut bracket_depth: u32 = 0;
            while let Some(ch) = cur.advance_char() {
                if ch == '[' {
                    bracket_depth = bracket_depth.saturating_add(1);
                } else if ch == ']' {
                    bracket_depth = bracket_depth.saturating_sub(1);
                    if bracket_depth == 0 {
                        break;
                    }
                }
            }
            emit.emit(start, cur.utf16, TokenKind::Attribute);
            continue;
        }
        // Annotations / decorators `@Name`
        if b == b'@'
            && matches!(
                lang,
                SyntaxLanguage::Kotlin
                    | SyntaxLanguage::Java
                    | SyntaxLanguage::TypeScript
                    | SyntaxLanguage::JavaScript
                    | SyntaxLanguage::Swift
                    | SyntaxLanguage::Zig
            )
            && cur
                .peek_byte_at(1)
                .is_some_and(|n| n.is_ascii_alphabetic() || n == b'_')
        {
            let start = cur.utf16;
            cur.advance_ascii(1);
            let _ = cur.eat_ident();
            emit.emit(start, cur.utf16, TokenKind::Attribute);
            continue;
        }
        // Triple-quoted string `"""`
        if b == b'"'
            && cur.starts_with("\"\"\"")
            && matches!(
                lang,
                SyntaxLanguage::Kotlin | SyntaxLanguage::Java | SyntaxLanguage::Swift
            )
        {
            let start = cur.utf16;
            cur.advance_ascii(3);
            let closed = scan_triple_tail(&mut cur, "\"\"\"", false);
            emit.emit(start, cur.utf16, TokenKind::String);
            if !closed {
                return LineState::new(MODE_TRIPLE_DOUBLE_QUOTE, 0, 0, 0);
            }
            continue;
        }
        // Rust raw strings: `r"..."`, `r#"..."#`, `br#"..."#`, `cr#"..."#`
        if lang == SyntaxLanguage::Rust
            && let Some((prefix_bytes, hashes)) = match_rust_raw_string_open(&cur)
        {
            let start = cur.utf16;
            cur.advance_ascii(prefix_bytes);
            let closed = scan_rust_raw_string_tail(&mut cur, hashes);
            emit.emit(start, cur.utf16, TokenKind::String);
            if !closed {
                return LineState::new(MODE_RUST_RAW_STRING, hashes, 0, 0);
            }
            continue;
        }
        // Double-quoted string
        if b == b'"' {
            let start = cur.utf16;
            cur.eat_quoted(b'"', true);
            emit.emit(start, cur.utf16, TokenKind::String);
            continue;
        }
        // Backtick string (JS/TS template or Go raw string)
        if b == b'`' && has_backtick_strings {
            let start = cur.utf16;
            cur.advance_ascii(1);
            let closed = scan_backtick_tail(&mut cur, lang != SyntaxLanguage::Go);
            emit.emit(start, cur.utf16, TokenKind::String);
            if !closed {
                return LineState::new(MODE_BACKTICK_STRING, 0, 0, 0);
            }
            continue;
        }
        // Single-quoted char/string (for Rust, distinguish lifetime `'a` from char `'x'`!)
        if b == b'\'' {
            if lang == SyntaxLanguage::Rust {
                if let Some(next) = cur.peek_byte_at(1) {
                    if next == b'\\' || cur.peek_byte_at(2) == Some(b'\'') {
                        let start = cur.utf16;
                        cur.eat_quoted(b'\'', true);
                        emit.emit(start, cur.utf16, TokenKind::String);
                        continue;
                    }
                    if next.is_ascii_alphabetic() || next == b'_' {
                        let start = cur.utf16;
                        cur.advance_ascii(1);
                        let _ = cur.eat_ident();
                        emit.emit(start, cur.utf16, TokenKind::Attribute);
                        continue;
                    }
                }
            } else {
                let start = cur.utf16;
                cur.eat_quoted(b'\'', true);
                emit.emit(start, cur.utf16, TokenKind::String);
                continue;
            }
        }
        // Numbers
        if b.is_ascii_digit() {
            let start = cur.utf16;
            cur.eat_number();
            emit.emit(start, cur.utf16, TokenKind::Number);
            continue;
        }
        // Identifiers / Keywords / Types / Functions / Macros
        if b.is_ascii_alphabetic() || b == b'_' {
            let start = cur.utf16;
            let ident = cur.eat_ident();
            let end = cur.utf16;
            if lang == SyntaxLanguage::Rust && cur.peek_byte() == Some(b'!') {
                cur.advance_ascii(1);
                emit.emit(start, cur.utf16, TokenKind::Attribute);
                continue;
            }
            if let Some(kind) = classify_c_ident(lang, ident, cur.peek_byte()) {
                emit.emit(start, end, kind);
            }
            continue;
        }
        let _ = cur.advance_char();
    }
    LineState::NORMAL
}

fn scan_block_comment(cur: &mut Cursor<'_>, mut depth: u8, nested: bool) -> (bool, u8) {
    while let Some(b) = cur.peek_byte() {
        if nested && b == b'/' && cur.peek_byte_at(1) == Some(b'*') {
            cur.advance_ascii(2);
            depth = depth.saturating_add(1).min(15);
        } else if b == b'*' && cur.peek_byte_at(1) == Some(b'/') {
            cur.advance_ascii(2);
            depth = depth.saturating_sub(1);
            if depth == 0 {
                return (true, 0);
            }
        } else {
            let _ = cur.advance_char();
        }
    }
    (false, depth)
}

fn scan_backtick_tail(cur: &mut Cursor<'_>, allow_escapes: bool) -> bool {
    while let Some(b) = cur.peek_byte() {
        if allow_escapes && b == b'\\' {
            cur.advance_ascii(1);
            let _ = cur.advance_char();
        } else if b == b'`' {
            cur.advance_ascii(1);
            return true;
        } else {
            let _ = cur.advance_char();
        }
    }
    false
}

fn scan_triple_tail(cur: &mut Cursor<'_>, delim: &str, allow_escapes: bool) -> bool {
    while !cur.is_eof() {
        if allow_escapes && cur.peek_byte() == Some(b'\\') {
            cur.advance_ascii(1);
            let _ = cur.advance_char();
        } else if cur.starts_with(delim) {
            cur.advance_ascii(3);
            return true;
        } else {
            let _ = cur.advance_char();
        }
    }
    false
}

/// Matches Rust raw string openings (`r"`, `r#"`, `br#"`, `cr#"`) and returns `(prefix_bytes, hash_count)`.
fn match_rust_raw_string_open(cur: &Cursor<'_>) -> Option<(usize, u8)> {
    let prefix = match cur.peek_byte()? {
        b'r' => 1usize,
        b'b' | b'c' if cur.peek_byte_at(1) == Some(b'r') => 2usize,
        _ => return None,
    };
    let mut hashes: usize = 0;
    while cur.peek_byte_at(prefix + hashes) == Some(b'#') {
        hashes += 1;
        if hashes > usize::from(u8::MAX) {
            return None;
        }
    }
    if cur.peek_byte_at(prefix + hashes) != Some(b'"') {
        return None;
    }
    let hash_u8 = u8::try_from(hashes).ok()?;
    Some((prefix + hashes + 1, hash_u8))
}

fn scan_rust_raw_string_tail(cur: &mut Cursor<'_>, hashes: u8) -> bool {
    let needed = usize::from(hashes);
    while let Some(b) = cur.peek_byte() {
        if b == b'"' {
            let mut matched = 0usize;
            while matched < needed && cur.peek_byte_at(1 + matched) == Some(b'#') {
                matched += 1;
            }
            if matched == needed {
                cur.advance_ascii(1 + needed);
                return true;
            }
            cur.advance_ascii(1);
        } else {
            let _ = cur.advance_char();
        }
    }
    false
}

fn classify_c_ident(lang: SyntaxLanguage, ident: &str, next_byte: Option<u8>) -> Option<TokenKind> {
    if matches!(
        ident,
        "true" | "false" | "null" | "nil" | "None" | "undefined" | "NaN" | "Infinity"
    ) {
        return Some(TokenKind::Number);
    }
    if is_c_keyword(lang, ident) {
        return Some(TokenKind::Keyword);
    }
    if is_c_primitive_type(lang, ident) {
        return Some(TokenKind::Type);
    }
    if next_byte == Some(b'(') {
        return Some(TokenKind::Function);
    }
    if ident.bytes().next().is_some_and(|b| b.is_ascii_uppercase())
        && ident.bytes().any(|b| b.is_ascii_lowercase())
    {
        return Some(TokenKind::Type);
    }
    None
}

fn is_c_keyword(lang: SyntaxLanguage, w: &str) -> bool {
    match lang {
        SyntaxLanguage::Rust => matches!(
            w,
            "as" | "async"
                | "await"
                | "break"
                | "const"
                | "continue"
                | "crate"
                | "dyn"
                | "else"
                | "enum"
                | "extern"
                | "fn"
                | "for"
                | "if"
                | "impl"
                | "in"
                | "let"
                | "loop"
                | "match"
                | "mod"
                | "move"
                | "mut"
                | "pub"
                | "ref"
                | "return"
                | "self"
                | "Self"
                | "static"
                | "struct"
                | "super"
                | "trait"
                | "type"
                | "unsafe"
                | "use"
                | "where"
                | "while"
        ),
        SyntaxLanguage::Kotlin => matches!(
            w,
            "abstract"
                | "actual"
                | "annotation"
                | "as"
                | "break"
                | "by"
                | "catch"
                | "class"
                | "companion"
                | "const"
                | "constructor"
                | "continue"
                | "crossinline"
                | "data"
                | "do"
                | "else"
                | "enum"
                | "expect"
                | "external"
                | "final"
                | "finally"
                | "for"
                | "fun"
                | "get"
                | "if"
                | "import"
                | "in"
                | "infix"
                | "init"
                | "inline"
                | "inner"
                | "interface"
                | "internal"
                | "is"
                | "lateinit"
                | "noinline"
                | "object"
                | "open"
                | "operator"
                | "out"
                | "override"
                | "package"
                | "private"
                | "protected"
                | "public"
                | "reified"
                | "return"
                | "sealed"
                | "set"
                | "super"
                | "suspend"
                | "tailrec"
                | "this"
                | "throw"
                | "try"
                | "typealias"
                | "val"
                | "var"
                | "vararg"
                | "when"
                | "where"
                | "while"
        ),
        SyntaxLanguage::Java => matches!(
            w,
            "abstract"
                | "assert"
                | "break"
                | "case"
                | "catch"
                | "class"
                | "continue"
                | "default"
                | "do"
                | "else"
                | "enum"
                | "extends"
                | "final"
                | "finally"
                | "for"
                | "if"
                | "implements"
                | "import"
                | "instanceof"
                | "interface"
                | "native"
                | "new"
                | "package"
                | "permits"
                | "private"
                | "protected"
                | "public"
                | "record"
                | "return"
                | "sealed"
                | "static"
                | "super"
                | "switch"
                | "synchronized"
                | "this"
                | "throw"
                | "throws"
                | "transient"
                | "try"
                | "var"
                | "volatile"
                | "while"
                | "yield"
        ),
        SyntaxLanguage::C | SyntaxLanguage::Cpp => matches!(
            w,
            "alignas"
                | "alignof"
                | "auto"
                | "break"
                | "case"
                | "catch"
                | "class"
                | "const"
                | "consteval"
                | "constexpr"
                | "constinit"
                | "continue"
                | "co_await"
                | "co_return"
                | "co_yield"
                | "decltype"
                | "default"
                | "delete"
                | "do"
                | "else"
                | "enum"
                | "explicit"
                | "export"
                | "extern"
                | "final"
                | "for"
                | "friend"
                | "goto"
                | "if"
                | "inline"
                | "mutable"
                | "namespace"
                | "new"
                | "noexcept"
                | "nullptr"
                | "operator"
                | "override"
                | "private"
                | "protected"
                | "public"
                | "register"
                | "restrict"
                | "return"
                | "sizeof"
                | "static"
                | "static_assert"
                | "struct"
                | "switch"
                | "template"
                | "this"
                | "throw"
                | "try"
                | "typedef"
                | "typename"
                | "union"
                | "using"
                | "virtual"
                | "volatile"
                | "while"
        ),
        SyntaxLanguage::CSharp => matches!(
            w,
            "abstract"
                | "as"
                | "async"
                | "await"
                | "base"
                | "break"
                | "case"
                | "catch"
                | "checked"
                | "class"
                | "const"
                | "continue"
                | "default"
                | "delegate"
                | "do"
                | "else"
                | "enum"
                | "event"
                | "explicit"
                | "extern"
                | "finally"
                | "fixed"
                | "for"
                | "foreach"
                | "goto"
                | "if"
                | "implicit"
                | "in"
                | "interface"
                | "internal"
                | "is"
                | "lock"
                | "namespace"
                | "new"
                | "operator"
                | "out"
                | "override"
                | "params"
                | "partial"
                | "private"
                | "protected"
                | "public"
                | "readonly"
                | "record"
                | "ref"
                | "return"
                | "sealed"
                | "sizeof"
                | "static"
                | "struct"
                | "switch"
                | "this"
                | "throw"
                | "try"
                | "typeof"
                | "unchecked"
                | "unsafe"
                | "using"
                | "var"
                | "virtual"
                | "volatile"
                | "while"
                | "yield"
        ),
        SyntaxLanguage::Go => matches!(
            w,
            "break"
                | "case"
                | "chan"
                | "const"
                | "continue"
                | "default"
                | "defer"
                | "else"
                | "fallthrough"
                | "for"
                | "func"
                | "go"
                | "goto"
                | "if"
                | "import"
                | "interface"
                | "map"
                | "package"
                | "range"
                | "return"
                | "select"
                | "struct"
                | "switch"
                | "type"
                | "var"
        ),
        SyntaxLanguage::Swift => matches!(
            w,
            "actor"
                | "as"
                | "async"
                | "await"
                | "break"
                | "case"
                | "catch"
                | "class"
                | "continue"
                | "default"
                | "defer"
                | "do"
                | "else"
                | "enum"
                | "extension"
                | "fallthrough"
                | "fileprivate"
                | "for"
                | "func"
                | "guard"
                | "if"
                | "import"
                | "in"
                | "init"
                | "inout"
                | "internal"
                | "is"
                | "let"
                | "mutating"
                | "open"
                | "operator"
                | "override"
                | "private"
                | "protocol"
                | "public"
                | "repeat"
                | "return"
                | "self"
                | "Self"
                | "some"
                | "static"
                | "struct"
                | "subscript"
                | "super"
                | "switch"
                | "throw"
                | "throws"
                | "try"
                | "typealias"
                | "var"
                | "where"
                | "while"
        ),
        SyntaxLanguage::Zig => matches!(
            w,
            "addrspace"
                | "align"
                | "allowzero"
                | "and"
                | "anyframe"
                | "anytype"
                | "asm"
                | "async"
                | "await"
                | "break"
                | "callconv"
                | "catch"
                | "comptime"
                | "const"
                | "continue"
                | "defer"
                | "else"
                | "enum"
                | "errdefer"
                | "error"
                | "export"
                | "extern"
                | "fn"
                | "for"
                | "if"
                | "inline"
                | "noalias"
                | "nosuspend"
                | "opaque"
                | "or"
                | "orelse"
                | "packed"
                | "pub"
                | "resume"
                | "return"
                | "struct"
                | "suspend"
                | "switch"
                | "test"
                | "threadlocal"
                | "try"
                | "union"
                | "unreachable"
                | "var"
                | "volatile"
                | "while"
        ),
        SyntaxLanguage::JavaScript | SyntaxLanguage::TypeScript => matches!(
            w,
            "abstract"
                | "as"
                | "async"
                | "await"
                | "break"
                | "case"
                | "catch"
                | "class"
                | "const"
                | "continue"
                | "debugger"
                | "declare"
                | "default"
                | "delete"
                | "do"
                | "else"
                | "enum"
                | "export"
                | "extends"
                | "finally"
                | "for"
                | "from"
                | "function"
                | "get"
                | "if"
                | "implements"
                | "import"
                | "in"
                | "instanceof"
                | "interface"
                | "is"
                | "keyof"
                | "let"
                | "namespace"
                | "new"
                | "of"
                | "override"
                | "private"
                | "protected"
                | "public"
                | "readonly"
                | "return"
                | "satisfies"
                | "set"
                | "static"
                | "super"
                | "switch"
                | "this"
                | "throw"
                | "try"
                | "type"
                | "typeof"
                | "var"
                | "void"
                | "while"
                | "with"
                | "yield"
        ),
        _ => false,
    }
}

fn is_c_primitive_type(lang: SyntaxLanguage, w: &str) -> bool {
    match lang {
        SyntaxLanguage::Rust => matches!(
            w,
            "bool"
                | "char"
                | "str"
                | "i8"
                | "i16"
                | "i32"
                | "i64"
                | "i128"
                | "isize"
                | "u8"
                | "u16"
                | "u32"
                | "u64"
                | "u128"
                | "usize"
                | "f32"
                | "f64"
        ),
        SyntaxLanguage::C | SyntaxLanguage::Cpp => matches!(
            w,
            "bool"
                | "char"
                | "double"
                | "float"
                | "int"
                | "long"
                | "short"
                | "signed"
                | "size_t"
                | "ssize_t"
                | "int8_t"
                | "int16_t"
                | "int32_t"
                | "int64_t"
                | "uint8_t"
                | "uint16_t"
                | "uint32_t"
                | "uint64_t"
                | "unsigned"
                | "void"
        ),
        SyntaxLanguage::Java | SyntaxLanguage::CSharp => matches!(
            w,
            "boolean"
                | "bool"
                | "byte"
                | "char"
                | "decimal"
                | "double"
                | "float"
                | "int"
                | "long"
                | "object"
                | "sbyte"
                | "short"
                | "string"
                | "uint"
                | "ulong"
                | "ushort"
                | "void"
        ),
        SyntaxLanguage::Go => matches!(
            w,
            "any"
                | "bool"
                | "byte"
                | "comparable"
                | "error"
                | "float32"
                | "float64"
                | "int"
                | "int8"
                | "int16"
                | "int32"
                | "int64"
                | "rune"
                | "string"
                | "uint"
                | "uint8"
                | "uint16"
                | "uint32"
                | "uint64"
                | "uintptr"
        ),
        SyntaxLanguage::TypeScript => matches!(
            w,
            "any"
                | "bigint"
                | "boolean"
                | "never"
                | "number"
                | "object"
                | "string"
                | "symbol"
                | "unknown"
                | "void"
        ),
        SyntaxLanguage::Zig => matches!(
            w,
            "bool"
                | "void"
                | "noreturn"
                | "type"
                | "anyerror"
                | "usize"
                | "isize"
                | "u8"
                | "u16"
                | "u32"
                | "u64"
                | "i8"
                | "i16"
                | "i32"
                | "i64"
                | "f32"
                | "f64"
        ),
        _ => false,
    }
}

#[allow(clippy::too_many_lines)]
fn scan_hash_family(
    lang: SyntaxLanguage,
    line: &str,
    state: LineState,
    emit: &mut LineEmitter<'_>,
) -> LineState {
    let mut cur = Cursor::new(line);
    if lang == SyntaxLanguage::Python {
        if state.mode() == MODE_TRIPLE_DOUBLE_QUOTE {
            let start = cur.utf16;
            let closed = scan_triple_tail(&mut cur, "\"\"\"", true);
            emit.emit(start, cur.utf16, TokenKind::String);
            if !closed {
                return LineState::new(MODE_TRIPLE_DOUBLE_QUOTE, 0, 0, 0);
            }
        } else if state.mode() == MODE_TRIPLE_SINGLE_QUOTE {
            let start = cur.utf16;
            let closed = scan_triple_tail(&mut cur, "'''", true);
            emit.emit(start, cur.utf16, TokenKind::String);
            if !closed {
                return LineState::new(MODE_TRIPLE_SINGLE_QUOTE, 0, 0, 0);
            }
        }
    }

    while let Some(b) = cur.peek_byte() {
        if b.is_ascii_whitespace() {
            cur.advance_ascii(1);
            continue;
        }
        if b == b'#' {
            let start = cur.utf16;
            cur.advance_to_end();
            emit.emit(start, cur.utf16, TokenKind::Comment);
            return LineState::NORMAL;
        }
        if lang == SyntaxLanguage::Python && b == b'@' {
            let start = cur.utf16;
            cur.advance_ascii(1);
            let _ = cur.eat_ident();
            emit.emit(start, cur.utf16, TokenKind::Attribute);
            continue;
        }
        if lang == SyntaxLanguage::Shell && b == b'$' {
            let start = cur.utf16;
            cur.advance_ascii(1);
            if cur.peek_byte() == Some(b'{') {
                while let Some(ch) = cur.advance_char() {
                    if ch == '}' {
                        break;
                    }
                }
            } else {
                let _ = cur.eat_ident();
            }
            emit.emit(start, cur.utf16, TokenKind::Attribute);
            continue;
        }
        if lang == SyntaxLanguage::Python
            && matches!(b, b'f' | b'r' | b'b' | b'u' | b'F' | b'R' | b'B' | b'U')
            && matches!(cur.peek_byte_at(1), Some(b'"' | b'\''))
        {
            let quote = cur.peek_byte_at(1).unwrap_or(b'"');
            let start = cur.utf16;
            cur.advance_ascii(1);
            if cur.starts_with("\"\"\"") {
                cur.advance_ascii(3);
                let closed = scan_triple_tail(&mut cur, "\"\"\"", true);
                emit.emit(start, cur.utf16, TokenKind::String);
                if !closed {
                    return LineState::new(MODE_TRIPLE_DOUBLE_QUOTE, 0, 0, 0);
                }
            } else if cur.starts_with("'''") {
                cur.advance_ascii(3);
                let closed = scan_triple_tail(&mut cur, "'''", true);
                emit.emit(start, cur.utf16, TokenKind::String);
                if !closed {
                    return LineState::new(MODE_TRIPLE_SINGLE_QUOTE, 0, 0, 0);
                }
            } else {
                cur.eat_quoted(quote, true);
                emit.emit(start, cur.utf16, TokenKind::String);
            }
            continue;
        }
        if b == b'"' {
            let start = cur.utf16;
            if lang == SyntaxLanguage::Python && cur.starts_with("\"\"\"") {
                cur.advance_ascii(3);
                let closed = scan_triple_tail(&mut cur, "\"\"\"", true);
                emit.emit(start, cur.utf16, TokenKind::String);
                if !closed {
                    return LineState::new(MODE_TRIPLE_DOUBLE_QUOTE, 0, 0, 0);
                }
            } else {
                cur.eat_quoted(b'"', true);
                emit.emit(start, cur.utf16, TokenKind::String);
            }
            continue;
        }
        if b == b'\'' {
            let start = cur.utf16;
            if lang == SyntaxLanguage::Python && cur.starts_with("'''") {
                cur.advance_ascii(3);
                let closed = scan_triple_tail(&mut cur, "'''", true);
                emit.emit(start, cur.utf16, TokenKind::String);
                if !closed {
                    return LineState::new(MODE_TRIPLE_SINGLE_QUOTE, 0, 0, 0);
                }
            } else {
                cur.eat_quoted(b'\'', lang != SyntaxLanguage::Shell);
                emit.emit(start, cur.utf16, TokenKind::String);
            }
            continue;
        }
        if b.is_ascii_digit() {
            let start = cur.utf16;
            cur.eat_number();
            emit.emit(start, cur.utf16, TokenKind::Number);
            continue;
        }
        if b.is_ascii_alphabetic() || b == b'_' {
            let start = cur.utf16;
            let ident = cur.eat_ident();
            let end = cur.utf16;
            let kind = match lang {
                SyntaxLanguage::Python => {
                    if matches!(ident, "True" | "False" | "None") {
                        Some(TokenKind::Number)
                    } else if matches!(
                        ident,
                        "and"
                            | "as"
                            | "assert"
                            | "async"
                            | "await"
                            | "break"
                            | "case"
                            | "class"
                            | "continue"
                            | "def"
                            | "del"
                            | "elif"
                            | "else"
                            | "except"
                            | "finally"
                            | "for"
                            | "from"
                            | "global"
                            | "if"
                            | "import"
                            | "in"
                            | "is"
                            | "lambda"
                            | "match"
                            | "nonlocal"
                            | "not"
                            | "or"
                            | "pass"
                            | "raise"
                            | "return"
                            | "try"
                            | "while"
                            | "with"
                            | "yield"
                    ) {
                        Some(TokenKind::Keyword)
                    } else if matches!(
                        ident,
                        "int"
                            | "float"
                            | "str"
                            | "bool"
                            | "bytes"
                            | "list"
                            | "dict"
                            | "set"
                            | "tuple"
                            | "object"
                            | "type"
                    ) {
                        Some(TokenKind::Type)
                    } else if cur.peek_byte() == Some(b'(') {
                        Some(TokenKind::Function)
                    } else {
                        None
                    }
                }
                SyntaxLanguage::Shell => {
                    if matches!(
                        ident,
                        "if" | "then"
                            | "else"
                            | "elif"
                            | "fi"
                            | "for"
                            | "while"
                            | "until"
                            | "do"
                            | "done"
                            | "case"
                            | "esac"
                            | "in"
                            | "function"
                            | "return"
                            | "local"
                            | "export"
                            | "readonly"
                            | "unset"
                            | "set"
                            | "shift"
                            | "exit"
                            | "break"
                            | "continue"
                            | "trap"
                    ) {
                        Some(TokenKind::Keyword)
                    } else if matches!(ident, "true" | "false") {
                        Some(TokenKind::Number)
                    } else {
                        None
                    }
                }
                SyntaxLanguage::Ruby => {
                    if matches!(ident, "true" | "false" | "nil") {
                        Some(TokenKind::Number)
                    } else if matches!(
                        ident,
                        "alias"
                            | "and"
                            | "begin"
                            | "break"
                            | "case"
                            | "class"
                            | "def"
                            | "do"
                            | "else"
                            | "elsif"
                            | "end"
                            | "ensure"
                            | "for"
                            | "if"
                            | "in"
                            | "module"
                            | "next"
                            | "not"
                            | "or"
                            | "redo"
                            | "rescue"
                            | "retry"
                            | "return"
                            | "self"
                            | "super"
                            | "then"
                            | "undef"
                            | "unless"
                            | "until"
                            | "when"
                            | "while"
                            | "yield"
                            | "require"
                            | "require_relative"
                    ) {
                        Some(TokenKind::Keyword)
                    } else if cur.peek_byte() == Some(b'(') {
                        Some(TokenKind::Function)
                    } else {
                        None
                    }
                }
                _ => None,
            };
            if let Some(kind) = kind {
                emit.emit(start, end, kind);
            }
            continue;
        }
        let _ = cur.advance_char();
    }
    LineState::NORMAL
}

fn scan_sql(line: &str, state: LineState, emit: &mut LineEmitter<'_>) -> LineState {
    let mut cur = Cursor::new(line);
    if state.mode() == MODE_BLOCK_COMMENT {
        let start = cur.utf16;
        let (closed, _) = scan_block_comment(&mut cur, 1, false);
        emit.emit(start, cur.utf16, TokenKind::Comment);
        if !closed {
            return LineState::new(MODE_BLOCK_COMMENT, 1, 0, 0);
        }
    }
    while let Some(b) = cur.peek_byte() {
        if b.is_ascii_whitespace() {
            cur.advance_ascii(1);
            continue;
        }
        if b == b'-' && cur.peek_byte_at(1) == Some(b'-') {
            let start = cur.utf16;
            cur.advance_to_end();
            emit.emit(start, cur.utf16, TokenKind::Comment);
            return LineState::NORMAL;
        }
        if b == b'/' && cur.peek_byte_at(1) == Some(b'*') {
            let start = cur.utf16;
            cur.advance_ascii(2);
            let (closed, _) = scan_block_comment(&mut cur, 1, false);
            emit.emit(start, cur.utf16, TokenKind::Comment);
            if !closed {
                return LineState::new(MODE_BLOCK_COMMENT, 1, 0, 0);
            }
            continue;
        }
        if b == b'\'' || b == b'"' {
            let start = cur.utf16;
            cur.eat_quoted(b, true);
            emit.emit(start, cur.utf16, TokenKind::String);
            continue;
        }
        if b.is_ascii_digit() {
            let start = cur.utf16;
            cur.eat_number();
            emit.emit(start, cur.utf16, TokenKind::Number);
            continue;
        }
        if b.is_ascii_alphabetic() || b == b'_' {
            let start = cur.utf16;
            let ident = cur.eat_ident();
            let end = cur.utf16;
            let upper = ident.to_ascii_uppercase();
            let kind = if matches!(upper.as_str(), "TRUE" | "FALSE" | "NULL") {
                Some(TokenKind::Number)
            } else if matches!(
                upper.as_str(),
                "SELECT"
                    | "FROM"
                    | "WHERE"
                    | "INSERT"
                    | "INTO"
                    | "VALUES"
                    | "UPDATE"
                    | "SET"
                    | "DELETE"
                    | "CREATE"
                    | "TABLE"
                    | "INDEX"
                    | "DROP"
                    | "ALTER"
                    | "ADD"
                    | "JOIN"
                    | "INNER"
                    | "LEFT"
                    | "RIGHT"
                    | "OUTER"
                    | "FULL"
                    | "CROSS"
                    | "ON"
                    | "AS"
                    | "AND"
                    | "OR"
                    | "NOT"
                    | "IN"
                    | "IS"
                    | "LIKE"
                    | "BETWEEN"
                    | "EXISTS"
                    | "GROUP"
                    | "BY"
                    | "HAVING"
                    | "ORDER"
                    | "ASC"
                    | "DESC"
                    | "LIMIT"
                    | "OFFSET"
                    | "UNION"
                    | "ALL"
                    | "DISTINCT"
                    | "CASE"
                    | "WHEN"
                    | "THEN"
                    | "ELSE"
                    | "END"
                    | "PRIMARY"
                    | "KEY"
                    | "FOREIGN"
                    | "REFERENCES"
                    | "UNIQUE"
                    | "DEFAULT"
                    | "CHECK"
                    | "CONSTRAINT"
                    | "WITH"
                    | "RECURSIVE"
                    | "BEGIN"
                    | "COMMIT"
                    | "ROLLBACK"
                    | "TRANSACTION"
                    | "RETURNING"
                    | "CONFLICT"
                    | "DO"
                    | "NOTHING"
            ) {
                Some(TokenKind::Keyword)
            } else if matches!(
                upper.as_str(),
                "INT"
                    | "INTEGER"
                    | "BIGINT"
                    | "SMALLINT"
                    | "TEXT"
                    | "VARCHAR"
                    | "CHAR"
                    | "BOOLEAN"
                    | "BOOL"
                    | "REAL"
                    | "FLOAT"
                    | "DOUBLE"
                    | "NUMERIC"
                    | "DECIMAL"
                    | "BLOB"
                    | "DATE"
                    | "TIME"
                    | "TIMESTAMP"
                    | "UUID"
                    | "JSON"
                    | "JSONB"
            ) {
                Some(TokenKind::Type)
            } else if cur.peek_byte() == Some(b'(') {
                Some(TokenKind::Function)
            } else {
                None
            };
            if let Some(kind) = kind {
                emit.emit(start, end, kind);
            }
            continue;
        }
        let _ = cur.advance_char();
    }
    LineState::NORMAL
}

fn scan_lua(line: &str, state: LineState, emit: &mut LineEmitter<'_>) -> LineState {
    let mut cur = Cursor::new(line);
    if state.mode() == MODE_BLOCK_COMMENT {
        let start = cur.utf16;
        while !cur.is_eof() {
            if cur.starts_with("]]") {
                cur.advance_ascii(2);
                emit.emit(start, cur.utf16, TokenKind::Comment);
                break;
            }
            let _ = cur.advance_char();
        }
        if cur.is_eof() && !line.contains("]]") {
            emit.emit(start, cur.utf16, TokenKind::Comment);
            return LineState::new(MODE_BLOCK_COMMENT, 1, 0, 0);
        }
    }
    while let Some(b) = cur.peek_byte() {
        if b.is_ascii_whitespace() {
            cur.advance_ascii(1);
            continue;
        }
        if b == b'-' && cur.peek_byte_at(1) == Some(b'-') {
            let start = cur.utf16;
            if cur.starts_with("--[[") {
                cur.advance_ascii(4);
                let mut closed = false;
                while !cur.is_eof() {
                    if cur.starts_with("]]") {
                        cur.advance_ascii(2);
                        closed = true;
                        break;
                    }
                    let _ = cur.advance_char();
                }
                emit.emit(start, cur.utf16, TokenKind::Comment);
                if !closed {
                    return LineState::new(MODE_BLOCK_COMMENT, 1, 0, 0);
                }
                continue;
            }
            cur.advance_to_end();
            emit.emit(start, cur.utf16, TokenKind::Comment);
            return LineState::NORMAL;
        }
        if b == b'"' || b == b'\'' {
            let start = cur.utf16;
            cur.eat_quoted(b, true);
            emit.emit(start, cur.utf16, TokenKind::String);
            continue;
        }
        if b.is_ascii_digit() {
            let start = cur.utf16;
            cur.eat_number();
            emit.emit(start, cur.utf16, TokenKind::Number);
            continue;
        }
        if b.is_ascii_alphabetic() || b == b'_' {
            let start = cur.utf16;
            let ident = cur.eat_ident();
            let end = cur.utf16;
            let kind = if matches!(ident, "true" | "false" | "nil") {
                Some(TokenKind::Number)
            } else if matches!(
                ident,
                "and"
                    | "break"
                    | "do"
                    | "else"
                    | "elseif"
                    | "end"
                    | "for"
                    | "function"
                    | "goto"
                    | "if"
                    | "in"
                    | "local"
                    | "not"
                    | "or"
                    | "repeat"
                    | "return"
                    | "then"
                    | "until"
                    | "while"
            ) {
                Some(TokenKind::Keyword)
            } else if cur.peek_byte() == Some(b'(') {
                Some(TokenKind::Function)
            } else {
                None
            };
            if let Some(kind) = kind {
                emit.emit(start, end, kind);
            }
            continue;
        }
        let _ = cur.advance_char();
    }
    LineState::NORMAL
}

fn scan_json(line: &str, emit: &mut LineEmitter<'_>) {
    let mut cur = Cursor::new(line);
    while let Some(b) = cur.peek_byte() {
        if b.is_ascii_whitespace() {
            cur.advance_ascii(1);
            continue;
        }
        if b == b'/' && cur.peek_byte_at(1) == Some(b'/') {
            let start = cur.utf16;
            cur.advance_to_end();
            emit.emit(start, cur.utf16, TokenKind::Comment);
            return;
        }
        if b == b'"' {
            let start = cur.utf16;
            cur.eat_quoted(b'"', true);
            let end = cur.utf16;
            let after = cur.rest().trim_start();
            let kind = if after.starts_with(':') {
                TokenKind::Attribute
            } else {
                TokenKind::String
            };
            emit.emit(start, end, kind);
            continue;
        }
        if b == b'-' && cur.peek_byte_at(1).is_some_and(|n| n.is_ascii_digit())
            || b.is_ascii_digit()
        {
            let start = cur.utf16;
            if b == b'-' {
                cur.advance_ascii(1);
            }
            cur.eat_number();
            emit.emit(start, cur.utf16, TokenKind::Number);
            continue;
        }
        if b.is_ascii_alphabetic() {
            let start = cur.utf16;
            let ident = cur.eat_ident();
            if matches!(ident, "true" | "false" | "null") {
                emit.emit(start, cur.utf16, TokenKind::Number);
            }
            continue;
        }
        let _ = cur.advance_char();
    }
}

fn scan_yaml(line: &str, emit: &mut LineEmitter<'_>) {
    let mut cur = Cursor::new(line);
    cur.skip_whitespace();
    if cur.is_eof() {
        return;
    }
    if cur.peek_byte() == Some(b'#') {
        let start = cur.utf16;
        cur.advance_to_end();
        emit.emit(start, cur.utf16, TokenKind::Comment);
        return;
    }
    if cur.rest() == "---" || cur.rest() == "..." {
        let start = cur.utf16;
        cur.advance_to_end();
        emit.emit(start, cur.utf16, TokenKind::Operator);
        return;
    }
    if cur.peek_byte() == Some(b'-') && cur.peek_byte_at(1).is_none_or(|n| n.is_ascii_whitespace())
    {
        let start = cur.utf16;
        cur.advance_ascii(1);
        emit.emit(start, cur.utf16, TokenKind::Operator);
        cur.skip_whitespace();
    }
    // Check for `key:` before scanning value tokens.
    let key_start_pos = cur.pos;
    let key_start_utf16 = cur.utf16;
    if let Some(colon_idx) = find_yaml_key_colon(cur.rest()) {
        let key_slice = cur.rest().get(..colon_idx).unwrap_or("");
        let key_utf16 = count_utf16(key_slice);
        cur.pos = key_start_pos.saturating_add(colon_idx);
        cur.utf16 = key_start_utf16.saturating_add(key_utf16);
        emit.emit(key_start_utf16, cur.utf16, TokenKind::Attribute);
        cur.advance_ascii(1); // ':'
    }
    scan_config_value(&mut cur, emit);
}

fn find_yaml_key_colon(rest: &str) -> Option<usize> {
    let bytes = rest.as_bytes();
    if bytes
        .first()
        .is_some_and(|b| matches!(b, b'"' | b'\'' | b'#' | b'[' | b'{'))
    {
        return None;
    }
    for (idx, &b) in bytes.iter().enumerate() {
        if b == b':' && bytes.get(idx + 1).is_none_or(u8::is_ascii_whitespace) {
            return (idx > 0).then_some(idx);
        }
        if b == b'#' && idx > 0 && bytes.get(idx - 1).is_some_and(u8::is_ascii_whitespace) {
            return None;
        }
    }
    None
}

fn scan_toml_ini(line: &str, is_toml: bool, emit: &mut LineEmitter<'_>) {
    let mut cur = Cursor::new(line);
    cur.skip_whitespace();
    if cur.is_eof() {
        return;
    }
    if cur.peek_byte() == Some(b'#') || (!is_toml && cur.peek_byte() == Some(b';')) {
        let start = cur.utf16;
        cur.advance_to_end();
        emit.emit(start, cur.utf16, TokenKind::Comment);
        return;
    }
    if cur.peek_byte() == Some(b'[') {
        let start = cur.utf16;
        while let Some(ch) = cur.advance_char() {
            if ch == ']' {
                if cur.peek_byte() == Some(b']') {
                    cur.advance_ascii(1);
                }
                break;
            }
        }
        emit.emit(start, cur.utf16, TokenKind::Type);
        scan_config_value(&mut cur, emit);
        return;
    }
    // Key before `=`
    if let Some(eq_idx) = cur.rest().find('=') {
        let key_slice = cur.rest().get(..eq_idx).unwrap_or("");
        let trimmed = key_slice.trim_end();
        if !trimmed.is_empty() && !trimmed.contains('#') {
            let start = cur.utf16;
            let key_utf16 = count_utf16(trimmed);
            emit.emit(start, start.saturating_add(key_utf16), TokenKind::Attribute);
            let consumed_utf16 = count_utf16(key_slice);
            cur.pos = cur.pos.saturating_add(eq_idx);
            cur.utf16 = cur.utf16.saturating_add(consumed_utf16);
            cur.advance_ascii(1); // '='
        }
    }
    scan_config_value(&mut cur, emit);
}

fn scan_config_value(cur: &mut Cursor<'_>, emit: &mut LineEmitter<'_>) {
    while let Some(b) = cur.peek_byte() {
        if b.is_ascii_whitespace() {
            cur.advance_ascii(1);
            continue;
        }
        if b == b'#' {
            let start = cur.utf16;
            cur.advance_to_end();
            emit.emit(start, cur.utf16, TokenKind::Comment);
            return;
        }
        if b == b'"' || b == b'\'' {
            let start = cur.utf16;
            cur.eat_quoted(b, b == b'"');
            emit.emit(start, cur.utf16, TokenKind::String);
            continue;
        }
        if b.is_ascii_digit()
            || (b == b'-' && cur.peek_byte_at(1).is_some_and(|n| n.is_ascii_digit()))
        {
            let start = cur.utf16;
            if b == b'-' {
                cur.advance_ascii(1);
            }
            cur.eat_number();
            emit.emit(start, cur.utf16, TokenKind::Number);
            continue;
        }
        if b.is_ascii_alphabetic() || b == b'_' {
            let start = cur.utf16;
            let ident = cur.eat_ident();
            if matches!(
                ident,
                "true" | "false" | "null" | "yes" | "no" | "on" | "off"
            ) {
                emit.emit(start, cur.utf16, TokenKind::Number);
            }
            continue;
        }
        let _ = cur.advance_char();
    }
}

fn scan_html_xml(line: &str, state: LineState, emit: &mut LineEmitter<'_>) -> LineState {
    let mut cur = Cursor::new(line);
    if state.mode() == MODE_HTML_COMMENT {
        let start = cur.utf16;
        let closed = scan_html_comment_tail(&mut cur);
        emit.emit(start, cur.utf16, TokenKind::Comment);
        if !closed {
            return LineState::new(MODE_HTML_COMMENT, 0, 0, 0);
        }
    }
    while let Some(b) = cur.peek_byte() {
        if b == b'<' {
            if cur.starts_with("<!--") {
                let start = cur.utf16;
                cur.advance_ascii(4);
                let closed = scan_html_comment_tail(&mut cur);
                emit.emit(start, cur.utf16, TokenKind::Comment);
                if !closed {
                    return LineState::new(MODE_HTML_COMMENT, 0, 0, 0);
                }
                continue;
            }
            cur.advance_ascii(1);
            if matches!(cur.peek_byte(), Some(b'/' | b'!' | b'?')) {
                cur.advance_ascii(1);
            }
            if cur
                .peek_byte()
                .is_some_and(|n| n.is_ascii_alphabetic() || n == b'_')
            {
                let tag_start = cur.utf16;
                while let Some(tb) = cur.peek_byte() {
                    if tb.is_ascii_alphanumeric() || matches!(tb, b'-' | b'_' | b':') {
                        cur.advance_ascii(1);
                    } else {
                        break;
                    }
                }
                emit.emit(tag_start, cur.utf16, TokenKind::Type);
                // Scan attributes until `>`
                while let Some(ab) = cur.peek_byte() {
                    if ab == b'>' {
                        cur.advance_ascii(1);
                        break;
                    }
                    if ab.is_ascii_whitespace() || ab == b'/' || ab == b'=' || ab == b'?' {
                        cur.advance_ascii(1);
                        continue;
                    }
                    if ab == b'"' || ab == b'\'' {
                        let vs = cur.utf16;
                        cur.eat_quoted(ab, false);
                        emit.emit(vs, cur.utf16, TokenKind::String);
                        continue;
                    }
                    if ab.is_ascii_alphabetic() || ab == b'_' {
                        let attr_s = cur.utf16;
                        while let Some(nb) = cur.peek_byte() {
                            if nb.is_ascii_alphanumeric() || matches!(nb, b'-' | b'_' | b':') {
                                cur.advance_ascii(1);
                            } else {
                                break;
                            }
                        }
                        emit.emit(attr_s, cur.utf16, TokenKind::Attribute);
                        continue;
                    }
                    let _ = cur.advance_char();
                }
            }
            continue;
        }
        let _ = cur.advance_char();
    }
    LineState::NORMAL
}

fn scan_html_comment_tail(cur: &mut Cursor<'_>) -> bool {
    while !cur.is_eof() {
        if cur.starts_with("-->") {
            cur.advance_ascii(3);
            return true;
        }
        let _ = cur.advance_char();
    }
    false
}

#[allow(clippy::too_many_lines)]
fn scan_css(line: &str, state: LineState, emit: &mut LineEmitter<'_>) -> LineState {
    let mut cur = Cursor::new(line);
    if state.mode() == MODE_BLOCK_COMMENT {
        let start = cur.utf16;
        let (closed, _) = scan_block_comment(&mut cur, 1, false);
        emit.emit(start, cur.utf16, TokenKind::Comment);
        if !closed {
            return LineState::new(MODE_BLOCK_COMMENT, 1, 0, 0);
        }
    }
    while let Some(b) = cur.peek_byte() {
        if b.is_ascii_whitespace() {
            cur.advance_ascii(1);
            continue;
        }
        if b == b'/' && cur.peek_byte_at(1) == Some(b'*') {
            let start = cur.utf16;
            cur.advance_ascii(2);
            let (closed, _) = scan_block_comment(&mut cur, 1, false);
            emit.emit(start, cur.utf16, TokenKind::Comment);
            if !closed {
                return LineState::new(MODE_BLOCK_COMMENT, 1, 0, 0);
            }
            continue;
        }
        if b == b'"' || b == b'\'' {
            let start = cur.utf16;
            cur.eat_quoted(b, true);
            emit.emit(start, cur.utf16, TokenKind::String);
            continue;
        }
        if b == b'@' {
            let start = cur.utf16;
            cur.advance_ascii(1);
            while let Some(nb) = cur.peek_byte() {
                if nb.is_ascii_alphanumeric() || nb == b'-' {
                    cur.advance_ascii(1);
                } else {
                    break;
                }
            }
            emit.emit(start, cur.utf16, TokenKind::Keyword);
            continue;
        }
        if b == b'#' && cur.peek_byte_at(1).is_some_and(|n| n.is_ascii_hexdigit()) {
            let start = cur.utf16;
            cur.advance_ascii(1);
            while let Some(nb) = cur.peek_byte() {
                if nb.is_ascii_hexdigit() {
                    cur.advance_ascii(1);
                } else {
                    break;
                }
            }
            emit.emit(start, cur.utf16, TokenKind::Number);
            continue;
        }
        if b.is_ascii_digit() {
            let start = cur.utf16;
            cur.eat_number();
            if cur.peek_byte() == Some(b'%') {
                cur.advance_ascii(1);
            }
            emit.emit(start, cur.utf16, TokenKind::Number);
            continue;
        }
        if b == b'.' && cur.peek_byte_at(1).is_some_and(|n| n.is_ascii_alphabetic()) {
            let start = cur.utf16;
            cur.advance_ascii(1);
            while let Some(nb) = cur.peek_byte() {
                if nb.is_ascii_alphanumeric() || matches!(nb, b'-' | b'_') {
                    cur.advance_ascii(1);
                } else {
                    break;
                }
            }
            emit.emit(start, cur.utf16, TokenKind::Type);
            continue;
        }
        if b.is_ascii_alphabetic() || b == b'-' {
            let start = cur.utf16;
            while let Some(nb) = cur.peek_byte() {
                if nb.is_ascii_alphanumeric() || matches!(nb, b'-' | b'_') {
                    cur.advance_ascii(1);
                } else {
                    break;
                }
            }
            let end = cur.utf16;
            if cur.rest().trim_start().starts_with(':') {
                emit.emit(start, end, TokenKind::Attribute);
            } else if cur.peek_byte() == Some(b'(') {
                emit.emit(start, end, TokenKind::Function);
            }
            continue;
        }
        let _ = cur.advance_char();
    }
    LineState::NORMAL
}

fn scan_diff(line: &str, emit: &mut LineEmitter<'_>) {
    let len = count_utf16(line);
    if len == 0 {
        return;
    }
    let kind = if line.starts_with("@@")
        || line.starts_with("diff ")
        || line.starts_with("index ")
        || line.starts_with("--- ")
        || line.starts_with("+++ ")
    {
        Some(TokenKind::DiffHeader)
    } else if line.starts_with('+') {
        Some(TokenKind::DiffAdded)
    } else if line.starts_with('-') {
        Some(TokenKind::DiffRemoved)
    } else {
        None
    };
    if let Some(kind) = kind {
        emit.emit(0, len, kind);
    }
}

#[allow(clippy::too_many_lines)]
fn scan_markdown(line: &str, state: LineState, emit: &mut LineEmitter<'_>) -> LineState {
    // CommonMark §4.5 recognizes only ASCII spaces/tabs as fence indentation and closing tail.
    let trimmed = line.trim_start_matches([' ', '\t']);
    let indent_bytes = line.len().saturating_sub(trimmed.len());
    let indent_utf16 = count_utf16(line.get(..indent_bytes).unwrap_or(""));
    let indent_columns: usize = line
        .get(..indent_bytes)
        .unwrap_or("")
        .bytes()
        .map(|b| if b == b'\t' { 4 } else { 1 })
        .sum();
    let total_utf16 = count_utf16(line);

    // Inside a fenced code block?
    if matches!(state.mode(), MODE_MD_FENCE_BACKTICK | MODE_MD_FENCE_TILDE) {
        let fence_char = if state.mode() == MODE_MD_FENCE_BACKTICK {
            '`'
        } else {
            '~'
        };
        let min_len = usize::from(state.param().max(3));
        let fence_run = trimmed.chars().take_while(|&c| c == fence_char).count();
        if indent_columns <= 3
            && fence_run >= min_len
            && trimmed
                .get(fence_run..)
                .is_some_and(|r| r.bytes().all(|b| b == b' ' || b == b'\t'))
        {
            emit.emit(indent_utf16, total_utf16, TokenKind::Operator);
            return LineState::NORMAL;
        }
        let inner = SyntaxLanguage::from_u8(state.inner_lang()).unwrap_or(SyntaxLanguage::Plain);
        if inner != SyntaxLanguage::Plain && inner != SyntaxLanguage::Markdown {
            let inner_state = LineState::new(state.inner_mode(), state.inner_param(), 0, 0);
            let before_len = emit.sink.as_ref().map_or(0, |(v, _)| v.len());
            let next_inner = scan_line(
                inner,
                line,
                inner_state,
                emit.base_utf16,
                emit.sink.as_mut().map(|(v, f)| (&mut **v, &mut **f)),
                emit.remaining,
            );
            if let Some((tokens, _)) = emit.sink.as_ref() {
                let added = tokens.len().saturating_sub(before_len);
                emit.remaining = emit.remaining.saturating_sub(added);
                if let Some(last) = tokens.last() {
                    emit.last_end = last.end_utf16;
                }
            }
            return LineState::with_inner_param(
                state.mode(),
                state.param(),
                inner.as_u8(),
                next_inner.mode(),
                next_inner.param(),
            );
        }
        emit.emit(0, total_utf16, TokenKind::String);
        return state;
    }

    // Opening fence? (CommonMark §4.5: at most 3 leading space columns)
    if indent_columns <= 3 && (trimmed.starts_with("```") || trimmed.starts_with("~~~")) {
        let fence_char = trimmed.chars().next().unwrap_or('`');
        let fence_run = trimmed.chars().take_while(|&c| c == fence_char).count();
        let rest = trimmed.get(fence_run..).unwrap_or("");
        if fence_char == '~' || !rest.contains('`') {
            emit.emit(indent_utf16, total_utf16, TokenKind::Operator);
            let inner = SyntaxLanguage::from_fence_info(rest);
            let mode = if fence_char == '`' {
                MODE_MD_FENCE_BACKTICK
            } else {
                MODE_MD_FENCE_TILDE
            };
            let param = u8::try_from(fence_run.min(255)).unwrap_or(255);
            return LineState::new(mode, param, inner.as_u8(), MODE_NORMAL);
        }
    }

    // ATX Heading `# ...`
    if trimmed.starts_with('#') {
        let hashes = trimmed.bytes().take_while(|&b| b == b'#').count();
        if (1..=6).contains(&hashes)
            && trimmed
                .as_bytes()
                .get(hashes)
                .is_none_or(|b| *b == b' ' || *b == b'\t')
        {
            let marker_end = indent_utf16.saturating_add(u32::try_from(hashes).unwrap_or(6));
            emit.emit(indent_utf16, marker_end, TokenKind::Operator);
            if marker_end < total_utf16 {
                emit.emit(marker_end, total_utf16, TokenKind::Function);
            }
            return LineState::NORMAL;
        }
    }

    // Thematic break `---`, `***`, `___`
    if is_thematic_break(trimmed) {
        emit.emit(indent_utf16, total_utf16, TokenKind::Operator);
        return LineState::NORMAL;
    }

    // Blockquote / List prefix
    let mut cur = Cursor::new(line);
    cur.skip_whitespace();
    if cur.peek_byte() == Some(b'>') {
        let start = cur.utf16;
        while matches!(cur.peek_byte(), Some(b'>' | b' ')) {
            cur.advance_ascii(1);
        }
        emit.emit(start, cur.utf16, TokenKind::Comment);
    }
    if matches!(cur.peek_byte(), Some(b'-' | b'*' | b'+')) && cur.peek_byte_at(1) == Some(b' ') {
        let start = cur.utf16;
        cur.advance_ascii(1);
        emit.emit(start, cur.utf16, TokenKind::Operator);
        cur.advance_ascii(1);
        // Optional task marker `[ ]` / `[x]` / `[X]`
        if (cur.starts_with("[ ]") || cur.starts_with("[x]") || cur.starts_with("[X]"))
            && cur.peek_byte_at(3).is_none_or(|b| b == b' ' || b == b'\t')
        {
            let ts = cur.utf16;
            cur.advance_ascii(3);
            emit.emit(ts, cur.utf16, TokenKind::Keyword);
        }
    } else if cur.peek_byte().is_some_and(|b| b.is_ascii_digit()) {
        let save_pos = cur.pos;
        let save_utf16 = cur.utf16;
        while cur.peek_byte().is_some_and(|b| b.is_ascii_digit()) {
            cur.advance_ascii(1);
        }
        if matches!(cur.peek_byte(), Some(b'.' | b')')) && cur.peek_byte_at(1) == Some(b' ') {
            cur.advance_ascii(1);
            emit.emit(save_utf16, cur.utf16, TokenKind::Operator);
        } else {
            cur.pos = save_pos;
            cur.utf16 = save_utf16;
        }
    }

    // Inline Markdown spans: `code`, **strong**, *em*, [label](url), $math$
    // Track exhausted delimiters per line so unclosed markers never rescan O(n^2).
    let mut exhausted_mask: u8 = 0;
    let mut last_failed_close_bracket: usize = 0;

    while let Some(b) = cur.peek_byte() {
        if b == b'`' && (exhausted_mask & EX_TICK) == 0 {
            let start = cur.utf16;
            let save_pos = cur.pos;
            let save_utf16 = cur.utf16;
            let ticks = cur.rest().bytes().take_while(|&c| c == b'`').count();
            cur.advance_ascii(ticks);
            let mut closed = false;
            while !cur.is_eof() {
                if cur.peek_byte() == Some(b'`') {
                    let run = cur.rest().bytes().take_while(|&c| c == b'`').count();
                    cur.advance_ascii(run);
                    if run == ticks {
                        closed = true;
                        break;
                    }
                } else {
                    let _ = cur.advance_char();
                }
            }
            if closed {
                emit.emit(start, cur.utf16, TokenKind::String);
            } else {
                exhausted_mask |= EX_TICK;
                cur.pos = save_pos;
                cur.utf16 = save_utf16;
                cur.advance_ascii(ticks);
            }
            continue;
        }
        if b == b'$'
            && (exhausted_mask & EX_DOLLAR) == 0
            && cur.peek_byte_at(1).is_some_and(|n| n != b' ')
        {
            let start = cur.utf16;
            let save_pos = cur.pos;
            let save_utf16 = cur.utf16;
            let double = cur.peek_byte_at(1) == Some(b'$');
            cur.advance_ascii(if double { 2 } else { 1 });
            let mut closed = false;
            while !cur.is_eof() {
                if double && cur.starts_with("$$") {
                    cur.advance_ascii(2);
                    closed = true;
                    break;
                }
                if !double && cur.peek_byte() == Some(b'$') {
                    cur.advance_ascii(1);
                    closed = true;
                    break;
                }
                let _ = cur.advance_char();
            }
            if closed {
                emit.emit(start, cur.utf16, TokenKind::Attribute);
            } else {
                exhausted_mask |= EX_DOLLAR;
                cur.pos = save_pos;
                cur.utf16 = save_utf16;
                cur.advance_ascii(1);
            }
            continue;
        }
        if matches!(b, b'*' | b'_' | b'~') {
            let ex_bit = match b {
                b'*' => EX_STAR,
                b'_' => EX_UNDER,
                _ => EX_TILDE,
            };
            if (exhausted_mask & ex_bit) != 0 {
                cur.advance_ascii(1);
                continue;
            }
            let delim = b;
            let double = cur.peek_byte_at(1) == Some(delim);
            if delim == b'~' && !double {
                cur.advance_ascii(1);
                continue;
            }
            let start = cur.utf16;
            let save_pos = cur.pos;
            let save_utf16 = cur.utf16;
            cur.advance_ascii(if double { 2 } else { 1 });
            if cur.peek_byte().is_some_and(|n| !n.is_ascii_whitespace()) {
                let mut closed = false;
                while !cur.is_eof() {
                    if cur.peek_byte() == Some(delim)
                        && (!double || cur.peek_byte_at(1) == Some(delim))
                    {
                        cur.advance_ascii(if double { 2 } else { 1 });
                        closed = true;
                        break;
                    }
                    let _ = cur.advance_char();
                }
                if closed {
                    emit.emit(
                        start,
                        cur.utf16,
                        if double {
                            TokenKind::Keyword
                        } else {
                            TokenKind::Type
                        },
                    );
                    continue;
                }
                exhausted_mask |= ex_bit;
            }
            cur.pos = save_pos;
            cur.utf16 = save_utf16;
            cur.advance_ascii(1);
            continue;
        }
        if b == b'[' && (exhausted_mask & EX_LINK) == 0 {
            if cur.pos < last_failed_close_bracket {
                cur.advance_ascii(1);
                continue;
            }
            let save_pos = cur.pos;
            let save_utf16 = cur.utf16;
            cur.advance_ascii(1);
            let mut close_pos = None;
            while !cur.is_eof() {
                if cur.peek_byte() == Some(b']') {
                    close_pos = Some(cur.pos);
                    cur.advance_ascii(1);
                    break;
                }
                let _ = cur.advance_char();
            }
            if let Some(found_close_pos) = close_pos {
                if cur.peek_byte() == Some(b'(') {
                    let url_start = cur.utf16;
                    let mut url_closed = false;
                    while !cur.is_eof() {
                        if cur.peek_byte() == Some(b')') {
                            cur.advance_ascii(1);
                            url_closed = true;
                            break;
                        }
                        let _ = cur.advance_char();
                    }
                    if url_closed {
                        emit.emit(save_utf16, url_start, TokenKind::Type);
                        emit.emit(url_start, cur.utf16, TokenKind::Operator);
                        continue;
                    }
                    exhausted_mask |= EX_LINK;
                } else {
                    last_failed_close_bracket = found_close_pos;
                }
            } else {
                exhausted_mask |= EX_LINK;
            }
            cur.pos = save_pos;
            cur.utf16 = save_utf16;
            cur.advance_ascii(1);
            continue;
        }
        let _ = cur.advance_char();
    }
    LineState::NORMAL
}

fn is_thematic_break(trimmed: &str) -> bool {
    let Some(first) = trimmed.bytes().next() else {
        return false;
    };
    if !matches!(first, b'-' | b'*' | b'_') {
        return false;
    }
    let mut count = 0usize;
    for b in trimmed.bytes() {
        if b == first {
            count += 1;
        } else if !b.is_ascii_whitespace() {
            return false;
        }
    }
    count >= 3
}
