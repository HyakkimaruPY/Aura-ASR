# Third-party rights and dependency audit — source snapshot 0.7.6

This review records upstream license declarations and distribution conditions known at the time of the cleanup. **It is not legal advice and does not certify every transitively downloaded binary.** Different model files, formats and versions may have different rights.

| Component / source | License or terms | Handling |
| --- | --- | --- |
| Kotlin, Android Gradle plugin, AndroidX Media3/Core | Apache-2.0 / respective upstream notices | Gradle dependencies; retain upstream notices |
| sherpa-onnx runtime | Apache-2.0 | Official Android runtime fetched during build; retain notices; native binaries are not committed |
| Microsoft ONNX Runtime | MIT | Gradle dependency/runtime; retain notice |
| Vosk API | Apache-2.0 | Gradle dependency |
| Listed Vosk small language models (en, fr, zh, ja, it, tr, ko) | Apache-2.0, as listed individually by Vosk model catalog | Downloads on demand, not committed; preserve model provenance and relevant notices |
| Whisper original model source and weights | MIT | Listed tiny/base model conversion; verify specific distributed artifacts and conversion provenance before commercial packaging |
| Silero VAD | MIT in source repository; check exact version/weight | Runtime download, not committed |
| PaddleOCR official source | Apache-2.0 | OCR integration retained; previous third-party repackage mirror removed pending provenance |
| SenseVoice Small model | Model-specific `model-license` label, exact downstream terms not cleared | Auto-download disabled; manual import subject to license review |
| Third-party preconverted Whisper Small | Upstream weights MIT but repackaging provenance not independently established | Auto-download disabled |
| ML Kit on-device Translation | Proprietary Google service terms and attribution requirements | Optional on-device feature on eligible smartphones; **do not assume server redistribution or embedded-TV use is permitted** |
| Apache Commons Compress | Apache-2.0 | Gradle dependency |
| JUnit 4 | Eclipse Public License 1.0 (test-only) | Development/test dependency |
| Gradle wrapper / Gradle | Apache-2.0 | Build tool; wrapper JAR is committed, no APK/build artifacts |

## Actions taken

- Removed unofficial consumer-app chat protocol and all preconfigured shared/public translation backends. An opt-in OpenAI-compatible Chat Completions **schema** remains; no official vendor host or bundled API key.
- Cleared default model manifest mirrors for `sensevoice`, `ocr` and `whisper-small` pending a verified exact artifact/license chain. Manual imports and vetted custom manifests remain supported.
- Disabled automatic discovery and preloaded provider-specific channel seeds. Users must supply links they are authorized to use.
- Removed previous releases/history documents from this source snapshot; ignored APK/AAB/build outputs.
- Replaced sample frame with a synthetic test fixture; large model weights are not committed.

## Terms and official sources

- Vosk model-specific table: https://alphacephei.com/vosk/models
- Vosk code: https://github.com/alphacep/vosk-api
- sherpa-onnx license: https://github.com/k2-fsa/sherpa-onnx/blob/master/LICENSE
- ONNX Runtime license: https://github.com/microsoft/onnxruntime/blob/main/LICENSE
- Whisper code + weights license: https://github.com/openai/whisper/blob/main/LICENSE
- Silero VAD license: https://github.com/snakers4/silero-vad/blob/master/LICENSE
- PaddleOCR license: https://github.com/PaddlePaddle/PaddleOCR/blob/main/LICENSE
- SenseVoice model card: https://huggingface.co/FunAudioLLM/SenseVoiceSmall
- ML Kit API terms: https://developers.google.com/ml-kit/language/translation/translation-terms
- ML Kit translation Android setup: https://developers.google.com/ml-kit/language/translation/android

## Project source licensing

No blanket project-source license has been selected by the repository owner in the source provided here. Do not infer that all code is MIT or Apache-2.0 merely because some dependencies are permissively licensed. The owner should choose a source license and add the correct original-source attribution after an authorship review; until then, no reuse license is implied.
