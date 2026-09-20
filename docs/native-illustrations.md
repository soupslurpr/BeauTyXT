# Native math and diagrams

Math and diagrams are optional appearances of existing Markdown source. They
do not change document text, autosave, or the source of truth. The implemented
subset uses no WebView, JavaScript runtime, network access, external images,
document-selected fonts, or unrestricted renderer configuration.

## Supported content and fallback

Math recognizes inline `$...$` / `\(...\)`, display `$$...$$` / `\[...\]`, and
`math` fences. Bracket delimiters preserve byte/source positions and are not
interpreted in code, link destinations, escaped literals, or unsupported HTML.
Fractions, roots, scripts, matrices, sums, integrals, and supported aligned expressions use
RaTeX's parser/layout and fixed bundled KaTeX fonts. Custom macro definitions,
unbounded expansion primitives, unsupported environments, assets, and unsupported
font/glyph operations are declined. `alignat` is deliberately declined before
upstream column allocation. Inline placeholders cannot cross a paragraph;
multiline inline expressions remain source. Ordinary currency and escaped-dollar
fixtures are tested separately from math syntax.

`mermaid` fences accept flowcharts (`flowchart` / `graph`), `sequenceDiagram`,
state diagrams, `classDiagram`, and `erDiagram`. Merman uses a host-owned theme
and a closed collection of platform fonts selected by Android's text shaper.
Document frontmatter/configuration, CSS/style/class styling commands, click/link
actions, modern asset-bearing shapes, HTML labels, and unsupported diagram families remain
source. Semantic class declarations are accepted within class diagrams; this
does not enable authored CSS classes or styling. Labels requiring glyphs absent
from the admitted platform fonts also remain source;
BeauTyXT does not quietly substitute missing-glyph boxes. This is a deliberate
subset, not general Mermaid, HTML/CSS, or TeX compatibility.

Unsupported, oversized, timed-out, or failed content remains readable as its
original source with an explanation. An oversized fence is not rendered as a
partial diagram. Ordinary unsupported syntax does not prevent the next valid
illustration from rendering. A crashed or timed-out worker is released; a later
source can acquire a fresh worker. The same failing source is not retried in a
tight loop. The UI distinguishes unsupported syntax/fonts, size or complexity,
timeout, unavailable worker, invalid output, and the current rendering budget.

## Android isolation and transport

The math and diagram services each run as a private `isolatedProcess`, without
shared isolated processes or app-zygote authority. Each client gets a unique
service instance. The app binds these lazily after the Markdown worker has
finished; a document without illustrations starts neither worker.
Preview schedules the currently visible illustrations and a small adjacent
prefetch. Backgrounding releases its worker instances; immutable, bounded
results may remain in the session's memory-only cache.

A one-way Binder request contains a bounded UTF-8 snippet (4 KiB for math,
16 KiB for a diagram), job/options, and a fixed 512 KiB anonymous output
descriptor. These small snippets are an explicit exception to bulk-document
descriptor transport. The renderer receives no provider URI or save capability.

The app owns the anonymous output buffer and seals growth/shrinkage before
transfer. It checks the isolated sender, job, descriptor identity/type/size,
and seals; after completion it seals writes before taking a bounded immutable
copy. A renderer-retained descriptor cannot mutate the published result.

The independent service watchdog terminates its own isolated process after
2 seconds for math or 3 seconds for a diagram. Cancellation, client callback
death, and unbinding also terminate outstanding work. The client imposes a
7-second overall connection deadline. One-way dispatch means an unresponsive
renderer cannot hold a synchronous Binder reply beyond that deadline.

These defenses constrain work and retention; they are not an operating-system
RSS quota or a claim that a native parser cannot transiently allocate more.
The isolation boundary contains worker failure without assigning the worker
the app's identity or another worker's capabilities.

Inside the diagram process, Android supplies read-only `Font.buffer` mappings
for the normal, bold, and italic sans-serif runs needed by the bounded source.
There are at most 16 distinct buffers, 48 MiB in aggregate, and 64 parsed font
faces. JNI validates direct capacities before borrowing; font owners remain
alive for the synchronous call. The font database makes one bounded owned copy.
No platform font data travels through Binder or is included in the APK.
Complete grapheme runs receive an available family before shaping, so mixed
Latin/Arabic ligatures do not depend on index-based glyph substitution. Layout
measurement and final outlines use the same shaping and fallback selection.

## Closed rendering packet

`BTXTILL3` is the internal illustration protocol, unrelated to QR/NFC envelope
versions. Its exact-size packet contains finite dimensions and filled paths
with bounded clip chains. Limits include 2,048 paths, 100,000 combined
command/coordinate components, eight clips per path, and 512 KiB total output.
The host rejects unknown commands, non-finite geometry, invalid path grammar,
extra/truncated bytes, and out-of-range dimensions before creating Android paths.
Its 48-byte header also declares bounded UTF-8 title and description fields
(1 KiB and 4 KiB respectively); strict decoding and the total packet budget
apply to them too. These are inert accessibility alternatives, not commands.

