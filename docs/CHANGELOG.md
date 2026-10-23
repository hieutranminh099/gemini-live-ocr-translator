# Android 1.6.2 / Windows 1.6.0 — 23/10/2026

- Chuyển máy hiện tại sang Gemini API sau khi người dùng cung cấp khóa; Fast dùng Gemini 3.5 Flash-Lite, khóa lưu DPAPI, không kèm APK/mã nguồn. Không đọc OAuth hoặc tự bật thanh toán.
- Metadata backend_mode/translation_available/experimental_cli_enabled qua status/hello/invalidate; Android hiển thị đúng API/cache/chế độ chưa rõ, không giữ nhãn CLI sai sau khi chuyển provider.
- Có tùy chọn CLI cá nhân thử nghiệm mặc định tắt và cần bật/lưu rõ ràng; hiện vẫn tắt. UI giữ cảnh báo Google/FAQ. provider_configured phản ánh cấu hình thực, không dựa vào checked cũ.
- Guard nghiên cứu cache từ chối khi API/CLI thử nghiệm có thể gọi cloud, tránh benchmark cũ phát sinh inference ngoài ý định.
- 98 JVM Android và 71 backend test đạt; packaged smoke/bộ cài giữ dữ liệu đạt. [Kết quả API và OCR thực tế](API_RESTORE_V162.md).

# Android 1.6.1 / Windows 1.5.0 — 23/10/2026

- Giữ lỗi cấu hình/xác thực qua các khung OCR; không ghi đè thành “Đang chờ thử lại Gemini”, không tự gửi lại cùng câu mỗi 60 giây.
- Lỗi cấu hình ghi nhớ tối đa 256 khóa theo ngôn ngữ/profile/mode/câu; câu khác vẫn tra server cache, RAM cache được ưu tiên trước gate.
- Lỗi tạm thời vẫn giữ backoff toàn phiên 5/15/60 giây, hiện đúng lý do/countdown. 1 KHUNG, hello/invalidate mở lại khả năng thử; reset vùng/tạm dừng không xóa nhầm latch.
- Xóa pendingToken khi nhận lỗi hiện tại; kiểm tra metadata lỗi, không để lỗi cũ ảnh hưởng câu mới. Trace request_failed chỉ có mã/metadata, không ghi nội dung OCR.
- 16 test bổ sung, tổng 91 JVM Android đạt. Windows backend 1.5.0 không thay đổi. [Báo cáo và kiểm tra native](RETRY_FIX_V161.md).

# Android 1.6.0 / Windows 1.5.0 — 23/10/2026

- Ghi rõ thời gian máy chủ và khung OCR→vẽ; thêm telemetry stabilization/nhịp thích nghi, không gọi số server là end-to-end.
- CJK auto không lấy dòng Việt/pinyin làm nguồn khi CJK biến mất; giữ fragment tên/số/gạch dưới cùng dòng nếu OCR nhận được.
- Tracking dọc bên trong VÙNG, mặc định tắt; hai quan sát, quét toàn vùng 750 ms. Fingerprint chỉ so CRC cùng hình học crop để không bỏ nhầm full scan.
- Debounce 60 ms chỉ khi OCR dao động nhanh, không chặn OCR hoặc cache. Giữ latest-frame/một OCR và chống kết quả cũ.
- Chặn consumer CLI wrapping theo FAQ Google, khóa nút CLI, giữ dữ liệu/xác thực/provider. Chưa API theo lựa chọn người dùng; cache/từ điển vẫn trả được, câu mới chưa có backend hiện tại.
- 75 JVM Android + 59 backend + 9 mock API tests đạt; lint 0 lỗi/43 cảnh báo. Hai cohort cache 100 trước/100 sau và prototype local 8×100 câu đo thật; không gộp thành benchmark Gemini.
- Local nhanh nhưng sai phủ định/con số, chrF <39 dưới ngưỡng 55; không đưa Hybrid vào app. Chưa chứng minh một giây cho câu mới. [Báo cáo](PERFORMANCE_V160.md).

Các mục cũ bên dưới là lịch sử; không còn hướng dẫn dùng consumer CLI backend.

# Android 1.5.1 — 23/10/2026

