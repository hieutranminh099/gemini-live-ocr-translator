# Dịch hàng loạt theo giai đoạn — bàn giao kỹ thuật

## Bản nền và phạm vi

Nhánh `feature/batch-staged-pipeline` chạy trong worktree riêng. Git nền là `529635b` (checkout cũ 1.7.1); **bản nền chức năng là mã bàn giao 1.7.9 + Ocean Phase 1 + Ocean Phase 2**, không phải nội dung thuần của commit đó. Ba gói nguồn đã được khôi phục theo thứ tự, SHA-256 nằm trong `build/staged-evidence/baseline-archives.json`; `baseline-source-hashes.json` dùng để phân biệt bản nền với patch lần này. Không áp toàn bộ diff Git vào một checkout khác rồi coi là patch nhỏ.

Thay đổi tập trung ở Android `batch/`, worker/video model và client video. Tái sử dụng `VideoJobs`, `VideoStore`, `BatchStore`, bộ OCR Trung cục bộ, exact cache riêng cho batch, scheduler API, renderer/export và Kho phim hiện có. Dịch trực tiếp, Windows Companion, đăng nhập Google và giao thức pairing không được thay thế. Không thay API key, tài khoản hoặc cấu hình toàn cục.

Bản vá 1.8.1 nằm ở nhánh `codex/batch-decode-recovery`, dựa trên gói nguồn 1.8.0 đã đối chiếu hash, không sao chép thay đổi đang làm của phiên khác. Phạm vi bản vá là đọc video cục bộ, phục hồi checkpoint và thông báo lỗi nguồn.

Bản 1.8.3 ở `codex/hybrid-full-dialogue` tiếp tục từ gói nguồn 1.8.2 đã xác minh SHA-256, giữ bản sửa timing/renderer. Luồng HYBRID mới dưới đây áp dụng cho cả video riêng và hàng loạt; không thay OCR Engine, model Gemini, cấu hình Live hoặc dữ liệu đã duyệt.

Bản 1.8.4 ở `codex/hybrid-adaptive-chunks` tiếp tục từ gói nguồn 1.8.3 đã xác minh SHA-256. Thay đổi tập trung ở cách chia yêu cầu HYBRID khi toàn bộ hội thoại không vừa một lượt; vẫn giữ hàng rào hoàn tất OCR trước dịch và các bản sửa timing/renderer trước đó.

## Trình tự và mốc lưu

Tích hợp trên source **1.8.12 / code 37**, ngày 10/11/2026: `BatchJobs.execute()` gọi `BatchEpisodePipeline`, điều phối **theo từng tập**. Một lane trích/clean và các lane dịch/hiệu đính/hoàn thiện dùng lại `VideoJobs`; không còn vòng EXTRACT của cả danh sách trước TRANSLATE. Tập đầu được giao dịch trước khi trích tập tiếp theo. Khi tài nguyên cho phép, AI dịch tập trước trong lúc extractor đọc tập sau. Channel chỉ chuyển project ID; số ID chờ hữu hạn. Lỗi nguồn riêng một tập được ghi và không chặn các tập khác; lỗi xác thực/quota/dịch vụ chung vẫn giữ cơ chế dừng và retry hiện có.

| Giai đoạn | Đường xử lý và dữ liệu bền vững |
|---|---|
| `EXTRACT` | Đọc phụ đề văn bản hoặc `VideoDecoder` OCR cục bộ, độc lập `translationMode` và khóa Gemini. Giữ ID, timestamp, `rawZh`, scan/carry; lưu `source.zh.srt`. |
| `CLEAN` | Chuẩn hóa khoảng trắng, giữ nhiều dòng, không gộp câu gần giống. Lưu riêng `correctedZh`, cờ bất thường và trạng thái ASR. |
| `TRANSLATE` | Tái sử dụng exact memory/cache có phạm vi. HYBRID dịch hội thoại theo các phần lớn nhất vừa giới hạn, chỉ yêu cầu ID chưa xong; GEMINI giữ nhóm có giới hạn. Chỉ ghi câu hợp lệ và checkpoint ngay. |
| `POLISH` | HYBRID đã dịch với toàn bộ hội thoại nên hoàn thành bước này mà không tự gửi lượt thứ hai. GEMINI giữ lượt riêng cho câu được đánh dấu/quá dài. Giữ `draftVi`, ghi `polishedVi`; không đổi ID/timestamp. OFFLINE chỉ đánh dấu cần kiểm tra. |
| `FINISH` | Kiểm tra timeline và các câu đã dịch, tạo SRT/ASS bằng exporter cũ. Render MP4 vẫn theo điều kiện duyệt cũ; đưa vào Kho phim cần xác nhận riêng. |

Trong **Kiểm tra và xuất**, chọn **Duyệt toàn bộ và lưu Kho phim** để chấp nhận các bản dịch hiện có và lưu từng tập cùng phụ đề Việt. Nút yêu cầu xác nhận và chỉ lưu khi mọi video đã xử lý xong, mọi câu có bản dịch. Trạng thái duyệt hàng loạt được ghi trong bản lưu Kho phim. Dự án chỉnh sửa và tùy chọn ghép MP4 giữ nguyên.

Trong **Thiết lập phụ đề → Dịch đồng thời**, giữ các cấu hình **Tuần tự**, **8 GB**, **16 GB** và mặc định dữ liệu cũ. Trần worker theo cấu hình và tài nguyên được giữ trong lượt chạy; admission đọc lại RAM định kỳ, giảm ngay và chỉ tăng sau khi tài nguyên ổn định hai giây. Một API worker vẫn có thể chạy chồng với một extractor nếu thiết bị đủ tài nguyên. Economy giữ một text worker. Nhóm câu trong từng tập vẫn chạy theo thứ tự ngữ cảnh và lưu checkpoint trước nhóm tiếp theo; các tập khác nhau có thể dịch đồng thời. Hạn mức provider/tài khoản và cooldown cũ vẫn có hiệu lực.

