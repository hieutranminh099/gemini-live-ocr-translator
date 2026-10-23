# AI Video Translator và Translation Memory — 1.7.0

Ngày kiểm tra: 23/10/2026. Tính năng được bổ sung trực tiếp vào Windows Companion hiện có; Live OCR, ghép cặp, từ điển và cache cũ được giữ. Android 1.7.0 bổ sung thông báo trạng thái thu màn hình để Windows nhường tài nguyên đúng khi Live tạm dừng hoặc tiếp tục.

Video được nhập, giải mã, OCR/ASR và render trên máy. **Dịch câu mới vẫn cần Internet và Gemini API chính thức.** Đây không phải bộ dịch hoàn toàn offline; không dùng CLI/OAuth làm backend video và không thay trọng số Gemini.

## Trạng thái nghiệm thu

Luồng **nhập MKV có phụ đề văn bản → Gemini thật → SRT/ASS/MP4 có tiếng Việt → xác nhận và tái sử dụng Translation Memory** đã chạy bằng EXE đóng gói trên video mẫu công khai. Bản dịch thực tế gồm “Xin chào thế giới” và “Tạm biệt thế giới”. Lượt đầu gọi Gemini một lần; dự án thứ hai dùng lại hai câu đã xác nhận và không gọi Gemini. [Kết quả EXE](results/video-packaged-v170.json), [MP4 mẫu](../dist/examples/Video-Translator-Demo.mp4).

**Speech trong EXE cuối đã chạy đạt mẫu tiếng Anh tổng hợp:** worker dùng model đóng gói nhận transcript có timestamp, gửi Gemini API thật và nhận bản dịch Việt; dự án riêng tư không tạo mục TM toàn cục. Đã kiểm tra thêm RapidOCR đóng gói với hai câu Trung và khôi phục checkpoint đã hoàn tất sau clean restart mà không gọi thêm AI. Đây là phép thử tích hợp mẫu nhỏ, chưa nghiệm thu chất lượng ASR/dịch trên phim thật.

OCR tiếng Hàn không đạt mẫu thực tế, nên đường OCR tiếng Hàn bị chặn trong sản phẩm; có thể dùng luồng phụ đề văn bản. Auto tiếng Hàn chuyển sang Speech nếu không có luồng văn bản; Speech Trung/Nhật/Hàn có cấu hình hỗ trợ nhưng chưa kiểm thử độ chính xác thực tế. Chưa có benchmark chất lượng hội thoại đa ngôn ngữ hoặc chứng minh Live câu mới đạt một giây.

## Cách sử dụng

1. Mở Windows Companion 1.7.0, chọn **AI Video Translator**. Chọn hoặc kéo thả video; ứng dụng sao chép vào thư mục dự án và đọc metadata. Bước nhập chưa gọi AI và không sửa tệp gốc.
2. Chọn ngôn ngữ Trung/Nhật/Hàn/Anh, hồ sơ nội dung và Fast/Smart/Quality. Model dùng cấu hình Gemini API đã lưu trong **Cài đặt AI**; ứng dụng không tự chọn một model ngoài cấu hình đó.
3. **Auto** ưu tiên luồng phụ đề văn bản. **Subtitle** cho chọn luồng; **OCR** đọc chữ đã ghi cứng vào hình, với ROI dạng tỉ lệ 0–1; **Speech** nhận dạng lời nói bằng mô hình cục bộ khi khả dụng. Khoanh ROI quanh dòng nguồn, tránh pinyin và dòng Việt có sẵn. Phụ đề ảnh PGS/DVD chưa được chuyển thành chữ bằng đường trích xuất trực tiếp.
4. Giữ **Video riêng tư** nếu không muốn bổ sung nội dung vào kho chung. Muốn tích lũy, bật **Kho dịch thuật → Cho phép tích lũy bản dịch mới**, tắt Video riêng tư và bật **Lưu bản dịch của video vào kho**. Mặc định tích lũy tắt, riêng tư bật và lưu video vào kho tắt.
5. Nhấn **Bắt đầu nhận dạng và dịch bằng Gemini**. Trang hiển thị giai đoạn, số câu, cache hit, số mục mới, lượt gọi AI và token. API có hạn mức/chi phí riêng; chưa ước tính tiền khi không có bảng giá tương ứng. Tạm dừng Live để tác vụ video được chạy; video tự chờ khi Live hoạt động.
6. Khi nhận dạng/dịch xong, sửa văn bản nguồn, bản dịch hoặc thời gian rồi **Lưu timeline**. Nút **Xác nhận trang này** áp dụng trang đang mở; **Thay tất cả** tìm/thay trên toàn timeline và đánh dấu bản sửa là User Approved. Nguồn đổi thì bản dịch cũ bị bỏ cho tới khi được dịch hoặc xác nhận lại.
7. Chọn font Unicode có sẵn trên Windows, cỡ chữ, màu, viền, bóng, vị trí, nền và song ngữ. Xuất **SRT**, **ASS** hoặc **MP4**; MP4 có lựa chọn chất lượng cao/xuất nhanh. Tải kết quả qua liên kết và chọn nơi lưu trong trình duyệt. Tệp xuất cũng được giữ trong thư mục dự án của ứng dụng.

