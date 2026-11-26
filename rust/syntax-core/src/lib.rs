//! Bounded, panic-free, single-pass syntax highlighting for `BeauTyXT`.
//!
//! All scanners are strictly linear in input length, forbid `unsafe` code,
//! avoid panicking indexing/unwraps (release builds use `panic = "abort"`),
//! and emit sorted, non-overlapping UTF-16 token ranges.

#![forbid(unsafe_code)]
#![deny(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing
)]

pub mod frontmatter;
mod lexers;
pub mod markdown_assist;

/// Maximum UTF-8 bytes accepted by a single highlight call (64 KiB).
pub const MAX_HIGHLIGHT_INPUT_BYTES: usize = 64 * 1024;

/// Default maximum tokens emitted for a single code block or edit window.
pub const MAX_TOKENS_PER_BLOCK: usize = 4_096;

/// Maximum tokens emitted per single line to keep pathological lines bounded.
pub const MAX_TOKENS_PER_LINE: usize = 512;

/// Closed semantic token roles shared across Reading, Source, and PDF rendering.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(u8)]
pub enum TokenKind {
    /// Language keywords and control flow.
    Keyword = 1,
    /// Quoted string and character literals.
    String = 2,
    /// Line, block, and documentation comments.
    Comment = 3,
    /// Numeric, boolean, and null literals.
    Number = 4,
    /// Type names, primitives, HTML/XML tags, and CSS selectors.
    Type = 5,
    /// Function/method names and Markdown headings.
    Function = 6,
    /// Attributes, annotations, preprocessor directives, keys, and sections.
    Attribute = 7,
    /// Structural punctuation, escapes, links, and emphasis markers.
    Operator = 8,
    /// Added lines in unified diffs.
    DiffAdded = 9,
    /// Removed lines in unified diffs.
    DiffRemoved = 10,
    /// Diff file and hunk headers.
    DiffHeader = 11,
}

impl TokenKind {
    /// Decodes one wire token kind value.
    #[must_use]
    pub const fn from_u8(value: u8) -> Option<Self> {
        match value {
            1 => Some(Self::Keyword),
            2 => Some(Self::String),
            3 => Some(Self::Comment),
            4 => Some(Self::Number),
            5 => Some(Self::Type),
            6 => Some(Self::Function),
            7 => Some(Self::Attribute),
            8 => Some(Self::Operator),
            9 => Some(Self::DiffAdded),
            10 => Some(Self::DiffRemoved),
            11 => Some(Self::DiffHeader),
            _ => None,
        }
    }

    /// Returns the stable numeric wire identifier (`1..=11`).
    #[must_use]
    pub const fn as_u8(self) -> u8 {
        self as u8
    }
}

/// Supported syntax highlighting languages.
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
#[repr(u8)]
pub enum SyntaxLanguage {
    /// Unhighlighted plain text.
    #[default]
    Plain = 0,
    /// Rust.
    Rust = 1,
    /// Kotlin.
    Kotlin = 2,
    /// Java.
    Java = 3,
    /// C.
    C = 4,
    /// C++.
    Cpp = 5,
    /// C#.
    CSharp = 6,
    /// Go.
    Go = 7,
    /// Swift.
    Swift = 8,
    /// Zig.
    Zig = 9,
    /// JavaScript / JSX.
    JavaScript = 10,
    /// TypeScript / TSX.
    TypeScript = 11,
    /// Python.
    Python = 12,
    /// POSIX Shell / Bash / Zsh.
    Shell = 13,
    /// Ruby.
    Ruby = 14,
    /// Lua.
    Lua = 15,
    /// SQL.
    Sql = 16,
    /// CSS / SCSS.
    Css = 17,
    /// HTML / XML / SVG.
    HtmlXml = 18,
    /// JSON / JSONC.
    Json = 19,
    /// YAML.
    Yaml = 20,
    /// TOML.
    Toml = 21,
    /// INI / Java properties / config files.
    Ini = 22,
    /// Unified diff / patch.
    Diff = 23,
    /// Markdown source.
    Markdown = 24,
}

