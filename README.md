# BeauTyXT

BeauTyXT is a plain-text and Markdown reader and editor for Android, with a
Material 3 Expressive interface.

Read a note, a reference document, or an assistant's Markdown output without
reopening the app or conversation that produced it. Make changes when needed,
then share the document on its own. Reading is a complete use case; editing
remains a first-class capability.

Requires **Android 17 / API 37**. The interface is currently English-only.

## Read, edit, and share

- Read Markdown with tables, task lists, alerts, footnotes, and a supported HTML
  subset. Supported TeX math and Mermaid diagrams render natively, without a
  browser or JavaScript.
- Open Markdown directly in Reading mode and switch smoothly to Source editing
  while keeping your place. Navigate long documents with live Contents,
  document-wide Find, and Go to line.
- Customize fonts, text size, spacing, and column layout independently for
  Source and Reading in **Text appearance**, plus an optional **True black**
  OLED dark theme in **App theme**.
- Search and replace with literal, Whole word, and Regex options, Results with
  context, batch replacement review, and one-step **Undo replacement**.
- Edit with undo, redo, automatic saving to compatible files, and a read-only
  **Compare changes** diff view if another app modifies an open file on disk.
- Share or export whole documents or selections via Android, PDF, QR codes
  (including lossless WebP/PNG image export), NFC tags, Copy, or Print — with
  explicit preview and per-format explanations.

## Files and privacy

Files stay with the storage provider you choose. Compatible files save
automatically after edits; files whose providers cannot support verified saving
open view-only (**Save as editable** creates a compatible copy without
modifying the original).

New documents and received text stay in memory until you choose **Save**.
BeauTyXT keeps no app-private document copies, recent-file list, or recovery
files. Sessions and undo history exist only in memory and are lost when closed
or if Android ends the process.

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
