# Prototype dịch local tách biệt — 23/10/2026

**Kết luận: chưa đưa Hybrid vào ứng dụng chính.** OPUS-MT chạy rất nhanh trên máy này nhưng dịch sai phủ định, con số và thành ngữ. Tất cả cấu hình đo được đều không qua ngưỡng chất lượng đã đặt trước khi chạy: chrF ≥ 55, không có lỗi nghiêm trọng về phủ định/con số, và cần đọc kiểm tra bản dịch. Ngưỡng này là tiêu chí kỹ thuật của thử nghiệm, không phải chuẩn chất lượng phổ quát.

Thư mục này không được import bởi Android/Windows Companion. Không có Google/API call, OAuth, truy cập keyring hoặc thay đổi driver/hệ thống. Không có bản dịch local nào được hiển thị cho người dùng trong ứng dụng chính.

## Thiết bị và cách đo

- CPU Intel Core i5-13500H, 12 core / 16 logical processor; RAM vật lý 16.087 MiB (~15,7 GiB).
- GPU NVIDIA RTX 3050 Laptop 6 GiB; driver 610.78.
- Python 3.12.14, CTranslate2 4.8.3, SentencePiece 0.2.2, sacrebleu 2.6.0.
- Model gốc OPUS-MT zho→vie transformer-align ngày 17/06/2020; chuyển trực tiếp Marian `.npz` sang CTranslate2. Model INT8 ~78,9 MiB; FP32 ~299,6 MiB. Không tải PyTorch.
- 100 câu nguồn riêng biệt mỗi cấu hình, gọi nối tiếp từng câu, **batch=1, không cache**, không gom 100 câu thành một batch để làm đẹp độ trễ.
- Thời gian mỗi câu = chuẩn hóa NFKC + SentencePiece encode + inference + decode. Dùng `perf_counter_ns()`, đồng bộ kết quả trước khi dừng đồng hồ. **Không gồm capture/OCR/network/overlay**; đây không phải P95 của ứng dụng.
- Thời gian load và lần inference đầu tiên đo riêng. Cấu hình chính có một lượt làm nóng bằng một câu khác trước 100 câu benchmark. Không tính lần làm nóng vào phân vị.
- RSS lấy mẫu 20 ms, bao gồm runtime của tiến trình Python, model và native buffers; **không phải VRAM**. Đỉnh VRAM chưa đo. Không đo điện năng/chi phí phần cứng.
- Một pass mỗi cấu hình; các lần đo có nhiễu tải Windows/BlueStacks. Không suy ra GPU hoặc beam4 luôn nhanh/chậm hơn từ khác biệt nhỏ của một pass.

## Kết quả thực tế, đơn vị ms

| Cấu hình | n | P50 | P90 | P95 | P99 | BLEU | chrF | Peak RSS MiB |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| CPU INT8, 1 thread, beam1 | 100 | 45,971 | 79,003 | 87,833 | 111,874 | 19,93 | 38,06 | 170,3 |
| CPU INT8, 2 thread, beam1 | 100 | 27,916 | 46,640 | 53,263 | 63,938 | 19,93 | 38,06 | 146,5 |
| CPU INT8, 4 thread, beam1 | 100 | 23,242 | 35,196 | 42,774 | 50,965 | 19,93 | 38,06 | 145,8 |
| CPU INT8, 8 thread, beam1 | 100 | 21,012 | 35,851 | 41,470 | 48,278 | 19,93 | 38,06 | 175,1 |
| CPU INT8, 4 thread, beam4 | 100 | 33,425 | 56,045 | 66,013 | 83,143 | 19,95 | 38,59 | 147,8 |
| CPU FP32, 4 thread, beam4 | 100 | 98,085 | 167,324 | 183,955 | 219,939 | 20,21 | 38,54 | 533,0 |
| GPU INT8/FP16, beam1 | 100 | 34,583 | 65,694 | 73,745 | 91,745 | 20,37 | 38,29 | 467,5 |
| GPU INT8/FP16, beam4 | 100 | 31,242 | 50,174 | 67,620 | 76,567 | 21,03 | 38,87 | 467,1 |

800 lượt inference thực, trên cùng 100 câu đối chiếu, không phải 800 câu độc lập. Không câu nào rỗng hoặc chạm giới hạn 128 token (đầu ra dài nhất 26 token). Tỉ lệ cache hit = 0% theo thiết kế.

