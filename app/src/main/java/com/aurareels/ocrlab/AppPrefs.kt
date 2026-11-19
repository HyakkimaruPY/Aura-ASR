package com.aurareels.ocrlab

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** User settings (subtitle look, player delay, noise filter). Persisted in SharedPreferences. */
class AppPrefs(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("aura_lab_settings", Context.MODE_PRIVATE)

    /** Explicitly configured trusted translation gateway. Disabled by default. */
    var translationEndpoint: String
        get() = prefs.getString("translation_endpoint", "").orEmpty()
        set(v) { prefs.edit().putString("translation_endpoint", v.trim()).apply() }
    var translationModel: String
        get() = prefs.getString("translation_model", "").orEmpty()
        set(v) { prefs.edit().putString("translation_model", v.trim()).apply() }

    var menuHintSeen: Boolean
        get() = prefs.getBoolean("menu_hint_seen", false)
        set(v) { prefs.edit().putBoolean("menu_hint_seen", v).apply() }
    /** Last selected main tab (0 Local, 1 Online, 2 Canais, 3 Músicas); reopened on next launch. */
    var lastTab: Int
        get() = prefs.getInt("last_tab", 0).coerceIn(0, 3)
        set(v) { prefs.edit().putInt("last_tab", v.coerceIn(0, 3)).apply() }
    /** Order of the four main tabs (ids 0 Local, 1 Online, 2 Canais, 3 Músicas); kept across launches. */
    var tabOrder: List<Int>
        get() {
            val saved = prefs.getString("tab_order", "0,1,2,3").orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }
            return if (saved.sorted() == listOf(0, 1, 2, 3)) saved else listOf(0, 1, 2, 3)
        }
        set(v) { if (v.sorted() == listOf(0, 1, 2, 3)) prefs.edit().putString("tab_order", v.joinToString(",")).apply() }
    /** Zooms away black bars baked into 16:9 pictures (smart, follows the video). On by default. */
    var autoZoomBars: Boolean
        get() = prefs.getBoolean("auto_zoom_bars", true)
        set(v) { prefs.edit().putBoolean("auto_zoom_bars", v).apply() }
    var queueHintSeen: Boolean
        get() = prefs.getBoolean("queue_hint_seen", false)
        set(v) { prefs.edit().putBoolean("queue_hint_seen", v).apply() }

    /**
     * Vertical and horizontal videos keep separate subtitle looks, so one never overwrites the other.
     * Set by the activity as soon as the picture size is known.
     */
    @Volatile var vertical = false
    private val o: String get() = if (vertical) "v_" else "h_"

    /** Subtitle text size in sp. */
    var textSizeSp: Int
        get() = prefs.getInt(o + "text_size", prefs.getInt("text_size", 18)).coerceIn(12, 34)
        set(v) { prefs.edit().putInt(o + "text_size", v.coerceIn(12, 34)).apply() }

    /** Subtitle background opacity, 0 (none, outline only) .. 100 (solid black). */
    var backgroundPercent: Int
        get() = prefs.getInt(o + "bg_percent", prefs.getInt("bg_percent", 80)).coerceIn(0, 100)
        set(v) { prefs.edit().putInt(o + "bg_percent", v.coerceIn(0, 100)).apply() }

    /** Where the BOTTOM edge of the subtitle sits, as % of the picture height from the top (50..98). */
    var positionPercent: Int
        get() = prefs.getInt(o + "pos_percent", prefs.getInt("pos_percent", 88)).coerceIn(50, 98)
        set(v) { prefs.edit().putInt(o + "pos_percent", v.coerceIn(50, 98)).apply() }

    /** Experimental subtitle-removal mask (per orientation): on/off, centre and height in % of the picture. */
    var maskEnabled: Boolean
        get() = prefs.getBoolean(o + "mask_on", false)
        set(v) { prefs.edit().putBoolean(o + "mask_on", v).apply() }
    var maskCenter: Int
        get() = prefs.getInt(o + "mask_center", 86).coerceIn(5, 98)
        set(v) { prefs.edit().putInt(o + "mask_center", v.coerceIn(5, 98)).apply() }
    var maskHeight: Int
        get() = prefs.getInt(o + "mask_height", 12).coerceIn(3, 30)
        set(v) { prefs.edit().putInt(o + "mask_height", v.coerceIn(3, 30)).apply() }

    /** Vertical video in fullscreen: crop the sides so the picture touches top and bottom. Off by default. */
    var fillVertical: Boolean
        get() = prefs.getBoolean("fill_vertical", false)
        set(v) { prefs.edit().putBoolean("fill_vertical", v).apply() }

    /** Shows the status/event panels below the player. Off by default. */
    var debugMode: Boolean
        get() = prefs.getBoolean("debug_mode", false)
        set(v) { prefs.edit().putBoolean("debug_mode", v).apply() }

    /** How far (s) the visible player stays behind the ASR. Larger = safer for dense/fast languages. */
    var delaySeconds: Int
        get() = prefs.getInt("delay_s", 20).coerceIn(8, 90)
        set(v) { prefs.edit().putInt("delay_s", v.coerceIn(8, 90)).apply() }

    var preferWhisperSmall: Boolean
        get() = prefs.getBoolean("prefer_whisper_small", false)
        set(v) { prefs.edit().putBoolean("prefer_whisper_small", v).apply() }
    var smoothPauses: Boolean
        get() = prefs.getBoolean("smooth_pauses", true)
        set(v) { prefs.edit().putBoolean("smooth_pauses", v).apply() }
    var vaultEnabled: Boolean
        get() = prefs.getBoolean("vault_enabled", true)
        set(v) { prefs.edit().putBoolean("vault_enabled", v).apply() }
    /** 0 hidden; 1 localized event; 2 original laughter. Never sent to translation. */
    var laughterMode: Int
        get() = prefs.getInt("laughter_mode", 0).coerceIn(0, 2)
        set(v) { prefs.edit().putInt("laughter_mode", v.coerceIn(0, 2)).apply() }

    /** Noise filter: 0 permissive, 1 normal, 2 strict. */
    var noiseLevel: Int
        get() = prefs.getInt("noise_level", 1).coerceIn(0, 2)
        set(v) { prefs.edit().putInt("noise_level", v.coerceIn(0, 2)).apply() }

    /** Top subtitles: the original-language line above the video. Off by default (immersion in the translation). */
    var showOriginal: Boolean
        get() = prefs.getBoolean("top_subs", false)
        set(v) { prefs.edit().putBoolean("top_subs", v).apply() }
}