Preview trình duyệt minh họa phụ đề của **trang timeline đang mở**; MP4 cuối áp dụng toàn bộ timeline. MKV hoặc codec không được trình duyệt hỗ trợ có thể không phát được trong preview; dùng MP4 xuất để kiểm tra. Nền phụ đề/hộp che giúp đọc rõ chữ: **không phải inpainting xóa sạch phụ đề gốc**.

## Pipeline và tài nguyên

| Công đoạn | Triển khai hiện có |
|---|---|
| Import/probe | Upload được đọc từng khối 1 MiB; giới hạn 20 GiB/video, tối đa 100 dự án và 20.000 cue. Kiểm tra chỗ trống, chỉ xử lý bản sao do dự án sở hữu. |
| Luồng phụ đề | ffprobe phân biệt text/image; text được trích xuất trực tiếp, giữ ID và start/end, không gọi OCR/ASR. |
| Video OCR | FFmpeg giải mã theo PTS thực, lấy mẫu 250 ms trong ROI; hàng đợi khung giới hạn 8. Khung giống dùng lại kết quả trong thời gian giới hạn; gộp câu liên tiếp, kết thúc ở thời điểm mất chữ. Câu tái xuất hiện sau khoảng trống vẫn là cue mới. |
| OCR ngôn ngữ | RapidOCR/ONNX chạy CPU; Trung/Anh, model Nhật riêng. Lọc theo hệ chữ nguồn CJK để giảm nhận nhầm pinyin/Việt. Không dùng so khớp mờ để gộp các câu khác phủ định, số hoặc tên. OCR tiếng Hàn bị chặn sau phép thử sai. |
| Speech | faster-whisper tiny, CPU INT8, hai luồng, VAD; tách âm thanh PCM mono 16 kHz từng 30 giây, worker riêng có thể dừng. Hiện nạp lại model mỗi khối; chất lượng và tốc độ phim thật chưa nghiệm thu. |
| Dịch | Dùng TranslationManager, API provider và glossary chung. Gộp câu nguồn trùng chính xác, batch tối đa 10 câu/6.000 ký tự, kiểm tra đủ ID trước khi lưu kết quả. Câu trong dữ liệu được coi là nội dung dịch, không thực thi chỉ dẫn của câu đó. |
| Ưu tiên | Toàn server tối đa hai lượt AI, video tối đa một. Live được nhận trước; video không nhận lượt mới khi có Live capture, Live chờ hoặc Live đang gọi. Chừa một phần hạn mức cục bộ cho Live; không tăng hạn mức thật của Google. |
| CPU video | FFmpeg/worker do tác vụ sở hữu được tạm đình chỉ khi gate Live đóng, tiếp tục sau khi gate mở; process chạy ưu tiên thấp hơn, số luồng giới hạn. Một lần OCR đang suy luận trong thread không bị ngắt giữa chừng. Không có giới hạn RAM/GPU cứng của hệ điều hành. |
| Lưu/khôi phục | project.json và timeline.json ghi nguyên tử. Checkpoint OCR sau các cue đã hoàn tất, dịch sau từng batch; khi mở lại, tác vụ chưa xong thành Interrupted để người dùng khôi phục. |
| Export | FFmpeg H.264/yuv420p, giữ kích thước và timestamp qua FPS passthrough; audio AAC tương thích được stream copy, audio khác đổi AAC. SRT/ASS UTF-8, song ngữ và xuống dòng; MP4 xuất sang tên mới. |

