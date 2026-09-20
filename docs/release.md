# Release process

BeauTyXT uses one monotonically increasing integer for both `versionCode` and
`versionName`. Change `versionCode` in `app/build.gradle.kts` only when a new
release candidate is intentionally started.

Build artifacts and verification evidence may live in ignored project directories
such as `captures/`; do not commit them to Git. Keep production signing material
outside the worktree. Never configure a production keystore, alias, or password
in a Gradle file or commit them to Git.

## Toolchain

Use the versions pinned by the project:

- Java 21
- Android compile SDK 37.1 (minimum and target SDK remain 37)
- Android NDK 30.0.16248370, native API 37
- the Rust toolchain in `rust-toolchain.toml`
- cargo-ndk 4.1.2
- bundletool 1.18.3

## Verification

Start from a clean worktree. Regenerate the third-party notice as described in
`CONTRIBUTING.md` if any dependency changed, then run:

```sh
cargo fmt --all -- --check
cargo clippy --workspace --all-targets -- -D warnings
cargo test --workspace
./gradlew clean :app:verifyDeviceTestResultsGuard :app:testDebugUnitTest :app:lintRelease \
    :test-providers:lintDebug :app:bundleRelease
```

For separate staging APKs, use `-Pbeautyxt.splitApksByAbi=true` with
`:app:assembleStaging`. Build `:app:bundleRelease` in a separate invocation
without that property; AGP does not support combining per-ABI APK resource
shrinking with bundle packaging.

On an Android 17 emulator and a physical test device,
verify the candidate before preparing the production-signed artifact:

- Run the document instrumentation suite, including worker ownership,
  cancellation, provider failures, and process-death handling. Run opt-in
  large-file and platform-font tests explicitly and record any omitted checks.
- Exercise the installed minified staging build: source autosave immediately
  after composing input and Home, keyboard-only Back, rapid Back with unsaved
  content, rotation, source/preview navigation, Find, and large-file scrolling.
- Inspect light/dark, landscape, and large-text layouts, supported Markdown,
  math and diagrams, both QR image formats, and actual printed PDFs. Check
  NFC and camera lifecycle; distinguish software tests from physical-target
  read/write and scan tests.
- Verify package identity, version, signer, native/ZIP alignment, packaged
  notices, private isolated workers, and the absence of Internet permission
  and debug-only components. Exercise an in-place staging upgrade without
  clearing data; it is not a production-lineage upgrade test.

Record the exact commit, toolchain, commands, artifact sizes/digests, results,
and coverage limitations alongside the ignored local evidence. A test count
or snapshot from an earlier revision does not verify the final release.
After squashing or otherwise changing the release revision, repeat its build
verification. Production signing, the exact signed upgrade rehearsal, merging
into `main`, pushing, and publication require separate approval.

## Dependency review

Before adding or updating a dependency, review its provenance, maintenance and
security history, transitive dependencies, and build-time code before building
it. Keep the ordinary development build workflow; no separate build-isolation
infrastructure is required. Pins and checksums do not replace this review.

Use current stable releases outside the existing Compose/Material and Merman
alpha channels. Review upstream compatibility constraints instead of forcing
incompatible transitive updates or introducing private forks to align versions.

