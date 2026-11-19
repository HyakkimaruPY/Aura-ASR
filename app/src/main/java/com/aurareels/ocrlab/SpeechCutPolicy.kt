package com.aurareels.ocrlab

/** Chooses where a long, uninterrupted utterance is split for recognition.
 * A fixed cut every N seconds slices sentences in the middle, which hurts both the transcript and its
 * translation. Instead, once the utterance is long enough ([softMs]) the first short dip in level
 * ([pauseMs]) is used; [hardMs] stays as the backstop.
 * The dip is measured against the *mean* speech level (not its peaks) using a ~100 ms smoothed
 * frame level, so it also works over background music / compressed vlog audio. */
class SpeechCutPolicy(
    private val frameMs: Double,
    val softMs: Long = 3_000,
    val hardMs: Long = 8_000,
    private val pauseMs: Long = 160,
    private val quietRatio: Float = 0.5f
) {
    private var level = -1f
    private val recent = FloatArray(3)
    private var count = 0
    private var quietFrames = 0
    fun reset() { level = -1f; count = 0; quietFrames = 0; recent.fill(0f) }
    /** Call once per VAD frame with the frame RMS. */
    fun observe(rms: Float) {
        recent[count % recent.size] = rms; count++
        val smoothed = recent.take(minOf(count, recent.size)).average().toFloat()
        level = if (level < 0f) rms else level + LEVEL_ALPHA * (rms - level)
        quietFrames = if (level > MIN_LEVEL && smoothed < level * quietRatio) quietFrames + 1 else 0
    }
    /** @return null to keep going, "PAUSE" or "HARD" when the open utterance should be closed now. */
    fun decide(spokenMs: Long): String? = when {
        spokenMs >= hardMs -> "HARD"
        spokenMs >= softMs && quietFrames * frameMs >= pauseMs -> "PAUSE"
        else -> null
    }
    private companion object { const val MIN_LEVEL = 0.002f; const val LEVEL_ALPHA = 0.02f }
}
