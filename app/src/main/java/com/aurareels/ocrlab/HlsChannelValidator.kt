package com.aurareels.ocrlab

import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

enum class ChannelProbeState { VALID, INVALID, RETRY }
data class ChannelProbe(val state: ChannelProbeState, val reason: String, val retryAfterMs: Long = 0, val detectedTitle: String? = null)
data class ChannelHttpSample(val status: Int, val url: String, val bytes: ByteArray, val retryAfterMs: Long = 0, val rangeStart: Long? = null, val contentType: String = "")

/**
 * Checks the actual HLS media, not just HTTP 200 or a line starting with #EXTM3U.
 * One bounded transfer at a time; no video decoder or ASR is started by discovery.
 */
class HlsChannelValidator(private val allowHttp: Boolean = false, private val fetch: (String, Int, Long) -> ChannelHttpSample) {
    private var requests = 0
    private var discoveredTitle: String? = null
    private class Failed(val result: ChannelProbe) : Exception()

    @Synchronized fun validate(url: String): ChannelProbe = try {
        requests = 0
        discoveredTitle = null
        playlist(url, 0)
        ChannelProbe(ChannelProbeState.VALID, "manifesto e mídia válidos", detectedTitle = discoveredTitle)
    } catch (e: Failed) {
        e.result
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        ChannelProbe(ChannelProbeState.RETRY, "busca pausada")
    } catch (_: Exception) {
        ChannelProbe(ChannelProbeState.RETRY, "falha temporária de conexão")
    }

    private fun fail(reason: String): Nothing = throw Failed(ChannelProbe(ChannelProbeState.INVALID, reason))

    private fun read(url: String, limit: Int, offset: Long = 0): ChannelHttpSample {
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        if (++requests > 12) fail("playlist excede o limite de validação")
        val parsed = runCatching { URI(url).toURL() }.getOrNull() ?: fail("URL inválida")
        if ((parsed.protocol != "https" && !(allowHttp && parsed.protocol == "http")) || parsed.host.isBlank()) fail("fonte requer HTTPS")
        val sample = fetch(url, limit, offset)
        if (sample.status == 429 || sample.status in 500..599 || sample.status in setOf(408, 401, 403)) {
            throw Failed(ChannelProbe(ChannelProbeState.RETRY, "HTTP ${sample.status}", maxOf(sample.retryAfterMs, if (sample.status == 429) 60_000L else 15_000L)))
        }
        if (sample.status !in setOf(200, 206)) fail("HTTP ${sample.status}")
        if (offset > 0 && (sample.status != 206 || sample.rangeStart != offset)) fail("servidor não respeita byte-range")
        if (sample.bytes.isEmpty()) fail("resposta vazia")
        return sample
    }

    private fun resolve(base: String, relative: String): String =
        runCatching { resolveUrl(base, relative).toString() }.getOrElse { fail("URI de mídia inválida") }

    private fun attr(line: String, name: String): String? =
        Regex("(?:^|[:,])${Regex.escape(name)}=(?:\"([^\"]*)\"|([^,]*))")
            .find(line)?.let { it.groupValues[1].ifEmpty { it.groupValues[2] }.trim() }

    private fun maybeCaptureTitle(lines: List<String>) {
        if (discoveredTitle != null) return
        for (line in lines) {
            if (line.startsWith("#EXT-X-SESSION-DATA:")) {
                val dataId = attr(line, "DATA-ID")?.lowercase().orEmpty()
                val value = attr(line, "VALUE")?.trim().orEmpty()
                if (value.length in 2..80 && ("title" in dataId || "name" in dataId || "channel" in dataId)) {
                    discoveredTitle = value
                    return
                }
            } else if (line.startsWith("#EXT-X-STREAM-INF:")) {
                val name = attr(line, "NAME")?.trim().orEmpty()
                if (name.length in 3..80 && !TECHNICAL_STREAM_NAME.matches(name)) {
                    discoveredTitle = name
                    return
                }
            }
        }
    }

    private fun playlist(url: String, depth: Int) {
        if (depth > 3) fail("playlist recursiva")
        val response = read(url, 256 * 1024)
        val text = response.bytes.toString(Charsets.UTF_8).trimStart('\uFEFF', ' ', '\r', '\n', '\t')
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.firstOrNull() != "#EXTM3U") fail("não é uma playlist HLS")
        maybeCaptureTitle(lines)

        val variants = ArrayList<Pair<Long, String>>()
        for (i in lines.indices) if (lines[i].startsWith("#EXT-X-STREAM-INF:")) {
            val uri = lines.drop(i + 1).firstOrNull { !it.startsWith('#') } ?: fail("variante sem URL")
            variants.add((attr(lines[i], "BANDWIDTH")?.toLongOrNull() ?: Long.MAX_VALUE) to resolve(response.url, uri))
        }
        if (variants.isNotEmpty()) {
            var retry: ChannelProbe? = null
            for ((_, variant) in variants.sortedBy { it.first }.take(2)) {
                try { playlist(variant, depth + 1); return }
                catch (e: Failed) { if (e.result.state == ChannelProbeState.RETRY) retry = e.result }
            }
            throw Failed(retry ?: ChannelProbe(ChannelProbeState.INVALID, "nenhuma variante reproduzível"))
        }

