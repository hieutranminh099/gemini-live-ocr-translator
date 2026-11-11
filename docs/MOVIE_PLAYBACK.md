# Kho phim và phụ đề — 1.8.9

Trong trình phát Kho phim, **vuốt xuống trên hình để sang tập tiếp theo**, vuốt lên để về tập trước. Thứ tự lấy từ danh sách tập đã xác nhận, không suy lại từ tên tệp khi đang xem. Thao tác ở thanh tua, nút điều khiển và mép màn hình không dùng để chuyển tập. Vị trí của tập đang xem được lưu trước khi mở tập mới; không quay vòng từ tập cuối về tập đầu.

Nút toàn màn hình mở video trong toàn bộ vùng ứng dụng, ẩn tiêu đề, dòng thông tin và thời gian phụ. Các nút ẩn ngay khi mở, chạm vào hình để hiện trong 2,5 giây. Bấm biểu tượng thu nhỏ, chạm hai lần hoặc Back để thoát. Chế độ vừa hình giữ đầy đủ video; biểu tượng Fit/Fill cho phép chọn phóng kín. Viền đen hai bên của video dọc ở màn ngang có thể vẫn cần để giữ nguyên hình. Thanh cửa sổ của BlueStacks và giao diện âm lượng do hệ điều hành quản lý.

Kiểu phụ đề mặc định là **chữ vàng, viền đen, không có khối nền đen**, cùng làm mờ vùng chữ gốc. Hiệu ứng GPU chỉ tác động một dải gọn trong vùng nguồn khi có bản dịch đang hiện. Vùng tìm OCR rộng được thu thành dải cao tối đa 9% khung hình; vùng chọn hẹp được giữ. Nguồn ASR không tự làm mờ hình. Có thể chỉnh vùng nguồn, khung bản dịch, tắt làm mờ hoặc chọn nền tối thủ công trong thiết lập phụ đề.

Hiệu ứng hình dùng chung cho xem lại và MP4. SRT chỉ chứa văn bản, ASS giữ màu/viền/nền; hai định dạng này không tự làm mờ các điểm ảnh của video. Kiểu mặc định cũ hiển thị theo mặc định mới; kiểu tùy chỉnh được giữ. Snapshot Kho phim cũ vẫn giữ revision cũ; không ghi lại phụ đề hay video chỉ để cập nhật giao diện. Snapshot nguồn mới có kiểu/vùng nguồn mới dùng schema 2; các thiết bị nhận qua Drive cần cập nhật ứng dụng để đọc định dạng này.

MP4 cũ đã đóng sẵn nền đen cần được xuất lại từ video gốc với kiểu mới. Đổi kiểu trong trình phát không thể khôi phục những điểm ảnh đã bị che trong tệp MP4 đó.

Trong **Dịch hàng loạt → Thiết lập phụ đề → Lưu kết quả**, bật **Tự ghép các tập thành một phim** để tạo một MP4 cả bộ. Nếu đang chọn chỉ phát nguồn gốc, thao tác này chọn thêm MP4. Kiểm tra thứ tự danh sách trước khi bắt đầu. Sau khi mọi tập hoàn tất, duyệt phụ đề rồi chọn **Tạo MP4 cả bộ**. Hàng đợi tạo từng MP4 và ghép theo thứ tự đã chọn. Khi ghép xong, chọn **Lưu MP4 cả bộ** trong **Kiểm tra và xuất**. Có thể tiếp tục lưu riêng tập đã xong. Tắt chế độ ghép vẫn giữ mã tập, nhóm ghép cũ, timeline và các tệp nguồn. MP4 cần có định dạng hình và âm thanh tương thích để ghép giữ chất lượng. Nếu ghép lỗi, ứng dụng báo nguyên nhân và giữ kết quả từng tập. Bạn xác nhận đưa vào Kho phim riêng.

## Vị trí và kiểm chứng

- Chuyển tập và tiến độ: `movie/MoviePlaybackActivity.kt`, `movieAdjacentEpisode`, `video/FullscreenPlayerView`.
- Kiểu và render: `video/VideoSubtitleStyle`, `SubtitleBackdropEffect.kt`, `TimelineBitmapOverlay`; snapshot có vùng nguồn ở `movie/MovieSource.kt`.
- Ghép cả bộ: `batch/BatchProject.mergeEpisodes`, `BatchJobs.setMergeEpisodes`, `BatchPlan.groups`; dùng bộ ghép `VideoExporter.concatenate` hiện có.
- JVM: `MovieEpisodeNavigationTest`, `VideoEpisodeSwipeTest`, `VideoFullscreenStateTest`, `SubtitleBackdropTest`, `MovieSourceAppearanceTest`, `BatchAutoMergeTest` và regression video/batch/movie.
- Thiết bị: `MovieExperienceNativeTest` chỉ chạy với `executeMovieExperience=true`, package thử riêng `com.gemini.ocr.movie189`, mạng bị Android từ chối. Dùng clip tổng hợp 320×576, 4 giây có âm thanh; kiểm render làm mờ, phạm vi ảnh thay đổi, ghép thời lượng, chuyển tập, lưu tiến độ và toàn màn hình. Kết quả không thay cho kiểm chứng Gemini, phim riêng hoặc đồng bộ Drive.
