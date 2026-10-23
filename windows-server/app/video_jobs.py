"""Checkpointed local video projects. Source media and global TM are separate.

Only one video stage runs at a time. Live capture owns priority; heavy tools
cooperate with wait_ready and suspend while it blocks. No CLI fallback is used.
"""
from __future__ import annotations

import asyncio
from dataclasses import replace
import hashlib
import importlib.util
import json
import os
from pathlib import Path, PureWindowsPath
import secrets
import shutil
import sys
import time

from pydantic import BaseModel, ConfigDict, Field
from typing import Literal

from .models import TranslateRequest
from .provider import TranslationError
from .storage import normalize
from .translation_memory import MemoryEntry
from .video_media import (VideoMediaError, VideoSegment, SubtitleStyle, discover_tools,
    probe_video, extract_subtitle_stream, export_srt, export_ass, render_video, preview_frame, validate_roi,VideoMetadata)

MAX_VIDEO_BYTES = 20 * 1024**3
MAX_SEGMENTS = 20000
ALLOWED_EXTENSIONS = {'.mp4','.mkv','.mov','.webm','.avi','.m4v','.mts','.ts'}
ACTIVE = {'queued','extracting','translating','exporting','paused','waiting_live'}


class VideoOptions(BaseModel):
    model_config = ConfigDict(extra='forbid')
    source: Literal['zh','ja','ko','en'] = 'zh'
    method: Literal['auto','subtitle','ocr','speech'] = 'auto'
    stream_index: int | None = Field(default=None, ge=0)
    profile: Literal['game','manga','document','web','technical'] = 'game'
    mode: Literal['fast','smart','quality'] = 'fast'
    model: str | None = Field(default=None, max_length=100)
    use_memory: bool = True
    private: bool = True
    save_memory: bool = False
    roi: tuple[float,float,float,float] = (0.,.6,1.,.95)


def _atomic_json(path, value):
    temp = path.with_suffix(path.suffix+'.tmp')
    temp.write_text(json.dumps(value,ensure_ascii=False,separators=(',',':')),encoding='utf-8')
    temp.replace(path)


def _segments(rows, duration):
    if len(rows)>MAX_SEGMENTS:
        raise VideoMediaError('too_many_segments','Video vượt giới hạn 20.000 đoạn; hãy chia thành dự án nhỏ hơn.')
    result=[]
    seen=set()
    for row in rows:
        item = row if isinstance(row,VideoSegment) else VideoSegment(**{k:row.get(k,default) for k,default in
            [('id',''),('start_ms',0),('end_ms',0),('text',''),('translation',''),('language',''),('confidence',None),('source_type','SUBTITLE')]})
        if not isinstance(item.id,str) or not item.id or len(item.id)>80 or item.id in seen:
            raise VideoMediaError('invalid_timeline','ID phụ đề không hợp lệ hoặc trùng.')
        seen.add(item.id)
        if (not isinstance(item.start_ms,int) or not isinstance(item.end_ms,int) or
                not 0<=item.start_ms<item.end_ms<=duration+250):
            raise VideoMediaError('invalid_timeline','Thời gian phụ đề nằm ngoài video hoặc không hợp lệ.')
        if not isinstance(item.text,str) or not normalize(item.text) or len(item.text)>2000 or not isinstance(item.translation,str) or len(item.translation)>8000:
            raise VideoMediaError('invalid_timeline','Nội dung phụ đề rỗng hoặc quá dài.')
        result.append(item)
    result.sort(key=lambda item:(item.start_ms,item.end_ms,item.id))
    for previous,current in zip(result,result[1:]):
        if current.start_ms < previous.end_ms:
            raise VideoMediaError('overlapping_subtitles','Có đoạn phụ đề chồng thời gian. Hãy sửa hoặc chọn một luồng nguồn.')
    return result


