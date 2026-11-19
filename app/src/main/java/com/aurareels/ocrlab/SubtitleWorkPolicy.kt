package com.aurareels.ocrlab

/** Do not spend the video translation queue on expired captions or far future audio.
 * Music keeps its complete transcript, and explicit SRT export may translate any cue. */
object SubtitleWorkPolicy {
    /** While the picture is not advancing (startup, auto-pause) a caption near the position is what blocks the first frame. */
    const val STARTUP_API_GRACE_MS = 4_000L
    const val STARTUP_NEAR_MS = 20_000L
    /** Leave time for the fast fallback before this caption is due; never wait 35s for a current cue.
     * Not advancing: API keeps its long wait only for distant captions; near ones get a short head start and the
     * Google result stays provisional until API upgrades it. */
    fun primaryGrace(start: Long, position: Long, advancing: Boolean=true): Long {
        val deadline=(start-position-1500).coerceIn(0,35_500)
        return if(advancing) deadline
        else if(start-position<=STARTUP_NEAR_MS) minOf(deadline,STARTUP_API_GRACE_MS) else 35_500
    }
    fun eligible(start: Long, end: Long, position: Long, music: Boolean, export: Boolean=false): Boolean =
        music || export || (end+2500>=position && start<=position+60_000)
}
