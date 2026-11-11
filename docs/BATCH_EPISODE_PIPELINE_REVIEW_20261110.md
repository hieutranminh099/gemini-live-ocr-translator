# Cập nhật nghiệm thu 1.8.13 — 10/11/2026

Đã hoàn thiện pipeline, đóng gói APK 1.8.13 / 38 và chạy đạt 635 kiểm thử trong 110 nhóm. [Bản sửa và kết quả cài đặt](BATCH_PIPELINE_V1813.md).

Phần dưới là biên bản bàn giao source 1.8.12 trước khi khắc phục môi trường build và chạy kiểm thử tích hợp. Các mục chưa chạy/không có APK trong phần lịch sử đã được thay thế bởi kết quả 1.8.13 ở liên kết trên.

---

# Batch Episode Pipeline tích hợp trên source 1.8.12 — 10/11/2026

## Kết quả và giới hạn nghiệm thu

**Đã nối BatchEpisodePipeline vào BatchJobs trong source hiện tại.** Vòng điều phối
stage trước/tập sau đã được thay bằng pipeline từng tập; không còn class điều phối
đứng riêng chưa được dùng. Chưa có APK mới hoặc xác nhận hoạt động trên thiết bị.

**15 test JVM độc lập đạt**: 11 ca trước đây được giữ nguyên, hai ca bổ sung cho
playback/extraction và hai ca quyền ưu tiên playback. Compiler trực tiếp cũng kiểm
tra BatchParallelism/BatchResourceAdmission với SDK 35. **8 test tích hợp mới** dùng
BatchJobs/VideoJobs/AtomicFile trong Robolectric đã viết nhưng chưa chạy được.
Kotlin PSI kiểm tra cú pháp 14 file; không thay thế type checking/compile toàn app.

Gradle test và assembleDebug đều dừng trước compile: socket localhost bị hệ điều hành
từ chối (`java.net.SocketException: Permission denied: getsockopt`). Daemon khởi động
và mở port được; client không kết nối được 127.0.0.1. Thử --no-daemon, --max-workers=1
và JVM client khớp cấu hình vẫn gặp cùng giới hạn. Không đổi quyền mạng, firewall,
DNS/proxy hoặc JDK/Gradle. Cache thiếu Media3 1.5.1 và một số dependency của source
mới; đọc metadata Maven Google bằng kết nối chuẩn cũng thất bại DNS. Không xóa cache.

## Bảo vệ source 1.8.12

- Nhánh `codex/app-current`, HEAD `c9aea76`. HEAD khai báo 1.8.6 / 31; working source
  khai báo 1.8.12 / 37. Không dùng commit cũ thay thế working source mới.
- Đã đọc AGENTS, mục lục, kiến trúc, hướng dẫn tích hợp/kiểm thử và diff liên quan.
  Theo bàn giao rõ của chủ dự án trong lượt này, tích hợp có kiểm soát ngay tại checkout;
  không sửa quyền `.git`, không dùng worktree của người khác.
- Trước sửa đã lưu `before-integration.diff`, `before-status.txt`, hash 674 file source/
  tài liệu và bản sao file được sửa tại `build/batch-integration-20261110-v1812/`.
- Đối chiếu `docs/APPEND_EPISODES_V1812.md`: giữ luồng thêm tập mới vào bộ phim hiện
  có, targetMovieId, giữ tập/vị trí xem cũ và tua chọn vùng OCR. Các hàm liên quan
  ngoài phạm vi điều phối và file Kho phim/region dialog không bị thay đổi.
- Không thể gán từng dòng chưa commit cho một release trung gian chỉ bằng lịch sử
  Git hiện tại; toàn bộ working source được bảo vệ làm nền bàn giao. Không commit,
  push, merge, reset, clean, stash, đổi signing/credential hoặc cài ứng dụng.

## File sửa và mục đích

Các đường dẫn sau tương đối với `android-app/app/src/main/java/com/gemini/ocr/`:

