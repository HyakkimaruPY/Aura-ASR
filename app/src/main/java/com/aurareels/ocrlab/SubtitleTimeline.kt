package com.aurareels.ocrlab

import java.util.concurrent.ConcurrentHashMap

/** Time values always refer to media time, not wall-clock time or translation completion time. */
class SpeechCue(
    startMs: Long,
    @Volatile var endMs: Long,
    val source: String,
    stableId: String? = null,
    originStartMs: Long = startMs,
    originEndMs: Long = endMs
) {
    /** Current start. Starts equal to [originStartMs]; a human timing edit may move it without changing [id]. */
    @Volatile var startMs: Long = startMs; private set
    /** Recognition start & end. Feed stable [id], duplicate detection and the underlying `cues` row, unaffected by human retiming. */
    val originStartMs: Long = originStartMs
    @Volatile var originEndMs: Long = originEndMs; private set
    /** Translated text; null while pending, empty for a deliberately suppressed event. */
    val id: String = stableId ?: java.security.MessageDigest.getInstance("SHA-256")
        .digest("$originStartMs|$source".toByteArray(Charsets.UTF_8)).take(16).joinToString("") { "%02x".format(it) }
    @Volatile var humanTimed = false; private set
    @Volatile var humanEdited = false; private set
    @Volatile var suppressed = false; private set
    @Volatile private var translatedText: String? = null
    companion object { val changeEpoch = java.util.concurrent.atomic.AtomicLong() }
    var output: String?
        get() = translatedText
        @Synchronized set(value) {
            if (!humanEdited && translatedText != value) {
                translatedText = value
                changeEpoch.incrementAndGet()
            }
        }

    @Synchronized fun applyHuman(text: String, deleted: Boolean = false) {
        humanEdited = true; suppressed = deleted; translatedText = if (deleted) "" else text
        reason = if (deleted) "SUPPRESSED:HUMAN" else "HUMAN"; provider = "human"; changeEpoch.incrementAndGet()
    }

    /** Extends machine duration (repetition/sentence continuation) without overwriting an explicit human window. */
    @Synchronized fun extendMachineEnd(newEnd: Long) {
        originEndMs = maxOf(originEndMs, newEnd)
        if (!humanTimed) endMs = maxOf(endMs, newEnd)
        changeEpoch.incrementAndGet()
    }

    /** Human timing: the identity and origin timestamps stay intact; only the playback window moves. */
    @Synchronized fun applyTiming(start: Long, end: Long): Boolean {
        if (start < 0 || end < start + SubtitleTiming.MIN_DURATION_MS) return false
        startMs = start; endMs = end; humanTimed = true; changeEpoch.incrementAndGet()
        return true
    }

    @Synchronized fun snapshot(): SpeechCue = SpeechCue(startMs, endMs, source, id, originStartMs, originEndMs).also { copy ->
        copy.humanTimed = humanTimed
        copy.translatedText = translatedText; copy.humanEdited = humanEdited; copy.suppressed = suppressed
        copy.reason = reason; copy.provider = provider; copy.acousticSpeech = acousticSpeech; copy.preservedNames = preservedNames.toList()
    }

    @Volatile var acousticSpeech = false
    @Volatile var preservedNames: List<String> = emptyList()
    @Volatile var reason: String? = null
    @Volatile var provider: String? = null
    @Volatile var retryAt = 0L
    @Volatile var tries = 0
    @Volatile var lastTryAt = 0L
    @Volatile var busy = false
}

object SubtitleTiming {
    private val WRAP_SPACES = Regex("\\s+")
    const val LEAD_IN_MS = 0L
    const val LEAD_OUT_MS = 0L
    const val NEXT_GAP_MS = 80L
    const val MAX_READING_EXTENSION_MS = 2500L
    const val MIN_DURATION_MS = 200L
    const val TARGET_CPS = 19

    fun readableEnd(cue: SpeechCue, nextStart: Long? = null): Long {
        // A human-timed cue ends exactly where the person put it (still never over the next cue).
        if (cue.humanTimed) return if (nextStart != null) minOf(cue.endMs, maxOf(cue.startMs + 1, nextStart - NEXT_GAP_MS)) else cue.endMs
        val text = cue.output?.takeIf { it.isNotEmpty() } ?: return cue.endMs + LEAD_OUT_MS
        val needed = cue.startMs + text.length * 1000L / TARGET_CPS
        val end = maxOf(cue.endMs + LEAD_OUT_MS, minOf(needed, cue.endMs + MAX_READING_EXTENSION_MS))
        return if (nextStart != null) minOf(end, maxOf(cue.startMs + 1, nextStart - NEXT_GAP_MS)) else end
    }

