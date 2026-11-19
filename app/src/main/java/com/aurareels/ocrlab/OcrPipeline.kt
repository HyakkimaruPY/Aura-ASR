package com.aurareels.ocrlab

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/** Deliberately separate the video, OCR and translation threads while sharing the unified translation hub and model repository. */
class OcrPipeline private constructor(
    context: Context,
    private val repository: ModelRepository,
    private val ownsRepository: Boolean,
    private val status: (String) -> Unit
) : Closeable {

    constructor(context: Context, status: (String) -> Unit) :
        this(context, ModelRepository(context.applicationContext), true, status)

    constructor(context: Context, repository: ModelRepository, status: (String) -> Unit) :
        this(context, repository, false, status)

    class Cue(val startMs: Long, endMs: Long, val chinese: String) {
        val translation = SpeechCue(
            startMs,
            if (endMs == Long.MAX_VALUE) startMs + 15_000L else max(startMs + 200L, endMs),
            chinese
        )
        @Volatile var endMs: Long = endMs
            set(value) {
                field = value
                translation.endMs = if (value == Long.MAX_VALUE) max(translation.endMs, startMs + 15_000L) else max(startMs + 200L, value)
            }
        var portuguese: String?
            get() = translation.output
            set(value) { translation.output = value }
    }

    data class Snapshot(
        val samples: Int,
        val ocrRuns: Int,
        val averageOcrMs: Long,
        val translated: Int,
        val processedMs: Long
    )

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "aura-ocr") }
    private val translationService = GoogleTranslateBridge(context, status)
    private val generation = java.util.concurrent.atomic.AtomicLong()
    private val inFlight = AtomicBoolean(false)
    private val cropper = SubtitleCropper()
    private val cues = ArrayList<Cue>()
    private var paddle: PaddleTinyRecognizer? = null
    private val prepared = AtomicBoolean(false)
    private var lastSignature: Long? = null
    private var lastOcrAt = 0L
    private var lastText = ""
    private var proposed = ""
    private var proposedAt = 0L
    private var proposedHits = 0
    private var constantText = ""
    private var constantSinceMs = 0L
    private var suppressedConstant = false
    private var active: Cue? = null
    private var samples = 0
    private var ocrRuns = 0
    private var ocrTimeMs = 0L
    @Volatile var processedThroughMs = 0L
        private set
    @Volatile var engineName = "Inicializando"
        private set
    val translatorState: String get() = translationService.state
    @Volatile var topFraction = 0.58f
    @Volatile var bottomFraction = 0.935f

    private val appContext: Context = context.applicationContext

    /** OCR is not implicitly enabled and never silently falls back to a bundled model. */
    fun prepareModel() {
        prepared.set(false)
        worker.execute {
            paddle?.close()
            paddle = if (repository.installed("ocr")) try {
                PaddleTinyRecognizer(appContext, repository.directory("ocr"))
            } catch (e: Exception) { tell("OCR não carregou: ${e.message}"); null }
            else null
            engineName = if (paddle != null) "PP-OCRv6 Small · modelo local" else "OCR não instalado · abra Modelos"
            prepared.set(paddle != null)
            tell(engineName)
        }
        translationService.configure("zh", "pt")
    }

    /** True when [offer] would accept a frame now; lets callers skip allocating a Bitmap that would be dropped. */
    fun accepting(): Boolean = prepared.get() && !inFlight.get()

    /** Enqueues OCR work on [worker] and takes ownership of [frame]. */
    fun offer(frame: Bitmap, timestampMs: Long) {
        if (!prepared.get() || !inFlight.compareAndSet(false, true)) {
            frame.recycle()
            return
        }
        val epoch = generation.get()
        worker.execute {
            try {
                if (epoch != generation.get()) return@execute
                samples++
                val extraction = cropper.extract(frame, topFraction, bottomFraction)
                val hasLines = extraction.lines.isNotEmpty()
                val unchanged = lastSignature == extraction.signature &&
                    timestampMs - lastOcrAt < 1800L
                if (!unchanged) {
                    val start = System.nanoTime()
                    lastText = if (hasLines) extraction.lines.joinToString(" ") { paddle!!.recognize(it) } else ""
                    lastText = cleanText(lastText)
                    lastSignature = extraction.signature
                    lastOcrAt = timestampMs
                    ocrRuns++
                    ocrTimeMs += TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
                }
                synchronized(cues) {
                    if (epoch == generation.get()) {
                        observeLocked(lastText, timestampMs)
                        processedThroughMs = max(processedThroughMs, timestampMs)
                    }
                }
                extraction.lines.forEach { it.recycle() }
                extraction.region.recycle()
            } catch (e: Exception) {
                tell("OCR: ${e.message?.take(120)}")
                processedThroughMs = max(processedThroughMs, timestampMs)
            } finally {
                frame.recycle()
                inFlight.set(false)
            }
        }
    }

    /** Suppresses 1-glyph frame jitter on long CJK lines so compression noise does not duplicate cues or API calls. */
    private fun sameOrMinorJitter(current: String, candidate: String, elapsedMs: Long): Boolean {
        if (current == candidate) return true
        if (elapsedMs > 2_500L || current.length != candidate.length || current.length < 5) return false
        var diffs = 0
        for (i in current.indices) if (current[i] != candidate[i] && ++diffs > 1) return false
        return diffs <= 1
    }

    private fun observeLocked(newText: String, timeMs: Long) {
        var text = newText.take(180)
        if (text.count(::isChinese) < 2) text = ""
        if (text != constantText) {
            constantText = text
            constantSinceMs = timeMs
            suppressedConstant = false
        } else if (text.isNotEmpty() && timeMs - constantSinceMs >= 14_000L) {
            suppressedConstant = true
        }
        if (suppressedConstant && text == constantText) text = ""
        if (text != proposed) {
            proposed = text
            proposedAt = timeMs
            proposedHits = 1
        } else proposedHits++
        if (proposedHits < 2) return
        val cur = active
        if (cur != null && text.isNotBlank() && sameOrMinorJitter(cur.chinese, text, timeMs - cur.startMs)) {
            cur.translation.endMs = max(cur.translation.endMs, timeMs + 10_000L)
            return
        }
        if (cur == null && text.isBlank()) return
        cur?.endMs = max(cur!!.startMs + 100L, proposedAt)
        active = null
        if (text.isNotBlank()) {
            val cue = Cue(proposedAt, Long.MAX_VALUE, text)
            val idx = cues.binarySearchBy(proposedAt) { it.startMs }.let { if (it < 0) -it - 1 else it }
            cues.add(idx, cue)
            active = cue
            if (cue.portuguese == null) translate(cue)
            tell("ZH ${formatMs(proposedAt)} · ${text.take(65)}")
        }
    }

    private fun translate(cue: Cue) = translationService.translate(cue.translation)

    fun ensureTranslated(positionMs: Long) {
        val cue = cueAt(positionMs) ?: return
        if (cue.endMs == Long.MAX_VALUE) cue.translation.endMs = max(cue.translation.endMs, positionMs + 10_000L)
        translationService.ensure(cue.translation)
    }

    fun updatePlaybackPosition(positionMs: Long, advancing: Boolean) = translationService.updatePlaybackPosition(positionMs, advancing)
    val translationEpoch: Long get() = translationService.sessionEpoch
    fun exportSnapshot(): List<Cue> = synchronized(cues) { cues.toList() }
    fun translatePendingBlocking(snapshot: List<Cue> = exportSnapshot(), epoch: Long = translationEpoch): Int =
        translationService.translatePendingBlocking(snapshot.map { it.translation }, epoch)

    fun cueAt(positionMs: Long): Cue? = synchronized(cues) {
        var low = 0; var high = cues.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (cues[mid].startMs <= positionMs) low = mid + 1 else high = mid
        }
        val idx = low - 1
        if (idx < 0) null else cues[idx].takeIf { positionMs >= it.startMs && positionMs < it.endMs }
    }

    fun snapshot(): Snapshot = Snapshot(
        samples,
        ocrRuns,
        if (ocrRuns == 0) 0 else ocrTimeMs / ocrRuns,
        synchronized(cues) { cues.count { !it.portuguese.isNullOrBlank() } },
        processedThroughMs
    )

    fun srt(snapshot: List<Cue> = exportSnapshot()): String = synchronized(cues) {
        SubtitleTimeline.formatSrtEntries(snapshot.mapNotNull { cue ->
            if (cue.portuguese == "" || cue.translation.suppressed || cue.translation.reason?.startsWith("SUPPRESSED:") == true) null
            else {
                val end = if (cue.endMs == Long.MAX_VALUE) max(cue.startMs + 1500, processedThroughMs) else cue.endMs
                val text = NoiseFilter.cleanModelTokens(cue.portuguese ?: cue.chinese)
                if (text.isBlank()) null else Triple(cue.startMs, end, text)
            }
        })
    }

    fun reset() = synchronized(cues) {
        generation.incrementAndGet()
        cues.clear(); active = null
        translationService.newSession()
        worker.execute {
            synchronized(cues) { active = null }
            lastSignature = null
            lastOcrAt = 0L
            lastText = ""
            proposed = ""
            proposedHits = 0
            constantText = ""
            constantSinceMs = 0L
            suppressedConstant = false
            samples = 0
            ocrRuns = 0
            ocrTimeMs = 0L
            processedThroughMs = 0L
        }
    }

    private fun tell(message: String) = main.post { status(message) }
    private fun isChinese(c: Char): Boolean = c in '\u3400'..'\u9fff' || c in '\uf900'..'\ufaff'
    private val WHITESPACE = Regex("\\s+")
    private fun cleanText(s: String): String = s.replace(WHITESPACE, " ").trim()
    private fun formatMs(ms: Long): String = "%02d:%02d.%03d".format(ms / 60000, ms / 1000 % 60, ms % 1000)

    override fun close() {
        generation.incrementAndGet()
        worker.execute { paddle?.close() }
        translationService.close()
        worker.shutdown()
        if (ownsRepository) repository.close()
    }
}
