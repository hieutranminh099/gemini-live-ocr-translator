# Đo hiệu năng batch trước khi tối ưu

Đợt này bổ sung phép đo trên pipeline 1.8.5. Nguồn bàn giao đã dùng `SOURCE_LIBRARY`, exact private cache và checkpoint từng phần HYBRID. HYBRID hoàn thành POLISH từ kết quả dịch hội thoại đã lưu.

## Dữ liệu đo

`smart-batch-metrics.json` là sidecar AtomicFile số liệu của từng video, tách khỏi timeline và checkpoint nguồn. Số liệu cộng dồn qua các lần tiếp tục; các trường đếm cue hiện tại là snapshot, không cộng lặp mỗi checkpoint. Không có migration dữ liệu phim, không ghi source text, bản dịch, URI, tên phim, prompt, credential hoặc response body vào metrics.

- Các trường `extract_wall_ms`, `clean_wall_ms`, `translate_wall_ms`, `polish_wall_ms`, `finish_wall_ms` và `export_wall_ms` đo thời gian monotonic của worker trong mỗi giai đoạn. Batch dùng từng giai đoạn staged. Đường video đơn phân thời gian thành EXTRACT, TRANSLATE và EXPORT theo trạng thái worker. EXPORT đo render cho từng video, chưa gồm thời gian ghép nhiều tệp đã render.
- Các trường có hậu tố `_process_cpu_ms` đo chênh lệch thời gian CPU của **toàn tiến trình Android** trong khoảng đó, gồm native, thư viện và tác vụ nền.
- `worker_wall_ms` và `worker_process_cpu_ms` cộng các khoảng worker đã quan sát. `live_wait_wall_ms` và `live_wait_process_cpu_ms` tách khoảng nhường Live. Wall time bao gồm network/retry/quota trong worker. Thời gian chờ `nextRetryAt` trước khi khởi chạy, khoảng giữa các stage, ghép MP4 và thời gian tạm dừng nằm ngoài phép đo worker. Các bộ đếm request và quota sẵn có giúp phân tích các khoảng chờ trong worker.
- `peak_pss_kib` và các trường như `extract_peak_pss_kib` lấy PSS cao nhất của toàn tiến trình từ các mẫu tại checkpoint, tương ứng cho toàn worker và từng giai đoạn. Profiler dùng lại lần lấy mẫu hiện có. Số đo phụ thuộc vào thời điểm lấy mẫu.
- `source_cues`, `translated_cues`, `completed_cues`, `pending_cues`, `approved_cues` và `needs_review_cues` lấy số cue từ timeline lưu gần nhất khi kết thúc worker. Completed yêu cầu bản dịch có chữ và `VideoTranslationPlan.isComplete`. Needs-review đếm cue chưa hoàn tất hoặc cue chưa được bảo vệ mà có lỗi hay cờ chất lượng cần xem lại. `ASR_UNAVAILABLE` đứng riêng chỉ báo tình trạng ASR. Các số đếm này chỉ bao phủ cue đã có trong timeline.
- Decoder tiếp tục ghi `decoded_frames`, `recognition_requests`, `ocr_native_calls`, `exact_pixel_reused_observations` và các thời gian crop/bitmap/OCR/gate trong `ocrMetrics`. Sidecar chụp các bộ đếm khi project đang dùng nguồn OCR và đã có tiến độ quét. Các nguồn sidecar/embedded hoặc lần quét chưa bắt đầu có số đếm bằng 0. Tính tỷ lệ reuse theo `exact_pixel_reused_observations / (exact_pixel_reused_observations + recognition_requests)` khi mẫu số lớn hơn 0.
- Request, cache, token có usage thực, quota wait và checkpoint write/wall time giữ phép đo hiện có. Không thay token thiếu bằng giá trị ước đoán. `missing_cues` của response là số thiếu tích lũy qua response, khác `pending_cues` của timeline cuối.

Profiler cập nhật timer trong RAM tại checkpoint, dùng lần lấy PSS có sẵn và đếm cue một lượt khi worker kết thúc. Sidecar dùng các lần ghi metrics sẵn có. Nếu tiến trình dừng đột ngột, phần telemetry chưa flush có thể mất. Worker và timeline tiếp tục dựa vào checkpoint AtomicFile hiện có.

