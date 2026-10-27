# Rule feedback and explicit presets (Android v0.3.2)

Live classifications are model scores, not notification requests. AURA uses this pipeline:

1. Real YAMNet output → curated class IDs (or an explicit constituent class filter).
2. Enabled, supported typed rule → smoothed score → persistence/gap/hysteresis/cooldown detector.
3. New episode → event metadata → optional Android notification request.

The Live screen now explains each stage. No model mapping, saved threshold or detector transition is changed automatically. This is deterministic UI/decision-state feedback, not an LLM rule builder or new audio model. Scores remain uncalibrated and presets remain provisional. There are no new network requests, audio persistence or microphone activation paths.

## What Live shows

Top scores have coverage notes: no curated mapping, no matching rule, disabled matches, unavailable profiles or an enabled matching rule. **Dog** (`/m/0bt9lr`) is a broad AudioSet class; the **Dog barking** rule still uses only **Bark** (`/m/05tny_`). **Water tap, faucet** (`/m/02jz0l`) needs a Running water rule. A high unmapped score is not silently reinterpreted. Constituent class filters are respected.

Every saved rule has a card even when disabled or unavailable. It names actual model labels and IDs, shows raw and smoothed scores when evaluated, and explains:

- Disabled / stopped / starting / waiting for a first evaluation / unsupported or unavailable classifier.
- Smoothed score below activation (displayed values are rounded).
- Candidate elapsed time versus minimum persistence, with a bounded progress bar.
- A score dip inside tolerated gap, distinguishing candidate recovery from an already-active episode.
- Active continuous episode with duplicate suppression (legacy RMS repeat policy is explicitly distinguished).
- Remaining cooldown at the last processed evaluation. This is a snapshot from the detector's monotonic clock, not a wall-clock timer or promise of immediate detection after expiry.

Snapshot fields for cooldown, gap tolerance and exact RMS are bounded scalar diagnostics. They are not stored in API contracts and do not drive a second evaluator. Existing deterministic transitions, activation/deactivation thresholds, legacy repeat behavior and event timestamps remain unchanged. Clock/capture resets clear diagnostic progress with detector state.

## Detection versus notification delivery

Cards separately show the next-episode notification setting: disabled rule, master alerts off, rule notify off, Android/channel blocked, or request enabled. The most recent event's historical notification status is shown independently. Enabling alerts during a continuous active episode does not replay its original notification.

`posted` means the notification request was submitted to Android. It does **not** prove a banner was displayed, a sound played or the user saw it. Volume, channel importance, Do Not Disturb and OEM policy remain Android controls. **Alerts → Android alert settings** opens the experimental sound-event channel (or app settings before channel creation); availability is refreshed when returning to the app. It never enables settings automatically or generates a test/emergency alert.

## Optional form presets

Stop monitoring, select a sound type in Rules, choose a preset, review the fields and Save. Presets do not save, enable, activate capture or notify automatically. They change only activation/release, persistence, max gap and EMA in the editor:

| Preset | Activation / release | Persistence | Max gap | EMA alpha |
| --- | --- | --- | --- | --- |
| Short sound · provisional | 0.70 / 0.50 | 500 ms | 1000 ms | 0.50 |
| Sustained sound · provisional | 0.70 / 0.50 | 3000 ms | 1000 ms | 0.50 |

Cooldown, notify, enabled state, source, class filter and pinned model/mapping/threshold versions are retained. Saving an edit now also retains its rule text/note. Shorter persistence may catch brief sounds but can increase false positives; these are usability starting points, not measured operating points. No accuracy, calibration, power or natural-event reliability improvement is claimed.

## Validation and remaining physical checks

Pure Kotlin tests reproduce a high Dog score without a Bark mapping, missing faucet rules, class filters, persistence progress, candidate/active gaps, cooldown return, clock reset, RMS precision/repeat behavior, notification policy distinctions and immutable pinned preset application. Existing detector tests still pass. Instrumentation checks mapped label names against the actual model and editor behavior/pin preservation; presentation-only synthetic fixtures exercise visible messages. Native interpreter tests remain separate and use the licensed bundled test fixture. Synthetic GUI values do not establish acoustic accuracy.

On an actual phone, verify missing-rule notes, per-rule raw/EMA values, candidate/gap/cooldown messages, both presets without implicit saving, unsupported profiles, disabled notifications, DND/silent mode and settings-return refresh. Those new physical checks remain PENDING. Existing user reports of bark recognition and working running-water alerts are informal observations, not calibration data. Signing setup remains deferred; an APK update still requires a compatible certificate.
