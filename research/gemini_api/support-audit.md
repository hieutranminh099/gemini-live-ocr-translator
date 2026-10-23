# Provider support audit — 2026-10-23 (Asia/Saigon)

Historical audit before the owner supplied an API key. The installed app later switched to the official API; the live200-attempt experiment and current limitations are in [README.md](README.md) and [API_RESTORE_V162.md](../../docs/API_RESTORE_V162.md). The earlier no-API statements below describe that audit instant, not current configuration.

## Installed facts and code

Read-only authenticated `/api/status` check reported Windows **1.4.0**, `provider=cli`, `cli_model=auto`, client **Antigravity CLI 1.4.5**, `api_configured=false`, and zero warm sessions at the instant checked. Only selected non-secret status fields were printed. No OAuth/token/keyring files were read, no live translation calls were sent, and no production setting was changed for this audit.

Production `windows-server/app/antigravity_provider.py` launches stock `agy` as a hidden child with stream-json input/output and routes OCR requests through it. The fast mode resolves to `gemini-3.8-flash-low`. It reuses a live child for a device/configuration/model session; it does **not** normally create one child per subtitle. It rotates at 32 turns, 32 KiB input, 180 seconds idle, child exit, errors/cancellation or reset/disconnect. `cli_start` measures OS process creation, not Google's authentication/model readiness. First output and final-result timings include remote work and transport. The initial private-profile/agent setup does not itself establish that the surrounding application's consumer use is supported.

The API implementation in `windows-server/app/provider.py` instead reuses `httpx.AsyncClient` and calls the official `models:generateContent` REST endpoint. It currently requests a large 8192-token ceiling and no explicit thinking level. It does not use Interactions, so an Interactions `store=true` hypothesis does not describe the current production backend.

Android's current result feedback formats the server response's `latency_ms / 1000.0`. The manager calculates this as server handling time plus time waiting in the latest-request mailbox; handling includes cache lookup, semaphore/CLI queue and provider work. It ends before the WebSocket response is delivered. It is **not** screen-to-overlay latency, an elapsed pending counter, or isolated Gemini computation. Further Android/service instrumentation is owned by the main project changes, not this research folder.

## Google consumer CLI support limitation

The current official [Antigravity FAQ](https://www.antigravity.google/docs/faq/) states: “Wrapping `agy` inside third-party coding agents or applications” is not supported under consumer plans. The same FAQ distinguishes direct scripting/CI from wrapping applications. This production translation app wraps `agy` inside an application, so headless mode or use of the stock binary cannot be treated as authorization for the consumer backend. This is broader than extracting OAuth tokens; the code's avoidance of token extraction does not resolve it.

Accordingly this audit made **no additional consumer CLI benchmark calls**. Existing historical measurements may be analyzed as historical data, but are not fresh 100–200-call evidence. A supported API credential/entitlement is needed for an API/Flash-Lite comparison; none is currently configured. The previous assumption that using the official CLI binary made this consumer backend supported was incorrect.

The official [CLI authentication guide](https://www.antigravity.google/docs/cli/install/) describes using a developer Gemini API key to send requests directly to the Gemini API without creating an account session. It requires explicit provider selection in the CLI. This harness uses the direct official REST API instead and makes no authentication changes to the application.

## Verified model/API information

The official [Gemini 3.5 Flash-Lite model page](https://ai.google.dev/gemini-api/docs/models/gemini-3.5-flash-lite) lists stable model ID `gemini-3.5-flash-lite`, text output and structured-output support. It describes a latency-oriented model; that is not a guarantee of 1-second latency or of Chinese–Vietnamese subtitle quality.

The official [thinking guide](https://ai.google.dev/gemini-api/docs/thinking) lists minimal as the Flash-Lite 3.5 default with minimal/low/medium/high options. Flash 3.6 also supports minimal/low; Flash 3.8 supports low/medium/high and does not support minimal. The harness rejects Flash 3.8 + minimal rather than silently substituting a level.

The official [REST generateContent reference](https://ai.google.dev/api/generate-content) documents `models:generateContent`, `models:streamGenerateContent`, JSON output and `thinkingConfig.thinkingLevel`. This is the interface already used by the production API route. The harness keeps this route as its default comparison and verifies model access via the official model-list endpoint before any translation calls.

The official [Interactions overview](https://ai.google.dev/gemini-api/docs/interactions-overview) says generateContent remains supported and Interactions supports optional state. Interactions defaults to storage; `store=false` disables interaction storage and cannot be used for background execution or later `previous_interaction_id` chaining. The isolated optional matrix compares false/true explicitly; it does not assume this setting makes calls faster. See the official [structured-output guide](https://ai.google.dev/gemini-api/docs/structured-output) and [streaming guide](https://ai.google.dev/gemini-api/docs/streaming) for the event schema. Complete valid translations are distinct from first streamed text tokens.

## What is measured and what is not

Only provider/configuration **status** was verified live. **Nine offline mocked-response tests passed** (0.171 seconds, no HTTP requests). They validate the benchmark parser and payload construction, not translation quality or Google latency. The dry-run plan uses `research/local_translation/dialogue100.jsonl`: 100 unique public Tatoeba Chinese sentences with independent Vietnamese references, not continuous subtitle dialogue or a human evaluation panel. No API latency percentiles, model quality, cost, provider cache ratio or Hybrid improvement have been measured. The generated plan explicitly records `status=not_run`, `requests_sent=0`, `planned_requests=400`; it must not be combined with earlier CLI timings to fabricate Before/After or target attainment.
