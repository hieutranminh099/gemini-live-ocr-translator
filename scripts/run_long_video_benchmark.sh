#!/usr/bin/env bash
set -euo pipefail

baseline=$1
candidate=$2
results=$3
fixture="$candidate/scripts/fixtures/long-video-ocr-benchmark.mp4"
mkdir -p "$results"

run_variant() {
  local checkout=$1 package=$2 name=$3
  local apks="$checkout/android-app/app/build/outputs/apk"
  adb install "$apks/debug/app-debug.apk"
  adb install "$apks/androidTest/debug/app-debug-androidTest.apk"
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

run_variant "$baseline" com.gemini.ocr.codexbench long-baseline
run_variant "$candidate" com.gemini.ocr.batchunified186 long-candidate

python3 - "$results" <<'PY'
import json
import sys
from pathlib import Path

root = Path(sys.argv[1])
base, candidate = (json.loads((root / f"long-{name}.json").read_text()) for name in ("baseline", "candidate"))
assert base["timeline_sha256"] == candidate["timeline_sha256"], "Native OCR text or timestamps changed"
assert base["duration_ms"] == candidate["duration_ms"] == 2_185_000
assert all(2_163_150 <= report["pause_ms"] < report["duration_ms"] for report in (base, candidate))
PY