Một lượt video đã gọi API có thể hoàn tất sau khi Live bắt đầu; ứng dụng không hủy HTTP đang chạy để tránh lặp phí. Lượt đó chiếm tối đa một trong hai slot. Pause/cancel không hủy dữ liệu phụ đề đã checkpoint. Render MP4 dang dở được chạy lại từ timeline; không tiếp tục giữa bitstream đã xuất dở.

## Translation Memory dùng chung

`TranslationMemoryManager` ghi SQLite qua hàng đợi bất đồng bộ giới hạn 512. Mỗi mục có nguyên văn, văn bản chuẩn hóa, bản dịch Việt, ngôn ngữ, ngữ cảnh, nguồn LIVE/VIDEO, dự án/profile, provider/model, mức chất lượng, confidence metadata, glossary version, thời gian tạo/sửa, hit count và hash. Chuẩn hóa NFKC/khoảng trắng giữ ranh giới từ; không tìm gần đúng các câu chỉ khác phủ định, tên hoặc con số.

- **Draft:** kết quả AI mới. Không tự tăng lên Verified và không được video/Live dùng làm mục Translation Memory đã kiểm chứng.
- **Verified:** mức hỗ trợ cho mục đã được kiểm tra rõ ràng; hiện chưa có bộ chấm chất lượng tự động đủ tin cậy để tự gán mức này.
- **User Approved:** chỉ do thao tác xác nhận/chỉnh sửa của người dùng. AI không ghi đè được; người dùng có thể xác nhận lại bản sửa.

Tra cứu chỉ dùng Verified/User Approved khớp namespace ngôn ngữ, profile, mode, provider/model, glossary và scope. Mục được xác nhận từ video công khai có thể dùng cho Live khi cấu hình khớp. Câu trùng nhưng khác cách xưng hô/ngữ cảnh vẫn cần người dùng xét lại: exact match không chứng minh mọi ngữ cảnh có cùng nghĩa. Chưa có tìm kiếm ngữ nghĩa hoặc tự học trọng số model.

Live có thể tích lũy Draft khi người dùng bật công tắc chung. Video còn cần quyền lưu riêng và không được đánh dấu riêng tư. **Video riêng tư không ghi nội dung vào TM toàn cục, hash cache toàn cục hoặc ngữ cảnh hội thoại dùng chung**, kể cả khi xác nhận timeline. Bản sao video và checkpoint riêng vẫn lưu cục bộ để chỉnh sửa/khôi phục; xóa dự án mới xóa các bản sao này. Nội dung chưa có bản dịch vẫn phải gửi Gemini khi người dùng bắt đầu dịch.

**Tra cứu Translation Memory** và **Lưu bản dịch vào kho** là hai quyền độc lập: tắt tra cứu không tự tắt lưu Draft nếu người dùng đã bật tích lũy và cho phép lưu video công khai. Ngược lại, bật tra cứu không cho phép tự lưu khi công tắc tích lũy hoặc quyền lưu video đang tắt.

