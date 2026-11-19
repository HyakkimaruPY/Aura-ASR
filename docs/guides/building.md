# Building the Android reference client

## Requirements

- Java 17; Android SDK with compile API 35; Gradle wrapper and Kotlin/Android plugin versions pinned in Gradle files.
- Python 3 for provisioning scripts, package checks and fast tests.
- Internet access to fetch Gradle dependencies, allowed ASR model artifacts and native JNI runtime. Review their licenses before any public redistribution.

## Commands

```bash
python3 -B -I -m unittest discover -s tests -p 'test_*.py'
./gradlew :app:testDebugUnitTest
./scripts/build_apk.sh
```

Artifacts under `app/build`, `build`, `.gradle`, `.apk` and `.aab` are ignored by Git. Signing material comes only from environment variables documented in `app/build.gradle.kts`; do not commit keystores or credentials.

No Android SDK-based build was executed during the current source-only cleanup. Successful host-side source/policy tests cannot replace Android compilation and on-device media playback validation.

## Model-download policy

The stock manifest includes only verified upstream links where license conditions appear compatible. For `sensevoice`, `ocr`, and third-party Whisper Small conversion, download mirrors are deliberately blank until a reviewer approves each exact file and its model license. Use an authorized custom manifest or manual import only after validation.