The diagram stack uses `usvg` 0.48.1 and HarfRust/Fontations, not RustyBuzz.
Its font adapter still directly uses `ttf-parser` 0.25.1, whose
[maintenance advisory](https://rustsec.org/advisories/RUSTSEC-2026-0192.html)
is an accepted, unresolved risk. Revisit it when evaluating an appropriate
upstream update or a new relevant advisory. Isolation and Android-selected
font inputs limit exposure but do not establish that the library is defect-free.
`usvg` brings Skrifa 0.44; math/printing retain Skrifa 0.47 rather than being
downgraded to match it.

The unmodified Merman sequence parser also has a known contained stall. The
[native illustration reference](native-illustrations.md#known-parser-limitation)
describes its fallback and regression coverage. It is an accepted limitation,
not a pending private patch or upstream submission.

## Reproducibility

The unsigned app bundle is the reproducible source for the signed split APK
set. Build it twice from the same clean commit and toolchain, preserving the
first result outside the build directory and disabling Gradle build caching:

```sh
mkdir -p captures/reproducibility
./gradlew clean :app:bundleRelease --no-build-cache
cp app/build/outputs/bundle/release/app-release.aab \
    captures/reproducibility/first.aab
./gradlew clean :app:bundleRelease --no-build-cache
cmp captures/reproducibility/first.aab \
    app/build/outputs/bundle/release/app-release.aab
sha256sum app/build/outputs/bundle/release/app-release.aab
```

APK signatures may use randomness, so independently signed APK sets are not
required to be byte-identical. Verify their contents and signatures instead.

AGP 9.4 enumerates native debug-symbol files without sorting them. The release
bundle therefore uses a public `SingleArtifact.BUNDLE` transform to stream all
ZIP entries in stable name order. It retains every entry, including native
symbols, along with entry metadata; it does not strip symbols or normalize
away content differences. The final artifact keeps the standard path above.
Debug/staging APK assembly and signing configuration are unchanged. The ZIP
compression implementation comes from the build JVM, so use the same toolchain
for both builds.

Always require a passing raw `cmp`; matching uncompressed entries alone is not
byte-for-byte reproducibility. Repeat this comparison on the final approved
release commit, not just a development snapshot. Record which Gradle, Cargo,
and toolchain caches were retained; disabling Gradle's build cache alone is
not a clean-room or cross-machine reproducibility claim. If embedded build
metadata omits the Git revision, retain it explicitly with the evidence.

## Accrescent artifact

Accrescent accepts a developer-signed split APK set rather than a monolithic
APK. Follow the current [Accrescent build documentation][accrescent-build] and
[publishing requirements][accrescent-requirements]. For manual signing,
provide the keystore and key alias, then enter the keystore password at
bundletool's terminal prompt. No password file is needed:

```sh
bundletool build-apks \
    --bundle=app/build/outputs/bundle/release/app-release.aab \
    --output=/secure/output/beautyxt.apks \
    --ks=/secure/keys/beautyxt.jks \
    --ks-key-alias=beautyxt
```

This example assumes the key and keystore share a password. If they differ,
provide the key password using `--key-pass=file:/secure/secrets/key-password`.
For automation, `--ks-pass=file:/secure/secrets/keystore-password` can also
read a restricted local file. Keep any such files outside the repository;
never put plaintext passwords in command arguments or shell history. See
the [bundletool password options][bundletool-passwords].

Do not omit the keystore flags: bundletool otherwise falls back to a debug
keystore when one is available. Inspect the set, verify every split with
Android SDK 37's `apksigner`, and install the exact set on an Android 17 test
device before uploading it.

The release bundle keeps language resources together. Accrescent installs all
language splits, so generating a separate APK for every dependency locale only
adds package and download overhead. ABI and density resources remain split.

## Post-quantum signing

Android 17 supports APK Signature Scheme v3.2 hybrid signing with a new
classical key and an ML-DSA key. Keep the migration prepared, but do not use it
for a production Accrescent release until Accrescent explicitly supports v3.2
APK sets and the release tooling can generate them directly. Accrescent's
[requirements][accrescent-requirements], checked on September 19, 2026, list v2,
v3, and v3.1; this does not establish that every v3.2-containing set is rejected.
Distribution support remains unconfirmed, and the reviewed bundletool 1.18.3
does not offer v3.2 hybrid-signing inputs. Recheck both before production
signing rather than treating this checkpoint as a permanent restriction.

Until that distribution gate is resolved, sign releases with the established
production lineage. Do not create production ML-DSA material by converting an
expanded private key into a seed-only encoding. Generate and back up the final
classical and post-quantum keys through an audited production process once the
complete publishing path supports them.

[accrescent-build]: https://accrescent.app/docs/guide/getting-started/building.html
[accrescent-requirements]: https://accrescent.app/docs/guide/appendix/requirements.html
[bundletool-passwords]: https://developer.android.com/tools/bundletool#generate_apks
