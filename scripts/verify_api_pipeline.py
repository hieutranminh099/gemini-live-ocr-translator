"""Verify fresh public subtitles through the official API and actual Android drawing.

An OCR hash mismatch is a failed exact-source check, even if the translation is useful.
Every attempt and its partial trace are saved, including on timeout or assertion failure.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
from benchmark_subtitle_pipeline import Benchmark, percentiles, source_hash

SOURCES = ["今天天气很好", "坐下休息一会", "我们马上回家"]
REPORT = ROOT / "docs/results/api-android-v162.json"
SCREENSHOT = ROOT / "docs/images/api-android-live-v162.png"


def verify_sample(bench, source: str, index: int, row: dict) -> None:
    """Keep exact native source, OCR source and rendered translation checks separate."""
    initial = bench.status()
    row["source"] = source
    row["expected_source_hash"] = source_hash(source)
    row["initial_ai_requests"] = initial["usage"]["requests"]
    scene = bench.scene(source)
    row["source_drawn"] = scene
    if scene.get("source_hash") != row["expected_source_hash"]:
        raise AssertionError("Native scene drew a different source hash")
    # Observe the actual draw first. Requiring a matching hash inside the wait would
    # turn a real OCR substitution into an opaque 50-second timeout.
    overlay = bench.wait(lambda e: e.get("event") == "overlay_drawn" and e["at_ms"] >= scene["at_ms"],
                         bench.args.timeout)
    row["overlay_drawn"] = overlay
    row["observed_source_hash"] = overlay.get("source_hash")
    row["exact_source_match"] = overlay.get("source_hash") == row["expected_source_hash"]
    row["source_to_overlay_ms"] = overlay["at_ms"] - scene["at_ms"]
    result = bench.wait(lambda e: e.get("event") == "result_received" and
                        e.get("request_id") == overlay.get("request_id") and e["at_ms"] >= scene["at_ms"])
    row["result_received"] = result
    final = bench.status()
    row["fresh_ai_requests"] = final["usage"]["requests"] - initial["usage"]["requests"]
    if not row["exact_source_match"]:
        raise AssertionError("OCR/overlay source hash differs from the exact native source; sample failed")
    if result.get("server_timings_ms", {}).get("provider", 0) <= 0 or row["fresh_ai_requests"] != 1:
        raise AssertionError("Expected exactly one fresh official API inference, not a cached response")
    response = bench.client.post("/api/translate", json=dict(
        request_id=f"verify-api-post-cache-{index}", source="zh", profile="game", mode="fast",
        blocks=[dict(id="b0", text=source, box=dict(x=0, y=0, width=.8, height=.1))],
    )).raise_for_status().json()
    cached = response["blocks"][0]
    row["server_cache_hit"] = cached["cached"]
    row["translation"] = cached["text"]
    row["server_translation_hash"] = hashlib.sha256(cached["text"].encode()).hexdigest()[:16]
    row["translation_matches_android_draw"] = overlay.get("translation_hash") == row["server_translation_hash"]
    after_cache = bench.status()
    row["cache_verification_ai_requests"] = after_cache["usage"]["requests"] - final["usage"]["requests"]
    if not row["server_cache_hit"] or row["cache_verification_ai_requests"] != 0:
        raise AssertionError("Server read-back must be a cache hit without another AI request")
    if not row["translation_matches_android_draw"]:
        raise AssertionError("Android canvas translation hash does not match server text")
    row["status"] = "passed"


def execute(bench, report: Path = REPORT, screenshot: Path = SCREENSHOT, *, android_version="1.6.2") -> dict:
    out = dict(provider="official_gemini_api", android_version=android_version, roi=bench.args.roi,
               requested_sources=SOURCES, samples=[], status="running", limitations=[
                   "Three controlled public subtitles only; not a P95 population benchmark or Redmi measurement.",
                   "Exact source hashes are required; OCR substitutions remain failures and are never canonicalized.",
               ])
    try:
        before = bench.status()
        if not (before["settings"]["provider"] == "api" and before["api_configured"] and before["translation_available"]):
            raise AssertionError("Official API must already be selected and configured")
        if before["settings"]["personal_cli_enabled"] is not False:
            raise AssertionError("Personal CLI must remain disabled for the official API test")
        out["windows_version"] = before["version"]
        out["model"] = before["settings"].get("fast_model")
        for index, source in enumerate(SOURCES):
            row = dict(status="running")
            out["samples"].append(row)
            try:
                verify_sample(bench, source, index, row)
            except BaseException as error:
                row["status"] = "failed"
                row["failure"] = dict(type=type(error).__name__, message=str(error))
                raise
            finally:
                # Retain preceding successful samples even if a later attempt fails.
                report.parent.mkdir(parents=True, exist_ok=True)
                report.write_text(json.dumps(out, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            print(json.dumps({k: v for k, v in row.items() if k not in ("result_received", "overlay_drawn", "source_drawn")},
                             ensure_ascii=False), flush=True)
        out["status"] = "passed"
        return out
    except BaseException as error:
        out["status"] = "failed"
        out["failure"] = dict(type=type(error).__name__, message=str(error))
        raise
    finally:
        # Diagnostic collection cannot erase the original test outcome or prevent saving.
        for name, collect in (
            ("events", bench.events),
            ("installed_apk_sha256", lambda: bench.run("shell", "sha256sum", bench.run(
                "shell", "pm", "path", "com.gemini.ocr").decode().strip().removeprefix("package:")
            ).decode().split()[0]),
        ):
            try:
                out[name] = collect()
            except Exception as error:
                out.setdefault("diagnostic_errors", {})[name] = f"{type(error).__name__}: {error}"
        try:
            screenshot.parent.mkdir(parents=True, exist_ok=True)
            screenshot.write_bytes(bench.run("exec-out", "screencap", "-p"))
        except Exception as error:
            out.setdefault("diagnostic_errors", {})["screenshot"] = f"{type(error).__name__}: {error}"
        passed = [row["source_to_overlay_ms"] for row in out["samples"] if row["status"] == "passed"]
        out["descriptive_e2e_ms"] = percentiles(passed)
        out["passed_samples"] = len(passed)
        try:
            report.parent.mkdir(parents=True, exist_ok=True)
            report.write_text(json.dumps(out, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        finally:
            bench.client.close()


def main():
    args = argparse.Namespace(serial="127.0.0.1:5555", phase="api-v162", roi=[233, 496, 809, 538], width=1600, height=900,
                              subtitle_sp=24, timeout=50, resume=None)
    execute(Benchmark(args))
    print("Native OCR -> official API -> Vietnamese -> Android overlay: PASS; 3 new AI calls.", flush=True)


if __name__ == "__main__":
    main()
