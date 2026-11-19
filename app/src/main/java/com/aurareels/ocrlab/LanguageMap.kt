package com.aurareels.ocrlab

import java.text.Normalizer
import java.util.Locale

/**
 * Pure Kotlin (no Android dependency, so it is unit-testable on the JVM).
 * Converts the codes reported by the Whisper language identifier into the ids used by the
 * installed Vosk packs, and into the tags expected by each translation backend.
 */
object LanguageMap {
    /** Whisper / BCP-47 / ISO-639-3 variants -> canonical two-letter code used across the app. */
    private val aliases = mapOf(
        "yue" to "yue", "cmn" to "zh", "wuu" to "zh", "zho" to "zh", "chi" to "zh",
        "jpn" to "ja", "jp" to "ja",
        "eng" to "en", "fra" to "fr", "fre" to "fr", "ita" to "it", "tur" to "tr",
        "por" to "pt", "spa" to "es", "deu" to "de", "ger" to "de", "rus" to "ru", "kor" to "ko",
        "iw" to "he", "in" to "id", "jw" to "jv", "fil" to "tl", "tgl" to "tl"
    )

    private val names = LanguageCatalog.entries.associate { it.code to it.namePt.lowercase(Locale.ROOT) } +
        mapOf("pt" to "português", "jv" to "javanês")

    /**
     * Canonical language code: lower-case, region/script dropped ("zh-TW" -> "zh", "pt_BR" -> "pt"),
     * known aliases folded ("cmn" -> "zh"). Returns "" for blank / unusable input
     * (Whisper sometimes returns an empty string for non-speech audio).
     */
    fun canonical(raw: String?): String {
        // Whisper-style tokens ("<|en|>") and stray whitespace/quotes are stripped first.
        val token = raw.orEmpty().lowercase().replace("<|", " ").replace("|>", " ")
            .trim().trim('"', '\'', '`').trim().substringBefore(' ').substringBefore('\n')
            .substringBefore('-').substringBefore('_').trim()
        val base = fold(token)
        if (base.isEmpty() || base == "und" || base == "unknown" || base == "nospeech" || base == "null") return ""
        if (!base.all { it in 'a'..'z' }) return ""
        aliases[base]?.let { return it }
        // The detector may return the language NAME ("inglês", "English", "coreano") in the device
        // language instead of its ISO code, so names are resolved before short codes.
        nameIndex[base]?.let { return it }
        return if (base.length in 2..3) base else ""
    }

