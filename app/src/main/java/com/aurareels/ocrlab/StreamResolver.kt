package com.aurareels.ocrlab

import java.net.URI

enum class StreamKind(val mime: String?) { HLS("application/x-mpegURL"), DASH("application/dash+xml"), PROGRESSIVE(null), CATALOG(null), UNSUPPORTED(null) }
data class StreamEntry(val name: String,val url: String)
data class ResolvedStream(val url: String,val kind: StreamKind,val entries: List<StreamEntry> = emptyList(),val issue: String?=null,val mediaValidated: Boolean=false)

/** Bounded sniffing, shared by visible playback, audio scout and thumbnails. No URI rewriting. */
class StreamResolver(private val fetch: (String,Int,Long)->ChannelHttpSample) {
    fun resolve(url: String): ResolvedStream {
        require(httpUrl(url)) { "Informe uma URL HTTP(S) válida" }
        val sample=fetch(url,256*1024,0)
        require(sample.status in setOf(200,206)) { "HTTP ${sample.status}: verifique conexão e autenticação" }
        val result=inspect(sample.url,sample.bytes,sample.contentType)
        if(result.kind==StreamKind.HLS) {
            val validated=HlsChannelValidator(allowHttp=true,fetch=fetch).validate(sample.url)
            if(validated.state!=ChannelProbeState.VALID) return result.copy(kind=if(validated.reason.contains("criptografia não suportada")) StreamKind.UNSUPPORTED else result.kind,issue=validated.reason)
            return result.copy(mediaValidated=true)
        }
        return result
    }
    companion object {
        fun httpUrl(raw: String): Boolean = runCatching { val uri=URI(raw); uri.scheme?.lowercase() in setOf("http","https") && !uri.host.isNullOrBlank() && uri.rawUserInfo==null }.getOrDefault(false)
        fun allowedHeaders(raw: String): Map<String,String> {
            if(raw.isBlank()) return emptyMap()
            require(raw.length<=8192) { "Cabeçalhos muito longos" }
            val allowed=setOf("referer","origin","user-agent","authorization","cookie","accept","accept-language")
            return raw.lines().filter { it.isNotBlank() }.associate { line ->
                val split=line.indexOf(':'); require(split>0) { "Use Nome: valor em cada linha" }
                val name=line.take(split).trim(); val value=line.drop(split+1).trim()
                require(name.lowercase() in allowed && value.none { it=='\r' || it=='\n' || it.code<32 }) { "Cabeçalho não permitido" }
                name to value
            }
        }
        fun inspect(url: String,bytes: ByteArray,mime: String=""): ResolvedStream {
            val text=bytes.toString(Charsets.UTF_8).trimStart('\uFEFF',' ','\r','\n','\t')
            if(text.startsWith("#EXTM3U")) {
                if(text.lineSequence().any { it.startsWith("#EXT-X-") }) {
                    if(Regex("METHOD=SAMPLE-AES|KEYFORMAT=\"(?!identity)",RegexOption.IGNORE_CASE).containsMatchIn(text))
                        return ResolvedStream(url,StreamKind.UNSUPPORTED,issue="SAMPLE-AES/DRM requer configuração de licença compatível; PCM para ASR não é garantido.")
                    return ResolvedStream(url,StreamKind.HLS)
                }
                val entries=ArrayList<StreamEntry>(); var name=""
                for(line in text.lines()) {
                    val clean=line.trim()
                    if(clean.startsWith("#EXTINF:")) name=clean.substringAfter(',',"").trim().take(100)
                    else if(clean.isNotBlank() && !clean.startsWith('#')) {
                        val resolved=runCatching { URI(url).resolve(clean).toString() }.getOrNull()
                        if(resolved!=null && httpUrl(resolved)) entries.add(StreamEntry(name,resolved))
                        name=""
                    }
                    if(entries.size>=200) break
                }
                return if(entries.isEmpty()) ResolvedStream(url,StreamKind.UNSUPPORTED,issue="Lista sem canais HTTP(S)") else ResolvedStream(url,StreamKind.CATALOG,entries.distinctBy { it.url })
            }
            if(Regex("<(?:[A-Za-z0-9_]+:)?MPD(?:\\s|>)").containsMatchIn(text)) {
                if(text.contains("ContentProtection")) return ResolvedStream(url,StreamKind.UNSUPPORTED,issue="DASH protegido exige configuração de licença DRM; ASR pode não acessar o áudio.")
                return ResolvedStream(url,StreamKind.DASH) // Mark valid only after a decoded frame.
            }
            val mp4=bytes.size>=12 && bytes.copyOfRange(4,8).toString(Charsets.US_ASCII) in setOf("ftyp","styp","moov","moof")
            val ts=bytes.size>376 && bytes[0]==0x47.toByte() && bytes[188]==0x47.toByte() && bytes[376]==0x47.toByte()
            val magic=bytes.take(4).toByteArray().toString(Charsets.US_ASCII)
            val audio=magic.startsWith("ID3") || magic in setOf("OggS","fLaC","RIFF") || (bytes.size>2 && bytes[0].toInt() and 255==255 && bytes[1].toInt() and 0xe0==0xe0)
            val webm=bytes.size>4 && bytes.take(4).map { it.toInt() and 255 }==listOf(0x1a,0x45,0xdf,0xa3)
            return if(mp4 || ts || audio || webm) ResolvedStream(url,StreamKind.PROGRESSIVE,mediaValidated=true)
            else ResolvedStream(url,StreamKind.UNSUPPORTED,issue="Resposta sem mídia reconhecível (${mime.substringBefore(';').take(60)}). Verifique o link ou os cabeçalhos.")
        }
    }
}
