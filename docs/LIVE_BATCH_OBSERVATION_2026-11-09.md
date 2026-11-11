# Quan sát lô phim mới — 09/11/2026

Lô đang chạy trên BlueStacks gồm 10 video, tổng 1:17:22. Cấu hình giao diện: OCR hình ảnh, Local + Gemini/toàn bộ hội thoại, dịch đồng thời 16 GB. Các giờ dưới đây theo Việt Nam, UTC+7. Quan sát bắt đầu lúc 23:56 ngày 08/11, tiếp tục qua nửa đêm.

Không thay cấu hình, tạm dừng, cài lại hoặc chạy thử Gemini trong lúc lấy số đo. Chỉ xem giao diện/nguồn ứng dụng và số liệu tài nguyên hệ thống; không đọc nội dung tệp phim, khóa API hoặc phản hồi thô.

## Mốc thực tế

| Giờ | Video | Trạng thái | Số đoạn OCR | Phần trăm |
| --- | --- | --- | ---: | ---: |
| 23:59:04 | 1, dài 7:44 | Trích xuất | 578 | 29% |
| 00:00:11 | 1 | Trích xuất | 740 | 36% |
| 00:01:17 | 1 | Trích xuất | 875 | 45% |
| 00:02:05 | 1 | Trích xuất | 976 | 51% |
| 00:03:19 | 1 | Trích xuất | 1.117 | 60% |
| 00:04:32 | 1 | Trích xuất | 1.318 | 69% |
| 00:06:08 | 1 | Trích xuất | 1.532 | 81% |
| 00:07:55 | 1 | Trích xuất | 1.758 | 93% |
| 00:09:51 | 1 | Trích xuất xong, lần đầu quan sát được | 1.873 | 100% |
| 00:10:18 | 2 | Trích xuất | 184 | 9% |
| 00:14:07 | 2, dài 7:44 | Trích xuất | 702 | 34% |

Từ mốc 29% đến 93%, xử lý khoảng 297 giây nguồn trong khoảng 531 giây thực, tương đương khoảng 0,56 giây video/giây thực. Đây là ước lượng từ phần trăm nguyên trên một video, không phải benchmark có đối chứng hoặc tốc độ dịch Gemini. Không dùng mốc lần đầu nhìn thấy 100% làm thời điểm hoàn tất chính xác.

Trong các mốc trên, dịch vụ xử lý vẫn chạy, chưa thấy mã lỗi trên thẻ đang theo dõi. Chưa có lượt dịch cloud trong phần thống kê tại thời điểm mở đầu giai đoạn Trích xuất. Chưa đủ dữ liệu đánh giá lỗi/độ trễ Gemini của lô mới.

Đã quét các thẻ 1–10 để đối chiếu: video 1 hoàn tất trích xuất, video 2 tiếp tục tăng tiến độ, các video sau đang chờ giai đoạn này. Tại mốc 00:14:07 đã để giao diện ở đúng thẻ video 2 đang xử lý. Một phép đọc theo ancestor UI đã chạm cả thẻ 1 và 2; số liệu đó bị loại khỏi nhật ký. Mốc video 2 ở trên được đối chiếu lại bằng thứ tự tiêu đề, thời lượng và dòng tiến độ trong cùng thẻ.

Tài nguyên lúc 00:08:30: Android được cấp 4 nhân, MemTotal 8.157.208 KiB, MemAvailable 6.763.984 KiB; PSS ứng dụng 227.619 KiB (khoảng 222 MiB). Heap cấu hình 384 MiB. Chưa có dấu hiệu thiếu RAM tại mốc này; chọn 16 GB trong app không tự tăng tài nguyên của máy ảo.

## Các điểm đã xác định từ nguồn