impl SyntaxLanguage {
    /// Decodes a stable wire language identifier.
    #[must_use]
    pub const fn from_u8(value: u8) -> Option<Self> {
        match value {
            0 => Some(Self::Plain),
            1 => Some(Self::Rust),
            2 => Some(Self::Kotlin),
            3 => Some(Self::Java),
            4 => Some(Self::C),
            5 => Some(Self::Cpp),
            6 => Some(Self::CSharp),
            7 => Some(Self::Go),
            8 => Some(Self::Swift),
            9 => Some(Self::Zig),
            10 => Some(Self::JavaScript),
            11 => Some(Self::TypeScript),
            12 => Some(Self::Python),
            13 => Some(Self::Shell),
            14 => Some(Self::Ruby),
            15 => Some(Self::Lua),
            16 => Some(Self::Sql),
            17 => Some(Self::Css),
            18 => Some(Self::HtmlXml),
            19 => Some(Self::Json),
            20 => Some(Self::Yaml),
            21 => Some(Self::Toml),
            22 => Some(Self::Ini),
            23 => Some(Self::Diff),
            24 => Some(Self::Markdown),
            _ => None,
        }
    }

    /// Returns the stable wire identifier.
    #[must_use]
    pub const fn as_u8(self) -> u8 {
        self as u8
    }

    /// Resolves a Markdown fenced code block info string to a language.
    #[must_use]
    pub fn from_fence_info(info: &str) -> Self {
        let token = info
            .trim_start()
            .split(|c: char| c.is_whitespace() || c == ',' || c == '{')
            .next()
            .unwrap_or("");
        if token.is_empty() || token.len() > 32 {
            return Self::Plain;
        }
        let lower = token.to_ascii_lowercase();
        let normalized = lower.strip_prefix("language-").unwrap_or(&lower);
        Self::from_alias(normalized)
    }

    /// Resolves a filename or path extension to a language.
    #[must_use]
    pub fn from_filename(filename: &str) -> Self {
        let base = filename
            .rsplit(['/', '\\'])
            .next()
            .unwrap_or(filename)
            .trim();
        if base.is_empty() {
            return Self::Plain;
        }
        let lower_base = base.to_ascii_lowercase();
        if matches!(
            lower_base.as_str(),
            "dockerfile" | "containerfile" | ".bashrc" | ".zshrc" | ".profile"
        ) {
            return Self::Shell;
        }
        if matches!(lower_base.as_str(), "cargo.lock") {
            return Self::Toml;
        }
        let Some((_, ext)) = lower_base.rsplit_once('.') else {
            return Self::Plain;
        };
        if ext.is_empty() || ext.len() > 16 {
            return Self::Plain;
        }
        Self::from_alias(ext)
    }

    /// Maps one lowercased language alias or file extension.
    #[must_use]
    pub fn from_alias(alias: &str) -> Self {
        match alias {
            "rs" | "rust" => Self::Rust,
            "kt" | "kts" | "kotlin" => Self::Kotlin,
            "java" => Self::Java,
            "c" | "h" => Self::C,
            "cpp" | "cc" | "cxx" | "hpp" | "hh" | "hxx" | "c++" => Self::Cpp,
            "cs" | "csharp" | "c#" => Self::CSharp,
            "go" | "golang" => Self::Go,
            "swift" => Self::Swift,
            "zig" => Self::Zig,
            "js" | "jsx" | "mjs" | "cjs" | "javascript" => Self::JavaScript,
            "ts" | "tsx" | "mts" | "cts" | "typescript" => Self::TypeScript,
            "py" | "pyi" | "python" | "python3" => Self::Python,
            "sh" | "bash" | "zsh" | "shell" | "shellscript" | "console" => Self::Shell,
            "rb" | "ruby" => Self::Ruby,
            "lua" => Self::Lua,
            "sql" | "sqlite" | "postgresql" | "postgres" | "mysql" => Self::Sql,
            "css" | "scss" | "less" => Self::Css,
            "html" | "htm" | "xml" | "xhtml" | "svg" | "gradle.xml" | "plist" => Self::HtmlXml,
            "json" | "jsonc" | "jsonl" | "geojson" => Self::Json,
            "yaml" | "yml" => Self::Yaml,
            "toml" => Self::Toml,
            "ini" | "properties" | "prop" | "cfg" | "conf" | "env" | "editorconfig" => Self::Ini,
            "diff" | "patch" => Self::Diff,
            "md" | "markdown" | "mkd" | "mdown" => Self::Markdown,
            _ => Self::Plain,
        }
    }
}

