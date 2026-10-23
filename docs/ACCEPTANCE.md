# Nghiệm thu APK 1.3.1 / Companion 1.2.1

1.3.1 đã chạy lại trực tiếp khung Trung người dùng báo lỗi: một lượt AI mới, 10,66 giây, hiển thị đúng phụ đề Việt. Thanh nổi có chữ OCR, thời gian, hủy/thử lại và lỗi. 24 bài JVM đạt. Xem báo cáo hiện tại trong [VERIFICATION.md](VERIFICATION.md); phần dưới ghi phạm vi 1.3.0 trước đó.

Đã kiểm tra bản APK mới trên BlueStacks Android 9: một khung theo vùng kéo chỉnh, dải kín che chữ gốc, AI thật, cache, hai câu tự động và không gọi lặp khi giữ khung 30 giây. Android JVM 21 bài đạt, Lint/chữ ký và APK đã cài được xác minh. Chi tiết số đo và ảnh trong [VERIFICATION.md](VERIFICATION.md); cách dùng trong [SUBTITLE_MODE.md](SUBTITLE_MODE.md).

Còn cần nghiệm thu: Redmi/HyperOS thật, video/game thực với font khó, Wi-Fi điện thoại, pin/nhiệt và hành vi chạm dải phủ trên Android 12+. Một khung bỏ bước xác nhận hai lần nên có thể sai khi chữ đang chuyển động. Antigravity vẫn mất nhiều giây, chưa đạt phụ đề phim đồng bộ tức thời.

## Lịch sử nghiệm thu 1.2.1

Thiết bị mục tiêu: Xiaomi Redmi Note 13 Pro 5G. Ngày xác minh 22/10/2026.

| Hạng mục | Đã xác minh | Còn cần nghiệm thu |
| --- | --- | --- |
| APK | Release 1.2.1 code 4 đã ký, cài cập nhật BlueStacks giữ ghép cặp; 14 bài JVM | Cài/cập nhật trên Redmi thật |
| Thu màn hình/phụ đề | BlueStacks: OCR Nhật → AI thật → phụ đề Việt; Android 15 trước đó: xoay, pause, khóa, loại trừ | Game/trình duyệt và cửa sổ nổi HyperOS |
| OCR | Mô hình Nhật chạy thật trên BlueStacks; 4 ngôn ngữ tổng hợp kiểm tra native trước đó | Khung game có nhãn, CER, tốc độ và font khó |
| Tài khoản Google | Antigravity 1.4.5 đã đăng nhập; provider/Windows/APK đều dịch `こんにちは` → `Xin chào` | Chất lượng chuỗi hội thoại và hạn mức trong phiên dài |
| Độ trễ | Windows 11,55 giây; BlueStacks toàn luồng khoảng 14,19 giây trong một lượt | p50/p95, tải dài; chưa đạt dịch tức thời |
| Kết nối | WSS/TLS thật, pairing cũ được giữ, BlueStacks qua ADB reverse | Wi-Fi thật, Firewall LAN, PC ngủ/thức và thay IP |
| Cache/ngữ cảnh | Kiểm thử cache, invalidation, tách thiết bị/profile/ngôn ngữ | Giảm lượt gọi/pin; xưng hô nhiều câu |
| Windows | 48 bài Python, EXE smoke, bộ cài và cài cập nhật hiện tại đạt | Cài/gỡ trên Windows sạch |
| Dữ liệu | Không đọc OAuth/keyring, không lưu ảnh, cấu hình CLI riêng; không xóa dữ liệu người dùng | CLI có thể lưu OCR trong hội thoại; người dùng quản lý qua công cụ Google |

Không suy rộng tốc độ, pin hoặc tương thích Redmi từ máy ảo. Khuyến nghị chạy 15 phút trên từng game/app, khoảng 100 câu ở ba chế độ, ghi OCR CER, độ trễ p50/p95, cache hit, lượt/token và lỗi bằng dữ liệu thử không riêng tư. Xem [báo cáo thực tế](VERIFICATION.md).
