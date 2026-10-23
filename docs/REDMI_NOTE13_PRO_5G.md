# Sử dụng trên Xiaomi Redmi Note 13 Pro 5G

**Bản hiện tại: Android 1.6.2 / Windows 1.6.0.** [Báo cáo hiện hành](API_RESTORE_V162.md). Các số CLI ở cuối tài liệu là lịch sử.

Đây là thiết bị mục tiêu do bạn cung cấp. Chưa có kết nối trực tiếp tới điện thoại; đã dịch bằng tài khoản thật trên Windows và BlueStacks Android 9, cùng kiểm thử Android JVM/native trước đó. Chưa xác nhận firmware Redmi thật.

1. Cài bộ cài Windows 1.6.0 và APK release 1.6.2 trong `dist/`. Nếu đã cài APK release trước từ dự án này, bản mới dùng cùng khóa ký và versionCode cao hơn. Bản debug dùng khóa khác nên không thể cập nhật đè lên bản release và ngược lại. Với phụ đề video, xem [chế độ phụ đề](SUBTITLE_MODE.md).
2. PC và điện thoại dùng cùng Wi-Fi, giữ Companion và PC mở. Máy hiện tại đã dùng Gemini API, Fast là Flash-Lite; khóa được lưu mã hóa trên Windows. Máy mới cần tự nhập khóa trong Cài đặt AI. CLI thử nghiệm mặc định tắt và không được Google hỗ trợ. Xem [trạng thái AI Pro/API](GOOGLE_AI_PRO.md). Local/Hybrid chưa đạt chất lượng nên chưa bật. Cho phép Firewall trên mạng riêng tư nếu Windows hỏi.
3. Mở **Thiết bị → Tạo mã ghép cặp** trên PC; dùng camera điện thoại quét QR, hoặc gửi liên kết sang điện thoại và dán trong **Kết nối → Đọc liên kết**. Chỉ xác nhận QR từ máy tính của bạn. Mã dùng một lần và hết hạn sau hai phút.
4. Trong app chọn ngôn ngữ và **Smart**. Với hội thoại game, vào **Tùy chỉnh → Vùng nhận diện → Nửa dưới** để bỏ qua HUD phía trên. Chọn **Phụ đề** trong lần dùng đầu; có thể đổi cỡ chữ và vị trí sau khi kết thúc phiên.
5. Bật dịch, cấp quyền hiển thị nổi và thu màn hình. App đọc phiên bản Android thực tế: từ Android 14 trở lên nên chọn **Một ứng dụng** trong hộp thoại hệ thống. Với Android cũ hoặc chọn **Toàn màn hình**, bật **Thu toàn màn hình** trong Tùy chỉnh; phụ đề tự động đặt dải Việt ngoài vùng nguồn để không ẩn/hiện định kỳ. [Tài liệu Android về chia sẻ màn hình](https://developer.android.com/media/grow/media-projection).
6. Chuyển sang game/ứng dụng đã chọn và chờ chữ ổn định. Chạm nút xanh nổi để tạm dừng, kéo để di chuyển, giữ để dừng. Khi khóa màn hình hoặc ứng dụng thu hồi quyền, có thể cần mở app và cấp lại quyền thu màn hình.

Nếu không thấy phụ đề, mở app xem trạng thái. Nếu không kết nối được, kiểm tra PC còn chạy, địa chỉ IP/cổng đúng và hai thiết bị có liên lạc được trên Wi-Fi; Wi-Fi khách có thể cô lập thiết bị. Nếu báo chứng chỉ, tạo QR mới từ PC và kiểm tra ngày giờ của cả hai máy.

Nếu hệ thống dừng app khi chuyển sang game, dùng nút **Mở thông tin ứng dụng** trong Tùy chỉnh để kiểm tra quyền cửa sổ nổi/thông báo và các giới hạn pin. Tên và vị trí mục phụ thuộc phiên bản MIUI/HyperOS; app không tự sửa các cài đặt đó. Không cần bật USB debugging để dùng ứng dụng thông thường.

Danh sách bỏ qua dùng tên package, mỗi dòng một ứng dụng, và cần quyền **Truy cập dữ liệu sử dụng**. Khi không kiểm tra được quyền/ứng dụng hiện hành, app sẽ tạm dừng. Trước khi mở nội dung riêng tư, chủ động tạm dừng bằng nút nổi.

Hiện chưa có số đo tốc độ, nhiệt độ hay pin trên Redmi. Phép thử BlueStacks 1.2.1 dùng tài khoản Google đã đăng nhập: `こんにちは` → `Xin chào`, khoảng 14,19 giây toàn luồng. Ảnh dùng nội dung thử tổng hợp trên máy ảo. Xem [báo cáo](VERIFICATION.md).
