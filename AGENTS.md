# Hướng dẫn làm việc với Gemini OCR

Ứng dụng Android/Windows đã tồn tại. Mở rộng có kiểm soát, giữ mọi chức năng và dữ liệu hiện có. Không tạo lại ứng dụng hoặc refactor hàng loạt để giải quyết một tính năng nhỏ.

Hướng dẫn review riêng cho Claude: [CLAUDE.md](CLAUDE.md). Giữ vai trò và worktree riêng của từng AI.

## Bắt đầu mỗi tác vụ

1. Chạy `git status --short --branch`, `git branch --show-current`, `git worktree list`. Đọc hướng dẫn cấp thư mục nếu có và diff của file định sửa.
2. Mọi thay đổi chưa commit không do phiên này tạo đều phải được bảo vệ. Git sạch không chứng minh không có AI/CLI khác đang làm việc. Không sửa chung file với phiên khác; không checkout/reset/clean/stash/rebase hoặc ghi đè worktree của họ.
3. Checkout gốc có thể chứa tài liệu bàn giao và mã khác phiên bản. Đối chiếu [phạm vi khảo sát](ARCHITECTURE.md#phạm-vi) trước khi suy ra trạng thái ứng dụng đang cài.
4. Chọn chức năng trong [MODULE_INDEX.md](MODULE_INDEX.md), đọc đúng phần [ARCHITECTURE.md](ARCHITECTURE.md) và tìm symbol bằng `rg -n` trong phạm vi module. Chỉ mở đoạn cần thiết; không đọc lại toàn repository mỗi lượt.
5. Nêu kế hoạch ngắn: module, file sẽ sửa, hành vi cần giữ và kiểm thử liên quan. Khi thiếu thông tin, tiếp tục phần độc lập đã được cho phép.

## Phạm vi chỉnh sửa

- Ưu tiên patch nhỏ, một mục tiêu, không kèm đổi định dạng toàn file, đổi framework/dependency hoặc tối ưu suy đoán.
- Giữ API, protocol, settings mặc định, định dạng dữ liệu, đường cập nhật/cài đặt và tương thích ứng dụng cũ trừ khi tác vụ cần thay đổi rõ ràng.
- Thử kiến trúc hoặc tích hợp code chồng lấn trong worktree riêng theo [INTEGRATION_GUIDE.md](INTEGRATION_GUIDE.md); không tự chiếm `codex-dev`/`gemini-review` đã có.
- Không đọc `.env*`, `.gemini/`, `.tools/signing/`, token store, chứng chỉ/khóa, DB/cache/log/video cá nhân hoặc dữ liệu ứng dụng Windows để khảo sát mã. Không đưa chúng vào ngữ cảnh hay artifact.
- Không đổi tài khoản, backend, model, billing hoặc cài đặt hệ thống. Không gửi source/dữ liệu tới AI bên ngoài hay chạy benchmark tốn quota ngoài phạm vi đã được chủ dự án cho phép.
- Không tự commit/push/merge, đăng bình luận hoặc phát hành/cài ứng dụng khi chưa có quyền cho hành động đó. Việc đọc, phân tích, tạo tài liệu mới và kiểm thử offline liên quan đã được giao không cần xin lại quyền.
- Giữ các quy tắc review hiện có tại [GEMINI.md](GEMINI.md) và [GITHUB_COLLABORATION.md](docs/GITHUB_COLLABORATION.md); dùng hướng dẫn tích hợp mới cho checklist nhận patch.

## Các bất biến cần bảo vệ

- Capture, OCR, dịch, vẽ phải bất đồng bộ; latest-frame mailbox và một OCR consumer; native OCR giữ bitmap đến khi xong.
- Không bỏ request/region/generation/epoch guards, loại response cũ và xác nhận OCR sau response trước khi tự vẽ. Nguồn biến mất phải xóa trạng thái/overlay phù hợp.
- Không gộp cache theo fuzzy text; giữ namespace ngôn ngữ/profile/mode/provider/model/glossary, invalidation và cooldown quota/cấu hình qua rescan.
- RAM `TranslationMemory` Android khác kho SQLite Windows. Kho Windows opt-in; AI ghi Draft, không ghi đè User Approved. Video private không rò vào cache/ngữ cảnh/kho chung.
- Giữ ưu tiên Live trên video/prewarm, hủy tác vụ, checkpoint, TLS certificate pinning, pairing một lần, token Keystore/DPAPI và admin loopback.
- CLI cá nhân vẫn là tính năng thử nghiệm opt-in; không mô tả như backend được hỗ trợ. Video/prewarm dùng API chính thức. Không tự chuyển backend khi lỗi.

## Chu trình hoàn thành tính năng

1. Xác định module và hợp đồng bị ảnh hưởng bằng mục lục.
2. Đọc file/symbol cần thiết và lập kế hoạch ngắn.
3. Viết patch tập trung, giữ thay đổi của người khác.
4. Chạy kiểm thử module; chạy tích hợp khi chạm protocol, storage, scheduler hoặc ranh giới module.
5. Đối chiếu bất biến/chức năng cũ, xem diff và `git diff --check`.
6. Cập nhật đúng tài liệu khi vị trí, kiến trúc, hợp đồng hoặc cách kiểm thử thay đổi; không tạo bản sao nội dung.
7. Báo kết quả, file sửa, lệnh/kết quả thực chạy và phần chưa xác minh. Không dùng số liệu lịch sử thay cho kiểm thử hiện tại.

## Lệnh nhanh và quản lý ngữ cảnh

Từ root, dùng Python/dependency đã thiết lập theo [TESTING.md](TESTING.md):

```powershell
python scripts/check_codex_docs.py
python -m pytest tests/test_subtitle_import.py -q -p no:cacheprovider --basetemp=build/pytest-<task-id>
python scripts/build_android.py --offline testDebugUnitTest --tests com.gemini.ocr.capture.SubtitlePipelineTest
git diff --check
```

Thay `<task-id>` bằng tên riêng của lượt chạy. Build/test đầy đủ và tác dụng phụ nằm ở TESTING; không chạy mặc định lệnh đóng gói hoặc native smoke.

- Tìm tên file trước bằng `rg --files <module>`; tìm symbol bằng `rg -n '<symbol>' <file-or-module>`. Mở rộng phạm vi chỉ khi kết quả không đủ.
- Bỏ qua `.git/`, `.worktrees/`, `.tools/`, `.python-deps/`, `.gradle-home/`, `build/`, `dist/`, runtime và ảnh khi định vị source. Chỉ xem output cụ thể khi cần chẩn đoán.
- Đọc summary và lỗi đầu/cuối log; giữ log dài ở thư mục build riêng. Không import script tiện ích chỉ để khảo sát: một số script có tác dụng phụ ở cấp module.
- Mỗi tài liệu có một trách nhiệm: quy tắc ở đây; sơ đồ/rủi ro ở ARCHITECTURE; vị trí ở MODULE_INDEX; lệnh/bằng chứng ở TESTING; bàn giao patch ở INTEGRATION_GUIDE.
- Không quy số dòng/byte/file thành token hoặc phần trăm tiết kiệm. Chỉ báo token khi có bộ đếm thực cho cùng phạm vi; thiếu đối chứng thì ghi rõ.
