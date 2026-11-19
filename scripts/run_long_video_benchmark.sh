#!/usr/bin/env bash
set -euo pipefail

baseline=$1
candidate=$2
results=$3
trials=${4:-1}
fixture="$candidate/scripts/fixtures/long-video-ocr-benchmark.mp4"
mkdir -p "$results"

install_variant() {
  local checkout=$1
  local apks="$checkout/android-app/app/build/outputs/apk"
  adb install "$apks/debug/app-debug.apk"
  adb install "$apks/androidTest/debug/app-debug-androidTest.apk"
}

run_variant() {
  local package=$1 name=$2
  adb shell pm clear "$package" >/dev/null
  adb shell run-as "$package" mkdir -p files
  adb shell -T run-as "$package" sh -c '"cat > files/long-video-ocr-benchmark.mp4"' < "$fixture"
  local log="$results/$name.instrumentation"
  if ! timeout 18m adb shell am instrument -w -r \
    -e class com.gemini.ocr.batch.LongVideoBenchmarkTest -e longVideoBenchmark true \
    "$package.test/androidx.test.runner.AndroidJUnitRunner" > "$log" || ! grep -Fq 'OK (1 test)' "$log"; then
    tail -60 "$log"
    adb exec-out run-as "$package" cat files/long-video-progress.json || true
    return 1
  fi
  adb exec-out run-as "$package" cat files/long-video-result.json > "$results/$name.json"
  printf '%s\n' "$name"
  cat "$results/$name.json"
  printf '\n'
  adb shell pm clear "$package" >/dev/null
}

install_variant "$baseline"
install_variant "$candidate"
for ((trial = 1; trial <= trials; trial++)); do
  if ((trial % 2 == 1)); then
    run_variant com.gemini.ocr.codexbench "long-baseline-$trial"
    run_variant com.gemini.ocr.batchunified186 "long-candidate-$trial"
  else
    run_variant com.gemini.ocr.batchunified186 "long-candidate-$trial"
    run_variant com.gemini.ocr.codexbench "long-baseline-$trial"
  fi
done

python3 - "$results" "$trials" <<'PY'
import json
import statistics
import sys
from pathlib import Path

root = Path(sys.argv[1])
trials = range(1, int(sys.argv[2]) + 1)
base, candidate = (
    [json.loads((root / f"long-{name}-{index}.json").read_text()) for index in trials]
    for name in ("baseline", "candidate")
)
for field in ("timeline_sha256", "reviewed_cues_sha256", "request_sha256",
              "srt_sha256", "ass_sha256", "source_video_sha256"):
    assert len({report[field] for report in base + candidate}) == 1, f"Quality/identity check failed: {field}"
assert all(report["duration_ms"] == 2_185_000 for report in base + candidate)
assert all(2_163_150 <= report["pause_ms"] < report["duration_ms"] for report in base + candidate)
base_ms = statistics.median(report["wall_ms"] for report in base)
candidate_ms = statistics.median(report["wall_ms"] for report in candidate)
reduction = 100 * (1 - candidate_ms / base_ms)
summary = {"baseline_ms": base_ms, "candidate_ms": candidate_ms,
           "time_reduction_percent": reduction, "reached_35_percent": reduction >= 35}
(root / "long-summary.json").write_text(json.dumps(summary, indent=2) + "\n")
print(json.dumps(summary))
PY