| File | Thay đổi |
|---|---|
| `batch/BatchJobs.kt` | Gọi pipeline thực tế, reconcile từng tập, serialize cập nhật trạng thái, phục hồi reservation khi intent được giao lại; lưu Pause/Cancel trước khi hủy và giữ trạng thái dừng trước checkpoint ghi muộn |
| `batch/BatchEpisodePipeline.kt` | Chặn riêng extractor khi playback; nguồn đã trích vẫn có thể dịch; queue chỉ chuyển ID, giữ giới hạn worker và structured cancellation |
| `batch/BatchParallelism.kt` | Admission đọc RAM định kỳ; giảm ngay, tăng sau khi ổn định; trích và một API worker có thể chạy chồng nếu thiết bị đủ tài nguyên |
| `batch/BatchPlaybackPriority.kt` | File mới được dùng bởi listener player, pipeline và API; theo dõi nhiều player, giữ hai giây sau khi dừng để tránh dao động |
| `batch/BatchProject.kt` | Chuẩn hóa item RUNNING bị gián đoạn sang PAUSED/PENDING; giữ trạng thái batch đã Pause/Cancel và kết quả hoàn thành |
| `batch/BatchProcessingService.kt` | START_REDELIVER_INTENT cho công việc active; intent trùng không để service mắc kẹt với startId cũ; tránh vòng lặp restart khi không đọc được checkpoint |
| `video/VideoJobs.kt` | Cho lane text/FINISH chạy cùng media của project khác; giữ reservation và ngăn trùng project; gate tác vụ nặng khi playback |
| `video/VideoApiRecovery.kt` | Dùng admission có giới hạn playback cho request batch; giữ retry/cooldown/account gate cũ |
| `video/VideoAdaptiveAdmission.kt` | Giảm request mới theo capacity động; request đang chạy được hoàn tất; thức dậy khi capacity thay đổi dù chưa có response mới |
| `video/VideoActivity.kt` | Chỉ thêm tín hiệu ưu tiên từ listener/dispose của VideoPreview dùng chung; giữ nguyên giao diện, player và chức năng 1.8.12 |

Test tại `android-app/app/src/test/java/com/gemini/ocr/batch/`:

- `BatchEpisodePipelineTest.kt`: giữ nguyên 11 test trước, thêm hai ca.
- `BatchPlaybackPriorityTest.kt`: hai ca mới, đã chạy đạt.
- `BatchEpisodeIntegrationTest.kt`: tám ca mới, chưa chạy do môi trường build.
- `BatchOrchestrationTest.kt`: đổi assertion vốn giả định rào cản toàn danh sách;
  giữ kiểm tra quota, không dispatch tập kế tiếp, dữ liệu và timestamp.

Tài liệu cập nhật tại MODULE_INDEX, ARCHITECTURE, TESTING, BATCH_STAGED_PIPELINE và
báo cáo này. Chi tiết hợp đồng nằm trong BATCH_STAGED_PIPELINE, không tạo bản sao
kiến trúc hoặc queue khác.

## Luồng thực tế trong source

`BatchJobs.execute` → `BatchEpisodePipeline.run` → lane EXTRACT/CLEAN → các lane
TRANSLATE/POLISH/FINISH của VideoJobs. Tập đầu được giao dịch trước khi trích tập
tiếp. Khi đủ tài nguyên, extractor đọc tập sau trong lúc API xử lý tập trước.
Channel rendezvous chỉ chứa project ID; số ID do worker giữ bị giới hạn. Không
chuyển bitmap/video/timeline qua queue và không giữ toàn bộ video trong RAM.

VideoJobs cũ vẫn sở hữu OCR/ASR, chọn SRT/embedded → OCR → ASR, chia nhóm/token,
timestamp/ID, cache riêng batch, kiểm tra response, lưu nhóm hợp lệ và retry phần
thiếu. Các stage của từng tập hoàn thành độc lập. Hoàn thiện phụ đề xong mới đánh
dấu DONE; render MP4/duyệt/xác nhận Kho phim tiếp tục theo cổng hiện có.

Pause/Cancel ghi trạng thái bằng AtomicFile trước khi hủy. Checkpoint ghi muộn không
được đổi trạng thái đó về RUNNING. Hủy chờ mọi lane lưu checkpoint và giải phóng rồi
mới thả reservation. Reconcile cấu hình chạy từng tập, tránh một project hỏng chặn
việc đọc mọi project khác. Lỗi riêng tập được ghi; quota/auth/lỗi dịch vụ chung vẫn
theo cơ chế dừng và retry cũ.

