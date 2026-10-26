# AURA v0.2

**AURA by kaula.systems** is a local-first ambient monitoring platform. This release delivers one real monitoring capability: explicitly activated Android microphone sampling and local, uncalibrated RMS amplitude threshold rules. The monorepo includes a native Kotlin / Jetpack Compose app, responsive TypeScript dashboard, and authenticated FastAPI / SQLite API.

RMS is normalized amplitude (0–1), **not calibrated decibels and not sound classification**. It does not detect rain, speech, alarms, or particular sounds. Multimodal classification and hardware integrations are future work. AURA never places autonomous emergency calls.

## Phone-only setup (no computer or backend required)

1. Open [Android build runs](https://github.com/BSFEAL/aura/actions/workflows/android.yml) in your phone's browser. Choose a successful run for the feature branch or merged main. Sign into GitHub to download its `aura-v0.2-debug-apk` artifact. Artifacts expire after 14 days; rerun the workflow if needed.
2. Unzip the artifact with Android Files, then open `app-debug.apk`. Allow “Install unknown apps” for that browser/file manager only if you trust this repository and build. Android 8.0+ is required. This is a development APK, not a signed production release.
3. Open AURA → **Rules**. Add `Notify me when sound level is above 0.15`. You can also use `Notify me when RMS above 0.15`. Valid thresholds are greater than 0 and at most 1.
4. Go to **Dashboard** → **Start monitoring**. Grant microphone and notification permissions. Then tap **Start monitoring again**; permission approval alone does not start recording. Android notifications must be enabled.
5. Speak or make a sound near the phone and observe the RMS indicator. Adjust the threshold to your environment. A threshold event appears in **Events**, with a local notification if alerts are enabled. There is a 30-second cooldown per rule. Quiet or muted microphones may never exceed your threshold.
6. Tap **Stop monitoring** in the app or persistent “AURA microphone active” notification. Removing the app task also stops monitoring. Monitoring never starts on boot and does not automatically restart after process termination. Android may stop it for battery or privacy reasons.

Rules and the most recent 200 events remain in app-private storage. No raw audio is stored or transmitted. **Alerts** controls threshold notifications; it does not remove the mandatory active-monitoring notification. Revoked permissions, microphone contention or recording errors stop monitoring and surface an error. Use Android settings to restore denied permissions.

## Run the API and dashboard locally

Prerequisites: Python 3.12+, Node 22+, npm. From repository root:

```bash
python -m venv .venv
. .venv/bin/activate
pip install -r backend/requirements.txt
# Generate a private token; copy it from your own terminal into the dashboard.
export AURA_API_TOKEN="$(python -c 'import secrets; print(secrets.token_urlsafe(32))')"
printf '%s\n' "$AURA_API_TOKEN"
cd backend
uvicorn app:app --host 127.0.0.1 --port 8000
```

In another terminal:

```bash
cd web
npm ci
npm run dev
```

Open `http://127.0.0.1:5173` and enter your token. Vite proxies `/api` to loopback port 8000. The dashboard holds the token in memory only. Refreshing locks it. Register a device, create a rule, and generate a labeled simulation or submit a device amplitude event. Click Refresh to load new events; there is no live polling. Registering or enabling a rule never remotely activates a microphone. Device registration is not proof that a device is online.

The backend refuses all data endpoints if `AURA_API_TOKEN` is missing or shorter than 24 characters. SQLite defaults to `backend/aura.db`; set `AURA_DB` to another writable path if needed. Restarting retains the data. Deleting a device cascades to its API rules/events, not phone-local history. Keep the database private and back it up while the API is stopped.

**Do not expose the development API or Vite server publicly.** For optional phone sync, use a private network with a trusted HTTPS reverse proxy, access controls and a valid certificate in front of the authenticated loopback API. Do not port-forward an unauthenticated service. Android rejects cleartext HTTP and does not bypass certificate checks. Production hosting, identity management, rate limiting and TLS provisioning are outside this development release.

## Optional Android → API → web flow

1. Set up private HTTPS access as above. Stop phone monitoring before syncing.
2. In Android **Devices**, enter the HTTPS backend URL, API token, and an existing dashboard device ID, or leave ID blank to register this phone. The token is not persisted.
3. Tap **Sync rules & events**. The phone uploads amplitude event history and downloads API rules for its device. It keeps local rules. API rule changes take effect only after another explicit sync. Toggling a downloaded rule locally lasts until the next sync.
4. Start monitoring on the phone. Stop it and tap Sync when you want to upload new event reports. Refresh the web dashboard to view them.

Local rules are created on-device; dashboard rules are downloaded. Sync uploads historical local events as device-level reports (without API rule association) so deleted or disabled remote rules do not invalidate phone history. API event timestamps currently represent ingestion time; original timestamps remain on the phone. Sync remembers uploaded local IDs per backend/device; a response lost after server commit can cause a duplicate on retry. It is not an exactly-once delivery queue. Switching API identity/URL creates a separate sync history. This is deliberately manual, not continuous cloud monitoring.

## API contract

OpenAPI: `http://127.0.0.1:8000/docs` or `/openapi.json` (schemas only). Every `/api/*` route requires `Authorization: Bearer <AURA_API_TOKEN>`. Pydantic models reject unknown fields and invalid ranges; errors use FastAPI's `detail` envelope.

| Endpoint | Behavior |
| --- | --- |
| `GET /api/status` | Counts and device-controlled monitoring description |
| `GET/POST /api/devices` | List/register `{name, kind: "phone" or "sensor"}` |
| `DELETE /api/devices/{id}` | Remove device and associated data |
| `POST /api/rules/validate` | Validate `{text}` against supported natural language grammar |
| `GET/POST /api/rules` | List/create `{device_id, text}` |
| `PATCH /api/rules/{id}` | Set `{enabled: true/false}` |
| `GET /api/events?limit=100` | Newest first; limit 1–500 |
| `POST /api/events` | Submit `{device_id, rms, rule_id?}`; optional rule must match device, be enabled, and exceed threshold |
| `POST /api/simulate` | Same payload, always stored with `simulated: true` |

A sensor integration can register `kind: "sensor"` and submit normalized amplitude events using these contracts. No physical sensor adapters or other modalities ship in v0.2. The simulated generator is an explicit one-shot API operation and dashboard control; it never pretends to be a real sensor.

## Development and checks

```bash
cd backend && ../.venv/bin/python -m pytest -q
cd ../web && npm ci && npm test && npm run build
npx playwright install chromium && npm run test:e2e
cd ../android && ./gradlew testDebugUnitTest assembleDebug lintDebug
```

Android builds require JDK 17, Android SDK platform 35 and Gradle 8.9 (wrapper included). Open `android/` in Android Studio, let it install the SDK and run the app on a physical phone. The debug APK is at `android/app/build/outputs/apk/debug/app-debug.apk`. CI runs Python tests, web unit tests/type checking/production build, and Android JVM tests/debug build/lint. APK, web bundle and Android reports are downloadable workflow artifacts.

Backend tests cover auth, persistence, rule validation, amplitude ranges, device ownership, enabled-rule checks and cascade deletion. Web unit tests cover safe rendering and simulation provenance. A Playwright integration test runs against the real API and covers registration, rule creation/toggling, simulated events, dashboard locking and mobile layout. Android JVM tests cover RMS, supported grammar and per-rule cooldown. Physical-device microphone behavior, OEM battery policy and notification permission flows require manual verification; JVM tests do not exercise Android hardware.

## Layout

- `android/` — native Compose UI, opt-in foreground service, local rules/events, manual authenticated sync
- `backend/` — typed FastAPI contracts, SQLite persistence, rule validator, simulation endpoint
- `web/` — responsive navy/cyan/indigo dashboard; no external runtime fonts or scripts
- `.github/workflows/` — independent Python, web and Android checks
- `AGENTS.md` — contributor guardrails

This is an amplitude-monitoring foundation for a multimodal platform, not a medical, security, or emergency system. Shared-token authentication is intended for a single trusted local installation; per-user authorization, remote microphone control, background sync, push notifications and production distribution are not implemented.
