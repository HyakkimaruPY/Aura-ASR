#!/usr/bin/env bash
# Build release com ofuscação R8. Exige variáveis de assinatura no ambiente:
#   AURA_KEYSTORE AURA_KEYSTORE_PASS AURA_KEY_ALIAS AURA_KEY_PASS
# Mantenha app/build/outputs/mapping/release/mapping.txt privado.
set -euo pipefail
cd "$(dirname "$0")/.."
if [ -z "${ANDROID_HOME:-}" ] && [ -z "${ANDROID_SDK_ROOT:-}" ] && ! grep -q "^sdk\.dir=" local.properties 2>/dev/null; then
  echo "Defina ANDROID_HOME/ANDROID_SDK_ROOT ou crie local.properties com sdk.dir=..."
  exit 1
fi
if [ -z "${AURA_KEYSTORE:-}" ]; then echo "Defina AURA_KEYSTORE (e as demais variáveis de assinatura)."; exit 1; fi
python3 scripts/prepare_sherpa_arm64.py
python3 scripts/isolate_sherpa_ort.py
python3 scripts/fetch_lid_model.py
./gradlew --no-daemon :app:assembleRelease
printf '\nAPK release: %s\n' "$PWD/app/build/outputs/apk/release/app-release.apk"
