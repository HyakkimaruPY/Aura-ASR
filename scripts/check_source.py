#!/usr/bin/env python3
"""Kotlin/JVM typecheck and JUnit runner without Gradle, dex, APK or native inference.
Requires kotlinc 2.1+ and a directory of dependency JARs (including Android API stubs).
"""
import argparse
import pathlib
import re
import subprocess
import sys
import tempfile

parser = argparse.ArgumentParser()
parser.add_argument("--kotlinc", required=True, type=pathlib.Path)
parser.add_argument("--deps", required=True, type=pathlib.Path)
parser.add_argument("--benchmark", action="store_true")
args = parser.parse_args()

root = pathlib.Path(__file__).resolve().parents[1]
production = sorted((root / "app/src/main/java/com/aurareels/ocrlab").glob("*.kt"))
tests = sorted((root / "app/src/test/java/com/aurareels/ocrlab").glob("*.kt"))
jars = sorted(args.deps.glob("*.jar"))
assert jars, "No dependency JARs found"

with tempfile.TemporaryDirectory(prefix="aura-source-check-") as tmp_str:
    tmp = pathlib.Path(tmp_str)
    combined_src = "\n".join(p.read_text(encoding="utf-8") for p in production)
    stubs = []
    for pkg, prefix, stub_name in [
        ("com.aurareels.ocrlab", r"(?<!\.)R", "R.kt"),
        ("androidx.media3.ui", r"androidx\.media3\.ui\.R", "MediaR.kt"),
    ]:
        groups: dict[str, set[str]] = {}
        for kind, key in re.findall(prefix + r"\.(\w+)\.(\w+)", combined_src):
            groups.setdefault(kind, set()).add(key)
        lines = [f"package {pkg}", "object R {"]
        for kind, keys in sorted(groups.items()):
            lines.append(f"    object {kind} {{")
            for idx, key in enumerate(sorted(keys), 1):
                lines.append(f"        const val {key} = {idx}")
            lines.append("    }")
        lines.append("}")
        stub_file = tmp / stub_name
        stub_file.write_text("\n".join(lines) + "\n", encoding="utf-8")
        stubs.append(stub_file)

    artifact = tmp / "tests.jar"
    subprocess.run(
        [
            str(args.kotlinc),
            "-language-version", "2.1",
            "-api-version", "2.1",
            "-jvm-target", "17",
            *[str(x) for x in production + tests + stubs],
            "-cp", ":".join(map(str, jars)),
            "-d", str(artifact),
        ],
        check=True,
    )
    classes = []
    for test_file in tests:
        classes += [
            f"com.aurareels.ocrlab.{name}"
            for name in re.findall(r"^class (\w+Test)\b", test_file.read_text(encoding="utf-8"), re.M)
        ]
    runtime_jars = [j for j in jars if "android-all" not in j.name and j.name != "android.jar"]
    kotlin_bin = str(args.kotlinc.with_name("kotlin"))
    subprocess.run(
        [
            kotlin_bin,
            "-J--add-modules=jdk.httpserver",
            "-classpath", ":".join(map(str, [artifact] + runtime_jars)),
            f"-Daura.project.root={root}",
            "org.junit.runner.JUnitCore",
            *classes,
        ],
        check=True,
    )
    if args.benchmark:
        subprocess.run(
            [kotlin_bin, "-classpath", ":".join(map(str, [artifact] + runtime_jars)), "com.aurareels.ocrlab.TimelineBenchmark"],
            check=True,
        )

subprocess.run([sys.executable, "-B", "-I", "-m", "unittest", "discover", "-s", str(root / "tests"), "-p", "test_*.py"], check=True)
print("Source/JVM checks passed. No Android APK was compiled.")
