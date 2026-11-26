# BeauTyXT

BeauTyXT is a plain-text and Markdown reader and editor for Android, with a
Material 3 Expressive interface.

Read a note, a reference document, or an assistant's Markdown output without
reopening the app or conversation that produced it. Make changes when needed,
then share the document on its own. Reading is a complete use case; editing
remains a first-class capability.

Requires **Android 17 / API 37**. The interface is currently English-only.

## Read, edit, and share

- Read Markdown with tables, interactive task lists, alerts, footnotes,
  collapsible `<details>` sections, YAML frontmatter properties, syntax-highlighted
  code blocks, and a supported HTML subset. Supported TeX math and 12 Mermaid
  diagram families render natively, without a browser or JavaScript, with
  full-screen pinch-to-zoom diagram/formula & 2D table inspectors and lossless
  WebP/PNG image export.
- Open Markdown directly in Reading mode, switch smoothly to Source editing
  while keeping your place, or use live side-by-side **Split view** on wide
  screens (`≥ 840 dp`). Navigate long documents with filterable live Contents,
  subtle Reading progress, document-wide Find, and Go to line.
- Write faster in Source mode with bounded syntax highlighting, optional line
  numbers, Markdown Enter continuation, selection wrapping, a non-focusable
  formatting strip, hardware editing shortcuts, live selection/reading-time
  stats, and atomic Undo-able `LF` / `CRLF` line-ending conversion.
- Open a new blank document side-by-side with **New window**, one-tap **Paste**
  from Home, and static launcher shortcuts (**New**, **Open**, **Paste**,
  **Scan QR**).
- Customize fonts, text size, spacing, column layout, syntax highlighting, line
  numbers, and **Keep screen on** independently for Source and Reading in
  **Text appearance**, plus an optional **True black** OLED dark theme in
  **App theme**.
- Search and replace with literal, Whole word, and Regex options, Results with
  context, batch replacement review, and one-step **Undo replacement**.
- Edit with undo, redo, automatic saving to compatible files, and a read-only
  diff view for **Compare changes** (external conflicts), **Compare with file…**,
  and **Compare with clipboard**.
- Share or export whole documents, illustrations, or selections via Android,
  syntax-highlighted vector PDF, QR codes (including lossless WebP/PNG image
  export), NFC tags, Copy, or Print — with explicit preview and per-format
  explanations.

## Files and privacy

Files stay with the storage provider you choose. Compatible files save
automatically after edits; files whose providers cannot support verified saving
open view-only (**Save as editable** creates a compatible copy without
modifying the original).

New documents and received text stay in memory until you choose **Save** by
default. If you want new documents saved automatically once you start typing,
you can opt in from Home (**Autosave**) and choose a folder; BeauTyXT keeps
access to that single folder until you turn it off, while received text still
requires **Save**. BeauTyXT keeps no app-private document copies, recent-file
list, or recovery files. Sessions and undo history exist only in memory and are
lost when closed or if Android ends the process.

BeauTyXT verifies the source before and after saving and reports conflicts or
uncertain writes. Storage providers do not universally guarantee atomic writes,
so another app can still race a save or an interrupted write can leave a file
incomplete.

The app has no Internet permission, account, or cloud service, and never
fetches remote images or resources. Keyboards receive a request not to use
input for personalized learning, though BeauTyXT cannot enforce how an
independent keyboard handles input.

## Supported content and limits

- UTF-8 plain text or Markdown up to 256 MiB, preserving existing BOMs and
  untouched line endings.
- Markdown rendering up to 16 MiB of source (images render as alt text; CSS,
  scripts, and unsupported HTML stay source).
- Deliberate native subsets for TeX math and Mermaid diagrams, with source
  fallback ([native illustrations](docs/native-illustrations.md)).
- Transfers: up to 128 KiB direct text share, 1,536 UTF-8 bytes for QR, and
  256 KiB for NFC messages (subject to tag capacity).
- Streamed vector PDFs with selectable text, not full PDF/UA compliance
  ([vector printing](docs/vector-printing.md)).

## Development and documentation

Jetpack Compose drives the Android UI; Rust implements the document engine and
isolated import, export, Markdown, transfer, math, and diagram workers.

- [Product direction](docs/product-direction.md) — purpose, scope, and language policy.
- [Architecture](docs/architecture.md) — privacy invariants, process isolation, engine design, and limits.
- [Contributing](CONTRIBUTING.md) — code conventions, third-party notices, and device testing.
- [Release process](docs/release.md) — toolchain, verification, reproducibility, and packaging.

## License

BeauTyXT is licensed under the [MIT License](LICENSE).
