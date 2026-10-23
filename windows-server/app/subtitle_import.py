"""Strict, side-effect-free subtitle import and bounded Gemini batch planning.

This module never writes cache entries or calls a provider. The caller must use
the normal TranslationManager namespace (source/profile/mode/model/glossary) for
prewarming; a subtitle file is untrusted text, not a translation instruction.
"""

from dataclasses import dataclass
from html.parser import HTMLParser
import math
import re
from pathlib import PureWindowsPath

from .storage import normalize


MAX_FILE_BYTES = 2 * 1024 * 1024
MAX_CUES = 3000
MAX_TEXT_CHARS = 2000
DEFAULT_BATCH_ITEMS = 10
DEFAULT_BATCH_CHARS = 6000
DEFAULT_BATCH_ESTIMATED_TOKENS = 2400


class SubtitleImportError(ValueError):
    """A malformed file is rejected as a whole; never import a partial plan."""


@dataclass(frozen=True)
class SubtitleCue:
    cue_id: str
    source_id: str | None
    start_ms: int
    end_ms: int
    text: str
    settings: str = ''

    def as_dict(self):
        return dict(cue_id=self.cue_id, source_id=self.source_id,
                    start_ms=self.start_ms, end_ms=self.end_ms, text=self.text,
                    settings=self.settings)


@dataclass(frozen=True)
class SubtitleDocument:
    format: str
    cues: tuple[SubtitleCue, ...]
    warnings: tuple[str, ...] = ()


@dataclass(frozen=True)
class SubtitleItem:
    item_id: str
    text: str
    cue_ids: tuple[str, ...]

    def as_dict(self):
        return dict(item_id=self.item_id, text=self.text, cue_ids=list(self.cue_ids))


@dataclass(frozen=True)
class SubtitleBatch:
    batch_id: str
    items: tuple[SubtitleItem, ...]
    estimated_input_tokens: int

    def as_dict(self):
        return dict(batch_id=self.batch_id, items=[item.as_dict() for item in self.items],
                    estimated_input_tokens=self.estimated_input_tokens)


@dataclass(frozen=True)
class SubtitlePlan:
    document: SubtitleDocument
    unique_items: tuple[SubtitleItem, ...]
    batches: tuple[SubtitleBatch, ...]

    def as_dict(self, *, include_batches=True):
        result = dict(format=self.document.format, cue_count=len(self.document.cues),
                      unique_text_count=len(self.unique_items),
                      duplicate_cue_count=len(self.document.cues) - len(self.unique_items),
                      expected_batches=len(self.batches),
                      estimated_input_tokens=sum(batch.estimated_input_tokens for batch in self.batches),
                      warnings=list(self.document.warnings),
                      cues=[cue.as_dict() for cue in self.document.cues])
        if include_batches:
            result['batches'] = [batch.as_dict() for batch in self.batches]
        return result


_SRT_TIME = re.compile(r'^(\d{1,3}):([0-5]\d):([0-5]\d)[,.](\d{3})$')
_VTT_TIME = re.compile(r'^(?:(\d{1,3}):)?([0-5]\d):([0-5]\d)\.(\d{3})$')
_ASS_TIME = re.compile(r'^(\d{1,3}):([0-5]\d):([0-5]\d)\.(\d{2})$')
_INLINE_VTT_TIME = re.compile(r'<(?:(?:\d{1,3}):)?[0-5]\d:[0-5]\d\.\d{3}>')
_ASS_OVERRIDE = re.compile(r'\{\\[^{}]*\}')


class _TextExtractor(HTMLParser):
    # Formatting and WebVTT voice/language/class tags do not become speech.
    allowed = {'b', 'i', 'u', 's', 'font', 'c', 'v', 'lang', 'span'}

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.parts = []

    def handle_starttag(self, tag, attrs):
        # HTMLParser parses <c.class> as tag c.class.
        if tag.split('.', 1)[0] not in self.allowed | {'br'}:
            raise SubtitleImportError(f'Thẻ phụ đề chưa hỗ trợ: <{tag}>.')
        if tag == 'br':
            self.parts.append(' ')

    def handle_startendtag(self, tag, attrs):
        self.handle_starttag(tag, attrs)

    def handle_endtag(self, tag):
        if tag.split('.', 1)[0] not in self.allowed | {'br'}:
            raise SubtitleImportError(f'Thẻ phụ đề chưa hỗ trợ: </{tag}>.')

    def handle_data(self, data):
        self.parts.append(data)

    def handle_comment(self, data):
        raise SubtitleImportError('Không hỗ trợ comment HTML trong nội dung phụ đề.')

    def handle_decl(self, decl):
        raise SubtitleImportError('Không hỗ trợ khai báo HTML trong nội dung phụ đề.')


