#!/usr/bin/env python3
"""Isola a ONNX Runtime do sherpa-onnx da ONNX Runtime do AAR onnxruntime-android.

Problema: o APK carregava DUAS bibliotecas chamadas libonnxruntime.so (a do sherpa e a do AAR do OCR).
O Gradle mantinha só uma (pickFirst) e a outra JNI falhava com
  dlopen failed: cannot locate symbol "OrtGetApiBase"
Solução: renomear a ORT do sherpa para libsherpaort.so (SONAME e DT_NEEDED das JNIs), editando as
strings do .dynstr no próprio lugar (nome novo é mais curto, então nada se desloca). Python puro,
sem patchelf. Idempotente.
"""
import struct
import sys
from pathlib import Path

OLD = b"libonnxruntime.so"
NEW = b"libsherpaort.so"
assert len(NEW) <= len(OLD)
DT_NEEDED, DT_SONAME, DT_STRTAB, DT_STRSZ = 1, 14, 5, 10


def _patch_dynamic(data: bytearray, rename_soname: bool) -> int:
    if data[:4] != b"\x7fELF" or data[4] != 2 or data[5] != 1:
        raise SystemExit("Esperado ELF64 little-endian (arm64).")
    e_phoff = struct.unpack_from("<Q", data, 0x20)[0]
    e_phentsize, e_phnum = struct.unpack_from("<HH", data, 0x36)
    segments, dynamic = [], None
    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        p_type, _fl, p_off, p_vaddr, _pa, p_filesz, _ms, _al = struct.unpack_from("<IIQQQQQQ", data, off)
        if p_type == 1:  # PT_LOAD
            segments.append((p_vaddr, p_off, p_filesz))
        elif p_type == 2:  # PT_DYNAMIC
            dynamic = (p_off, p_filesz)
    if dynamic is None:
        return 0

    def v2o(vaddr):
        for va, fo, sz in segments:
            if va <= vaddr < va + sz:
                return vaddr - va + fo
        raise SystemExit("vaddr fora dos segmentos")

    entries, strtab = [], None
    off, size = dynamic
    for pos in range(off, off + size, 16):
        tag, val = struct.unpack_from("<qQ", data, pos)
        if tag == 0:
            break
        entries.append((tag, val))
        if tag == DT_STRTAB:
            strtab = v2o(val)
    if strtab is None:
        return 0
    changed = 0
    for tag, val in entries:
        if tag == DT_NEEDED or (tag == DT_SONAME and rename_soname):
            start = strtab + val
            if bytes(data[start:start + len(OLD) + 1]) == OLD + b"\0":
                data[start:start + len(OLD) + 1] = NEW + b"\0" * (len(OLD) + 1 - len(NEW))
                changed += 1
    return changed


def main() -> None:
    import os
    root = Path(os.environ.get("AURA_JNILIBS") or Path(__file__).resolve().parents[1] / "app/src/main/jniLibs/arm64-v8a")
    ort = root / "libonnxruntime.so"
    renamed = root / "libsherpaort.so"
    if not ort.exists() and not renamed.exists():
        raise SystemExit("libonnxruntime.so do sherpa ausente: rode prepare_sherpa_arm64.py primeiro")
    if ort.exists():
        data = bytearray(ort.read_bytes())
        _patch_dynamic(data, rename_soname=True)
        renamed.write_bytes(data)
        ort.unlink()                       # o nome original fica livre para o AAR do OCR
        print("ORT do sherpa ->", renamed.name, len(data), "bytes")
    for jni in sorted(root.glob("libsherpa-onnx*.so")):
        data = bytearray(jni.read_bytes())
        n = _patch_dynamic(data, rename_soname=False)
        if n:
            jni.write_bytes(data)
        print(jni.name, "DT_NEEDED reescritos:", n)
    # verificação final: nenhuma JNI do sherpa pode ainda depender do nome original
    for jni in root.glob("libsherpa-onnx*.so"):
        if b"\0" + OLD + b"\0" in jni.read_bytes():
            raise SystemExit(f"{jni.name} ainda referencia {OLD.decode()}")
    print("OK: ORT do sherpa isolada.")


if __name__ == "__main__":
    sys.exit(main())
