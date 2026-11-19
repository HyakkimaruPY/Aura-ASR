package com.aurareels.ocrlab

import android.content.Context
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset

/** One lyric line; startMs<0 means the source carried no timing. */
data class LyricLine(val startMs: Long, val text: String)

/** Pure parsing helpers (no Android types), so they can be unit-tested on the JVM. */
object LyricsParser {
    private val timeTag = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
    private val wordTag = Regex("<\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?>")
    private val metaTag = Regex("^\\[(?:ti|ar|al|au|by|re|ve|offset|length|la|id|tool|lr|ve):.*]$", RegexOption.IGNORE_CASE)
    private val offsetTag = Regex("^\\[offset:\\s*(-?\\d+)]$", RegexOption.IGNORE_CASE)
    private val wordTokens = Regex("[\\p{L}’']+")
    private val creditLine = Regex(
        "(?i)^\\s*[\\[(]?\\s*(letra|letras|m[uú]sica|composi[cç][aã]o|compositor(?:es)?|autor(?:es)?|arranjo|produ[cç][aã]o|produtor|" +
            "lyrics?|words?|music|written|composed|composer|songwriters?|writers?|produced|producer|arranged|arrangement|" +
            "mixed|mix|mastered|mastering|publisher|publishing|label|cr[eé]ditos?|credits?|" +
            "paroles?|texte|auteur|letra y m[uú]sica|testo|text von|songtext|translat(?:ed|ion)|tradu[cç][aã]o|traduzido|" +
            "歌词|作词|作曲|编曲|作詞|歌詞|編曲|작사|작곡|편곡)(?=\\s|[:：])[^\\n]{0,10}[:：]\\s*\\S.*$")
    private val creditBare = Regex("(?i)^\\s*[\\[(]?\\s*(written|produced|composed|lyrics|music|words|arranged|mixed|letra|m[uú]sica|composi[cç][aã]o)\\s+(by|por|de)\\b.*$")
    private val siteLine = Regex("(?i)(www\\.|https?://|@[a-z0-9_]{3,}\\b|lrc\\s*by|lyrics?\\s*(provided|from)\\b|genius\\.com|musixmatch|lrclib)")

    fun isCredit(line: String): Boolean {
        val t = line.trim()
        if (t.isEmpty()) return false
        return creditLine.matches(t) || creditBare.matches(t) || siteLine.containsMatchIn(t)
    }

    /** Parses LRC (several time tags per line, enhanced word tags, [offset:]). Returns lines sorted by time. */
    fun parseLrc(raw: String): List<LyricLine> {
        val out = ArrayList<LyricLine>()
        var offset = 0L
        raw.replace("\r\n", "\n").replace('\r', '\n').split('\n').forEach { source ->
            val line = source.trim().trimStart('\uFEFF')
            if (line.isEmpty()) return@forEach
            offsetTag.find(line)?.let { offset = it.groupValues[1].toLong(); return@forEach }
            if (metaTag.matches(line)) return@forEach
            val tags = timeTag.findAll(line).toList()
            if (tags.isEmpty()) return@forEach
            val text = wordTag.replace(line.substring(tags.last().range.last + 1), "").trim()
            tags.forEach { m ->
                val frac = m.groupValues[3]
                val ms = when (frac.length) { 0 -> 0L; 1 -> frac.toLong() * 100; 2 -> frac.toLong() * 10; else -> frac.take(3).toLong() }
                out.add(LyricLine(m.groupValues[1].toLong() * 60000 + m.groupValues[2].toLong() * 1000 + ms + offset, text))
            }
        }
        return out.map { it.copy(startMs = it.startMs.coerceAtLeast(0)) }.sortedBy { it.startMs }
    }

    fun looksLikeLrc(raw: String) = raw.lineSequence().any { timeTag.find(it.trim())?.range?.first == 0 }

    fun parsePlain(raw: String): List<LyricLine> =
        raw.replace("\r\n", "\n").replace('\r', '\n').split('\n').map { LyricLine(-1, it.trim()) }

