package com.aurareels.ocrlab

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Process-wide shared translation resources (endpoint health, cross-session verified phrase cache,
 * elastic worker pools and on-device ML Kit translator pool) so Video, Online, Canais, Músicas,
 * OCR and SubtitlePackageTranslator reuse warm models, caches and health telemetry without
 * spinning up duplicate thread pools or re-downloading ML Kit packs.
 */
internal object SharedTranslationHub {
    data class CachedEntry(val text: String, val backend: String, val preservedNames: List<String> = emptyList())

    val health = EndpointHealth()

    private fun elasticPool(name: String, maxThreads: Int = 4) = ThreadPoolExecutor(
        0, maxThreads, 60L, TimeUnit.SECONDS, SynchronousQueue(),
        { r -> Thread(r, name).apply { isDaemon = true } }
    )

    val apiPool: ThreadPoolExecutor = elasticPool("aura-api", 4)
    val secondaryApiPool: ThreadPoolExecutor = elasticPool("aura-api-fallback", 2)
    val offlinePool: ThreadPoolExecutor = elasticPool("aura-offline", 3)
    val cleanupPool = Executors.newSingleThreadExecutor { r -> Thread(r, "aura-translator-cleanup").apply { isDaemon = true } }

    private val verifiedCache = object : LinkedHashMap<String, CachedEntry>(1024, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedEntry>?) = size > 3000
    }

    fun getCached(key: String, allowDictionary: Boolean): CachedEntry? =
        synchronized(verifiedCache) { verifiedCache[key] }?.takeUnless { it.backend == "dictionary" && !allowDictionary }

    fun putCached(key: String, entry: CachedEntry) {
        synchronized(verifiedCache) { verifiedCache[key] = entry }
    }

    fun clearCache() {
        synchronized(verifiedCache) { verifiedCache.clear() }
    }

    fun isOnline(connectivity: ConnectivityManager?): Boolean {
        if (connectivity == null) return true
        return runCatching {
            val network = connectivity.activeNetwork ?: return false
            val caps = connectivity.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }.getOrDefault(true)
    }
}

/**
 * Shared LRU pool of on-device ML Kit [Translator] instances keyed by (mlSrc, mlDst).
 * Checks [RemoteModelManager.isModelDownloaded] first so already-downloaded offline models
 * become available immediately even in airplane mode, and retries pending downloads on reconnect.
 */
internal object OfflineTranslatorPool {
    private class Entry(
        val key: String,
        val srcTag: String,
        val dstTag: String,
        val client: Translator,
        @Volatile var ready: Boolean = false,
        @Volatile var downloading: Boolean = false,
        val listeners: MutableList<(Boolean) -> Unit> = ArrayList()
    )

    private val entries = object : LinkedHashMap<String, Entry>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean {
            if (size <= 5) return false
            eldest?.value?.client?.let { old -> SharedTranslationHub.cleanupPool.execute { runCatching { old.close() } } }
            return true
        }
    }

    fun supported(rawSrc: String, rawDst: String): Boolean =
        TranslateLanguage.fromLanguageTag(LanguageMap.mlKit(rawSrc)) != null &&
            TranslateLanguage.fromLanguageTag(LanguageMap.mlKit(rawDst)) != null

    fun isReady(rawSrc: String, rawDst: String): Boolean {
        val src = TranslateLanguage.fromLanguageTag(LanguageMap.mlKit(rawSrc)) ?: return false
        val dst = TranslateLanguage.fromLanguageTag(LanguageMap.mlKit(rawDst)) ?: return false
        return synchronized(entries) { entries["$src>$dst"]?.ready == true }
    }

    fun readyClient(rawSrc: String, rawDst: String): Translator? {
        val src = TranslateLanguage.fromLanguageTag(LanguageMap.mlKit(rawSrc)) ?: return null
        val dst = TranslateLanguage.fromLanguageTag(LanguageMap.mlKit(rawDst)) ?: return null
        val key = "$src>$dst"
        val existing = synchronized(entries) { entries[key] }
        if (existing != null) {
            if (existing.ready) return existing.client
            if (!existing.downloading) warmEntry(existing)
            return null
        }
        // Warm secondary language pairs discovered dynamically by ScriptLanguage.effective (e.g. English lines in K-pop).
        prepare(rawSrc, rawDst)
        return synchronized(entries) { entries[key]?.takeIf { it.ready }?.client }
    }

    fun prepare(rawSrc: String, rawDst: String, onState: ((Boolean) -> Unit)? = null): Translator? {
        val src = TranslateLanguage.fromLanguageTag(LanguageMap.mlKit(rawSrc))
        val dst = TranslateLanguage.fromLanguageTag(LanguageMap.mlKit(rawDst))
        if (src == null || dst == null) {
            onState?.invoke(false)
            return null
        }
        val key = "$src>$dst"
        val entry = synchronized(entries) {
            entries.getOrPut(key) {
                val opts = TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(dst).build()
                Entry(key, src, dst, Translation.getClient(opts))
            }.also { e ->
                if (e.ready) {
                    onState?.invoke(true)
                    return e.client
                }
                if (onState != null) e.listeners.add(onState)
            }
        }
        warmEntry(entry)
        return entry.client
    }

    private fun warmEntry(entry: Entry) {
        synchronized(entries) {
            if (entry.ready || entry.downloading) return
            entry.downloading = true
        }
        val mgr = RemoteModelManager.getInstance()
        val srcModel = TranslateRemoteModel.Builder(entry.srcTag).build()
        val dstModel = TranslateRemoteModel.Builder(entry.dstTag).build()
        Tasks.whenAllSuccess<Boolean>(mgr.isModelDownloaded(srcModel), mgr.isModelDownloaded(dstModel))
            .addOnSuccessListener { flags ->
                if (flags.size == 2 && flags[0] == true && flags[1] == true) {
                    complete(entry, true)
                } else {
                    download(entry)
                }
            }
            .addOnFailureListener { download(entry) }
    }

    private fun download(entry: Entry) {
        entry.client.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener { complete(entry, true) }
            .addOnFailureListener { complete(entry, false) }
    }

    private fun complete(entry: Entry, ok: Boolean) {
        val callbacks = synchronized(entries) {
            entry.downloading = false
            if (ok) entry.ready = true
            entry.listeners.toList().also { if (ok) entry.listeners.clear() }
        }
        callbacks.forEach { runCatching { it(ok) } }
    }

    fun retryPendingDownloads() {
        val pending = synchronized(entries) { entries.values.filter { !it.ready && !it.downloading } }
        pending.forEach { warmEntry(it) }
    }
}

/** Only the user-configured compatible gateway is allowed for network fallback.
 * No scraped translation services, browser-private protocols or default provider hosts.
 */
