# Architecture

## Product constraints

BeauTyXT edits plain-text and Markdown documents on Android. The first release
has these non-negotiable constraints:

- BeauTyXT never transmits document content unless the user explicitly shares
  or exports it.
- The application has no internet permission or background network behavior.
- Existing files remain in their user-controlled locations and are accessed
  only following an explicit user action.
- App-private storage never contains document bytes, document metadata,
  internal working files, recents, recovery data, or private autosave copies.
- New documents and documents created from pasted or received text remain in
  memory until the user explicitly chooses Save. Pasting into an existing
  editable source follows that document's ordinary autosave behavior.
- The application does not retain persistable URI grants.
- Large documents remain responsive without materializing the complete text in
  Compose.
- Import, export, Markdown, and local-transfer streams cross explicit,
  least-authority process boundaries.
- The Android interface follows Material 3 Expressive through a shared theme
  and motion scheme, with explicit opt-ins where UI code consumes alpha APIs.
- Android 17 / API 37 is the target and minimum SDK; compilation uses API 37.1.

Plain text and Markdown are the complete initial document scope.

## Components

The application has a thin Kotlin integration layer and Rust-owned document
logic.

### Application process

Legacy private-state cleanup runs once per process on an I/O dispatcher.
Document UI, provider metadata queries, and new picker launches wait for success.
Cleanup failure leaves these workflows gated behind an explicit Retry action;
closing one activity does not cancel the process-owned cleanup. No persistent
migration marker is required.

The application process currently owns the Compose UI, Android lifecycle
integration, Storage Access Framework requests, NFC, foreground camera, and
explicit share-intent integration. Only this
Android integration layer handles a user-authorized URI. It never gives a
worker a URI or filesystem path. It opens the URI and gives each worker only
the job-specific descriptor and access mode that operation requires. During
import, the integration layer retains the provider descriptor until the worker
returns a terminal result so reliable provider errors remain authoritative,
then closes it. The exact selected content URI remains only in the live editor
so it can open a fresh descriptor for each source save. The application never
persists that URI or its grant, does not copy it into Android instance state,
and forgets the in-process capability when the editor closes. Android retains
control of the temporary task-scoped grant; the app does not call broad
URI-revocation APIs that can affect other matching grants.

Source and destination descriptor acquisition share four process-wide worker
slots. A cancelled or timed-out request cannot release its slot until the
provider call actually returns; late descriptors are closed without use.
Queued requests remain cancellable and retain the operation's timeout. This
bounds blocked provider calls even when a provider ignores cancellation.

Document format has one shared rule: a recognized filename extension takes
precedence over the reported MIME type. The reported type is the fallback when
the name has no recognized extension. Opening, File info, printing defaults,
Android sharing, and QR/NFC generation use this same resolved format.

Before a source save opens a read-write provider descriptor, the
application captures the immutable revision in a sealed anonymous package. A
sparse package contains a piece table, only live edit and boundary-repair
payloads, and an optional duplicate of the already sealed anonymous import
source. It then gives the isolated export worker only that package, optional
immutable backing, and one fresh source descriptor. Retained-source autosave
requires the provider to return a seekable regular descriptor with exact
read-write access and to support flush and filesystem synchronization. A
provider that exposes only a pipe or does not support these operations cannot
be an autosave source; its selected URI capability remains owned by a view-only
editor until that editor closes. Save as editable writes and verifies a
compatible new source before the editor accepts changes, leaving the original
source unchanged.
During Save a copy, the application rejects an exact retained-source URI match,
opens the newly selected destination without truncation, clears the URI, and
retains only an idempotent descriptor owner until export claims it. Only an
accepted native worker that wins the cancellation race truncates a supported
destination.

An in-process Rust document engine owns the editable model, immutable
revisions, source descriptors, and document metrics. Compose requests bounded
viewport snapshots and sends compact edit operations. This hot path avoids
Binder and never parses Markdown or transfer envelopes. Exact in-document
search runs in this editor core because it is another revision-bound document
operation; it does not cross into an isolated worker or grant one additional
document authority.

### Isolated worker services

The application uses separate private Android isolated services and Rust
dynamic libraries for import, export, Markdown, local transfers, math, and diagrams:

- Implemented: the import service reads an untrusted descriptor and produces a
  byte-identical, strictly validated UTF-8 session source.
- Implemented: the export service reads a trusted snapshot stream or validated
  source-save package and copies its exact serialized bytes to a
  user-authorized destination descriptor.
