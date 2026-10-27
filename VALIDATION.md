# AURA v0.3 validation

## Preserved baseline

PR #1 verified head: `e97c5678e114398d859f26c917cdf1a5c27e5a4f`; merged main: `4796e968012f74e7d521e6e687540d1f1bf4a2e5`. v0.3 branched from updated main. Baseline Android (3 tests/build/lint), backend (8 tests), web (3 tests/typecheck/build and 1 browser integration) passed before changes. Baseline local APK: **9,158,756 bytes**. See `docs/BASELINE_V02.md`.

## Executed v0.3 checks

- Backend/API/evaluation: 35 tests passed, including all unchanged v0.2 tests, additive migration, typed validation, provenance, idempotency, auth/device ownership and evaluation CLI smoke.
- Web: 7 unit tests, strict TypeScript/production build and 2 real-API Playwright browser flows passed (including legacy RMS, typed sound rule, model-versus-simulation labels, escaped untrusted labels and 390px viewport).
- Android: 13 Kotlin/JVM tests, debug APK/test-APK compilation and lint passed locally. All **5 Android emulator instrumentation tests passed** on verified snapshot `e9f6929`: actual native model → typed local episode, preference migration, permission denial, explicit Start/Stop/notification lifecycle, and rule editing/history rendering. Final PR checks always validate the newest commit.
- Actual host LiteRT smoke: verified bundled model and checksums, `[15600]` float input, `[1,521]` finite float output, embedded label alignment, and the attributed bark fixture's Bark response. This is actual inference, not a fake classifier.
- No physical-device results or calibration accuracy numbers are claimed.

## Measurements (not phone benchmarks)

Initial host smoke on Linux x86_64 / Python 3.12.14 / LiteRT 2.3.0, 2 native threads:

- Model initialization: 34.58 ms.
- Mean inference: 1.37 ms; p95: 2.21 ms over 12 repeated fixture windows.

These are development-host CPU timings, not Android, power, natural-event accuracy or thermal measurements. One repeated bark fixture response is an integration assertion, not a calibrated probability. The model asset is **4,126,810 bytes**. Local v0.3 APK: **28,609,794 bytes**, versus **9,158,756 bytes** for v0.2: **+19,451,038 bytes**. The increase includes the 4.13 MB model, universal native TFLite runtimes and serialization/UI code. The model SHA inside the APK was verified, and the test-only bark fixture is absent from the user APK. CI artifact byte counts can vary slightly with signing/toolchain.

Android runtime instrumentation exposes initialization time, session mean and last-128 p95 inference latency, processed windows, windows/s, failures, dropped windows, Java heap estimate, Android process PSS and foreground-service errors. One consumer and one waiting window bound concurrency. No battery consumption numbers are inferred from CI.

## Remaining validation

The full physical-phone matrix is PENDING in `docs/ANDROID_V03_VALIDATION.md`: signing-compatible v0.2 upgrade, real microphone/permission/privacy behavior, controlled playback, natural backgrounds, screen lock, notification stop/disable, airplane mode, repeated lifecycle, 30-minute endurance, long battery/thermal observation and HTTPS metadata synchronization. Emulator/JVM tests do not mark these passed.

Calibration is incomplete: no representative held-out natural-event dataset has been evaluated. Provisional thresholds, aggregation semantics, source/licensing/fixture limitations and evaluation partitioning are documented. Android debug keys can differ between CI runs; installing over v0.2 requires the original signing certificate. CI validates the preference/schema migration, not arbitrary cross-key package upgrades.

Nonblocking tool warnings can include newer AndroidX dependencies, Android analytics home configuration in this workspace, and a local Starlette/AnyIO alias deprecation. Lint errors and test failures are not suppressed.

The offline WAV-manifest inference CLI was also exercised with a single attributed fixture wrapped as PCM16 WAV. This checks audio loading/resampling/model execution/report generation; it is not a calibration dataset. Dependency audits of backend requirements and web packages found no known vulnerabilities. Dropped-window diagnostics count inference-queue overflows, not unobservable audio-driver overruns.

## Verified CI snapshot and emulator measurements

Snapshot `e9f6929e069040340663e1129dbc983076628b27`:

