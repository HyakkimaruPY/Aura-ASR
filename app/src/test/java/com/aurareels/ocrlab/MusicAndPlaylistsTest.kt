package com.aurareels.ocrlab

import java.io.ByteArrayOutputStream
import kotlin.random.Random
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** LRC/ID3/Vorbis/MP4 lyrics parsing, language guessing, music queue shuffle/repeat policy, playlists and content sessions. */
class LyricsTest {
    @Test fun lrcKeepsTimesAndDropsMetadataAndCredits() {
        val raw="[ti:Song]\n[ar:Band]\n[00:01.00]Letra: Fulano\n[00:02.50]Hello world\n[00:05.5]<00:05.5>Second line\n[00:08.00]\n[00:09.00]Written by John Doe\n[00:10.00]Last"
        val lines=LyricsParser.fromText(raw)
        assertEquals(listOf("Hello world","Second line","","Last"),lines.map { it.text })
        assertEquals(listOf(2500L,5500L,8000L,10000L),lines.map { it.startMs })
    }
    @Test fun creditsAreRemovedButNormalLinesSurvive() {
        assertTrue(LyricsParser.isCredit("Composição: Fulano e Ciclano"))
        assertTrue(LyricsParser.isCredit("Lyrics by Jane"))
        assertTrue(LyricsParser.isCredit("作词: 某某"))
        assertFalse(LyricsParser.isCredit("Words - I can't say them"))
        assertFalse(LyricsParser.isCredit("You are a genius"))
        assertFalse(LyricsParser.isCredit("Music is my life"))
    }
    @Test fun multipleTimeTagsAndOffset() {
        val lines=LyricsParser.parseLrc("[offset:500]\n[00:10.00][00:20.00]Chorus")
        assertEquals(listOf(10500L,20500L),lines.map { it.startMs })
    }
    @Test fun plainTextHasNoTiming() {
        val lines=LyricsParser.fromText("Uma linha\n\n\nOutra linha\n")
        assertTrue(lines.all { it.startMs<0 })
        assertEquals(listOf("Uma linha","","Outra linha"),lines.map { it.text })
    }
    @Test fun languageGuessByScriptAndWords() {
        fun l(t: String)=listOf(LyricLine(-1,t))
        assertEquals("ja",LyricsParser.guessLanguage(l("夜に駆ける 沈むように溶けてゆくように")))
        assertEquals("ko",LyricsParser.guessLanguage(l("사랑해 너를 정말 많이 사랑해")))
        assertEquals("en",LyricsParser.guessLanguage(l("I love you and you love me, baby, in the night")))
        assertEquals("pt",LyricsParser.guessLanguage(l("Eu não sei você está com o meu coração, tudo é amor")))
        assertNull(LyricsParser.guessLanguage(l("la la la")))
    }
    private fun frame(id: String, body: ByteArray): ByteArray {
        val o=ByteArrayOutputStream(); o.write(id.toByteArray()); val n=body.size
        o.write(byteArrayOf((n shr 24).toByte(),(n shr 16).toByte(),(n shr 8).toByte(),n.toByte(),0,0)); o.write(body); return o.toByteArray()
    }
    private fun tag(frames: ByteArray): ByteArray {
        val n=frames.size
        return byteArrayOf('I'.code.toByte(),'D'.code.toByte(),'3'.code.toByte(),3,0,0,
            ((n shr 21) and 0x7f).toByte(),((n shr 14) and 0x7f).toByte(),((n shr 7) and 0x7f).toByte(),(n and 0x7f).toByte())+frames
    }
    @Test fun id3SyltIsPreferredAndCleaned() {
        val b=ByteArrayOutputStream()
        b.write(byteArrayOf(3)); b.write("eng".toByteArray()); b.write(byteArrayOf(2,1)); b.write(0)   // utf-8, ms, lyrics, empty descriptor
        fun item(t: String, ms: Int) { b.write(t.toByteArray()); b.write(0); b.write(byteArrayOf((ms shr 24).toByte(),(ms shr 16).toByte(),(ms shr 8).toByte(),ms.toByte())) }
        item("Written by Someone",0); item("First line",1000); item("Second line",4000)
        val uslt=ByteArrayOutputStream(); uslt.write(byteArrayOf(3)); uslt.write("eng".toByteArray()); uslt.write(0); uslt.write("plain".toByteArray())
        val data=tag(frame("USLT",uslt.toByteArray())+frame("SYLT",b.toByteArray()))
        val lines=LyricsParser.clean(EmbeddedLyrics.id3FromBytes(data))
        assertEquals(listOf("First line","Second line"),lines.map { it.text })
        assertEquals(listOf(1000L,4000L),lines.map { it.startMs })
    }
    @Test fun id3UsltWithLrcInsideIsSynchronized() {
        val u=ByteArrayOutputStream(); u.write(byteArrayOf(3)); u.write("por".toByteArray()); u.write(0); u.write("[00:01.00]Oi\n[00:03.00]Tchau".toByteArray())
        val lines=EmbeddedLyrics.id3FromBytes(tag(frame("USLT",u.toByteArray())))
        assertEquals(listOf(1000L,3000L),lines.map { it.startMs })
    }
    @Test fun flacVorbisCommentLyrics() {
        fun le(n: Int)=byteArrayOf(n.toByte(),(n shr 8).toByte(),(n shr 16).toByte(),(n shr 24).toByte())
        val o=ByteArrayOutputStream(); val vendor="x".toByteArray(); o.write(le(vendor.size)); o.write(vendor); o.write(le(2))
        val a="TITLE=Song".toByteArray(); o.write(le(a.size)); o.write(a)
        val b="LYRICS=[00:02.00]Hi there".toByteArray(); o.write(le(b.size)); o.write(b)
        val lines=EmbeddedLyrics.vorbisComments(o.toByteArray())
        assertEquals(listOf("Hi there"),lines.map { it.text }); assertEquals(2000L,lines[0].startMs)
    }
}