Kho cho tìm/lọc, sửa/xác nhận, xóa một mục hoặc các mục do dự án sở hữu. Ghi Draft/hit có thể bị bỏ khi hàng đợi đầy; xác nhận được ghi có chờ. Tắt đột ngột có thể mất Draft chưa flush. Mục trùng giữ provenance chủ sở hữu đầu tiên; xóa dự án sở hữu có thể xóa cả mục đã được dự án khác dùng lại. Không sửa các dự án khác hoặc video gốc.

## Kiểm thử A–J và số đo thực tế

| Ca | Kết quả đã quan sát | Phạm vi chưa chứng minh |
|---|---|---|
| A — text subtitle | MKV mẫu hai cue 400–1.300 ms và 1.600–2.600 ms được trích đúng, không OCR. EXE gọi Gemini thật và xuất đủ hai câu Việt. | Chưa có corpus nhiều định dạng/codec phụ đề. |
| B — burned OCR | RapidOCR thật trên video Trung tạo tại máy: hai cue 0–1.000 và 2.000–3.000 ms, khoảng trống ở giữa, không nhân bản. EXE cuối trích đúng hai câu và dùng lại TM đã xác nhận, AI call 0. Mẫu ảnh Nhật khớp chính xác. | Mẫu Hàn sai và bị chặn; chưa đo phim thật, font chuyển động hoặc tracking ROI tự động. |
| C — Speech | Mã nguồn nhận đúng đoạn tiếng Anh tổng hợp 8,95 giây, hai đoạn có timestamp hợp lệ; im lặng hai giây trả rỗng. EXE cuối dùng model đóng gói nhận đúng mẫu tiếng Anh, gọi Gemini một lần và nhận đủ bản dịch Việt. | Không suy rộng WER mẫu tổng hợp sang phim hay Trung/Nhật/Hàn. Bản dịch còn Draft, chưa được duyệt chất lượng xưng hô. |
| D — TM reuse | EXE: lần đầu 1 Gemini call, 2 Draft; sau thao tác test xác nhận, dự án thứ hai cache hit 2, AI call 0. Request Live qua HTTP tái sử dụng đúng mục đã xác nhận, không gọi thêm AI. | Thao tác xác nhận tự động trong test không phải chuyên gia duyệt chất lượng ngôn ngữ; chưa xác nhận render Android của mục TM mới. |
| E — bảo vệ chất lượng | Regression kiểm tra Draft không được reuse, AI không ghi đè User Approved, sửa nguồn xóa bản dịch cũ và đúng scope/glossary/ngôn ngữ. | Không có benchmark 100 câu chấm nghĩa/xưng hô của Video Translator. |
| F — export | FFmpeg thật: MP4 H.264/AAC 320×180, 12 fps; AAC packet giống hệt nguồn; thời lượng 3.023 → 3.001 ms, lệch −22 ms. Đường dẫn Unicode và hash nguồn được kiểm tra. EXE xuất SRT/ASS/MP4 thật. | Nguồn thử CFR ngắn; chưa benchmark VFR, mọi audio codec hoặc phim dài. Không cam kết thời lượng byte-for-byte với mọi muxer. |
| G — Live priority | Regression kiểm tra slot/gate, capture pause/continue, nhiều thiết bị và cleanup kết nối. Trên BlueStacks 1.7.0, video đang export chuyển `waiting_live` khi Live bắt đầu; encoder không tăng CPU trong cửa sổ đo một giây. Ca video chờ Live vẽ đúng 20/20 cue cache, P95 290,10 ms và không có lượt vẽ câu cũ. Follow-up không video cũng 20/20, P95 618,10 ms. | Baseline lạnh chỉ 18/20 cue, có một lượt vẽ câu trước; báo cáo so sánh giữ `passed=false`, đồng thời ghi `priority_suspension_verified=true`. Chưa chứng minh latency mọi câu mới hoặc mọi phim không bị ảnh hưởng. |
| H — recovery | Regression đóng dịch giữa các batch, mở lại checkpoint và không gọi lại những batch hoàn tất; edit/output không ghi đè bản gốc. EXE cuối clean restart rồi khôi phục dự án đã hoàn tất, không phát sinh AI call. | Phép thử EXE là clean restart với checkpoint hoàn tất, chưa phải force crash giữa batch. Batch đã gọi nhưng chưa checkpoint có thể phải gọi lại; Speech chưa checkpoint transcript sau từng khối. |
| I — video dài | Đã giải mã video tổng hợp hai giờ, 1 fps, xử lý 7.200 timestamp tăng dần. Parent RSS 31,01 → 31,85 MiB; parent + child peak 59,26 MiB, thời gian 4.547 ms. | Recognizer deterministic, file chỉ 270.405 byte; không phải real OCR/ASR, phim thực hay thử dung lượng 20 GiB. RSS không là giới hạn bộ nhớ cứng. |
| J — riêng tư | Regression kiểm tra global opt-in mặc định tắt, private không ghi TM/cache/context, kể cả sau xác nhận; xóa dự án không đụng tệp gốc/dự án khác. EXE cuối đã hoàn tất private Speech → Gemini mà không thêm mục TM chung. | Không coi đây là audit bảo mật toàn hệ thống. Video và checkpoint riêng vẫn được lưu cục bộ. |

