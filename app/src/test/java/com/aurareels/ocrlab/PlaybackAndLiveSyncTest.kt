package com.aurareels.ocrlab

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

/** AsrPacer lookahead/stall hysteresis, HLS live epoch clock, PcmTap PTS capture, startup timeout and deadline scheduling. */
class LiveClockTest {
    @Test fun slidingWindowAndPeriodOffsetCancelWithoutMovingTheCue() {
        val origin=1_794_679_247_767L
        val a=LiveSubtitleClock.periodOffset(origin,0,origin)
        val b=LiveSubtitleClock.periodOffset(origin+30_000,30_000,origin)
        assertEquals(a,b)
        assertEquals(22_128L,LiveSubtitleClock.pcmTime(1_000_022_128_000L,1_000_000_000_000L,b))
    }
    @Test fun relativeLiveClockMapsIndependentHlsWindowsOntoSameMonotonicTimeline() {
        // Scout and main can report different positions when no PROGRAM-DATE-TIME exists.
        val origin = 1000L
        assertEquals(52_000L,LiveSubtitleClock.relativeLiveTime(2000,origin,79_000,88_000))
        assertEquals(52_000L,LiveSubtitleClock.relativeLiveTime(2000,origin,102_000,111_000))
        // Twenty seconds later the live window slides by 20 s; subtitle time advances,
        // without accidentally returning to the beginning of the window.
        assertEquals(72_000L,LiveSubtitleClock.relativeLiveTime(22_000,origin,59_000,68_000))
        val first = LiveSubtitleClock.relativeWindowStart(22_000,origin,68_000)
        assertEquals(59_000L,LiveSubtitleClock.relativeSeekPosition(22_000,origin,68_000,first+59_000))
    }
    @Test fun behindLiveRecoveryAppliesOnlyToLiveStreams() {
        assertTrue(LiveSubtitleClock.shouldRecoverBehindLive(1,1,true))
        assertFalse(LiveSubtitleClock.shouldRecoverBehindLive(1,1,false))
        assertFalse(LiveSubtitleClock.shouldRecoverBehindLive(2,1,true))
    }
    @Test fun reportedExpiredTargetCannotStartAtWindowZeroThirtySecondsLater() {
        val translated=listOf(LiveSubtitleClock.Span(272,4226),LiveSubtitleClock.Span(22128,24706))
        assertNull(LiveSubtitleClock.startAt(30_000,60_000,22_128,20_000,translated))
        assertNull(LiveSubtitleClock.startAt(133_000,163_000,24706,20_000,translated))
    }
    @Test fun startsOnlyOnAnAvailableTranslatedInterval() {
        val spans=listOf(LiveSubtitleClock.Span(22128,24706),LiveSubtitleClock.Span(40_000,43_000))
        assertEquals(40_000L,LiveSubtitleClock.startAt(30_000,60_000,50_000,20_000,spans))
        assertNull(LiveSubtitleClock.startAt(30_000,60_000,50_000,20_000,emptyList()))
    }
    @Test fun pcmUsesCodecPtsAcrossDiscontinuityAndNeverConsumesPlaybackBuffer() {
        val received=ArrayList<Pair<Long,FloatArray>>()
        val tap=PcmTap(now={ 1234L }) { pcm,rate,at -> assertEquals(16000,rate); received.add(at to pcm) }
        fun buffer()=ByteBuffer.allocate(8000).order(ByteOrder.LITTLE_ENDIAN).apply { repeat(4000) { putShort(8192) }; flip() }
        val input=buffer()
        tap.capture(input,16000,1,2,1_000_018_000_000,1_000_000_000_000)
        assertTrue(received.isEmpty()) // wait until the timeline mapping is available
        tap.setPeriodOffset(0)
        tap.capture(input,16000,1,2,1_000_018_000_000,1_000_000_000_000)
        tap.capture(buffer(),16000,1,2,1_000_040_000_000,1_000_000_000_000)
        assertEquals(listOf(18_000L,40_000L),received.map { it.first })
        assertEquals(0,input.position()); assertEquals(8000,input.remaining())
        assertEquals(40_250L,tap.latestTimeMs)
        assertEquals(1234L,tap.lastPacketElapsedMs)
        assertEquals(0.25f,received[0].second[0],0.0001f)
    }
    @Test fun provisionalMusicRemainsVisibleButMustBeUpgradedByApi() {
        val cue=SpeechCue(1000,3000,"I am waiting for you").apply { output="Estou esperando por você"; provider="gtx"; reason="PROVISIONAL:API_TIMEOUT" }
        assertEquals(CoverageState.PROVISIONAL,TranslationCoverage.state(cue,"en","pt"))
        assertFalse(TranslationCoverage.complete(cue,"en","pt"))
        assertEquals("Estou esperando por você",SubtitleTiming.display(cue))
        cue.provider="api"; cue.reason=null
        assertTrue(TranslationCoverage.complete(cue,"en","pt"))
        assertEquals(1000L,cue.startMs); assertEquals(3000L,cue.endMs)
    }
}

