package com.aurareels.ocrlab

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.TimeUnit
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.io.RandomAccessFile

/** Pure JVM transport. Never resumes unverified bytes across servers or different validators. */
class MirrorDownloader(private val selector: MirrorSelector = MirrorSelector()) {
    data class Resource(val mirrors: List<ModelMirror>, val size: Long = 0, val sha256: String = "")
    private data class Probe(val mirror: ModelMirror, val length: Long, val ranged: Boolean, val validator: String?)
    /** The server answered a Range request without honouring it; the caller falls back to one stream. */
    private class RangeUnsupported(message: String) : IOException(message)
    companion object {
        const val SEGMENT_MIN_BYTES = 8L * 1024 * 1024
        const val SEGMENT_TARGET_BYTES = 4L * 1024 * 1024
        const val MAX_SEGMENTS = 4
        /** Parallel connections for a file of [size] bytes; 1 means "do not split". */
        fun segmentCount(size: Long, ranged: Boolean): Int =
            if(!ranged || size < SEGMENT_MIN_BYTES) 1 else (size / SEGMENT_TARGET_BYTES).toInt().coerceIn(2, MAX_SEGMENTS)
        /** Inclusive byte ranges that exactly tile [0,size). */
        fun segmentRanges(size: Long, count: Int): List<LongRange> {
            val per=(size + count - 1) / count
            return (0 until count).mapNotNull { i -> val from=i*per; val to=minOf(size-1,from+per-1); if(from<=to) from..to else null }
        }
    }
    private fun open(address: String, range: String? = null, validator: String? = null): HttpURLConnection {
        var url=address
        repeat(8) {
            val c=URI(url).toURL().openConnection() as HttpURLConnection
            try {
                c.connectTimeout=7000; c.readTimeout=7000; c.instanceFollowRedirects=false
                c.setRequestProperty("Accept-Encoding","identity"); c.setRequestProperty("User-Agent","AuraOcrLab/0.3.3")
                if(range!=null) c.setRequestProperty("Range",range)
                if(validator!=null) c.setRequestProperty("If-Range",validator)
                val code=c.responseCode
                if(code in 300..399) {
                    val next=c.getHeaderField("Location") ?: throw IOException("Redirecionamento sem destino")
                    val baseUri=URI(url)
                    val nextUrl=(if(baseUri.rawPath.isNullOrEmpty() && !next.startsWith("/") && !next.contains("://")) baseUri.resolve("/$next") else baseUri.resolve(next)).toURL()
                    require(nextUrl.protocol in setOf("http","https"))
                    require(!(baseUri.toURL().protocol=="https" && nextUrl.protocol=="http")) { "Redirecionamento inseguro" }
                    url=nextUrl.toString(); c.disconnect()
                } else return c
            } catch(e: Exception) { c.disconnect(); throw e }
        }; throw IOException("Redirecionamentos demais")
    }
    private fun probe(m: ModelMirror): Probe {
        var c=open(m.url,"bytes=0-0")
        try {
            if(c.responseCode in setOf(400,403,405,416)) { c.disconnect(); c=open(m.url) }
            if(c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            val ranged=c.responseCode==206
            val size=if(ranged) c.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull() ?: -1 else c.contentLengthLong
            return Probe(m,size,ranged,c.getHeaderField("ETag")?.takeUnless { it.startsWith("W/") })
        } finally { c.disconnect() }
    }
    /**
     * Fetches [expected] bytes over several simultaneous Range requests into a pre-sized file.
     * Every segment must come back as 206 with the exact Content-Range, so a server that ignores
     * Range, or serves a different file mid-way (If-Range / total length), fails instead of corrupting the output.
     */
    private fun downloadSegmented(m: ModelMirror, p: Probe, expected: Long, count: Int, output: File,
        cancelled: () -> Boolean, status: (String) -> Unit, attempt: Int) {
        RandomAccessFile(output,"rw").use { it.setLength(expected) }
        val ranges=segmentRanges(expected,count)
        val received=AtomicLong(0); val abort=AtomicBoolean(false)
        val pool=Executors.newFixedThreadPool(ranges.size)
        try {
            val jobs=ranges.map { r -> pool.submit(Callable {
                val c=open(m.url,"bytes=${r.first}-${r.last}",p.validator)
                try {
                    if(c.responseCode!=206) throw RangeUnsupported("HTTP ${c.responseCode} sem Range")
                    val match=Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(c.getHeaderField("Content-Range").orEmpty()) ?: throw RangeUnsupported("Content-Range inválido")
                    if(match.groupValues[1].toLong()!=r.first || match.groupValues[2].toLong()!=r.last || match.groupValues[3].toLong()!=expected) throw IOException("Intervalo divergente")
                    var position=r.first
                    RandomAccessFile(output,"rw").use { file ->
                        file.seek(position)
                        c.inputStream.use { input ->
                            val bytes=ByteArray(128*1024)
                            while(true) {
                                if(abort.get() || cancelled()) throw IOException("Cancelado")
                                val n=input.read(bytes); if(n<0) break
                                if(position+n-1>r.last) throw IOException("Tamanho divergente")
                                file.write(bytes,0,n); position+=n; received.addAndGet(n.toLong())
                            }
                        }
                    }
                    if(position!=r.last+1) throw IOException("Segmento truncado (${position-r.first}/${r.last-r.first+1})")
                } finally { c.disconnect() }
            }) }
            val begin=System.nanoTime()
            /** First real failure wins; "Cancelado" from segments we aborted ourselves never hides the cause. */
            fun failure(): Exception? {
                val errors=jobs.filter { it.isDone }.mapNotNull { j -> try { j.get(); null } catch(e: ExecutionException) { (e.cause as? Exception) ?: e } }
                return errors.firstOrNull { it.message!="Cancelado" } ?: errors.firstOrNull()
            }
            try {
                while(!jobs.all { it.isDone }) {
                    if(cancelled() || Thread.currentThread().isInterrupted) throw IOException("Cancelado")
                    failure()?.let { throw it }
                    val done=received.get(); val speed=done/maxOf(1.0,(System.nanoTime()-begin)/1e9)
                    status("${m.id} · ${ranges.size} conexões · tentativa $attempt · ${100*done/expected}% · ${done/1_000_000} MB · ${"%.1f".format(speed/1_000_000)} MB/s")
                    Thread.sleep(250)
                }
                failure()?.let { throw it }
            } catch(e: Exception) { abort.set(true); throw e }
            if(received.get()!=expected) throw IOException("Arquivo truncado (${received.get()}/$expected)")
        } finally { abort.set(true); pool.shutdownNow() }
    }
    fun download(resource: Resource, output: File, lastGood: String? = null,
        cancelled: () -> Boolean = { false }, status: (String) -> Unit = {}, validate: (File) -> Unit = {}): String {
        val candidates=selector.ordered(resource.mirrors,lastGood)
        require(candidates.isNotEmpty()) { "Espelhos em espera; tente novamente em 10 min" }
        val probes=HashMap<String,Probe>(); val failures=ArrayList<String>()
        // Race only probes (never large downloads) and close both response bodies.
        val pool=Executors.newFixedThreadPool(2)
        val completion=ExecutorCompletionService<Pair<ModelMirror,Result<Probe>>>(pool)
        val first=candidates.take(2)
        first.forEach { m -> completion.submit(java.util.concurrent.Callable { m to runCatching { probe(m) } }) }
        var winner: ModelMirror?=null
        try {
            repeat(first.size) {
                if(cancelled()) throw IOException("Cancelado")
                val done=completion.poll(8,TimeUnit.SECONDS) ?: return@repeat
                val (mirror,result)=done.get()
                result.getOrNull()?.let { p ->
                    if(resource.size<=0 || p.length<=0 || p.length==resource.size) {
                        probes[mirror.id]=p; if(winner==null) winner=mirror
                    }
                }
            }
        } finally { pool.shutdownNow() }
        val ordered=winner?.let { w -> listOf(w)+candidates.filter { it!=w } } ?: candidates
        var previous: Probe?=null
        for(m in ordered) {
            for(attempt in 1..2) {
                if(cancelled() || Thread.currentThread().isInterrupted) throw IOException("Cancelado")
                try {
                    val p=probes[m.id] ?: probe(m)
                    val expected=if(resource.size>0) resource.size else p.length
                    if(resource.size>0 && p.length>0 && p.length!=resource.size) throw IOException("Tamanho divergente: ${p.length}/${resource.size}")
                    require(expected<1_200_000_000L) { "Modelo excede limite de tamanho" }
                    output.parentFile?.mkdirs()
                    if(expected>0 && output.parentFile!!.usableSpace < expected*2) throw NoModelSpace("Espaço insuficiente para baixar e extrair")
                    val same=previous?.let { old ->
                        (resource.sha256.isNotEmpty() && resource.size>0) ||
                        (old.mirror.url==m.url && p.validator!=null && old.validator==p.validator && old.length==p.length)
                    } ?: false
                    if(!same || !p.ranged || (expected>0 && output.length()>=expected)) output.delete()
                    previous=p
                    // Several connections when the server proves Range support and the size is known: this is the main speed-up with a single host.
                    var segmented=false
                    val segments=segmentCount(expected,p.ranged)
                    if(segments>1 && output.length()==0L) {
                        try { downloadSegmented(m,p,expected,segments,output,cancelled,status,attempt); segmented=true }
                        catch(e: RangeUnsupported) { output.delete(); status("${m.id}: Range não suportado, usando uma conexão") }
                        catch(e: Exception) { output.delete(); throw e }
                    }
                    if(!segmented) {
                        var offset=output.length()
                        val c=open(m.url,if(offset>0) "bytes=$offset-" else null,if(offset>0) p.validator else null)
                        try {
                            if(c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
                            if(offset>0 && c.responseCode==200) offset=0
                            if(c.responseCode==206) {
                                val header=c.getHeaderField("Content-Range").orEmpty()
                                val match=Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(header) ?: throw IOException("Content-Range inválido")
                                if(match.groupValues[1].toLong()!=offset || (expected>0 && match.groupValues[3].toLong()!=expected)) throw IOException("Intervalo divergente")
                            }
                            val begin=System.nanoTime(); var count=offset; var last=0L
                            c.inputStream.use { input -> java.io.FileOutputStream(output,offset>0).use { out ->
                                val bytes=ByteArray(128*1024)
                                while(true) {
                                    if(cancelled() || Thread.currentThread().isInterrupted) throw IOException("Cancelado")
                                    val n=input.read(bytes); if(n<0) break
                                    count+=n
                                    if(count>1_200_000_000L || (expected>0 && count>expected)) throw IOException("Tamanho divergente")
                                    out.write(bytes,0,n)
                                    val now=System.nanoTime()
                                    if(now-last>400_000_000) {
                                        last=now; val seconds=maxOf(1.0,(now-begin)/1e9); val speed=(count-offset)/seconds
                                        status("${m.id} · tentativa $attempt · ${if(expected>0) "${100*count/expected}% · " else ""}${count/1_000_000} MB · ${"%.1f".format(speed/1_000_000)} MB/s")
                                    }
                                }
                            } }
                            if(expected>0 && count!=expected) throw IOException("Arquivo truncado ($count/$expected)")
                            if(count<128) throw IOException("Resposta vazia ou incompleta")
                        } finally { c.disconnect() }
                    }
                    if(resource.sha256.isNotEmpty()) {
                        val digest=MessageDigest.getInstance("SHA-256")
                        output.inputStream().use { input -> val b=ByteArray(128*1024); while(true) { val n=input.read(b); if(n<0) break; digest.update(b,0,n) } }
                        val actual=digest.digest().joinToString("") { "%02x".format(it) }
                        if(!actual.equals(resource.sha256,true)) { output.delete(); throw IOException("SHA-256 divergente") }
                    }
                    try { validate(output) } catch(e: Exception) { output.delete(); throw IOException("Estrutura do pacote não reconhecida: ${e.message}",e) }
                    selector.success(m); return m.id
                } catch(e: Exception) {
                    if(!selector.rotatable(e,cancelled())) throw e
                    selector.fail(m); failures.add("${m.id} ($attempt): ${e.message?.take(120)}")
                    status(failures.last())
                    if(attempt<2) {
                        repeat(10) { if(cancelled()) throw IOException("Cancelado"); Thread.sleep(100) }
                    }
                }
            }
        }
        throw IOException(failures.joinToString(" · "))
    }
}
