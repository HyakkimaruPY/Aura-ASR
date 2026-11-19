package com.aurareels.ocrlab

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Completed recognition only. Failed translations remain null and are audited after reopening. */
class LocalSubtitleCache(private val directory: File) {
    data class Entry(val language: String, val cues: List<SpeechCue>)

    private fun file(video: LocalVideo, language: String, target: String, policy: String): File {
        val key = "v1|${video.uri}|${video.size}|${video.modified}|$language|$target|$policy"
        val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(directory, "$hash.json")
    }

    @Synchronized fun load(video: LocalVideo, language: String, target: String, policy: String): Entry? = runCatching {
        val file = file(video, language, target, policy)
        if (!file.isFile || file.length() > 16_000_000) return null
        val json = JSONObject(file.readText())
        if (!json.optBoolean("complete") || json.optString("language") != language) return null
        val array = json.getJSONArray("cues")
        val cues = (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            val start = o.getLong("start"); val end = o.getLong("end")
            require(end > start && start >= 0)
            val origin = o.optLong("origin", start)
            val originEnd = o.optLong("origin_end", end)
            SpeechCue(origin, originEnd, o.getString("source"), o.optString("id", "").takeIf { it.isNotEmpty() }, origin, originEnd).apply {
                if (origin != start || originEnd != end || o.optBoolean("timed", false)) applyTiming(start, end)
                output = if (o.isNull("output")) null else o.getString("output")
                reason = if (o.isNull("reason")) null else o.getString("reason")
                provider = if (o.isNull("provider")) null else o.getString("provider")
                acousticSpeech = o.optBoolean("acoustic_speech", false)
                preservedNames = o.optJSONArray("preserved_names")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
            }
        }
        Entry(language, cues.sortedBy { it.startMs })
    }.getOrNull()

    @Synchronized fun save(video: LocalVideo, language: String, target: String, policy: String, cues: List<SpeechCue>) {
        directory.mkdirs()
        val file = file(video, language, target, policy); val tmp = File(directory, file.name + ".tmp")
        val array = JSONArray()
        cues.forEach { c ->
            array.put(JSONObject().put("start", c.startMs).put("end", c.endMs).put("source", c.source)
                .put("id", c.id).put("origin", c.originStartMs).put("origin_end", c.originEndMs).put("timed", c.humanTimed)
                .put("acoustic_speech", c.acousticSpeech).put("preserved_names", JSONArray(c.preservedNames))
                .put("output", c.output ?: JSONObject.NULL).put("reason", c.reason ?: JSONObject.NULL).put("provider", c.provider ?: JSONObject.NULL))
        }
        tmp.writeText(JSONObject().put("complete", true).put("language", language).put("cues", array).toString())
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        // Bound the cache on disk, independent of the user's video files.
        directory.listFiles()?.filter { it.extension == "json" }?.sortedByDescending { it.lastModified() }?.drop(80)?.forEach { it.delete() }
    }
}
