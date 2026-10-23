# Cải thiện dịch phụ đề — Android 1.6.3 / Windows 1.6.1

Ngày 23/10/2026, giờ Việt Nam. Đã cài Android **1.6.3/code14** lên BlueStacks và Windows Companion **1.6.1**. Đường đang dùng là **Gemini API chính thức**, chế độ Fast với `gemini-3.5-flash-lite`, `thinkingLevel=minimal`; CLI thử nghiệm tắt. Giữ khóa mã hóa DPAPI, ghép cặp, chứng chỉ, vùng OCR và dữ liệu hiện có.

**Đã giảm chờ câu cũ, bổ sung dịch trước tệp phụ đề và kiểm tra được bản dịch Gemini thực sự xuất hiện trên Android. Chưa đạt một giây ổn định cho câu mới.** Ba câu mới trên Android mất **1.379 / 1.511 / 1.345 ms** từ lúc nguồn được vẽ đến lúc lớp phủ được vẽ. Mốc P95 **375,85 ms** bên dưới thuộc 100 lần chuyển giữa ba câu đã dịch trước; không đại diện 100 câu mới gọi Gemini.

## Nguyên nhân đã xác minh và thay đổi

| Vấn đề trong đường xử lý | Bằng chứng và cách xử lý |
|---|---|
| Câu mới có thể chờ yêu cầu cũ đang xử lý | OCR Android vốn chạy độc lập; đường xử lý API tuần tự trên máy chủ mới giữ câu mới phía sau câu cũ. Đường API mới có tối đa hai tác vụ đang hoạt động mỗi thiết bị, thêm đúng một ô chờ có thể bị câu mới nhất thay thế. Android tiếp tục OCR và gửi câu hiện tại theo khả năng máy chủ công bố. Không tạo hàng đợi không giới hạn. |
| Kết quả cũ vẫn có thể tiêu tốn hạn mức dù không còn được hiển thị | Bỏ yêu cầu đã lỗi thời trước lúc lấy được lượt gọi toàn cục. Lượt HTTP đã bắt đầu có thể hoàn tất để điền cache, nhưng không được gửi đè câu mới hoặc ghi ngược ngữ cảnh hội thoại mới. Tất cả đường hiển thị vẫn kiểm tra requestId, regionId, screenGeneration và chữ nguồn hiện tại. |
| Câu có cache có thể bị chờ sau hai yêu cầu AI | Thêm tra cache theo đúng namespace hiện có trước hàng đợi provider. Tra trượt không chờ semaphore và không tiêu tốn lượt API. Kiểm thử cho thấy một câu có cache đi qua được khi cả hai tác vụ AI đang bận. |
| OCR dao động có thể phát sinh yêu cầu không cần thiết | Đợi ổn định khoảng **90 ms** cho nguồn mới, tăng lên **180 ms** khi có ít nhất ba lần đổi trong một giây. Cache điện thoại đi trước cổng chờ này; nguồn biến mất được xử lý ngay. Đây là đánh đổi thêm một khoảng ngắn để giảm gửi câu OCR chưa ổn định, không phải cam kết tiết kiệm hạn mức trong mọi video. |
| Cấu hình Fast chưa có đường payload riêng | Rút gọn chỉ dẫn và JSON, giữ từ điển/ngữ cảnh/thứ tự câu; đặt mức thinking theo model hỗ trợ, dùng trần đầu ra thích ứng với độ dài và độ nở bản dịch. Tiếp tục dùng lại AsyncClient. Không dùng trần quá nhỏ làm cắt batch; số lượng bản dịch phải khớp đầu vào. Chưa có phép thử cô lập chứng minh riêng từng thay đổi đóng góp bao nhiêu mili giây. |
| HTTP429 dễ dẫn đến thử lại sớm và tiếp tục tiêu tốn lượt nội bộ | Đọc `Retry-After` và `google.rpc.RetryInfo`, truyền `retry_after_ms` xuống Android và dùng khoảng chờ chung trước khi đặt lượt API. Nếu không có metadata hợp lệ thì dùng khoảng chờ tăng dần có giới hạn. Cache vẫn dùng được trong thời gian đó. Phản hồi429 thuộc khóa cũ không được khóa chặn khóa thay thế. |
| Nút “1 KHUNG” làm chế độ tự động khó nhận biết | Trong chế độ tự động, nút hiện **QUÉT LẠI**; chế độ một khung vẫn giữ chức năng riêng. Đường báo trạng thái/hủy và lỗi cấu hình được kiểm thử lại. Không thêm hiệu ứng nhấp nháy hay vẽ từng token/JSON chưa hoàn chỉnh. |

