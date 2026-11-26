# Native math and diagrams

Math and diagrams are optional rendered appearances of Markdown source. They
never alter document text, autosave, or the source of truth, and use no
WebView, JavaScript, network access, external images, or document-selected
fonts.

## Supported subset and fallback

- **Math:** inline `$...$` / `\(...\)`, display `$$...$$` / `\[...\]`, and
  `math` fences via RaTeX (0.1.16) and bundled KaTeX fonts. Custom macros,
  unbounded expansion, `alignat`, multiline inline expressions, and unsupported
  environments/assets stay source.
- **Diagrams:** `mermaid` fences for 12 deterministic host-palette families
  (`flowchart`/`graph`, `sequenceDiagram`, `stateDiagram`, `classDiagram`,
  `erDiagram`, `pie`, `gitGraph`, `requirementDiagram`, `packet`/`packet-beta`,
  `quadrantChart`, `xychart`/`xychart-beta`, and `block`/`block-beta`) via
  Merman (0.11.0, enabling only those 12 pure-SVG diagram features)
  with a host-owned theme and platform fonts selected by Android's NDK
  `AFontMatcher`. Families relying on SVG filter effects, hardcoded colors, or
  group opacity (`timeline`, `journey`, `gantt`) as well as frontmatter/config,
  CSS/styling commands, click/link actions, asset shapes, HTML labels,
  unsupported families, and labels with missing glyphs remain source (no silent
  missing-glyph boxes).

Unsupported, oversized, timed-out, or failed illustrations remain readable as
exact source with an explicit reason and never block neighboring illustrations.

## Isolation, transport, and packet (`BTXTILL4`)

- **Workers:** separate non-exported, ART-free native `isolatedProcess`
  services (`math-service`, `diagram-service`), lazy-bound per document and
  released on backgrounding. Watchdogs terminate hung workers after **2 s**
  (math) or **3 s** (diagram), with a **7 s** client deadline.
- **Transport:** one-way Binder carries a bounded snippet (**4 KiB** math /
  **16 KiB** diagram) plus an app-owned, sealed **512 KiB** anonymous output
  `memfd`. Workers receive no URIs, paths, or full document access.
- **Platform fonts (diagrams):** `AFontMatcher` selects at most **16** files /
  **48 MiB** / **64** faces inside the diagram worker; font data never crosses
  Binder or ships in the APK. SVG is converted to outlined paths inside the
  worker (rejecting external refs, images, scripts, masks, filters, gradients,
  and unsupported compositing).
- **Closed packet (`BTXTILL4`):** host validates exact size, finite geometry,
  and strict limits (**2,048** paths, **100,000** command/coordinate floats,
  **8** clips/path, **512 KiB** total, **1 KiB** title / **4 KiB** description,
  **2,048** semantic text runs / **32 KiB** text / **4,096** highlight boxes)
  before constructing Android paths. Preview caps measured extent at **4,096**
  px.

## Budgets and accessibility

- **Caches & budgets:** preview keeps an in-memory revision-scoped LRU cache
  (**128** entries / **8 MiB** encoded packets) with viewport priority and a
  **12 s** per-viewport work budget; printing uses an eager **128**-attempt /
  **8 MiB** / **12 s** budget.
- **Interaction, inspection, export, & accessibility:** diagrams start fit-width
  with a text-size pan toggle and open a full-screen pinch-to-zoom vector
  inspector (`IllustrationInspectorDialog`) supporting lossless WebP/PNG
  **Save image** (SAF via `IsolatedDocumentExporter`) and **Share image**
  (`ExcerptProvider` session-owned lease, no app-private files). Copy always
  copies exact TeX/Mermaid source. Accessibility exposes original TeX or
  authored `accTitle`/`accDescr` (falling back to Mermaid source). Formatted
  PDFs draw the same paths with exact source in the selectable Unicode layer.

## Parser containment regression

Earlier Merman releases (`0.8.0-alpha.6`) could stall in the sequence parser on
an empty `%%` comment without advancing past the newline. Merman `0.11.0` fixes
that lexer stall, while BeauTyXT retains the adapted fuzz regression fixture
(provenance recorded in `CREDITS`) and the isolated worker's independent
3-second watchdog so parser errors or future stalls preserve exact source and
never poison subsequent diagrams.

## Dependencies and verification

Direct versions (`ratex` 0.1.16, `merman` 0.11.0 with default features disabled
and only the 12 admitted diagram families enabled) are pinned in `Cargo.lock`
and covered by bundled notices (see
[third-party notices](../CONTRIBUTING.md#third-party-notices)). Host unit tests
and debug-only worker-probe instrumentation phases verify packet validation,
bounds, source mapping, timeouts, crashes, and parser containment.
