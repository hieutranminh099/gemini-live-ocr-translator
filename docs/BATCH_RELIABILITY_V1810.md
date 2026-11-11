# Cải tiến độ ổn định dịch hàng loạt 1.8.10

## Vấn đề xác định

Lô thực tế trước đó đã gặp `API_SERVICE_503`, `API_RATE_LIMIT` và phản hồi thiếu câu. Lô 7 video đã được hoàn tất và lưu thư viện trong bản trước; đợt này cải tiến các lần sử dụng tiếp theo, không dịch lại lô đó.

Kiểm tra nguồn phát hiện thêm các tình huống:

- Mức đồng thời cố định chưa phản ứng với lỗi quá tải; nếu chỉ đặt bộ điều chỉnh trong một công đoạn, mức giảm sẽ mất khi tự thử lại.
- Thông báo 429 có lời nhắc chung về billing có thể bị phân loại thành hết quota ngày dù dịch vụ yêu cầu chờ ngắn. Điều này có thể chặn tự tiếp tục và đưa khóa vào thời gian chờ 24 giờ sai nguyên nhân.
- Deadline tổng 300 giây của HYBRID bao gồm cả thời gian chờ quyền gửi, Live và Retry-After. Một yêu cầu chưa được gửi có thể bị báo timeout dù transport vẫn có timeout riêng.
- Bộ đếm cục bộ chưa có giao diện để phân biệt số lượt gửi thực, chờ/thử lại, phản hồi thiếu và lỗi cuối cùng.

Đây là các tình huống xác định từ mã và kiểm thử tổng hợp; không khẳng định chúng đều đã xảy ra trong lô phim người dùng. Google mô tả 503 là dịch vụ tạm thời quá tải hoặc ngừng đáp ứng và phân biệt 429 giới hạn lượt gọi với quota đã hết. [Tài liệu lỗi Gemini](https://ai.google.dev/gemini-api/docs/api-errors).

## Thay đổi

- Tự giảm số yêu cầu mạng đồng thời khi gặp lỗi mạng, timeout, giới hạn lượt gọi hoặc lỗi dịch vụ; tăng dần trở lại sau chuỗi thành công. Giới hạn tài nguyên/chế độ người dùng vẫn là trần. Giữ bộ điều chỉnh trong toàn lượt xử lý, qua công đoạn và các lượt tự tiếp tục. Không hủy các kết quả đang về chỉ để giảm số luồng.
- Giữ riêng thời gian chờ và lỗi của mỗi khóa trong chế độ nhiều khóa. Xác thực/quota không được dùng làm lý do gửi lặp vô hạn; hạn mức cùng dự án vẫn phải được tôn trọng.
- Phân loại 429 theo bằng chứng quota ngày/vô hiệu hóa và cửa sổ tạm thời, thay vì từ billing trong thông báo chung. Deadline do dịch vụ cung cấp được giữ.
- HYBRID staged thường dùng timeout HTTP và số lần gửi hữu hạn; chờ quyền gửi không còn bị tính vào deadline tổng của worker. Các thao tác chờ vẫn hủy được. Luồng video đơn và tác vụ Economy giữ hợp đồng deadline trước đó.
- Thêm phần Thống kê xử lý ở Tiến độ và Kiểm tra/xuất: tổng số lượt gửi, phần thiếu/rỗng/bị cắt, chờ/thử lại và lỗi theo nhóm. Chỉ đọc các số đếm cho UI, không đưa nội dung phim, khóa API hoặc phản hồi thô vào thống kê.

## Kiểm chứng

- Lượt kiểm chứng đầu: **583 kiểm thử Android JVM/Robolectric đạt, 0 lỗi/thất bại/bỏ qua**; dựng APK thành công trong 7 phút 25 giây. Có 4 ca admission, 5 ca thống kê, 6 ca deadline và các ca quota/nhiều khóa bổ sung.
- Rà soát tiếp phát hiện worker tuần tự có scope riêng, cần truyền ngữ cảnh phục hồi từ batch vào worker; đã bổ sung và thêm một ca worker tích hợp bảo toàn Approved/nguồn/timing.
- Nguồn cuối: **584 kiểm thử Android JVM/Robolectric đạt, 0 lỗi/thất bại/bỏ qua**; dựng APK thành công trong 5 phút 36 giây. Các phép thử dùng phản hồi tổng hợp, có chặn mạng.
- APK: package `com.gemini.ocr`, phiên bản `1.8.10`, mã `35`, SHA-256 `3C5CA56BC2F4A928B338BA5754BA540BA71595D6FC8AABB0E3D4BDBCF9F61229`. Đã xác minh chữ ký cập nhật khớp và căn chỉnh hợp lệ; 719 mục assets/thư viện/tài nguyên giữ nguyên, manifest đổi phiên bản.
- Đã cài cập nhật 1.8.10 lên BlueStacks, xác minh hash, UID và thời điểm cài lần đầu được giữ; ứng dụng mở được. Mở dự án 7 video đã hoàn tất qua giao diện, mở phần Thống kê dịch và lỗi và xác minh không có tác vụ dịch chạy ngoài ý muốn.
- Bảng đọc được số liệu lịch sử: 175 lượt gửi, 157 phản hồi, 70 phản hồi thiếu và 2.922 lượt câu hợp lệ. Tổng lượt câu thiếu trong các phản hồi là số cộng dồn, có thể lặp cùng một ID; không phải số câu còn thiếu hiện tại. Kiểm tra giao diện phát hiện nhãn cũ dễ nhầm, nên đã sửa thành "lượt câu bị thiếu (có thể lặp)" và chỉ rõ số chưa xong hiện tại nằm ở từng video.
- Lần dựng cuối chỉ thay nhãn giao diện; không thay logic đã qua 584 kiểm thử. Đã dựng lại APK thành công trong 6 phút 7 giây và xác minh 720 mục tài nguyên/manifest/thư viện giữ nguyên so với APK 1.8.10 đầu; đã cài cập nhật cuối và xác minh trực tiếp nhãn mới hiển thị đúng trên BlueStacks.

Không đo hoặc công bố phần trăm tăng tốc, không gửi yêu cầu Gemini thật để thử lỗi và không thay đổi khóa, mô hình, thanh toán hay cấu hình hệ thống của người dùng.

Bàn giao: bản cuối đã cài có hash khớp APK hiện tại, phiên bản 1.8.10/mã 35; dữ liệu cài đặt được giữ. Bảng thống kê hiển thị nhãn cộng dồn mới và không khởi động xử lý phim. Nguồn/APK/tài liệu được giữ trong App, chỉ một APK hiện tại trên ổ C; bản khôi phục và tệp dựng tạm ở ổ E.
