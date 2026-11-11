# Phát phim từ Google Drive

Trong Kho phim, chọn **Đồng bộ danh mục** bằng tài khoản đã lưu phim. **Xem ngay** mở tập đã chọn và ưu tiên tệp cục bộ còn hợp lệ. **Tải ngoại tuyến** vẫn tải và xác minh bản đầy đủ để xem khi mất mạng hoặc sửa timeline phụ đề.

`MoviePlaybackActivity` kiểm tra nguồn cục bộ trước. Khi cần phát từ Drive, `MovieCloudSync` và `DriveVideoRepository` đọc manifest đã đồng bộ, đối chiếu phim, tập, thời lượng và revision của phụ đề. Kích thước và SHA-256 của video được đối chiếu với metadata Drive. Việc xác minh byte của toàn bộ tệp vẫn thuộc đường tải ngoại tuyến.

`DriveRangeDataSource` cấp từng đoạn tối đa 1 MiB cho Media3. Mỗi đoạn phải có HTTP 206 và `Content-Range` khớp phần yêu cầu, tổng kích thước và số byte thực tế. Player dùng mục tiêu bộ đệm 12 MiB trong RAM. Khi tua, player yêu cầu đoạn tại vị trí mới. Luồng này chỉ giữ dữ liệu tạm trong RAM và giải phóng khi đóng player.

OAuth giữ phạm vi quyền Drive hiện có. Mỗi yêu cầu đọc và mỗi lần giao dữ liệu cho player đều kiểm tra phiên tài khoản. Token nằm trong header `Authorization`. Một phản hồi 401 cho phép làm mới token một lần. Lỗi mạng và giới hạn yêu cầu có số lần thử hữu hạn. Khi `Retry-After` yêu cầu đợi hơn 5 giây, player báo lỗi để người dùng thử lại sau. Các lỗi quyền, tệp không còn và đoạn byte hỏng được báo riêng.

Với video gốc, `VideoPreview` dùng timeline và kiểu phụ đề đã lưu. `VideoSubtitleClock` lấy vị trí từ chính player khi phát, tua, tạm dừng hoặc đổi tốc độ. MP4 đã render giữ chữ trong hình video. Cả hai đường dùng cùng chế độ toàn màn hình. Tiến độ tiếp tục dùng dấu thời gian theo từng tập. Callback cũ, khác tài khoản hoặc khác revision bị bỏ qua. Khi dừng sau khi đã xem, ứng dụng xếp lại lần đồng bộ cuối bằng WorkManager hiện có.

Kiểm thử JVM dùng `DriveRangeDataSourceTest`, `DriveMovieRepositoryTest`, `DriveProgressiveMp4Test`, `MovieCloudPlaybackUiTest`, `MoviePlaybackProgressGuardTest`, `VideoPreviewViewTest` và `VideoSubtitleClockTest`. Ca MP4 dùng bộ đọc container của Media3 và máy chủ HTTP giả lập để đọc mẫu video, tua theo byte và xác minh giới hạn từng đoạn. Kiểm thử trên thiết bị cần phim tổng hợp đã đồng bộ qua tài khoản thử nghiệm. Xem cả hai dạng phim, tua tới các cue ngắn, đổi tốc độ, vào toàn màn hình, tạm dừng, mở lại trên thiết bị thứ hai, rồi thử mất mạng và chuyển tài khoản. Kết quả thực chạy được ghi trong báo cáo bàn giao.

[Google Drive API về đọc từng phần](https://developers.google.com/workspace/drive/api/guides/manage-downloads#partial_download) · [Google AuthorizationClient](https://developers.google.com/android/reference/com/google/android/gms/auth/api/identity/AuthorizationClient)
