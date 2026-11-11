# Bổ sung tập và tua chọn vùng OCR — 1.8.12

Nút Thêm tập mới nằm trên thẻ và menu Kho phim. Tạo hoặc mở lại lượt bổ sung riêng liên kết bằng ID bộ phim; chỉ xử lý video mới. Sau kiểm tra/duyệt, lưu nối các tập theo ID dự án nguồn, không ghi đè do trùng tên hay số tập. Giữ tên bộ, ảnh bìa, thứ tự tập cũ, phụ đề và vị trí xem. Xác nhận lặp không nhân đôi tập; lỗi giữa chừng không công bố danh sách dở hoặc xóa nguồn cũ. Bộ chỉ ở Drive cần tải ngoại tuyến trước khi bổ sung để giữ đủ media lúc đồng bộ lại.

Hộp chọn vùng OCR dùng chung có thanh tua, đồng hồ và các bước ±1/±5 giây. Kéo thanh chỉ tải khung mới khi thả; vùng đang chỉnh được giữ. Xem thử OCR dùng đúng mốc vừa tua, không gọi dịch/API. Khóa xác nhận trong lúc đổi khung hoặc OCR đang chạy; giải phóng bitmap cũ, giới hạn một lần giải mã khung trong mỗi hộp chọn. Chọn vùng vẫn chỉ lưu sau xác nhận; hủy không đổi dự án.

## Cách dùng

- Kho phim → **Thêm tập mới** trên thẻ bộ phim hoặc trong menu ba chấm → chọn video mới → thiết lập, dịch và kiểm tra như thường → xác nhận thêm tập vào bộ phim. Hủy chọn tệp không thay đổi bộ phim đã lưu; lượt bổ sung có thể mở lại.
- Dịch hàng loạt → **2. Thiết lập phụ đề** → **Chọn vùng chữ Trung** → kéo thanh tua hoặc dùng **−5 / −1 / +1 / +5 giây** đến cảnh có thoại → khoanh chữ → **Xem thử OCR** → **Xác nhận vùng**. Nút tua chỉ đổi ảnh xem trước; không tự lưu vùng hay gửi Gemini.

## Phạm vi kiểm tra

Kiểm thử bổ sung gồm lưu nối và mở lại dữ liệu, giữ tập/vị trí xem cũ, xác nhận lặp, hoàn tác khi nguồn mới bị lỗi, nhận diện lượt bổ sung đã lưu, biên thời gian tua và giao diện tua với khung hình tổng hợp. Kết quả chạy, chữ ký APK và cài đặt được ghi bên dưới.

## Kết quả

- Bộ kiểm thử rộng: 610 bài / 106 nhóm đạt, không lỗi hoặc bỏ qua; gồm video, batch, movie, uxbatch và settings.
- Bản nguồn cuối: 19 bài liên quan đạt; bài giao diện tua mới đạt sau khi bổ sung metadata thời lượng cho mẫu giả lập. Kiểm tra các mốc giải mã 0 → 5 → 4 giây, nút hiển thị trong màn hình ngang và xác nhận giữ vùng đã chọn. Đây là kiểm tra giao diện với khung giả lập, không phải đo tốc độ giải mã phim thật.
- APK 1.8.12 / mã 37 khớp chữ ký bản cũ, đạt căn chỉnh 16 KB. 718 mục assets/lib/res giữ nguyên CRC/kích thước.
- SHA-256: `BAD14C31DEAE77D08BB7874D6FEADE9BD85304CE0DBB9739CC3CFF9E58983997`.
- Các phép thử không gọi dịch vụ AI hoặc sử dụng phim riêng. Đã cài đè và kiểm tra trực tiếp trên BlueStacks ngày 10/11/2026.

## Kiểm tra BlueStacks sau cài

- Cài đè thành công bản 1.8.12 / mã 37. SHA-256 APK cài thực tế khớp; UID 10067 và ngày cài đầu 22/10/2026 12:04:13 giữ nguyên. Không có dịch vụ dịch đang chạy khi cập nhật.
- Kho phim: thấy Thêm tập mới trên thẻ của bộ phim và trong menu Tùy chọn phim. Không tạo lượt bổ sung giả trên dữ liệu người dùng.
- Mở dự án hàng loạt sẵn có → bước 2 → Chọn vùng chữ Trung: thanh tua, đồng hồ và bốn nút bước đều hiển thị và hoạt động. Thử mốc 00:01 → +5 = 00:06 → −1 = 00:05 → kéo giữa thanh = 03:52 của video 07:44. Khung xem trước giải mã thành công, nút xác nhận khả dụng.
- Sau thử đã bấm Hủy, không xác nhận vùng mới, không bắt đầu dịch và không phát sinh lượt gọi AI. Lưu nối tập được kiểm chứng bằng mẫu kiểm thử ngoại tuyến; chưa bổ sung một tập thật vào kho của người dùng.
