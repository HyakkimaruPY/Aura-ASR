package com.aurareels.ocrlab

object TimelineBenchmark {
    @JvmStatic fun main(args: Array<String>) {
        val cues=(0 until 5000).map { SpeechCue(it*3000L,it*3000L+1800,"line $it").apply { output="linha" } }
        val timeline=SubtitleTimeline(); timeline.restore(cues)
        val positions=LongArray(50000) { (it*7919L)%(5000*3000L) }
        var checksum=0L
        fun linear(at: Long): SpeechCue? { val i=cues.indexOfLast { at>=it.startMs }; return if(i<0) null else cues[i].takeIf { at<SubtitleTiming.readableEnd(it,cues.getOrNull(i+1)?.startMs) } }
        repeat(3) { positions.forEach { checksum+=timeline.at(it)?.startMs ?: 0 }; positions.forEach { checksum+=linear(it)?.startMs ?: 0 } }
        val binary=LongArray(5); val reference=LongArray(5)
        repeat(5) { run ->
            var at=System.nanoTime(); positions.forEach { checksum+=timeline.at(it)?.startMs ?: 0 }; binary[run]=System.nanoTime()-at
            at=System.nanoTime(); positions.forEach { checksum+=linear(it)?.startMs ?: 0 }; reference[run]=System.nanoTime()-at
        }
        val fast=binary.sorted()[2]/1e6; val slow=reference.sorted()[2]/1e6
        println("TIMELINE_BENCH cues=5000 queries=50000 binary_ms=%.3f linear_ms=%.3f ratio=%.2f checksum=%d".format(java.util.Locale.US,fast,slow,slow/fast,checksum))
    }
}