Bằng chứng máy đọc được: [media/OCR/export](results/video-media-v170.json), [ASR từ mã nguồn](results/video-speech-v170.json), [decode dài có giới hạn](results/video-long-v170.json), [EXE/Gemini/TM và clean restart](results/video-packaged-v170.json).

ASR mẫu tiếng Anh từ mã nguồn mất **1.375 ms**, gồm decode 47 ms và worker 1.312 ms; worker gồm nạp model, VAD và suy luận. WER bằng 0 trên đúng mẫu tổng hợp này. Mẫu im lặng mất 906 ms. Các phép thử media, speech và video dài không gọi Google. Các dự án trong báo cáo EXE dùng tổng cộng **hai Gemini call, 185 input token và 50 output token**: một batch phụ đề Trung và một batch transcript Anh. Đợt xác minh sau sửa worker chỉ gọi mới một lần; các ca TM/OCR/restart không gọi thêm.

Bộ Android mới đã chạy **124 JVM tests, không failure/error**, cùng `assembleRelease` và `lintDebug` thành công; APK 1.7.0 đã cài đè thành công trên BlueStacks. Lần backend cuối **284 test đạt, 0 failure/error, 42,42 giây**; có một `DeprecationWarning` của Starlette. [JUnit XML](results/video-backend-v170.xml). Lần trước có một lỗi chờ child khởi động khi máy đang đóng gói; đã sửa thời hạn chờ và lần suite cuối chạy đạt.

Giao diện có **11 kiểm tra DOM/payload với API mock** và **12 kiểm tra đọc dữ liệu từ EXE thật** bằng Edge headless: hiển thị đúng nguyên văn/bản dịch Gemini, luồng phụ đề và model, seek/preview kiểu chữ/song ngữ, ngăn export khi có sửa chưa lưu, tải đủ SRT/ASS/MP4 và giải mã khung video thật. Không có JavaScript page error, server mutation hoặc Google call trong đợt kiểm tra đọc này. Đây là fixture công khai hai cue, chưa phải phép thử phim thực. [Báo cáo UI thật](results/video-ui-real-v170.json), [ảnh giao diện](images/video-translator-v170.png).

Windows 1.7.0 đã được cài đè và kiểm tra EXE cài đặt có cùng SHA-256 với bản giao. API key được bảo vệ, certificate, thiết bị ghép cặp, settings, usage và CLI runtime cũ đều được giữ; nâng cấp không gọi AI. Windows smoke test của bản đóng gói cuối chạy đạt. [Kết quả nâng cấp](results/windows-upgrade-v170.json).

## Ảnh hưởng Live đã đo trên BlueStacks

