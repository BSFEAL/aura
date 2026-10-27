# Anthropic rule planning (v0.4.1)

The local FastAPI backend supports **OpenAI or Anthropic**, selected explicitly in the Android/web natural-language builder. OpenAI remains the default for old clients. Existing saved rules, manual HTTPS sync and local YAMNet monitoring are unchanged. Old Android clients only offer OpenAI; upgrade to 0.4.1 to select Anthropic, or use the web builder and sync reviewed typed rules to the existing phone.

Follow [local backend and private HTTPS setup](OPENAI_RULE_BUILDER.md), using `main` after this feature merges (otherwise `feat/aura-anthropic-rule-planner`). Set the following **in the backend process environment**, never in the APK, web app, repository or chat:

```bash
export AURA_LLM_ENABLED=true
read -r -s -p 'Anthropic API key: ' ANTHROPIC_API_KEY
export ANTHROPIC_API_KEY
export AURA_ANTHROPIC_MODEL=claude-sonnet-4-6
```

Keep `AURA_API_TOKEN` configured separately (24+ characters). Run the backend on `127.0.0.1`, not a public interface. The sample `.env.example` is documentation only; uvicorn does not load it automatically. `OPENAI_API_KEY` is optional when only Anthropic is used. Both keys may coexist. Provider model availability depends on your account; the model setting is configurable but must support Anthropic JSON structured output. The default names the Sonnet 4.6 model family; provider-side updates/reproducibility are outside AURA's control.

Select **Anthropic**, then explicitly consent to sharing rule text with Anthropic and request a preview. Changing providers clears the preview and consent. Review exact classes, settings and assumptions, then **Save disabled rule**; enabling and starting the microphone remain separate actions. No provider fallback, retry, recording or audio upload occurs.

Explicit live smoke (potential API cost; does not save):

```bash
python tools/openai_rule_smoke.py --provider anthropic --allow-send-text
```

The script retains its old filename for compatibility. It requires the same AURA token in that terminal and defaults to loopback HTTP. Phone access still requires private trusted HTTPS; do not disable certificate validation.

## Protocol and safeguards

* Official fixed `https://api.anthropic.com/v1/messages`; `anthropic-version: 2023-06-01`, server-side `x-api-key` only. No endpoint overrides, redirects or tools.
* Messages API `output_config.format.type=json_schema`, the same curated schema/instructions and independent typed-rule validation as OpenAI. Completed `end_turn`, one JSON text block, valid model metadata required; refusal, truncation, malformed/unsupported rules and HTTP failures fail closed.
* Request: `provider: "anthropic"`, `consent_to_anthropic: true`; old OpenAI consent never authorizes Anthropic. Response mode `anthropic`, planner version `aura-anthropic-rule-planner-v1`. Clients verify provider/version binding. Old OpenAI payloads stay valid.
* Only explicit rule text, supported catalog, instructions and schema leave the backend. Device IDs, AURA tokens, raw audio and histories are not sent. Anthropic's key authenticates only Anthropic; OpenAI's key authenticates only OpenAI.
* Shared two-call concurrency bound, 25-second overall limit, 20-second HTTP timeout, 256 KiB response limit, no automatic retries/fallback. Keys and provider response bodies are not exposed in client errors.
* Anthropic has no OpenAI-style `store:false` parameter here; do not send it. Provider retention policies apply. This feature does not promise zero retention. Avoid sensitive text.

Official protocol references: [Messages API](https://platform.claude.com/docs/en/api/messages), [structured outputs](https://platform.claude.com/docs/en/build-with-claude/structured-outputs).

## Validation limits

Automated tests use explicitly synthetic provider transports against the real authenticated API/SQLite and web UI. Kotlin tests validate Anthropic envelopes and reject provider mismatches; Android UI tests cover provider consent reset. Actual YAMNet inference tests remain required and unchanged. These checks do not establish live Anthropic account access, language accuracy, cloud latency/cost, battery use or physical-phone behavior. Live testing remains pending until a key is configured on your machine. LLM interpretation requires review; strict JSON is not proof of correct intent.
