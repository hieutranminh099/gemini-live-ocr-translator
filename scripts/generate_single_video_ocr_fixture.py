"""Create a public, synthetic video for the GitHub Actions OCR/Gemini benchmark."""

import argparse
import subprocess
from pathlib import Path


def stamp(tenths: int) -> str:
    seconds, fraction = divmod(tenths, 10)
    minutes, seconds = divmod(seconds, 60)
    return f"0:{minutes:02}:{seconds:02}.{fraction}0"


parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("output", type=Path)
parser.add_argument("--long", action="store_true", help="Generate a 36m25s clip with captions around the beginning, middle and end")
args = parser.parse_args()
output = args.output.resolve()
output.parent.mkdir(parents=True, exist_ok=True)
subtitles = """[Script Info]
ScriptType: v4.00+
PlayResX: 640
PlayResY: 360
[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Default,Noto Sans CJK SC,32,&H00FFFFFF,&H00FFFFFF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,0,0,2,14,14,32,1
[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
"""
subjects = ["小明", "小红", "老师", "医生", "司机", "小雨"]
places = ["公园看花", "商店买书", "车站等车", "图书馆看书", "学校接朋友", "电影院看电影"]
if args.long:
    cues = [(2, 25, "小明今天去公园看花。"), (10_802, 10_825, "老师今天去车站等车。"),
            (21_822, 21_847, "小雨今天去电影院看电影。")]
    rate, duration = 10, 2185
else:
    cues = [(20*i + 2, 20*i + 17, f"{subject}今天去{place}。")
            for i, (subject, place) in enumerate(zip(subjects, places))]
    rate, duration = 25, 2 * len(cues)
for start, end, line in cues:
    subtitles += f"Dialogue: 0,{stamp(start)},{stamp(end)},Default,,0,0,0,,{line}\n"
ass = output.parent / "benchmark.ass"
ass.write_text(subtitles, encoding="utf-8")
subprocess.run(["ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i",
                f"color=c=black:s=640x360:r={rate}:d={duration}", "-vf", "ass=benchmark.ass", "-c:v", "libx264",
                "-pix_fmt", "yuv420p", "-threads", "2", "-movflags", "+faststart", output.name],
               cwd=output.parent, check=True)