- Implemented: the Markdown service reads an immutable normalized snapshot and
  produces a sanitized, bounded render model for Compose. It preserves
  CommonMark and the selected GFM semantics plus a narrow structural HTML
  allowlist. HTML is tokenized without a browser; scripts, styles, event
  handlers, embeds, forms, media, and unsupported attributes stay inert source.
  The render packet marks each semantic table's first row independently of
  header styling. Preview and printing preserve adjacent tables as separate
  groups, including headerless HTML tables and tables with multiple header rows.
  Continuation chunks never repeat that table-start marker.
- Implemented: the transfer service creates and validates bounded QR and NFC
  envelopes, decodes bounded QR luminance frames, and strictly parses complete
  bounded NDEF messages into inert text.
- Implemented: separate math and diagram services render bounded TeX or a narrow
  Mermaid subset into a closed path-and-clip packet. Each receives only its
  snippet and an app-owned fixed-size anonymous output descriptor. Neither
  receives a provider capability, full document, URI, or app-private path.
  Preview work is viewport-prioritized with a revision-owned bounded cache;
  print preparation has a separate total budget. A failed worker is replaced
  for later input. Platform-font fallback and authored accessibility text remain
  inside the closed [illustration contract](native-illustrations.md).

Every implemented service is non-exported, uses `isolatedProcess`, explicitly
disables shared isolated processes, and exposes a narrow Binder interface.
Every binding uses `bindIsolatedService` with a new opaque random instance name;
the manifest's process names identify roles, not shared worker instances.
Import, export, Markdown, and transfer operations own fresh bindings. Math and
diagram clients may reuse their own healthy instance within one document's
preview or print preparation, never across document clients. Closing one
binding cannot cancel or occupy a different document's worker. Multiple open
documents can therefore use the same role concurrently, at the cost of separate
process startup and memory. Unbinding releases the operation's worker;
illustration workers also terminate themselves on unbind.
Its minimal Kotlin host owns only lifecycle and descriptor plumbing; the
capability logic runs through its dedicated Rust JNI library. Every worker
preserves the same split. A compromised worker gains neither the app's
identity nor another worker's authority.

The pinned NDK r30 toolchain now builds and links the Rust libraries against
native API 37, matching the app's minimum OS. Android 17 also defines ART-free
native isolated services, but adopting the toolchain does not migrate these
services: their minimal Kotlin hosts and narrow Binder contracts remain in
place. A native-only transport would be a separate implementation and review,
not a requirement for unique isolated worker instances.

The control plane carries job identifiers, options, status, cancellation, and
descriptors. Full documents, snapshots, transfer envelopes, and render output use
descriptors, reliable pipes, or shared memory, not Binder transaction payloads.
The illustration interfaces have one explicit bounded exception: a single TeX
snippet (at most 4 KiB) or Mermaid fence (at most 16 KiB) travels in a one-way
Binder call. Output still uses an app-owned 512 KiB anonymous descriptor whose
identity, size, seals, and complete packet are validated before publication.

### Native printing

Printing consumes one immutable source revision or a bounded model from the
isolated Markdown service. `EditorPrintContent` transfers or releases that
snapshot exactly once; the retained editor session still owns operation
serialization, generation checks, and cancellation. Android's print adapter
owns the prepared content after an explicit handoff.

Transient print setup offers source text or formatted Markdown, four independent
margins, font family and size, source-line wrapping, a filename header, and page
numbers. Formatted Markdown defaults to 12-point sans-serif text; source printing
defaults to 10-point monospace. Formatted preparation releases its exact source
snapshot after the isolated Markdown worker returns and before the native print
screen receives the render model. Android's print service owns the printer or
Save as PDF destination; BeauTyXT retains neither an output copy nor print
preferences afterward.

Formatted output retains aligned table cells, reading-order footnote numbers,
quote and alert rails, code-language labels, and visibly labeled inert HTML.
Pathological table rows fall back to bounded styled text rather than exceeding
the page's layout budget. Links and images are never fetched.

Source and formatted output share the streamed vector PDF writer. Android owns
text layout, font selection, shaping, and glyph placement. A small in-process
Rust outline bridge reads only Android-selected platform font mappings, never
document-supplied fonts. It has no URI or storage API and retains no borrowed
buffer. This is not another isolated parser service: the document parsing
boundary remains the editor core or the existing Markdown worker.