    /** Two intentional lines; 42/32 characters is a soft target, never truncates spoken meaning. */
    fun display(cue: SpeechCue): String = cue.output?.let(NoiseFilter::cleanModelTokens).orEmpty()

    fun wrap(text: String, vertical: Boolean): String {
        val clean = text.replace(WRAP_SPACES, " ").trim(); val width = if (vertical) 32 else 42
        if (clean.length <= width) return clean
        val candidates = clean.indices.filter { clean[it] == ' ' }
        val mid = candidates.minByOrNull { kotlin.math.abs(it - clean.length / 2) } ?: return clean
        return clean.take(mid) + "\n" + clean.substring(mid + 1)
    }
}

class SubtitleTimeline {
    private val cues = ArrayList<SpeechCue>()
    private val seen = ConcurrentHashMap.newKeySet<String>()
    private var snapshotCache: List<SpeechCue>? = emptyList()

    @Synchronized fun add(startMs: Long, endMs: Long, source: String, mergeRepetitions: Boolean = true, maxDurationMs: Long = 9500): SpeechCue? {
        val clean = NoiseFilter.cleanModelTokens(source)
        if (clean.isEmpty() || clean.length > 350 || endMs <= startMs) return null
        val start = startMs.coerceAtLeast(0)
        val end = endMs.coerceIn(start + 120, start + maxDurationMs.coerceAtLeast(120))
        // Origin, not current start: a cue the user moved must not come back when recognition revisits that audio.
        val existing = cues.firstOrNull { it.originStartMs == start && it.source == clean }
        if (existing != null) return null
        val previous = cues.lastOrNull()
        // Only adjacent cues merge; an intervening speaker/text breaks the repetition run.
        if (mergeRepetitions && previous != null && !previous.humanEdited && start >= previous.startMs && start <= previous.endMs + 1500 &&
            DictionaryPolicy.normalize(previous.source, "und") == DictionaryPolicy.normalize(clean, "und")) {
            previous.extendMachineEnd(end)
            return null
        }
        val key = "$start:${clean.lowercase()}"
        if (!seen.add(key)) return null
        return SpeechCue(start, end, clean).also { cue ->
            val index = cues.binarySearchBy(start) { it.startMs }.let { if (it < 0) -it - 1 else it }
            cues.add(index, cue)
            snapshotCache = null
        }
    }

