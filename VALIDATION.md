# AURA v0.3 validation

## Preserved baseline

PR #1 verified head: `e97c5678e114398d859f26c917cdf1a5c27e5a4f`; merged main: `4796e968012f74e7d521e6e687540d1f1bf4a2e5`. v0.3 branched from updated main. Baseline Android (3 tests/build/lint), backend (8 tests), web (3 tests/typecheck/build and 1 browser integration) passed before changes. Baseline local APK: **9,158,756 bytes**. See `docs/BASELINE_V02.md`.

## Executed v0.3 checks

- Backend/API/evaluation: 35 tests passed, including all unchanged v0.2 tests, additive migration, typed validation, provenance, idempotency, auth/device ownership and evaluation CLI smoke.
- Web: 7 unit tests, strict TypeScript/production build and 2 real-API Playwright browser flows passed (including legacy RMS, typed sound rule, model-versus-simulation labels, escaped untrusted labels and 390px viewport).
- Android: 13 Kotlin/JVM tests, debug APK/test-APK compilation and lint passed locally. Real native Android inference, migration and Start/Stop passed in the first emulator run; one navigation/history UI assertion failed and is being fixed before final CI validation.
- Actual host LiteRT smoke: verified bundled model and checksums, `[15600]` float input, `[1,521]` finite float output, embedded label alignment, and the attributed bark fixture's Bark response. This is actual inference, not a fake classifier.
- No physical-device results or calibration accuracy numbers are claimed.

## Measurements (not phone benchmarks)

Initial host smoke on Linux x86_64 / Python 3.12.14 / LiteRT 2.3.0, 2 native threads:

- Model initialization: 34.58 ms.
- Mean inference: 1.37 ms; p95: 2.21 ms over 12 repeated fixture windows.

These are development-host CPU timings, not Android, power, natural-event accuracy or thermal measurements. One repeated bark fixture response is an integration assertion, not a calibrated probability. The model asset is **4,126,810 bytes**. Final APK comparison and CI artifact links are recorded below after the final build.

Android runtime instrumentation exposes initialization time, session mean and last-128 p95 inference latency, processed windows, windows/s, failures, dropped windows, Java heap estimate, Android process PSS and foreground-service errors. One consumer and one waiting window bound concurrency. No battery consumption numbers are inferred from CI.

## Remaining validation

The full physical-phone matrix is PENDING in `docs/ANDROID_V03_VALIDATION.md`: signing-compatible v0.2 upgrade, real microphone/permission/privacy behavior, controlled playback, natural backgrounds, screen lock, notification stop/disable, airplane mode, repeated lifecycle, 30-minute endurance, long battery/thermal observation and HTTPS metadata synchronization. Emulator/JVM tests do not mark these passed.

Calibration is incomplete: no representative held-out natural-event dataset has been evaluated. Provisional thresholds, aggregation semantics, source/licensing/fixture limitations and evaluation partitioning are documented. Android debug keys can differ between CI runs; installing over v0.2 requires the original signing certificate. CI validates the preference/schema migration, not arbitrary cross-key package upgrades.

Nonblocking tool warnings can include newer AndroidX dependencies, Android analytics home configuration in this workspace, and a local Starlette/AnyIO alias deprecation. Lint errors and test failures are not suppressed.
