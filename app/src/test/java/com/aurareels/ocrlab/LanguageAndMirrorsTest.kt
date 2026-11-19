package com.aurareels.ocrlab

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.text.Collator
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

/** Language normalization, vote tracking, catalog invariants, archive path safety and segmented mirror downloads. */
class LanguageMapTest {
    @Test fun canonicalFoldsAliasesAndRegions() {
        assertEquals("yue", LanguageMap.canonical("yue"))
        assertEquals("zh", LanguageMap.canonical("zh-TW"))
        assertEquals("zh", LanguageMap.canonical("CMN"))
        assertEquals("pt", LanguageMap.canonical("pt_BR"))
        assertEquals("en", LanguageMap.canonical(" EN-us "))
        assertEquals("ja", LanguageMap.canonical("jpn"))
        assertEquals("he", LanguageMap.canonical("iw"))
    }

    @Test fun canonicalAcceptsWhisperTokensAndNoise() {
        assertEquals("en", LanguageMap.canonical("<|en|>"))
        assertEquals("ja", LanguageMap.canonical("<|ja|>\n"))
        assertEquals("zh", LanguageMap.canonical("\"zh\""))
        assertEquals("yue", LanguageMap.canonical("<|yue|>"))
        assertEquals("", LanguageMap.canonical("<||>"))
    }

    @Test fun canonicalResolvesLanguageNamesReturnedByDetector() {
        // Seen on a pt-BR phone: bruto: 'inglês' / 'coreano'
        assertEquals("en", LanguageMap.canonical("inglês"))
        assertEquals("en", LanguageMap.canonical("ingles"))
        assertEquals("en", LanguageMap.canonical("English"))
        assertEquals("ko", LanguageMap.canonical("coreano"))
        assertEquals("ko", LanguageMap.canonical("Korean"))
        assertEquals("ja", LanguageMap.canonical("japonês"))
        assertEquals("zh", LanguageMap.canonical("Chinese"))
        assertEquals("jv", LanguageMap.canonical("jw"))
        assertEquals("jv", LanguageMap.canonical("Javanese"))
        assertEquals("", LanguageMap.canonical("blablabla"))
    }

    @Test fun canonicalRejectsUnusableInput() {
        for (bad in listOf(null, "", "   ", "und", "unknown", "nospeech", "12", "e!")) {
            assertEquals("'$bad' deve ser vazio", "", LanguageMap.canonical(bad))
        }
    }

    @Test fun pickInstalledOnlyReturnsInstalledPack() {
        val installed = listOf("en", "zh")
        assertNull(LanguageMap.pickInstalled("yue", installed))
        assertEquals("en", LanguageMap.pickInstalled("en-US", installed))
        assertNull(LanguageMap.pickInstalled("ja", installed))
        assertNull(LanguageMap.pickInstalled("", installed))
        assertNull(LanguageMap.pickInstalled("en", emptyList()))
    }

    @Test fun backendTags() {
        assertEquals("zh-CN", LanguageMap.google("zh"))
        assertEquals("zh-CN", LanguageMap.google("yue"))
        assertEquals("ja", LanguageMap.google("ja"))
        assertEquals("pt-BR", LanguageMap.myMemory("pt"))
        assertEquals("zh-CN", LanguageMap.myMemory("zh"))
        assertEquals("fr", LanguageMap.myMemory("fr"))
        assertEquals("zh", LanguageMap.mlKit("yue"))
    }

    @Test fun displayNameFallsBackToCode() {
        assertEquals("japonês", LanguageMap.displayName("ja"))
        assertEquals("cantonês", LanguageMap.displayName("yue"))
        assertEquals("xx", LanguageMap.displayName("xx"))
    }
}

class LanguageVoteTrackerTest {
    @Test fun twoMatchingVotesConfirm() {
        val t = LanguageVoteTracker()
        assertNull(t.add("ja"))
        assertEquals("ja", t.add("ja"))
    }

    @Test fun nonConsecutiveVotesStillConfirmWhenLeading() {
        val t = LanguageVoteTracker()
        assertNull(t.add("en")); assertNull(t.add("fr"))
        assertEquals("en", t.add("en"))
    }

    @Test fun cantoneseAndMandarinRemainDistinct() {
        val t = LanguageVoteTracker()
        t.add("yue")
        assertNull(t.add("zh"))
    }

    @Test fun tieDoesNotConfirm() {
        val t = LanguageVoteTracker()
        t.add("en"); t.add("fr"); t.add("en")   // en leads 2-1
        assertEquals("en", t.confirmed())
        val u = LanguageVoteTracker()
        u.add("en"); u.add("en"); u.add("fr"); u.add("fr")
        assertNull("2-2 não pode confirmar", u.confirmed())
    }

