package com.aurareels.ocrlab

import java.nio.file.Files
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

/** Timeline ordering, binary search, human timing overlays, audio coverage intervals, episode sorting and atomic local cache. */
class SubtitleTimingEditTest {
    @Test fun timeTextParsesCommonFormats() {
        assertEquals(5000L,SubtitleTimeText.parse("5"))
        assertEquals(5250L,SubtitleTimeText.parse("5.25"))
        assertEquals(5250L,SubtitleTimeText.parse("5,25"))
        assertEquals(65_500L,SubtitleTimeText.parse("1:05.5"))
        assertEquals(3_723_004L,SubtitleTimeText.parse("1:02:03.004"))
        assertEquals(0L,SubtitleTimeText.parse("0:00"))
    }
    @Test fun timeTextRejectsGarbage() {
        listOf("","abc","1:2:3:4","1:75","1:61:00","-5","5.","5.1234","1..2",":30","1:.5").forEach { assertNull("'$it'",SubtitleTimeText.parse(it)) }
    }
    @Test fun timeTextRoundTrips() {
        listOf(0L,1L,999L,1000L,61_234L,3_599_999L,3_600_000L,7_325_678L).forEach { assertEquals(it,SubtitleTimeText.parse(SubtitleTimeText.format(it))) }
    }
    @Test fun validationRequiresMinimumDuration() {
        assertNull(SubtitleTimeText.validate(1000,1000+SubtitleTiming.MIN_DURATION_MS))
        assertNotNull(SubtitleTimeText.validate(1000,1100))
        assertNotNull(SubtitleTimeText.validate(null,1100))
        assertNotNull(SubtitleTimeText.validate(1000,null))
    }
    @Test fun liveOverlapsNeverHideReadyPortugueseBehindAnUntranslatedFragment() {
        val timeline = SubtitleTimeline()
        val ready = timeline.add(95_000,101_000,"Una nueva huelga de médicos de la sanidad pública",mergeRepetitions=false)!!
        ready.output = "Uma nova greve dos médicos da saúde pública"
        ready.reason = "PROVISIONAL:API_TIMEOUT"
        timeline.add(95_700,96_100,"sufr",mergeRepetitions=false)!!
        // The ordinary timeline clips the previous cue at the new cue's start.
        assertNull(timeline.at(95_800)?.output)
        assertEquals(ready.id,timeline.atLive(95_800,"es","pt")?.id)
        assertEquals(ready.id,timeline.atLive(99_000,"es","pt")?.id)
        assertNull(timeline.atLive(101_100,"es","pt"))
    }
    @Test fun liveLookupDoesNotResurrectSuppressedOrExpiredCaptions() {
        val timeline=SubtitleTimeline()
        val blocked=timeline.add(95_000,99_000,"ruído",mergeRepetitions=false)!!
        blocked.applyHuman("",true)
        assertNull(timeline.atLive(96_000,"es","pt"))
        assertNull(timeline.atLive(100_000,"es","pt"))
    }
    @Test fun movingStartKeepsStableId() {
        val cue=SpeechCue(1000,3000,"Hello"); val id=cue.id
        assertTrue(cue.applyTiming(1500,3600))
        assertEquals(id,cue.id); assertEquals(1500L,cue.startMs); assertEquals(3600L,cue.endMs); assertEquals(1000L,cue.originStartMs); assertTrue(cue.humanTimed)
        val copy=cue.snapshot()
        assertEquals(id,copy.id); assertEquals(1500L,copy.startMs); assertEquals(1000L,copy.originStartMs); assertTrue(copy.humanTimed)
    }
    @Test fun invalidWindowIsRejectedAndLeavesCueUntouched() {
        val cue=SpeechCue(1000,3000,"Hello")
        assertFalse(cue.applyTiming(-1,3000)); assertFalse(cue.applyTiming(2000,2100))
        assertEquals(1000L,cue.startMs); assertEquals(3000L,cue.endMs); assertFalse(cue.humanTimed)
    }
    @Test fun timelineResortsAndFindsMovedCue() {
        val timeline=SubtitleTimeline()
        val a=timeline.add(1000,2000,"first",mergeRepetitions=false)!!
        val b=timeline.add(5000,6000,"second",mergeRepetitions=false)!!
        timeline.applyTimings(listOf(SubtitlePackageStore.Timing(b.id,200,900)))
        assertEquals(listOf("second","first"),timeline.all().map { it.source })
        assertEquals("second",timeline.at(500)?.source)
        assertNull(timeline.at(5500))                       // the old position no longer shows it
        assertEquals("first",timeline.at(1500)?.source)
        assertEquals(a.id,timeline.all().last().id)
    }
    @Test fun humanTimedCueEndsExactlyWhereSet() {
        val cue=SpeechCue(1000,2000,"A long long long sentence that would normally be extended for reading time")
        cue.output="Uma frase longa longa longa que normalmente seria estendida para dar tempo de leitura"
        assertTrue(SubtitleTiming.readableEnd(cue)>2000)
        cue.applyTiming(1000,2000)
        assertEquals(2000L,SubtitleTiming.readableEnd(cue))
        assertEquals(1500L,SubtitleTiming.readableEnd(cue,1580L))   // still never overlaps the next cue
    }
    @Test fun recognitionRevisitingOriginalAudioDoesNotDuplicateMovedCue() {
        val timeline=SubtitleTimeline()
        val moved=timeline.add(1000,2000,"Hello",mergeRepetitions=false)!!
        timeline.applyTimings(listOf(SubtitlePackageStore.Timing(moved.id,4000,5000)))
        assertNull(timeline.add(1000,2000,"Hello",mergeRepetitions=false))
        assertEquals(1,timeline.all().size)
    }
    @Test fun restoredTimelineKeepsDuplicateGuardForMovedCue() {
        val moved=SpeechCue(1000,2000,"Hello").also { it.applyTiming(4000,5000) }
        val timeline=SubtitleTimeline(); timeline.restore(listOf(moved.snapshot()))
        assertNull(timeline.add(1000,2000,"Hello",mergeRepetitions=false))
        assertEquals(4000L,timeline.all().single().startMs)
    }
    @Test fun srtExportUsesEditedTimes() {
        val cue=SpeechCue(1000,2000,"Hello").apply { output="Olá"; applyTiming(61_000,63_500) }
        assertTrue(SubtitleTimeline().exportSrt(listOf(cue)).contains("00:01:01,000 --> 00:01:03,500"))
    }
}

