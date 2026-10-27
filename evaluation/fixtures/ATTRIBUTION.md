# Test-only bark fixture

`android/app/src/androidTest/assets/dog_bark_16k.pcm` is a 0.975 s crop from ESC-10 file `1-100032-A-0.wav`, fold 1, dog. ESC repository commit `33c8ce9eb2cf0b1c2f8bcf322eb349b6be34dbb6`.

Source: https://github.com/karoldvl/ESC-50/blob/33c8ce9eb2cf0b1c2f8bcf322eb349b6be34dbb6/audio/1-100032-A-0.wav
Original `rose_bark.wav` by **nfrae**, Freesound 100032, CC0: https://freesound.org/people/nfrae/sounds/100032/ . ESC-10 compilation: Karol J. Piczak, Creative Commons Attribution 3.0 Unported: https://creativecommons.org/licenses/by/3.0/ . This fixture follows the attribution terms. Full ESC-50 is CC BY-NC 3.0; do not treat the full dataset as commercially unrestricted.

Derivation: signed mono PCM16 at 44.1 kHz → float / 32768 → scipy.signal.resample_poly(up=160, down=441) → samples [28000:43600] → clipped/rounded little-endian PCM16. No gain normalization. The fixture is in the instrumentation test APK only, not the user APK. It checks inference plumbing, not accuracy or calibration. No tuning/evaluation split is claimed from one clip.
