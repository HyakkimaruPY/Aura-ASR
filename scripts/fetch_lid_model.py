#!/usr/bin/env python3
"""Coloca o detector de idioma (Whisper Tiny multilíngue, INT8) em app/src/main/assets/lid/
para ele ir EMBUTIDO no APK. Requer internet no build. Fonte: k2-fsa/sherpa-onnx (Apache-2.0).
Idempotente: não baixa de novo se os dois arquivos já existem."""
from pathlib import Path
from urllib.request import Request, urlopen
import io
import tarfile

ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / "app/src/main/assets/lid"
URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-tiny.tar.bz2"
FILES = ("tiny-encoder.int8.onnx", "tiny-decoder.int8.onnx")


def main():
    DEST.mkdir(parents=True, exist_ok=True)
    if all((DEST / f).is_file() and (DEST / f).stat().st_size > 1_000_000 for f in FILES):
        print("detector de idioma: já presente em assets/lid")
        return
    print("Baixando detector de idioma (Whisper Tiny)…")
    req = Request(URL, headers={"User-Agent": "AuraOcrLab-Build/0.2"})
    with urlopen(req, timeout=300) as resp:
        payload = resp.read(400_000_000)
    found = set()
    with tarfile.open(fileobj=io.BytesIO(payload), mode="r:bz2") as tar:
        for member in tar:
            name = member.name.replace("\\", "/").split("/")[-1]
            if not member.isfile() or name not in FILES:
                continue
            data = tar.extractfile(member).read()
            assert len(data) > 1_000_000, f"arquivo suspeito: {name}"
            (DEST / name).write_bytes(data)
            found.add(name)
            print(name, len(data), "bytes")
    missing = set(FILES) - found
    if missing:
        raise SystemExit(f"Arquivos ausentes no pacote: {missing}")
    print("OK: detector embutido preparado em", DEST)


if __name__ == "__main__":
    main()
