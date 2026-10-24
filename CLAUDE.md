# Gemini Live OCR Translator — hướng dẫn cho Claude

Đây là dự án hiện có (Android + Windows Companion). Mở rộng mã hiện tại, không tạo lại dự án.
Đọc trước: README.md, GEMINI.md, docs/GITHUB_COLLABORATION.md. Các quy tắc trong GEMINI.md
(bảo vệ riêng tư, không chạm khóa/credential, giữ guard bất đồng bộ của pipeline) áp dụng cho cả Claude.

## Vai trò và nhánh
- Claude: review và đề xuất sửa trên nhánh `claude-review`, worktree riêng `.worktrees/claude-review`.
- Codex: phát triển trên `codex-dev`. Gemini: review/test trên `gemini-review`.
- Không sửa worktree hoặc nhánh của AI khác. Không để hai AI cùng sửa một worktree.

## Quy tắc
1. Không commit, push, merge hay tạo PR nếu chủ dự án chưa cho phép đúng hành động đó.
2. Không đọc `.env*`, `.gemini/`, `.tools/signing/`, token, SQLite, cache, video riêng tư.
3. Không gọi Gemini API thật hay benchmark mạng khi chưa được cho phép (tốn quota).
4. Mỗi phát hiện phải ghi: file/hàm liên quan, cách tái hiện, mức độ, đề xuất sửa.
5. Đề xuất vá phải kèm test; nói rõ test nào đã chạy và test nào chưa.
6. Không khẳng định "một giây" nếu chưa có phép đo đầy đủ (mẫu mới, không cache, có số lỗi).
7. Ghi báo cáo vào file cục bộ (ví dụ `docs/reviews/claude-YYYY-MM-DD.md`) trước, rồi đợi chủ dự án duyệt.

## Lệnh test
```powershell
python -m pytest tests -q --basetemp=build/pytest-claude
python scripts/build_android.py testDebugUnitTest lintDebug
```