class PlaybackRecoveryTest {
    @Test fun aSmallSentenceFragmentRetainsApiContextButInterjectionsKeepTheirShortRoute() {
        val previous=SpeechCue(1000,3000,"They went to the")
        val fragment=SpeechCue(3100,4100,"Island.")
        assertFalse(SubtitleTranslationPolicy.shortRoute(fragment,"en",previous,null))
        assertTrue(SubtitleTranslationPolicy.shortRoute(SpeechCue(3100,4100,"Wow!"),"en",previous,null))
        assertTrue(SubtitleTranslationPolicy.shortRoute(fragment,"en",null,null))
        assertFalse(SubtitleTranslationPolicy.shortRoute(previous,"en",null,fragment))
    }
    private fun input(known: Boolean=false, active: Boolean=false, wait: Long=0, ready: Boolean=true)=
        PacerInput(0,120000,0,0,known,active,.2,false,false,false,20000,mainReady=ready,startupWaitMs=wait)
    @Test fun localOrOnlineFirstFrameCannotWaitForeverForLanguageOrModel() {
        val pacer=AsrPacer()
        assertEquals(MainAction.NONE,pacer.decide(input(wait=24999)).main)
        assertEquals(MainAction.START,pacer.decide(input(wait=25000)).main)
        assertEquals(MainAction.START,pacer.decide(input(known=true,active=true,wait=25000)).main)
        assertEquals(MainAction.NONE,pacer.decide(input(wait=120000,ready=false)).main)
        assertEquals("STARTUP_TIMEOUT",pacer.decide(input(wait=25000)).reason)
    }
    @Test fun recoveryDoesNotImmediatelyFreezeThePictureAgainForMissingAsr() {
        val i=input(known=true,active=true,wait=25000).copy(mainStarted=true,mainPlaying=true,mainPos=5000,scoutPos=15000,recoveryPlayback=true)
        val result=AsrPacer().decide(i)
        assertEquals(MainAction.NONE,result.main)
        assertEquals(1f,result.mainSpeed,0f)
        assertTrue(result.scoutPlay)
        assertEquals(MainAction.RESUME,AsrPacer().decide(i.copy(mainPlaying=false,autoPaused=true)).main)
    }
    @Test fun frozenLivePictureAtZeroDoesNotKeepTranslationsOutsideTheDvrWindow() {
        val anchor=PlaybackStartup.liveAnchor(180000,240000,216000,20000)!!
        assertEquals(196000L,anchor)
        assertFalse(SubtitleWorkPolicy.eligible(196000,200000,0,false))
        assertTrue(SubtitleWorkPolicy.eligible(196000,200000,anchor,false))
        val target=PlaybackStartup.liveTarget(180000,240000,216000,20000,
            listOf(LiveSubtitleClock.Span(196000,200000)),5000)
        assertEquals(196000L,target)
    }
    @Test fun missingOrExpiredTranslationsNeverGateLiveForever() {
        val expired=listOf(LiveSubtitleClock.Span(5000,8000))
        assertNull(PlaybackStartup.liveTarget(180000,240000,216000,20000,expired,5000))
        assertEquals(196000L,PlaybackStartup.liveTarget(180000,240000,216000,20000,expired,25000))
        assertEquals(180000L,PlaybackStartup.liveTarget(180000,240000,0,20000,emptyList(),25000))
        assertEquals(180000L,PlaybackStartup.liveTarget(180000,240000,Long.MAX_VALUE/4,20000,emptyList(),25000))
    }
    @Test fun movingAsrHorizonDoesNotRestartAnUnacknowledgedLiveSeek() {
        assertEquals(196000L,PlaybackStartup.liveTarget(180000,240000,228000,20000,emptyList(),25000,pending=196000))
        assertEquals(208000L,PlaybackStartup.liveTarget(200000,260000,228000,20000,emptyList(),25000,pending=196000))
    }
    @Test fun invalidWindowAndNewSessionWaitAreNotReadiness() {
        assertNull(PlaybackStartup.liveTarget(180000,180000,216000,20000,emptyList(),25000))
        assertNull(PlaybackStartup.liveAnchor(180000,180500,216000,20000))
        assertFalse(PlaybackStartup.expired(0))
        assertFalse(PlaybackStartup.expired(24999))
    }
}