class PackageAndStreamContractTest {
    @Test fun reopeningNaturalEofRestartsButPartialConsumptionResumes() {
        assertEquals(0L,PlaybackStartup.resumePosition(100000,100000))
        assertEquals(0L,PlaybackStartup.resumePosition(99500,100000))
        assertEquals(40000L,PlaybackStartup.resumePosition(40000,100000))
        assertEquals(2000L,PlaybackStartup.resumePosition(2000,0))
        assertEquals(0L,PlaybackStartup.resumePosition(-50,100000))
    }
    @Test fun coveragePreservesSilenceAndSeekHoles() {
        val merged=AudioCoverage.merge(listOf(AudioInterval(5000,9000),AudioInterval(0,2000),AudioInterval(1000,3000),AudioInterval(9000,12000)))
        assertEquals(listOf(AudioInterval(0,3000),AudioInterval(5000,12000)),merged)
        assertEquals(3000L,AudioCoverage.firstGap(0,merged)); assertEquals(12000L,AudioCoverage.firstGap(6000,merged))
        assertFalse(AudioCoverage.contains(2000,6000,merged)); assertTrue(AudioCoverage.contains(5000,6000,merged))
    }
    @Test fun coverageSplitsOverlappingPcmWithoutReprocessingSavedAudio() {
        val coverage=listOf(AudioInterval(1000,3000),AudioInterval(5000,8000))
        assertEquals(listOf(AudioInterval(0,1000),AudioInterval(3000,5000),AudioInterval(8000,9000)),AudioCoverage.uncovered(0,9000,coverage))
        assertEquals(emptyList<AudioInterval>(),AudioCoverage.uncovered(1500,2500,coverage))
        assertEquals(listOf(AudioInterval(3000,3200)),AudioCoverage.uncovered(2900,3200,coverage))
        assertEquals(listOf(AudioInterval(8000,9000)),AudioCoverage.uncovered(7500,9000,coverage))
    }
    @Test fun humanEditsWinAgainstLateCallbacksAndSuppressionSurvivesRestore() {
        val cue=SpeechCue(1000,2200,"Hello")
        cue.output="Olá"; cue.applyHuman("Minha revisão"); cue.output="Resposta atrasada"
        assertEquals("Minha revisão",cue.output); assertTrue(TranslationCoverage.complete(cue,"en","pt"))
        cue.applyHuman("",true); cue.reason="PROVISIONAL:late callback"; cue.output="Reapareceu"
        assertTrue(cue.suppressed); assertEquals("",cue.output); assertTrue(TranslationCoverage.complete(cue,"en","pt"))
        val timeline=SubtitleTimeline(); timeline.restore(listOf(cue))
        assertNull(timeline.add(1000,2200,"Hello")); assertNull(timeline.at(1500)); assertEquals("",timeline.exportSrt())
    }
    @Test fun stableIdsDoNotDependOnTranslationOrMutableEnd() {
        val first=SpeechCue(50,1000,"原文"); first.output="Texto"; first.endMs=2000
        assertEquals(first.id,SpeechCue(50,4000,"原文").id)
        assertNotEquals(first.id,SpeechCue(51,4000,"原文").id)
    }
    @Test fun binaryLookupMatchesReferenceAtBoundariesAndOutOfOrderRestore() {
        val cues=(0 until 500).map { i -> SpeechCue(i*3000L,i*3000L+1800,"line $i").apply { output="linha $i" } }
        val timeline=SubtitleTimeline(); timeline.restore(cues.shuffled(Random(8)))
        for(at in -1L..1_500_000L step 79) {
            val index=cues.indexOfLast { at>=it.startMs }
            val expected=if(index<0) null else cues[index].takeIf { at<SubtitleTiming.readableEnd(it,cues.getOrNull(index+1)?.startMs) }
            assertSame("position=$at",expected,timeline.at(at))
        }
    }
    @Test fun sniffDistinguishesCatalogHlsDashAndProgressiveWithoutExtensions() {
        val base="https://test.example/stream?token=a%2Bb&expires=9"
        val hls="#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nseg.ts"
        assertEquals(StreamKind.HLS,StreamResolver.inspect(base,hls.toByteArray()).kind)
        val catalog=StreamResolver.inspect(base,"#EXTM3U\n#EXTINF:-1,Meu canal\n/live?id=20&token=x\n#EXTINF:-1,Outro\nhttps://cdn.example/no-extension".toByteArray())
        assertEquals(StreamKind.CATALOG,catalog.kind); assertEquals("Meu canal",catalog.entries[0].name)
        assertEquals("https://test.example/live?id=20&token=x",catalog.entries[0].url)
        assertEquals(StreamKind.DASH,StreamResolver.inspect(base,"<?xml version=\"1.0\"?><MPD type=\"static\"></MPD>".toByteArray()).kind)
        assertEquals(StreamKind.PROGRESSIVE,StreamResolver.inspect(base,byteArrayOf(0,0,0,20)+"ftypisom".toByteArray()).kind)
        assertEquals(base,StreamResolver.inspect(base,hls.toByteArray()).url)
    }
    @Test fun htmlAndProtectedFormatsAreNotClassifiedAsPlayable() {
        val base="https://test.example/video.mp4"
        assertEquals(StreamKind.UNSUPPORTED,StreamResolver.inspect(base,"<html>Error</html>".toByteArray(),"video/mp4").kind)
        assertEquals(StreamKind.UNSUPPORTED,StreamResolver.inspect(base,"#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"key\"".toByteArray()).kind)
        assertEquals(StreamKind.UNSUPPORTED,StreamResolver.inspect(base,"<MPD><ContentProtection/></MPD>".toByteArray()).kind)
    }
    @Test fun resolverValidatesMediaNotJustManifest200AndRetainsAuthenticationErrors() {
        val url="http://test.example/live"
        val manifest="#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nsegment.ts"
        val ts=ByteArray(188*4).apply { for(i in 0..3) this[188*i]=0x47 }
        val resolver=StreamResolver { at,_,_ -> ChannelHttpSample(200,at,if(at.endsWith("segment.ts")) ts else manifest.toByteArray()) }
        assertTrue(resolver.resolve(url).mediaValidated)
        val bad=StreamResolver { at,_,_ -> ChannelHttpSample(if(at.endsWith("segment.ts")) 403 else 200,at,manifest.toByteArray()) }.resolve(url)
        assertFalse(bad.mediaValidated); assertTrue(bad.issue!!.contains("403"))
    }
    @Test fun headerParserRejectsTransportHeadersAndControlCharacters() {
        assertEquals(mapOf("Referer" to "https://example.test/","Authorization" to "Bearer token"),StreamResolver.allowedHeaders("Referer: https://example.test/\nAuthorization: Bearer token"))
        for(raw in listOf("Host: evil.test","Range: bytes=0-","Cookie: value\rInjected: bad")) {
            assertTrue(runCatching { StreamResolver.allowedHeaders(raw) }.isFailure)
        }
        assertFalse(StreamResolver.httpUrl("file:///tmp/video")); assertFalse(StreamResolver.httpUrl("https://user:pass@example.test/a"))
        assertTrue(StreamResolver.httpUrl("HTTP://example.test/video?signature=a%2Fb"))
    }
    @Test fun playlistReorderAndDuplicateValidationSurviveReopen() {
        val dir=Files.createTempDirectory("aura-playlists").toFile()
        try {
            val file=java.io.File(dir,"lists.json"); val store=PlaylistStore(file); val list=store.create("Minha lista")!!
            assertTrue(store.add(list.id,"Um","https://example.test/1", "")); assertTrue(store.add(list.id,"Dois","https://example.test/2", ""))
            assertFalse(store.add(list.id,"Duplicado","https://example.test/1", "")); assertFalse(store.add(list.id,"Inválido","file:///test", ""))
            val items=store.all().single().items; store.moveItemTo(list.id,items[1].id,items[0].id)
            assertEquals(listOf("Dois","Um"),PlaylistStore(file).all().single().items.map { it.title })
        } finally { dir.deleteRecursively() }
    }
    @Test fun musicQueueNeverRepeatsBeforeCycleAndManualNextOverridesTrackRepeat() {
        val policy=MusicQueuePolicy(Random(4)); val queue=(0..39).map { "$it" }; policy.reset("0"); policy.setShuffle(true,"0")
        val visited=mutableListOf("0"); var current="0"
        repeat(39) { current=policy.next(queue,current,true)!!; visited.add(current) }
        assertEquals(40,visited.toSet().size); assertNull(policy.next(queue,current,true))
        policy.cycleRepeat(); policy.cycleRepeat(); assertEquals(current,policy.next(queue,current,true))
        assertNotEquals(current,policy.next(queue,current,false))
    }
}

