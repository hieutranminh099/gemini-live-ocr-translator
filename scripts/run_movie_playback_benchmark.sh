#!/usr/bin/env bash
set -euo pipefail

baseline=$1
candidate=$2
results=$3
package=com.gemini.ocr.playbacktest
reports="files/playback-bench"
fixture="$candidate/scripts/fixtures/movie-playback-benchmark.mp4"
mkdir -p "$results"

run_test() {
  local variant=$1 method=$2
  local log="$results/$variant-$method.instrumentation"
  if ! timeout 5m adb shell am instrument -w -r -e variant "$variant" -e benchmarkInternalFiles true \
    -e class "com.gemini.ocr.movie.MoviePlaybackBenchmarkTest#$method" \
    "$package.test/androidx.test.runner.AndroidJUnitRunner" > "$log" || ! grep -Fq 'OK (1 test)' "$log"; then
    tail -70 "$log"
    for file in "$variant.json" rename.json navigation-failure.png navigation-failure.xml; do
      if adb shell run-as "$package" test -s "$reports/$file"; then
        collect "$file" "$variant-failure-$file"
      fi
    done
    return 1
  fi
}

collect() {
  adb exec-out run-as "$package" cat "$reports/$1" > "$results/$2"
  test -s "$results/$2"
}

run_variant() {
  local checkout=$1 variant=$2 failed=0
  local apks="$checkout/android-app/app/build/outputs/apk"
  adb install -r "$apks/debug/app-debug.apk" || return 1
  adb install -r "$apks/androidTest/debug/app-debug-androidTest.apk" || return 1
  adb shell pm clear "$package" >/dev/null || return 1
  adb shell run-as "$package" mkdir -p files || return 1
  adb shell -T run-as "$package" sh -c '"cat > files/playback-input.mp4"' < "$fixture" || return 1
  { run_test "$variant" measuredPlayback && collect "$variant.json" "$variant-playback.json"; } || failed=1
  { run_test "$variant" renameAndLatestEpisode && collect rename.json "$variant-navigation.json" &&
    collect library.png "$variant-library.png" && collect player.png "$variant-player.png"; } || failed=1
  { run_test "$variant" coldLibraryCatalog && collect cold-library.json "$variant-cold-library.json"; } || failed=1
  return "$failed"
}

failed=0
run_variant "$baseline" baseline || failed=1
run_variant "$candidate" candidate || failed=1
if (( failed )); then
  exit "$failed"
fi

python3 - "$results" <<'PY'
import json
import sys
from pathlib import Path
from statistics import median

root = Path(sys.argv[1])
summary = {}
fingerprints = []
for variant in ("baseline", "candidate"):
    playback = json.loads((root / f"{variant}-playback.json").read_text())
    navigation = json.loads((root / f"{variant}-navigation.json").read_text())
    catalog = json.loads((root / f"{variant}-cold-library.json").read_text())
    runs = playback["runs"]
    assert len(runs) == 5 and playback["completed"] and navigation["completed"]
    seeks = [seek for trial in runs for seek in trial["seeks"]]
    assert len(seeks) == 10 and all(seek["commands"] == 1 for seek in seeks)
    assert catalog["catalog_movies"] == 8 and catalog["cues_per_movie"] == 5065
    fingerprints.append(playback["fixture_sha256"])
    summary[variant] = {
        "first_frame_median_ms": median(row["open_ns"] for row in runs) / 1_000_000,
        "seek_median_ms": median(seek["release_to_frame_ns"] for seek in seeks) / 1_000_000,
        "episode_change_median_ms": median(switch["ns"] for row in runs for switch in row["switches"]) / 1_000_000,
        "subtitle_callbacks_per_2s_median": median(row["subtitle_update_callbacks"] for row in runs),
        "playing_cpu_ms_per_2s_median": median(row["playing_cpu_ms"] for row in runs),
        "window_frames_over_16ms": sum(row["window_frames_over_16ms"] for row in runs),
        "window_frames": sum(row["window_frames"] for row in runs),
        "dropped_video_frames": playback["dropped_video_frames"],
        "catalog_main_thread_ms": catalog["main_thread_ns"] / 1_000_000,
        "catalog_loaded_ms": catalog["loaded_ns"] / 1_000_000,
    }
assert len(set(fingerprints)) == 1
(root / "movie-experience-summary.json").write_text(json.dumps(summary, indent=2) + "\n")
print(json.dumps(summary, ensure_ascii=False))
PY