## Phép đối chiếu offline lặp lại

`VideoBatchProfilingBenchmarkTest` chạy cùng fixture trước và sau instrumentation. Ba tập có 80 cue SRT tổng hợp mỗi tập và một cue Approved. Một worker HYBRID xử lý qua từng giai đoạn, hoàn tất nguồn trước khi dịch. Harness dùng parser, staged worker và storage thật, cấp bản dịch giả và chặn dispatch mạng. Kiểm từng ID/source/timestamp, toàn bộ Approved row, số cue hoàn tất và digest timeline cuối.

```powershell
python scripts/build_android.py --offline testDebugUnitTest --tests com.gemini.ocr.video.VideoBatchProfilingBenchmarkTest
python scripts/build_android.py --offline testDebugUnitTest --tests com.gemini.ocr.video.VideoBatchProfilerTest --tests com.gemini.ocr.video.VideoAdaptiveChunksWorkerTest --tests com.gemini.ocr.video.VideoStagedPipelineTest --tests com.gemini.ocr.batch.BatchOrchestrationTest
```

Trên máy bàn giao có cache Gradle offline sẵn ở `E:\g-roi`. Lệnh tương ứng trong một cửa sổ PowerShell riêng dùng JDK 17 và SDK của checkout gốc.

```powershell
$repoRoot = 'C:\Users\AD\Documents\ChatGPT\App'
$upgradeWorktree = Join-Path $repoRoot '.worktrees\cloud-streaming-batch-185'
$env:JAVA_HOME = Join-Path $repoRoot '.tools\zulu17.68.203-ca-jdk17.0.20.1-win_x64'
$env:ANDROID_HOME = Join-Path $repoRoot '.tools\android-sdk'
$env:ANDROID_USER_HOME = Join-Path $upgradeWorktree 'build\android-home'
$env:GRADLE_USER_HOME = 'E:\g-roi'
Set-Location (Join-Path $upgradeWorktree 'android-app')
& (Join-Path $repoRoot '.tools\gradle-8.11.1\bin\gradle.bat') --offline --no-daemon --console=plain --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8' '-Pkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests '*VideoBatchProfilingBenchmarkTest'
```

Report được ghi vào `android-app/app/build/test-screenshots/batch-worker-baseline.json` hoặc `batch-worker-profiled.json`. Harness dùng `System.nanoTime`, thời gian CPU tiến trình từ host JDK nếu có, và heap đang dùng của JVM. Report ghi từng giai đoạn/tập và tổng wall time của phép thử. Hai lượt dùng cùng JDK, cấu hình và fixture. Phép đo bắt đầu sau khi Gradle compile. Một lượt trước/sau chịu nhiễu JIT, GC và lập lịch nên chỉ kiểm tra hoạt động của instrumentation.

Phép thử này **không đo decoder, OCR, Gemini thật, quota mạng, render MP4 hoặc hiệu năng thiết bị Android/BlueStacks**. Token thực là null, network call bằng không; số fake request không phải số lượt Gemini thực. EXTRACT ở fixture này chỉ parse SRT. EXPORT không chạy vì chọn nguồn + phụ đề; không ghi số 0 thành kết quả render đo được.

`SmartBatchPerformanceNativeTest` sẵn có có thể dùng riêng cho 1/3/10 video tổng hợp với model offline đã chuẩn bị trong app kiểm thử riêng. Decoder/OCR cần fixture được phép sử dụng và thiết bị thử riêng. Không tự cài APK, chạm phiên đang xem, tải phim riêng, tải model hoặc gọi Gemini để lấp chỗ chưa đo.

Chỉ xét tối ưu sau khi có số đo chỉ ra điểm nghẽn. Không đổi scheduler sang nhiều worker trên reservation một worker hiện tại; không đổi barrier nguồn HYBRID, sampling/confidence, cache identity hoặc checkpoint recovery dựa trên benchmark planner/mocked translation.

## Kết quả

Kết quả thực chạy, đường dẫn artifact và các giới hạn môi trường được ghi trong báo cáo bàn giao của đợt nâng cấp. Tài liệu này mô tả hợp đồng phép đo và lệnh tái lập, không thay số liệu hoặc pass/fail chưa chạy.
