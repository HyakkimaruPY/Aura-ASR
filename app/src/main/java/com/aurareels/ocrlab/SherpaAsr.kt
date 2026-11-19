package com.aurareels.ocrlab

import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.Closeable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Loads sherpa-onnx native code in a fixed order: its private ONNX Runtime (libsherpaort.so, renamed at
 * build time so it cannot clash with libonnxruntime.so of the OCR AAR) first, then the JNI.
 * Throws one clear message instead of the raw linker text when the APK was built without the runtime.
 */
object SherpaNative {
    @Volatile private var loaded = false
    @Synchronized fun ensure() {
        if (loaded) return
        try {
            System.loadLibrary("sherpaort")
            System.loadLibrary("sherpa-onnx-jni")
            loaded = true
        } catch (e: UnsatisfiedLinkError) {
            throw IllegalStateException(
                "Runtime de voz ausente ou incompatível no APK (${e.message}). " +
                    "Rebuild com scripts/prepare_sherpa_arm64.py + scripts/isolate_sherpa_ort.py.", e)
        }
    }
}

/**
 * Segment-based ASR for fast / tonal / "dense" languages (ja, zh, ko, ar, tr, ...).
 *
 *   16 kHz PCM  ->  Silero VAD  ->  speech segments  ->  SenseVoice (zh/ja/ko/en)  or  Whisper
 *
 * Why it beats continuous streaming recognition on these languages:
 *  - VAD removes most silence before recognition. Event metadata and text checks then reject
 *    likely non-speech; these heuristics cannot guarantee zero false positives on real audio.
 *  - Offline models see a whole utterance with context (better accuracy than a streaming decoder
 *    that must commit word by word). SenseVoice is non-autoregressive: ~10-30x faster than real time.
 *  - Decoding runs on its own thread, so VAD ingestion never stalls while a segment is recognised.
 *
 * Threading: [feed], [flush], [reset] are called from ONE thread (the SpeechBridge worker).
 * Decoding happens on a private single thread; results are delivered through [onCue] from there.
 * All sherpa calls live in this file so API differences between sherpa-onnx releases stay local.
 */