data class HistoryEntry(val url: String, val referer: String, val lastUsed: Long, val positionMs: Long = 0, val durationMs: Long = 0)

/**
 * Most-recent-first list of played links (max 60), backed by an in-memory RAM cache so UI reads
 * (`all()`, `languageFor()`) never re-parse JSON on the main thread and progress updates coalesce cleanly.
 */
class LinkHistory(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("aura_lab_history", Context.MODE_PRIVATE)
    private var entriesCache: List<HistoryEntry>? = null
    private var languagesCache: LinkedHashMap<String, String>? = null
    private companion object { const val KEY = "entries"; const val MAX = 60; const val MAX_LANGUAGES = 240 }

    private fun languageKey(url: String): String =
        if (url.startsWith("content://")) url
        else try {
            val uri = java.net.URI(url)
            "${uri.host.orEmpty().lowercase()}${uri.path.orEmpty()}"
        } catch (_: Exception) { url.substringBefore('?') }

    @Synchronized private fun ensureLanguages(): LinkedHashMap<String, String> {
        languagesCache?.let { return it }
        val map = LinkedHashMap<String, String>()
        runCatching {
            val saved = JSONObject(prefs.getString("languages", "{}") ?: "{}")
            val order = languageOrder()
            order.forEach { k -> if (saved.has(k)) map[k] = saved.optString(k) }
            saved.keys().asSequence().forEach { k -> if (k !in map) map[k] = saved.optString(k) }
        }
        languagesCache = map
        return map
    }

    @Synchronized fun languageFor(url: String): String? =
        ensureLanguages()[languageKey(url)]?.takeIf { LanguageCatalog.get(it) != null }

    /** The next playback of this link/file detects the language from the audio again. */
    @Synchronized fun forgetLanguage(url: String) {
        val map = ensureLanguages()
        val key = languageKey(url)
        if (map.remove(key) != null) {
            persistLanguages(map, null)
        }
    }

    private fun languageOrder(): List<String> = runCatching {
        val arr = JSONArray(prefs.getString("language_order", "[]") ?: "[]")
        (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
    }.getOrDefault(emptyList())

    fun lastLanguage(): String? = prefs.getString("last_language", null)

    @Synchronized private fun persistLanguages(map: LinkedHashMap<String, String>, lastLang: String?) {
        val saved = JSONObject()
        val order = JSONArray()
        map.forEach { (k, v) -> saved.put(k, v); order.put(k) }
        val editor = prefs.edit()
            .putString("languages", saved.toString())
            .putString("language_order", order.toString())
        if (lastLang != null) editor.putString("last_language", lastLang)
        editor.apply()
    }

    /** Keeps remembered languages for Local videos, Music URIs and Online links up to [MAX_LANGUAGES] items. */
    @Synchronized fun rememberLanguage(url: String, language: String) {
        if (LanguageCatalog.get(language) == null) return
        val key = languageKey(url)
        if (key.isBlank()) return
        val map = ensureLanguages()
        if (map[key] == language && map.keys.lastOrNull() == key && prefs.getString("last_language", null) == language) return
        map.remove(key)
        map[key] = language
        val activeOnline = all().map { languageKey(it.url) }.toSet()
        val recentKeys = map.keys.toList()
        val keep = (activeOnline + recentKeys.takeLast(MAX_LANGUAGES)).toSet()
        val iter = map.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            if (entry.key !in keep) iter.remove()
        }
        while (map.size > MAX_LANGUAGES + activeOnline.size) {
            val firstRemovable = map.keys.firstOrNull { it !in activeOnline } ?: break
            map.remove(firstRemovable)
        }
        persistLanguages(map, language)
    }

    @Synchronized fun all(): List<HistoryEntry> {
        entriesCache?.let { return it }
        val raw = prefs.getString(KEY, null) ?: return emptyList<HistoryEntry>().also { entriesCache = it }
        val loaded = try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val url = o.optString("u")
                if (url.isBlank()) null else HistoryEntry(url, o.optString("r"), o.optLong("t"), o.optLong("p"), o.optLong("d"))
            }
        } catch (_: Exception) { emptyList() }
        entriesCache = loaded
        return loaded
    }

    /** Adds (or moves to the top) a link. Same URL replaces the older entry. */
    @Synchronized fun add(url: String, referer: String) {
        val clean = url.trim()
        if (clean.isEmpty()) return
        val current = all()
        val old = current.firstOrNull { it.url == clean }
        val list = ArrayList<HistoryEntry>(minOf(current.size + 1, MAX))
        list.add(HistoryEntry(clean, referer.trim(), System.currentTimeMillis(), old?.positionMs ?: 0, old?.durationMs ?: 0))
        for (item in current) {
            if (item.url != clean && list.size < MAX) list.add(item)
        }
        save(list)
    }

    @Synchronized fun progress(url: String, position: Long, duration: Long) {
        val current = all()
        val safePos = position.coerceAtLeast(0)
        val safeDur = duration.coerceAtLeast(0)
        val existing = current.firstOrNull { it.url == url } ?: return
        // Skip redundant JSON serialization & SharedPreferences disk writes when playback is paused or barely moved.
        if (abs(existing.positionMs - safePos) < 1500L && existing.durationMs == safeDur) return
        save(current.map { if (it.url == url) it.copy(positionMs = safePos, durationMs = safeDur) else it })
    }

    @Synchronized fun remove(url: String) = save(all().filter { it.url != url })

    @Synchronized fun removeChannelEntries(urls: Set<String>) {
        if (urls.isEmpty()) return
        val current = all()
        val filtered = current.filter { it.url !in urls }
        if (filtered.size != current.size) save(filtered)
    }

    @Synchronized fun clear() {
        entriesCache = emptyList()
        languagesCache = LinkedHashMap()
        prefs.edit().remove(KEY).remove("languages").remove("language_order").remove("last_language").apply()
    }

    private fun save(list: List<HistoryEntry>) {
        entriesCache = list
        val array = JSONArray()
        for (e in list) array.put(JSONObject().put("u", e.url).put("r", e.referer).put("t", e.lastUsed).put("p", e.positionMs).put("d", e.durationMs))
        prefs.edit().putString(KEY, array.toString()).apply()
    }
}
