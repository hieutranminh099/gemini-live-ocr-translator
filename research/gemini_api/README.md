# Official Gemini API comparison (isolated research)

No production setting, pairing, keyring, CLI login or application cache is changed by these files.

The original `api-results.json` remains a **plan**, not measured data. Its original installed baseline was Windows 1.4.0 with Antigravity CLI 1.4.5 and no API key. The later supported API experiment below supersedes that no-API research state; it never reuses consumer CLI authentication.

## Actual Flash-Lite API experiment — 2026-10-23

After the owner supplied a supported API credential, the root agent ran the existing harness for **200 actual translation attempts**: 100 unique Chinese sentences with independent Vietnamese references, once non-streaming and once streaming. Model `gemini-3.5-flash-lite`, thinking `minimal`, compact prompt, 256 output-token ceiling, one reused HTTP client, concurrency 1, no retries, application translation cache bypassed. Raw evidence: [api-flashlite-live.json](api-flashlite-live.json). Independent offline validation and scoring: [live-analysis.json](live-analysis.json).

**Only 55/200 attempts succeeded; 145 returned HTTP 429. This run does not establish stable 1-second translation.** Successful calls returned the requested model identity. Latency columns below are **API-client durations of successful calls only**, include network/provider work, and exclude Android capture/OCR/overlay:

| Condition | Attempts | Success / HTTP 429 | First useful P50 | P90 | P95 | P99 | Final completion P95 |
|---|---:|---:|---:|---:|---:|---:|---:|
| Non-streaming | 100 | 28 / 72 | 732.375 ms | 1012.707 ms | 1042.988 ms | 1172.321 ms | 1042.990 ms |
| Streaming | 100 | 27 / 73 | 674.811 ms | 911.495 ms | 929.485 ms | 1035.042 ms | 983.475 ms |

Across **all attempts**, only **25/100 non-streaming** and **26/100 streaming** produced a complete accepted translation within 1 second. Failed requests count as having no accepted translation; there is no observed finite all-attempt P50/P90/P95/P99 bound. They must not be counted as fast completions or removed when discussing reliability. “First useful” here means validated complete JSON matching final accepted output, not certified semantic quality.

The first HTTP 429 appeared at request #14, the last success at #152, and the longest consecutive failure run was 70 requests. Requests #61–120 and #161–200 were all HTTP 429. This is consistent with provider rate/quota enforcement. The evidence intentionally omits error bodies and retry headers, so it cannot identify the precise RPM, TPM or daily limit. These are request-order ranges, not wall-clock windows. No 401/403 authentication errors or JSON-mapping failures occurred in this run.

Only **22 sentences succeeded in both conditions**. In that paired subset, streaming first-useful median delta was **−40.178 ms**, while final-completion median delta was **+10.341 ms** (streaming minus non-streaming). Complete-JSON-to-final median gap was about **50.275 ms** for streaming. Streaming is therefore not proven to remove a multi-second bottleneck, and this failure-heavy comparison does not justify a production switch by itself.

Reference metrics use SacreBLEU 2.6.0, BLEU tokenization `13a`, default chrF, one independent Tatoeba reference per isolated sentence. Successful-subset BLEU/chrF were **28.57/49.12 (n=28)** non-streaming and **32.38/51.72 (n=27)** streaming; these are different source subsets and cannot establish which condition has better quality. On the identical 22-source subset, BLEU/chrF were **26.71/46.97** and **29.55/50.24**, respectively. Sampling variation and stochastic output remain; 12/22 paired outputs differed. Scoring all 100 attempts with failed translations as empty gives chrF **14.29/14.78**, documenting missing output rather than claiming a pure engine-quality score. Only 33/100 unique sources had any successful result, and no full 100-success quality assessment or human review is claimed. These partial scores are not directly comparable with the local prototype's complete 100-sentence score.

Reported successful-call totals were **3,088 prompt tokens**, **1,215 candidate tokens**, **4,303 total tokens**. Failed calls did not report token usage; absent cached-token and thinking-token fields are recorded as unknown, not zero. No application translation-cache hit occurred. Billing cost was not measured or estimated.

Recompute the offline artifact without network or credential access:

