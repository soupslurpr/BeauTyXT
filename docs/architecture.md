# Architecture

## Constraints

BeauTyXT edits plain-text and Markdown documents on Android 17 (API 37;
compiled against API 37.2) with Material 3 Expressive UI. These constraints
are non-negotiable:

- **Offline only:** no Internet permission, no accounts, no cloud services,
  and no fetching of remote images or resources.
- **No app-private document retention:** app-private storage never holds
  document bytes, metadata, staging files, recents, recovery data, or
  autosave copies. Android backup is disabled and URI grants are never
  persisted.
- **User-owned locations:** existing files remain with the user's storage
  provider. New and received documents stay in memory until explicitly saved.
- **Least-authority isolation:** untrusted import, export, Markdown, local
  transfer, math, and diagram work runs in private ART-free native
  `isolatedProcess` services. Workers never receive content URIs or
  filesystem paths — only job-specific file descriptors or anonymous
  `memfd` buffers.
- **Bounded memory:** large documents stream through fixed buffers and render
  via bounded viewports without materializing full text in Compose.
- **Safe core:** core Rust document and parsing crates forbid `unsafe` code;
  JNI and Binder interop is confined to small boundary crates.

## Process and crate layout

| Process | Role | Notes |
| --- | --- | --- |
| App process | Kotlin/Compose UI, Navigation 3 (`HomeActivity`), separate-task external handlers (`MainActivity`, `ShareActivity`), SAF/NFC/camera/print integration, in-process Rust `editor-core` & `print-core` via JNI | Owns the sole retained in-memory session (`retain` store); never serializes document content or source URIs into Android instance state. Stores only content-free preferences in `noBackupFilesDir/text-appearance.properties` and `noBackupFilesDir/app-theme.properties` via `AtomicFile`. |
| `import-service` | Streaming UTF-8 validation & byte-preserving copy to an anonymous `memfd` | Private `isolatedProcess`, `nativeService` (no ART), fresh opaque instance per operation. |
| `export-service` | Exact-byte verified source autosave & reliable-pipe Save a copy | Verifies preflight/post-write SHA-256 on seekable read-write source descriptors. |
| `markdown-service` | CommonMark, GFM, and narrow structural HTML allowlist → sanitized render model (`BTXTMDR6`) | No WebView or script execution; unsupported HTML/CSS stays inert source. |
| `transfer-service` | QR encoding/decoding and NDEF parsing/building | Foreground QR scanner reuses one private instance per scan; NFC & QR encoding use fresh instances. |
| `math-service` / `diagram-service` | Native TeX (RaTeX) and Mermaid subset (Merman) → closed path packet (`BTXTILL4`) | Lazy-bound per document; independent process watchdog. See [native illustrations](native-illustrations.md). |

All six worker services are non-exported, disable shared isolated processes,
bind with random opaque instance names via `bindIsolatedService`, and mark
their entry libraries `DF_1_NODELETE` so detached cancellation does not race
`dlclose`. Bulk payloads travel over file descriptors, reliable pipes (passing
data and status channels separately across NDK Binder), or sealed `memfd`
buffers — never Binder transaction payloads (except bounded 4 KiB math /
16 KiB diagram snippets).

## Document engine, editing, search, and diff

- **Piece tree (`editor-core`):** persistent source-backed AVL piece tree over
  a permanently sealed anonymous `memfd` source. Unchanged spans stay
  source-backed with preserved BOM and mixed LF/CRLF/CR endings; edits live in
  bounded 64 KiB chunks with local compaction. Tree summaries track logical
  UTF-8, exact serialized bytes, scalars, UTF-16 units, and line counts.
- **Bounded editing & history:** Compose edits inside a viewport-bounded
  `BasicTextField` window (16 Ki UTF-16 target, 32 Ki ceiling; floating bottom
  toolbars overlay the viewport with nested-scroll trailing clearance and
  obstruction-aware selection handles). Bulk paste streams directly into Rust
  outside the field. Native undo/redo restores immutable tree roots under a
  128-entry / 64 MiB changed-allocation budget. All editable fields request
  `IME_FLAG_NO_PERSONALIZED_LEARNING` (advisory to the keyboard).
