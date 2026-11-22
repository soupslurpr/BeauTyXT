# Contributing

Thanks for your interest in BeauTyXT.

Please discuss substantial changes in the issue tracker before implementing
them. Development questions are also welcome in the
[BeauTyXT Matrix room](https://matrix.to/#/#beautyxt:matrix.org).

## Code and UI conventions

- **Languages:** Kotlin for the Android/Compose layer and Rust for document
  processing and native services. Java is not accepted. Use Android Views only
  when Compose cannot implement a required behavior accessibly and efficiently.
- **UI:** Follow Material 3 Expressive, reusing `ui/designsystem` and existing
  components. See [architecture](docs/architecture.md#dependency-policy).
- **Icons:** Use 24 dp Material Symbols Rounded Android vector drawables via
  `painterResource`, preserving auto-mirroring and letting `Icon` apply theme
  colors. Record imported assets in `CREDITS` and bundled notices.
- **Unsafe Rust:** Core document and parsing crates must `#![forbid(unsafe_code)]`.
  Confine `unsafe` to small JNI/Binder interop modules with documented safety
  invariants.
- **Localization:** English is the only supported interface language; keep
  user-facing copy in Android string/plural resources, and do not submit
  translations (see [language policy](docs/product-direction.md#languages)).
- **Tests:** Protect observable behavior, invariants, or concrete regressions
  rather than repeating getters or library guarantees.
- **Commits:** Small logical units with lowercase imperative headers under 72
  characters and prose bodies hard-wrapped to 72 characters (preserving URLs,
  code, and trailers). Run formatters, linters, and tests before submitting.

See [docs/release.md](docs/release.md) for release-candidate verification,
reproducibility, Accrescent packaging, and signing.

## Third-party notices

After changing any Rust, Android, or JVM dependency, audit the resolved release
graph, update `about.hbs` and `about.toml`, and regenerate the bundled notices
with `cargo-about` 0.8.0 (normalizing line endings):

```sh
cargo about generate about.hbs --workspace --locked --fail \
    --output-file app/src/main/res/raw/third_party_notices.txt
sed -i 's/\r$//' app/src/main/res/raw/third_party_notices.txt
```

RaTeX's published crates omit their repository-root MIT license, so notice
generation fetches it at the pinned revision in `about.toml` and verifies its
SHA-256 (requiring network access only during regeneration, not ordinary builds
or runtime). Separate KaTeX code/data and font notices remain in `about.hbs`.

Any copied or adapted third-party code must be permissively licensed and
recorded in `CREDITS` with exact upstream provenance, copied/adapted status,
and license text while preserving original file notices. Existing MPL/OFL
dependencies keep their bundled notices and source references; BeauTyXT's MIT
license never replaces third-party licenses.

## Device tests

`test-providers/` builds a separate test-only helper APK (distinct UID and
Kotlin runtime, signature-permission gated, absent from staging/release) that
supplies synthetic SAF providers and separate-UID share callers/receivers.

Because AGP's connected-test `--serial` filter and emulator user-ID handling
can misbehave (guarded by `:app:verifyDeviceTestResultsGuard`), run device
instrumentation directly against an explicit `SERIAL`:

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest \
    :test-providers:assembleDebug
adb -s SERIAL install -r -t \
    test-providers/build/outputs/apk/debug/test-providers-debug.apk
adb -s SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s SERIAL install -r -t \
    app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s SERIAL shell am instrument --user 0 -w -r \
    dev.soupslurpr.beautyxt.debug.test/dev.soupslurpr.beautyxt.document.DocumentBridgeInstrumentation
```

- **Pass criteria:** require the `verification passed` stream message, a
  completed test status, and `INSTRUMENTATION_CODE: -1` (shell exit code alone
  is not sufficient).
- **Single phase:** pass `-e phase 'PHASE_NAME'` (unknown phase names fail).
  See `DocumentBridgeInstrumentation.kt` for the full list of standard and
  opt-in phases (staging journeys, profiling, gesture navigation, physical
  NFC/camera checks).
- **Print diagnostics:** add `-e retainPrintArtifacts true` with a print phase
  to keep generated PDFs/images for inspection; the next ordinary run clears
  them automatically before storage audits.
