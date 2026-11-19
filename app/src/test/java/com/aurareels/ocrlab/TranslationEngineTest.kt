package com.aurareels.ocrlab

import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

/** Unit tests for translation race, endpoint health, dictionary/vault policy, echo guards and web payload parsers. */
class EchoAndScriptRoutingTest {
    @Test fun numbersLike429AreNotProviderErrors() {
        assertNotNull(TranslationText.accepted("It happened in 1429 near the river","Aconteceu em 1429 perto do rio","pt"))
        assertNull(TranslationText.accepted("Hello there my friend","429 TOO MANY REQUESTS","pt"))
        assertNotNull(TranslationText.accepted("Call 429 now","Ligue para 429 agora","pt"))
    }
    @Test fun unchangedLatinTextIsBenignEchoButChineseIsNot() {
        assertEquals("Okay",TranslationText.benignEcho("Okay","okay!"))
        assertEquals("John Smith",TranslationText.benignEcho("John Smith","John Smith"))
        assertNull(TranslationText.benignEcho("朋友们之间谈心的话","朋友们之间谈心的话"))
        assertNull(TranslationText.benignEcho("Hello there","MYMEMORY WARNING: QUOTA"))
        assertNull(TranslationText.benignEcho("Hello there","Olá"))
    }
    @Test fun echoCountsAsFinalCoverageOnlyWithMarker() {
        val c=SpeechCue(0,2000,"John Smith"); c.output="John Smith"
        assertFalse(TranslationCoverage.complete(c,"en","pt"))
        c.reason="ECHO:ORIGINAL"; assertTrue(TranslationCoverage.complete(c,"en","pt"))
    }
    @Test fun clearlyPortugueseTextDeclaredEnglishIsRoutedAsPortuguese() {
        assertEquals("pt",ScriptLanguage.effective("Eu não sei você está pronto","en"))
        assertEquals("en",ScriptLanguage.effective("I do not know if you are ready","en"))
        assertEquals("pt",ScriptLanguage.effective("Não","en"))
        assertEquals("zh",ScriptLanguage.effective("朋友们之间谈心的话","en"))
    }
    @Test fun uncoveredToleratesNegativeStart() {
        val gaps=AudioCoverage.uncovered(-300,1000,emptyList())
        assertEquals(listOf(AudioInterval(0,1000)),gaps)
        assertTrue(AudioCoverage.uncovered(-500,0,emptyList()).isEmpty())
    }
}

class UnifiedTranslationHubTest {
    @Test fun endpointHealthOrdersByStickyWinnerAndLatencyAndClearsOnlyTransientFailures() {
        var now = 1_000L
        val health = EndpointHealth { now }
        health.recordSuccess("lingva", 420)
        health.recordSuccess("at", 95)
        val ordered = health.ordered(listOf("gtx", "at", "chrome", "lingva", "mymemory"))
        assertEquals("at", ordered.first())

        // Transient socket failure vs HTTP 429 server rejection
        repeat(3) { health.failure("gtx", rejected = false) }
        health.failure("mymemory", rejected = true)
        assertFalse(health.allowed("gtx"))
        assertFalse(health.allowed("mymemory"))

        health.clearTransient()
        assertTrue("Falha transitória deve limpar ao reconectar", health.allowed("gtx"))
        assertFalse("Bloqueio HTTP 429/403 do servidor deve ser respeitado", health.allowed("mymemory"))
    }

    @Test fun scriptLanguageDetectsMultiscriptAndEmbeddedEnglishChorus() {
        assertEquals("ru", ScriptLanguage.effective("Привет, как твои дела сегодня?", "en"))
        assertEquals("ar", ScriptLanguage.effective("مرحبا يا صديقي كيف حالك", "en"))
        assertEquals("el", ScriptLanguage.effective("Καλημέρα φίλε μου", "en"))
        assertEquals("th", ScriptLanguage.effective("สวัสดีครับเพื่อน", "en"))
        assertEquals("hi", ScriptLanguage.effective("नमस्ते मेरे दोस्त", "en"))
        assertEquals("he", ScriptLanguage.effective("שלום חבר שלי", "en"))
        assertEquals("en", ScriptLanguage.effective("I just want to stay with you tonight", "ko"))
        assertEquals("ko", ScriptLanguage.effective("사랑해 너를 정말 많이 사랑해", "en"))
    }