Sau process death, service có thể được Android giao lại intent. Chỉ batch bền vững
RUNNING/RETRY_WAIT được phục hồi, lấy lại reservation và giữ deadline/attempt. Intent
trùng không tạo worker thứ hai; intent cũ không khởi động batch người dùng đã dừng.
Force-stop vẫn cần người dùng mở lại/Tiếp tục. Nhóm đã lưu được bỏ qua và câu sửa tay
giữ protectedText. Nếu tiến trình chết sau response nhưng trước khi ghi, nhóm đó
vẫn pending và có thể phải gọi lại. Không tuyên bố exactly-once cho request mơ hồ;
chỉ tiếp tục từ checkpoint bền vững gần nhất.

Khi playback có quyền ưu tiên, không nhận trích mới, tác vụ nặng nhường tại gate,
request mới giảm về một. Response đang hoàn thành vẫn được lưu. Khi dừng xem có
cửa sổ giữ hai giây; số worker tăng lại sau hai giây tài nguyên ổn định. Live vẫn có
gate ưu tiên riêng. Chưa đo ảnh hưởng codec/RAM hoặc độ mượt thực tế.

## Bằng chứng chạy

| Kiểm tra | Kết quả hiện tại |
|---|---|
| Compiler Kotlin 2.1.10 + JDK 17 + JUnit 4.13.2 trực tiếp, coroutines 1.10.1 | **15 test đạt**, không dùng network/AI/phim riêng |
| Kotlin PSI trên 14 file sửa/thêm | Cú pháp đạt; không type-check toàn app |
| Gradle test nhóm batch/recovery/admission sau tích hợp | Bị chặn trước compile bởi socket daemon |
| Gradle assembleDebug sau tích hợp | Cùng lỗi môi trường; **không có APK mới** |
| Kiểm tra tài liệu | 173 link nội bộ hợp lệ, không lỗi |
| Diff | Mặc định exit 2 do CRLF; `git -c core.whitespace=cr-at-eol diff --check` exit 0, giữ line ending gốc |
| Cài/chạy emulator, BlueStacks, điện thoại | Chưa thực hiện |
| Tốc độ xử lý 10 tập và PSS/RAM | Chưa đo |

Runner/log/snapshot tại `build/batch-integration-20261110-v1812/`:

- `run_core_tests.py`, `core-tests.log`: 15 test độc lập.
- `check_kotlin_syntax.py`, `syntax-check.log`: kiểm tra parser, không thay compile.
- `gradle-diagnostic.log`, `gradle-tests-final.log`, `gradle-apk-final.log`: lỗi thật.
- `gradle-inprocess.log`: thử JVM client khớp cấu hình, vẫn single-use daemon bị chặn.
- `session.diff`: phần thay đổi của lượt tích hợp, tách khỏi diff chưa commit trước đó.
- `verification.json`: kiểm tra hash, bảo toàn chức năng 1.8.12 và phạm vi sửa.

## Chưa được xác minh

Tám test tích hợp mới đã viết cho: 10 tập với extractor tập 2 bị giữ và tập 1 hoàn
thành trước tám tập sau; Pause/Resume; Cancel/drain; instance mới từ trạng thái
RUNNING và dispatch lặp; intent cũ của batch đã dừng; response mất trước commit;
lỗi mạng/retry; playback giữ rồi mở lại extractor. Chúng kiểm tra timestamp và câu
sửa tay bằng worker/store thật nhưng **chưa được chạy**. Đó là test JVM/Robolectric,
không phải bằng chứng kill tiến trình thiết bị thật hay OCR/ASR/Gemini thật.

Chưa xác minh compile/type checking toàn app, test tích hợp Android, native media,
process death/force-stop thật, hành vi service trên từng phiên bản Android hoặc
BlueStacks, chất lượng AI và hiệu năng khi phát phim. Không tăng version hoặc cung
cấp APK lịch sử dưới tên bản nâng cấp mới.
