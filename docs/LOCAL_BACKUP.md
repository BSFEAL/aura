# Local metadata backup and restore

Android v0.3.1 (version code 4) adds a **Backup** tab. It uses Android's Storage Access Framework; no broad storage permission is requested. Export and restore require monitoring to be stopped, including service cleanup. They perform file IO off the UI thread and never activate the microphone or post old event notifications.

## Phone workflow

1. Keep the currently working app installed. An update still needs a matching signing certificate. This feature helps only after a compatible installation containing it; it cannot recover data from an uninstall already performed. Retained CI signing setup is a separate, deferred task.
2. Stop monitoring. Open **Backup → Export metadata backup** and choose a private JSON file location. Wait for the success message. If export fails, remove the incomplete destination file.
3. Copy the completed backup to a safe location before changing installations. It is not encrypted. File providers such as Drive can upload metadata when explicitly selected; choose local storage to keep the file local.
4. On the destination installation, stop monitoring and choose **Restore from backup**. Select the file, read the counts and warning, then confirm **Restore disabled rules & history**. Canceling changes no rules/history.
5. Inspect Rules and Events. Newly added rules are disabled and local to the destination phone; their model, mapping and threshold versions remain pinned. Unsupported pinned profiles stay unavailable. Enable desired rules yourself, then explicitly Start monitoring.

Files include rule settings/text/device IDs and the bounded event history: labels/class IDs, timestamps/durations, uncalibrated scores/RMS, provenance, model/threshold/mapping, and historical notification status. No raw audio, API token, server URL, registered API device identity, notification master preference, cadence, sync markers, or quarantined preference backups are included. Corrupt active collections fail export rather than silently omitting entries. Quarantined originals are not a portable repair format.

## Format and merge contract

- Envelope `format: aura-local-metadata`, `format_version: 1`, `created_at_ms`, `device_id`, `rules`, `events`. Nested records retain schema version 2; legacy v0.2 records in local preferences are migrated before export.
- Strict UTF-8/JSON decoding; unknown fields and unsupported envelope versions rejected. Maximum file size 1 MiB, JSON depth 32, 128 rules and 200 events. IDs, timestamps/durations, scores, event/class alignment and provenance are validated before any write.
- Restore keeps destination identity/settings and existing records. New rules are assigned the destination local phone ID, marked local (not downloaded), and disabled. Original IDs, conditions, legacy RMS repeat behavior and pinned profile versions are preserved.
- Existing rule IDs are skipped only when condition, policy and text match after source/enabled/remote normalization; existing enablement is retained. Conflicting semantics reject the entire restore. Repeated identical restores add nothing.
- Historical event device IDs and provenance are preserved, but new events are marked `restored_from_backup: true` and displayed **restored/unverified**. Imported `model_detected` means the file claims model provenance; AURA does not authenticate a backup or independently verify its scores. Never treat it as security evidence.
- Event-ID conflicts reject the restore. An unfinished event is closed at its last persisted duration with `restored_interrupted` state, not the current time. Historical `posted` means a past notification status; no alert is replayed.
- Capacity overflow rejects the entire restore rather than evicting current history. Deleted-rule event references are allowed so old history remains portable. Rules/events are saved together with one preferences commit; errors are surfaced. A storage-write failure requires checking local data/storage before retrying.
- Restored history does not automatically contact a server. Later explicit HTTPS sync can transmit its historical fields as unverified client-reported metadata; existing per-backend sync markers are not imported. API evidence remains unverified and client IDs remain subject to server idempotency/conflict checks.

## Automated validation

Pure Kotlin tests cover round trips, version pinning, disabling/rebinding, legacy RMS, idempotency, conflicts/capacity, interrupted episodes, strict UTF-8, depth/size bounds, unknown fields, duplicate IDs and invalid evidence. Android instrumentation checks persisted merge results, destination settings/identity preservation, no service activation or notification replay, conflict rejection without changing stored collections and corrupt-export rejection. Compose instrumentation checks the Backup controls are reachable on a small emulator screen. Existing native TFLite and lifecycle tests still run.

## Physical file-provider checklist — PENDING

On a real phone record model/Android/APK hash and test local Files export, provider cancellation, import confirmation/cancel, restore into seeded destination data, repeated identical restore, corrupt/oversized file, cloud-provider behavior, failed storage access, background/recreate while the picker is open, and refusal while monitoring. Verify rule disablement, historical labels, no new alerts/microphone use, and review-before-enable. Perform a same-certificate update without losing data separately; do not uninstall merely to pass that test. CI does not establish physical-provider compatibility.
