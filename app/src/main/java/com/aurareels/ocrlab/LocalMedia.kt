package com.aurareels.ocrlab

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract as Docs
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

data class LocalVideo(val uri: String, val name: String, val size: Long, val modified: Long, val directory: Boolean = false)

/** No numeric conversion overflow and no dependence on provider cursor ordering. */
object EpisodeOrder {
    private val seasonEpisode = Regex("(?i)(?:s|t|temporada)\\s*(\\d+).*?(?:ep(?:is[oó]dio)?|e)\\s*(\\d+)|(?:^|\\D)(\\d+)x(\\d+)")
    private val episode = Regex("(?i)(?:ep(?:is[oó]dio)?|e)\\s*(\\d+)|^(\\d+)(?:\\D|$)")

    private class SortKey(
        val item: LocalVideo,
        val dirOrder: Int,
        val hasEp: Int,
        val season: Long,
        val ep: Long,
        val fallbackMod: Long,
        val lowerName: String
    ) : Comparable<SortKey> {
        override fun compareTo(other: SortKey): Int {
            var c = dirOrder.compareTo(other.dirOrder); if (c != 0) return c
            c = hasEp.compareTo(other.hasEp); if (c != 0) return c
            c = season.compareTo(other.season); if (c != 0) return c
            c = ep.compareTo(other.ep); if (c != 0) return c
            c = fallbackMod.compareTo(other.fallbackMod); if (c != 0) return c
            c = lowerName.compareTo(other.lowerName); if (c != 0) return c
            return item.uri.compareTo(other.item.uri)
        }
    }

    fun key(name: String): Pair<Long, Long>? {
        seasonEpisode.find(name)?.let { m ->
            val a = m.groupValues
            return (a[1].ifBlank { a[3] }.toLongOrNull() ?: Long.MAX_VALUE) to
                (a[2].ifBlank { a[4] }.toLongOrNull() ?: Long.MAX_VALUE)
        }
        return episode.find(name)?.let { 1L to (it.groupValues[1].ifBlank { it.groupValues[2] }.toLongOrNull() ?: Long.MAX_VALUE) }
    }

    /** Precomputes regex episode keys once per item (`O(N)` regexes instead of `O(6 N log N)`). */
    fun sorted(items: List<LocalVideo>): List<LocalVideo> {
        if (items.size <= 1) return items.toList()
        return items.map { item ->
            val parsed = key(item.name)
            SortKey(
                item = item,
                dirOrder = if (item.directory) 0 else 1,
                hasEp = if (parsed != null) 0 else 1,
                season = parsed?.first ?: Long.MAX_VALUE,
                ep = parsed?.second ?: Long.MAX_VALUE,
                fallbackMod = if (parsed == null) item.modified else 0L,
                lowerName = item.name.lowercase()
            )
        }.sorted().map { it.item }
    }
}

/** Same SAF browser for videos and music; only storage name and accepted files differ. */
enum class MediaKind(val prefsName: String, val mimePrefix: String, val extensions: Set<String>, val rootName: String) {
    VIDEO("aura_local_media", "video/", setOf("mp4", "mkv", "webm", "avi", "mov", "m4v", "ts", "3gp"), "Vídeos"),
    AUDIO("aura_local_music", "audio/", setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "oga", "opus", "wma", "amr", "mka"), "Músicas")
}

class LocalMedia(private val context: Context, private val kind: MediaKind = MediaKind.VIDEO) {
    private val prefs = context.getSharedPreferences(kind.prefsName, Context.MODE_PRIVATE)
    private val progressCache = ConcurrentHashMap<String, Pair<Long, Long>>()

    var root: String
        get() = prefs.getString("root", "").orEmpty()
        private set(value) { prefs.edit().putString("root", value).apply() }
    var path: List<LocalVideo> = emptyList(); private set

    fun authorize(uri: Uri) {
        context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        root = uri.toString(); path = listOf(rootItem()); savePath()
    }

