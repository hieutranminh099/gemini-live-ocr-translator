"""Measure overlay disappearance in controlled 1600x900 Android screen recordings.

Requires PyAV and Pillow in the analysis environment only. Does not call AI or control Android.
The defaults are for the neutral TestSceneActivity and the user's existing OCR ROI in this run.
Counts encoded frames/presentation transitions; not a fixed-FPS or general movie detector.
"""
import argparse
import json
from pathlib import Path
import av


def rectangle(value):
    result=tuple(int(x) for x in value.split(','))
    if len(result)!=4 or result[2]<=result[0] or result[3]<=result[1]:
        raise argparse.ArgumentTypeError('Use left,top,right,bottom')
    return result


def analyze(path, caption, controls=(0,80,110,250)):
    samples=[]
    with av.open(str(path)) as recording:
        for frame in recording.decode(video=0):
            image=frame.to_image().convert('RGB')
            mint=sum(70<r<155 and g>185 and 145<b<240 and g-r>60
                     for r,g,b in image.crop(controls).get_flattened_data())
            dark=sum(max(pixel)<38 for pixel in image.crop(caption).get_flattened_data())
            # A damaged/blank recording frame cannot establish whether either window
            # disappeared. Keep it in raw counts and disclose it separately.
            points=[image.getpixel(p) for p in [(1500,100),(1500,600),(1000,100)]]
            valid=sum(20<r<50 and 45<g<85 and 60<b<110 for r,g,b in points)>=2
            samples.append(dict(time_s=round(float(frame.time),6),scene_present=valid,
                                controls_visible=mint>1500,caption_visible=dark>12000))
    if not samples: raise ValueError('Recording has no decoded video frames')
    valid=[v for v in samples if v['scene_present']]
    return dict(file=Path(path).name,decoded_frames=len(samples),first_frame_s=samples[0]['time_s'],
                last_frame_s=samples[-1]['time_s'],
                valid_scene_frames=len(valid),unclassified_frames=len(samples)-len(valid),
                unclassified_times_s=[v['time_s'] for v in samples if not v['scene_present']],
                raw_controls_hidden_frames=sum(not v['controls_visible'] for v in samples),
                raw_caption_hidden_frames=sum(not v['caption_visible'] for v in samples),
                controls_hidden_frames=sum(not v['controls_visible'] for v in valid),
                caption_hidden_frames=sum(not v['caption_visible'] for v in valid),
                controls_visibility_transitions=sum(a['controls_visible']!=b['controls_visible'] for a,b in zip(valid,valid[1:])),
                caption_visibility_transitions=sum(a['caption_visible']!=b['caption_visible'] for a,b in zip(valid,valid[1:])),
                controls_disappearances=sum(a['controls_visible'] and not b['controls_visible'] for a,b in zip(valid,valid[1:])),
                caption_disappearances=sum(a['caption_visible'] and not b['caption_visible'] for a,b in zip(valid,valid[1:])),
                controls_rect=controls,caption_rect=caption)


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('before',type=Path);p.add_argument('after',type=Path)
    p.add_argument('--before-caption',type=rectangle,default=(110,435,928,520))
    p.add_argument('--after-caption',type=rectangle,default=(110,300,928,434))
    p.add_argument('--output',type=Path,required=True)
    args=p.parse_args()
    result={'scene':'Neutral native Android TestSceneActivity; same selected OCR ROI, Chinese source',
            'method':'Classify mint controls/dark caption in encoded frames with the known blue test background present at >=2/3 reference points; disclose all raw/unclassified counts. VFR, not fixed-FPS or latency percentiles.',
            'before':analyze(args.before,args.before_caption),'after':analyze(args.after,args.after_caption)}
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(result,ensure_ascii=False))


if __name__=='__main__':main()
