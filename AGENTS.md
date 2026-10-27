# AURA contributor instructions

Keep monitoring opt-in and visibly active. Never store or transmit raw microphone audio.
RMS is uncalibrated amplitude, not sound classification or physical decibels.
Never add autonomous emergency calls. Bind development servers to loopback and require API authentication.
Run backend pytest, web npm test/build and Android unit tests/debug compilation when tools are available.
Document limitations and keep API/client contracts aligned. Do not commit secrets, databases, or build outputs.

For sound intelligence, keep model assets and label maps pinned and integrity-checked. Run the real-interpreter smoke and Android inference integration tests; fake-only predictions are insufficient. Scores are uncalibrated model outputs, not independently verified events. Preserve typed-rule versions and legacy RMS semantics. Keep physical-device and calibration results pending until actually measured.
