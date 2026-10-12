# 0.16.0 validation

## Target

- DeepSeek Harness `dsh-v0.2.1-alpha.2`, peeled commit
  `d743267388641bc76f17c45ce8b4c231aed1d32c`.
- Separate source worktree: `G:/LAB/deepseek-harness-alpha2`.
- Frozen install with the declared pnpm `11.28.5` and `build:lib` completed.
- Release target: `0.16.0`; publication has not been performed.
- Tests below ran before the version-only correction, with app version `0.15.0`.
  The corrected version derives versionCode `1600`; existing APKs must be rebuilt
  before release.

## Evidence so far

Before publication, merged remote main through `a10c80c` (including 0.15.0,
compact transcripts and Termux support). Reasoning translation now renders inside
the compact thinking panel. Full post-merge validation passed in one run:
core and mock tests, debug/release app unit tests, debug/release lint, release
assembly, all 30 Android instrumentation tests (zero failures/skips), and all
14 real-host conformance tests. Earlier totals below describe the pre-merge code.

The first mobile test run exposed a historical fixture assertion that incorrectly
equated the alpha.1 fixture to the current baseline. The assertion now checks its
original commit; the new alpha.2 fixture separately checks the current pin.

The following run passed core tests, app debug unit tests, debug lint and debug APK
assembly. Real-host tests did not run because the new test used a positional
`SessionAddress.Session` argument. That constructor call is now corrected.

The next run passed core tests, app debug unit tests, debug lint and APK assembly.
Real-host conformance ran 14 tests: 13 passed, including working-directory projection
changes and native HTTPS authentication/unary/mux checks. The local plugin lifecycle
test failed with `ERR_PNPM_ADDING_TO_ROOT`. The test host inherited global pnpm 9
instead of the checkout's pnpm 11; its process PATH now prefers the local toolchain.
The targeted management rerun passed all three tests, including inspect/install/
disable/update/remove, confirming the toolchain mismatch. Across the full run and
targeted rerun, all 14 conformance scenarios have passed.

Release unit tests, release lint and unsigned release APK assembly passed.
Android instrumentation could not start: emulator-5554 timed out responding to
ADB property queries, so Gradle reported an unknown API level and no compatible
device. A separate API 30 validation emulator booted successfully with software
rendering. All five transcript instrumentation tests passed with zero skips.
The complete Android instrumentation suite then passed: 25 tests, zero failures
and zero skips on Android 11 / API 30 (`DSH_Validation_API30`, emulator-5556).
The stale emulator-5554 property warning persisted but did not prevent this run.

## Release gate

JUnit report totals: core 265 tests, zero failures/skips; app debug and release
311 tests each, zero failures and three skipped live-relay conformance tests each.
Live relay compatibility has not been freshly verified in this run.

Pre-correction build metadata confirms `versionName=0.15.0`, `versionCode=1500` for both
`app/build/outputs/apk/debug/app-debug.apk` and
`app/build/outputs/apk/release/app-release-unsigned.apk`.

No local release-signing environment variables are configured. An unsigned release
build does not establish installability or upgrade compatibility with the published
app. Signed artifact verification and a signed upgrade installation remain required.
The release workflow now requires all signing secrets and verifies the APK signature.
GitHub has the four required signing secrets configured. It uploads a draft release
so the signed artifact can be upgrade-tested before publication.

## Scope

Changes cover migration-aware DTOs and fork errors, cached preset ordering, current
working directories, external subagents, official plugin install targets, optional
reasoning translation, and native HTTPS conformance. Browser-specific font and
collapse settings are not represented as Android preferences by this change.
Existing mobile themes, scalable system text and disclosure controls remain in use.