        data class Segment(val uri: String, val key: String?, val map: String?, val sequence: Long, val range: Pair<Long, Long>?)
        var key: String? = null; var map: String? = null; var sequence = 0L; var expecting = false; var range: String? = null
        val rangeEnds = HashMap<String, Long>()
        val segments = ArrayList<Segment>()
        for (line in lines) when {
            line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> sequence = line.substringAfter(':').toLongOrNull() ?: 0
            line.startsWith("#EXT-X-KEY:") -> key = line
            line.startsWith("#EXT-X-MAP:") -> map = line
            line.startsWith("#EXT-X-BYTERANGE:") -> range = line.substringAfter(':')
            line.startsWith("#EXTINF:") -> expecting = true
            !line.startsWith('#') && expecting -> {
                val uri = resolve(response.url, line)
                val bytes = range?.let { value ->
                    val length = value.substringBefore('@').toLongOrNull() ?: fail("byte-range inválido")
                    val offset = if (value.contains('@')) value.substringAfter('@').toLongOrNull() ?: fail("offset inválido")
                    else rangeEnds[uri] ?: fail("byte-range sem offset anterior")
                    if (length <= 0 || offset < 0 || offset > Long.MAX_VALUE - length) fail("byte-range inválido")
                    rangeEnds[uri] = offset + length; offset to length
                }
                segments.add(Segment(uri, key, map, sequence++, bytes)); expecting = false; range = null
            }
        }
        if (segments.isEmpty()) fail("playlist sem segmentos")
        // The newest complete segment represents what is actually playing, rather than an expired first segment.
        val segment = segments.last()
        var payload = read(segment.uri, minOf(64 * 1024L, segment.range?.second ?: 64 * 1024L).toInt(), segment.range?.first ?: 0).bytes
        val encryption = segment.key
        if (encryption != null && attr(encryption, "METHOD") != "NONE") {
            if (attr(encryption, "METHOD") != "AES-128" || attr(encryption, "KEYFORMAT")?.let { it != "identity" } == true) {
                fail("criptografia não suportada pelo player")
            }
            val uri = attr(encryption, "URI") ?: fail("chave sem URI")
            val bytes = read(resolve(response.url, uri), 17).bytes
            if (bytes.size != 16) fail("chave AES inválida")
            val ivText = attr(encryption, "IV")
            val iv = ByteArray(16)
            if (ivText != null) {
                val hex = ivText.removePrefix("0x").removePrefix("0X")
                if (hex.length > 32 || hex.any { it.digitToIntOrNull(16) == null }) fail("IV inválido")
                val padded = hex.padStart(32, '0')
                for (i in iv.indices) iv[i] = padded.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            } else {
                for (i in 0..7) iv[15 - i] = (segment.sequence ushr (i * 8)).toByte()
            }
            val length = payload.size / 16 * 16
            if (length < 16) fail("segmento criptografado vazio")
            payload = Cipher.getInstance("AES/CBC/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(bytes, "AES"), IvParameterSpec(iv))
                doFinal(payload.copyOf(length))
            }
        }
        segment.map?.let { declaration ->
            val rangeAttr = attr(declaration, "BYTERANGE")
            val length = rangeAttr?.substringBefore('@')?.toLongOrNull() ?: 16 * 1024L
            val offset = rangeAttr?.substringAfter('@', "0")?.toLongOrNull() ?: 0L
            if (length <= 0 || offset < 0) fail("byte-range de mapa inválido")
            val uri = attr(declaration, "URI") ?: fail("mapa sem URI")
            if (!mp4(read(resolve(response.url, uri), minOf(16 * 1024L, length).toInt(), offset).bytes)) {
                fail("inicialização MP4 inválida")
            }
        }
        if (!media(payload)) fail("segmento não contém mídia reconhecível")
    }

    private fun mp4(data: ByteArray): Boolean {
        if (data.size < 8) return false
        return String(data, 4, 4, Charsets.US_ASCII) in setOf("ftyp", "styp", "moof", "moov", "sidx", "mdat")
    }

    private fun media(data: ByteArray): Boolean {
        if (mp4(data)) return true
        // MPEG-TS has sync bytes 188 bytes apart. Require several packets, not one lucky byte.
        for (offset in 0 until minOf(512, data.size - 376)) {
            if (data[offset].toInt() and 255 == 0x47 && data[offset + 188].toInt() and 255 == 0x47 && data[offset + 376].toInt() and 255 == 0x47) return true
        }
        // ID3 by itself is metadata, not proof that the audio is playable.
        if (data.size >= 10 && String(data, 0, 3, Charsets.US_ASCII) == "ID3") {
            val size = ((data[6].toInt() and 127) shl 21) or ((data[7].toInt() and 127) shl 14) or ((data[8].toInt() and 127) shl 7) or (data[9].toInt() and 127)
            val end = 10 + size + if (data[5].toInt() and 16 != 0) 10 else 0
            return end in 10 until data.size && media(data.copyOfRange(end, data.size))
        }
        if (data.size < 32 || data[0].toInt() and 255 != 255 || data[1].toInt() and 0xe0 != 0xe0) return false
        // ADTS: verify the next frame too, instead of accepting two magic bytes.
        if (data[1].toInt() and 0xf6 == 0xf0) {
            val length = ((data[3].toInt() and 3) shl 11) or ((data[4].toInt() and 255) shl 3) or ((data[5].toInt() and 224) ushr 5)
            return length >= 7 && length + 1 < data.size && data[length].toInt() and 255 == 255 && data[length + 1].toInt() and 0xf6 == 0xf0
        }
        // MPEG audio layer III: frame length locates a second sync word.
        val b1 = data[1].toInt() and 255; val b2 = data[2].toInt() and 255
        val version = (b1 ushr 3) and 3; val layer = (b1 ushr 1) and 3
        val rateIndex = (b2 ushr 2) and 3; val bitrateIndex = (b2 ushr 4) and 15
        if (version == 1 || layer != 1 || rateIndex == 3 || bitrateIndex !in 1..14) return false
        val rates = intArrayOf(44100, 48000, 32000)
        val bitrate = if (version == 3) intArrayOf(32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)[bitrateIndex - 1]
        else intArrayOf(8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)[bitrateIndex - 1]
        val rate = rates[rateIndex] / if (version == 3) 1 else if (version == 2) 2 else 4
        val length = (if (version == 3) 144000 else 72000) * bitrate / rate + ((b2 ushr 1) and 1)
        return length + 1 < data.size && data[length].toInt() and 255 == 255 && data[length + 1].toInt() and 0xe0 == 0xe0
    }

    companion object {
        private val TECHNICAL_STREAM_NAME = Regex(
            "^(?:\\d{3,4}[pi]?|\\d+x\\d+|auto|hd|fhd|uhd|sd|low|med|medium|high|main|audio.*|video.*|program.*)$",
            RegexOption.IGNORE_CASE
        )
    }
}

