package com.aurareels.ocrlab

enum class CoverageState { MISSING, EMPTY, SOURCE_ECHO, INVALID, PROVISIONAL, SUPPRESSED, TRANSLATED }
object TranslationCoverage {
    fun state(cue: SpeechCue, sourceLanguage: String, targetLanguage: String): CoverageState {
        if(cue.humanEdited) return if(cue.suppressed) CoverageState.SUPPRESSED else CoverageState.TRANSLATED
        if(cue.reason?.startsWith("SUPPRESSED:")==true) return CoverageState.SUPPRESSED
        // Text the providers returned unchanged (names, already-Portuguese speech) is final, not an endless retry.
        if(cue.reason=="ECHO:ORIGINAL" && !cue.output.isNullOrBlank()) return CoverageState.TRANSLATED
        val output=cue.output ?: return CoverageState.MISSING
        if(output.isBlank()) return CoverageState.EMPTY
        if(cue.reason?.startsWith("MERGED:")==true) return CoverageState.TRANSLATED   // full sentence from a merged continuation
        if(cue.reason?.startsWith("PROVISIONAL:")==true) return CoverageState.PROVISIONAL
        val effectiveSource=ScriptLanguage.effective(cue.source,sourceLanguage)
        if(effectiveSource==targetLanguage) return CoverageState.TRANSLATED
        if(cue.preservedNames.any { it==cue.source && it==output }) return CoverageState.TRANSLATED
        if(DictionaryPolicy.normalize(cue.source,"en")==DictionaryPolicy.normalize(output,"en")) return CoverageState.SOURCE_ECHO
        return if(TranslationText.accepted(cue.source,output,targetLanguage,cue.preservedNames)!=null) CoverageState.TRANSLATED else CoverageState.INVALID
    }
    fun complete(cue: SpeechCue, sourceLanguage: String, targetLanguage: String)=
        state(cue,sourceLanguage,targetLanguage).let { it==CoverageState.SUPPRESSED || it==CoverageState.TRANSLATED }
    /** True when the cue already carries a displayable target-language text (final or provisional fallback). */
    fun playable(cue: SpeechCue, sourceLanguage: String, targetLanguage: String)=
        state(cue,sourceLanguage,targetLanguage).let { it==CoverageState.TRANSLATED || it==CoverageState.PROVISIONAL }
    /** No terminal attempt count. Delays grow, then remain capped indefinitely. */
    fun retryDelay(attempt: Int, jitter: Long)=
        (1000L shl (attempt-1).coerceIn(0,6)).coerceAtMost(60_000)+jitter.coerceIn(0,1000)
}

class ProviderHttpException(val status: Int, val retryAfterMs: Long=0) : java.io.IOException("HTTP $status")
object RetryAfter {
    fun millis(header: String?, now: Long=System.currentTimeMillis()): Long {
        val value=header?.trim() ?: return 0
        value.toLongOrNull()?.let { return it.coerceIn(0,86400)*1000 }
        return runCatching {
            (java.time.ZonedDateTime.parse(value,java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()-now).coerceIn(0,86_400_000)
        }.getOrDefault(0)
    }
}
