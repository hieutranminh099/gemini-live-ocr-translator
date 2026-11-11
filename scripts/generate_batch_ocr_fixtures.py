"""Build synthetic 640×360 Chinese subtitle fixtures for the Android native test."""

import argparse
from pathlib import Path
import subprocess


def generate(ffmpeg: Path, font: Path, out: Path) -> None:
    out.mkdir(parents=True, exist_ok=False)
    first, second = "你好，我是小明", "我没有两本书"
    (out / "first.txt").write_text(first, encoding="utf-8")
    (out / "second.txt").write_text(second, encoding="utf-8")
    (out / "traditional.txt").write_text("我沒有兩本書", encoding="utf-8")
    (out / "source.srt").write_text(
        f"1\n00:00:00,500 --> 00:00:01,750\n{first}\n\n"
        f"2\n00:00:02,000 --> 00:00:03,250\n{second}\n", encoding="utf-8"
    )

    def run(*args: str) -> None:
        subprocess.run([str(ffmpeg), "-nostdin", "-hide_banner", "-loglevel", "error", *args], cwd=out, check=True)

    font_filter = font.as_posix().replace(":", r"\:")

    def text(file: str, y: int, start: float, end: float) -> str:
        return (f"drawtext=fontfile='{font_filter}':textfile={file}:fontsize=33:fontcolor=white:"
                f"x=(w-tw)/2:y={y}:enable='gte(t,{start})*lt(t,{end})'")

    def clip(name: str, *filters: str) -> None:
        vf = ["-vf", ",".join(filters)] if filters else []
        run("-f", "lavfi", "-i", "color=black:s=640x360:r=30:d=4", *vf,
            "-c:v", "libx264", "-threads", "2", "-pix_fmt", "yuv420p", "-g", "120", "-movflags", "+faststart", name)

    clip("chinese-4s.mp4", text("first.txt", 280, .5, 1.75), text("second.txt", 280, 2, 3.25))
    clip("multiline-4s.mp4", text("first.txt", 238, .5, 3), text("second.txt", 281, .5, 3))
    clip("traditional-4s.mp4", text("first.txt", 280, .5, 1.75), text("traditional.txt", 280, 2, 3.25))
    clip("blank-4s.mp4")
    run("-stream_loop", "44", "-i", "chinese-4s.mp4", "-t", "180", "-c", "copy", "chinese-180s.mp4")
    run("-i", "blank-4s.mp4", "-i", "source.srt", "-c:v", "copy", "-c:s", "mov_text",
        "-metadata:s:s:0", "language=zho", "embedded-4s.mp4")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("ffmpeg", type=Path)
    parser.add_argument("font", type=Path, help="Chinese-capable system font")
    parser.add_argument("output", type=Path, help="New directory for generated fixtures")
    args = parser.parse_args()
    generate(args.ffmpeg.resolve(strict=True), args.font.resolve(strict=True), args.output.resolve())
