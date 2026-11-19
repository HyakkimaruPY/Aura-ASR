#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [ -z "${ANDROID_HOME:-}" ] && [ -z "${ANDROID_SDK_ROOT:-}" ] && ! grep -q "^sdk\.dir=" local.properties 2>/dev/null; then
  echo "Defina ANDROID_HOME/ANDROID_SDK_ROOT ou crie local.properties com sdk.dir=..."
  exit 1
fi
# Provisiona apenas o runtime JNI ARM64 e o detector embutido (não baixa modelos opcionais de ASR/OCR).
python3 scripts/prepare_sherpa_arm64.py
python3 scripts/isolate_sherpa_ort.py
python3 scripts/fetch_lid_model.py
./gradlew --no-daemon :app:assembleDebug
printf '\nAPK de teste: %s\n' "$PWD/app/build/outputs/apk/debug/app-debug.apk"
