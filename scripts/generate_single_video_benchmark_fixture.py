"""Generate the synthetic MP4 and matching SRT for SingleVideoBatchBenchmarkTest."""

import argparse
import hashlib
import json
import subprocess
from pathlib import Path


def srt_time(milliseconds: int) -> str:
    seconds, remainder = divmod(milliseconds, 1000)
    minutes, second = divmod(seconds, 60)
    hours, minute = divmod(minutes, 60)
    return f"{hours:02}:{minute:02}:{second:02},{remainder:03}"


def generate(output: Path, cues: int) -> None:
    output.mkdir(parents=True, exist_ok=False)
    video = output / "single-EP01.mp4"
    sidecar = video.with_suffix(".srt")
    duration_seconds = (cues * 425 + 999) // 1000
    subprocess.run(
        [
            "ffmpeg", "-hide_banner", "-loglevel", "error", "-f", "lavfi",
            "-i", "color=c=black:s=160x90:r=1", "-t", str(duration_seconds),
            "-c:v", "libx264", "-pix_fmt", "yuv420p", "-movflags", "+faststart", str(video),
        ],
        check=True,
    )
    subtitles = []
    golden = hashlib.sha256()
    for number in range(1, cues + 1):
        start = (number - 1) * 425
        end = start + 375
        source = f"这是第{number}句测试台词"
        draft = f"Bản dịch thử số {number}."
        polished = f"Bản hiệu đính thử số {number}."
        # The short duration exercises the normal editorial stage for sidecar cues.
        subtitles.append(f"{number}\n{srt_time(start)} --> {srt_time(end)}\n{source}\n\n")
        golden.update(
            f"sub-{number}|{start}|{end}|{source}|{draft}|{polished}|{polished}|true\n".encode()
        )
    sidecar.write_text("".join(subtitles), encoding="utf-8", newline="\n")
    metadata = json.loads(subprocess.check_output(
        ["ffprobe", "-v", "error", "-select_streams", "v:0",
         "-show_entries", "stream=width,height:format=duration", "-of", "json", str(video)]
    ))
    stream = metadata["streams"][0]
    (output / "fixture.json").write_text(json.dumps({
        "cueCount": cues,
        "durationMs": round(float(metadata["format"]["duration"]) * 1000),
        "width": stream["width"],
        "height": stream["height"],
        "videoSha256": hashlib.sha256(video.read_bytes()).hexdigest(),
        "sidecarSha256": hashlib.sha256(sidecar.read_bytes()).hexdigest(),
        "timelineSha256": golden.hexdigest(),
    }, indent=2) + "\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path, help="new fixture directory")
    parser.add_argument("--cues", type=int, default=4000)
    args = parser.parse_args()
    if not 1 <= args.cues <= 20000:
        parser.error("--cues must be between 1 and 20000")
    generate(args.output, args.cues)


if __name__ == "__main__":
    main()
