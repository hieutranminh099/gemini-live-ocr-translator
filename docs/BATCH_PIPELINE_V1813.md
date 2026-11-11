# Pipeline từng tập — Android 1.8.13 / 38

Ngày 10/11/2026. Bản nâng cấp trên mã nguồn hiện tại; không thay backend, model, tài khoản, định dạng checkpoint hay dữ liệu phim.

## Thay đổi

- Trích/làm sạch nguồn theo một lane; dịch/hiệu đính/hoàn thiện theo từng tập. Khi đủ RAM/CPU, trích tập tiếp trong lúc dịch tập trước. Bộ điều tiết vẫn giới hạn tài nguyên và ưu tiên playback/Live.
- Tập đã FINISH trong luồng lưu nguồn vào Kho phim được ghi DONE ngay, không đợi toàn bộ bộ phim. Chưa tự duyệt hoặc lưu Kho phim khi chưa xác nhận.
- Worker tạm dừng trước khi commit kết quả được truyền lên điều phối. Một lane bị hủy sẽ hủy scope phối hợp, giải phóng producer/worker và giữ checkpoint; không bỏ qua nhóm chưa lưu khi phục hồi.
- Giữ nguyên thêm tập vào bộ phim cũ, vị trí xem/phụ đề, tua chọn vùng OCR, các chế độ RAM và ba khóa Gemini.

## Kiểm chứng

- Bộ hồi quy cuối: **635 kiểm thử / 110 nhóm đạt, 0 fail/error/skip**. Phạm vi batch, video, movie, uxbatch, settings. Gồm cả 8 ca BatchEpisodeIntegrationTest trước đây chưa chạy; JUnit/Robolectric dùng nguồn/phản hồi giả, không gọi Gemini hay xử lý phim riêng.
- Compiler Kotlin + JUnit độc lập: 16 ca điều phối/playback đạt, có ca worker tự hủy không làm producer kẹt.
- Các kiểm thử cũ được chỉnh đúng hợp đồng từng tập thay vì yêu cầu trích hết danh sách trước mọi lượt dịch. Sửa URI mẫu file Windows và kiểm tra rõ phần draftVi được chuẩn hóa, vẫn giữ nguyên câu sửa tay.
- Lượt đầu có 7/634 ca lỗi; sau sửa còn 1/20 ở phục hồi sau hủy; bộ cuối 635 ca đạt. Không dùng kết quả cũ thay cho lượt cuối.
- Build/test dùng bản sao nguồn và output cùng trên E:, cache Gradle có sẵn; không sửa firewall, JDK hoặc tải dependency/model mới.
- APK khớp chứng chỉ cũ, đạt căn chỉnh 16 KB. 718 mục assets/lib/res giữ nguyên CRC và kích thước.
- SHA-256: `7F852D619618A9A0AA2E5A0EE03B0CDBE40542A1C9706F76CCC185D5D7632F2B`.
- Chưa đo tốc độ dịch phim thật hoặc benchmark API. Kết quả cài thiết bị ghi tiếp sau khi xác minh.

## Bảo vệ dự án

Chỉ cập nhật 6 file giao: build.gradle.kts, BatchJobs.kt, BatchEpisodePipeline.kt và ba lớp kiểm thử liên quan. Kiểm tra hash baseline trước khi ghi vào checkout chính. Không commit/push/reset/clean. Bộ cài cũ lưu rollback trên E:; dist/android-current trên C: chỉ giữ bản mới nhất.

## Cài và kiểm tra BlueStacks

Đã cài đè thành công 1.8.13 / 38 trên emulator-5554, không có tác vụ dịch đang chạy khi cập nhật. SHA-256 APK trên thiết bị khớp bộ cài đã ký. UID 10067 và ngày cài đầu 22/10/2026 12:04:13 giữ nguyên.

Mở được Kho phim (25 dự án chuẩn bị/xử lý, 4 bộ phim đã hoàn thành) và mở lại một dự án hàng loạt với đủ bốn bước. Không bắt đầu dịch hoặc xác nhận sửa vùng/phụ đề trong kiểm tra này; không phát sinh quota. Cài đè và số mục hiển thị không thay thế kiểm chứng nội dung từng bộ phim; các bất biến lưu nối/phục hồi đã được kiểm thử bằng fixture offline.

Kiểm tra tài liệu: 5 tài liệu, 173 liên kết nội bộ, không lỗi. git diff --check với quy tắc CRLF đạt cho các file sửa. Source giao trong dự án chính khớp hash với sáu file đã build trên E:.
