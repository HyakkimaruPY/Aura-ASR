package com.aurareels.ocrlab

data class AudioInterval(val startMs: Long,val endMs: Long) { init { require(startMs>=0 && endMs>startMs) } }
object AudioCoverage {
    fun merge(input: List<AudioInterval>): List<AudioInterval> {
        val result=ArrayList<AudioInterval>()
        input.sortedBy { it.startMs }.forEach { r ->
            val last=result.lastOrNull()
            if(last!=null && r.startMs<=last.endMs) result[result.lastIndex]=AudioInterval(last.startMs,maxOf(last.endMs,r.endMs)) else result.add(r)
        }
        return result
    }
    fun firstGap(at: Long,coverage: List<AudioInterval>): Long {
        var next=at.coerceAtLeast(0)
        merge(coverage).forEach { if(next>=it.startMs && next<it.endMs) next=it.endMs }
        return next
    }
    fun uncovered(start: Long,end: Long,coverage: List<AudioInterval>): List<AudioInterval> {
        // Live PCM clocks can start slightly before 0; AudioInterval only accepts media time >= 0.
        val start=start.coerceAtLeast(0)
        if(end<=start) return emptyList()
        var cursor=start
        val gaps=ArrayList<AudioInterval>()
        merge(coverage).forEach { r ->
            if(r.endMs<=cursor || r.startMs>=end) return@forEach
            if(r.startMs>cursor) gaps.add(AudioInterval(cursor,minOf(end,r.startMs)))
            cursor=maxOf(cursor,r.endMs)
        }
        if(cursor<end) gaps.add(AudioInterval(cursor,end))
        return gaps
    }
    fun contains(start: Long,end: Long,coverage: List<AudioInterval>)=coverage.any { start>=it.startMs && end<=it.endMs }
}
