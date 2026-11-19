"""Music player controller, foreground service, shared ASR/decoder flow, LRC/embedded lyrics,
provisional gate release and ContentPageHost backdrop preservation."""
import unittest

from helpers import MANIFEST_PATH, read_kt, slice_between


class MusicServiceAndPlayerTests(unittest.TestCase):
    def test_manifest_and_service_declare_foreground_media_playback(self):
        manifest = MANIFEST_PATH.read_text(encoding="utf-8")
        for item in (
            "FOREGROUND_SERVICE_MEDIA_PLAYBACK",
            "POST_NOTIFICATIONS",
            "WAKE_LOCK",
            'android:foregroundServiceType="mediaPlayback"',
            ".MusicService",
        ):
            self.assertIn(item, manifest)

        svc = read_kt("MusicService.kt")
        self.assertIn("FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK", svc)
        self.assertIn("Notification.MediaStyle", svc)
        self.assertIn("START_NOT_STICKY", svc)

    def test_background_music_survives_onstop_and_uses_wake_lock(self):
        main = read_kt("MainActivity.kt")
        on_stop = slice_between(main, "override fun onStop()", "override fun onDestroy()")
        self.assertNotIn("musicPlayer?.pause()", on_stop)
        self.assertIn("localDecoder.paused = musicPlayer==null", on_stop)
        self.assertIn("setWakeMode(C.WAKE_MODE_LOCAL)", main)
        self.assertIn("moveTaskToBack(true)", main)

    def test_full_screen_player_and_lyrics_page_keep_dynamic_backdrop(self):
        music_ctrl = read_kt("MusicPlayerController.kt")
        now_fn = slice_between(music_ctrl, "fun showNowPlaying()", "fun showLyricsPage(")
        self.assertNotIn("openSheet(", now_fn)
        self.assertIn("TOCANDO AGORA", now_fn)
        self.assertIn("isFillViewport=true", now_fn)
        self.assertIn("content.gravity=Gravity.CENTER_VERTICAL", now_fn)

        host = read_kt("ContentPageHost.kt")
        self.assertIn("if (view.background == null) view.setBackgroundColor(Color.BLACK)", host)


class SharedMusicAsrAndLyricsTests(unittest.TestCase):
    def test_music_shares_single_decoder_and_restore_flow_with_video(self):
        main = read_kt("MainActivity.kt")
        self.assertEqual(main.count("localDecoder.start("), 1)
        self.assertEqual(main.count("private fun restoreCues("), 1)
        self.assertEqual(main.count("private fun snapshotCues("), 1)
        self.assertGreaterEqual(main.count("restoreCues("), 3)
        self.assertGreaterEqual(main.count("snapshotCues("), 3)

        prep = slice_between(main, "private fun prepareLyrics(", "private fun lyricCues(")
        order = [prep.index(k) for k in ("cachedAsr!=null", "lines.isNotEmpty() && synced", "asrPossible(remembered)", "lines.isNotEmpty() ->")]
        self.assertEqual(order, sorted(order))
        self.assertIn('it.reason="ECHO:ORIGINAL"', prep)

    def test_music_gate_releases_on_playable_and_prioritizes_active_verse(self):
        main = read_kt("MainActivity.kt")
        render_music = slice_between(main, "private fun renderMusic()", "// ---- Online: playlists")
        self.assertIn("TranslationCoverage.playable(", render_music)
        self.assertIn("translations.ensure(cue)", render_music)
        self.assertIn("musicController.render(", render_music)

        list_view = read_kt("LyricsListView.kt")
        self.assertIn("text=if(same) cue.source else translated", list_view)


if __name__ == "__main__":
    unittest.main()