    /**
     * Removes credits/metadata lines and collapses long runs of blank lines. A blank synced line
     * is kept (instrumental gap) so the previous lyric does not stay on screen.
     */
    fun clean(lines: List<LyricLine>): List<LyricLine> {
        val out = ArrayList<LyricLine>()
        lines.forEach { l ->
            if (isCredit(l.text)) return@forEach
            if (l.text.isEmpty() && (out.isEmpty() || out.last().text.isEmpty())) {
                if (l.startMs >= 0 && out.isNotEmpty() && out.last().startMs >= 0) out[out.size - 1] = l   // keep the later gap marker
                return@forEach
            }
            out.add(l)
        }
        while (out.isNotEmpty() && out.last().text.isEmpty() && out.last().startMs < 0) out.removeAt(out.size - 1)
        return out
    }

    fun fromText(raw: String): List<LyricLine> =
        clean(if (looksLikeLrc(raw)) parseLrc(raw) else parsePlain(raw))

    private val LANGUAGE_BAGS = mapOf(
        "pt" to setOf("não", "você", "eu", "meu", "minha", "com", "uma", "para", "mais", "amor", "coração", "está", "são", "tudo", "nada", "então", "porque", "ele", "ela", "nós", "mas", "saudade", "pra"),
        "es" to setOf("que", "los", "las", "una", "por", "con", "pero", "mi", "tu", "mis", "corazón", "amor", "está", "yo", "todo", "nada", "porque", "cuando", "como", "también", "siempre", "contigo"),
        "en" to setOf("the", "you", "and", "i", "my", "me", "your", "to", "of", "in", "love", "is", "it", "that", "we", "don't", "i'm", "with", "baby", "all", "can't", "never", "heart"),
        "fr" to setOf("je", "tu", "les", "des", "une", "est", "pas", "mon", "ma", "pour", "avec", "dans", "que", "amour", "mais", "vous", "nous", "tout", "cœur", "qui", "jamais", "c'est"),
        "de" to setOf("ich", "du", "und", "nicht", "das", "die", "der", "ist", "mit", "ein", "eine", "mein", "dich", "mich", "wir", "auf", "für", "liebe", "wie", "aber", "herz"),
        "it" to setOf("il", "che", "non", "per", "una", "con", "mio", "mia", "sono", "amore", "cuore", "sei", "ti", "mi", "tutto", "come", "ma", "della", "nel", "più", "senza"),
        "tr" to setOf("ve", "bir", "bu", "ne", "için", "ben", "sen", "çok", "gibi", "daha", "var", "yok", "ama", "beni", "seni", "aşk", "kalp"),
        "nl" to setOf("het", "een", "van", "ik", "je", "niet", "dat", "met", "voor", "zijn", "maar", "mijn", "jij", "naar", "ook"),
        "pl" to setOf("nie", "się", "jak", "ale", "mnie", "czy", "już", "tylko", "jest", "tak", "mój", "ciebie", "serce"),
        "id" to setOf("yang", "dan", "aku", "kamu", "tidak", "ini", "itu", "dengan", "untuk", "ada", "cinta", "hati", "kau", "bisa"),
        "vi" to setOf("anh", "em", "của", "không", "và", "có", "người", "yêu", "trong", "một", "những", "đã", "cho", "tôi"),
        "sv" to setOf("jag", "det", "att", "du", "inte", "är", "och", "som", "för", "med", "dig", "mig", "älskar"),
        "ro" to setOf("și", "nu", "în", "cu", "mai", "mă", "te", "din", "iubire", "inimă", "sunt", "doar")
    )

