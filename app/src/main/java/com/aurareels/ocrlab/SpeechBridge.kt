package com.aurareels.ocrlab

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

/**
 * Central bridge: HLS PCM (16 kHz, from PcmTap) -> independent audio LID -> installed-language gate
 * -> recognition engine -> media-time cues -> translation chain.
 *
 * Engines (see ModelRepository.asrBackendFor): VAD + SenseVoice / Whisper by speech segment
 * ([SegmentEngine], preferred: fast, noise-proof, handles ja/zh/ko/ar/tr) or Vosk streaming (fallback).
 *
 * Back-pressure instead of dropping: [backlogMs] tells the UI how much audio is waiting; the UI pauses
 * the (muted, audio-only) scout when it grows and delays the visible player until subtitles are ready.
 * Always fails closed when audio is unknown or a model has not been downloaded.
 *
 * Threading: every field marked "worker" is touched ONLY by the single worker thread.
 * The main thread only flips volatile flags / bumps [epoch]; state is reset lazily on the worker
 * when the first packet of a new epoch arrives ([ensureState]).
 *
 * Timing: each packet carries its media start time (from PcmTap). The recognizer clock is kept
 * aligned with it: lost/dropped audio is replaced by silence, so cue timestamps cannot drift.
 */
class SpeechBridge(
    private val repo: ModelRepository,
    private val timeline: SubtitleTimeline,
    private val translation: GoogleTranslateBridge,
    private val notify: (String) -> Unit,
    private val verdict: (String?, String) -> Unit
) : Closeable {
    private data class Packet(val epoch: Int, val scout: Boolean, val samples: FloatArray,
                              val sampleRate: Int, val startMs: Long)
    private class Chunk(val startMs: Long, val samples: FloatArray)

    private companion object {
        const val PREFLIGHT_MAX = 16_000 * 90      // rolling buffer: last 90 s of decoded PCM, replayed to the engine once the language is known
        const val HELD_MAX = 16_000 * 90
        const val MAX_GAP_FILL_MS = 30_000L
        const val LID_WINDOW = 96_000              // six seconds of PCM at 16 kHz (no VAD installed)
        const val LID_SPEECH_WINDOW = 80_000       // five seconds of pure speech when the VAD gates the audio
        const val MIN_ENERGY = 0.009f
        val NO_SPACE_LANGS = setOf("zh", "ja", "yue")
    }

    private val queue = ArrayBlockingQueue<Packet>(480)      // ~2 min of 250 ms blocks
    private val queuedSamples = AtomicLong(0)
    private val epoch = AtomicInteger(0)
    val sessionEpoch: Int get() = epoch.get()
    @Volatile private var initializing = false
    private val sessionLock = Any()
    @Volatile var laughterMode = 0
    @Volatile var bypass = false; private set
    @Volatile var phaseCode = "LID"; private set
    @Volatile private var confirmedAt = 0L
    @Volatile private var firstCue = false
    private var probeAt = 0L
    val rtfEwma: Double get() = engine?.rtfEwma ?: 0.5
    private val draining = AtomicBoolean(false)
    @Volatile private var finishRequestedEpoch = -1
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "aura-asr-bridge").apply { priority=Thread.NORM_PRIORITY-1 } }
    private val main = Handler(Looper.getMainLooper())

    /** Called (main thread) when detection cannot decide: uncertain votes or language without installed pack. */
    @Volatile var onUndecided: ((String) -> Unit)? = null
    @Volatile var uncertainRounds = 0; private set
    /** Weak language prior for songs (from title/artist script): seeds the votes and answers when detection stays unsure. */
    @Volatile var languageHint: String? = null

    @Volatile private var scanWanted = false      // language search should resume after a seek
    @Volatile private var scouting = false
    @Volatile private var active = false
    @Volatile private var priming = false           // probe finished, Vosk loading: keep PCM
    @Volatile var language: String? = null; private set
    @Volatile var phase = "Inativo"; private set
    @Volatile var packetsDropped = 0; private set
    @Volatile var processedThroughMs = 0L; private set
    val probing: Boolean get() = scouting
    /** True once the recognition engine is loaded and consuming audio. */
    val isActive: Boolean get() = active
    /** User setting 0..2 (permissive / normal / strict). Read when an engine is created and per hypothesis. */
    @Volatile var strictness = 1
    @Volatile var musicMode = false
        set(value) { field=value; translation.musicMode=value }
    /** Audio (ms) delivered by the scout but not yet turned into cues: queue + segments waiting to be decoded. */
    val backlogMs: Long get() = queuedSamples.get() * 1000 / 16_000 + (engine?.backlogMs ?: 0L)
    @Volatile var engineName = "—"; private set

    // ---- worker-only state ----
    private var stateEpoch = -1
    private var identifier: AudioLanguageIdentifier? = null
    private var gate: SpeechGate? = null
    private var gateTried = false
    @Volatile private var engine: SegmentEngine? = null
    private var engineKey = ""
    @Volatile private var engineEpoch = -1
    @Volatile private var replayUntilMs = 0L
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var collected = FloatArray(0)
    private var attempts = 0
    // Languages we can transcribe; anything else needs stronger acoustic evidence before it is believed.
    private val votes = LanguageVoteTracker(supported = { code ->
        code in LabModels.senseVoiceLanguages || code in LabModels.whisperLanguages ||
            LabModels.all.any { it.type == LabModel.Type.VOSK && it.language == code }
    })
    private val scoutResampler = StreamingPcm16k()
    private val preflight = ArrayList<Chunk>()
    private var preflightSamples = 0
    private val held = ArrayList<Chunk>()
    private var heldSamples = 0
    private var totalFrames = 0L
    private var timeBaseMs = 0L

    private fun engineIdentity(backend: AsrBackend, code: String) = "$backend:$code:${if(backend==AsrBackend.WHISPER) repo.whisperPack(musicMode) else "sensevoice"}:$strictness:$musicMode"
    private class EngineBinding(@Volatile var token: Int)
    private var engineBinding: EngineBinding?=null
    private fun createSegmentEngine(backend: AsrBackend, code: String): SegmentEngine {
        val binding=EngineBinding(epoch.get()); engineBinding=binding
        return SegmentEngine(repo,backend,code,strictness,musicMode,
            { from,to,text -> synchronized(sessionLock) { if(binding.token==epoch.get()) emitCue(from,to,text) } },
            { value -> synchronized(sessionLock) { if(binding.token==epoch.get()) { processedThroughMs=value; if(musicMode && value>=Long.MAX_VALUE/8) translation.finishMusicInput(timeline.all()); if(phaseCode=="REPLAY" && value>=replayUntilMs) phaseCode="STEADY" } } },
            { notifyOnUi(it,binding.token) })
    }

    @Volatile var preserveTimeline=false
    @Volatile var restoredCoverage: List<AudioInterval> = emptyList()
    private val coverageLock=Any()
    private var delivered=emptyList<AudioInterval>()
    private var completed=emptyList<AudioInterval>()
    fun coverageSnapshot(): List<AudioInterval> = synchronized(coverageLock) {
        val through=processedThroughMs
        AudioCoverage.merge(completed+delivered.mapNotNull { r -> minOf(r.endMs,through).takeIf { it>r.startMs }?.let { AudioInterval(r.startMs,it) } })
    }
    private fun sealCoverage() { synchronized(coverageLock) { completed=coverageSnapshot(); delivered=emptyList() } }
    fun startProbe() {
        synchronized(coverageLock) { completed=emptyList(); delivered=emptyList() }
        restoredCoverage=emptyList(); preserveTimeline=false
        val token=synchronized(sessionLock) {
            initializing=true; queue.clear(); queuedSamples.set(0); bypass=false
            epoch.incrementAndGet()
        }
        translation.newSession(); phaseCode = "LID"; probeAt = System.currentTimeMillis(); firstCue = false
        processedThroughMs = 0L; packetsDropped = 0
        phase = "Analisando áudio · prévia silenciosa"; language = null
        priming = false; active = false; scouting = true; scanWanted = true
        uncertainRounds = 0
        worker.execute {
            if(token!=epoch.get()) return@execute
            identifier?.close(); identifier = null; closeGate(); closeRecognizer(); releaseEngine()
            preflight.clear(); preflightSamples = 0; held.clear(); heldSamples = 0
            collected = floatArrayOf(); votes.reset(); attempts = 0; lidFailures = 0
            scoutResampler.reset(); stateEpoch=token; finishRequestedEpoch=-1; engineEpoch=-1
            lastErrorText=""; lastErrorAtMs=0; engineName="—"
            synchronized(sessionLock) { if(token==epoch.get()) initializing=false }
            scheduleDrain()
        }
        notify(phase)
    }

    fun scoutPcm(token: Int, samples: FloatArray, rate: Int, startMs: Long) = offer(token,true,samples,rate,startMs)

    /** Local decoding waits for capacity instead of dropping PCM or throttling to playback time. */
    fun localPcm(token: Int, samples: FloatArray, rate: Int, startMs: Long, current: () -> Boolean): Boolean {
        while(current() && token==epoch.get() && !Thread.currentThread().isInterrupted) {
            if (bypass) return false
            if ((active || scouting) && backlogMs < 8000) {
                synchronized(sessionLock) {
                    if (token!=epoch.get() || !current()) return false
                    if(queue.offer(Packet(token,true,samples,rate,startMs))) {
                        queuedSamples.addAndGet(samples.size.toLong()*16000/rate)
                        scheduleDrain(); return true
                    }
                }
            }
            Thread.sleep(20)
        }
        return false
    }

    private fun offer(token: Int, scout: Boolean, samples: FloatArray, rate: Int, startMs: Long) {
        synchronized(sessionLock) {
            if (token!=epoch.get() || (!scouting && !active && !priming)) return
            val packet = Packet(epoch.get(),scout,samples,rate,startMs)
            if(!queue.offer(packet)) {
                queue.poll()?.let { queuedSamples.addAndGet(-it.samples.size.toLong()*16000/it.sampleRate) }
                packetsDropped++
                notifyOnUi("ASR sobrecarregado: bloco descartado (total $packetsDropped)")
                if(!queue.offer(packet)) return
            }
            queuedSamples.addAndGet(samples.size.toLong()*16000/rate)
        }
        scheduleDrain()
    }

    // The same failure (e.g. missing native runtime) used to flood the debug log on every audio packet.
    private var lastErrorText = ""
    private var lastErrorAtMs = 0L
    private fun notifyErrorOnce(text: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (text == lastErrorText && now - lastErrorAtMs < 30_000L) return
        lastErrorText = text; lastErrorAtMs = now
        notifyOnUi(text)
    }

    private fun scheduleDrain() {
        if(initializing || worker.isShutdown) return
        if (draining.compareAndSet(false, true)) worker.execute {
            try {
                repeat(24) {
                    if(initializing) return@repeat
                    val packet = synchronized(sessionLock) {
                        queue.poll()?.also { p -> queuedSamples.addAndGet(-p.samples.size.toLong()*16000/p.sampleRate) }
                    } ?: return@repeat
                    if (packet.epoch != epoch.get()) return@repeat
                    try {
                        ensureState(packet.epoch)
                        val converted = scoutResampler.convert(packet.samples, packet.sampleRate)
                        if (converted.isEmpty()) return@repeat
                        val chunk = Chunk(packet.startMs, converted)
                        when {
                            scouting -> analyze(chunk, packet.epoch)
                            active -> transcribe(chunk,packet.epoch)
                            priming -> if (heldSamples < HELD_MAX) { held.add(chunk); heldSamples += converted.size }
                        }
                    } catch (error: Throwable) { notifyErrorOnce("ASR: ${error.message}") }
                }
            } finally {
                draining.set(false)
                if (queue.isNotEmpty()) scheduleDrain() else maybeFinishOnWorker()
            }
        }
    }

    /** Resets per-epoch buffers on the worker thread (recognizer/time base are managed by begin()). */
    private fun ensureState(current: Int) {
        if (stateEpoch == current) return
        stateEpoch = current
        collected = floatArrayOf(); attempts = 0; votes.reset()
        scoutResampler.reset(); gate?.reset()
        if (scouting) { preflight.clear(); preflightSamples = 0; held.clear(); heldSamples = 0 }
    }

    private fun analyze(chunk: Chunk, packetEpoch: Int) {
        val samples = chunk.samples
        // Rolling buffer: speech may start minutes in, so keep the most recent PCM, not the oldest.
        preflight.add(chunk); preflightSamples += samples.size
        while (preflightSamples > PREFLIGHT_MAX && preflight.size > 1) preflightSamples -= preflight.removeAt(0).samples.size
        // Language is decided from SPEECH only: the Silero VAD (when installed) drops silence, studio logos
        // and music, so the identifier no longer burns minutes on a silent intro.
        val g = if (musicMode) null else speechGate()
        val pcm = if (g != null) g.speechOnly(samples) else samples
        val need = if (g != null) LID_SPEECH_WINDOW else LID_WINDOW
        var offset = 0
        while (offset < pcm.size && scouting) {
            val take = minOf(need - collected.size, pcm.size - offset)
            collected += pcm.copyOfRange(offset, offset + take)
            offset += take
            if (collected.size >= need) classifyWindow(g != null, packetEpoch)
        }
    }

    private fun speechGate(): SpeechGate? {
        gate?.let { return it }
        if (gateTried || !repo.installed("vad")) return null
        gateTried = true
        return try { SpeechGate(repo).also { gate = it } } catch (e: Throwable) {
            notifyOnUi("VAD do detector indisponível (${e.message}); usando energia do áudio"); null
        }
    }
    private fun closeGate() { gate?.close(); gate = null; gateTried = false }

    private fun classifyWindow(speechOnly: Boolean, packetEpoch: Int) {
        val window = collected
        collected = floatArrayOf()
        var squared = 0.0
        for (sample in window) squared += sample.toDouble() * sample
        val energy = sqrt(squared / window.size).toFloat()
        attempts++
        // Silence never ends the search: speech may begin minutes into a film.
        if (!speechOnly && energy < MIN_ENERGY) return
        try {
            val id = identifier ?: AudioLanguageIdentifier(repo).also { identifier = it }
            val detected = id.identify(window)
            if (packetEpoch != epoch.get() || !scouting) return
            if (musicMode && votes.total == 0) languageHint?.let { votes.add(it) }
            val confirmed = votes.add(detected)       // aliases (yue/zh-TW...) are folded inside
            val shown = detected.ifEmpty { "?" }
            notifyOnUi("LID acústico: $shown (bruto: '${id.lastRaw.take(24)}') · votos ${votes.count(shown)}/2 · ${votes.total} amostras · ${chunkSeconds(window)}s")
            when {
                confirmed == "pt" && !musicMode -> {
                    bypass = true; scouting = false; scanWanted = false; phaseCode = "STEADY"
                    phase = "O áudio já está em português; legenda desnecessária"
                    notifyOnUi(phase)
                }
                confirmed != null -> {
                    val selected = LanguageMap.pickInstalled(confirmed, repo.asrLanguages())
                    if (selected == null) {
                        scouting = false; scanWanted = false      // stop scanning; the player keeps playing without subtitles
                        val msg = "Áudio identificado como ${LanguageMap.displayName(confirmed)}, mas nenhum modelo de reconhecimento desse idioma está instalado. Baixe SenseVoice/Whisper (e o VAD) em Modelos ou escolha outro idioma."
                        phase = msg; notifyOnUi(msg); main.post { if (epoch.get() == packetEpoch) onUndecided?.invoke(msg) }
                    } else finishProbe(selected, "Idioma ${LanguageMap.displayName(selected)} validado; modelo instalado")
                }
                votes.exhausted() -> {
                    uncertainRounds++
                    votes.reset()                 // keep scanning with fresh windows
                    val hinted = if (musicMode) languageHint?.let { LanguageMap.pickInstalled(it, repo.asrLanguages()) } else null
                    if (hinted != null) {
                        finishProbe(hinted, "Idioma ${LanguageMap.displayName(hinted)} deduzido do título/artista")
                    } else {
                        val msg = "Não consegui identificar o idioma com segurança. Escolha o idioma manualmente."
                        phase = "Idioma incerto · continuo analisando (rodada $uncertainRounds)"
                        notifyOnUi(phase)
                        // The chooser is the last resort: only after several rounds of real speech stayed undecided
                        // (songs need more: instrumental passages produce noisy votes).
                        if (uncertainRounds == (if (musicMode) 6 else 3)) main.post { if (epoch.get() == packetEpoch) onUndecided?.invoke(msg) }
                    }
                }
            }
        } catch (e: Throwable) {       // UnsatisfiedLinkError/OOM are Errors: Exception would let them escape and freeze the player
            notifyErrorOnce("LID falhou: ${e.message}")
            // A missing/incompatible native runtime cannot recover by retrying: give up at once.
            if (e is IllegalStateException && e.message?.startsWith("Runtime de voz") == true) lidFailures = 2
            if (++lidFailures >= 3) {
                scouting = false; scanWanted = false
                val msg = "Detector de idioma indisponível (${e.message}). Escolha o idioma manualmente."
                phase = msg; main.post { if (epoch.get() == packetEpoch) onUndecided?.invoke(msg) }
            }
        }
    }
    private var lidFailures = 0
    private fun chunkSeconds(w: FloatArray) = w.size / 16_000

    /**
     * Manual language choice (user picked it, or answered the "uncertain" dialog).
     * Works while scanning, while idle in "undecided", or while another language is active.
     */
    fun forceLanguage(code: String, targetCode: String, startMediaMs: Long = 0L, onReady: (Boolean) -> Unit = {}) {
        val installedId = LanguageMap.pickInstalled(code, repo.asrLanguages())
        if (installedId == null) {
            phase = "Modelo de ${LanguageMap.displayName(code)} não instalado"
            notifyOnUi(phase); main.post { onReady(false) }; return
        }
        synchronized(sessionLock) {
            epoch.incrementAndGet(); initializing=false; queue.clear(); queuedSamples.set(0); if(!preserveTimeline) timeline.reset()
            active = false; scouting = false; bypass = false; processedThroughMs = startMediaMs
        }
        translation.newSession(false); scanWanted = false
        language = installedId
        priming = true
        confirmedAt = System.currentTimeMillis(); firstCue = false
        phase = "Idioma ${LanguageMap.displayName(installedId)} escolhido manualmente"
        notifyOnUi(phase)
        begin(installedId, targetCode, startMediaMs, replayPreflight = false, onReady = onReady)
    }

    private fun finishProbe(code: String?, result: String) {
        scouting = false; scanWanted = false
        closeGate()
        priming = code != null     // keep decoding PCM while the UI loads Vosk
        language = code
        phase = result
        notifyOnUi(result)
        val token = epoch.get()
        confirmedAt = System.currentTimeMillis(); firstCue = false
        notifyOnUi("ASR_LID confirmedMs=${confirmedAt-probeAt} lang=$code")
        main.post { if (epoch.get() == token) verdict(code, result) }
    }

    /**
     * Loads Vosk and (optionally) replays the preflight PCM. [startMediaMs] is the media time of the
     * first PCM the new scout will deliver (0 at first start, the seek target after a seek).
     */
    fun begin(languageCode: String, targetCode: String, startMediaMs: Long = 0L,
              replayPreflight: Boolean = true, onReady: (Boolean) -> Unit = {}) {
        val backend = repo.asrBackendFor(languageCode,music=musicMode)
        if (language != languageCode || backend == null) {
            // Never throw on the caller (main) thread: report the failure through the callback.
            phase = "Idioma não validado ou modelo ausente: $languageCode"
            notifyOnUi(phase); main.post { onReady(false) }; return
        }
        val expectedEpoch = epoch.get()
        priming = true; phaseCode = "ENGINE_LOAD"
        val loadAt = System.currentTimeMillis()
        worker.execute {
            if (epoch.get() != expectedEpoch) return@execute
            try {
                if (backend == AsrBackend.VOSK) {
                    releaseEngine(); closeRecognizer()
                    model = Model(repo.directory(languageCode).absolutePath)
                    recognizer = Recognizer(model, 16000.0f).apply { setWords(true) }
                    engineName = "Vosk (${name(languageCode)})"
                } else {
                    closeRecognizer()
                    val key = engineIdentity(backend,languageCode)
                    val current = engine
                    if (current != null && engineKey == key) { current.reset(); engineEpoch = expectedEpoch; engineBinding?.token=expectedEpoch }
                    else {
                        releaseEngine()
                        notifyOnUi("Carregando ${backend.name.lowercase()} · ${name(languageCode)}…")
                        engineEpoch = expectedEpoch
                        engine=createSegmentEngine(backend,LanguageMap.canonical(languageCode))
                        engineKey = key
                    }
                    engineName = (if (backend == AsrBackend.SENSEVOICE) "SenseVoice + VAD" else "${repo.whisperPack(musicMode)} + VAD") + (if(musicMode) " · canto" else "") + " (${name(languageCode)})"
                }
                if (epoch.get() != expectedEpoch) return@execute
                notifyOnUi("ASR_LOAD engine=$engineName ms=${System.currentTimeMillis()-loadAt}")
                totalFrames = 0; timeBaseMs = startMediaMs
                translation.configure(languageCode, targetCode)
                if (replayPreflight) {
                    phaseCode = "REPLAY"
                    // Recent audio (up to 90 s) is decoded once and replayed with its real timestamps.
                    val replay = ArrayList(preflight); preflight.clear(); preflightSamples = 0
                    replayUntilMs = replay.lastOrNull()?.let { it.startMs+it.samples.size*1000L/16000 } ?: startMediaMs
                    replay.firstOrNull()?.let { timeBaseMs = it.startMs }
                    processedThroughMs = timeBaseMs
                    for (chunk in replay) { if (epoch.get() != expectedEpoch) return@execute; transcribe(chunk,expectedEpoch) }
                    val pending = ArrayList(held); held.clear(); heldSamples = 0
                    for (chunk in pending) { if (epoch.get() != expectedEpoch) return@execute; transcribe(chunk,expectedEpoch) }
                } else { preflight.clear(); preflightSamples = 0; held.clear(); heldSamples = 0; processedThroughMs = startMediaMs }
                if (epoch.get() != expectedEpoch) return@execute
                active = true; priming = false; phaseCode = if(replayPreflight && processedThroughMs<replayUntilMs) "REPLAY" else "STEADY"
                phase = "Reconhecendo: ${name(languageCode)} · $engineName"
                notifyOnUi(phase)
                maybeFinishOnWorker()
                main.post { if (epoch.get() == expectedEpoch) onReady(true) }
            } catch (e: Throwable) {
                if(epoch.get()!=expectedEpoch) return@execute
                active = false; priming = false
                phase = "Falha ao iniciar o reconhecimento: ${e.message}"
                notifyOnUi(phase)
                main.post { if (epoch.get() == expectedEpoch) onReady(false) }
            }
        }
    }

    /** Drops the part of a chunk that sits before media time 0 (live PTS can precede the clock origin). */
    private fun clampToZero(chunk: Chunk): Chunk? {
        if(chunk.startMs>=0) return chunk
        val skip=(-chunk.startMs*16).coerceAtMost(chunk.samples.size.toLong()).toInt()
        return if(skip>=chunk.samples.size) null else Chunk(0L,chunk.samples.copyOfRange(skip,chunk.samples.size))
    }
    private fun transcribe(original: Chunk, token: Int = epoch.get()) {
        if(token!=epoch.get()) return
        val chunk=clampToZero(original) ?: return
        val end=chunk.startMs+chunk.samples.size*1000L/16000
        val gaps=AudioCoverage.uncovered(chunk.startMs,end,restoredCoverage)
        for(range in gaps) {
            val from=((range.startMs-chunk.startMs)*16).toInt().coerceIn(0,chunk.samples.size)
            val to=if(range.endMs==end) chunk.samples.size else ((range.endMs-chunk.startMs)*16).toInt().coerceIn(from,chunk.samples.size)
            if(to>from) transcribeUncovered(if(from==0 && to==chunk.samples.size) chunk else Chunk(range.startMs,chunk.samples.copyOfRange(from,to)),token)
        }
    }
    private fun transcribeUncovered(chunk: Chunk,token: Int) {
        if(token!=epoch.get()) return
        val end=chunk.startMs+chunk.samples.size*1000L/16000
        synchronized(coverageLock) { delivered=AudioCoverage.merge(delivered+AudioInterval(chunk.startMs,maxOf(chunk.startMs+1,end))) }
        engine?.let { it.feed(chunk.startMs, chunk.samples); return }
        val rec = recognizer ?: return
        val pcm = chunk.samples
        if (pcm.isEmpty()) return
        // Keep the recognizer clock equal to the media clock.
        val expected = timeBaseMs + totalFrames * 1000 / 16000
        val gap = chunk.startMs - expected
        if (gap > 300) {
            if (gap <= MAX_GAP_FILL_MS) feedSilence(rec, gap, token)
            else resync(rec, chunk.startMs, token)
        } else if (gap < -1500) resync(rec, chunk.startMs, token) // clock went backwards (stream discontinuity)
        val shorts = ShortArray(pcm.size) { i -> (pcm[i].coerceIn(-1f, 1f) * 32767).toInt().toShort() }
        val completed = rec.acceptWaveForm(shorts, shorts.size)
        totalFrames += pcm.size
        val fedThrough = timeBaseMs + totalFrames * 1000 / 16000
        if (completed) { acceptJson(rec.result,token); synchronized(sessionLock) { if(token==epoch.get()) processedThroughMs = fedThrough } }
        else if (JSONObject(rec.partialResult).optString("partial").isBlank()) synchronized(sessionLock) { if(token==epoch.get()) processedThroughMs = (fedThrough - 1000).coerceAtLeast(timeBaseMs) }
        else if (fedThrough - processedThroughMs > 12_000) {
            acceptJson(rec.finalResult,token); rec.reset(); timeBaseMs = fedThrough; totalFrames = 0
            synchronized(sessionLock) { if(token==epoch.get()) processedThroughMs = fedThrough }
        }
    }

    private fun feedSilence(rec: Recognizer, gapMs: Long, token: Int) {
        var left = (gapMs * 16).toInt()
        val block = ShortArray(16_000)
        while (left > 0) {
            val n = minOf(left, block.size)
            val done = rec.acceptWaveForm(block, n)
            totalFrames += n; left -= n
            if (done) acceptJson(rec.result,token)
        }
        notifyOnUi("ASR: ${gapMs}ms de áudio perdido preenchidos com silêncio (tempo preservado)")
    }

    private fun resync(rec: Recognizer, newBaseMs: Long, token: Int) {
        acceptJson(rec.finalResult,token)
        rec.reset(); timeBaseMs = newBaseMs; totalFrames = 0
    }

    private fun acceptJson(value: String, token: Int = epoch.get()) {
        if(token!=epoch.get()) return
        val json = JSONObject(value)
        val words = json.optJSONArray("result") ?: return
        if (words.length() == 0) return
        var confidence = 0.0
        for (i in 0 until words.length()) confidence += words.optJSONObject(i)?.optDouble("conf", 1.0) ?: 1.0
        confidence /= words.length()
        val needed = NoiseFilter.minVoskConfidence(words.length(), strictness)
        if (confidence < needed) { notifyOnUi("ASR descartou hipótese fraca (conf %.2f < %.2f)".format(confidence, needed)); return }
        val first = words.getJSONObject(0)
        val last = words.getJSONObject(words.length() - 1)
        var text = NoiseFilter.clean(json.optString("text"))
        // Vosk zh/ja models separate every token with a space; written CJK has none.
        if (language?.let { it in NO_SPACE_LANGS } == true) text = text.replace(" ", "")
        val from = timeBaseMs + (first.optDouble("start") * 1000).toLong()
        val to = timeBaseMs + (last.optDouble("end") * 1000).toLong()
        val kind = NoiseClassifier.classify(text, LanguageMap.canonical(language ?: ""), to-from, strictness,event="Speech",lyrics=musicMode)
        if (kind !in setOf(CueClass.SPEECH, CueClass.INTERJECTION, CueClass.LAUGH)) return
        synchronized(sessionLock) { if(token==epoch.get()) emitCue(from, to, text) }
    }

    private fun emitCue(from: Long, to: Long, text: String) {
        val lang = language ?: return
        val kind = NoiseClassifier.classify(text,lang,to-from,strictness,event="Speech",lyrics=musicMode)
        if (kind == CueClass.LAUGH) {
            if (laughterMode == 0) { notifyOnUi("COVERAGE cue=$from-$to state=SUPPRESSED reason=LAUGH"); return }
            timeline.add(from,to,NoiseFilter.clean(text).ifBlank { "(risos)" })?.apply {
                output=if (laughterMode == 1) "(risos)" else NoiseFilter.clean(text).ifBlank { "(risos)" }
                reason="SUPPRESSED:LAUGH"; provider="event"
            }
            return
        }
        if (kind !in setOf(CueClass.SPEECH, CueClass.INTERJECTION)) {
            notifyOnUi("ASR_FILTER kind=$kind lang=$lang"); return
        }
        if (!firstCue) { firstCue = true; notifyOnUi("ASR_FIRST_CUE confirmedToCueMs=${System.currentTimeMillis()-confirmedAt}") }
        if(restoredCoverage.any { from>=it.startMs && to<=it.endMs }) return
        val cue = timeline.add(from, to, text, mergeRepetitions=!musicMode, maxDurationMs=if(musicMode) 12_500 else 9500) ?: return
        cue.acousticSpeech=true
        if(musicMode) translation.registerSourceCues(timeline.all())
        translation.translate(cue)
        notifyOnUi("${name(language ?: "und")} ${from / 1000}s: ${text.take(70)}")
    }

    /** After a seek the caller builds a new scout starting at [positionMs]; this reloads ASR for it. */
    fun seek(positionMs: Long, onReady: (Boolean) -> Unit = {}) {
        sealCoverage()
        synchronized(sessionLock) { epoch.incrementAndGet(); initializing=false; queue.clear(); queuedSamples.set(0); if(!preserveTimeline) timeline.reset(); processedThroughMs = positionMs }
        translation.newSession(false)
        active = false; scouting = false; priming = false
        val code = language
        if (code == null) {                       // still searching for the language: keep scanning at the new position
            if (scanWanted) scouting = true
            main.post { onReady(true) }
            return
        }
        begin(code, translation.target, positionMs, replayPreflight = false, onReady = onReady)
    }

    fun finish() {
        if (!active && !priming) return
        finishRequestedEpoch=epoch.get()
        scheduleDrain()
    }
    /** EOF cannot outrun queued PCM when draining yields to engine-load work. */
    private fun maybeFinishOnWorker() {
        val token=epoch.get()
        if(!active || finishRequestedEpoch!=token || queue.isNotEmpty()) return
        finishRequestedEpoch=-1
        try {
            val eng=engine
            if(eng!=null) eng.flush()
            else {
                recognizer?.let { acceptJson(it.finalResult,token) }
                synchronized(sessionLock) { if(token==epoch.get()) processedThroughMs=Long.MAX_VALUE/4; if(musicMode) translation.finishMusicInput(timeline.all()) }
            }
        } catch(e: Exception) { notifyOnUi("SRT ASR: ${e.message}") }
    }

    fun continueWithoutSubtitles() {
        synchronized(sessionLock) { epoch.incrementAndGet(); initializing=false; queue.clear(); queuedSamples.set(0); bypass = true; active = false; scouting = false; priming = false }
        translation.newSession(false)
        worker.execute { releaseEngine(); closeRecognizer(); closeGate() }
        notifyOnUi("Reprodução sem legendas")
    }

    fun stop() {
        synchronized(coverageLock) { completed=emptyList(); delivered=emptyList() }
        restoredCoverage=emptyList(); preserveTimeline=false
        synchronized(sessionLock) { epoch.incrementAndGet(); initializing=false; queue.clear(); queuedSamples.set(0) }
        translation.newSession(false)
        scouting = false; active = false; priming = false; language = null; phase = "Inativo"; languageHint = null
        processedThroughMs = 0L
        worker.execute { closeGate(); closeRecognizer(); releaseEngine(); identifier?.close(); identifier = null }
    }

    private fun releaseEngine() {
        engineBinding?.token=-1; engineBinding=null
        engine?.close(); engine = null; engineKey = ""
    }
    private fun closeRecognizer() {
        recognizer?.close(); recognizer = null
        model?.close(); model = null
    }
    private fun notifyOnUi(message: String, token: Int = epoch.get()) {
        main.post { if(token==epoch.get()) notify(message) }
    }
    private fun name(code: String): String = LanguageMap.displayName(code)
    override fun close() { stop(); worker.shutdown() }
}
