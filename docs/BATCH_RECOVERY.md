# Phục hồi và xuất từng tập (1.8.8)

Trong **Thiết lập phụ đề**, mục **Tự tiếp tục khi gặp lỗi tạm thời** bật mặc định. Khi bấm Bắt đầu hoặc Tiếp tục, hàng đợi tự thử phần còn thiếu sau ít nhất 1 phút, tối đa 3 lượt cho lần chạy đó. Mốc chờ của Gemini được ưu tiên nếu dài hơn. Nếu mốc chờ của lượt tự tiếp tục vượt 5 phút, ứng dụng lưu mốc và tạm dừng. Nút Tạm dừng hoặc Dừng hàng đợi có hiệu lực cả khi đang đếm ngược. Sau khi ứng dụng bị đóng, bấm Tiếp tục để khôi phục hàng đợi theo mốc đã lưu.

Gemini có thể trả đủ một phần của nhóm. Ứng dụng giữ các ID đã có bản dịch hợp lệ và chỉ gửi lại phần thiếu. Mã lỗi cụ thể được lưu cùng trạng thái tập. Một tập cần sửa thủ công không bị chạy lại khi hàng đợi tự thử lỗi tạm thời của tập khác.

| Tình huống | Cách xử lý |
| --- | --- |
| Mất mạng, quá thời gian, dịch vụ 5xx | Kiểm tra kết nối. Hàng đợi tạm ngừng gửi và tự thử lại theo mốc. |
| 429 do giới hạn theo phút | Chờ mốc hiển thị. Ứng dụng tôn trọng cả `Retry-After` và thông tin thời gian chờ của Google. |
| 429 do hạn mức ngày hoặc mức cấp bằng 0 | Kiểm tra hạn mức dự án và bấm Tiếp tục sau khi đã được cấp lại. |
| 400, 401 hoặc 403 do khóa hoặc quyền | Kiểm tra khóa và quyền Gemini trong Tùy chỉnh rồi bấm Tiếp tục. |
| Nhóm thiếu ID hoặc bị cắt đầu ra | Kiểm tra số câu còn thiếu. Hàng đợi có thể tự thử nhóm nhỏ hơn và giữ câu đã dịch. |
| Phản hồi sai định dạng, dữ liệu bị chặn hoặc nhóm quá lớn | Mở **Xem và sửa phụ đề**. Kiểm tra câu còn thiếu, ngữ cảnh và kích thước nhóm. |
| Nguồn thiếu quyền, vùng OCR sai, thiếu model hoặc bộ nhớ | Kiểm tra quyền đọc tệp, vùng chữ, gói dịch/ASR hoặc chỗ trống theo chỉ dẫn trên thẻ tập. |

Google phân biệt hạn mức theo phút và theo ngày. Các lỗi khóa, quyền, hạn mức ngày và lỗi nguồn cần người dùng xử lý. [Mã lỗi Gemini](https://ai.google.dev/gemini-api/docs/api-errors?hl=en) · [Hạn mức](https://ai.google.dev/gemini-api/docs/rate-limits?hl=en) · [Thời gian chờ RetryInfo](https://developers.google.com/health/reference/rpc/google.rpc#retryinfo).

Thẻ tập ở **Tiến độ** hoặc **Kiểm tra và xuất** có nút **Lưu SRT tiếng Việt** và **Lưu MP4 có sẵn** khi dữ liệu của tập đã hoàn tất. Lưu SRT không cần chờ tập khác. Ứng dụng kiểm lại tập và tệp tại thời điểm chọn nơi lưu. Muốn tạo MP4 mới, tạm dừng hàng đợi nếu đang chạy rồi mở phần xuất của tập. Ghép nhiều phần và lưu Kho phim vẫn theo quy trình xác nhận hiện có.

Kiểm chứng offline tại `BatchRecoveryTest`, `BatchOrchestrationTest`, `BatchRetryUiTest`, `BatchEpisodeExportTest` và `VideoApiRecoveryTest`. Các ca mô phỏng phản hồi của Gemini để xác minh lưu đúng ID, giới hạn lượt, tạm dừng trong thời gian chờ, hạn mức và xuất một tập trong khi tập khác chưa hoàn thành.