Đo bằng Android/Windows **1.7.0**, cùng ROI đã lưu `[233,496,809,538]`: tổng cộng **ba lượt × 20 cue**, đổi mỗi 800 ms và lặp ba câu công khai đã được Gemini dịch trước. Độ trễ là từ callback vẽ câu nguồn đến callback vẽ overlay trên cùng đồng hồ Android, chưa phải thời điểm GPU trình bày. Đây là phép thử **cache playback**, không phải inference Gemini mới.

| Điều kiện | Cue vẽ đúng / dự kiến | Vẽ câu cũ/sai | P50 | P90 | P95 | P99 |
|---|---:|---:|---:|---:|---:|---:|
| Không có tác vụ video, lần đầu sau kích hoạt Live | 18/20 | 1 | 254,00 ms | 509,20 ms | 598,35 ms | 767,67 ms |
| Video đang export, sau đó Live khiến video chờ | 20/20 | 0 | 168,00 ms | 275,20 ms | 290,10 ms | 352,42 ms |
| Follow-up không video, cache đã nóng | 20/20 | 0 | 244,50 ms | 511,70 ms | 618,10 ms | 771,62 ms |

Percentile dòng đầu tính trên **18 cue có bản dịch khớp**, hai cue mất được báo riêng; không coi đó là 100% dịch đúng. Cả ba lượt playback đều phát sinh **0 AI call**, nhưng chuẩn bị dự án video để thử export đã dùng một batch Gemini. Encoder đang `exporting` có CPU tích lũy 0,328125 giây; sau khi Live bắt đầu, tác vụ chuyển `waiting_live` và CPU encoder tăng **0,0 giây trong một giây quan sát**, xác nhận tạm dừng process thực.

Số đo xác minh được gate ưu tiên và cache render trong một fixture. Không thể suy việc P95 ở lượt thứ hai thấp hơn là do bật video giúp tăng tốc: trạng thái khởi động/cache giữa các lượt khác nhau và cỡ mẫu nhỏ. Lượt baseline lạnh mất hai cue và vẽ một câu cũ, nên [báo cáo so sánh](results/video-live-priority-v170.json) **giữ `passed=false`**, không bỏ lượt lỗi; `priority_suspension_verified=true` chỉ xác nhận gate/process. [Baseline đầy đủ](results/video-live-baseline-v170.json), [video chờ Live](results/video-live-concurrent-v170.json), [follow-up cache nóng](results/video-live-warm-followup-v170.json). Chưa đo inference Gemini mới, Redmi thật hoặc phim liên tục trong ca này; không tuyên bố đã đạt một giây cho mọi câu thoại.

## Các lỗi đã tìm và giới hạn còn lại

