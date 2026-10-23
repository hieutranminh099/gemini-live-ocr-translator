"""Measure the installed Android app against the installed Companion using public test text.

Does not change pairing, source language, capture settings, ROI or playback controls.
Requires an active OCR session and the separate TestScene APK. Device monotonic source
draw and overlay draw events provide E2E measurements without cross-device clock math.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shlex
import subprocess
import time
import unicodedata
from urllib.parse import urlsplit

import httpx


ROOT = Path(__file__).resolve().parents[1]
SCENE = "com.gemini.ocr.test/com.gemini.ocr.TestSceneActivity"
SAMPLES = ["风有点大。", "今天休息。", "车快到了。", "还要多久。", "先买张票。", "小心台阶。"]


def source_hash(text: str) -> str:
    return hashlib.sha256(unicodedata.normalize("NFKC", text).encode()).hexdigest()[:16]


def percentiles(values: list[float]) -> dict:
    ordered = sorted(values)
    if not ordered:
        return {"n": 0}
    def quantile(p):
        position = (len(ordered) - 1) * p
        lo = int(position)
        hi = min(lo + 1, len(ordered) - 1)
        return round(ordered[lo] + (ordered[hi] - ordered[lo]) * (position - lo), 2)
    return {"n": len(ordered), "method": "linear interpolation", "average_ms": round(sum(ordered) / len(ordered), 2),
            "p50_ms": quantile(.5), "p90_ms": quantile(.9), "p95_ms": quantile(.95), "p99_ms": quantile(.99),
            "max_ms": max(ordered)}


class Benchmark:
    def __init__(self, args):
        tools = json.loads((ROOT / ".tools/android-tools.json").read_text())
        self.adb = [str(Path(tools["sdk"]) / "platform-tools/adb.exe"), "-s", args.serial]
        url = urlsplit((Path(os.environ["LOCALAPPDATA"]) / "GeminiOCR/data/dashboard.url").read_text())
        self.client = httpx.Client(base_url="http://" + url.netloc,
                                  headers={"Authorization": "Bearer " + url.fragment}, trust_env=False)
        self.args = args
        self.counter = 0
        self.started_ms = int(float(self.run("shell", "cat", "/proc/uptime").decode().split()[0]) * 1000)
        self.output = {"measurement": "Android elapsedRealtime: source first draw to overlay first draw",
                       "started_device_ms": self.started_ms, "phase": args.phase,
                       "roi_preserved": args.roi, "samples": [], "checks": {}, "events": [],
                       "limitations": "Small controlled sample; not a guarantee for every subtitle or network condition."}
        if args.resume:
            self.output = json.loads((ROOT / args.resume).read_text(encoding="utf-8"))
            self.started_ms = self.output["started_device_ms"]
            self.counter = len(self.output.get("events", [])) + 100
            if "failure" in self.output:
                self.output.setdefault("prior_attempts", []).append(self.output.pop("failure"))

    def run(self, *args, timeout=30):
        return subprocess.run(self.adb + list(args), capture_output=True, check=True, timeout=timeout).stdout

    def status(self):
        for attempt in range(3):
            try:
                return self.client.get("/api/status").raise_for_status().json()
            except httpx.TransportError:
                if attempt == 2:
                    raise
                time.sleep(.1)

    def events(self):
        raw = self.run("logcat", "-d", "-v", "raw", "-s", "OCRPipeline:I", "GeminiScene:I").decode(errors="replace")
        events = []
        for line in raw.splitlines():
            try:
                event = json.loads(line)
            except (ValueError, TypeError):
                continue
            if not isinstance(event, dict):
                continue
            event["at_ms"] = event.get("at_ms", event.get("elapsed_ms", 0))
            if event["at_ms"] >= self.started_ms:
                events.append(event)
        return events

    def wait(self, predicate, timeout=30):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            events = self.events()
            matches = [event for event in events if predicate(event)]
            if matches:
                return matches[-1]
            time.sleep(.075)
        raise TimeoutError("Expected public test event was not observed; see partial benchmark JSON")

    def scene(self, text):
        self.counter += 1
        ident = f"{self.args.phase}-{self.started_ms}-{self.counter}"
        left, top, right, bottom = self.args.roi
        center = ((left + right) / 2) / self.args.width
        baseline = (top + .78 * (bottom - top)) / self.args.height
        self.run("shell", "am", "start", "-f", "0x20000000", "-n", SCENE,
                 "--ez", "subtitle", "true", "--ez", "animate", "true",
                 "--ef", "sourceCenter", str(center), "--ef", "subtitleBaseline", str(baseline),
                 "--ef", "subtitleSp", str(self.args.subtitle_sp),
                 "--es", "eventId", ident, "--es", "sourceText", shlex.quote(text))
        return self.wait(lambda e: e.get("event") == "source_drawn" and e.get("id") == ident, 5)

    def overlay(self, source, since):
        return self.wait(lambda e: e.get("event") == "overlay_drawn" and
                         e.get("source_hash") == source_hash(source) and e["at_ms"] >= since, self.args.timeout)

    def screenshot(self, suffix):
        path = ROOT / "build" / f"pipeline-{self.args.phase}-{suffix}.png"
        path.write_bytes(self.run("exec-out", "screencap", "-p"))
        return str(path.relative_to(ROOT)).replace("\\", "/")

    def rapid_sequence(self):
        """Four real source changes driven inside the Activity, with no host timing jitter."""
        texts = ["慢慢走。", "坐下吧。", "等一下。", "休息吧。"]
        ident = f"rapid-{self.args.phase}-{self.started_ms}"
        sequence = [{"id": f"{ident}-{i}", "text": text, "durationMs": 800} for i, text in enumerate(texts)]
        left, top, right, bottom = self.args.roi
        before = self.status()
        self.run("shell", "am", "start", "-f", "0x20000000", "-n", SCENE,
                 "--ez", "subtitle", "true", "--ez", "animate", "true",
                 "--ef", "sourceCenter", str(((left + right) / 2) / self.args.width),
                 "--ef", "subtitleBaseline", str((top + .78 * (bottom - top)) / self.args.height),
                 "--ef", "subtitleSp", str(self.args.subtitle_sp),
                 "--es", "sequenceJson", shlex.quote(json.dumps(sequence, ensure_ascii=False)))
        last = self.wait(lambda e: e.get("event") == "source_drawn" and e.get("id") == sequence[-1]["id"], 8)
        overlay = self.overlay(texts[-1], last["at_ms"])
        after = self.status()
        sources = [e for e in self.events() if e.get("event") == "source_drawn" and e.get("id", "").startswith(ident)]
        stale = []
        for event in self.events():
            if event.get("event") != "overlay_drawn" or not sources or event["at_ms"] < sources[0]["at_ms"]:
                continue
            latest = max((source for source in sources if source["at_ms"] <= event["at_ms"]), key=lambda e: e["at_ms"])
            index = int(latest["id"].rsplit("-", 1)[1])
            if event.get("source_hash") != source_hash(texts[index]):
                stale.append(event)
        self.output["checks"]["rapid_sequence"] = {
            "source_duration_ms": 800, "source_changes": len(sources), "sources": sources,
            "last_source": texts[-1], "last_source_to_overlay_ms": overlay["at_ms"] - last["at_ms"],
            "last_overlay": overlay, "late_stale_overlays": stale,
            "new_ai_calls": after["usage"]["requests"] - before["usage"]["requests"],
            "new_errors": after["metrics"]["errors"] - before["metrics"]["errors"],
            "passed": len(sources) == len(texts) and not stale,
            "limitations": "Intermediate sentences may be coalesced; this tests freshness, not successful translation of every short-lived subtitle.",
            "screenshot": self.screenshot("rapid-latest")}

    def verify_rendered_translations(self):
        """Read public translations back from server cache and compare actual canvas draw hashes."""
        targets = [{"source": self.output["samples"][-1]["source"],
                    "overlay": self.output["samples"][-1]["overlay_drawn"]},
                   {"source": self.output["checks"]["A"]["source_b"],
                    "overlay": self.output["checks"]["A"]["overlay_b"]}]
        if "rapid_sequence" in self.output["checks"]:
            rapid = self.output["checks"]["rapid_sequence"]
            targets.append({"source": rapid["last_source"], "overlay": rapid["last_overlay"]})
        before = self.status()
        rows = []
        for index, target in enumerate(targets):
            response = self.client.post("/api/translate", json={"request_id": f"benchmark-cache-{self.started_ms}-{index}",
                "source": self.args.source, "profile": self.args.profile, "mode": self.args.mode,
                "blocks": [{"id": "verify", "text": target["source"],
                            "box": {"x": 0, "y": 0, "width": .9, "height": .1}}]}).raise_for_status().json()
            block = response["blocks"][0]
            digest = hashlib.sha256(block["text"].encode()).hexdigest()[:16]
            rows.append({"source": target["source"], "server_translation": block["text"],
                         "server_cached": block["cached"], "server_translation_hash": digest,
                         "android_overlay_translation_hash": target["overlay"].get("translation_hash"),
                         "matches_canvas_draw": digest == target["overlay"].get("translation_hash")})
            if not block["cached"]:
                break  # A profile/source mismatch must not trigger multiple unplanned AI calls.
        after = self.status()
        self.output["checks"]["server_to_android_translation"] = {"rows": rows,
            "new_ai_calls": after["usage"]["requests"] - before["usage"]["requests"],
            "cache_verification_frames": after["metrics"]["frames"] - before["metrics"]["frames"],
            "passed": len(rows) == len(targets) and all(r["server_cached"] and r["matches_canvas_draw"] for r in rows)
                      and after["usage"]["requests"] == before["usage"]["requests"]}

    def save(self):
        self.output["events"] = self.events()
        self.output["dialogue_e2e"] = percentiles([s["source_to_overlay_ms"] for s in self.output["samples"]])
        self.output["uncached_e2e"] = percentiles([s["source_to_overlay_ms"] for s in self.output["samples"] if s["new_ai_calls"] > 0])
        self.output["cached_e2e"] = percentiles([s["source_to_overlay_ms"] for s in self.output["samples"] if s["new_ai_calls"] == 0])
        fresh = [s for s in self.output["samples"] if s["new_ai_calls"] > 0]
        self.output["uncached_server"] = percentiles([s["result_received"]["server_timings_ms"]["server"]
            for s in fresh if s.get("result_received") and s["result_received"].get("server_timings_ms", {}).get("server") is not None])
        stages = {}
        for row in self.output["samples"]:
            for key in ("capture_ms", "ocr_queue_ms", "ocr_ms", "source_image_age_ms"):
                value = (row.get("source_changed") or {}).get(key)
                if value is not None:
                    stages.setdefault(key, []).append(value)
            for key in ("round_trip_ms", "transport_residual_ms"):
                value = (row.get("result_received") or {}).get(key)
                if value is not None:
                    stages.setdefault(key, []).append(value)
            for key, value in ((row.get("result_received") or {}).get("server_timings_ms") or {}).items():
                stages.setdefault("server_" + key + "_ms", []).append(value)
            for key in ("overlay_ms", "received_to_draw_ms", "observed_to_draw_ms"):
                value = row["overlay_drawn"].get(key)
                if value is not None:
                    stages.setdefault(key, []).append(value)
        self.output["stage_percentiles"] = {key: percentiles(values) for key, values in stages.items()}
        path = ROOT / self.args.output
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(self.output, ensure_ascii=False, indent=2), encoding="utf-8")

    def execute(self):
        initial = self.status()
        self.output["android_version"] = self.run("shell", "dumpsys", "package", "com.gemini.ocr").decode().split("versionName=")[1].splitlines()[0].strip()
        self.output["windows_version"] = initial["version"]
        self.output["provider"] = initial["settings"]["provider"]
        self.output["model"] = initial["cli"].get("model")
        try:
            self.scene("")
            time.sleep(.35)
            samples = json.loads(Path(self.args.samples_file).read_text(encoding="utf-8")) if self.args.samples_file else SAMPLES
            if not isinstance(samples, list) or not samples or not all(isinstance(t, str) and t.strip() for t in samples):
                raise ValueError("Samples must be a JSON list of nonempty public source strings")
            if self.args.checks_only:
                samples = [row["source"] for row in self.output["samples"]]
            for source in ([] if self.args.checks_only else samples):
                before = self.status()
                scene = self.scene(source)
                overlay = self.overlay(source, scene["at_ms"])
                after = self.status()
                matching = [e for e in self.events() if e.get("event") == "result_received" and
                            e.get("request_id") == overlay.get("request_id") and e["at_ms"] >= scene["at_ms"]]
                changed = [e for e in self.events() if e.get("event") == "source_changed" and
                           e.get("source_hash") == source_hash(source) and
                           e.get("screen_generation") == overlay.get("screen_generation") and e["at_ms"] >= scene["at_ms"]]
                row = {"source": source, "source_hash": source_hash(source), "source_drawn": scene,
                       "overlay_drawn": overlay, "result_received": matching[-1] if matching else None,
                       "source_changed": changed[-1] if changed else None,
                       "source_to_overlay_ms": overlay["at_ms"] - scene["at_ms"],
                       "new_ai_calls": after["usage"]["requests"] - before["usage"]["requests"],
                       "new_server_frames": after["metrics"]["frames"] - before["metrics"]["frames"],
                       "new_errors": after["metrics"]["errors"] - before["metrics"]["errors"]}
                self.output["samples"].append(row)
                print(json.dumps({k: row[k] for k in ("source", "source_to_overlay_ms", "new_ai_calls", "new_errors")}, ensure_ascii=False), flush=True)
                self.save()
            if not self.args.checks_only:
                self.output["samples_final_screenshot"] = self.screenshot("dialogue")

            # A: the AI call for A must complete after B starts without drawing A over B.
            text_a, text_b = self.args.fresh_a, self.args.fresh_b
            before_a = self.status()
            scene_a = self.scene(text_a)
            sent_a = self.wait(lambda e: e.get("event") == "request_sent" and e.get("source_hash") == source_hash(text_a) and e["at_ms"] >= scene_a["at_ms"], 5)
            at_b = self.status()
            scene_b = self.scene(text_b)
            overlay_b = self.overlay(text_b, scene_b["at_ms"])
            after_b = self.status()
            events = self.events()
            result_a = [e for e in events if e.get("event") in ("result_received", "result_discarded") and e.get("request_id") == sent_a.get("request_id")]
            stale_draws = [e for e in events if e.get("event") == "overlay_drawn" and e["at_ms"] >= scene_b["at_ms"] and e.get("source_hash") == source_hash(text_a)]
            result_b = [e for e in events if e.get("event") == "result_received" and e.get("request_id") == overlay_b.get("request_id")]
            a_inflight = at_b["usage"]["requests"] > before_a["usage"]["requests"] and at_b["metrics"]["frames"] == before_a["metrics"]["frames"]
            completed = after_b["metrics"]["frames"] - before_a["metrics"]["frames"]
            calls = after_b["usage"]["requests"] - before_a["usage"]["requests"]
            self.output["checks"]["A"] = {"source_a": text_a, "source_b": text_b, "source_a_drawn": scene_a,
                "request_a": sent_a, "source_b_drawn": scene_b, "result_a": result_a,
                "result_b": result_b, "a_inflight_before_b": a_inflight,
                "completed_server_frames": completed, "new_ai_calls": calls,
                "overlay_b": overlay_b, "stale_a_draws_after_b": stale_draws,
                "old_response_suppression_expected": True,
                "passed": a_inflight and completed >= 2 and calls >= 2 and not stale_draws,
                "screenshot": self.screenshot("test-a-current-b")}
            self.save()

            # B: removing text must invalidate the source state and caption.
            blank = self.scene("")
            cleared = self.wait(lambda e: e.get("event") in ("source_cleared", "source_changed") and
                                e["at_ms"] >= blank["at_ms"] and
                                (e.get("empty") is True or e.get("source_hash") == source_hash("")), 5)
            self.output["checks"]["B"] = {"blank_drawn": blank, "cleared": cleared,
                "clear_ms": cleared["at_ms"] - blank["at_ms"], "passed": True,
                "screenshot": self.screenshot("test-b-empty")}
            self.save()

            # Cache: the earlier known phrase should render without a second Gemini request.
            before = self.status()
            cached_scene = self.scene(samples[0])
            cached_overlay = self.overlay(samples[0], cached_scene["at_ms"])
            after = self.status()
            self.output["checks"]["cache"] = {"source": samples[0], "overlay": cached_overlay,
                "source_to_overlay_ms": cached_overlay["at_ms"] - cached_scene["at_ms"],
                "new_ai_calls": after["usage"]["requests"] - before["usage"]["requests"],
                "passed": after["usage"]["requests"] == before["usage"]["requests"]}
            # C: unchanged frame for five seconds must not produce another request.
            before = self.status()
            since = cached_overlay["at_ms"]
            time.sleep(5)
            after = self.status()
            duplicates = [e for e in self.events() if e.get("event") == "request_sent" and
                          e["at_ms"] > since and e.get("source_hash") == source_hash(samples[0])]
            self.output["checks"]["C"] = {"stationary_ms": 5000, "new_ai_calls": after["usage"]["requests"] - before["usage"]["requests"],
                "duplicate_request_events": duplicates,
                "passed": not duplicates and after["usage"]["requests"] == before["usage"]["requests"]}
            if self.args.rapid:
                self.rapid_sequence()
            self.verify_rendered_translations()
            failed = [name for name, check in self.output["checks"].items() if not check["passed"]]
            if failed:
                raise AssertionError("Device checks failed: " + ", ".join(failed))
        except Exception as error:
            self.output["failure"] = {"type": type(error).__name__, "message": str(error)}
            raise
        finally:
            self.save()
            self.client.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--phase", default="after")
    parser.add_argument("--output", default="build/pipeline-device-after.json")
    parser.add_argument("--serial", default="127.0.0.1:5555")
    parser.add_argument("--roi", nargs=4, type=int, default=[153, 428, 925, 478])
    parser.add_argument("--width", type=int, default=1600)
    parser.add_argument("--height", type=int, default=900)
    parser.add_argument("--subtitle-sp", type=float, default=24)
    parser.add_argument("--timeout", type=float, default=30)
    parser.add_argument("--samples-file", help="JSON list of fresh public text when rerunning after a previous cached run")
    parser.add_argument("--resume", help="Resume retained measurements without relabeling cached reruns as fresh calls")
    parser.add_argument("--checks-only", action="store_true", help="Run A/B/C after retained sample measurements")
    parser.add_argument("--fresh-a", default="我不去。")
    parser.add_argument("--fresh-b", default="明天去。")
    parser.add_argument("--rapid", action="store_true", help="Also test four source changes at 800ms intervals")
    parser.add_argument("--source", default="zh")
    parser.add_argument("--profile", default="game")
    parser.add_argument("--mode", default="fast")
    Benchmark(parser.parse_args()).execute()


if __name__ == "__main__":
    main()
