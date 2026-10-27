## AURA v0.3 — on-device sound intelligence

The verified v0.2 platform now runs a real, pinned YAMNet TFLite model locally on Android and turns noisy scores into versioned sound-rule episodes. Explicit microphone activation, visible foreground notification/Stop, RMS-only operation, local history, manual authenticated sync and existing v0.2 API payloads remain supported. No raw microphone audio is persisted or uploaded, and no cloud inference or emergency calls are added.

### Architecture and implementation

- Branched from merged v0.2 main (`4796e968…`); verified PR #1 head `e97c5678…` is preserved. Baseline tests and 9,158,756-byte local APK recorded before changes.
- Bundled official Apache-2.0 Google YAMNet classification TFLite v1. SHA-256 `10c95ea3eb9a7bb4cb8bddf6feb023250381008177ac162ce169694d05c317de`; 4,126,810 bytes, float32 `[15600]` input at 16 kHz and `[1,521]` outputs. Embedded labels/AudioSet IDs and curated mappings are integrity-checked. No runtime download.
- Dog bark, alarm/siren, rain, water faucet, knock and doorbell map to actual AudioSet IDs. Grouped events use max score; optional constituent-class filters allow per-class thresholds. Doorbell is a sound, not a door opening.
- Bounded PCM16 rolling window, /32768 normalization, configurable 250–2000 ms cadence (500 default), captured monotonic timestamps, one native inference consumer and one waiting window; overflow is counted. Model/session resource cleanup is serialized. Classification failures visibly degrade to RMS, never fabricated predictions.
- Pure Kotlin EMA/hysteresis/persistence/gap/cooldown detector with candidate cancellation, explicit start/end and stable IDs, continuous-episode duplicate suppression, monotonic elapsed time and wall-time anchoring. Versioned model/mapping/threshold semantics; no RMS noise gate.
- Schema-v2 typed rules, editing/toggling/deletion, explicit `legacy_repeat` migration, bounded local history and preserved v0.2 originals. Typed episode metadata includes raw peak/mean, class/device/model/version, provenance and notification status.
- Compose dashboard, live scores/state/persistence, typed rule builder, event history and diagnostics. Fixed navigation, named switch semantics and small-screen scroll layouts. Metadata-only manual HTTPS sync preserves provenance/timestamps with device/client-ID idempotency.
- Additive transactional SQLite migration; strict authenticated `/api/v2` rules/events plus legacy routes. Client-supplied model evidence is explicitly unverified. Web typed-rule creation/editing and distinct RMS/model/simulated timelines.
- Evaluation CLI consumes labeled saved scores or runs the pinned model on PCM16 WAV manifests, checks source partition leakage and produces per-class frame/temporal precision/recall/F1, false positives, confusion matrices and threshold sweeps. Synthetic fixtures are explicitly not accuracy evidence.

### Executed validation

- Python: **35 tests passed**, including all original v0.2 tests, migrations, auth/ownership, typed validation, provenance/idempotency and evaluation CLI.
- Web: **7 unit tests + 2 real-API Playwright flows passed**, strict TypeScript and production build passed, 390px viewport and untrusted labels covered.
- Android: **13 JVM tests**, debug/test-APK compilation and lint passed locally. **5 emulator integration tests passed** on verified snapshot `e9f6929`, including actual native TFLite inference → temporal rule → typed local store, all curated label mappings, migration, permission denial, Start/Stop notification behavior and rule/history UI. Latest PR checks rerun final source refinements.
- Actual host LiteRT smoke verifies finite `[1,521]` output and embedded-label alignment. The user APK was inspected for the pinned model SHA and absence of test audio.
- Backend and web dependency audits: no known vulnerabilities. No failing check is suppressed.

### Measured performance and artifact

- Model asset: **4,126,810 bytes**. Final local debug APK: **28,609,675 bytes**, versus v0.2 **9,158,756 bytes** (**+19,450,919**); native TFLite runtimes/model account for much of the increase. Signing/toolchains can slightly change APK bytes.
- Development Linux x86_64 host, 12 repeated fixture windows: initialization **34.58 ms**, mean **1.37 ms**, p95 **2.21 ms**.
- Android API 30 x86_64 **CI emulator**, 12 repeated fixture windows: initialization **78 ms**, mean **3.25 ms**, p95 **4 ms**, process PSS **99,870 KiB**. These are not physical-phone, battery or accuracy measurements.
- [Verified Android CI and APK artifact](https://github.com/BSFEAL/aura/actions/runs/40425627569/artifacts/12698479445). The final PR artifact link is added after the latest checks complete.

### Provenance, calibration and limitations

See `docs/MODEL_CARD.md`, `models/manifest.json`, `models/LICENSE-YAMNET.txt`, `evaluation/fixtures/ATTRIBUTION.md`, `docs/AUDIO_EVALUATION.md`, `VALIDATION.md` and `docs/ANDROID_V03_VALIDATION.md`.

Scores are uncalibrated; thresholds are provisional. The attributed ESC-10 bark crop validates integration only. No held-out real-world accuracy/calibration study or natural-event detection performance is claimed. Full ESC-50 has noncommercial restrictions; no full dataset is downloaded or bundled.

**All physical-phone scenarios remain pending**, including acoustic playback/natural backgrounds, compatible v0.2 package upgrade, screen lock/endurance, permission/privacy/OEM behavior, airplane mode, thermal/battery observations, raw-audio/traffic audit and private HTTPS synchronization. Debug APK upgrades require the original signing certificate; ephemeral CI debug keys may differ. No security/fire/medical/emergency guarantees, production identity/TLS deployment, sensor fusion or exactly-once unlimited delivery queue are implemented.
