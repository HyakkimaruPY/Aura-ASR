package com.aurareels.ocrlab

/** Use native token timestamps when their character stream matches the recognized text exactly.
 * Raw text is sliced, never rebuilt from BPE pieces. Unsupported/invalid token data falls back. */
object TokenCueTiming {
    private data class Token(val charAt: Int, val time: Long, val duration: Long)
    fun split(text: String, start: Long, end: Long, tokens: Array<String>, times: FloatArray,
              durations: FloatArray, joinWithoutSpaces: Boolean, music: Boolean): List<Triple<Long,Long,String>>? {
        if(tokens.isEmpty() || tokens.size!=times.size || end<=start) return null
        fun canonical(value: String)=value.filter { it.isLetterOrDigit() }.map { it.lowercaseChar() }.joinToString("")
        val chars=text.indices.filter { text[it].isLetterOrDigit() }
        val expected=canonical(text)
        val normalized=tokens.map { token ->
            if(token.startsWith("<|") || token in setOf("<blank>","<s>","</s>")) "" else canonical(token.replace("▁"," ").replace("Ġ"," "))
        }
        if(normalized.joinToString("")!=expected || expected.isEmpty()) return null
        val mapped=ArrayList<Token>(); var used=0; var previous=-1L
        for(i in tokens.indices) {
            val count=normalized[i].length
            if(count==0) continue
            if(!times[i].isFinite() || times[i]<0) return null
            val relative=(times[i]*1000).toLong()
            if(relative<previous || relative>end-start+300) return null
            val duration=durations.getOrNull(i)?.takeIf { it.isFinite() && it>0 }?.let { (it*1000).toLong() } ?: 0L
            mapped.add(Token(chars[used],(start+relative).coerceAtMost(end-1),duration))
            used+=count; previous=relative
        }
        if(mapped.isEmpty()) return null
        val groups=ArrayList<Int>(); groups.add(0)
        var group=0
        for(i in 1 until mapped.size) {
            val current=mapped[i]; val first=mapped[group]
            val prior=text[current.charAt-1]
            val boundary=joinWithoutSpaces || prior.isWhitespace() || prior in ".,!?;。！？、，；…"
            val sentence=text.substring(mapped[i-1].charAt,current.charAt).any { it in ".!?;。！？؟…" }
            val span=current.time-first.time
            val limit=if(music) 3500 else 4000
            if(boundary && span>=300 && (sentence || span>=limit || current.charAt-first.charAt>=if(joinWithoutSpaces) 24 else 72)) {
                groups.add(i); group=i
            }
        }
        val output=groups.mapIndexed { index, first ->
            val next=groups.getOrNull(index+1)
            val from=mapped[first].time
            val last=mapped[(next ?: mapped.size)-1]
            val boundary=if(next!=null) mapped[next].time else end
            val until=if(last.duration>0) minOf(boundary,last.time+last.duration) else boundary
            val piece=text.substring(if(index==0) 0 else mapped[first].charAt,next?.let { mapped[it].charAt } ?: text.length).trim()
            Triple(from,until,piece)
        }
        return output.takeIf { it.all { row -> row.third.isNotBlank() && row.second>row.first } }
    }
}