class SubtitleTimelineTest {
    @Test fun auditedBaseOffsetsStayUnchanged() { assertEquals(0L,SubtitleTiming.LEAD_IN_MS); assertEquals(0L,SubtitleTiming.LEAD_OUT_MS) }
    @Test fun outOfOrderCuesAreChronological() { val t=SubtitleTimeline(); t.add(5000,6000,"late"); t.add(1000,2000,"early"); assertEquals("early",t.all().first().source); assertEquals("late",t.at(5500)!!.source) }
    @Test fun readingExtensionNeverInvadesNextCue() { val c=SpeechCue(0,1000,"hello"); c.output="Uma frase portuguesa bem mais comprida para testar o limite"; assertEquals(1920L,SubtitleTiming.readableEnd(c,2000)) }
    @Test fun untranslatedTimingIsPreserved() { assertEquals(3000L,SubtitleTiming.readableEnd(SpeechCue(1000,3000,"hello"))) }
    @Test fun hiddenCueIsNotExported() { val t=SubtitleTimeline(); t.add(0,1000,"uncertain")!!.output=""; t.add(2000,3000,"hello")!!.output="Olá"; assertFalse(t.exportSrt().contains("uncertain")); assertTrue(t.exportSrt().startsWith("1\n")) }
    @Test fun oneGlyphInterjectionIsKept() { assertNotNull(SubtitleTimeline().add(0,1000,"嗯")) }
    @Test fun wrappingPreservesAllWords() { val input="Esta é uma legenda um pouco mais comprida que precisa de duas linhas para leitura"; assertEquals(input,SubtitleTiming.wrap(input,false).replace('\n',' ')) }
}

