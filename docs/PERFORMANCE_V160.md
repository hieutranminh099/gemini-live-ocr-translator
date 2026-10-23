# Đo hiệu năng — Android 1.6.0 / Windows 1.5.0

**Báo cáo lịch sử của APK 1.6.0.** Bản hiện tại là Android1.6.2/Windows1.6.0 dùng API; xem [API_RESTORE_V162.md](API_RESTORE_V162.md). Các phân vị100 lượt cache dưới đây chưa được đo lại trên bản mới. Hash APK ở cuối là artifact lịch sử, không phải APK đang nằm trong dist. Bản sửa retry1.6.1 được giữ ở [báo cáo riêng](RETRY_FIX_V161.md).

Ngày 23/10/2026, BlueStacks Pie64 Android 9, 1600×900; CPU i5-13500H, RAM 16 GB, RTX 3050 Laptop 6 GiB. Vùng người dùng giữ nguyên [233,496,809,538]. Không tạo lại dự án, xóa dữ liệu hoặc đổi cấu hình hệ thống. Cài đè cùng chữ ký, giữ ghép cặp/chứng chỉ/thiết lập.

**Chưa đạt hoặc xác nhận P95 ≤ 1 giây cho câu mới.** Cache đạt dưới một giây trên cảnh thử; local engine rất nhanh nhưng chất lượng không đạt. Gemini API chưa đo theo lựa chọn “Tiếp tục phần local, chưa dùng API”. Không đưa Hybrid vào bản chính.

## 1. Số 2.547s thực sự là gì?

Trước đây Android lấy latency_ms từ response Windows rồi chia 1000 và hiện trên thanh nổi. TranslationManager đo thời gian xử lý request ở Windows cộng thời gian nằm trong latest-request mailbox: gồm cache, semaphore/provider queue và provider round-trip; kết thúc trước gửi WebSocket response. Đây không phải source→overlay, không phải chỉ thời gian model suy luận, cũng không phải số giây đang chờ đang trôi qua. Nhãn progress “Đang dịch Ns” là một đồng hồ chờ khác.

1.6.0 ghi rõ **Máy chủ: N ms** và **Khung OCR → hiện: N ms**. Số thứ hai bắt đầu ở ảnh phát hiện câu, kết thúc ở overlay onDraw; chưa gồm khoảng chờ từ lúc chữ xuất hiện đến khi lấy khung. Benchmark dùng native TestScene source onDraw và overlay onDraw cùng elapsedRealtime Android, đo cả khoảng đó. onDraw là callback Canvas, chưa đo riêng thời điểm GPU/display thực sự trình chiếu pixel.

Telemetry tách capture, hàng đợi OCR, OCR, tuổi image, nhịp thích nghi, stabilization/debounce, gửi request, RTT Android↔Windows, mailbox/provider queue, provider, nhận và vẽ overlay. RTT trừ server chỉ là phần dư gồm transport/serialize/lập lịch, không tách riêng network thuần hoặc compute bên Google. Không cộng các P95 từng công đoạn thành P95 tổng. Các stage có n nhỏ và zero sentinel của RAM cache không được gán thành “network 0 ms”.

## 2. Before/After trên Android, cache riêng

Hai cohort chính, mỗi cohort 100 lượt chuyển giữa **6 câu công khai đã có cache**, cùng VÙNG, cỡ chữ native 24sp, Windows 1.5.0, tracking tắt, cache không bị xóa. Mỗi cohort bắt đầu phiên Android mới: 6 lượt cache Windows và 94 lượt RAM Android. Tỷ lệ cache 100% do thiết kế fixture, không đại diện video thật hoặc tỷ lệ cache của người dùng. Không gửi AI mới, không đánh giá chất lượng Gemini từ việc đối chiếu cache.

| Chỉ số source→overlay | Before 1.5.1 (ms) | After 1.6.0 APK cuối (ms) |
|---|---:|---:|
| P50 | 222,5 | 212 |
| P90 | 348 | 321,4 |
| P95 | 387,5 | 368,95 |
| P99 | 549,72 | 463,55 |
| Max | 819 | 518 |
| RAM cache P95, n=94 | 372,25 | 340,95 |
| Server cache P95, n=6 nhỏ | 751 | 489,25 |

P95 giảm khoảng 4,79% trong hai cohort này; không chứng minh tăng tốc câu mới.

Dữ liệu: [before 1.5.1](../research/results/android-cache-before-v151.json), [after APK giao](../research/results/android-cache-after-v160.json), [so sánh tái tính](../research/results/android-cache-comparison.json). Cả hai 100/100 thành công; 0 AI mới, 0 lỗi, 0 source/translation hash mismatch, 0 overlay draw sai nguồn theo cảnh native và theo OCR đã biết. Đây không bảo đảm đúng mọi chuyển cảnh thực tế.

