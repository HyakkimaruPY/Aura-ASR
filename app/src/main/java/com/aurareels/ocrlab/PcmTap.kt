package com.aurareels.ocrlab

import android.content.Context
import androidx.media3.common.C
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/** PCM fork at the audio sink input. Each codec buffer carries its real presentation timestamp;
 * sample counting is only used inside that buffer, never as a synthetic live-stream clock. */
class PcmTap(startMs: Long=0L, private val now: ()->Long = { android.os.SystemClock.elapsedRealtime() }, private val receive: (FloatArray,Int,Long)->Unit) {
    private var elapsedMs=startMs.toDouble()
    private val resampler=StreamingPcm16k()
    private var pending=FloatArray(8000)
    private var pendingCount=0
    private var pendingStartMs=0L
    @Volatile private var periodOffsetMs: Long?=null
    @Volatile var latestTimeMs=Long.MIN_VALUE; private set
    @Volatile var lastPacketElapsedMs=0L; private set
    /** Raw codec time allows a missing-PDT HLS scout to be calibrated before consuming audio. */
    @Volatile var rawPacketTimeMs=Long.MIN_VALUE; private set
    @Volatile var rawPacketElapsedMs=0L; private set
    @Volatile var clockReady=false; private set
    @Volatile private var relativeLiveClock=false
    private var previousRawTimeMs=Long.MIN_VALUE
    fun setPeriodOffset(value: Long) { periodOffsetMs=value; relativeLiveClock=false; clockReady=true }
    fun setRelativeLiveOffset(value: Long) { periodOffsetMs=value; relativeLiveClock=true; clockReady=true }
    fun capture(buffer: ByteBuffer, rate: Int, channels: Int, encoding: Int, ptsUs: Long, streamOffsetUs: Long) {
        if (ptsUs == C.TIME_UNSET || streamOffsetUs == C.TIME_UNSET) return
        val raw = (ptsUs-streamOffsetUs)/1000
        rawPacketTimeMs=raw; rawPacketElapsedMs=now()
        // Playlist/period changes may reset PTS even though the channel is still live.
        // Never feed the recognizer with stale timestamps after a backwards jump.
        if (relativeLiveClock && previousRawTimeMs != Long.MIN_VALUE &&
            raw < previousRawTimeMs - 2000) {
            pendingCount=0; resampler.reset(); periodOffsetMs=null; clockReady=false
        }
        previousRawTimeMs=raw
        val offset=periodOffsetMs ?: return
        val time=raw+offset
        if(kotlin.math.abs(time-elapsedMs)>100) { flushPending(); resampler.reset() }
        elapsedMs=time.toDouble()
        val bytes=when(encoding) { C.ENCODING_PCM_16BIT->2; C.ENCODING_PCM_24BIT->3; C.ENCODING_PCM_32BIT,C.ENCODING_PCM_FLOAT->4; else->0 }
        if(bytes==0 || channels !in 1..8 || rate !in 8000..192000) return
        val read=buffer.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN)
        var left=read.remaining()/(bytes*channels)
        while(left>0) {
            val n=min(left,rate/4)
            val mono=FloatArray(n) {
                var sum=0f
                repeat(channels) { sum+=when(encoding) {
                    C.ENCODING_PCM_16BIT -> read.short/32768f
                    C.ENCODING_PCM_24BIT -> { val a=read.get().toInt() and 255; val b=read.get().toInt() and 255; val c=read.get().toInt(); ((c shl 16) or (b shl 8) or a)/8388608f }
                    C.ENCODING_PCM_32BIT -> read.int/2147483648f
                    else -> read.float.coerceIn(-1f,1f)
                } }
                sum/channels
            }
            val pcm=resampler.convert(mono,rate)
            if(pendingCount==0) pendingStartMs=elapsedMs.toLong()
            if(pendingCount+pcm.size>pending.size) pending=pending.copyOf(maxOf(pending.size*2,pendingCount+pcm.size))
            pcm.copyInto(pending,pendingCount); pendingCount+=pcm.size
            elapsedMs+=n*1000.0/rate; left-=n
            if(pendingCount>=4000) flushPending()
        }
    }
    private fun flushPending() {
        if(pendingCount==0) return
        val block=pending.copyOf(pendingCount); pendingCount=0
        latestTimeMs=pendingStartMs+block.size*1000L/16000
        lastPacketElapsedMs=now()
        receive(block, 16000,pendingStartMs)
    }
    fun onQueueEndOfStream() { flushPending() }
    fun reset() {
        pendingCount=0; resampler.reset(); previousRawTimeMs=Long.MIN_VALUE
        if (relativeLiveClock) { periodOffsetMs=null; clockReady=false; rawPacketTimeMs=Long.MIN_VALUE }
    }
}

