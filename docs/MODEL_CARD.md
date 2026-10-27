# YAMNet in AURA v0.3

## Provenance and redistribution

- Model: Google **YAMNet classification TFLite version 1**, Apache License 2.0.
- Official distribution: https://storage.googleapis.com/download.tensorflow.org/models/tflite/task_library/audio_classification/android/lite-model_yamnet_classification_tflite_1.tflite
- Model card/version: https://www.kaggle.com/models/google/yamnet/tfLite/classification-tflite/1 (formerly `google/lite-model/yamnet/classification/tflite/1` on TF Hub).
- Architecture/source: https://github.com/tensorflow/models/tree/39759bb62d056618eb4bda2a0e781900fe93a715/research/audioset/yamnet
- Authors/maintainers: Google, Manoj Plakal and Dan Ellis. See `models/LICENSE-YAMNET.txt` for Apache-2.0 terms.
- Bundled model: `android/app/src/main/assets/yamnet.tflite`, **4,126,810 bytes**.
- SHA-256: `10c95ea3eb9a7bb4cb8bddf6feb023250381008177ac162ce169694d05c317de`.
- AURA version: `yamnet-tflite-1-10c95ea3`. Mapping: `aura-audioset-v1`. Threshold profile: `provisional-v1`.

The official exported asset is committed, not downloaded at runtime. `models/manifest.json` pins asset, label-map and test-fixture checksums. `python tools/verify_model.py` verifies all bytes, checks the model's embedded label list against the external labels/AudioSet map and validates every curated ID/index. `.gitattributes` preserves upstream CSV bytes. The Android loader validates the model SHA and actual tensor contract; initialization/inference failure visibly degrades to RMS-only operation, never fabricated predictions. No cloud inference or microphone auto-start is implemented.

## Verified tensor semantics

Inspected with a real LiteRT interpreter, not inferred from the architecture name:

| Tensor | Name | Shape/type | Meaning |
| --- | --- | --- | --- |
| Input 0 | `waveform_binary` | `[15600]`, float32 | 0.975 s mono waveform at 16 kHz |
| Output 0 | `tower0/network/layer32/final_output` | `[1,521]`, float32 | Independent logistic class scores, AudioSet-derived label order |

Signed PCM16 samples are converted using `sample.toInt()/32768f`: −32768 → −1, +32767 → 0.9999695. No per-window gain normalization, clipping beyond legitimate PCM conversion, amplitude gate, softmax, or score-to-probability conversion is applied. The selected export contains waveform feature extraction; callers must **not** feed a mel spectrogram. The export includes quantized internals with float32 IO; scores can have discrete steps. Output scores are not guaranteed calibrated real-world probabilities.

The model computes 25 ms STFT windows at 10 ms hop and 64 log-mel bins, with 96 feature frames per classification patch. Its 521 labels match `yamnet_label_list.txt` embedded in the TFLite ZIP metadata. `yamnet_labels.txt` and `yamnet_class_map.csv` map each output to the exact human label and AudioSet machine ID. The original AudioSet taxonomy has additional classes; do not substitute a 527-class map.

## Curated event mapping (v1)

Default aggregation is **maximum**, never a sum or a claim of multiple independent confirmations. A rule may restrict to one of the listed class IDs and set its own activation/release thresholds, enabling per-class configuration without relabeling the model. Unknown classes and unsupported schema/model/mapping/threshold versions never silently acquire a new meaning.

| AURA event | Output indices | AudioSet IDs and labels |
| --- | --- | --- |
| `dog_bark` | 70 | `/m/05tny_` — Bark |
| `alarm_siren` | 382, 390 | `/m/07pp_mv` — Alarm; `/m/03kmc9` — Siren |
| `rain` | 283, 284, 285 | `/m/06mb1` — Rain; `/m/07r10fb` — Raindrop; `/t/dd00038` — Rain on surface |
| `running_water` | 364 | `/m/02jz0l` — Water tap, faucet |
| `knocking` | 353 | `/m/07r4wb8` — Knock |
| `doorbell` | 349 | `/m/03wwcy` — Doorbell |

`running_water` is deliberately scoped to **Water tap, faucet**, not generic Water. `doorbell` is a doorbell sound, not a physical door opening. Alarm/siren model scores do not make AURA a security, fire, medical or emergency detector.

## Decision and limitations

Defaults: activation ≥ 0.70, release ≥ 0.50, EMA α=0.5, persistence 3 s, tolerated low-evidence gap 1 s, cooldown 30 s. These are configurable **provisional settings**, not calibrated recommendations or accuracy claims. In particular, 3 s persistence may miss isolated barks, knocks or doorbells; choose persistence suited to your experiment. Lower thresholds/persistence can increase false positives. No RMS gate suppresses quiet events; quiet-event sensitivity still needs evaluation.

AUDIOSET training data and acoustics differ from users' microphones. Playback, background noise, room response, distance, compression, model bias and overlapping sounds can change scores. High score + persisted episode is a **rule-confirmed model episode**, not verified ground truth. No deployment precision/recall or battery claims are made.

## Reproduction and fixture

```bash
python -m pip install -r tools/requirements-model.txt
python tools/verify_model.py
python tools/model_smoke.py --report /tmp/model-smoke.json
```

The smoke test executes the actual interpreter, verifies 521 finite outputs, class alignment and a reproducible Bark response on the attributed test-only fixture. Android instrumentation executes the same asset through the native Android TFLite runtime, pure temporal detector and typed local store. Test fixture provenance/license and resampling/cropping recipe are in `evaluation/fixtures/ATTRIBUTION.md`. It is **not** stored microphone audio and is **not** included in the user APK.

Dependencies: Android `org.tensorflow:tensorflow-lite:2.17.0`, Kotlin 2.0.21, kotlinx serialization 1.7.3, Gradle 8.9, AGP 8.7.3, SDK 35; host verification uses LiteRT 2.3.0, NumPy 2.5.4 and SciPy 1.18.1. See Gradle/requirements files for remaining pinned dependencies. No runtime dependency on Google Play services.
