# Giao thức Gemini OCR, phiên bản 1

Máy chủ thiết bị: `https://<IP-LAN>:8765`, WebSocket `wss://<IP-LAN>:8765/v1/stream`.
Giao diện quản trị chỉ bind `127.0.0.1:8766`; không dùng cổng quản trị trên điện thoại.

1. Windows tạo chứng chỉ TLS riêng khi khởi chạy lần đầu. Ghép cặp trao dấu vân tay SHA-256 của toàn bộ chứng chỉ DER qua QR/liên kết được người dùng kiểm tra.
2. Android đối chiếu dấu vân tay trong TLS TrustManager **trước** khi gửi mã ghép cặp hoặc token. Không dùng kết nối HTTP hay tin cậy mọi chứng chỉ.
3. Windows tạo mã 6 số, hiệu lực 120 giây, một lần, tối đa 8 lần thử/mã cho toàn bộ máy chủ. `POST /v1/pair` nhận `{code,name}` và trả `{device_id,token,protocol:1}`. Token ngẫu nhiên 256 bit, chỉ hash SHA-256 được lưu trên Windows.
4. Android lưu token qua AES-GCM/Android Keystore; mở WSS với header `Authorization: Bearer <token>`. Token không nằm trong URL WebSocket.
5. Máy chủ trả `{type:"hello",protocol:1}`. Gói dịch tuân thủ [protocol.json](protocol.json). Tọa độ chuẩn hóa 0..1, đơn vị khung hình, không phải dp.

Ví dụ yêu cầu:

```json
{"type":"translate","request_id":"42","source":"ja","profile":"game","mode":"smart","blocks":[{"id":"b0","text":"こんにちは","box":{"x":0.1,"y":0.7,"width":0.8,"height":0.1}}]}
```

Phản hồi:

```json
{"type":"result","request_id":"42","latency_ms":420,"model":"gemini-3.8-flash","blocks":[{"id":"b0","text":"Xin chào","box":{"x":0.1,"y":0.7,"width":0.8,"height":0.1},"cached":false}]}
```

- Windows 1.4.0 trả capabilities `latest_pending`, `correlation_v1`, `timings_v1` trong hello. Android 1.5.0 chỉ bật mở rộng khi có capability. Gói dịch thêm `region_id` (1–80 ký tự), `screen_generation` (số nguyên không âm), `latest_only:true`. Result/error echo region/generation. Android kiểm tra request + vùng + thế hệ + signature; A→B→A không nhận phản hồi A lần đầu.
- `latest_only:true` giữ một tác vụ đang chạy và một câu chờ mới nhất. B/C thay thế câu chờ, không hủy process CLI đang chạy; bỏ phản hồi nếu request không còn hiện hành. Gói cũ không có cờ giữ hành vi cancel trước đây để tương thích.
- Gói `{"type":"cancel"}` hủy yêu cầu đang chờ/đang dịch. `{"type":"cancel","preserve_session":true}` xóa câu chờ và chặn phản hồi lượt đang chạy, cho lượt đó hoàn tất/cache để giữ phiên CLI. Có thể thêm `request_id` để chỉ hủy đúng request đang được chờ. Một phản hồi có thể đã đi vào mạng ngay trước lúc nguồn đổi: Android còn xác nhận OCR từ snapshot sau lúc nhận trước khi tự vẽ.
- Result thêm `timings_ms`: queue (mailbox+semaphore), mailbox_queue, cache, provider, server, cli_lock/start/first_output/result; `diagnostics.cli_reused`. Durations tính bằng monotonic trên Windows, `latency_ms` gồm mailbox. Provider đã gồm cli_lock. First-output là agent text hoặc result dự phòng, không khẳng định token đầu mô hình; không tách được mạng Google khỏi compute. Android đo capture/OCR/queue/round-trip/draw trên clock riêng, không trừ timestamp khác máy.
- `{"type":"ping"}` → `{"type":"pong"}`. OkHttp còn dùng ping WebSocket mỗi 15 giây. Android nối lại theo backoff 1–15 giây, không tự khởi động lại MediaProjection sau khi người dùng dừng.
- `{"type":"invalidate","cache_enabled":true,"revision":2,"provider":"cli"}` thông báo dữ liệu thay đổi; Android xóa cache RAM, hủy kết quả hiện hành rồi đọc lại khung hình ổn định. `hello` cũng chứa `cache_enabled`/`revision` và `provider` (`api` hoặc `cli`, bổ sung tương thích từ 1.2). Nếu máy chủ cũ không có cờ thì Android không bật cache RAM giữa các khung.
- Lỗi: `{"type":"error","request_id":"42","code":"no_api_key","message":"..."}`. Mã gồm `no_api_key`, `rate_limited`, `daily_limit`, `quota_exceeded`, `provider_configuration`, `provider_unavailable`, `invalid_response`, `invalid_request`, `configuration_changed`, `internal_error`. Từ 1.2 có thêm `cli_missing`, `cli_auth`, `cli_configuration`, `cli_login_active`, `cli_timeout`, `cli_unavailable`; phía Android hiển thị thông báo của máy chủ và lùi thời gian thử lại với lỗi cấu hình.
- Android chờ 15 giây khi hết RPM/quota, 60 giây khi thiếu khóa/cấu hình hoặc hết hạn mức ngày. Cache và từ điển trùng chính xác vẫn dùng được mà không gọi Gemini.
- Thu hồi thiết bị đóng kết nối với mã `4401`. Giới hạn 8 thiết bị kết nối, một kênh/thiết bị, 2 yêu cầu Gemini song song, 40 vùng/khung, 12.000 ký tự/khung, 64 KiB/gói.
- Không gửi ảnh. Ngữ cảnh lưu RAM riêng theo thiết bị/profile/ngôn ngữ, tối đa 12 đoạn và hết hạn 10 phút; Fast dùng 2, Smart 6, Quality 12. Cache cố định theo văn bản chuẩn hóa + ngôn ngữ + profile + mode + model + phiên bản từ điển, không theo toàn bộ ngữ cảnh, để ưu tiên tái sử dụng. Câu mơ hồ có thể giữ cách dịch từ lần đầu; sửa bằng từ điển hoặc xóa cache.
- Cache máy tính tối đa 20.000 bản dịch; SQLite chỉ lưu hash chữ gốc và bản dịch. Android giữ tối đa 256 câu trong RAM của phiên, chỉ gửi các vùng còn thiếu. Không có lịch sử màn hình mặc định.