class TimelineRepetitionTest {
    @Test fun threeConsecutiveRepetitionsHaveOneCueAndOneTranslationSubmission() {
        val t=SubtitleTimeline(); var submissions=0
        listOf(0L,1000L,2000L).forEach { at -> t.add(at,at+500,"No!")?.let { submissions++; it.output="Não." } }
        assertEquals(1,submissions); assertEquals(1,t.all().size)
        assertEquals(2500L,t.all().single().endMs); assertEquals("Não.",t.at(2200)!!.output)
    }
    @Test fun interveningSpeechBreaksRepetition() {
        val t=SubtitleTimeline(); t.add(0,500,"No!"); t.add(600,1000,"Go home"); t.add(1100,1500,"No!")
        assertEquals(3,t.all().size)
    }
    @Test fun interveningSpeechInSameOldDedupBucketIsPreserved() {
        val t=SubtitleTimeline(); t.add(0,120,"No!"); t.add(150,270,"Go!"); t.add(300,420,"No!")
        assertEquals(3,t.all().size)
    }
    @Test fun distantRepeatRemainsSeparate() {
        val t=SubtitleTimeline(); t.add(0,500,"No!"); t.add(2100,2500,"No!")
        assertEquals(2,t.all().size)
    }
    @Test fun normalizedPunctuationAndOverlapMergeAndExtend() {
        val t=SubtitleTimeline(); t.add(0,1000,"No!"); t.add(700,1700,"no.")
        assertEquals(1,t.all().size); assertEquals(1700L,t.all().single().endMs)
    }
    @Test fun restoredLongRepetitionKeepsItsFullDuration() {
        val t=SubtitleTimeline(); t.restore(listOf(SpeechCue(0,12000,"No!")))
        assertEquals(12000L,t.all().single().endMs)
    }
}