    @Test fun blankVotesAreIgnored() {
        val t = LanguageVoteTracker()
        assertNull(t.add("")); assertNull(t.add(null)); assertNull(t.add("und"))
        assertEquals(0, t.total)
        assertNull(t.add("it"))
        assertEquals("it", t.add("it"))
    }

    @Test fun exhaustsWithoutWinner() {
        val t = LanguageVoteTracker()
        for (c in listOf("en", "fr", "ja", "tr", "it")) t.add(c)
        assertTrue(t.exhausted())
        assertNull(t.confirmed())
    }

    @Test fun unsupportedLanguageNeedsThreeVotes() {
        val t = LanguageVoteTracker(supported = { it == "en" })
        assertNull(t.add("jw")); assertNull(t.add("jw"))   // 2 votes of Javanese on an intro: not enough
        assertEquals("jv", t.add("jw"))                    // 3 consistent votes are believed
        val u = LanguageVoteTracker(supported = { it == "en" })
        u.add("jw"); u.add("jw")
        assertNull(u.add("en"))                            // en 1 vs jv 2
        assertNull(u.add("en"))                            // tie 2-2
        assertEquals("en", u.add("en"))                    // en 3 > jv 2
    }

    @Test fun oldVotesExpire() {
        val t = LanguageVoteTracker(maxVotes = 5)
        for (c in listOf("ja", "ko", "tr", "fr", "it")) t.add(c)
        assertEquals(0, t.count("ja") - 1)                 // ja still in window
        t.add("en")                                        // window slides: ja leaves
        assertEquals(0, t.count("ja"))
        assertEquals("en", t.add("en"))
    }

    @Test fun resetClears() {
        val t = LanguageVoteTracker()
        t.add("en"); t.add("en"); t.reset()
        assertNull(t.confirmed()); assertEquals(0, t.total)
    }
}

class ArchivePathsTest {
    @Test fun commonRootDetectsSingleFolder() {
        assertEquals("vosk-model-small-en-us-0.15",
            ArchivePaths.commonRoot(listOf("vosk-model-small-en-us-0.15/", "vosk-model-small-en-us-0.15/am/final.mdl")))
        assertEquals("m", ArchivePaths.commonRoot(listOf("./m/conf/model.conf")))
        assertNull(ArchivePaths.commonRoot(listOf("am/final.mdl", "conf/model.conf")))
        assertNull(ArchivePaths.commonRoot(listOf("a/x", "b/y")))
        assertNull(ArchivePaths.commonRoot(emptyList()))
    }

    @Test fun stripRoot() {
        assertEquals("am/final.mdl", ArchivePaths.stripRoot("m/am/final.mdl", "m"))
        assertEquals("am/final.mdl", ArchivePaths.stripRoot("./m/am/final.mdl", "m"))
        assertEquals("am/final.mdl", ArchivePaths.stripRoot("m\\am\\final.mdl", "m"))
        assertNull(ArchivePaths.stripRoot("m/", "m"))
        assertNull(ArchivePaths.stripRoot("other/am/final.mdl", "m"))
        assertEquals("am/final.mdl", ArchivePaths.stripRoot("am/final.mdl", ""))
    }

    @Test fun traversalIsRejected() {
        val base = java.io.File(System.getProperty("java.io.tmpdir"), "aura-test-base").apply { mkdirs() }
        assertTrue(ArchivePaths.isInside(base, "am/final.mdl"))
        assertFalse(ArchivePaths.isInside(base, "../evil.txt"))
        assertFalse(ArchivePaths.isInside(base, "am/../../evil.txt"))
        assertFalse(ArchivePaths.isInside(base, ""))
    }

    @Test fun voskCompleteness() {
        val files = mapOf("am/final.mdl" to 5_000_000L, "conf/model.conf" to 100L, "graph/words.txt" to 10L)
        assertTrue(ArchivePaths.voskLooksComplete({ it in files }, { files[it] ?: 0L }))
        val noGraph = files - "graph/words.txt"
        assertFalse(ArchivePaths.voskLooksComplete({ it in noGraph }, { noGraph[it] ?: 0L }))
        val tiny = files + ("am/final.mdl" to 10L)
        assertFalse(ArchivePaths.voskLooksComplete({ it in tiny }, { tiny[it] ?: 0L }))
    }
}

