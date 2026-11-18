"""Create a public, synthetic video for the GitHub Actions OCR/Gemini benchmark."""

import subprocess
import sys
from pathlib import Path


def stamp(tenths: int) -> str:
    seconds, fraction = divmod(tenths, 10)
    minutes, seconds = divmod(seconds, 60)
    return f"0:{minutes:02}:{seconds:02}.{fraction}0"


output = Path(sys.argv[1]).resolve()
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
subjects = ["小明", "小红", "老师", "医生", "司机", "小雨", "小林", "记者"]
places = ["公园看花", "商店买书", "车站等车", "图书馆看书", "学校接朋友", "电影院看电影"]
for i, (subject, place) in enumerate((a, b) for b in places for a in subjects):
    subtitles += f"Dialogue: 0,{stamp(20*i + 2)},{stamp(20*i + 17)},Default,,0,0,0,,{subject}今天去{place}。\n"
ass = output.parent / "benchmark.ass"
ass.write_text(subtitles, encoding="utf-8")
subprocess.run(["ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i",
                "color=c=black:s=640x360:r=25:d=96", "-vf", "ass=benchmark.ass", "-c:v", "libx264",
                "-pix_fmt", "yuv420p", "-threads", "2", "-movflags", "+faststart", output.name],
               cwd=output.parent, check=True)
