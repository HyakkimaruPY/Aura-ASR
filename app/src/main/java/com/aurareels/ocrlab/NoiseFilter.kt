package com.aurareels.ocrlab

import kotlin.math.max
import kotlin.math.min

/**
 * Pure Kotlin (JVM-testable). Decides whether a recognizer hypothesis is real speech or an artefact
 * of noise (barking, music, laughter, engine hum): short, low-confidence, filler-only, looping or
 * "subtitle credit" hallucinations are rejected.
 *
 * [strictness]: 0 = permissive, 1 = normal, 2 = strict (user setting "Filtro de ruído").
 */
object NoiseFilter {
    private val CJK = Regex("[\\u3040-\\u30ff\\u3400-\\u9fff\\uac00-\\ud7af]")
    private val LETTER = Regex("[\\p{L}]")
    private val TAGS = Regex("<\\|[^|>]*\\|>|\\[[^\\]]{0,24}\\]|\\([^)]{0,24}\\)")
    private val LOOP = Regex("(.{1,6}?)\\1{3,}")

    /** Typical Whisper/SenseVoice hallucinations on non-speech audio (matched in lower case). */
    private val HALLUCINATIONS = listOf(
        "thanks for watching", "thank you for watching", "please subscribe", "subscribe to", "amara.org",
        "subtitles by", "subtitles made by", "legendas pela comunidade", "obrigado por assistir",
        "altyazı", "izlediğiniz için", "abone ol", "ご視聴ありがとうございました", "チャンネル登録",
        "字幕", "请不吝点赞", "点赞订阅", "订阅", "اشترك", "ترجمة", "نانسي قنقر", "شكرا للمشاهدة", "شكرًا للمشاهدة",
        "시청해 주셔서", "구독", "mbc 뉴스"
    )

    /** One-token utterances that noise typically produces (only dropped when strictness >= 1). */
    private val FILLERS = setOf(
        "the", "a", "uh", "um", "huh", "hm", "hmm", "mm", "oh", "ah", "i", "and", "so", "eh", "ha",
        "euh", "ben", "bah", "ah", "e", "è", "ma", "ee", "ıı", "hı", "evet", "ne", "bir", "هه", "آه", "ها"
    )

    /** Removes model tags ("<|ja|>", "[music]") and normalises whitespace. */
    /** Strip innermost control tokens first: old releases could produce <|<|Speech|>|>.
     * Keep ordinary parentheses and punctuation in translated dialogue. */
    fun cleanModelTokens(raw: String): String {
        var text = raw
        val token = Regex("<\\|[^<>|]*\\|>|<\\|>")
        while (true) {
            val next = text.replace(token, "")
            if (next == text) break
            text = next
        }
        return text.replace(Regex("\\s+"), " ").trim()
    }
    fun clean(raw: String): String = cleanModelTokens(raw).replace(TAGS, " ").replace(Regex("\\s+"), " ").trim()

    fun cjkCount(text: String): Int = CJK.findAll(text).count()

    /** Minimum average word confidence a Vosk hypothesis needs, by number of words. */
    fun minVoskConfidence(wordCount: Int, strictness: Int): Double {
        val base = when {
            wordCount <= 1 -> 0.80
            wordCount == 2 -> 0.65
            else -> 0.50
        }
        val shift = when (strictness) { 0 -> -0.12; 2 -> 0.10; else -> 0.0 }
        return (base + shift).coerceIn(0.2, 0.97)
    }

    /**
     * True when [text] (already cleaned) should be discarded. [durationMs] is the audio span it came
     * from. [language] is the canonical code.
     */
    fun reject(text: String, language: String, durationMs: Long, strictness: Int, lyrics: Boolean = false): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return true
        if (!LETTER.containsMatchIn(t)) return true                         // only punctuation / digits / symbols
        val lower = t.lowercase()
        if (durationMs < 7000 && t.length < 90 && HALLUCINATIONS.any { lower.contains(it) }) return true
        if (!lyrics && LOOP.containsMatchIn(lower.replace(" ", ""))) return true       // "ああああああ", "la la la la"
        val tokens = lower.split(' ').filter { it.isNotBlank() }
        if (!lyrics && tokens.size >= 4 && tokens.toSet().size == 1) return true
        if (lyrics) return durationMs < 200
        val cjk = cjkCount(t)
        if (cjk > 0 && cjk >= t.count { LETTER.matches(it.toString()) } / 2) {
            // Written CJK: one or two glyphs on a short burst is almost always noise.
            val glyphs = t.count { LETTER.matches(it.toString()) }
            val minGlyphs = if (strictness == 0) 1 else if (strictness == 1) 2 else 3
            if (glyphs < minGlyphs) return true
            if (durationMs < 350 && glyphs <= 2) return true
        } else {
            val words = tokens.size
            if (words == 1 && strictness >= 1) {
                val w = tokens[0].trim('.', ',', '!', '?', '…')
                if (w.length <= 2 || w in FILLERS) return true
                if (durationMs < 250) return true
            }
            if (strictness == 2 && words <= 2 && durationMs < 500) return true
        }
        return false
    }

    /**
     * Splits one long recognised segment into readable cues (sentence enders), distributing time
     * proportionally to text length. Short segments are returned unchanged.
     */
    fun split(text: String, startMs: Long, endMs: Long): List<Triple<Long, Long, String>> {
        val duration = max(1L, endMs - startMs)
        if (duration <= 4500 && text.length <= 70) return listOf(Triple(startMs, endMs, text))
        val pieces = ArrayList<String>()
        val current = StringBuilder()
        for (ch in text) {
            current.append(ch)
            if (ch in ".!?;。！？؟…" && current.length >= 8) { pieces.add(current.toString().trim()); current.setLength(0) }
        }
        if (current.isNotBlank()) {
            if (pieces.isNotEmpty() && current.length < 8) pieces[pieces.size - 1] = pieces.last() + " " + current.toString().trim()
            else pieces.add(current.toString().trim())
        }
        if (pieces.size <= 1) return listOf(Triple(startMs, endMs, text))
        val total = pieces.sumOf { it.length }.toDouble()
        val out = ArrayList<Triple<Long, Long, String>>()
        var cursor = startMs.toDouble()
        for (piece in pieces) {
            val share = duration * (piece.length / total)
            val from = cursor.toLong()
            val to = min(endMs.toDouble(), cursor + share).toLong()
            out.add(Triple(from, max(to, from + 300), piece))
            cursor += share
        }
        return out
    }

    /** Root-mean-square level of a PCM block, used to skip near-silent segments. */
    fun rms(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        var sum = 0.0
        for (s in samples) sum += s.toDouble() * s
        return Math.sqrt(sum / samples.size).toFloat()
    }
}
