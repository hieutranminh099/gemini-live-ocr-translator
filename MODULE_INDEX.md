# Tra cứu chức năng → mã nguồn → kiểm thử

Bắt đầu từ hàng gần yêu cầu nhất, tìm symbol trong file đó rồi mới mở rộng sang dependency. Link là đường dẫn tương đối từ root; tên test là điểm bắt đầu, không phải toàn bộ phạm vi tích hợp. Lệnh chạy nằm tại [TESTING.md](TESTING.md).

## Android

Chỉ có module Gradle `:app`. Các test trong bảng là JVM/Robolectric; native được ghi riêng.

| Chức năng / symbol | File cần đọc trước | Test đầu tiên |
|---|---|---|
| UI, pairing, consent | [MainActivity.kt](android-app/app/src/main/java/com/gemini/ocr/MainActivity.kt) | [MainActivityTest](android-app/app/src/test/java/com/gemini/ocr/MainActivityTest.kt) |
| WSS/TLS pinning, reconnect, capabilities | [TranslationClient.kt](android-app/app/src/main/java/com/gemini/ocr/network/TranslationClient.kt) | [CertificatePinTest](android-app/app/src/test/java/com/gemini/ocr/network/CertificatePinTest.kt) |
| Settings, ROI preset, Keystore token | [AppSettings.kt](android-app/app/src/main/java/com/gemini/ocr/settings/AppSettings.kt) | [SubtitleRegionTest](android-app/app/src/test/java/com/gemini/ocr/capture/SubtitleRegionTest.kt) |
| Batch từng tập, resume và playback admission | [BatchJobs.kt](android-app/app/src/main/java/com/gemini/ocr/batch/BatchJobs.kt), [BatchEpisodePipeline.kt](android-app/app/src/main/java/com/gemini/ocr/batch/BatchEpisodePipeline.kt), [VideoJobs.kt](android-app/app/src/main/java/com/gemini/ocr/video/VideoJobs.kt) | [BatchEpisodeIntegrationTest](android-app/app/src/test/java/com/gemini/ocr/batch/BatchEpisodeIntegrationTest.kt), [BatchEpisodePipelineTest](android-app/app/src/test/java/com/gemini/ocr/batch/BatchEpisodePipelineTest.kt), [BatchPlaybackPriorityTest](android-app/app/src/test/java/com/gemini/ocr/batch/BatchPlaybackPriorityTest.kt) |
| Capture/OCR lifetime, request/result orchestration | [ScreenCaptureService.kt](android-app/app/src/main/java/com/gemini/ocr/capture/ScreenCaptureService.kt): `captureLoop`, `sendLatest`, `onMessage`, `renderCurrent` | [CaptureConfirmationTest](android-app/app/src/test/java/com/gemini/ocr/capture/CaptureConfirmationTest.kt), [CapturePriorityStateTest](android-app/app/src/test/java/com/gemini/ocr/capture/CapturePriorityStateTest.kt) |
| Fingerprint / wakeup | [OcrFrameFingerprint.kt](android-app/app/src/main/java/com/gemini/ocr/capture/OcrFrameFingerprint.kt), [CaptureWakeup.kt](android-app/app/src/main/java/com/gemini/ocr/capture/CaptureWakeup.kt) | [OcrFrameFingerprintTest](android-app/app/src/test/java/com/gemini/ocr/capture/OcrFrameFingerprintTest.kt), [CaptureWakeupTest](android-app/app/src/test/java/com/gemini/ocr/capture/CaptureWakeupTest.kt) |
| ML Kit OCR Latin/zh/ja/ko | [OcrProcessor.kt](android-app/app/src/main/java/com/gemini/ocr/ocr/OcrProcessor.kt): `process` | [NativeOcrTest](android-app/app/src/androidTest/java/com/gemini/ocr/NativeOcrTest.kt) — thiết bị riêng |
| Freshness/correlation phụ đề | [SubtitlePipeline.kt](android-app/app/src/main/java/com/gemini/ocr/capture/SubtitlePipeline.kt) | [SubtitlePipelineTest](android-app/app/src/test/java/com/gemini/ocr/capture/SubtitlePipelineTest.kt) |
| Stable text / **RAM** TranslationMemory | [StableText.kt](android-app/app/src/main/java/com/gemini/ocr/capture/StableText.kt) | [StableTextTest](android-app/app/src/test/java/com/gemini/ocr/capture/StableTextTest.kt) |
| Debounce nguồn | [SubtitleDebounce.kt](android-app/app/src/main/java/com/gemini/ocr/capture/SubtitleDebounce.kt) | [SubtitleDebounceTest](android-app/app/src/test/java/com/gemini/ocr/capture/SubtitleDebounceTest.kt) |
| Vùng nguồn / theo dõi dòng | [SubtitleRegion.kt](android-app/app/src/main/java/com/gemini/ocr/capture/SubtitleRegion.kt), [SourceRowTracker.kt](android-app/app/src/main/java/com/gemini/ocr/capture/SourceRowTracker.kt), [SubtitleRegionSelector.kt](android-app/app/src/main/java/com/gemini/ocr/overlay/SubtitleRegionSelector.kt) | [SourceRowTrackerTest](android-app/app/src/test/java/com/gemini/ocr/capture/SourceRowTrackerTest.kt), [SubtitleSourceRowsTest](android-app/app/src/test/java/com/gemini/ocr/capture/SubtitleSourceRowsTest.kt) |
| Text identity / tránh OCR overlay | [SubtitleText.kt](android-app/app/src/main/java/com/gemini/ocr/capture/SubtitleText.kt) | [SubtitleTextTest](android-app/app/src/test/java/com/gemini/ocr/capture/SubtitleTextTest.kt) |
| Retry/cooldown/cấu hình | [TranslationRetryState.kt](android-app/app/src/main/java/com/gemini/ocr/capture/TranslationRetryState.kt), service `onMessage` | [TranslationRetryStateTest](android-app/app/src/test/java/com/gemini/ocr/capture/TranslationRetryStateTest.kt), [ConfigurationFailureCaptureTest](android-app/app/src/test/java/com/gemini/ocr/capture/ConfigurationFailureCaptureTest.kt) |
| Vẽ/vị trí/ổn định overlay | [TranslationOverlay.kt](android-app/app/src/main/java/com/gemini/ocr/overlay/TranslationOverlay.kt), [OverlayPlacement.kt](android-app/app/src/main/java/com/gemini/ocr/overlay/OverlayPlacement.kt) | [TranslationOverlayTest](android-app/app/src/test/java/com/gemini/ocr/overlay/TranslationOverlayTest.kt), [StableOverlayTest](android-app/app/src/test/java/com/gemini/ocr/overlay/StableOverlayTest.kt), [OverlayPlacementTest](android-app/app/src/test/java/com/gemini/ocr/overlay/OverlayPlacementTest.kt) |
| Backend badge / session metrics | [SessionState.kt](android-app/app/src/main/java/com/gemini/ocr/SessionState.kt), [PipelineTrace.kt](android-app/app/src/main/java/com/gemini/ocr/capture/PipelineTrace.kt) | [TranslationBackendStateTest](android-app/app/src/test/java/com/gemini/ocr/TranslationBackendStateTest.kt) |