/// One highlighted UTF-16 half-open token range `[start_utf16, end_utf16)`.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct SyntaxToken {
    /// Start offset in UTF-16 code units from the start of the highlighted text.
    pub start_utf16: u32,
    /// Exclusive end offset in UTF-16 code units.
    pub end_utf16: u32,
    /// Semantic token role.
    pub kind: TokenKind,
}

/// Opaque cross-line lexer state carried between lines or bounded chunks.
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct LineState(pub u64);

impl LineState {
    /// Neutral initial line state.
    pub const NORMAL: Self = Self(0);

    pub(crate) const fn new(mode: u8, param: u8, inner_lang: u8, inner_mode: u8) -> Self {
        Self::with_inner_param(mode, param, inner_lang, inner_mode, 0)
    }

    pub(crate) const fn with_inner_param(
        mode: u8,
        param: u8,
        inner_lang: u8,
        inner_mode: u8,
        inner_param: u8,
    ) -> Self {
        Self(
            (mode as u64)
                | ((param as u64) << 8)
                | ((inner_lang as u64) << 16)
                | ((inner_mode as u64) << 24)
                | ((inner_param as u64) << 32),
        )
    }

    pub(crate) const fn mode(self) -> u8 {
        (self.0 & 0xff) as u8
    }

    pub(crate) const fn param(self) -> u8 {
        ((self.0 >> 8) & 0xff) as u8
    }

    pub(crate) const fn inner_lang(self) -> u8 {
        ((self.0 >> 16) & 0xff) as u8
    }

    pub(crate) const fn inner_mode(self) -> u8 {
        ((self.0 >> 24) & 0xff) as u8
    }

    pub(crate) const fn inner_param(self) -> u8 {
        ((self.0 >> 32) & 0xff) as u8
    }
}

/// Output of one bounded syntax highlighting request.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct HighlightResult {
    /// Sorted, non-overlapping UTF-16 token spans.
    pub tokens: Vec<SyntaxToken>,
    /// True when input or token caps caused remaining text to stay plain.
    pub truncated: bool,
    /// Final cross-line state after scanning the input.
    pub exit_state: LineState,
}

/// Highlights `text` from a neutral initial state with default token caps.
#[must_use]
pub fn highlight_text(language: SyntaxLanguage, text: &str) -> HighlightResult {
    highlight_with_state(language, text, LineState::NORMAL, MAX_TOKENS_PER_BLOCK)
}

/// Highlights `text` from `entry_state`, emitting at most `max_tokens` tokens.
#[must_use]
pub fn highlight_with_state(
    language: SyntaxLanguage,
    text: &str,
    entry_state: LineState,
    max_tokens: usize,
) -> HighlightResult {
    if language == SyntaxLanguage::Plain || text.is_empty() || max_tokens == 0 {
        return HighlightResult {
            tokens: Vec::new(),
            truncated: false,
            exit_state: entry_state,
        };
    }
    if text.len() > MAX_HIGHLIGHT_INPUT_BYTES {
        return HighlightResult {
            tokens: Vec::new(),
            truncated: true,
            exit_state: LineState::NORMAL,
        };
    }
    let cap = max_tokens.min(MAX_TOKENS_PER_BLOCK);
    let mut tokens = Vec::new();
    let mut truncated = false;
    let mut state = entry_state;
    let mut line_utf16_base: u32 = 0;

    for raw_line in text.split_inclusive('\n') {
        let line_len_utf16 = count_utf16(raw_line);
        let line = raw_line.strip_suffix('\n').unwrap_or(raw_line);
        let line = line.strip_suffix('\r').unwrap_or(line);
        let remaining = cap.saturating_sub(tokens.len());
        if remaining == 0 {
            truncated = true;
            state = lexers::scan_line(language, line, state, line_utf16_base, None, 0);
        } else {
            let line_cap = remaining.min(MAX_TOKENS_PER_LINE);
            let before = tokens.len();
            let mut hit_cap = false;
            state = lexers::scan_line(
                language,
                line,
                state,
                line_utf16_base,
                Some((&mut tokens, &mut hit_cap)),
                line_cap,
            );
            if hit_cap || (tokens.len() >= cap && tokens.len() > before) {
                truncated = truncated || hit_cap;
            }
        }
        let Some(next_base) = line_utf16_base.checked_add(line_len_utf16) else {
            truncated = true;
            break;
        };
        line_utf16_base = next_base;
    }

    HighlightResult {
        tokens,
        truncated,
        exit_state: state,
    }
}