    /** Cheap script/word heuristic. Returns null when unsure (callers may then skip translation). */
    fun guessLanguage(lines: List<LyricLine>): String? {
        val sample = lines.joinToString(" ") { it.text }.take(4000)
        if (sample.isBlank()) return null
        var kana = 0; var hangul = 0; var han = 0; var cyr = 0; var arab = 0; var latin = 0
        var greek = 0; var thai = 0; var deva = 0; var hebrew = 0
        sample.forEach { c ->
            when (c.code) {
                in 0x3040..0x30FF -> kana++
                in 0xAC00..0xD7AF, in 0x1100..0x11FF -> hangul++
                in 0x4E00..0x9FFF -> han++
                in 0x0400..0x04FF -> cyr++
                in 0x0600..0x06FF -> arab++
                in 0x0590..0x05FF -> hebrew++
                in 0x0370..0x03FF -> greek++
                in 0x0E00..0x0E7F -> thai++
                in 0x0900..0x097F -> deva++
                else -> if (c.isLetter()) latin++
            }
        }
        val total = kana + hangul + han + cyr + arab + latin + greek + thai + deva + hebrew
        if (total < 8) return null
        fun big(n: Int) = n * 100 / total >= 30
        if (big(kana) || (han > 0 && kana > 0)) return "ja"
        if (big(hangul)) return "ko"
        if (big(han)) return "zh"
        if (big(cyr)) return "ru"
        if (big(arab)) return "ar"
        if (big(hebrew)) return "he"
        if (big(greek)) return "el"
        if (big(thai)) return "th"
        if (big(deva)) return "hi"
        val lower = sample.lowercase().replace('’', '\'')
        if (lower.count { it in "ăâđêôơưảấầẩẫậắằẳẵặẹẻẽếềểễệỉịọỏốồổỗộớờởỡợụủứừửữựỳỵỷỹ" } >= 3) return "vi"
        if (lower.count { it in "ğşı" } >= 3) return "tr"
        if (lower.count { it in "łśźżńąę" } >= 3) return "pl"
        val words = wordTokens.findAll(lower).map { it.value }.toList()
        val scores = LANGUAGE_BAGS.mapValues { (_, bag) -> words.count { it in bag } }
        val best = scores.maxByOrNull { it.value } ?: return null
        val second = scores.filter { it.key != best.key }.values.maxOrNull() ?: 0
        val minHits = if (words.size in 4..20) 2 else 3
        return if (best.value >= minHits && best.value > second) best.key else null
    }
}

/** Reads lyrics embedded in audio files: ID3v2 (USLT/SYLT), FLAC Vorbis comments and MP4 ©lyr. */
object EmbeddedLyrics {
    private const val MAX_TAG = 4 * 1024 * 1024

    fun read(context: Context, uri: Uri): List<LyricLine> = runCatching {
        val head = ByteArray(16)
        context.contentResolver.openInputStream(uri)?.use { s -> readFully(s, head) } ?: return emptyList()
        val raw: List<LyricLine> = when {
            head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte() ->
                context.contentResolver.openInputStream(uri)?.use { id3(it) }.orEmpty()
            head[0] == 'f'.code.toByte() && head[1] == 'L'.code.toByte() && head[2] == 'a'.code.toByte() && head[3] == 'C'.code.toByte() ->
                context.contentResolver.openInputStream(uri)?.use { flac(it) }.orEmpty()
            head[4] == 'f'.code.toByte() && head[5] == 't'.code.toByte() && head[6] == 'y'.code.toByte() && head[7] == 'p'.code.toByte() ->
                mp4(context, uri)
            else -> emptyList()
        }
        LyricsParser.clean(raw)
    }.getOrDefault(emptyList())

    private fun readFully(s: InputStream, buf: ByteArray): Int {
        var n = 0
        while (n < buf.size) { val r = s.read(buf, n, buf.size - n); if (r < 0) break; n += r }
        return n
    }
    private fun skipFully(s: InputStream, count: Long) {
        var left = count
        while (left > 0) {
            val k = s.skip(left)
            if (k <= 0) { if (s.read() < 0) return; left-- } else left -= k
        }
    }

    // ---- ID3v2 -------------------------------------------------------------------------------
    private fun synchsafe(b: ByteArray, o: Int) = (b[o].toInt() and 0x7f shl 21) or (b[o + 1].toInt() and 0x7f shl 14) or
        (b[o + 2].toInt() and 0x7f shl 7) or (b[o + 3].toInt() and 0x7f)
    private fun be32(b: ByteArray, o: Int) = (b[o].toInt() and 0xff shl 24) or (b[o + 1].toInt() and 0xff shl 16) or
        (b[o + 2].toInt() and 0xff shl 8) or (b[o + 3].toInt() and 0xff)

