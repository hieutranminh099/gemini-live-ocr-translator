#!/usr/bin/env bash
set -euo pipefail

baseline=$1
candidate=$2
results=$3
fixture=$4
: "${GEMINI_BENCHMARK_API_KEY:?Missing benchmark credential}"
mkdir -p "$results"

report_failure() {
  local package=$1 report=$2
  grep -oE 'kotlinx\.coroutines\.TimeoutCancellationException|java\.lang\.(AssertionError|IllegalArgumentException|IllegalStateException|SecurityException)' "$report" | sort -u || true
  grep -E 'Batch did not reach review|Expected actual OCR|Expected real Gemini|Benchmark checkpoint did not advance|FAILURES|Tests run:' "$report" || true
  adb exec-out run-as "$package" cat files/benchmark-progress.jsonl 2>/dev/null | tail -18 || true
}

run_variant() {
  local variant=$1 run=$2
  local package="com.gemini.ocr.$variant"
  local test_package="$package.test"
  adb shell am force-stop "$package"
  adb shell pm clear "$package" > /dev/null
  adb shell run-as "$package" mkdir -p files
  adb shell -T run-as "$package" sh -c '"cat > files/benchmark.mp4"' < "$fixture"
  printf '%s' "$GEMINI_BENCHMARK_API_KEY" | adb shell -T run-as "$package" sh -c '"umask 077; cat > files/benchmark-key"'
  local report="$results/$variant-$run.instrumentation"
  if ! timeout 9m adb shell am instrument -w -r \
    -e class com.gemini.ocr.batch.SingleVideoGeminiBenchmarkTest \
    -e singleVideoGeminiBenchmark true "$test_package/androidx.test.runner.AndroidJUnitRunner" > "$report"; then
    echo "Instrumentation failed for $variant, run $run" >&2
    report_failure "$package" "$report"
    return 1
  fi
  # Success requires both the JUnit result and the report from a completed app run.
  if ! grep -Fq 'OK (1 test)' "$report"; then
    report_failure "$package" "$report"
    return 1
  fi
  adb exec-out run-as "$package" cat files/benchmark-result.json > "$results/$variant-$run.json"
  printf '%s run %s completed\n' "$variant" "$run"
  cat "$results/$variant-$run.json"
  printf '\n'
  adb shell pm clear "$package" > /dev/null
  sleep 20
}

for variant in perfbase perfnext; do
  checkout=$baseline
  if [[ "$variant" == perfnext ]]; then checkout=$candidate; fi
  adb install "$checkout/android-app/app/build/outputs/apk/debug/app-debug.apk"
  adb install "$checkout/android-app/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
done
trap 'adb shell pm clear com.gemini.ocr.perfbase >/dev/null 2>&1 || true; adb shell pm clear com.gemini.ocr.perfnext >/dev/null 2>&1 || true' EXIT
for run in 1 2 3; do
  if [[ "$run" == 2 ]]; then
    run_variant perfnext "$run"
    run_variant perfbase "$run"
  else
    run_variant perfbase "$run"
    run_variant perfnext "$run"
  fi
done

python3 - "$results" <<'PY'
import json
import statistics
import sys
from pathlib import Path

folder = Path(sys.argv[1])
base, candidate = ( [json.loads((folder / f"{variant}-{run}.json").read_text()) for run in (1, 2, 3)]
                    for variant in ("perfbase", "perfnext"))
assert len({row["timeline_sha256"] for row in base + candidate}) == 1, "OCR timelines diverged"
base_ms = statistics.median(row["total_ms"] for row in base)
candidate_ms = statistics.median(row["total_ms"] for row in candidate)
speedup = (base_ms - candidate_ms) / base_ms
result = {"baseline_median_ms": base_ms, "candidate_median_ms": candidate_ms,
          "time_reduction_percent": 100 * speedup, "baseline": base, "candidate": candidate}
(folder / "summary.json").write_text(json.dumps(result, indent=2) + "\n")
print(json.dumps(result, indent=2))
if candidate_ms >= base_ms:
    raise SystemExit("Candidate did not reduce median wall time")
PY
