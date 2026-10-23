# Phối hợp Codex và Gemini CLI

Đây là dự án Gemini Live OCR Translator hiện có, gồm Android App, Windows Companion, Gemini Translation Engine, AI Video Translator và Translation Memory. Việc đưa lên GitHub không tạo lại ứng dụng.

## Trạng thái chuyển giao

Repo mới là [hieutranminh099/gemini-live-ocr-translator](https://github.com/hieutranminh099/gemini-live-ocr-translator), **Private**, được tạo theo phương án chủ dự án đã phê duyệt. Không sử dụng hoặc thay đổi repo `instant-ocr-translator` đang tồn tại. Lần nhập đầu tiên giữ mã nguồn hiện có; kết quả đối chiếu SHA sau push được ghi trong báo cáo migration cục bộ ở `build/github-preflight/`.

Checkout hiện tại đang có mã phát triển 1.7.1 và các sửa hiệu năng chưa phát hành; ứng dụng đã cài vẫn là 1.7.0. Không reset về bản cũ để tạo repo. Số đo một giây cho mọi câu mới chưa đạt, không được ghi thành kết quả đã đạt.

## Nhánh và worktree

Checkout gốc giữ nhánh `main`; hai nhánh làm việc riêng theo phương án đã duyệt:

| Vai trò | Nhánh | Thư mục tương đối từ checkout gốc |
|---|---|---|
| Phát triển/sửa lỗi | `codex-dev` | `.worktrees/codex-dev` |
| Phân tích/kiểm thử/review | `gemini-review` | `.worktrees/gemini-review` |

Thư mục `.worktrees/` được Git bỏ qua. Hai worktree có index, nhánh và file làm việc riêng, cùng chia sẻ lịch sử Git. Không dùng `reset --hard`, `clean`, force push, `push --mirror` hoặc đẩy các ref nội bộ của Codex.

Quy trình: Codex sửa và kiểm thử trên `codex-dev`; Gemini đọc bản commit được giao trên `gemini-review`, ghi nhận xét và test; Codex đánh giá nhận xét, sửa lỗi có bằng chứng và chạy lại test. Chủ dự án quyết định publish/merge. Không để hai AI cùng sửa một worktree.

Trên máy phát triển hiện tại, kiểm tra rồi mở Gemini CLI đã có sẵn từ checkout gốc:

```powershell
powershell -NoProfile -File scripts/start_gemini_review.ps1 -Check
powershell -NoProfile -File scripts/start_gemini_review.ps1
```

Launcher dùng cài đặt CLI cục bộ hiện có, vào đúng `gemini-review` và chọn `--approval-mode plan` (chỉ đọc); không đổi đăng nhập/model/cấu hình Gemini. Trên máy mới, cài Gemini CLI chính thức rồi chạy `gemini --approval-mode plan` trong worktree review. Chỉ giao phạm vi source đã được cho phép; một CLI khởi động được chưa chứng minh đã có review thành công.

## Cấu hình và thông tin riêng tư

Không commit API key, mật khẩu, `.env*`, thư mục xác thực `.gemini/`, token OAuth, khóa ký Android, chứng chỉ/khóa máy chủ, SQLite, cache, log, video người dùng, build hoặc runtime tải về. Không xóa các file đang được ứng dụng sử dụng. Không thay đổi cấu hình Gemini đang có để thực hiện việc chuyển repo.

`GEMINI.md` mô tả phạm vi review. Ban đầu chủ dự án chỉ cho phép gửi bốn file `manager.py`, `server.py`, `provider.py`, `CaptureWakeup.kt` tới Gemini API. Quyền này không cho phép gửi khóa hay dữ liệu video. Khi dùng Gemini CLI cho phạm vi rộng hơn, chủ dự án phải xác nhận phạm vi đó.

Chưa bật GitHub Actions gọi Gemini, chưa thêm repository secret và chưa tạo review tự động. Có thể chuẩn bị workflow chạy thủ công sau khi owner chọn phương thức xác thực và đồng ý phạm vi mã được gửi. Không ghi khóa hiện tại vào repository.

## Chạy Windows từ mã nguồn

Python 3.12 và các dependency của `windows-server/requirements.txt` là cấu hình đã kiểm thử trên Windows. Từ từng worktree có thể tạo `.venv` riêng:

```powershell
python -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -r windows-server/requirements.txt
python -m pip install pytest pytest-asyncio
$env:PYTHONPATH = 'windows-server'
python -m pytest tests -q --basetemp=build/pytest-review
```

Chạy Companion thử nghiệm bằng **thư mục dữ liệu và cổng riêng**, để giữ nguyên bản đang chạy:

```powershell
python windows-server/launcher.py --data-dir .runtime-review --port 8965 --admin-port 8966 --no-browser
```

Không tự sao chép khóa, ghép cặp hoặc SQLite người dùng vào môi trường review. Các unit test dùng fake provider; benchmark thật cần quyền riêng. OCR/video thật cần FFmpeg/model:

```powershell
python scripts/setup_video_tools.py
python scripts/setup_video_models.py --speech
```

Các setup script chỉ tải runtime vào thư mục dự án khi được gọi rõ ràng. Không chạy hai server thử nghiệm cùng cổng.

## Chạy Android

Mở `android-app/` bằng Android Studio, dùng JDK 17 và Android SDK. Có thể dùng công cụ thiết lập của dự án:

```powershell
python scripts/bootstrap_android.py
python scripts/build_android.py assembleDebug testDebugUnitTest lintDebug
```

Runtime/SDK và `local.properties` chỉ có trên máy, không ở GitHub. Mỗi worktree giữ build và cấu hình local riêng; không chia sẻ file build đang ghi. APK phát hành cần khóa ký local đã có; không tạo khóa thay thế hoặc đưa khóa đó lên GitHub.

Các phép thử BlueStacks, ADB, cài APK, khởi động MediaProjection và Gemini thật chỉ chạy khi được chủ dự án cho phép, vì chúng có thể thay đổi phiên đang xem hoặc tiêu thụ quota.

## Giữ lịch sử

Nếu kiểm tra phát hiện không có commit người dùng, commit đầu tiên sẽ là bản nhập nguyên trạng đã quét. Không thể tạo lại lịch sử phát triển chưa từng được commit. Các checkpoint nội bộ của Codex được bảo toàn tại máy và trong bản sao lưu Git; không biến chúng thành lịch sử công khai hay đẩy lên remote ngoài ý muốn.

Sau push phải kiểm tra qua GitHub: URL thật, visibility Private, SHA của các nhánh và các file module chính. Chỉ khi những kiểm tra đó đạt mới báo đã đồng bộ.