    @Test fun serbianMapsToCroatianMlKitWithCyrillicToLatinTransliteration() {
        assertEquals("hr", LanguageMap.mlKit("sr"))
        assertEquals("hr", LanguageCatalog.get("sr")?.mlKitCode)
        val translit = LanguageMap.prepareMlKitInput("Добро јутро, љубави и џез!", "sr")
        assertEquals("Dobro jutro, ljubavi i džez!", translit)
        assertEquals("zh", LanguageMap.lingva("zh-TW"))
        assertEquals("tl", LanguageMap.canonical("fil"))
    }

    @Test fun dictionaryPolicyDecodesHtmlEntitiesAndChecksAllScripts() {
        assertEquals("Não sei \"por quê\" d'água & luz", DictionaryPolicy.decodeEntities("Não sei &quot;por quê&quot; d&#39;água &amp; luz"))
        assertFalse(DictionaryPolicy.eligible("hello world", "ru"))
        assertFalse(DictionaryPolicy.eligible("hello world", "ar"))
        assertTrue(DictionaryPolicy.eligible("привет мир", "ru"))
        assertTrue(DictionaryPolicy.quality("at") > DictionaryPolicy.quality("lingva"))
        assertTrue(DictionaryPolicy.quality("lingva") > DictionaryPolicy.quality("mymemory"))
    }

    @Test fun playableAcceptsProvisionalAndPortugueseInForeignSessionCompletesWithoutLoop() {
        val provisional = SpeechCue(0, 2500, "Where are you going?").apply {
            output = "Para onde você vai?"
            provider = "gtx"
            reason = "PROVISIONAL:API_429"
        }
        assertFalse(TranslationCoverage.complete(provisional, "en", "pt"))
        assertTrue(TranslationCoverage.playable(provisional, "en", "pt"))

        val ptLineInEnStream = SpeechCue(3000, 5000, "Eu não sei se você está pronto").apply {
            output = "Eu não sei se você está pronto"
            provider = "original"
            reason = "ECHO:ORIGINAL"
        }
        assertTrue(TranslationCoverage.complete(ptLineInEnStream, "en", "pt"))
    }

    @Test fun videoBatchEligibilityRespectsShortLinesAndTimestampGap() {
        val head = SpeechCue(10_000, 12_500, "We need to leave before the storm arrives.")
        val near = SpeechCue(12_800, 15_200, "Take your coat and meet me outside the gate.")
        val far = SpeechCue(45_000, 47_500, "That cue is way too far ahead to hold up the first one.")
        val shortInterj = SpeechCue(13_000, 13_600, "Yes!")
        assertTrue(SubtitleTranslationPolicy.videoBatchEligible(head, near, "en", head, null))
        assertFalse(SubtitleTranslationPolicy.videoBatchEligible(head, far, "en", near, null))
        assertFalse(SubtitleTranslationPolicy.videoBatchEligible(head, shortInterj, "en", head, null))
    }

    @Test fun lyricsLanguageGuesserCoversEuropeanScriptsAndShortHooks() {
        fun l(text: String) = listOf(LyricLine(0, text))
        assertEquals("fr", LyricsParser.guessLanguage(l("Je suis dans la nuit avec mon coeur")))
        assertEquals("de", LyricsParser.guessLanguage(l("Ich bin nicht allein in der Nacht mit dir")))
        assertEquals("it", LyricsParser.guessLanguage(l("Io non sono solo nella notte con amore")))
        assertEquals("tr", LyricsParser.guessLanguage(l("Ben seni çok seviyorum bu gece için")))
        assertEquals("en", LyricsParser.guessLanguage(l("Hold me in the night")))
        assertNull(LyricsParser.guessLanguage(l("la la la")))
    }
}

class CompatibleEndpointFallbackTest {
    @Test fun noImplicitNetworkProviderWithoutConfiguration() {
        WebTranslate.configure { CompatibleApiConfig() }
        assertTrue(WebTranslate.translateBatch(listOf("Hello","World"),"en","pt").all { it == null })
        assertThrows(java.io.IOException::class.java) { WebTranslate.translateDetailed("Hello","en","pt") }
    }
}

