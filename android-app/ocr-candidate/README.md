# Debug OCR candidate

This module extends the existing Android app with an experimental PaddleOCR backend. It is attached through the app's `debugImplementation` dependency; the production engine is not selected by this module. Public-crop OCR screening and a debug harness do not establish live end-to-end translation latency or dialogue/context accuracy.

SDK source was copied from [PaddlePaddle/PaddleOCR at dab3fe35379033fdcb2d0e9572fac0b36c9a9ebf](https://github.com/PaddlePaddle/PaddleOCR/tree/dab3fe35379033fdcb2d0e9572fac0b36c9a9ebf/deploy/ppocr-android/ppocr-sdk), rather than creating another Android project. Local CTC shape/dictionary validation and serialized native lifetime wrappers are recorded in [NOTICE](NOTICE); upstream copyright headers and the [Apache-2.0 LICENSE](LICENSE) remain. The wrapper keeps one native owner and waits for inference before release. Existing requestId, regionId and screenGeneration guards must still run before any result is displayed.

The two ONNX weights are ignored by this module's `.gitignore`, so a fresh checkout needs them prepared. The recognizer YML is tracked and must match the pinned model dictionary. Run from the repository root with Python 3.9 or newer; only the standard library is required:

```text
python scripts/prepare_paddle_ocr_candidate.py --verify-only
python scripts/prepare_paddle_ocr_candidate.py --download-missing
```

The first command verifies locally and never uses the network. Verification is also the default without arguments. The second explicitly downloads only missing public weights, then verifies their exact size and SHA-256. It makes no Gemini calls and needs no Hugging Face login, token or API key. It does not read environment files, home configuration, tokens, cookies or proxy settings. Initial URLs are restricted to the three pinned sources below; redirects may use only HTTPS Hugging Face/CDN hosts. There is no arbitrary model/revision/URL/output-directory CLI option.

| Asset under `src/main/assets/models/` | Bytes | SHA-256 |
|---|---:|---|
| `det/inference.onnx` | 1780590 | `193bab7a04fca699a6c82e6abb5b81bdb28177f0abd4062552b04908dafb19f8` |
| `rec/inference.onnx` | 4462639 | `9ef676d6ed3c88256a2d92c640c44f25b0c40947e111b14b8be8f594091563e6` |
| `rec/inference.yml` | 55571 | `66170210bad538e83fff3c4a3867e547d6bf20b50d64b20347c4b913f3034ea1` |

Detector source: [PaddlePaddle/PP-OCRv6_tiny_det_onnx, revision 2ba1506c0380b8f0b03dd142459aac66d4421f6c](https://huggingface.co/PaddlePaddle/PP-OCRv6_tiny_det_onnx/tree/2ba1506c0380b8f0b03dd142459aac66d4421f6c). Recognizer and dictionary source: [PaddlePaddle/PP-OCRv6_tiny_rec_onnx, revision 2612ab37152ae0a677521bae4e1e3d4fb4cf7c30](https://huggingface.co/PaddlePaddle/PP-OCRv6_tiny_rec_onnx/tree/2612ab37152ae0a677521bae4e1e3d4fb4cf7c30). Both official model cards declare Apache-2.0. Preserve their provenance, this module's license and attribution if distributing an APK containing these assets.

Every existing asset is checked before any download. A mismatched existing weight or modified/missing tracked YML fails clearly and remains untouched; restore the correct tracked dictionary or investigate the weight instead of overwriting it. Streams are bounded to the exact expected size and verified in a unique temporary file in the destination directory. Publication uses an atomic hard link that cannot overwrite a concurrently created file. A filesystem without that support fails closed. Temporary files created by this run are cleaned on success or failure; unrelated files are preserved. Existing correct weights are verified and reused without another download. The tracked YML is never downloaded or rewritten by the preparation script.

Preparation regression tests use mocked public responses and temporary test directories:

```text
python -m pytest tests/test_paddle_candidate_prepare.py -q --basetemp=build/pytest-paddle-prepare
```

They cover fixed pins, offline default, missing/existing assets, altered dictionary, exact-size/SHA failures, interrupted transfers, preservation of concurrent destinations, atomic publication failure, URL/path restrictions and credential-free proxy configuration. Running those tests does not download a model or perform OCR. Native quality, sustained memory, device ABI loading and fresh live YouTube subtitle-to-display measurements remain separate checks before any production proposal or sub-1s claim.