The writer retains only one selected page's vector resources and measured
Unicode text, then streams that page to the print destination. Color glyphs,
synthetic bold, and partially clipped glyphs use bounded native bitmap patches.
There is no full-page image allocation, whole-PDF buffer, or app-private print
file. Text selection/search, page ranges, fixed paper colors, monochrome,
physical margins, cancellation, and explicit failure on exceeded limits remain
part of the contract. See [vector printing](vector-printing.md) for exact
boundaries and verification.

Formatted printing reuses the same validated math and diagram appearances as
preview. Source text remains in the PDF's selectable text layer. Neither raw
SVG nor a formula interpreter enters the app's print renderer. See
[native illustrations](native-illustrations.md) for the separate worker and
admission contracts.

## Large-document model

Compose text fields are not a whole-document storage strategy. The primary
editor model is a persistent source-backed Rust AVL piece tree. The source is a
byte-identical anonymous descriptor, not the provider's mutable descriptor.
Unchanged pieces refer to physical ranges in that source and expose LF-normalized
logical text through bounded reads. Edit allocations of at most 64 KiB own new
and modified logical text. Contiguous ranges from the same backing rejoin
without copying. Similarly sized edit-backed boundaries coalesce, and
fragmented edit-backed regions rechunk locally within the same bound, so
repeated edits do not retain obsolete fragmentation. Compaction never copies
source-backed bytes. Sparse changes can retain source pieces proportional to
the current disjoint changes, but not to superseded operation history. Tree
summaries independently track logical UTF-8 length, exact serialized length,
Unicode scalar count, UTF-16 length, and newline count so most navigation and
snapshot accounting skip unrelated text.

Opening performs one streaming UTF-8 validation and byte-preserving copy in the
isolated worker followed by one bounded indexing scan in the document engine.
The scan is linear in the source byte length and uses a 64 KiB buffer; it does
not create a second whole-document Rust or ART heap copy. Viewport operations
use positioned reads and retain at most four decoded source pieces for the
duration of one request. The anonymous source itself still occupies
kernel-managed memory proportional to the original document size.

The engine returns immutable viewport snapshots containing visible logical
lines plus bounded overscan. The UI lays out only that snapshot. Selection,
search, and navigation use stable document offsets rather than offsets into a
temporary Compose string.

The editor derives its visible logical-line range from the laid-out document
blocks, excluding auxiliary pagination items and clipped content-padding
boundaries. Go to line sends one revision-bound cursor directly to Rust with
the ordinary viewport limits. The previous cache and scroll position remain
published until that response is validated, so failure cannot blank or move
the document. Successful navigation atomically replaces the cache, while a
failed target remains retryable or dismissible.

Find uses live literal substring matching over the engine's LF-normalized
logical text. It ignores case by default and exposes an explicit Match case
toggle; it does not interpret regular expressions or execute Markdown or HTML.
A query is limited to 4,096 UTF-16 code units and 16 KiB of UTF-8. Next advances
by the first Unicode scalar of the current query, so overlapping matches remain
reachable; Previous excludes only the current match start. Each direction wraps
at most once for one user request. The UI reports the selected match and its
logical line, but does not calculate a document-wide match count.

Each native request searches a candidate-start span of at most 256 Ki UTF-16
code units. Its fixed-size bridge packet returns revision metrics and either
one half-open UTF-16 match with its line-relative viewport cursor, or the
remaining candidate range for the next bounded request. The registry lock is
released before the engine scans an immutable snapshot. Compose retains the
old viewport while the bounded batches run and publishes the exact matching
viewport only after its revision and offsets are validated. Stale responses
and superseded queries are discarded.

IME interaction uses a bounded editable window around the active selection.
The ordinary target is 16 Ki UTF-16 code units, and both the native protocol
and Compose draft enforce a 32 Ki hard ceiling. Window edges prefer extended
grapheme boundaries and always fall back to valid Unicode scalar boundaries.
`ActiveEditDraft` owns each bounded `TextFieldState`, its selection, and scroll
positions. `EditorHistory` owns the user-visible undo and redo stacks under one
128-entry / 256 Ki UTF-16-unit budget. Both belong to the retained in-memory
editor session. Those objects never use a `Saver`, `rememberSaveable`,
`SavedStateHandle`, or serialization,
so BeauTyXT does not serialize document content or retained editor state into
Android instance state. Transfer metadata drafts, including an optional NFC
tag label, use the same process-memory-only retention. The retained session and
its operation scope survive Activity configuration changes, while process
death still discards them. Compose's separate internal field history is cleared
periodically outside active composition; the session journal retains logical
edits until its shared budget requires eviction. The journal advances only
after a successful native edit and rejects stale completions. Edits are reduced
to the smallest scalar-aligned replacement before they enter the document
engine, then a new window is published with a monotonically increasing
generation. Stale UI and
worker responses are discarded. Active IME composition is allowed to finish
before applying or moving the window. Explicit Undo and Redo first commit the
visible composing word without changing its characters, selection, or focus.

