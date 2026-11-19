package com.aurareels.ocrlab

import org.junit.Assert.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ASR token cleaning, non-speech/laughter classification, VAD 512-sample framing, singing segmentation and token alignment. */
class NoiseFilterTest {
    @Test fun tagsAreRemoved() = assertEquals("こんにちは", NoiseFilter.clean("<|ja|><|NEUTRAL|> こんにちは [music]"))

    @Test fun singleGlyphBurstIsNoise() {
        assertTrue(NoiseFilter.reject("あ", "ja", 400, 1))
        assertFalse(NoiseFilter.reject("はい、わかりました", "ja", 1800, 1))
    }

    @Test fun fillerAndSubtitleCreditsAreRejected() {
        assertTrue(NoiseFilter.reject("the", "en", 600, 1))
        assertTrue(NoiseFilter.reject("Thanks for watching!", "en", 2000, 1))
        assertTrue(NoiseFilter.reject("ご視聴ありがとうございました", "ja", 2500, 1))
        assertFalse(NoiseFilter.reject("I will meet you tomorrow", "en", 2500, 1))
    }

    @Test fun loopsAreRejected() {
        assertTrue(NoiseFilter.reject("la la la la la", "en", 3000, 1))
        assertTrue(NoiseFilter.reject("ははははははは", "ja", 3000, 1))
    }

    @Test fun voskConfidenceGrowsWithStrictness() {
        assertTrue(NoiseFilter.minVoskConfidence(1, 2) > NoiseFilter.minVoskConfidence(1, 1))
        assertTrue(NoiseFilter.minVoskConfidence(1, 1) > NoiseFilter.minVoskConfidence(4, 1))
        assertTrue(NoiseFilter.minVoskConfidence(4, 0) < NoiseFilter.minVoskConfidence(4, 1))
    }

    @Test fun longSegmentsSplitIntoReadableCues() {
        val text = "Bu ilk cümle yeterince uzun. Bu ikinci cümle de uzun olmalı. Üçüncü cümle burada bitiyor."
        val cues = NoiseFilter.split(text, 10_000, 18_000)
        assertTrue(cues.size >= 2)
        assertEquals(10_000L, cues.first().first)
        assertTrue(cues.zipWithNext().all { (a, b) -> b.first >= a.first })
    }
}

class MusicSegmenterTest {
    @Test fun continuousSingingLosesNoSamplesAcrossWindowsAndFinalTail() {
        val out=ArrayList<Pair<Long,FloatArray>>()
        val segmenter=MusicSegmenter { at,pcm -> out.add(at to pcm) }
        val source=FloatArray(16000*31+123) { ((it%101)+1)/102f }
        var at=0
        while(at<source.size) {
            val n=minOf(3200,source.size-at)
            segmenter.feed(2400L+at*1000L/16000,source.copyOfRange(at,at+n)); at+=n
        }
        segmenter.flush()
        assertEquals(source.size,out.sumOf { it.second.size })
        var offset=0
        for((time,pcm) in out) {
            assertEquals(2400L+offset*1000L/16000,time)
            assertTrue(pcm.size<=12*16000)
            assertArrayEquals(source.copyOfRange(offset,offset+pcm.size),pcm,0f)
            offset+=pcm.size
        }
        assertNull(segmenter.pendingStartMs)
    }
    @Test fun quietBoundaryIsPreferredWithoutDroppingTheSilence() {
        val out=ArrayList<Pair<Long,FloatArray>>()
        val segmenter=MusicSegmenter { at,pcm -> out.add(at to pcm) }
        val pcm=FloatArray(12*16000) { 0.1f }
        for(i in 8*16000 until 8*16000+1600) pcm[i]=0f
        segmenter.feed(0,pcm)
        assertEquals(8*16000+1600,out.first().second.size)
        assertEquals((8*16000+1600)*1000L/16000,segmenter.pendingStartMs)
        segmenter.flush()
        assertEquals(pcm.size,out.sumOf { it.second.size })
    }
    @Test fun discontinuityKeepsOldTailAndStartsAtNewMediaTime() {
        val out=ArrayList<Pair<Long,FloatArray>>()
        val segmenter=MusicSegmenter { at,pcm -> out.add(at to pcm) }
        segmenter.feed(1000,FloatArray(16000) { 0.1f })
        segmenter.feed(40_000,FloatArray(8000) { 0.2f })
        segmenter.flush()
        assertEquals(listOf(1000L,40_000L),out.map { it.first })
        assertEquals(listOf(16000,8000),out.map { it.second.size })
    }
    @Test fun resetDropsPendingAudioBeforeSeek() {
        val out=ArrayList<Long>()
        val segmenter=MusicSegmenter { at,_ -> out.add(at) }
        segmenter.feed(0,FloatArray(16000))
        segmenter.reset()
        segmenter.feed(50_000,FloatArray(16000))
        segmenter.flush(); segmenter.flush()
        assertEquals(listOf(50_000L),out)
    }
    @Test fun lyricCuesKeepFullWindowAndSeparateRepeatedLines() {
        val timeline=SubtitleTimeline()
        val first=timeline.add(0,12_000,"愛してる",mergeRepetitions=false,maxDurationMs=12_500)!!
        val second=timeline.add(12_000,24_000,"愛してる",mergeRepetitions=false,maxDurationMs=12_500)!!
        assertEquals(12_000L,first.endMs)
        assertEquals(24_000L,second.endMs)
        assertEquals(2,timeline.all().size)
        assertNotNull(timeline.at(11_000))
    }
    @Test fun songTextAndRefrainsSurviveMusicMetadataAndNoiseSettings() {
        for(level in 0..2) {
            assertEquals(CueClass.SPEECH,NoiseClassifier.classify("<|Music|>君を愛している", "ja",6000,level,"Music",lyrics=true))
            assertEquals(CueClass.SPEECH,NoiseClassifier.classify("啦啦啦啦啦啦", "zh",6000,level,lyrics=true))
            assertEquals(CueClass.SPEECH,NoiseClassifier.classify("la la la la", "en",6000,level,lyrics=true))
            assertEquals(CueClass.SPEECH,NoiseClassifier.classify("你", "zh",2000,level,lyrics=true))
        }
        assertNotEquals(CueClass.SPEECH,NoiseClassifier.classify("<|Music|>","ja",6000,1,lyrics=true))
        assertNotEquals(CueClass.SPEECH,NoiseClassifier.classify("thanks for watching","en",6000,1,lyrics=true))
        assertNotEquals(CueClass.SPEECH,NoiseClassifier.classify("啦啦啦啦啦啦","zh",6000,1))
    }
}

