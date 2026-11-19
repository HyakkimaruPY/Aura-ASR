package com.aurareels.ocrlab

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

data class ChannelPlaylist(
    val id: String,
    val name: String,
    val builtIn: Boolean = false,
    val sources: List<String> = emptyList(),
    val autoEnabled: Boolean = true
)

/** Live channel belonging to a [playlistId] (empty built-in placeholder or a user-created playlist). */
data class LiveChannel(
    val id: Int,
    val url: String,
    val favoriteAt: Long = 0L,
    val verifiedAt: Long = 0L,
    val checkedAt: Long = 0L,
    val failures: Int = 0,
    val validationVersion: Int = 0,
    val uuid: String = UUID.nameUUIDFromBytes("provider:$id".toByteArray()).toString(),
    val name: String = "Canal $id",
    val kind: String = "HLS",
    val headers: Map<String, String> = emptyMap(),
    val manual: Boolean = false,
    val validationState: String = "valid",
    val automaticNumber: Int = 0,
    val playlistId: String = ChannelDiscoverySource.OTTERA_ID
)

/**
 * Channel catalog organized by playlists and backed by in-memory snapshots (`playlistsCache`, `channelsCache`)
 * so UI refreshes and background probe waves never re-parse large JSON strings repeatedly.
 */
class ChannelCatalog(context: Context) {
    private val store = context.getSharedPreferences("aura_live_catalog", Context.MODE_PRIVATE)
    private val legacy = context.getSharedPreferences("aura_channel_source", Context.MODE_PRIVATE)
    private val executor = Executors.newSingleThreadScheduledExecutor { Thread(it, "aura-channel-probe") }
    private val transport = ChannelHttpTransport()
    private var probeGeneration = -1
    private val validator = HlsChannelValidator { url, limit, offset ->
        if (stopped || generation.get() != probeGeneration) throw InterruptedException()
        transport.fetch(url, limit, offset)
    }
    private val generation = AtomicInteger()
    private val testedCount = AtomicLong(store.getLong("tested", 0L))
    private var playlistsCache: List<ChannelPlaylist>? = null
    private var channelsCache: List<LiveChannel>? = null
    private var scheduled: ScheduledFuture<*>? = null
    @Volatile private var stopped = false
    @Volatile private var suspended = true
    @Volatile private var scanning = false
    @Volatile private var cooldownUntil = 0L
    @Volatile private var lastReason = ""
    @Volatile private var roundRobinIndex = 0
    @Volatile var revision = 0L; private set
    @Volatile var onChange: (() -> Unit)? = null
    private val rechecks = ConcurrentLinkedQueue<String>()
    private val examples = ConcurrentLinkedQueue<LiveChannel>()

    companion object {
        const val OTTERA_PLAYLIST_ID = ChannelDiscoverySource.OTTERA_ID
        const val MAX_PLAYLISTS = 50
        private const val VALIDATION_VERSION = 2
        private const val BATCH = 20
        private const val MAX_VALID_PER_PLAYLIST = 120
        private const val MAX_TOTAL_CHANNELS = 400
        private const val REFRESH_MS = 30 * 60_000L
    }

    init {
        migrateAndEnsureDefaultPlaylist()
    }

    @Synchronized private fun migrateAndEnsureDefaultPlaylist() {
        val currentLists = loadPlaylistsInternal().toMutableList()
        val hasOttera = currentLists.any { it.id == OTTERA_PLAYLIST_ID }
        if (!hasOttera) {
            currentLists.add(
                0,
                ChannelPlaylist(
                    id = OTTERA_PLAYLIST_ID,
                    name = ChannelDiscoverySource.OTTERA_NAME,
                    builtIn = true,
                    sources = emptyList(),
                    autoEnabled = false
                )
            )
        } else {
            val idx = currentLists.indexOfFirst { it.id == OTTERA_PLAYLIST_ID }
            val existing = currentLists[idx]
            val fixedSources = emptyList<String>()
            currentLists[idx] = existing.copy(name = ChannelDiscoverySource.OTTERA_NAME, builtIn = true, sources = fixedSources, autoEnabled = false)
        }

        if (!store.getBoolean("playlists_v2_migrated", false)) {
            val prevInput = store.getString("source_input", null) ?: legacy.getString("template", "").orEmpty()
            val parsedPrev = ChannelDiscoverySource.parse(prevInput)
            if (parsedPrev != null && !parsedPrev.isOttera && currentLists.none { prevInput in it.sources }) {
                val migratedId = UUID.randomUUID().toString()
                currentLists.add(
                    ChannelPlaylist(
                        id = migratedId,
                        name = "Fonte salva",
                        builtIn = false,
                        sources = listOf(prevInput),
                        autoEnabled = store.getBoolean("auto_enabled", true)
                    )
                )
                val seed = legacy.getString("first", null)?.toIntOrNull()?.takeIf { it in ChannelDiscoverySource.FIRST..ChannelDiscoverySource.LAST }
                    ?: parsedPrev.seed
                val key = cursorKey(parsedPrev, migratedId)
                if (!store.contains(key)) store.edit().putInt(key, seed).apply()
            }
            store.edit().putBoolean("playlists_v2_migrated", true).apply()
        }
        savePlaylistsInternal(currentLists)
        if (store.getString("selected_playlist", null).isNullOrBlank()) {
            store.edit().putString("selected_playlist", OTTERA_PLAYLIST_ID).apply()
        }
    }

