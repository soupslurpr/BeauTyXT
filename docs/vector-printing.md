# Streamed vector printing

Source text and formatted Markdown stream directly to PDF as page-local vector
outlines plus a measured, nonpainting Unicode text layer — without rasterizing
full pages or buffering the complete PDF in memory or app-private files.

## Rendering boundary

- **Layout & shaping:** Android `StaticLayout` and `TextRunShaper` handle bidi,
  shaping, font fallback, glyph placement, and decorations.
- **Outlines:** in-process Rust `print-core` reads only Android-selected
  read-only platform font mappings via Skrifa (never document-supplied fonts),
  extracts bounded unhinted outlines, and emits reusable page-local PDF forms
  with flattened page coordinates.
- **Fallback patches:** color/bitmap glyphs, synthetic bold, and partially
  clipped glyphs emit bounded alpha-preserving native bitmap patches so
  off-page outlines are never exposed.
- **Palette & links:** printing uses a fixed paper palette (independent of dark
  or dynamic UI themes), honors Android's color/monochrome setting, never
  fetches remote resources, and emits only bounded URI or internal GoTo
  annotations for targets present in emitted pages.
- **Native illustrations:** supported TeX math and Mermaid diagrams draw the
  same host-validated `BTXTILL4` vector paths as preview, with original source
  placed in the selectable Unicode layer (see
  [native illustrations](native-illustrations.md)).

## Retention, pagination, and limits

Only the active page allocates drawing resources; completed pages stream to the
caller's destination and retain only PDF object references/offsets and bounded
link/heading metadata. Cancellation is checked throughout layout, glyph
extraction, and output; exceeding a limit fails the job explicitly rather than
dropping content.

| Boundary | Limit |
| --- | --- |
| Single layout (complex bidi source line, prose block, table row, code line) | 64 Ki UTF-16 units |
| Font mapping | 64 MiB, ≤ 64 variation axes |
| Single glyph outline | 32,768 floats, 4 MiB scratch |
| Single page | 8 MiB drawing ops, 8 MiB outlines, 4M bitmap px, 8,192 resources, 128 font instances, 128 Ki UTF-16 text layer |

Formatted pagination keeps headings paired with short introductions/compact
blocks (≤ 2 bounded lookahead blocks, ≤ 8-line code samples) when they fit on a
fresh page, yielding cleanly rather than cropping or creating blank pages.

## Limitations and verification

Selectable Unicode and basic structure do not claim full PDF/UA compliance, and
text extraction whitespace varies by PDF reader; exact source remains available
via document export and Copy.

Native instrumentation verifies vector output against Android text rendering
across scripts, styles, color/monochrome compositing, 256-page streaming,
clipping, and bounds. Use `-e phase 'vector PDF' -e retainPrintArtifacts true`
to inspect generated artifacts (see [CONTRIBUTING.md](../CONTRIBUTING.md) and
[release.md](release.md)).