Một cohort 1.6.0 ban đầu được giữ ở [initial](../research/results/android-cache-after-v160-initial.json); sau đó phát hiện/sửa lỗi fingerprint ở tracking thử nghiệm và đo lại đủ 100 lượt trên APK cuối. Bảng chỉ dùng cohort cuối. Không gộp mẫu của hai build hoặc chọn câu nhanh nhất.

| Công đoạn P95 | n mỗi cohort | Before ms | After ms |
|---|---:|---:|---:|
| Capture/copy ROI | 100 | 11 | 10 |
| Hàng đợi OCR | 100 | 25 | 24,65 |
| OCR | 100 | 168,1 | 177,1 |
| Sampling/detection residual (ước lượng) | 100 | 250,05 | 206,15 |
| Overlay submit→onDraw | 100 | 22,1 | 23,25 |
| RTT Android↔Windows, chỉ server cache | 6 | 46 | 30,25 |
| Response→draw, gồm OCR xác nhận | 6 | 366,75 | 229,25 |

OCR P95 168,1→177,1 ms và overlay P95 22,1→23,25 ms tăng nhẹ, không báo hai công đoạn này nhanh hơn. Provider/queue server bằng 0 ở cache, không là số đo Gemini. Gate debounce chỉ áp dụng AI mới nên cohort cache không đo độ trễ debounce của AI.

Các phân vị nội suy tuyến tính. Một pass mỗi APK, không random/crossover nhiều vòng nên chênh lệch có thể gồm nhiễu Windows/giả lập; không tuyên bố cải thiện có ý nghĩa thống kê. Capture/OCR/sampling là phần đáng kể trên đường cache. Trong đường câu mới lịch sử, provider chiếm nhiều giây; không thể dùng tối ưu vài ms OCR để hứa mọi câu mới dưới một giây.

## 3. Provider và phần chưa benchmark

Đã kiểm tra cấu hình đang chạy: provider CLI, stock Antigravity 1.4.5, API chưa cấu hình. CLI cũ tái sử dụng tiến trình theo thiết bị/cấu hình/model, không khởi động lại cho mỗi câu; xoay sau 32 lượt/32 KiB, 180 giây rảnh hoặc lỗi/reset. Mẫu lịch sử cold có OS process creation 31 ms, first agent text 6922 ms và kết quả 6937 ms. Khoảng first text→final 0–31 ms trong mẫu cũ, nên chưa có bằng chứng streaming token đầu sẽ giảm nhiều thời gian tới bản dịch đủ dùng. First agent text gồm remote/transport, không cô lập compute Google.

