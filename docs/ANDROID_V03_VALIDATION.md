# AURA v0.3 physical-device validation

**Status: initial user-reported checks below; structured validation remains incomplete.** No physical phone is attached to this development environment. Emulator/inference tests do not establish microphone acoustics, background reliability, thermal or battery behavior.

Use an ordinary Android 8+ phone and a trusted debug APK artifact. Upgrades require the same application ID **and signing certificate**. The old GitHub debug signing key was ephemeral; an APK from a different Actions run may not update an installed v0.2 APK. Do not uninstall to “pass” an upgrade test, since uninstall removes data. For the migration test build both versions with the same retained local debug keystore, or obtain the original signing key. This distribution limitation is not a completed upgrade test.

## User-reported session — 2026-10-27

The user reports working local events/dog-bark detection. They subsequently answered “all good” to the requested checks for 10-minute screen-lock monitoring, notification Stop, five-minute quiet-room behavior and five Start/Stop cycles. They also answered “all good” after the requested 30-minute monitoring session. These are informal user-reported passes, not measurements independently observed by CI or this environment.

Phone model, Android version, APK hash, exact rule parameters, acoustic conditions, battery change, temperature, processed/dropped-window counts and failures were not supplied. Do not infer zero failures, zero battery drain or calibrated detection accuracy. Full quiet/speech/noise, 20-cycle stress and controlled playback protocols remain pending. The user also reports having uninstalled the previous version before installing; a data-preserving upgrade was not established.

| Check | User report | Remaining evidence |
| --- | --- | --- |
| Bark events | Working | Model/device/parameters and controlled playback record |
| Screen locked 10 min | “all good” | Device/OEM settings, window counts |
| Notification Stop | “all good” | Device/Android and recorded indicator/service observations |
| Quiet room 5 min | “all good” | Event/candidate counts; separate speech and steady-noise tests |
| Five Start/Stop cycles | “all good” | Full 20-cycle/relaunch/process-termination protocol |
| 30-minute session | “all good” | Actual battery/thermal/diagnostics readings |

Additional user feedback: running-water recognition initially appeared in Live without alerts; the user subsequently reported that alerts worked after matching-rule guidance. Bark recognition versus alerting was also discussed. These reports do not establish calibrated thresholds, natural-event accuracy or performance measurements. New v0.3.2 feedback/preset/Android-settings checks are listed in `docs/RULE_FEEDBACK.md` and remain pending physical validation.

## Run record (complete for each phone/session)

- Tester / date / APK SHA-256 / Git commit:
- Device model / chipset / Android/API version / OEM power settings:
- APK signing certificate / installation path / starting app version:
- Sample rate / inference cadence / model/mapping/threshold versions:
- Rule parameters (activation, release, EMA, persistence, gap, cooldown, class filter):
- Background noise (descriptive or independently calibrated instrument) / room / placement:
- Separate speaker / source license / playback volume setting / distance and orientation:
- Starting/ending battery / charging / ambient temperature / screen state:
- Diagnostics start/end (init, average/p95 inference ms, windows/s, processed/dropped windows, failures, PSS):
- Metadata/traffic audit method / observations / issue links:

## Matrix

