# Contributing

Thanks for your interest in BeauTyXT.

Please discuss substantial changes in the issue tracker before implementing
them. Development questions can also be asked in the
[BeauTyXT Matrix room](https://matrix.to/#/#beautyxt:matrix.org).

BeauTyXT accepts Kotlin for the Android and Compose integration layer and Rust
for document processing and native services. Java is not accepted. Android
Views should only be introduced when a required behavior cannot be implemented
accessibly and efficiently with Compose.

Keep unsafe Rust confined to small platform interop modules. Every unsafe block
must state and enforce its safety invariants. Core document and parsing crates
must forbid unsafe code.

Before submitting a change, run the applicable formatters, static analysis, and
tests. Commit changes in small logical units with imperative, lowercase commit
headers shorter than 72 characters. Hard-wrap prose in commit bodies to 72
characters, preserving URLs, code, and trailers.

Tests should protect observable behavior, important invariants, or a concrete
regression. Avoid tests that merely repeat a getter, static wording, or a
library guarantee. Keep overlapping unit and device coverage only when the
device test exercises additional Android integration behavior.

English is the only supported interface language. Translation contributions
are not accepted or solicited; see the maintainer-controlled
[language policy](docs/product-direction.md#languages). Keep user-facing text
in Android string and plural resources.

Use Material Symbols Rounded Android vector drawables for standard interface
icons, loaded with `painterResource`. Match the existing 24 dp source assets,
preserve their automatic mirroring, and let `Icon` apply theme colors. Record
imported assets in `CREDITS` and the bundled notices as described below.

Use the [release process](docs/release.md) for release-candidate verification,
reproducibility checks, Accrescent packaging, and signing decisions.

## Third-party notices

After changing a Rust, Android, or JVM dependency, audit the resolved release
graph and update the component list and any license clarifications in
`about.hbs` and `about.toml`. Generate the bundled notice with cargo-about
0.8.0, then normalize line endings inherited from dependency license files:

```sh
cargo about generate about.hbs --workspace --locked --fail \
    --output-file app/src/main/res/raw/third_party_notices.txt
sed -i 's/\r$//' app/src/main/res/raw/third_party_notices.txt
```

RaTeX's published crates omit their repository-root MIT license. Notice
generation fetches that file at the verified revision in `about.toml` and
checks its SHA-256. It therefore needs network access; `--frozen` cannot replace
`--locked` here. This affects notice regeneration only, not ordinary app builds
or runtime behavior. The separate KaTeX code/data and font notices remain in
the template, with their upstream source references.

Code copied from or based on another project must use a permissive license and
must be recorded in `CREDITS` with its exact upstream source, copied or adapted
status, and applicable license text. Preserve required notices in the original
files too. Existing dependencies also include MPL-covered components and OFL
fonts; their notices and source references remain in the bundled attribution.
BeauTyXT's MIT license does not replace third-party licenses. Review any new
dependency's obligations before adoption rather than assuming that an existing
license exception authorizes another one.

## Local verification artifacts

Keep screenshots, recordings, generated documents, and build/test logs in
ignored project directories such as `captures/`, not in Git. Release evidence
must identify the exact tested revision and any coverage limitations; it does
not replace verification of a later revision.

## Device-test providers

The Kotlin providers in `test-providers/` supply synthetic import sources and
export destinations, including paused and failing streams. They run in their
own test-only APK with a separate UID and Kotlin runtime. Putting them in the
instrumentation APK would leave their standalone process without Kotlin's
runtime: Android's build plugin omits dependencies already packaged in the app
under test. Only the provider protocol constants are shared with the tests.

`androidTestUtil` installs this helper for Gradle's connected-device tests.
For manual `adb shell am instrument` runs, build `:test-providers:assembleDebug`
and install `test-providers/build/outputs/apk/debug/test-providers-debug.apk`
with `adb -s SERIAL install -r -t`, alongside the debug and instrumentation
APKs. Always select the intended emulator or explicitly authorized device.
The helper has no release variant or launcher activity. A signature permission
and caller-package validation restrict it to the debug test target; neither
the helper nor its access permission ships in staging or production.

The verified direct-run workflow is:

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest \
    :test-providers:assembleDebug
adb -s SERIAL install -r -t \
    test-providers/build/outputs/apk/debug/test-providers-debug.apk
adb -s SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s SERIAL install -r -t \
    app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s SERIAL shell am instrument -w -r \
    dev.soupslurpr.beautyxt.debug.test/dev.soupslurpr.beautyxt.document.DocumentBridgeInstrumentation
```

Require the explicit verification-passed message, a completed test status, and
`INSTRUMENTATION_CODE: -1`; the shell command's exit code alone is insufficient.
The runner reports one test for the selected batch, with individual phases in
logcat. An unknown `-e phase` is a failure, not an empty successful run.

With AGP 9.4.1, the connected-test task's `--serial` filter throws an upstream
immutable-list exception. `ANDROID_SERIAL=SERIAL` avoids that filter, but on the
API 37 emulator the runner then passes an invalid Android user ID (`-2`) and
can report build success with zero tests. Our connected-test result guard now
fails the task in that situation. It clears stale XML results before the run
and requires nonempty, successful test reports for every reported device;
missing, malformed, failed, and skipped-only reports cannot pass. Its regression
checks run with `:app:verifyDeviceTestResultsGuard` and as part of `:app:check`.
This does not fix the upstream launch error: until that is resolved, use the
direct command above to run the tests.

The `isolated service death` phase uses a debug-only native Binder transaction
to terminate the import worker: `am crash` requests a VM crash and cannot test
an ART-free service. Cargo's release profile excludes this transaction from
staging and release builds. The illustration crash/hang probe is a separate
native library packaged only in debug builds. The opt-in `import service
profile` phase measures seven fresh bindings with `dumpsys meminfo --local`;
ordinary application memory dumps require a managed runtime in the target.

## Native print diagnostics

Instrumentation removes its named PDF-fixture directories before verification
and after each print phase. This keeps synthetic output out of subsequent
app-storage audits without excluding any storage from those audits.

To inspect generated PDFs, select a print phase with `-e phase` and add
`-e retainPrintArtifacts true`. Pull the needed files into an ignored project
directory before the next ordinary run, which removes those test artifacts.
