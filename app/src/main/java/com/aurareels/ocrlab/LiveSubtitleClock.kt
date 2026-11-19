package com.aurareels.ocrlab

/** One coordinate system for HLS window positions, decoded PCM PTS and subtitles. */
object LiveSubtitleClock {
    fun periodOffset(windowStartEpochMs: Long, positionInFirstPeriodMs: Long, originEpochMs: Long) =
        windowStartEpochMs-positionInFirstPeriodMs-originEpochMs
    fun pcmTime(presentationTimeUs: Long, outputStreamOffsetUs: Long, periodOffsetMs: Long) =
        (presentationTimeUs-outputStreamOffsetUs)/1000+periodOffsetMs
    /** Without EXT-X-PROGRAM-DATE-TIME, the independent players' sliding-window
     * positions do not share an origin. Measure both against their live-default
     * positions and the same monotonic clock. The 60 s bias keeps pre-roll >= 0.
     * This is best-effort for unequal HLS renditions, not frame-exact sync. */
    const val FALLBACK_BASE_MS = 60_000L
    /** Stream (period) time is continuous across sliding-window refreshes, unlike
     * `position - defaultPosition`, which steps by a segment whenever the window grows/slides
     * and made the visible clock jump back by ~6 s. Both players and the PCM tap share it. */
    fun stableLiveTime(periodTimeMs: Long, originPeriodMs: Long): Long = FALLBACK_BASE_MS + periodTimeMs - originPeriodMs

    fun relativeLiveTime(nowElapsedMs: Long, originElapsedMs: Long, positionMs: Long, defaultPositionMs: Long): Long =
        FALLBACK_BASE_MS + (nowElapsedMs - originElapsedMs) + positionMs - defaultPositionMs

    fun relativeWindowStart(nowElapsedMs: Long, originElapsedMs: Long, defaultPositionMs: Long): Long =
        relativeLiveTime(nowElapsedMs, originElapsedMs, 0L, defaultPositionMs)

    fun relativeSeekPosition(nowElapsedMs: Long, originElapsedMs: Long, defaultPositionMs: Long, clockMs: Long): Long =
        clockMs - relativeWindowStart(nowElapsedMs, originElapsedMs, defaultPositionMs)

    fun shouldRecoverBehindLive(errorCode: Int, behindLiveCode: Int, live: Boolean): Boolean =
        live && errorCode == behindLiveCode

    /** Margin kept before the end of the live window so the scout never waits for a segment that does not exist yet. */
    const val SCOUT_EDGE_MARGIN_MS = 2_000L
    /** Lead the scout is given over the default live position. Must stay below the playback cap
     * (max(26 s, delay+15 s)) or the scout is paused and the ASR starves. */
    const val SCOUT_LEAD_MS = 22_000L
    /** The scout must lead the visible player; both start at the default live position, so the lead
     * would be ~0. Returns a bounded window position (never the far edge of a long window), or null
     * when the window is too short to gain a useful lead. */
    fun scoutEdgeTarget(windowDurationMs: Long, defaultPositionMs: Long, leadMs: Long = SCOUT_LEAD_MS): Long? {
        if (windowDurationMs <= 0) return null
        val base = if (defaultPositionMs < 0) 0L else defaultPositionMs
        val target = minOf(windowDurationMs - SCOUT_EDGE_MARGIN_MS, base + leadMs)
        return target.takeIf { it - base >= 3_000L }
    }

    data class Span(val start: Long, val end: Long)
    /** Never pretend a clamped, expired seek reached its requested caption. */
    fun startAt(windowStart: Long, windowEnd: Long, recognizedThrough: Long, delayMs: Long, translated: List<Span>, minReserveMs: Long=500): Long? {
        val last=minOf(windowEnd-500,recognizedThrough-maxOf(500,minReserveMs))
        if(last<windowStart) return null
        val preferred=maxOf(windowStart,recognizedThrough-delayMs.coerceAtLeast(1000))
        return translated.mapNotNull { cue ->
            val beginning=maxOf(windowStart,cue.start)
            val point=maxOf(preferred,beginning)
            when {
                point<=last && cue.end-point>=300 -> point
                beginning<=last && cue.end-beginning>=300 -> beginning
                else -> null
            }
        }.minByOrNull { kotlin.math.abs(it-preferred) }
    }
}