1. **Lưu tiến độ đọc lại toàn bộ dự án.** `VideoJobs.persist()` lưu timeline rồi gọi `refresh()`. `VideoStore.list()` nạp và phân tích mọi dự án đã lưu. Cứ mỗi khoảng 3 giây nguồn được xử lý, decoder lại checkpoint. Chi phí này có thể tăng theo số dự án và kích thước timeline; cần đo riêng trước khi định lượng mức chậm. Bộ đếm checkpoint hiện kết thúc trước refresh, nên chưa bao gồm toàn bộ chi phí.
2. **OCR vẫn tuần tự.** EXTRACT/CLEAN có một worker. Chế độ 8/16 GB chỉ mở đồng thời ở TRANSLATE/POLISH. Nhiều RAM còn trống không tự làm giai đoạn này chạy nhiều video một lúc.
3. **Thống kê cloud chưa phản ánh OCR trực tiếp.** Decoder đã có số khung, số lượt OCR, thời gian crop/bitmap/OCR và tái sử dụng. Các số đó chủ yếu được chuyển vào tệp telemetry khi worker kết thúc. Số hoàn thành 0/10 hiện đếm đủ mọi công đoạn, nên không thể dùng để kết luận OCR bị đứng.
4. **Nguồn đang có nhiều đoạn ngắn, cần kiểm tra mẫu.** Video 1 tạo 1.873 đoạn ở thời lượng 7:44. Mật độ này là dấu hiệu cần kiểm tra phân đoạn/độ ổn định OCR sau khi có thể mở nguồn, chưa phải bằng chứng OCR sai. CLEAN chỉ chuẩn hóa khoảng trắng và gắn cờ; không tự gộp các đoạn gần giống.
5. **HYBRID có thể gửi lặp ngữ cảnh lớn.** Khi vừa giới hạn, toàn hội thoại xuất hiện trong các yêu cầu tiếp theo, kể cả khi chỉ sửa một nhóm nhỏ ID. Đây là hành vi có chủ đích để giữ ngữ cảnh, chưa được xác nhận là nút thắt của lô mới vì chưa tới bước Dịch.

## Thứ tự tối ưu đề xuất

| Ưu tiên | Cải tiến | Cách kiểm chứng |
| --- | --- | --- |
| 1 | Sau khi lưu thành công, chỉ cập nhật dự án đó vào danh sách trong bộ nhớ; đối chiếu toàn bộ ở ranh giới tác vụ và thao tác thêm/xóa/sửa. | Đo thời gian lưu và refresh tách biệt, số byte đọc; so sánh cùng fixture với nhiều dự án cũ. Kiểm đồng thời, thứ tự danh sách, pause/resume và bản sửa ngoài worker. |
| 2 | Hiển thị "đã quét mm:ss/tổng", số video lấy xong nguồn và tuổi checkpoint. | Kiểm UI với clip dài; phân biệt đợi I/O, chờ Live, OCR và lỗi thật. Không tự hủy chỉ vì phần trăm chưa đổi. |
| 3 | Đưa số đo OCR lên thống kê theo nhịp thời gian thực có giới hạn, khoảng 3–5 giây. | Ghi các số đếm/timing đang có; tránh thêm việc đọc toàn bộ dự án hay flush theo từng khung. |
| 4 | Chỉ khi đo được crop/bitmap là đáng kể: tái dùng bộ đệm có giới hạn sau khi native OCR hoàn tất, và tính trước tọa độ crop khi hình học không đổi. | Kiểm byte/pixel trùng khớp, xoay/stride/đổi vùng, frame cuối, hủy tác vụ và ownership bộ đệm. |
| 5 | Nếu phần dịch xuất hiện nhiều phản hồi thiếu hoặc 429: đo số dòng/ngữ cảnh, byte payload, token và thời gian đợi so với thời gian HTTP. | Giới hạn mục tiêu trực tiếp ở planner và tái sử dụng payload đã xác minh có thể giảm công dựng JSON; phải giữ cùng ID, ngân sách và ngữ cảnh. |

Giới hạn số yêu cầu đồng thời không thay thế ngân sách RPM/TPM. Ba khóa cùng dự án vẫn chia sẻ hạn mức; nhóm quota cần cấu hình rõ, không đoán từ chuỗi khóa. Chưa kiểm tra hoặc thay đổi chế độ khóa của người dùng trong lượt quan sát này.