Before/After **chỉ trong prototype**, cùng CPU 4 thread và beam4:

| Chỉ số | Before FP32 | After INT8 |
|---|---:|---:|
| P50 | 98,085 ms | 33,425 ms |
| P95 | 183,955 ms | 66,013 ms |
| Peak RSS | 533,0 MiB | 147,8 MiB |
| chrF | 38,54 | 38,59 |

INT8 giảm P95 khoảng 64,1%, nhưng không sửa được chất lượng. Ví dụ CPU INT8/4 thread/beam1: load 202,86 ms, inference đầu 40,09 ms. GPU beam1: load 347,06 ms, inference đầu 224,74 ms; thời gian nạp DLL NVIDIA riêng chưa bao gồm trong hai số này.

Hai lần probe GPU đầu tiên không chạy được inference do thiếu `cublas64_12.dll`; giữ nguyên báo lỗi trong `results/`. Sau đó tải wheel NVIDIA `nvidia-cublas-cu12==12.9.2.10`, chỉ giải nén DLL vào `.tools/local-translation-cuda`, preload đường dẫn tuyệt đối trong tiến trình benchmark. Hai dòng GPU trong bảng là các lượt **đã chạy đủ 100 câu**, không suy ra từ CPU hay từ số GPU báo có.

Wheel cuBLAS tải về khoảng 553 MB, DLL riêng khoảng 736 MiB trên đĩa; cộng bản ZIP/model gốc, hai bản chuyển đổi và thư viện thì prototype chiếm khoảng 2,3 GiB trong `.tools/`. Đây là footprint nghiên cứu có giữ đầu vào tái lập, không phải kích thước APK/bộ cài hay footprint tối thiểu của một sản phẩm local.

## Chất lượng và tập tham chiếu