- [Python (35 passed)](https://github.com/BSFEAL/aura/actions/runs/40425627319)
- [Web (7 unit + 2 browser flows, typecheck/build)](https://github.com/BSFEAL/aura/actions/runs/40425627299)
- [Android build/lint/JVM + all 5 emulator tests](https://github.com/BSFEAL/aura/actions/runs/40425627569)
- [Debug APK artifact](https://github.com/BSFEAL/aura/actions/runs/40425627569/artifacts/12698479445)

Android API 30 x86_64 **CI emulator**, 12 repeated fixture windows: model init **78 ms**, mean inference **3.25 ms**, nearest-rank p95 **4 ms**, process PSS **99,870 KiB** at measurement. These are emulator numbers, not physical-phone measurements, steady-state power or real-world detection accuracy. Machine-readable reports: `docs/measurements/v03-emulator-model.json` and `v03-host-model.json`. The Android benchmark uses integer-millisecond timings; the host report uses high-resolution timing. Always use the latest PR checks/artifact for final installation; the above link is a recorded verified snapshot.

## v0.3.1 local metadata backup follow-up — 2026-10-27

Adds an explicit Storage Access Framework Backup screen, a bounded/versioned metadata format and merge-only restore. New restored rules are disabled/local with pinned profiles preserved; imported history is visibly restored/unverified and does not replay notifications. One preferences commit saves both collections after validation. File IO runs off the UI thread. No credentials, audio, networking or broad storage permission is added. Version code 4 / version name 0.3.1 retains the existing application ID.

Local checks: **20 Kotlin/JVM tests** (13 existing + 7 backup), **35 backend tests**, **7 web unit tests**, **2 real-API Playwright tests**, TypeScript/production build, Android debug and instrumentation APK compilation, and lint passed. The pinned model/labels/fixture integrity and actual host LiteRT inference also passed. Local debug APK measured **28,662,990 bytes** in this toolchain; CI artifacts may differ with signing/toolchain. The new Android tests verify persisted restore, no activation/alert replay, destination settings preservation, conflict/corrupt-input behavior and Backup controls; they require the CI emulator for execution. Latest executed results and artifact are recorded on the follow-up PR.

The user reports bark events, lock/Stop/quiet-room/five-cycle checks and a 30-minute session working. These are informal reports, without device identity, battery/thermal change or diagnostic counters. See `docs/ANDROID_V03_VALIDATION.md`; full measurements and the new physical file-provider checks remain pending. Calibration remains incomplete. Signing setup is deliberately deferred and PR #3 remains separate: this change cannot bypass certificate compatibility, recover an earlier uninstall or add backup functionality to an old APK without a compatible update.


## v0.3.2 rule feedback and presets — 2026-10-27

Adds exact class labels/IDs and rule coverage notes to Live, plus disabled/stopped/starting/unavailable, score, persistence, gap, active/duplicate and cooldown explanations. Cooldown/gap timing is read from scalar snapshots of the existing monotonic detector, not a second rule engine. Notification readiness is separate from detection; `posted` is explicitly a request, not proof of audible delivery. Android channel settings can be opened explicitly and are rechecked on resume. Two provisional form-only presets require explicit Save and preserve profile pins, class filter, cooldown and notification policy. Editing retains original rule text. There is no LLM integration or automatic threshold/mapping change.

Executed local checks: **27 Kotlin tests** (all 20 prior + 7 feedback), **35 Python tests**, **7 web tests**, **2 real-API browser tests**, TypeScript/production build, Android debug/instrumentation APK compilation and lint passed. Pinned assets and actual host LiteRT inference passed. Local APK size **28,692,932 bytes**, version code 5 / name 0.3.2 (toolchain-dependent). New emulator presentation tests use explicitly synthetic UI fixtures; actual native inference remains separately tested and now verifies display class labels against the selected model. Latest emulator results and verified downloadable APK metadata are recorded in the PR.

This branch is stacked on the unmerged backup PR #4; signing PR #3 remains separate and deferred. New feedback/preset/settings behavior needs physical-phone validation in `docs/RULE_FEEDBACK.md`. No accuracy, natural-event reliability, battery/thermal or compatible-installed-certificate claim is made.


## v0.4.0 OpenAI natural-language rule preview — 2026-10-27

Implements an opt-in OpenAI Responses planner on the local backend with authenticated, consented text-only requests. Keys remain server-side. Strict structured output is independently checked against existing typed-rule contracts; Android validates the resulting envelope, original request/source binding, disabled state, catalog labels and profile pins again. Previews never persist rules/events or activate devices. Save is separate and disabled by default. Once enabled, typed rules run locally without another provider request. No schema/database destruction, class remapping, raw-audio upload, emergency call or fabricated/offline LLM fallback is added.

Local results: **68 Python tests**, **32 Kotlin/JVM tests**, **7 web unit tests**, **3 real-API browser integration tests**, TypeScript/production build, Android debug/instrumentation APK compilation and lint passed. Pinned model integrity and actual host LiteRT inference passed. The provider protocol/browser tests inject explicitly test-only HTTP fixtures; they are not live OpenAI calls or semantic-accuracy measurements. The new Android consent/preview/disabled-save UI test requires the CI emulator; results and final APK link are recorded in the PR. Local APK **28,736,539 bytes**, version name 0.4.0 / code 6 (toolchain-dependent).

Live OpenAI key/model availability, natural-language interpretation quality, private HTTPS phone connectivity, actual cloud latency/cost and new phone UI behavior are **PENDING** until the user's machine is configured and `tools/openai_rule_smoke.py --allow-send-text` is explicitly run. No real key was supplied to or embedded by this implementation. See `docs/OPENAI_RULE_BUILDER.md` for local process-environment setup and safe testing. Signing stays deferred. This work is stacked on PR #5; PRs #4/#5 must be merged/retargeted in order before this PR reaches main.