/// Advances cross-line lexer state over `text` without allocating tokens.
///
/// Useful for bounded lookback before a visible Source edit window.
#[must_use]
pub fn advance_state(language: SyntaxLanguage, text: &str, entry_state: LineState) -> LineState {
    if language == SyntaxLanguage::Plain
        || text.is_empty()
        || text.len() > MAX_HIGHLIGHT_INPUT_BYTES
    {
        return LineState::NORMAL;
    }
    let mut state = entry_state;
    for raw_line in text.split_inclusive('\n') {
        let line = raw_line.strip_suffix('\n').unwrap_or(raw_line);
        let line = line.strip_suffix('\r').unwrap_or(line);
        state = lexers::scan_line(language, line, state, 0, None, 0);
    }
    state
}

pub(crate) fn char_utf16_len(ch: char) -> u32 {
    if ch.len_utf16() == 2 { 2 } else { 1 }
}

pub(crate) fn count_utf16(text: &str) -> u32 {
    let mut units: u32 = 0;
    for ch in text.chars() {
        units = units.saturating_add(char_utf16_len(ch));
    }
    units
}

#[cfg(test)]
mod tests {
    use super::*;

    fn assert_valid_tokens(text: &str, tokens: &[SyntaxToken]) {
        let utf16: Vec<u16> = text.encode_utf16().collect();
        let mut prev_end = 0u32;
        for token in tokens {
            assert!(
                token.start_utf16 < token.end_utf16,
                "empty token: {token:?}"
            );
            assert!(
                token.start_utf16 >= prev_end,
                "unsorted/overlapping token: {token:?} after {prev_end}"
            );
            let start = token.start_utf16 as usize;
            let end = token.end_utf16 as usize;
            assert!(end <= utf16.len(), "token end out of bounds: {token:?}");
            if let Some(&unit) = utf16.get(start) {
                assert!(
                    !(0xdc00..=0xdfff).contains(&unit),
                    "start splits surrogate pair at {start}"
                );
            }
            if end < utf16.len()
                && let Some(&unit) = utf16.get(end)
            {
                assert!(
                    !(0xdc00..=0xdfff).contains(&unit),
                    "end splits surrogate pair at {end}"
                );
            }
            prev_end = token.end_utf16;
        }
    }

    #[test]
    fn resolves_fence_and_filename_aliases() {
        assert_eq!(
            SyntaxLanguage::from_fence_info("rust"),
            SyntaxLanguage::Rust
        );
        assert_eq!(
            SyntaxLanguage::from_fence_info("  kotlin,linenos"),
            SyntaxLanguage::Kotlin
        );
        assert_eq!(
            SyntaxLanguage::from_fence_info("language-tsx {1}"),
            SyntaxLanguage::TypeScript
        );
        assert_eq!(
            SyntaxLanguage::from_fence_info("mermaid"),
            SyntaxLanguage::Plain
        );
        assert_eq!(
            SyntaxLanguage::from_filename("EditorSession.kt"),
            SyntaxLanguage::Kotlin
        );
        assert_eq!(
            SyntaxLanguage::from_filename("Cargo.toml"),
            SyntaxLanguage::Toml
        );
        assert_eq!(
            SyntaxLanguage::from_filename("notes.txt"),
            SyntaxLanguage::Plain
        );
        assert_eq!(
            SyntaxLanguage::from_filename("README.md"),
            SyntaxLanguage::Markdown
        );
    }

    #[test]
    fn highlights_rust_with_comments_strings_and_emoji_offsets() {
        let code = "#[derive(Debug)]\nfn greet() -> String {\n    /* nested /* comment */ */\n    let s = \"hello 😀!\"; // trailing\n    let n = 42_u64;\n    s.to_owned()\n}\n";
        let result = highlight_text(SyntaxLanguage::Rust, code);
        assert!(!result.truncated);
        assert_valid_tokens(code, &result.tokens);
        assert!(result.tokens.iter().any(|t| t.kind == TokenKind::Attribute));
        assert!(result.tokens.iter().any(|t| t.kind == TokenKind::Keyword));
        assert!(result.tokens.iter().any(|t| t.kind == TokenKind::Function));
        assert!(result.tokens.iter().any(|t| t.kind == TokenKind::Type));
        assert!(result.tokens.iter().any(|t| t.kind == TokenKind::Comment));
        assert!(result.tokens.iter().any(|t| t.kind == TokenKind::String));
        assert!(result.tokens.iter().any(|t| t.kind == TokenKind::Number));
    }