Đổi disconnect/reconnect còn đọc [DisconnectedCaptureTest](android-app/app/src/test/java/com/gemini/ocr/capture/DisconnectedCaptureTest.kt). Native pairing/capture tại [androidTest](android-app/app/src/androidTest/java/com/gemini/ocr) có thể unpair thiết bị, không chạy trên phiên đang dùng.

## Windows Companion và engine

| Chức năng / symbol | File cần đọc trước | Test đầu tiên |
|---|---|---|
| Khởi động, TLS, admin/device boundary | [launcher.py](windows-server/launcher.py), [server.py](windows-server/app/server.py): `Runtime`, `device_app`, `admin_app`, [security.py](windows-server/app/security.py) | [test_system.py](tests/test_system.py), [test_privacy_updates.py](tests/test_privacy_updates.py) |
| WebSocket/latest/cancel/correlation | [server.py](windows-server/app/server.py): `stream`, [models.py](windows-server/app/models.py), [protocol.json](shared/protocol.json) | [test_api_latest_scheduling.py](tests/test_api_latest_scheduling.py), [test_latest_frame.py](tests/test_latest_frame.py) |
| Dịch/cache/glossary/context/quota/singleflight | [manager.py](windows-server/app/manager.py): `_translate`, [storage.py](windows-server/app/storage.py) | [test_system.py](tests/test_system.py), [test_translation_memory.py](tests/test_translation_memory.py) |
| Ưu tiên Live/video/prewarm | [resources.py](windows-server/app/resources.py), manager admission | [test_live_scheduler_v171.py](tests/test_live_scheduler_v171.py), [test_video_resource_priority.py](tests/test_video_resource_priority.py), [test_video_metering.py](tests/test_video_metering.py) |
| API chính thức/retry/transport | [provider.py](windows-server/app/provider.py): `GeminiProvider`, `ProviderRouter` | [test_api_speed_policy.py](tests/test_api_speed_policy.py), [test_api_transport_telemetry.py](tests/test_api_transport_telemetry.py) |
| CLI cá nhân opt-in / Antigravity | [cli_provider.py](windows-server/app/cli_provider.py), [antigravity_provider.py](windows-server/app/antigravity_provider.py) | [test_cli_provider.py](tests/test_cli_provider.py), [test_antigravity_provider.py](tests/test_antigravity_provider.py), [test_personal_cli_state.py](tests/test_personal_cli_state.py) |
| TM **Windows persistent**, approval/privacy | [translation_memory.py](windows-server/app/translation_memory.py), manager; **routes tại [video_routes.py](windows-server/app/video_routes.py)** | [test_translation_memory.py](tests/test_translation_memory.py), [test_video_routes.py](tests/test_video_routes.py), [test_privacy_updates.py](tests/test_privacy_updates.py) |
| Import SRT/VTT/ASS/SSA và chia batch | [subtitle_import.py](windows-server/app/subtitle_import.py): `parse_subtitle_file`, `plan_subtitles` | [test_subtitle_import.py](tests/test_subtitle_import.py) |
| Dịch trước/prewarm cache Live | [subtitle_jobs.py](windows-server/app/subtitle_jobs.py), [subtitle_routes.py](windows-server/app/subtitle_routes.py) | [test_subtitle_jobs.py](tests/test_subtitle_jobs.py), [test_subtitle_routes.py](tests/test_subtitle_routes.py) |
| Video project/checkpoint/timeline | [video_jobs.py](windows-server/app/video_jobs.py): `VideoJobService`, [video_routes.py](windows-server/app/video_routes.py) | [test_video_jobs.py](tests/test_video_jobs.py), [test_video_routes.py](tests/test_video_routes.py) |
| Probe/extract/render/export SRT/ASS/MP4 | [video_media.py](windows-server/app/video_media.py) | [test_video_media.py](tests/test_video_media.py) |
| Video OCR/speech | [video_ocr.py](windows-server/app/video_ocr.py), [video_speech.py](windows-server/app/video_speech.py) | [test_video_media.py](tests/test_video_media.py), [test_video_speech.py](tests/test_video_speech.py) |
| Capture priority / telemetry | [resources.py](windows-server/app/resources.py), [telemetry.py](windows-server/app/telemetry.py), server | [test_capture_priority_state.py](tests/test_capture_priority_state.py), [test_pipeline_telemetry.py](tests/test_pipeline_telemetry.py) |
| Companion UI | [app.js](windows-server/static/app.js), [index.html](windows-server/static/index.html), [style.css](windows-server/static/style.css) | Route contracts: [video](tests/test_video_routes.py), [subtitle](tests/test_subtitle_routes.py); chưa thấy browser suite |

