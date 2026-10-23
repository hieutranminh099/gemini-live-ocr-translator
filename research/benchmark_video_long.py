"""Bounded decode memory on a generated 2-hour, 1-fps clip.

Recognition is deliberately deterministic: this tests file/PTS/frame memory,
not 2 hours of OCR accuracy, ASR quality, translation or cloud throughput.
"""
import asyncio
import json
from pathlib import Path
import time
import psutil
from PIL import Image,ImageDraw,ImageFont
from app.video_media import discover_tools,probe_video,run_tool
from app.video_ocr import ocr_video,OcrObservation

ROOT=Path(__file__).resolve().parents[1]


async def main():
    directory=ROOT/'build/video-long-v170';directory.mkdir(exist_ok=True)
    tools=discover_tools([ROOT/'.tools/video-runtime'])
    image=Image.new('RGB',(320,180),'#14242c')
    ImageDraw.Draw(image).text((90,125),'你好世界',font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',22),fill='white')
    image.save(directory/'source.png')
    source=directory/'long-static.mp4'
    if not source.exists():
        await run_tool([tools.ffmpeg,'-hide_banner','-loglevel','error','-nostdin','-loop','1','-framerate','1','-i',directory/'source.png',
            '-t','7200','-c:v','libx264','-preset','ultrafast','-pix_fmt','yuv420p','-threads','1',source],timeout=300)
    metadata=await probe_video(source,tools)
    assert metadata.duration_ms==7200000
    own=psutil.Process();baseline=own.memory_info().rss;peak=baseline;tree_peak=baseline;done=asyncio.Event()
    async def monitor():
        nonlocal peak,tree_peak
        while not done.is_set():
            peak=max(peak,own.memory_info().rss)
            total=own.memory_info().rss
            for child in own.children(recursive=True):
                try:total+=child.memory_info().rss
                except psutil.Error:pass
            tree_peak=max(tree_peak,total)
            await asyncio.sleep(.05)
    task=asyncio.create_task(monitor());count=0
    def recognize(frame):
        nonlocal count
        count+=1
        return OcrObservation('你好世界',.99)
    started=time.monotonic()
    try:segments=await ocr_video(source,tools,recognizer=recognize,roi=(0,.6,1,1),sample_ms=1000)
    finally:done.set();await task
    elapsed=(time.monotonic()-started)*1000
    assert len(segments)==1 and segments[0].start_ms==0 and segments[0].end_ms==7200000
    report=dict(kind='CONTROLLED_LONG_DECODE_NOT_OCR_QUALITY',source_duration_ms=metadata.duration_ms,source_fps=metadata.fps,
        source_bytes=source.stat().st_size,elapsed_ms=round(elapsed,2),recognizer_calls=count,segments=[s.as_dict()for s in segments],
        process_baseline_mib=round(baseline/1024**2,2),process_peak_mib=round(peak/1024**2,2),
        process_increase_mib=round((peak-baseline)/1024**2,2),parent_plus_children_peak_mib=round(tree_peak/1024**2,2),
        google_calls=0,passed=True,limitations=['Two-hour static generated video at 1fps, not a real movie or 20GiB disk benchmark.',
            'Deterministic recognition; measures incremental decoding and timestamps, not OCR/ASR quality or Gemini.',
            'RSS is OS working-set sampling, not a hard process memory limit.'])
    (ROOT/'docs/results/video-long-v170.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(report,ensure_ascii=False),flush=True)


if __name__=='__main__':asyncio.run(main())