class PlaylistStoreTest {
    @Test fun createAddMoveRemoveAndDuplicates() {
        val file=java.nio.file.Files.createTempDirectory("pl").resolve("p.json").toFile()
        val store=PlaylistStore(file)
        val list=store.create("  Série  ")!!
        assertEquals("Série",list.name)
        assertTrue(store.add(list.id,"A","https://x/a.m3u8",""))
        assertFalse(store.add(list.id,"A2","https://x/a.m3u8",""))
        assertTrue(store.add(list.id,"B","https://x/b.m3u8","https://x/"))
        val items=store.all()[0].items
        store.move(list.id,items[1].id,-1)
        assertEquals(listOf("B","A"),PlaylistStore(file).all()[0].items.map { it.title })
        store.removeItem(list.id,items[0].id)
        assertEquals(1,store.all()[0].items.size)
        assertNull(store.create("   "))
    }
    @Test fun firstUnfinishedSkipsWatchedAndWrapsWhenAllDone() {
        val l=OnlinePlaylist("1","x",listOf(PlaylistItem("a","a","u1",""),PlaylistItem("b","b","u2","")))
        val store=PlaylistStore(java.io.File("unused.json"))
        assertEquals("u2",store.firstUnfinished(l) { if(it=="u1") 96L to 100L else 10L to 100L }!!.url)
        assertEquals("u1",store.firstUnfinished(l) { 99L to 100L }!!.url)
    }
}

class MusicQueuePolicyTest {
    private val q = listOf("a", "b", "c", "d")

