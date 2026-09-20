# Streamed vector printing

The renderer streams source text or formatted Markdown as page-local vector
appearances with a measured Unicode text layer. It does not rasterize complete
pages.

## Rendering boundary

Android `StaticLayout` and `TextRunShaper` retain text direction, shaping, font
fallback, glyph positioning, style runs, and decorations. Skrifa reads only the
Android-selected platform font's read-only mapped buffer. It extracts a bounded,
unhinted outline for that glyph and variation instance; the bridge retains no
borrowed pointer. The Kotlin wrapper keeps the font reachable throughout JNI.
Document-provided font files never enter this boundary.

The PDF writer emits the outlines as reusable, page-local forms. The existing
measured, nonpainting Unicode layer preserves selection/search and basic logical
structure. This is not embedded editable font text or a claim of PDF/UA support.
Headings, rules, table backgrounds, link underlines, and quote rails are vector.
The fixed paper palette does not inherit a dark or dynamic screen theme.
Android's color/monochrome print setting controls both graphics and images.

Color/bitmap fonts and synthetic bold use Android's native glyph renderer in a
bounded transparent patch. Genuinely clipped glyphs also use only their visible
pixels, so a PDF form cannot expose their off-page outlines. Fully off-page
glyphs create no visual resource. The Unicode layer independently excludes
clipped text. Bitmap patches retain alpha rather than hiding backgrounds behind
an opaque white rectangle. Neither links nor external images are fetched or
turned into PDF actions.

Layout translations are flattened into canonical page coordinates. This avoids
PDF matrix-rounding differences when the same logical text arrives through
different bounded source fragments. Matrix coefficients use finer precision
than ordinary coordinates so page-scale rounding does not accumulate visibly.

## Retention and limits

Only a selected page allocates drawing resources. It is written directly to the
caller-owned destination, then closed before the next page is created. Completed
pages retain primitive PDF object references and offsets, not text, fonts,
bitmaps, or drawing programs. Output is not buffered as a complete document and
no app-private temporary file is used.

Primary limits:

- Source lines requiring paragraph-level bidirectional context: 64 Ki UTF-16
  units. Oversized complex lines report an error asking for line breaks;
  ordinary left-to-right text streams with a bounded unfinished visual line.
- Formatted prose blocks, table rows, and individual code lines: 64 Ki UTF-16
  units per layout. Oversized content reports an error offering Source text.
  Native Markdown transport fragments are reassembled before layout; code and
  unsupported HTML stream at existing newlines rather than transport boundaries.
- One font mapping: 64 MiB; at most 64 variation axes.
- One outline: 32,768 command/coordinate floats; 4 MiB extraction scratch.
- One page: 8 MiB drawing commands, 8 MiB encoded outlines, 4 million localized
  bitmap pixels, 8,192 visual resources, and 128 font instances.
- The separately bounded text layer retains at most 128 Ki UTF-16 units per page.
- Existing logical-page, output-byte, and PDF-object limits remain in force.

These are component limits, not a claim that total process RSS equals their
sum. Android shaping, font mappings, allocator capacity, and PDF metadata also
consume memory. The retained layout grid controls Android measurements and
bitmap fallback sampling; it no longer implies a page-sized bitmap allocation.

Cancellation is checked during layout, shaping, glyph loops, resource output,
image rows, and cross-reference output. A failed initial page preparation also
releases its already-created page resources. Unsupported drawing operations or
font/geometry/resource failures fail the print job rather than silently losing
content.

## Pagination

Formatted printing measures a block's label and first content line together.
Complete code samples of at most eight laid-out lines also request whole-block
placement, so a short sample does not strand its closing line on the next page.
Long fences still stream at their existing bounded logical-line boundaries.

Headings request space with the start of their following content. A heading,
an introduction of at most three laid-out lines, and a compact code sample or
rendered illustration can move together when the group fits a fresh page.
Lookahead retains at most two additional bounded semantic blocks, not a section
or a document's worth of Android layouts. Each measured layout owns its paint;
measuring the next block cannot restyle an already measured heading.

Grouping yields when it cannot fit the page body. It does not retry indefinitely,
create blank pages, change the selected paper/margins, or silently crop content.
The existing atomic illustration bounds and semantic table-header rules remain
in force. Keeping related content together can increase whitespace and page count.

## Verification

The native instrumentation checks compare actual PDF appearances with Android
text for Latin, combining sequences, ligatures, Arabic, Hebrew, Indic, CJK,
emoji, variable fonts, bold/italic, horizontal scale/skew, serif, decorations,
colored text, and translucent backgrounds. The native reference and PDF are
inspected side by side; pixel checks permit raster hinting differences but not
missing or displaced text.

The vector phase also produces 256 streamed pages without a page bitmap or a
complete-document buffer. It checks bounded page admission, malformed outline
commands, and off-page resource exclusion. Color-glyph tests inspect actual
PDF compositing for source and Markdown in color and monochrome.

Existing tests still cover exact fragment-continuity images, Unicode extraction
and search, logical bidi text, clipping, selected-page confidentiality, long
paragraphs, formatted tables/footnotes, cancellation, and destination ownership.

Also inspect PDFs exported through the installed minified app and Android's
actual Save as PDF destination. Record the tested revision, page appearance,
text extraction, and resource checks with ignored local evidence, not as
permanent test counts in this reference. Run `-e phase 'vector PDF'
-e retainPrintArtifacts true` with the document bridge instrumentation to
retain its comparison PDF and native/PDF image. Ordinary runs clear the named
synthetic print artifacts before storage checks. See the [release process](release.md).

Skrifa, read-fonts, font-types, and their resolved dependencies are covered by
the generated in-app third-party notices.

## Reader limitations

Selectable Unicode and basic structure do not amount to PDF/UA compliance.
Lists and table cells do not have a complete accessible hierarchy. Readers
differ in their handling of logical replacement text, bidirectional runs,
supplementary characters, and inserted whitespace. Keep correct Unicode rather
than altering it to accommodate one reader. Exact original text remains
available through document export and source copying; PDF extraction is not
a lossless substitute.

## Native illustrations

Supported math and Mermaid use the same closed, host-validated path appearances
as preview, bounded to the printable width and height. They do not add SVG,
scripts, or document-controlled fonts to the PDF renderer. The original TeX or
Mermaid is retained as the selectable text alternative, not inferred from the
drawing. Android measures that original source into grapheme- and bidi-aware
nonpainting text positions, which are fitted to the illustration bounds. This
keeps mixed-script alternatives searchable without changing the vector image;
the complete source is charged against the page's text budget. Text extraction
and inserted whitespace vary between PDF readers;
preview's explicit Copy action preserves the exact original source. See
[native illustrations](native-illustrations.md) for syntax and resource limits.