- Metadata có trường tính toán dành cho UI không thuộc constructor `VideoMetadata`; truyền nguyên dict vào Speech làm hỏng luồng. Đã lọc đúng dataclass fields và thêm regression.
- faster-whisper 1.2.1 gọi tham số decoder không tương thích PyAV 19. Đã dùng PCM 16 kHz mà FFmpeg giải mã sẵn, đọc qua `wave`/NumPy để tránh giải mã lặp. Luồng nguồn đã chạy lại thành công.
- Worker trong EXE lỗi `ImportError` vì PyInstaller NumPy hook không thu thập `numpy._core._exceptions`. Đã thêm hidden import vào `scripts/build_windows.py`; EXE cuối đã nhận đúng mẫu tiếng Anh công khai và hoàn tất Speech → Gemini → timeline.
- OCR Hàn cho chữ khác nguồn dù confidence khoảng 0,61. Confidence không là bằng chứng chính xác; sản phẩm không gửi đường OCR này cho Gemini.
- Cancel ngay trước khi coroutine video bắt đầu có thể để active owner kẹt; đã chốt trạng thái rõ ràng và có regression. Quota video nay được đếm theo provider call thực, gồm call lỗi, thay vì suy từ số batch/cache hit.
- Hai yêu cầu Start/Start hoặc Start/Export có thể cùng vượt kiểm tra rảnh trong lúc chờ ghi checkpoint, tạo hai worker. Admission lock nay bảo vệ kiểm tra và nhận tác vụ. Hai regression tái hiện lỗi khi bỏ lock và chạy đạt sau sửa; dịch vụ đã đóng cũng từ chối worker mới.
- `use_memory=False` từng tắt cả lưu Draft dù người dùng đã cho phép lưu. Nay quyền lookup và persistence được tách riêng; ba tổ hợp công tắc được kiểm thử để chỉ lưu khi có đủ opt-in, không ghi cache toàn cục của video.
- OCR video hiện dùng ROI cố định do người dùng đặt và lấy mẫu 250 ms. Có reuse khung giống, chưa có scene detection hoặc subtitle region tracking tự động. Các cue ngắn hơn nhịp mẫu có thể bị bỏ; không fuzzy-correct chữ vì dễ đổi tên/phủ định.
- Chưa kết hợp phụ đề với ASR để đối chiếu, chưa forced alignment. Editor sửa cue hiện có, chưa thêm/tách cue hoặc waveform; timestamp chồng lấn bị từ chối.
- Timeline bị giới hạn 20.000 cue, video 24 giờ cho Speech; import giới hạn 20 GiB và cần ít nhất 1 GiB trống. Bản sao nguồn cùng file export tiêu tốn dung lượng; chưa có tự dọn dự án theo tuổi. File export cũ không còn liên kết được giữ tới khi xóa dự án.
- H.264/yuv420p cần kích thước chẵn; ứng dụng báo lỗi với nguồn kích thước lẻ thay vì âm thầm resize. Tệp ảnh subtitle stream chưa có đường OCR riêng. Bộ thử hiện chưa bao phủ VFR thực tế.
- Batch giới hạn ký tự không phải giới hạn token chính xác. Câu quá dài, quota hoặc model lỗi có thể làm batch thất bại; checkpoint trước đó được giữ. Không có dự toán chi phí bằng tiền hoặc đảm bảo quota Google.
- Mẫu Speech → Gemini cho thấy chất lượng vẫn cần kiểm tra: “Please close the window” được dịch thành “Vợ/chồng hãy đóng cửa sổ lại”, thêm xưng hô không có trong nguồn. Bản này vẫn Draft; người dùng phải sửa/xác nhận trước khi dùng làm TM đã kiểm chứng. Phép thử nhận đủ câu không đồng nghĩa đã đạt chất lượng dịch tự nhiên.
- Baseline lạnh của phép thử cache BlueStacks mất 2/20 cue và ghi nhận một lượt vẽ câu trước; nguyên nhân chi tiết chưa được sửa/chứng minh trong đợt bổ sung video. Video đã nhường tài nguyên đúng trong phép thử, nhưng chưa đủ dữ liệu để bảo đảm P95 không tăng ở mọi điều kiện. Chưa nghiệm thu Redmi thật, latency Gemini câu mới hoặc chất lượng trên hội thoại phim liên tục; mục tiêu một giây vẫn chưa được chứng minh.

## File chính và tái lập

| Nhóm | File thêm/sửa |
|---|---|
| Media/OCR/ASR | `windows-server/app/video_media.py`, `video_ocr.py`, `video_speech.py` |
| Tác vụ và API | `windows-server/app/video_jobs.py`, `video_routes.py`, `server.py`, `launcher.py` |
| TM và ưu tiên | `windows-server/app/translation_memory.py`, `resources.py`, `manager.py`, `storage.py`, `models.py`, `subtitle_jobs.py` |
| Giao diện | `windows-server/static/index.html`, `app.js`, `style.css` |
| Android | `android-app/app/src/main/java/com/gemini/ocr/capture/ScreenCaptureService.kt`, `android-app/app/src/test/java/com/gemini/ocr/capture/CapturePriorityStateTest.kt`, `android-app/app/build.gradle.kts` |
| Build/runtime | `windows-server/requirements.txt`, `scripts/setup_video_tools.py`, `setup_video_models.py`, `build_windows.py`, version/installer/smoke |
| Kiểm thử | `tests/test_video_media.py`, `test_video_speech.py`, `test_video_jobs.py`, `test_video_routes.py`, `test_video_resource_priority.py`, `test_video_metering.py`, `test_translation_memory.py`, `test_capture_priority_state.py`; `research/benchmark_video_long.py` |