class MirrorSegmentsTest {
    private val data=ByteArray(9_000_000) { (it*31%251).toByte() }
    private fun runServer(test: (HttpServer,String,java.io.File)->Unit) {
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0); val dir=Files.createTempDirectory("aura-seg").toFile()
        server.start()
        try { test(server,"http://127.0.0.1:${server.address.port}",java.io.File(dir,"download")) } finally { server.stop(0); dir.deleteRecursively() }
    }
    private fun ranged(x: HttpExchange,bytes: ByteArray,total: Long=bytes.size.toLong()) {
        x.responseHeaders.set("ETag","\"seg\"")
        val range=Regex("bytes=(\\d+)-(\\d+)").matchEntire(x.requestHeaders.getFirst("Range").orEmpty())
        if(range==null) { x.sendResponseHeaders(200,bytes.size.toLong()); x.responseBody.use { it.write(bytes) }; return }
        val from=range.groupValues[1].toInt(); val to=minOf(range.groupValues[2].toInt(),bytes.size-1)
        x.responseHeaders.set("Content-Range","bytes $from-$to/$total")
        x.sendResponseHeaders(206,(to-from+1).toLong()); x.responseBody.use { it.write(bytes,from,to-from+1) }
    }
    @Test fun rangesTileTheFileExactly() {
        listOf(8_388_608L to 2,9_000_000L to 3,12_345_679L to 4,100L to 4).forEach { (size,count) ->
            val parts=MirrorDownloader.segmentRanges(size,count)
            assertEquals(0L,parts.first().first); assertEquals(size-1,parts.last().last)
            parts.zipWithNext().forEach { (a,b) -> assertEquals(a.last+1,b.first) }
            assertEquals(size,parts.sumOf { it.last-it.first+1 })
        }
    }
    @Test fun smallOrNonRangedFilesAreNotSplit() {
        assertEquals(1,MirrorDownloader.segmentCount(1_000_000,true)); assertEquals(1,MirrorDownloader.segmentCount(50_000_000,false))
        assertTrue(MirrorDownloader.segmentCount(9_000_000,true) in 2..MirrorDownloader.MAX_SEGMENTS)
        assertEquals(MirrorDownloader.MAX_SEGMENTS,MirrorDownloader.segmentCount(500_000_000,true))
    }
    @Test fun largeFileUsesParallelRangesAndMatches() = runServer { server,url,out ->
        val partial=AtomicInteger()
        server.createContext("/m") { x -> if(x.requestHeaders.getFirst("Range")!="bytes=0-0" && x.requestHeaders.getFirst("Range")!=null) partial.incrementAndGet(); ranged(x,data) }
        val hash=java.security.MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
        MirrorDownloader().download(MirrorDownloader.Resource(listOf(ModelMirror("a","$url/m")),data.size.toLong(),hash),out)
        assertArrayEquals(data,out.readBytes()); assertTrue("ranged requests: ${partial.get()}",partial.get()>=2)
    }
    @Test fun serverIgnoringRangeFallsBackToOneStream() = runServer { server,url,out ->
        server.createContext("/m") { x ->
            if(x.requestHeaders.getFirst("Range")=="bytes=0-0") ranged(x,data)
            else { x.sendResponseHeaders(200,data.size.toLong()); x.responseBody.use { it.write(data) } }
        }
        MirrorDownloader().download(MirrorDownloader.Resource(listOf(ModelMirror("a","$url/m")),data.size.toLong()),out)
        assertArrayEquals(data,out.readBytes())
    }
    @Test fun inconsistentTotalRotatesToHealthyMirror() = runServer { server,url,out ->
        server.createContext("/bad") { x -> if(x.requestHeaders.getFirst("Range")=="bytes=0-0") ranged(x,data) else ranged(x,data,total=data.size+1L) }
        server.createContext("/ok") { x -> ranged(x,data) }
        val winner=MirrorDownloader().download(MirrorDownloader.Resource(listOf(ModelMirror("bad","$url/bad",1),ModelMirror("ok","$url/ok",2)),data.size.toLong()),out)
        assertEquals("ok",winner); assertArrayEquals(data,out.readBytes())
    }
}

class LanguageCatalogTest {
    @Test fun sortedInPortugueseWithAccents() { val c=Collator.getInstance(Locale.forLanguageTag("pt-BR")); assertTrue(LanguageCatalog.entries.zipWithNext().all { (a,b)->c.compare(a.namePt,b.namePt)<=0 }) }
    @Test fun noPortugueseOrDuplicates() { val codes=LanguageCatalog.entries.map { it.code }; assertFalse("pt" in codes); assertEquals(codes.size,codes.toSet().size); assertTrue(codes.size>=35) }
    @Test fun everyLanguageHasEngineAndTranslation() { assertTrue(LanguageCatalog.entries.all { it.engines.isNotEmpty() && it.googleCode.isNotBlank() && it.namePt.isNotBlank() }) }
    @Test fun cantoneseNotWhisperBase() { assertEquals(listOf(AsrBackend.SENSEVOICE),LanguageCatalog.get("yue")!!.engines); assertFalse("yue" in LanguageCatalog.whisperLanguages) }
    @Test fun fastLanguagesUseSenseVoiceFirst() { for(l in listOf("ja","zh","ko","en","yue")) assertEquals(AsrBackend.SENSEVOICE,LanguageCatalog.get(l)!!.engines.first()) }
    @Test fun turkishWhisperBeforeVosk() { assertEquals(listOf(AsrBackend.WHISPER,AsrBackend.VOSK),LanguageCatalog.get("tr")!!.engines) }
}

