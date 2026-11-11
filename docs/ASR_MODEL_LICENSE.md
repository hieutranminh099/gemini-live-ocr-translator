# Nguồn và giấy phép gói nhận diện tiếng Trung

Gói nhận diện gồm ba tệp, tổng **240.193.589 byte**. Các tệp được tải hoặc nhập rõ ràng, kiểm SHA-256 và dung lượng trước khi lưu; chúng không được nhúng vào APK. `ChineseAsrLicense.TEXT` giữ thỏa thuận FunASR bằng tiếng Anh và giấy phép Silero; trình quản lý ghi bản này thành `LICENSE.txt` trong gói đã cài. Giao diện hiển thị tên và tác giả của gói.

| Thành phần | Nguồn và giấy phép |
| --- | --- |
| SenseVoiceSmall int8 | FunAudioLLM/FunASR/Alibaba Group. Trọng số chịu [FunASR Model Open Source License Agreement 1.1](https://github.com/modelscope/FunASR/blob/main/MODEL_LICENSE). Giữ nguồn, tác giả và tên mô hình khi sử dụng/phân phối. Giấy phép MIT của mã SenseVoice không thay thế giấy phép trọng số. |
| Bản chuyển ONNX | k2-fsa, [commit cố định](https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/tree/2365baeacb507f821a0c8120fcee3d484dba7a07), giữ tên SenseVoiceSmall và nguồn trọng số. |
| Silero VAD | Silero Team, [MIT](https://github.com/snakers4/silero-vad/blob/master/LICENSE). Tệp ONNX từ release ASR models của k2-fsa. |
| sherpa-onnx 2.0.1 | k2-fsa, [Apache 2.0](https://github.com/k2-fsa/sherpa-onnx/blob/v2.0.1/LICENSE). AAR static-ORT chính thức được xác minh riêng khi chuẩn bị dependency. |

| Tệp | Byte | SHA-256 |
| --- | ---: | --- |
| `model.int8.onnx` | 239.233.841 | `c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51` |
| `tokens.txt` | 315.894 | `f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc` |
| `silero_vad.onnx` | 643.854 | `9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6` |

Hai bản ghi Mandarin dùng trong kiểm thử được nêu tên, URL, hash và văn bản tham chiếu trong manifest do `scripts/generate_asr_fixtures.py` tạo. Văn bản tham chiếu lấy từ [DataPrepare của SenseVoice tại commit cố định](https://github.com/FunAudioLLM/SenseVoice/blob/586bbdb2b052f4fe19322581825228b6810a9fb3/README.md). Các WAV/video thử chỉ nằm trong thư mục build kiểm chứng và không được thêm vào source assets hoặc APK. Nguồn bộ dữ liệu AISHELL-1 được công bố tại [OpenSLR33](https://www.openslr.org/33/).

Script build kiểm SHA-256 của AAR phát hành trước khi tạo AAR dùng trong ứng dụng. Bản phát hành x86 32 bit của Sherpa dùng ONNX Runtime chia sẻ, trùng thư viện đang có của dự án. AAR của ứng dụng bỏ các thư viện Sherpa x86 32 bit. ASR có thư viện cho ARM64, ARM32 và x86_64, còn Android x86 32 bit tiếp tục dùng OCR và phụ đề văn bản.
