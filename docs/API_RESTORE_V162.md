# Gemini API hoạt động — Android 1.6.2 / Windows 1.6.0

Ngày 23/10/2026. Sau khi người dùng cung cấp khóa, máy hiện tại đã chuyển sang **Gemini API chính thức**, Fast dùng **gemini-3.5-flash-lite**. Khóa được lưu qua endpoint DPAPI sẵn có trên Windows; không nằm trong APK, source ZIP, báo cáo hoặc ảnh. CLI cá nhân thử nghiệm vẫn **tắt**; không có lượt gọi CLI trong đợt này.

**Đã khôi phục dịch câu mới trên BlueStacks, chưa đạt mục tiêu một giây ổn định.** Ba câu kiểm tra native gần nhất mất 1,214 / 1,387 / 1,032 giây từ source onDraw đến overlay onDraw. Đợt API 200 lượt gặp 145 HTTP429, nên không dùng các lượt nhanh thành công để tuyên bố P95 đạt mục tiêu.

## Xác thực và bản cài thực tế

- Google chấp nhận khóa khi đọc danh sách model, có Gemini 3.5 Flash-Lite.
- Gọi provider API thật: `こんにちは` → `Xin chào`, 1442,18 ms phía client, 150 input/18 output token. Đây là một mẫu, không phải percentile.
- Qua Companion Windows 1.6.0 đã cài: cùng câu → `Xin chào`, **1125 ms server**, cache=false. [JSON](results/api-installed-w160.json), [kiểm tra API](results/api-credential-check.json).
- Android 1.6.2/code13 và Windows 1.6.0 được build/cài đè; giữ ghép cặp, vùng [233,496,809,538], chứng chỉ, dữ liệu và CLI runtime cũ. Chỉ đổi cài đặt thuộc dự án: provider API, khóa DPAPI, Fast Flash-Lite và CLI thử nghiệm=false. Không tự bật thanh toán hoặc sửa cấu hình hệ thống.

Khóa đã được chia sẻ trong chat, vì vậy người dùng nên thu hồi/tạo khóa thay thế và nhập trực tiếp trong Windows **Cài đặt AI → Gemini API → Lưu**. Không gửi khóa mới qua chat. Thay khóa không cần build APK hoặc ghép cặp lại.

## OCR → API → lớp phủ Android

Ba nguồn công khai mới, không dùng bản dịch cache để đo lượt đầu:

| Nguồn native | Gemini trả và Android vẽ | Source→overlay |
|---|---|---:|
| 今天天气很好 | Hôm nay thời tiết rất đẹp | 1214 ms |
| 坐下休息一会 | Ngồi xuống nghỉ ngơi một lát | 1387 ms |
| 我们马上回家 | Chúng ta về nhà ngay đây | 1032 ms |

Mỗi lượt tăng đúng một request AI; hash nguồn OCR khớp native, requestId khớp response/overlay. Đọc lại từ cache sau khi vẽ không gọi AI thêm và hash bản dịch Windows khớp hash Canvas Android. [Raw JSON](results/api-android-v162.json), [ảnh cảnh công khai](images/api-android-live-v162.png). Đồng hồ chung elapsedRealtime Android; chưa đo thời điểm pixel thực sự được GPU/display trình chiếu. n=3 không đủ xác nhận P95 của video hay Redmi.

Đợt đầu có hai ca đúng nguồn, E2E 1704/1217 ms. Ca thứ ba API đã vẽ bản dịch đúng nghĩa, nhưng ML Kit đọc `请把窗户关上。` thành **`靖把窗戶关上。`**. Hash OCR khớp chính xác chuỗi sai này, nên ca đó được giữ là **failed exact-source**, không đổi văn bản để làm đẹp số liệu. Con số 923 ms trên ảnh ca này là **khung OCR→draw**, không phải source→overlay. [Bản ghi đợt đầu](results/api-android-v162-prior-attempt.json) ghi rõ phần được tái dựng từ output/screenshot ban đầu, không giả thành raw trace đầy đủ. Chưa sửa triệt để lỗi nhận diện ML Kit này.

Đã phát thử video đang mở khoảng 6 giây và tạm dừng ở khoảng 3:01. Log thấy 12 thay đổi nguồn OCR, 8 request mới nhất, 2 response và 2 lượt vẽ trong cửa sổ quan sát, không có lỗi provider trong cửa sổ đó. Các lượt cũ bị coalescing/loại khi nguồn đổi, không phải mọi câu ngắn đều đã được dịch. Ảnh kiểm tra xác nhận app vẽ “Đời người cũng giống như lái xe vậy” tương ứng câu Trung đang hiện. Chỉ [metadata hash](results/api-real-video-v162.json) được đưa vào source; ảnh video nằm ngoài gói. 898 ms UI lúc này là khung OCR→draw, không là phép đo onset video hoặc P95.

## 200 lượt API thực, gồm toàn bộ lỗi

100 câu Trung riêng biệt từ tập tham chiếu độc lập Tatoeba/Helsinki-NLP, mỗi câu thử non-streaming và streaming. Flash-Lite 3.5, thinking minimal, prompt gọn, cap256 output token, một AsyncClient tái sử dụng, concurrency1, pause250 ms, không retry, bỏ qua cache ứng dụng. Đây là harness nghiên cứu riêng, không phải payload/full pipeline Android sản phẩm; request trực tiếp không nằm trong bộ đếm giới hạn nội bộ của Companion.