    @Test fun repeatCyclesOffQueueTrackOff() {
        val p = MusicQueuePolicy()
        assertEquals(RepeatMode.OFF, p.repeat)
        assertEquals(RepeatMode.QUEUE, p.cycleRepeat())
        assertEquals(RepeatMode.TRACK, p.cycleRepeat())
        assertEquals(RepeatMode.OFF, p.cycleRepeat())
    }
    @Test fun sequentialEndStopsOnlyWhenRepeatIsOff() {
        val p = MusicQueuePolicy()
        assertEquals("b", p.next(q, "a", auto = true))
        assertNull(p.next(q, "d", auto = true))
        assertNull(p.next(q, "d", auto = false))
        p.cycleRepeat()
        assertEquals("a", p.next(q, "d", auto = true))
    }
    @Test fun trackRepeatReplaysOnNaturalEndButManualNextAdvances() {
        val p = MusicQueuePolicy(); p.cycleRepeat(); p.cycleRepeat()
        assertEquals("b", p.next(q, "b", auto = true))
        assertEquals("c", p.next(q, "b", auto = false))
        assertEquals("a", p.next(q, "d", auto = false))
    }
    @Test fun oneTrackFolderRepeatsOrStops() {
        val p = MusicQueuePolicy()
        assertNull(p.next(listOf("x"), "x", auto = true))
        p.cycleRepeat()
        assertEquals("x", p.next(listOf("x"), "x", auto = true))
        p.setShuffle(true, "x")
        assertEquals("x", p.next(listOf("x"), "x", auto = true))
    }
    @Test fun shuffleVisitsEveryTrackOncePerCycleAndStaysInsideTheQueue() {
        val p = MusicQueuePolicy(Random(7)); p.setShuffle(true, "a")
        val seen = mutableListOf("a"); var cur = "a"
        repeat(3) { cur = p.next(q, cur, auto = true)!!; seen += cur }
        assertEquals(q.toSet(), seen.toSet()); assertEquals(4, seen.size)
        assertNull(p.next(q, cur, auto = true))                // cycle finished, repeat off
        p.cycleRepeat()
        val again = p.next(q, cur, auto = true)!!              // repeat queue: new cycle, never the same track twice in a row
        assertTrue(again in q && again != cur)
    }
    @Test fun previousWalksBackThroughShuffleHistoryThenRestarts() {
        val p = MusicQueuePolicy(Random(3)); p.setShuffle(true, "a")
        val b = p.next(q, "a", true)!!; val c = p.next(q, b, true)!!
        assertEquals(b, p.previous(q, c))
        assertEquals("a", p.previous(q, b))
        assertNull(p.previous(q, "a"))
    }
    @Test fun previousInOrderGoesToPreviousTrackAndWrapsOnlyWithRepeat() {
        val p = MusicQueuePolicy()
        assertEquals("a", p.previous(q, "b"))
        assertNull(p.previous(q, "a"))
        p.cycleRepeat()
        assertEquals("d", p.previous(q, "a"))
    }
    @Test fun resetKeepsUserChoicesButForgetsHistory() {
        val p = MusicQueuePolicy(Random(1)); p.setShuffle(true, "a"); p.cycleRepeat()
        p.next(q, "a", true); p.reset("c")
        assertTrue(p.shuffle); assertEquals(RepeatMode.QUEUE, p.repeat)
        assertNull(p.previous(q, "c"))
    }
    @Test fun unknownCurrentOrEmptyQueueIsSafe() {
        val p = MusicQueuePolicy()
        assertNull(p.next(emptyList(), "a", true))
        assertEquals("a", p.next(q, "zzz", true))
    }
}

