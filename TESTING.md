# Kiểm thử Gemini OCR

Chạy từ root đúng worktree, kiểm tra Git trước/sau. Dùng dữ liệu tổng hợp, thư mục output riêng và fake provider/MockTransport của test. Không dùng DB, token, video hoặc phiên thiết bị người dùng. Kết quả lịch sử trong [VERIFICATION.md](docs/VERIFICATION.md) không thay cho lượt chạy hiện tại.

## Môi trường

Python 3.12 là cấu hình Windows đã dùng; dependency ứng dụng ở [requirements.txt](windows-server/requirements.txt). Test runner còn cần `pytest`, `pytest-asyncio`; analyzer research cần `sacrebleu`. Khi chuẩn bị môi trường mới theo phạm vi được giao:

```powershell
python -m venv .venv
.\.venv\Scripts\python -m pip install -r windows-server/requirements.txt
.\.venv\Scripts\python -m pip install pytest pytest-asyncio
```

Các lệnh dưới dùng `python` của môi trường đã chuẩn bị. Nếu máy không có trên PATH, dùng đường dẫn interpreter thực; không đổi PATH/cài đặt toàn máy chỉ để chạy test. `.python-deps/` là môi trường cục bộ không được version hóa, không phải bằng chứng mọi máy có dependency. Thiếu quyền đọc/package/tool phải báo là lỗi môi trường, không sửa test để che lỗi. Trên Windows, dùng `python -X utf8` khi harness/log có chữ Việt; tùy chọn chỉ áp dụng cho tiến trình đó.

Android: Java 17, SDK 35, Gradle 8.11.1, AGP 8.9.1/Kotlin 2.1.10 theo Gradle files. [build_android.py](scripts/build_android.py) cần `.tools/android-tools.json`, đặt JAVA_HOME/ANDROID_HOME/GRADLE_USER_HOME trong tiến trình con. `bootstrap_android.py` tải công cụ và ghi local.properties; không chạy như một phép kiểm tra read-only. Có thể dùng Gradle wrapper trực tiếp trong `android-app/` nếu máy đã thiết lập JDK/SDK, thay cho helper metadata.

## Kiểm thử theo module

Thay `<task-id>` bằng ID riêng; pytest có thể dọn lại chính `--basetemp`, nên không trỏ vào thư mục có dữ liệu cần giữ. [MODULE_INDEX.md](MODULE_INDEX.md) ánh xạ phần còn lại.

| Phạm vi | Lệnh từ root |
|---|---|
| Tài liệu | `python scripts/check_codex_docs.py` |
| Import/chia batch phụ đề | `python -m pytest tests/test_subtitle_import.py -q -p no:cacheprovider --basetemp=build/pytest-<task-id>` |
| Dịch/TM/privacy | `python -m pytest tests/test_system.py tests/test_translation_memory.py tests/test_privacy_updates.py -q -p no:cacheprovider --basetemp=build/pytest-<task-id>` |
| Scheduler/transport | `python -m pytest tests/test_api_latest_scheduling.py tests/test_live_scheduler_v171.py tests/test_api_transport_telemetry.py -q -p no:cacheprovider --basetemp=build/pytest-<task-id>` |
| Prewarm/routes | `python -m pytest tests/test_subtitle_jobs.py tests/test_subtitle_routes.py -q -p no:cacheprovider --basetemp=build/pytest-<task-id>` |
| Video | `python -m pytest tests/test_video_jobs.py tests/test_video_media.py tests/test_video_routes.py tests/test_video_speech.py tests/test_video_resource_priority.py -q -p no:cacheprovider --basetemp=build/pytest-<task-id>` |
| Android freshness | `python scripts/build_android.py --offline testDebugUnitTest --tests com.gemini.ocr.capture.SubtitlePipelineTest` |
| Android cooldown | `python scripts/build_android.py --offline testDebugUnitTest --tests com.gemini.ocr.capture.TranslationRetryStateTest` |

`--offline` tránh Gradle tải dependency, không bảo đảm cache đã đủ. Android test report ở `android-app/app/build/reports/tests/testDebugUnitTest/`, XML ở `android-app/app/build/test-results/testDebugUnitTest/`. Targeted test chỉ là bằng chứng cho nhóm đó; muốn kiểm lại thực thi thay vì UP-TO-DATE thì thêm `--rerun-tasks` khi cần.