def _decode(content):
    if isinstance(content, str):
        try:
            size = len(content.encode('utf-8'))
        except UnicodeEncodeError:
            raise SubtitleImportError('Phụ đề chứa ký tự Unicode không hợp lệ.') from None
        text = content.lstrip('\ufeff')
    elif isinstance(content, bytes):
        size = len(content)
        if size > MAX_FILE_BYTES:
            raise SubtitleImportError('Tệp phụ đề vượt giới hạn 2 MiB.')
        try:
            encoding = 'utf-16' if content.startswith((b'\xff\xfe', b'\xfe\xff')) else 'utf-8-sig'
            text = content.decode(encoding, errors='strict')
        except UnicodeError:
            raise SubtitleImportError('Hãy lưu phụ đề dưới dạng UTF-8 hoặc UTF-16 có BOM.') from None
    else:
        raise SubtitleImportError('Dữ liệu phụ đề phải là văn bản hoặc bytes.')
    if size > MAX_FILE_BYTES:
        raise SubtitleImportError('Tệp phụ đề vượt giới hạn 2 MiB.')
    if any(ord(c) < 32 and c not in '\r\n\t' for c in text):
        raise SubtitleImportError('Tệp phụ đề có dữ liệu nhị phân hoặc ký tự điều khiển không hợp lệ.')
    text = text.replace('\r\n', '\n').replace('\r', '\n')
    if not text.strip():
        raise SubtitleImportError('Tệp phụ đề trống.')
    return text


def _timestamp(value, kind):
    expression = {'srt': _SRT_TIME, 'vtt': _VTT_TIME, 'ass': _ASS_TIME}[kind]
    match = expression.fullmatch(value)
    if not match:
        raise SubtitleImportError(f'Mốc thời gian {kind.upper()} không hợp lệ: {value[:60]}.')
    hours, minutes, seconds, fraction = match.groups()
    scale = 10 if kind == 'ass' else 1
    return ((int(hours or 0) * 60 + int(minutes)) * 60 + int(seconds)) * 1000 + int(fraction) * scale


def _clean_text(value, kind):
    if kind in ('srt', 'ass'):
        # ASS positioning/colour/karaoke overrides are formatting, but vector
        # drawing is not dialogue and must not be submitted as a sentence.
        for override in _ASS_OVERRIDE.findall(value):
            if any(int(mode) != 0 for mode in re.findall(r'\\p(\d+)(?!\d)', override)):
                raise SubtitleImportError('Phụ đề ASS chứa hình vẽ vector; hãy xuất phần lời thoại sang SRT.')
        value = _ASS_OVERRIDE.sub('', value)
        if re.search(r'\{\\|\\[^{}]*\}', value):
            raise SubtitleImportError('Khối định dạng ASS không hoàn chỉnh.')
    if kind == 'ass':
        value = value.replace(r'\N', ' ').replace(r'\n', ' ').replace(r'\h', ' ')
    if kind == 'vtt':
        value = _INLINE_VTT_TIME.sub('', value)
    extractor = _TextExtractor()
    try:
        extractor.feed(value)
        extractor.close()
    except SubtitleImportError:
        raise
    except (ValueError, AssertionError):
        raise SubtitleImportError('Định dạng chữ trong phụ đề không hợp lệ.') from None
    text = normalize(''.join(extractor.parts))
    if not text:
        raise SubtitleImportError('Một cue không có lời thoại sau khi bỏ định dạng.')
    if len(text) > MAX_TEXT_CHARS:
        raise SubtitleImportError('Một cue vượt giới hạn 2.000 ký tự; hãy tách câu trước khi nhập.')
    return text


