package com.aurareels.ocrlab

/**
 * Detects black bars (letterbox) above and below the picture from a small frame sample, and decides
 * when it is safe to zoom them away. Pure Kotlin (no Android types) so it is unit-testable.
 *
 * Native ultra-wide videos have no bars inside the decoded frame, so nothing is detected and nothing
 * is zoomed. Only 16:9 frames that really contain bars trigger the zoom, and the Tracker follows the
 * video: bars may exist in one stretch and disappear later (zoom turns off quickly, so content is
 * never cropped for long), or appear only in the middle of a normal video.
 */
object LetterboxDetector {
    const val W = 160
    const val H = 90
    private const val BLACK = 28          // brightest channel still considered "black"
    private const val MIN_BAR = 0.03f     // smaller bars are not worth a zoom
    private const val MAX_BARS = 0.80f    // above this the frame is just dark, not letterboxed

    /** Total bar fraction (top + bottom) of the frame height. */
    data class Bars(val top: Float, val bottom: Float) { val total: Float get() = top + bottom }

    private fun rowIsBlack(px: IntArray, w: Int, y: Int): Boolean {
        var lit = 0
        val base = y * w
        for (x in 0 until w) {
            val c = px[base + x]
            val m = maxOf((c shr 16) and 0xff, (c shr 8) and 0xff, c and 0xff)
            if (m > BLACK && ++lit > w / 100) return false   // tolerate 1% noise/logo pixels
        }
        return true
    }

    /**
     * Returns Bars(0,0) when the frame has no usable bars, Bars(top,bottom) when it is letterboxed,
     * and null when the sample is ambiguous (black frame, dark scene, one-sided black area).
     */
    fun measure(px: IntArray, w: Int = W, h: Int = H): Bars? {
        if (w <= 0 || h <= 0 || px.size < w * h) return null
        var top = 0
        while (top < h && rowIsBlack(px, w, top)) top++
        if (top >= h) return null                                  // fully black frame
        var bottom = 0
        while (bottom < h - top && rowIsBlack(px, w, h - 1 - bottom)) bottom++
        val t = top.toFloat() / h; val b = bottom.toFloat() / h
        if (t + b > MAX_BARS) return null
        // The picture between the bars must really have light in it, otherwise it is a dark scene.
        var lit = 0; var total = 0
        for (y in top until h - bottom) for (x in 0 until w) {
            val c = px[y * w + x]
            if (maxOf((c shr 16) and 0xff, (c shr 8) and 0xff, c and 0xff) > 40) lit++
            total++
        }
        if (total == 0 || lit < total / 50) return null
        if (t < MIN_BAR && b < MIN_BAR) return Bars(0f, 0f)
        // True letterbox is symmetric. A black sky or a one-sided area is not: treat as "no bars".
        if (t < MIN_BAR || b < MIN_BAR || minOf(t, b) < maxOf(t, b) * 0.6f) return Bars(0f, 0f)
        return Bars(t, b)
    }

    /**
     * Turns a stream of samples into a stable decision. Turning ON needs ON_STREAK consistent samples;
     * turning OFF needs only OFF_STREAK, because keeping a zoom over a picture that reached the frame
     * edges would crop real content. Ambiguous samples (null) change nothing.
     */
    class Tracker(private val onStreak: Int = 4, private val offStreak: Int = 2) {
        var applied = 0f; private set
        private var candidate = 0f
        private var onCount = 0
        private var offCount = 0

        fun reset() { applied = 0f; candidate = 0f; onCount = 0; offCount = 0 }

        fun push(sample: Bars?): Float {
            if (sample == null) return applied
            if (sample.total < MIN_BAR) {
                onCount = 0; candidate = 0f
                if (++offCount >= offStreak) applied = 0f
            } else {
                offCount = 0
                if (kotlin.math.abs(sample.total - candidate) <= 0.02f && candidate > 0f) onCount++
                else { candidate = sample.total; onCount = 1 }
                if (onCount >= onStreak) applied = candidate
            }
            return applied
        }
    }
}