class ContentFlowTest {
    private val lines=listOf(ApiTimedLine("a",1000,2000,"田中、ここに来て"),ApiTimedLine("b",2500,4000,"君を待っている"),ApiTimedLine("c",5000,6500,"もう一度"))
    private fun response(ids: List<String> = listOf("c","a","b")): String {
        val rows=JSONArray()
        ids.forEach { id -> rows.put(JSONObject().put("id",id).put("status","ok").put("pt",when(id) {
            "a" -> "田中, venha aqui."; "b" -> "Estou esperando por você."; else -> "Mais uma vez."
        }).put("preserved_names",JSONArray().apply { if(id=="a") put("田中") })) }
        return JSONObject().put("schema","translation_batch_result_v1").put("batch_id","batch").put("items",rows).toString()
    }
    @Test fun reorderedBatchResultsKeepTheirOwnCueAndMediaTimes() {
        val decoded=ApiBatchContract.decode(response(),"batch",lines)
        assertEquals(listOf("a","b","c"),decoded.map { it.id })
        assertEquals("田中, venha aqui.",decoded[0].text)
        assertEquals(listOf(1000L,2500L,5000L),lines.map { it.startMs })
        assertEquals(listOf(2000L,4000L,6500L),lines.map { it.endMs })
    }
    @Test fun partialDuplicateOrUnexpectedBatchNeverPartiallyCommits() {
        for(ids in listOf(listOf("a","a","c"),listOf("a","b"),listOf("a","b","unknown"))) {
            try { ApiBatchContract.decode(response(ids),"batch",lines); fail("Invalid batch must fail atomically") }
            catch(_: IllegalStateException) {}
        }
    }
    @Test fun properNameMetadataKeepsMixedScriptTranslationComplete() {
        val decoded=ApiBatchContract.decode(response(),"batch",lines)[0]
        val cue=SpeechCue(1000,2000,lines[0].source).apply { output=decoded.text; preservedNames=decoded.preservedNames }
        assertEquals(CoverageState.TRANSLATED,TranslationCoverage.state(cue,"ja","pt"))
        cue.preservedNames=emptyList()
        assertEquals(CoverageState.INVALID,TranslationCoverage.state(cue,"ja","pt"))
        val name=SpeechCue(0,1000,"田中").apply { output="田中"; preservedNames=listOf("田中") }
        assertTrue(TranslationCoverage.complete(name,"ja","pt"))
        name.preservedNames=emptyList()
        assertFalse(TranslationCoverage.complete(name,"ja","pt"))
    }
    @Test fun inventedNameExemptionIsRejected() {
        val json=JSONObject(response())
        json.getJSONArray("items").getJSONObject(1).put("preserved_names",JSONArray().put("東京"))
        val result=ApiBatchContract.decode(json.toString(),"batch",lines)[0]
        assertTrue(result.preservedNames.isEmpty())
        assertNull(TranslationText.accepted(lines[0].source,result.text.orEmpty(),"pt",result.preservedNames))
    }
    @Test fun onlyShortLexicalUtterancesUseShortRoute() {
        for(text in listOf("Hi!","Come on","ああ","可以","走")) assertTrue(text,SubtitleTranslationPolicy.isShort(text))
        for(text in listOf("This is a complete sentence","君のことをずっと待っていた","...","12345","")) assertFalse(text,SubtitleTranslationPolicy.isShort(text))
        assertEquals(CueClass.SPEECH,NoiseClassifier.classify("猫","zh",700,1,event="Speech"))
        assertNotEquals(CueClass.SPEECH,NoiseClassifier.classify("<|Noise|>","en",700,1,event="Noise"))
    }
    @Test fun shortGoogleCueCannotExtendIntoTheNextApiSentence() {
        val timeline=SubtitleTimeline()
        val short=timeline.add(1000,1500,"Hi")!!
        val sentence=timeline.add(1700,4500,"This is a complete sentence")!!
        sentence.output="Esta é uma frase completa."
        short.output="Olá, tudo bem?" // Simulate Google finishing after API, without changing timing.
        assertEquals(short,timeline.at(1400))
        assertEquals(sentence,timeline.at(1800))
        assertTrue(SubtitleTiming.readableEnd(short,sentence.startMs)<sentence.startMs)
        assertEquals(1000L,short.startMs); assertEquals(1700L,sentence.startMs)
    }
    private fun input(live: Boolean, autoPaused: Boolean=false)=PacerInput(
        mainPos=100_000,scoutPos=115_000,readyThrough=99_000,backlogMs=7000,known=true,engineActive=true,rtf=0.5,
        mainStarted=true,mainPlaying=!autoPaused,afterSeek=false,delayMs=20_000,autoPaused=autoPaused,live=live)
    @Test fun liveDoesNotPauseOrSlowThePictureWhenRecognitionFallsBehind() {
        val pacer=AsrPacer()
        val live=pacer.decide(input(true))
        assertEquals(MainAction.NONE,live.main); assertEquals(1f,live.mainSpeed,0f)
        assertEquals(MainAction.RESUME,pacer.decide(input(true,true)).main)
        assertEquals(MainAction.PAUSE,pacer.decide(input(false)).main)
    }
    @Test fun firstMusicCallWaitsForNeighboringLinesButFinalTailAlwaysFlushes() {
        assertFalse(SubtitleTranslationPolicy.musicBatchReady(1,1,false,15_000))
        assertFalse(SubtitleTranslationPolicy.musicBatchReady(2,2,false,1200))
        assertTrue(SubtitleTranslationPolicy.musicBatchReady(2,2,false,2500))
        assertTrue(SubtitleTranslationPolicy.musicBatchReady(3,3,false,0))
        assertTrue(SubtitleTranslationPolicy.musicBatchReady(1,8,false,1200))
        assertTrue(SubtitleTranslationPolicy.musicBatchReady(1,1,true,0))
        assertFalse(SubtitleTranslationPolicy.musicBatchReady(0,8,true,5000))
    }
    @Test fun languageVotesResetBetweenEnglishAndMandarinBothDirections() {
        val votes=LanguageVoteTracker()
        repeat(3) { votes.add("en") }; assertEquals("en",votes.confirmed())
        votes.reset(); assertNull(votes.confirmed()); assertNull(votes.add("zh")); assertEquals("zh",votes.add("zh"))
        votes.reset(); assertNull(votes.add("en")); assertEquals("en",votes.add("en"))
    }
}

class PlaylistRamCacheTest {
    @Test fun inMemorySnapshotAvoidsDiskReadsAfterInitialLoadAndStaysConsistentOnWrite() {
        val dir = java.nio.file.Files.createTempDirectory("pl-ram").toFile()
        try {
            val file = java.io.File(dir, "playlists.json")
            val store = PlaylistStore(file)
            val created = store.create("Favoritos")!!
            assertTrue(store.add(created.id, "Ep 1", "https://cdn.example.com/1.m3u8", ""))
            val firstRead = store.all()
            val secondRead = store.all()
            assertSame(firstRead, secondRead)
            assertEquals(1, firstRead.single().items.size)
        } finally {
            dir.deleteRecursively()
        }
    }
}