class VideoJobService:
    def __init__(self,directory,manager,*,tools=None,recognizer_factory=None,speech_processor=None):
        self.directory=Path(directory).resolve()
        self.directory.mkdir(parents=True,exist_ok=True)
        self.manager=manager
        self.runtime_roots=[Path(getattr(sys,'_MEIPASS',Path(__file__).resolve().parents[2]))/'video-runtime',
            Path(__file__).resolve().parents[2]/'.tools/video-runtime',self.directory.parent/'runtime/video']
        self.tools=tools or discover_tools(self.runtime_roots)
        self.recognizer_factory=recognizer_factory
        self.speech_processor=speech_processor
        self.jobs={}
        self.locks={}
        # Admission and timeline mutations must be atomic across their first
        # filesystem await. The background runner never takes this lock, so
        # cancel/close can safely wait for it while holding admission.
        self.admission_lock=asyncio.Lock()
        self.task=None
        self.active_id=None
        self.cancel_event=asyncio.Event()
        self.paused=False
        self.closed=False
        self.tokens={}
        self.generation=0
        for path in sorted(self.directory.glob('*/project.json'))[-100:]:
            try:
                item=json.loads(path.read_text(encoding='utf-8'))
                ident=item['id']
                if path.parent!=self._path(ident) or not isinstance(item.get('metadata'),dict):
                    continue
                if item['status'] in ACTIVE:
                    item['status']='interrupted'
                    item['error']='Phiên trước đã dừng. Nhấn Khôi phục để tiếp tục từ checkpoint.'
                    _atomic_json(path,item)
                self.jobs[ident]=item
                self.tokens[ident]=secrets.token_urlsafe(32)
            except (OSError,ValueError,KeyError,TypeError):
                continue

    def _path(self,ident):
        if not isinstance(ident,str) or len(ident)!=24 or any(c not in '0123456789abcdef' for c in ident):
            raise VideoMediaError('project_not_found','Không tìm thấy dự án video.')
        path=(self.directory/ident).resolve()
        if path.parent!=self.directory:
            raise VideoMediaError('invalid_project','Đường dẫn dự án không hợp lệ.')
        return path

    def _job(self,ident):
        if ident not in self.jobs:
            raise VideoMediaError('project_not_found','Không tìm thấy dự án video.')
        return self.jobs[ident]

    async def _save(self,job):
        async with self.locks.setdefault(job['id'],asyncio.Lock()):
            job['updated_at']=time.time()
            await asyncio.to_thread(_atomic_json,self._path(job['id'])/'project.json',job)

    def _read_timeline(self,ident):
        path=self._path(ident)/'timeline.json'
        return json.loads(path.read_text(encoding='utf-8')) if path.exists() else []

    async def _write_timeline(self,job,rows):
        async with self.locks.setdefault(job['id'],asyncio.Lock()):
            await asyncio.to_thread(_atomic_json,self._path(job['id'])/'timeline.json',rows)
        job['total_segments']=len(rows)
        job['progress']['segments']=len(rows)
        job['progress']['translated']=sum(bool(row.get('translation','').strip()) for row in rows)
        await self._save(job)

    def _public(self,job):
        result={k:v for k,v in job.items() if k not in ('input','export_spec','namespace')}
        result['preview_url']=f"/api/video/files/{job['id']}/source?token={self.tokens[job['id']]}"
        result['outputs']=[dict(format=fmt,name=Path(name).name,
            url=f"/api/video/files/{job['id']}/{fmt}?token={self.tokens[job['id']]}") for fmt,name in job.get('outputs',{}).items()]
        return result

    def list_jobs(self):
        return [self._public(j) for j in sorted(self.jobs.values(),key=lambda j:j['created_at'],reverse=True)]

    async def detail(self,ident,*,offset=0,limit=200):
        job=self._job(ident)
        rows=await asyncio.to_thread(self._read_timeline,ident)
        return dict(self._public(job),segments=rows[offset:offset+limit],total_segments=len(rows),segment_offset=offset,segment_limit=limit)

    def capabilities(self):
        self.tools=self.tools or discover_tools(self.runtime_roots)
        ocr=bool(self.recognizer_factory or importlib.util.find_spec('rapidocr_onnxruntime'))
        model=self.speech_model()
        speech=bool(self.speech_processor or (importlib.util.find_spec('faster_whisper') and model))
        return dict(ffmpeg=self.tools is not None,ocr=ocr,speech=speech,live_active=self.manager.resources.active_live,
            memory_enabled=self.manager.settings().memory_enabled,limits=dict(max_video_bytes=MAX_VIDEO_BYTES,max_segments=MAX_SEGMENTS),
            ocr_languages=['zh','en']+[lang for lang,name in [('ja','japan')]
                if any((root/'ocr'/f'{name}_PP-OCRv4_rec_mobile.onnx').is_file() for root in self.runtime_roots)],speech_model=str(model.name) if model else None,
            ocr_unverified_languages=['ko'],
            message='Video tự nhường tài nguyên khi Live OCR hoạt động. Dùng API chính thức; dịch cần Internet, xử lý media chạy trên máy.')

    def speech_model(self):
        for root in self.runtime_roots:
            for name in ('whisper-base','whisper-tiny'):
                path=root/name
                if (path/'model.bin').is_file():
                    return path
        return None

    @staticmethod
    def _metadata(job):
        # as_dict also exposes computed UI fields such as video_stream_index.
        return VideoMetadata(**{key:value for key,value in job['metadata'].items()
            if key in VideoMetadata.__dataclass_fields__})

    async def import_upload(self,filename,reader):
        if self.tools is None:
            raise VideoMediaError('ffmpeg_missing','Chưa có FFmpeg/ffprobe. Chạy scripts/setup_video_tools.py rồi mở lại ứng dụng.')
        if len(self.jobs)>=100:
            raise VideoMediaError('project_limit','Đã có 100 dự án. Hãy xóa dự án không cần trong ứng dụng.')
        name=PureWindowsPath(str(filename).replace('/','\\')).name[:240]
        suffix=Path(name).suffix.lower()
        if suffix not in ALLOWED_EXTENSIONS:
            raise VideoMediaError('unsupported_video','Chọn MP4, MKV, MOV, WebM, AVI, M4V, MTS hoặc TS.')
        ident=secrets.token_hex(12)
        folder=self._path(ident)
        folder.mkdir()
        path=folder/('source'+suffix)
        size=0
        try:
            with path.open('wb') as output:
                while chunk:=await reader(1024*1024):
                    size+=len(chunk)
                    if size>MAX_VIDEO_BYTES or shutil.disk_usage(folder).free<1024**3:
                        raise VideoMediaError('upload_limit','Video quá lớn hoặc ổ đĩa còn dưới 1 GiB trống.')
                    await asyncio.to_thread(output.write,chunk)
            if not size:
                raise VideoMediaError('empty_video','Tệp video rỗng.')
            metadata=(await probe_video(path,self.tools)).as_dict()
            for stream in metadata['streams']:
                stream['codec_type']=stream['type'];stream['codec_name']=stream['codec']
            now=time.time()
            job=dict(id=ident,name=name,size_bytes=size,input=path.name,metadata=metadata,status='imported',
                options=VideoOptions().model_dump(),progress=dict(stage='imported',percent=0,segments=0,translated=0,cache_hits=0,new_memory=0,ai_calls=0),
                total_segments=0,extraction_complete=False,error=None,outputs={},created_at=now,updated_at=now)
            self.jobs[ident]=job;self.tokens[ident]=secrets.token_urlsafe(32)
            await self._save(job)
            return self._public(job)
        except BaseException:
            # This is only the newly allocated upload directory, never a user path.
            assert folder.parent==self.directory and folder.name==ident
            await asyncio.to_thread(shutil.rmtree,folder)
            self.jobs.pop(ident,None);self.tokens.pop(ident,None)
            raise

    async def _ready(self,job,stage):
        while True:
            if self.cancel_event.is_set() or self.closed:
                raise asyncio.CancelledError
            if self.paused:
                if job['status']!='paused':
                    job['status']='paused';await self._save(job)
                await asyncio.sleep(.1)
                continue
            resources=self.manager.resources.status()
            if resources['live_active'] or resources['live_waiters'] or resources['active_calls']>resources['active_video_calls']:
                if job['status']!='waiting_live':
                    job['status']='waiting_live';await self._save(job)
                await self.manager.resources.wait_background_ready()
                continue
            job['status']=stage;job['progress']['stage']=job.get('extraction_method',stage) if stage=='extracting' else stage
            return

    async def start(self,ident,options=None):
        async with self.admission_lock:
            return await self._start(ident,options)

    async def _start(self,ident,options=None):
        if self.closed:
            raise VideoMediaError('video_closed','Ứng dụng đang đóng. Mở lại ứng dụng để tiếp tục dự án.')
        job=self._job(ident)
        if self.task is not None and not self.task.done():
            raise VideoMediaError('video_busy','Đang xử lý một video. Hãy dừng tác vụ đó trước.')
        settings=self.manager.settings()
        if settings.provider!='api' or not getattr(self.manager.provider,'configured',False):
            raise VideoMediaError('api_required','Dịch video cần Gemini API chính thức đã cấu hình. Không dùng CLI.')
        opts=VideoOptions.model_validate(options or job['options'])
        validate_roi(opts.roi)
        if opts.model and opts.model!=getattr(settings,opts.mode+'_model'):
            raise VideoMediaError('model_mismatch','Chọn model của chế độ tương ứng trong Cài đặt AI trước khi chạy video.')
        if opts.private and opts.save_memory:
            raise VideoMediaError('private_memory','Video riêng tư không được đưa vào kho dùng chung. Bỏ chọn Lưu vào kho hoặc đổi quyền riêng tư.')
        if opts.save_memory and not settings.memory_enabled:
            raise VideoMediaError('memory_disabled','Bật lưu Translation Memory trong mục Kho dịch thuật trước.')
        old=VideoOptions(**job['options']).model_dump()
        changed=any(old.get(k)!=opts.model_dump().get(k) for k in ('source','method','stream_index','roi'))
        rows=await asyncio.to_thread(self._read_timeline,ident)
        if changed and rows:
            raise VideoMediaError('source_options_changed','Dự án đã có phụ đề. Nhập một dự án mới để đổi nguồn nhận diện hoặc vùng OCR.')
        # Reusing a checkpoint with different model/style/context must be explicit.
        glossary=self.manager.store.glossary(opts.profile)
        glossary_hash=hashlib.sha256(json.dumps(glossary,ensure_ascii=False,sort_keys=True).encode()).hexdigest()
        model=getattr(settings,opts.mode+'_model')
        fingerprint=hashlib.sha256(json.dumps([opts.source,opts.profile,opts.mode,model,settings.provider,glossary_hash,opts.private],sort_keys=True).encode()).hexdigest()
        if rows and job.get('translation_config') and job['translation_config']!=fingerprint:
            raise VideoMediaError('configuration_changed','Cấu hình AI/từ điển đã đổi. Xuất/sửa dự án hiện có hoặc nhập mới để dịch lại.')
        job['options']=opts.model_dump();job['translation_config']=fingerprint
        job['namespace']=f'v2|api|{opts.source}|{opts.profile}|{opts.mode}|{model}|{glossary_hash}|'
        job['translation_model']=model;job['glossary_version']=glossary_hash
        if 'initial_memory_count' not in job:
            with self.manager.store.lock:
                job['initial_memory_count']=self.manager.store.db.execute('SELECT count(*) FROM translation_memory WHERE project_id=?',(ident,)).fetchone()[0]
        job['status']='queued';job['error']=None
        self.paused=False;self.cancel_event=asyncio.Event();self.active_id=ident
        await self._save(job)
        self.task=asyncio.create_task(self._run(job),name='video-'+ident)
        return self._public(job)

    async def _run(self,job):
        ident=job['id'];folder=self._path(ident);opts=VideoOptions(**job['options'])
        revision=self.manager.revision
        async def ready(stage='extracting'):
            await self._ready(job,stage)
            self.manager.require_revision(revision)
        async def progress(value):
            job['progress']['percent']=round(100*value,1);await self._save(job)
        try:
            rows=await asyncio.to_thread(self._read_timeline,ident)
            if not job.get('extraction_complete',bool(rows)):
                await ready()
                streams=job['metadata']['streams']
                method=opts.method
                text_streams=[s for s in streams if s['type']=='subtitle' and s.get('is_text')]
                if method=='auto':
                    method='subtitle' if text_streams else ('speech' if opts.source=='ko' else 'ocr')
                job['extraction_method']=method
                if method=='subtitle':
                    index=opts.stream_index if opts.stream_index is not None else (text_streams[0]['index'] if text_streams else None)
                    if index is None:
                        raise VideoMediaError('no_text_subtitles','Không có luồng phụ đề văn bản. Hãy chọn OCR hoặc Speech.')
                    segments=await extract_subtitle_stream(folder/job['input'],index,self.tools,wait_ready=ready,cancel_event=self.cancel_event)
                elif method=='ocr':
                    if opts.source=='ko':
                        raise VideoMediaError('ocr_language_unavailable','OCR video tiếng Hàn chưa vượt kiểm tra chất lượng. Chọn Subtitle hoặc Speech; chưa gửi văn bản OCR lỗi tới Gemini.')
                    from .video_ocr import ocr_video, RapidOcrRecognizer
                    recognizer=await asyncio.to_thread(self.recognizer_factory or (lambda language:RapidOcrRecognizer(language=language,model_roots=self.runtime_roots)),opts.source)
                    saved=list(rows)
                    async def checkpoint(new_rows,pts):
                        for row in new_rows:
                            row.update(id=f'ocr-{len(saved)+1:06d}',language=opts.source,quality='draft')
                            saved.append(row)
                        job['extraction_progress_ms']=pts
                        await self._write_timeline(job,saved)
                    segments=await ocr_video(folder/job['input'],self.tools,recognizer=recognizer,roi=opts.roi,
                        language=opts.source,wait_ready=ready,cancel_event=self.cancel_event,progress=progress,
                        start_ms=job.get('extraction_progress_ms',0),checkpoint=checkpoint)
                    segments=[VideoSegment(**{k:row[k] for k in VideoSegment.__dataclass_fields__ if k in row}) for row in saved]
                else:
                    from .video_speech import speech_video
                    segments=await (self.speech_processor or speech_video)(folder/job['input'],self._metadata(job),self.tools,
                        source=opts.source,model_dir=self.speech_model(),wait_ready=ready,cancel_event=self.cancel_event,progress=progress)
                if not segments and opts.method=='auto' and method=='ocr' and any(s['type']=='audio' for s in streams):
                    job['extraction_method']='speech'
                    from .video_speech import speech_video
                    segments=await (self.speech_processor or speech_video)(folder/job['input'],self._metadata(job),self.tools,
                        source=opts.source,model_dir=self.speech_model(),wait_ready=ready,cancel_event=self.cancel_event,progress=progress)
                segments=_segments([replace(s,language=opts.source) for s in segments],job['metadata']['duration_ms'])
                if not segments:
                    raise VideoMediaError('no_segments','Chưa nhận được câu nguồn. Kiểm tra luồng phụ đề, ROI hoặc chọn Speech.')
                rows=[dict(s.as_dict(),quality='draft') for s in segments]
                job['extraction_complete']=True
                await self._write_timeline(job,rows)
            missing={}
            for row in rows:
                if not row.get('translation','').strip():
                    missing.setdefault(normalize(row['text']),[]).append(row)
            entries=list(missing.items())
            batches=[];batch=[];chars=0
            for text,items in entries:
                if batch and (len(batch)>=10 or chars+len(text)>6000):
                    batches.append(batch);batch=[];chars=0
                batch.append((text,items));chars+=len(text)
            if batch:batches.append(batch)
            for batch_index,batch in enumerate(batches):
                await ready('translating')
                request=TranslateRequest.model_validate(dict(request_id=f'video-{ident}-{batch_index}',source=opts.source,profile=opts.profile,mode=opts.mode,
                    blocks=[dict(id=f's{i}',text=text,box=dict(x=0,y=0,width=1,height=1)) for i,(text,_) in enumerate(batch)]))
                try:
                    result=await self.manager.translate(request,'video:'+ident,priority='video',allow_cache=False,
                        use_memory=opts.use_memory,persist_memory=opts.save_memory and not opts.private,
                        context_scope='global' if not opts.private else 'project:'+ident,source_type='VIDEO',project_id=ident,
                        allow_context=not opts.private,context_information=dict(video_id=ident,batch=batch_index),
                        confidence_metadata=dict(extraction=opts.method,not_human_verified=True))
                except TranslationError as error:
                    job['progress']['ai_calls']+=getattr(error,'diagnostics',{}).get('provider_calls',0)
                    raise
                if result is None or len(result['blocks'])!=len(batch):
                    raise VideoMediaError('invalid_mapping','Gemini chưa trả đủ ID phụ đề; chưa ghi checkpoint của batch.')
                for i,((text,items),block) in enumerate(zip(batch,result['blocks'])):
                    if block['id']!=f's{i}':
                        raise VideoMediaError('invalid_mapping','ID kết quả không khớp đoạn nguồn.')
                    for row in items:
                        row['translation']=block['text'];row['quality']='draft'
                    job['progress']['cache_hits']+=int(block['cached'])
                job['progress']['ai_calls']+=result['diagnostics'].get('provider_calls',0)
                for name in ('input_tokens','output_tokens'):
                    job['progress'][name]=job['progress'].get(name,0)+result['diagnostics'].get(name,0)
                job['progress']['percent']=round(100*(batch_index+1)/max(1,len(batches)),1)
                await self._write_timeline(job,rows)
            job['status']='ready';job['progress']['stage']='ready';job['progress']['percent']=100
            job['error']=None
            await self.manager.memory.flush()
            with self.manager.store.lock:
                count=self.manager.store.db.execute('SELECT count(*) FROM translation_memory WHERE project_id=?',(ident,)).fetchone()[0]
            job['progress']['new_memory']=max(0,count-job['initial_memory_count'])
        except asyncio.CancelledError:
            job['status']='interrupted' if self.closed else 'cancelled'
            job['error']='Đã lưu checkpoint. Có thể tiếp tục các câu chưa dịch.'
        except (TranslationError,VideoMediaError) as error:
            job['status']='failed';job['error']=str(error);job['error_code']=error.code
            if getattr(error,'retry_after_ms',None):job['retry_after_ms']=error.retry_after_ms
        except Exception:
            job['status']='failed';job['error']='Lỗi xử lý video. Checkpoint trước đó được giữ; không ghi nội dung video vào log.'
        finally:
            await self._save(job)
            self.manager.contexts={k:v for k,v in self.manager.contexts.items() if k[0]!='video:'+ident}
            if self.active_id==ident:self.active_id=None

    async def action(self,ident,action):
        async with self.admission_lock:
            return await self._action(ident,action)

    async def _action(self,ident,action):
        job=self._job(ident)
        if action in ('resume','recover'):
            if self.active_id==ident and self.task and not self.task.done():
                self.paused=False
                return self._public(job)
            return await self._start(ident)
        if action=='pause':
            if self.active_id==ident:self.paused=True
            return self._public(job)
        if action=='cancel':
            if self.active_id==ident and self.task and not self.task.done():
                self.cancel_event.set();self.task.cancel()
                await asyncio.gather(self.task,return_exceptions=True)
                if self.active_id==ident:
                    self.active_id=None;job['status']='cancelled';job['error']='Đã hủy. Checkpoint trước đó được giữ.'
                    await self._save(job)
            return self._public(job)
        raise VideoMediaError('invalid_action','Thao tác video không hợp lệ.')

    async def edit(self,ident,changes):
        async with self.admission_lock:
            return await self._edit(ident,changes)

    async def _edit(self,ident,changes):
        job=self._job(ident)
        if self.active_id==ident:
            raise VideoMediaError('video_busy','Dừng tác vụ trước khi sửa timeline để tránh ghi đè checkpoint.')
        rows=await asyncio.to_thread(self._read_timeline,ident)
        mapping={row['id']:row for row in rows}
        approved=[]
        for change in changes:
            if change.get('id') not in mapping:
                raise VideoMediaError('invalid_segment','Không tìm thấy ID phụ đề trong dự án.')
            row=mapping[change['id']]
            old_text=row['text'];old_translation=row.get('translation','')
            for key in ('text','translation','start_ms','end_ms'):
                if key in change:row[key]=change[key]
            if row['text']!=old_text and row.get('translation')==old_translation and change.get('quality')!='user_approved':
                row['translation']='';row['quality']='draft'
            elif (row.get('translation','') and (row.get('translation')!=old_translation or change.get('quality')=='user_approved')):
                row['quality']='user_approved';approved.append(row)
        validated=_segments(rows,job['metadata']['duration_ms'])
        rows=[dict(s.as_dict(),quality=mapping[s.id].get('quality','draft')) for s in validated]
        await self._write_timeline(job,rows)
        if approved:await self._approve_memory(job,approved)
        job['outputs']={};job['status']='ready';await self._save(job)
        return await self.detail(ident)

    async def _approve_memory(self,job,rows):
        opts=VideoOptions(**job['options']);settings=self.manager.settings()
        if opts.private or not opts.save_memory or not settings.memory_enabled:
            return
        version=job['glossary_version'];model=job['translation_model'];namespace=job['namespace']
        for row in rows:
            await self.manager.memory.put_verified(MemoryEntry(namespace=namespace,original_text=row['text'],translation=row['translation'],
                source_language=opts.source,profile=opts.profile,mode=opts.mode,provider='api',model=model,glossary_version=version,
                context_scope='global',source_type='VIDEO',project_id=job['id'],quality='user_approved',
                context_information=dict(video_id=job['id']),confidence_metadata=dict(user_approved=True,recognition_confidence=row.get('confidence'))))
        self.manager.invalidate()

    async def replace_text(self,ident,search,replacement):
        async with self.admission_lock:
            return await self._replace_text(ident,search,replacement)

    async def _replace_text(self,ident,search,replacement):
        if not search or len(search)>500 or len(replacement)>500:
            raise VideoMediaError('invalid_replace','Nhập từ cần tìm và từ thay thế tối đa 500 ký tự.')
        rows=await asyncio.to_thread(self._read_timeline,ident)
        changes=[dict(id=row['id'],translation=row.get('translation','').replace(search,replacement),quality='user_approved')
                 for row in rows if search in row.get('translation','')]
        if not changes:return dict(await self.detail(ident),changed=0)
        return dict(await self._edit(ident,changes),changed=len(changes))

    async def export(self,ident,spec):
        async with self.admission_lock:
            return await self._export(ident,spec)

    async def _export(self,ident,spec):
        if self.closed:
            raise VideoMediaError('video_closed','Ứng dụng đang đóng. Mở lại ứng dụng để tiếp tục dự án.')
        job=self._job(ident)
        if self.task is not None and not self.task.done():
            raise VideoMediaError('video_busy','Có tác vụ video đang chạy. Hãy chờ hoặc dừng trước khi xuất.')
        fmt=spec.get('format','mp4')
        if fmt not in ('mp4','srt','ass'):
            raise VideoMediaError('invalid_format','Chọn MP4, SRT hoặc ASS.')
        rows=await asyncio.to_thread(self._read_timeline,ident)
        segments=_segments(rows,job['metadata']['duration_ms'])
        if not segments or any(not s.translation.strip() for s in segments):
            raise VideoMediaError('untranslated','Hoàn tất bản dịch của tất cả đoạn trước khi xuất.')
        style_data=dict(spec.get('style') or {})
        if 'font_name'in style_data:style_data['font']=style_data.pop('font_name')
        if style_data.get('position')=='middle':style_data['position']='center'
        try:style=SubtitleStyle(**style_data);style.validate()
        except TypeError:raise VideoMediaError('invalid_style','Tùy chọn kiểu phụ đề không hợp lệ.') from None
        if spec.get('quality','quality') not in ('balanced','fast','quality'):
            raise VideoMediaError('invalid_quality','Chọn chất lượng quality, balanced hoặc fast.')
        folder=self._path(ident);output=folder/('translated-'+secrets.token_hex(6)+'.'+fmt)
        if fmt in ('srt','ass'):
            content=export_srt(segments,bilingual=bool(spec.get('bilingual'))) if fmt=='srt' else export_ass(segments,
                width=job['metadata']['width'],height=job['metadata']['height'],style=style,bilingual=bool(spec.get('bilingual')))
            await asyncio.to_thread(output.write_text,content,encoding='utf-8-sig')
            job['outputs'][fmt]=output.name;await self._save(job)
            return self._public(job)
        job['export_spec']=spec;job['status']='queued';job['error']=None
        self.cancel_event=asyncio.Event();self.paused=False;self.active_id=ident
        async def run():
            try:
                async def ready():await self._ready(job,'exporting')
                async def progress(value):job['progress']['percent']=round(100*value,1);await self._save(job)
                await render_video(folder/job['input'],output,segments,self.tools,style=style,bilingual=bool(spec.get('bilingual')),
                    quality=spec.get('quality','balanced'),wait_ready=ready,cancel_event=self.cancel_event,progress=progress)
                job['outputs']['mp4']=output.name;job['status']='completed';job['progress']['percent']=100;job['progress']['stage']='completed'
            except asyncio.CancelledError:
                job['status']='interrupted' if self.closed else 'cancelled';job['error']='Render chưa hoàn tất; có thể xuất lại từ checkpoint phụ đề.'
            except VideoMediaError as error:
                job['status']='failed';job['error']=str(error)
            except Exception:
                job['status']='failed';job['error']='Không xuất được video. Nguồn và checkpoint được giữ.'
            finally:
                await self._save(job)
                if self.active_id==ident:self.active_id=None
        await self._save(job)
        self.task=asyncio.create_task(run(),name='render-'+ident)
        return self._public(job)

    def file(self,ident,kind,token):
        job=self._job(ident)
        if not secrets.compare_digest(self.tokens[ident],token):
            raise VideoMediaError('file_forbidden','Liên kết video không hợp lệ hoặc hết hạn.')
        name=job['input'] if kind=='source' else job.get('outputs',{}).get(kind)
        if not name:raise VideoMediaError('file_missing','Tệp chưa được xuất.')
        path=(self._path(ident)/name).resolve()
        if path.parent!=self._path(ident) or not path.is_file():
            raise VideoMediaError('file_missing','Không tìm thấy tệp dự án.')
        return path

    async def preview(self,ident,timestamp_ms):
        job=self._job(ident)
        if self.manager.resources.active_live:
            raise VideoMediaError('live_priority','Tạm dừng Live OCR trước khi tạo ảnh preview video.')
        data=await preview_frame(self._path(ident)/job['input'],self.tools,timestamp_ms=timestamp_ms,
            wait_ready=self.manager.resources.wait_background_ready)
        (self._path(ident)/'preview.jpg').write_bytes(data)
        job['outputs']['preview']='preview.jpg'
        return dict(image_url=f"/api/video/files/{ident}/preview?token={self.tokens[ident]}")

    async def delete(self,ident):
        async with self.admission_lock:
            return await self._delete(ident)

    async def _delete(self,ident):
        self._job(ident)
        await self._action(ident,'cancel')
        folder=self._path(ident)
        assert folder.parent==self.directory
        await self.manager.memory.delete_project(ident)
        await asyncio.to_thread(shutil.rmtree,folder)
        self.jobs.pop(ident);self.tokens.pop(ident,None)
        return dict(ok=True)

    async def close(self):
        async with self.admission_lock:
            self.closed=True
            if self.task and not self.task.done():
                self.cancel_event.set();self.task.cancel()
                await asyncio.gather(self.task,return_exceptions=True)