## Bộ đầy đủ và tích hợp

```powershell
python -m pytest tests -q -p no:cacheprovider --basetemp=build/pytest-<task-id>
python -X utf8 -m unittest discover -s scripts -p test_verify_api_pipeline.py -v
python -X utf8 -m unittest discover -s research/gemini_api -p 'test_*.py' -v
```

`pytest.ini` chỉ collect `tests/`; hai lệnh unittest kiểm tra harness offline ngoài thư mục đó. Script unittest cần `build/` tồn tại và `httpx`; tạo thư mục riêng nếu chưa có. Full Python gồm test dùng subprocess/FFmpeg/OCR thật với media tổng hợp khi runtime đã có; thiếu tool/model có thể skip, phải báo số skip. Không gọi cả suite là toàn bộ mocked. Không tự tải model để biến skip thành pass.

Full Android JVM + lint trong **worktree kiểm thử riêng**:

```powershell
python scripts/build_android.py --offline testDebugUnitTest lintDebug
```

**Tác dụng phụ đã xác minh:** `MainActivityTest.screenshot()` ghi `gemini.test.screenshots` được Gradle trỏ vào `docs/images/`, có thể ghi đè ảnh tracked. Dùng worktree không có công việc khác và xem diff ảnh sau lượt chạy; nếu chỉ cần logic, chọn đúng lớp test. Không chạy full JVM ở checkout dùng chung khi chưa cô lập output ảnh.

Khi chạm protocol, namespace, scheduler, storage/TM hoặc freshness: chạy cả test server liên quan và test Android counterpart; kiểm tra client/capability cũ, cancel/stale response, quota/retry và privacy. Khi thay UI, route tests chưa chứng minh thao tác/hiển thị browser đúng; cần smoke giao diện ở môi trường riêng.

Native test tại [androidTest](android-app/app/src/androidTest/java/com/gemini/ocr) có thể unpair và đổi cấu hình/capture; chỉ dùng thiết bị/Companion thử nghiệm được giao. Gemini thật, ADB/cài APK, render dài hoặc benchmark tốn quota cần phạm vi cho phép riêng. Không chạy trên phim hoặc phiên người dùng đang xem.

## Build và các lệnh cần chọn có chủ đích

- Android debug: `python scripts/build_android.py assembleDebug` ghi APK vào `dist/GeminiOCR-Android.apk`. Release/sign phải dùng quy trình và khóa có sẵn, không tạo khóa thay thế.
- Windows: `python scripts/build_windows.py` dùng PyInstaller và runtime media/model có sẵn; có thể ghi `windows-server/static/app.ico`. Không phải smoke test vô hại.
- `scripts/package_delivery.py` thu cả **untracked không bị ignore** vào source ZIP và sao chép docs; kiểm tra danh sách trước đóng gói. Không chạy để kiểm thử thay đổi tài liệu.
- `scripts/smoke_windows.py` chạy EXE ở cổng 8965/8966 với runtime test; verifier/native smoke có thể ghi ảnh source. Research scripts có thể ghi đè báo cáo tracked ngay khi import; đọc mã trước khi chạy.
- Chưa thấy CI workflow tracked. Đề xuất đợt riêng: thiết lập dependency test tái lập, cô lập screenshot, rồi thêm CI offline theo module. Không tự bật CI gọi Gemini hoặc dùng secret.

## Kiểm chứng đợt tài liệu 28/10/2026

Phạm vi: commit `529635b`; bảo toàn README và RELEASE_PRIORITY chưa commit, không đổi mã chức năng. Bằng chứng máy cục bộ nằm trong `build/codex-docs-audit-20261028-01a18298/` (ignored, không phải artifact version hóa).