Giữ lịch lấy mẫu và cơ chế xác nhận phụ đề hiện tại khi tối ưu I/O/bộ nhớ. Giảm khung hoặc gộp câu gần giống có thể mất câu ngắn, số hoặc phủ định; cần kiểm chứng chất lượng riêng, không áp dụng trong lô đang chạy.

Phạm vi báo cáo là lượt quan sát ban đầu đến khoảng 00:14 ngày 09/11, chưa kết luận toàn bộ 10 video đã dịch xong hay mọi lỗi ở bước Gemini đã được kiểm tra. Chưa triển khai các đề xuất mới vào ứng dụng đang chạy; tác vụ hiện tại được giữ nguyên.

## Quan sát tiếp lúc 13:28–13:34 ngày 09/11

Kết nối TCP cũ không còn; đã đọc lại đúng ứng dụng qua emulator-5554, không thay đổi tác vụ.

| Video | Nguồn OCR | Số đoạn | Giờ quan sát |
| --- | ---: | ---: | --- |
| 1 | 100% | 1.873 | 13:30:07 |
| 2 | 100% | 1.873 | 13:33:05 |
| 3 | 100% | 1.873 | 13:30:09 |
| 4 | 100% | 1.876 | 13:30:11 |
| 5 | 63% → 86% → 95% | 1.198 → 1.628 → 1.779 | 13:30:11 → 13:33:12 → 13:34:25 |
| 6–10 | Chờ lấy nguồn ở lần quét | 0 | 13:30 |

Video 5 dài 7:44, tăng 23 điểm phần trăm trong khoảng 182 giây: khoảng 0,59 giây nguồn/giây thực, gần với mẫu video 1 khoảng 0,56. Đây là ước lượng UI, chưa phải benchmark có đối chứng.

Thống kê lúc 13:32 ghi chưa có lượt dịch cloud, 0 lượt chờ/thử lại và 0 yêu cầu dừng vì lỗi. Các thẻ đã đối chiếu chưa hiển thị mã lỗi. Lô còn ở EXTRACT; chưa kiểm chứng 429/503, phản hồi thiếu hoặc tốc độ Gemini của lượt này.

Tài nguyên lúc 13:34:29: 4 nhân, MemTotal 8.157.208 KiB, MemAvailable 6.792.636 KiB, khoảng 6,48 GiB. Chưa có bằng chứng thiếu RAM tại mốc này.

Nhật ký Windows ghi vào/ra Modern Standby lúc 00:49, 03:00, 03:19, 09:41; sleep/resume lúc 09:45 và bản ghi thoát Modern Standby với lý do “Resume from Hibernate” lúc 11:56. Các mốc chứng minh máy có nghỉ/thức; không chứng minh app bị lỗi, không cho phép tính toàn bộ khoảng qua đêm là thời gian OCR thực chạy hoặc suy ra chính xác số giờ gián đoạn.

### Bảng tổng kết

