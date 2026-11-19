package com.aurareels.ocrlab

/** Subtitles can prepare in the background; they must never hold the first frame indefinitely. */
object PlaybackStartup {
    const val MAX_WAIT_MS=25_000L
    fun resumePosition(position: Long,duration: Long): Long = if(duration>0 && position>=maxOf(0,duration-1000)) 0L else position.coerceAtLeast(0)
    fun expired(waitMs: Long)=waitMs>=MAX_WAIT_MS
    fun liveAnchor(first: Long, end: Long, through: Long, delay: Long): Long? {
        if(end-first<1000) return null
        val last=end-500
        val desired=if(through>=first && through<Long.MAX_VALUE/8) through-delay.coerceAtLeast(1000) else first
        return desired.coerceIn(first,last)
    }
    fun liveTarget(first: Long, end: Long, through: Long, delay: Long,
                   translated: List<LiveSubtitleClock.Span>, waitMs: Long, pending: Long?=null): Long? {
        val anchor=liveAnchor(first,end,through,delay) ?: return null
        // Keep the same seek while ExoPlayer acknowledges it, even when the ASR horizon changes.
        pending?.takeIf { it>=first && it<=end-500 }?.let { return it }
        val reserve=minOf(delay.coerceAtLeast(1000),(through-first-1000).coerceAtLeast(500))
        return LiveSubtitleClock.startAt(first,end,through,delay,translated,reserve)
            ?: anchor.takeIf { expired(waitMs) }
    }
}
