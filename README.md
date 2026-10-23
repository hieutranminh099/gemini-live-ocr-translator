# Gemini Live OCR Translator — Android / Windows 1.7.0

Android đọc phụ đề bằng ML Kit, Windows quản lý bản dịch và gửi kết quả về lớp phủ. Dự án được cập nhật trực tiếp, giữ dữ liệu, ghép cặp và tính năng một khung/tự động.

Repository Private của **dự án hiện có**: [hieutranminh099/gemini-live-ocr-translator](https://github.com/hieutranminh099/gemini-live-ocr-translator). Chủ dự án đã phê duyệt nhập mã nguồn hiện tại và tạo nhánh `main`, `codex-dev`, `gemini-review`. [Quy trình Codex/Gemini CLI và worktree](docs/GITHUB_COLLABORATION.md), [hướng dẫn cho Gemini](GEMINI.md). Các báo cáo thô ở `docs/results/` được giữ cục bộ và loại khỏi phạm vi GitHub; không xóa dữ liệu đang sử dụng.

Mã đang phát triển hướng tới 1.7.1 với sửa lịch chờ API và đánh thức capture; chưa phát hành hay cài thay bản 1.7.0. Chưa chứng minh mọi câu mới đạt một giây. Các mục phiên bản/kiểm thử 1.7.0 bên dưới mô tả bản đã giao.

**Bản 1.7.0 bổ sung AI Video Translator và Kho dịch thuật dùng chung với Live OCR.** Nhập video, ưu tiên luồng phụ đề văn bản hoặc OCR chữ ghi cứng, dịch bằng Gemini API, sửa timeline và xuất SRT/ASS/MP4. Tích lũy Translation Memory mặc định tắt; bản AI mới là Draft và chỉ bản đã xác minh/xác nhận được dùng lại. [Cách dùng, kiểm thử và giới hạn 1.7.0](docs/VIDEO_TRANSLATOR_V170.md).

EXE đã chạy được video mẫu có phụ đề, RapidOCR Trung và Speech tiếng Anh tổng hợp → Gemini API thật → phụ đề Việt. Speech/phim đa ngôn ngữ chưa được chấm độ chính xác; bản dịch mới vẫn cần kiểm tra và xác nhận. OCR tiếng Hàn chưa đạt mẫu thực tế nên bị chặn; dùng luồng phụ đề văn bản hoặc Speech khi phù hợp. Không coi các mẫu này là benchmark phim thật hoặc xác nhận Live một giây.

Máy hiện tại dùng **Gemini API chính thức**, Fast dùng Gemini 3.5 Flash-Lite với thinking minimal. Khóa được lưu mã hóa DPAPI trên Windows, không nằm trong APK/mã nguồn. CLI thử nghiệm vẫn tắt; Video Translator chỉ dùng API. Xem [báo cáo tốc độ 1.6.3](docs/PERFORMANCE_V163.md) để phân biệt độ trễ câu mới và cache.

Windows 1.6.0 có tùy chọn CLI cá nhân thử nghiệm, mặc định tắt và chỉ chạy khi chủ máy bật/lưu rõ ràng. Google không hỗ trợ cách tích hợp CLI này, có nêu nguy cơ đình chỉ tài khoản. Đợt kiểm tra hiện tại dùng API hoàn toàn; không gọi CLI, không đọc OAuth. [Cấu hình API/CLI](docs/GOOGLE_AI_PRO.md).

Prototype OPUS-MT/CTranslate2 chạy riêng đã đo 100 câu tham chiếu trên CPU và GPU. Nhanh nhưng sai nghĩa/phủ định/con số, nên **chưa bật Hybrid hoặc dịch local trong ứng dụng chính**. Chưa xác nhận P95 ≤ 1 giây cho câu mới. [Báo cáo đo 1.6.0](docs/PERFORMANCE_V160.md) phân biệt cache, inference local và toàn luồng; [kết quả local](research/local_translation/README.md) chứa đủ số đo và giới hạn.

## Bộ cài

| Tệp trong dist/ | Công dụng |
|---|---|
| GeminiOCR-Android.apk | Android 1.7.0, Android 8+, kèm mô hình OCR Live Anh/Nhật/Trung/Hàn và trạng thái ưu tiên capture |
| GeminiOCR-Windows-Setup.exe | Bộ cài Windows 1.7.0 x64 |
| GeminiOCR.exe | Companion chạy trực tiếp, kèm runtime media, không cần Python/Node.js |
| GeminiOCR-Source.zip | Mã nguồn, kiểm thử và báo cáo; không kèm khóa, dữ liệu riêng, model/runtime local |
| SHA256SUMS.txt / android-signature.txt | Đối chiếu artifact và chữ ký APK |
| examples/Video-Translator-Demo.mp4 | Video mẫu tổng hợp đã qua Gemini API và render phụ đề Việt |

1. Cài Windows bằng **Cài đặt cho tôi** hoặc chạy EXE trực tiếp. Lối tắt là **Gemini OCR Windows**; **Gemini OCR** trong BlueStacks mở Android. Windows EXE chưa có chữ ký Authenticode của nhà phát hành.
2. Cài đè APK để giữ thiết lập/ghép cặp. Android có thể yêu cầu cho phép cài ngoài Play Store.
3. Windows: **Thiết bị → Tạo mã ghép cặp**. Điện thoại dùng QR/liên kết hoặc nhập IP:cổng, mã một lần và SHA-256 chứng chỉ; cần cùng Wi-Fi. BlueStacks hiện dùng ADB 127.0.0.1:5555 và reverse cổng 8765. Sau khởi động lại chạy **Mo-Gemini-BlueStacks.cmd** trong thư mục dự án.
4. Chọn đúng ngôn ngữ nguồn, **Tự động tăng tốc · Gemini**, cấp quyền nổi/thu màn hình. **VÙNG** cần khoanh gọn chữ gốc, tránh pinyin/tiếng Việt có sẵn. Máy hiện tại đã lưu khóa API và chọn provider API để dịch cả câu mới. Máy cài mới cần tự nhập khóa trong Windows.
5. Chạm nút tròn để tạm dừng/tiếp tục, giữ để dừng phiên. Tự động hiện **QUÉT LẠI**; chế độ thủ công hiện **1 KHUNG**. Đóng trình duyệt không dừng Companion; dùng **Cài đặt AI → Tắt máy chủ**.

Nếu có file phụ đề nguồn: mở **Dịch trước phụ đề** trên Windows, chọn file và cùng ngôn ngữ/hồ sơ/chế độ với Android → **Xem trước tệp** → **Bắt đầu dịch bằng Gemini API**. Preview không gọi AI; nút bắt đầu mới dùng quota API. Các câu trùng được gộp, tối đa 10 câu/lượt. Bản 1.7.0 cho tác vụ nền nhường lượt cho Live. Android dùng cache khi OCR khớp câu đã chuẩn bị; riêng trang Dịch trước phụ đề chỉ chuẩn bị cache, không phát video. Sai khác OCR/dấu câu có thể vẫn cần gọi Gemini mới.

Nếu có video trên máy: mở **AI Video Translator** → nhập tệp → chọn nguồn/ngôn ngữ/ROI → bắt đầu → sửa và xác nhận timeline → xuất SRT/ASS/MP4. Nhập chưa gọi AI, tệp gốc được giữ nguyên. Xử lý media chạy cục bộ; dịch câu mới gửi Gemini và cần Internet. Muốn tích lũy dữ liệu chung, bật công tắc trong **Kho dịch thuật**, tắt riêng tư cho đúng video rồi bật lưu vào kho. [Hướng dẫn và phép thử A–J](docs/VIDEO_TRANSLATOR_V170.md).

Nếu sau này chủ động dùng Gemini API, tự nhập khóa trong **Cài đặt AI**, chọn API và lưu. Không gửi khóa vào chat. API có quyền model, hạn mức và chi phí riêng. Ứng dụng không tự chuyển provider khi lỗi/hết hạn mức. Máy hiện tại đã chọn API theo khóa người dùng cung cấp; không bật CLI hoặc tự mở thanh toán.

Nếu Firewall hỏi, chỉ cần cho phép TCP 8765 trên mạng riêng tư. Trang quản trị 8766 chỉ nghe loopback. Không cần mở cổng router. [Hướng dẫn phụ đề](docs/SUBTITLE_MODE.md), [Redmi Note 13 Pro 5G](docs/REDMI_NOTE13_PRO_5G.md).

## Pipeline và hiển thị

- Capture có hộp chờ một khung mới nhất; chỉ một OCR chạy, tách khỏi chờ dịch. OCR chậm thì giãn nhịp; kết quả thuộc vùng/thế hệ cũ bị bỏ.
- requestId, regionId, screenGeneration bảo vệ nhận kết quả, cache và vẽ. Câu mới thay câu chờ cũ; response cũ không ghi đè câu mới. Chữ biến mất xóa OCR/bản dịch/trạng thái chờ.
- Response mạng phải được ảnh OCR lấy sau response xác nhận trước khi vẽ tự động. Cache RAM không gọi lại AI. Cache Windows có namespace ngôn ngữ/profile/mode/provider/model/từ điển và nguyên văn chuẩn hóa.
- Tự động thu toàn màn hình đặt dải Việt nền kín **ngoài vùng OCR**, tránh việc ẩn lớp phủ để lấy ảnh. Nội dung/bố cục không đổi được giữ nguyên. Một khung hoặc chia sẻ một ứng dụng trên Android 14+ giữ kiểu che chữ gốc.
- 1.6.0 yêu cầu ký tự đúng hệ nguồn Trung/Nhật/Hàn trong phụ đề tự động; nguồn CJK mất thì không đổi dòng Việt/pinyin thành câu nguồn. Giữ tên/số/gạch dưới cùng hàng nếu ML Kit nhận được. ML Kit vẫn có thể bỏ gạch dưới hoặc nhận sai chữ.
- **Tự theo dõi dòng nguồn** là thử nghiệm, mặc định tắt: chỉ thu hẹp chiều dọc trong VÙNG đã chọn, cần hai quan sát, quét lại cả VÙNG mỗi 750 ms; mất chữ thì trở về toàn vùng. Chưa đo lợi ích độ trễ của tùy chọn này.
- Câu mới chờ ổn định 90 ms; OCR dao động ít nhất ba lần/giây thì 180 ms, chưa gồm nhịp lấy khung/OCR. Cache RAM bỏ qua chờ này. Mục đích là giảm gọi API cho mảnh câu tạm thời, không làm inference Gemini tự nhanh hơn.
- API tối đa hai tác vụ mỗi thiết bị, toàn server tối đa hai lượt AI; chỉ giữ một câu chờ mới nhất. Cache máy chủ có lối trả ngay. Tác vụ chờ đã lỗi thời không tiêu quota; HTTP đã chạy có thể hoàn tất để lưu cache nhưng không vẽ câu cũ.
- HTTP 429 chuyển thời hạn RetryInfo/Retry-After về Android. Câu mới/quét lại không xóa thời hạn này; cache vẫn dùng được. Dịch trước dừng khi hết quota, không lặp API tự động.
- Số cũ dạng **2.547s** là thời gian **máy chủ**, không phải màn hình đến bản dịch. Nay UI ghi rõ **Máy chủ** và **Khung OCR → hiện**; số thứ hai vẫn chưa gồm chờ lấy khung sau khi chữ xuất hiện.

Giữ Fast/Smart/Quality, năm profile, từ điển nhập/xuất JSON, cache SQLite/RAM, giới hạn lượt gọi, hội thoại theo thiết bị, WSS/TLS ghim chứng chỉ, mã ghép cặp một lần, thu hồi thiết bị, danh sách loại trừ ứng dụng. Không dùng Accessibility Service. CLI chỉ chạy khi bật tùy chọn thử nghiệm cá nhân; mặc định tắt và vẫn không được Google hỗ trợ.

## Kiểm thử và giới hạn

Video/TM 1.7.0: [VIDEO_TRANSLATOR_V170.md](docs/VIDEO_TRANSLATOR_V170.md), gồm kết quả thực và các giới hạn chưa nghiệm thu. Hiệu năng Live đã đo ở bản trước: [PERFORMANCE_V163.md](docs/PERFORMANCE_V163.md). Kiểm tra API lần đầu: [API_RESTORE_V162.md](docs/API_RESTORE_V162.md). Số đo cache/local lịch sử: [PERFORMANCE_V160.md](docs/PERFORMANCE_V160.md). Lịch sử ở [VERIFICATION.md](docs/VERIFICATION.md). Benchmark mô phỏng được ghi riêng, không đại diện latency/quality của Google.

Bản 1.7.0 đã cài đè Windows/BlueStacks, giữ cấu hình và ghép cặp; 284 backend tests và 124 Android JVM tests đạt. Khi bật Live trong lúc render, encoder video thực sự tạm dừng. Ba lượt thử cache × 20 cue cho P95 598,35 / 290,10 / 618,10 ms; lượt lạnh mất hai cue và có một lượt vẽ câu trước, vẫn được giữ trong báo cáo. Đây chưa phải bằng chứng Gemini câu mới đạt một giây hoặc Live không suy giảm trong mọi điều kiện.

Chưa nghiệm thu Redmi thật, pin/Wi-Fi hoặc hội thoại phim liên tục bằng backend được hỗ trợ. FLAG_SECURE có thể thu ra khung đen. Một khung có thể nhận sai khi chữ chuyển động. Dải dài có thể rút gọn bằng “…”. Cache cùng câu khác ngữ cảnh có thể cần sửa từ điển/xóa cache. Không bảo đảm mọi câu mới có latency một giây.

## Dữ liệu và build

Windows cài tại %LOCALAPPDATA%\Programs\GeminiOCR; dữ liệu ở %LOCALAPPDATA%\GeminiOCR\data gồm DB, chứng chỉ/khóa TLS, khóa API mã hóa DPAPI và log giới hạn dung lượng. Video nhập tạo bản sao/checkpoint cục bộ trong thư mục dự án; riêng tư ngăn ghi kho chung, không tự xóa các bản sao này. Android Keystore bảo vệ token, tắt backup; không lưu ảnh màn hình. Cache có thể chứa văn bản dịch; TM bật rõ ràng mới lưu thêm nguyên văn. Xác thực/hội thoại CLI cũ do CLI quản lý được giữ; app không đọc/sao chép OAuth token. Không chia sẻ dữ liệu riêng.

Bộ gỡ giữ dữ liệu/từ điển. Khóa ký APK ở .tools/signing/ được bỏ khỏi gói giao. Prototype local và DLL NVIDIA chỉ nằm trong .tools/, không thay PATH, driver hoặc cấu hình hệ thống.

Python 3.12+ trên Windows:

~~~powershell
python -m venv .venv
.\.venv\Scripts\python -m pip install -r windows-server/requirements.txt
.\.venv\Scripts\python windows-server/launcher.py
~~~

Android Studio mở android-app/, SDK 35/JDK 17; hoặc dùng công cụ riêng trong .tools/:

~~~powershell
python scripts/bootstrap_android.py
python scripts/build_android.py assembleRelease testDebugUnitTest lintDebug
python scripts/sign_apk.py
python scripts/setup_video_tools.py
python scripts/setup_video_models.py --speech
python scripts/build_windows.py
python scripts/package_delivery.py
~~~

Backend: python -m pytest. API harness: python -m unittest discover -s research/gemini_api -p test_benchmark_api.py. Cần dependencies từng thư mục. Model/runtime local không nằm trong source ZIP; [cách tái lập](research/local_translation/README.md).

Không thay chính sách PowerShell toàn máy. GEMINI_API_KEY, nếu người dùng tự cấu hình, được ưu tiên hơn khóa lưu; khóa trong đợt này do người dùng cung cấp và được lưu DPAPI. Không chia sẻ khóa vào chat; nếu đã chia sẻ, nên thu hồi/tạo khóa thay thế và nhập trực tiếp trong Windows. [CHANGELOG](docs/CHANGELOG.md).