class CoveragePolicyTest {
    private fun cue(output: String?=null, reason: String?=null)=SpeechCue(0,500,"Hello there!").apply { this.output=output; this.reason=reason }
    @Test fun missingBlankEchoAreSeparateFailures() {
        assertEquals(CoverageState.MISSING,TranslationCoverage.state(cue(),"en","pt"))
        assertEquals(CoverageState.EMPTY,TranslationCoverage.state(cue(" "),"en","pt"))
        assertEquals(CoverageState.SOURCE_ECHO,TranslationCoverage.state(cue("hello there."),"en","pt"))
    }
    @Test fun emptyRequiresExplicitSuppressionReason() {
        assertFalse(TranslationCoverage.complete(cue("","PENDING:429"),"en","pt"))
        assertTrue(TranslationCoverage.complete(cue("","SUPPRESSED:LAUGH"),"en","pt"))
    }
    @Test fun validTranslationAndOriginalLanguageAreComplete() {
        assertTrue(TranslationCoverage.complete(cue("Olá!"),"en","pt"))
        assertTrue(TranslationCoverage.complete(cue("Hello there!"),"en","en"))
    }
    @Test fun invalidProviderResponseIsRetryable() {
        assertEquals(CoverageState.INVALID,TranslationCoverage.state(cue("HTTP 429"),"en","pt"))
    }
    @Test fun attemptsNeverBecomeTerminal() {
        assertTrue(TranslationCoverage.retryDelay(1,100)<TranslationCoverage.retryDelay(5,100))
        assertEquals(60_100L,TranslationCoverage.retryDelay(100000,100))
    }
    @Test fun retryAfterRespectedAndOtherProviderStaysAvailable() {
        var now=1000L; val health=EndpointHealth { now }
        health.failure("api",true,120_000)
        now=100_000; assertFalse(health.allowed("api")); assertTrue(health.allowed("gtx"))
        now=122_000; assertTrue(health.allowed("api"))
    }
    @Test fun retryAfterAcceptsSecondsOrHttpDate() {
        assertEquals(60_000L,RetryAfter.millis("60",0))
        assertEquals(60_000L,RetryAfter.millis("Thu, 1 Jan 1970 00:01:00 GMT",0))
        assertEquals(0L,RetryAfter.millis("nonsense",0))
    }
    @Test fun shortChineseAnswerRemainsLocalButMusicDoesNot() {
        assertEquals(CueClass.INTERJECTION,NoiseClassifier.classify("不！","zh",300,1))
        assertEquals("Não.",NoiseClassifier.seed("不！","zh"))
        assertEquals(CueClass.NONSPEECH_OTHER,NoiseClassifier.classify("不","zh",300,1,"Music"))
    }
}

class DictionaryPolicyTest {
    @Test fun nfkcAndPunctuationNormalize() { assertEquals("hello world",DictionaryPolicy.normalize("Ｈｅｌｌｏ,  WORLD!","en")) }
    @Test fun cjkDoesNotKeepSpaces() { assertEquals("你好世界",DictionaryPolicy.normalize("你 好 世界！","zh")) }
    @Test fun turkishPreservesDottedAndDotlessI() { assertEquals("ı i",DictionaryPolicy.normalize("I İ","tr")) }
    @Test fun hashStableAndLanguageInDbKey() { assertEquals(DictionaryPolicy.hash("hello"),DictionaryPolicy.hash("hello")); assertNotEquals(DictionaryPolicy.hash("hello"),DictionaryPolicy.hash("Hello")) }
    @Test fun laughterCannotBeLearned() { assertFalse(DictionaryPolicy.eligible("ha ha ha ha","en")); assertFalse(DictionaryPolicy.eligible("哈哈哈哈","zh")) }
    @Test fun phraseAndInterjectionEligibility() { assertTrue(DictionaryPolicy.eligible("Where are you","en")); assertTrue(DictionaryPolicy.eligible("はい","ja")); assertFalse(DictionaryPolicy.eligible("a".repeat(400),"en")) }
    @Test fun scriptMismatchCannotBeLearned() { assertFalse(DictionaryPolicy.eligible("hello world","ja")); assertFalse(DictionaryPolicy.eligible("hello world","zh")) }
    @Test fun quotaAndHtmlNotTranslations() { for(t in listOf("", "MYMEMORY WARNING: quota exceeded", "<html>error</html>","hello world")) assertFalse(DictionaryPolicy.validTranslation("hello world",t)) }
    @Test fun phraseNeedsSessionsOrSixHits() { assertFalse(DictionaryPolicy.promote("PHRASE",3,1,true)); assertTrue(DictionaryPolicy.promote("PHRASE",3,2,true)); assertTrue(DictionaryPolicy.promote("PHRASE",6,1,true)) }
    @Test fun conflictsBlockPromotion() { assertFalse(DictionaryPolicy.promote("PHRASE",9,4,false)) }
    @Test fun interjectionNeedsTwoHits() { assertFalse(DictionaryPolicy.promote("INTERJECTION",1,1,true)); assertTrue(DictionaryPolicy.promote("INTERJECTION",2,1,true)) }
    @Test fun backendOrdering() { assertTrue(DictionaryPolicy.quality("api")>DictionaryPolicy.quality("mymemory")); assertTrue(DictionaryPolicy.quality("user")>DictionaryPolicy.quality("api")) }
}

