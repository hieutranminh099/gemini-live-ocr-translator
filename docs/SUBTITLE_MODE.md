# Phụ đề — Android 1.6.2 / Windows 1.6.0

**Backend hiện tại là Gemini API, Fast dùng Gemini 3.5 Flash-Lite.** Khóa trên Windows được mã hóa DPAPI; Android không chứa khóa. CLI thử nghiệm mặc định tắt và đang tắt trên máy này. Local/Hybrid chưa bật. [Kiểm tra API 1.6.2](API_RESTORE_V162.md), [cấu hình](GOOGLE_AI_PRO.md).

## Chọn vùng và tự động

1. Chọn đúng ngôn ngữ nguồn, **Tự động tăng tốc · Gemini**, bật dịch và cấp quyền nổi/thu màn hình.
2. Chuyển sang video, nhấn **VÙNG**, kéo góc khoanh gọn cả chiều cao dòng nguồn. Kéo trong khung để di chuyển; **Lưu vùng**. Tránh pinyin/tiếng Việt, logo và nút player. Vùng lưu theo tỷ lệ màn hình; xoay/đổi player có thể cần chỉnh lại.
3. Thu toàn màn hình (gồm BlueStacks Android 9) đặt dải Việt nền kín và nút ngoài VÙNG OCR. Chừa chỗ phía trên/dưới. Không ẩn/hiện định kỳ để lấy ảnh; nguồn được để hở để OCR đọc câu mới.
4. Chạm nút tròn tạm dừng/tiếp tục, giữ để dừng phiên. Chữ nguồn mất thì xóa OCR và bản dịch. Câu mới thiếu backend báo lỗi cấu hình, không giữ câu cũ như câu hiện tại.

Capture giữ một khung mới nhất, một OCR chạy, không đợi Gemini. Response đối chiếu request/vùng/thế hệ và phải xác nhận ảnh OCR lấy sau response trước khi vẽ tự động. Khung lặp cùng câu không gọi Gemini lặp.

Phụ đề tự động Trung/Nhật/Hàn yêu cầu ký tự đúng hệ nguồn. Nguồn CJK mất thì dòng Việt/pinyin riêng không được thay làm nguồn. Tên Latin/số/gạch dưới cùng hàng CJK được giữ nếu OCR nhận được. Đây không phải nhận diện ngôn ngữ ngữ nghĩa: dòng Latin hoàn toàn khi chọn Trung sẽ bị bỏ; chọn Anh nếu video chỉ có chữ Latin.

**Tự theo dõi dòng nguồn** là thử nghiệm, mặc định tắt, chỉ sửa khi chưa chạy. Hai quan sát tương đồng mới thu hẹp chiều dọc trong VÙNG; không tự quét cả màn hình. Quét lại toàn VÙNG mỗi 750 ms; mất dòng thì trở về toàn vùng. Chưa có benchmark khẳng định tùy chọn làm nhanh hơn. Nếu dòng di chuyển/credit nhiều hàng, nên tắt và khoanh bằng tay.

Câu đổi bình thường không thêm debounce; OCR dao động ít nhất ba lần/giây thì chờ 60 ms ổn định trước yêu cầu mới. Capture/OCR tiếp tục; cache không chờ gate này. Không thêm chữ còn thiếu vào OCR.

## Một khung và lớp phủ

Chọn **Dùng kiểu phụ đề 1 khung → Chạm dịch 1 khung**, bật dịch, dừng video rồi nhấn **1 KHUNG**. Một lần chỉ đọc một khung; giữ câu đó tới khi có kết quả. **HỦY** cho phép chụp lại. Chế độ này không tự đọc câu sau.

Một khung và chia sẻ **Một ứng dụng** trên Android 14+ giữ kiểu nền kín đè chữ gốc. Tùy chọn **Thu toàn màn hình** cần khớp hộp thoại hệ thống. Tự động thu toàn màn hình dùng dải ngoài vùng để tránh thu lại bản dịch mà không cần nháy. Không đủ chỗ thì thu hẹp VÙNG.

Dải nhận chạm trong diện tích của nó; đổi vùng/tạm dừng để thao tác player phía sau. Có tùy chỉnh cỡ/màu/vị trí; câu dài có thể rút gọn bằng “…”. **Khung vừa đọc** chỉ giữ trong RAM của phiên.

## Thời gian và lỗi

- **Máy chủ: N ms**: Windows và hàng đợi server, kết thúc trước gửi response; không phải toàn luồng hay riêng AI.
- **Khung OCR → hiện: N ms**: từ ảnh khung phát hiện câu tới callback vẽ overlay; chưa gồm chờ lấy khung sau khi chữ xuất hiện.
- **Đang dịch Ns**: số giây chờ đang trôi qua, chưa là latency hoàn tất.
- RTT là Android↔Windows; RTT trừ server vẫn gồm xử lý/serialize/lập lịch, không phải riêng mạng. Token đầu của stream chưa là bản dịch hữu ích hoàn chỉnh.
- **Không có chữ nguồn**: kiểm tra ngôn ngữ/VÙNG, chừa trọn chữ/viền. ML Kit có thể sai khi chữ mờ/chạy/gạch dưới; chưa sửa được mọi lỗi nhận diện.
- **Chưa kết nối**: mở Companion, BlueStacks chạy file khôi phục ADB. Điện thoại kiểm tra Wi-Fi/ghép cặp.
- **CLI đang tắt**: chọn API và lưu khóa trên Windows để dùng đường chính thức. Máy hiện tại đã cấu hình API; metadata mới trên Android hiển thị đúng provider/trạng thái.
- **Màn hình đen**: nội dung có thể chặn thu bằng FLAG_SECURE; app không vượt bảo vệ đó.

Chưa nghiệm thu Redmi thật hoặc chứng minh P95 ≤ 1 giây cho câu mới.

## Lỗi cấu hình và thử lại từ 1.6.1

Lỗi thiếu backend/khóa/quyền hoặc cấu hình được giữ nguyên lý do, không tự thử cùng câu theo đồng hồ. OCR vẫn đọc câu tiếp theo; RAM và tra cache Windows cho câu chưa gặp vẫn hoạt động. Các câu lỗi cấu hình được ghi nhớ tối đa 256 khóa trong RAM của phiên, theo ngôn ngữ/profile/mode/câu.

Khi sửa cấu hình/cache/từ điển ở Windows, thông báo invalidate hoặc kết nối mới xóa trạng thái lỗi để lấy lại. Có thể bấm **1 KHUNG** chủ động thử lại; thao tác này không làm backend chưa hỗ trợ trở thành dùng được. Tạm dừng/đổi VÙNG không tự xóa lỗi cấu hình. Lỗi tạm thời vẫn thử theo khoảng chờ 5/15/60 giây và hiện đúng nguyên nhân. [Kết quả kiểm tra](RETRY_FIX_V161.md).
