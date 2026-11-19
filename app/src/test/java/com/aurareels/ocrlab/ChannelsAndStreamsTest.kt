package com.aurareels.ocrlab

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test

/** HLS/AES-128/fMP4/byte-range media validation, metadata capture and OTTera/template channel discovery. */
class ChannelValidationTest {
    private val base="https://provider.test/123/index.m3u8"
    private fun ts()=ByteArray(188*4).apply { for(i in 0..3) this[i*188]=0x47 }
    private fun sample(url: String, body: String, status: Int=200)=ChannelHttpSample(status,url,body.toByteArray())
    private fun validator(values: Map<String,ChannelHttpSample>, calls: MutableList<String> = arrayListOf())=HlsChannelValidator { url,_,_ -> calls.add(url); values[url] ?: ChannelHttpSample(404,url,byteArrayOf()) }
    @Test fun manifestHeaderAloneNeverAddsAChannel() {
        assertEquals(ChannelProbeState.INVALID,validator(mapOf(base to sample(base,"#EXTM3U\n"))).validate(base).state)
        assertEquals(ChannelProbeState.INVALID,validator(mapOf(base to sample(base,"<html>online</html>"))).validate(base).state)
    }
    @Test fun mediaIsFetchedBeforeSuccess() {
        val segment="https://provider.test/123/latest.ts"; val calls=arrayListOf<String>()
        val values=mapOf(base to sample(base,"#EXTM3U\n#EXTINF:5,\nlatest.ts"),segment to ChannelHttpSample(200,segment,ts()))
        assertEquals(ChannelProbeState.VALID,validator(values,calls).validate(base).state)
        assertEquals(listOf(base,segment),calls)
    }
    @Test fun htmlMasqueradingAsSegmentFails() {
        val segment="https://provider.test/123/latest.ts"
        val values=mapOf(base to sample(base,"#EXTM3U\n#EXTINF:5,\nlatest.ts"),segment to sample(segment,"<html>Not found</html>"))
        assertEquals(ChannelProbeState.INVALID,validator(values).validate(base).state)
    }
    @Test fun masterPlaylistResolvesRelativeVariantAndSegmentAgainstFinalRedirectUrl() {
        val final="https://cdn.test/live/master.m3u8"; val child="https://cdn.test/live/low/index.m3u8"; val segment="https://cdn.test/live/seg.ts"
        val values=mapOf(base to sample(final,"#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=128000\nlow/index.m3u8"),child to sample(child,"#EXTM3U\n#EXTINF:5,\n../seg.ts"),segment to ChannelHttpSample(200,segment,ts()))
        assertEquals(ChannelProbeState.VALID,validator(values).validate(base).state)
    }
    @Test fun latestCompleteSegmentIsCheckedInsteadOfExpiredFirstSegment() {
        val latest="https://provider.test/123/new.ts"
        val values=mapOf(base to sample(base,"#EXTM3U\n#EXTINF:5,\nold.ts\n#EXTINF:5,\nnew.ts"),latest to ChannelHttpSample(200,latest,ts()))
        val calls=arrayListOf<String>();assertEquals(ChannelProbeState.VALID,validator(values,calls).validate(base).state)
        assertFalse(calls.any { it.endsWith("old.ts") })
    }
    @Test fun inaccessibleKeyIsTemporaryAndNeverValid() {
        val segment="https://provider.test/123/seg.ts";val key="https://provider.test/123/key"
        val values=mapOf(base to sample(base,"#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\n#EXTINF:5,\nseg.ts"),segment to ChannelHttpSample(200,segment,ts()),key to ChannelHttpSample(403,key,byteArrayOf()))
        assertEquals(ChannelProbeState.RETRY,validator(values).validate(base).state)
    }
    @Test fun encryptedSegmentIsActuallyDecryptedForValidation() {
        val key=ByteArray(16) { it.toByte() };val iv=ByteArray(16).apply { this[15]=42 }
        val encrypted=Cipher.getInstance("AES/CBC/PKCS5Padding").run { init(Cipher.ENCRYPT_MODE,SecretKeySpec(key,"AES"),IvParameterSpec(iv));doFinal(ts()) }
        val segment="https://provider.test/123/seg.ts";val keyUrl="https://provider.test/123/key"
        val values=mapOf(base to sample(base,"#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:42\n#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\n#EXTINF:5,\nseg.ts"),segment to ChannelHttpSample(200,segment,encrypted),keyUrl to ChannelHttpSample(200,keyUrl,key))
        assertEquals(ChannelProbeState.VALID,validator(values).validate(base).state)
    }
    @Test fun serverRateLimitHonorsRetryAfter() {
        val probe=validator(mapOf(base to ChannelHttpSample(429,base,byteArrayOf(),120_000))).validate(base)
        assertEquals(ChannelProbeState.RETRY,probe.state);assertEquals(120_000L,probe.retryAfterMs)
    }
    @Test fun forbiddenAndUnavailableAreNotPermanentMissingChannels() {
        for(status in listOf(401,403,408,500,503)) assertEquals(ChannelProbeState.RETRY,validator(mapOf(base to ChannelHttpSample(status,base,byteArrayOf()))).validate(base).state)
        assertEquals(ChannelProbeState.INVALID,validator(emptyMap()).validate(base).state)
    }
    @Test fun fmp4ChecksInitAndFragment() {
        fun box(kind: String)=byteArrayOf(0,0,0,16)+kind.toByteArray()+ByteArray(8)
        val seg="https://provider.test/123/seg.m4s";val init="https://provider.test/123/init.mp4"
        val values=mapOf(base to sample(base,"#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:5,\nseg.m4s"),seg to ChannelHttpSample(200,seg,box("moof")),init to ChannelHttpSample(200,init,box("ftyp")))
        assertEquals(ChannelProbeState.VALID,validator(values).validate(base).state)
        assertEquals(ChannelProbeState.INVALID,validator(values-init).validate(base).state)
    }
    @Test fun byteRangeUsesTheCorrectOffsetAndRejectsAnIgnoringServer() {
        val calls=arrayListOf<Long>();val segment="https://provider.test/123/all.ts"
        fun source(honor: Boolean)=HlsChannelValidator { url,_,offset ->
            if(url==base) sample(base,"#EXTM3U\n#EXTINF:5,\n#EXT-X-BYTERANGE:752@1000\nall.ts\n#EXTINF:5,\n#EXT-X-BYTERANGE:752\nall.ts")
            else { assertEquals(segment,url);calls.add(offset);ChannelHttpSample(if(honor) 206 else 200,url,ts(),rangeStart=if(honor) offset else null) }
        }
        assertEquals(ChannelProbeState.VALID,source(true).validate(base).state);assertEquals(listOf(1752L),calls)
        assertEquals(ChannelProbeState.INVALID,source(false).validate(base).state)
    }
    @Test fun recursiveAndUnsupportedPlaylistsFailBoundedly() {
        val probe=validator(mapOf(base to sample(base,"#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=100\nindex.m3u8"))).validate(base)
        assertEquals(ChannelProbeState.INVALID,probe.state)
        val media="https://provider.test/123/a.ts"
        val values=mapOf(base to sample(base,"#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://drm\"\n#EXTINF:4,\na.ts"),media to ChannelHttpSample(200,media,ts()))
        assertEquals(ChannelProbeState.INVALID,validator(values).validate(base).state)
    }
    @Test fun id3MetadataWithoutAudioIsRejected() {
        val segment="https://provider.test/123/a.aac"
        val values=mapOf(base to sample(base,"#EXTM3U\n#EXTINF:5,\na.aac"),segment to ChannelHttpSample(200,segment,byteArrayOf(73,68,51,4,0,0,0,0,0,0)))
        assertEquals(ChannelProbeState.INVALID,validator(values).validate(base).state)
    }
    @Test fun capturesDescriptiveChannelTitleAndIgnoresTechnicalVariantLabels() {
        val child="https://provider.test/123/low.m3u8"; val segment="https://provider.test/123/seg.ts"
        val sessionValues=mapOf(
            base to sample(base,"#EXTM3U\n#EXT-X-SESSION-DATA:DATA-ID=\"com.example.channel.name\",VALUE=\"Anime TV\"\n#EXT-X-STREAM-INF:BANDWIDTH=800000,NAME=\"1080p\"\nlow.m3u8"),
            child to sample(child,"#EXTM3U\n#EXTINF:5,\nseg.ts"),
            segment to ChannelHttpSample(200,segment,ts())
        )
        val probe1=validator(sessionValues).validate(base)
        assertEquals(ChannelProbeState.VALID,probe1.state)
        assertEquals("Anime TV",probe1.detectedTitle)

        val techOnlyValues=mapOf(
            base to sample(base,"#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000,NAME=\"1080p\"\nlow.m3u8"),
            child to sample(child,"#EXTM3U\n#EXTINF:5,\nseg.ts"),
            segment to ChannelHttpSample(200,segment,ts())
        )
        val probe2=validator(techOnlyValues).validate(base)
        assertEquals(ChannelProbeState.VALID,probe2.state)
        assertNull(probe2.detectedTitle)
    }
}

