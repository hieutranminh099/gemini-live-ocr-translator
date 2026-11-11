# Dịch phim tự động dùng cá nhân trên Windows

Chủ dự án chọn dùng cá nhân trên máy trước. Mở rộng bộ xử lý video Windows hiện có; không thay phiên bản Android đang chạy hoặc cấu hình Gemini.

Luồng mục tiêu: Continue with ChatGPT → xác nhận quyền dùng gói → chọn phim → tự trích xuất phụ đề/OCR → dịch theo nhóm có ID → lưu checkpoint → xuất SRT và MP4. Sau đăng nhập lần đầu, chọn tệp là thao tác bắt đầu xử lý. AI tạo bản nháp; không tự gán User Approved.

Kết nối ChatGPT dùng Sign in with ChatGPT / ChatGPT plan usage cho dự án cá nhân chạy cục bộ. Không đọc cookie trình duyệt, token Codex, lịch sử chat hay kho khóa hiện có. Đăng nhập qua trình duyệt hệ thống; xác minh state, PKCE, nonce và chữ ký ID token. Chỉ dùng quyền thực sự được cấp. Dữ liệu kết nối mới lưu mã hóa DPAPI ở thư mục riêng. Xử lý video cục bộ; văn bản cần dịch gửi đến OpenAI khi người dùng chọn phim.

Chỉ gửi Responses API công khai, store=false, stream=true; chỉ chấp nhận kết quả sau response.completed. Danh sách model lấy theo tài khoản, không gán model tùy ý. Hết quyền/hạn mức thì lưu tiến độ và hiển thị nguyên nhân; không xoay tài khoản hoặc lặp đăng nhập. Luồng Gemini tiếp tục dùng API chính thức; chưa có bằng chứng đăng nhập Gemini web cho phép dùng gói cá nhân trong app này.

Giao diện chạy trên loopback. Mã mở cửa sổ được đổi thành cookie phiên HTTP-only, SameSite Strict để tải lại cửa sổ không mất kết nối; yêu cầu thay đổi qua cookie được kiểm tra Origin. Token OAuth tiếp tục chỉ ở bộ xử lý phía máy, không đi ra giao diện hoặc log. Video nguồn và model không tự tải lên dịch vụ. Tệp và checkpoint mới mặc định đặt trên E: nếu có, để giảm dung lượng C:.

Nghiệm thu: kiểm thử đăng nhập giả lập, từ chối callback giả/sai danh tính, luồng bị cắt, hạn mức, lưu/khôi phục; kiểm thử video tổng hợp qua trích xuất/dịch/xuất thật với phản hồi giả lập; sau đó người dùng đăng nhập và xác minh một lượt xử lý thật. Không coi kiểm thử giả lập là bằng chứng tài khoản đã được chấp nhận hoặc model dịch đạt chất lượng phim.

Nguồn chính thức đã đối chiếu ngày 09/11/2026:

- https://developers.openai.com/siwc/quickstart
- https://developers.openai.com/siwc/token-sharing-open-source/sign-in
- https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference
- https://developers.openai.com/siwc/token-sharing-open-source/profiles-and-sessions
- https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations
- https://developers.openai.com/siwc/token-sharing-open-source/errors-and-recovery

Đã triển khai bản thử riêng cho Windows, mở bằng `Mo-Dich-Phim-Ca-Nhan.cmd`. Không sửa các tệp xử lý Android/Gemini đang dùng. Bộ chạy Python riêng trên E: dùng lại thư viện media hiện có; mã nguồn và các tệp giao diện nằm trong dự án App.

Kiểm chứng: 70 ca đạt trong lượt kiểm thử chính, gồm 30 ca mới về kết nối/luồng tự động, 38 ca hồi quy video và 2 ca media thật. Hai ca media thật tạo video tổng hợp 4 giây: một ca phụ đề văn bản, một ca OCR chữ Trung ghi cứng; cả hai tự chạy đến xuất SRT và MP4 640×360, giữ nguồn và trạng thái Draft. AI trong các ca này dùng phản hồi giả lập. Sau đó bổ sung một ca mã hóa DPAPI thật và mở lại kết nối: 25/25 ca kết nối đạt, tổng cộng 71 ca riêng biệt đã đạt.