    #[test]
    fn chunk_state_matches_whole_document_highlighting() {
        let code =
            "fn demo() {\n    /* multi\n       line\n       comment */\n    let x = \"ok\";\n}\n";
        let whole = highlight_text(SyntaxLanguage::Rust, code);
        let mut combined = Vec::new();
        let mut state = LineState::NORMAL;
        let mut base = 0u32;
        for line in code.split_inclusive('\n') {
            let part =
                highlight_with_state(SyntaxLanguage::Rust, line, state, MAX_TOKENS_PER_BLOCK);
            for tok in part.tokens {
                combined.push(SyntaxToken {
                    start_utf16: tok.start_utf16 + base,
                    end_utf16: tok.end_utf16 + base,
                    kind: tok.kind,
                });
            }
            state = part.exit_state;
            base += count_utf16(line);
        }
        assert_eq!(whole.tokens, combined);
        assert_eq!(whole.exit_state, state);
    }

    #[test]
    fn highlights_json_yaml_toml_diff_sql_html_css_python_and_markdown() {
        let samples = [
            (
                SyntaxLanguage::Json,
                "{\"key\": \"value\", \"count\": 12, \"ok\": true}\n",
            ),
            (
                SyntaxLanguage::Yaml,
                "# header\nname: BeauTyXT\nversion: 86\nenabled: true\n",
            ),
            (
                SyntaxLanguage::Toml,
                "[package]\nname = \"beautyxt\"\nedition = \"2024\"\ncount = 3\n",
            ),
            (
                SyntaxLanguage::Diff,
                "--- a/file.rs\n+++ b/file.rs\n@@ -1,2 +1,2 @@\n-old\n+new\n context\n",
            ),
            (
                SyntaxLanguage::Sql,
                "-- query\nSELECT id, name FROM docs WHERE size > 10;\n",
            ),
            (
                SyntaxLanguage::HtmlXml,
                "<!-- note --><div class=\"card\">Hello</div>\n",
            ),
            (
                SyntaxLanguage::Css,
                "/* theme */\n.card { color: #1a2b3c; margin: 12px; }\n",
            ),
            (
                SyntaxLanguage::Python,
                "@dataclass\ndef compute(x: int) -> str:\n    \"\"\"doc\n    line\"\"\"\n    return f\"val={x}\"\n",
            ),
            (
                SyntaxLanguage::Markdown,
                "# Title\n\nSome **bold** and `code` with [link](https://example.org)\n\n```rust\nlet x = 1;\n```\n",
            ),
        ];
        for (lang, text) in samples {
            let res = highlight_text(lang, text);
            assert!(!res.truncated, "unexpected truncation for {lang:?}");
            assert!(!res.tokens.is_empty(), "expected tokens for {lang:?}");
            assert_valid_tokens(text, &res.tokens);
        }
    }

    #[test]
    fn preserves_nested_comment_depth_inside_markdown_fences() {
        let md = "```rust\n/* outer\n/* inner */\nstill comment\n*/\nlet x = 1;\n```\n";
        let res = highlight_text(SyntaxLanguage::Markdown, md);
        assert_valid_tokens(md, &res.tokens);
        let still_start = md
            .find("still comment")
            .map_or(0, |byte_idx| count_utf16(&md[..byte_idx]));
        let still_token = res
            .tokens
            .iter()
            .find(|t| t.start_utf16 <= still_start && still_start < t.end_utf16);
        assert_eq!(
            still_token.map(|t| t.kind),
            Some(TokenKind::Comment),
            "line after closing inner nested comment inside fenced rust must remain a comment"
        );
        let let_start = md
            .find("let x")
            .map_or(0, |byte_idx| count_utf16(&md[..byte_idx]));
        let let_token = res
            .tokens
            .iter()
            .find(|t| t.start_utf16 <= let_start && let_start < t.end_utf16);
        assert_eq!(
            let_token.map(|t| t.kind),
            Some(TokenKind::Keyword),
            "code after outer comment closes inside fenced rust must highlight as keyword"
        );
    }

