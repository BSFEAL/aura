# OpenAI rule builder — local backend setup

AURA v0.4.0 adds an explicit text-only OpenAI planner. YAMNet microphone inference remains entirely on the phone. The LLM proposes typed rules; it does not listen to audio, save rules, activate microphones, call emergency services or execute tools. There is no automatic local-parser/fabricated-LLM fallback on failure. Existing manual builders remain available. Once a typed rule is saved/enabled, monitoring evaluates it locally without a running backend or another LLM request.

## Configure your local machine

Use the feature branch `feat/aura-natural-rule-builder` until its PR is merged; main does not yet contain this endpoint. Fetch/check out that branch before starting. Use Python 3.12+ and the repository's pinned dependencies. Keep the server on loopback:

```bash
python -m venv .venv
. .venv/bin/activate
pip install -r backend/requirements.txt
read -r -s -p 'AURA API token (choose a private random value, 24+ characters): ' AURA_API_TOKEN
printf '\n'
export AURA_API_TOKEN
read -r -s -p 'OpenAI API key: ' OPENAI_API_KEY
printf '\n'
export OPENAI_API_KEY
export AURA_LLM_ENABLED=true
export AURA_OPENAI_MODEL=gpt-4.1-mini-2025-04-14
python -m uvicorn app:app --app-dir backend --host 127.0.0.1 --port 8000 --limit-concurrency 8
```

These Bash prompts do not echo keys or place their values in command history. Use your password manager to create/store the AURA token; it is separate from your OpenAI key. The OpenAI account must have billing/model access. The optional `backend/.env.example` is documentation with empty secrets; uvicorn does not automatically load it. Set variables in the server's actual process environment. Do not paste keys into chat, the APK, frontend files, screenshots, GitHub commits, URL parameters or command-line arguments. Signing secrets are unrelated and remain deferred.

Default provider: the official Responses endpoint `https://api.openai.com/v1/responses`, pinned model `gpt-4.1-mini-2025-04-14`. `AURA_OPENAI_MODEL` may select another account-accessible model supporting Responses strict structured outputs; model availability and compatible output behavior must be tested. The selected/returned model is shown in the preview. No custom provider URL is accepted, and redirects are refused so credentials are not forwarded elsewhere.

## First check on the computer

Keep the terminal above running. In another terminal using the same virtualenv, securely set the **same AURA_API_TOKEN**. Then run:

```bash
python tools/openai_rule_smoke.py --allow-send-text
```

This explicitly authorizes one small example prompt to OpenAI and may incur API cost. It calls your loopback backend and reports whether a validated disabled proposal was returned. It never saves, enables or activates a rule. No live API result is claimed until this is executed with your real configured key. The key need not be supplied to Codex.

For the web dashboard, start `npm run dev` from `web/` and open its loopback URL on the computer. Its development proxy forwards `/api` to the local backend. Connect using the AURA token, register/select a device, enter a request, check the OpenAI text-sharing checkbox, and choose **Send text to OpenAI & preview**. Review the exact classes, scores, timing and assumptions. **Save disabled rule** is a second explicit action. Changing text/device/consent invalidates the preview. Enable the rule separately; a phone still requires manual sync and explicit microphone Start.

## Try with your existing v0.3 phone app (no reinstall)

The planner saves the existing schema-2 rule format, so a v0.3 phone with manual typed-rule sync can use web-generated rules without the new Android editor. After private HTTPS is set up below: stop monitoring on the phone, register/sync it to this backend in Devices, select that registered phone in the computer's web dashboard, request/review/Save the disabled rule, explicitly enable it, then manually Sync again on the stopped phone. Verify the downloaded rule and explicitly Start monitoring. Nothing in this sequence requires uninstalling a working APK. Actual connectivity must still be tested on your machine/phone.

## Connect the Android phone privately

`localhost` on a phone means the phone, not your computer. Android requires a trusted private **HTTPS** URL; HTTP, redirects, embedded URL credentials and invalid certificates are rejected. The API stays on `127.0.0.1`; do not expose an unauthenticated development server or bind it publicly to make the phone connect.

One option is a private Tailscale network: install/connect Tailscale on your computer and phone, configure its HTTPS support, and use **Tailscale Serve** (not public Funnel) to proxy your loopback API. For current Tailscale versions the local command is:

