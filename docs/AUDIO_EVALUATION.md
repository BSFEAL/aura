# Offline audio evaluation

## Status: calibration incomplete

No representative, held-out deployment dataset has been evaluated. Default thresholds are provisional and configurable. Do not interpret synthetic test metrics or a bark fixture response as real-world detection accuracy. No measured natural-event precision/recall is claimed in this release.

## Saved-output CLI (standard-library only)

```bash
python tools/evaluate_audio.py \
  --scores evaluation/fixtures/synthetic_scores.json \
  --thresholds 0.3,0.5,0.7,0.9 --alpha 0.5 \
  --duration-ms 3000 --gap-ms 1000 --cooldown-ms 30000 \
  --report /tmp/aura-evaluation.json
```

Input is JSON with explicit `kind` (`synthetic` or `real`), `dataset`, labeled `frames` and annotated `truth_episodes`. Each frame has `clip_id`, strictly increasing monotonic `time_ms`, `scores` keyed by curated event ID and a possibly empty/multilabel `labels` list. Scores must be finite in [0,1]. Truth episodes have `clip_id,event_type,start_ms,end_ms`. Optional `recordings` metadata carries `source_id,split,fold`; source IDs shared across train/validation/test are rejected. The input fixture demonstrates the format.

For every threshold and event class the report contains precision, recall, F1, false-positive count and a one-versus-rest 2×2 confusion matrix (actual rows, predicted columns). This is multilabel evaluation; there is no forced single “winning” class. Threshold curves belong to the validation set; freeze a profile before using test data.

Temporal evaluation runs a reference of the documented EMA/hysteresis/persistence/gap/cooldown algorithm, separately from frame metrics. Predicted episodes are matched one-to-one to same-clip, same-class truth episodes with positive time overlap. The report includes matching semantics, false episode counts and predicted timestamps. This permissive overlap rule is not a benchmark claim; stricter IoU/onset tolerances should be chosen for a real study. Reported frame false positives and temporal false episodes are different measurements.

## WAV inference

Install pinned `tools/requirements-model.txt`, then provide a recording manifest:

```json
{
  "kind": "real",
  "dataset": "your-licensed-held-out-study",
  "recordings": [
    {"path":"audio/dog.wav","clip_id":"dog-1","labels":["dog_bark"],"source_id":"recording-1","split":"test","fold":5}
  ],
  "truth_episodes": [{"clip_id":"dog-1","event_type":"dog_bark","start_ms":1200,"end_ms":4200}]
}
```

```bash
python tools/evaluate_audio.py --audio-manifest study/manifest.json --split test --report /tmp/real-report.json
```

The WAV path selects `test` by default or explicitly `validation` with `--split`; it checks source leakage across the full recording manifest before inference. Keep annotations for the selected split in the report input. PCM16 WAVs are converted to float /32768, multichannel audio is explicitly averaged to mono and resampled with rational polyphase filtering to 16 kHz. The pinned model runs on 15,600 samples with an 8,000-sample (500 ms) hop. Frame timestamps are window **ends**; event onset precision cannot exceed window/cadence limitations. Clip-level labels apply to all windows in this simple WAV path; use independently annotated saved outputs for precise frame-level ground truth. Short clips with no complete window are rejected through the no-frame validator. Audio is read only from files explicitly selected for offline evaluation; the Android app never stores captured raw audio.

## Dataset and licensing plan

ESC-50 has predefined source-grouped folds: use folds 1–3 for development, 4 for threshold selection, 5 for final evaluation, keeping all derivatives of a source recording in one split. Document class imbalance, negative classes, playback conditions, device and microphone domain. Suggested mappings: ESC dog → `dog_bark`; rain → `rain`; water_drops/running-water-like labels require careful manual compatibility checks, not automatic equivalence; knocking → `knocking`; siren → `alarm_siren`. Do not invent a doorbell evaluation class from door-opening clips. Include other-class negatives and natural background recordings.

**Full ESC-50 is CC BY-NC 3.0.** ESC-10 subset compilation is CC BY 3.0; individual clip provenance can have additional attribution requirements. No full dataset is downloaded/bundled by CI. One ESC-10 bark crop, derived from nfrae's CC0 original, is included in the test APK only, with attribution. It verifies software integration and cannot support calibration. Obtain rights appropriate to your use before assembling real evaluation data.

## Temporal semantics

- `IDLE → CANDIDATE`: first smoothed score ≥ activation.
- Candidate evidence at/above activation refreshes the last-support time. Lower scores are tolerated for at most `max_gap_ms` since last support; a longer gap cancels the candidate. A late returning high score cannot bridge an already observed expired low gap.
- `CANDIDATE → ACTIVE`: a qualifying score arrives after elapsed candidate persistence and cooldown has expired. Persistence includes short tolerated gaps. The recorded start is the candidate's first window-end time, not a claim of true acoustic onset.
- Active evidence at/above the lower release threshold refreshes support. Longer low gaps end the episode at last support. A continuous active episode never creates a second notification.
- Cooldown starts at activation. After the episode ends, remaining cooldown ignores high evidence. After expiry a new candidate may start; it must meet persistence again. If the original episode is still active when cooldown expires, it remains one episode.
- Monotonic clock rollback or capture/evidence discontinuity >10 s resets state and ends active episodes as interrupted. Wall-clock start is captured once; end is start plus monotonic duration, so wall-clock changes do not create negative durations.
- Rule edit/disable/delete and classifier failure clean up detector state. RMS evaluation continues if the classifier fails. Migrated v0.2 RMS rules explicitly retain `legacy_repeat=true`, immediate strict amplitude comparisons and repeating cooldown semantics; new RMS rules suppress duplicates during continuous episodes.

Kotlin tests are authoritative for the on-device algorithm; Python tests independently verify evaluation mechanics. No noise gate, VAD or classifier-score fabrication is used. Temporal truth needs a separate annotation/evaluation protocol from frame labels.
