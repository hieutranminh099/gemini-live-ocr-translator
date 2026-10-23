# Sửa trạng thái chờ thử lại — Android 1.6.1

**Báo cáo lịch sử.** Sau khi người dùng cung cấp khóa, máy đã chuyển sang API ở Android 1.6.2 / Windows 1.6.0; xem [API_RESTORE_V162.md](API_RESTORE_V162.md). Tình trạng “chưa có backend câu mới” dưới đây mô tả thời điểm 1.6.1, không phải cấu hình hiện tại.

Ngày 23/10/2026. Đã sửa trực tiếp Android, build/ký/cài đè trên BlueStacks Pie64 Android 9, giữ VÙNG [233,496,809,538] và ghép cặp. Windows giữ bản 1.5.0, không đổi provider/xác thực hoặc bật API/local.

## Nguyên nhân thực tế

Windows trả `cli_configuration`: consumer Antigravity CLI không được hỗ trợ làm backend ứng dụng. Android nhận đúng lỗi, nhưng handler đặt `retryAt=now+60000` và xóa `sentSignature`. Khung OCR kế tiếp gọi `sendLatest()`, ghi đè lý do thật bằng **Đã đọc chữ • Đang chờ thử lại Gemini**. Hết 60 giây gửi lại cùng câu, tiếp tục nhận lỗi không thể tự hết.

Log đọc đầu phiên sửa có hai lần gửi cùng hash nguồn/generation ở 25.258.968 và 25.319.025 ms, cách **60.057 ms**. Server log xác nhận thông báo cấu hình tương ứng. Ảnh người dùng là bằng chứng trạng thái nhìn thấy; UIAutomator không đọc được cửa sổ nổi này trong snapshot. [Baseline metadata](results/retry-baseline-v160.json).

Đây là lỗi xử lý/hiển thị trạng thái Android, không phải Gemini đang suy luận chậm hoặc OCR chưa đọc được.

## Sửa mã nguồn

- `capture/TranslationRetryState.kt`: giữ nguyên lý do; lỗi cấu hình/xác thực không tự thử lại theo đồng hồ. Ghi nhớ tối đa 256 khóa ngôn ngữ/profile/mode/câu trong RAM, tránh câu A→B→A lặp lỗi. Câu chưa gặp vẫn được tra server cache; RAM cache luôn đi trước gate.
- `capture/ScreenCaptureService.kt`: dùng policy mới, xóa `pendingToken` khi xử lý lỗi hiện tại, kiểm tra request/vùng/generation của lỗi. Lỗi cũ không ghi đè câu mới. Trace `request_failed` chỉ có mã/metadata và cờ `automatic_retry`, không ghi OCR/token/message.
- Lỗi tạm thời vẫn giữ backoff toàn phiên: 15 giây rate/quota, 60 giây daily-limit, 5 giây lỗi tạm khác. Giữ lý do và countdown thật, không đổi thành nhãn chờ chung.
- `hello`/`invalidate` hoặc người dùng bấm **1 KHUNG** cho phép thử lại. Tạm dừng/đổi vùng không tự xóa latch cấu hình. Xóa hoặc sửa cache/từ điển/cấu hình trên Windows phát invalidate như trước.
- `app/build.gradle.kts`: versionName 1.6.1 / code12.

Các file Kotlin sản phẩm ở `android-app/app/src/main/java/com/gemini/ocr/`. Hai test mới ở `android-app/app/src/test/java/com/gemini/ocr/capture/TranslationRetryStateTest.kt` và `ConfigurationFailureCaptureTest.kt`. Script kiểm tra native: `scripts/verify_configuration_retry.py`. README, hướng dẫn phụ đề/AI Pro/Redmi và changelog được cập nhật.

## Kiểm thử đã chạy

**91 JVM tests đạt**, gồm 16 test mới: lỗi không hết hạn sau 60 giây, giữ lý do qua nhiều lần `onMessage→sendLatest`, lỗi A cũ không ảnh hưởng B, metadata sai, cache B, giới hạn bộ nhớ lỗi, phục hồi qua invalidate/1 KHUNG và countdown tạm thời. Release build thành công; lint **0 lỗi/43 cảnh báo**. Không chạy lại backend vì không sửa Windows trong bản này.

Kiểm tra native trên đúng APK đã giao:

| Tình huống | Kết quả |
|---|---|
| Câu công khai chưa có cache → lỗi cấu hình | Nhận `cli_configuration`, `automatic_retry=false` |
| Giữ câu 66,12 giây, vượt mốc retry cũ | Đúng **1 request**, không tự gửi lại, không overlay dịch giả |
| Chuyển sang câu có cache Windows | Vẽ đúng source và translation hash, requestId khớp response, provider=0 ms |
| Quay về câu lỗi trước | Không gửi lại |
| Lượt gọi AI mới | **0**; usage trước/sau không tăng |

Đã xem ảnh trước và sau thời gian giữ: thông báo ổn định, đọc được đầy đủ hai dòng **CLI AI Pro không hỗ trợ dịch câu mới / Chỉ dùng cache • Mở app xem chi tiết**. [JSON native](results/configuration-retry-v161.json), [ảnh sau kiểm tra](images/configuration-error-after-hold-v161.png), [audit release](results/release-v161.json).

Đây là kiểm tra correctness cho lỗi cấu hình, không phải benchmark latency mới. Phân vị 100 lượt cache của 1.6.0 là lịch sử ở [báo cáo riêng](PERFORMANCE_V160.md), chưa đo lại 100 lượt trên 1.6.1. Chưa nghiệm thu Redmi thật.

## Trạng thái dịch câu mới và artifact

**Bản này sửa lỗi chờ sai, chưa khôi phục backend cho câu mới.** [FAQ Google](https://www.antigravity.google/docs/faq/) không hỗ trợ consumer CLI wrapping; app giữ chặn đó. Theo lựa chọn chưa dùng API, chỉ cache/từ điển hiện có dùng được. Prototype local vẫn chưa đạt ngưỡng chất lượng; không bật Hybrid để che hạn chế. Không thay đổi phương thức xác thực hoặc tự mở thanh toán.

[APK Android 1.6.1](../dist/GeminiOCR-Android.apk), [mã nguồn](../dist/GeminiOCR-Source.zip), [SHA256SUMS](../dist/SHA256SUMS.txt). SHA256 APK **f526b5380bc9896843cc4bd1ca8934d7d9d9df9687988eb25292435dadf6e2b0** khớp bản đã cài/kiểm tra. APK xác minh chữ ký v2/v3, cùng certificate; không cần xóa dữ liệu để cập nhật. EXE/setup Windows 1.5.0 không đổi.