## Build, công cụ và tài liệu

| Nhu cầu | Điểm vào |
|---|---|
| Android toolchain/build | [build_android.py](scripts/build_android.py), [bootstrap_android.py](scripts/bootstrap_android.py), [app Gradle](android-app/app/build.gradle.kts) |
| Windows build/install/delivery | [build_windows.py](scripts/build_windows.py), [installer.py](windows-server/installer.py), [package_delivery.py](scripts/package_delivery.py) |
| Kiểm tra link năm tài liệu Codex | [check_codex_docs.py](scripts/check_codex_docs.py) — chỉ stdlib, không quét repo |
| Native/API pipeline diagnostics | [native_android_smoke.py](scripts/native_android_smoke.py), [verify_api_pipeline.py](scripts/verify_api_pipeline.py), test offline [test_verify_api_pipeline.py](scripts/test_verify_api_pipeline.py) |
| API benchmark / analyzer offline tests | [research/gemini_api](research/gemini_api); chạy test, không chạy benchmark mặc định |
| Thử dịch local chưa tích hợp | [research/local_translation/README.md](research/local_translation/README.md) |
| Protocol/usage/acceptance lịch sử | [PROTOCOL.md](shared/PROTOCOL.md), [SUBTITLE_MODE.md](docs/SUBTITLE_MODE.md), [VIDEO_TRANSLATOR_V170.md](docs/VIDEO_TRANSLATOR_V170.md), [VERIFICATION.md](docs/VERIFICATION.md) |
| Quy tắc / tích hợp / kiểm thử | [AGENTS.md](AGENTS.md), [INTEGRATION_GUIDE.md](INTEGRATION_GUIDE.md), [TESTING.md](TESTING.md) |

Sau khi thêm/di chuyển chức năng, sửa hàng tương ứng ở đây; chỉ sửa ARCHITECTURE khi ranh giới hoặc luồng thay đổi. Không biến mục lục thành bản liệt kê mọi file.