class MirrorSelectorTest {
    private val a=ModelMirror("a","https://a/model",10); private val b=ModelMirror("b","https://b/model",20)
    @Test fun remembersLastSuccess() { assertEquals(b,MirrorSelector().ordered(listOf(a,b),"b").first()) }
    @Test fun cooldownAndExpiry() { var now=0L; val s=MirrorSelector { now }; repeat(3){ s.fail(a) }; assertEquals(listOf(b),s.ordered(listOf(a,b),null)); now=600001; assertEquals(a,s.ordered(listOf(a,b),null).first()) }
    @Test fun diskAndCancellationNeverRotate() { val s=MirrorSelector(); assertFalse(s.rotatable(NoModelSpace("full"),false)); assertFalse(s.rotatable(java.io.IOException("network"),true)); assertTrue(s.rotatable(java.io.IOException("HTTP 403"),false)) }
}

class MirrorDownloaderTest {
    private fun runServer(test: (HttpServer,String,ByteArray,java.io.File)->Unit) {
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        val bytes=ByteArray(8192) { (it%251).toByte() }
        val dir=Files.createTempDirectory("aura-mirrors").toFile()
        server.start()
        try { test(server,"http://127.0.0.1:${server.address.port}",bytes,java.io.File(dir,"download")) }
        finally { server.stop(0); dir.deleteRecursively() }
    }
    private fun good(server: HttpServer, path: String, bytes: ByteArray) { server.createContext(path) { x ->
        val ranged=x.requestHeaders.getFirst("Range")=="bytes=0-0"
        x.responseHeaders.set("ETag","\"test\"")
        if(ranged) { x.responseHeaders.set("Content-Range","bytes 0-0/${bytes.size}"); x.sendResponseHeaders(206,1); x.responseBody.use { it.write(bytes,0,1) } }
        else { x.sendResponseHeaders(200,bytes.size.toLong()); x.responseBody.use { it.write(bytes) } }
    } }
    @Test fun http403RotatesAutomatically() = runServer { server,url,data,out ->
        server.createContext("/bad") { x -> x.sendResponseHeaders(403,-1); x.close() }; good(server,"/ok",data)
        val winner=MirrorDownloader().download(MirrorDownloader.Resource(listOf(ModelMirror("bad","$url/bad"),ModelMirror("good","$url/ok")),data.size.toLong()),out)
        assertEquals("good",winner); assertArrayEquals(data,out.readBytes())
    }
    @Test fun badChecksumRotatesToCorrectMirror() = runServer { server,url,data,out ->
        val corrupt=data.clone().also { it[0]=99 }; good(server,"/bad",corrupt); good(server,"/ok",data)
        val hash=MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
        val winner=MirrorDownloader().download(MirrorDownloader.Resource(listOf(ModelMirror("bad","$url/bad"),ModelMirror("good","$url/ok")),data.size.toLong(),hash),out)
        assertEquals("good",winner); assertArrayEquals(data,out.readBytes())
    }
    @Test fun rangeUnsupportedFallsBackToFullBody() = runServer { server,url,data,out ->
        server.createContext("/range") { x ->
            if(x.requestHeaders.getFirst("Range")!=null) { x.sendResponseHeaders(416,-1); x.close() }
            else { x.sendResponseHeaders(200,data.size.toLong()); x.responseBody.use { it.write(data) } }
        }
        MirrorDownloader().download(MirrorDownloader.Resource(listOf(ModelMirror("a","$url/range"))),out)
        assertArrayEquals(data,out.readBytes())
    }
    @Test fun invalidArchiveRotates() = runServer { server,url,data,out ->
        good(server,"/bad",data.clone().also { it[0]=99 }); good(server,"/ok",data)
        val winner=MirrorDownloader().download(MirrorDownloader.Resource(listOf(ModelMirror("bad","$url/bad"),ModelMirror("ok","$url/ok"))),out,
            validate={ require(it.readBytes()[0]==data[0]) { "structure" } })
        assertEquals("ok",winner)
    }
    @Test fun cancellationStopsBeforeDownloading() = runServer { _,url,_,out ->
        try { MirrorDownloader().download(MirrorDownloader.Resource(listOf(ModelMirror("a","$url/no"))),out,cancelled={true}); fail("must cancel") }
        catch(e: Exception) { assertTrue(e.message.orEmpty().contains("Cancelado")) }
    }
}