| Kiểm tra thực chạy | Kết quả |
|---|---|
| `pytest tests` với basetemp/cache riêng, JUnit XML | **302 pass**, không fail/skip, 30,18 giây; một deprecation warning từ Starlette/AnyIO. |
| Android offline: SubtitlePipelineTest, SubtitleDebounceTest, TranslationRetryStateTest, OverlayPlacementTest | **41 pass**, không fail/skip; Gradle BUILD SUCCESSFUL 36 giây, test task thực thi (các bước compile đã up-to-date). |
| `unittest` research/gemini_api | **13 pass**, 0,283 giây; dữ liệu tổng hợp/HTTP mock. |
| `unittest` scripts/test_verify_api_pipeline | **2 pass**, 0,011 giây sau dùng `-X utf8`; lượt đầu lỗi UnicodeEncodeError do output encoding, không sửa mã ứng dụng. |
| Checker + fixture link hỏng | 5 docs, **165 link nội bộ hợp lệ**; fixture thiếu đích được từ chối đúng exit 1. Bốn fixture bổ sung kiểm tra link hợp lệ/spaces, đích thiếu, thoát root và tài liệu thiếu đều đạt. |
| Bảo toàn công việc cũ | SHA-256 README và RELEASE_PRIORITY trước/sau giống nhau; Git không có thay đổi tracked mới ngoài README đã dirty từ đầu. |

Interpreter thực dùng là Python 3.12.14 bundled của môi trường Codex; các bài Python lấy dependency có sẵn từ `.python-deps/` qua PYTHONPATH của tiến trình. Không cài package hoặc tải runtime. Chưa chạy full Android JVM/lint, native/thiết bị thật, UI browser smoke, EXE/APK packaging hoặc Gemini thật trong đợt này.

`git diff --check` toàn checkout báo trailing whitespace ở ba dòng README đã thay đổi trước khi bắt đầu; giữ nguyên file này. Sáu file mới được kiểm tra riêng về whitespace/cú pháp/link. Không báo lệnh toàn checkout là sạch khi còn cảnh báo của công việc khác.

### Tác vụ nhỏ đánh giá quy trình

Triển khai/kiểm chứng `scripts/check_codex_docs.py`: định vị từ mục lục → script stdlib tập trung → chạy trên docs thật và fixture hỏng → review, ghi kết quả tại đây. Không sửa production để chứng minh quy trình.

| Chỉ số | Số đo / giới hạn |
|---|---|
| Phạm vi file | 1 file script được review; checker đọc 5 tài liệu root, chỉ kiểm tra tồn tại của 165 đích link, không đọc nội dung source ở các đích và không quét repo. Fixture âm đọc thêm 5 tài liệu tổng hợp. |
| Thời gian kiểm chứng có đo | **0,2097 giây** cho parse cú pháp + chạy checker thật + tạo/chạy/dọn fixture thiếu đích, ghi bằng perf_counter; chưa đo toàn bộ thời gian thiết kế/triển khai. |
| Vòng sửa sau lỗi checker | **0**; exit 1 ở fixture âm là kết quả mong đợi. Review tài liệu có một vòng sửa tên symbol và phạm vi chế độ tự động trước phép đo. |
| Token đầu vào/đầu ra | **Không đo được** cho tác vụ này; không có bộ đếm token theo phạm vi. |
| So sánh trước/sau | **Chưa có tác vụ đối chứng tương đương**; không công bố tỷ lệ tiết kiệm token/thời gian. Lượt khảo sát toàn repo không phải đối chứng cho checker nhỏ. |

Số đo chi tiết nằm trong `docs-check-metrics.json` tại thư mục bằng chứng ở trên; log và fixture là kiểm chứng công cụ, không phải benchmark OCR/Gemini.

Checker chỉ kiểm tra file/thư mục của link Markdown tương đối trong **năm tài liệu root**. Nó không quét toàn repository, xác minh anchor, symbol/API, Mermaid hoặc nội dung nhận định. Sau đổi tên/di chuyển chức năng vẫn cần review mục lục bằng mắt và chạy regression thích hợp.

## Kiểm tra tích hợp batch từng tập — 10/11/2026

Giữ JDK 17 / Gradle 8.11.1 / AGP 8.9.1 / SDK 35 / Kotlin 2.1.10. Không đổi signing, cache hoặc cấu hình hệ thống. Dữ liệu kiểm thử là SRT tổng hợp và phản hồi giả lập, không dùng phim/tài khoản/DB cá nhân.