`VideoJobs.runStageForBatch()` giữ lane media duy nhất. `runParallelStageForBatch()` nhận `TRANSLATE`/`POLISH`/`FINISH` ở lane riêng; chỉ pipeline có reservation hợp lệ mới opt-in chạy cùng media của project khác. Hai lane không được cùng project ID. Cancellation chờ mọi lane lưu và giải phóng trước khi thả reservation. Stage đã hoàn thành được đối chiếu từ AtomicFile con và bỏ qua; timeline cũ đã được bảo vệ không bị viết lại chỉ để thêm metadata. Render/ghép MP4 và cổng duyệt/xác nhận Kho phim giữ nguyên.

EXTRACT staged dùng `processVideoDraft()` để giữ chữ Han thực tế có độ tin cậy thấp/không biết thành bản nháp cần kiểm tra; không hạ ngưỡng hay thay thân hàm OCR của Live. `VideoDraftTimeline` chỉ gộp các quan sát liền nhau giống hoàn toàn. Quan sát chưa được xác nhận vẫn được lưu với `OCR_UNSTABLE_SINGLE_FRAME`, thay vì bỏ cả đoạn nhiều dòng do từng ký tự OCR dao động. Bằng chứng đơn được giới hạn tới mẫu tiếp theo/200 ms sau lần nhìn thấy cuối, không kéo qua khoảng không biết; khoảng bất định được gắn cờ. `rawZh` giữ xuống dòng từ OCR, confidence là giá trị native thực tế (nullable), không lấy số demo. Luồng video cũ giữ quorum ba. Bộ giải mã sao chép ROI sang bộ đệm luma hữu hạn rồi trả output cho codec trước khi chờ OCR hoặc ghi checkpoint. Kiểm tra đuôi track ngăn đánh dấu trích xuất hoàn tất khi decode kết thúc sớm.

`VideoStageCheckpoint` lưu `status`, `lastCheckpoint`, `attempts`, `errorCode`, `nextRetryAt`; trạng thái gồm `PENDING`, `RUNNING`, `RETRY_WAIT`, `COMPLETE`, `PAUSED`, `NEEDS_REVIEW`. Mốc trích xuất là `scanMs`; mốc dịch/hiệu đính là số câu đã có kết quả. Timeline và checkpoint nằm trong **cùng một AtomicFile** của dự án, nên không thể đánh dấu hoàn tất trước dữ liệu. Batch giữ `currentStage`, thứ tự tập và trạng thái từng item trong AtomicFile riêng; khi tiếp tục, worker đối chiếu lại dự án con thay vì tin trạng thái batch cũ.

`VideoPreview` báo quyền ưu tiên playback qua listener của player hiện có, kể cả lúc buffering. Pipeline không nhận trích xuất nặng mới khi đang xem; extractor/export đang chạy nhường tại gate có thể hủy. Request text đang hoàn thành vẫn được lưu. Admission API giảm về một request; sau khi player dừng, giữ hai giây trước khi phục hồi để tránh dao động do seek/pause. Live tiếp tục có gate ưu tiên riêng.

Foreground service dùng `START_REDELIVER_INTENT`: hệ thống có thể giao lại intent sau process death. `execute()` chỉ phục hồi batch có trạng thái bền vững RUNNING/RETRY_WAIT, lấy lại reservation và giữ deadline/attempt đã lưu. Intent trùng không tạo worker thứ hai; intent cũ không khởi động lại batch PAUSED/CANCELLED/REVIEW. Android force-stop không được vượt qua; người dùng mở lại và Tiếp tục từ checkpoint. Phản hồi nhận được nhưng chưa ghi bền vững vẫn là pending và có thể phải gửi lại; không bảo đảm exactly-once qua khoảng mất thông tin này.

Các kiểm thử đồng thời nằm tại `BatchParallelismTest`, `BatchParallelExecutionTest` và `VideoApiRecoveryTest`. Chúng dùng dữ liệu/phản hồi tổng hợp để kiểm giới hạn luồng, ngữ cảnh riêng từng video, lưu tiến độ khi hủy, thời gian chờ chung và cổng lỗi xác thực. Chưa đo mức tăng tốc trên điện thoại thực.

## Ba khóa Gemini cho video

Mở **Tùy chỉnh → Kết nối Gemini → 3 khóa Gemini cho video**. Khóa chính tiếp tục dùng cho dịch trực tiếp. Hai khóa bổ sung được lưu mã hóa bằng Android Keystore, với nút Lưu/Xóa riêng. Dữ liệu cũ mặc định **Chỉ khóa chính**.

- **Chỉ khóa chính** dùng đường gửi và phục hồi hiện có.
- **Dự phòng** ưu tiên khóa chính, chờ nếu khóa chính đang bận, chuyển khóa khi lỗi hoặc đang trong thời gian chờ.
- **Dùng đồng thời** chia yêu cầu video qua tối đa ba khóa khác nhau đang sẵn sàng. Kết hợp với Dịch đồng thời 8 GB/16 GB để xử lý nhiều video. Mỗi khóa có tối đa một yêu cầu mạng đang thực hiện.

