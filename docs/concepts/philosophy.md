# Philosophy — language access without prohibitive localization cost

**Access:** every small publisher should have an attainable way to create multilingual captions; capacity should not depend only on the size of a localization budget.

**Reuse rather than repeat:** timestamps, confidence, dictionary entries and approved translations are reusable resources. Deduplication and targeted inference reduce compute and spending.

**Move the same architecture to the right machine:** the mobile lab is a deliberately constrained test bed. Production platforms can provide decoding, model management, translation and caption packaging on their own backend, distributing subtitles already aligned to each media item.

**Language priorities:** practical Chinese-to-Brazilian-Portuguese comprehension comes first. Both languages can be source or target when available models support the direction. Other languages are not secondary citizens in the interface: source/target codes and timed subtitle contracts should remain language neutral.

**Accessibility:** readable subtitles, timestamps and support for varied devices matter as much as model accuracy.

**Ethics:** automation supports localization teams rather than promising perfect translation. Preserve authorship, speaker intent and legally authorized access. A platform remains responsible for rights clearance, QA and privacy.

**Server-side vision:** authorized content → media/audio decoding → VAD + ASR and optional OCR → timestamp alignment → cache/terminology → configurable translation backend → export timed subtitle tracks (WebVTT/SRT). The Android app demonstrates individual components; the server is a proposed deployment architecture, not already shipped.

## Research directions (concepts, not shipped features)

- Measure true cost per finished subtitle hour, not theoretical compute savings.
- Separate fast live captioning from archival VOD refinement and human-review passes.
- Implement per-language quality gates and confidence-aware retranslation.
- Support multiple subtitle languages from a shared transcript, minimizing repeat ASR calls.
- Build tenant-separated backend caches and retention controls for commercial platforms.
