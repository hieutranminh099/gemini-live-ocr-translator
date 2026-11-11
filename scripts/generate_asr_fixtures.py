"""Create video witnesses from two public AISHELL Mandarin recordings; never invent ASR output."""

import argparse
import hashlib
import json
from pathlib import Path
import subprocess


REFERENCES = (
    ("BAC009S0764W0121.wav", "甚至出现交易几乎停滞的情况"),
    ("BAC009S0916W0489.wav", "湖北一公司以员工名义贷款数十员工负债千万"),
)
SOURCE_PREFIX = "https://isv-data.oss-cn-hangzhou.aliyuncs.com/ics/MaaS/ASR/test_audio/"
ANNOTATION = "https://github.com/FunAudioLLM/SenseVoice/blob/586bbdb2b052f4fe19322581825228b6810a9fb3/README.md"


def generate(ffmpeg: Path, ffprobe: Path, first: Path, second: Path, out: Path, font: Path | None) -> None:
    out.mkdir(parents=True, exist_ok=False)
    sources = (first.resolve(strict=True), second.resolve(strict=True))
    assert all(path.name == name for path, (name, _) in zip(sources, REFERENCES)), "Use the named public annotation witnesses"

    def duration(source: Path) -> float:
        result = subprocess.check_output([str(ffprobe), "-v", "error", "-show_entries", "format=duration",
            "-of", "default=noprint_wrappers=1:nokey=1", str(source)], text=True)
        return float(result.strip())

    durations = tuple(duration(path) for path in sources)
    provenance = [dict(name=name, url=SOURCE_PREFIX + name, reference=text, annotation_url=ANNOTATION,
        sha256=hashlib.sha256(path.read_bytes()).hexdigest(), bytes=path.stat().st_size,
        source_duration_ms=round(seconds * 1000))
        for path, seconds, (name, text) in zip(sources, durations, REFERENCES)]
    fixtures = []

    def run(*args: str) -> None:
        subprocess.run([str(ffmpeg), "-nostdin", "-hide_banner", "-loglevel", "error", *args], cwd=out, check=True)

    def clip(name: str, total: float, audio_args: list[str], audio_filter: str | None,
             utterances: list[dict], rate: int = 48000, video_filter: str | None = None) -> None:
        size, fps = ("640x360", 30) if video_filter else ("320x180", 25)
        args = ["-f", "lavfi", "-i", f"color=black:s={size}:r={fps}:d={total:.6f}", *audio_args]
        if audio_filter:
            args += ["-filter_complex", audio_filter, "-map", "0:v:0", "-map", "[audio]"]
        else:
            args += ["-map", "0:v:0", "-map", "1:a:0"]
        if video_filter:
            args += ["-vf", video_filter]
        run(*args, "-t", f"{total:.6f}", "-c:v", "libx264", "-threads", "2", "-pix_fmt", "yuv420p",
            "-c:a", "aac", "-ar", str(rate), "-ac", "2", "-b:a", "128k", "-movflags", "+faststart", name)
        fixtures.append(dict(name=name, duration_ms=round(total * 1000), utterances=utterances,
            source_sha256=hashlib.sha256((out / name).read_bytes()).hexdigest(),
            audio_rate=rate, audio_channels=2, contains_visible_subtitles=video_filter is not None))

    def utterance(index: int, start: float) -> dict:
        return dict(reference=REFERENCES[index][1], start_ms=round(start * 1000),
            end_ms=round((start + durations[index]) * 1000), speaker_fixture=REFERENCES[index][0])

    lead = .75
    for index in range(2):
        clip(f"mandarin-{index + 1}.mp4", durations[index] + 1.5, ["-i", str(sources[index])],
            "[1:a]adelay=750:all=1,apad[audio]", [utterance(index, lead)], rate=48000 if index == 0 else 44100)

    second_start = lead + durations[0] + 1.25
    concat = f"[1:a]aformat=sample_rates=16000:channel_layouts=mono,adelay=750:all=1[first];" \
             f"[2:a]aformat=sample_rates=16000:channel_layouts=mono,adelay={round(second_start * 1000)}:all=1[second];" \
             "[first][second]amix=inputs=2:duration=longest:normalize=0,apad[audio]"
    clip("two-speaker.mp4", second_start + durations[1] + .75,
        ["-i", str(sources[0]), "-i", str(sources[1])], concat, [utterance(0, lead), utterance(1, second_start)])

    second_start = 1 + durations[0] + 30
    concat = f"[1:a]aformat=sample_rates=16000:channel_layouts=mono,adelay=1000:all=1[first];" \
             f"[2:a]aformat=sample_rates=16000:channel_layouts=mono,adelay={round(second_start * 1000)}:all=1[second];" \
             "[first][second]amix=inputs=2:duration=longest:normalize=0,apad[audio]"
    clip("long-silence.mp4", second_start + durations[1] + .75,
        ["-i", str(sources[0]), "-i", str(sources[1])], concat, [utterance(0, 1), utterance(1, second_start)])

    total = durations[0] + 1.5
    noise = ["-f", "lavfi", "-i", f"anoisesrc=color=pink:amplitude=0.015:sample_rate=16000:duration={total:.6f}:seed=187",
             "-f", "lavfi", "-i", f"aevalsrc=0.012*(sin(2*PI*220*t)+0.5*sin(2*PI*330*t)):s=16000:d={total:.6f}"]
    clip("mandarin-noisy.mp4", total, ["-i", str(sources[0]), *noise],
        "[1:a]adelay=750:all=1,apad[voice];[voice][2:a][3:a]amix=inputs=3:duration=first:normalize=0[audio]",
        [utterance(0, lead)])
    clip("noise-music.mp4", 8, ["-f", "lavfi", "-i", "anoisesrc=color=pink:amplitude=0.08:sample_rate=16000:duration=8:seed=187",
        "-f", "lavfi", "-i", "aevalsrc=0.12*(sin(2*PI*220*t)+0.5*sin(2*PI*330*t)):s=16000:d=8"],
        "[1:a][2:a]amix=inputs=2:normalize=0[audio]", [])
    clip("silence.mp4", 8, ["-f", "lavfi", "-i", "anullsrc=sample_rate=16000:channel_layout=mono"], None, [])

    def stamp(ms: int) -> str:
        return f"{ms // 3600000:02}:{ms // 60000 % 60:02}:{ms // 1000 % 60:02},{ms % 1000:03}"
    cue = utterance(0, lead)
    (out / "mandarin-1.srt").write_text(f"1\n{stamp(cue['start_ms'])} --> {stamp(cue['end_ms'])}\n{REFERENCES[0][1]}\n", encoding="utf-8")
    run("-i", "mandarin-1.mp4", "-i", "mandarin-1.srt", "-c:v", "copy", "-c:a", "copy", "-c:s", "mov_text",
        "-metadata:s:s:0", "language=zho", "mandarin-embedded.mp4")
    if font is not None:
        (out / "mandarin-1.txt").write_text(REFERENCES[0][1], encoding="utf-8")
        font_filter = font.resolve(strict=True).as_posix().replace(":", r"\:")
        vf = f"drawtext=fontfile='{font_filter}':textfile=mandarin-1.txt:fontsize=33:fontcolor=white:x=(w-tw)/2:y=280:enable='between(t,{lead},{lead + durations[0]})'"
        clip("mandarin-hardsub.mp4", durations[0] + 1.5, ["-i", str(sources[0])],
            "[1:a]adelay=750:all=1,apad[audio]", [cue], video_filter=vf)
    manifest = dict(schema=1, provenance=provenance, fixtures=fixtures,
        scope="Actual ASR from public Mandarin speech, AAC at 48/44.1kHz stereo; synthetic noise/tonal bed and silence; no user media or cloud",
        annotation_note="Utterance envelope timestamps derive from published WAV positions, not phoneme-level forced alignment.",
        background_note="noise-music is generated pink noise and tonal accompaniment, not a recording of commercial music.")
    (out / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(dict(output=str(out), fixtures=len(fixtures), sources=provenance), ensure_ascii=False))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("ffmpeg", "ffprobe", "first_wav", "second_wav", "output"):
        parser.add_argument(name, type=Path)
    parser.add_argument("--font", type=Path, help="Optional Chinese-capable font for the OCR witness")
    args = parser.parse_args()
    generate(args.ffmpeg.resolve(strict=True), args.ffprobe.resolve(strict=True), args.first_wav,
        args.second_wav, args.output.resolve(), args.font)
