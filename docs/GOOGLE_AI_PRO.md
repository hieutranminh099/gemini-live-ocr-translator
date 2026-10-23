# Gemini API và CLI cá nhân — Android 1.6.2 / Windows 1.6.0

**Máy hiện tại đã dùng Gemini API chính thức**, sau khi người dùng cung cấp khóa. Fast dùng `gemini-3.5-flash-lite`; đã kiểm tra `こんにちは` → `Xin chào` bằng API và Companion đã cài. Khóa lưu DPAPI trên Windows, không được nhúng trong APK/mã nguồn hoặc ảnh kiểm tra. [Báo cáo hiện tại](API_RESTORE_V162.md).

Khóa đã được gửi trong cuộc trò chuyện nên người dùng nên thu hồi/tạo khóa thay thế, sau đó tự nhập trực tiếp **Cài đặt AI → Gemini API → Lưu** trên Windows. Không gửi khóa mới qua chat. API có hạn mức/chi phí riêng với gói AI Pro; dự án không tự bật thanh toán.

Windows 1.6.0 có tùy chọn **CLI cá nhân thử nghiệm**, mặc định tắt. Chỉ người dùng bật checkbox và lưu mới cho phép gọi CLI chính thức. [FAQ Google](https://www.antigravity.google/docs/faq/) nêu việc bọc agy trong ứng dụng bên thứ ba không được hỗ trợ trên consumer plans và có thể dẫn đến đình chỉ tài khoản. Dùng cá nhân không loại bỏ giới hạn này. Cấu hình hiện tại **provider=api, personal_cli_enabled=false**, không gọi CLI.

Ứng dụng không đọc/sao chép OAuth hoặc gọi endpoint nội bộ thay CLI, không tự xoay tài khoản. CLI giữ đăng nhập/dữ liệu cũ. Tắt thử nghiệm khi đang chọn CLI chỉ còn cache/từ điển; nguồn thiếu cache trả lỗi cấu hình trước quota/provider. Cấu hình API không phụ thuộc đăng nhập CLI. Local/Hybrid vẫn chưa bật vì prototype chưa đạt chất lượng.

## Kết nối BlueStacks

Android 1.6.2 đã cài đè, giữ ghép cặp/vùng/thiết lập. ADB là 127.0.0.1:5555, device server 8765, quản trị 8766. Sau khởi động lại BlueStacks chạy **Mo-Gemini-BlueStacks.cmd** trong thư mục dự án để mở Companion và khôi phục ADB reverse. Giữ Companion mở khi dịch. Script không thay chính sách PowerShell toàn máy.

Điện thoại thật dùng IP Wi-Fi/QR mới; chỉ cho phép TCP 8765 trên mạng riêng tư nếu Firewall hỏi. Tên OnePlus do BlueStacks giả lập không đại diện Redmi Note 13 Pro 5G.

## Dữ liệu và số đo

Dữ liệu CLI cũ, cấu hình đăng nhập, DB/chứng chỉ được giữ. Không đọc/sao chép OAuth, thay hồ sơ Google chung hoặc xóa dữ liệu CLI. Hội thoại CLI cũ có thể chứa OCR; không chia sẻ thư mục này.

Báo cáo 1.2–1.6.1 là **lịch sử** theo cấu hình lúc đo; số cache không đại diện câu mới. Các lần gọi mới đợt 1.6.2 dùng API, không dùng CLI. [Báo cáo hiện tại](API_RESTORE_V162.md), [audit provider lịch sử](../research/gemini_api/support-audit.md), [prototype local](../research/local_translation/README.md).
