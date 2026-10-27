# User-defined rules through chat

New Android and web rule creation begins with a blank conversation. There is no selected RMS/sound condition, threshold, duration, gap or cooldown, and no dog/rain/doorbell example or preset button. Nothing is registered as a rule merely by opening the screen or sending a message.

1. Choose your provider/backend and explicitly consent to sharing the conversation text.
2. Describe your own event and desired behavior.
3. AURA asks a follow-up question if the intent is ambiguous or unsupported. Otherwise the LLM proposes a typed rule, with provisional settings and explanations for review. It does not apply a fixed numeric template or claim that proposed values came from you.
4. Reply in chat to refine the proposal. Editing the message invalidates the previous save action. No rule or event is persisted by preview/refinement.
5. Review the exact mapped classes, scores, persistence, gap, EMA, cooldown and notification behavior. Explicitly **Save disabled rule**, then separately enable it and start monitoring.

Existing saved rules retain their exact meaning, IDs, model/mapping pins and history. Advanced editing of their saved values remains available. Legacy API contracts remain compatible with older clients; their schema defaults are not new-chat presets. No existing rule is deleted or migrated into an invented chat rule.

## Conversation contract and privacy

`POST /api/v2/rules/preview` keeps schema version 2 and old single-message compatibility. Optional `conversation` is at most 12 alternating assistant/user turns, starting with an assistant reply and ending with the latest user response. Each turn allows only `role` and `text`; user replies are limited to 240 characters, assistant text to 512. The original request remains at most 240 characters. Empty, oversized, malformed or extra-field turns fail validation before calling the provider. Limits are enforced independently in Android; clients never silently drop older context to fit.

The authenticated backend sends bounded original request/context, catalog and schema to the chosen fixed OpenAI/Anthropic endpoint. Conversation context is explicitly untrusted; independent typed validation and class/profile checks still apply. The same two-call concurrency, timeout, response-size, no-redirect/no-retry/no-tools/no-fallback safeguards remain. Consent covers conversation text, never audio. API keys and device identifiers are not included in the provider prompt.

Chat stays in client memory, is lost on reset/process restart and is not written to SQLite or the local backup. Explicit Save stores the original request and exact typed settings; the full chat is not persisted. The stored typed settings are authoritative, including refinements. Provider retention policies still apply. The LLM's interpretation requires human review; schema validation is not proof it understood every instruction. Real provider quality/calibration remain unverified without live evaluation.

## Learning from recordings

Recording-based learning is **not implemented**. The create screen clearly says it is a future feature and provides no fake Record/Learn action. Current monitoring uses transient audio buffers only. No recording is captured, stored or uploaded by this chat flow.

A future opt-in example-learning flow would require its own consent, microphone lifecycle, sample labeling/deletion/retention design, an appropriate local model and real evaluation. One recorded example would not by itself establish a reliably trained or calibrated new sound detector. Until then, unsupported event requests receive clarification instead of invented class IDs or scores.