[FAQ Google](https://www.antigravity.google/docs/faq/) xác nhận wrapping agy trong ứng dụng bên thứ ba không được hỗ trợ trên consumer plans. Dùng binary chính thức/không trích token vẫn chưa đủ. Giả định cũ của dự án về việc này sai. Windows 1.5.0 chặn provider trước khởi chạy và trước reserve hạn mức; giữ dữ liệu/tài khoản/cache, không tự đổi xác thực/provider. UI giữ lựa chọn cũ nhưng khóa nút cài/đăng nhập/kiểm tra/turbo. **Với lựa chọn chưa API hiện tại, câu mới không có backend dịch trong bản chính.**

API implementation dùng official generateContent và tái sử dụng AsyncClient, không dùng Interactions nên không có store=true để tối ưu ở production. Giới hạn đầu ra cũ 8192 có thể thu hẹp nhưng chưa có benchmark để kết luận tác động. Không đổi production theo phỏng đoán khi chưa có API key/quyền gọi.

Harness tách biệt: [README](../research/gemini_api/README.md), [support audit](../research/gemini_api/support-audit.md), [kế hoạch not_run](../research/gemini_api/api-results.json). Có prompt gọn, giới hạn 256 token, streaming/non-streaming, kết nối tái sử dụng, first complete useful translation riêng với first token/final; tùy chọn so Interactions store false/true. **9 test offline mock đạt; kế hoạch 100 câu × 4 = 400 requests, thực gửi 0.** Không có latency/quality/token/cost API thực, không suy từ dry-run.

[Gemini 3.5 Flash-Lite](https://ai.google.dev/gemini-api/docs/models/gemini-3.5-flash-lite) và [thinking guide](https://ai.google.dev/gemini-api/docs/thinking) đã đối chiếu: stable ID gemini-3.5-flash-lite, hỗ trợ minimal. Flash 3.8 dùng low, không minimal. Không khẳng định model được quảng bá nhanh sẽ đạt một giây trên máy/mạng này.

## 4. Prototype local: 100 câu tham chiếu, 800 inference

Dịch riêng bằng CTranslate2 4.8.3 + OPUS-MT zh→vi, batch=1, không cache. 8 cấu hình × cùng 100 câu nguồn riêng biệt, tham chiếu độc lập từ test Helsinki-NLP/Tatoeba-Challenge; không dùng output model làm đáp án. Các câu gần hội thoại ngắn, không phải phim đối thoại liên tiếp; chưa kiểm toán trùng dữ liệu huấn luyện, không hội đồng người dịch mù. [Corpus, provenance, license, 8 cấu hình và raw output](../research/local_translation/README.md).

| Engine local | n | P50 ms | P90 ms | P95 ms | P99 ms | chrF |
|---|---:|---:|---:|---:|---:|---:|
| CPU INT8, 8 thread, beam1 | 100 | 21,012 | 35,851 | 41,470 | 48,278 | 38,06 |
| CPU INT8, 4 thread, beam4 | 100 | 33,425 | 56,045 | 66,013 | 83,143 | 38,59 |
| CPU FP32, 4 thread, beam4 | 100 | 98,085 | 167,324 | 183,955 | 219,939 | 38,54 |
| RTX 3050 INT8/FP16, beam4 | 100 | 31,242 | 50,174 | 67,620 | 76,567 | 38,87 |

Thời gian trên gồm normalize/encode/inference/decode; **không gồm capture/OCR/Windows network/overlay**, không là tốc độ app. CPU cùng 4 thread/beam4: INT8 giảm P95 183,955→66,013 ms và peak RSS 533→147,8 MiB so FP32, chrF gần như không đổi. Cấu hình greedy 4 thread load 202,86 ms; inference đầu 40,09 ms. RSS là RAM process, chưa đo peak VRAM hoặc điện năng.

GPU thực chạy đủ 100 câu mỗi cấu hình sau hai probe thiếu cublas64_12.dll. Chỉ tải DLL NVIDIA riêng vào .tools/ và preload trong process nghiên cứu, không đổi driver/PATH/hệ thống. Hai probe lỗi được giữ riêng. Tất cả model/runtime/wheel/DLL nghiên cứu khoảng 2,3 GiB nằm ngoài APK/bộ cài/source ZIP.

Ngưỡng đặt trước: chrF ≥55 và không có lỗi phủ định/con số nghiêm trọng. **Cả 8 cấu hình không đạt**, best chrF 38,87. Đọc chẩn đoán đủ 100 output greedy ghi nhận 5 lỗi phủ định/con số nghiêm trọng, không công bố accuracy%. Ví dụ:

- 你去就免得我再去了。 → “Nếu anh đi thì tôi sẽ đi.”, đảo nghĩa “anh đi thì tôi khỏi đi”.
- 二百五十万 (2,5 triệu) → 2 triệu ở greedy, 250 triệu ở beam4/FP32.
- 相見恨晚 → “những kẻ thù của nhau”, sai thành ngữ.

Vì vậy **không nối Hybrid vào app chính**. Chưa có số thời gian local-first trên Android, thời gian Gemini sửa local, số lần bản dịch bị sửa/nháy Hybrid. Không cộng số engine local với OCR rồi trình bày thành benchmark end-to-end.

## 5. So sánh phương án và quyết định

| Phương án | Dữ liệu thực hiện có | Kết luận |
|---|---|---|
| Gemini CLI trước đây | Lịch sử n=6 câu mới: E2E P50 3351 / P90 6027,5 / P95 6690,25 ms; ca B chờ A 13183 ms | Chậm; không gọi benchmark mới do consumer backend không hỗ trợ |
| Gemini Flash-Lite API | 0 requests; harness/dry-run sẵn | Chưa so được latency/chất lượng/chi phí vì người dùng chưa API |
| Hybrid local + Gemini | Local engine 100 câu/config nhanh nhưng chất lượng không đạt; không gọi Gemini refine | Không triển khai, chưa có benchmark Hybrid end-to-end |
| Cache hiện có | 100 before + 100 after native source→overlay, số trong bảng | Chỉ xác nhận đường câu đã dịch; không thay thế phép đo câu mới |

Số lịch sử n=6 và các ca chậm ở [báo cáo 1.5.0](VERIFICATION.md), [raw pipeline](results/pipeline-device-v150.json). Không trộn n=6 lịch sử với 100 cache hoặc 100 local để giả đủ 100–200 Gemini misses. Chưa đo hội thoại phim liên tiếp trên backend được hỗ trợ. Chi phí thực local/cloud, chất lượng Google trên corpus này, tỷ lệ cache video thật và tần suất Gemini sửa output local đều chưa có dữ liệu.

## 6. Sửa trực tiếp và kiểm thử

- Capture/OCR/translation/overlay tách giai đoạn, latest bitmap một slot/một OCR và metadata chống stale từ 1.5.0 được giữ. 1.6.0 thêm nhãn thời gian đúng nguồn đo và debounce ngắn cho OCR dao động.
- VÙNG giữ thao tác người dùng; thêm tracking chiều dọc có giới hạn, mặc định tắt, hai quan sát, rescan 750 ms, mất nguồn trở về toàn VÙNG. Chưa đo lợi ích bật tracking. Sửa lỗi so CRC khác crop: chỉ loại OCR theo pixel mới hơn khi hình học vùng giống nhau, để full scan có thể reacquire.
- CJK auto không fallback sang dòng Latin/pinyin/Việt khi nguồn CJK biến mất; giữ mảnh cùng dòng. Không khẳng định ML Kit khôi phục mọi dấu gạch dưới/câu thiếu.
- Dải tự động thu toàn màn hình vẫn ngoài vùng OCR, không animation/ẩn định kỳ. Câu thực sự đổi/mất thì xóa bản dịch cũ; không giữ chữ sai để che nháy.
- Production consumer CLI bị chặn bằng kiểm tra cấu hình; test-only command_override chỉ cho mock/fake trong test, không là setting người dùng.

Android: **75 JVM tests đạt, 0 failure/error**, gồm A/B/C, offline/retry, source rows/tracking/debounce/fingerprint. Lint **0 lỗi, 43 cảnh báo**; không tuyên bố sạch cảnh báo. Release build/ký/cài đè thành công. Backend: **59 test đạt**, bao gồm GateProvider A/B/C, metadata/cache namespace/timing và không gọi CLI consumer. Windows EXE/setup build, self-test/smoke bản đóng gói và nâng cấp giữ settings/devices/certificates/usage/runtime đạt.

A: response A sau khi đã nhận B bị từ chối ở Android và server mailbox. B: nguồn rỗng xóa state/cancel pending; strict CJK còn dòng Việt được thử native. C: 100 frame trùng ở unit test tạo đúng một request; backend gated duplicates gọi provider một lần. Đây là kiểm thử deterministic fake provider; không gắn nhãn benchmark Gemini thật.

Kiểm thử native trên APK cuối: dòng Trung có cache → chỉ còn “Xin chào” trong cùng VÙNG; source chuyển rỗng sau **167 ms**. Giữ thêm 1,5 giây: không request mới, AI/lỗi/stale draw đều 0. Ảnh nền caption trong cảnh đồng nhất giảm 35.482→0 pixel đen, đã xem ảnh xác nhận không còn dải dịch. [JSON](../research/results/android-source-disappearance-v160.json), [trước](../research/results/android-source-disappearance-before.png), [sau](../research/results/android-source-disappearance-after.png). Đây là ca correctness, không phải thống kê P95 hoặc chất lượng OCR video tùy ý.

File sản phẩm thay đổi đợt này (ngoài report/research):

- android-app/app/build.gradle.kts; MainActivity.kt; settings/AppSettings.kt; SessionState.kt.
- capture/ScreenCaptureService.kt; SubtitleRegion.kt; SourceRowTracker.kt; SubtitleDebounce.kt; OcrFrameFingerprint.kt.
- ocr/OcrProcessor.kt và test MainActivity/SourceRowTracker/SubtitleDebounce/OcrFrameFingerprint.
- windows-server/app/antigravity_provider.py; app/__init__.py; installer.py; static/index.html; static/app.js.
- tests/test_antigravity_provider.py; scripts/smoke_windows.py.
Các đường Kotlin ở android-app/app/src/main/java/com/gemini/ocr/; tests ở src/test/java/com/gemini/ocr/. Source ZIP chứa đầy đủ mã và test. [CHANGELOG](CHANGELOG.md).

## 7. Artifact giao

APK 1.6.0 được đo có SHA256 **c05e62113b6449b7ccdbde709e81b85d5637fd62a5004d2cdce5a367e6ee7d83**, khớp bản đã cài/benchmark lúc đó. [Audit lịch sử](results/release-v160.json) ghi hash APK/EXE/setup và nâng cấp giữ dữ liệu. Artifact hiện tại trong dist đã thay APK bằng bản mới; tải và đối chiếu theo [báo cáo 1.6.1](RETRY_FIX_V161.md) và SHA256SUMS hiện tại.

Bộ cài/mã nguồn không kèm dữ liệu đăng nhập, OAuth, DB, private key, model weights, NVIDIA DLL hoặc wheels. Các dữ liệu/cấu hình hiện có không bị xóa. Chưa nghiệm thu Redmi thật. Giới hạn chặn consumer CLI được nêu rõ trong UI/README thay vì hướng dẫn đăng nhập lại.