For diagrams, SVG exists only inside the isolated worker. Bounded XML/USVG
conversion rejects external references, images, scripts, masks, filters,
gradients, complex unsupported clipping, and unsupported compositing. Text is
outlined with the explicitly supplied platform fonts; resource resolvers cannot
acquire external assets. Only the closed path packet crosses back into the app.
An ER-specific host postprocessor removes a redundant half-width translation
from centered relationship labels before Merman's terminal SVG validation.
Class labels use a family-local text-anchor correction. These adjustments do
not enable authored styling or rewrite diagram source.

Host-owned colors adapt preview to Material roles and printing to its fixed
paper palette. Preview caps each illustration's measured extent at 4,096
pixels, preserving aspect ratio rather than accepting a worker-controlled huge
Compose placeholder. Printing independently fits the available page dimensions.

## Document budgets and interaction

Preview uses a least-recently-used cache with at most 128 entries and 8 MiB of
successful encoded packets; that byte count is not total process memory. The
latest viewport wins the queue, with at most 128 distinct requests and 12 seconds
of combined rendering work for that viewport. Off-screen entries can be evicted
and rendered again when revisited. Queue exhaustion and unavailable-worker
results can be retried after the viewport changes rather than becoming a
permanent property of a formula. Results wait for an active fling to finish
before publication, preserving a source anchor when their size changes.

The cache belongs to the document revision, not the current composition. It can
survive rotation and an unchanged source/preview round trip, but is never saved
to disk or restored after process death. Exact source/style pairs share results.
Printing remains deterministic and eager: at most 128 unique attempts, 8 MiB
of retained packets, and 12 seconds of total preparation; later content falls
back with a budget explanation. Math source is
limited to 4 KiB; complete diagram fences to 16 KiB. Supported fences join their
bounded Markdown transport fragments only after a complete successful render.
Rejected fences retain the original fragments and source maps.

Diagrams initially fit the available width. A labeled toggle shows them at text
size for horizontal panning, and switches back to fit-width. Display formulas
and illustrated fences offer explicit Copy of the original source. Tapping or
using the accessibility source-edit action targets that source; editing and
returning to preview do not substitute a generated representation.

Accessibility exposes the original TeX and a source-edit action. Diagrams use
authored `accTitle` and `accDescr` when supplied, otherwise the original Mermaid.
Copy always retains exact source, independently of the authored alternative. This
does not claim spoken mathematical semantics, a navigable graph description,
or PDF/UA compliance. Formatted PDFs draw the same paths and retain original
source in their selectable Unicode alternative. That nonpainting text layer
uses measured bidirectional positions so multilingual source remains searchable;
PDF readers may still insert spaces or line breaks during extraction. Preview's
Copy action is the exact-source route.

## Dependencies

The implementation uses published crates.io releases: RaTeX 0.1.14 and
Merman 0.8.0-alpha.6. Direct versions are exact and `Cargo.lock` records all
resolved versions and package checksums. Their verified publication revisions
are `08cae05377938391117913ca4f278e6a3ffb6a8a` and
`d529f858ea3d337a1bdc8fe12e44e1403ededf2e`, respectively. Merman's default feature set is disabled;
the required SVG path is explicit. The broader upstream parser still contributes
binary size even though app admission enables only the listed diagram families.

Generated notices include crate licenses, exact math-font
notices, Merman's third-party component inventory, and MPL source references.
Android fonts are read from the platform, not redistributed as an app
asset. Notice generation retrieves RaTeX's omitted repository-root MIT license
at its verified publication revision and checks its SHA-256. This needs network
access only when regenerating notices; see
[contributing](../CONTRIBUTING.md#third-party-notices).

## Known parser limitation

Merman's published sequence parser can stall on an empty `%%` comment. Its
lexer may accept a comment prefix without advancing past the newline, so
cooperative cancellation between tokens cannot interrupt that call. The
independent three-second worker watchdog terminates the isolated process;
the app keeps the exact source with a timeout explanation and can render a
subsequent valid diagram through a fresh worker.

This remains an accepted limitation, not a repaired parser or a stable-release
blocker. There is no private parser fork, pending fix, or upstream submission.
The adapted public fuzz fixture and its immutable provenance remain in
`CREDITS`. Containment tests cover that fixture, a minimal empty-comment input,
cancellation, source fallback, and fresh-worker recovery; they do not prove
that the parser or sandbox is free of other defects.

## Verification

The normal document instrumentation suite includes `native math`, `native diagrams`,
`illustration lifecycle`, and `diagram parser containment`. The lifecycle phase uses a debug-only private isolated probe
to verify hangs, crashes, cancellation,
and fresh-worker recovery. The parser-containment phase exercises a real upstream
sequence-parser stall, cancellation, exact source fallback, and the next valid
diagram through the production worker. The probe is absent from staging/release manifests.
Opt-in visual journeys are `native math UI`, `native diagram UI`,
`native diagram families UI`, `diagram parser containment UI`, and
`progressive illustrations UI`.
Host tests additionally cover malformed packets, geometry/layout bounds,
source fragmentation and exact mapping, retained-byte/attempt budgets, command
admission, and unsupported-input fallback. Keep actual runtime results, native
visual captures, and artifact measurements with the exact tested revision in
ignored local evidence directories. Follow the [release process](release.md)
before distributing a candidate; these test descriptions do not certify an
untested build.
