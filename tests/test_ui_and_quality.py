"""UI visual/accessibility invariants across MainActivity and modular controllers,
static UX/usability/performance audit checks, R8 release protection, XML/JSON integrity,
clean documentation state and Kotlin 2.1 checks."""
import json
import re
import unittest
import xml.etree.ElementTree as ET

from helpers import (
    APP_GRADLE_PATH,
    KT_MAIN,
    KT_TEST,
    MANIFEST_PATH,
    PROGUARD_PATH,
    ROOT,
    ROOT_GRADLE_PATH,
    SCRIPTS_DIR,
    assert_kotlin_lexically_balanced,
    read_kt,
    slice_between,
)


class UiAndReleaseProtectionTests(unittest.TestCase):
    def test_no_emojis_or_system_alert_dialogs_across_all_ui_controllers(self):
        emoji_re = re.compile(r"[\u2630\u2699\u25b6\u25a0\u2715\u26a0\u23f3\U0001F300-\U0001FAFF]")
        for ui_file in (
            "MainActivity.kt",
            "UiWidgets.kt",
            "ChannelBrowserController.kt",
            "SubtitleSettingsSheet.kt",
            "MusicPlayerController.kt",
        ):
            src = read_kt(ui_file)
            self.assertNotIn("AlertDialog", src, f"System AlertDialog found in {ui_file}")
            self.assertIsNone(emoji_re.search(src), f"Emoji glyph found in {ui_file}")

    def test_modular_controllers_and_shared_widgets_eliminate_main_bloat(self):
        main = read_kt("MainActivity.kt")
        widgets = read_kt("UiWidgets.kt")
        ch_ctrl = read_kt("ChannelBrowserController.kt")
        self.assertLess(len(main.encode("utf-8")), 190_000)
        self.assertIn("private lateinit var settingsSheet: SubtitleSettingsSheet", main)
        self.assertIn("private lateinit var channelBrowser: ChannelBrowserController", main)
        self.assertIn("private lateinit var musicController: MusicPlayerController", main)
        self.assertIn("tabOrder", read_kt("AppPrefs.kt"))
        self.assertIn('reorderable(view,"tab:$id")', main)
        self.assertIn('reorderable(card,"pl:${list.id}")', main)
        self.assertIn('widgets.reorderable(ui, chip, "cpl:${pl.id}")', ch_ctrl)
        self.assertEqual(main.count("private fun renderFolder("), 1)
        self.assertEqual(widgets.count("fun cardRow("), 1)
        self.assertIn("private fun sourceSpinnerLabels()", main)

        add_link = slice_between(main, "private fun addCurrentLinkTo(", "private fun addLinkToPlaylist(")
        self.assertIn("fileWorker.execute", add_link)

    def test_letterbox_and_horizontal_fill_never_scale_subtitle_view(self):
        main = read_kt("MainActivity.kt")
        zoom_fn = slice_between(main, "private fun setLetterboxZoom(", "private fun isVerticalVideo(")
        self.assertIn("mainView.videoSurfaceView", zoom_fn)
        self.assertNotIn("videoBox", zoom_fn)
        self.assertIn("R.drawable.ic_fill_horizontal", main)

    def test_debug_log_batches_ui_reflow_and_retains_session_file(self):
        main = read_kt("MainActivity.kt")
        log_fn = slice_between(main, "private fun log(message:", "// Shared widget delegates")
        self.assertNotIn("status.text =", log_fn)
        self.assertNotIn("status.text=", log_fn)
        self.assertIn("debugDirty=true", log_fn)
        self.assertIn("debugLogFile.appendText", log_fn)
        self.assertIn("debugNow-debugRenderedAt>=1000", main)

    def test_release_r8_obfuscation_manifest_config_and_no_embedded_secrets(self):
        gradle = APP_GRADLE_PATH.read_text(encoding="utf-8")
        self.assertIn("isMinifyEnabled = true", gradle)
        self.assertIn("isShrinkResources = true", gradle)
        self.assertNotIn('storePassword = "', gradle)

        rules = PROGUARD_PATH.read_text(encoding="utf-8")
        for keep in ("-repackageclasses", "com.k2fsa.sherpa.onnx", "ai.onnxruntime", "org.vosk", "com.sun.jna"):
            self.assertIn(keep, rules)

        manifest = MANIFEST_PATH.read_text(encoding="utf-8")
        for cfg in ("orientation", "screenSize", "smallestScreenSize", "screenLayout", "uiMode", "density"):
            self.assertIn(cfg, manifest)