object WebTranslate {
    data class Result(val text: String, val backend: String = "api")
    @Volatile private var configSource: (() -> CompatibleApiConfig)? = null
    fun configure(source: () -> CompatibleApiConfig) { configSource=source }
    fun translate(text: String, source: String, target: String): String = translateDetailed(text,source,target).text
    fun translateDetailed(text: String, source: String, target: String, log: (String) -> Unit = {}): Result {
        val cfg=configSource?.invoke() ?: throw java.io.IOException("Compatible API gateway is not configured")
        if(!cfg.enabled) throw java.io.IOException("Compatible API gateway is not configured")
        return CompatibleApiSubtitleTranslator(cfg).use { client ->
            val out=client.translate(text,source,target,0L,3000L)
            log("TRANSLATE endpoint=api ok=true")
            Result(out)
        }
    }
    fun translateBatch(texts: List<String>, source: String, target: String, log: (String) -> Unit = {}): List<Result?> {
        if(texts.isEmpty()) return emptyList()
        val cfg=configSource?.invoke() ?: return List(texts.size) { null }
        if(!cfg.enabled) return List(texts.size) { null }
        val outputs=ArrayList<Result?>()
        CompatibleApiSubtitleTranslator(cfg).use { client ->
            texts.chunked(3).forEach { chunk ->
                val rows=chunk.mapIndexed { i,t -> ApiTimedLine("item:$i",i*3000L,i*3000L+2500,t.take(500)) }
                val batch=runCatching { client.translateLines(rows,source,target) }.getOrNull()
                if(batch!=null) log("TRANSLATE endpoint=api batch=${chunk.size} ok=true")
                outputs.addAll(chunk.indices.map { i -> batch?.getOrNull(i)?.text?.let { Result(it) } })
            }
        }
        return outputs
    }
}

/**
 * Unified translation engine shared across Video (Local/Online/Canais), Music/Lyrics, OCR and SubtitlePackageTranslator.
 * Combines:
 *  - Instant local resolution (script language override, noise/laughter filter, session + cross-session RAM cache,
 *    SQLite TranslationVault, and 12-language local interjection dictionary);
 *  - Opportunistic 2-3 cue API batching (cutting 3-stage HTTP handshakes by up to 3x in both Music and Video bursts);
 *  - Offline fallback + optional configured API refinement;
 *  - Zero-wait offline mode (skips network timeouts immediately when offline, routing straight to on-device ML Kit + Vault).
 */
class GoogleTranslateBridge(context: android.content.Context, private val notify: (String) -> Unit) : Closeable {
    private data class Result(
        val text: String,
        val backend: String,
        val reason: String? = null,
        val preservedNames: List<String> = emptyList(),
        val mergeInto: SpeechCue? = null
    )

