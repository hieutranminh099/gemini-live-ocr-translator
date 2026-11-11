# Theo dõi và cải tiến dịch hàng loạt — 08/11/2026

Báo cáo này ghi lại lô 7 video đã hoàn tất trên bản 1.8.9. Các cải tiến tiếp theo và bản cập nhật 1.8.10 nằm trong [báo cáo 1.8.10](BATCH_RELIABILITY_V1810.md).

## Phạm vi

Theo dõi lô hiện có gồm 7 video, tổng thời lượng 29:10, trên BlueStacks. Giữ cấu hình nguồn OCR và cách dịch HYBRID của người dùng. Đây là xử lý công việc đang dở, không phải phép đo so sánh tốc độ hoặc chất lượng ngôn ngữ.

Không ghi khóa API, nội dung hội thoại, URL tệp nguồn hoặc phản hồi dịch vào báo cáo. Các thời điểm dưới đây theo giờ Việt Nam (UTC+7).

## Bằng chứng quan sát

| Thời điểm | Kết quả quan sát qua giao diện | Diễn giải |
| --- | --- | --- |
| 18:35 | 5/7 video đạt 100% ở công đoạn Dịch; hai video còn 77% và 67%. Lô đã dừng sau ba lượt tự thử; chi tiết lỗi `API_SERVICE_503`. | Cơ chế lưu kết quả và tiếp tục phần thiếu đã hoạt động. Lỗi dịch vụ vẫn có thể khiến lô dừng. |
| 18:39 | Sau khi tiếp tục, hai video đạt 85% và 68%; dịch vụ xử lý đang chạy. | Kết quả đã xong được giữ và số câu hoàn tất tiếp tục tăng. |
| 18:44 | 6/7 video đạt 100% ở công đoạn Dịch; video cuối đạt 72%, còn 108 câu. Lô đang tạm dừng, chi tiết lỗi `API_RATE_LIMIT`. | Cần tôn trọng thời gian chờ. Đây là lỗi giới hạn lượt gọi khác với lỗi dịch vụ 503 trước đó. |
| 18:50 | Sau cài bản cải tiến và tiếp tục lô, đủ 7 video ở công đoạn Hoàn thiện 100%: 412, 173, 290, 740, 372, 546 và 389 câu. Không còn mã lỗi trên các thẻ; có nút Kiểm tra và xuất. | Đã hoàn tất xử lý timeline của cả lô. Không dùng thời gian này làm phép so sánh tăng tốc vì điều kiện dịch vụ thay đổi. |
| 18:52–18:54 | Duyệt toàn bộ và lưu thành công. Thẻ Kho phim hiển thị 7 tập, 29 phút, danh sách 7 tập đã hoàn thành, trạng thái Đã đồng bộ và nút Xem ngay được bật. | Đã xác minh bản lưu gồm video gốc kèm phụ đề riêng; lựa chọn tạo thêm bản sao video trên máy được giữ tắt. |
| 18:55 | Mở một tập từ đúng thẻ 7 tập, vào MoviePlaybackActivity; đã hết trạng thái Đang mở video, hiển thị video gốc/phụ đề riêng, không có thông báo lỗi đọc nguồn. Sau đó quay lại Kho phim. | Kiểm tra mở trình phát và nguồn thành công; chưa đánh giá toàn bộ lời dịch hoặc xem hết các tập. |

Đã đối chiếu tổng cộng 2.922 câu ở bước Hoàn thiện, duyệt và lưu đủ 7 tập vào Kho phim. Đã xác minh trạng thái đồng bộ và thử mở một tập. Các bản dịch trong snapshot thư viện được duyệt theo yêu cầu của người dùng; đây không phải xác nhận đã kiểm tra thủ công nghĩa của từng câu.

Kiểm tra tài nguyên lúc 18:42: hệ Android của BlueStacks có `MemTotal=8157208 kB`, khoảng 8 GB, CPU online `0-3` (4 nhân), heap tối đa cấu hình `384m`. Lựa chọn 16 GB trong ứng dụng không tự cấp thêm RAM hoặc CPU cho máy ảo; giới hạn worker vẫn phải theo tài nguyên thực tế. Chưa thay đổi thiết lập BlueStacks.

## Các vấn đề đã xác định và thay đổi

### 1. Phản hồi thiếu ID làm công đoạn dừng sớm

Triệu chứng ban đầu là `TEXT_INCOMPLETE_RESPONSE`, ví dụ tập 740 câu thiếu 205 câu. Luồng staged HYBRID trước đó lưu phần hợp lệ rồi dừng thay vì xử lý ngay phần thiếu.

Đã sửa: lưu các ID hợp lệ trước, tiếp tục chỉ với ID chưa hoàn thành, giảm nhóm phục hồi còn tối đa 64 hoặc nửa nhóm vừa gửi. Giới hạn được lưu cùng checkpoint. Câu nguồn, thời gian và câu được người dùng duyệt vẫn được bảo vệ. Ba phản hồi liên tiếp không có câu hợp lệ dừng bằng `TEXT_EMPTY_RESPONSE`; không tạo bản dịch để lấp chỗ thiếu.

### 2. Nhóm phục hồi có thể bị giữ mãi ở mức quá nhỏ

Kiểm tra mã cho thấy giới hạn nhóm chỉ giảm khi gặp phản hồi thiếu và chưa tăng sau phản hồi thành công. Nếu một checkpoint đã xuống một câu, phần còn lại có thể phải gửi từng câu kèm ngữ cảnh lặp lại. Đây là vấn đề xác định từ mã; chưa đọc checkpoint riêng của lô người dùng để khẳng định nó đang ở mức một câu.