class SegmentEngine(
    repo: ModelRepository,
    private val backend: AsrBackend,
    private val language: String,
    private val strictness: Int,
    private val musicMode: Boolean,
    private val onCue: (startMs: Long, endMs: Long, text: String) -> Unit,
    private val onProgress: (Long) -> Unit,
    private val log: (String) -> Unit
) : Closeable {
    private companion object {
        const val RATE = 16000
        const val MIN_SEGMENT_MS = 350L
        const val MIN_RMS = 0.0015f
        const val MAX_GAP_FILL_MS = 6000L
        const val VAD_FRAME = 512
    }

    private val whisperPack = repo.whisperPack(musicMode)
    private val whisperPrefix = if(whisperPack == "whisper-small") "small" else "base"
    private val profile = LanguageCatalog.profile(language)
    private val threads = (Runtime.getRuntime().availableProcessors()/2).coerceIn(1,2)
    private val vad: Vad
    private val recognizer: OfflineRecognizer
    private val decoder = java.util.concurrent.ThreadPoolExecutor(1,1,0,java.util.concurrent.TimeUnit.MILLISECONDS,
        java.util.concurrent.ArrayBlockingQueue<Runnable>(32), { Thread(it,"aura-asr-decode").apply { priority=Thread.NORM_PRIORITY-1 } },
        java.util.concurrent.RejectedExecutionHandler { task, executor ->
            if(executor.isShutdown) throw java.util.concurrent.RejectedExecutionException()
            executor.queue.put(task) // back-pressure, outside the VAD/progress lock
        })
    private val dispatch = ArrayList<Runnable>()
    private fun dispatchSegments() {
        val tasks=synchronized(this) { dispatch.toList().also { dispatch.clear() } }
        tasks.forEach { decoder.execute(it) }
    }

    // ---- ingest state (bridge worker thread only) ----
    private var started = false
    private var baseMs = 0L
    private var fed = 0L
    private var speaking = false
    private var speechOnsetMs = 0L
    // Old films / far-away voices are quiet: level them before the VAD so they are not mistaken for silence.
    private val agc = SimpleAgc()
    private val vadFrames=VadFrameBuffer()
    private val cutPolicy=SpeechCutPolicy(512*1000.0/RATE)
    private var vadFed=0L
    private var openSpeechSample=0L
    private var vadSegmentEnd=0L
    private val musicSegments = MusicSegmenter { time, pcm -> submit(time, pcm) }

    // ---- decode backlog (shared) ----
    private data class Pending(val start: Long, val generation: Int)
    private val pending = ConcurrentLinkedQueue<Pending>()       // segment start times, FIFO
    private val pendingMs = AtomicLong(0)
    private val generation = AtomicInteger(0)
    @Volatile private var lastProgress = 0L
    @Volatile private var closed = false

    /** Audio (ms) already cut into segments but not yet recognised: used for scout back-pressure. */
    val backlogMs: Long get() = pendingMs.get().coerceAtLeast(0)
    @Volatile var rtfEwma = 0.5; private set
    @Volatile var warm = false; private set
    @Volatile private var hasDecoded = false
    val safeHorizonMs: Long get() = lastProgress

    init {
        SherpaNative.ensure()
        val vadModel = repo.file("vad", "silero_vad.onnx").absolutePath
        vad = Vad(
            VadModelConfig.builder()
                .setSileroVadModelConfig(
                    SileroVadModelConfig.builder()
                        .setModel(vadModel)
                        // Higher threshold + longer minimum speech = fewer false triggers on noise.
                        .setThreshold(when (strictness) { 0 -> 0.4f; 2 -> 0.65f; else -> 0.55f })
                        .setMinSilenceDuration(profile.vadMinSilenceMs / 1000f)
                        .setMinSpeechDuration(if (strictness == 0) 0.2f else 0.4f)
                        .setMaxSpeechDuration(9f)
                        .setWindowSize(512)
                        .build()
                )
                .setSampleRate(RATE)
                .setNumThreads(1)
                .setDebug(false)
                .build()
        )
        var allocated: OfflineRecognizer? = null
        try {
        val modelConfig = when (backend) {
            AsrBackend.SENSEVOICE -> OfflineModelConfig.builder()
                .setSenseVoice(
                    OfflineSenseVoiceModelConfig.builder()
                        .setModel(repo.file("sensevoice", "model.int8.onnx").absolutePath)
                        .setLanguage(if (language in LanguageCatalog.senseVoiceLanguages) language else "auto")
                        .setInverseTextNormalization(true)
                        .build()
                )
                .setTokens(repo.file("sensevoice", "tokens.txt").absolutePath)
                .setNumThreads(threads)
                .setDebug(false)
                .build()
            AsrBackend.WHISPER -> OfflineModelConfig.builder()
                .setWhisper(
                    OfflineWhisperModelConfig.builder()
                        .setEncoder(repo.file(whisperPack, "$whisperPrefix-encoder.int8.onnx").absolutePath)
                        .setDecoder(repo.file(whisperPack, "$whisperPrefix-decoder.int8.onnx").absolutePath)
                        .setLanguage(language)
                        .setTask("transcribe")
                        .setEnableTokenTimestamps(true)
                        .setEnableSegmentTimestamps(true)
                        .build()
                )
                .setTokens(repo.file(whisperPack, "$whisperPrefix-tokens.txt").absolutePath)
                .setNumThreads(threads)
                .setDebug(false)
                .build()
            else -> throw IllegalArgumentException("Motor por segmentos exige SenseVoice ou Whisper")
        }
        recognizer = OfflineRecognizer(
            OfflineRecognizerConfig.builder()
                .setOfflineModelConfig(modelConfig)
                .setDecodingMethod("greedy_search")
                .build()
        )
        allocated = recognizer
        val warmAt = System.nanoTime()
        val stream = recognizer.createStream()
        try { stream.acceptWaveform(FloatArray(RATE), RATE); recognizer.decode(stream) }
        finally { stream.release() }
        warm = true
        log("ASR_WARMUP engine=$backend lang=$language ms=${(System.nanoTime()-warmAt)/1_000_000}")
        } catch(error: Throwable) {
            runCatching { allocated?.release() }; runCatching { vad.release() }; decoder.shutdown()
            throw error
        }
    }

    /** Feeds PCM whose first sample sits at media time [startMs]. Gaps are bridged with silence. */
    fun feed(startMs: Long, samples: FloatArray) {
        synchronized(this) {
        if (closed || samples.isEmpty()) return
        if (!started) { started = true; baseMs = startMs; fed = 0; speaking = false }
        val expected = baseMs + fed * 1000 / RATE
        val gap = startMs - expected
        if (!musicMode && gap > 400) {
            if (gap <= MAX_GAP_FILL_MS) feedSilence(gap)
            else restartClock(startMs)
        } else if (!musicMode && gap < -1500) restartClock(startMs)
        if (musicMode) {
            musicSegments.feed(startMs, samples)
            fed = (startMs - baseMs) * RATE / 1000 + samples.size
            reportProgress()
        } else {
        fed += samples.size
        vadFrames.feed(agc.apply(samples),::feedVadFrame)
        reportProgress()
        }
        }
        dispatchSegments()
    }

    private fun feedVadFrame(frame: FloatArray) {
        val frameStart=vadFed
        vad.acceptWaveform(frame); vadFed+=frame.size
        val nowSpeaking=vad.isSpeechDetected()
        if(nowSpeaking && !speaking) {
            openSpeechSample=(frameStart-RATE).coerceAtLeast(vadSegmentEnd)
            speechOnsetMs=baseMs+openSpeechSample*1000/RATE
        }
        speaking=nowSpeaking
        cutPolicy.observe(NoiseFilter.rms(frame))
        drainVad()
        if(speaking) {
            val spokenMs=(vadFed-openSpeechSample)*1000/RATE
            val cut=cutPolicy.decide(spokenMs)
            if(cut!=null) {
                vad.flush(); drainVad(); speaking=false
                log("ASR_${cut}_CUT at=${baseMs+vadFed*1000/RATE} spokenMs=$spokenMs")
            }
        }
    }
    private fun feedSilence(gapMs: Long) {
        var left=(gapMs*RATE/1000).toInt()
        while(left>0) {
            val n=minOf(left,RATE); fed+=n
            vadFrames.feed(FloatArray(n),::feedVadFrame); left-=n
        }
    }
    private fun restartClock(newBaseMs: Long) {
        vadFrames.flush(::feedVadFrame); vad.flush(); drainVad(); vad.reset(); vadFrames.reset()
        baseMs=newBaseMs; fed=0; vadFed=0; speaking=false; openSpeechSample=0; vadSegmentEnd=0; cutPolicy.reset()
    }

    private fun drainVad() {
        while (!vad.empty()) {
            val segment = vad.front()
            // EOF padding is only for VAD inference. It must never extend the media clock.
            val samples=segment.samples.takeIf { it.size.toLong()<=fed-segment.start.toLong() }
                ?: segment.samples.copyOf((fed-segment.start.toLong()).coerceIn(0,segment.samples.size.toLong()).toInt())
            val startMs = baseMs + segment.start.toLong() * 1000 / RATE
            vad.pop()
            vadSegmentEnd=maxOf(vadSegmentEnd,segment.start.toLong()+samples.size)
            submit(startMs, samples)
            // Silero may force-cut a monologue while isSpeechDetected remains true.
            if (speaking) {
                openSpeechSample=maxOf(openSpeechSample,segment.start.toLong()+samples.size)
                speechOnsetMs=baseMs+openSpeechSample*1000/RATE
            }
        }
    }

    private fun submit(startMs: Long, samples: FloatArray) {
        val durationMs = samples.size.toLong() * 1000 / RATE
        if (durationMs < MIN_SEGMENT_MS || NoiseFilter.rms(samples) < MIN_RMS) return   // blip / near silence
        val gen = generation.get()
        pendingMs.addAndGet(durationMs)
        val item = Pending(startMs, gen)
        pending.add(item)
        dispatch.add(Runnable {
            try {
                if (!closed && gen == generation.get()) decode(gen, startMs, durationMs, samples)
            } catch (e: Throwable) {
                log("ASR por segmentos: ${e.message}")
            } finally {
                synchronized(this) {
                    pending.remove(item)
                    if (gen == generation.get()) { pendingMs.addAndGet(-durationMs); reportProgress() }
                }
            }
        })
    }

    private fun decode(gen: Int, startMs: Long, durationMs: Long, samples: FloatArray) {
        val decodeAt = System.nanoTime()
        val stream = recognizer.createStream()
        val raw: String
        val event: String?
        val tokens: Array<String>
        val timestamps: FloatArray
        val durations: FloatArray
        try {
            stream.acceptWaveform(samples, RATE)
            recognizer.decode(stream)
            val result = recognizer.getResult(stream)
            raw = result.text.orEmpty()
            event = result.event
            tokens=result.tokens ?: emptyArray(); timestamps=result.timestamps ?: floatArrayOf(); durations=result.durations ?: floatArrayOf()
        } finally { stream.release() }
        val elapsed = (System.nanoTime()-decodeAt)/1_000_000
        synchronized(this) {
            if (closed || gen != generation.get()) return
            val rtf = elapsed.toDouble()/maxOf(1,durationMs)
            rtfEwma = if (!hasDecoded) rtf else 0.25*rtf + 0.75*rtfEwma
            log("ASR_DECODE engine=$backend lang=$language duration=$durationMs decodeMs=$elapsed rtf=$rtf ewma=$rtfEwma first=${!hasDecoded}")
            hasDecoded = true
            var text = NoiseFilter.clean(raw)
            if (profile.joinWithoutSpaces) text = text.replace(" ", "")
            val kind = NoiseClassifier.classify(raw,language,durationMs,strictness,event,lyrics=musicMode)
            if (kind == CueClass.LAUGH) { onCue(startMs,startMs+durationMs,"<|Laughter|>"); return }
            if (kind !in setOf(CueClass.SPEECH, CueClass.INTERJECTION)) {
                log("ASR_FILTER kind=$kind lang=$language event=${event.orEmpty()} duration=$durationMs"); return
            }
            val cues=TokenCueTiming.split(text,startMs,startMs+durationMs,tokens,timestamps,durations,profile.joinWithoutSpaces,musicMode)
                ?: NoiseFilter.split(text,startMs,startMs+durationMs)
            for ((from, to, piece) in cues) onCue(from, to, piece)
        }
    }

    /** Smallest media time up to which every cue is final (nothing earlier can still appear). */
    @Synchronized private fun reportProgress() {
        // Retain VAD lookbehind (min speech + frame/pre-padding), even before speech becomes detected.
        var p = (baseMs + fed * 1000 / RATE - 1000).coerceAtLeast(baseMs)
        if (musicMode) musicSegments.pendingStartMs?.let { p = minOf(p, it) }
        if (speaking) p = minOf(p, speechOnsetMs)
        pending.peek()?.let { p = minOf(p, it.start) }
        if (p > lastProgress) { lastProgress = p; onProgress(p) }
    }

    /** End of stream: closes the open utterance; progress jumps to "everything done" once decoded. */
    fun flush() {
        val gen=synchronized(this) {
            if(closed) return
            if (musicMode) musicSegments.flush() else { vadFrames.flush(::feedVadFrame); vad.flush(); drainVad() }; generation.get()
        }
        dispatchSegments()
        decoder.execute { synchronized(this) { if(!closed && gen==generation.get()) {
            lastProgress=Long.MAX_VALUE/4; onProgress(lastProgress)
        } } }
    }

    /** New position (seek): drops buffered audio and in-flight work, keeps the loaded models. */
    @Synchronized fun reset() {
        generation.incrementAndGet()
        vad.reset(); musicSegments.reset(); vadFrames.reset(); vadFed=0; openSpeechSample=0; vadSegmentEnd=0; cutPolicy.reset()
        dispatch.clear(); decoder.queue.clear(); pending.clear(); pendingMs.set(0)
        started = false; speaking = false; fed = 0; lastProgress = 0L; agc.reset()
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true; generation.incrementAndGet(); decoder.queue.clear(); dispatch.clear()
        // Native handles are freed by their owner only AFTER the last decoder job, with no timeout race.
        decoder.execute {
            try { recognizer.release() } catch (_: Throwable) {}
            try { vad.release() } catch (_: Throwable) {}
        }
        decoder.shutdown()
    }

}

