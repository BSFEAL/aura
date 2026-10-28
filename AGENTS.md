# AURA contributor instructions

Keep monitoring opt-in and visibly active. Never store or transmit raw microphone audio.
RMS is uncalibrated amplitude, not sound classification or physical decibels.
Never add autonomous emergency calls. Bind development servers to loopback and require API authentication.
Run backend pytest, web npm test/build and Android unit tests/debug compilation when tools are available.
Document limitations and keep API/client contracts aligned. Do not commit secrets, databases, or build outputs.

For sound intelligence, keep model assets and label maps pinned and integrity-checked. Run the real-interpreter smoke and Android inference integration tests; fake-only predictions are insufficient. Scores are uncalibrated model outputs, not independently verified events. Preserve typed-rule versions and legacy RMS semantics. Keep physical-device and calibration results pending until actually measured.

The optional OpenAI/Anthropic rule planner sends only explicit, consented rule text/catalog/schema through the authenticated backend. Keep provider keys server-side, fixed official HTTPS endpoints, redirects disabled, time/size/concurrency bounded, and previews read-only. Validate LLM output independently against the curated typed contracts; save only after review, disabled by default. Never add silent fabricated/offline LLM fallback or claim fixtures establish live provider quality. CI uses test-only provider transports; actual YAMNet inference must remain tested.

New rule creation starts with an empty user-authored chat, with no selected event, preset buttons or numeric templates. The LLM may propose provisional settings for explicit review, refinement and disabled Save; it must clarify ambiguous/unsupported intent. Keep conversation context bounded, text-only, consented and ephemeral. Existing saved rules and advanced editing remain compatible. Recording-based learning is future work; never imply a recording or a custom trained class exists.