```powershell
python scripts/build_android.py --offline --max-workers=1 --stacktrace testDebugUnitTest --tests com.gemini.ocr.batch.BatchEpisodePipelineTest --tests com.gemini.ocr.batch.BatchEpisodeIntegrationTest --tests com.gemini.ocr.batch.BatchPlaybackPriorityTest --tests com.gemini.ocr.batch.BatchOrchestrationTest --tests com.gemini.ocr.batch.BatchRecoveryTest --tests com.gemini.ocr.video.VideoAdaptiveAdmissionTest
python scripts/build_android.py --offline --max-workers=1 --stacktrace assembleDebug
```

`BatchEpisodeIntegrationTest` dùng BatchJobs/VideoJobs/AtomicFile thật trong Robolectric với nguồn/phản hồi tổng hợp: 10 tập, trích và dịch chồng nhau, tập đầu xong sớm, pause/cancel/resume, deadline/retry, ID đã lưu, timestamp, sửa tay, redelivery và playback. Phục hồi process death ở JVM được mô hình bằng batch RUNNING đã lưu và instance mới, không phải kill tiến trình emulator/điện thoại.

Kết quả phiên này: **15 test JVM độc lập đạt**, gồm 11 test điều phối trước đây, hai ca playback/extraction bổ sung và hai ca quyền ưu tiên playback. Compiler 2.1.10 chạy trực tiếp từ dependency có sẵn; không thay bằng kết quả Gradle. Các test tích hợp Robolectric mới chưa chạy được. Gradle bị chặn trước compile vì socket localhost trả `Permission denied: getsockopt`; `--no-daemon` vẫn cần single-use daemon. Cache còn thiếu Media3 1.5.1; thử đọc metadata công khai từ Maven Google thất bại DNS. Không xóa cache, mở quyền socket, đổi DNS/proxy hay toolchain. Chưa có APK mới, test emulator/điện thoại, OCR/ASR native, Gemini thật hoặc số đo xử lý phim/RAM.

Diff trước sửa, bản sao các file được giao, log và runner độc lập nằm tại `build/batch-integration-20261110-v1812/`. Lệnh tái lập phần độc lập: `python build/batch-integration-20261110-v1812/run_core_tests.py`. Thời gian JUnit là thời gian chạy fixture, không phải hiệu năng xử lý 10 tập phim.

## Đo thời gian xử lý một video đến Kho phim

Tạo một bộ MP4/SRT tổng hợp bằng `python scripts/generate_single_video_benchmark_fixture.py build/single-video-fixture --cues 4000`. Dùng cùng thư mục đầu vào và cùng bản kiểm thử `SingleVideoBatchBenchmarkTest` trên hai worktree. Đặt `GEMINI_BATCH_BENCH_FIXTURE` thành đường dẫn tuyệt đối rồi chạy Gradle từ `android-app`.

```sh
./gradlew :app:testDebugUnitTest --rerun --tests com.gemini.ocr.batch.SingleVideoBatchBenchmarkTest
```

Mỗi bản chạy khởi động một lượt và đo ba lượt với dự án mới mỗi lần. Báo cáo `android-app/app/build/test-screenshots/single-video-batch-benchmark.json` ghi thời gian nhập video, xử lý, xác nhận lưu cục bộ và SHA-256 của timeline, yêu cầu dịch, yêu cầu hiệu đính và SRT. Phép đo chạy luồng batch, đọc SRT và ghi checkpoint/Kho phim thật trên Robolectric với metadata giả lập và phản hồi Gemini cố định.

Kết quả ngày 18/11/2026 trên Codex Cloud, JDK 17 và Robolectric API 28. Bản phát hành `v1.8.18` (`a67e6ea`) có trung vị 17,71 giây (17,61–18,14 giây). Bản có sửa lỗi Kho phim và retry (`2cbcf87`) đạt 17,99 giây (17,85–20,17 giây). Bản tối ưu trên nền `2cbcf87` đạt 6,08 giây (6,02–6,35 giây). Ba bản ghi 1.012 checkpoint, giữ đủ 4.000 câu và có cùng SHA-256 của timeline, yêu cầu gửi, SRT và revision Kho phim. Số lượt gọi Gemini thật là 0 ở cả ba bản.

