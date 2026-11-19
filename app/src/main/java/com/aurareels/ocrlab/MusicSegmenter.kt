package com.aurareels.ocrlab

/** Continuous 16 kHz windows for singing. VAD must not discard sustained or quiet vocals.
 * Cuts prefer a quiet 100 ms region after 6 s, with a hard 12 s bound. Every sample is
 * submitted exactly once, including the final tail. Timing remains in media coordinates. */
class MusicSegmenter(private val emit: (Long, FloatArray) -> Unit) {
    private val rate = 16000
    private val buffer = FloatArray(rate * 12)
    private var size = 0
    private var origin = 0L
    private var consumed = 0L
    fun reset() { size = 0; consumed = 0; origin = 0 }
    fun feed(startMs: Long, samples: FloatArray) {
        if (samples.isEmpty()) return
        if (size == 0 && consumed == 0L) origin = startMs
        val expected = origin + (consumed + size) * 1000 / rate
        if (kotlin.math.abs(startMs - expected) > 400) {
            flush(); reset(); origin = startMs
        }
        var at = 0
        while (at < samples.size) {
            val n = minOf(samples.size - at, buffer.size - size)
            samples.copyInto(buffer, size, at, at + n); size += n; at += n
            if (size == buffer.size) {
                // Lowest-energy boundary in the last six seconds. No silence is thrown away.
                var cut = size
                var quietest = Float.MAX_VALUE
                for (end in rate * 6..size step rate / 10) {
                    var energy = 0.0
                    for (i in end - rate / 10 until end) energy += buffer[i].toDouble() * buffer[i]
                    val mean = (energy / (rate / 10)).toFloat()
                    if (mean <= quietest) { quietest = mean; cut = end }
                }
                deliver(cut)
            }
        }
    }
    private fun deliver(n: Int) {
        if (n <= 0) return
        emit(origin + consumed * 1000 / rate, buffer.copyOf(n))
        buffer.copyInto(buffer, 0, n, size); size -= n; consumed += n
    }
    fun flush() { deliver(size) }
    val pendingStartMs: Long? get() = if (size > 0) origin + consumed * 1000 / rate else null
}