| Hạng mục | Bằng chứng | Nhận xét và hướng cải thiện | Ưu tiên |
| --- | --- | --- | --- |
| Nghỉ/thức của máy | Có Modern Standby và khôi phục từ Hibernate | Tùy chọn giữ máy thức trong phiên xử lý khi người dùng bật; tự trả trạng thái sau tác vụ, tôn trọng lệnh ngủ thủ công. | Cao |
| Lưu tiến độ | Mã lưu timeline rồi đọc lại mọi dự án | Chỉ cập nhật dự án vừa lưu trong bộ nhớ. Đo riêng lưu/refresh và số byte trước khi định lượng tăng tốc. | Cao |
| OCR | Khoảng 0,56–0,59 lần tốc độ video | Tối ưu I/O/cấp phát trước; nhiều decoder cần kiểm chứng bộ nhớ, frame cuối và độ chính xác. | Cao |
| Hiển thị tiến độ | 4 video lấy xong nguồn; bộ đếm hoàn tất toàn lô chưa đại diện OCR | Thêm “đã lấy nguồn x/10”, thời gian đã quét/tổng và tuổi checkpoint. | Cao |
| Chất lượng nguồn | 1.873 đoạn trên video 7:44 | Kiểm mẫu đoạn ngắn/lặp/biến thể; chưa đủ bằng chứng OCR sai hay tệp trùng. Không gộp mù câu gần giống. | Cần kiểm |
| RAM/CPU | 4 nhân, khoảng 8 GB RAM, hơn 6 GB trống | Chưa có lý do tăng RAM như giải pháp đầu tiên; chế độ 16 GB trong app không tự tăng tài nguyên máy ảo. | Theo dõi |
| Gemini | Chưa có lượt gửi trong lô này | Sau OCR đo token/ngữ cảnh, lượt thiếu, 429/503 và đợi so với HTTP; chưa kết luận hiệu quả API. | Sau OCR |

Chưa quan sát được mã lỗi ứng dụng trong đợt tiếp này. Điểm hệ thống xác nhận được là các chu kỳ nghỉ/thức. Các điểm mã nguồn là chi phí/rủi ro cần đo, không phải lỗi runtime đã xảy ra. Các đề xuất chưa được triển khai vào ứng dụng đang chạy; chưa kết luận toàn lô hoàn tất.
Mốc cuối 13:36:34: video 5 đã lấy xong nguồn (1.874 đoạn, 100%), video 6 đang lấy nguồn (184 đoạn, 9%). Tổng 5/10 video lấy xong nguồn; đây chưa phải số video hoàn tất dịch. Dịch vụ vẫn hoạt động, chưa thấy mã lỗi ở các thẻ đang hiển thị.

## Kiểm tra chặn lưu Kho phim lúc 15:05–15:08 ngày 09/11

Lô vẫn ở TRANSLATE, dịch vụ còn hoạt động. Các dòng 100% có cả nhãn Dịch lẫn Đối chiếu; bộ đếm toàn lô quan sát được là 0/10 video hoàn thành. Đã mở chi tiết và xác nhận TEXT_INVALID_RESPONSE.

Thống kê giao diện lúc 15:07:36:
- 13 lượt gửi, 0 phản hồi đọc được.
- 0 lượt câu hợp lệ được ghi nhận.
- 12 yêu cầu dừng vì lỗi: 7 kết quả chưa hợp lệ, 5 dịch vụ gián đoạn.
- 7 lượt chờ/thử lại, đều thuộc dịch vụ gián đoạn.
- Nhóm gửi gần nhất: 1.462 câu.

Các số đếm này là cộng dồn, không khẳng định có 12 video lỗi. Zero ở bộ đếm thiếu/rỗng/bị cắt không chứng minh không có vấn đề: parse thất bại trước khi tạo Result sẽ không tăng các bộ đếm phản hồi đó.

Nguyên nhân chưa lưu được: validatedSources yêu cầu mọi timeline hoàn tất dịch, xong FINISH và không có câu trống khi duyệt toàn bộ; lô chưa thỏa điều kiện. Phần trăm 100 bị giữ từ CLEAN/nguồn khi bước TRANSLATE chưa nhận được kết quả; lỗi hiển thị không phản ánh phần trăm câu đã dịch. Không ép lưu hoặc đánh dấu hoàn thành các câu chưa có bản dịch.

Cần sửa trước: báo phần trăm theo công đoạn/câu hoàn tất; tách nguyên nhân parse (JSON, cấu trúc, ID, finishReason) bằng metadata an toàn, không lưu phản hồi thô; kiểm chứng nhóm mục tiêu nhỏ hơn với ngữ cảnh giữ nguyên và số lượt hữu hạn. Nhóm 1.462 câu là điểm cần thử nghiệm, chưa đủ bằng chứng xác định là nguyên nhân trực tiếp của mọi lỗi.