Đo trên thiết bị để kiểm tra tốc độ với OCR và Gemini thật. Dùng cùng clip, thiết lập, thiết bị, điều kiện mạng và trạng thái bộ đệm. Ghi từ lúc chọn video tới khi mục phim đã lưu xuất hiện, loại thời gian chờ người bấm xác nhận. Kiểm tra đủ câu và các mốc thời gian sau mỗi lượt. Dùng video thử nghiệm và tài khoản đã được phép dùng cho phép đo.

### Đo OCR và Gemini thật trên giả lập GitHub Actions

Workflow `Single-video Android benchmark` chạy khi nhánh `perf/single-video` được đẩy lên. Tạo Actions secret `GEMINI_BENCHMARK_API_KEY` trước khi chạy. Hai APK debug dùng package thử riêng và cùng một video tổng hợp 12 giây có sáu câu tiếng Trung trong hình. Workflow chạy xen kẽ ba lượt mỗi bản, xóa dữ liệu của package thử giữa các lượt và giữ báo cáo JSON trong artifact `single-video-android-results`. Báo cáo ghi trung vị từ nhập video đến xác nhận Kho phim, thời gian từng đoạn, số lần nhận dạng và gọi Gemini, số câu và mã băm timeline OCR. Clip thử nghiệm được dịch bằng tài khoản Gemini của khóa thử. Kết quả áp dụng cho giả lập Android của GitHub.

### Kiểm chứng video dài hơn 30 phút

- Tạo dữ liệu tải lớn bằng `python scripts/generate_single_video_benchmark_fixture.py build/long-video-fixture --cues 5065`. Dùng cùng tệp MP4 dài 35 phút 53 giây và SRT trên hai worktree. Dùng `--cues 8471` với thư mục mới để thử mẫu dài 60 phút 1 giây. Chạy `SingleVideoBatchBenchmarkTest` theo hướng dẫn ở trên để so thời gian, số lần ghi checkpoint và mã băm của các yêu cầu, phụ đề xuất ra và dữ liệu Kho phim.
- Nhánh `perf/long-video` chạy `LongVideoBenchmarkTest` với clip 36 phút 25 giây tạo bằng `python scripts/generate_single_video_ocr_fixture.py --long <đường-dẫn-MP4>`. Hai APK thử nghiệm bị tắt quyền mạng. Luồng quét OCR thật, tạm dừng sau 99% thời lượng nguồn, tiếp tục từ checkpoint, dịch bằng phản hồi cố định rồi đọc lại dữ liệu Kho phim đã lưu. Ba đoạn có chữ ở đầu, giữa và cuối phim. Artifact `single-video-android-results` ghi mốc tạm dừng, số câu lưu, số liệu OCR và mã băm nội dung cùng thời gian phụ đề. Phép đo JVM sử dụng 5.065 câu, còn phép đo Android kiểm tra đọc hết thời lượng và tiếp tục khi gần hết video.
- Nhánh `perf/long-video-next` chạy xen kẽ ba lượt mỗi bản, lấy commit `bb22bf5` làm đối chứng. Báo cáo so trung vị tổng thời gian với mục tiêu giảm 35%. Sáu lượt phải có cùng mã băm yêu cầu dịch, nội dung phụ đề đã duyệt, SRT, ASS và tệp video gốc được Kho phim sử dụng.

## Kiểm tra trải nghiệm Kho phim và trình phát

Nhánh `perf/movie-experience` lấy `052d22d` làm đối chứng và chạy `MoviePlaybackBenchmarkTest` trên cùng máy ảo Android. Hai APK kiểm thử dùng riêng package `com.gemini.ocr.playbacktest` và tắt quyền mạng. Clip tổng hợp 24 giây có hình động H.264 và âm thanh AAC. Kết quả ghi năm lượt mở phim, tua khi đang phát hoặc tạm dừng, đổi tập và số lần cập nhật phụ đề. Ca riêng kiểm tra mở danh mục có tám phim, mỗi phim 5.065 câu. Artifact giữ kết quả, ảnh Kho phim và ảnh trình phát. Đo thời gian ở môi trường giả lập dùng để đối chiếu giữa hai bản.