class EpisodeOrderTest {
    private fun video(name: String,date: Long=1)=LocalVideo("content://$name",name,100,date)
    @Test fun episodesAreNumericAndMissingNumbersAreAllowed() {
        assertEquals(listOf("EP2.mp4","EP9.mp4","EP10.mp4"),EpisodeOrder.sorted(listOf(video("EP10.mp4"),video("EP2.mp4"),video("EP9.mp4"))).map { it.name })
    }
    @Test fun seasonsAndLocalizedNames() {
        assertEquals(2L to 3L,EpisodeOrder.key("Temporada 2 Episódio 3.mp4"))
        assertEquals(1L to 10L,EpisodeOrder.key("Show S01E10.mkv"))
        assertEquals(1L to 3L,EpisodeOrder.key("Show 1x03.mp4"))
        assertEquals(3L to 2L,EpisodeOrder.key("T3 EP2.mp4"))
    }
    @Test fun unnamedFilesFallBackToModificationDate() {
        assertEquals(listOf("a.mp4","b.mp4"),EpisodeOrder.sorted(listOf(video("b.mp4",20),video("a.mp4",10))).map { it.name })
    }
    @Test fun oversizedNumbersDoNotCrash() { assertNotNull(EpisodeOrder.key("EP9999999999999999999999999.mp4")) }
}

