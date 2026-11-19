package com.aurareels.ocrlab

import java.text.Collator
import java.util.Locale

data class LanguageProfile(val vadMinSilenceMs: Int = 350, val vadMaxSegmentS: Float = 12f,
    val joinWithoutSpaces: Boolean = false, val maxCharsPerLine: Int = 42, val maxCps: Int = 19)
data class LanguageEntry(val code: String, val namePt: String, val engines: List<AsrBackend>,
    val profile: LanguageProfile, val googleCode: String = code, val memoryCode: String = code,
    val mlKitCode: String = code, val hasCinemaTag: Boolean = true)

/** Single source of truth. Portuguese remains a target/LID result, never a selectable source. */
object LanguageCatalog {
    private val names = mapOf(
        "de" to "Alemão", "ar" to "Árabe", "bn" to "Bengali", "yue" to "Cantonês",
        "ca" to "Catalão", "zh" to "Chinês (mandarim)", "ko" to "Coreano", "da" to "Dinamarquês",
        "es" to "Espanhol", "tl" to "Filipino", "fi" to "Finlandês", "fr" to "Francês",
        "el" to "Grego", "he" to "Hebraico", "hi" to "Hindi", "nl" to "Holandês",
        "hu" to "Húngaro", "id" to "Indonésio", "en" to "Inglês", "it" to "Italiano",
        "ja" to "Japonês", "ml" to "Malaiala", "ms" to "Malaio", "mr" to "Marati",
        "no" to "Norueguês", "fa" to "Persa", "pl" to "Polonês", "ro" to "Romeno",
        "ru" to "Russo", "sr" to "Sérvio", "sv" to "Sueco", "th" to "Tailandês",
        "ta" to "Tâmil", "cs" to "Tcheco", "te" to "Telugo", "tr" to "Turco",
        "uk" to "Ucraniano", "ur" to "Urdu", "vi" to "Vietnamita"
    )
    val senseVoiceLanguages = setOf("zh", "ja", "ko", "en", "yue")
    // Base lacks the extra Cantonese token introduced in Whisper large-v3.
    val whisperLanguages = names.keys - "yue"
    val entries: List<LanguageEntry> = names.map { (code, name) ->
        val engines = buildList {
            if (code in senseVoiceLanguages) add(AsrBackend.SENSEVOICE)
            if (code in whisperLanguages) add(AsrBackend.WHISPER)
            if (code in setOf("en", "fr", "zh", "ja", "it", "tr", "ko")) add(AsrBackend.VOSK)
        }
        LanguageEntry(code, name, engines, LanguageProfile(
            vadMinSilenceMs = if (code in setOf("ja", "zh", "yue", "ko")) 250 else 350,
            joinWithoutSpaces = code in setOf("zh", "ja", "yue")),
            googleCode = when(code) { "zh", "yue" -> "zh-CN"; "he" -> "iw"; else -> code },
            memoryCode = if (code in setOf("zh", "yue")) "zh-CN" else code,
            mlKitCode = when(code) { "yue" -> "zh"; "sr" -> "hr"; else -> code })
    }.sortedWith { a, b -> Collator.getInstance(Locale.forLanguageTag("pt-BR")).compare(a.namePt, b.namePt) }
    fun get(code: String): LanguageEntry? = entries.firstOrNull { it.code == code }
    fun profile(code: String) = get(code)?.profile ?: LanguageProfile()
}