`dialogue100.jsonl` lấy từ [test công khai của Helsinki-NLP/Tatoeba-Challenge](https://github.com/Helsinki-NLP/Tatoeba-Challenge/blob/5509af44a74016748c26f41ab164b64f48445f3f/data/test/vie-zho/test.txt). Các bản tham chiếu do cộng đồng Tatoeba cung cấp, **không do agent tự viết hay lấy đầu ra model làm đáp án**. Attribution: Tatoeba contributors; compilation by Helsinki-NLP/Jörg Tiedemann, *The Tatoeba Translation Challenge* (2020). Dữ liệu câu Tatoeba dùng [CC-BY 2.0 FR](https://tatoeba.org/en/terms_of_use); bản TSV upstream không có username từng câu, giữ đường dẫn nguồn, revision và số dòng để truy xuất nguồn gốc. Các câu được đảo chiều từ cột Vietnamese–Chinese sang nguồn Chinese–tham chiếu Vietnamese, không sửa nội dung tham chiếu.

Lọc câu nguồn dài 3–42 ký tự, chứa 我/你/您/咱 hoặc dấu hỏi, loại trùng; 213 câu đạt điều kiện, shuffle seed `20261023`, lấy 100 câu đầu. Có cả giản thể và phồn thể. Đây là câu ngắn gần hội thoại, **không phải 100 lượt hội thoại phim liên tiếp**. Không có ngữ cảnh nhân vật; các cách xưng hô khác nhau có thể đều hợp lý. Một số nguồn/tham chiếu có lỗi: câu `娶你的！` có tham chiếu như câu chửi, `知识` được tham chiếu thành “Trí thức”, một câu tiếng Việt không dấu. Giữ những trường hợp này và ghi nhận riêng, không sửa sau khi xem đầu ra.

BLEU/chrF một tham chiếu không chứng minh độ tự nhiên. Chính tác giả OPUS cảnh báo test câu ngắn có thể quá lạc quan. Nguồn test này không phải một đánh giá ngoài miền có kiểm soát; chưa kiểm toán trùng giữa test và dữ liệu huấn luyện của model. Bản chuẩn hóa NFKC + SPM trong prototype không sao chép nguyên script Moses của model gốc. Những giới hạn này không xóa được các lỗi ý nghĩa trực tiếp sau:

| Câu Trung | Đầu ra local thực tế | Lỗi |
|---|---|---|
| 你去就免得我再去了。 | Nếu anh đi thì tôi sẽ đi. | Đảo nghĩa “anh đi thì tôi khỏi đi” |
| 我校图书馆藏书二百五十万册。 | Thư viện của tôi cất giữ 2 triệu quyển sách. | 2,5 triệu thành 2 triệu, beam1 |
| Cùng câu trên, beam4 | Thư viện của tôi cất giữ 250 triệu cuốn sách. | 2,5 triệu thành 250 triệu; FP32 cũng sai |
| 我們是相見恨晚。 | Chúng tôi chỉ là những kẻ thù của nhau. | Thành ngữ tiếc gặp nhau muộn bị đổi thành kẻ thù |
| 他有两下子… | Nó có hai người. | Thành ngữ “có thực lực” bị hiểu thành con số |

Agent đã đọc cả 100 nguồn/tham chiếu/đầu ra CPU greedy; xem `results/agent-review100.json` cho từng câu, gồm 5 lỗi nghiêm trọng về phủ định/con số. Đây là nhận xét chẩn đoán của một agent, **không phải hội đồng người dịch mù**, không công bố “accuracy %”. Các khác biệt beam4 được đọc riêng; lỗi lớn còn nguyên và có thêm câu mất phủ định/chuyển chủ thể. Tất cả chrF < 39, thấp hơn ngưỡng 55 đã ghi trong cấu hình trước khi chạy.

Vì vậy chưa có chế độ Hybrid được bật, chưa có số “thời gian xuất hiện bản dịch local đầu tiên trên Android”, số lần Gemini sửa local hay độ nhấp nháy Hybrid. Gemini hoàn thiện/cost/tokens không đo ở prototype này vì không gọi Google. Không được cộng trừ các số inference local để tuyên bố đã đạt pipeline P95 ≤ 1 giây.

## Tái lập

Tất cả model, wheel và runtime nằm trong `.tools/` và được Git/source package bỏ qua. Trong PowerShell tại thư mục gốc dự án, dùng Python có sẵn của workspace:

```powershell
$py = 'C:\Users\AD\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe'
& $py -m pip install --target .tools/local-translation-deps -r research/local_translation/requirements.txt
& $py research/local_translation/prepare.py
& $py research/local_translation/benchmark.py --threads 4 --beam 1 --output research/local_translation/results/repeat-cpu.json
& $py research/local_translation/validate_results.py
```

Tùy chọn GPU, không cài CUDA toàn hệ thống:

```powershell
& $py -m pip download --no-deps --only-binary=:all: --dest .tools/local-translation-wheels nvidia-cublas-cu12==12.9.2.10
& $py research/local_translation/prepare_cuda.py
& $py research/local_translation/benchmark.py --device cuda --compute-type int8_float16 --threads 1 --beam 1 --output research/local_translation/results/repeat-gpu.json
```

Không chạy các cấu hình song song để so sánh độ trễ. `validate_results.py` đã kiểm tra 8 lượt thành công × 100 inference, đối chiếu nguồn/hashes/phân vị và không bị cắt token. File lỗi GPU được giữ riêng, không gộp thành thành công.

## Trọng số, giấy phép, checksum

[Model card Helsinki-NLP](https://huggingface.co/Helsinki-NLP/opus-mt-zh-vi) công bố Apache-2.0. Tuy nhiên **archive Marian gốc được tải để benchmark chứa LICENSE CC-BY-4.0**; giữ đúng giấy phép artifact đã dùng tại `licenses/OPUS-original-CC-BY-4.0.txt`, không gắn lại Apache cho archive này. Trọng số không nằm trong gói mã nguồn ứng dụng. CTranslate2 hỗ trợ [chuyển Marian trực tiếp](https://opennmt.net/CTranslate2/guides/marian.html).

| Input | SHA256 |
|---|---|
| `opus-2020-06-17.zip` | `4051997e89f4a016fd043eb4a59603868f70e1200331c11169de7ee0e91911bf` |
| `tatoeba-vie-zho.tsv` | `637d6b47c5e8ac5c31348f8bc63ef0ca4ee35299e795ac92acb21d992c64ecb5` |
| `dialogue100.jsonl` | `333173544f116ce3e2db2118b5c645782071c013a5ef9b15fed053a1a11c930b` |
| CTranslate2 INT8 `model.bin` | `46ac4c76bc88978a19fcc04e0c7317ee58cb872b8c2c3f2c23c7ca189b1ac4dc` |
| CTranslate2 FP32 `model.bin` | `0c7029255915602e4491432465c129067766f44780401c83aa2dd3262478e0c0` |