- Sửa status auto/manual khi đã đọc chữ nhưng Windows offline hoặc socket không gửi được; không giữ nhãn Đang đọc mãi.
- 3 regression bổ sung, tổng 59 JVM tests đạt; lint 0 lỗi; APK code 10 cùng chữ ký cài đè giữ dữ liệu.
- Khôi phục Companion đã ghép và ADB reverse trên BlueStacks; thử ngắt/nối lại và hiển thị Chào buổi tối thành công.
- Thêm file mở Gemini/BlueStacks và khôi phục kết nối trong thư mục dự án. Backend 1.4.0 không đổi.

# Android 1.5.0 / Windows 1.4.0 — pipeline và kết quả đúng câu

- Capture dùng hộp chờ một bitmap mới nhất, một OCR nền, bỏ ROI lỗi thời, giãn nhịp khi OCR chậm.
- Metadata requestId/regionId/screenGeneration, chặn response cũ và cache sai metadata; xác nhận OCR sau response trước khi vẽ tự động.
- Nguồn trống xóa OCR/trạng thái/bản dịch. Progress theo câu hiện tại, không giữ chữ lượt AI cũ.
- Server giữ một lượt đang chạy và đúng một câu chờ mới nhất; bỏ phản hồi cũ, giữ process CLI khi nguồn đổi/biến mất.
- Sửa khóa cache mất khoảng cách từ Latin/Hàn; giữ gạch dưới/ba chấm khi OCR trả về. Nhận CJK một ký tự, tách dòng pinyin/Việt khi có dòng nguồn CJK.
- Telemetry riêng capture/OCR/queue/WSS/provider/CLI/draw. 56 tests Android, 58 backend, lint 0 lỗi; A/B/C và đối chiếu server→Android thực tế đạt trên BlueStacks.
- Chưa đạt mục tiêu 1 giây: sáu câu mới P50 3,351s/P95 6,690s; ca B chờ A lâu hơn 13s; cache khoảng 0,23s. ML Kit vẫn có thể bỏ underscore hoặc nhầm giản/phồn thể. Xem [báo cáo và dữ liệu thật](VERIFICATION.md).

# Android 1.4.1 / Windows 1.3.0 — sửa nháy phụ đề

- Tự động trong dải phụ đề khi thu toàn màn hình, gồm BlueStacks Android 9: đặt dải Việt nền kín ngoài toàn bộ vùng OCR, ưu tiên phía trên. Không còn ẩn dải dịch/thanh nút định kỳ để lấy ảnh; phụ đề gốc được để hở để nhận diện câu mới.
- Thanh nút tránh vùng OCR và dải Việt. Nếu không tìm được chỗ an toàn, hiện hướng dẫn thu hẹp VÙNG thay vì để lớp dịch che chữ cần OCR.
- Giữ bố cục khi nội dung và vùng không đổi; bỏ cập nhật render/phản hồi giống hệt ở mỗi vòng OCR. Vị trí dải tự động không chạy theo sai lệch nhỏ của hộp chữ.
- Giữ kiểu đè chữ gốc cho 1 KHUNG và chia sẻ Một ứng dụng trên Android 14+. Chế độ tự động thu toàn màn hình hiển thị cạnh phụ đề gốc, không phủ trùng chữ nguồn.
- Windows vẫn là 1.3.0, giữ xác thực Google AI Pro và chỉ dùng Gemini; không thêm bộ dịch khác. Không có tuyên bố mới về độ trễ dưới một giây.
- Kết quả build/hồi quy và kiểm tra nháy trước/sau: [VERIFICATION.md](VERIFICATION.md). Các số đo 1.4.0 bên dưới là lịch sử.

# Android 1.4.0 / Windows 1.3.0 — 22/10/2026