class LocalSubtitleCacheTest {
    private fun withCache(block: (LocalSubtitleCache,java.io.File)->Unit) {
        val dir=Files.createTempDirectory("aura-subtitle-test").toFile()
        try { block(LocalSubtitleCache(dir),dir) } finally { dir.deleteRecursively() }
    }
    private val video=LocalVideo("content://provider/video", "EP1.mp4", 200, 100)
    @Test fun roundTripPreservesPendingAndTranslatedCues() = withCache { cache,_ ->
        val first=SpeechCue(0,1000,"Hello").apply { output="Olá"; provider="gtx" }
        val pending=SpeechCue(2000,3000,"Come here").apply { reason="PENDING:429" }
        cache.save(video,"en","pt","1:0",listOf(first,pending))
        val saved=cache.load(video,"en","pt","1:0")!!
        assertEquals(2,saved.cues.size); assertEquals("Olá",saved.cues[0].output)
        assertNull(saved.cues[1].output); assertEquals("PENDING:429",saved.cues[1].reason)
    }
    @Test fun changedFileLanguageOrPolicyInvalidatesCache() = withCache { cache,_ ->
        cache.save(video,"en","pt","1:0",emptyList())
        assertNull(cache.load(video.copy(size=201),"en","pt","1:0"))
        assertNull(cache.load(video.copy(modified=101),"en","pt","1:0"))
        assertNull(cache.load(video,"zh","pt","1:0"))
        assertNull(cache.load(video,"en","pt","2:0"))
    }
    @Test fun corruptFileFallsBackToRecognition() = withCache { cache,dir ->
        cache.save(video,"en","pt","1:0",emptyList()); dir.listFiles()!!.single().writeText("broken")
        assertNull(cache.load(video,"en","pt","1:0"))
    }
    @Test fun newerTranslationReplacesPendingCacheAtomically() = withCache { cache,dir ->
        val cue=SpeechCue(0,1000,"Hello")
        cache.save(video,"en","pt","1:0",listOf(cue)); cue.output="Olá"
        cache.save(video,"en","pt","1:0",listOf(cue))
        assertEquals("Olá",cache.load(video,"en","pt","1:0")!!.cues.single().output)
        assertEquals(1,dir.listFiles()!!.size)
    }
}


class OriginBoundsProtectionTest {
    @Test fun humanRetimingPreservesBothOriginStartAndOriginEndAcrossMachineExtensions() {
        val cue = SpeechCue(1000, 2200, "Original line")
        cue.extendMachineEnd(2800)
        assertEquals(2800L, cue.endMs)
        assertEquals(2800L, cue.originEndMs)

        assertTrue(cue.applyTiming(1500, 4200))
        assertEquals(1000L, cue.originStartMs)
        assertEquals(2800L, cue.originEndMs)
        assertEquals(1500L, cue.startMs)
        assertEquals(4200L, cue.endMs)

        val snap = cue.snapshot()
        assertEquals(1000L, snap.originStartMs)
        assertEquals(2800L, snap.originEndMs)
        assertEquals(1500L, snap.startMs)
        assertEquals(4200L, snap.endMs)
    }
}

class TimelineSnapshotPerformanceTest {
    @Test fun snapshotCacheReusesImmutableListUntilMutation() {
        val timeline = SubtitleTimeline()
        assertEquals(0, timeline.size())
        assertNull(timeline.lastEndMs())
        val empty1 = timeline.all()
        val empty2 = timeline.all()
        assertSame(empty1, empty2)

        val cue1 = timeline.add(1000, 2200, "First line")!!
        val cue2 = timeline.add(3000, 4500, "Second line")!!
        assertEquals(2, timeline.size())
        assertEquals(4500L, timeline.lastEndMs())

        val snap1 = timeline.all()
        val snap2 = timeline.all()
        assertSame(snap1, snap2)

        assertTrue(cue1.applyTiming(3800, 5200))
        timeline.applyTimings(listOf(SubtitlePackageStore.Timing(cue1.id, 3800, 5200)))
        val snapAfterTiming = timeline.all()
        assertNotSame(snap1, snapAfterTiming)
        assertEquals(listOf(cue2.id, cue1.id), snapAfterTiming.map { it.id })
        assertEquals(5200L, timeline.lastEndMs())

        timeline.reset()
        assertEquals(0, timeline.size())
        assertNull(timeline.lastEndMs())
    }
}