class UsabilityUxAndPerformanceAuditTests(unittest.TestCase):
    def test_ram_caches_eliminate_main_thread_json_reparse_and_redundant_progress_writes(self):
        prefs = read_kt("AppPrefs.kt")
        self.assertIn("private var entriesCache: List<HistoryEntry>? = null", prefs)
        self.assertIn("private var languagesCache: LinkedHashMap<String, String>? = null", prefs)
        self.assertIn("abs(existing.positionMs - safePos) < 1500L", prefs)

        pl_store = read_kt("PlaylistStore.kt")
        self.assertIn("private var cached: List<OnlinePlaylist>? = null", pl_store)
        self.assertIn("cached?.let { return it }", pl_store)

        local = read_kt("LocalMedia.kt")
        self.assertIn("private class SortKey(", local)
        self.assertIn("private val progressCache = ConcurrentHashMap<String, Pair<Long, Long>>()", local)
        self.assertIn("abs(prev.first - safePos) < 1500L", local)

        catalog = read_kt("ChannelCatalog.kt")
        self.assertIn("private var playlistsCache: List<ChannelPlaylist>? = null", catalog)
        self.assertIn("private var channelsCache: List<LiveChannel>? = null", catalog)
        self.assertIn("private val testedCount = AtomicLong(", catalog)
        self.assertIn("@Synchronized fun channelCountsByPlaylist(): Map<String, Int>", catalog)

    def test_ui_loop_suspends_when_idle_in_background_and_avoids_hot_allocations_or_jni_pixel_loops(self):
        main = read_kt("MainActivity.kt")
        self.assertIn("private fun ensureTickerScheduled()", main)
        self.assertIn("if (inBackground && !started && musicPlayer == null && !sampleMode) return", main)
        self.assertIn("pipeline = OcrPipeline(applicationContext, models, ::log)", main)
        self.assertIn("timeline.lastEndMs()", main)
        self.assertIn("timeline.size()", main)

        timeline = read_kt("SubtitleTimeline.kt")
        self.assertIn("private var snapshotCache: List<SpeechCue>? = emptyList()", timeline)
        self.assertIn("@Synchronized fun size(): Int", timeline)
        self.assertIn("@Synchronized fun lastEndMs(): Long?", timeline)

        thumb = read_kt("ChannelThumbnailer.kt")
        self.assertNotIn("bitmap.getPixel(", thumb)
        self.assertIn("bitmap.getPixels(", thumb)
        self.assertIn("fun peek(url: String): Bitmap?", thumb)

        lyrics = read_kt("LyricsListView.kt")
        self.assertNotIn(".toLong(16)", lyrics)
        self.assertIn(".toLongOrNull(16) ?: rawId.hashCode().toLong()", lyrics)

    def test_ime_keyboard_hygiene_quick_paste_filters_resume_and_editor_context(self):
        widgets = read_kt("UiWidgets.kt")
        self.assertIn("fun textInput(", widgets)
        self.assertIn("TYPE_TEXT_FLAG_CAP_SENTENCES", widgets)
        self.assertIn("fun hideKeyboard(", widgets)
        self.assertIn("fun focusAndShowKeyboard(", widgets)
        self.assertIn("fun urlQuickActionsRow(", widgets)
        self.assertIn("fun filterBar(", widgets)

        main = read_kt("MainActivity.kt")
        close_fn = slice_between(main, "private fun closeSheet()", "private fun showMessage(")
        self.assertIn("widgets.hideKeyboard(open)", close_fn)
        self.assertIn("widgets.urlQuickActionsRow(url)", main)
        self.assertIn("widgets.filterBar(\"Filtrar nesta pasta…\"", main)
        self.assertIn('button("CONTINUAR")', main)
        self.assertIn("playlists.firstUnfinished(list)", main)

        host = read_kt("ContentPageHost.kt")
        self.assertIn("hideSoftInputFromWindow", host)

        ch_ctrl = read_kt("ChannelBrowserController.kt")
        self.assertIn("ui.postDelayed(coalescedRefresh, 180L)", ch_ctrl)
        self.assertIn("thumbnailer.peek(channel.url)", ch_ctrl)
        self.assertIn("if (channel.favoriteAt > 0)", ch_ctrl)
        self.assertIn('widgets.filterBar("Buscar canal em ', ch_ctrl)

        editor = read_kt("SubtitleEditorPage.kt")
        self.assertIn("Buscar fala traduzida ou trecho original…", editor)
        self.assertIn("sourceLine.text = cue.source", editor)
        self.assertIn("val hasChanges = dirty() && !saving", editor)