- Preset Tự động tăng tốc: chu kỳ nghỉ 150 ms; sao chép riêng vùng RGBA đã khoanh, giữ tọa độ theo ảnh đầy đủ.
- Fast/auto chọn Gemini 3.8 Flash Low qua tài khoản Google AI Pro đã đăng nhập, không thêm bộ dịch khác.
- Giữ phiên stdin stream-json riêng từng thiết bị/cấu hình, hạn số phiên, số câu, kích thước và thời gian rỗi; đóng khi ngắt thiết bị hoặc đổi cấu hình.
- Mã ngẫu nhiên từng câu chặn structured_output cũ của Antigravity 1.4.5; chỉ nhận kết quả khớp mã. Token ghi nhận theo chênh lệch bộ đếm tích lũy.
- Ưu tiên câu mới nhất sau khi AI xong; cache hiển thị được khi còn lượt cũ đang chờ. Giữ 30 giây cùng câu không gọi thêm AI trong phép thử.
- 52 kiểm thử Python, 25 JVM Android, lint không lỗi; EXE/bộ cài và OCR → Gemini thật → phụ đề tự đổi trên BlueStacks đã kiểm tra. Xem VERIFICATION.md về số đo và giới hạn.

# Android 1.3.1 — phản hồi trạng thái ngay trên video

- Thay nút ĐỢI AI bị khóa bằng bộ đếm giây và nút HỦY; sau khi hủy, lỗi hoặc kết thúc có thể chụp/thử lại.
- Hiện chữ OCR, hoàn tất, OCR trống, lỗi AI và mất kết nối trên thanh nổi; thêm Khung vừa đọc trong màn hình chính, chỉ giữ trong RAM phiên.
- Trạng thái tự động phân biệt câu đã đổi với kết quả vừa được hiển thị. Giữ vùng OCR, ngôn ngữ, tài khoản và ghép cặp khi cập nhật.
- APK code 6 cùng khóa ký; 24 kiểm thử JVM, build/Lint/chữ ký đạt. Thử lại đúng khung Trung của người dùng, một lượt AI mới 10,66 giây và phụ đề hiển thị đúng. [Báo cáo](VERIFICATION.md).

# Android 1.3.0 — Companion Windows 1.2.1 tương thích

- Thêm preset Phụ đề video: dải nền kín đè chữ gốc, chữ đậm căn giữa, co cỡ theo nội dung; cửa sổ chỉ chiếm dải phụ đề.
- Nút VÙNG mở trình kéo/chỉnh trên video, lưu vùng theo tỷ lệ màn hình. Cắt vùng trước ML Kit, gộp các dòng thành một câu.
- Nút 1 KHUNG chỉ OCR một khung, giữ câu đã chọn, bỏ chờ hai lần ổn định và không OCR liên tục khi chờ AI. Câu lặp dùng cache.
- Tự động hoàn tất yêu cầu đang chạy rồi lấy câu mới nhất; không hủy lặp theo video. Chuẩn hóa định danh câu để giảm gọi do khoảng trắng/dấu chấm; chặn OCR đọc lại bản dịch.
- Thêm số khung/diện tích OCR và hướng dẫn chọn đúng ngôn ngữ. APK versionCode 5, cùng khóa ký để cập nhật giữ dữ liệu.
- Đo thực tế và ảnh: [VERIFICATION.md](VERIFICATION.md). Cách dùng: [SUBTITLE_MODE.md](SUBTITLE_MODE.md). Tối ưu OCR không loại bỏ độ trễ gọi Antigravity.

# Phiên bản 1.2.1

- Chuyển tài khoản cá nhân sang Antigravity CLI sau lỗi `UNSUPPORTED_CLIENT` của Gemini CLI cũ. Tải từ Google, kiểm tra SHA-512; không đóng gói Node/CLI cũ hoặc đổi PATH hệ thống.
- Sửa truyền OCR qua stdin stream JSON, prompt chỉ dịch `items`; parser chấp nhận JSON lặp giống nhau, từ chối kết quả mâu thuẫn/đường dẫn workspace/sai số đoạn.
- Hồ sơ riêng cho tiến trình con, deny công cụ; không đọc OAuth/keyring, không sửa cấu hình CLI người dùng, không tự xóa hội thoại CLI.
- Sửa shortcut để Windows và BlueStacks có lối tắt riêng, xóa đối số cũ khi tạo lối tắt Windows. Bộ cài hỗ trợ cài im lặng theo người dùng hiện tại.
- APK 1.2.1 / versionCode 4 cùng khóa ký, giữ ghép cặp khi cập nhật.
- 48 bài Python + 14 bài JVM đạt. Provider, Windows đã cài và BlueStacks đều dịch thật `こんにちは` → `Xin chào`; toàn luồng BlueStacks khoảng 14,19 giây. Xem [bằng chứng](VERIFICATION.md).

