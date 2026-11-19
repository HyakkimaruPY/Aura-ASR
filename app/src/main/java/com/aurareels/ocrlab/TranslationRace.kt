package com.aurareels.ocrlab

import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** API has first refusal, but a slow/unavailable primary must never hold the fallback queue.
 * Providers own bounded executors. Only the first valid result is committed to the cue. */
class TranslationRace(
    private val primaryGraceMs: Long = 900,
    private val offlineGraceMs: Long = 1200,
    private val budgetMs: Long = 6500
) {
    fun <T> resolve(primary: Future<T?>?, fallback: () -> Future<T?>?, offline: () -> Future<T?>?,
                    current: () -> Boolean = { true }): T? {
        val start = System.nanoTime()
        val deadline = start + TimeUnit.MILLISECONDS.toNanos(budgetMs)
        var secondary: Future<T?>? = null
        var local: Future<T?>? = null
        var fallbackStarted = false
        var offlineStarted = false
        var fallbackAt = start
        fun result(f: Future<T?>?): T? = if (f?.isDone == true && !f.isCancelled) runCatching { f.get() }.getOrNull() else null
        try {
            while (current() && !Thread.currentThread().isInterrupted && System.nanoTime() < deadline) {
                result(primary)?.let { return it }
                result(secondary)?.let { return it }
                result(local)?.let { return it }
                val now = System.nanoTime()
                if (!fallbackStarted && (primary == null || primary.isDone || now-start >= TimeUnit.MILLISECONDS.toNanos(primaryGraceMs))) {
                    fallbackStarted = true; fallbackAt = now; secondary = fallback()
                }
                result(primary)?.let { return it }
                result(secondary)?.let { return it }
                if (fallbackStarted && !offlineStarted && (secondary == null || secondary!!.isDone ||
                        now-fallbackAt >= TimeUnit.MILLISECONDS.toNanos(offlineGraceMs))) {
                    offlineStarted = true; local = offline()
                }
                if (offlineStarted && listOf(primary,secondary,local).all { it == null || it.isDone }) {
                    // Providers may have completed during the launches above.
                    return result(primary) ?: result(secondary) ?: result(local)
                }
                Thread.sleep(10)
            }
            return null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt(); return null
        } finally {
            listOf(primary,secondary,local).forEach { if (it != null && !it.isDone) it.cancel(true) }
        }
    }
}

object TranslationText {
    private val PROVIDER_ERROR = Regex(
        "MYMEMORY|QUERY LENGTH|QUOTA|TOO MANY REQUESTS|INVALID LANGUAGE|DAILY LIMIT|FORBIDDEN",
        RegexOption.IGNORE_CASE
    )
    private val SOURCE_SCRIPT = Regex(
        "[\\u0370-\\u03ff\\u0400-\\u04ff\\u0590-\\u05ff\\u0600-\\u06ff\\u0900-\\u09ff\\u0b80-\\u0bff\\u0c00-\\u0c7f\\u0d00-\\u0d7f\\u0e00-\\u0e7f\\u1100-\\u11ff\\u3040-\\u30ff\\u3400-\\u9fff\\uac00-\\ud7af\\uf900-\\ufaff]"
    )

    /** Latin-script text the provider returned unchanged (proper names, "OK", already-Portuguese speech) is a valid final subtitle. */
    fun benignEcho(source: String, candidate: String): String? {
        val s = NoiseFilter.cleanModelTokens(source)
        val c = DictionaryPolicy.decodeEntities(NoiseFilter.cleanModelTokens(candidate))
        if (s.isBlank() || c.isBlank() || c.length > 600 || SOURCE_SCRIPT.containsMatchIn(s) || !s.any { it.isLetter() }) return null
        if (PROVIDER_ERROR.containsMatchIn(c) && !PROVIDER_ERROR.containsMatchIn(s)) return null
        return if (DictionaryPolicy.normalize(s, "en") == DictionaryPolicy.normalize(c, "en")) s else null
    }

    /** Compiled once: accepted() runs for every cue on every coverage audit. */
    fun accepted(source: String, candidate: String, targetLanguage: String, preservedNames: List<String> = emptyList()): String? {
        val cleanSource = NoiseFilter.cleanModelTokens(source)
        val text = DictionaryPolicy.decodeEntities(NoiseFilter.cleanModelTokens(candidate))
        val names = preservedNames.filter { it.isNotBlank() && cleanSource.contains(it) && text.contains(it) }
        if (names.any { it == cleanSource && it == text }) return text
        if (!DictionaryPolicy.validTranslation(cleanSource, text)) return null
        if (DictionaryPolicy.normalize(cleanSource, "en") == DictionaryPolicy.normalize(text, "en")) return null
        // Reject obvious source-script echoes for a Latin-script Portuguese target.
        if (targetLanguage == "pt" && SOURCE_SCRIPT.containsMatchIn(names.fold(text) { value, name -> value.replace(name, " ") })) return null
        return text
    }
}
