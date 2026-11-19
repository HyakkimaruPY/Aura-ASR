package com.aurareels.ocrlab

/** Small, acoustically accepted utterances use the cheaper/faster translation path. */
object SubtitleTranslationPolicy {
    private val SPACES = Regex("\\s+")

    /** A short piece touching another cue may be a sentence fragment, not an interjection. */
    fun shortRoute(cue: SpeechCue, language: String, before: SpeechCue?, after: SpeechCue?): Boolean {
        if(!isShort(cue.source)) return false
        if(NoiseClassifier.seed(cue.source,language)!=null) return true
        val adjacentBefore=before!=null && cue.startMs-before.endMs in -100L..800L
        val adjacentAfter=after!=null && after.startMs-cue.endMs in -100L..800L
        return !adjacentBefore && !adjacentAfter
    }

    /** True when a queued video/package cue can share the same 2-3 item API batch as [head]. */
    fun videoBatchEligible(head: SpeechCue, candidate: SpeechCue, language: String, before: SpeechCue?, after: SpeechCue?): Boolean {
        if (candidate === head || candidate.humanEdited || candidate.suppressed) return false
        val clean = NoiseFilter.cleanModelTokens(candidate.source)
        if (clean.isBlank() || clean.length > 500 || candidate.endMs <= candidate.startMs) return false
        if (kotlin.math.abs(candidate.startMs - head.endMs) > 15_000L && kotlin.math.abs(head.startMs - candidate.startMs) > 18_000L) return false
        return !shortRoute(candidate, language, before, after)
    }

    fun musicBatchReady(pending: Int, known: Int, complete: Boolean, ageMs: Long): Boolean =
        pending>0 && (pending>=3 || complete || (ageMs>=1200 && known>=3) || (pending>=2 && ageMs>=2500))

    fun isShort(text: String): Boolean {
        val clean=NoiseFilter.clean(text).trim()
        if(clean.isEmpty() || !clean.any { it.isLetter() }) return false
        val glyphs=NoiseFilter.cjkCount(clean)
        return if(glyphs>0) clean.count { it.isLetter() }<=4
            else clean.length<=18 && clean.split(SPACES).size<=2
    }
}

data class ApiTimedLine(val id: String, val startMs: Long, val endMs: Long, val source: String)
data class ApiTranslatedLine(val id: String, val text: String?, val preservedNames: List<String> = emptyList(), val continuesPrevious: Boolean = false)

class ApiContractException(val detail: String): IllegalStateException("API contract=$detail")

/** Exact ID matching prevents a batch answer from moving one lyric into another lyric's time. */
object ApiBatchContract {
    private fun unwrap(raw: String): String {
        var text = raw.trim()
        repeat(2) {
            if (text.startsWith("<escape>") && text.endsWith("</escape>")) {
                text = text.removePrefix("<escape>").removeSuffix("</escape>").trim()
            }
            if (text.startsWith("```")) {
                text = text.substringAfter('\n', "").substringBeforeLast("```").trim()
            }
        }
        return text
    }

    fun decode(raw: String, batch: String, expected: List<ApiTimedLine>): List<ApiTranslatedLine> {
        val text=unwrap(raw)
        val result=org.json.JSONObject(text)
        if(result.length()!=3 || result.optString("schema")!="translation_batch_result_v1") throw ApiContractException("SCHEMA")
        if(result.optString("batch_id") != batch) throw ApiContractException("BATCH_ID")
        val rows=result.getJSONArray("items")
        if(rows.length()!=expected.size || expected.map { it.id }.toSet().size!=expected.size) throw ApiContractException("ITEM_COUNT")
        val remaining=expected.associateBy { it.id }.toMutableMap()
        val translated=HashMap<String,ApiTranslatedLine>()
        for(i in 0 until rows.length()) {
            val row=rows.getJSONObject(i)
            if(!row.keys().asSequence().all { it in setOf("id","status","translation","preserved_names","continues_previous") } || !row.has("id") || !row.has("status") || !row.has("translation")) throw ApiContractException("FIELDS")
            val cue=remaining.remove(row.getString("id")) ?: throw ApiContractException("ITEM_ID")
            val output=when(row.getString("status")) {
                "uncertain" -> { if(!row.isNull("translation")) throw ApiContractException("UNCERTAIN_TEXT"); null }
                "ok" -> (row.opt("translation") as? String ?: throw ApiContractException("TEXT")).trim().also { if(it.isEmpty() || it.length>600) throw ApiContractException("TEXT_LENGTH") }
                else -> throw ApiContractException("STATUS")
            }
            val names=row.optJSONArray("preserved_names")?.let { array ->
                // Advisory metadata is not the translation. Romanized/translated names may no
                // longer have the source spelling. Only proven matches can exempt source-script checks.
                (0 until minOf(array.length(),32)).mapNotNull { array.opt(it) as? String }.filter { name ->
                    name.isNotBlank() && name.length<=80 && cue.source.contains(name) && output?.contains(name)==true
                }.distinct().take(8)
            } ?: emptyList()
            translated[cue.id]=ApiTranslatedLine(cue.id,output,names,output!=null && row.optBoolean("continues_previous",false))
        }
        if(remaining.isNotEmpty()) throw ApiContractException("MISSING_ID")
        return expected.map { translated.getValue(it.id) }
    }
}

/** Logs structural failures only, never the response payload, session tokens or headers. */
object ApiFailure {
    private val STAGE_REGEX = Regex("stage=([a-z]+)")
    fun http(error: Throwable): ProviderHttpException? = generateSequence(error) { it.cause }.take(8).filterIsInstance<ProviderHttpException>().firstOrNull()
    fun contentFailure(error: Throwable): Boolean = generateSequence(error) { it.cause }.take(8).any {
        it is ApiContractException || it is org.json.JSONException || it.message=="Tradução IA inválida"
    }
    fun reason(error: Throwable): String {
        val chain=generateSequence(error) { it.cause }.take(8).toList()
        val stage=chain.mapNotNull { STAGE_REGEX.find(it.message.orEmpty())?.groupValues?.get(1) }.firstOrNull() ?: "response"
        val http=chain.filterIsInstance<ProviderHttpException>().firstOrNull()
        val kind=when {
            http!=null -> "HTTP_${http.status}"
            chain.any { it is java.net.SocketTimeoutException || it is java.util.concurrent.TimeoutException || it.message?.contains("timeout",true)==true } -> "TIMEOUT"
            chain.any { it.message?.contains("encerrado ou cancelado")==true || it.message=="Sessão de tradução alterada" } -> "CANCELLED"
            chain.any { it is org.json.JSONException } -> "INVALID_JSON"
            chain.any { it is java.util.concurrent.RejectedExecutionException } -> "BUSY"
            chain.any { it is ApiContractException } -> "CONTRACT_${chain.filterIsInstance<ApiContractException>().first().detail}"
            chain.any { it.message=="Tradução IA inválida" } -> "INVALID_TARGET_TEXT"
            chain.any { it is IllegalStateException } -> "INVALID_CLIENT_STATE"
            else -> chain.last().javaClass.simpleName
        }
        return "$kind@$stage"
    }
}