# Phiên bản 1.2.0 — lịch sử, đường CLI cá nhân đã ngừng hoạt động

Các mô tả sau ghi lại bản cũ. Dùng 1.2.1 để dịch bằng tài khoản Google; không dùng runtime/cơ chế dọn phiên của 1.2.0.

- Thêm bộ máy tài khoản Google qua tiến trình Gemini CLI chính thức, bên cạnh API bằng khóa riêng. Đăng nhập trong cửa sổ CLI, kiểm tra bằng câu mẫu và báo lỗi cụ thể khi chưa đăng nhập, hết hạn mức hoặc mô hình chưa truy cập được.
- Bộ Windows chứa Gemini CLI 0.65.0 và Node.js 24.21.0 win-x64 đã đối chiếu SHA-256 nguồn chính thức, kèm giấy phép.
- Hồ sơ CLI riêng, không đọc token. Tắt extensions/hooks/MCP/skills, danh sách công cụ rỗng và chính sách từ chối mọi tool call. Nội dung OCR qua stdin, escape ký tự cú pháp CLI, dọn tệp phiên tạm sau mỗi lượt.
- Hủy/timeout giết tiến trình CLI, không để tiến trình dịch cũ tiếp tục chạy. Cache tách theo bộ máy và mô hình; đổi bộ máy thông báo Android xóa cache RAM.
- Android có hướng dẫn AI Pro và hiển thị đường dịch đang kết nối. APK versionCode 3, versionName 1.2.0, giữ khóa ký để cập nhật.
- Thêm 10 bài kiểm thử Python, tổng 40. Phần đăng nhập và chất lượng AI Pro thật cần người dùng nghiệm thu bằng tài khoản của mình.

# Phiên bản 1.1.0

- Độ ổn định OCR được tính theo từng đoạn. Đồng hồ/điểm số thay đổi liên tục không ngăn hội thoại ổn định được dịch.
- Giữ đúng khung hình mới nhất bằng một buffer phần cứng; màn hình đứng yên không còn phải chờ một khung hình thay đổi mới. Chuyển đổi kích thước và hủy native OCR không tái sử dụng bitmap đã giải phóng.
- Cache điện thoại tối đa 256 câu trong RAM, chỉ yêu cầu các đoạn còn thiếu. Tắt cache, sửa từ điển và nối lại kênh đều làm sạch cache phù hợp.
- Chọn vùng dịch toàn màn hình, nửa dưới hoặc vùng giữa; màu nút chọn thống nhất với giao diện xanh bạc hà; thêm thông tin thiết bị và lối vào phần quyền ứng dụng.
- Xoay màn hình đặt lại tọa độ và yêu cầu hiện hành. Nút nổi được giới hạn trong khung màn hình; độ mờ cửa sổ tuân theo ngưỡng chạm xuyên của thiết bị.
- Kiểm tra khóa màn hình/ứng dụng loại trừ trước và sau OCR, cũng như trước hiển thị. Lỗi truy cập UsageStats tạm dừng xử lý thay vì làm chết luồng capture.
- Loại bỏ callback của WebSocket đã hết hiệu lực; không xóa ghép cặp khi máy chủ chỉ đang đầy. Token và thông tin ghép cặp được lưu đồng thời sau khi mã hóa thành công.
- Giới hạn chờ yêu cầu trên Android; đổi cấu hình hoặc xóa dữ liệu thắng kết quả còn đang dịch trên máy chủ, không ghi lại dữ liệu vừa xóa. Token thống kê được gắn với đúng ngày UTC của yêu cầu.
- Nhập từ điển JSON trong một giao dịch; sửa mục theo ID, kiểm tra trùng nguyên văn/profile và giới hạn 500 mục.
- Windows giữ cổng đã bind suốt thời gian chạy để tránh hai tiến trình cùng khởi động; xác minh phiên cũ trước khi mở lại; báo lỗi khi cổng do ứng dụng khác sử dụng.
- Bổ sung 14 kiểm thử Android JVM, 7 kiểm thử Android 15 native, 30 kiểm thử Python và kiểm tra EXE thực tế. Bản phát hành vẫn cần khóa Gemini thật và nghiệm thu firmware Redmi.
