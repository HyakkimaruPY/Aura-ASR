package com.aurareels.ocrlab

/** Sherpa's VAD decides speech once per acceptWaveform call. Never aggregate 8 windows into one
 * call: a speech OR over a large PCM block can hide the model's max-duration cutoff. */
class VadFrameBuffer(private val frameSize: Int=512) {
    private val pending=FloatArray(frameSize)
    var size=0; private set
    fun reset() { size=0 }
    fun feed(samples: FloatArray, emit: (FloatArray)->Unit) {
        var at=0
        while(at<samples.size) {
            val n=minOf(frameSize-size,samples.size-at)
            samples.copyInto(pending,size,at,at+n); at+=n; size+=n
            if(size==frameSize) { emit(pending.copyOf()); size=0 }
        }
    }
    fun flush(emit: (FloatArray)->Unit) {
        if(size==0) return
        pending.fill(0f,size); emit(pending.copyOf()); size=0
    }
}
