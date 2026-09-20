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
- Move between rendered Markdown and source editing while keeping your place.
  Navigate long documents with Contents, document-wide Find, and Go to line.
- Edit with undo, redo, and automatic saving to compatible source files.
- Share through Android, QR codes, or NFC tags. Save generated QR images as
  lossless WebP or PNG.
- Print source text or formatted Markdown, or save a PDF through Android's
  print dialog. Choose margins for each edge, font and size, a filename header,
  and page numbers.

## Files and privacy

Files stay with the storage provider you choose. Compatible files save
automatically after edits. Files whose providers cannot support verified saving
open view-only; **Save as editable** creates a compatible source without
changing the original.

New documents and received text remain in memory until you choose **Save**.
BeauTyXT keeps no app-private document copies, recent-file list, or recovery
files. Sessions and undo history exist only in memory. Unsaved content is lost
if its session closes or Android ends the app process.

BeauTyXT checks the source before and after saving and reports conflicts or
uncertain writes. Storage providers do not universally support atomic writes:
another app can still race a save, and an interrupted write can leave a source
file incomplete.

The app has no Internet permission, account, or cloud service. It does not fetch
document images or other remote resources. Your chosen storage provider may
still sync files, and explicitly opening an external link hands it to another app.
Keyboards receive a request not to use input for personalized learning, but
BeauTyXT cannot enforce how an independent keyboard handles your text.

## Supported content and limits

- Files must be UTF-8 plain text or Markdown, up to 256 MiB. Existing byte-order
  marks and untouched line endings are preserved.
- Markdown rendering accepts up to 16 MiB of source, with additional complexity
  limits. Images appear as alternative text; CSS, scripts, and unsupported HTML
  are not rendered. Unsupported content remains visible as source.
- Math and diagrams support a deliberate subset, not general TeX or Mermaid
  compatibility. Unsupported or oversized illustrations remain source with an
  explanation. See [native illustrations](docs/native-illustrations.md).
- Direct text sharing between apps is limited to 128 KiB of UTF-8; sharing a
  saved file uses its existing provider location. QR transfers accept up to
  1,536 UTF-8 bytes. Complete NFC messages are limited to 256 KiB, and writing
  also depends on the tag's capacity.
- PDFs provide selectable text and basic document structure, not full PDF/UA
  accessibility. Text extraction varies by reader. See
  [vector printing](docs/vector-printing.md).

## Development and documentation

The interface uses Jetpack Compose. Rust implements the document engine and
isolated import, export, Markdown, transfer, math, and diagram workers.

- [Product direction](docs/product-direction.md): the app's purpose and scope,
  including its role alongside AI assistants.
- [Architecture](docs/architecture.md): storage, lifecycle, process boundaries,
  rendering, and detailed resource limits.
- [Contributing](CONTRIBUTING.md): development conventions and dependency
  notices.
- [Release process](docs/release.md): toolchain, verification, reproducibility,
  and packaging.

## License

BeauTyXT is licensed under the [MIT License](LICENSE).