| Điều kiện | Thành công / tổng | Google HTTP429 | P50 bản dịch đủ dùng* | P95* | P99* |
|---|---:|---:|---:|---:|---:|
| Non-streaming | 28/100 | 72 | 732,375 ms | 1042,988 ms | 1172,321 ms |
| Streaming | 27/100 | 73 | 674,811 ms | 929,485 ms | 1035,042 ms |

\* **Chỉ trên các lượt thành công**, gồm mạng/provider, không gồm OCR/overlay. Toàn bộ 200 lượt chỉ **55 thành công**, 145 lỗi429 (72,5%). Chỉ 25/100 và 26/100 lượt có bản dịch hợp lệ trong một giây. Không có một cận P95 hữu hạn của bản dịch thành công cho toàn bộ tập thử; các lượt lỗi không được tính thành “dịch nhanh”.

Có 33/100 câu từng thành công, chỉ 22 câu thành công cả hai chế độ. Trên 22 cặp đó, streaming cải thiện trung vị bản dịch đủ dùng khoảng40 ms, trung vị hoàn thành cuối chậm hơn khoảng10 ms. P95 completion streaming trên 27 lượt thành công là983,475 ms. Lợi ích nhỏ này chưa biện minh đổi renderer sang stream partial hoặc hiển thị JSON/token chưa hoàn chỉnh.

HTTP429 xác minh Google đang giới hạn request/quota, nhưng harness không lưu error body hoặc dữ liệu tài khoản nên **chưa xác định chính xác hạn mức RPM/TPM/ngày**. Không coi đây là lỗi đăng nhập, cũng không tự nâng billing hoặc xoay tài khoản/model để che lỗi. Chất lượng BLEU/chrF chỉ chấm được các tập con thành công (chrF49,12/51,72); chưa đủ100 câu thành công hoặc đánh giá mù để kết luận chất lượng toàn bộ corpus. Token thống kê chỉ cho response có usage; chưa đo hóa đơn/chi phí thực.

[Raw200](../research/gemini_api/api-flashlite-live.json), [phân tích kiểm chứng](../research/gemini_api/live-analysis.json), [phương pháp và đầy đủ P50/P90/P95/P99](../research/gemini_api/README.md). Phân vị API nearest-rank, số native mô tả dùng nội suy tuyến tính; không trộn hai loại số đo. API model3.8/Hybrid chưa benchmark thực trong đợt này. Local/Hybrid vẫn chưa bật vì prototype trước không đạt chất lượng.

## Thay đổi và kiểm thử

- Android `SessionState.kt`, `MainActivity.kt`, nhánh hello/invalidate trong `ScreenCaptureService.kt`: trạng thái backend nullable/đúng provider, không báo “CLI chỉ cache” sau khi đã chuyển API. Server cũ thiếu metadata hiển thị chưa rõ thay vì tự nhận có thể dịch.
- Windows `models.py`, `antigravity_provider.py`, `server.py`: tùy chọn CLI cá nhân mặc địnhfalse; có bật/lưu rõ ràng mới cho phép CLI. Metadata backend_mode/translation_available/experimental_cli_enabled ở status/hello/invalidate; provider_configured phản ánh cấu hình hiện tại thay vì checked cũ. Cache/từ điển vẫn kiểm tra trước provider.
- Giao diện Windows giữ công tắc thử nghiệm, lưu đủ field, nhắc FAQ Google/hạn chế tài khoản; hiện không bật. Không đọc OAuth/keyring hoặc thay xác thực CLI. Nguồn chính thức: [FAQ Google](https://www.antigravity.google/docs/faq/).
- Guard nghiên cứu cache được sửa để từ chối API/CLI thử nghiệm có thể gọi cloud, tránh script cũ vô tình gửi inference.
- `scripts/verify_api_pipeline.py` lưu cả partial/failure, kiểm tra chữ nguồn trước cache read-back; có 2 test offline. Harness/analyzer ở `research/gemini_api/` có13 test mock/contract, không dùng mock làm latency thực.

**98 JVM Android +71 backend tests đạt**, Android lint0 lỗi/43 cảnh báo. Windows EXE/setup build và packaged smoke đạt, cài nâng cấp giữ dữ liệu. API harness/analyzer13 tests đạt ngoài sandbox; lần chạy sandbox bị treo được dừng đúng PID test rồi chạy lại. Không coi việc treo sandbox là lỗi sản phẩm. Android release có chữ ký v2/v3 cùng certificate cũ.

## Artifact

[APK1.6.2](../dist/GeminiOCR-Android.apk), [WindowsSetup1.6.0](../dist/GeminiOCR-Windows-Setup.exe), [source](../dist/GeminiOCR-Source.zip), [SHA256SUMS](../dist/SHA256SUMS.txt). APK đã cài/kiểm tra SHA256 **012108f62abd3f4fb670ddf9aa525e474fba62b821ae0d9c18954545f464df5c**. [Audit release](results/release-api-v162.json) ghi phiên bản, test, hash và trạng thái API không chứa khóa.

Chưa đạt P95≤1 giây ổn định, chưa nghiệm thu Redmi thật, chưa theo kịp mọi câu ngắn. Những giới hạn còn lại được giữ rõ: Google429 khi gửi liên tục, nhận diện sai chữ ở một ca native và các câu trung gian bị bỏ khi nguồn đổi nhanh hơn response.