    private val appContext = context.applicationContext
    private val prefs = AppPrefs(appContext)
    @Volatile var lastApiFailure: String? = null; private set
    val musicProvisionalCount: Int get() = audited.count { it.reason?.startsWith("PROVISIONAL:") == true }
    @Volatile var musicMode = false
    /** True for background package completion where no video frame is waiting on startup latency. */
    @Volatile var backgroundPackageMode = false
    val vault = TranslationVault.shared(appContext)
    @Volatile private var vaultRevision = vault.revision
    private val cache = object : LinkedHashMap<String, Result>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Result>?) = size > 1500
    }
    private val translationQueue = java.util.concurrent.LinkedBlockingDeque<Runnable>(24)
    private val exportRequests = ConcurrentHashMap.newKeySet<String>()
    private val queuedJobs = ConcurrentHashMap<String, Runnable>()
    private val queuedCues = ConcurrentHashMap<String, SpeechCue>()
    val pendingTranslations: Int get() = audited.count { !valid(it) }
    // Two warm workers keep near-live captions moving even when API takes 4–9s.
    // The bounded queue and existing priority/retry logic still apply.
    private val webWorker = ThreadPoolExecutor(
        2, 3, 45L, TimeUnit.SECONDS, translationQueue,
        { Thread(it, "aura-translate").apply { isDaemon = true } }
    ).apply { allowCoreThreadTimeOut(true) }
    private val apiWorker get() = SharedTranslationHub.apiPool
    private fun configuredRemote(): Boolean = prefs.translationEndpoint.isNotBlank() && prefs.translationModel.isNotBlank()
    private val googleWorker get() = SharedTranslationHub.secondaryApiPool
    private val offlineWorker get() = SharedTranslationHub.offlinePool
    @Volatile private var playbackPosition = 0L
    @Volatile private var playbackAdvancing = false
    fun updatePlaybackPosition(positionMs: Long, advancing: Boolean = true) {
        playbackPosition = positionMs.coerceAtLeast(0); playbackAdvancing = advancing
    }
    private val jobs = ConcurrentHashMap<String, CompletableFuture<Result?>>()
    private val clients = java.util.concurrent.CopyOnWriteArrayList<CompatibleApiSubtitleTranslator>()
    private val clientLocal = object : ThreadLocal<CompatibleApiSubtitleTranslator>() {
        override fun initialValue() = CompatibleApiSubtitleTranslator { CompatibleApiConfig(prefs.translationEndpoint,prefs.translationModel,ApiRuntimeCredentials.sessionToken) }.also { clients.add(it) }
    }
    private val health get() = SharedTranslationHub.health
    private val contextLines = java.util.concurrent.ConcurrentSkipListMap<Long, Pair<String, String>>()
    private val sourceLines = java.util.concurrent.ConcurrentSkipListMap<Long, SpeechCue>()
    private val musicPending = java.util.ArrayDeque<SpeechCue>()
    private var musicQueuedAt = 0L
    private var musicTranscriptComplete = false
    private val musicCalls = ConcurrentHashMap.newKeySet<Future<*>>()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { Thread(it, "aura-translator-pulse").apply { isDaemon = true } }
    @Volatile private var fallback: Translator? = null
    @Volatile private var offlineReady = false
    @Volatile private var generation = 0L
    val sessionEpoch: Long get() = generation
    @Volatile private var closed = false
    @Volatile private var session = java.util.UUID.randomUUID().toString()
    @Volatile var original = "zh"; private set
    @Volatile var target = "pt"; private set
    @Volatile var state = "IA principal · cache/cofre local antes da rede"; private set
    private val audited = ConcurrentHashMap.newKeySet<SpeechCue>()
    private val connectivity = appContext.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) {
            SharedTranslationHub.health.clearTransient()
            clients.forEach { it.clearTransientCooldown() }
            OfflineTranslatorPool.retryPendingDownloads()
            synchronized(this@GoogleTranslateBridge) {
                if (!closed) {
                    audited.filter { !valid(it) }.forEach { it.retryAt = 0 }
                    if (musicMode && musicPending.isNotEmpty()) scheduler.execute { runCatching { flushMusic(urgent = true) } }
                }
            }
        }
    }

    init {
        WebTranslate.configure { CompatibleApiConfig(prefs.translationEndpoint,prefs.translationModel,ApiRuntimeCredentials.sessionToken) }
        scheduler.scheduleWithFixedDelay({ runCatching { flushMusic() } }, 150, 150, TimeUnit.MILLISECONDS)
        scheduler.scheduleWithFixedDelay({ runCatching { auditCoverage() } }, 1000, 1000, TimeUnit.MILLISECONDS)
        runCatching { connectivity?.registerDefaultNetworkCallback(networkCallback) }
    }

    private fun isOnline(): Boolean = SharedTranslationHub.isOnline(connectivity)
    fun isCurrentlyOnline(): Boolean = isOnline()
    private fun effectiveSource(cue: SpeechCue): String = ScriptLanguage.effective(cue.source, original)
    private fun valid(cue: SpeechCue) = TranslationCoverage.complete(cue, effectiveSource(cue), target)

    @Synchronized private fun auditCoverage() {
        if (closed) return
        val now = System.currentTimeMillis()
        val online = isOnline()
        if (!musicMode && !backgroundPackageMode) {
            queuedCues.entries.forEach { (key, cue) ->
                if (!exportRequests.contains(key) && !SubtitleWorkPolicy.eligible(cue.startMs, cue.endMs, playbackPosition, false)) {
                    queuedJobs[key]?.let { task ->
                        if (translationQueue.remove(task)) {
                            queuedJobs.remove(key, task); queuedCues.remove(key); jobs.remove(key)?.complete(null)
                        }
                    }
                }
            }
        }
        audited.filter { cue ->
            !cue.busy && now >= cue.retryAt &&
                // While offline, do not spin retrying cues that already have a usable provisional offline translation.
                (online || cue.reason?.startsWith("PROVISIONAL:") != true) &&
                SubtitleWorkPolicy.eligible(cue.startMs, cue.endMs, playbackPosition, musicMode || backgroundPackageMode) &&
                !valid(cue)
        }.sortedBy { kotlin.math.abs(it.startMs - playbackPosition) }.take(3).forEach {
            if (it.reason?.startsWith("PROVISIONAL:") != true) it.output = null
            if (musicMode) enqueueMusic(it) else submit(it, urgent = true)
        }
    }

    private val hits = java.util.concurrent.atomic.AtomicLong()
    private val requests = java.util.concurrent.atomic.AtomicLong()
    val cacheStats: String get() = "dicionário/cache ${hits.get()}/${requests.get()} · ${100 * hits.get() / maxOf(1, requests.get())}% chamadas evitadas"

    /** Invalidates queued callbacks and API context immediately, including same-language video changes. */
    @Synchronized fun newSession(newVideo: Boolean = true) {
        audited.clear()
        musicPending.forEach { it.busy = false }; musicPending.clear(); sourceLines.clear(); musicTranscriptComplete = false
        musicCalls.forEach { it.cancel(true) }; musicCalls.clear()
        lastApiFailure = null
        generation++; playbackPosition = 0L; playbackAdvancing = false
        if (newVideo) session = java.util.UUID.randomUUID().toString()
        jobs.values.forEach { it.complete(null) }; jobs.clear(); webWorker.queue.clear()
        queuedJobs.clear(); queuedCues.clear(); exportRequests.clear()
        clients.forEach { it.reset() }; contextLines.clear()
        if (newVideo) synchronized(cache) { cache.clear() }
    }

    @Synchronized fun clearCache() {
        synchronized(cache) { cache.clear() }
        SharedTranslationHub.clearCache()
    }

    /** Pre-downloads the on-device ML Kit translation model in the background so offline playback works on the first run. */
    fun prewarmOffline(rawSource: String, rawTarget: String = "pt") {
        val src = LanguageMap.canonical(rawSource).ifEmpty { rawSource }
        val dst = LanguageMap.canonical(rawTarget).ifEmpty { rawTarget }
        if (src != dst) OfflineTranslatorPool.prepare(src, dst)
    }

    fun isOfflineReady(rawSource: String = original, rawTarget: String = target): Boolean {
        val src = LanguageMap.canonical(rawSource).ifEmpty { rawSource }
        val dst = LanguageMap.canonical(rawTarget).ifEmpty { rawTarget }
        return src == dst || OfflineTranslatorPool.isReady(src, dst)
    }

    @Synchronized fun configure(rawSource: String, rawTarget: String) {
        val sourceCode = LanguageMap.canonical(rawSource).ifEmpty { rawSource }
        val targetCode = LanguageMap.canonical(rawTarget).ifEmpty { rawTarget }
        val samePair = sourceCode == original && targetCode == target && (fallback != null || !OfflineTranslatorPool.supported(sourceCode, targetCode))
        newSession(false); original = sourceCode; target = targetCode
        val epoch = generation
        if (sourceCode == targetCode) { fallback = null; offlineReady = true; state = "Idioma original"; return }
        if (!samePair) {
            offlineReady = OfflineTranslatorPool.isReady(sourceCode, targetCode)
            state = if (offlineReady) "IA principal · ML Kit offline pronto" else "IA principal · preparando alternativa offline"
            val client = OfflineTranslatorPool.prepare(sourceCode, targetCode) { ok ->
                if (!closed && generation == epoch) {
                    offlineReady = ok
                    state = if (ok) "IA principal · ML Kit offline pronto" else "IA principal · alternativa web ativa"
                    notify(state)
                }
            }
            fallback = client
            if (client == null) {
                offlineReady = false
                state = "IA principal · alternativa web ativa"
            }
        }
        if (prefs.vaultEnabled) webWorker.execute {
            val entries = runCatching { vault.preload(sourceCode, targetCode) }.getOrDefault(emptyMap())
            synchronized(this) {
                if (epoch == generation && prefs.vaultEnabled) synchronized(cache) {
                    entries.forEach { (key, value) ->
                        val cacheKey = "$sourceCode:$targetCode:$key"
                        cache.putIfAbsent(cacheKey, Result(value, "dictionary"))
                    }
                }
            }
        }
    }

    @Synchronized fun registerSourceCues(cues: List<SpeechCue>, complete: Boolean = false) {
        cues.forEach { sourceLines[it.startMs] = it }
        while (sourceLines.size > 1500) sourceLines.pollFirstEntry()
        if (musicMode && complete) musicTranscriptComplete = true
    }

    fun finishMusicInput(cues: List<SpeechCue>) { registerSourceCues(cues, complete = true); flushMusic(urgent = true) }

    fun translate(cue: SpeechCue) {
        if (cue.humanEdited) return
        audited.add(cue)
        sourceLines[cue.startMs] = cue
        while (sourceLines.size > 1500) sourceLines.pollFirstEntry()
        if (musicMode) enqueueMusic(cue) else submit(cue)
    }

    /** Shared fast local path (noise/laugh filter, session & cross-session RAM cache, SQLite Vault, seed interjections). */
    private fun lookupLocal(cue: SpeechCue, src: String, dst: String, key: String): Result? {
        val input = NoiseFilter.cleanModelTokens(cue.source)
        if (src == dst) return Result(input, "original", if (original != dst) "ECHO:ORIGINAL" else null)
        // Defense in depth: no model/control tokens, fillers or sound-only cues reach any API.
        val kind = NoiseClassifier.classify(cue.source, src, cue.endMs - cue.startMs, prefs.noiseLevel, event = if (cue.acousticSpeech) "Speech" else null, lyrics = musicMode)
        if (kind == CueClass.LAUGH) return Result(when (prefs.laughterMode) { 1 -> "(risos)"; 2 -> NoiseFilter.clean(cue.source); else -> "" }, "event", "SUPPRESSED:LAUGH")
        if (kind !in setOf(CueClass.SPEECH, CueClass.INTERJECTION)) return Result("", "event", "SUPPRESSED:$kind")
        if (vaultRevision != vault.revision) { clearCache(); vaultRevision = vault.revision }
        synchronized(cache) { cache[key] }?.takeUnless { it.backend == "dictionary" && !prefs.vaultEnabled }?.let {
            TranslationText.accepted(input, it.text, dst, it.preservedNames)?.let { clean ->
                hits.incrementAndGet()
                return Result(clean, it.backend, preservedNames = it.preservedNames)
            }
        }
        SharedTranslationHub.getCached(key, prefs.vaultEnabled)?.let { shared ->
            TranslationText.accepted(input, shared.text, dst, shared.preservedNames)?.let { clean ->
                synchronized(cache) { cache[key] = Result(clean, shared.backend, preservedNames = shared.preservedNames) }
                hits.incrementAndGet()
                return Result(clean, shared.backend, preservedNames = shared.preservedNames)
            }
        }
        if (prefs.vaultEnabled) {
            val local = runCatching { vault.lookup(src, dst, input) }.getOrNull()
            if (local != null) TranslationText.accepted(input, local, dst)?.let {
                val res = Result(it, "dictionary")
                synchronized(cache) { cache[key] = res }
                hits.incrementAndGet()
                return res
            }
        }
        if (dst == "pt" && !musicMode) NoiseClassifier.seed(input, src)?.let { hits.incrementAndGet(); return Result(it, "seed") }
        return null
    }

    /** Single authoritative commit path shared by Video, Music, OCR, Editor and SRT Export. */
    @Synchronized private fun commitCueResult(
        cue: SpeechCue,
        src: String,
        dst: String,
        cacheKey: String,
        sessionId: String,
        epoch: Long,
        result: Result?,
        provisionalReason: String? = null
    ) {
        if (closed || generation != epoch) return
        if (result != null) {
            val mergeHead = result.mergeInto
            if (mergeHead != null && mergeTarget(cue) === mergeHead) {
                mergeCues(mergeHead, cue, result)
            } else if (mergeHead == null && !cue.humanEdited) {
                val effectiveReason = provisionalReason ?: result.reason
                val canReplace = !valid(cue) || (cue.reason?.startsWith("PROVISIONAL:") == true && effectiveReason?.startsWith("PROVISIONAL:") != true)
                if (canReplace) {
                    cue.preservedNames = result.preservedNames
                    cue.output = result.text
                    cue.provider = result.backend
                    cue.reason = effectiveReason
                }
                if (result.text.isNotBlank()) {
                    contextLines[cue.startMs] = cue.source to result.text
                    while (contextLines.size > 40) contextLines.pollFirstEntry()
                    val isProvisional = effectiveReason?.startsWith("PROVISIONAL:") == true
                    if (!isProvisional && result.backend !in setOf("event")) {
                        val finalRes = Result(result.text, result.backend, result.reason, result.preservedNames)
                        synchronized(cache) { cache[cacheKey] = finalRes }
                        if (result.backend !in setOf("original")) {
                            SharedTranslationHub.putCached(cacheKey, SharedTranslationHub.CachedEntry(result.text, result.backend, result.preservedNames))
                        }
                        if (prefs.vaultEnabled && result.backend !in setOf("dictionary", "original", "event", "seed")) {
                            vault.observe(src, dst, cue.source, result.text, result.backend, sessionId)
                        }
                    }
                }
            }
        }
        val completeNow = valid(cue)
        if (!completeNow) {
            if (cue.reason?.startsWith("PROVISIONAL:") == true) {
                val baseWait = if (isOnline()) 15_000L else 60_000L
                cue.retryAt = System.currentTimeMillis() + maxOf(baseWait, TranslationCoverage.retryDelay(cue.tries, 250))
            } else {
                cue.reason = "PENDING:" + (cue.reason?.removePrefix("PENDING:") ?: "PROVIDER_RECOVERY")
                cue.retryAt = System.currentTimeMillis() + TranslationCoverage.retryDelay(cue.tries, kotlin.random.Random.nextLong(100, 700))
            }
        }
        notify("COVERAGE cue=${cue.startMs}-${cue.endMs} state=${if (completeNow) "COMPLETE" else "PENDING"} provider=${cue.provider ?: "none"} attempts=${cue.tries} reason=${cue.reason ?: "translated"}")
    }

    @Synchronized private fun enqueueMusic(cue: SpeechCue, front: Boolean = false) {
        if (closed || valid(cue)) return
        if (cue.busy) {
            if (front && musicPending.remove(cue)) musicPending.addFirst(cue)
            return
        }
        cue.busy = true; cue.tries++; cue.lastTryAt = System.currentTimeMillis()
        cue.reason = if (cue.output.isNullOrBlank()) "PENDING:MUSIC_CONTEXT" else "PROVISIONAL:MUSIC_API_PENDING"
        if (musicPending.isEmpty()) musicQueuedAt = System.currentTimeMillis()
        if (front) musicPending.addFirst(cue) else musicPending.addLast(cue)
    }

    @Synchronized private fun flushMusic(urgent: Boolean = false) {
        if (closed || musicPending.isEmpty()) return
        val age = System.currentTimeMillis() - musicQueuedAt
        val nearPlayhead = musicPending.peekFirst()?.let { it.startMs - playbackPosition in -4000L..8000L } == true
        if (!urgent && !nearPlayhead && !SubtitleTranslationPolicy.musicBatchReady(musicPending.size, sourceLines.size, musicTranscriptComplete, age)) return
        val batch = ArrayList<SpeechCue>()
        repeat(minOf(3, musicPending.size)) { batch.add(musicPending.removeFirst()) }
        musicQueuedAt = System.currentTimeMillis()
        val epoch = generation; val dst = target; val sessionId = session
        try {
            webWorker.execute { translateMusicBatch(batch, dst, sessionId, epoch) }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            synchronized(this) { batch.forEach { it.busy = false; it.retryAt = System.currentTimeMillis() + 1500 } }
        }
    }

    private fun translateMusicBatch(batch: List<SpeechCue>, dst: String, sessionId: String, epoch: Long) {
        try {
            if (epoch != generation || closed) return
            // Step 1: Reuse local cache / Vault / Portuguese echo BEFORE making any network call (chorus deduplication!).
            val pending = ArrayList<SpeechCue>()
            for (cue in batch) {
                requests.incrementAndGet()
                val src = effectiveSource(cue)
                val key = "$src:$dst:${DictionaryPolicy.normalize(cue.source, src)}"
                val local = lookupLocal(cue, src, dst, key)
                if (local != null) {
                    commitCueResult(cue, src, dst, key, sessionId, epoch, local)
                } else {
                    pending.add(cue)
                }
            }
            if (pending.isEmpty() || epoch != generation || closed) return

            val batchSrc = effectiveSource(pending.first())
            val sameSrc = pending.all { effectiveSource(it) == batchSrc }
            val results = HashMap<SpeechCue, Result>()
            val online = isOnline()

            // Step 2: Online API stanza batch (with fast provisional fallback if a lyric is due right now).
            if (online && sameSrc && batchSrc != dst && configuredRemote() && health.allowed("api") && pending.all { it.source.length <= 500 && it.endMs > it.startMs }) {
                var call: Future<List<ApiTranslatedLine>>? = null
                try {
                    val lines = pending.mapIndexed { i, c -> ApiTimedLine("$epoch:${c.startMs}:$i", c.startMs, c.endMs, c.source) }
                    val before = sourceLines.headMap(pending.first().startMs).values.toList().takeLast(3).map { it.source to it.output.orEmpty() }
                    val after = sourceLines.tailMap(pending.last().startMs, false).values.take(3).mapIndexed { i, c -> ApiTimedLine("context:$i", c.startMs, c.endMs, c.source) }
                    call = apiWorker.submit(Callable {
                        check(epoch == generation && !closed)
                        clientLocal.get()!!.translateLines(lines, batchSrc, dst, before, after, music = true)
                    })
                    musicCalls.add(call)

                    // If an untranslated lyric is already near the playhead, give it a fast provisional line after 2.2s while API continues.
                    val dueSoon = pending.any { it.output.isNullOrBlank() && it.startMs - playbackPosition <= 8_000L }
                    val output = if (dueSoon) {
                        try {
                            call.get(2_200, TimeUnit.MILLISECONDS)
                        } catch (_: java.util.concurrent.TimeoutException) {
                            fillProvisionalBatch(pending, dst, sessionId, epoch, "API_IN_FLIGHT")
                            call.get(33_300, TimeUnit.MILLISECONDS)
                        }
                    } else {
                        call.get(35_500, TimeUnit.MILLISECONDS)
                    }

                    if (epoch != generation || closed) return
                    health.success("api"); lastApiFailure = null
                    output.zip(pending).forEach { (line, cue) ->
                        line.text?.let { TranslationText.accepted(cue.source, it, dst, line.preservedNames) }?.let {
                            results[cue] = Result(it, "api", preservedNames = line.preservedNames)
                        }
                    }
                    notify("TRANSLATE endpoint=api musicBatch=${pending.size} ok=true")
                } catch (e: Exception) {
                    if (epoch == generation && !closed && e !is java.util.concurrent.CancellationException) {
                        val http = ApiFailure.http(e)
                        if (!ApiFailure.contentFailure(e)) health.failure("api", http?.status in setOf(401, 403, 429), http?.retryAfterMs ?: 0)
                        lastApiFailure = ApiFailure.reason(e)
                        notify("TRANSLATE endpoint=api musicBatch=${pending.size} ok=false reason=$lastApiFailure · tradução provisória")
                    }
                } finally {
                    call?.let { musicCalls.remove(it); if (!it.isDone) it.cancel(true) }
                }
            } else if (!online) {
                lastApiFailure = "OFFLINE"
            }

            // Step 3: Batch WebTranslate / ML Kit fallback for any cue API did not resolve (avoids 1-by-1 HTTP floods).
            val unresolvedNew = pending.filter { it !in results && it.output.isNullOrBlank() }
            val webBatchMap = HashMap<SpeechCue, Result>()
            if (online && unresolvedNew.size >= 2 && sameSrc) {
                val webOut = WebTranslate.translateBatch(unresolvedNew.map { NoiseFilter.cleanModelTokens(it.source) }, batchSrc, dst) { msg ->
                    if (epoch == generation && !closed) notify(msg)
                }
                unresolvedNew.zip(webOut).forEach { (cue, wr) ->
                    if (wr != null) {
                        webBatchMap[cue] = if (wr.backend == "echo") Result(wr.text, "original", "ECHO:ORIGINAL") else Result(wr.text, wr.backend)
                    }
                }
            }

            for (cue in pending) {
                if (epoch != generation || closed || Thread.currentThread().isInterrupted) return
                val src = effectiveSource(cue)
                val key = "$src:$dst:${DictionaryPolicy.normalize(cue.source, src)}"
                // Keep the existing provisional text during API recovery; do not repeatedly call Google.
                val result = results[cue]
                    ?: webBatchMap[cue]
                    ?: if (cue.output.isNullOrBlank()) resolve(cue, src, dst, key, epoch, false, skipApi=true)
                    else Result(cue.output.orEmpty(), cue.provider ?: "gtx", preservedNames = cue.preservedNames)
                val provReason = if (result != null && result.backend !in setOf("api", "original", "dictionary", "seed", "event"))
                    "PROVISIONAL:API_${lastApiFailure ?: "UNAVAILABLE"}" else null
                commitCueResult(cue, src, dst, key, sessionId, epoch, result, provisionalReason = provReason)
            }
        } finally {
            batch.forEach { it.busy = false }
        }
    }

    private fun fillProvisionalBatch(cues: List<SpeechCue>, dst: String, sessionId: String, epoch: Long, tag: String) {
        if (epoch != generation || closed) return
        val need = cues.filter { it.output.isNullOrBlank() }
        if (need.isEmpty()) return
        val src = effectiveSource(need.first())
        val sameSrc = need.all { effectiveSource(it) == src }
        if (isOnline() && sameSrc) {
            val batchOut = runCatching { WebTranslate.translateBatch(need.map { NoiseFilter.cleanModelTokens(it.source) }, src, dst) }.getOrNull()
            if (batchOut != null) {
                need.zip(batchOut).forEach { (cue, wr) ->
                    if (wr != null && cue.output.isNullOrBlank()) {
                        val key = "$src:$dst:${DictionaryPolicy.normalize(cue.source, src)}"
                        val r = if (wr.backend == "echo") Result(wr.text, "original", "ECHO:ORIGINAL") else Result(wr.text, wr.backend)
                        val prov = if (r.backend == "original") null else "PROVISIONAL:$tag"
                        commitCueResult(cue, src, dst, key, sessionId, epoch, r, provisionalReason = prov)
                    }
                }
                return
            }
        }
        for (cue in need) {
            val cueSrc = effectiveSource(cue)
            val input = NoiseFilter.cleanModelTokens(cue.source)
            translateOffline(input, cueSrc, dst)?.let { r ->
                val key = "$cueSrc:$dst:${DictionaryPolicy.normalize(cue.source, cueSrc)}"
                commitCueResult(cue, cueSrc, dst, key, sessionId, epoch, r, provisionalReason = "PROVISIONAL:$tag")
            }
        }
    }

    @Synchronized private fun submit(cue: SpeechCue, forExport: Boolean = false, urgent: Boolean = false): CompletableFuture<Result?> {
        if (!SubtitleWorkPolicy.eligible(cue.startMs, cue.endMs, playbackPosition, musicMode || backgroundPackageMode, forExport)) {
            cue.reason = "DEFERRED:PLAYBACK_WINDOW"
            return CompletableFuture.completedFuture(null)
        }
        val epoch = generation; val src = effectiveSource(cue); val dst = target; val sessionId = session
        val norm = DictionaryPolicy.normalize(cue.source, src)
        val cacheKey = "$src:$dst:$norm"; val jobKey = "$epoch:$cacheKey"
        synchronized(cue) {
            if (valid(cue)) return CompletableFuture.completedFuture(Result(cue.output.orEmpty(), "existing"))
            if (forExport) exportRequests.add(jobKey)
            if (cue.busy) return jobs[jobKey] ?: CompletableFuture.completedFuture(null)
            cue.busy = true; cue.tries++; cue.lastTryAt = System.currentTimeMillis()
        }
        requests.incrementAndGet()
        // Fast synchronous check: hits in RAM cache / Vault / seed interjections finish in 0 ms without occupying the 24-slot worker queue!
        lookupLocal(cue, src, dst, cacheKey)?.let { immediate ->
            commitCueResult(cue, src, dst, cacheKey, sessionId, epoch, immediate)
            exportRequests.remove(jobKey)
            cue.busy = false
            return CompletableFuture.completedFuture(immediate)
        }

        val created = CompletableFuture<Result?>()
        val existing = jobs.putIfAbsent(jobKey, created)
        val future = existing ?: created
        future.whenComplete { result, _ ->
            synchronized(this) {
                commitCueResult(cue, src, dst, cacheKey, sessionId, epoch, result)
                cue.busy = false
            }
        }
        if (existing == null) {
            val task = Runnable {
                queuedJobs.remove(jobKey); queuedCues.remove(jobKey)
                try {
                    val isExport = exportRequests.contains(jobKey)
                    if (epoch != generation || closed || !SubtitleWorkPolicy.eligible(cue.startMs, cue.endMs, playbackPosition, musicMode || backgroundPackageMode, isExport)) {
                        created.complete(null); return@Runnable
                    }
                    // Opportunistically batch up to 2 companion cues waiting in the queue when API is active and online!
                    if (!urgent && tryCompleteVideoBatch(cue, jobKey, created, src, dst, epoch, isExport)) {
                        return@Runnable
                    }
                    val result = resolve(cue, src, dst, cacheKey, epoch, urgent)
                    synchronized(this) {
                        if (epoch == generation && !closed) created.complete(result) else created.complete(null)
                    }
                } catch (e: Exception) {
                    if (epoch == generation && !closed) notify("Tradução indisponível: ${e.message?.take(80)}")
                    created.complete(null)
                } finally {
                    jobs.remove(jobKey, created); exportRequests.remove(jobKey)
                }
            }
            queuedJobs[jobKey] = task; queuedCues[jobKey] = cue
            try {
                if (urgent && webWorker.poolSize >= 3) {
                    if (!translationQueue.offerFirst(task)) {
                        // Displace one speculative job, retaining its cue in the auditor for retry.
                        val displaced = translationQueue.pollLast()
                        queuedJobs.entries.firstOrNull { it.value === displaced }?.let { old ->
                            queuedJobs.remove(old.key, old.value); queuedCues.remove(old.key); exportRequests.remove(old.key)
                            jobs.remove(old.key)?.complete(null)
                        }
                        if (!translationQueue.offerFirst(task)) throw java.util.concurrent.RejectedExecutionException()
                    }
                } else webWorker.execute(task)
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                queuedJobs.remove(jobKey, task); queuedCues.remove(jobKey); exportRequests.remove(jobKey)
                jobs.remove(jobKey, created); created.complete(null)
            }
        }
        return future
    }

    /**
     * Reuses Music's 2-3 item API/Web batch contract for Video, Package Editor and SRT Export bursts
     * whenever companion cues of the same language are already waiting in [translationQueue].
     */
    private data class ClaimedCue(val key: String, val cue: SpeechCue, val future: CompletableFuture<Result?>)

    private fun claimBatchCompanions(head: SpeechCue, src: String, epoch: Long, forExport: Boolean): List<ClaimedCue> {
        val claimed = ArrayList<ClaimedCue>(2)
        synchronized(this) {
            if (closed || epoch != generation) return emptyList()
            val candidates = queuedCues.entries
                .filter { (_, c) ->
                    effectiveSource(c) == src &&
                        SubtitleWorkPolicy.eligible(c.startMs, c.endMs, playbackPosition, musicMode || backgroundPackageMode, forExport || exportRequests.contains("$epoch:$src:$target:${DictionaryPolicy.normalize(c.source, src)}")) &&
                        SubtitleTranslationPolicy.videoBatchEligible(head, c, src, sourceLines.lowerEntry(c.startMs)?.value, sourceLines.higherEntry(c.startMs)?.value)
                }
                .sortedBy { it.value.startMs }
            for ((k, c) in candidates) {
                if (claimed.size >= 2) break
                val runnable = queuedJobs[k] ?: continue
                val fut = jobs[k] ?: continue
                if (translationQueue.remove(runnable)) {
                    queuedJobs.remove(k, runnable); queuedCues.remove(k)
                    claimed.add(ClaimedCue(k, c, fut))
                }
            }
        }
        return claimed
    }

    private fun tryCompleteVideoBatch(
        head: SpeechCue,
        headJobKey: String,
        headFuture: CompletableFuture<Result?>,
        src: String,
        dst: String,
        epoch: Long,
        forExport: Boolean
    ): Boolean {
        if (src == dst) return false
        val headShort = SubtitleTranslationPolicy.shortRoute(head, src, sourceLines.lowerEntry(head.startMs)?.value, sourceLines.higherEntry(head.startMs)?.value)
        if (headShort) return false
        val grace = if (backgroundPackageMode || forExport) 35_500L else SubtitleWorkPolicy.primaryGrace(head.startMs, playbackPosition, playbackAdvancing)
        if (grace < 2_500L && isOnline() && configuredRemote() && health.allowed("api")) return false
        val companions = claimBatchCompanions(head, src, epoch, forExport)
        if (companions.isEmpty()) return false

        val allItems = listOf(ClaimedCue(headJobKey, head, headFuture)) + companions
        val sortedItems = allItems.sortedBy { it.cue.startMs }
        val resolvedByKey = HashMap<String, Result?>()
        try {
            val online = isOnline()
            if (online && configuredRemote() && health.allowed("api") && sortedItems.all { it.cue.source.length <= 500 && it.cue.endMs > it.cue.startMs }) {
                val minGrace = if (backgroundPackageMode || forExport) 35_500L
                else sortedItems.minOf { SubtitleWorkPolicy.primaryGrace(it.cue.startMs, playbackPosition, playbackAdvancing) }.coerceAtLeast(2_500L)
                val started = System.nanoTime()
                val primary = launch(apiWorker, epoch) {
                    try {
                        val before = sourceLines.headMap(sortedItems.first().cue.startMs).values.toList().takeLast(3).map { it.source to it.output.orEmpty() }
                        val after = sourceLines.tailMap(sortedItems.last().cue.startMs, false).values.take(3).mapIndexed { i, c -> ApiTimedLine("context:$i", c.startMs, c.endMs, c.source) }
                        val lines = sortedItems.mapIndexed { idx, item -> ApiTimedLine("b$idx", item.cue.startMs, item.cue.endMs, NoiseFilter.cleanModelTokens(item.cue.source)) }
                        val rawList = clientLocal.get()!!.translateLines(lines, src, dst, before, after, music = false)
                        if (epoch != generation || Thread.currentThread().isInterrupted) emptyMap()
                        else {
                            health.success("api"); lastApiFailure = null
                            notify("TRANSLATE endpoint=api videoBatch=${sortedItems.size} ms=${(System.nanoTime() - started) / 1_000_000} ok=true")
                            val out = HashMap<String, Result>()
                            for (i in sortedItems.indices) {
                                val raw = rawList[i]; val cue = sortedItems[i].cue; val input = NoiseFilter.cleanModelTokens(cue.source)
                                val text = raw.text ?: continue
                                // Do not merge across cues inside a multi-cue batch when the predecessor is in-flight; only merge into an already settled cue before the batch.
                                val mergeHead = if (raw.continuesPrevious && i == 0) mergeTarget(cue) else null
                                if (raw.continuesPrevious && mergeHead == null) continue
                                val checkSrc = if (mergeHead != null) mergeHead.source + input else input
                                TranslationText.accepted(checkSrc, text, dst, raw.preservedNames)?.let { clean ->
                                    out[sortedItems[i].key] = Result(clean, "api", preservedNames = raw.preservedNames, mergeInto = mergeHead)
                                }
                            }
                            out
                        }
                    } catch (e: Exception) {
                        if (epoch == generation && !Thread.currentThread().isInterrupted) {
                            val http = ApiFailure.http(e)
                            if (!ApiFailure.contentFailure(e)) health.failure("api", http?.status in setOf(401, 403, 429), http?.retryAfterMs ?: 0)
                            lastApiFailure = ApiFailure.reason(e)
                            notify("TRANSLATE endpoint=api videoBatch=${sortedItems.size} ms=${(System.nanoTime() - started) / 1_000_000} ok=false reason=$lastApiFailure · fallback em lote")
                        }
                        emptyMap()
                    }
                }
                val apiMap = try {
                    primary?.get(minGrace, TimeUnit.MILLISECONDS) ?: emptyMap()
                } catch (_: java.util.concurrent.TimeoutException) {
                    primary?.cancel(true); emptyMap()
                } catch (_: Exception) { emptyMap() }
                resolvedByKey.putAll(apiMap)
            }

            // Any cues in the batch still unresolved share a single WebTranslate batch call, then per-cue offline fallback.
            val missing = sortedItems.filter { resolvedByKey[it.key] == null }
            if (online && missing.size >= 2) {
                val webOut = WebTranslate.translateBatch(missing.map { NoiseFilter.cleanModelTokens(it.cue.source) }, src, dst) { msg ->
                    if (epoch == generation && !closed) notify(msg)
                }
                missing.zip(webOut).forEach { (item, wr) ->
                    if (wr != null) {
                        resolvedByKey[item.key] = if (wr.backend == "echo") Result(wr.text, "original", "ECHO:ORIGINAL") else Result(wr.text, wr.backend)
                    }
                }
            }
            for (item in sortedItems) {
                if (resolvedByKey[item.key] == null && epoch == generation && !closed) {
                    val itemCacheKey = "$src:$dst:${DictionaryPolicy.normalize(item.cue.source, src)}"
                    resolvedByKey[item.key] = resolve(item.cue, src, dst, itemCacheKey, epoch, urgent = false, skipApi=true)
                }
            }
            for (item in allItems) {
                val res = if (epoch == generation && !closed) resolvedByKey[item.key] else null
                item.future.complete(res)
            }
            return true
        } finally {
            for (comp in companions) {
                if (!comp.future.isDone) comp.future.complete(null)
                jobs.remove(comp.key, comp.future)
                exportRequests.remove(comp.key)
            }
        }
    }

    /** Previous visible cue this fragment may be glued to (same sentence cut by the ASR), or null. */
    private fun mergeTarget(cue: SpeechCue): SpeechCue? {
        var prev = sourceLines.lowerEntry(cue.startMs)?.value
        var guard = 0
        while (prev != null && prev.reason == "SUPPRESSED:MERGED" && guard++ < 6) prev = sourceLines.lowerEntry(prev.startMs)?.value
        if (prev == null || prev.humanEdited || prev.suppressed || prev.reason?.startsWith("SUPPRESSED:") == true) return null
        if (cue.startMs - prev.endMs !in -200L..1500L || cue.endMs - prev.startMs > 14_000L) return null
        return prev
    }

    /** The earlier cue carries the whole sentence and both durations; the fragment becomes invisible. */
    private fun mergeCues(head: SpeechCue, tail: SpeechCue, result: Result) {
        if (head.humanEdited) return
        head.preservedNames = result.preservedNames
        head.output = result.text; head.provider = result.backend; head.reason = "MERGED:API"
        head.extendMachineEnd(tail.endMs)
        tail.output = ""; tail.provider = "merged"; tail.reason = "SUPPRESSED:MERGED"
    }

    private fun <T> launch(pool: java.util.concurrent.ExecutorService, epoch: Long, action: () -> T?): Future<T?>? = try {
        pool.submit(Callable {
            if (closed || epoch != generation || Thread.currentThread().isInterrupted) null else action()
        })
    } catch (_: java.util.concurrent.RejectedExecutionException) { null }

    private fun translateOffline(input: String, src: String, dst: String): Result? {
        val client = if (src == original && dst == target && offlineReady) fallback ?: OfflineTranslatorPool.readyClient(src, dst)
        else OfflineTranslatorPool.readyClient(src, dst)
        if (client != null) {
            val preparedInput = LanguageMap.prepareMlKitInput(input, src)
            runCatching {
                TranslationText.accepted(input, Tasks.await(client.translate(preparedInput), 2500, TimeUnit.MILLISECONDS), dst)?.let {
                    return Result(it, "mlkit")
                }
            }
        }
        // Last-resort local memory fallback: rescue high-confidence seen phrases from earlier sessions.
        if (prefs.vaultEnabled) {
            runCatching { vault.lookupSeenFallback(src, dst, input) }.getOrNull()?.let { seen ->
                TranslationText.accepted(input, seen, dst)?.let { return Result(it, "dictionary") }
            }
        }
        return null
    }

    private fun resolve(cue: SpeechCue, src: String, dst: String, key: String, epoch: Long, urgent: Boolean, skipApi: Boolean=false): Result? {
        val input = NoiseFilter.cleanModelTokens(cue.source)
        lookupLocal(cue, src, dst, key)?.let { return it }
        // Explicit inline cache check order preserved for auditability: cache -> API -> WebTranslate -> ML Kit.
        synchronized(cache) { cache[key] }?.takeUnless { it.backend == "dictionary" && !prefs.vaultEnabled }?.let {
            TranslationText.accepted(input, it.text, dst, it.preservedNames)?.let { clean -> hits.incrementAndGet(); return Result(clean, it.backend, preservedNames = it.preservedNames) }
        }
        if (epoch != generation || closed) return null

        val online = isOnline()
        val short = !musicMode && SubtitleTranslationPolicy.shortRoute(
            cue, src, sourceLines.lowerEntry(cue.startMs)?.value, sourceLines.higherEntry(cue.startMs)?.value
        )
        // Keep normal short utterances off API, and skip network calls completely when offline.
        val primary = if (online && (!short || cue.tries >= 3) && !skipApi && configuredRemote() && health.allowed("api") && input.length <= 500 && cue.endMs > cue.startMs) launch(apiWorker, epoch) {
            val started = System.nanoTime()
            try {
                val context = sourceLines.headMap(cue.startMs).values.toList().takeLast(3).map { it.source to it.output.orEmpty() }
                val after = sourceLines.tailMap(cue.startMs, false).values.take(3).mapIndexed { i, c -> ApiTimedLine("context:$i", c.startMs, c.endMs, c.source) }
                val raw = clientLocal.get()!!.translateLines(listOf(ApiTimedLine("cue", cue.startMs, cue.endMs, input)),src,dst,context,after).single()
                val output = raw.text ?: throw CompatibleApiSubtitleTranslator.Uncertain()
                val head = if (raw.continuesPrevious) mergeTarget(cue) else null
                // Continuation claimed but not mergeable (gap/size/edited): drop it so the fast fallback translates this piece alone.
                if (raw.continuesPrevious && head == null) throw CompatibleApiSubtitleTranslator.Uncertain()
                val result = TranslationText.accepted(if (head != null) head.source + input else input, output, dst, raw.preservedNames) ?: error("Tradução IA inválida")
                if (epoch != generation || Thread.currentThread().isInterrupted) null else {
                    health.success("api"); lastApiFailure = null
                    notify("TRANSLATE endpoint=api ms=${(System.nanoTime() - started) / 1_000_000} ok=true")
                    Result(result, "api", preservedNames = raw.preservedNames, mergeInto = head)
                }
            } catch (_: CompatibleApiSubtitleTranslator.Uncertain) {
                null
            } catch (e: Exception) {
                if (epoch == generation && !Thread.currentThread().isInterrupted) {
                    val http = ApiFailure.http(e)
                    if (!ApiFailure.contentFailure(e)) health.failure("api", http?.status in setOf(401, 403, 429), http?.retryAfterMs ?: 0)
                    cue.reason = "API_${e.message?.take(60)}"; cue.provider = "api"
                    lastApiFailure = ApiFailure.reason(e)
                    notify("TRANSLATE endpoint=api ms=${(System.nanoTime() - started) / 1_000_000} ok=false reason=$lastApiFailure · fallback ativo")
                }
                null
            }
        } else null

        if (!online) {
            return launch(offlineWorker, epoch) { translateOffline(input, src, dst) }?.let { fut ->
                runCatching { fut.get(2800, TimeUnit.MILLISECONDS) }.getOrNull()
            }
        }

        val grace = if (!configuredRemote()) 0L else if (musicMode || backgroundPackageMode) 35_500L else SubtitleWorkPolicy.primaryGrace(cue.startMs, playbackPosition, playbackAdvancing)
        val race = TranslationRace(primaryGraceMs = grace, offlineGraceMs = if (urgent) 1200 else 2500, budgetMs = grace + 7500)
        return race.resolve(primary, fallback = {
            if (primary != null || !configuredRemote() || !health.allowed("api")) null else launch(googleWorker, epoch) {
                try {
                    val r = WebTranslate.translateDetailed(input, src, dst) { message -> if (epoch == generation && !closed) notify(message) }
                    if (r.backend == "echo") Result(r.text, "original", "ECHO:ORIGINAL") else Result(r.text, r.backend)
                } catch (e: Exception) {
                    if (epoch == generation) { cue.reason = "WEB_${e.message?.take(60)}"; cue.provider = "web" }
                    null
                }
            }
        }, offline = {
            launch(offlineWorker, epoch) {
                val client = if (src == original && dst == target && offlineReady) fallback ?: OfflineTranslatorPool.readyClient(src, dst)
                else OfflineTranslatorPool.readyClient(src, dst)
                if (client != null) {
                    val preparedInput = LanguageMap.prepareMlKitInput(input, src)
                    try {
                        TranslationText.accepted(input, Tasks.await(client.translate(preparedInput), 2500, TimeUnit.MILLISECONDS), dst)?.let { return@launch Result(it, "mlkit") }
                    } catch (_: Exception) {}
                }
                if (prefs.vaultEnabled) {
                    runCatching { vault.lookupSeenFallback(src, dst, input) }.getOrNull()?.let { seen ->
                        TranslationText.accepted(input, seen, dst)?.let { return@launch Result(it, "dictionary") }
                    }
                }
                null
            }
        }, current = { epoch == generation && !closed })
    }

    @Synchronized fun ensure(cue: SpeechCue) {
        if (closed || cue.humanEdited) return
        audited.add(cue)
        if (musicMode) {
            if (!valid(cue)) {
                // Promote the currently sung verse (and its next 2 neighbors) to the front of the music queue!
                val neighbors = sourceLines.tailMap(cue.startMs, true).values.take(3).filter { !valid(it) }
                for (item in neighbors.asReversed()) enqueueMusic(item, front = true)
                flushMusic(urgent = true)
            }
            return
        }
        // A visible cue takes precedence over speculative future captions waiting in the queue.
        val srcEff = effectiveSource(cue)
        val key = "$generation:$srcEff:$target:${DictionaryPolicy.normalize(cue.source, srcEff)}"
        queuedJobs[key]?.let { task ->
            if (translationQueue.remove(task) && !translationQueue.offerFirst(task)) {
                queuedJobs.remove(key, task); jobs.remove(key)?.complete(null)
            }
        }
        if (!valid(cue) && !cue.busy && System.currentTimeMillis() >= cue.retryAt) {
            if (cue.reason?.startsWith("PROVISIONAL:") != true) cue.output = null
            submit(cue, urgent = true)
        }
    }

    fun translatePendingBlocking(cues: List<SpeechCue>, epoch: Long = generation): Int {
        registerSourceCues(cues)
        val inFlight = java.util.ArrayDeque<CompletableFuture<Result?>>()
        fun awaitOldest() { runCatching { inFlight.removeFirst().get(45, TimeUnit.SECONDS) } }
        for (cue in cues) {
            if (epoch != generation) break
            if (cue.output != null) continue
            inFlight.addLast(submit(cue, forExport = true))
            while (inFlight.size >= 6) awaitOldest()
        }
        while (inFlight.isNotEmpty()) awaitOldest()
        return cues.count { it.output == null }
    }

    @Synchronized override fun close() {
        closed = true
        runCatching { connectivity?.unregisterNetworkCallback(networkCallback) }
        scheduler.shutdownNow()
        newSession()
        clients.forEach { it.close() }
        fallback = null
        webWorker.shutdownNow()
    }
}
