package com.aurareels.ocrlab

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.Closeable
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

/** Streaming whole-file decode: bounded PCM blocks, no scout, no wall-clock playback pacing. */
class LocalAudioDecoder(private val context: Context) : Closeable {
    private val worker=java.util.concurrent.ThreadPoolExecutor(1,1,0,java.util.concurrent.TimeUnit.MILLISECONDS,
        java.util.concurrent.LinkedBlockingQueue<Runnable>(), { Thread(it,"aura-local-audio").apply { priority=Thread.NORM_PRIORITY-1 } })
    private val epoch=AtomicInteger()
    @Volatile var paused=false
    @Volatile var decodedThroughMs=0L; private set
    @Volatile var ended=false; private set
    fun cancel() { epoch.incrementAndGet(); worker.queue.clear(); ended=false }
    fun start(uri: Uri, startMs: Long, receive: (FloatArray,Int,Long,()->Boolean)->Boolean,
              complete: ()->Unit, failure: (String)->Unit, state: (String)->Unit = {}) {
        val token=epoch.incrementAndGet(); worker.queue.clear(); paused=false; ended=false; decodedThroughMs=startMs
        worker.execute {
            if(token!=epoch.get()) return@execute
            val extractor=MediaExtractor(); var codec: MediaCodec?=null
            val current={ token==epoch.get() && !Thread.currentThread().isInterrupted }
            try {
                state("LOCAL_AUDIO_OPEN epoch=$token at=$startMs")
                extractor.setDataSource(context,uri,null)
                val track=(0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/")==true
                } ?: error("O arquivo não contém uma faixa de áudio compatível")
                extractor.selectTrack(track)
                val input=extractor.getTrackFormat(track)
                extractor.seekTo(startMs*1000,MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val decoder=MediaCodec.createDecoderByType(input.getString(MediaFormat.KEY_MIME)!!)
                codec=decoder; decoder.configure(input,null,null,0); decoder.start()
                var format=input; var inputDone=false; var outputDone=false; var firstPcm=true
                val info=MediaCodec.BufferInfo(); val resampler=StreamingPcm16k()
                while(current() && !outputDone) {
                    if(paused) { Thread.sleep(40); continue }
                    if(!inputDone) {
                        val index=decoder.dequeueInputBuffer(10_000)
                        if(index>=0) {
                            val buffer=decoder.getInputBuffer(index)!!; buffer.clear()
                            val n=extractor.readSampleData(buffer,0)
                            if(n<0) { decoder.queueInputBuffer(index,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone=true }
                            else { decoder.queueInputBuffer(index,0,n,extractor.sampleTime,0); extractor.advance() }
                        }
                    }
                    val out=decoder.dequeueOutputBuffer(info,10_000)
                    if(out==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { format=decoder.outputFormat; resampler.reset() }
                    else if(out>=0) {
                        try {
                            if(info.size>0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG==0) {
                                val rate=format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                                val channels=format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                                val encoding=if(format.containsKey(MediaFormat.KEY_PCM_ENCODING)) format.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                                require(encoding==AudioFormat.ENCODING_PCM_16BIT || encoding==AudioFormat.ENCODING_PCM_FLOAT) { "Formato PCM não suportado: $encoding" }
                                val bytes=if(encoding==AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                                val buffer=decoder.getOutputBuffer(out)!!.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                                buffer.position(info.offset); buffer.limit(info.offset+info.size)
                                val frames=info.size/(channels*bytes)
                                // Skip decoder preroll exactly in sample units, preserving the original PTS.
                                val skip=((startMs*1000-info.presentationTimeUs).coerceAtLeast(0)*rate/1_000_000).coerceAtMost(frames.toLong()).toInt()
                                buffer.position(info.offset+skip*channels*bytes)
                                var offset=skip
                                while(offset<frames && current()) {
                                    val count=minOf(frames-offset,rate/4)
                                    val mono=FloatArray(count) {
                                        var sum=0f
                                        repeat(channels) { sum+=if(bytes==4) buffer.float.coerceIn(-1f,1f) else buffer.short/32768f }
                                        sum/channels
                                    }
                                    val at=info.presentationTimeUs/1000+offset*1000L/rate
                                    val pcm=resampler.convert(mono,rate)
                                    if(pcm.isNotEmpty()) {
                                        if(firstPcm) { firstPcm=false; state("LOCAL_AUDIO_FIRST_PCM epoch=$token at=$at rate=$rate") }
                                        if(!receive(pcm,16000,at,current)) return@execute
                                    }
                                    offset+=count; decodedThroughMs=info.presentationTimeUs/1000+offset*1000L/rate
                                }
                            }
                            outputDone=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0
                        } finally { decoder.releaseOutputBuffer(out,false) }
                    }
                }
                if(current() && outputDone) { ended=true; state("LOCAL_AUDIO_END epoch=$token through=$decodedThroughMs"); complete() }
            } catch(e: Exception) { if(current()) failure(e.message ?: "Falha na leitura do áudio") }
            finally { runCatching { codec?.stop() }; runCatching { codec?.release() }; extractor.release() }
        }
    }
    override fun close() { cancel(); worker.shutdownNow() }
}