    @Synchronized private fun loadPlaylistsInternal(): List<ChannelPlaylist> {
        playlistsCache?.let { return it }
        val parsed = runCatching {
            val raw = store.getString("channel_playlists", null) ?: return@runCatching emptyList()
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id").ifBlank { return@mapNotNull null }
                val srcs = o.optJSONArray("sources")?.let { sa ->
                    (0 until sa.length()).mapNotNull { sa.optString(it).trim().takeIf(String::isNotBlank) }
                } ?: emptyList()
                ChannelPlaylist(
                    id = id,
                    name = o.optString("name").ifBlank { "Playlist" },
                    builtIn = o.optBoolean("builtIn", id == OTTERA_PLAYLIST_ID),
                    sources = srcs,
                    autoEnabled = if (id == OTTERA_PLAYLIST_ID) false else o.optBoolean("autoEnabled", true)
                )
            }
        }.getOrDefault(emptyList())
        playlistsCache = parsed
        return parsed
    }

    @Synchronized private fun savePlaylistsInternal(lists: List<ChannelPlaylist>) {
        val ottera = lists.firstOrNull { it.id == OTTERA_PLAYLIST_ID }?.copy(autoEnabled=false, sources=emptyList()) ?: ChannelPlaylist(
            id = OTTERA_PLAYLIST_ID,
            name = ChannelDiscoverySource.OTTERA_NAME,
            builtIn = true,
            sources = emptyList(),
            autoEnabled = false
        )
        val ordered = listOf(ottera) + lists.filter { it.id != OTTERA_PLAYLIST_ID }.take(MAX_PLAYLISTS)
        playlistsCache = ordered
        val arr = JSONArray()
        ordered.forEach { pl ->
            arr.put(
                JSONObject()
                    .put("id", pl.id)
                    .put("name", pl.name)
                    .put("builtIn", pl.builtIn)
                    .put("autoEnabled", pl.autoEnabled)
                    .put("sources", JSONArray(pl.sources))
            )
        }
        store.edit().putString("channel_playlists", arr.toString()).apply()
        revision++
    }

    @Synchronized fun playlists(): List<ChannelPlaylist> = loadPlaylistsInternal()

    /** Playlists explicitly created by the user (excludes the built-in OTTera playlist). */
    @Synchronized fun userPlaylists(): List<ChannelPlaylist> = loadPlaylistsInternal().filter { !it.builtIn }

    var selectedPlaylistId: String
        @Synchronized get() {
            val id = store.getString("selected_playlist", OTTERA_PLAYLIST_ID) ?: OTTERA_PLAYLIST_ID
            return if (loadPlaylistsInternal().any { it.id == id }) id else OTTERA_PLAYLIST_ID
        }
        @Synchronized set(value) {
            val validId = if (loadPlaylistsInternal().any { it.id == value }) value else OTTERA_PLAYLIST_ID
            store.edit().putString("selected_playlist", validId).apply()
            revision++
            notifyChange()
        }

    @Synchronized fun selectedPlaylist(): ChannelPlaylist =
        playlists().firstOrNull { it.id == selectedPlaylistId } ?: playlists().first()

    @Synchronized fun createPlaylist(rawName: String): ChannelPlaylist? {
        val clean = rawName.trim().take(60)
        val lists = loadPlaylistsInternal()
        if (clean.isEmpty() || lists.count { !it.builtIn } >= MAX_PLAYLISTS) return null
        val created = ChannelPlaylist(id = UUID.randomUUID().toString(), name = clean, builtIn = false, sources = emptyList(), autoEnabled = true)
        savePlaylistsInternal(lists + created)
        store.edit().putString("selected_playlist", created.id).apply()
        notifyChange()
        return created
    }

    @Synchronized fun renamePlaylist(id: String, rawName: String): Boolean {
        if (id == OTTERA_PLAYLIST_ID) return false
        val clean = rawName.trim().take(60)
        if (clean.isEmpty()) return false
        val lists = loadPlaylistsInternal()
        if (lists.none { it.id == id }) return false
        savePlaylistsInternal(lists.map { if (it.id == id) it.copy(name = clean) else it })
        notifyChange()
        return true
    }

    @Synchronized fun deletePlaylist(id: String): Boolean {
        if (id == OTTERA_PLAYLIST_ID) return false
        val lists = loadPlaylistsInternal()
        if (lists.none { it.id == id }) return false
        savePlaylistsInternal(lists.filterNot { it.id == id })
        save(saved().filterNot { it.playlistId == id })
        if (store.getString("selected_playlist", OTTERA_PLAYLIST_ID) == id) {
            store.edit().putString("selected_playlist", OTTERA_PLAYLIST_ID).apply()
        }
        notifyChange()
        return true
    }

    @Synchronized fun movePlaylist(fromId: String, toId: String) {
        if (fromId == OTTERA_PLAYLIST_ID || toId == OTTERA_PLAYLIST_ID || fromId == toId) return
        val users = userPlaylists().toMutableList()
        val from = users.indexOfFirst { it.id == fromId }
        val to = users.indexOfFirst { it.id == toId }
        if (from < 0 || to < 0) return
        users.add(to, users.removeAt(from))
        val ottera = loadPlaylistsInternal().first { it.id == OTTERA_PLAYLIST_ID }
        savePlaylistsInternal(listOf(ottera) + users)
        notifyChange()
    }

    @Synchronized fun removeSourceFromPlaylist(playlistId: String, rawSource: String): Boolean {
        if (playlistId == OTTERA_PLAYLIST_ID) return false
        val lists = loadPlaylistsInternal()
        val target = lists.firstOrNull { it.id == playlistId } ?: return false
        val updated = target.sources.filterNot { it == rawSource }
        if (updated.size == target.sources.size) return false
        savePlaylistsInternal(lists.map { if (it.id == playlistId) it.copy(sources = updated) else it })
        notifyChange()
        return true
    }

    val isScanning: Boolean get() = scanning
    val isEnabled: Boolean get() = playlists().any { it.autoEnabled && it.sources.isNotEmpty() }
    val sourceInput: String get() = selectedPlaylist().sources.firstOrNull().orEmpty()

    val statusSummary: String get() = playlistStatusSummary(selectedPlaylistId)

    fun playlistStatusSummary(playlistId: String = selectedPlaylistId): String {
        val pl = playlists().firstOrNull { it.id == playlistId } ?: selectedPlaylist()
        val listChannels = channels(pl.id)
        val validCount = listChannels.count { it.validationState == "valid" }
        val hasDiscovery = pl.sources.isNotEmpty()
        val state = when {
            !hasDiscovery -> "${pl.name} · ${listChannels.size} canais"
            suspended && (pl.autoEnabled || rechecks.isNotEmpty()) -> "${pl.name} · extração em pausa"
            scanning -> "${pl.name} · extraindo canais válidos"
            rechecks.isNotEmpty() -> "${pl.name} · revalidando canais"
            !pl.autoEnabled -> "${pl.name} · extração automática pausada"
            System.currentTimeMillis() < cooldownUntil -> "${pl.name} · aguardando resposta da fonte"
            else -> "${pl.name} · extração automática ativa"
        }
        val tail = if (hasDiscovery) " · $validCount canais válidos · ${testedCount.get()} testados" else if (validCount != listChannels.size) " · $validCount verificados" else ""
        return state + tail + if (lastReason.isBlank() || !hasDiscovery) "" else "\n$lastReason"
    }

    private fun isPlayableChannel(c: LiveChannel): Boolean =
        c.manual || (c.validationVersion == VALIDATION_VERSION && c.verifiedAt > 0 && c.failures == 0)

    @Synchronized private fun saved(): List<LiveChannel> {
        channelsCache?.let { return it }
        val loaded = runCatching {
            val a = JSONArray(store.getString("channels", "[]"))
            (0 until a.length()).mapNotNull { i ->
                runCatching {
                    val j = a.getJSONObject(i)
                    val id = j.getInt("id")
                    LiveChannel(
                        id = id,
                        url = j.getString("url"),
                        favoriteAt = j.optLong("favoriteAt"),
                        verifiedAt = j.optLong("verifiedAt"),
                        checkedAt = j.optLong("checkedAt"),
                        failures = j.optInt("failures"),
                        validationVersion = j.optInt("validationVersion"),
                        uuid = j.optString("uuid", UUID.nameUUIDFromBytes("provider:$id".toByteArray()).toString()),
                        name = j.optString("name", "Canal $id"),
                        kind = j.optString("kind", "HLS"),
                        headers = j.optJSONObject("headers")?.let { h -> h.keys().asSequence().associateWith { h.getString(it) } } ?: emptyMap(),
                        manual = j.optBoolean("manual"),
                        validationState = j.optString("validationState", "valid"),
                        automaticNumber = j.optInt("automaticNumber"),
                        playlistId = j.optString("playlistId", OTTERA_PLAYLIST_ID).ifBlank { OTTERA_PLAYLIST_ID }
                    )
                }.getOrNull()
            }
        }.getOrDefault(emptyList())
        channelsCache = loaded
        return loaded
    }

    @Synchronized fun addManual(
        name: String,
        url: String,
        kind: String,
        headers: Map<String, String>,
        validated: Boolean,
        playlistId: String = selectedPlaylistId
    ): Boolean {
        val list = saved()
        val targetPlaylist = if (loadPlaylistsInternal().any { it.id == playlistId }) playlistId else selectedPlaylistId
        if (list.any { it.url == url && it.playlistId == targetPlaylist } || list.size >= MAX_TOTAL_CHANNELS || !StreamResolver.httpUrl(url)) return false
        val number = (list.filter { it.manual && it.playlistId == targetPlaylist }.maxOfOrNull { it.automaticNumber } ?: 0) + 1
        val seq = (list.filter { it.manual }.maxOfOrNull { it.id } ?: -1_000_000) + 1
        val id = if (seq >= 0) (list.minOfOrNull { it.id } ?: 0).coerceAtMost(0) - 1 else seq
        val channel = LiveChannel(
            id = id,
            url = url,
            verifiedAt = if (validated) System.currentTimeMillis() else 0,
            validationVersion = VALIDATION_VERSION,
            uuid = UUID.randomUUID().toString(),
            name = name.trim().take(100).ifBlank { "Canal $number" },
            kind = kind,
            headers = headers,
            manual = true,
            validationState = if (validated) "valid" else "pending",
            automaticNumber = if (name.isBlank()) number else 0,
            playlistId = targetPlaylist
        )
        save(list + channel)
        notifyChange()
        return true
    }

    @Synchronized fun markPlayed(url: String) {
        val current = saved()
        val target = current.firstOrNull { it.url == url } ?: return
        if (target.validationState == "valid" && target.failures == 0 && System.currentTimeMillis() - target.verifiedAt < 60_000L) return
        save(current.map { if (it.url == url) it.copy(verifiedAt = System.currentTimeMillis(), validationState = "valid", failures = 0) else it })
        notifyChange()
    }

    @Synchronized fun knownUrls(): Set<String> = saved().mapTo(HashSet()) { it.url }

    /** Single O(N) RAM pass for rendering all playlist badges without repeating per-playlist filters. */
    @Synchronized fun channelCountsByPlaylist(): Map<String, Int> {
        val counts = HashMap<String, Int>()
        for (c in saved()) {
            if (isPlayableChannel(c)) counts[c.playlistId] = (counts[c.playlistId] ?: 0) + 1
        }
        return counts
    }

    @Synchronized fun channels(playlistId: String? = null): List<LiveChannel> =
        saved()
            .filter { (playlistId == null || it.playlistId == playlistId) && isPlayableChannel(it) }
            .sortedWith(compareByDescending<LiveChannel> { it.favoriteAt }.thenBy { it.id })

    @Synchronized private fun save(list: List<LiveChannel>) {
        val kept = list.sortedWith(
            compareByDescending<LiveChannel> { it.favoriteAt > 0 }
                .thenByDescending { it.manual }
                .thenByDescending { it.favoriteAt }
                .thenByDescending { it.verifiedAt }
                .thenBy { it.id }
        ).take(MAX_TOTAL_CHANNELS)
        channelsCache = kept
        val a = JSONArray()
        kept.forEach { c ->
            a.put(
                JSONObject()
                    .put("id", c.id).put("url", c.url).put("favoriteAt", c.favoriteAt).put("verifiedAt", c.verifiedAt)
                    .put("checkedAt", c.checkedAt).put("failures", c.failures).put("validationVersion", c.validationVersion)
                    .put("uuid", c.uuid).put("name", c.name).put("kind", c.kind).put("headers", JSONObject(c.headers))
                    .put("manual", c.manual).put("validationState", c.validationState)
                    .put("automaticNumber", c.automaticNumber).put("playlistId", c.playlistId)
            )
        }
        store.edit().putString("channels", a.toString()).apply()
        revision++
    }

    fun favorite(channel: LiveChannel) {
        synchronized(this) {
            save(saved().map {
                if (it.url == channel.url && it.playlistId == channel.playlistId) {
                    it.copy(favoriteAt = if (it.favoriteAt > 0) 0 else System.currentTimeMillis())
                } else it
            })
        }
        notifyChange()
    }

    fun remove(channel: LiveChannel) {
        synchronized(this) {
            val excluded = store.getStringSet("excluded", emptySet()).orEmpty().toMutableSet().apply { add("${channel.playlistId}|${channel.url}") }
            store.edit().putStringSet("excluded", excluded).apply()
            save(saved().filterNot { it.url == channel.url && it.playlistId == channel.playlistId })
        }
        notifyChange()
    }

    /** Attaches an automatic discovery source (template or example channel URL) to [playlistId]. */
    fun configureSource(playlistId: String, raw: String): Boolean {
        val source = ChannelDiscoverySource.parse(raw) ?: return false
        val clean = raw.trim()
        synchronized(this) {
            val lists = loadPlaylistsInternal()
            val target = lists.firstOrNull { it.id == playlistId } ?: return false
            val updatedSources = if (source.template in target.sources.mapNotNull { ChannelDiscoverySource.parse(it)?.template }) {
                target.sources
            } else {
                target.sources + clean
            }
            savePlaylistsInternal(lists.map { if (it.id == playlistId) it.copy(sources = updatedSources, autoEnabled = true) else it })
            val cKey = cursorKey(source, playlistId)
            if (!store.contains(cKey)) store.edit().putInt(cKey, source.seed).apply()
            if (!clean.contains("{id}") &&
                !clean.endsWith("playlist.m3u8", ignoreCase = true) && !clean.endsWith("playlist.m3u8?", ignoreCase = true)) {
                examples.add(LiveChannel(source.seed, source.url(source.seed), name = source.defaultChannelName(source.seed), playlistId = playlistId))
            }
            scheduled?.cancel(false)
            scheduled = null
        }
        generation.incrementAndGet()
        transport.cancel()
        cooldownUntil = 0
        lastReason = ""
        schedule(0)
        notifyChange()
        return true
    }

    fun configure(raw: String): Boolean = configureSource(selectedPlaylistId, raw)

    fun setPlaylistAutoEnabled(playlistId: String, enabled: Boolean) {
        synchronized(this) {
            val lists = loadPlaylistsInternal()
            val target = lists.firstOrNull { it.id == playlistId } ?: return
            if (enabled && target.sources.none { ChannelDiscoverySource.parse(it) != null }) return
            savePlaylistsInternal(lists.map { if (it.id == playlistId) it.copy(autoEnabled = enabled) else it })
            if (playlistId == OTTERA_PLAYLIST_ID) store.edit().putBoolean("legacy_default_auto_enabled", false).apply()
            scheduled?.cancel(false)
            scheduled = null
        }
        generation.incrementAndGet()
        if (!isEnabled) {
            rechecks.clear()
            transport.cancel()
        } else {
            schedule(0)
        }
        notifyChange()
    }

    fun setEnabled(enabled: Boolean) = setPlaylistAutoEnabled(selectedPlaylistId, enabled)

    fun setSuspended(value: Boolean) {
        if (suspended == value) {
            if (value) { generation.incrementAndGet(); transport.cancel() }
            return
        }
        suspended = value
        if (value) {
            generation.incrementAndGet()
            synchronized(this) { scheduled?.cancel(false); scheduled = null }
            transport.cancel()
            flushTestedCount()
        } else {
            schedule(600)
        }
        notifyChange()
    }

    fun revalidateAll(playlistId: String? = null) {
        rechecks.clear()
        rechecks.addAll(saved().filterNot { it.manual }.filter { playlistId == null || it.playlistId == playlistId }.map { it.url })
        cooldownUntil = 0
        schedule(0)
        notifyChange()
    }

    /** Playback errors are checked independently; one network failure never deletes a channel. */
    fun revalidate(url: String, allowed: () -> Boolean = { true }) {
        if (stopped) return
        executor.execute {
            val token = generation.get()
            if (stopped || !allowed()) return@execute
            val channel = saved().firstOrNull { it.url == url } ?: return@execute
            if (channel.manual) return@execute
            val result = probe(url, token)
            if (token != generation.get() || !allowed()) return@execute
            applyCheck(channel, result)
            notifyChange()
        }
    }

    private fun probe(url: String, token: Int): ChannelProbe {
        probeGeneration = token
        return validator.validate(url)
    }

    private fun cursorKey(source: ChannelDiscoverySource, playlistId: String = OTTERA_PLAYLIST_ID): String {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest((source.template + "|" + source.width).toByteArray()).take(12).joinToString("") { "%02x".format(it) }
        return if (playlistId == OTTERA_PLAYLIST_ID) "cursor_$hash" else "cursor_${playlistId.take(12)}_$hash"
    }

    @Synchronized private fun schedule(delay: Long) {
        if (stopped || suspended || (!isEnabled && rechecks.isEmpty() && examples.isEmpty()) || scheduled?.isDone == false) return
        scheduled = executor.schedule({ synchronized(this) { scheduled = null }; wave() }, delay, TimeUnit.MILLISECONDS)
    }

    private fun nextCandidateId(source: ChannelDiscoverySource, playlistId: String): Pair<Int, () -> Unit> {
        val cKey = cursorKey(source, playlistId)
        val id = store.getInt(cKey, source.seed).coerceIn(ChannelDiscoverySource.FIRST, ChannelDiscoverySource.LAST)
        return id to { store.edit().putInt(cKey, ChannelDiscoverySource.next(id)).apply() }
    }

    private fun activeDiscoveryTargets(): List<Pair<String, ChannelDiscoverySource>> {
        val activeLists = playlists().filter { it.autoEnabled && it.sources.isNotEmpty() }
        val currentId = selectedPlaylistId
        val prioritized = activeLists.sortedByDescending { it.id == currentId }
        return prioritized.flatMap { pl ->
            pl.sources.mapNotNull { raw -> ChannelDiscoverySource.parse(raw)?.let { pl.id to it } }
        }
    }

    private fun wave() {
        if (stopped || suspended) return
        val wait = cooldownUntil - System.currentTimeMillis()
        if (wait > 0) { schedule(wait); return }
        val token = generation.get(); scanning = true; notifyChange()
        try {
            var checked = 0
            while (checked < BATCH && current(token)) {
                val candidate = examples.poll() ?: break
                val result = probe(candidate.url, token)
                if (!current(token)) return
                checked++; countTest()
                if (result.state == ChannelProbeState.VALID) {
                    synchronized(this) {
                        val existing = saved().firstOrNull { it.url == candidate.url && it.playlistId == candidate.playlistId }
                        val time = System.currentTimeMillis()
                        val resolvedName = result.detectedTitle?.takeIf { it.isNotBlank() } ?: existing?.name ?: candidate.name
                        val entry = (existing ?: candidate).copy(
                            name = resolvedName,
                            verifiedAt = time,
                            checkedAt = time,
                            failures = 0,
                            validationVersion = VALIDATION_VERSION
                        )
                        save(saved().filterNot { it.url == candidate.url && it.playlistId == candidate.playlistId } + entry)
                    }
                    flushTestedCount()
                    notifyChange()
                }
                if (defer(result)) return
            }

            val now = System.currentTimeMillis()
            val due = saved().filter { !it.manual && now - it.checkedAt >= 60_000 && (it.validationVersion != VALIDATION_VERSION || it.failures > 0 || now - it.verifiedAt >= REFRESH_MS) }
                .map { it.url }
            val pending = ArrayList<String>()
            while (pending.size < BATCH - checked) pending.add(rechecks.poll() ?: break)
            if (pending.isEmpty()) pending.addAll(due.take(minOf(10, BATCH - checked)))
            for (url in pending.distinct()) {
                if (!current(token)) return
                val channel = saved().firstOrNull { it.url == url && !it.manual } ?: continue
                val result = probe(url, token)
                if (!current(token)) return
                applyCheck(channel, result); checked++; countTest(); notifyChange()
                if (defer(result)) return
                Thread.sleep(300)
            }

            val targets = activeDiscoveryTargets()
            if (targets.isEmpty()) return
            val excludedSet = store.getStringSet("excluded", emptySet()).orEmpty()
            while (checked < BATCH && current(token)) {
                val autoCounts = saved().filter { !it.manual && isPlayableChannel(it) }.groupingBy { it.playlistId }.eachCount()
                val eligibleTargets = targets.filter { (plId, _) -> (autoCounts[plId] ?: 0) < MAX_VALID_PER_PLAYLIST }
                if (eligibleTargets.isEmpty()) break
                val (playlistId, source) = eligibleTargets[roundRobinIndex++ % eligibleTargets.size]
                val (id, advanceCursor) = nextCandidateId(source, playlistId)
                val candidate = source.url(id)
                if ("$playlistId|$candidate" in excludedSet || candidate in excludedSet) {
                    advanceCursor(); checked++; continue
                }
                val exists = saved().any { it.url == candidate && it.playlistId == playlistId }
                val result = if (exists) ChannelProbe(ChannelProbeState.VALID, "já conhecido") else probe(candidate, token)
                if (!current(token)) return
                checked++; countTest()
                var addedChannel = false
                if (!exists && result.state == ChannelProbeState.VALID) synchronized(this) {
                    val time = System.currentTimeMillis()
                    val name = source.defaultChannelName(id, result.detectedTitle)
                    save(saved() + LiveChannel(id, candidate, verifiedAt = time, checkedAt = time, validationVersion = VALIDATION_VERSION, name = name, playlistId = playlistId))
                    addedChannel = true
                }
                lastReason = if (result.state == ChannelProbeState.RETRY) result.reason else ""
                if (defer(result)) { flushTestedCount(); notifyChange(); return }
                advanceCursor()
                if (addedChannel || checked % 5 == 0) notifyChange()
                if (!exists) Thread.sleep(250)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            scanning = false
            flushTestedCount()
            notifyChange()
            schedule(if (rechecks.isEmpty()) 25_000 else 5000)
        }
    }

    private fun current(token: Int) = !stopped && !suspended && token == generation.get() && !Thread.currentThread().isInterrupted
    private fun countTest() { testedCount.incrementAndGet() }
    private fun flushTestedCount() { store.edit().putLong("tested", testedCount.get()).apply() }

    private fun defer(result: ChannelProbe): Boolean {
        if (result.state != ChannelProbeState.RETRY) return false
        cooldownUntil = System.currentTimeMillis() + maxOf(result.retryAfterMs, 15_000)
        lastReason = result.reason
        return true
    }

    @Synchronized private fun applyCheck(channel: LiveChannel, result: ChannelProbe) {
        val current = saved().firstOrNull { it.url == channel.url && it.playlistId == channel.playlistId } ?: return
        val now = System.currentTimeMillis()
        val next = when (result.state) {
            ChannelProbeState.VALID -> current.copy(
                name = result.detectedTitle?.takeIf { it.isNotBlank() && (current.name.startsWith("Canal ") || current.name.startsWith("Canal ")) } ?: current.name,
                verifiedAt = now,
                checkedAt = now,
                failures = 0,
                validationVersion = VALIDATION_VERSION
            )
            ChannelProbeState.RETRY -> current.copy(checkedAt = now)
            ChannelProbeState.INVALID -> if (current.failures >= 1 && now - current.checkedAt >= 30_000) null else current.copy(checkedAt = now, failures = current.failures + 1)
        }
        save(saved().filterNot { it.url == current.url && it.playlistId == current.playlistId } + listOfNotNull(next))
        lastReason = if (result.state == ChannelProbeState.RETRY) result.reason else ""
    }

    private fun notifyChange() { onChange?.invoke() }

    fun close() {
        stopped = true; generation.incrementAndGet(); transport.cancel()
        flushTestedCount()
        synchronized(this) { scheduled?.cancel(false); scheduled = null }
        executor.shutdownNow()
    }
}