```powershell
python research/gemini_api/analyze_live.py
python -m unittest discover -s research/gemini_api -p 'test_analyze_live.py' -v
```

The analyzer uses the existing project-local `sacrebleu` research dependency, verifies raw corpus/evidence hashes, requires exactly one attempt per source per condition, rejects partial runs, and recalculates nearest-rank quantiles independently. Its four contract tests use synthetic records that are never saved as benchmark evidence. `android_pipeline_target_proven=false` remains explicit in the actual analysis.

The Google FAQ explicitly limits consumer CLI wrapping; see [support-audit.md](support-audit.md). A consumer AI Pro login is not used or extracted by this harness. A supported Gemini API key with authorized quota/billing is required to execute the comparison. The harness reads only an explicitly supplied `GEMINI_API_KEY` environment variable and never logs it. It never obtains credentials from the installed application.

Run with Python and httpx available (the project already has httpx):

```powershell
$env:PYTHONPATH='.python-deps'
# Plan only; zero HTTP requests, no credential required:
python research/gemini_api/benchmark_api.py --corpus research/local_translation/dialogue100.jsonl
# Only after a supported API key is available through GEMINI_API_KEY:
python research/gemini_api/benchmark_api.py --corpus research/local_translation/dialogue100.jsonl --execute-requests
```

The corpus can be JSON or JSONL: each row has `id`, `source`/`zh`, `reference`/`vi`; it must contain at least `--count` rows (default 100). Adjust the example corpus path to the actual reference set. The default plan makes **400 translations** if executed: 100 sentences × `gemini-3.5-flash-lite:minimal` and `gemini-3.8-flash:low` × stream/non-stream. It first verifies availability using the official `models` endpoint. No silent fallback or automatic retries. Unavailable models and all errors are reported.

`corpus_sha256` identifies the raw corpus file bytes. `corpus_normalized_text_sha256` separately identifies its decoded UTF-8 text after removing a BOM and normalizing Windows newlines to LF. These differ for the current Windows JSONL file; the difference does not indicate a different sentence set.

Additional supported comparison parameters:

```powershell
# Add low thinking for Flash-Lite, and minimal/low for Flash 3.6:
python research/gemini_api/benchmark_api.py --corpus CORPUS --variant gemini-3.5-flash-lite:minimal --variant gemini-3.5-flash-lite:low --variant gemini-3.6-flash:minimal --max-requests 600
# Interactions matrix: store=false versus store=true, streaming versus non-streaming.
# The current production API provider uses generateContent, so store is not applicable there.
python research/gemini_api/benchmark_api.py --corpus CORPUS --endpoint interactions --compare-store --max-requests 800
# Baseline prompt/token ceiling comparison must use a separate output file:
python research/gemini_api/benchmark_api.py --corpus CORPUS --prompt baseline --max-output-tokens 8192 --output baseline-plan.json
```

Each command above remains **plan only** without `--execute-requests`. Do not put keys in command arguments, source files, reports or screenshots. This is not an API-key setup wizard, an OAuth migration, or approval to incur new billing.

Measurements use one reused `httpx.AsyncClient`, concurrency 1, a seeded shuffled scenario order per sentence and no application cache. Each sample records headers, first text, first complete valid JSON, final completion and total request wall time, along with actual token/cache usage and returned model identity. A “first useful complete translation” is counted only after its complete JSON matches the final accepted output and STOP/completed status. This does **not** establish natural-language quality. Partial tokens and thoughts never count as useful translations. Streaming errors and truncation are failures even if early JSON appears valid.

These are **API client durations**, including network and provider work; they do not measure internal Gemini compute, separate RTT, Android capture/OCR/debounce/queue or overlay draw. P50/P90/P95/P99 are nearest-rank quantiles of successful calls and include sample counts, with failures reported separately. Do not present them as Android end-to-end results. Cold first call is included and identifiable in row order. Provider token-cache hits must be distinguished from application translation-cache hits. Human quality review and reference scoring remain required before any model/Hybrid decision.

Offline contract tests (mocked responses, no network):

```powershell
python -m unittest discover -s research/gemini_api -p 'test_*.py' -v
```