    @Synchronized fun at(positionMs: Long): SpeechCue? {
        var low = 0; var high = cues.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (cues[mid].startMs - SubtitleTiming.LEAD_IN_MS <= positionMs) low = mid + 1 else high = mid
        }
        var index = low - 1
        if (index < 0) return null
        while (index > 0 && cues[index].reason == "SUPPRESSED:MERGED") index--
        val cue = cues[index]
        var next = index + 1
        while (next < cues.size && cues[next].reason == "SUPPRESSED:MERGED") next++
        return cue.takeIf { !it.suppressed && positionMs < SubtitleTiming.readableEnd(it, cues.getOrNull(next)?.startMs) }
    }

    /** For live ASR, independent HLS recoveries can create overlapping hypotheses.
     * The ordinary next-cue clipping can reduce a translated 6 s sentence to 1 ms.
     * Prefer a playable caption actually covering this instant; do not allow an
     * untranslated later fragment to blank a finished caption. */
    @Synchronized fun atLive(positionMs: Long, sourceLanguage: String, targetLanguage: String): SpeechCue? {
        var low = 0; var high = cues.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (cues[mid].startMs <= positionMs) low = mid + 1 else high = mid
        }
        var chosen: SpeechCue? = null
        var fallback: SpeechCue? = null
        // Most recent finished, translated caption. A VAD/hard-cut window ends when the chunk ends, not when
        // the speaker is replaced, so the caption stays until a newer translated one takes over (bounded, short: a caption must not outlive the speech it translates).
        var held: SpeechCue? = null
        var index = low - 1
        while (index >= 0 && cues[index].startMs >= positionMs - 20_000) {
            val cue = cues[index--]
            if (cue.suppressed || cue.reason?.startsWith("SUPPRESSED:") == true) continue
            if (cue.endMs <= positionMs) {
                if (!cue.output.isNullOrBlank() && positionMs - cue.endMs <= holdFor(cue.output!!) &&
                    TranslationCoverage.playable(cue, sourceLanguage, targetLanguage) &&
                    (held == null || cue.endMs > held.endMs)) held = cue
                continue
            }
            if (fallback == null) fallback = cue
            if (!TranslationCoverage.playable(cue, sourceLanguage, targetLanguage) || cue.output.isNullOrBlank()) continue
            // Keep complete, longer-lived sentences instead of flickering between
            // fragments whose starts differ by a few milliseconds.
            if (chosen == null || cue.endMs > chosen.endMs + 300 ||
                (kotlin.math.abs(cue.endMs - chosen.endMs) <= 300 && cue.startMs > chosen.startMs)) chosen = cue
        }
        return chosen ?: held ?: fallback
    }

    /** Returns a cached immutable list snapshot without allocating a copy on every 350ms UI tick. */
    @Synchronized fun all(): List<SpeechCue> {
        snapshotCache?.let { return it }
        return cues.toList().also { snapshotCache = it }
    }

    @Synchronized fun size(): Int = cues.size
    @Synchronized fun lastEndMs(): Long? = cues.lastOrNull()?.endMs

    /** Applies human timings by stable id and restores start order (binary search in [at] depends on it). */
    @Synchronized fun applyTimings(timings: List<SubtitlePackageStore.Timing>) {
        if (timings.isEmpty()) return
        val byId = timings.associateBy { it.cueId }
        var changed = false
        cues.forEach { c -> byId[c.id]?.let { if (c.applyTiming(it.startMs, it.endMs)) changed = true } }
        if (changed) {
            cues.sortBy { it.startMs }
            snapshotCache = null
        }
    }

    @Synchronized fun restore(snapshot: List<SpeechCue>) {
        cues.clear(); seen.clear()
        cues.addAll(snapshot.sortedBy { it.startMs })
        cues.forEach { seen.add("${it.originStartMs}:${it.source.lowercase()}") }
        snapshotCache = cues.toList()
    }

    @Synchronized fun reset() {
        cues.clear(); seen.clear()
        snapshotCache = emptyList()
    }

    @Synchronized fun exportSrt(snapshot: List<SpeechCue> = all()): String =
        formatSrtEntries(snapshot.mapNotNull { c ->
            if (c.suppressed || c.reason?.startsWith("SUPPRESSED:") == true || c.output == "") null
            else {
                val text = NoiseFilter.cleanModelTokens(c.output ?: c.source)
                if (text.isBlank()) null else Triple(c.startMs, c.endMs, text)
            }
        })

    companion object {
        /** How long a finished live caption stays while its successor is still being recognised/translated. */
        const val LIVE_HOLD_MS = 2_500L
        const val LIVE_HOLD_MAX_MS = 5_000L
        /** Long translations need reading time, and a caption that finished decoding late must still be seen. */
        fun holdFor(text: String): Long = (text.length * 1000L / SubtitleTiming.TARGET_CPS).coerceIn(LIVE_HOLD_MS, LIVE_HOLD_MAX_MS)
        fun srtTime(ms: Long): String {
            val safe = ms.coerceAtLeast(0L)
            return "%02d:%02d:%02d,%03d".format(safe / 3_600_000, safe / 60_000 % 60, safe / 1000 % 60, safe % 1000)
        }

        /** Shared SRT serializer used by both ASR/Package timelines and OCR exports. */
        fun formatSrtEntries(entries: List<Triple<Long, Long, String>>): String = buildString {
            var seq = 0
            for ((startMs, endMs, rawText) in entries) {
                val text = rawText.trim()
                if (text.isEmpty()) continue
                val start = startMs.coerceAtLeast(0L)
                val end = maxOf(start + 100L, endMs)
                append(++seq).append('\n')
                append(srtTime(start)).append(" --> ").append(srtTime(end)).append('\n')
                append(text).append("\n\n")
            }
        }
    }
}