class SubtitleLatencyAndFollowTest {
    @Test fun manualBrowsingSurvivesAppendedRowsAndTranslationsUntilTheNextCue() {
        val follow=LyricFollowController()
        assertTrue(follow.cueChanged("cue1"))
        assertTrue(follow.canCenter)
        follow.beginTouch()
        assertFalse(follow.canCenter)
        assertFalse(follow.endTouch())
        repeat(10) { assertFalse(follow.cueChanged("cue1")); assertFalse(follow.canCenter) }
        assertTrue(follow.cueChanged("cue2"))
        assertTrue(follow.canCenter)
    }
    @Test fun aNewCueDuringDraggingWaitsForRelease() {
        val follow=LyricFollowController()
        follow.cueChanged("one"); follow.beginTouch()
        assertFalse(follow.cueChanged("two"))
        assertFalse(follow.canCenter)
        assertTrue(follow.endTouch())
        assertTrue(follow.canCenter)
        assertFalse(follow.cueChanged("two"))
        follow.reset()
        assertFalse(follow.canCenter)
        assertFalse(follow.cueChanged(null))
        assertTrue(follow.cueChanged("two"))
    }
    @Test fun centerUsesTheViewportAndHasSpaceForTheLastVerse() {
        assertEquals(400,LyricViewport.centeredScroll(600,200,600,1600))
        // Last row ends at 1200; half a viewport of real bottom padding permits centering.
        assertEquals(800,LyricViewport.centeredScroll(1000,200,600,1500))
        assertEquals(300,LyricViewport.centeredScroll(200,800,600,1500))
        assertEquals(0,LyricViewport.centeredScroll(20,100,600,300))
        assertEquals(550,LyricViewport.centeredScroll(600,200,300,1600))
    }
    @Test fun pcmChunkingNeverAggregatesVadWindowsOrLosesSamples() {
        val buffer=VadFrameBuffer(); val frames=ArrayList<FloatArray>()
        val pcm=FloatArray(12023) { it.toFloat() }
        buffer.feed(pcm.copyOfRange(0,4000),frames::add)
        buffer.feed(pcm.copyOfRange(4000,4001),frames::add)
        buffer.feed(pcm.copyOfRange(4001,pcm.size),frames::add)
        assertEquals(247,buffer.size)
        buffer.flush(frames::add)
        assertEquals(24,frames.size)
        assertTrue(frames.all { it.size==512 })
        val emitted=frames.flatMap { it.toList() }
        assertEquals(pcm.toList(),emitted.take(pcm.size))
        assertTrue(emitted.drop(pcm.size).all { it==0f })
        buffer.flush(frames::add); assertEquals(24,frames.size)
        buffer.feed(floatArrayOf(999f),frames::add); buffer.reset(); buffer.flush(frames::add)
        assertEquals(24,frames.size)
    }
    @Test fun nativeTokenTimesSplitWithoutRebuildingSubwordsOrRedistributingSilence() {
        val result=TokenCueTiming.split("Hello world. Goodbye friend!",10000,16000,
            arrayOf("▁Hel","lo","▁world",".","▁Good","bye","▁friend","!"),
            floatArrayOf(.5f,.7f,1f,1.3f,4f,4.3f,4.5f,4.9f),
            floatArrayOf(.2f,.3f,.3f,.1f,.3f,.2f,.4f,.1f),false,false)!!
        assertEquals(listOf("Hello world.","Goodbye friend!"),result.map { it.third })
        assertEquals(10500L,result.first().first)
        assertEquals(11300L,result.first().second)
        assertEquals(14000L,result.last().first)
        assertEquals(14900L,result.last().second)
    }
    @Test fun denseLyricTokensUseShortTimedGroups() {
        val text="你随河流而来路过我门前"
        val result=TokenCueTiming.split(text,2000,8000,text.map { it.toString() }.toTypedArray(),
            FloatArray(text.length) { it*.5f },FloatArray(text.length) { .4f },true,true)!!
        assertEquals(text,result.joinToString("") { it.third })
        assertTrue(result.size>=2)
        assertEquals(2000L,result.first().first)
        assertTrue(result.zipWithNext().all { (a,b)->a.second<=b.first })
        assertTrue(result.all { it.second<=8000 && it.second>it.first })
    }
    @Test fun unavailableOrInvalidNativeAlignmentFallsBackInsteadOfInventingTimes() {
        fun split(tokens: Array<String>, times: FloatArray)=TokenCueTiming.split("Hello world",0,6000,tokens,times,floatArrayOf(),false,false)
        assertNull(split(arrayOf("wrong"),floatArrayOf(1f)))
        assertNull(split(arrayOf("Hello","world"),floatArrayOf(2f,1f)))
        assertNull(split(arrayOf("Hello","world"),floatArrayOf(0f,Float.NaN)))
        assertNull(split(arrayOf("Hello","world"),floatArrayOf(0f,7f)))
        assertNull(split(arrayOf(),floatArrayOf()))
    }
    @Test fun staleVideoWorkIsDeferredButSeekMusicAndExportRemainRecoverable() {
        assertFalse(SubtitleWorkPolicy.eligible(1000,2500,30000,false))
        assertFalse(SubtitleWorkPolicy.eligible(100000,103000,30000,false))
        assertTrue(SubtitleWorkPolicy.eligible(29000,31000,30000,false))
        assertTrue(SubtitleWorkPolicy.eligible(1000,2500,1000,false))
        assertTrue(SubtitleWorkPolicy.eligible(1000,2500,30000,true))
        assertTrue(SubtitleWorkPolicy.eligible(1000,2500,30000,false,export=true))
    }
    @Test fun apiWaitLeavesTimeForFallbackBeforeTheCaptionIsDue() {
        assertEquals(0L,SubtitleWorkPolicy.primaryGrace(30000,30000))
        assertEquals(0L,SubtitleWorkPolicy.primaryGrace(29000,30000))
        assertEquals(12500L,SubtitleWorkPolicy.primaryGrace(44000,30000))
        assertEquals(35500L,SubtitleWorkPolicy.primaryGrace(90000,30000))
        // 0.6.10: a stopped picture (startup/auto-pause) no longer holds near captions for the full API wait.
        assertEquals(0L,SubtitleWorkPolicy.primaryGrace(30000,30000,advancing=false))
        assertEquals(4000L,SubtitleWorkPolicy.primaryGrace(44000,30000,advancing=false))
        assertEquals(2500L,SubtitleWorkPolicy.primaryGrace(34000,30000,advancing=false))
        assertEquals(35500L,SubtitleWorkPolicy.primaryGrace(90000,30000,advancing=false))
    }
    @Test fun liveStartupCannotSpendTheTranslationReserveOnANewerCue() {
        val spans=listOf(LiveSubtitleClock.Span(18000,20500),LiveSubtitleClock.Span(31000,34000))
        assertEquals(18000L,LiveSubtitleClock.startAt(18000,42000,42000,14000,spans,minReserveMs=14000))
        assertNull(LiveSubtitleClock.startAt(18000,42000,42000,14000,spans.drop(1),minReserveMs=14000))
        assertEquals(18000L,LiveSubtitleClock.startAt(18000,42000,32000,14000,spans,minReserveMs=14000))
    }
}

