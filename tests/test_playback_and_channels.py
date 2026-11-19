"""Playback pacing, live HLS clock, authorized-source discovery, playlist UX, and OCR wiring."""
import unittest

from helpers import read_kt, slice_between


class PlaybackPacingAndLiveClockTests(unittest.TestCase):
    def test_embedded_text_tracks_disabled_on_both_scout_and_main_players(self):
        main = read_kt("MainActivity.kt")
        build_fn = slice_between(main, "private fun buildPlayer(", "private fun clockOf(")
        after_scout = build_fn.split("// Embedded/CEA", 1)[1]
        self.assertIn("setTrackTypeDisabled(C.TRACK_TYPE_TEXT,true)", after_scout)
        self.assertIn("mainView.subtitleView?.visibility=View.GONE", main)
        self.assertIn("translatedSubtitleView else null", main)

    def test_live_epoch_clock_and_pre_start_anchor_keep_cues_eligible(self):
        main = read_kt("MainActivity.kt")
        self.assertNotIn("anchorLiveTap", main)
        self.assertIn("private var anchored = false", main)
        self.assertIn("private fun clockOf(player: ExoPlayer)", main)
        self.assertIn("renderSpeechCue(clockOf(it))", main)
        self.assertIn("val scoutSpeed = if (realtime) 1f", main)
        self.assertIn("val scoutPlay = if (realtime) !speech.bypass", main)

        control_fn = slice_between(main, "private fun controlAsr(", "// ------------------------------------------------------------------ fullscreen")
        before_when = control_fn.split("when(action)", 1)[0]
        self.assertIn("PlaybackStartup.liveAnchor(", before_when)
        self.assertIn("translations.updatePlaybackPosition(preAnchor,false)", before_when)
        self.assertIn("translations.updatePlaybackPosition(anchor,false)", control_fn)
        self.assertIn("startupWaitMs=waitMs", control_fn)
        self.assertIn("pendingLiveStart)", control_fn)

        render_fn = slice_between(main, "private fun renderSpeechCue(", "private fun testFrame(")
        self.assertIn("if(mainStarted || mainPlayer?.isCurrentMediaItemLive!=true", render_fn)

    def test_local_audio_decoder_drops_stale_opens_and_unpauses_new_media(self):
        dec = read_kt("LocalAudioDecoder.kt")
        self.assertIn("worker.queue.clear(); paused=false", dec)
        self.assertLess(dec.index("if(token!=epoch.get()) return@execute"), dec.index("val extractor=MediaExtractor()"))
        self.assertIn("LOCAL_AUDIO_FIRST_PCM", dec)

    def test_ocr_pipeline_shares_translation_bridge_and_srt_formatter(self):
        ocr = read_kt("OcrPipeline.kt")
        self.assertIn("GoogleTranslateBridge(context, status)", ocr)
        self.assertIn('translationService.configure("zh", "pt")', ocr)
        self.assertIn("translationService.translate(cue.translation)", ocr)
        self.assertIn("SubtitleTimeline.formatSrtEntries(", ocr)
        self.assertIn("translation.endMs = if (value == Long.MAX_VALUE)", ocr)
        self.assertNotIn("com.google.mlkit", ocr)


class ChannelsAndStreamsTests(unittest.TestCase):
    def test_manual_channels_keep_m3u_insertion_order_and_skip_auto_revalidation(self):
        cat = read_kt("ChannelCatalog.kt")
        add_fn = slice_between(cat, "@Synchronized fun addManual(", "@Synchronized fun markPlayed(")
        self.assertIn("-1_000_000", add_fn)
        self.assertIn("playlistId: String = selectedPlaylistId", add_fn)
        reval_fn = slice_between(cat, "fun revalidateAll(", "fun revalidate(")
        self.assertIn("filterNot { it.manual }", reval_fn)

    def test_custom_channel_extractor_has_no_embedded_provider_urls(self):
        src = read_kt("ChannelDiscoverySource.kt")
        self.assertIn('OTTERA_TEMPLATE = ""', src)
        self.assertNotIn("stream.ads.ottera.tv", src)
        self.assertIn("network_id|net_id|nid", src)
        self.assertIn("OTTERA_PRIORITY_IDS", src)
        self.assertIn("OTTERA_PRIORITY_IDS: List<Int> = emptyList()", src)
        self.assertNotIn("otteravision.com", src)

        val_kt = read_kt("HlsChannelValidator.kt")
        transport_fn = slice_between(val_kt, "class ChannelHttpTransport(", "private fun resolveUrl(")
        self.assertIn('if (offset > 0) setRequestProperty("Range"', transport_fn)
        self.assertIn("#EXT-X-SESSION-DATA:", val_kt)

    def test_channel_playlists_default_empty_and_smart_add_source_routing(self):
        cat = read_kt("ChannelCatalog.kt")
        self.assertIn("migrateAndEnsureDefaultPlaylist()", cat)
        self.assertIn("OTTERA_PLAYLIST_ID = ChannelDiscoverySource.OTTERA_ID", cat)
        for fn in ("fun playlists()", "fun userPlaylists()", "fun createPlaylist(", "fun renamePlaylist(", "fun deletePlaylist(", "fun configureSource("):
            self.assertIn(fn, cat)

        ctrl = read_kt("ChannelBrowserController.kt")
        self.assertIn('"Adicionar fonte de canais"', ctrl)
        self.assertIn('"Criar playlist"', ctrl)
        add_fn = slice_between(ctrl, "fun promptAddSource(", "private fun promptCreatePlaylistForSource(")
        self.assertIn("val existingUserPlaylists = catalog.userPlaylists()", add_fn)
        self.assertIn("if (existingUserPlaylists.isEmpty())", add_fn)
        self.assertIn("promptCreatePlaylistForSource(", add_fn)
        self.assertIn("promptChoosePlaylistForSource(", add_fn)

    def test_channel_discovery_suspends_during_video_music_and_background(self):
        main = read_kt("MainActivity.kt")
        self.assertIn("val suspended = inBackground || started || musicPlayer != null", main)
        self.assertIn("channelBrowser.updateDiscoverySuspension(suspended)", main)
        self.assertIn("channelThumbnailer.setPaused(", main)


if __name__ == "__main__":
    unittest.main()