def _cue(cues, source_id, start_ms, end_ms, value, kind, settings=''):
    if len(cues) >= MAX_CUES:
        raise SubtitleImportError('Tệp phụ đề vượt giới hạn 3.000 cue.')
    if end_ms <= start_ms:
        raise SubtitleImportError('Thời điểm kết thúc cue phải sau thời điểm bắt đầu.')
    if source_id is not None and len(source_id) > 500:
        raise SubtitleImportError('Mã cue vượt giới hạn 500 ký tự.')
    cues.append(SubtitleCue(f'cue-{len(cues) + 1:05d}', source_id, start_ms, end_ms,
                            _clean_text(value, kind), settings))


def _timed_text(text, kind):
    blocks = re.split(r'\n[ \t]*\n', text.strip())
    cues, warnings = [], []
    if kind == 'vtt':
        header = blocks.pop(0).splitlines()
        if not header or not re.fullmatch(r'WEBVTT(?:[ \t].*)?', header[0]):
            raise SubtitleImportError('Tệp VTT thiếu dòng WEBVTT đầu tệp.')
        if any('-->' in line for line in header[1:]):
            raise SubtitleImportError('Tệp VTT phải có dòng trống sau header.')
    seen_ids = set()
    for block in blocks:
        lines = block.splitlines()
        if not lines:
            continue
        if kind == 'vtt' and (lines[0] in ('STYLE', 'REGION') or re.match(r'^NOTE(?:[ \t]|$)', lines[0])):
            warnings.append('Bỏ qua metadata NOTE/STYLE/REGION của VTT; giữ thời gian và lời thoại.')
            continue
        source_id = None
        if '-->' not in lines[0]:
            source_id = lines.pop(0).strip()
            if kind == 'srt' and not source_id.isdecimal():
                raise SubtitleImportError('Mã cue SRT phải là số; không thể ghép khối phụ đề.')
            if not source_id or source_id in seen_ids:
                raise SubtitleImportError('Mã cue trống hoặc trùng trong tệp phụ đề.')
            seen_ids.add(source_id)
        if len(lines) < 2 or lines[0].count('-->') != 1:
            raise SubtitleImportError('Mỗi cue phải có một dòng thời gian và ít nhất một dòng lời thoại.')
        before, after = (part.strip() for part in lines.pop(0).split('-->'))
        end_parts = after.split(None, 1)
        if not end_parts:
            raise SubtitleImportError('Cue thiếu thời điểm kết thúc.')
        settings = end_parts[1] if len(end_parts) == 2 else ''
        if kind == 'srt' and settings:
            raise SubtitleImportError('Vị trí kiểu SRT cũ chưa được hỗ trợ; hãy xuất SRT tiêu chuẩn.')
        if kind == 'vtt' and settings:
            for setting in settings.split():
                if not re.match(r'^(?:vertical|line|position|size|align|region):\S+$', setting):
                    raise SubtitleImportError('Thiết lập vị trí VTT không hợp lệ.')
        if any('-->' in line for line in lines):
            raise SubtitleImportError('Thiếu dòng trống giữa các cue; không nhập ghép hai câu.')
        _cue(cues, source_id, _timestamp(before, kind), _timestamp(end_parts[0], kind),
             '\n'.join(lines), kind, settings)
    return cues, tuple(dict.fromkeys(warnings))


def _ass(text):
    cues, fields = [], None
    section = ''
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith(';'):
            continue
        if line.startswith('[') and line.endswith(']'):
            section = line.casefold()
            continue
        if section != '[events]':
            continue
        kind, separator, value = line.partition(':')
        if not separator:
            raise SubtitleImportError('Dòng Events của ASS thiếu loại sự kiện.')
        if kind.casefold() == 'format':
            fields = [part.strip().casefold() for part in value.split(',')]
            if (len(set(fields)) != len(fields) or not {'start', 'end', 'text'}.issubset(fields)
                    or fields[-1] != 'text'):
                raise SubtitleImportError('Format ASS phải có Start, End và Text ở cột cuối, không có cột trùng.')
        elif kind.casefold() == 'comment':
            continue
        elif kind.casefold() == 'dialogue':
            if fields is None:
                raise SubtitleImportError('ASS phải khai báo Format trước Dialogue.')
            values = value.split(',', len(fields) - 1)
            if len(values) != len(fields):
                raise SubtitleImportError('Số cột Dialogue ASS không khớp Format.')
            row = dict(zip(fields, (part.strip() for part in values)))
            _cue(cues, None, _timestamp(row['start'], 'ass'), _timestamp(row['end'], 'ass'), row['text'], 'ass')
        else:
            raise SubtitleImportError('Loại sự kiện ASS chưa hỗ trợ; hãy xuất phần lời thoại sang SRT.')
    return cues, ()


