"""Local authenticated admin controls for explicit subtitle cache prewarming."""
from typing import Literal

from fastapi import Depends, HTTPException
from pydantic import BaseModel, ConfigDict, Field

from .subtitle_import import SubtitleImportError
from .subtitle_jobs import SubtitleJobError


class SubtitleUpload(BaseModel):
    model_config = ConfigDict(extra='forbid')
    filename: str = Field(min_length=1, max_length=260)
    content: str = Field(min_length=1, max_length=2 * 1024 * 1024)


class SubtitleStart(SubtitleUpload):
    source: Literal['en', 'ja', 'zh', 'ko'] = 'zh'
    profile: Literal['game', 'manga', 'document', 'web', 'technical'] = 'game'
    mode: Literal['fast', 'smart', 'quality'] = 'fast'


def register_subtitle_routes(app, service, admin):
    def failure(error):
        status = 404 if getattr(error, 'code', '') == 'job_not_found' else 400
        return HTTPException(status, str(error))

    @app.post('/api/subtitles/preview', dependencies=[Depends(admin)])
    async def preview(body: SubtitleUpload):
        try:
            return service.preview(body.filename, body.content)
        except SubtitleImportError as error:
            raise failure(error) from None

    @app.post('/api/subtitles/jobs', dependencies=[Depends(admin)])
    async def start(body: SubtitleStart):
        try:
            return await service.start(body.filename, body.content,
                                       source=body.source, profile=body.profile, mode=body.mode)
        except (SubtitleImportError, SubtitleJobError) as error:
            raise failure(error) from None

    @app.get('/api/subtitles/jobs', dependencies=[Depends(admin)])
    async def jobs():
        return service.list_jobs()

    @app.delete('/api/subtitles/jobs/{job_id}', dependencies=[Depends(admin)])
    async def cancel(job_id: str):
        try:
            return await service.cancel(job_id)
        except SubtitleJobError as error:
            raise failure(error) from None
