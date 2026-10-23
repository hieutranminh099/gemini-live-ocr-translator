"""Authenticated controls and scoped local media URLs for Video Translator."""
from fastapi import Depends, File, Header, HTTPException, UploadFile
from fastapi.responses import FileResponse
from pydantic import BaseModel, ConfigDict, Field, ValidationError
from typing import Literal

from .video_jobs import VideoOptions
from .video_media import VideoMediaError


class TimelineChange(BaseModel):
    model_config=ConfigDict(extra='forbid')
    segments:list[dict]=Field(min_length=1,max_length=20000)


class ReplaceText(BaseModel):
    search:str=Field(min_length=1,max_length=500)
    replacement:str=Field(max_length=500)


class ExportOptions(BaseModel):
    model_config=ConfigDict(extra='forbid')
    format:Literal['srt','ass','mp4']='mp4'
    style:dict=Field(default_factory=dict)
    bilingual:bool=False
    quality:Literal['balanced','fast','quality']='quality'


class MemorySettings(BaseModel):
    enabled:bool


class MemoryEdit(BaseModel):
    translation:str=Field(min_length=1,max_length=8000)
    quality:Literal['user_approved']='user_approved'


class Preview(BaseModel):
    timestamp_ms:int=Field(default=0,ge=0)


def register_video_routes(app,runtime,admin):
    service=runtime.video_jobs
    def failure(error):
        code=getattr(error,'code','')
        status=404 if code in ('project_not_found','file_missing') else 403 if code=='file_forbidden' else 400
        return HTTPException(status,str(error))

    @app.get('/api/video/capabilities',dependencies=[Depends(admin)])
    async def capabilities():
        return service.capabilities()

    @app.post('/api/video/import',dependencies=[Depends(admin)])
    async def import_video(file:UploadFile=File(...)):
        try:
            return await service.import_upload(file.filename,file.read)
        except VideoMediaError as error:
            raise failure(error) from None
        finally:
            await file.close()

    @app.get('/api/video/jobs',dependencies=[Depends(admin)])
    async def jobs():return service.list_jobs()

    @app.get('/api/video/jobs/{ident}',dependencies=[Depends(admin)])
    async def detail(ident:str,offset:int=0,limit:int=200):
        if offset<0 or not 1<=limit<=2000:raise HTTPException(400,'Phạm vi timeline không hợp lệ.')
        try:return await service.detail(ident,offset=offset,limit=limit)
        except VideoMediaError as error:raise failure(error) from None

    @app.post('/api/video/jobs/{ident}/start',dependencies=[Depends(admin)])
    async def start(ident:str,body:VideoOptions):
        try:return await service.start(ident,body.model_dump())
        except VideoMediaError as error:raise failure(error) from None

    @app.post('/api/video/jobs/{ident}/pause',dependencies=[Depends(admin)])
    async def pause(ident:str):
        try:return await service.action(ident,'pause')
        except VideoMediaError as error:raise failure(error) from None

    @app.post('/api/video/jobs/{ident}/resume',dependencies=[Depends(admin)])
    async def resume(ident:str):
        try:return await service.action(ident,'resume')
        except VideoMediaError as error:raise failure(error) from None

    @app.post('/api/video/jobs/{ident}/recover',dependencies=[Depends(admin)])
    async def recover(ident:str):
        try:return await service.action(ident,'recover')
        except VideoMediaError as error:raise failure(error) from None

    @app.post('/api/video/jobs/{ident}/cancel',dependencies=[Depends(admin)])
    async def cancel(ident:str):
        try:return await service.action(ident,'cancel')
        except VideoMediaError as error:raise failure(error) from None

    @app.delete('/api/video/jobs/{ident}',dependencies=[Depends(admin)])
    async def delete(ident:str):
        try:
            result=await service.delete(ident)
            runtime.invalidate()
            return result
        except VideoMediaError as error:raise failure(error) from None

    @app.put('/api/video/jobs/{ident}/timeline',dependencies=[Depends(admin)])
    async def timeline(ident:str,body:TimelineChange):
        try:
            result=await service.edit(ident,body.segments)
            runtime.invalidate()
            return result
        except (VideoMediaError,TypeError,ValueError) as error:raise failure(error) from None

    @app.post('/api/video/jobs/{ident}/replace',dependencies=[Depends(admin)])
    async def replace_text(ident:str,body:ReplaceText):
        try:
            result=await service.replace_text(ident,body.search,body.replacement)
            runtime.invalidate()
            return result
        except VideoMediaError as error:raise failure(error) from None

    @app.post('/api/video/jobs/{ident}/export',dependencies=[Depends(admin)])
    async def export(ident:str,body:ExportOptions):
        try:return await service.export(ident,body.model_dump())
        except VideoMediaError as error:raise failure(error) from None

    @app.post('/api/video/jobs/{ident}/preview',dependencies=[Depends(admin)])
    async def preview(ident:str,body:Preview):
        try:return await service.preview(ident,body.timestamp_ms)
        except VideoMediaError as error:raise failure(error) from None

    @app.get('/api/video/files/{ident}/{kind}')
    async def media(ident:str,kind:str,token:str=''):
        try:
            path=service.file(ident,kind,token)
            return FileResponse(path,filename=path.name,
                content_disposition_type='inline' if kind in ('source','preview') else 'attachment')
        except VideoMediaError as error:raise failure(error) from None

    @app.get('/api/memory/settings',dependencies=[Depends(admin)])
    async def memory_settings():
        return dict(enabled=runtime.manager.settings().memory_enabled)

    @app.put('/api/memory/settings',dependencies=[Depends(admin)])
    async def save_memory_settings(body:MemorySettings):
        settings=runtime.manager.settings().model_dump(exclude={'api_key'})
        settings['memory_enabled']=body.enabled
        runtime.store.set('settings',settings)
        runtime.invalidate()
        return dict(enabled=body.enabled)

    @app.get('/api/memory',dependencies=[Depends(admin)])
    async def memory(search:str='',quality:str|None=None,source:str|None=None,project_id:str|None=None,limit:int=100,offset:int=0):
        try:
            return runtime.manager.memory.list_entries(search=search,quality=quality,source=source,project_id=project_id,limit=min(200,max(1,limit)),offset=max(0,offset))
        except ValueError as error:raise HTTPException(400,str(error)) from None

    @app.patch('/api/memory/{content_hash}',dependencies=[Depends(admin)])
    async def edit_memory(content_hash:str,body:MemoryEdit):
        try:
            result=await runtime.manager.memory.update_entry(content_hash,body.translation,quality=body.quality)
            runtime.invalidate()
            return result or dict(ok=True)
        except (ValueError,KeyError) as error:raise HTTPException(400,'Không tìm thấy mục hoặc nội dung không hợp lệ.') from None

    @app.delete('/api/memory/{content_hash}',dependencies=[Depends(admin)])
    async def delete_memory(content_hash:str):
        await runtime.manager.memory.delete_entry(content_hash)
        runtime.invalidate()
        return dict(ok=True)

    @app.delete('/api/memory/projects/{project_id}',dependencies=[Depends(admin)])
    async def delete_project_memory(project_id:str):
        await runtime.manager.memory.delete_project(project_id)
        runtime.invalidate()
        return dict(ok=True)
