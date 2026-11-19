package com.aurareels.ocrlab

import java.util.Locale

enum class CueClass { SPEECH, LAUGH, INTERJECTION, FILLER, NONSPEECH_OTHER, HALLUCINATION }
object NoiseClassifier {
    private val laugh = Regex("(?:ha){2,}|(?:he){2,}|(?:哈){2,}|(?:呵){2,}|(?:嘿){2,}|(?:嘻){2,}|(?:は){2,}|(?:ふ){2,}|あはは+|えへへ|w+|笑|ㅋ+|ㅎ+|(?:하){2,}|(?:크){2,}|lol|k{2,}|(?:ها){2,}|ه{3,}")
    private val events = Regex("<\\|([^|>]+)\\|>|\\[([^]]+)\\]|\\(([^)]+)\\)")
    private val bareStrip = Regex("[\\p{P}\\s]+")
    private val nonLetters = Regex("[^\\p{L}]+")
    private val spaces = Regex("\\s+")
    private val nonspeech = setOf("music", "bgm", "applause", "cough", "breath", "sneeze", "cry", "nospeech", "noise")
    val interjections = mapOf(
        "en" to mapOf(
            "oh" to "Ah!", "wow" to "Uau!", "hey" to "Ei!", "yes" to "Sim.", "yeah" to "Sim.", "yep" to "Sim.",
            "no" to "Não.", "nope" to "Não.", "okay" to "Certo.", "ok" to "Certo.", "ouch" to "Ai!",
            "oops" to "Opa!", "thanks" to "Obrigado.", "thankyou" to "Obrigado.", "sorry" to "Desculpe.",
            "goodbye" to "Adeus.", "bye" to "Tchau."
        ),
        "zh" to mapOf(
            "哎" to "Ei!", "哎呀" to "Ai!", "嗯" to "Hum.", "啊" to "Ah!", "不" to "Não.", "不是" to "Não.",
            "是" to "Sim.", "是的" to "Sim.", "好" to "Certo.", "好的" to "Certo.", "对" to "Isso.",
            "走" to "Vamos.", "走吧" to "Vamos.", "谢谢" to "Obrigado.", "对不起" to "Desculpe.",
            "抱歉" to "Desculpe.", "你好" to "Olá.", "再见" to "Tchau.", "救命" to "Socorro!"
        ),
        "yue" to mapOf("啊" to "Ah!", "嗯" to "Hum.", "係" to "Sim.", "唔係" to "Não.", "多謝" to "Obrigado.", "唔該" to "Por favor."),
        "ja" to mapOf(
            "はい" to "Sim.", "ええ" to "Sim.", "うん" to "Sim.", "えっ" to "Hein?", "ああ" to "Ah!",
            "いいえ" to "Não.", "いや" to "Não.", "ありがとう" to "Obrigado.", "ありがとうございます" to "Muito obrigado.",
            "すみません" to "Com licença.", "ごめん" to "Desculpe.", "ごめんなさい" to "Desculpe.",
            "おはよう" to "Bom dia.", "おやすみ" to "Boa noite.", "さようなら" to "Adeus.", "助けて" to "Socorro!"
        ),
        "ko" to mapOf(
            "네" to "Sim.", "예" to "Sim.", "응" to "Sim.", "아니요" to "Não.", "아니" to "Não.",
            "아" to "Ah!", "어" to "Ah!", "고마워" to "Obrigado.", "고마워요" to "Obrigado.",
            "감사합니다" to "Obrigado.", "미안해" to "Desculpe.", "미안해요" to "Desculpe.",
            "죄송합니다" to "Desculpe.", "안녕하세요" to "Olá.", "도와줘" to "Socorro!"
        ),
        "es" to mapOf("sí" to "Sim.", "no" to "Não.", "hola" to "Olá!", "gracias" to "Obrigado.", "perdón" to "Perdão.", "adiós" to "Adeus.", "vale" to "Certo."),
        "fr" to mapOf("oui" to "Sim.", "non" to "Não.", "merci" to "Obrigado.", "salut" to "Oi!", "bonjour" to "Bom dia.", "pardon" to "Perdão.", "désolé" to "Desculpe."),
        "de" to mapOf("ja" to "Sim.", "nein" to "Não.", "danke" to "Obrigado.", "bitte" to "Por favor.", "hallo" to "Olá!", "entschuldigung" to "Desculpe.", "tschüss" to "Tchau."),
        "it" to mapOf("sì" to "Sim.", "no" to "Não.", "ciao" to "Oi!", "grazie" to "Obrigado.", "scusa" to "Desculpe.", "scusi" to "Com licença.", "prego" to "De nada."),
        "ru" to mapOf("да" to "Sim.", "нет" to "Não.", "спасибо" to "Obrigado.", "привет" to "Olá!", "пока" to "Tchau.", "извините" to "Desculpe.", "простите" to "Perdão."),
        "tr" to mapOf("evet" to "Sim.", "hayır" to "Não.", "ah" to "Ah!", "teşekkürler" to "Obrigado.", "merhaba" to "Olá!", "lütfen" to "Por favor.", "pardon" to "Perdão."),
        "ar" to mapOf("نعم" to "Sim.", "لا" to "Não.", "آه" to "Ah!", "شكرا" to "Obrigado.", "شكراً" to "Obrigado.", "عفوا" to "De nada.", "مرحبا" to "Olá!")
    )
    private fun bare(text: String) = text.lowercase(Locale.ROOT).replace(bareStrip, "")
    fun seed(text: String, language: String): String? = interjections[language]?.get(bare(text))
    fun classify(raw: String, language: String, durationMs: Long, strictness: Int, event: String? = null, lyrics: Boolean = false): CueClass {
        fun eventNames(value: String): List<String> = value.lowercase(Locale.ROOT)
            .split(nonLetters).filter { it.isNotBlank() }
        // sherpa returns event separately, sometimes already enclosed in <|...|>.
        val tags = events.findAll(raw).flatMap { eventNames(it.value) }.toList() + eventNames(event.orEmpty())
        val text = NoiseFilter.clean(raw)
        val plain = bare(text)
        // Singing is often tagged as Music by SenseVoice. Keep lexical lyrics and repetitions;
        // empty event labels and known subtitle-credit hallucinations remain rejected.
        if (lyrics) return if (NoiseFilter.reject(text, language, durationMs, strictness, lyrics=true))
            CueClass.HALLUCINATION else CueClass.SPEECH
        val speechEvent = "speech" in tags
        val shortText = if (NoiseFilter.cjkCount(text) > 0) plain.length <= 2
            else text.split(spaces).size <= 2
        val laughEvent = tags.any { it in setOf("laughter", "laugh", "risos", "笑い") }
        if (!speechEvent) {
            if (laughEvent && (shortText || durationMs < 1200)) return CueClass.LAUGH
            if (tags.any { it in setOf("cough", "breath", "breathing", "sneeze", "cry", "crying", "applause", "bark", "noise", "nospeech") })
                return CueClass.NONSPEECH_OTHER
            if (tags.any { it in setOf("music", "bgm") } && (shortText || durationMs < 1200))
                return CueClass.NONSPEECH_OTHER
        }
        // Background music alongside a complete sentence must not erase dialogue.
        if (laugh.matches(plain) || (plain.isEmpty() && tags.any { it in setOf("laughter", "laugh", "risos", "笑い") })) return CueClass.LAUGH
        if (plain.isEmpty() && tags.any { it in nonspeech }) return CueClass.NONSPEECH_OTHER
        if (seed(text, language) != null && durationMs >= 200) return CueClass.INTERJECTION
        if (durationMs < 200 && !speechEvent) return CueClass.NONSPEECH_OTHER
        if (plain in setOf("uh", "um", "hmm", "euh", "ıı")) return CueClass.FILLER
        val level=if(speechEvent && durationMs>=200) minOf(strictness,0) else strictness
        return if (NoiseFilter.reject(text, language, durationMs, level)) CueClass.HALLUCINATION else CueClass.SPEECH
    }
}