    #[test]
    fn highlights_rust_raw_strings_as_single_tokens_including_multiline_and_fenced() {
        let code = "let s = r#\"hello \"quoted\" world\"#;\n";
        let res = highlight_text(SyntaxLanguage::Rust, code);
        assert_valid_tokens(code, &res.tokens);
        let raw_start = code.find("r#\"").map_or(0, |b| count_utf16(&code[..b]));
        let raw_end = raw_start + count_utf16("r#\"hello \"quoted\" world\"#");
        let string_tokens: Vec<_> = res
            .tokens
            .iter()
            .filter(|t| t.kind == TokenKind::String)
            .collect();
        assert_eq!(
            string_tokens.len(),
            1,
            "raw string must be emitted as one contiguous String token: {string_tokens:?}"
        );
        assert_eq!(
            string_tokens.first().map(|t| (t.start_utf16, t.end_utf16)),
            Some((raw_start, raw_end))
        );

        // Multi-line raw string inside a Markdown rust fence preserves hash count via inner_param.
        let md = "```rust\nlet m = r##\"line 1\n\"# still string\n\"##;\nlet after = 1;\n```\n";
        let md_res = highlight_text(SyntaxLanguage::Markdown, md);
        assert_valid_tokens(md, &md_res.tokens);
        let still_start = md.find("still string").map_or(0, |b| count_utf16(&md[..b]));
        let still_tok = md_res
            .tokens
            .iter()
            .find(|t| t.start_utf16 <= still_start && still_start < t.end_utf16);
        assert_eq!(still_tok.map(|t| t.kind), Some(TokenKind::String));
        let after_start = md.find("let after").map_or(0, |b| count_utf16(&md[..b]));
        let after_tok = md_res
            .tokens
            .iter()
            .find(|t| t.start_utf16 <= after_start && after_start < t.end_utf16);
        assert_eq!(after_tok.map(|t| t.kind), Some(TokenKind::Keyword));

        // A 4-space indented fence is an indented code block (CommonMark §4.5), NOT a fence.
        let indented_md = "    ```rust\n# Heading\n";
        let indented_res = highlight_text(SyntaxLanguage::Markdown, indented_md);
        assert_valid_tokens(indented_md, &indented_res.tokens);
        let heading_start = indented_md
            .find("Heading")
            .map_or(0, |b| count_utf16(&indented_md[..b]));
        let heading_tok = indented_res
            .tokens
            .iter()
            .find(|t| t.start_utf16 <= heading_start && heading_start < t.end_utf16);
        assert_eq!(heading_tok.map(|t| t.kind), Some(TokenKind::Function));

        // NBSP before opener or after closer is not CommonMark §4.5 ASCII space/tab.
        let nbsp_open = "\u{00A0}```rust\n# StillHeading\n";
        let nbsp_open_res = highlight_text(SyntaxLanguage::Markdown, nbsp_open);
        assert_valid_tokens(nbsp_open, &nbsp_open_res.tokens);
        let h_start = nbsp_open
            .find("StillHeading")
            .map_or(0, |b| count_utf16(&nbsp_open[..b]));
        let h_tok = nbsp_open_res
            .tokens
            .iter()
            .find(|t| t.start_utf16 <= h_start && h_start < t.end_utf16);
        assert_eq!(h_tok.map(|t| t.kind), Some(TokenKind::Function));

        let nbsp_close = "```rust\n```\u{00A0}\nlet still_rust = 1;\n```\n";
        let nbsp_close_res = highlight_text(SyntaxLanguage::Markdown, nbsp_close);
        assert_valid_tokens(nbsp_close, &nbsp_close_res.tokens);
        let rust_start = nbsp_close
            .find("let still_rust")
            .map_or(0, |b| count_utf16(&nbsp_close[..b]));
        let rust_tok = nbsp_close_res
            .tokens
            .iter()
            .find(|t| t.start_utf16 <= rust_start && rust_start < t.end_utf16);
        assert_eq!(rust_tok.map(|t| t.kind), Some(TokenKind::Keyword));
    }

    #[test]
    fn enforces_input_and_token_caps_without_panicking() {
        let huge = "let a = 1;\n".repeat(10_000);
        let res = highlight_text(SyntaxLanguage::Rust, &huge);
        assert!(res.truncated);
        assert!(res.tokens.is_empty());

        let dense = "let a = 1; ".repeat(600);
        let res = highlight_with_state(SyntaxLanguage::Rust, &dense, LineState::NORMAL, 32);
        assert!(res.truncated);
        assert!(res.tokens.len() <= 32);
        assert_valid_tokens(&dense, &res.tokens);
    }
}
