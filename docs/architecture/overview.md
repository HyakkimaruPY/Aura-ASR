# Technical architecture and deployment intent

## Implemented Android reference

- `MainActivity`, `AudioCoverage`, `AsrPacer`, `PcmTap`, `LiveSubtitleClock` coordinate live/recorded media timing and look-ahead.
- `SpeechBridge`, `SherpaAsr`, `ModelRepository` orchestrate installed VAD/ASR models and language identification.
- `OcrPipeline`, `SubtitleCropper`, `PaddleTinyRecognizer` handle detection and recognition of embedded subtitles when a compatible local model was installed.
- `GoogleTranslateBridge` (legacy class name retained for binary/source compatibility) orchestrates offline ML Kit, cache/Vault and a **user-configured compatible API**. It no longer calls unofficial Google or guest chat endpoints.
- `TranslationVault`, `SubtitleTimeline`, `SubtitlePackageStore` implement reusable translations, timeline alignment, stateful caption storage and export.

## Proposed scalable backend, not yet implemented

```text
Ingest/authorization → Job queue → Decode workers → ASR/VAD/OCR workers
                 → Language-neutral timed cue store
                 → Per-tenant terminology + exact/semantic cache
                 → Optional API-compatible or owned translation models
                 → Alignment/QA → WebVTT/SRT/HLS subtitle tracks → CDN
```

Suggested controls: tenant-isolated media storage, per-job budgets, rate limits, trace IDs without transcript secrets, model/version pinning, checkpointed processing, signed worker-to-gateway credentials and fallback/cancellation semantics.

Production-grade server implementation, Android benchmark results, pricing analysis, multi-tenant authorization and SLA metrics are **future work**, not claimed capabilities.