    private fun fold(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    /** folded language name (device locale, English and Portuguese) -> canonical code. */
    private val nameIndex: Map<String, String> by lazy {
        val index = HashMap<String, String>()
        val locales = listOf(Locale.getDefault(), Locale.ENGLISH, Locale.forLanguageTag("pt-BR"))
        for (iso in Locale.getISOLanguages()) {
            val code = aliases[iso] ?: iso            // java.util.Locale still reports legacy codes (iw, in, jw...)
            for (viewer in locales) {
                val name = Locale.forLanguageTag(iso).getDisplayLanguage(viewer)
                if (name.isNotBlank() && !name.equals(iso, ignoreCase = true)) index.putIfAbsent(fold(name), code)
            }
        }
        for ((code, pt) in names) index.putIfAbsent(fold(pt), code)
        index
    }

    /** Portuguese display name; falls back to the raw code instead of showing nothing. */
    fun displayName(code: String): String = names[canonical(code)] ?: code.ifBlank { "desconhecido" }

    /** Picks the installed ASR pack for a detected language, or null if it is not installed. */
    fun pickInstalled(detected: String, installedIds: Collection<String>): String? {
        val code = canonical(detected)
        if (code.isEmpty()) return null
        return installedIds.firstOrNull { canonical(it) == code }
    }

    /** Google web endpoints need zh-CN; everything else is passed through. */
    fun google(code: String): String = when (val c = canonical(code)) {
        "zh", "yue" -> "zh-CN"; "he" -> "iw"; "" -> code; else -> c
    }

    /** Legacy format adapter, kept for local language-code compatibility only. */
    fun lingva(code: String): String = when (val c = canonical(code)) {
        "zh", "yue" -> "zh"; "he" -> "iw"; "" -> code; else -> c
    }

    /** Legacy locale formatting adapter; no external service is contacted. */
    fun myMemory(code: String): String = when (val c = canonical(code)) {
        "zh", "yue" -> "zh-CN"; "pt" -> "pt-BR"; "" -> code; else -> c
    }

    /** Tag used for ML Kit (`TranslateLanguage.fromLanguageTag`): plain two-letter codes (sr -> hr). */
    fun mlKit(code: String): String = when (val c = canonical(code)) {
        "sr" -> "hr"
        else -> LanguageCatalog.get(c)?.mlKitCode ?: c.ifEmpty { code }
    }

    private val SERBIAN_CYRILLIC = mapOf(
        'А' to "A", 'Б' to "B", 'В' to "V", 'Г' to "G", 'Д' to "D", 'Ђ' to "Đ", 'Е' to "E", 'Ж' to "Ž",
        'З' to "Z", 'И' to "I", 'Ј' to "J", 'К' to "K", 'Л' to "L", 'Љ' to "Lj", 'М' to "M", 'Н' to "N",
        'Њ' to "Nj", 'О' to "O", 'П' to "P", 'Р' to "R", 'С' to "S", 'Т' to "T", 'Ћ' to "Ć", 'У' to "U",
        'Ф' to "F", 'Х' to "H", 'Ц' to "C", 'Ч' to "Č", 'Џ' to "Dž", 'Ш' to "Š",
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'ђ' to "đ", 'е' to "e", 'ж' to "ž",
        'з' to "z", 'и' to "i", 'ј' to "j", 'к' to "k", 'л' to "l", 'љ' to "lj", 'м' to "m", 'н' to "n",
        'њ' to "nj", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'ћ' to "ć", 'у' to "u",
        'ф' to "f", 'х' to "h", 'ц' to "c", 'ч' to "č", 'џ' to "dž", 'ш' to "š"
    )

    /** ML Kit lacks a dedicated "sr" pack, so Serbian Cyrillic is converted to Gaj's Latin for the "hr" model. */
    fun prepareMlKitInput(text: String, sourceCode: String): String {
        if (canonical(sourceCode) != "sr" || text.none { it in '\u0400'..'\u04ff' }) return text
        return buildString(text.length + 8) {
            for (ch in text) append(SERBIAN_CYRILLIC[ch] ?: ch)
        }
    }
}

/**
 * Accumulates acoustic language votes over a sliding window of the last [maxVotes] decided votes
 * (old votes expire, so an intro/music mis-detection cannot poison a later speech scene).
 * Blank votes never count. A language is confirmed when it has at least [required] votes in the
 * window AND strictly more than any other. Languages the app cannot transcribe ([supported] false)
 * need [requiredOther] votes: a false "Javanese" on music must not block or hijack the session.
 */
class LanguageVoteTracker(
    private val required: Int = 2,
    private val maxVotes: Int = 5,
    private val supported: (String) -> Boolean = { true },
    private val requiredOther: Int = 3
) {
    private val window = ArrayDeque<String>()
    var total = 0; private set

    fun reset() { window.clear(); total = 0 }

    /** Registers a vote; returns the confirmed code or null. Blank input is ignored. */
    fun add(raw: String?): String? {
        val code = LanguageMap.canonical(raw)
        if (code.isEmpty()) return null
        window.addLast(code)
        while (window.size > maxVotes) window.removeFirst()
        total++
        return confirmed()
    }

    private fun tally(): List<Pair<String, Int>> =
        window.groupingBy { it }.eachCount().entries.map { it.key to it.value }.sortedByDescending { it.second }

    fun confirmed(): String? {
        val ranked = tally()
        val top = ranked.firstOrNull() ?: return null
        val second = ranked.getOrNull(1)?.second ?: 0
        val need = if (supported(top.first)) required else requiredOther
        return if (top.second >= need && top.second > second) top.first else null
    }

    fun exhausted(): Boolean = window.size >= maxVotes && confirmed() == null
    fun count(code: String): Int { val c = LanguageMap.canonical(code); return window.count { it == c } }
    fun leader(): String? = tally().firstOrNull()?.first
}
