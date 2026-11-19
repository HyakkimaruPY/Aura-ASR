# Aura ASR — Accessible Multilingual Subtitles

**Current source:** Android research client v0.7.6 (ARM64). **Primary language pair:** Mandarin Chinese ↔ Brazilian Portuguese. **Architecture goal:** affordable, multilingual subtitle generation across devices and server-side media pipelines.

## Why it exists

Subtitles should not require a platform to pay for expensive manual translation on every title. Aura ASR explores a practical alternative: reusable speech recognition, optical character recognition, terminology memory, and lightweight translation models that can process media automatically. The same design principles that make a prototype feasible on an intermediate or entry-level smartphone can also support scalable server workers.

**The ambition is to reduce per-title localization costs, not to claim that human review is unnecessary.** Timing, accuracy, model size, language pairs, and compute capacity determine the actual production cost. Human editorial review remains important for culturally sensitive or high-value content.

## How it works

```text
Authorized media input (video, live HLS, audio, or embedded subtitles)
    ├─ Audio → language identification → VAD → speech recognition
    └─ Video frames → subtitle region detection → OCR
                    ↓
      Subtitle events with media timestamps
                    ↓
      Local dictionary / quality filters / persistent translation cache
                    ↓
      On-device translation OR optional configured API-compatible gateway
                    ↓
        Timed captions · editor · SRT export
```

The reference Android app handles local videos, direct online media links, user-provided channels, music/lyrics and subtitle packages. It preserves streaming timing and supports background translation workers and look-ahead playback. The **server implementation is a deployment concept, not a finished backend bundled in this repository**.

## Language strategy

- **First-class research focus:** Simplified/Traditional Chinese speech and subtitles with natural **pt-BR** output, plus bidirectional Chinese ↔ Portuguese workflows where the installed models support them.
- **Multilingual by design:** source and target are language codes, not hard-coded Portuguese; Whisper, Vosk and available translation engines cover additional languages according to the actual installed model set.
- **Reality check:** language support varies by ASR engine, OCR recognition vocabulary and translation backend. The app does not guarantee identical accuracy or full language-pair coverage.

## Operating principles

1. **Local-first cost control:** normalize once, skip non-speech, reuse cached subtitles and local phrase memory before invoking remote inference.
2. **Lightweight deployment:** bounded queues, per-provider health limits, model-on-demand installation and ARM64 execution. Performance on entry-level devices must be benchmarked rather than assumed.
3. **Honest synchronization:** use media timestamps, VAD windows and bounded look-ahead; live streams lacking a stable program clock have inherent uncertainty.
4. **Portable integration:** an optional **OpenAI-compatible Chat Completions schema** (`/v1/chat/completions`) lets a platform operate its own gateway/model. No private client endpoints or credentials are included.
5. **Legal and respectful operation:** media sources must be authorized. Restricted model downloads, undocumented public translation gateways, and preloaded third-party stream discovery are disabled.

## Repository map

```text
app/                    Android application, Kotlin source, resource assets and JVM tests
app/src/main/assets/     Model manifest and synthetic OCR test frame (no bundled large weights)
scripts/                Build helpers, model provisioning, source checks
settings.gradle.kts     Android/Gradle module setup
build.gradle.kts        Toolchain configuration
gradle/                 Gradle wrapper files

tests/                  Fast host-side structural and policy tests
docs/
  architecture/         Components, data flow, and server integration target
  concepts/             Vision, principles and research directions
  guides/               Build and secure gateway configuration
  changes/              Current v0.7.6 live synchronization fixes
  legal/                Third-party license and distribution review
```

## Configuring the optional gateway

In **Settings → Translation server**, enter the HTTPS URL of your trusted `/v1/chat/completions` endpoint and its model ID. An optional **session-only gateway access token** stays in memory and is not written into the repository or persistent settings. Keep provider master credentials on your backend, never in an APK. No endpoint is set by default; local translation remains the intended fallback.

The gateway must accept `model`, `messages` and `stream:false` and return `choices[0].message.content` containing JSON with matched cue IDs and a `translation` field. See [gateway contract](docs/architecture/gateway-contract.md).

## Build and tests

```bash
python3 -B -I -m unittest discover -s tests -p 'test_*.py'
./gradlew :app:testDebugUnitTest       # Android SDK and dependencies required
./scripts/build_apk.sh                # debug build; do not commit APKs
```

Build guide: [docs/guides/building.md](docs/guides/building.md). Full Android build is **not verified** in this cleanup environment.

## Documentation

- [Philosophy and goals](docs/concepts/philosophy.md)
- [Architecture and deployment path](docs/architecture/overview.md)
- [Optional API gateway contract](docs/architecture/gateway-contract.md)
- [Dependencies, rights and licensing status](docs/legal/third-party.md)
- [Current live-channel correction notes](docs/changes/live-channel-sync.md)

## Rights and contributions

This repository contains source code; it does **not** grant licenses for third-party video, audio, streams or model weights. Different ASR/OCR models have different licenses and can require additional attribution or permission. Project-source licensing has not yet been selected by the owner; see [legal notes](docs/legal/third-party.md). Commercial deployment must undergo an independent review of the selected model files, platform terms and content rights.