Đã bổ sung: sau phản hồi đầy đủ, tăng gấp đôi giới hạn đến tối đa 64. Lưu kết quả và giới hạn mới trong cùng lần ghi checkpoint. Nếu tiếp tục thiếu, nhóm lại giảm. Kiểm thử tổng hợp bắt đầu với 130 câu chưa dịch và giới hạn một câu; yêu cầu mong đợi lần lượt là 1, 2, 4, 8, 16, 32, 64, 3 câu. Đây là kiểm thử hành vi, không phải số đo tốc độ Gemini thật.

### 3. Gemini trả lỗi dịch vụ 503 và giới hạn lượt gọi

Đây là lỗi đã quan sát trực tiếp trong lô hiện tại. Google mô tả HTTP 503 là dịch vụ tạm thời quá tải hoặc không hoạt động; cập nhật ứng dụng không thể bảo đảm phía dịch vụ luôn sẵn sàng. [Tài liệu lỗi Gemini](https://ai.google.dev/gemini-api/docs/api-errors).

Đã bổ sung: sau khi hết lượt thử trong một yêu cầu, hàng đợi chờ tăng dần 1, 2 rồi 4 phút, tối đa ba lượt tự tiếp tục. Luôn tôn trọng deadline dài hơn do dịch vụ cung cấp; chờ quá năm phút giữ mốc và dừng để người dùng tiếp tục sau. Giữ giới hạn số lần thử và khả năng tạm dừng. Lỗi xác thực, hết quota ngày và lỗi nội dung không được tự thử vô hạn.

Lượt tiếp theo còn quan sát được `API_RATE_LIMIT`: sáu tập dịch xong, tập cuối còn 108 câu. Không xóa thời gian chờ hoặc chuyển khóa nhằm bỏ qua quota dự án. Phục hồi kích thước nhóm giúp hạn chế số yêu cầu nhỏ lặp lại, nhưng chưa có bằng chứng đo cho thấy thay đổi này loại bỏ được lỗi quota trong lô thật.

## Hướng cải tiến tiếp theo

1. **Điều chỉnh số video gửi đồng thời theo sức đáp ứng dịch vụ.** Chế độ RAM quyết định giới hạn tài nguyên trên máy; khi có nhiều 429/503 nên giảm số yêu cầu đang gửi rồi tăng lại sau một chuỗi thành công. Cần kiểm thử riêng việc chia sẻ cooldown và hủy tác vụ trước khi triển khai. Chưa thay đổi cấu hình đồng thời của người dùng trong đợt này.
2. **Bổ sung bảng chẩn đoán gọn trong ứng dụng.** Hiển thị số phản hồi thiếu/rỗng/bị giới hạn đầu ra, số lỗi dịch vụ, kích thước nhóm hiện tại và thời gian chờ. Chỉ lưu mã lỗi và số đếm; không lưu nội dung, khóa hoặc phản hồi thô. Hiện đã có bộ đếm hiệu năng riêng, nhưng chưa có bảng tổng hợp các nguyên nhân này cho người dùng.
3. **Phân biệt rõ kết quả dịch và kết quả xuất.** Tiếp tục kiểm tra từng mốc TRANSLATE, FINISH và lưu Kho phim; hoàn thành bản dịch không tự chứng minh MP4 đã ghép thành công. Giữ khả năng tiếp tục phần còn thiếu sau khi đóng/mở app và kiểm tra nguồn còn đọc được khi lưu thư viện.

Ba khóa API trong cùng Google Cloud project vẫn dùng chung hạn mức dự án. Chế độ dùng đồng thời không đồng nghĩa hạn mức tăng ba lần. Không suy diễn quota từ dung lượng RAM; không tự đổi tài khoản, mô hình hay thanh toán. [Tài liệu hạn mức Gemini](https://ai.google.dev/gemini-api/docs/rate-limits).

## Kiểm chứng và bàn giao

- Bản sửa phản hồi thiếu đã được cài cập nhật vào BlueStacks; giữ dữ liệu ứng dụng và cấu hình hiện có.
- Bộ kiểm thử ngoại tuyến trước cải tiến bổ sung: 562 ca, không có lỗi.
- Bản cải tiến bổ sung: **564 ca Android JVM/Robolectric, 0 thất bại/lỗi/bỏ qua**; gồm 7 ca phục hồi phản hồi thiếu và 4 ca lịch thử lại. Dựng APK thành công trong 6 phút 49 giây; kiểm thử dùng phản hồi tổng hợp và chặn mạng.
- APK cải tiến: package `com.gemini.ocr`, phiên bản `1.8.9`/mã `34`, SHA-256 `37815AC6F1E82F378280455B0A8F3D839044DDADD710056B7EB879CBD3567294`. Đã xác minh chữ ký cập nhật khớp, căn chỉnh APK hợp lệ và 720 mục assets/thư viện/tài nguyên/manifest giữ nguyên.
- Đã cài cập nhật thành công lên BlueStacks lúc khoảng 18:48. Hash APK đã cài khớp bản dựng; UID và thời điểm cài lần đầu không đổi. Hoàn tất đúng lô 7 video, lưu đủ 7 tập vào Kho phim và xác minh trạng thái Đã đồng bộ. Đã trở lại màn hình Kho phim sau khi thử mở một tập.
- Nguồn hiện tại, APK và báo cáo này được giữ trong dự án App. Tệp dựng tạm và bản APK khôi phục được đặt ở ổ E; bản APK cũ trên ổ C đã được thay thế tại đường dẫn bản hiện tại.
- Chưa công bố phần trăm tăng tốc hoặc độ chính xác dịch vì chưa có phép so sánh độc lập, cùng điều kiện.
