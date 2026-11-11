# Bản sửa phản hồi và tiến độ 1.8.11

Lô 10 video giữ dữ liệu OCR nhưng các lượt dịch trả TEXT_INVALID_RESPONSE. Ngay trước khi cập nhật (09/11/2026 22:54 giờ Việt Nam), thống kê ghi 74 lượt gửi, 0 phản hồi đọc được, 69 lượt dừng vì lỗi (48 kết quả không hợp lệ, 21 dịch vụ gián đoạn) và 33 lượt chờ. Nhóm gần nhất có 1.465 ID. Phần trăm 100 của bước trước tiếp tục xuất hiện ở TRANSLATE dù chưa có câu hoàn tất. Nguyên nhân parser cụ thể của các phản hồi cũ chưa được ghi lại, vì vậy chưa thể quy tất cả phản hồi lỗi cho một nguyên nhân duy nhất.

Staged HYBRID thường bắt đầu 16 ID. Ba phản hồi đầy đủ liên tiếp cho phép tăng gấp đôi, tối đa 64. Giới hạn được lưu trong checkpoint. Phản hồi thiếu làm giảm nhóm với mức sàn 8. Phản hồi sai định dạng hoặc ID chỉ được thử phục hồi thêm một lượt trong mỗi worker. Auth, quota, chờ dịch vụ và nội dung bị chặn giữ hợp đồng riêng. Các ID hợp lệ đã lưu được giữ trước khi lập yêu cầu tiếp theo.

Planner nhận giới hạn mục tiêu trực tiếp, giữ toàn hội thoại khi vừa ngân sách. Yêu cầu nhóm nhỏ có enum ID nguồn. Parser ghi nguyên nhân cố định cho envelope, finishReason, nội dung rỗng, JSON, schema, ID lạ, ID trùng và kích thước. Telemetry chỉ lưu mã số và thời điểm. Phản hồi có mapping mơ hồ bị loại trước khi cập nhật timeline.

Tiến độ thẻ và tiến độ lưu được tính lại theo công đoạn. TRANSLATE tính số câu có bản dịch hợp lệ, gồm các câu đã được duyệt có nội dung. Header hiển thị số video lấy xong nguồn, dịch xong và hoàn tất các bước. Việc lưu Kho phim tiếp tục yêu cầu timeline dịch hoàn tất và nguồn đọc được.

Kiểm chứng cần thực hiện qua parser, planner, worker, lưu checkpoint và mở lại dữ liệu cũ. Các fixture phải chặn mạng và kiểm giữ ID, thời gian, câu đã duyệt, giới hạn số lần thử và phần trăm. Sau khi dựng/ký, cài cập nhật tại chỗ rồi kiểm tra lô hiện có qua giao diện và các yêu cầu dịch được giới hạn bởi cơ chế phục hồi mới.

Kiểm chứng offline: assembleDebug và 605 ca JVM/Robolectric thuộc video, batch, movie, uxbatch, settings đều đạt; 0 lỗi, 0 bỏ qua, 105 bộ kiểm thử. Bao gồm parser, planner, worker, checkpoint và dữ liệu cũ báo 100% sai. Các phép thử giữ ID, thời gian và câu được người dùng duyệt; kiểm số lần gửi, giới hạn nhóm và các nhánh phản hồi thiếu/rỗng/bị cắt/sai ID. Không dùng API cho kiểm thử offline.

APK 1.8.11 (versionCode 36) đã ký bằng cùng chứng thư với 1.8.10, kiểm tra chữ ký và căn chỉnh 16 KB. Dữ liệu tĩnh assets/lib/res giữ nguyên; manifest thay phiên bản. SHA-256: `A41A1F1FD51C1E9E7EF1F1329FC3A2A1F6A2B3AC41D24A92720A4AF5C41AED8D`.