    internal fun id3(input: InputStream): List<LyricLine> {
        val header = ByteArray(10)
        if (readFully(input, header) < 10) return emptyList()
        val version = header[3].toInt()
        val size = synchsafe(header, 6)
        if (size <= 0 || size > MAX_TAG || version !in 2..4) return emptyList()
        val tag = ByteArray(size)
        readFully(input, tag)
        return id3Frames(tag, version, header[5].toInt())
    }

    internal fun id3Frames(tag: ByteArray, version: Int, flags: Int): List<LyricLine> {
        var pos = 0
        if (version >= 3 && flags and 0x40 != 0 && tag.size >= 4) pos = if (version == 4) synchsafe(tag, 0) else be32(tag, 0) + 4
        var synced: List<LyricLine> = emptyList()
        var unsynced: List<LyricLine> = emptyList()
        val idLen = if (version == 2) 3 else 4
        val headLen = if (version == 2) 6 else 10
        while (pos + headLen <= tag.size) {
            if (tag[pos].toInt() == 0) break
            val id = String(tag, pos, idLen, Charsets.ISO_8859_1)
            val frameSize = when (version) {
                2 -> (tag[pos + 3].toInt() and 0xff shl 16) or (tag[pos + 4].toInt() and 0xff shl 8) or (tag[pos + 5].toInt() and 0xff)
                3 -> be32(tag, pos + 4)
                else -> synchsafe(tag, pos + 4)
            }
            val start = pos + headLen
            if (frameSize < 0 || start + frameSize > tag.size) break
            val body = tag.copyOfRange(start, start + frameSize)
            when (id) {
                "SYLT", "SLT" -> if (synced.isEmpty()) synced = sylt(body)
                "USLT", "ULT" -> if (unsynced.isEmpty()) unsynced = uslt(body)
            }
            pos = start + frameSize
        }
        return if (synced.isNotEmpty()) synced else unsynced
    }

    private fun charsetFor(enc: Int): Charset = when (enc) {
        1 -> Charsets.UTF_16
        2 -> Charsets.UTF_16BE
        3 -> Charsets.UTF_8
        else -> Charsets.ISO_8859_1
    }
    private fun terminatorSize(enc: Int) = if (enc == 1 || enc == 2) 2 else 1
    private fun indexOfTerminator(b: ByteArray, from: Int, enc: Int): Int {
        val t = terminatorSize(enc)
        var i = from
        while (i + t <= b.size) {
            if (t == 1) { if (b[i].toInt() == 0) return i }
            else if (b[i].toInt() == 0 && b[i + 1].toInt() == 0 && (i - from) % 2 == 0) return i
            i += if (t == 2) 2 else 1
        }
        return -1
    }

    private fun uslt(b: ByteArray): List<LyricLine> {
        if (b.size < 5) return emptyList()
        val enc = b[0].toInt() and 0xff
        val descEnd = indexOfTerminator(b, 4, enc)
        if (descEnd < 0) return emptyList()
        val text = String(b, descEnd + terminatorSize(enc), b.size - descEnd - terminatorSize(enc), charsetFor(enc)).trimStart('\uFEFF')
        return LyricsParser.fromText(text)
    }

    private fun sylt(b: ByteArray): List<LyricLine> {
        if (b.size < 7) return emptyList()
        val enc = b[0].toInt() and 0xff
        val format = b[4].toInt() and 0xff
        if (format != 2) return emptyList()               // MPEG-frame stamps cannot be converted without the frame rate
        val descEnd = indexOfTerminator(b, 6, enc)
        if (descEnd < 0) return emptyList()
        var pos = descEnd + terminatorSize(enc)
        val out = ArrayList<LyricLine>()
        while (pos < b.size) {
            val end = indexOfTerminator(b, pos, enc)
            if (end < 0 || end + terminatorSize(enc) + 4 > b.size) break
            val text = String(b, pos, end - pos, charsetFor(enc)).trimStart('\uFEFF').replace("\n", " ").trim()
            val stamp = be32(b, end + terminatorSize(enc)).toLong() and 0xffffffffL
            out.add(LyricLine(stamp, text))
            pos = end + terminatorSize(enc) + 4
        }
        return out.sortedBy { it.startMs }
    }