/** The codec's output stream offset is not a subtitle offset. Subtract it before mapping the
 * PCM PTS to the current HLS period. Capture a retried sink buffer exactly once. */
class TimestampedTapSink(delegate: AudioSink, private val tap: PcmTap) : androidx.media3.exoplayer.audio.ForwardingAudioSink(delegate) {
    private var format: androidx.media3.common.Format?=null
    private var streamOffsetUs=0L
    private var captured: ByteBuffer?=null
    override fun supportsFormat(format: androidx.media3.common.Format)=format.sampleMimeType==androidx.media3.common.MimeTypes.AUDIO_RAW && super.supportsFormat(format)
    override fun getFormatSupport(format: androidx.media3.common.Format)=if(format.sampleMimeType==androidx.media3.common.MimeTypes.AUDIO_RAW) super.getFormatSupport(format) else AudioSink.SINK_FORMAT_UNSUPPORTED
    override fun configure(inputFormat: androidx.media3.common.Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        format=inputFormat; super.configure(inputFormat,specifiedBufferSize,outputChannels)
    }
    override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) { streamOffsetUs=outputStreamOffsetUs; super.setOutputStreamOffsetUs(outputStreamOffsetUs) }
    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if(captured!==buffer) { format?.let { tap.capture(buffer,it.sampleRate,it.channelCount,it.pcmEncoding,presentationTimeUs,streamOffsetUs) }; captured=buffer }
        return super.handleBuffer(buffer,presentationTimeUs,encodedAccessUnitCount).also { if(it) captured=null }
    }
    override fun playToEndOfStream() { tap.onQueueEndOfStream(); super.playToEndOfStream() }
    override fun flush() { captured=null; tap.reset(); super.flush() }
    override fun reset() { captured=null; tap.reset(); super.reset() }
}

class TapRenderersFactory(context: Context, private val tap: PcmTap) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean): AudioSink =
        TimestampedTapSink(DefaultAudioSink.Builder(context).setEnableFloatOutput(false).build(),tap)
}

/** Low-overhead mono resampling (linear interpolation); keeps audio outside UI thread. */
object Pcm16k {
    fun convert(input: FloatArray, sampleRate: Int): FloatArray {
        if (sampleRate == 16000) return input
        if (sampleRate < 8000 || input.isEmpty()) return floatArrayOf()
        val outputCount = (input.size.toLong() * 16000 / sampleRate).toInt()
        val output = FloatArray(outputCount)
        for (i in 0 until outputCount) {
            val source = i.toDouble() * sampleRate / 16000.0
            val index = source.toInt().coerceIn(0, input.lastIndex)
            val next = (index + 1).coerceAtMost(input.lastIndex)
            val portion = (source - index).toFloat()
            output[i] = input[index] + (input[next] - input[index]) * portion
        }
        return output
    }
}

/**
 * Streaming mono resampler to 16 kHz using area averaging (box filter): each output sample is the
 * average of the input span it covers, which also removes most energy above 8 kHz (no aliasing
 * into the speech band). Keeps the fractional phase across buffers, so there is no drift or click.
 * Allocation-light (FloatArray only; the previous version boxed every sample).
 */
class StreamingPcm16k {
    private var rate = 0
    private var tail = FloatArray(0)
    private var next = 0.0            // start of the next output window, in samples relative to tail[0]
    fun reset() { rate = 0; tail = FloatArray(0); next = 0.0 }
    fun convert(input: FloatArray, rate: Int): FloatArray {
        if (rate <= 0 || input.isEmpty()) return FloatArray(0)
        if (rate == 16000) return input
        if (rate != this.rate) { reset(); this.rate = rate }
        val ratio = rate / 16000.0
        val data = FloatArray(tail.size + input.size)
        System.arraycopy(tail, 0, data, 0, tail.size)
        System.arraycopy(input, 0, data, tail.size, input.size)
        val n = data.size
        val capacity = ((n - next) / ratio).toInt() + 1
        if (capacity <= 0) { tail = data; return FloatArray(0) }
        val out = FloatArray(capacity)
        var count = 0
        while (count < capacity) {
            val windowStart = next
            val windowEnd = windowStart + ratio
            if (windowEnd > n + 1e-9) break
            var i = windowStart.toInt()
            val last = kotlin.math.ceil(windowEnd).toInt().coerceAtMost(n)
            var sum = 0.0
            while (i < last) {
                val lo = maxOf(i.toDouble(), windowStart)
                val hi = minOf(i + 1.0, windowEnd)
                sum += data[i] * (hi - lo)
                i++
            }
            out[count++] = (sum / ratio).toFloat()
            next = windowEnd
        }
        val keep = next.toInt().coerceIn(0, n)
        tail = data.copyOfRange(keep, n)
        next -= keep
        return out.copyOf(count)
    }
}