Cài cập nhật tại chỗ vào BlueStacks thành công. Phiên bản cài đặt là 1.8.11 / 36, SHA-256 trùng APK đã ký. UID ứng dụng và thời điểm cài lần đầu giữ nguyên. Lô 10 video, tổng 1:17:22 được tiếp tục từ nguồn đã có, không chạy lại OCR. Giao diện xác nhận “Đã lấy nguồn: 10/10 · Đã dịch: 0/10”, “Hoàn tất các bước: 0/10 video”. Một thẻ hiển thị 8/1.873 câu dịch hợp lệ, 0% thay vì 100% sai.

| Mốc kiểm tra 09/11/2026 (giờ Việt Nam) | Số liệu cộng dồn của lô thực tế |
|---|---|
| 22:54, trước cập nhật | 74 lượt gửi; 0 phản hồi đọc được; 0 câu hợp lệ; nhóm gần nhất 1.465 câu |
| 22:56:40, sau tiếp tục | 78 lượt gửi; 4 phản hồi đọc được; 48 câu hợp lệ; nhóm gần nhất 16 câu |
| 22:57:36 | 83 lượt gửi; 6 phản hồi đọc được; 72 câu hợp lệ; phát sinh 4 lượt giới hạn gọi; mức gửi giảm từ 4/4 xuống 1/4 |
| 22:58:48, sau thời hạn chờ | 85 lượt gửi; 7 phản hồi đọc được; 80 câu hợp lệ; tự tiếp tục; vẫn 1/4 yêu cầu đồng thời |
| 23:00:14, kiểm tra bàn giao | 92 lượt gửi; 12 phản hồi đọc được; 152 câu hợp lệ; tổng 8 lượt giới hạn gọi mới; đang chờ 42 giây, service vẫn hoạt động |

Từ mốc trước cập nhật đến 23:00:14 không tăng bộ đếm phản hồi sai định dạng (vẫn 48) hoặc dịch vụ gián đoạn (vẫn 21). Một phản hồi rỗng/thiếu 16 câu đã được thu nhỏ nhóm và tiếp tục; số câu hợp lệ tiếp tục tăng. Đây là kiểm chứng trên lô thực tế đang chạy, chưa phải bằng chứng tất cả video đã dịch xong hoặc đã lưu Kho phim. Các số “câu hợp lệ” trong thống kê là tổng các lượt chấp nhận, không thay thế số câu/ID hiện có ở từng video.

Kết luận: lỗi tiến độ tái sử dụng 100% từ bước trước được xác nhận và sửa trực tiếp. Giảm số ID mục tiêu cùng ràng buộc enum đã khôi phục việc đọc/lưu phản hồi trong lô đang lỗi; không có nội dung phản hồi cũ để chứng minh mọi TEXT_INVALID_RESPONSE trước đó có cùng nguyên nhân. Hệ thống hiện còn chịu giới hạn lượt gọi Gemini; cơ chế giảm đồng thời, chờ và tiếp tục đã hoạt động thực tế. Không tăng CPU/RAM để vượt giới hạn API, không đổi model, khóa hoặc thanh toán.

Các bước cải thiện tiếp theo cần căn cứ số liệu: theo dõi tỉ lệ chấp nhận và thời gian mỗi nhóm 16/32/64, phân biệt giới hạn lượt gọi với hạn mức token khi dịch vụ cung cấp thông tin, và chỉ tăng số yêu cầu sau chuỗi thành công. Không tự bỏ câu OCR khó, không gán bản dịch theo vị trí, không đánh dấu hoàn tất để ép xuất Kho phim. Sự cố dịch vụ bên ngoài được xử lý theo giới hạn thử lại và checkpoint; không thể bảo đảm Gemini luôn trả kết quả hoặc luôn sẵn sàng.

Dự án App chính đã nhận 22 tệp mã/kiểm thử cùng README và báo cáo này. Thư mục `dist/android-current` chỉ giữ APK 1.8.11, tệp đối chiếu SHA-256 và kết quả kiểm chứng. APK 1.8.10 được xác nhận đối chiếu và giữ trên E: để quay lui; APK cũ đã được bỏ khỏi C:.