class AsrPacerTest {
    private fun base()=PacerInput(0,25000,22000,0,true,true,0.5,false,false,false,20000)
    @Test fun startsOnlyWithReadyEngine() { assertEquals(MainAction.START,AsrPacer().decide(base()).main) }
    @Test fun languageDetectionNeverCountsAsSubtitleLead() { assertEquals(MainAction.NONE,AsrPacer().decide(base().copy(known=false,engineActive=false,scoutPos=90000)).main) }
    @Test fun longEngineLoadDoesNotStartEarly() { assertEquals(MainAction.NONE,AsrPacer().decide(base().copy(engineActive=false)).main) }
    @Test fun replayIncreasesInitialBudget() { val d=AsrPacer().decide(base().copy(backlogMs=20000,rtf=0.8)); assertEquals(MainAction.NONE,d.main); assertTrue(d.needMs>20000) }
    @Test fun slowCpuBudgetIsFinite() { val d=AsrPacer().decide(base().copy(rtf=1.4)); assertTrue(d.needMs in 20000..90000); assertEquals("RTF_SLOW",d.reason) }
    @Test fun seekHasShortPreroll() { val d=AsrPacer().decide(base().copy(afterSeek=true,readyThrough=8000)); assertEquals(8000L,d.needMs); assertEquals(MainAction.START,d.main) }
    @Test fun warnsBySlowingInsteadOfPausing() { val d=AsrPacer().decide(base().copy(mainStarted=true,mainPlaying=true,readyThrough=4000)); assertEquals(MainAction.NONE,d.main); assertEquals(0.94f,d.mainSpeed) }
    @Test fun onlyHardLowPauses() { assertEquals(MainAction.PAUSE,AsrPacer().decide(base().copy(mainStarted=true,mainPlaying=true,readyThrough=500)).main) }
    @Test fun respectsUserPause() { assertEquals(MainAction.NONE,AsrPacer().decide(base().copy(mainStarted=true,mainPlaying=false,autoPaused=false)).main) }
    @Test fun resumeUsesHysteresis() { val p=AsrPacer(); val i=base().copy(mainStarted=true,autoPaused=true); assertEquals(MainAction.NONE,p.decide(i.copy(readyThrough=2000)).main); assertEquals(MainAction.RESUME,p.decide(i).main) }
    @Test fun scoutPausesForBackpressure() { assertFalse(AsrPacer().decide(base().copy(backlogMs=13000)).scoutPlay) }
    @Test fun scoutRestartsOnlyAfterDrain() { val p=AsrPacer(); assertFalse(p.decide(base().copy(scoutPlaying=false,backlogMs=5000)).scoutPlay); assertTrue(p.decide(base().copy(scoutPlaying=false,backlogMs=2000)).scoutPlay) }
    @Test fun completedShortVideoStarts() { assertEquals(MainAction.START,AsrPacer().decide(base().copy(scoutEnded=true,readyThrough=Long.MAX_VALUE/4)).main) }
    @Test fun bypassPlaysWithoutModelAndStopsScout() { val d=AsrPacer().decide(base().copy(bypass=true,known=false,engineActive=false)); assertEquals(MainAction.START,d.main); assertFalse(d.scoutPlay) }
    @Test fun smoothingCanBeDisabled() { assertEquals(1f,AsrPacer().decide(base().copy(mainStarted=true,mainPlaying=true,readyThrough=3000,smooth=false)).mainSpeed) }
    @Test fun learnsFromInitialStalls() { val p=AsrPacer(); p.noteStall(15000); assertEquals(1,p.initialStalls); assertEquals(25000L,p.decide(base()).needMs); assertEquals(MainAction.NONE,p.decide(base().copy(mainStarted=true,autoPaused=true,readyThrough=12000)).main); p.reset(); assertEquals(0,p.initialStalls) }
    @Test fun noNanFromColdMetrics() { assertTrue(AsrPacer().decide(base().copy(rtf=Double.NaN)).scoutSpeed.isFinite()) }
    @Test fun loadingPausesScoutButLidDoesNot() { val p=AsrPacer(); assertFalse(p.decide(base().copy(engineActive=false)).scoutPlay); assertTrue(p.decide(base().copy(known=false,engineActive=false,scoutPos=150000)).scoutPlay) }
    @Test fun scoutStartsNearLiveEdgeToLeadThePicture() {
        // 24 s window, default position at its start: scout must jump ahead (22 s), not sit with the picture.
        assertEquals(22_000L, LiveSubtitleClock.scoutEdgeTarget(24_000, 0))
        assertEquals(22_000L, LiveSubtitleClock.scoutEdgeTarget(60_000, 0)) // long window: never the far edge
        assertEquals(10_000L, LiveSubtitleClock.scoutEdgeTarget(12_000, 0))
        // Window too short to gain a useful lead: stay on the default position.
        assertNull(LiveSubtitleClock.scoutEdgeTarget(4_000, 0))
        assertNull(LiveSubtitleClock.scoutEdgeTarget(0, 0))
    }
    @Test fun stableLiveClockIgnoresWindowGrowth() {
        // Window grows by one segment (pif unchanged, default moves): the old default-relative clock stepped back 6 s.
        assertEquals(66_000L, LiveSubtitleClock.stableLiveTime(6_000, 0))
        // Window slides: pif advances while the player position drops by the same amount -> same clock.
        assertEquals(LiveSubtitleClock.stableLiveTime(0 + 18_000, 0), LiveSubtitleClock.stableLiveTime(12_000 + 6_000, 0))
    }
    @Test fun liveCaptionStaysUntilSuccessorIsReady() {
        val t=SubtitleTimeline()
        val a=t.add(1000,7000,"hello there my friend",mergeRepetitions=false)!!; a.output="Olá, meu querido amigo"
        t.add(7000,13000,"how are you doing today",mergeRepetitions=false)  // still untranslated
        assertEquals(a.id,t.atLive(8000,"en","pt")?.id)   // held briefly while the next one is pending
        assertEquals(t.all().last().id,t.atLive(12900,"en","pt")?.id) // stale caption is not kept for long
        val b=t.all().last(); b.output="Como você está hoje"
        assertEquals(b.id,t.atLive(8000,"en","pt")?.id)   // replaced as soon as it is ready
        // Hold is bounded: long silence clears the caption.
        assertNull(t.atLive(13000+SubtitleTimeline.LIVE_HOLD_MS+1,"en","pt"))
    }
}
