package com.aurareels.ocrlab

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

data class PlaylistItem(val id: String, val title: String, val url: String, val referer: String)
data class OnlinePlaylist(val id: String, val name: String, val items: List<PlaylistItem>)

/**
 * Online playlists backed by an in-memory snapshot so UI reads (`all()`) never perform synchronous
 * disk I/O or JSON parsing on the main thread, while writes remain crash-safe (tmp + atomic move).
 */
class PlaylistStore(private val file: File) {
    companion object { const val MAX_ITEMS = 200; const val MAX_LISTS = 50 }

    private var cached: List<OnlinePlaylist>? = null

    @Synchronized fun all(): List<OnlinePlaylist> {
        cached?.let { return it }
        val loaded = runCatching {
            val root = JSONArray(if (file.exists()) file.readText() else "[]")
            (0 until root.length()).mapNotNull { i ->
                val o = root.optJSONObject(i) ?: return@mapNotNull null
                val items = o.optJSONArray("items") ?: JSONArray()
                OnlinePlaylist(
                    o.optString("id").ifBlank { return@mapNotNull null },
                    o.optString("name"),
                    (0 until items.length()).mapNotNull { j ->
                        val entry = items.optJSONObject(j) ?: return@mapNotNull null
                        val url = entry.optString("url")
                        if (url.isBlank()) null else PlaylistItem(
                            entry.optString("id").ifBlank { UUID.randomUUID().toString() },
                            entry.optString("title").ifBlank { url },
                            url,
                            entry.optString("referer")
                        )
                    }
                )
            }
        }.getOrDefault(emptyList())
        cached = loaded
        return loaded
    }

    private fun write(lists: List<OnlinePlaylist>) {
        val root = JSONArray()
        lists.forEach { l ->
            root.put(JSONObject().put("id", l.id).put("name", l.name).put("items", JSONArray().also { a ->
                l.items.forEach { a.put(JSONObject().put("id", it.id).put("title", it.title).put("url", it.url).put("referer", it.referer)) }
            }))
        }
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(root.toString())
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        cached = lists
    }

    @Synchronized fun create(name: String): OnlinePlaylist? {
        val clean = name.trim().take(60)
        val lists = all()
        if (clean.isEmpty() || lists.size >= MAX_LISTS) return null
        val made = OnlinePlaylist(UUID.randomUUID().toString(), clean, emptyList())
        write(lists + made)
        return made
    }

    @Synchronized fun delete(id: String) = write(all().filterNot { it.id == id })

    @Synchronized fun rename(id: String, name: String) {
        val clean = name.trim().take(60)
        if (clean.isEmpty()) return
        write(all().map { if (it.id == id) it.copy(name = clean) else it })
    }

    /** Returns false for duplicates (same URL) or a full list. */
    @Synchronized fun add(id: String, title: String, url: String, referer: String): Boolean {
        val clean = url.trim()
        if (!StreamResolver.httpUrl(clean) || (referer.isNotBlank() && !StreamResolver.httpUrl(referer))) return false
        var added = false
        write(all().map { l ->
            if (l.id != id || l.items.size >= MAX_ITEMS || l.items.any { it.url == clean } || clean.isEmpty()) l
            else {
                added = true
                l.copy(items = l.items + PlaylistItem(UUID.randomUUID().toString(), title.trim().ifBlank { clean }.take(80), clean, referer.trim()))
            }
        })
        return added
    }

    @Synchronized fun removeItem(id: String, itemId: String) =
        write(all().map { if (it.id == id) it.copy(items = it.items.filterNot { i -> i.id == itemId }) else it })

    @Synchronized fun move(id: String, itemId: String, delta: Int) = write(all().map { l ->
        if (l.id != id) return@map l
        val from = l.items.indexOfFirst { it.id == itemId }
        val to = from + delta
        if (from < 0 || to !in l.items.indices) l
        else l.copy(items = l.items.toMutableList().also { it.add(to, it.removeAt(from)) })
    })

    /** Drag-and-drop: puts the playlist where the target playlist is. */
    @Synchronized fun moveList(id: String, targetId: String) {
        val lists = all()
        val from = lists.indexOfFirst { it.id == id }
        val to = lists.indexOfFirst { it.id == targetId }
        if (from < 0 || to < 0 || from == to) return
        write(lists.toMutableList().also { it.add(to, it.removeAt(from)) })
    }

    /** Drag-and-drop inside a playlist: puts the item where the target item is. */
    @Synchronized fun moveItemTo(id: String, itemId: String, targetId: String) = write(all().map { l ->
        if (l.id != id) return@map l
        val from = l.items.indexOfFirst { it.id == itemId }
        val to = l.items.indexOfFirst { it.id == targetId }
        if (from < 0 || to < 0 || from == to) l
        else l.copy(items = l.items.toMutableList().also { it.add(to, it.removeAt(from)) })
    })

    /** First unfinished item (watched = 95%), or the first item when all are finished. */
    fun firstUnfinished(list: OnlinePlaylist, progress: (String) -> Pair<Long, Long>): PlaylistItem? =
        list.items.firstOrNull { val (p, d) = progress(it.url); d <= 0 || p.toDouble() / d < 0.95 } ?: list.items.firstOrNull()
}
