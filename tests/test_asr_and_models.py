"""ASR native runtime isolation, ELF binary patching, Whisper LID, segment recognition,
model repository atomic installation and segmented Range downloads."""
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

from helpers import APP_GRADLE_PATH, ROOT, SCRIPTS_DIR, read_kt, read_rel


class NativeRuntimeAndLidTests(unittest.TestCase):
    def test_gradle_provisions_sherpa_and_forbids_pickfirst_collision(self):
        gradle = APP_GRADLE_PATH.read_text(encoding="utf-8")
        self.assertIn("prepareSherpaRuntime", gradle)
        self.assertIn("isolate_sherpa_ort.py", gradle)
        self.assertIn("fetchLidModel", gradle)
        self.assertIn('noCompress += listOf("onnx")', gradle)
        self.assertNotIn("pickFirst", gradle.split("packaging {", 1)[1].split("dependencies {", 1)[0].replace("// No pickFirst", ""))

    def test_sherpa_native_loads_isolated_ort_before_jni(self):
        sherpa = read_kt("SherpaAsr.kt")
        self.assertLess(sherpa.index('loadLibrary("sherpaort")'), sherpa.index('loadLibrary("sherpa-onnx-jni")'))
        self.assertGreaterEqual(sherpa.count("SherpaNative.ensure()"), 2)
        self.assertIn("SherpaNative.ensure()", read_kt("AudioLanguageIdentifier.kt"))

    def test_speech_bridge_catches_errors_and_clamps_negative_live_pts(self):
        bridge = read_kt("SpeechBridge.kt")
        self.assertIn("catch (e: Throwable)", bridge)
        self.assertIn("notifyErrorOnce", bridge)
        self.assertIn("private fun clampToZero", bridge)
        self.assertIn("val chunk=clampToZero(original) ?: return", bridge)
        self.assertIn("val start=start.coerceAtLeast(0)", read_kt("AudioCoverage.kt"))

    def test_segment_engine_uses_bounded_threads_and_backpressure(self):
        sherpa = read_kt("SherpaAsr.kt")
        self.assertIn("coerceIn(1,2)", sherpa)
        self.assertIn("executor.queue.put(task)", sherpa)
        self.assertIn("VadFrameBuffer()", sherpa)
        self.assertIn("MusicSegmenter", sherpa)


@unittest.skipUnless(subprocess.run(["which", "gcc"], capture_output=True).returncode == 0, "gcc ausente")
class SherpaOrtBinaryPatcherTests(unittest.TestCase):
    def test_isolate_script_renames_soname_and_dt_needed_idempotently(self):
        with tempfile.TemporaryDirectory() as tmp:
            lib_dir = Path(tmp)
            (lib_dir / "ort.c").write_text("int OrtGetApiBase(void) { return 1; }\n", encoding="utf-8")
            (lib_dir / "jni.c").write_text("extern int OrtGetApiBase(void); int probe(void) { return OrtGetApiBase(); }\n", encoding="utf-8")
            subprocess.check_call(
                ["gcc", "-shared", "-fPIC", "-Wl,-soname,libonnxruntime.so", "-o", str(lib_dir / "libonnxruntime.so"), str(lib_dir / "ort.c")]
            )
            subprocess.check_call(
                ["gcc", "-shared", "-fPIC", "-o", str(lib_dir / "libsherpa-onnx-jni.so"), str(lib_dir / "jni.c"), f"-L{lib_dir}", "-lonnxruntime"]
            )
            env = dict(os.environ, AURA_JNILIBS=str(lib_dir))
            for _ in range(2):
                subprocess.check_call(["python3", str(SCRIPTS_DIR / "isolate_sherpa_ort.py")], env=env, stdout=subprocess.DEVNULL)

            self.assertFalse((lib_dir / "libonnxruntime.so").exists())
            self.assertTrue((lib_dir / "libsherpaort.so").exists())
            dyn = subprocess.check_output(["readelf", "-d", str(lib_dir / "libsherpa-onnx-jni.so")], text=True)
            self.assertIn("libsherpaort.so", dyn)
            self.assertNotIn("libonnxruntime.so", dyn)


class ModelRepositoryAndDownloaderTests(unittest.TestCase):
    def test_model_repository_atomic_install_import_and_non_deprecated_tar_api(self):
        repo = read_kt("ModelRepository.kt")
        for token in (
            "fun ensureBundledLid(",
            "fun importArchive(",
            "fun importManifest(",
            "ArchivePaths.isInside(",
            "tar.nextEntry ?: break",
        ):
            self.assertIn(token, repo)
        self.assertNotIn("nextTarEntry", repo)

        manifest_json = read_rel("app/src/main/assets/models_manifest.json")
        for model_id in ("lid", "vad", "sensevoice", "whisper", "whisper-small", "ocr", "ko"):
            self.assertIn(f'"{model_id}"', manifest_json)

    def test_segmented_mirror_downloader_enforces_range_and_sha256(self):
        dl = read_kt("MirrorDownloader.kt")
        for token in (
            "segmentCount(expected,p.ranged)",
            "Content-Range",
            "RangeUnsupported",
            'MessageDigest.getInstance("SHA-256")',
        ):
            self.assertIn(token, dl)


if __name__ == "__main__":
    unittest.main()