| # | Scenario and procedure | Result | Device / Android / observations |
| --- | --- | --- | --- |
| 1 | **Install / upgrade** — Build v0.2 and v0.3 with the same signing key; seed RMS rules and history; install v0.3 over v0.2 without clearing data. Verify IDs/history, enabled flags and legacy_repeat are retained. | PENDING | Not performed |
| 2 | **Permissions / activation** — Start from Stopped. Tap Start, grant permissions, verify microphone remains off until a second Start. Check Starting → Active and the ongoing notification. | PENDING | Not performed |
| 3 | **Model / live scores** — Confirm Diagnostics shows pinned model loaded, 16 kHz, increasing windows and finite raw/EMA scores. Verify no model network download. | PENDING | Not performed |
| 4 | **Controlled bark playback** — Use the attributed fixture or separately licensed bark recording on a separate speaker. Record volume, distance, orientation, room noise and rule settings. Verify a possible-dog episode, class ID, provenance and metadata; do not equate playback with natural events. | PENDING | Not performed |
| 5 | **Silence / noise** — Observe quiet room, ordinary speech and steady background noise for at least 5 min each. Record all false candidates/episodes; ensure no continuous repeat notifications for one episode. | PENDING | Not performed |
| 6 | **Persistence / cooldown** — Use repeatable playback above/below configured thresholds. Test a short sound below persistence, tolerated gaps, long gaps, continuous playback and return before/after cooldown. | PENDING | Not performed |
| 7 | **Screen locked 10 min** — Start explicitly, lock for 10 min. Record windows, lifecycle, notifications and any OEM battery restrictions. | PENDING | Not performed |
| 8 | **Notification Stop** — Tap Stop on ongoing notification. Verify Android mic indicator clears, service notification disappears, windows stop and native resources are released. | PENDING | Not performed |
| 9 | **Deny / revoke microphone** — Deny initial request and verify no microphone. Grant/start then revoke via Android settings/privacy control; verify safe shutdown/error with no hidden restart. | PENDING | Not performed |
| 10 | **Airplane mode** — Install model-containing APK, enable airplane mode, start locally, verify classification/events without any network. | PENDING | Not performed |
| 11 | **Notifications disabled** — Test app-wide notification denial and active-monitoring channel disabled before/during monitoring. Monitoring must refuse or stop. Disable only alert channel: events remain recorded with permission_denied status. | PENDING | Not performed |
| 12 | **Repeated lifecycle** — Perform 20 Start/Stop cycles, task removal, rotation, relaunch and process termination. Check no concurrent inference/recorders, stale active indicator or unbounded memory. Interrupted episodes must not appear as confirmed new events. | PENDING | Not performed |
| 13 | **30-minute session** — Record init time, avg/p95 latency, windows/s, drops, inference/foreground errors, process PSS and event counts before/after. | PENDING | Not performed |
| 14 | **Long battery / thermal** — Observe at least 2 h with screen state, starting/ending battery %, charging state, ambient temperature, device temperature, cadence and OEM policy recorded. Compare the same phone/configuration to RMS-only v0.2. CI is not a battery test. | PENDING | Not performed |
| 15 | **Privacy audit** — Inspect app-private files and traffic with an authorized test setup. Only metadata/preferences/model assets may persist; verify no microphone PCM/WAV files, audio uploads, cloud inference, reboot receiver or emergency calls. | PENDING | Not performed |
| 16 | **Optional HTTPS sync** — Use authenticated private HTTPS; sync after stopping. Compare class IDs, start/end, duration, score/model/threshold versions, provenance and notification status. Retry the same episode and verify idempotency. Legacy RMS uploads must remain device_reported. | PENDING | Not performed |

For each row record PASS/FAIL/BLOCKED, actual measurements, settings and reproducible steps. Attach metadata/log excerpts without raw microphone audio. If playback does not reach threshold, record failure/unknown and the scores; do not tune on a test clip and then report that clip as independent accuracy evidence. Real naturally occurring events require a separate, properly annotated study.

## Automated coverage (not physical validation)

- Host LiteRT: real bundled model, finite `[1,521]` output and embedded-label alignment on the attributed bark crop.
- Android instrumentation: native interpreter → curated class → temporal rule → typed local persistence, v0.2 preference migration, permission denial, explicit Start/Stop and foreground notification, rule editing/history UI.
- Pure Kotlin: PCM scaling/window overlap, tensor validation, class filtering, EMA, persistence/cancellation, hysteresis/gaps, cooldown/duplicate suppression, clock reset, legacy RMS, serialization and fake-classifier failures.

See VALIDATION.md for the actual executed checks and CI links. These tests require no physical microphone hardware. Never mark the above matrix passed based on CI.