def parse_subtitle_file(filename: str, content: bytes | str) -> SubtitleDocument:
    """Parse the complete file before returning, with no partial-import effects."""
    suffix = PureWindowsPath(str(filename)).suffix.casefold()
    kind = {'.srt': 'srt', '.vtt': 'vtt', '.ass': 'ass', '.ssa': 'ass'}.get(suffix)
    if kind is None:
        raise SubtitleImportError('Chỉ hỗ trợ tệp phụ đề .srt, .vtt, .ass hoặc .ssa.')
    text = _decode(content)
    cues, warnings = _ass(text) if kind == 'ass' else _timed_text(text, kind)
    if not cues:
        raise SubtitleImportError('Tệp không có cue lời thoại hợp lệ.')
    return SubtitleDocument(kind, tuple(cues), warnings)


def estimate_input_tokens(text: str) -> int:
    """Planning estimate only, not a model tokenizer, bill, or quota promise."""
    # UTF-8/3 conservatively accounts for CJK compared with a chars/4 heuristic.
    return max(1, math.ceil(len(text.encode('utf-8')) / 3)) + 12


def plan_subtitles(document: SubtitleDocument, *, max_batch_items=DEFAULT_BATCH_ITEMS,
                   max_batch_chars=DEFAULT_BATCH_CHARS,
                   max_batch_estimated_tokens=DEFAULT_BATCH_ESTIMATED_TOKENS) -> SubtitlePlan:
    """Deduplicate exact normalized sources and preserve every cue-to-item link.

    No punctuation folding or fuzzy OCR aliases are used: matching a different
    sentence would be worse than a cache miss. Timing never changes translation
    identity. Batches preserve first occurrence order for limited context.
    """
    if not (1 <= max_batch_items <= 12 and 1 <= max_batch_chars <= 12000
            and 1 <= max_batch_estimated_tokens <= 12000):
        raise SubtitleImportError('Giới hạn batch phụ đề không hợp lệ.')
    if not document.cues or len(document.cues) > MAX_CUES:
        raise SubtitleImportError('Số cue phụ đề không hợp lệ.')
    grouped = {}
    cue_ids = set()
    for cue in document.cues:
        if cue.cue_id in cue_ids:
            raise SubtitleImportError('Cue ID bị trùng trong kế hoạch phụ đề.')
        cue_ids.add(cue.cue_id)
        text = normalize(cue.text)
        if not text or len(text) > MAX_TEXT_CHARS or cue.end_ms <= cue.start_ms or cue.start_ms < 0:
            raise SubtitleImportError('Cue trong kế hoạch phụ đề không hợp lệ.')
        grouped.setdefault(text, []).append(cue.cue_id)
    items = tuple(SubtitleItem(f'item-{index + 1:05d}', text, tuple(ids))
                  for index, (text, ids) in enumerate(grouped.items()))
    batches, pending, chars, tokens = [], [], 0, 0
    for item in items:
        estimate = estimate_input_tokens(item.text)
        if len(item.text) > max_batch_chars or estimate > max_batch_estimated_tokens:
            raise SubtitleImportError('Một câu vượt giới hạn batch; hãy tách câu trước khi nhập.')
        if pending and (len(pending) >= max_batch_items or chars + len(item.text) > max_batch_chars
                        or tokens + estimate > max_batch_estimated_tokens):
            batches.append(SubtitleBatch(f'batch-{len(batches) + 1:05d}', tuple(pending), tokens))
            pending, chars, tokens = [], 0, 0
        pending.append(item)
        chars += len(item.text)
        tokens += estimate
    if pending:
        batches.append(SubtitleBatch(f'batch-{len(batches) + 1:05d}', tuple(pending), tokens))
    return SubtitlePlan(document, items, tuple(batches))