/**
 * Slow automatic gain: lifts quiet speech (max x8) without pumping on every syllable. Digital silence and
 * very low noise floors are left alone so hiss is not amplified into "speech".
 */
class SimpleAgc {
    private var gain = 1f
    fun reset() { gain = 1f }
    fun apply(samples: FloatArray): FloatArray {
        val rms = NoiseFilter.rms(samples)
        val wanted = if (rms < 0.0008f) 1f else (0.06f / rms).coerceIn(1f, 8f)
        gain += (wanted - gain) * (if (wanted < gain) 0.6f else 0.12f)
        if (gain < 1.02f) return samples
        val out = FloatArray(samples.size)
        for (i in samples.indices) out[i] = (samples[i] * gain).coerceIn(-1f, 1f)
        return out
    }
}

/**
 * Speech-only gate for language identification: silence, logos and music never reach the identifier,
 * so the language is decided from windows that really contain talking (even when it starts minutes in).
 */
class SpeechGate(repo: ModelRepository) : Closeable {
    private val nativeReady = SherpaNative.ensure()
    private val vad: Vad = Vad(
        VadModelConfig.builder()
            .setSileroVadModelConfig(
                SileroVadModelConfig.builder()
                    .setModel(repo.file("vad", "silero_vad.onnx").absolutePath)
                    .setThreshold(0.38f)
                    .setMinSilenceDuration(0.3f)
                    .setMinSpeechDuration(0.25f)
                    .setMaxSpeechDuration(4.0f)
                    .setWindowSize(512)
                    .build()
            )
            .setSampleRate(16000)
            .setNumThreads(1)
            .setDebug(false)
            .build()
    )
    private val agc = SimpleAgc()
    private val frames=VadFrameBuffer()

    /** Feeds 16 kHz PCM; returns the speech segments completed so far (possibly empty). */
    fun speechOnly(samples: FloatArray): FloatArray {
        val parts = ArrayList<FloatArray>()
        var total = 0
        frames.feed(agc.apply(samples)) { frame ->
            vad.acceptWaveform(frame)
            while (!vad.empty()) {
                val segment = vad.front().samples
                vad.pop()
                parts.add(segment); total += segment.size
            }
        }
        val out = FloatArray(total)
        var at = 0
        for (part in parts) { System.arraycopy(part, 0, out, at, part.size); at += part.size }
        return out
    }
    fun reset() { vad.reset(); frames.reset(); agc.reset() }
    override fun close() { try { vad.release() } catch (_: Throwable) {} }
}
