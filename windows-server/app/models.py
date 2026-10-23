from typing import Literal
from pydantic import BaseModel, ConfigDict, Field, model_validator


class Box(BaseModel):
    x: float = Field(ge=0, le=1)
    y: float = Field(ge=0, le=1)
    width: float = Field(gt=0, le=1)
    height: float = Field(gt=0, le=1)

    @model_validator(mode='after')
    def within_screen(self):
        if self.x + self.width > 1.001 or self.y + self.height > 1.001:
            raise ValueError('Bounding box exceeds screen')
        return self


class Block(BaseModel):
    id: str = Field(min_length=1, max_length=80)
    text: str = Field(min_length=1, max_length=2000)
    box: Box


class TranslateRequest(BaseModel):
    model_config = ConfigDict(extra='forbid')
    type: Literal['translate'] = 'translate'
    request_id: str = Field(min_length=1, max_length=80)
    region_id: str | None = Field(default=None, min_length=1, max_length=80)
    screen_generation: int | None = Field(default=None, ge=0)
    latest_only: bool = False
    source: Literal['en', 'ja', 'zh', 'ko'] = 'ja'
    profile: Literal['game', 'manga', 'document', 'web', 'technical'] = 'game'
    mode: Literal['fast', 'smart', 'quality'] = 'smart'
    blocks: list[Block] = Field(min_length=1, max_length=40)

    @model_validator(mode='after')
    def validate_blocks(self):
        if len({b.id for b in self.blocks}) != len(self.blocks):
            raise ValueError('Duplicate block ids')
        if sum(len(b.text) for b in self.blocks) > 12000:
            raise ValueError('Frame exceeds 12000 characters')
        return self


class PairRequest(BaseModel):
    code: str = Field(pattern=r'^\d{6}$')
    name: str = Field(min_length=1, max_length=64)


class Settings(BaseModel):
    provider: Literal['api', 'cli'] = 'api'
    personal_cli_enabled: bool = False
    cli_model: str = Field(default='auto', pattern=r'^[a-zA-Z0-9._-]{1,100}$')
    fast_model: str = Field(default='gemini-3.5-flash-lite', pattern=r'^[a-zA-Z0-9._-]{1,100}$')
    smart_model: str = Field(default='gemini-3.8-flash', pattern=r'^[a-zA-Z0-9._-]{1,100}$')
    quality_model: str = Field(default='gemini-3.8-flash', pattern=r'^[a-zA-Z0-9._-]{1,100}$')
    requests_per_minute: int = Field(default=15, ge=1, le=120)
    daily_request_limit: int = Field(default=500, ge=1, le=100000)
    cache_enabled: bool = True
    context_enabled: bool = True
    memory_enabled: bool = False
    api_key: str | None = Field(default=None, max_length=200)


class GlossaryEntry(BaseModel):
    source: str = Field(min_length=1, max_length=200)
    target: str = Field(min_length=1, max_length=500)
    profile: Literal['all', 'game', 'manga', 'document', 'web', 'technical'] = 'all'

    @model_validator(mode='after')
    def nonblank(self):
        if not self.source.strip() or not self.target.strip():
            raise ValueError('Source and target must not be blank')
        return self


class GlossaryImport(BaseModel):
    entries: list[GlossaryEntry] = Field(min_length=1, max_length=500)

    @model_validator(mode='after')
    def unique_entries(self):
        import unicodedata
        keys = [(' '.join(unicodedata.normalize('NFKC', e.source).split()), e.profile) for e in self.entries]
        if len(set(keys)) != len(keys):
            raise ValueError('Duplicate normalized source/profile in import')
        return self