Dịch chữ, hiệu đính, OCR Gemini và gợi ý thứ tự qua API dùng cùng bộ chọn khóa. Tối đa ba lượt gửi thực tế cho một yêu cầu logic; callback tiến độ được gọi trước mỗi lượt gửi. Hạn mức cục bộ, thời gian chờ và lỗi của từng khóa được lưu bằng định danh SHA-256, không lưu khóa trong checkpoint, cache dịch hoặc thông báo lỗi. Khóa trùng nguyên văn chỉ nhận một suất xử lý. Khóa chính dùng chung bộ giới hạn lượt gọi với Live; khóa bổ sung có bộ giới hạn riêng. Các khóa cùng dự án vẫn chia sẻ hạn mức phía Google theo [tài liệu Gemini](https://ai.google.dev/gemini-api/docs/rate-limits).

Lỗi xác thực giữ khóa chờ 60 giây; lỗi giới hạn lượt gọi chờ ít nhất 61 giây hoặc Retry-After dài hơn. Hết hạn mức ngày giữ khóa chờ 24 giờ hoặc deadline dài hơn do dịch vụ cung cấp. Khi còn khóa sẵn sàng, một khóa bị chờ không dừng các khóa còn lại. Khi mọi khóa đều chưa dùng được, app lưu deadline thích hợp và giữ tiến độ để tiếp tục. Đổi model/backend không thuộc việc chuyển khóa.

Dịch tiết kiệm gắn fingerprint và vị trí khóa vào checkpoint trước khi nộp tác vụ. Chế độ đồng thời chọn khóa cho từng video, sau đó dùng đúng khóa đó để đọc hoặc hủy tác vụ đến khi hoàn tất. Tác vụ cũ thiếu fingerprint tiếp tục dùng khóa chính. Nếu khóa đã nộp bị xóa hoặc thay, app yêu cầu khôi phục khóa trước khi đọc/hủy, giữ mốc SUBMITTING và mã tác vụ để tránh gửi trùng.

Kiểm thử ngoại tuyến nằm tại `GeminiKeySettingsTest`, `VideoGeminiAccountsTest` và `VideoEconomyBatchTest`, cùng các regression video/batch. Chúng dùng khóa và phản hồi tổng hợp; không xác minh tài khoản, quota hoặc tốc độ dịch thực tế.

Kiểm thử phục hồi phản hồi thiếu nằm tại `VideoPartialResponseRecoveryTest`. Các ca dùng worker/checkpoint thật với phản hồi tổng hợp, gồm 740 câu thiếu 205, tiếp tục dữ liệu cũ, MAX_TOKENS, tạm dừng và giới hạn phản hồi rỗng.

Sau một phản hồi phục hồi đầy đủ, giới hạn ID tăng gấp đôi đến tối đa 64; kết quả và giới hạn mới được lưu trong cùng checkpoint. Một lần chia xuống một câu không giữ toàn bộ phần phim còn lại ở mức một câu mỗi yêu cầu. Phản hồi thiếu tiếp theo lại giảm nhóm; ba phản hồi rỗng liên tiếp vẫn dừng để kiểm tra. Ca hồi quy khởi động ở mức một câu kiểm tra phục hồi lên 64 và không gửi lại câu đã hoàn thành/đã duyệt.

Sau khi hết các lượt thử của yêu cầu hiện tại, hàng đợi chờ 1, 2 rồi 4 phút cho tối đa ba lượt tự tiếp tục. Deadline dài hơn do dịch vụ cung cấp vẫn có hiệu lực; chờ quá năm phút yêu cầu người dùng tiếp tục sau. Các lượt không tự vượt lỗi xác thực, quota ngày hoặc lỗi cần kiểm tra nội dung. Điều này giảm việc gọi dồn khi Gemini tiếp tục trả 503, không bảo đảm dịch vụ hết quá tải.

## Tương thích dữ liệu và chỉnh sửa

- Giữ schema dự án/batch hiện tại là `1`, bổ sung trường có mặc định. Dự án cũ thiếu `pipeline` được đọc thành checkpoint phiên bản `0`; checkpoint mới dùng phiên bản `1`. Không xóa hoặc chuyển dữ liệu người dùng sang kho mới.
- `VideoCue` bổ sung `rawZh`, `correctedZh`, `draftVi`, `polishedVi`, `ocrConfidence` nullable, `qualityFlags`, `version`. ID câu chỉ có phạm vi dự án; cặp `(videoId, cue.id)` là định danh đầy đủ. Không suy mapping bằng vị trí response.
- Approved và bản người dùng sửa được bảo vệ khỏi AI. Khi sửa nguồn, giữ OCR gốc và vô hiệu phần cần xử lý sau đó; sửa bản dịch giữ bản nháp để so sánh. Thay nguồn/ROI làm mất hiệu lực checkpoint trích xuất; thay cấu hình dịch làm mất hiệu lực từ dịch trở đi.
- Giữ giới hạn nội dung cũ cho các trường cũ và giới hạn JSON tổng 32 MiB. Không thay đổi namespace cache hoặc dùng fuzzy match để nhập các câu gần giống.
- ROI dùng tọa độ chuẩn hóa của khung video; metadata/decoder hiện có xử lý hướng xoay. `sharedRoiRevision` ở batch và từng dự án cho phép hoàn tất việc áp ROI sau crash. Chỉ áp revision chưa thực hiện; chỉnh ROI riêng sau đó vẫn được giữ khi chạy lại batch. Đổi ROI có nội dung đã trích xuất cần xác nhận trước.
- Hồ sơ phim nhập thủ công được lưu theo batch, truyền vào `filmContext` của nhóm dịch/hiệu đính và tham gia khóa exact cache. `contextRevision` khôi phục cập nhật dở giữa nhiều video, chỉ mở lại hiệu đính và giữ công việc đã duyệt. Không tự tạo tên/quan hệ nhân vật thiếu bằng chứng.
- Dự án ghép cũ chỉ được chấp nhận fingerprint tương thích khi toàn bộ đầu vào vẫn là pipeline phiên bản 0 và các kiểm tra nguồn/style/quyền sở hữu cũ đều đạt. Bỏ qua stage hoàn tất trước khi xét trạng thái PAUSED của stage phía sau, tránh tiếp tục rồi dừng lại ở EXTRACT.

## API, parser và phục hồi

### Bộ giải mã video cục bộ từ 1.8.1

`VideoCodecBoundary` chỉ bọc thao tác native codec/extractor, trả mã `FRAME_CODEC_STATE`/`FRAME_CODEC_ERROR`, tên thao tác, mốc đã đọc và diagnostic/code đã lọc. Không sao chép URI, chữ trong phim hoặc raw exception message. Cancellation, lỗi lưu checkpoint, lỗi ROI và lỗi OCR không được nhận nhầm thành lỗi codec để tự chạy lại.

Watchdog 30 giây chỉ đếm thời gian bộ giải mã không tiến triển; chờ Live và xử lý ROI đã sở hữu không tính vào thời gian này. Khi lỗi đọc có thể phục hồi, staged EXTRACT lưu ngay cue đã đóng và carry của cue đang mở, giải phóng toàn bộ tài nguyên rồi tạo reader mới từ `scanMs` mới nhất. Tối đa hai lần mở lại trong một lượt; chờ 250/500 ms có thể hủy. Khi hết lượt, giữ `NEEDS_REVIEW` và phần đã đọc. Checkpoint lỗi luôn nhỏ hơn duration; chỉ EOS đã kiểm chứng mới đánh dấu đọc hết. Cleanup phụ không thay thế lỗi gốc, cancellation hoặc kết quả đã hoàn tất.

`friendlyError()` nhận mã lỗi nguồn trước các heuristic HTTP/TIMEOUT, kể cả thông báo cũ đã lưu. Vì vậy `FRAME_DECODE_TIMEOUT` không còn bị báo mất mạng; lỗi codec có hướng dẫn tiếp tục đọc video. `IllegalStateException` không xác định nguồn vẫn không được tự suy thành codec.

Các phép thử lỗi native trên video công khai dùng fault injection có ghi rõ, không chứng minh đã tái hiện chính xác lỗi driver của video cá nhân trong ảnh. Quy tắc recoverable/transient tham khảo [Android MediaCodec.CodecException](https://developer.android.com/reference/android/media/MediaCodec.CodecException).

`VideoApiRecovery` dùng hàng đợi tuần tự mặc định; dự án chọn dịch đồng thời dùng semaphore riêng có giới hạn cho lượt pipeline. Các worker dùng chung thời gian chờ và lỗi xác thực/hết quota, đồng thời vẫn qua limiter hiện có và kiểm tra Live/cancellation trước mỗi lần gửi. Lỗi tạm thời có tối đa ba lần gửi, backoff 1/2 giây cộng jitter; `Retry-After` hợp lệ được chờ có thể hủy, giới hạn 24 giờ. Observer lưu `RETRY_WAIT`/deadline trước khi chờ, xóa deadline khi gửi lại; mở lại ứng dụng tiếp tục chờ deadline đã lưu. Route staged không đặt timeout 45 giây quanh cả thời gian đợi scheduler; timeout transport từng yêu cầu vẫn áp dụng.

| Phân loại | Hành vi |
|---|---|
| Frame/ROI/decode không hợp lệ | Chặn trước API; giữ checkpoint, báo lỗi nguồn để kiểm tra. Không tạo frame/phụ đề demo. |
| JSON/schema/ID thiếu hoặc sai | Xác thực trước ghi; giữ câu hợp lệ của dịch, không ghi đè nháp bằng nhóm hiệu đính thiếu ID; chia nhỏ có giới hạn hoặc đánh dấu nhóm cần kiểm tra. |
| Mất mạng, timeout, 408/500/502/503/504 | Retry có giới hạn; hết lượt thì lưu lỗi và tiếp tục phần độc lập khi an toàn. |
| 429 tốc độ/RPM | Backoff/`Retry-After`, lưu deadline; không gửi vô hạn. |
| Quota ngày/billing thực sự hết | `API_QUOTA_EXHAUSTED`, dừng hàng đợi API; không tự retry như lỗi mạng. |
| 401/403 | `API_AUTH`, dừng hàng đợi API để người dùng kiểm tra quyền/khóa. |

`VISION_INVALID_RESPONSE` trước đây gộp nhiều trường hợp parser/schema/mapping. Client mới phân biệt phản hồi rỗng, bị chặn, bị cắt và JSON/mapping lỗi; chỉ tháo fence Markdown hoàn chỉnh, không lấy JSON tùy tiện từ văn xuôi hay tự suy ID bị thiếu. Khi chia nhóm Vision, callback ghi timeline/carry của nửa đã hợp lệ trước khi gửi nửa tiếp theo; lỗi nửa sau không làm gửi lại nửa đã lưu. Lỗi ghi checkpoint không được nhận nhầm là lỗi provider để chia nhóm/gọi lại. `API_KEY_INVALID` có `google.rpc.ErrorInfo` trong HTTP 400 được phân loại xác thực; không suy từ văn xuôi bất kỳ. Route batch staged ưu tiên OCR cục bộ nên không cần Gemini Vision để trích xuất. Chẩn đoán nguồn từ mã/fixture không đồng nghĩa đã tái hiện đúng request lỗi của người dùng; không đọc log cá nhân hoặc gọi API thật để chứng minh thay cho phép thử được báo cáo.

## UI và giới hạn thực tế

Batch dùng bốn màn: Chọn phim → Thiết lập phụ đề → Tiến độ → Kiểm tra và xuất, dùng Ocean components sẵn có. ROI mẫu có thể áp cho các tập; kiểm tra cục bộ ba thời điểm/video là tín hiệu kiểm tra vùng, không phải bằng chứng toàn phim có OCR đúng. Tên tập có marker `EP`, `E`, `Tập`, `第十二集` có thể nhận diện; số đầu filename chỉ là gợi ý thứ tự không chắc chắn, không tự thành lệnh ghép tập.

ASR hiện chỉ có interface `VideoChineseAsr` và provider thật sự không khả dụng `UnavailableChineseAsr`. Không nhúng model mới, không giả transcript, không trích audio rồi tuyên bố đã đối chiếu. `ASR_UNAVAILABLE` là thông tin chất lượng; không khóa toàn bộ pipeline. Làm sạch hiện tự sửa khoảng trắng và đánh dấu nghi vấn, chưa chứng minh tự khôi phục đúng mọi chữ OCR sai hoặc phát hiện mọi đoạn thoại bị bỏ sót. Hồ sơ nhân vật/ngữ cảnh là dữ liệu người dùng cung cấp, chưa có bước tự động xác minh nhân vật bằng AI.

Không công bố tăng tốc, chất lượng dịch, độ chính xác timestamp hoặc tỷ lệ lỗi API khi chưa có số đo thực trước/sau. Test mock/provider giả chỉ chứng minh điều phối và hợp đồng. Test video công khai thật chỉ chứng minh những bước thực chạy được ghi rõ; không dùng kết quả đó để suy rằng Gemini, ASR hay render MP4 đã được kiểm chứng.

## Lệnh kiểm chứng và bàn giao

Chạy từ đúng worktree với Java/SDK/Gradle và cache đã thiết lập theo hướng dẫn kiểm thử hiện có. Ví dụ task Gradle (offline khi dependency đã sẵn):

```powershell
gradle --offline -p android-app :app:testDebugUnitTest --tests com.gemini.ocr.video.VideoStagedPipelineTest --tests com.gemini.ocr.video.VideoApiRecoveryTest --tests com.gemini.ocr.batch.BatchStagedUiTest
gradle --offline -p android-app :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
git -c core.whitespace=cr-at-eol diff --check
```

Regression liên quan: `VideoJobsTest`, `VideoSmartBatchWorkerTest`, `BatchOrchestrationTest`, `BatchVideoCacheIntegrationTest`, `VideoOcrStabilizerTest`, `VideoExportCardTest`, `MainActivityTest` và các test Drive hiện có. Các assertion trạng thái/đường lỗi cũ phải được đánh giá theo hợp đồng staged mới, không bỏ test hoặc che lỗi chỉ để có màu xanh.

`StagedPublicVideoNativeTest` là test opt-in trong ứng dụng kiểm thử riêng `com.gemini.ocr.stagedtest`, yêu cầu video công khai đúng SHA-256; chỉ chạy khi đã chuẩn bị tệp và cài đúng APK kiểm thử. Nó cấm network, kiểm decoder/OCR Trung thật, làm sạch, mở lại checkpoint và bố cục bốn màn. Không chạy test này trên dữ liệu/app chính của người dùng.

`DecodeRecoveryNativeTest` dùng app riêng `com.gemini.ocr.decodefix`, opt-in `executeDecodeRecovery=true`, cùng fixture công khai đã ghim hash. Chèn lỗi native rỗng và timeout tại ranh giới thao tác codec để kiểm reader thật được mở lại, prefix/ID/timestamp/carry được giữ và lỗi kéo dài dừng hữu hạn; tiếp tục bằng BatchJobs thực tế. Không dùng test này để tuyên bố đã tái hiện video riêng hoặc dịch Gemini thật.

## Đồng bộ phụ đề video (1.8.2)

`VideoDraftTimeline` chỉ nối các quan sát có cùng NFKC/khoảng trắng trong cùng lần xuất hiện đã biết; không dùng fuzzy, bỏ dấu câu/số/phủ định hoặc nối qua quan sát unknown/blank. Ranh giới dùng PTS quan sát, không cắt cue theo giới hạn 200/250 ms khi đã có quan sát kế tiếp. Sau EOS đã kiểm tra coverage, decoder nhận dạng ROI khung cuối thực tế rồi gọi `finishAtValidatedEnd`; không kéo dài đuôi thiếu bằng chứng. Cue cũ không bị tự sửa hoặc gộp theo suy đoán.

`VideoTimeline.validate` sắp xếp bản chiếu, yêu cầu `0 <= start < end <= duration`, ID duy nhất và không chồng lấn. Overlap bị báo lỗi để kiểm tra, không tự cắt/xóa hội thoại. Preview, SRT, ASS và MP4 dùng cùng timeline đã kiểm tra. `VideoSubtitleClock` đọc `Player.currentPosition` theo nhịp hiển thị và sự kiện player; chỉ một callback còn hiệu lực cho mỗi player, dừng khi pause/stop/ended và hủy khi đóng. Seek, phát lại và thay tốc độ không dùng đồng hồ hệ thống để ước lượng timestamp.

`SavedTimelineSubtitleView` chỉ invalidate khi chữ/kiểu/hình học thay đổi. `TimelineBitmapOverlay` giữ bitmap theo chữ hiển thị, cho phép hai ID liền nhau cùng bản dịch dùng chung bitmap; khoảng trống thực sự vẫn xóa và câu lặp ở thời điểm khác vẫn độc lập. Chỉnh cue tương lai không tái tạo hình hiện tại. Đây là thay đổi renderer, không thay nội dung/timing đã lưu để che lỗi OCR.

Kiểm thử liên quan: `VideoSubtitleClockTest`, `VideoPreviewViewTest`, `VideoDraftTimelineTest`, `VideoTimelineTest`, `VideoExporterTest`. `SubtitleJitterNativeTest` chạy opt-in `executeSubtitleJitter=true` trong app riêng `com.gemini.ocr.jittertest`, với SHA-256 fixture qua `fixtureSha256`. Dùng video công khai lặp 65 giây và cue thời gian có kiểm soát để kiểm player thực, seek tiến/lùi, pause/resume, cue 65 ms, cùng chữ khác ID, EOF, phát lại, đóng/mở lại và xuất MP4/SRT. Cue thử không phải transcript của phim, không chứng minh độ chính xác OCR/dịch. Không chạy trên app/dữ liệu chính; không gọi cloud. Bằng chứng thực chạy và giới hạn được ghi trong báo cáo bàn giao.

## Local + Gemini: toàn nguồn trước, tự chia yêu cầu vừa giới hạn (1.8.4)

HYBRID dùng OCR cục bộ cho video riêng lẫn batch, độc lập khóa/hạn mức Gemini và model dịch NMT. `VideoHybridWorkflow` chặn dịch trước khi `scanMs == durationMs`, đồng thời chuyển carry Vision cũ sang draft có đánh dấu bất định, giữ các cue đã đóng/đã duyệt và khoảng nguồn đang mở đã biết. Tệp nguồn `source.zh.srt` được lưu trước khi kiểm tra khóa hoặc dispatch. “100%” là thời lượng quét, không cam kết OCR đúng toàn bộ nội dung.

`VideoFullDialoguePlan` chọn phần liên tiếp lớn nhất trong danh sách ID còn thiếu đã sắp theo thời gian, thay vì từ chối toàn bộ hội thoại quá lớn. Ngân sách đầu ra được ước lượng bảo thủ từ nội dung nguồn, ID và phần dự phòng JSON; đây không phải phép đếm token thực tế của Gemini. Kích thước context được tính theo byte UTF-8. Sau đó callback preflight chỉ dựng và kiểm payload HTTP hoàn chỉnh, gồm profile/từ điển, không gửi mạng; nếu cần, tìm phần tiền tố nhỏ hơn vừa giới hạn. Câu, ID và mốc thời gian gốc không bị cắt, gộp hoặc đổi; mapping phản hồi vẫn theo ID và chỉ số lưu ban đầu.

`targetIds` chỉ gồm ID chưa hoàn tất, không có câu Approved hoặc đã dịch xong. Planner ưu tiên giữ nguyên số câu mục tiêu trước khi bổ sung ngữ cảnh: dùng toàn hội thoại nếu vừa (`contextScope=full`, `contextComplete=true`), nếu không thì thêm các câu gần phần đang dịch trong giới hạn (`contextScope=window`). Câu đã duyệt/đã dịch có thể xuất hiện làm ngữ cảnh nhưng không bị viết lại. Nguồn chỉ xuất hiện một lần trong mảng `dialogue` của mỗi payload; context có thể lặp giữa các phần để giữ mạch hội thoại. `VideoTranslationClient` giữ model hiện có và trả kết quả theo ID. Không cần tải model dịch offline để dùng HYBRID. Chế độ OFFLINE vẫn dịch NMT; GEMINI/Vision và các nhóm nhỏ của nó được giữ.

Khi một phần thành công, worker lưu checkpoint bền vững trước khi chọn phần tiếp theo; tiếp tục đến khi không còn ID cần dịch. Mở lại hoặc bấm Tiếp tục chỉ chọn ID còn thiếu, không gửi lại các phần đã xong. Hội thoại nhỏ vẫn dùng một payload. Cách đóng gói ưu tiên ít lượt nhất trong thứ tự thời gian và các ngân sách kiểm soát này, không cam kết số lượt Gemini thực tế tối ưu theo tokenizer chưa đo. Không tự gửi quality repair hoặc POLISH cho phần đã thành công.

Trong TRANSLATE của batch HYBRID, khi phản hồi thiếu ID hoặc bị cắt đầu ra, worker lưu phần hợp lệ và giới hạn nhóm tiếp theo còn tối đa 64 ID chưa hoàn thành hoặc nửa kích thước nhóm vừa gửi, lấy mức nhỏ hơn. Planner tiếp tục dùng toàn hội thoại hoặc cửa sổ ngữ cảnh vừa ngân sách, gồm các bản dịch đã lưu. `targetCueLimit` nằm trong checkpoint cùng timeline và được giữ qua tạm dừng/mở lại. Dự án cũ có lỗi TEXT_INCOMPLETE_RESPONSE/TEXT_TRUNCATED bắt đầu phần còn thiếu với mức tối đa 64. Ba phản hồi liên tiếp không có câu hợp lệ sẽ dừng bằng TEXT_EMPTY_RESPONSE để kiểm tra nguồn. Lỗi xác thực/quota, JSON/mapping mơ hồ và tác vụ tiết kiệm giữ cổng lỗi riêng. Màn hình video đơn tiếp tục lưu phần hợp lệ rồi chờ người dùng tiếp tục. Nếu JSON đóng đủ nhưng `MAX_TOKENS`, ID cuối **thực sự trả về** vẫn được giữ pending vì nội dung có thể dở, không suy từ thứ tự target. Lượt tiếp tục chỉ yêu cầu ID còn thiếu, với context đầy đủ hoặc cửa sổ vừa giới hạn. Thay đổi ngữ cảnh phim mở lại TRANSLATE của các cue HYBRID chưa duyệt, giữ chữ cũ làm nháp và cập nhật fingerprint cache; Approved không bị viết lại.

Lỗi mạng/dịch vụ transient vẫn dùng retry/backoff hữu hạn của `VideoApiRecovery`; mỗi POST thực tế được đếm. Quota ngày/auth và phản hồi thiếu/sai ID vẫn dừng, không bị coi là lý do tự gửi các phần nhỏ hơn. Quota/cooldown vẫn được tôn trọng; batch được hoàn tất OCR/CLEAN trong thời gian chờ nhưng dừng trước TRANSLATE. Chia vừa dung lượng không tăng quota của tài khoản.

Giới hạn HYBRID riêng cho mỗi phần: context tối đa 1 MiB UTF-8, payload HTTP hoàn chỉnh tối đa 2 MiB UTF-8, response tối đa 2 MiB UTF-8, ngân sách tối đa 65.536 output token, Mỗi lượt HTTP tối đa 240 giây; staged thường tách thời gian chờ quyền gửi/Live/Retry-After khỏi timeout transport. Video đơn và Economy HYBRID giữ deadline worker 300 giây. Không âm thầm cắt câu; một câu hoặc cấu hình/ngữ cảnh bắt buộc riêng lẻ vẫn quá lớn thì dừng trước POST và giữ nguồn để kiểm tra. Đây là giới hạn kiểm soát của luồng, không số đo tốc độ hoặc bảo đảm Gemini luôn trả đủ kết quả. Giới hạn Live/client nhóm nhỏ giữ nguyên.

Economy Batch API vẫn opt-in, dùng cùng payload đã chia cho từng phần; submit/poll của cùng job không phải nhiều lượt dịch khác nhau. Fingerprint/tác vụ đang nộp được giữ; không tự hủy hoặc gửi lại job cũ khác cấu hình. Chỉ xác nhận xong job sau khi checkpoint kết quả đã bền vững trước phần kế tiếp. Cache riêng và Reviewed Memory giữ điều kiện opt-in/phạm vi cũ.

Kiểm thử tập trung: `VideoAdaptiveDialoguePlanTest`, `VideoAdaptiveChunksWorkerTest`, `VideoHybridWorkerTest`, `VideoHybridWorkflowTest`, `VideoFullDialoguePlanTest`, `BatchHybridStartTest` và nhóm video/batch cũ. Ví dụ chạy riêng planner, worker và hợp đồng payload:

```powershell
gradle --offline -p android-app :app:testDebugUnitTest --tests com.gemini.ocr.video.VideoAdaptiveDialoguePlanTest --tests com.gemini.ocr.video.VideoAdaptiveChunksWorkerTest --tests com.gemini.ocr.video.VideoFullDialoguePlanTest --tests com.gemini.ocr.video.VideoHybridWorkerTest
```

Các fixture kiểm phần lớn nhất vừa giới hạn, UTF-8, preflight không gửi mạng, toàn/cửa sổ ngữ cảnh, input lưu không theo thứ tự, 1.512 câu, checkpoint trước phần hai và resume sau lỗi không gửi lại ID đã hoàn tất. Số phần phụ thuộc nội dung: assertion của fixture không phải số đo cho video người dùng.

`AdaptiveChunksNativeTest` opt-in `executeAdaptiveChunks=true`, chỉ cho package riêng `com.gemini.ocr.chunktest`. Fixture có 1.512 cue với nguồn/mốc thời gian có kiểm soát, trong đó một cue Approved; dùng worker, storage, UI và xuất SRT thật, chặn mọi yêu cầu mạng. Nó kiểm checkpoint phần một trước phần hai, bảo toàn ID/timing/nguồn và Approved; phản hồi Việt giả lập, không chạy decoder/OCR hoặc dịch Gemini thật. Ví dụ sau khi chuẩn bị và cài đúng APK kiểm thử riêng:

```powershell
adb shell am instrument -w -e class com.gemini.ocr.video.AdaptiveChunksNativeTest -e executeAdaptiveChunks true com.gemini.ocr.chunktest.test/androidx.test.runner.AndroidJUnitRunner
```

`HybridFullDialogueNativeTest` vẫn opt-in `executeHybridFullDialogue=true`, package riêng `com.gemini.ocr.hybridtest`, fixture công khai ghim SHA-256. Nó dùng decoder/OCR thật để kiểm nguồn đủ trước cloud và resume carry cũ; phản hồi Việt là dữ liệu thử có kiểm soát, không phải bằng chứng Gemini dịch thật hoặc OCR/dịch đúng 100%. Không chạy các phép thử này trên app/dữ liệu chính.

## Xưng hô và văn phong hội thoại HYBRID (1.8.5)

`VideoDialoguePrompt` tạo một system instruction riêng cho route hội thoại đầy đủ, thay vì nối hợp đồng JSON ID lên hướng dẫn trả câu dịch thuần của Live. Nguồn Trung vẫn có thẩm quyền; bản nháp chưa duyệt không trở thành sự thật. Prompt yêu cầu tiếng Việt tự nhiên nhưng trọn nghĩa, xưng hô nhất quán theo cuộc đối thoại/cảnh có căn cứ, không áp một cặp đại từ cho mọi người hoặc tự suy tuổi, giới tính, quan hệ. Các câu đã duyệt và glossary giúp giữ cách gọi đã có; thiếu căn cứ thì diễn đạt trung tính. Đây là yêu cầu với model, không phải bảo đảm đã xác định được người nói từ OCR.

Mã preset hiện có được ánh xạ trong phạm vi video: `manga`/`historical` là gợi ý cổ trang/tu tiên, `document`/`modern` là phim hiện đại, `game` là game/kiếm hiệp. Mã lạ dùng hướng dẫn trung tính; raw profile vẫn là JSON dữ liệu, không chèn vào system instruction. Gợi ý thể loại không được ép lối cổ trang khi nguyên văn cho thấy cảnh hiện đại. Không đổi lựa chọn người dùng, model, temperature, thinking config hoặc thêm lượt hiệu đính tự động.

`promptRevision` tham gia context trước tính ngân sách và exact private-cache key. Request mới không trúng bản cache thuộc prompt cũ; không xóa hoặc di chuyển cache/dữ liệu cũ, không đổi schema lưu trữ. Job Economy đang nộp vẫn chịu fingerprint và guard cũ; không tự hủy hay nộp lại khi prompt khác. Nguồn, ID, timestamp, Approved và các phần đã hoàn tất vẫn giữ nguyên. Bản dịch cũ không tự được viết lại sau cập nhật; nút dịch lại câu hoặc luồng thay đổi ngữ cảnh hiện có vẫn do người dùng chủ động chọn.

`VideoDialogueQualityRequestTest` kiểm tra hợp đồng system duy nhất, mapping preset có kiểm soát, dữ liệu nguyên vẹn, revision/cache identity và generation config/Live/legacy không đổi. Kết quả giả lập không chứng minh xưng hô hay văn phong thực tế tốt hơn. Đánh giá ngôn ngữ cần cặp câu nguồn/bản dịch và kiểm thử Gemini thật có phạm vi được cho phép; không dùng điểm số suy đoán hoặc tự tiêu quota để công bố chất lượng.

Tài liệu này mô tả triển khai và điểm cần kiểm, **không ghi nhận pass/fail hoặc số đo chưa chạy**. Kết quả thực, môi trường BlueStacks/ADB, giới hạn phép thử, APK/SHA-256 và danh sách delta cuối được bổ sung vào báo cáo bàn giao sau khi root hoàn tất kiểm chứng.

## Dịch offline và hiệu đính tự chọn (1.8.6)

Màn hình batch có lựa chọn hiệu đính cho chế độ dịch trên máy. `NONE` hoàn tất bằng bản dịch offline. `FLAGGED` gửi các câu có dấu hiệu cần kiểm tra. `ALL` gửi các bản nháp chưa duyệt và chưa hiệu đính. Trình tự mặc định vẫn đi qua EXTRACT/CLEAN cho cả đợt, rồi TRANSLATE, POLISH và FINISH. Các bước dùng cùng `VideoJobs`, bộ OCR, parser, timeline, exporter và kho dự án với màn hình video đơn.

Chỉ lựa chọn hiệu đính của người dùng mới cho phép gửi văn bản và bản nháp lên Gemini trong chế độ OFFLINE. POLISH dùng structured output theo ID, ngữ cảnh lân cận, hồ sơ phim và glossary. Kết quả hợp lệ được lưu trước khi chuyển nhóm. Approved và bản sửa tay được bảo vệ. Lỗi mạng sau giới hạn retry hoặc lỗi quota dừng lượt hiệu đính và giữ bản nháp. Người dùng có thể tiếp tục sau hoặc chọn giữ bản offline rồi chạy FINISH. Dự án cũ đọc trường tùy chọn với giá trị `NONE`.

ML Kit dịch một cue hoàn chỉnh mỗi lần. Cache bản nháp mới giữ tối đa 256 mục trong phạm vi dự án và phiên worker, khớp nguyên văn cùng hai câu nguồn lân cận. Kho câu đã duyệt theo phim tiếp tục dùng lựa chọn opt-in và điều kiện khớp ngữ cảnh hiện có. Kho 100.000 câu và fuzzy lookup của Live có ranh giới dữ liệu khác, nên chưa mở rộng tra cứu sang mọi phim private. Model offline phải có sẵn trên thiết bị để bắt đầu TRANSLATE. Việc tải model vẫn là thao tác chuẩn bị rõ ràng của người dùng.

Chọn chế độ dịch giữ lựa chọn nguồn riêng của từng tập, bao gồm track và SRT. Nhập nhiều video từ màn hình video đơn với nguồn AUTO tiếp tục phát hiện track. Vùng chung chỉ tác động đến dự án OCR. Khi tiếp tục một OCR checkpoint hợp lệ, batch dùng vùng đã lưu. Các tệp mới có cùng số tập hoặc chưa rõ thứ tự được giữ thành đầu ra riêng, và ghép tập qua thao tác nhóm rõ ràng. Nhóm đã lưu từ phiên bản cũ được đọc theo dữ liệu hiện có.

Bộ giải mã staged tái sử dụng một mảng luma riêng cho khung cuối chưa vào ring. Mỗi khung ring và dữ liệu đang OCR vẫn sở hữu byte độc lập. Lịch lấy mẫu và các kiểm tra xác nhận/PTS/EOS tiếp tục dùng hợp đồng cũ. `luma_buffer_reuses` ghi số lần tránh cấp phát mảng.

Các ca hồi quy mới nằm tại `BatchConfigurationTest`, `BatchOrderTest`, `VideoOfflinePolishTest`, `VideoOcrTailBenchmarkTest` và `BatchUnifiedNativeOcrTest`. Script `scripts/generate_batch_ocr_fixtures.py` tạo bộ clip tổng hợp có manifest thời gian rõ ràng. Native benchmark dùng package riêng bị Android chặn Internet, đo 1/3/5/10 video ngắn và một video 180 giây. So sánh hai decoder chỉ thuộc harness thử nghiệm, giải mã hình và OCR tiếp tục dùng một worker; dịch chữ có thể chạy đồng thời theo mức bộ nhớ đã chọn.

Kiểm thử thiết bị còn phát hiện track MP4 tx3g có thời lượng cue chưa đặt. Với `CUE_REPLACEMENT_BEHAVIOR_REPLACE`, cue mới hoặc sample rỗng đóng cue trước. `VideoEmbeddedTimeline` xử lý các sự kiện đó theo timestamp, giữ khoảng trống, câu lặp ở thời điểm khác và thời lượng rõ ràng khi có. Track MERGE vẫn cần duration hợp lệ. `VideoEmbeddedTimelineTest` kiểm các ranh giới này. Hợp đồng REPLACE được mô tả trong [Media3 Format](https://developer.android.com/reference/androidx/media3/common/Format#CUE_REPLACEMENT_BEHAVIOR_REPLACE).

`SmartExportQualityNativeTest` chạy opt-in trong package debug riêng `com.gemini.ocr.codexbench` hoặc `com.gemini.ocr.batchunified186`, bắt buộc bị Android chặn Internet. Tham số `executeExportQuality=true`, `qualityLabel=new-186` và `qualityFixture=synthetic-quality-720p.mp4` chọn clip tổng hợp 3–12 giây trong thư mục external-files `smart-quality`. Phép thử dùng cue Việt tiêm sẵn để kiểm độ phân giải, PTS, frame count và byte AAC, không đánh giá OCR hoặc chất lượng dịch. Host cần đối chiếu PTS chuẩn bằng ffprobe vì MediaExtractor API 28 có thể bỏ qua edit list của MP4.