- **Unified Find & Replace:** one Rust matcher (`memmem` for case-sensitive
  literals, bounded Rust regex without backreferences/look-around, lazy
  UAX #29 whole-word boundaries) serves both source and Reading. Reading Find
  uses an off-UI segment/source-range index (`O(log N)` mapping) and bounded
  literal windows; `BTXTILL4` supplies semantic text coverage for illustrations.
  Batch replacements (`BEBT` v1, ≤ 4,096 patches) validate revision, ordering,
  and size before publishing one atomic Undo revision.
- **Conflict comparison (`diff.rs`):** read-only bounded Myers line diff with
  indexed linear fallback, contiguous hunk projection, and streaming
  BOM/line-ending format detection (`BEDF` v1 packet via
  `NativeDocument.diffSnapshots`). Comparing a conflict imports the external
  file into an isolated read-only snapshot without altering the live baseline.

## Storage, autosave, and conflict safety

- **Descriptor limits:** provider open calls share 4 process-wide slots so
  hung providers remain bounded even when ignoring cancellation.
- **Format resolution:** recognized filename extension takes precedence over
  provider MIME type across opening, File info, sharing, printing, and
  transfers. Recognized Markdown opened from Home starts in Reading while
  keeping writable source access.
- **Verified autosave:** captures an immutable revision into an exact-size,
  permanently sealed sparse `memfd` package (referencing unchanged ranges in
  the sealed session source plus live edit bytes). Before truncating, the
  export worker verifies the seekable read-write provider descriptor's exact
  byte length and SHA-256 against the in-memory baseline, writes and re-hashes,
  and the app performs a final fresh-read verification before advancing the
  clean baseline.
- **Fail-closed conflict & uncertainty handling:** Storage Access Framework
  providers are not universally atomic and cannot prevent external races.
  Conflicts and post-truncation failures never retry unconditionally:
  - Conflicts offer **Compare changes**, **Save as new file**, **Reload**, and
    **Overwrite file**. Reviewed overwrite binds strictly to the reviewed
    `(localRevision, reviewedExternalVersion)` and still runs conditional
    preflight/post-write verification against the current disk bytes.
  - Non-seekable/pipe providers open view-only; **Save as editable** writes a
    verified new file without modifying the original.
- **Session lifetime:** all document state, history, caches, and Find state
  live only in process memory and drop on close or process death.

## Reading, printing, excerpts, and transfer

- **Markdown reading:** Compose renders the worker's lazy block/span model
  directly, with off-UI heading/footnote indexes, shared-scroll tables,
  reversible source-to-Reading selection round-trips, and explicit
  scheme-checked external links. See [native illustrations](native-illustrations.md).
- **Streamed vector printing & PDF:** Android shapes text; in-process
  `print-core` extracts outlines from Android-selected platform fonts and
  streams one page at a time with a selectable Unicode layer. See
  [vector printing](vector-printing.md).
- **Excerpts & sharing:** whole-source sharing waits for verified autosave and
  grants a temporary read permission on the provider URI. Generated outputs
  (excerpts, conversions, PDFs) use a non-exported `ExcerptProvider` with
  random single-output URIs and bounded session-owned leases (4 / 256 MiB)
  backed by sealed anonymous descriptors.
- **QR & NFC:** versioned, integrity-checked Rust envelopes. QR images export
  as lossless WebP (default) or PNG via the isolated export service. NFC
  writing requires explicit confirmation per attempt, checks capacity without
  formatting or locking tags, and verifies by reading back.

## Resource limits

| Boundary | Limit |
| --- | --- |
| Document import & serialized save | 256 MiB UTF-8 |
| Markdown render input / model | 16 MiB source, 16,384 blocks, 131,072 spans, 4 KiB per text block |
| Editable Compose window | 16 Ki UTF-16 target, 32 Ki ceiling |
| Native Undo/Redo history | 128 entries / 64 MiB changed allocations |
| Find query / regex / batch | 4,096 UTF-16 query, 4 MiB regex, 4,096 results / patches, 3 s scan |
| Conflict diff preview | 2,048 lines / 256 Ki UTF-16 per side, 512 Myers distance, 512 output lines |
| Code-block copy | 65,536 UTF-16 units |
| Direct text share / QR / NFC | 128 KiB share / 1,536 B QR / 256 KiB NDEF |
| Excerpt share leases | 4 active leases / 256 MiB total |
| Native math / diagram | 4 KiB math (2 s watchdog) / 16 KiB diagram (3 s watchdog), 512 KiB packet, 128 entries / 8 MiB cache |

Limits constrain work and retention per component rather than acting as an OS
RSS quota. Worker death and cancellation are normal error paths; partial
output is never treated as success.

## Dependency policy

Material 3 Expressive is pinned to an exact version. Shared theme and motion
setup lives in `ui/designsystem`; feature UI uses Material components directly
with local opt-ins for experimental APIs.

Rust dependencies use explicit features and a checked-in `Cargo.lock`. Review
licenses, provenance, and transitive obligations (including MPL components and
OFL fonts) before adopting or updating dependencies; third-party notices do
not inherit BeauTyXT's MIT license. See
[contributing](../CONTRIBUTING.md#third-party-notices) and
[release dependency review](release.md#dependency-review).

## Verification

Unit, host Rust, and separate-UID Android instrumentation tests cover piece-tree
invariants, bounded I/O, worker death/cancellation, storage audits, Markdown
and illustration containment, vector printing, and 100 MiB large-document
acceptance cases. See [CONTRIBUTING.md](../CONTRIBUTING.md) and
[release.md](release.md).
