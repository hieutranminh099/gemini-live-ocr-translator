# Gemini Live OCR Translator — development review

This is an existing Android/Windows application. Extend the current code; do not recreate the project.

## Roles and workspace

- Codex develops on `codex-dev` in its own worktree.
- Gemini CLI reviews and tests on `gemini-review` in a separate worktree.
- The original checkout is preserved. Do not switch, clean, reset, rebase or edit another AI's worktree.
- Do not commit, push, create a repository, merge or post a GitHub comment without the owner's authorization for that action.
- Initially review the files assigned by the owner. Sending source to a cloud model requires the owner's permission for that scope; never infer permission for credentials or personal data.

## Existing modules

- `android-app/`: screen capture, ML Kit OCR, latest-frame pipeline, subtitle overlay.
- `windows-server/app/`: official Gemini API, translation scheduling, cache, glossary, Translation Memory, video jobs and HTTP/WebSocket interfaces.
- `windows-server/static/`: Windows Companion web interface.
- `tests/`: Python regression tests; `android-app/app/src/test/`: JVM/Robolectric tests.
- `research/`: isolated benchmarks. A simulated or cached result is not fresh Gemini latency.

## Review rules

1. Inspect the requested code and explain concrete problems with a reproduction and relevant file/symbol.
2. Run the applicable offline tests when explicitly assigned. State exactly what ran and what did not.
3. Do not execute network benchmarks or spend API quota unless the owner authorized the bounded experiment.
4. Never read `.env*`, `.gemini/`, `.tools/signing/`, credentials, token stores, Windows application data, private videos or caches for a code review.
5. Do not change Gemini authentication, model selection, billing, global CLI configuration or system settings.
6. Do not auto-apply model-generated patches. Proposed fixes must be reviewed and tested before adoption.
7. Keep capture, OCR, translation and overlay asynchronous. Preserve request/region/generation guards, one OCR consumer, exact cache namespaces and stale-response rejection.
8. Gemini CLI is a development assistant. Do not turn consumer CLI/OAuth into an unsupported translation backend. The app's video translation uses the official API.
9. Preserve privacy defaults. AI translations remain Draft; AI cannot overwrite User Approved entries.
10. Do not claim a stable one-second latency without an uncached end-to-end measurement, sample count and failures.

## Test commands

Use the worktree environment described in `docs/GITHUB_COLLABORATION.md`.

```powershell
python -m pytest tests -q --basetemp=build/pytest-review
python scripts/build_android.py testDebugUnitTest lintDebug
```

Report findings in a local review document first. Do not run an automatic merge or post external messages.