Đã kiểm tra callback sai state/nonce/chữ ký/issuer/audience, trả sai tài khoản, thiếu quyền dùng gói, mã đăng nhập hết hạn, làm mới phiên, phản hồi bị ngắt, mapping thiếu/trùng/sai ID, hạn mức không tự gọi lặp, phục hồi dịch vụ tối đa ba lượt. Luồng tự động giữ checkpoint khi dừng/hết hạn mức và khi tiếp tục chỉ dịch phần chưa xong. Kiểm tra HTTP gồm mã phiên, Host, chống nhúng trang và liên kết tải có phạm vi. Tệp phim được đọc theo luồng trực tiếp vào thư mục dữ liệu, tránh ghi tạm toàn bộ phim lên C:.

Kiểm chứng bổ sung ngày 10/11/2026: tài khoản của chủ máy đã hoàn tất đăng nhập và cấp quyền dùng gói; danh sách trả về có 8 model, lựa chọn hiện có là gpt-6.1-sol. Đã chạy một video tổng hợp 8 giây với 3 câu qua kết nối ChatGPT thật. Một lượt AI trả đủ ba bản dịch hợp lệ; app tự lưu và xuất SRT/MP4. Video kết quả vẫn 8 giây, 640×360; ba mốc phụ đề giữ nguyên. Không dùng phim riêng hay chạy đo tải.

| Nguồn mẫu | Kết quả thực tế |
|---|---|
| 你好，我们明天见。 | Chào bạn, hẹn gặp ngày mai. |
| 我没有拿你的钱。 | Tôi không lấy tiền của bạn. |
| 这件衣服要二十五元。 | Chiếc áo này giá 25 tệ. |

Lượt kiểm thử hoàn thiện đạt 76/76 ca, gồm 36 ca kết nối/luồng cá nhân, 38 ca hồi quy và 2 ca OCR/xuất media thật với AI giả lập. Bổ sung kiểm tra tải lại bằng phiên HTTP-only, từ chối thay đổi từ origin khác, tạm dừng phim cũ không hủy phim đang chạy, tiếp tục phim đã xong không gọi AI/render lại, tôn trọng Retry-After và thống kê đúng các lần gửi khi phục hồi. Tệp tải về dùng tên phim kèm .vi.srt/.vi.mp4.

Mẫu thật trên xác nhận luồng đăng nhập → trích xuất → dịch → xuất hoạt động; không phải đánh giá chất lượng của phim dài, tốc độ bền vững hay cam kết hết lỗi hạn mức. OCR tự động hiện ưu tiên vùng phụ đề dưới của phim theo bộ xử lý sẵn có; không bảo đảm mọi bố cục chữ hoặc ngôn ngữ đều nhận đúng.

Đã cập nhật dự án App chính và mở lại bản cá nhân. Kiểm tra sau cập nhật xác nhận vẫn kết nối, vẫn có quyền dùng gói và lấy được 8 model. Phiên cửa sổ HTTP-only cho phép đọc trạng thái sau khi bỏ mã mở cửa sổ; yêu cầu thay đổi từ origin khác trả 403. Gọi tiếp tục video mẫu đã hoàn tất giữ nguyên kết quả, không xử lý lại hoặc tăng số lượt AI.

Kiểm tra giao diện dùng máy chủ thử tách biệt, không có tài khoản thật và không gọi AI: tải lại trang vẫn giữ phiên, chọn tệp video tổng hợp qua hộp chọn tệp của trình duyệt, rồi giao diện tự hiện 3/3 câu và đủ liên kết tải SRT/MP4. Máy chủ thử đã đóng; bản cá nhân thật tiếp tục chạy. Không đưa mã mở cửa sổ hoặc token ChatGPT ra báo cáo.

Tệp kiểm chứng thật giữ trên E: tại `E:/GeminiOCR-build/personal-video/live-proof/translated.mp4` và `translated.srt`; `result.json` chỉ ghi số liệu và ba câu mẫu tổng hợp. Bản dịch thật giữ nghĩa phủ định và số tiền 25 tệ. Bộ mã nguồn, kiểm thử và báo cáo ở App chính đã đồng bộ với worktree đang phát triển.
