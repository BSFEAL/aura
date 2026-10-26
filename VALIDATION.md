# AURA v0.2 validation

Executed in the managed workspace on 2026-10-26, after pulling `origin/main` into `feat/aura-v0.2`.

| Check | Result |
| --- | --- |
| Backend `python -m pytest -q` | 8 passed |
| Web `npm test` | 3 passed |
| Web `npm run build` | TypeScript strict check and Vite production build passed |
| Web `npm run test:e2e` | 1 passed against the real FastAPI/SQLite backend using Chromium |
| Android Gradle 8.9 `testDebugUnitTest assembleDebug lintDebug --no-daemon` | BUILD SUCCESSFUL; 3 JVM tests passed; APK generated; no lint errors |
| `npm audit` | 0 known vulnerabilities |
| `pip-audit -r backend/requirements.txt` | No known vulnerabilities found |
| `git diff --check` | Passed |

Browser integration covers token connection, device registration with markup characters safely rendered, rule creation/toggling, labeled simulation, mobile navigation at 390px, no horizontal overflow, dashboard locking and no browser page errors. Screenshots were captured in `/tmp` during the test.

Android compiled with the installed JDK 21 targeting Java/Kotlin 17, SDK platform 35, and Gradle 8.9. CI uses JDK 17. The debug APK is `android/app/build/outputs/apk/debug/app-debug.apk` (approximately 8.8 MiB). Gradle wrapper 8.9 is included with the official distribution SHA-256.

Nonblocking warnings: Starlette TestClient emits an AnyIO alias deprecation warning in the local environment. Android lint reports available newer AndroidX versions and primitive Compose-state boxing suggestions. Local Android analytics initialization reports an unwritable default home directory; build and lint complete successfully.

## Manual verification still required

No physical Android device is attached. Verify permission denial/grant, visible active notification and Stop action, microphone sampling, threshold alerts, task removal, notification-channel disable, microphone privacy controls and OEM battery behavior on a phone. Optional HTTPS sync needs a private trusted TLS endpoint and was not exercised on a device. JVM tests verify signal math, constrained grammar and cooldown, not Android hardware.

Only RMS amplitude monitoring is implemented. No sound classification, automatic remote activation, autonomous emergency calls, production identity/TLS deployment, physical sensor adapters or exactly-once event sync is claimed. See README for sync and retention limits.

GitHub publishing and CI execution depend on repository write access. Local build results do not imply remote Actions have run.
