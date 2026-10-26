## AURA v0.2

An empty repository now contains a working local-first vertical slice: Android microphone RMS → phone rule evaluation and local notifications/events → explicit authenticated API sync → web event timeline. Monitoring starts only after a visible user action and permission approval. RMS is uncalibrated amplitude; no sound-classification claims or emergency calls are implemented.

### Implementation

- Kotlin/Jetpack Compose Android dashboard, devices/manual HTTPS sync, constrained natural-language rules, bounded event history and notification preferences.
- Explicit microphone foreground service with ongoing Stop action, no audio persistence/uploads, per-rule cooldown, error cleanup and no sticky/reboot restart.
- Responsive navy/cyan/indigo TypeScript dashboard for private API access, devices, rule creation/toggling, event history and labeled simulations.
- FastAPI/Pydantic API with SQLite foreign keys, bearer authentication, strict contracts, rule validation, device event ingestion and one-shot simulation generator.
- Independent Python, web and Android CI; downloadable debug APK, web build and Android reports; phone-only installation instructions and contributor guidance.

### Validation

- Backend: 8 tests passed.
- Web: 3 unit tests, strict TypeScript check, production build and 1 real-API Playwright integration test passed (desktop and 390px viewport).
- Android: 3 JVM tests, debug APK compilation and lint passed with Gradle 8.9 / SDK 35.
- npm audit and Python requirements audit: no known vulnerabilities.
- Physical-device microphone, notifications and private HTTPS sync still require manual verification. See VALIDATION.md for commands and nonblocking warnings.

### Remaining limitations

Only normalized amplitude monitoring ships; multimodal classification and hardware adapters remain future work. Sync is explicit; remote rules require a download and local event uploads use ingestion timestamps without API rule associations. A lost server response can duplicate a retry. No physical Android device is attached for microphone/notification testing. Single trusted-installation token auth, debug-only distribution, and no production TLS/identity deployment.