/**
 * Runtime HTTP transport with bounded bodies, redirects, cancellation and Retry-After.
 * Avoids sending `Range` when `offset == 0` so dynamic SSAI manifest generators do not
 * reject playlist requests with HTTP 400/416,
 * while still bounding the read stream to [limit] bytes.
 */
class ChannelHttpTransport(private val headers: Map<String, String> = emptyMap(), private val allowHttp: Boolean = false) {
    @Volatile private var connection: HttpURLConnection? = null
    fun cancel() { connection?.disconnect() }

    fun fetch(url: String, limit: Int, offset: Long = 0): ChannelHttpSample {
        var current = url
        repeat(5) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val conn = (URI(current).toURL().openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false; connectTimeout = 5000; readTimeout = 5000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
                setRequestProperty("Accept", "application/vnd.apple.mpegurl, application/x-mpegURL, */*")
                headers.forEach { (name, value) -> setRequestProperty(name, value) }
                if (offset > 0) setRequestProperty("Range", "bytes=$offset-${offset + limit - 1}")
            }
            connection = conn
            try {
                val code = conn.responseCode
                if (code in setOf(301, 302, 303, 307, 308)) {
                    val baseUrl = URI(current).toURL()
                    val next = resolveUrl(current, conn.getHeaderField("Location") ?: error("Redirect sem Location"))
                    require(next.protocol == "https" || (allowHttp && baseUrl.protocol == "http" && next.protocol == "http"))
                    current = next.toString()
                } else {
                    val retry = RetryAfter.millis(conn.getHeaderField("Retry-After"))
                    if (code !in setOf(200, 206)) return ChannelHttpSample(code, current, byteArrayOf(), retry)
                    val bytes = conn.inputStream.use { input ->
                        val output = java.io.ByteArrayOutputStream(); val block = ByteArray(4096)
                        while (output.size() < limit) {
                            if (Thread.currentThread().isInterrupted) throw InterruptedException()
                            val n = input.read(block, 0, minOf(block.size, limit - output.size())); if (n < 0) break
                            output.write(block, 0, n)
                        }
                        output.toByteArray()
                    }
                    val rangeStart = Regex("bytes (\\d+)-").find(conn.getHeaderField("Content-Range").orEmpty())?.groupValues?.get(1)?.toLongOrNull()
                    return ChannelHttpSample(code, current, bytes, retry, rangeStart, conn.contentType.orEmpty())
                }
            } finally {
                conn.disconnect()
                if (connection === conn) connection = null
            }
        }
        error("Redirecionamentos demais")
    }
}

private fun resolveUrl(base: String, relative: String): URL {
    val b = URI(base)
    val rel = if (b.rawPath.isNullOrEmpty() && !relative.startsWith("/") && !relative.contains("://")) "/$relative" else relative
    return b.resolve(rel).toURL()
}