class ChannelDiscoverySourceTest {
    @Test fun templateNeedsNoNumericBounds() {
        val source=ChannelDiscoverySource.parse("https://provider.test/channel/{id}/index.m3u8")!!
        assertEquals(ChannelDiscoverySource.FIRST,source.seed)
        assertEquals("https://provider.test/channel/547/index.m3u8",source.url(547))
    }
    @Test fun numericPathAndQueryAreInferredWithoutReplacingM3u8Digits() {
        assertEquals("https://p.test/live/{id}.m3u8",ChannelDiscoverySource.parse("https://p.test/live/543.m3u8")!!.template)
        assertEquals(543,ChannelDiscoverySource.parse("https://p.test/live/543.m3u8")!!.seed)
        assertEquals("https://p.test/play?id={id}&token=abc",ChannelDiscoverySource.parse("https://p.test/play?id=123&token=abc")!!.template)
    }
    @Test fun leadingZeroesAndOtherNumericParametersArePreserved() {
        val source=ChannelDiscoverySource.parse("https://p.test/000123/index.m3u8?quality=720")!!
        assertEquals(123,source.seed);assertEquals("https://p.test/000124/index.m3u8?quality=720",source.url(124))
    }
    @Test fun authorizedSourceTemplatesParseWithoutBundledProviderEndpoints() {
        val net=ChannelDiscoverySource.parse("https://media.example.test/playlist.m3u8?network_id=3021")!!
        assertEquals(3021,net.seed)
        assertEquals("https://media.example.test/playlist.m3u8?network_id={id}",net.template)
        assertEquals("Canal 3021",net.defaultChannelName(3021))
        assertEquals("Retro Cinema",net.defaultChannelName(3021,"Retro Cinema"))
        assertFalse(net.isOttera)

        val withQuery=ChannelDiscoverySource.parse("https://media.example.test/playlist.m3u8?network_id=13168&quality=1#live")!!
        assertEquals(13168,withQuery.seed)
        assertEquals("https://media.example.test/playlist.m3u8?network_id={id}&quality=1",withQuery.template)
        val blank=ChannelDiscoverySource.parse("https://media.example.test/playlist.m3u8?network_id=")!!
        assertEquals(0,blank.seed)
        assertEquals("https://media.example.test/playlist.m3u8?network_id={id}",blank.template)
        assertNull(ChannelDiscoverySource.parse("ottera"))
        assertNull(ChannelDiscoverySource.parse("https://media.example.test/playlist.m3u8"))
        assertTrue(ChannelDiscoverySource.OTTERA_PRIORITY_IDS.isEmpty())
        assertTrue(ChannelDiscoverySource.OTTERA_TEMPLATE.isEmpty())
    }
    @Test fun invalidSourcesFailAndCursorWrapsWithoutOverflow() {
        for(source in listOf("", "http://p.test/{id}.m3u8", "https://p.test/a.m3u8", "https://p.test/{id}/{id}")) assertNull(source,ChannelDiscoverySource.parse(source))
        assertEquals(ChannelDiscoverySource.FIRST,ChannelDiscoverySource.next(ChannelDiscoverySource.LAST))
        assertEquals(124,ChannelDiscoverySource.next(123))
    }
}