Bulk paste is independent of the small input-method window. An insertion of up
to 128 Ki UTF-16 units is committed as one undoable native edit, then the same
focused field shows a bounded window around the result. Pending typing is
settled first, accepted bulk input cannot be discarded by an immediate Back,
and oversized input is rejected without copying only a prefix. Pasted CRLF and
bare-CR line endings are normalized to the editor's logical LF representation.

The document host wraps Compose text-input requests with a stable interceptor
that adds Android's
[`IME_FLAG_NO_PERSONALIZED_LEARNING`](https://developer.android.com/reference/android/view/inputmethod/EditorInfo#IME_FLAG_NO_PERSONALIZED_LEARNING)
after the field creates its input connection. The original connection, field
metadata, action flags, and ownership are preserved; ordinary recomposition
does not replace the interceptor or restart input. This covers source editing,
Find, and editable dialog fields. It is only an advisory request: the selected
keyboard remains an independent application that receives typed content.

`DocumentEditor` coordinates lifecycle and actions. Source windows, Markdown
blocks and navigation, Find, dialogs, feedback, and status descriptions live in
separate UI modules. Rendering does not own source-save or worker lifetimes;
those remain serialized by `EditorSession`.

The migrated presentation modules describe app wording with Android resources.
`UiText` carries a string/plural identifier and formatting arguments, or a
sanitized operation message, without retaining a Context in session state.
Resolution happens at the UI or print boundary. English remains the only
supported language; resource preparation does not solicit translations.

Opening and saving stream through fixed-size buffers while the ART heap stays
viewport-sized. Every selected source, seekable or streaming, is copied
byte-for-byte with cancellation, strict UTF-8 validation, and fixed input and
output limits into an anonymous seekable descriptor. Once the isolated writer
closes and reports success, the application verifies its identity and length,
then Rust permanently seals writes and size changes before indexing it. The
provider descriptor is released, and the sealed descriptor has no app-private
path. Its final source-backed tree reference closes when the document and its
snapshots no longer need it. Oversized sources are rejected rather than copied
into app-private storage.

Saving captures a constant-time immutable piece-tree revision. Untouched source
spans retain their BOM and mixed LF, CRLF, or bare-CR line endings. New logical
line breaks use the most common source convention, with the first observed form
as the deterministic tie-breaker and LF as the empty-source default. Both
output paths enforce an exact expected byte count and a 256 MiB hard bound.

The document engine also rejects an edit before publication if it would
increase the exact serialized size beyond that ceiling, including BOM and
line-ending bytes. Non-growing recovery edits remain available. A rejected
submission never enters history or source autosave; newer input is not rolled
back when an older submission fails.

Source autosave encodes the captured revision into a versioned package with a
fixed header, a bounded ordered record table, and a compact trailing payload.
Source records address unchanged ranges in one duplicate of the immutable
anonymous import source. In sparse packages, payload records address only live
edit bytes, serialized new line endings, a preserved BOM when no source record
can carry it, and boundary repairs that keep adjacent bare CR and LF
terminators distinct. Contiguous source ranges coalesce. If no transferable
source remains, fragmentation exceeds the record bound, or sparse encoding is
not smaller, the producer streams one full-payload record instead.

The package `memfd` is allocated to its exact declared size before the provider
is opened for writing. Its type, owner, link count, identity, size, and offset
are checked; growth is sealed before native output, and writes, growth,
shrinking, and further seal changes are sealed afterward. The optional source
backing is already anonymous, exact-size, and permanently sealed. The isolated
export worker rejects descriptor aliases and validates the complete header,
table, backing ranges, payload coverage, and reconstructed output length before
provider preflight begins. After a matching preflight, it reconstructs the
revision with fixed 64 KiB buffers and positioned reads, without changing the
package or backing cursor or status flags.

Sparse autosave staging therefore uses memory proportional to the current live
edits and record table rather than the complete document. A full-payload
fallback remains bounded by the same 256 MiB output limit and never creates an
app-private pathname or exposes a partially captured revision to the source.
The isolated import stream and each verified source write produce an exact
raw-byte version made of a byte length and SHA-256 digest. That version remains
only in process memory. Before truncation, the export worker hashes the same
seekable read-write descriptor and compares it with the retained version. A
mismatch reports a conflict without modifying the source. After a matching
preflight, the worker copies and hashes the reconstructed revision, then
verifies the source bytes again before reporting success. The application
performs one final isolated check through a fresh read descriptor before
advancing the clean revision baseline.

An active source write is not cancelled for a newer edit; after it settles,
only the newest pending revision is written. Retryable failures stay latched
until explicit retry. A source conflict never exposes ordinary Retry. Any
failure after the destructive boundary is conservatively uncertain and cannot
fall back to an unconditional write; the user can preserve the in-memory
revision by selecting another destination.

A conflict also offers separately confirmed Reload file and Overwrite file
actions when the source supports them. Reload discards local changes and opens
the current provider content. Overwrite captures the local revision, reads a
fresh exact-byte source baseline, and performs the same conditional write and
post-write verification against that baseline. It is not an unconditional
write: another change after inspection can cause a new conflict. These actions
do not turn an uncertain post-boundary failure into an automatic retry.

Save a copy streams the immutable revision through a reliable pipe and does not
retain the selected destination URI. A one-shot Parcelable transfer silently
releases each sending-process descriptor copy during Binder marshalling,
leaving the worker as the sole owner that can publish its reliable terminal
status. The producer traverses immutable source pieces without a
document-sized allocation, while the worker uses a fixed 64 KiB buffer. Saving
a copy never changes the source clean baseline.

New and received content has no source until the user selects Save. That first
empty destination becomes the live autosave source. Ordinary later Save actions
create copies. After a source failure, Save elsewhere explicitly replaces the
retained source, while Save a copy leaves the existing source and its baseline
unchanged. Export workers write only to the descriptor authorized for that
operation. Provider atomicity is never assumed: generic Storage Access
Framework descriptors cannot guarantee rollback or atomic replacement, and a
provider or process failure after truncation can leave the source incomplete.
They also expose no generic compare-and-swap replacement. The exact-byte
preflight prevents silent overwrite of any differing bytes present when that
check completes, while post-write checks prevent a mismatching result from
being reported as saved. A noncooperating writer can still race after the
preflight because the provider owns the backing object. The UI reports conflict
and uncertainty separately and retains the newest in-memory revision while the
process remains alive. Markdown changes only the selected MIME type and file
extension; it does not grant the export worker parsing authority.

URI equality rejects the live source selected directly as a replacement or
copy destination, but Android exposes no provider-independent way to prove that
two different content URIs are not aliases. A provider already controls its
own backing objects and must not return the retained source under a different
URI for a newly created destination.

## Markdown reading

Markdown preview captures one exact immutable revision and renders it through
a dedicated isolated Rust worker. Compose displays its bounded semantic
block-and-span model directly; BeauTyXT does not use a WebView or execute raw
HTML. CommonMark, GFM tables, tasks, strikethrough, alerts, extended autolinks,
footnotes, and the enabled superscript and subscript extensions retain their
presentation semantics.

A narrow semantic HTML subset converts nested sections, paragraphs, headings,
quotes, lists, preformatted code, tables, links, line breaks, images, emphasis,
deletion, superscript, and subscript to the same model. Only link destinations
and titles, image sources, alternative text and titles, and ordered-list start
values are parsed. Image sources are discarded, never fetched, and represented
by labeled alternative text. Unsupported tags and every other attribute,
including CSS and event handlers, remain inert source text.

Rendering is limited to 16 MiB of source, 16,384 blocks, 131,072 spans, and
bounded nesting, metadata, links, and output size. Every worker-emitted text
block is at most 4 KiB of UTF-8. Compose lays out those blocks and bounded table
runs lazily. Complete supported diagram fences can join their fragments within
the separate 16 KiB illustration limit. Native TeX and supported Mermaid
appearances use the separate workers, source-preserving fallback, and
accessibility alternatives described in [native illustrations](native-illustrations.md).

Link destinations become accessible native Compose links. Lazy grouping and
local heading and footnote indexes are prepared off the UI thread once per
rendered revision. Repeated heading names retain stable, collision-free
destinations without rescanning on every tap. Footnote references navigate only
within the rendered document. Explicit taps navigate to rendered headings or
pass destinations with approved web, communication, and map schemes to Android
without granting URI permissions. Unfamiliar app schemes require confirmation;
executable and local data schemes are blocked. BeauTyXT retains no Internet
permission.

The same in-memory preview can be reused when returning from the source editor
without a document change. An edit invalidates that revision; neither the
preview nor its illustration cache is persisted. In editable documents,
tapping rendered text opens its source position; tapping blank space below the
final rendered block opens the editor at the document end. Other unused
reading-page space returns to the retained editing position. Links, code-copy
buttons, scrolling, and long-press selection keep their own gestures. Back
clears a reading selection first; the next Back follows normal navigation.
Canceling a Back gesture leaves the selection intact. These shortcuts do not
make view-only sources editable.

Code blocks offer explicit whole-block copying, including off-screen renderer
fragments, as plain text without fences or added separators. Copies are bounded
to 65,536 UTF-16 code units; larger blocks remain selectable without silently
copying a prefix. Copying writes plain text to Android's clipboard with the
ordinary system preview. It never executes the code or creates an app-private
copy. No clipboard content is read as part of this action.

## Storage and lifecycle

BeauTyXT never writes document bytes or document-identifying metadata to its
private files, databases, preferences, logs, or crash reports. It creates no
internal working files, import or export staging files, recents, content
indexes, autosaves, recovery copies, or persistent session records. Android
backup remains disabled, and URI grants are not persisted. Import sources and
source-autosave stages are pathname-free anonymous descriptors, not private
storage entries. Sparse source-autosave packages retain only a sealed piece
table and payload plus an optional duplicate capability for the already sealed
session source.

Document text, edits, undo history, indexes, render results, and transfer
payloads and exact-byte source versions are session state. They live only in
process memory or bounded anonymous descriptors with explicit owners and
lifetimes. Closing a document drops its document descriptors and memory. The
selected URI and exact source version remain only with the live editor's source
owner and are forgotten when that editor closes. If closing overlaps an active
source job, the job retains its source owner, sealed package, and optional
source backing only until the write settles. Process death drops in-memory
state immediately and can also interrupt a provider-owned write, so unsaved
edits are intentionally lost and a source may be incomplete. The UI
communicates failures and requests confirmation before a deliberate close with
unsaved changes; BeauTyXT provides no process-death recovery or
provider-atomicity promise.

Find queries, matches, direction, and wrap state are transient session state.
They are never written to private storage, instance state, logs, indexes, or
the document. Closing Find clears its query and match, and closing the document
or losing the process clears the complete search state.

The Activity composition retains one application-session owner through
Compose's lifecycle-aware in-memory retain store. It owns any in-flight import,
live editor, bounded draft, and document-operation coroutine scope. Temporary
configuration destruction therefore neither closes a native document nor
cancels an operation after it has entered a loading or applying state. Terminal
Activity removal retires and closes that owner. None of these objects is
converted into instance state, selected source identity is not copied there,
and a new process always starts with an empty session.

This policy does not prevent Android from maintaining ordinary application
artifacts such as compiled code and runtime profiles, or BeauTyXT from storing
content-free interface preferences. Those artifacts must never contain
document content or identifying metadata.

## Sharing and local transfer

The Android Sharesheet is the general-purpose sharing path. A source-backed
document waits for its latest verified autosave and then shares the existing
provider URI with a temporary read grant. A small transient document is read
from one immutable snapshot into a bounded text extra. Neither path creates an
app-private file. Sharing first settles the newest editor draft. Incoming
`ACTION_SEND` content accepts one supported text or Markdown value or one
unambiguous content URI and requires explicit review before opening it. Direct
incoming and outgoing text share the same 128 KiB UTF-8 budget. Untouched
received content still requires confirmation before closing without saving,
with wording distinct from user edits.

QR and BeauTyXT-authored NFC records carry the same versioned,
integrity-checked Rust envelope containing the exact UTF-8 text and plain-text
or Markdown format. NFC can also receive a deliberately narrow set of standard
NDEF text representations. The transfer service runs separately from the
document and Markdown processes. It receives only bounded anonymous
descriptors and has no network or storage authority.

QR sharing accepts at most 1,536 UTF-8 bytes and returns a packed module grid
for direct Compose drawing. Foreground scanning requires camera consent, copies
only one bounded luminance frame at a time, and never saves frames. The
isolated worker rejects missing, ambiguous, malformed, oversized, or damaged
codes.

Saving a QR image encodes the 1,024-pixel bitmap as lossless WebP by default,
with PNG offered for compatibility. Both preserve identical pixels; WebP's
compression effort is 100 and does not reduce image quality. The selected
format, filename extension, and picker MIME type stay fixed throughout the
destination and write operation, including configuration changes. The image
is encoded into a size-bounded anonymous memory file and sealed before opening
the selected destination. A fresh instance of the isolated export service
used for document copies owns provider writes,
cancellation, and terminal verification. No temporary image path or second
image-sized byte array is created in the application.

NFC reading sends one complete serialized NDEF message, bounded to 256 KiB, to
the isolated Rust decoder. Ordinary messages must contain exactly one supported
top-level record: the BeauTyXT MIME envelope, an NFC Forum Text or URI record,
`text/plain`, a supported Markdown MIME type, or `text/html`. A single Smart
Poster is accepted only when its nested records resolve to exactly one URI;
one optional title is retained for review while action, icon, and unrelated
metadata is ignored. Multiple ordinary records, multiple candidate URIs,
malformed framing, invalid Unicode, unsupported types, and decoded text beyond
the 256 KiB UTF-8 ceiling are rejected. URI records never navigate. HTML stays
editable source and can enter only the existing non-executable Compose
Markdown presentation.

NFC writing creates one BeauTyXT MIME record with an optional one-to-three
character physical tag label. The complete serialized message remains within
the same 256 KiB ceiling. Writing requires a separate confirmation after
payload preparation, replaces the existing message only on an already
formatted, writable tag whose reported NDEF capacity is sufficient, and
verifies the exact serialized message by reading it back. Each confirmation
allows only one tag attempt; failure requires a new confirmation. Leaving the
foreground also disarms the writer. BeauTyXT never
formats a tag or makes it read-only. Android's reported hardware tag ID is
shown only as transient technical metadata and is never treated as identity.

Received QR and NFC text enters the same explicit review flow as Android shared
text. It remains in process memory and is not persisted until the user chooses
Save.

## Defensive limits

Each job declares its applicable byte and elapsed-work limits before work
starts. Parsers also enforce bounded block text, metadata, links, nesting,
node counts, and output size. Cancellation closes the worker's descriptors and
invalidates its generation.

Worker death is a normal error path. BeauTyXT never treats a partial output as
success. Cancellation that wins before the native output boundary leaves an
existing supported regular copy destination unchanged. After ordinary copy
output begins, native rollback best-effort resets a failed seekable destination
and every accepted failure closes a reliable provider descriptor with an error.
A provider may still retain incomplete output because atomicity is
provider-owned. Retryable pre-output source failures are rebound only for an
explicit Retry. Source conflicts and indeterminate post-boundary writes never
retry automatically or unconditionally; the in-memory document remains
available for Save elsewhere or Save a copy.

## Dependency policy

Material 3 Expressive is pinned to an exact alpha version. Shared theme and
motion construction live in `ui/designsystem`; feature UI consumes Material
components directly and opts into experimental APIs at its use sites. Upgrades
include visual, accessibility, and interaction regression tests.

Dependencies follow their existing stable or explicitly selected alpha channels;
transitive versions remain subject to upstream compatibility constraints. Rust
dependencies use explicit features and a checked-in lockfile. Licenses and
provenance are reviewed before adoption, including obligations for MPL-covered
components and bundled OFL fonts. Third-party notices and source references do
not inherit BeauTyXT's MIT license. See [contributing](../CONTRIBUTING.md#third-party-notices)
and the [release dependency policy](release.md#dependency-review).

Core crates forbid unsafe code. Android Binder and JNI interop lives in small
boundary crates with focused tests and documented safety invariants.

## Verification

Deterministic tests cover persistent Unicode edits, cross-piece UTF-8 and CRLF,
bounded positioned I/O, import and export limits, cancellation races, provider
and worker failure, descriptor cleanup, Markdown model bounds, transfer
integrity, and 100 MiB import, editing, and sparse-save acceptance cases.

See the [release process](release.md) for candidate verification and
reproducibility requirements. Keep results with their tested revision in ignored
local evidence directories; a previous result does not verify a later checkout.