```bash
tailscale serve --bg http://127.0.0.1:8000
```

Use the private HTTPS URL printed by Tailscale, restrict tailnet access to your own devices, and keep AURA bearer authentication enabled. This requires your own Tailscale account/configuration; it has not been performed by this implementation. An existing authenticated private reverse proxy with a certificate trusted by Android is another option. No certificate validation bypass or blanket cleartext exception is added to the APK.

In **Rules → Describe a monitoring rule**, enter that private HTTPS URL and the **AURA API token**, not the OpenAI key. These share the app's existing backend fields; the token stays in memory. A registered API device is not required to preview/save a local phone rule. Enter, for example, “Notify me when my dog barks for 2 seconds, with a cooldown of 30 seconds.” Consent to text sharing, send, review and save disabled. Enable the rule separately and explicitly Start monitoring. Existing HTTPS manual sync behavior is unchanged.

Do not uninstall a working installation to obtain this version: a matching signing certificate is still required for an in-place update. This PR builds on the unmerged backup/feedback PRs; signing setup remains a separate task.

## Data flow, validation and limitations

`POST /api/v2/rules/preview` requires AURA bearer authentication, schema version 2, source device ID, 1–240-character text and boolean `consent_to_openai: true`. Extra/audio fields are rejected. The request's device ID is used only to bind the preview; it is not sent to OpenAI or used to read another device. Rule creation still checks registered device ownership as before.

The backend sends rule text, a static curated class catalog, instructions and JSON output schema to OpenAI. The OpenAI key is sent only as the HTTPS Authorization header to the fixed provider endpoint. No microphone audio, existing history, AURA token or app device ID is included in the model input. OpenAI `store:false` disables Responses storage, but does not guarantee zero retention or override account/provider policies. Do not include sensitive information in rule text. Requests are explicit, never background jobs or retries; time is bounded to 25 seconds, response size to 256 KiB and concurrent provider calls to two per backend process. Deployment concurrency is additionally bounded in the example above.

Strict structured outputs are followed by independent Pydantic validation against AURA's existing condition/policy contracts. Only the six curated events and nine real model IDs are allowed. A class filter must select one class or the complete mapping of one event; unsupported subsets/extra actions, invalid scores, hysteresis, times, types and schemas are rejected. Model/mapping/threshold versions are set by AURA, not the LLM. RMS remains amplitude only and new RMS proposals use non-legacy repeat behavior; the old RMS natural-language form keeps its existing semantics.

The Android client independently validates the response version, original text/device binding, disabled state, class labels/IDs, profile pins and numeric bounds. Preview cannot save, and Save creates a new local disabled rule. Server previews never touch SQLite rules/events. The web saves only after explicit confirmation through the existing validated API. A later manual sync never activates capture.

Typed validation cannot prove that an LLM understood the complete request. Negation, combined conditions, physical door opening, schedules, absence, location and safety guarantees are not supported by the current rule engine; the model is instructed to ask for clarification. It may still misinterpret intent. **Only the displayed typed conditions are enforced. Review them against the original request before saving.** Scores/defaults are provisional, not calibrated probabilities. Original request text and exact typed settings are retained; planner model metadata is shown in the preview, not a full persisted conversation/audit trail. No emergency actions are available.

## Automated versus live validation

CI uses explicit provider HTTP fixtures through `httpx.MockTransport`, not OpenAI credentials or invented production fallback predictions. It tests the actual API/OpenAI request shape, strict schema conversion, auth/consent, no preview writes, disabled save, refusal/error/timeout/redirect/size handling, class filtering and no secret leakage. Browser integration uses the real FastAPI/SQLite app with a test-only injected provider transport; Android UI uses a test-only planner interface. Shared protocol fixtures check client/server agreement. Actual native YAMNet inference tests continue independently.

Live OpenAI interpretation quality, real-key/model access, private phone HTTPS connectivity, cloud latency/cost, and phone UI/consent/save behavior remain PENDING until tested on your configured machine/phone. Model accuracy, battery use and thermal behavior are not inferred from these tests.

API reference: [OpenAI Responses create](https://platform.openai.com/docs/api-reference/responses/create) and [Structured Outputs](https://platform.openai.com/docs/guides/structured-outputs).