class NoiseClassifierTest {
    @Test fun laughterAcrossScripts() {
        for((lang,text) in listOf("ja" to "ははは", "ja" to "wwww", "zh" to "哈哈哈哈", "ko" to "ㅋㅋㅋ", "en" to "hahaha", "tr" to "hehehe", "ar" to "هههه", "pt" to "kkkk"))
            assertEquals("$lang $text",CueClass.LAUGH,NoiseClassifier.classify(text,lang,2000,1))
    }
    @Test fun eventSurvivesUntilClassification() { assertEquals(CueClass.LAUGH,NoiseClassifier.classify("<|ja|><|Laughter|>","ja",1000,1)) }
    @Test fun backgroundMusicDoesNotEraseDialogue() { assertEquals(CueClass.SPEECH,NoiseClassifier.classify("<|BGM|>Where are you going?","en",2000,1)) }
    @Test fun musicOnlyIsNotSpeech() { assertEquals(CueClass.NONSPEECH_OTHER,NoiseClassifier.classify("[music]","en",1000,1)) }
    @Test fun meaningfulInterjectionsAreKept() { for((lang,text) in listOf("ja" to "はい", "zh" to "嗯", "tr" to "evet", "ko" to "네")) assertEquals(CueClass.INTERJECTION,NoiseClassifier.classify(text,lang,500,1)) }
    @Test fun hallucinatedCreditsAreFiltered() { assertEquals(CueClass.HALLUCINATION,NoiseClassifier.classify("thanks for watching","en",2500,1)) }
}

