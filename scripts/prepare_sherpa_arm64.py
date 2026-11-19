#!/usr/bin/env python3
"""Provision the sherpa-onnx v1.13.8 *runtime*, not the downloadable ML models.

The source release deliberately excludes native .so binaries and all model weights.
Downloads the official Android release and extracts just ARM64 JNI libs into the
build tree. Requires network. Source: k2-fsa/sherpa-onnx, Apache-2.0.
"""
from pathlib import Path
from urllib.request import Request, urlopen
import io
import tarfile

ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / "app/src/main/jniLibs/arm64-v8a"
BASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/"
ARCHIVE = "sherpa-onnx-v1.13.8-android.tar.bz2"
REQUIRED = ("libsherpa-onnx-jni.so", "libonnxruntime.so")


def main():
    DEST.mkdir(parents=True, exist_ok=True)
    jni = DEST / "libsherpa-onnx-jni.so"
    ort = DEST / "libsherpaort.so"        # nome após isolate_sherpa_ort.py
    ort_raw = DEST / "libonnxruntime.so"  # nome original, antes do isolamento
    if jni.exists() and jni.stat().st_size > 1_000_000 and ((ort.exists() and ort.stat().st_size > 1_000_000) or ort_raw.exists()):
        print("sherpa ARM64: JNI já preparada")
        return
    print("Obtendo runtime JNI ARM64 oficial sherpa-onnx 1.13.8…")
    request = Request(BASE + ARCHIVE, headers={"User-Agent": "AuraOcrLab-Bridge/0.2"})
    with urlopen(request, timeout=180) as response:
        payload = response.read(100_000_000)
    print("Arquivo de runtime recebido:", len(payload), "bytes")
    found = set()
    with tarfile.open(fileobj=io.BytesIO(payload), mode="r:bz2") as archive:
        for member in archive:
            if not member.isfile():
                continue
            parts = member.name.replace('\\', '/').split('/')
            # Typical archive path: ./jniLibs/arm64-v8a/libonnxruntime.so
            parts = [p for p in parts if p not in ('', '.')]
            if len(parts) < 3 or parts[-3:-1] != ['jniLibs', 'arm64-v8a']:
                continue
            name = parts[-1]
            if name not in REQUIRED:
                continue
            assert member.size > 1_000_000 and member.size < 90_000_000, f"Biblioteca suspeita: {name}"
            file = archive.extractfile(member)
            assert file is not None
            output = DEST / name
            with output.open('wb') as out:
                written = 0
                while True:
                    chunk = file.read(131072)
                    if not chunk:
                        break
                    out.write(chunk)
                    written += len(chunk)
                assert written == member.size, f"Tamanho inválido: {name}"
            found.add(name)
            print(name, written, 'bytes')
    missing = set(REQUIRED) - found
    if missing:
        raise RuntimeError(f"JNI ARM64 incompleta: {missing}; confira o release upstream")
    print("JNI ARM64 disponível para build. Nenhum modelo foi embutido.")


if __name__ == "__main__":
    main()