class SourceXmlJsonAndDocHygieneTests(unittest.TestCase):
    def test_all_kotlin_and_gradle_files_are_balanced_and_free_of_deprecated_constructors(self):
        kt_files = sorted(list(KT_MAIN.glob("*.kt")) + list(KT_TEST.glob("*.kt")) + [ROOT_GRADLE_PATH, APP_GRADLE_PATH])
        self.assertGreaterEqual(len(kt_files), 64)
        deprecated_re = re.compile(r"\bURL\s*\(|\bLocale\s*\(|nextTarEntry")
        for path in kt_files:
            assert_kotlin_lexically_balanced(path)
            text = path.read_text(encoding="utf-8")
            imports = [ln.strip() for ln in text.splitlines() if ln.strip().startswith("import ")]
            self.assertEqual(len(imports), len(set(imports)), f"Duplicate imports in {path.name}")
            if path.suffix == ".kt":
                self.assertIsNone(deprecated_re.search(text), f"Deprecated Java/Commons API in {path.name}")

    def test_check_source_discovers_all_junit_test_classes(self):
        check_py = (SCRIPTS_DIR / "check_source.py").read_text(encoding="utf-8")
        self.assertIn(r"^class (\w+Test)\b", check_py)
        total_at_tests = 0
        discovered = []
        for tf in sorted(KT_TEST.glob("*Test.kt")):
            content = tf.read_text(encoding="utf-8")
            classes = re.findall(r"^class (\w+Test)\b", content, re.M)
            self.assertTrue(classes, f"No top-level *Test class in {tf.name}")
            discovered.extend(classes)
            total_at_tests += len(re.findall(r"@Test\b", content))
        self.assertGreaterEqual(total_at_tests, 278)
        self.assertEqual(len(discovered), len(set(discovered)))

    def test_all_xml_and_json_files_parse_and_obsolete_reports_are_removed(self):
        xml_files = sorted((ROOT / "app/src").rglob("*.xml"))
        self.assertEqual(len(xml_files), 21)
        for xf in xml_files:
            ET.parse(xf)

        for jf in sorted(ROOT.rglob("*.json")):
            json.loads(jf.read_text(encoding="utf-8"))

        for removed in (
            "BUILD_INFO.txt",
            "docs/PLANO_INTERFACE_LEGENDAS_BASE.md",
            "docs/IMPLEMENTACAO_070.md",
            "docs/VALIDACAO.md",
            "docs/VALIDACAO_EXECUCAO.txt",
            "docs/VALIDACAO_RESULTADOS.json",
        ):
            self.assertFalse((ROOT / removed).exists(), f"Obsolete report still present: {removed}")


if __name__ == "__main__":
    unittest.main()
