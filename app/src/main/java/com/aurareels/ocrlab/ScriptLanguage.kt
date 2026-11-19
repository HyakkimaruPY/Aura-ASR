package com.aurareels.ocrlab

/** The written script of recognised text is stronger evidence than a wrong acoustic language guess. */
object ScriptLanguage {
    private data class ScriptCounts(
        val han: Int, val kana: Int, val hangul: Int, val cyrillic: Int,
        val arabic: Int, val hebrew: Int, val greek: Int, val thai: Int,
        val devanagari: Int, val letters: Int
    ) {
        val cjk: Int get() = han + kana + hangul
        val nonLatin: Int get() = cjk + cyrillic + arabic + hebrew + greek + thai + devanagari
    }

    private val NON_LATIN_DECLARED = setOf(
        "zh", "yue", "ja", "ko", "ru", "uk", "ar", "fa", "ur",
        "he", "el", "th", "hi", "mr", "bn", "ta", "te", "ml"
    )

    private fun counts(text: String): ScriptCounts {
        var han = 0; var kana = 0; var hangul = 0; var cyrillic = 0
        var arabic = 0; var hebrew = 0; var greek = 0; var thai = 0
        var devanagari = 0; var letters = 0
        for (ch in text) {
            when {
                ch in '\u3040'..'\u30ff' -> kana++
                ch in '\uac00'..'\ud7af' || ch in '\u1100'..'\u11ff' -> hangul++
                ch in '\u3400'..'\u9fff' || ch in '\uf900'..'\ufaff' -> han++
                ch in '\u0400'..'\u04ff' -> cyrillic++
                ch in '\u0600'..'\u06ff' -> arabic++
                ch in '\u0590'..'\u05ff' -> hebrew++
                ch in '\u0370'..'\u03ff' -> greek++
                ch in '\u0e00'..'\u0e7f' -> thai++
                ch in '\u0900'..'\u097f' -> devanagari++
            }
            if (ch.isLetter()) letters++
        }
        return ScriptCounts(han, kana, hangul, cyrillic, arabic, hebrew, greek, thai, devanagari, letters)
    }

    /** Source language to translate [text] from when the declared one contradicts its script (e.g. "en" for Chinese, or English chorus in K-pop). */
    fun effective(text: String, declared: String): String {
        val c = counts(text)
        if (c.letters == 0) return declared
        val d = LanguageMap.canonical(declared)
        if (c.nonLatin == 0) {
            if (d != "pt" && looksPortuguese(text)) return "pt"
            if (d in NON_LATIN_DECLARED && looksEnglish(text)) return "en"
            return declared
        }
        if (c.nonLatin * 2 < c.letters) return declared
        return when {
            c.kana > 0 -> if (d == "ja") declared else "ja"
            c.hangul > 0 -> if (d == "ko") declared else "ko"
            c.han > 0 -> if (d == "zh" || d == "yue" || d == "ja" || d == "ko") declared else "zh"
            c.cyrillic > 0 -> if (d == "ru" || d == "uk" || d == "sr") declared else "ru"
            c.arabic > 0 -> if (d == "ar" || d == "fa" || d == "ur") declared else "ar"
            c.hebrew > 0 -> if (d == "he") declared else "he"
            c.greek > 0 -> if (d == "el") declared else "el"
            c.thai > 0 -> if (d == "th") declared else "th"
            c.devanagari > 0 -> if (d == "hi" || d == "mr") declared else "hi"
            else -> declared
        }
    }

    private val PORTUGUESE = setOf("não","você","vocês","está","estou","são","também","então","obrigado","obrigada","pra","né","muito","porque","mas","ainda","isso","essa","esse","aqui","fazer","tudo")
    private val STRONG = setOf("não","você","vocês","está","estou","são","também","então","obrigado","obrigada","né","pra")
    private val ENGLISH = setOf("the","you","your","and","are","with","this","that","from","have","will","what","when","where","never","always","love","know","want","need")
    private val ENGLISH_CONTRACTIONS = setOf("don't","i'm","can't","won't","it's","you're","we're","they're","isn't","didn't","doesn't","i'll","we'll")

    /** Latin text that is clearly Portuguese even though the acoustic detector said another language: nothing to translate. */
    fun looksPortuguese(text: String): Boolean {
        val words = text.lowercase().split(Regex("[^\\p{L}]+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return false
        val strong = words.count { it in STRONG }
        val weak = words.count { it in PORTUGUESE }
        return strong >= 2 || (strong >= 1 && weak >= 2) || (strong >= 1 && words.size <= 4)
    }

    /** Clear English line inside non-Latin media (e.g. English verses/choruses in CJK/Cyrillic songs or films). */
    fun looksEnglish(text: String): Boolean {
        val tokens = text.lowercase().replace('’', '\'').split(Regex("[^\\p{L}']+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return false
        if (tokens.any { it in ENGLISH_CONTRACTIONS }) return true
        return tokens.count { it in ENGLISH } >= 2
    }

    /** Weak language hint from a title/artist/file name; null when the text is Latin or ambiguous. */
    fun hint(vararg texts: String?): String? {
        val c = counts(texts.filterNotNull().joinToString(" "))
        if (c.letters == 0) return null
        return when {
            c.hangul > 0 && c.hangul >= c.kana -> "ko"
            c.kana > 0 -> "ja"
            c.han > 0 && c.han * 2 >= c.letters -> "zh"
            c.cyrillic > 0 && c.cyrillic * 2 >= c.letters -> "ru"
            c.arabic > 0 && c.arabic * 2 >= c.letters -> "ar"
            c.hebrew > 0 && c.hebrew * 2 >= c.letters -> "he"
            c.greek > 0 && c.greek * 2 >= c.letters -> "el"
            c.thai > 0 && c.thai * 2 >= c.letters -> "th"
            c.devanagari > 0 && c.devanagari * 2 >= c.letters -> "hi"
            else -> null
        }
    }
}
