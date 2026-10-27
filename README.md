# AURA v0.4.0 — Natural-language rule previews

**AURA by kaula.systems** is a local-first ambient intelligence platform. This experimental release adds a real, bundled **on-device YAMNet TFLite classifier**, typed rules, temporal sound episodes and Android diagnostics to the verified v0.2 RMS/Compose/FastAPI/SQLite/TypeScript foundation.

Supported curated model classes: **dog barking, alarm or siren, rain, running water/faucet, knocking and doorbell sound**. Class mappings and provenance are pinned in [MODEL_CARD.md](docs/MODEL_CARD.md). A doorbell sound is not evidence that a door opened. Model scores are **uncalibrated**, thresholds are **provisional**, and rule-confirmed episodes are **not independently verified real-world events**. AURA is not a security, fire, medical or emergency detector. It never places emergency calls.

## Phone-only setup

1. On your phone, open [Android Actions runs](https://github.com/BSFEAL/aura/actions/workflows/android.yml). Choose a successful v0.3 run, sign into GitHub and download `aura-v0.3-debug-apk`. Unzip with Android Files and open `app-debug.apk`. Android 8+ is required. Allow installation from that file manager only if you trust this build. Artifacts expire after 14 days; rerun the workflow if necessary.
2. For an **upgrade**, keep the same application ID and signing certificate. PR and feature-branch debug keys from different CI runs may differ. Main builds support a retained key with a pinned certificate; see [Android signing setup](docs/ANDROID_SIGNING.md). Do not uninstall to test data preservation. Build both versions locally with the same retained key if the original installed APK's key is unavailable. Existing preference/database migration is tested; cross-key Android upgrade is not possible.
3. Open **Rules**. Keep/add an RMS rule (e.g. threshold `0.15`), or select a curated sound type. Set activation/release scores, persistence, gap, EMA and cooldown. Defaults for sound rules: `0.70 / 0.50`, `3000 ms`, `1000 ms`, `0.5`, `30000 ms`. They are starting points, not calibrated accuracy claims. Short barks/knocks/doorbells may need shorter persistence; changing it changes false-positive behavior.
4. **Dashboard → Start monitoring** requests microphone and notification permission. Granting permission alone does not activate capture: tap **Start monitoring again**. Model loading occurs once per explicit session on a worker thread. Status moves through Starting → Active, or visibly Degraded (RMS only) if the model fails.
5. **Live** displays top raw model outputs and per-rule smoothed scores, persistence and state. **Events** records typed episodes, class IDs, timestamps/duration, peak/mean scores, model/threshold version, device, provenance and notification status. Unknown/unconfirmed is expected when evidence does not meet an enabled rule. A per-class ID filter can apply a distinct threshold to a constituent AudioSet class.
6. **Stop monitoring** in the app or ongoing microphone notification ends capture. Removing the task also stops the service. There is no boot receiver, sticky restart or hidden activation. Permission loss, capture errors, Android silencing or disabling the active notification stops capture. Alert-only channel settings can suppress alerts while preserving history.
7. **Diagnostics** shows model status, 16 kHz sample rate, measured initialization/inference timings, processed/dropped windows and approximate process memory. Change inference cadence (250–2000 ms; default 500 ms) only between sessions. Faster inference costs more processing; slower inference can miss brief events. No physical-device battery measurements are claimed.

The sound-classification model is already in the APK; airplane-mode monitoring needs no account/server/download. Optional OpenAI rule previews require your configured backend and an explicit text-sharing action. Capture uses 16 kHz mono PCM16, normalized /32768, a bounded 15,600-sample rolling window, and one inference consumer with at most one queued window. No raw microphone audio is saved, logged or uploaded. No RMS noise gate discards quiet inputs. Inference exceptions clear sound evidence and preserve explicit RMS-only monitoring.

## OpenAI rule builder (v0.4.0)

Describe a rule, explicitly consent to sharing its **text**, and review an OpenAI-generated typed proposal before **Save disabled rule**. The OpenAI key stays on your local FastAPI machine in `OPENAI_API_KEY`; it is never entered in the APK or web dashboard. Set `AURA_LLM_ENABLED=true` to enable this optional endpoint. Raw microphone audio stays on-device. No rule, notification or microphone activates from preview/save; enable the new rule separately, then explicitly Start.

See [OPENAI_RULE_BUILDER.md](docs/OPENAI_RULE_BUILDER.md) for secure local-machine setup, a live smoke command and private HTTPS phone connectivity. Android requires trusted HTTPS; the computer's web dashboard works through its loopback development proxy. Do not expose the development API publicly. This is a real OpenAI Responses integration with independent schema validation, not an offline parser or fabricated fallback. CI uses clearly labeled provider fixtures; live-key/model/phone connectivity validation remains pending until you configure your machine. Existing manual builders work without OpenAI.

## Rule feedback and presets (v0.3.2)

**Live** now explains why a classification has not alerted: missing/disabled rule, unavailable profile, score below threshold, insufficient persistence, a tolerated gap or cooldown. Cards name exact classes and show notification readiness separately from the last notification request status. A high **Dog** score does not substitute for **Bark**. **Alerts → Android alert settings** opens Android's channel controls; `posted` is not proof of audible delivery.

**Rules** offers explicit, provisional short-sound (500 ms) and sustained-sound (3000 ms) presets. They only populate editor fields; review and Save are required. Existing saved rules, class mappings, pinned profiles, cooldown and notify settings are not changed automatically. These manual presets do not use an LLM or establish calibration. See [RULE_FEEDBACK.md](docs/RULE_FEEDBACK.md).

## Local metadata backup (v0.3.1)

Stop monitoring, open **Backup → Export metadata backup**, and choose a private file location. **Restore from backup** validates the file and asks for confirmation before merging rules/history. New rules are disabled and assigned to this phone; review and enable them yourself. Event device IDs, timestamps, scores and model versions remain historical metadata, visibly marked restored/unverified. No microphone activation, alert replay or automatic sync occurs.

Backups contain no audio, credentials, backend URL or sync bookkeeping. Files are unencrypted metadata; a selected cloud file provider may upload them. Invalid, conflicting or oversized restores are rejected without replacing existing rules/history. Existing IDs are retained; limits are 128 rules, 200 events and 1 MiB. See [LOCAL_BACKUP.md](docs/LOCAL_BACKUP.md) for exact semantics and phone tests. This feature cannot recover an earlier uninstall or bypass APK signing requirements. Keep your current working APK unless a compatible update is available.

## Rules, episodes and storage

Schema v2 supports `rms_above` and `audio_event` only, with an audio/device source and explicit notification/cooldown policy. Unsupported versions are shown as unavailable, not reinterpreted. Rule creation/download never activates a microphone. Rules can be edited, toggled and deleted; editing/creating requires capture stopped. Downloaded-rule edits are local until the next explicit sync; use the web dashboard to edit the server copy.

Sound rules pin model, mapping and threshold versions. Event mappings use max score across documented classes or a selected constituent class. The deterministic EMA/hysteresis/persistence/gap/cooldown state machine suppresses duplicate notifications for continuous episodes. [AUDIO_EVALUATION.md](docs/AUDIO_EVALUATION.md) defines exact transitions and timestamp semantics. A new sound rule does not identify a physical emergency.

v0.2 RMS rules migrate without losing IDs, enabled flags or text, with explicit `legacy_repeat=true` to preserve immediate strict-amplitude and repeating-cooldown semantics. Old event history is retained as `device_reported`, with unknown legacy notification status. App-private originals are backed up during migration. Up to 128 rules and the latest 200 events are evaluated/stored. Corrupt/unsupported data is retained in quarantine with a visible warning; a legacy installation above 128 rules retains originals while evaluating only the first 128. Interrupted active episodes on relaunch use the last persisted evidence duration, with `process_terminated` status; the true process-kill end is unknown.

Stored metadata: rule configuration/device IDs, event label/class ID, start/end/duration, raw peak/mean scores, RMS, model/mapping/threshold versions, notification status, provenance, bounded history and explicit-sync bookkeeping. Model outputs are metadata, not proof. Android backups/device transfer are disabled by app configuration.

## Local API and web setup

Prerequisites: Python 3.12+, Node 22+, npm. From the repository root:

```bash
python -m venv .venv
. .venv/bin/activate
pip install -r backend/requirements.txt
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

Open `http://127.0.0.1:5173` and enter the token. It stays in memory; refresh locks the dashboard. Register a device, create legacy RMS or typed sound rules, edit/toggle/delete rules, and view labeled simulations or device/model reports. Click Refresh to fetch new data. Registration does not prove online status and never starts a sensor.

All `/api/*` routes require a bearer token of at least 24 characters. SQLite defaults to `backend/aura.db`; override `AURA_DB` if needed. v0.3 performs an additive, transactional migration (spec/metadata columns and a device/client-event idempotency index); it does not replace the database or delete v0.2 rows. Back up the database with the API stopped before upgrades. API/client contracts reject unknown fields, unsupported class IDs/versions, nonfinite/out-of-range scores, incompatible class mappings and inconsistent/unzoned timestamps. Shared-token authentication is for one trusted installation; client confidence is always marked `evidence_verified=false`.

**Keep development servers on loopback; do not expose them publicly.** For optional Android sync, use a private network and trusted HTTPS reverse proxy in front of the authenticated loopback API. Android rejects cleartext HTTP, redirects and invalid certificates. Production identity/TLS provisioning, remote activation and cloud inference are outside this release.

## Optional explicit sync

Stop capture and wait for cleanup. In Android **Devices**, supply the trusted HTTPS backend URL, token and dashboard device ID, or leave ID blank to register. Tap **Sync rules & events**. Tokens are not saved. No network activity occurs in monitoring.

Sync downloads schema-v2 rules and uploads **completed/interrupted event metadata only**, preserving original timestamps, score/model/threshold versions and provenance. Historical uploads omit API `rule_id` so a deleted/disabled remote rule cannot invalidate phone history; the original rule association remains locally. The server deduplicates `device_id + client_event_id` and rejects conflicting retries. Existing local sync bookkeeping is retained. Events previously uploaded by v0.2 remain legacy RMS records; v0.3 does not retroactively claim classifications for them.

An interrupted upload can be retried explicitly. A 200-event rolling history is not an unlimited delivery queue. API and Android stores are separate; deleting an API device cascades its server rules/events, not phone-local history. Local rules are not automatically uploaded.

## API contracts

OpenAPI schemas/UI: `http://127.0.0.1:8000/openapi.json` and `/docs` (data routes require auth). v0.2 `/api/devices`, `/api/rules`, `/api/events`, `/api/simulate`, `/api/status` continue to work. Legacy clients only receive legacy rules and RMS event representations, never reinterpret sound rules as RMS.

| v2 endpoint | Contract |
| --- | --- |
| `GET/POST /api/v2/rules` | Typed source/condition/policy; strict schema_version=2 |
| `POST /api/v2/rules/validate` | Validate full typed input without activation |
| `PUT/PATCH/DELETE /api/v2/rules/{id}` | Edit, enable/disable, delete; device ownership remains fixed |
| `GET /api/v2/events?limit=100` | Typed and migrated legacy history, newest ingestion first; limit 1–500 |
| `POST /api/v2/events` | Device/client ID, event type/class, provenance, start/end/duration and optional model metadata |
| `POST /api/v2/simulate` | Same strict input, forced simulated provenance |

For sound events, model/mapping/threshold version and finite `mean_score <= peak_score` are required. `class_id` must belong to `event_type`. Timestamps require timezones; end-start must agree with duration. API checks are structural, not independent verification of the sound. Simulations remain visibly simulated. v0.2 natural-language RMS input is retained as a constrained grammar, not a language-model interpretation service.

## Checks and artifacts

```bash
cd backend && ../.venv/bin/python -m pytest -q
cd ../web && npm ci && npm test && npm run build
npx playwright install chromium && npm run test:e2e
cd ../android && ./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug
# With an Android emulator already running:
./gradlew connectedDebugAndroidTest
cd ..
.venv/bin/python tools/verify_model.py
.venv/bin/pip install -r tools/requirements-model.txt
.venv/bin/python tools/model_smoke.py --report /tmp/model-smoke.json
.venv/bin/python tools/evaluate_audio.py --scores evaluation/fixtures/synthetic_scores.json --report /tmp/evaluation.json
```

Android: JDK 17, Gradle 8.9 (checksummed wrapper), AGP 8.7.3, Kotlin 2.0.21, SDK 35, TFLite 2.17.0. Open `android/` in Android Studio or use the wrapper. Debug APK: `android/app/build/outputs/apk/debug/app-debug.apk`. Host model verification pins LiteRT/NumPy/SciPy separately. Model assets and all direct build dependencies are version-pinned; CI runner images/transitive dependencies are not a bit-for-bit reproducible toolchain. Experimental debug APK distribution is not production signing.

Existing Python/web/Android workflows remain, with model integrity/real-inference smoke and emulator integration added. CI publishes `aura-v0.3-debug-apk`, web bundle, model smoke metrics, lint/JVM reports and emulator reports. Failures propagate. No real microphone hardware is required for emulator inference.

See [VALIDATION.md](VALIDATION.md) for executed results/measurements, [MODEL_CARD.md](docs/MODEL_CARD.md) for provenance, [AUDIO_EVALUATION.md](docs/AUDIO_EVALUATION.md) for calibration tooling, and [ANDROID_V03_VALIDATION.md](docs/ANDROID_V03_VALIDATION.md) for the physical-device matrix. **Initial user-reported phone checks are recorded separately; detailed device/measurement records and remaining scenarios are still pending.**

### Anthropic alternative (v0.4.1)

The natural-language rule preview now offers OpenAI or Anthropic, with provider-specific consent and server-only keys. See [Anthropic setup and safeguards](docs/ANTHROPIC_RULE_BUILDER.md). No automatic provider fallback; saved rules remain local and disabled until explicitly enabled.