    // ---- FLAC / Vorbis comments ----------------------------------------------------------------
    internal fun flac(input: InputStream): List<LyricLine> {
        val magic = ByteArray(4)
        if (readFully(input, magic) < 4) return emptyList()
        while (true) {
            val h = ByteArray(4)
            if (readFully(input, h) < 4) return emptyList()
            val last = h[0].toInt() and 0x80 != 0
            val type = h[0].toInt() and 0x7f
            val len = (h[1].toInt() and 0xff shl 16) or (h[2].toInt() and 0xff shl 8) or (h[3].toInt() and 0xff)
            if (type == 4 && len in 1..MAX_TAG) {
                val block = ByteArray(len)
                readFully(input, block)
                return vorbisComments(block)
            }
            skipFully(input, len.toLong())
            if (last) return emptyList()
        }
    }

    internal fun vorbisComments(block: ByteArray): List<LyricLine> {
        val bb = ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN)
        if (bb.remaining() < 4) return emptyList()
        val vendor = bb.int
        if (vendor < 0 || vendor > bb.remaining()) return emptyList()
        bb.position(bb.position() + vendor)
        if (bb.remaining() < 4) return emptyList()
        val count = bb.int
        var synced = ""; var plain = ""
        repeat(count.coerceIn(0, 4096)) {
            if (bb.remaining() < 4) return@repeat
            val n = bb.int
            if (n < 0 || n > bb.remaining()) return@repeat
            val bytes = ByteArray(n); bb.get(bytes)
            val s = String(bytes, Charsets.UTF_8)
            val eq = s.indexOf('=')
            if (eq > 0) when (s.substring(0, eq).uppercase()) {
                "SYNCEDLYRICS" -> synced = s.substring(eq + 1)
                "LYRICS", "UNSYNCEDLYRICS", "UNSYNCED LYRICS" -> if (plain.isEmpty()) plain = s.substring(eq + 1)
            }
        }
        return when {
            synced.isNotBlank() -> LyricsParser.fromText(synced)
            plain.isNotBlank() -> LyricsParser.fromText(plain)
            else -> emptyList()
        }
    }

    // ---- MP4 / M4A (©lyr) ----------------------------------------------------------------------
    private fun mp4(context: Context, uri: Uri): List<LyricLine> {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return emptyList()
        return pfd.use { fd ->
            java.io.FileInputStream(fd.fileDescriptor).channel.use { ch ->
                findLyr(ch, 0, ch.size(), 0)?.let { LyricsParser.fromText(it) } ?: emptyList()
            }
        }
    }

    private fun findLyr(ch: java.nio.channels.FileChannel, from: Long, to: Long, depth: Int): String? {
        if (depth > 6) return null
        var pos = from
        val head = ByteBuffer.allocate(16)
        while (pos + 8 <= to) {
            head.clear(); head.limit(16)
            if (ch.read(head, pos) < 8) return null
            head.flip()
            var size = (head.int.toLong() and 0xffffffffL)
            val type = ByteArray(4); head.get(type)
            var header = 8L
            if (size == 1L && head.remaining() >= 8) { size = head.long; header = 16 }
            else if (size == 0L) size = to - pos
            if (size < header || pos + size > to) return null
            val name = String(type, Charsets.ISO_8859_1)
            when (name) {
                "moov", "udta", "ilst" -> findLyr(ch, pos + header, pos + size, depth + 1)?.let { return it }
                "meta" -> findLyr(ch, pos + header + 4, pos + size, depth + 1)?.let { return it }
                "\u00a9lyr" -> return lyrData(ch, pos + header, pos + size)
            }
            pos += size
        }
        return null
    }

    private fun lyrData(ch: java.nio.channels.FileChannel, from: Long, to: Long): String? {
        val size = (to - from).coerceAtMost(MAX_TAG.toLong()).toInt()
        if (size < 16) return null
        val buf = ByteBuffer.allocate(size)
        ch.read(buf, from)
        val b = buf.array()
        if (String(b, 4, 4, Charsets.ISO_8859_1) != "data") return null
        return String(b, 16, size - 16, Charsets.UTF_8)
    }

    /** For tests: parse an in-memory ID3 tag. */
    internal fun id3FromBytes(bytes: ByteArray) = id3(ByteArrayInputStream(bytes))
}
