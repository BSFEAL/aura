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
