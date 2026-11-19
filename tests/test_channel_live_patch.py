"""Regression guards for the HLS channel translation/display patch."""
import unittest
from helpers import read_kt, slice_between

class ChannelLivePatchTests(unittest.TestCase):
    def test_missing_program_date_time_has_common_live_clock(self):
        main = read_kt("MainActivity.kt")
        clock = read_kt("LiveSubtitleClock.kt")
        pcm = read_kt("PcmTap.kt")
        self.assertIn("fun relativeLiveTime(", clock)
        self.assertIn("relativeWindowStart(", clock)
        self.assertIn("LIVE_CLOCK_FALLBACK reason=NO_PROGRAM_DATE_TIME", main)
        self.assertIn("LIVE_PCM_CALIBRATE", main)
        self.assertIn("rawPacketTimeMs", pcm)
        self.assertIn("raw < previousRawTimeMs - 2000", pcm)
        self.assertIn("if (relativeLiveClock) { periodOffsetMs=null; clockReady=false", pcm)
        self.assertIn("tap.setRelativeLiveOffset(offset)", main)
        self.assertNotIn("scoutTap?.setPeriodOffset(-w.positionInFirstPeriodMs)", main)

    def test_live_recovery_backpressure_and_manual_seek_protection(self):
        main = read_kt("MainActivity.kt")
        self.assertIn("LIVE_MAIN_RECOVERY reason=BEHIND_LIVE_WINDOW", main)
        self.assertIn("failingPlayer.seekToDefaultPosition()", main)
        self.assertIn("liveScout.playWhenReady && last > 0", main)
        self.assertIn("scoutAhead", main)
        self.assertIn("maxLiveLead", main)
        self.assertIn("mainPlayer?.isCurrentMediaItemLive != true", main)

    def test_live_caption_selection_does_not_depend_on_the_last_untranslated_fragment(self):
        main = read_kt("MainActivity.kt")
        timeline = read_kt("SubtitleTimeline.kt")
        self.assertIn("timeline.atLive(positionMs", main)
        self.assertIn("TranslationCoverage.playable(cue", timeline)
        self.assertIn("cue.endMs <= positionMs", timeline)
        self.assertIn("SUBTITLE_RENDER playerMs=", main)

if __name__ == "__main__": unittest.main()