Runtime và model nằm riêng trong `.tools/video-runtime`, không sửa PATH hoặc cài model vào thư mục hệ thống. Build EXE mang theo FFmpeg, OCR và tiny ASR; mã nguồn ZIP không kèm model/runtime, dữ liệu riêng hoặc API key.

Tái lập từ mã nguồn sau khi cài dependencies của dự án:

```powershell
python -m pip install -r windows-server/requirements.txt
python scripts/setup_video_tools.py
python scripts/setup_video_models.py --speech
python windows-server/launcher.py
python -m pytest tests/test_video_media.py tests/test_video_speech.py tests/test_video_jobs.py tests/test_video_routes.py tests/test_video_resource_priority.py tests/test_video_metering.py tests/test_translation_memory.py tests/test_capture_priority_state.py
```

Các script setup tải artifact/model công khai khi người dùng chạy rõ ràng, kiểm tra hash FFmpeg/model OCR và lưu manifest của model ASR. Không tự tải trong lúc nhập hay dịch video. Bài pytest dùng fake provider cho orchestration; các ca FFmpeg/RapidOCR thật cần portable runtime đã có. Các helper thử video mẫu trong `build/` là tệp kiểm thử cục bộ, không phải sản phẩm hoặc bằng chứng phim người dùng đã được xử lý.

## Nguồn runtime và giấy phép

| Thành phần | Nguồn / giấy phép của bản dùng |
|---|---|
| FFmpeg/ffprobe 9.0.2 essentials | [GyanD/codexffmpeg release](https://github.com/GyanD/codexffmpeg/releases/tag/9.0.2), [trang tải FFmpeg](https://ffmpeg.org/download.html). Gói tải kèm văn bản GPL v3, được giữ trong `video-runtime/ffmpeg/licenses` của EXE. [Mã nguồn FFmpeg](https://github.com/FFmpeg/FFmpeg), [GPL v3](https://github.com/FFmpeg/FFmpeg/blob/master/COPYING.GPLv3). |
| RapidOCR / mô hình PaddleOCR | [RapidOCR](https://github.com/RapidAI/RapidOCR), [Apache 2.0](https://github.com/RapidAI/RapidOCR/blob/main/LICENSE); [PaddleOCR Apache 2.0](https://github.com/PaddlePaddle/PaddleOCR/blob/main/LICENSE). Model Nhật/Hàn lấy từ URL/hash trong manifest chính thức RapidOCR, lưu manifest cục bộ để truy vết. |
| faster-whisper 1.2.1 | [SYSTRAN/faster-whisper](https://github.com/SYSTRAN/faster-whisper), [MIT](https://github.com/SYSTRAN/faster-whisper/blob/master/LICENSE). |
| tiny ASR weights | [Systran/faster-whisper-tiny](https://huggingface.co/Systran/faster-whisper-tiny), model card ghi MIT; revision đã đo `d90ca5fe260221311c53c58e660288d3deb8d356`. Model dùng để nhận dạng lời nói, không thay Gemini dịch. |
| CTranslate2 / ONNX Runtime | [CTranslate2 MIT](https://github.com/OpenNMT/CTranslate2/blob/master/LICENSE), [ONNX Runtime MIT](https://github.com/microsoft/onnxruntime/blob/main/LICENSE). Chạy CPU trong tính năng này. |

Font preview/test lấy từ font Windows có trên máy; không đóng gói lại các font Windows trong mã nguồn hay bộ cài.
