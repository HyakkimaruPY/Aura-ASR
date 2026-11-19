package com.aurareels.ocrlab

import java.text.Normalizer
import java.util.Locale

object DictionaryPolicy {
    private val PUNCT_OR_SYMBOL = Regex("[\\p{P}\\p{S}]+")
    private val SPACES = Regex("\\s+")
    private val NUMERIC_DEC_ENTITY = Regex("&#(\\d{1,6});")
    private val NUMERIC_HEX_ENTITY = Regex("&#[xX]([0-9a-fA-F]{1,6});")
    private val ERROR_MARKERS = Regex(
        "<[^>]+>|https?://|MYMEMORY WARNING|QUERY LENGTH|QUOTA|TOO MANY REQUESTS|INVALID LANGUAGE|DAILY LIMIT|403 FORBIDDEN|(?<!\\d)429(?!\\d)",
        RegexOption.IGNORE_CASE
    )

    fun normalize(text: String, language: String): String {
        val locale = if (language == "tr") Locale.forLanguageTag("tr") else Locale.ROOT
        val value = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(locale)
            .replace(PUNCT_OR_SYMBOL, " ").replace(SPACES, " ").trim()
        return if (LanguageCatalog.profile(language).joinWithoutSpaces) value.replace(" ", "") else value
    }

    fun hash(text: String): Long {
        var hash = -3750763034362895579L // FNV-1a offset, signed 64 bit
        for (byte in text.toByteArray(Charsets.UTF_8)) hash = (hash xor (byte.toLong() and 255)) * 1099511628211L
        return hash
    }

    /** Decodes HTML character entities returned by web memories without turning tags into executable markup. */
    fun decodeEntities(text: String): String {
        if ('&' !in text) return text
        return text
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&#x27;", "'", ignoreCase = true)
            .replace("&apos;", "'")
            .replace("&nbsp;", " ")
            .let { s ->
                NUMERIC_DEC_ENTITY.replace(s) { m ->
                    val cp = m.groupValues[1].toIntOrNull()
                    if (cp != null && cp in 32..0x10FFFF && cp != '<'.code && cp != '>'.code && Character.isValidCodePoint(cp))
                        String(Character.toChars(cp)) else m.value
                }
            }
            .let { s ->
                NUMERIC_HEX_ENTITY.replace(s) { m ->
                    val cp = m.groupValues[1].toIntOrNull(16)
                    if (cp != null && cp in 32..0x10FFFF && cp != '<'.code && cp != '>'.code && Character.isValidCodePoint(cp))
                        String(Character.toChars(cp)) else m.value
                }
            }
            .replace("&amp;", "&")
    }

    fun eligible(text: String, language: String): Boolean {
        if (LanguageCatalog.get(language) == null || !scriptMatches(text, language)) return false
        val kind = NoiseClassifier.classify(text, language, 2000, 0)
        if (kind == CueClass.INTERJECTION) return true
        if (kind != CueClass.SPEECH) return false
        val normalized = normalize(text, language)
        return if (LanguageCatalog.profile(language).joinWithoutSpaces) normalized.length in 3..40
            else normalized.length <= 180 && normalized.split(' ').size in 2..12
    }

    fun scriptMatches(text: String, language: String): Boolean = when(language) {
        "ja" -> Regex("[\\u3040-\\u30ff\\u3400-\\u9fff]").containsMatchIn(text)
        "zh", "yue" -> Regex("[\\u3400-\\u9fff]").containsMatchIn(text)
        "ko" -> Regex("[\\uac00-\\ud7af]").containsMatchIn(text)
        "ar", "fa", "ur" -> Regex("[\\u0600-\\u06ff]").containsMatchIn(text)
        "ru", "uk" -> Regex("[\\u0400-\\u04ff]").containsMatchIn(text)
        "sr" -> Regex("[\\u0400-\\u04ff\\p{IsLatin}]").containsMatchIn(text)
        "el" -> Regex("[\\u0370-\\u03ff]").containsMatchIn(text)
        "he" -> Regex("[\\u0590-\\u05ff]").containsMatchIn(text)
        "th" -> Regex("[\\u0e00-\\u0e7f]").containsMatchIn(text)
        "hi", "mr" -> Regex("[\\u0900-\\u097f]").containsMatchIn(text)
        "bn" -> Regex("[\\u0980-\\u09ff]").containsMatchIn(text)
        "ta" -> Regex("[\\u0b80-\\u0bff]").containsMatchIn(text)
        "te" -> Regex("[\\u0c00-\\u0c7f]").containsMatchIn(text)
        "ml" -> Regex("[\\u0d00-\\u0d7f]").containsMatchIn(text)
        else -> text.any { it.isLetter() }
    }

    fun validTranslation(source: String, target: String): Boolean {
        val t = target.trim()
        if (t.isEmpty() || t.length > 600 || t.equals(source.trim(), true)) return false
        // Provider error markers only count when the source did not contain them (a real "429" or "<b>" in speech is legitimate).
        if (ERROR_MARKERS.findAll(t).any { m -> !source.contains(m.value, true) }) return false
        return t.length <= maxOf(40, source.length * 8) && t.length * 12 >= source.length
    }

    fun promote(kind: String, hits: Int, sessions: Int, consistent: Boolean) = consistent &&
        if (kind == "INTERJECTION") hits >= 2 else (hits >= 3 && sessions >= 2) || hits >= 6

    fun quality(backend: String) = when(backend) {
        "user", "seed" -> 100; "api" -> 90; "gtx", "at" -> 80; "chrome", "lingva" -> 75; "mymemory" -> 50; else -> 30
    }
}
