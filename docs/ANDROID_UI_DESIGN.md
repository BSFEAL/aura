# AURA Android interface — v0.4.2

Implements the supplied visual reference using Jetpack Compose: near-black navy backgrounds, cyan/blue accents, restrained indigo, bordered rounded panels, a circular microphone hero and compact event/rule cards. The interface retains AURA by kaula.systems branding and uses vector marks drawn in code without a new icon dependency.

## Navigation and screens

Phone: Dashboard, Live, Rules, Events and Settings bottom navigation. At 600 dp available width, navigation switches to a rail; the dashboard places monitoring and measurement panels in two columns. Content is centered with a maximum width on large windows. Routes reset their scroll position; keyboard insets and system bars are respected. Settings exposes Alerts, Diagnostics, Devices, Backup and About. Android Back returns event details to history and settings subpages to Settings.

Dashboard shows actual monitoring state, visible microphone status, explicit Start/Stop, uncalibrated RMS, raw/smoothed current rule evidence and last local episode. The circular microphone graphic is a control-area illustration, not a sound measurement or waveform.

Live shows actual top classifier classes, scores, mappings, coverage and per-rule temporal explanations. A bounded session-only plot holds at most 60 observed top-score updates over up to 30 seconds. It is not raw audio, a fixed-class probability chart or an accuracy measurement; the leading class may change. Empty/stopped data clears the trace. No fixture scores are used in the installed app.

Rules presents management cards first, with enabled state, summary, capability warnings, notification explanation, edit and delete. Create opens the existing typed form with exact numeric inputs plus threshold/duration/gap/cooldown sliders. The natural-language builder remains separately accessible, with OpenAI/Anthropic selection, explicit provider consent and a reviewed disabled save. The redesign does not change manual rule semantics, pinned profiles, defaults or detector behavior.

Events provides metadata cards and a tappable details view: class, provenance, times/duration, raw score aggregates, RMS, device/model/threshold versions, notification status and restored warnings. A posted notification is not proof of an audible alert. Unknown/unconfirmed and simulated/restored states remain explicit.

Diagnostics groups model, actual session statistics, capture configuration and cadence settings. About provides model/privacy information and an explicit documentation link. Settings has no nonfunctional noise-gating/cloud-sync switches.

## Deliberate differences from the image

The reference's playback controls, stored waveform and spectrogram are omitted: AURA does not persist microphone audio, so there is no recording to replay or visualize later. No fake chart, audio clip, confidence probability, performance number or detection guarantee is added. Scores are labeled uncalibrated. No model/class mappings are copied from the picture: the pinned actual Bark ID and all existing mappings are preserved.

## Validation

Existing unit and integration checks remain required. UI tests exercise opt-in monitoring/service notification, rule editing/pinned settings, provider consent and disabled save, metadata event details, backup access through Settings, and phone/tablet navigation. `AdaptiveDesignTest` captures real emulator renders, including a 1600×1000 / 160 dpi tablet-sized window; it restores display settings in `finally`. Only screenshots explicitly labeled synthetic contain test event metadata. No physical-device or natural-event accuracy claim follows from these renders.

The Android CI integration report artifact includes `ui-screenshots`. Instrumentation copies each PNG to a test-only Download directory before Gradle cleanup; the CI job exports it even when a test fails. Both test and export failures propagate. This uses the instrumentation shell identity only, adds no app storage permission, and is not part of normal application behavior. Physical phone/tablet layout, large text, keyboard, TalkBack, private HTTPS and upgrade testing remain pending until performed on hardware. Debug signing continuity still requires the same retained certificate; do not uninstall a working app to validate migration.