class RecognitionRegressionTest {
    @Test fun screenshotOne() { assertEquals("So first day, yes, scary.",NoiseFilter.clean("<|>So first day, yes, scary.")) }
    @Test fun screenshotTwo() { assertEquals("Yeah, you need to turn it on.",NoiseFilter.clean("<|>Yeah, you need to turn it on.")) }
    @Test fun nestedSherpaEvent() { assertEquals("Hello there.",NoiseFilter.clean("<|<|Speech|>|>Hello there.")) }
    @Test fun repeatedNestedTokensAreIdempotent() {
        val raw="<|<|Speech|>|><|en|><|NEUTRAL|><|>Come here!<||>"
        assertEquals("Come here!",NoiseFilter.clean(raw)); assertEquals(NoiseFilter.clean(raw),NoiseFilter.clean(NoiseFilter.clean(raw)))
    }
    @Test fun parenthesesInTranslationArePreserved() { assertEquals("Ele (meu irmão) chegou.",NoiseFilter.cleanModelTokens("Ele (meu irmão) chegou.")) }
    @Test fun separateEventIsNotPartOfDialogue() { assertEquals(CueClass.SPEECH,NoiseClassifier.classify("Yeah, you need to turn it on.","en",2500,1,"<|Speech|>")) }
    @Test fun breathWithHallucinatedWordIsNotSpeech() { assertEquals(CueClass.NONSPEECH_OTHER,NoiseClassifier.classify("Hello","en",700,1,"<|Breath|>")) }
    @Test fun coughWithTextIsNotSpeech() { assertEquals(CueClass.NONSPEECH_OTHER,NoiseClassifier.classify("Come on","en",700,1,"Cough")) }
    @Test fun musicWithShortWordIsNotSpeech() { assertEquals(CueClass.NONSPEECH_OTHER,NoiseClassifier.classify("Oh!","en",700,1,"Music")) }
    @Test fun musicWithDialogueSurvives() { assertEquals(CueClass.SPEECH,NoiseClassifier.classify("Where are you going?","en",2000,1,"<|BGM|>")) }
    @Test fun speechAndBackgroundEventSurvive() { assertEquals(CueClass.INTERJECTION,NoiseClassifier.classify("Yes","en",500,1,"<|Speech|><|BGM|>")) }
    @Test fun laughterDoesNotTurnIntoEnglishWord() { assertEquals(CueClass.LAUGH,NoiseClassifier.classify("Ha","en",600,1,"<|Laughter|>")) }
    @Test fun blipNeverBecomesInterjectionRequest() { assertEquals(CueClass.NONSPEECH_OTHER,NoiseClassifier.classify("oh","en",90,1)) }
    @Test fun realShortAnswersHaveLocalTranslation() { assertEquals("Sim.",NoiseClassifier.seed("Yes!","en")); assertEquals("Não.",NoiseClassifier.seed("No.","en")); assertEquals(CueClass.INTERJECTION,NoiseClassifier.classify("No.","en",500,1)) }
    @Test fun timelineCleansLegacyTags() { val t=SubtitleTimeline(); val c=t.add(1000,2000,"<|>Hello there")!!; assertEquals("Hello there",c.source); assertFalse(t.exportSrt().contains("<|")) }
    @Test fun pendingCaptionDoesNotPretendSourceIsTranslated() { assertEquals("",SubtitleTiming.display(SpeechCue(0,1000,"Hello"))) }
    @Test fun translatedCaptionCleansTagsAndKeepsTiming() { val c=SpeechCue(1000,2500,"Hello there"); c.output="<|>Olá!"; assertEquals("Olá!",SubtitleTiming.display(c)); assertEquals(1000L,c.startMs); assertEquals(2500L,c.endMs) }
    @Test fun sourceEchoWithDifferentPunctuationFails() { assertNull(TranslationText.accepted("<|>Hello there!","Hello there.","pt")) }
    @Test fun tagsDoNotMakeGoodTranslationFail() { assertEquals("Você precisa ligar.",TranslationText.accepted("You need to turn it on.","<|>Você precisa ligar.","pt")) }
    @Test fun untranslatedScriptIsRejected() { assertNull(TranslationText.accepted("你好世界","您好世界！","pt")) }
    @Test fun warmedFastEngineStartsWithTwelveSeconds() { val d=AsrPacer().decide(PacerInput(0,15000,12000,0,true,true,0.5,false,false,false,20000)); assertEquals(MainAction.START,d.main); assertEquals(12000L,d.needMs) }
    @Test fun slowEngineKeepsReserve() { val d=AsrPacer().decide(PacerInput(0,15000,12000,0,true,true,0.9,false,false,false,20000)); assertEquals(MainAction.NONE,d.main); assertEquals(20000L,d.needMs) }
    @Test fun cutsLongSpeechAtAPauseNotAtAFixedTime() {
        val p=SpeechCutPolicy(32.0)
        repeat(100) { p.observe(0.1f) }
        assertNull(p.decide(2_000))                       // too short to split
        repeat(8) { p.observe(0.005f) }                   // ~256 ms dip
        assertNull(p.decide(2_900))                       // still below the soft limit
        assertEquals("PAUSE",p.decide(4_000))
        assertEquals("PAUSE",p.decide(3_000))
        repeat(3) { p.observe(0.1f) }; assertNull(p.decide(4_100))      // speech resumed
        assertEquals("HARD",p.decide(8_000))              // backstop
    }
}
