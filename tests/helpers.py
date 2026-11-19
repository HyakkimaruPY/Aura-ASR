"""Shared paths, cached source readers and structural validators for the Python test suite."""
from __future__ import annotations

from functools import lru_cache
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
KT_MAIN = ROOT / "app/src/main/java/com/aurareels/ocrlab"
KT_TEST = ROOT / "app/src/test/java/com/aurareels/ocrlab"
RES_DIR = ROOT / "app/src/main/res"
ASSETS_DIR = ROOT / "app/src/main/assets"
SCRIPTS_DIR = ROOT / "scripts"
MANIFEST_PATH = ROOT / "app/src/main/AndroidManifest.xml"
APP_GRADLE_PATH = ROOT / "app/build.gradle.kts"
ROOT_GRADLE_PATH = ROOT / "build.gradle.kts"
PROGUARD_PATH = ROOT / "app/proguard-rules.pro"


@lru_cache(maxsize=None)
def read_kt(filename: str) -> str:
    return (KT_MAIN / filename).read_text(encoding="utf-8")


@lru_cache(maxsize=None)
def read_rel(relative_path: str) -> str:
    return (ROOT / relative_path).read_text(encoding="utf-8")


def slice_between(text: str, start_marker: str, end_marker: str) -> str:
    after_start = text.split(start_marker, 1)[1]
    return after_start.split(end_marker, 1)[0]


def _step_char_literal(text: str, index: int) -> int:
    assert text[index] == "'"
    index += 1
    if text[index] == "\\":
        index += 6 if text.startswith("\\u", index) else 2
    else:
        index += 1
    if index >= len(text) or text[index] != "'":
        snippet = text[max(0, index - 8) : index + 8]
        raise AssertionError(f"Malformed char literal near {snippet!r}")
    return index + 1


def assert_kotlin_lexically_balanced(path: Path) -> None:
    """Verifies package header, balanced braces/brackets/parens, strings, templates and comments."""
    text = path.read_text(encoding="utf-8")
    if path.suffix == ".kt":
        assert text.lstrip().startswith("package com.aurareels.ocrlab"), f"Missing package in {path}"
    stack: list[tuple[str, int]] = []
    pairs = {"{": "}", "(": ")", "[": "]"}
    closing = {"}": "{", ")": "(", "]": "["}
    i = 0
    n = len(text)
    line = 1
    while i < n:
        ch = text[i]
        if ch == "\n":
            line += 1
            i += 1
            continue
        if text.startswith("//", i):
            nl = text.find("\n", i)
            if nl < 0:
                break
            line += 1
            i = nl + 1
            continue
        if text.startswith("/*", i):
            depth = 1
            i += 2
            while i < n and depth > 0:
                if text.startswith("/*", i):
                    depth += 1
                    i += 2
                elif text.startswith("*/", i):
                    depth -= 1
                    i += 2
                else:
                    if text[i] == "\n":
                        line += 1
                    i += 1
            if depth != 0:
                raise AssertionError(f"Unclosed block comment in {path}")
            continue
        if text.startswith('"""', i):
            i += 3
            while i < n:
                if text.startswith('"""', i):
                    i += 3
                    while i < n and text[i] == '"':
                        i += 1
                    break
                if text[i] == "\n":
                    line += 1
                i += 1
            else:
                raise AssertionError(f"Unclosed raw string in {path} near line {line}")
            continue
        if ch == '"':
            i += 1
            while i < n:
                if text[i] == "\\":
                    i += 2
                    continue
                if text[i] == '"':
                    i += 1
                    break
                if text.startswith("${", i):
                    t_depth = 1
                    i += 2
                    while i < n and t_depth > 0:
                        if text[i] == "\n":
                            line += 1
                        if text.startswith('"""', i):
                            i += 3
                            while i < n and not text.startswith('"""', i):
                                if text[i] == "\n":
                                    line += 1
                                i += 1
                            i += 3
                            continue
                        if text[i] == '"':
                            i += 1
                            while i < n:
                                if text[i] == "\\":
                                    i += 2
                                    continue
                                if text[i] == '"':
                                    i += 1
                                    break
                                i += 1
                            continue
                        if text[i] == "'":
                            i = _step_char_literal(text, i)
                            continue
                        if text[i] == "{":
                            t_depth += 1
                        elif text[i] == "}":
                            t_depth -= 1
                        i += 1
                    if t_depth != 0:
                        raise AssertionError(f"Unclosed string template in {path} line {line}")
                    continue
                if text[i] == "\n":
                    raise AssertionError(f"Newline inside single-line string in {path} line {line}")
                i += 1
            else:
                raise AssertionError(f"Unclosed string in {path} line {line}")
            continue
        if ch == "'":
            i = _step_char_literal(text, i)
            continue
        if ch in pairs:
            stack.append((ch, line))
        elif ch in closing:
            if not stack:
                raise AssertionError(f"Unexpected '{ch}' in {path} line {line}")
            top, open_line = stack.pop()
            if top != closing[ch]:
                raise AssertionError(f"Mismatched '{top}' (line {open_line}) vs '{ch}' (line {line}) in {path}")
        i += 1
    if stack:
        raise AssertionError(f"Unclosed {stack[-1]} in {path}")