class EndpointHealthTest {
    @Test fun failuresOpenAndRecover() { var now=0L; val h=EndpointHealth { now }; repeat(3){ h.failure("api") }; assertFalse(h.allowed("api")); assertTrue(h.allowed("gtx")); now=90001; assertTrue(h.allowed("api")); h.success("api"); assertTrue(h.allowed("api")) }
    @Test fun rejectionOpensImmediately() { val h=EndpointHealth { 0L }; h.failure("api",true); assertFalse(h.allowed("api")) }
}

class TranslationRaceTest {
    private fun ready(s: String?): CompletableFuture<String?> = CompletableFuture.completedFuture(s)
    @Test fun fastApiIsPrimaryAndMakesNoFallbackRequest() {
        val calls=AtomicInteger()
        assertEquals("API",TranslationRace().resolve(ready("API"),{ calls.incrementAndGet(); ready("Google") },{ error("No offline request") }))
        assertEquals(0,calls.get())
    }
    @Test fun hangingApiDoesNotBlockGoogleAndIsCancelled() {
        val primary=CompletableFuture<String?>()
        assertEquals("Google",TranslationRace(30,30,2000).resolve(primary,{ ready("Google") },{ ready(null) }))
        assertTrue(primary.isCancelled)
    }
    @Test fun invalidOrUncertainApiFallsBackInsteadOfHidingCue() {
        assertEquals("Google",TranslationRace().resolve(ready(null),{ ready("Google") },{ ready(null) }))
    }
    @Test fun apiExceptionFallsBack() {
        val f=CompletableFuture<String?>(); f.completeExceptionally(IllegalStateException("HTTP 429"))
        assertEquals("Google",TranslationRace().resolve(f,{ ready("Google") },{ ready(null) }))
    }
    @Test fun saturatedApiPoolSkipsWait() {
        assertEquals("Google",TranslationRace(10000,10000,1000).resolve(null,{ ready("Google") },{ error("Offline unnecessary") }))
    }
    @Test fun slowGoogleAllowsOfflineWithoutSerialTimeout() {
        val google=CompletableFuture<String?>()
        assertEquals("offline",TranslationRace(10,30,2000).resolve(null,{ google },{ ready("offline") }))
        assertTrue(google.isCancelled)
    }
    @Test fun deadlineCancelsAllHangingProviders() {
        val a=CompletableFuture<String?>();val b=CompletableFuture<String?>();val c=CompletableFuture<String?>()
        assertNull(TranslationRace(0,0,75).resolve(a,{ b },{ c }))
        assertTrue(a.isCancelled && b.isCancelled && c.isCancelled)
    }
    @Test fun allFailuresFinishWithoutWaitingFullBudget() {
        val calls=AtomicInteger()
        assertNull(TranslationRace().resolve(ready(null),{ calls.incrementAndGet(); ready(null) },{ calls.incrementAndGet(); ready(null) }))
        assertEquals(2,calls.get())
    }
    @Test fun newVideoCancelsOldWorkWithoutCallingFallback() {
        val old=CompletableFuture<String?>()
        assertNull(TranslationRace().resolve(old,{ error("Old session must not make requests") },{ error("Old session") },{ false }))
        assertTrue(old.isCancelled)
    }
    @Test fun lateApiCannotReplaceCommittedGoogleResult() {
        val api=CompletableFuture<String?>()
        val result=TranslationRace(0,100,1000).resolve(api,{ ready("Google") },{ ready(null) })
        assertFalse(api.complete("Late API")); assertEquals("Google",result)
    }
    @Test fun stillAcceptsPrimaryWhileGoogleIsPending() {
        val executor=Executors.newSingleThreadScheduledExecutor()
        val api=CompletableFuture<String?>(); val google=CompletableFuture<String?>()
        try {
            executor.schedule({ api.complete("API") },50,TimeUnit.MILLISECONDS)
            assertEquals("API",TranslationRace(0,1000,2000).resolve(api,{ google },{ ready(null) }))
            assertTrue(google.isCancelled)
        } finally { executor.shutdownNow() }
    }
}