Hai tác vụ API dùng chung semaphore toàn cục tối đa hai lượt; không nhân đôi mức này theo thiết bị. Khi nguồn trống, tạm dừng hoặc ngắt kết nối, các tác vụ liên quan được hủy và ô chờ được bỏ. Hủy một HTTP đã gửi không bảo đảm Google hoàn lại hạn mức.

Chính sách thinking dựa trên [tài liệu Google về thinking](https://ai.google.dev/gemini-api/docs/thinking); kiểu metadata thử lại dựa trên [định nghĩa RetryInfo chính thức của Google](https://github.com/googleapis/googleapis/blob/master/google/rpc/error_details.proto). Đây là cơ sở triển khai, không phải bằng chứng benchmark. Tài liệu nêu Flash-Lite 3.5 mặc định minimal, nên không quy toàn bộ mức giảm quan sát được cho việc đặt minimal rõ ràng.

## Số đo máy chủ trước và sau

Mỗi đợt gửi ba câu công khai mới, không lấy cache cho lượt đầu, không tự thử lại. Trước là Windows1.6.0, sau là Windows1.6.1; cùng model Fast Flash-Lite. **Hai đợt dùng các câu khác nhau, chỉ có ba mẫu mỗi đợt.** Chưa đủ để kết luận mức tăng tốc nhân quả hoặc P95 ổn định của provider.

| Đợt | Thời gian máy chủ của ba lượt | Phạm vi phép đo |
|---|---|---|
| Trước | 4.547 / 5.031 / 4.687 ms | Xử lý máy chủ, chủ yếu chờ HTTP Gemini |
| Sau | 937 / 860 / 640 ms | Cùng loại chỉ số; chưa gồm capture/OCR/lớp phủ Android |

`provider` đo toàn bộ thời gian gọi HTTP tới Google, gồm mạng và xử lý phía dịch vụ; chưa tách riêng suy luận Gemini khỏi mạng. `server` gồm các công đoạn máy chủ và hàng đợi. Không gọi một trong hai số này là độ trễ toàn luồng. Chênh lệch nhỏ giữa đồng hồ HTTP phía client và đồng hồ máy chủ không được dùng để suy ra thời gian mạng âm.

[Ba mẫu trước](results/api-speed-before-w161.json) · [Ba mẫu sau](results/api-speed-after-w161.json).

## Ba câu mới: nguồn native → Gemini → Canvas Android

Nguồn được vẽ trong cảnh thử native công khai. Hash OCR phải khớp chữ nguồn, mỗi mẫu tăng đúng một lượt AI; đọc lại cache sau đó không gọi thêm AI và hash bản dịch máy chủ khớp hash bản dịch Android đã vẽ.

| Câu nguồn | Bản dịch Gemini đã được Android vẽ | Nguồn→vẽ |
|---|---|---:|
| 现在我们出发 | Bây giờ chúng ta xuất phát | 1.379 ms |
| 今天一起吃饭 | Hôm nay cùng ăn cơm nhé | 1.511 ms |
| 这里有一个人 | Ở đây có một người | 1.345 ms |

Các công đoạn ghi nhận ở cùng ba mẫu:

| Chỉ số | Mẫu1 | Mẫu2 | Mẫu3 |
|---|---:|---:|---:|
| Capture của nguồn được nhận, ms | 9 | 9 | 9 |
| OCR tại khung gửi, ms | 117 | 121 | 62 |
| Chờ ổn định thực tế trước gửi, ms | 125 | 128 | 134 |
| RTT Android↔máy chủ, ms | 891 | 812 | 659 |
| Chờ HTTP provider, ms | 875 | 797 | 640 |
| Máy chủ, ms | 890 | 797 | 656 |
| Nhận phản hồi→vẽ, ms | 222 | 240 | 296 |
| Lần yêu cầu vẽ→Canvas, ms | 36 | 23 | 40 |

Các hàng có công đoạn lồng nhau và dùng các mốc khác nhau, không được cộng tất cả lại. Sau phản hồi vẫn cần OCR mới để xác nhận chữ nguồn còn hiện; khoảng **222–296 ms** này bảo vệ việc không vẽ câu cũ. Khoảng xuất hiện nguồn trước lần capture tiếp theo cũng thuộc toàn luồng, nhưng không nằm trong nhãn “Khung OCR → hiện”.

Đồng hồ đầu/cuối là elapsedRealtime trên Android, từ lần vẽ nguồn đầu tiên đến Canvas vẽ lớp phủ. Chưa đo thời điểm GPU/display thực sự trình chiếu pixel. Ba mẫu không đủ để nghiệm thu P95, chất lượng100 câu, tốc độ phim liên tục hoặc điện thoại Redmi thật. [Bản ghi đầy đủ](results/api-android-v163.json), [ảnh cảnh thử công khai](images/api-android-live-v163.png).

## Phương án đã thêm: dịch trước phụ đề bằng Gemini

Windows có mục **Dịch trước phụ đề**. Chọn tệp nguồn SRT/VTT/ASS/SSA, chọn ngôn ngữ/hồ sơ/chế độ, bấm **Xem trước tệp**, kiểm tra số câu và số batch, rồi mới bấm dịch. Xem trước không gọi AI. Tác vụ dịch trước chỉ chạy khi Gemini API đã cấu hình và cache bật; không chuyển sang CLI hoặc engine local.

Tệp tối đa2 MiB/3.000 cue. Parser kiểm tra toàn bộ tệp trước khi tạo tác vụ, giữ thời gian và ID cue, bỏ định dạng được hỗ trợ và từ chối tệp lỗi/ASS vector thay vì âm thầm ghép sai câu. Câu trùng được gộp theo NFKC và khoảng trắng, batch tối đa10 câu cùng các giới hạn độ dài/token ước lượng. Một tác vụ hoạt động; từng batch đi qua TranslationManager hiện có, dùng đúng namespace ngôn ngữ/hồ sơ/chế độ/model/từ điển. Có tiến độ và nút Dừng, không tự gửi lại khi lỗi quota. Đổi cấu hình hoặc revision thì dừng tác vụ. Nội dung tệp chỉ được giữ trong RAM trong thời gian xử lý; trạng thái chỉ chứa số lượng và tiến độ.

Đã chạy thật trên bản Windows đã cài với [tệp mẫu công khai](examples/thu-phu-de-zh.srt):

| Hạng mục | Kết quả thực |
|---|---:|
| Cue trong tệp / câu riêng biệt | 4 / 3 |
| Lượt AI khi phân tích | 0 |
| Batch dịch trước / lượt Gemini mới | 1 / 1 |
| Thời gian tác vụ | 1.375 ms |
| Đọc lại ba câu từ cache | 3/3 đúng bản dịch |
| Lượt AI khi đọc lại | 0 |

Ví dụ bản dịch trả về: “Tôi đợi bạn ở đây”, “Hôm nay trời không mưa”, “Chúng ta cùng về nhà nhé”. [Bản ghi tác vụ và đọc lại](results/subtitle-prewarm-live-v163.json).

**Đợi dịch trước hoàn tất hoặc bấm Dừng trước khi xem trực tiếp.** Hai chế độ dùng chung kết nối, hai lượt đang xử lý và hạn mức Gemini; chạy cùng lúc có thể làm chậm câu đang xem. Giao diện Windows có cảnh báo này. Dịch theo lô giảm số yêu cầu so với gửi từng câu, nhưng chưa đo hóa đơn hoặc mức tiết kiệm chi phí thực.

Giới hạn: cần có tệp phụ đề nguồn đúng video. Tính năng hiện chuẩn bị cache, chưa phát video hay tự đồng bộ lớp phủ theo timestamp. OCR phải khớp nguồn sau chuẩn hóa hiện có; khác dấu câu, cách tách dòng, nhận sai chữ hoặc chọn hồ sơ/model khác vẫn có thể trượt cache. Không tạo ánh xạ gần đúng có nguy cơ trả nhầm câu. Với video chỉ có chữ cháy vào hình và không có tệp nguồn, vẫn phải dịch sau khi OCR thấy câu.

## 100 lần chuyển phụ đề đã dịch trước

Chạy cảnh native thay chữ mỗi **800 ms**, lặp ba câu công khai trong tệp mẫu. Cache được tạo bằng đúng một batch Gemini trước khi phát. Đây là kiểm tra tốc độ dùng lại bản dịch và độ đúng câu hiện tại, **không phải 100 lượt suy luận mới**.

| Chỉ số | Kết quả |
|---|---:|
| Lần chuyển nguồn / lần khớp bản dịch | 100 / 100 |
| Câu bỏ lỡ | 0 |
| Lượt vẽ sai câu hoặc lỗi thời | 0 |
| Lượt AI mới / lỗi server mới | 0 / 0 |
| Lấy từ cache điện thoại / cache máy chủ | 97 / 3 |
| P50 nguồn→vẽ | 201 ms |
| P90 | 308,1 ms |
| P95 | 375,85 ms |
| P99 | 502,15 ms |
| Cao nhất | 616 ms |

Phân vị nội suy tuyến tính; ghi cả các lần bỏ lỡ thay vì chỉ giữ mẫu thành công. Kết quả không suy rộng sang100 câu mới, font/vùng OCR khác, lỗi nhận diện, mạng khác hoặc Redmi thật. [Toàn bộ100 mẫu và hạn chế](results/pretranslated-android-100-v163.json). Kết quả này chứng minh lợi ích thực của chuẩn bị trước đúng câu; chưa chứng minh theo kịp mọi hội thoại chưa biết trước.

## Kiểm tra video YouTube đang mở

Đã phát tiếp khoảng **10,38 giây thời gian thực**, không tua, sau đó tạm dừng lại. Đồng hồ video đi khoảng3:49→3:54, có khoảng tải/đoạn giới thiệu nên đây không phải10 giây hội thoại liên tiếp. Log có9 thay đổi thế hệ OCR, trong đó6 có nguồn không rỗng; nhận2 kết quả và vẽ2 lớp phủ. Cả hai dùng cache, không có AI mới hoặc lỗi mới; không thấy vẽ phản hồi thuộc thế hệ OCR cũ.

Các thế hệ OCR không tương đương số câu thoại hoàn chỉnh. Không dùng cửa sổ ngắn này để tuyên bố đã dịch đủ6 câu hoặc đo P95 câu mới trong phim. Chỉ metadata/hash được ghi trong [báo cáo video](results/api-real-video-v163.json).

## Kiểm tra chính sách hàng đợi bằng mô phỏng

**Bảng này là mô phỏng, KHÔNG PHẢI GEMINI.** Dùng máy chủ WebSocket thật với provider giả chờ250 ms, nguồn đổi mỗi150 ms; ba lần chạy, mỗi lần20 câu cho từng chính sách. Không có Google, OCR, Android, capture hoặc lớp phủ. Nguồn biến mất nhanh hơn thời gian dịch giả, nên chỉ câu cuối có đủ thời gian giữ nguyên để nhận kết quả.

| Chính sách | Tổng nguồn | Lượt provider giả | Kết quả còn đúng câu hiện tại | P50 chờ câu cuối | P50 nhận câu cuối |
|---|---:|---:|---:|---:|---:|
| Tuần tự | 60 | 39 | 3 | 161,397 ms | 415,572 ms |
| Hai tác vụ API, một ô chờ mới nhất | 60 | 60 | 3 | 1,991 ms | 257,309 ms |

Cả hai không gửi kết quả lỗi thời. Chính sách mới giảm hàng đợi nhưng thực hiện **nhiều lượt giả hơn:60 so với39**, vẫn chỉ trả3/60 nguồn khi còn hiện. Vì vậy tăng song song không tự giải quyết quota hoặc độ phủ câu ngắn. Không dùng số mô phỏng làm độ trễ sản phẩm hay mục tiêu Gemini. [Phương pháp, dữ liệu từng lượt và cảnh báo](results/scheduler-simulation-v163.json).

## Các phương án sử dụng và phần chưa triển khai

| Phương án | Trạng thái | Lợi ích và giới hạn |
|---|---|---|
| OCR vùng nhỏ → Gemini API Fast, hai tác vụ có giới hạn | Đã triển khai/cài | Giảm chờ câu cũ; vẫn phụ thuộc Google, mạng, quota và thời gian giữ câu. Ba câu mới còn1,345–1,511 giây toàn luồng. |
| Dịch trước tệp phụ đề nguồn theo batch, sau đó dùng cache | Đã triển khai/cài và đo | Đã đạt P95 dưới một giây trong100 lần chuyển giữa ba câu đã chuẩn bị. Cần tệp nguồn khớp OCR và cùng cấu hình, không đại diện phim bất kỳ. |
| Trình phát video do ứng dụng quản lý, đệm khởi đầu3–5 giây để dịch phía trước | Phương án thiết kế, chưa triển khai/đo | Có thể đọc phụ đề hoặc khung tương lai trước thời điểm phát, đổi một khoảng đợi ban đầu lấy bản dịch kịp cảnh. Cần kiểm soát media và đồng hồ phát; không hứa áp dụng được với YouTube hay ứng dụng ngoài đang chiếu màn hình. |
| Local + Gemini tinh chỉnh | Chưa đưa vào ứng dụng chính | Prototype trước nhanh nhưng sai phủ định/con số, không đạt ngưỡng chất lượng; không hiển thị bản dịch kém chỉ để đạt chỉ số. Xem [báo cáo local](../research/local_translation/README.md). |

Chưa đưa streaming từng token vào lớp phủ. Bản dịch chỉ hiện sau khi JSON đủ mục, đúng liên kết yêu cầu và còn khớp nguồn. Chưa hoàn thành kiểm thử chất lượng100 câu mới thành công, so sánh mô hình/hóa đơn toàn diện hoặc nghiệm thu Redmi; không có cơ sở bảo đảm bất kỳ câu thoại nào cũng quanh một giây.

## Kiểm thử và tệp thay đổi

**163 kiểm thử backend đạt;116 kiểm thử Android đạt; lint Android0 lỗi/43 cảnh báo; kiểm tra bản Windows đóng gói đạt.** Bộ harness API nghiên cứu có thêm13 kiểm thử offline đạt. Các kiểm thử dùng provider giả xác minh thứ tự, hủy, quota và mapping; không coi thời gian test giả là thời gian Gemini. Các số API/native bên trên có nguồn JSON riêng. [Tổng hợp kiểm tra phát hành](results/release-verification-v163.json) và [kiểm tra nâng cấp giữ dữ liệu](results/installed-w161-upgrade.json) ghi nhận EXE cài khớp bản giao, giữ thiết lập/khóa mã hóa/chứng chỉ/ghép cặp/usage, không gọi AI trong lúc nâng cấp.

Kiểm thử hồi quy bao gồm A đang dịch→B xuất hiện→A không ghi đè B; nguồn biến mất→hủy/chuyển trạng thái rỗng; nhiều khung cùng câu→không gọi lặp; hai tác vụ bận→chỉ giữ câu mới nhất; cache vượt qua tác vụ bận; phản hồi cũ không ghi ngược ngữ cảnh; metadata429/khóa thay thế; parser, tác vụ dịch trước, hủy, đổi cấu hình, xác thực admin và reuse đúng namespace cache.

Các tệp nguồn/kiểm thử chính trong lần nâng cấp này:

- Android: `android-app/app/src/main/java/com/gemini/ocr/capture/SubtitleDebounce.kt`, `TranslationRetryState.kt`, `ScreenCaptureService.kt`; `android-app/app/src/main/java/com/gemini/ocr/overlay/TranslationOverlay.kt`; các test tương ứng `SubtitleDebounceTest.kt`, `TranslationRetryStateTest.kt`, `ConfigurationFailureCaptureTest.kt`, `TranslationOverlayTest.kt`; `android-app/app/build.gradle.kts`.
- Máy chủ: `windows-server/app/provider.py`, `manager.py`, `server.py`, `subtitle_import.py`, `subtitle_jobs.py`, `subtitle_routes.py`, `__init__.py`; `windows-server/launcher.py`, `windows-server/installer.py`.
- Giao diện Windows: `windows-server/static/index.html`, `app.js`.
- Kiểm thử máy chủ: `tests/test_api_speed_policy.py`, `test_api_latest_scheduling.py`, `test_pipeline_telemetry.py`, `test_subtitle_import.py`, `test_subtitle_jobs.py`, `test_subtitle_routes.py`; `scripts/smoke_windows.py` kiểm tra bản đóng gói và phiên bản.
- Đo và minh họa: `scripts/verify_api_pipeline.py`, `scripts/benchmark_pretranslated_subtitles.py`, `research/benchmark_scheduler_simulated.py`, tệp mẫu `docs/examples/thu-phu-de-zh.srt` và các JSON được liên kết trong báo cáo.
- Tài liệu: `README.md`, `docs/VERIFICATION.md`, báo cáo này.

Bản APK đã đo/cài có SHA256 `f0ee36bbb9ea8dc56fcc71f3484b4757a31e3cdef8c2d7f388f91bc2a80eb567`. Tệp phát hành: [APK Android](../dist/GeminiOCR-Android.apk), [bộ cài Windows](../dist/GeminiOCR-Windows-Setup.exe), [mã nguồn](../dist/GeminiOCR-Source.zip), [SHA256SUMS](../dist/SHA256SUMS.txt). Các tên tệp phát hành cố định được cập nhật theo bản mới; kiểm tra phiên bản và SHA256 trước khi đối chiếu số đo.