    private fun rootItem(): LocalVideo {
        val tree = Uri.parse(root)
        val doc = Docs.buildDocumentUriUsingTree(tree, Docs.getTreeDocumentId(tree))
        val name = context.contentResolver.query(doc, arrayOf(Docs.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else kind.rootName
        } ?: kind.rootName
        return LocalVideo(doc.toString(), name, 0, 0, true)
    }

    fun restore() {
        if (root.isEmpty()) return
        path = listOf(rootItem())
        val saved = runCatching { JSONArray(prefs.getString("path", "[]")) }.getOrElse { JSONArray() }
        for (i in 1 until saved.length()) {
            val next = children().firstOrNull { it.directory && it.uri == saved.optString(i) } ?: break
            path = path + next
        }
        savePath()
    }

    fun enter(item: LocalVideo) {
        require(item.directory && children().any { it.uri == item.uri && it.directory })
        path = path + item; savePath()
    }

    fun up(index: Int) { require(index in path.indices); path = path.take(index + 1); savePath() }
    private fun savePath() { prefs.edit().putString("path", JSONArray(path.map { it.uri }).toString()).apply() }

    fun children(): List<LocalVideo> {
        val parent = path.lastOrNull() ?: return emptyList()
        val tree = Uri.parse(root)
        val query = Docs.buildChildDocumentsUriUsingTree(tree, Docs.getDocumentId(Uri.parse(parent.uri)))
        val fields = arrayOf(
            Docs.Document.COLUMN_DOCUMENT_ID, Docs.Document.COLUMN_DISPLAY_NAME,
            Docs.Document.COLUMN_MIME_TYPE, Docs.Document.COLUMN_SIZE, Docs.Document.COLUMN_LAST_MODIFIED
        )
        val list = ArrayList<LocalVideo>()
        context.contentResolver.query(query, fields, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1).orEmpty(); val mime = c.getString(2).orEmpty()
                val dir = mime == Docs.Document.MIME_TYPE_DIR
                if (dir || mime.startsWith(kind.mimePrefix) || name.substringAfterLast('.').lowercase() in kind.extensions) {
                    list.add(LocalVideo(Docs.buildDocumentUriUsingTree(tree, c.getString(0)).toString(), name, c.getLong(3), c.getLong(4), dir))
                }
            }
        }
        return EpisodeOrder.sorted(list)
    }

    fun ordered(items: List<LocalVideo>): List<LocalVideo> {
        val saved = runCatching { JSONArray(prefs.getString("queue:${path.lastOrNull()?.uri}", "[]")) }.getOrElse { JSONArray() }
        val ranks = (0 until saved.length()).associate { saved.optString(it) to it }
        return EpisodeOrder.sorted(items).sortedBy { ranks[it.uri] ?: Int.MAX_VALUE }
    }

    fun saveQueue(items: List<LocalVideo>) {
        prefs.edit().putString("queue:${path.lastOrNull()?.uri}", JSONArray(items.map { it.uri }).toString()).apply()
    }

    fun progress(uri: String): Pair<Long, Long> = progressCache.getOrPut(uri) {
        runCatching {
            val raw = prefs.getString("progress:$uri", null) ?: return@runCatching 0L to 0L
            val o = JSONObject(raw)
            o.optLong("p") to o.optLong("d")
        }.getOrDefault(0L to 0L)
    }

    fun saveProgress(uri: String, position: Long, duration: Long) {
        val safePos = position.coerceAtLeast(0)
        val safeDur = duration.coerceAtLeast(0)
        val prev = progressCache[uri]
        if (prev != null && abs(prev.first - safePos) < 1500L && prev.second == safeDur) return
        progressCache[uri] = safePos to safeDur
        prefs.edit().putString("progress:$uri", JSONObject().put("p", safePos).put("d", safeDur).toString()).apply()
    }
}
